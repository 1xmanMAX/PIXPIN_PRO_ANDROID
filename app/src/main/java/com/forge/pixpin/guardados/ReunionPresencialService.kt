package com.forge.pixpin.guardados

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.audio.Grabador
import com.forge.pixpin.motor.añadirVistaFluida
import com.forge.pixpin.pin.OverlayComposeWindow
import com.forge.pixpin.pin.Voz
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * **La reunión presencial: grabar en segundo plano con «Foto» y «Clip»** (28-oct-2026, §3.3 de las
 * novedades del PC, «En el móvil es el uso natural»; el usuario: «que siga grabando aunque haya salido
 * de la app, que aparezca un botón flotante para tomar FOTOS de la reunión y, como las capturas, que
 * todo se adjunte a la reunión; y las fotos y las capturas a resolución COMPLETA»). Lo puro y el
 * formato están en [Presencial]; la cámara, en [CamaraDeLaReunion].
 *
 * ## Un servicio de micrófono y cámara, atado a CameraX
 *
 * Un servicio en primer plano **de tipo `microphone|camera`** (sin el permiso de la cámara, solo
 * `microphone`): es lo único con que Android deja seguir usando la cámara con la app fuera y la
 * pantalla apagada. Lo arranca [GrabarClaseActivity] con la app a la vista (Android 14 no deja
 * arrancarlo de otra forma). El servicio es el [LifecycleOwner] al que se ata CameraX, siempre
 * «resumido» mientras graba: un `LifecycleService` sin la dependencia.
 *
 * ## Lo que se ve: la notificación y, si se quiere, la burbuja
 *
 * La notificación permanente dice **«Grabando…»** y lleva **Foto**, **Clip** y **Terminar**; sus
 * botones van al servicio (`PendingIntent.getService`), **no abren la app**: se puede hacer una foto
 * con la pantalla bloqueada desde la notificación, sin salir de lo que se esté haciendo. Si se pidió y
 * hay permiso de dibujar encima, además **la burbuja flotante** encima de otras apps, con lo mismo y la
 * pausa, que se arrastra como la barra de [GrabarClaseService].
 *
 * ## Las dos formas
 *
 * - **Vídeo de la sala** ([Presencial.Forma.VIDEO]): el vídeo entero de la reunión (HEVC o H.264,
 *   1080p30 o 720p, AAC mono 96 kb/s) con su micrófono; al terminar la nota de voz sale de su pista de
 *   audio ([Mp4DeLaReunion.unirAudio]). Con la calidad automática, pasada la hora sigue a 720p en otro
 *   tramo (que entra como un clip largo en su minuto: ver `docs/reunion-presencial-android.md`). Los
 *   clips se recortan del propio vídeo ([Mp4DeLaReunion.recortar]): la cámara ya está grabando.
 * - **Solo audio** ([Presencial.Forma.AUDIO]): la nota de voz de siempre ([Voz.empezar]); un clip
 *   graba la cámara sin micrófono y al terminar se le pone el trozo de la nota ([Mp4DeLaReunion.ponerSonido]).
 *
 * En las dos, **la nota de voz sale siempre** y se pasa a texto al terminar, como la clase; las fotos,
 * a la resolución entera de la cámara y en JPEG ([FotoDeLaCamara]), entran como capturas con
 * `fuente: "camara"`, y los clips en `clips` de la nota. Ver [Presencial.mensajes].
 */
class ReunionPresencialService : Service(), LifecycleOwner {

    companion object {
        private const val EL_PROYECTO = "proyecto"
        private const val LA_FORMA = "forma"
        private const val LA_CALIDAD = "calidad"
        private const val LA_BURBUJA = "burbuja"
        private const val ACCION_FOTO = "com.forge.pixpin.action.REUNION_FOTO"
        private const val ACCION_CLIP = "com.forge.pixpin.action.REUNION_CLIP"
        private const val ACCION_PAUSA = "com.forge.pixpin.action.REUNION_PAUSA"
        private const val ACCION_TERMINAR = "com.forge.pixpin.action.REUNION_TERMINAR"
        private const val CANAL = "pixpin_reunion"
        private const val AVISO = 21

        /** Lo que se recuerda: la cámara elegida, como el PC. */
        private const val PREFS = "grabar_clase"
        private const val CAMARA = "camara_presencial"

        /** Una sola grabación a la vez (con [GrabarClaseService]): se pelearían por el micrófono. */
        @Volatile
        var enMarcha = false
            private set

        /** Empieza la reunión en el chat [proyecto] (null, el general). Con la app a la vista. */
        fun empezar(
            context: Context, proyecto: String?, forma: Presencial.Forma, calidad: Presencial.Calidad, burbuja: Boolean
        ) {
            context.startForegroundService(
                Intent(context, ReunionPresencialService::class.java)
                    .putExtra(EL_PROYECTO, proyecto)
                    .putExtra(LA_FORMA, forma.clave)
                    .putExtra(LA_CALIDAD, calidad.clave)
                    .putExtra(LA_BURBUJA, burbuja)
            )
        }
    }

