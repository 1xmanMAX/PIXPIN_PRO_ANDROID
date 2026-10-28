package com.forge.pixpin.sincro

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **Lo que sabe este teléfono de la grabación del PC** (28-oct-2026, §2.1, §7 y §8 de
 * `docs/movil-como-camara.md` del PC).
 *
 * - **Pregunta** (`camara_remota`) cada [CADA_CON_LA_CAMARA] con la pantalla de cámara abierta y
 *   cada [CADA_A_LA_VISTA] con PixPin a la vista, para la tira de la lista de chats
 *   ([AvisoDeCamaraDelPc]). Es el camino que siempre funciona.
 * - **Atiende el aviso del PC** al empezar, pausar, seguir o acabar ([alAviso], desde el
 *   [Respondedor] de [Presencia]): llega antes que la siguiente pregunta.
 * - Con cada anuncio lleva **su propio reloj de la grabación** ([RelojDelMovil], con
 *   `elapsedRealtime`) y lo que va adelantado su reloj sobre el del PC (medido como NTP).
 *
 * **Para no molestar a los demás aparatos del grupo**, con la pantalla de cámara cerrada solo se
 * pregunta a quien ya dijo tener una grabación (o avisó), y a los demás, una vez cada
 * [CADA_A_LOS_DEMAS]: un teléfono del grupo contesta «no grabo» y no vuelve a preguntarse hasta
 * entonces. Con la cámara abierta, a todos.
 */
object CamaraDelPc {

    const val CADA_CON_LA_CAMARA = 4_000L
    const val CADA_A_LA_VISTA = 20_000L
    const val CADA_A_LOS_DEMAS = 5 * 60_000L
    /** Lo que se sigue enseñando lo último sabido si el PC deja de contestar un momento. */
    private const val SIN_NOTICIAS = 60_000L

    /** Lo último que dijo un PC con una grabación (en curso o recién acabada). */
    data class Visto(
        val anuncio: CamaraRemota.Anuncio,
        /** El id del PC y su nombre. */
        val pc: String,
        val pcNombre: String,
        /** Lo que va adelantado el reloj de este teléfono sobre el del PC. */
        val adelanto: Long,
        val reloj: RelojDelMovil,
        /** Cuándo se supo (`elapsedRealtime`). */
        val cuando: Long
    ) {
        val grabando: Boolean get() = anuncio.grabando
        val titulo: String get() = anuncio.titulo.ifBlank { "la reunión" }
        val nombreDelPc: String get() = anuncio.aparato.ifBlank { pcNombre.ifBlank { "el PC" } }
    }

    /** El PC que graba (o acaba de grabar), o nada. */
    val visto = MutableStateFlow<Visto?>(null)

    /** Si ya se preguntó a todos al menos una vez con la pantalla de cámara abierta. */
    val preguntado = MutableStateFlow(false)

    private val conCamara: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val preguntadoEn = ConcurrentHashMap<String, Long>()
    private val adelantos = ConcurrentHashMap<String, Long>()
    private val pantallas = AtomicInteger()
    private val despertar = Channel<Unit>(Channel.CONFLATED)
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vigia: Job? = null
    private var aLaVista = 0
    @Volatile private var app: Application? = null

