package com.forge.pixpin.estadoia

import android.content.Context
import com.forge.pixpin.asistente.ClavesDeLaNube
import com.forge.pixpin.ia.Nube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * **El estado de las IA en la app** (1-nov-2026, `apps/pixpin/src/estado_ia.rs` del PC y
 * `docs/ui-funciones-y-chats.md`): lo que se sabe de Claude, DeepSeek y Jev para los puntitos de
 * junto al botón de sincronizar de la lista de chats ([com.forge.pixpin.ui.PuntitosDeLasIa]).
 *
 * - **De lo que se usa de verdad**: cada petición del asistente, de «Probar» en Ajustes y de Jev
 *   avisa de cómo acabó ([Nube.alContestar] y [com.forge.pixpin.jev.Cliente.alContestar]). Lo que
 *   sale del cache de Jev no cuenta.
 * - **Una comprobación que no gasta**, en su hilo («estado-ia»): la lista de modelos de cada API
 *   (Anthropic `GET /v1/models?limit=1`, DeepSeek `GET /models`, TypeSafe `GET /v1/models`), a
 *   los 10 s de arrancar, al guardar o borrar una clave, con «Comprobar ahora» y como mucho cada
 *   30 min; sin red, antes y con espera creciente. Las reglas, con sus pruebas, en [Tablero].
 *   Nunca bloquea la pantalla.
 *
 * Las claves son de cada aparato (no viajan): cada teléfono enseña las suyas. La clave solo se
 * descifra en el hilo de la comprobación, de camino a la cabecera HTTP: nunca a un registro.
 */
object EstadoDeLasIa {
    /** Lo que se ve: una copia del tablero y su número (sube con cada cambio). */
    class Foto(val tablero: Tablero, val version: Long)

    private val cerrojo = ReentrantLock()
    private val despertador = cerrojo.newCondition()
    private val tablero = Tablero()
    private var version = 0L
    private val _foto = MutableStateFlow(Foto(tablero.copia(), 0))

    /** **Lo que se sabe ahora**, para pintarlo. */
    val foto: StateFlow<Foto> get() = _foto

    @Volatile private var app: Context? = null

    /** Lo más que duerme el hilo sin mirar si hay claves nuevas en el disco. */
    private const val VISTAZO_MS = 5 * 60_000L

    private val origen = System.nanoTime()

    /** Los ms del reloj del estado (solo avanza: no le afecta cambiar la hora del teléfono). */
    fun ahoraMs(): Long = (System.nanoTime() - origen) / 1_000_000

    /** Algo de lo que se ve cambió: la lista repinta. Con el cerrojo puesto. */
    private fun publicar() {
        version++
        _foto.value = Foto(tablero.copia(), version)
    }

    private fun <R> con(f: (Tablero) -> R): R = cerrojo.withLock { f(tablero) }

    private fun deProveedor(p: Nube.Proveedor) = when (p) {
        Nube.Proveedor.CLAUDE -> Servicio.CLAUDE
        Nube.Proveedor.DEEPSEEK -> Servicio.DEEPSEEK
    }

    private fun aProveedor(s: Servicio) = when (s) {
        Servicio.CLAUDE -> Nube.Proveedor.CLAUDE
        Servicio.DEEPSEEK -> Nube.Proveedor.DEEPSEEK
        Servicio.JEV -> null
    }

    private fun apuntar(s: Servicio, d: Desenlace) {
        val ahora = ahoraMs()
        cerrojo.withLock {
            val antes = tablero.ficha(s)
            tablero.apuntar(s, d, ahora)
            // También si solo cambió «hace cuánto»: el menú lo enseña.
            if (tablero.ficha(s) != antes) publicar()
        }
    }

    /** Si hay clave guardada (sin descifrarla). */
    private fun hayClave(c: Context, s: Servicio): Boolean = runCatching {
        when (s) {
            Servicio.JEV -> com.forge.pixpin.jev.Jev.hayClave(c)
            else -> ClavesDeLaNube.hay(c, aProveedor(s)!!)
        }
    }.getOrDefault(false)

    /**
     * **Arranca** el estado: escucha lo que pasa en cada petición y lanza el hilo de las
     * comprobaciones. Una vez, al arrancar la app ([com.forge.pixpin.PixPinApp]).
     */
    @Synchronized
    fun arrancar(c: Context) {
        if (app != null) return
        val contexto = c.applicationContext
        app = contexto
        val arranque = ahoraMs()
        Nube.alContestar = { p, codigo -> apuntar(deProveedor(p), Desenlace.deCodigo(codigo)) }
        com.forge.pixpin.jev.Cliente.alContestar = { codigo -> apuntar(Servicio.JEV, Desenlace.deCodigo(codigo)) }
        runCatching {
            Thread({ bucle(contexto, arranque) }, "estado-ia").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }.onFailure { android.util.Log.w("PixPin", "estado de las IA: no se pudo lanzar su hilo", it) }
    }

    /**
     * **El usuario guardó o borró una clave** (Ajustes): se olvida lo de la anterior y, si hay una
     * nueva, se comprueba ya. Lee el disco: fuera del hilo principal.
     */
    fun claveCambiada(c: Context, s: Servicio) {
        val hay = hayClave(c, s)
        cerrojo.withLock {
            tablero.claveCambiada(s, hay)
            publicar()
            despertador.signalAll()
        }
    }

    fun claveCambiada(c: Context, p: Nube.Proveedor) = claveCambiada(c, deProveedor(p))

    /** **«Comprobar ahora»** (el menú de los puntitos). */
    fun comprobarAhora() {
        cerrojo.withLock {
            tablero.pedirAhora()
            publicar()
            despertador.signalAll()
        }
    }

    /** El hilo: mira las claves, comprueba lo que toca y duerme hasta la próxima (o hasta que lo despierten). */
    private fun bucle(c: Context, arranque: Long) {
        while (true) {
            try {
                vuelta(c, arranque)
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                android.util.Log.w("PixPin", "estado de las IA: una vuelta falló", e)
                runCatching { Thread.sleep(60_000) }
            }
        }
    }

    private fun vuelta(c: Context, arranque: Long) {
        // Mirar el disco, fuera del cerrojo.
        val claves = Servicio.entries.map { it to hayClave(c, it) }
        val ahora = ahoraMs()
        val toca = cerrojo.withLock {
            var cambiado = false
            for ((s, hay) in claves) cambiado = tablero.ponerClave(s, hay) || cambiado
            val toca = tablero.porComprobar(ahora, arranque)
            for (s in toca) tablero.empezar(s, ahora)
            if (cambiado || toca.isNotEmpty()) publicar()
            toca
        }
        for (s in toca) {
            runCatching { comprobar(c, s) }
            con { it.acabar(s) }
            cerrojo.withLock { publicar() }
        }
        cerrojo.withLock {
            val espera = (tablero.proxima(arranque)?.let { it - ahoraMs() } ?: VISTAZO_MS).coerceAtMost(VISTAZO_MS)
            if (espera > 0) despertador.await(espera, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Una comprobación que no gasta tokens. Lo que conteste llega por [Nube.alContestar] o
     * [com.forge.pixpin.jev.Cliente.alContestar], como cualquier petición.
     */
    private fun comprobar(c: Context, s: Servicio) {
        val p = aProveedor(s)
        if (p != null) {
            val clave = ClavesDeLaNube.leer(c, p) ?: return
            Nube.comprobar(p, clave)
        } else {
            val clave = com.forge.pixpin.jev.Jev.clave(c) ?: return
            runCatching { com.forge.pixpin.jev.Cliente(clave).modelos() }
        }
    }
}
