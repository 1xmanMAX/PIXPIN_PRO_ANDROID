package com.forge.pixpin.guardados

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.forge.pixpin.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * **La cola de pasar a texto, en Android** (28-oct-2026). Lo puro y el porqué, en [ColaDeTexto].
 *
 * - [encolar] apunta el mensaje en `pasar_a_texto.json` y lo pone a trabajar.
 * - Se trabaja **de uno en uno** (el reconocedor no se reparte) y **dentro de [PasarATextoService]**,
 *   en primer plano: la notificación dice qué se pasa y cuánto lleva; cerrar la app no lo para.
 * - Una nota de voz se pasa a texto con `[m:ss]` ([MensajesStore.transcribirAqui]); a una canción se
 *   le ponen los tiempos a la letra con lo que se oye ([AlinearLetra], [tiemposDeLaLetra]).
 * - **Sin el modelo de voz y sin red**, se queda esperando, y una notificación dice cómo bajarlo; en
 *   cuanto vuelve la red ([vigilar]), sigue sola.
 * - Lo que quedó a medias (el proceso murió) se retoma al volver a abrir PixPin ([vigilar]).
 */
object PasarATexto {

    private const val ARCHIVO = "pasar_a_texto.json"
    private const val CANAL = "pixpin_pasar_a_texto_cola"
    private const val AVISO = 14

    private val cerrojo = Any()
    private val trabajando: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val obrero = Executors.newSingleThreadExecutor { r -> Thread(r, "pixpin-pasar-a-texto").apply { isDaemon = true } }
    private val principal = Handler(Looper.getMainLooper())

    private val _cola = MutableStateFlow<List<ColaDeTexto.Pendiente>>(emptyList())

    /** **Lo que hay en la cola**, para la pantalla de estudiar: «en cola», «falta el modelo». */
    val cola: StateFlow<List<ColaDeTexto.Pendiente>> get() = _cola

    @Volatile private var leida = false

    private fun archivo(context: Context) = File(context.applicationContext.filesDir, ARCHIVO)

    /** La cola del disco (una vez por proceso; luego, la de memoria). */
    private fun leer(context: Context): List<ColaDeTexto.Pendiente> = synchronized(cerrojo) {
        if (!leida) {
            _cola.value = ColaDeTexto.deJson(runCatching { archivo(context).takeIf { it.exists() }?.readText() }.getOrNull())
            leida = true
        }
        _cola.value
    }

    /** Cambia la cola y la escribe (en un archivo aparte y luego cambiado: nunca a medias). */
    private fun cambiar(context: Context, cambio: (List<ColaDeTexto.Pendiente>) -> List<ColaDeTexto.Pendiente>) = synchronized(cerrojo) {
        val nueva = cambio(leer(context))
        _cola.value = nueva
        runCatching {
            val f = archivo(context)
            val tmp = File(f.parentFile, "$ARCHIVO.tmp")
            tmp.writeText(ColaDeTexto.aJson(nueva))
            if (!tmp.renameTo(f)) { f.writeText(ColaDeTexto.aJson(nueva)); tmp.delete() }
        }.onFailure { android.util.Log.w("PixPinVoz", "la cola de pasar a texto no se guardó", it) }
    }

    /**
     * **Pone [id] en la cola** y a trabajar. [idioma] solo lo pone quien practica otro idioma; si no,
     * el de Ajustes. Se puede llamar desde cualquier hilo.
     */
    fun encolar(context: Context, id: String, idioma: String? = null) {
        val app = context.applicationContext
        cambiar(app) { ColaDeTexto.poner(it, id, idioma) }
        empezar(app, id)
    }

    /**
     * **Retoma lo pendiente**: lo que quedó a medias y, con red (o [aunSinRed], el «Reintentar» de la
     * notificación), lo que esperaba al modelo. Quita antes lo que ya no hace falta.
     */
    fun reanudar(context: Context, aunSinRed: Boolean = false) {
        val app = context.applicationContext
        obrero.execute {
            runCatching {
                if (leer(app).isEmpty()) return@runCatching
                val porId = MensajesStore(app).leer().associateBy { it.id }
                cambiar(app) { c -> ColaDeTexto.limpiar(c, porId) { m -> m.ruta?.let { File(it).exists() } == true } }
                val red = aunSinRed || hayRed(app)
                ColaDeTexto.aRetomar(_cola.value, red, trabajando).forEach { empezar(app, it.id) }
                if (!ColaDeTexto.esperaAlModelo(_cola.value)) quitarElAviso(app)
            }.onFailure { android.util.Log.w("PixPinVoz", "retomar la cola", it) }
        }
    }