    /** Lo que pintan la burbuja y la notificación. */
    private data class Estado(
        val segundos: Long = 0,
        val enPausa: Boolean = false,
        val fotos: Int = 0,
        val clips: Int = 0,
        /** El minuto en que empezó el clip que se graba, o null. */
        val clipDesde: Long? = null,
        val aviso: String? = null,
        val lista: Boolean = false
    )

    /** Una foto hecha: su minuto y el JPEG en la carpeta de adjuntos. */
    private class FotoHecha(val ms: Long, val archivo: File)

    /** Un clip: su minuto, lo que dura y su MP4 (null en la forma de vídeo: se recorta al terminar). */
    private class ClipHecho(val ms: Long, val duracionMs: Long, val archivo: File?)

    /** Un tramo del vídeo de la reunión: su fichero y el minuto de la nota en que empezó. */
    private class TramoGrabado(val archivo: File, val desdeMs: Long)

    private val registro = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registro

    private val estado = MutableStateFlow(Estado())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wm by lazy { getSystemService(WindowManager::class.java) }

    private var forma = Presencial.Forma.AUDIO
    private var calidad = Presencial.Calidad.AUTOMATICA
    private var proyecto: String? = null
    private var camara: CamaraDeLaReunion? = null
    private var conCamara = false
    private var grabador: Grabador? = null
    private var nota: File? = null
    private val tramos = mutableListOf<TramoGrabado>()
    private var cambiandoDeTramo = false
    private var cronometro: GrabarClase.Cronometro? = null
    private val picos = Picos()
    private val fotos = mutableListOf<FotoHecha>()
    private val clips = mutableListOf<ClipHecho>()
    private var clipArchivo: File? = null
    private var clipCambiando = false
    private var fotosPendientes = 0
    private var numeroDeFoto = 0
    private var terminado = false
    private var empezado = false
    private var orientacion: OrientationEventListener? = null

    private var burbuja: OverlayComposeWindow? = null
    private var lp: WindowManager.LayoutParams? = null
    private var avisoJob: Job? = null

