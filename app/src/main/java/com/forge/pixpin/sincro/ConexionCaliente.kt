package com.forge.pixpin.sincro

import android.app.Application
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * **Cuándo abrir, mantener y cerrar la conexión caliente** (28-oct-2026, §11.7 de
 * `docs/movil-como-camara.md` del PC), pura para probarla sin red (`ConexionCalienteTest`):
 *
 * - Mientras el PC graba (baliza o anuncio `activa`), **abierta**, y por ella `camara_remota` cada
 *   [CADA_PREGUNTA]: es el *keepalive* y a la vez el reloj.
 * - Cada foto, un `suelto` más por la misma conexión, sin saludo ni PBKDF2.
 * - Si se cae, otra en cuanto llegue la siguiente baliza ([oyoBaliza]) o enseguida con esperas de
 *   0,5, 1, 2… s hasta [TOPE_ESPERA] mientras haya fotos en cola ([esperaTrasFallo]).
 * - Al acabar (`activa:false`) y con la cola vacía: `adios` y cerrarla.
 *
 * Los tiempos son de `elapsedRealtime`.
 */
class PlanCaliente {
    enum class Paso { NADA, ABRIR, PREGUNTAR, SUBIR, CERRAR }

    private var fallos = 0
    private var abrirDesde = 0L
    private var preguntada = NUNCA
    private var fallosAlSubir = 0
    private var subirDesde = 0L

    /** Qué hacer ahora: [grabando], si el PC graba; [abierta], si hay conexión; [hayFotos], si esperan fotos suyas. */
    @Synchronized fun paso(ahora: Long, grabando: Boolean, abierta: Boolean, hayFotos: Boolean): Paso = when {
        !abierta && !grabando && !hayFotos -> Paso.NADA
        !abierta -> if (ahora >= abrirDesde) Paso.ABRIR else Paso.NADA
        !grabando && !hayFotos -> Paso.CERRAR
        hayFotos && ahora >= subirDesde -> Paso.SUBIR
        grabando && ahora - preguntada >= CADA_PREGUNTA -> Paso.PREGUNTAR
        else -> Paso.NADA
    }

    /** Cuánto dormir hasta mirar otra vez (lo despierta antes una foto nueva o una baliza). */
    @Synchronized fun espera(ahora: Long, grabando: Boolean, abierta: Boolean, hayFotos: Boolean): Long {
        val hasta = when {
            !abierta && !grabando && !hayFotos -> return DORMIDO
            !abierta -> abrirDesde
            hayFotos -> minOf(subirDesde, if (grabando) preguntada + CADA_PREGUNTA else Long.MAX_VALUE)
            grabando -> preguntada + CADA_PREGUNTA
            else -> ahora
        }
        return (hasta - ahora).coerceIn(MINIMA, DORMIDO)
    }

    @Synchronized fun abrio() { fallos = 0; abrirDesde = 0; preguntada = NUNCA }

    /** No se pudo abrir, o se cayó: la siguiente, con espera que se dobla hasta [TOPE_ESPERA]. */
    @Synchronized fun fallo(ahora: Long) { fallos++; abrirDesde = ahora + esperaTrasFallo(fallos) }

    /** Llegó una baliza del PC: si estaba esperando para abrir, ya. */
    @Synchronized fun oyoBaliza() { abrirDesde = 0 }

    @Synchronized fun pregunto(ahora: Long) { preguntada = ahora }

    /** Una pasada de subida: [bien], si no quedó nada por reintentar. */
    @Synchronized fun subio(ahora: Long, bien: Boolean) {
        if (bien) { fallosAlSubir = 0; subirDesde = 0 } else { fallosAlSubir++; subirDesde = ahora + ColaDeLaCamara.espera(fallosAlSubir) }
    }

    /** Una foto nueva en la cola: se sube ya, sin esperar a lo que falló antes. */
    @Synchronized fun fotoNueva() { fallosAlSubir = 0; subirDesde = 0 }

    companion object {
        const val CADA_PREGUNTA = 3_000L
        /** «Nunca se preguntó»: sin desbordar al restarlo. */
        const val NUNCA = Long.MIN_VALUE / 2
        const val TOPE_ESPERA = 5_000L
        private const val MINIMA = 50L
        private const val DORMIDO = 1_000L

        /** 0,5, 1, 2, 4 s y luego 5 s. */
        fun esperaTrasFallo(n: Int): Long = minOf(500L shl (n - 1).coerceIn(0, 4), TOPE_ESPERA)
    }
}