    private fun empezar(app: Context, id: String) {
        if (!trabajando.add(id)) return
        obrero.execute {
            try {
                trabajar(app, id)
            } finally {
                trabajando.remove(id)
            }
        }
    }

    /** Un trabajo de la cola, en el hilo del obrero y con el servicio en primer plano. */
    private fun trabajar(app: Context, id: String) {
        val almacen = MensajesStore(app)
        val m = almacen.leer().firstOrNull { it.id == id }
        val p = _cola.value.firstOrNull { it.id == id }
        val tarea = ColaDeTexto.queToca(m, m?.ruta?.let { File(it).exists() } == true)
        if (m == null || tarea == ColaDeTexto.Tarea.NADA) {
            almacen.quitarAvance(id)
            cambiar(app) { ColaDeTexto.alAcabar(it, id, ColaDeTexto.Fin.NADA_QUE_HACER) }
            return
        }
        val titulo = app.getString(
            if (tarea == ColaDeTexto.Tarea.TIEMPOS_DE_LA_LETRA) R.string.pasar_a_texto_letra else R.string.pasar_a_texto_de,
            tituloDeAudio(app, m)
        )
        val fin = runCatching {
            PasarATextoService.mientras(app, titulo, descarga = false) { aviso ->
                when (tarea) {
                    ColaDeTexto.Tarea.TIEMPOS_DE_LA_LETRA -> tiemposDeLaLetra(app, m, aviso)
                    else -> almacen.transcribirAqui(m, p?.idioma, aviso)
                }
            }
        }.getOrElse {
            android.util.Log.e("PixPinVoz", "la cola: $id", it)
            ColaDeTexto.Fin.FALLO
        }
        cambiar(app) { ColaDeTexto.alAcabar(it, id, fin) }
        // **Lo importante de la clase** (28-oct-2026, Jev): con la clave de TypeSafe, al acabar de pasar
        // a texto una nota se buscan sus tareas, decisiones, preguntas y lo de examen, en su hilo. Volver
        // a pasarla lo rehace entero (lo ya apuntado en Tareas sigue marcado). Sin clave, nada.
        if (fin == ColaDeTexto.Fin.HECHO && tarea != ColaDeTexto.Tarea.TIEMPOS_DE_LA_LETRA) com.forge.pixpin.jev.ReunionEnLaApp.lanzar(app, id, forzar = true)
        when {
            fin == ColaDeTexto.Fin.SIN_MODELO -> avisarDelModelo(app)
            !ColaDeTexto.esperaAlModelo(_cola.value) -> quitarElAviso(app)
        }
        // Un fallo que aún tiene intentos: otra vuelta, al final de la cola.
        if (fin == ColaDeTexto.Fin.FALLO && _cola.value.any { it.id == id }) principal.postDelayed({ empezar(app, id) }, 30_000)
    }

    // --- La música -----------------------------------------------------------------------------