    private val carpeta: File get() = MensajesStore(this).carpetaDeAdjuntos()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registro.currentState = Lifecycle.State.CREATED
        val canal = NotificationChannel(CANAL, getString(R.string.reunion_canal), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        conCamara = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        // Con la app a la vista (la arranca [GrabarClaseActivity]): si no, Android 14 no da ni el
        // micrófono ni la cámara a un servicio y esto lanza. Sin el permiso de la cámara, solo el
        // micrófono: la nota se graba igual.
        val tipos = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (conCamara) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        val bien = runCatching { startForeground(AVISO, aviso(), tipos) }.isSuccess ||
            (conCamara && runCatching {
                conCamara = false
                startForeground(AVISO, aviso(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }.isSuccess)
        if (!bien) {
            toast(getString(R.string.clase_no_arranca))
            terminado = true
            stopSelf()
            return
        }
        registro.currentState = Lifecycle.State.RESUMED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_FOTO -> { foto(); return START_NOT_STICKY }
            ACCION_CLIP -> { clip(); return START_NOT_STICKY }
            ACCION_PAUSA -> { pausa(); return START_NOT_STICKY }
            ACCION_TERMINAR -> { terminar(); return START_NOT_STICKY }
        }
        if (empezado || terminado) return START_NOT_STICKY
        empezado = true
        enMarcha = true
        proyecto = intent?.getStringExtra(EL_PROYECTO)
        forma = Presencial.Forma.de(intent?.getStringExtra(LA_FORMA))
        calidad = Presencial.Calidad.de(intent?.getStringExtra(LA_CALIDAD))
        val conBurbuja = intent?.getBooleanExtra(LA_BURBUJA, true) ?: true
        scope.launch { arrancar(conBurbuja) }
        return START_NOT_STICKY
    }

    private suspend fun arrancar(conBurbuja: Boolean) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val cam = if (conCamara) CamaraDeLaReunion(this, this) else null
        cam?.giro = giroDeLaPantalla()
        val abierta = cam != null && cam.abrir(runCatching { prefs.getString(CAMARA, null) }.getOrNull())
        if (terminado) {
            // Se terminó mientras se abría la cámara: no hay nada grabado.
            cam?.cerrar()
            return
        }
        if (abierta) {
            camara = cam
            cam!!.camaraId?.let { id -> runCatching { prefs.edit().putString(CAMARA, id).apply() } }
            vigilarElGiro()
        } else {
            avisar(getString(R.string.reunion_sin_camara))
        }
        val ahora = System.currentTimeMillis()
        var empezoElVideo = false
        if (forma == Presencial.Forma.VIDEO && abierta) {
            val mp4 = File(carpeta, "reunion_${ahora}_1.mp4")
            empezoElVideo = cam!!.empezarVideo(mp4, Presencial.altoDelVideo(calidad, 0), conSonido = true)
            if (empezoElVideo) tramos += TramoGrabado(mp4, 0) else mp4.delete()
        }
        if (terminado) {
            if (empezoElVideo) cam?.pararVideo()
            tramos.forEach { it.archivo.delete() }
            cam?.cerrar()
            return
        }
        if (forma == Presencial.Forma.VIDEO && !empezoElVideo) {
            // Sin vídeo, la reunión no se pierde: solo audio, con fotos si hay cámara.
            forma = Presencial.Forma.AUDIO
            toast(getString(R.string.reunion_sin_video))
        }
        if (forma == Presencial.Forma.AUDIO) {
            val m4a = File(carpeta, "reunion_$ahora.m4a")
            val g = Voz.empezar(this, m4a, paraUnaClase = true)
            if (g == null) {
                m4a.delete()
                toast(getString(R.string.clase_no_arranca))
                terminado = true
                enMarcha = false
                camara?.cerrar()
                stopSelf()
                return
            }
            grabador = g
            nota = m4a
        }
        val c = GrabarClase.Cronometro()
        cronometro = c
        estado.update { it.copy(lista = true) }
        actualizarAviso()
        if (conBurbuja) ensenarLaBurbuja()
        // El reloj, la onda y, con la calidad automática, el paso a 720p pasada la hora.
        scope.launch {
            var ultimoSegundo = -1L
            while (isActive && !terminado) {
                if (!c.enPausa) picos.anota(if (forma == Presencial.Forma.VIDEO) camara?.pico() ?: 0 else Voz.pico(grabador))
                val llevado = c.llevadoMs()
                val s = llevado / 1000
                if (s != ultimoSegundo) {
                    ultimoSegundo = s
                    estado.update { it.copy(segundos = s) }
                    val cam2 = camara
                    if (forma == Presencial.Forma.VIDEO && cam2 != null && !cambiandoDeTramo && cam2.grabando &&
                        Presencial.tocaBajar(calidad, llevado, cam2.altoDelVideo)
                    ) scope.launch { siguienteTramo() }
                }
                delay(Voz.MS_ENTRE_PICOS)
            }
        }
    }

    override fun onDestroy() {
        // Cerrar no es tirar: si el sistema nos para a media reunión, se guarda.
        if (!terminado && empezado) terminar()
        quitarLaBurbuja()
        orientacion?.disable()
        enMarcha = false
        registro.currentState = Lifecycle.State.DESTROYED
        scope.cancel()
        super.onDestroy()
    }

    // --- Las acciones -------------------------------------------------------------------------