    /** Se engancha al ciclo de vida de las pantallas: pregunta solo con PixPin a la vista. */
    fun instalar(aplicacion: Application) {
        app = aplicacion
        aplicacion.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                aLaVista++
                if (aLaVista == 1) arrancar(aplicacion)
            }
            override fun onActivityStopped(activity: Activity) {
                aLaVista = (aLaVista - 1).coerceAtLeast(0)
                if (aLaVista == 0) { vigia?.cancel(); vigia = null }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** La pantalla de cámara se abre o se cierra: con ella abierta se pregunta más a menudo y a todos. */
    fun pantallaDeCamara(abierta: Boolean) {
        if (abierta) { pantallas.incrementAndGet(); preguntado.value = false } else pantallas.updateAndGet { (it - 1).coerceAtLeast(0) }
        despertar.trySend(Unit)
    }

    private fun arrancar(c: Context) {
        vigia?.cancel()
        vigia = alcance.launch {
            // Lo que quedó por subir de otra vez (sin red, la app cerrada): ahora que se ve, sigue.
            runCatching { SubidaDeLaCamaraService.empujar(c) }
            while (isActive) {
                val conCamaraAbierta = pantallas.get() > 0
                runCatching { preguntar(c, todos = conCamaraAbierta) }
                if (conCamaraAbierta) preguntado.value = true
                withTimeoutOrNull(if (pantallas.get() > 0) CADA_CON_LA_CAMARA else CADA_A_LA_VISTA) { despertar.receive() }
            }
        }
    }

    /** Una conexión corta: si no contesta en un momento, no está. */
    private fun conectar(host: String, puerto: Int): Socket = Socket().apply {
        connect(InetSocketAddress(host, puerto), 2_000)
        soTimeout = 15_000
        tcpNoDelay = true
    }

    /**
     * **Pregunta a los aparatos del grupo** cómo va su grabación (en el hilo de quien llama: hace
     * red). Con [todos], a todos los que tienen dirección; si no, a los que ya grabaron y, de vez en
     * cuando, a los demás. Deja en [visto] la grabación en curso (o la última acabada).
     */
    fun preguntar(context: Context, todos: Boolean): Visto? {
        val disco = Disco(context.applicationContext.filesDir)
        val id = disco.identidad.leer()
        if (!id.enGrupo) { visto.value = null; return null }
        val dirs = disco.direcciones()
        val pared = System.currentTimeMillis()
        val antes = visto.value
        val candidatos = id.miembros
            .filter { it.id.isNotBlank() && it.id != id.yo.id && dirs[it.id] != null }
            .distinctBy { it.id }
            .filter { todos || it.id in conCamara || pared - (preguntadoEn[it.id] ?: 0L) > CADA_A_LOS_DEMAS }
            .sortedByDescending { (if (it.id == antes?.pc) 2 else 0) + (if (it.id in conCamara) 1 else 0) }
        var mejor: Visto? = null
        var elDeAntesNoContesto = false
        for (m in candidatos) {
            val (host, puerto) = dirs.getValue(m.id)
            preguntadoEn[m.id] = pared
            val r = runCatching {
                conectar(host, puerto).use { s ->
                    val con = ConexionDeCamara.abrir(s.getInputStream(), s.getOutputStream(), id, miPuerto())
                    if (con.puertoDelOtro > 0) disco.apuntarDireccion(con.otro.id, host, con.puertoDelOtro)
                    val respuesta = if (con.sabeDeCamara) runCatching { con.preguntar() }.getOrNull() else null
                    con.adios()
                    respuesta
                }
            }
            if (r.isFailure) { if (m.id == antes?.pc) elDeAntesNoContesto = true; continue }
            val resp = r.getOrNull() ?: continue
            adelantos[m.id] = resp.adelanto
            if (resp.anuncio.grabacion.isEmpty()) continue
            conCamara += m.id
            val ahora = SystemClock.elapsedRealtime()
            val reloj = antes?.takeIf { it.pc == m.id }?.reloj?.alDia(resp.anuncio, ahora, resp.ida)
                ?: RelojDelMovil.de(resp.anuncio, ahora, resp.ida)
            val v = Visto(resp.anuncio, m.id, m.nombre, resp.adelanto, reloj, ahora)
            if (mejor == null || (v.grabando && !mejor.grabando)) mejor = v
            if (v.grabando) break
        }
        val sigue = mejor == null && antes != null && elDeAntesNoContesto &&
            SystemClock.elapsedRealtime() - antes.cuando < SIN_NOTICIAS
        if (!sigue) visto.value = mejor
        return visto.value
    }

    /**
     * **El PC avisó** (desde el [Respondedor]): ya se le contestó con la hora de aquí ([llegoEn]).
     * Sin `grabacion`, el PC apagó la cámara: fuera lo suyo.
     */
    fun alAviso(a: CamaraRemota.Anuncio, otro: Aparato, llegoEn: Long) {
        val antes = visto.value
        if (a.grabacion.isEmpty()) {
            if (antes?.pc == otro.id) visto.value = null
            return
        }
        conCamara += otro.id
        // Sin la vuelta no hay NTP: lo medido al preguntar, o lo que dice la hora del aviso.
        val adelanto = adelantos[otro.id] ?: if (a.reloj > 0) llegoEn - a.reloj else 0L
        val ahora = SystemClock.elapsedRealtime()
        val reloj = antes?.takeIf { it.pc == otro.id }?.reloj?.alDia(a, ahora) ?: RelojDelMovil.de(a, ahora)
        visto.value = Visto(a, otro.id, otro.nombre, adelanto, reloj, ahora)
        despertar.trySend(Unit)
    }

    /**
     * **La baliza dice que [pc] graba** (28-oct-2026, §11.5 de la guía del PC, desde
     * [BalizaDelMovil]): la tira sale al momento, con el minuto de la baliza («● 12:34») mientras
     * llega la respuesta de `camara_remota`, que se pregunta ya en la dirección de la baliza (ya
     * apuntada). El reloj bueno sigue siendo el de `camara_remota`.
     */
    fun alOirBaliza(pc: String, pcNombre: String, grabacion: String, enPausa: Boolean, msActual: Long) {
        conCamara += pc
        val antes = visto.value
        if (antes != null && antes.pc == pc && antes.grabando && antes.anuncio.grabacion == grabacion) return
        val a = CamaraRemota.Anuncio(grabacion = grabacion, activa = true, enPausa = enPausa, msActual = msActual)
        val ahora = SystemClock.elapsedRealtime()
        visto.value = Visto(a, pc, pcNombre, adelantos[pc] ?: 0L, RelojDelMovil.de(a, ahora), ahora)
        despertar.trySend(Unit)
    }

    /**
     * **Lo que contestó el PC por la conexión caliente** ([ConexionCaliente], §11.7): lo mismo que al
     * [preguntar], sin abrir otra conexión.
     */
    fun alResponder(pc: String, pcNombre: String, resp: ConexionDeCamara.Respuesta) {
        adelantos[pc] = resp.adelanto
        val antes = visto.value
        if (resp.anuncio.grabacion.isEmpty()) {
            if (antes?.pc == pc) visto.value = null
            return
        }
        conCamara += pc
        val ahora = SystemClock.elapsedRealtime()
        val reloj = antes?.takeIf { it.pc == pc }?.reloj?.alDia(resp.anuncio, ahora, resp.ida)
            ?: RelojDelMovil.de(resp.anuncio, ahora, resp.ida)
        visto.value = Visto(resp.anuncio, pc, pcNombre, resp.adelanto, reloj, ahora)
    }

    private fun miPuerto(): Int = runCatching { Presencia.red.puerto }.getOrDefault(0)
}
