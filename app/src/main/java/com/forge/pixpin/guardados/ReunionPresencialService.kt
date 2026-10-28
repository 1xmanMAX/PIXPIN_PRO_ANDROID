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
import android.graphics.drawable.Icon
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.StateFlow
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
 *
 * ## Saber qué se hizo y poder terminar siempre (28-oct-2026)
 *
 * El usuario: «mientras grabo una reunión no sé si tomé la foto o grabé el clip, y no hay botón para
 * terminar la grabación»; y luego: «puede ser un pequeño cuadro donde vea que estoy tomando fotos o
 * grabando». Ahora:
 *
 * - **Cada toque se nota al momento** ([sentir]): una vibración corta y el sonido del sistema (el
 *   obturador para la foto, el de empezar y parar vídeo para el clip), y la cuenta sube en la
 *   notificación, en la burbuja y en la pantalla de la reunión. Grabando un clip se ve «● Clip 0:12».
 * - **La notificación lleva los botones a la vista también plegada**: es de estilo multimedia
 *   (`Notification.MediaStyle`, con Foto, Clip y Terminar en la vista compacta y la pausa al
 *   desplegarla). Antes eran acciones normales, que Android solo enseña desplegada: plegada no había
 *   ni «Terminar». **Tocarla abre la pantalla de la reunión** ([ReunionEnCursoActivity]).
 * - **La burbuja es un cuadro con la mirilla de la cámara** (lo que se fotografía, en vivo, ver
 *   [CamaraDeLaReunion.ponerMirilla]): un destello blanco al hacer una foto, «● 0:12» en rojo mientras
 *   se graba un clip, y Foto, Clip, pausa y **Terminar** en el propio cuadro. Si el teléfono no da la
 *   mirilla (o se soltó para grabar el vídeo), la burbuja de siempre, con el contador.
 * - **Con PixPin delante**, la burbuja se esconde y cada pantalla de la app lleva abajo **la barra de
 *   la reunión** ([ReunionEnLaApp]) con lo mismo; tocar su reloj abre la pantalla de la reunión, con
 *   la mirilla grande.
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

        /** El servicio en marcha, para la pantalla y la barra de la reunión en la app (mismo proceso). */
        @Volatile
        private var vivo: ReunionPresencialService? = null

        private val _estado = MutableStateFlow(EstadoDeLaReunion())

        /** **Lo que pintan la burbuja, la notificación, la barra y la pantalla de la reunión.** */
        val estado: StateFlow<EstadoDeLaReunion> get() = _estado

        /**
         * **Si hay una pantalla de PixPin delante** (lo pone [ReunionEnLaApp]): entonces la burbuja se
         * esconde y manda la barra de la reunión de la app, que hace lo mismo sin tapar nada.
         */
        val appDelante = MutableStateFlow(false)

        /** Una foto, como el botón de la notificación. */
        fun foto() { vivo?.foto() }
        /** Empieza o para un clip. */
        fun clip() { vivo?.clip() }
        /** Pausa o sigue. */
        fun pausa() { vivo?.pausa() }
        /** Termina y guarda la reunión. */
        fun terminar() { vivo?.terminar() }

        /**
         * **La mirilla en la pantalla de la reunión de la app** (su `PreviewView`), o null al irse: vuelve
         * a la burbuja si la hay.
         */
        fun mirillaEnLaApp(proveedor: Preview.SurfaceProvider?) {
            val s = vivo ?: return
            s.mirillaDeLaApp = proveedor
            s.elegirMirilla()
        }

        /** Lo que abre tocar la notificación o la barra: la pantalla de la reunión. */
        fun intentoDeLaPantalla(context: Context): Intent =
            Intent(context, ReunionEnCursoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

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


    /** Una foto hecha: su minuto y el JPEG en la carpeta de adjuntos. */
    private class FotoHecha(val ms: Long, val archivo: File)

    /** Un clip: su minuto, lo que dura y su MP4 (null en la forma de vídeo: se recorta al terminar). */
    private class ClipHecho(val ms: Long, val duracionMs: Long, val archivo: File?)

    /** Un tramo del vídeo de la reunión: su fichero y el minuto de la nota en que empezó. */
    private class TramoGrabado(val archivo: File, val desdeMs: Long)

    private val registro = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registro

    private val estado get() = _estado
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
    /** Lo que dice la baliza de esta reunión (28-oct-2026): el PC sabe en un segundo que se graba. */
    private val paraLaBaliza = com.forge.pixpin.sincro.GrabacionParaLaBaliza.de(com.forge.pixpin.sincro.GrabacionParaLaBaliza.idNuevo()) { cronometro }
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
    /** Dónde se ve la mirilla: la `PreviewView` de la burbuja o la de la pantalla de la reunión. */
    private var mirillaDeLaBurbuja: Preview.SurfaceProvider? = null
    private var mirillaDeLaApp: Preview.SurfaceProvider? = null
    private val sonidos by lazy {
        MediaActionSound().apply {
            runCatching {
                load(MediaActionSound.SHUTTER_CLICK); load(MediaActionSound.START_VIDEO_RECORDING); load(MediaActionSound.STOP_VIDEO_RECORDING)
            }
        }
    }
    private var lp: WindowManager.LayoutParams? = null
    private var avisoJob: Job? = null

    private val carpeta: File get() = MensajesStore(this).carpetaDeAdjuntos()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registro.currentState = Lifecycle.State.CREATED
        _estado.value = EstadoDeLaReunion()
        vivo = this
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
            elegirMirilla()
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
        com.forge.pixpin.sincro.BalizaDelMovil.empezo(paraLaBaliza)
        estado.update { it.copy(lista = true, conCamara = camara != null, video = forma == Presencial.Forma.VIDEO, mirilla = camara?.conMirilla == true) }
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
        com.forge.pixpin.sincro.BalizaDelMovil.acabo(paraLaBaliza)
        if (vivo === this) vivo = null
        runCatching { sonidos.release() }
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
        // **Se nota al momento** (28-oct-2026): el obturador, una vibración y el destello en la mirilla,
        // antes de que la cámara devuelva la foto (eso tarda: es la resolución entera).
        sentir(MediaActionSound.SHUTTER_CLICK)
        estado.update { it.copy(destellos = it.destellos + 1, fotosEnCurso = fotosPendientes) }
        scope.launch {
            try {
                val bien = cam.foto(f) && withContext(Dispatchers.IO) { FotoDeLaCamara.enSuSitio(f) || f.length() > 0 }
                if (bien) {
                    fotos += FotoHecha(ms, f)
                    estado.update { it.copy(fotos = fotos.size) }
                    avisar(getString(R.string.reunion_foto_hecha, GrabarClase.minuto(ms)), fuera = true)
                    actualizarAviso()
                } else {
                    f.delete()
                    avisar(getString(R.string.reunion_foto_fallo), fuera = true)
                }
            } finally {
                fotosPendientes--
                estado.update { it.copy(fotosEnCurso = fotosPendientes) }
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
                sentir(MediaActionSound.START_VIDEO_RECORDING)
                estado.update { it.copy(clipDesde = ms) }
                avisar(getString(R.string.reunion_clip_empezado), fuera = true)
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
                        sentir(MediaActionSound.START_VIDEO_RECORDING)
                        estado.update { it.copy(clipDesde = ms, mirilla = cam.conMirilla) }
                        avisar(getString(R.string.reunion_clip_empezado), fuera = true)
                    } else {
                        mp4.delete()
                        avisar(getString(R.string.reunion_clip_fallo), fuera = true)
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
                sentir(MediaActionSound.STOP_VIDEO_RECORDING)
                estado.update { it.copy(clipDesde = null, clips = clips.size) }
                avisar(getString(R.string.reunion_clip_hecho, GrabarClase.minuto(desde)), fuera = true)
                actualizarAviso()
                return
            }
            clipCambiando = true
            scope.launch {
                try {
                    sentir(MediaActionSound.STOP_VIDEO_RECORDING)
                    val bien = camara?.pararVideo() == true
                    val f = clipArchivo
                    clipArchivo = null
                    if (bien && f != null) {
                        clips += ClipHecho(desde, dura, f)
                        avisar(getString(R.string.reunion_clip_hecho, GrabarClase.minuto(desde)), fuera = true)
                    } else {
                        f?.delete()
                        avisar(getString(R.string.reunion_clip_fallo), fuera = true)
                    }
                    estado.update { it.copy(clipDesde = null, clips = clips.size, mirilla = camara?.conMirilla == true) }
                    elegirMirilla()
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
        com.forge.pixpin.sincro.BalizaDelMovil.cambio()
        sentir(null)
        avisar(getString(if (p) R.string.clase_en_pausa else R.string.reunion_sigue))
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
        com.forge.pixpin.sincro.BalizaDelMovil.acabo(paraLaBaliza)
        sentir(MediaActionSound.STOP_VIDEO_RECORDING)
        estado.update { it.copy(terminando = true) }
        quitarLaBurbuja()
        orientacion?.disable()
        val app = applicationContext as PixPinApp
        // **El servicio de pasar a texto, arrancado ya** (28-oct-2026): el toque de «Terminar» deja
        // arrancarlo; cuando la reunión esté guardada (segundos después), quizá ya no. Ver [ColaDeTexto].
        val reserva = PasarATextoService.reservar(app, getString(R.string.pasar_a_texto_titulo))
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
                // Su chat sale el primero al sincronizar (§11.6).
                com.forge.pixpin.sincro.BalizaDelMovil.guardada(elProyecto)
                withContext(Dispatchers.Main) {
                    toast(dicho)
                    notaGuardada?.let { avisarParaEstudiar(it) }
                    estado.update { EstadoDeLaReunion(guardada = notaGuardada) }
                    // La cola ya cogió la nota (o la cogerá con el servicio aún en pie).
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ PasarATextoService.soltar(reserva) }, 10_000)
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
        // **Y se pasa a texto, siempre**, como la clase: `[m:ss]` cada trozo. Por la cola ([ColaDeTexto]):
        // en primer plano, aunque la app esté fuera, y retomada si el proceso muere (28-oct-2026; antes
        // se lanzaba en un hilo suelto y, con el servicio ya parado, Android congelaba el proceso).
        val conTexto = Transcriptor.disponible(context)
        if (conTexto && ColaDeTexto.entraSola(mensajes.first(), hayAudio = true, grabadoAqui = true)) almacen.transcribir(mensajes.first())
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

    /**
     * **La burbuja** (28-oct-2026): con la cámara y su mirilla, **un cuadro pequeño con lo que se
     * fotografía** —un destello blanco al hacer una foto, «● 0:12» en rojo mientras se graba un clip— y
     * debajo Foto, Clip, pausa y Terminar. Sin mirilla (sin cámara, o soltada para grabar el vídeo), la
     * fila de siempre con el contador. Con PixPin delante no se pinta: manda la barra de la app
     * ([ReunionEnLaApp]).
     */
    @Composable
    private fun LaBurbuja() {
        val e by estado.collectAsState()
        val delante by appDelante.collectAsState()
        val fondo = Color(0xEE1E1E22)
        val claro = Color.White
        val rojo = Color(0xFFE53935)
        if (!delante) Column(horizontalAlignment = Alignment.Start) {
            if (e.mirilla) {
                val destello = remember { Animatable(0f) }
                LaunchedEffect(e.destellos) {
                    if (e.destellos > 0) { destello.snapTo(0.9f); destello.animateTo(0f, tween(380)) }
                }
                Box(
                    Modifier.size(120.dp, 160.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)
                        .border(if (e.clipDesde != null) 3.dp else 1.dp, if (e.clipDesde != null) rojo else claro.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .pointerInput(Unit) { detectDragGestures { cambio, d -> cambio.consume(); mover(d.x, d.y) } }
                ) {
                    AndroidView(
                        factory = { c ->
                            PreviewView(c).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                mirillaDeLaBurbuja = surfaceProvider
                                elegirMirilla()
                            }
                        },
                        onRelease = { mirillaDeLaBurbuja = null; elegirMirilla() },
                        modifier = Modifier.matchParentSize()
                    )
                    Box(Modifier.matchParentSize().background(Color.White.copy(alpha = destello.value)))
                    Text(
                        if (e.clipDesde != null) "● " + getString(R.string.reunion_clip) + " " + GrabarClase.minuto((e.segundos * 1000 - e.clipDesde!!).coerceAtLeast(0))
                        else (if (e.enPausa) "❚❚ " else "● ") + GrabarClase.minuto(e.segundos * 1000),
                        color = claro, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (e.clipDesde != null) rojo else Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                    if (e.fotos > 0 || e.fotosEnCurso > 0) Text(
                        "📷 ${e.fotos}" + (if (e.fotosEnCurso > 0) "…" else ""), color = claro, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Row(
                    Modifier.padding(top = 4.dp).clip(RoundedCornerShape(20.dp)).background(fondo),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { foto() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.PhotoCamera, getString(R.string.reunion_foto), tint = claro)
                    }
                    IconButton(onClick = { clip() }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (e.clipDesde != null) Icons.Filled.StopCircle else Icons.Filled.Videocam,
                            getString(if (e.clipDesde != null) R.string.reunion_parar_clip else R.string.reunion_clip),
                            tint = if (e.clipDesde != null) Color(0xFFFF5252) else claro
                        )
                    }
                    IconButton(onClick = { pausa() }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (e.enPausa) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            getString(if (e.enPausa) R.string.clase_seguir else R.string.clase_pausa), tint = claro
                        )
                    }
                    IconButton(onClick = { terminar() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Stop, getString(R.string.clase_terminar), tint = Color(0xFF81C784))
                    }
                }
            } else Row(
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
                        .background(if (e.enPausa) Color.Gray else rojo)
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
                        if (desde != null) "● " + getString(R.string.reunion_clip) + " " + GrabarClase.minuto((e.segundos * 1000 - desde).coerceAtLeast(0))
                        else if (e.clips == 0) getString(R.string.reunion_clip) else "${getString(R.string.reunion_clip)} · ${e.clips}",
                        color = if (desde != null) Color(0xFFFF8A80) else claro, fontSize = 13.sp
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

    /**
     * **Dónde va la mirilla**: a la pantalla de la reunión si está abierta; si no, a la burbuja (si se
     * ve); si no, a ningún sitio (la cámara no la alimenta).
     */
    private fun elegirMirilla() {
        val cam = camara ?: return
        val donde = mirillaDeLaApp ?: mirillaDeLaBurbuja.takeIf { !appDelante.value }
        cam.ponerMirilla(donde)
        estado.update { it.copy(mirilla = cam.conMirilla) }
    }

    /**
     * **Que se note**: una vibración corta y, si se da, un sonido del sistema (el obturador, empezar o
     * parar vídeo). Es lo que dice que la foto o el clip se hizo con el teléfono en el bolsillo.
     */
    private fun sentir(sonido: Int?) {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
        if (sonido != null) runCatching { sonidos.play(sonido) }
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

    /**
     * Un aviso corto en la burbuja y en la barra de la app. Con [fuera] (una foto, un clip) y sin nadie
     * que lo vea (ni burbuja ni PixPin delante), también un toast: es la confirmación de que se hizo.
     */
    private fun avisar(texto: String, fuera: Boolean = false) {
        if (fuera && burbuja == null && !appDelante.value) runCatching { Toast.makeText(applicationContext, texto, Toast.LENGTH_SHORT).show() }
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

    private fun boton(icono: Int, texto: Int, accion: String, codigo: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icono), getString(texto), alServicio(accion, codigo)).build()

    /**
     * **La notificación permanente**: «Grabando…» (o «● Clip» con su reloj mientras se graba uno), lo
     * que va hecho y **Foto**, **Clip** (o «Parar clip»), **Terminar** y la pausa.
     *
     * **De estilo multimedia** (28-oct-2026, el usuario: «no hay botón para terminar la grabación»):
     * con acciones normales Android solo enseña los botones con la notificación desplegada, y plegada
     * —como se queda casi siempre— no había ni «Terminar». Con `MediaStyle` los tres primeros van
     * también en la vista compacta. Sin sesión multimedia: no es música, y así no sale en los mandos de
     * reproducción del sistema. **Tocarla abre la pantalla de la reunión** ([ReunionEnCursoActivity]).
     */
    private fun aviso(): Notification {
        val e = estado.value
        val llevado = cronometro?.llevadoMs() ?: 0L
        val desde = e.clipDesde
        val titulo = when {
            desde != null -> "● " + getString(R.string.reunion_clip)
            e.enPausa -> getString(R.string.reunion_notif_pausa)
            else -> getString(R.string.reunion_notif)
        }
        val texto = buildString {
            if (desde != null) append(getString(R.string.reunion_notif)).append(" ").append(GrabarClase.minuto(llevado)).append(" · ")
            append(getString(if (forma == Presencial.Forma.VIDEO) R.string.reunion_forma_video else R.string.reunion_forma_audio))
            append(" · ").append(resources.getQuantityString(R.plurals.reunion_fotos, e.fotos, e.fotos))
            append(" · ").append(resources.getQuantityString(R.plurals.reunion_clips, e.clips, e.clips))
        }
        val abrir = PendingIntent.getActivity(
            this, 4, intentoDeLaPantalla(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (e.lista && !e.enPausa) {
            // El reloj lo lleva el sistema: el de la reunión o, grabando un clip, el del clip.
            val desdeCuando = if (desde != null) llevado - desde else llevado
            b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - desdeCuando.coerceAtLeast(0)).setShowWhen(true)
        } else b.setShowWhen(false)
        val compactos: IntArray
        if (e.lista) {
            b.addAction(boton(R.drawable.ic_reunion_foto, R.string.reunion_foto, ACCION_FOTO, 1))
            b.addAction(
                if (desde != null) boton(R.drawable.ic_reunion_parar_clip, R.string.reunion_parar_clip, ACCION_CLIP, 2)
                else boton(R.drawable.ic_reunion_clip, R.string.reunion_clip, ACCION_CLIP, 2)
            )
            b.addAction(boton(R.drawable.ic_reunion_terminar, R.string.clase_terminar, ACCION_TERMINAR, 3))
            b.addAction(
                if (e.enPausa) boton(R.drawable.ic_reunion_seguir, R.string.clase_seguir, ACCION_PAUSA, 5)
                else boton(R.drawable.ic_reunion_pausa, R.string.clase_pausa, ACCION_PAUSA, 5)
            )
            compactos = intArrayOf(0, 1, 2)
        } else {
            b.addAction(boton(R.drawable.ic_reunion_terminar, R.string.clase_terminar, ACCION_TERMINAR, 3))
            compactos = intArrayOf(0)
        }
        b.setStyle(Notification.MediaStyle().setShowActionsInCompactView(*compactos))
        return b.build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}

/**
 * **Lo que se ve de una reunión presencial en marcha** (28-oct-2026): lo pintan la burbuja, la
 * notificación, la barra de la reunión en la app ([ReunionEnLaApp]) y su pantalla
 * ([ReunionEnCursoActivity]).
 */
data class EstadoDeLaReunion(
    val segundos: Long = 0,
    val enPausa: Boolean = false,
    val fotos: Int = 0,
    val clips: Int = 0,
    /** El minuto en que empezó el clip que se graba, o null. */
    val clipDesde: Long? = null,
    val aviso: String? = null,
    /** Ya graba (el reloj corre). */
    val lista: Boolean = false,
    /** Cuántas fotos se han pedido: cada una es un destello en la mirilla. */
    val destellos: Int = 0,
    /** Fotos pedidas que la cámara aún no ha devuelto. */
    val fotosEnCurso: Int = 0,
    /** Si hay cámara (fotos y clips). */
    val conCamara: Boolean = false,
    /** Si hay mirilla que enseñar ahora. */
    val mirilla: Boolean = false,
    /** Si se graba el vídeo de la sala. */
    val video: Boolean = false,
    /** Pulsado «Terminar»: guardándose. */
    val terminando: Boolean = false,
    /** La nota de la reunión ya guardada (para «Estudiar»), al acabar. */
    val guardada: String? = null
)