    /**
     * **Una foto**, en el minuto en que se pulsa (no en el que la cámara la devuelve), a la resolución
     * entera. Varias seguidas se hacen todas: la cámara las pone en cola.
     */
    private fun foto() {
        val c = cronometro ?: return
        if (terminado) return
        val cam = camara
        if (cam == null) {
            avisar(getString(R.string.reunion_sin_camara))
            return
        }
        val ms = Presencial.msDeLaFoto(c.llevadoMs())
        val n = ++numeroDeFoto
        val f = File(carpeta, "reunion_foto_${System.currentTimeMillis()}_$n.jpg")
        fotosPendientes++
        scope.launch {
            try {
                val bien = cam.foto(f) && withContext(Dispatchers.IO) { FotoDeLaCamara.enSuSitio(f) || f.length() > 0 }
                if (bien) {
                    fotos += FotoHecha(ms, f)
                    estado.update { it.copy(fotos = fotos.size) }
                    avisar(getString(R.string.reunion_foto_hecha, GrabarClase.minuto(ms)))
                    actualizarAviso()
                } else {
                    f.delete()
                    avisar(getString(R.string.reunion_foto_fallo))
                }
            } finally {
                fotosPendientes--
            }
        }
    }

    /**
     * **Clip**: un toque empieza, otro lo para. En la forma de vídeo solo se apuntan el minuto y lo que
     * dura (se recorta del vídeo al terminar); en la de audio, la cámara graba su MP4 sin micrófono.
     */
    private fun clip() {
        val c = cronometro ?: return
        if (terminado || clipCambiando) return
        val desde = estado.value.clipDesde
        if (desde == null) {
            val ms = c.llevadoMs()
            if (forma == Presencial.Forma.VIDEO) {
                if (tramos.isEmpty()) return
                estado.update { it.copy(clipDesde = ms) }
                avisar(getString(R.string.reunion_clip_empezado))
                actualizarAviso()
                return
            }
            val cam = camara ?: return avisar(getString(R.string.reunion_sin_camara))
            clipCambiando = true
            val mp4 = File(carpeta, "reunion_clip_${System.currentTimeMillis()}.mp4")
            scope.launch {
                try {
                    if (cam.empezarVideo(mp4, 1080, conSonido = false)) {
                        if (c.enPausa) cam.pausar(true)
                        clipArchivo = mp4
                        estado.update { it.copy(clipDesde = ms) }
                        avisar(getString(R.string.reunion_clip_empezado))
                    } else {
                        mp4.delete()
                        avisar(getString(R.string.reunion_clip_fallo))
                    }
                    actualizarAviso()
                } finally {
                    clipCambiando = false
                }
            }
        } else {
            val dura = (c.llevadoMs() - desde).coerceAtLeast(0)
            if (forma == Presencial.Forma.VIDEO) {
                clips += ClipHecho(desde, dura, null)
                estado.update { it.copy(clipDesde = null, clips = clips.size) }
                avisar(getString(R.string.reunion_clip_hecho, GrabarClase.minuto(desde)))
                actualizarAviso()
                return
            }
            clipCambiando = true
            scope.launch {
                try {
                    val bien = camara?.pararVideo() == true
                    val f = clipArchivo
                    clipArchivo = null
                    if (bien && f != null) {
                        clips += ClipHecho(desde, dura, f)
                        avisar(getString(R.string.reunion_clip_hecho, GrabarClase.minuto(desde)))
                    } else {
                        f?.delete()
                        avisar(getString(R.string.reunion_clip_fallo))
                    }
                    estado.update { it.copy(clipDesde = null, clips = clips.size) }
                    actualizarAviso()
                } finally {
                    clipCambiando = false
                }
            }
        }
    }

    private fun pausa() {
        val c = cronometro ?: return
        if (terminado) return
        val p = !c.enPausa
        c.pausar(p)
        grabador?.pausar(p)
        camara?.pausar(p)
        estado.update { it.copy(enPausa = p) }
        if (p) avisar(getString(R.string.clase_en_pausa))
        actualizarAviso()
    }

    /**
     * **Pasada la hora, a 720p** (la calidad automática): se cierra el tramo de 1080p y se abre otro.
     * Mientras se cambia, el cronómetro se para: lo que no se graba no cuenta en el minuto de las fotos.
     */
    private suspend fun siguienteTramo() {
        val cam = camara ?: return
        val c = cronometro ?: return
        if (cambiandoDeTramo || terminado) return
        cambiandoDeTramo = true
        try {
            val yaEnPausa = c.enPausa
            if (!yaEnPausa) c.pausar(true)
            cam.pararVideo()
            val mp4 = File(carpeta, "reunion_${System.currentTimeMillis()}_${tramos.size + 1}.mp4")
            val desde = c.llevadoMs()
            if (cam.empezarVideo(mp4, Presencial.altoDelVideo(calidad, desde), conSonido = true)) {
                tramos += TramoGrabado(mp4, desde)
                if (yaEnPausa) cam.pausar(true)
            } else {
                mp4.delete()
                avisar(getString(R.string.reunion_sin_video))
            }
            if (!yaEnPausa) c.pausar(false)
        } finally {
            cambiandoDeTramo = false
        }
    }

