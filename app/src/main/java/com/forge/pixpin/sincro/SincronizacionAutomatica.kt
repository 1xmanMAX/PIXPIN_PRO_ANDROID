package com.forge.pixpin.sincro

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress

/**
 * **Una vuelta sin nadie delante**: la de la sincronización automática. Es la de «Sincronizar con
 * todos» ([SincronizarActivity]) —lo elegido la última vez con ese aparato, o todo la primera—,
 * con una diferencia a propósito: **no borra proyectos**. Un proyecto con lápida en cualquiera de
 * los dos lados se deja para la sincronización a mano, que enseña la lista y pregunta antes de
 * borrar (v0.79, «lo mío manda»): que algo desaparezca sin que nadie lo vea pasar es justo lo
 * que costó lienzos el 15-sep. Lo demás —mensajes, lienzos, archivos, la galería— se junta igual
 * que siempre ([Fusion]).
 */
class VueltaCallada(private val disco: Disco, private val miPuerto: Int = 0, private val ahora: () -> Long = System::currentTimeMillis) {
    class Resultado(val nombre: String, val hecho: Sesion.Hecho, val bytes: Long) {
        /** Si llegó algo del otro lado (lo único que merece decirse). */
        val trajoAlgo: Boolean get() = hecho.traidos > 0 || hecho.fusionados > 0 || hecho.borrados > 0 || hecho.capturas > 0
    }

    fun con(entrada: InputStream, salida: OutputStream): Resultado {
        val sesion = Sesion.conectar(entrada, salida, disco, ahora = ahora, miPuerto = miPuerto)
        try {
            val otro = sesion.otro
            val mios = disco.chats().associateBy { it.id }
            val suyos = sesion.catalogo().associateBy { it.id }
            // Lo que tenga lápida en cualquiera de los dos, fuera: eso lo decide quien mire.
            val conLapida = disco.lapidas().map { it.chat }.toSet() + sesion.lapidas().map { it.chat }
            val candidatos = (mios.keys + suyos.keys).filter { it !in conLapida }
            val elegidos = disco.elegidos(otro.id) ?: candidatos.toSet()
            val hecho = Sesion.Hecho()
            for (chat in candidatos.filter { it in elegidos }) {
                val prep = sesion.preparar(chat)
                sesion.aplicar(prep, hecho)
                sesion.aplicarArchivos(sesion.prepararArchivos(prep), hecho)
                sesion.cerrar(prep)
            }
            sesion.galeria(hecho, ahora())
            val bytes = sesion.enviados + sesion.recibidos
            sesion.adios()
            return Resultado(otro.nombre, hecho, bytes)
        } finally {
            sesion.soltar()
        }
    }
}

/**
 * **Cuándo toca sincronizar solo** (`Reloj::toca` de `sincronizar/automatica.rs` del PC), puro
 * para poder probarlo sin red. Las mismas tres razones que en el PC:
 *
 * - **Apareció** uno del grupo que antes no contestaba (entró en la Wi-Fi o abrió PixPin).
 * - **Cambió** algo aquí y lleva [QUIETO] sin cambiar más (escribir o dibujar son muchos cambios seguidos).
 * - **Periodo**: cada [PERIODO] mientras haya alguno, que es como llega lo hecho en el otro.
 */
class RelojDeSincro {
    enum class Motivo { APARECIO, CAMBIO, PERIODO }

    private var presentes: Set<String> = emptySet()
    private var firma: Firma? = null
    private var cambioEn = 0L
    private var pendiente = false
    private var ultima = 0L

    /** Lo que se mira de los chats: cuántos archivos, cuánto pesan y la suma de sus fechas. */
    data class Firma(val ficheros: Long, val bytes: Long, val tocados: Long)

    /**
     * Con quién hay que sincronizar ahora y por qué, o null. [presentesAhora]: los del grupo que
     * contestan; [firmaAhora]: la de los chats, o null si esta vez no se miró.
     */
    fun toca(ahora: Long, presentesAhora: Set<String>, firmaAhora: Firma?): Pair<Motivo, Set<String>>? {
        val nuevos = presentesAhora - presentes
        presentes = presentesAhora
        if (firmaAhora != null) {
            val antes = firma
            firma = firmaAhora
            if (antes != null && antes != firmaAhora) { pendiente = true; cambioEn = ahora }
        }
        if (presentesAhora.isEmpty()) return null
        val r = when {
            nuevos.isNotEmpty() -> Motivo.APARECIO to nuevos
            pendiente && ahora - cambioEn >= QUIETO -> Motivo.CAMBIO to presentesAhora
            ahora - ultima >= PERIODO -> Motivo.PERIODO to presentesAhora
            else -> return null
        }
        if (r.first != Motivo.APARECIO) pendiente = false
        ultima = ahora
        return r
    }

    /** Lo que se escribe al sincronizar también cambia la firma: no es un cambio nuestro. */
    fun despuesDeSincronizar(firmaAhora: Firma?) {
        if (firmaAhora != null) firma = firmaAhora
        pendiente = false
    }