/**
 * **La conexión caliente con el PC que graba** (28-oct-2026, §11.7): para que una foto empiece a
 * subir **en menos de 1 s** desde el disparo. Mientras el PC graba (lo dice su baliza,
 * [BalizaDelMovil.quienGraba], o su anuncio, [CamaraDelPc.visto]) se mantiene abierta una
 * [ConexionDeCamara] (`hola` con `camara1`) y se pregunta `camara_remota` por ella cada
 * [PlanCaliente.CADA_PREGUNTA] ([CamaraDelPc.alResponder]). Lo que entra en la [ColaDeLaCamara] sube
 * por ella al momento ([SubidaALaGrabacion.pasar]).
 *
 * **La cola no se pierde**: cada toma está en disco antes de subir, y lo que no sale por aquí lo
 * sigue intentando [SubidaDeLaCamaraService], que también sube por esta conexión si está abierta
 * ([subir]). Lo uno y lo otro van de uno en uno ([SUBIDA]): la misma foto nunca sale dos veces a la
 * par.
 */
object ConexionCaliente {
    private const val TAG = "PixPinCaliente"
    private const val VIGENCIA_DEL_ANUNCIO = 60_000L

    /** Lo que sube a un PC, de uno en uno: esta conexión y el servicio de subida. */
    val SUBIDA = Any()

    private class Abierta(val socket: Socket, val con: ConexionDeCamara, val pc: String, val nombre: String)

    @Volatile private var app: Application? = null
    @Volatile private var abierta: Abierta? = null
    @Volatile private var hilo: Thread? = null
    private val plan = PlanCaliente()
    private val despertar = Semaphore(0)
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** El PC con el que está abierta, o null. */
    val abiertaCon: String? get() = abierta?.pc

    fun instalar(aplicacion: Application) {
        app = aplicacion
        // Una foto nueva en la cola: que suba ya.
        alcance.launch {
            var antes = ColaDeLaCamara.cambios.value
            ColaDeLaCamara.cambios.collect { v ->
                if (v != antes) { antes = v; plan.fotoNueva(); avivar() }
            }
        }
    }

    /** Llegó una baliza del PC que graba (o que acaba): se mira ya. */
    fun alOirBaliza() {
        plan.oyoBaliza()
        avivar()
    }

    /** Se mira ya (y arranca el hilo si hace falta). */
    fun avivar() {
        if (app == null) return
        despertar.release()
        synchronized(this) {
            if (hilo?.isAlive != true) hilo = Thread(::bucle, "pixpin-camara-caliente").apply { isDaemon = true; start() }
        }
    }

    /**
     * **Sube lo pendiente de [pc] por la conexión caliente**, si está abierta con él (lo llama
     * [SubidaDeLaCamaraService]); null si no lo está, y entonces abre la suya.
     */
    fun subir(pc: String, cola: ColaDeLaCamara): SubidaALaGrabacion.Pasada? = synchronized(SUBIDA) {
        val a = abierta?.takeIf { it.pc == pc } ?: return null
        val p = SubidaALaGrabacion.pasar(a.con, cola, pc)
        if (p.reintentar) despertar.release()
        p
    }

    /** El PC que graba ahora: su id y su nombre. */
    private fun quienGraba(): Pair<String, String>? {
        BalizaDelMovil.quienGraba()?.takeIf { it.baliza.camara }?.let { return it.baliza.id to it.baliza.id }
        val v = CamaraDelPc.visto.value ?: return null
        // Lo que dijo hace mucho (la pantalla apagada, nadie preguntando) no cuenta.
        return if (v.grabando && SystemClock.elapsedRealtime() - v.cuando < VIGENCIA_DEL_ANUNCIO) v.pc to v.pcNombre else null
    }