    /** Para todo y mete la reunión en el chat, fuera del servicio (que se va enseguida). */
    private fun terminar() {
        if (terminado) return
        if (!empezado || cronometro == null) {
            // Aún arrancando (o ni eso): no hay nada que guardar.
            terminado = true
            enMarcha = false
            camara?.cerrar()
            stopSelf()
            return
        }
        terminado = true
        enMarcha = false
        quitarLaBurbuja()
        orientacion?.disable()
        val app = applicationContext as PixPinApp
        val c = cronometro
        val clipAbierto = estado.value.clipDesde
        val elProyecto = proyecto
        val laForma = forma
        val lasPicos = picos.lista()
        // En el alcance de la app y no en el del servicio: si el sistema para el servicio a media
        // reunión, esto sigue hasta guardarlo (el del servicio se cancela al destruirse).
        app.scope.launch(Dispatchers.Main.immediate) {
            // Lo que estaba a medias: las fotos que aún no han vuelto y el clip que se grababa.
            withTimeoutOrNull(5_000) { while (fotosPendientes > 0) delay(50) }
            val llevado = c?.llevadoMs() ?: 0
            val cam = camara
            if (clipAbierto != null) {
                val dura = (llevado - clipAbierto).coerceAtLeast(0)
                if (laForma == Presencial.Forma.VIDEO) clips += ClipHecho(clipAbierto, dura, null)
                else {
                    val f = clipArchivo
                    clipArchivo = null
                    if (cam?.pararVideo() == true && f != null) clips += ClipHecho(clipAbierto, dura, f) else f?.delete()
                }
            }
            val bienElVideo = if (laForma == Presencial.Forma.VIDEO) cam?.pararVideo() == true || tramos.isNotEmpty() else false
            cam?.cerrar()
            val bienLaNota = if (laForma == Presencial.Forma.AUDIO) Voz.parar(grabador) else bienElVideo
            grabador = null
            val lasFotos = fotos.sortedBy { it.ms }
            val losClips = clips.sortedBy { it.ms }
            val losTramos = tramos.toList()
            val laNota = nota
            app.scope.launch(Dispatchers.IO) {
                val dicho = runCatching {
                    guardar(app, bienLaNota, laForma, laNota, losTramos, elProyecto, lasPicos, lasFotos, losClips)
                }.onFailure { android.util.Log.e("PixPinReunion", "la reunión no se guardó", it) }
                    .getOrElse { getString(R.string.clase_no_arranca) }
                withContext(Dispatchers.Main) {
                    toast(dicho)
                    notaGuardada?.let { avisarParaEstudiar(it) }
                    stopSelf()
                }
            }
        }
    }