    /**
     * **Los tiempos de la letra de una canción**: se oye entera, se alinea con la letra pegada
     * ([AlinearLetra.propuesta]) y se guarda **solo si la letra no cambió entretanto** (no se pisa lo
     * que el usuario esté escribiendo). Sin letra, lo oído como letra. La letra sigue siendo letra
     * (`estadoDelTexto: "letra"`): el PC la lee igual. Ver `docs/musica-con-letra.md`.
     */
    private fun tiemposDeLaLetra(app: Context, m: Mensaje, avance: (Float) -> Unit): ColaDeTexto.Fin {
        val almacen = MensajesStore(app)
        val antes = m.transcripcion
        almacen.ponerAvance(m.id, 0f)
        try {
            val r = oirLaCancion(app, m) { almacen.ponerAvance(m.id, it); avance(it) }
            return when (r) {
                is Transcriptor.Resultado.Texto -> {
                    // Con letra pegada, cuántas líneas se oyeron (para decir si va a ojo, como el PC).
                    val conTiempos = antes?.takeIf { AlinearLetra.lineasDeLaLetra(it).isNotEmpty() }
                        ?.let { AlinearLetra.conTiempos(it, r.trozos, m.duracionMs.takeIf { d -> d > 0 }?.toLong()) }
                    val propuesta = conTiempos?.texto ?: AlinearLetra.propuesta(antes, r.trozos, m.duracionMs.takeIf { it > 0 })
                    if (propuesta == null) {
                        avisar(app, app.getString(R.string.letra_no_se_alineo))
                        ColaDeTexto.Fin.HECHO
                    } else {
                        var puesta = false
                        almacen.actualizar(m.id) {
                            if (it.transcripcion != antes) it
                            else { puesta = true; it.copy(transcripcion = propuesta, estadoDelTexto = TEXTO_LETRA) }
                        }
                        if (puesta) avisar(
                            app,
                            conTiempos?.alineado?.let { a ->
                                app.getString(
                                    if (a.aOjo) R.string.letra_con_tiempos_lista_a_ojo else R.string.letra_con_tiempos_lista_de,
                                    tituloDeAudio(app, m), a.ancladas, a.lineas.size
                                )
                            } ?: app.getString(R.string.letra_con_tiempos_lista, tituloDeAudio(app, m))
                        )
                        ColaDeTexto.Fin.HECHO
                    }
                }
                is Transcriptor.Resultado.DescargandoIdioma -> ColaDeTexto.Fin.SIN_MODELO
                is Transcriptor.Resultado.Fallo ->
                    if (r.codigo == Transcriptor.FALLO_INTERNO) ColaDeTexto.Fin.FALLO else ColaDeTexto.Fin.HECHO
            }
        } finally {
            almacen.quitarAvance(m.id)
        }
    }

    /**
     * **Lo que se oye en una canción**, con el motor elegido (Whisper por defecto). En este hilo.
     *
     * **Con Whisper, como el PC** (29-oct-2026, [OirCancion]): tanda a tanda de 8 s, en el idioma de la
     * letra si se nota y sin anotaciones («[Música]»). Con otro motor, el de siempre, pero también en
     * el idioma de la letra.
     */
    fun oirLaCancion(app: Context, m: Mensaje, avance: (Float) -> Unit): Transcriptor.Resultado {
        val ruta = m.ruta ?: return Transcriptor.Resultado.Fallo(Transcriptor.NO_SE_LEE)
        val lineas = m.transcripcion?.let { AlinearLetra.lineasDeLaLetra(it) }.orEmpty()
        val elegido = (app.applicationContext as? com.forge.pixpin.PixPinApp)?.ajustes?.motorDeVoz ?: com.forge.pixpin.data.MOTOR_POR_DEFECTO
        if (Transcriptor.motorQueVa(elegido, MotorWhisper.soportado()) == com.forge.pixpin.data.MOTOR_WHISPER) {
            return when (val o = OirCancion.oir(app, File(ruta), lineas, avance)) {
                is OirCancion.Oido.Texto -> Transcriptor.Resultado.Texto(o.trozos.joinToString(" ") { it.texto }, 0, o.trozos, trozos = o.trozos)
                OirCancion.Oido.SinModelo -> Transcriptor.Resultado.DescargandoIdioma
                OirCancion.Oido.Nada -> Transcriptor.Resultado.Fallo(Transcriptor.NADA)
                OirCancion.Oido.Cancelada -> Transcriptor.Resultado.Fallo(Transcriptor.FALLO_INTERNO, "parada")
                is OirCancion.Oido.Fallo -> Transcriptor.Resultado.Fallo(o.codigo, o.detalle)
            }
        }
        val idioma = AlinearLetra.idiomaDeLaLetra(lineas) ?: Transcriptor.idiomaElegido(app)
        return runCatching {
            Transcriptor.transcribirAqui(app, File(ruta), idioma, avance)
        }.getOrElse { Transcriptor.Resultado.Fallo(Transcriptor.FALLO_INTERNO, Transcriptor.resumenDe(it)) }
    }