    companion object {
        const val QUIETO = 30_000L
        const val PERIODO = 10 * 60_000L
        const val FALLOS_PARA_AVISAR = 3
    }
}

/**
 * **Sincronizar solo, con PixPin abierto** (9-oct-2026, lo que el PC hace desde el 8-oct: «que
 * se sincronice todo con solo tener la app abierta»). Mientras haya alguna pantalla de PixPin a la
 * vista ([Presencia]), cada pocos segundos se pregunta «¿estás?» a la última dirección de cada
 * aparato del grupo y se mira la firma de los chats; [RelojDeSincro] decide. Con quien no contesta
 * no se intenta: cada intento con uno apagado cuesta agotar la conexión.
 *
 * **Sin ruido**: nada cuando sale bien y no trae nada; un aviso corto si llega algo del otro lado,
 * o si con un aparato falla [RelojDeSincro.FALLOS_PARA_AVISAR] veces seguidas (una vez). Si ya hay
 * una vuelta en marcha —a mano, o porque llamó el otro— espera ([Protocolo.ocupar]).
 * Se apaga en Sincronizar («Sincronizar sola»).
 */
object SincronizacionAutomatica {
    private const val LATIDO = 5_000L
    private const val LATIDOS_POR_SONDEO = 3
    private const val PREFS = "sincro_automatica"

    private lateinit var app: Application
    private val principal by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var hilo: Thread? = null
    @Volatile private var aLaVista = false
    private val fallos = HashMap<String, Int>()

    fun encendida(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("encendida", true)

    fun poner(c: Context, si: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("encendida", si).apply()
    }

    fun instalar(aplicacion: Application) { app = aplicacion }

    /** PixPin a la vista (lo dice [Presencia]). */
    fun alaVista(si: Boolean) {
        if (!::app.isInitialized) return
        aLaVista = si
        if (si && hilo?.isAlive != true) hilo = Thread(::bucle, "sincro-automatica").apply { isDaemon = true; start() }
    }

    private fun bucle() {
        val reloj = RelojDeSincro()
        var latido = 0
        while (aLaVista) {
            try {
                val disco = Red.disco(app)
                val id = disco.identidad.leer()
                if (id.enGrupo && encendida(app) && latido % LATIDOS_POR_SONDEO == 0) {
                    val dirs = disco.direcciones()
                    val presentes = id.miembros.filter { it.id != id.yo.id }
                        .filter { m -> dirs[m.id]?.let { (h, p) -> Red.sondear(h, p) } == true }
                        .associateBy { it.id }
                    val decision = reloj.toca(System.currentTimeMillis(), presentes.keys, firma(disco.filesDir))
                    if (decision != null) {
                        for (otroId in decision.second) {
                            val (host, puerto) = dirs[otroId] ?: continue
                            vuelta(disco, otroId, presentes[otroId]?.nombre ?: otroId, host, puerto)
                        }
                        reloj.despuesDeSincronizar(firma(disco.filesDir))
                    }
                }
            } catch (_: Throwable) {
            }
            latido++
            try { Thread.sleep(LATIDO) } catch (_: InterruptedException) { return }
        }
    }

    private fun vuelta(disco: Disco, otroId: String, nombre: String, host: String, puerto: Int) {
        try {
            val r = Red.conectar(InetAddress.getByName(host), puerto).use { s ->
                VueltaCallada(disco, Presencia.red.puerto).con(s.getInputStream(), s.getOutputStream())
            }
            fallos.remove(otroId)
            if (r.trajoAlgo) decir("Sincronizado con ${r.nombre}: " + resumen(r.hecho))
        } catch (e: Throwable) {
            // Ocupado (otra vuelta en marcha) no es un fallo: se intenta en la próxima.
            if (e.message == Protocolo.OCUPADO) return
            val n = (fallos[otroId] ?: 0) + 1
            fallos[otroId] = n
            if (n == RelojDeSincro.FALLOS_PARA_AVISAR) decir("No se pudo sincronizar solo con $nombre (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun resumen(h: Sesion.Hecho): String = listOfNotNull(
        "${h.traidos} traídos".takeIf { h.traidos > 0 },
        "${h.fusionados} juntados".takeIf { h.fusionados > 0 },
        "${h.borrados} borrados".takeIf { h.borrados > 0 },
        "${h.capturas} capturas".takeIf { h.capturas > 0 },
    ).joinToString(" · ")

    private fun decir(texto: String) {
        principal.post { if (aLaVista) Toast.makeText(app, texto, Toast.LENGTH_SHORT).show() }
    }

    /** La firma de lo que viaja: el chat, los proyectos y los dibujos (no las cachés). */
    fun firma(raiz: File): RelojDeSincro.Firma {
        var n = 0L; var b = 0L; var t = 0L
        fun mirar(f: File) {
            if (f.isFile) { n++; b += f.length(); t += f.lastModified() }
            else f.listFiles()?.forEach(::mirar)
        }
        for (nombre in listOf("guardados.jsonl", "proyectos", "pins/draw", "chats")) File(raiz, nombre).takeIf { it.exists() }?.let(::mirar)
        return RelojDeSincro.Firma(n, b, t)
    }
}