    /**
     * **Mete la reunión en el chat**: la nota, el vídeo, las fotos y los clips enlazados
     * ([Presencial.mensajes]) y todo en su carpeta. Devuelve lo que se le dice al usuario.
     */
    private fun guardar(
        context: Context, bien: Boolean, laForma: Presencial.Forma, laNota: File?, losTramos: List<TramoGrabado>,
        proyecto: String?, picos: List<Int>, lasFotos: List<FotoHecha>, losClips: List<ClipHecho>
    ): String {
        val almacen = MensajesStore(context)
        val ahora = System.currentTimeMillis()
        val nombre = context.getString(R.string.reunion_nombre, GrabarClase.fechaDelNombre(ahora))
        // La nota: la grabada, o la pista de audio del vídeo (de sus tramos, seguidos).
        val m4a = when {
            laForma == Presencial.Forma.AUDIO -> laNota?.takeIf { bien && it.exists() }
            losTramos.isNotEmpty() -> File(carpeta, "reunion_$ahora.m4a").takeIf { Mp4DeLaReunion.unirAudio(losTramos.map { it.archivo }, it) }
            else -> null
        }
        val duracion = m4a?.let { Voz.duracion(it.absolutePath) } ?: 0
        val conNota = m4a != null && duracion >= Voz.MINIMO_MS
        val vozId = UUID.randomUUID().toString()
        // Los tramos del vídeo en la línea de la nota: uno detrás de otro, lo que dura cada fichero.
        var desde = 0L
        val enLaNota = losTramos.map { t ->
            val d = Voz.duracion(t.archivo.absolutePath).toLong().coerceAtLeast(0)
            Presencial.Tramo(desde, d).also { desde += d }
        }

        // Los clips: recortados del vídeo de la reunión, o con el sonido de la nota puesto.
        val clipsListos = losClips.mapIndexedNotNull { i, c ->
            val final = File(carpeta, "${ahora}_${nombreConExtension(Presencial.nombreDelClip(nombre, i + 1), "mp4")}")
            val hecho = if (c.archivo == null) {
                val (k, dentro, dura) = Presencial.tramoDelClip(enLaNota, c.ms, c.duracionMs) ?: return@mapIndexedNotNull null
                Mp4DeLaReunion.recortar(losTramos[k].archivo, dentro, dura, final)
            } else {
                val conSonido = m4a != null && Mp4DeLaReunion.ponerSonido(c.archivo, m4a, c.ms, final)
                if (conSonido) c.archivo.delete() else c.archivo.renameTo(final)
                final.exists()
            }
            if (!hecho) return@mapIndexedNotNull null
            val dura = Voz.duracion(final.absolutePath).toLong().takeIf { it > 0 } ?: c.duracionMs
            Presencial.ClipGuardado(UUID.randomUUID().toString(), c.ms, dura, final.absolutePath, Presencial.nombreDelClip(nombre, i + 1), final.length())
        }
        // Las fotos: con su nombre del PC (`foto-<hora>-<n>.jpg`), los bytes tal cual.
        val fotosListas = lasFotos.mapIndexedNotNull { i, f ->
            val fichero = Presencial.nombreDeLaFoto(true, ahora, i + 1, "jpg")
            val final = File(carpeta, "${ahora}_$fichero")
            if (!f.archivo.renameTo(final)) return@mapIndexedNotNull null
            Presencial.FotoGuardada(UUID.randomUUID().toString(), Presencial.msDeLaFoto(f.ms, duracion.toLong().takeIf { conNota }), final.absolutePath, fichero, final.length())
        }
        // El vídeo de la reunión: el primer tramo; los siguientes (720p pasada la hora), como clips.
        val primero = losTramos.firstOrNull()?.archivo?.takeIf { it.exists() && it.length() > 0 }
        val video = primero?.let { v ->
            val n = GrabarClase.nombreDelVideo(nombre)
            val final = File(carpeta, "${ahora}_${nombreConExtension(n, "mp4")}")
            val ruta = if (v.renameTo(final)) final else v
            Presencial.VideoGuardado(UUID.randomUUID().toString(), ruta.absolutePath, n, ruta.length(), Voz.duracion(ruta.absolutePath))
        }
        val otrosTramos = losTramos.drop(1).zip(enLaNota.drop(1)).mapIndexedNotNull { i, (t, tr) ->
            if (!t.archivo.exists() || t.archivo.length() == 0L) return@mapIndexedNotNull null
            val n = "$nombre · parte ${i + 2}.mp4"
            val final = File(carpeta, "${ahora}_${nombreConExtension(n, "mp4")}")
            val ruta = if (t.archivo.renameTo(final)) final else t.archivo
            Presencial.ClipGuardado(UUID.randomUUID().toString(), tr.desdeMs, tr.duracionMs, ruta.absolutePath, n, ruta.length())
        }
        val todosLosClips = (clipsListos + otrosTramos).sortedBy { it.ms }

        if (!conNota) {
            // Sin nota no hay reunión que enlazar: lo de la cámara entra suelto, como el PC.
            m4a?.delete()
            var cuando = ahora
            fun suelto(m: Mensaje) = almacen.anadir(m)
            video?.let { suelto(Mensaje(id = it.id, cuando = cuando++, clase = Clase.ARCHIVO, ruta = it.ruta, nombre = it.nombre, bytes = it.bytes, duracionMs = it.duracionMs, proyecto = proyecto)) }
            for (f in fotosListas) suelto(Mensaje(id = f.id, cuando = cuando++, clase = Clase.IMAGEN, ruta = f.ruta, nombre = f.nombre, bytes = f.bytes, proyecto = proyecto, fuente = Presencial.DE_CAMARA))
            for (c in todosLosClips) suelto(Mensaje(id = c.id, cuando = cuando++, clase = Clase.ARCHIVO, ruta = c.ruta, nombre = c.nombre, bytes = c.bytes, duracionMs = c.duracionMs.toInt(), proyecto = proyecto, fuente = Presencial.DE_CAMARA))
            return context.getString(if (video != null || fotosListas.isNotEmpty() || todosLosClips.isNotEmpty()) R.string.reunion_sin_nota else R.string.voz_muy_corta)
        }
        val elM4a = m4a!!
        val final = File(carpeta, "${ahora}_${nombreConExtension(GrabarClase.nombreDeLaNota(nombre), "m4a")}")
        val rutaVoz = if (elM4a.renameTo(final)) final else elM4a
        notaGuardada = vozId
        val mensajes = Presencial.mensajes(
            vozId, nombre, ahora, proyecto, rutaVoz.absolutePath, rutaVoz.length(), duracion, picos,
            video, fotosListas, todosLosClips
        )
        // En orden y con números seguidos, como el PC: la nota, el vídeo, las fotos y los clips.
        mensajes.forEach { almacen.anadir(it) }
        // Y todo dentro de su carpeta (§3.2): una tarjeta en el chat, las fotos con «⏱» y los clips con «▶».
        runCatching {
            val doc = Presencial.carpeta(vozId, duracion.toLong(), video?.id, fotosListas, todosLosClips)
            val cuando = CarpetaDePaginas.cuandoLibre(System.currentTimeMillis(), almacen.leer())
            almacen.anadir(CarpetaDePaginas.mensajeNuevo(nombre, cuando, proyecto, doc))
        }.onFailure { android.util.Log.w("PixPinReunion", "la reunión quedó sin su carpeta", it) }
        // Y se pasa a texto, como la clase: `[m:ss]` cada trozo.
        val conTexto = Transcriptor.disponible(context)
        if (conTexto) almacen.transcribir(mensajes.first())
        return context.getString(
            if (conTexto) R.string.reunion_lista_con_texto else R.string.reunion_lista_sin_texto,
            fotosListas.size, todosLosClips.size
        )
    }