    /**
     * **«Poner los tiempos a la letra» a mano**, desde la pantalla de estudiar: lo mismo que la cola
     * pero **sin guardar**, para enseñar la propuesta y que el usuario la acepte o la siga editando.
     * [alAcabar] en el hilo principal con la letra propuesta, o null y por qué.
     */
    fun proponerTiempos(context: Context, m: Mensaje, letra: String?, avance: (Float) -> Unit, alAcabar: (String?, String?) -> Unit) {
        val app = context.applicationContext
        val almacen = MensajesStore(app)
        obrero.execute {
            almacen.ponerAvance(m.id, 0f)
            val (propuesta, porque) = try {
                PasarATextoService.mientras(app, app.getString(R.string.pasar_a_texto_letra, tituloDeAudio(app, m)), descarga = false) { aviso ->
                    when (val r = oirLaCancion(app, m) { almacen.ponerAvance(m.id, it); avance(it); aviso(it) }) {
                        is Transcriptor.Resultado.Texto -> {
                            val p = AlinearLetra.propuesta(letra, r.trozos, m.duracionMs.takeIf { it > 0 })
                            p to (if (p == null) app.getString(R.string.letra_no_se_alineo) else null)
                        }
                        is Transcriptor.Resultado.DescargandoIdioma -> null to app.getString(R.string.pasar_a_texto_falta_el_modelo)
                        is Transcriptor.Resultado.Fallo -> null to (if (r.codigo == Transcriptor.NADA) app.getString(R.string.letra_no_se_oye) else app.getString(R.string.guardados_transcripcion_fallo) + (r.detalle?.let { "\n($it)" } ?: ""))
                    }
                }
            } finally {
                almacen.quitarAvance(m.id)
            }
            principal.post { alAcabar(propuesta, porque) }
        }
    }

    // --- El modelo que falta y la red ------------------------------------------------------------

    private fun hayRed(app: Context): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm?.activeNetwork != null
    }.getOrDefault(false)

    /**
     * **«Falta el modelo de voz»**: lo pendiente espera y se dice cómo bajarlo. Tocarla abre Ajustes
     * (Voz, «Bajar»); «Reintentar» lo intenta ya (si hay red, se baja solo).
     */
    private fun avisarDelModelo(app: Context) {
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CANAL, app.getString(R.string.pasar_a_texto_canal), NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(true) }
            )
            val ajustes = PendingIntent.getActivity(
                app, 0, Intent(app, com.forge.pixpin.ConfiguracionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val reintentar = PendingIntent.getForegroundService(
                app, 1, Intent(app, PasarATextoService::class.java).setAction(PasarATextoService.REANUDAR),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cuantos = _cola.value.count { it.faltaElModelo }
            val n = NotificationCompat.Builder(app, CANAL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(app.getString(R.string.pasar_a_texto_falta_el_modelo_titulo, cuantos))
                .setContentText(app.getString(R.string.pasar_a_texto_falta_el_modelo))
                .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(R.string.pasar_a_texto_falta_el_modelo)))
                .setContentIntent(ajustes)
                .addAction(0, app.getString(R.string.pasar_a_texto_reintentar), reintentar)
                .addAction(0, app.getString(R.string.pasar_a_texto_abrir_ajustes), ajustes)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(AVISO, n)
        }
    }

    private fun quitarElAviso(app: Context) {
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(AVISO) }
    }

    private fun avisar(app: Context, texto: String) {
        principal.post { android.widget.Toast.makeText(app, texto, android.widget.Toast.LENGTH_LONG).show() }
    }

    /**
     * **Lo que retoma la cola sola** (desde [com.forge.pixpin.PixPinApp]): al volver a una pantalla de
     * PixPin (con la app delante, Android deja arrancar el servicio en primer plano) y cuando vuelve
     * la red, si algo esperaba al modelo.
     */
    fun vigilar(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var ultima = 0L
            override fun onActivityResumed(activity: Activity) {
                val ahora = System.currentTimeMillis()
                if (ahora - ultima < 60_000) return
                ultima = ahora
                reanudar(app)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (ColaDeTexto.esperaAlModelo(_cola.value)) principal.post { reanudar(app) }
                }
            })
        }
    }
}