    private fun bucle() {
        val c = app ?: return
        val cola = ColaDeLaCamara.de(c.filesDir)
        try {
            while (true) {
                val ahora = SystemClock.elapsedRealtime()
                val graba = quienGraba()
                var a = abierta
                // Graba otro PC y con este ya no queda nada: se cambia.
                if (a != null && graba != null && graba.first != a.pc && cola.pendientes().none { it.pc == a!!.pc }) {
                    cerrar(despedirse = true); a = null
                }
                val pc = a?.pc ?: graba?.first
                val hayFotos = pc != null && cola.pendientes().any { it.pc == pc }
                val grabando = graba != null && graba.first == pc
                when (plan.paso(ahora, grabando, a != null, hayFotos)) {
                    PlanCaliente.Paso.ABRIR -> abrir(c, pc!!, graba?.second ?: pc)
                    PlanCaliente.Paso.PREGUNTAR -> preguntar()
                    PlanCaliente.Paso.SUBIR -> synchronized(SUBIDA) {
                        val x = abierta
                        if (x != null) {
                            val p = SubidaALaGrabacion.pasar(x.con, cola, x.pc)
                            plan.subio(SystemClock.elapsedRealtime(), !p.reintentar)
                            if (p.subidas > 0) Log.i(TAG, "${p.subidas} por la conexión caliente")
                            // Se cortó a mitad: la siguiente pregunta lo confirma ya.
                            if (p.reintentar) plan.pregunto(PlanCaliente.NUNCA)
                        }
                    }
                    PlanCaliente.Paso.CERRAR -> cerrar(despedirse = true)
                    PlanCaliente.Paso.NADA -> if (a == null && graba == null && !hayFotos) return
                }
                val ahora2 = SystemClock.elapsedRealtime()
                val espera = plan.espera(ahora2, grabando, abierta != null, hayFotos)
                despertar.tryAcquire(espera, TimeUnit.MILLISECONDS)
                despertar.drainPermits()
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w(TAG, "la conexión caliente se paró", t)
        } finally {
            if (abierta != null && quienGraba() == null) cerrar(despedirse = true)
            hilo = null
        }
    }

    private fun abrir(c: Application, pc: String, nombre: String) {
        val disco = Disco(c.filesDir)
        val (host, puerto) = disco.direcciones()[pc] ?: run { plan.fallo(SystemClock.elapsedRealtime()); return }
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, puerto), 2_000)
            // El PC espera hasta 30 min entre peticiones; aquí, lo que tarde una foto grande.
            s.soTimeout = 120_000
            s.tcpNoDelay = true
            val id = disco.identidad.leer()
            val con = ConexionDeCamara.abrir(s.getInputStream(), s.getOutputStream(), id, runCatching { Presencia.red.puerto }.getOrDefault(0))
            if (!con.sabeDeCamara) {
                con.adios(); s.close()
                plan.fallo(SystemClock.elapsedRealtime())
                return
            }
            abierta = Abierta(s, con, pc, con.otro.nombre.ifBlank { nombre })
            plan.abrio()
            Log.i(TAG, "abierta con $pc en $host:$puerto")
        } catch (t: Throwable) {
            runCatching { s.close() }
            plan.fallo(SystemClock.elapsedRealtime())
            Log.i(TAG, "no se pudo abrir con $pc en $host:$puerto: ${t.message}")
        }
    }

    /** `camara_remota` por la conexión: el keepalive y el reloj. Si falla, se cierra y se reabre. */
    private fun preguntar(): Unit = synchronized(SUBIDA) {
        val a = abierta ?: return@synchronized
        plan.pregunto(SystemClock.elapsedRealtime())
        try {
            CamaraDelPc.alResponder(a.pc, a.nombre, a.con.preguntar())
        } catch (e: Protocolo.ErrorDelOtro) {
            // Un PC de antes que no sabe preguntar: la conexión sigue sana para las fotos.
            Log.i(TAG, "el PC no contesta camara_remota: ${e.message}")
        } catch (e: IOException) {
            Log.i(TAG, "se cayó la conexión caliente: ${e.message}")
            cerrar(despedirse = false)
            plan.fallo(SystemClock.elapsedRealtime())
        }
        Unit
    }

    private fun cerrar(despedirse: Boolean): Unit = synchronized(SUBIDA) {
        val a = abierta ?: return@synchronized
        abierta = null
        if (despedirse) a.con.adios()
        runCatching { a.socket.close() }
        Log.i(TAG, "cerrada con ${a.pc}")
        Unit
    }
}