    // --- La burbuja ---------------------------------------------------------------------------

    private fun ensenarLaBurbuja() {
        if (burbuja != null || !Settings.canDrawOverlays(this)) return
        val alto = resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Sin foco: tocarla no le quita el teclado a lo que haya debajo.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = alto / 6
        }
        val w = OverlayComposeWindow(this) { LaBurbuja() }
        runCatching {
            wm.añadirVistaFluida(w.view, params)
            w.onAttached()
            burbuja = w
            lp = params
        }
    }

    private fun mover(dx: Float, dy: Float) {
        val params = lp ?: return
        val v = burbuja?.view ?: return
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        runCatching { wm.updateViewLayout(v, params) }
    }

    private fun quitarLaBurbuja() {
        burbuja?.let { w -> runCatching { wm.removeView(w.view) }; w.onDetached() }
        burbuja = null
        lp = null
    }

    @Composable
    private fun LaBurbuja() {
        val e by estado.collectAsState()
        val fondo = Color(0xEE1E1E22)
        val claro = Color.White
        Column(horizontalAlignment = Alignment.Start) {
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).background(fondo).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.DragIndicator, contentDescription = null, tint = claro.copy(alpha = 0.6f),
                    modifier = Modifier.size(28.dp).pointerInput(Unit) {
                        detectDragGestures { cambio, d -> cambio.consume(); mover(d.x, d.y) }
                    }
                )
                Box(
                    Modifier.padding(start = 2.dp, end = 6.dp).size(10.dp).clip(CircleShape)
                        .background(if (e.enPausa) Color.Gray else Color(0xFFE53935))
                )
                Text(
                    GrabarClase.minuto(e.segundos * 1000), color = claro, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp)
                )
                TextButton(onClick = { foto() }) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = claro, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (e.fotos == 0) getString(R.string.reunion_foto) else "${getString(R.string.reunion_foto)} · ${e.fotos}",
                        color = claro, fontSize = 13.sp
                    )
                }
                val desde = e.clipDesde
                TextButton(onClick = { clip() }) {
                    Icon(
                        if (desde != null) Icons.Filled.StopCircle else Icons.Filled.Videocam, contentDescription = null,
                        tint = if (desde != null) Color(0xFFFF5252) else claro, modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (desde != null) GrabarClase.minuto((e.segundos * 1000 - desde).coerceAtLeast(0))
                        else if (e.clips == 0) getString(R.string.reunion_clip) else "${getString(R.string.reunion_clip)} · ${e.clips}",
                        color = claro, fontSize = 13.sp
                    )
                }
                IconButton(onClick = { pausa() }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (e.enPausa) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = getString(if (e.enPausa) R.string.clase_seguir else R.string.clase_pausa),
                        tint = claro
                    )
                }
                IconButton(onClick = { terminar() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = getString(R.string.clase_terminar), tint = Color(0xFF81C784))
                }
            }
            e.aviso?.let {
                Text(
                    it, color = claro, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(fondo)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }

    // --- El giro, los avisos y la notificación ------------------------------------------------

    /** El giro de la pantalla ahora: el del vídeo que empieza y el de las fotos. */
    private fun giroDeLaPantalla(): Int = runCatching {
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation
    }.getOrDefault(Surface.ROTATION_0)

    /**
     * **Las fotos, con el teléfono como esté** al hacerlas (tumbado o de pie), aunque la pantalla esté
     * apagada o en otra app: el giro se lee del sensor y va en el EXIF de cada foto.
     */
    private fun vigilarElGiro() {
        val o = object : OrientationEventListener(this) {
            override fun onOrientationChanged(grados: Int) {
                if (grados == ORIENTATION_UNKNOWN) return
                val giro = when (grados) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (camara?.giro != giro) camara?.giro = giro
            }
        }
        if (o.canDetectOrientation()) o.enable()
        orientacion = o
    }

    private fun avisar(texto: String) {
        estado.update { it.copy(aviso = texto) }
        avisoJob?.cancel()
        avisoJob = scope.launch {
            delay(2_500)
            estado.update { if (it.aviso == texto) it.copy(aviso = null) else it }
        }
    }

    private fun toast(texto: String) {
        Toast.makeText(applicationContext, texto, Toast.LENGTH_LONG).show()
    }

    /** La nota recién guardada, para el aviso de «Abrir para estudiar». */
    @Volatile private var notaGuardada: String? = null

    private fun avisarParaEstudiar(nota: String) {
        runCatching {
            val abrir = PendingIntent.getActivity(
                this, 1, EstudiarClaseActivity.intento(this, nota),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.estudiar_titulo))
                .setContentText(getString(R.string.clase_abrir_para_estudiar))
                .setContentIntent(abrir)
                .addAction(0, getString(R.string.clase_abrir_para_estudiar), abrir)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(AVISO + 1, n)
        }
    }

    private fun actualizarAviso() {
        if (terminado) return
        runCatching { getSystemService(NotificationManager::class.java)?.notify(AVISO, aviso()) }
    }

    /** Un botón de la notificación: va al servicio, **no abre la app**. */
    private fun alServicio(accion: String, codigo: Int): PendingIntent = PendingIntent.getService(
        this, codigo, Intent(this, ReunionPresencialService::class.java).setAction(accion),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * **La notificación permanente**: «Grabando…», lo que va hecho y **Foto**, **Clip** (o «Parar clip»)
     * y **Terminar**. El reloj lo lleva el sistema (se ajusta en cada cambio, también en las pausas).
     */
    private fun aviso(): Notification {
        val e = estado.value
        val llevado = cronometro?.llevadoMs() ?: 0L
        val texto = buildString {
            append(getString(if (forma == Presencial.Forma.VIDEO) R.string.reunion_forma_video else R.string.reunion_forma_audio))
            append(" · ").append(resources.getQuantityString(R.plurals.reunion_fotos, e.fotos, e.fotos))
            append(" · ").append(resources.getQuantityString(R.plurals.reunion_clips, e.clips, e.clips))
            if (e.enPausa) append(" · ").append(getString(R.string.clase_pausa))
        }
        val b = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.reunion_notif))
            .setContentText(texto)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (e.lista && !e.enPausa) {
            b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - llevado).setShowWhen(true)
        }
        if (e.lista) {
            b.addAction(0, getString(R.string.reunion_foto), alServicio(ACCION_FOTO, 1))
            b.addAction(0, getString(if (e.clipDesde != null) R.string.reunion_parar_clip else R.string.reunion_clip), alServicio(ACCION_CLIP, 2))
        }
        b.addAction(0, getString(R.string.clase_terminar), alServicio(ACCION_TERMINAR, 3))
        return b.build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
