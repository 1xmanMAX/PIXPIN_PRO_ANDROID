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
import androidx.compose.material.icons.filled.Fullscreen
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
import com.forge.pixpin.audio.RespaldoWav
import com.forge.pixpin.motor.añadirVistaFluida
import com.forge.pixpin.pin.OverlayComposeWindow
import com.forge.pixpin.pin.Voz
import com.forge.pixpin.sincro.GrabacionDelMovil
import com.forge.pixpin.capture.CaptureFlow
import com.forge.pixpin.capture.CaptureService
import com.forge.pixpin.capture.ProjectionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
 *   1080p30 o 720p). Desde el 28-oct-2026 («Grabar sin perder nada») **sin sonido y en trozos**: el
 *   sonido lo graba la nota de voz aparte (con su respaldo WAV) y se le pone al vídeo al juntar sus
 *   trozos ([GuardarLaGrabacion.juntarVideo]); así, si Android mata la app, la nota se rehace y del
 *   vídeo solo se pierde el trozo en curso. Con la calidad automática, pasada la hora sigue a 720p en
 *   otro tramo (que entra como un clip largo en su minuto: ver `docs/reunion-presencial-android.md`).
 *   Los clips se recortan del propio vídeo ya junto ([Mp4DeLaReunion.recortar]).
 * - **Solo audio** ([Presencial.Forma.AUDIO]): la nota de voz de siempre ([Voz.empezar]); un clip
 *   graba la cámara sin micrófono y al terminar se le pone el trozo de la nota ([Mp4DeLaReunion.ponerSonido]).
 *
 * En las dos, **la nota de voz sale siempre** y se pasa a texto al terminar, como la clase; las fotos,
 * a la resolución entera de la cámara y en JPEG ([FotoDeLaCamara]), entran como capturas con
 * `fuente: "camara"`, y los clips en `clips` de la nota. Ver [Presencial.mensajes].
 *
 * ## Grabar sin perder nada (28-oct-2026)
 *
 * Todo lo que se hace se apunta en el acto en su diario ([DiarioEnMarcha]): cada foto se escribe en su
 * carpeta, cada clip y cada trozo del vídeo con su minuto, y el reloj de pared late cada 5 s. Si la
 * reunión no llega a guardarse, al volver a abrir PixPin se ofrece recuperarla ([RecuperarGrabacion]).
 * Ver `docs/grabar-sin-perder-nada-android.md`.
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
        private const val ACCION_CAPTURA = "com.forge.pixpin.action.REUNION_CAPTURA"
        private const val ACCION_PANTALLA = "com.forge.pixpin.action.REUNION_PANTALLA"
        private const val EL_CODIGO = "codigo_pantalla"
        private const val LOS_DATOS = "datos_pantalla"
        private const val EL_PERFIL = "perfil_video"
        private const val CANAL = "pixpin_reunion"
        private const val AVISO = 21

        /** Lo que se recuerda: la cámara elegida, como el PC. */
        private const val PREFS = "grabar_clase"
        private const val CAMARA = "camara_presencial"

        /** Una sola grabación a la vez: dos se pelearían por el micrófono. */
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
         * **Una captura de la pantalla** (29-oct-2026, la grabación única): la pantalla entera al instante o,
         * con [cambiarZona], el recorte. [retrasoMs]: lo que se espera antes (desde la pantalla completa de
         * la grabación, a que se vaya y se vea lo de debajo).
         */
        fun captura(cambiarZona: Boolean = false, retrasoMs: Long = 0) { vivo?.capturaLuego(cambiarZona, retrasoMs) }

        /** **Abre la cámara** para ver la mirilla (en la pantalla completa, «Ver la cámara»). */
        fun abrirLaCamara() { vivo?.let { s -> s.scope.launch { s.asegurarLaCamara() } } }

        /** **Descarta** la grabación entera (borra lo grabado). */
        fun descartar() { vivo?.descartar() }

        /** **Enciende o apaga el vídeo de la pantalla**; el audio sigue siempre (29-oct-2026). */
        fun pantalla() { vivo?.alternarLaPantalla() }

        /**
         * **El permiso de la pantalla recién concedido** (lo llama [PermisoDePantallaActivity] con su
         * pantalla aún delante: Android 14 solo deja añadir el tipo `mediaProjection` así).
         */
        fun conPermisoDePantalla(codigo: Int, datos: Intent) { vivo?.empezarLaPantalla(codigo, datos) }

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

        /**
         * Empieza la grabación en el chat [proyecto] (null, el general). Con la app a la vista. Con [datos]
         * (el permiso de la pantalla recién concedido, [codigo] su resultado) graba también el vídeo de la
         * pantalla desde el principio, con la calidad [perfil].
         */
        fun empezar(
            context: Context, proyecto: String?, forma: Presencial.Forma, calidad: Presencial.Calidad, burbuja: Boolean,
            codigo: Int = 0, datos: Intent? = null, perfil: PerfilDeVideo = PerfilDeVideo.NORMAL
        ) {
            context.startForegroundService(
                Intent(context, ReunionPresencialService::class.java)
                    .putExtra(EL_PROYECTO, proyecto)
                    .putExtra(LA_FORMA, forma.clave)
                    .putExtra(LA_CALIDAD, calidad.clave)
                    .putExtra(LA_BURBUJA, burbuja)
                    .putExtra(EL_CODIGO, codigo)
                    .putExtra(EL_PERFIL, perfil.clave)
                    .apply { if (datos != null) putExtra(LOS_DATOS, datos) }
            )
        }
    }


    /** Una foto hecha (o una captura de la pantalla, sin [deCamara]): su minuto y su fichero. */
    private class FotoHecha(val ms: Long, val archivo: File, val cuando: Long = 0, val deCamara: Boolean = true)

    /** Un clip: su minuto, lo que dura y su MP4 (null en la forma de vídeo: se recorta al terminar). */
    private class ClipHecho(val ms: Long, val duracionMs: Long, val archivo: File?)

    /**
     * Un tramo del vídeo de la reunión: su fichero, el minuto de la nota en que empezó y su grupo (los
     * trozos de un mismo tamaño se juntan al terminar; ver [TrozosDeVideo]).
     */
    private class TramoGrabado(val archivo: File, val desdeMs: Long, val grupo: Int = 0)

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
    /** **El diario en el disco** de esta reunión ([DiarioEnMarcha], «Grabar sin perder nada»). */
    private var diario: DiarioEnMarcha? = null
    private var marca = 0L
    private var nombre = ""
    /** El grupo de los trozos que se graban ahora (sube al cambiar de calidad). */
    private var grupo = 0
    private var numeroDeTrozo = 0
    /** El vídeo se cerró antes de terminar (el disco casi lleno): la nota sigue. */
    private var videoCortado = false
    private var avisadoPocoSitio = false
    /** No se pudo poner en primer plano (Android no da el micrófono desde atrás). */
    private var sinPrimerPlano = false

    // --- La pantalla (29-oct-2026, la grabación única) ---

    /** **El vídeo de la pantalla** ([VideoDeLaClase]), sin sonido y en trozos; null si está apagado. */
    private var pantalla: VideoDeLaClase? = null
    private var perfil = PerfilDeVideo.NORMAL
    /** Los trozos del vídeo de la pantalla (grupos desde [Presencial.GRUPO_DE_PANTALLA]). */
    private val tramosDePantalla = mutableListOf<TramoGrabado>()
    private var grupoDePantalla = Presencial.GRUPO_DE_PANTALLA - 1
    private var numeroDePantalla = 0
    private var pantallaDesde = 0L
    /** El cierre del vídeo de la pantalla en curso (se espera al terminar). */
    private var parandoLaPantalla: Job? = null
    /** Los minutos de las capturas hechas con la pantalla grabándose: salen de sus fotogramas al terminar. */
    private val minutosDelVideo = mutableListOf<Long>()
    /** Las capturas de la pantalla ya en el disco. */
    private val capturas = mutableListOf<FotoHecha>()
    private var fotografiando = false
    /** Si la sesión de [CaptureService] la abrió esta grabación (se cierra al acabar). */
    private var abrimosLaSesion = false
    private val zona by lazy { ZonaDeLaCaptura(this) }

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
            // Lo que se hace lo decide [onStartCommand]: si Android volvió a crear el servicio tras
            // matar la app, no es un fallo que decir.
            sinPrimerPlano = true
            return
        }
        registro.currentState = Lifecycle.State.RESUMED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // **Android volvió a crear el servicio** tras matar la app a media reunión (START_STICKY): la
            // de antes quedó en su diario. Se avisa para recuperarla.
            if (!empezado) {
                RecuperarGrabacion.avisarSiHay(this)
                terminado = true
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (sinPrimerPlano && !empezado) {
            toast(getString(R.string.clase_no_arranca))
            terminado = true
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACCION_FOTO -> { foto(); return START_NOT_STICKY }
            ACCION_CLIP -> { clip(); return START_NOT_STICKY }
            ACCION_PAUSA -> { pausa(); return START_NOT_STICKY }
            ACCION_TERMINAR -> { terminar(); return START_NOT_STICKY }
            ACCION_CAPTURA -> { captura(cambiarZona = false); return START_NOT_STICKY }
            ACCION_PANTALLA -> { alternarLaPantalla(); return START_NOT_STICKY }
        }
        if (empezado || terminado) return START_STICKY
        empezado = true
        enMarcha = true
        proyecto = intent.getStringExtra(EL_PROYECTO)
        forma = Presencial.Forma.de(intent.getStringExtra(LA_FORMA))
        calidad = Presencial.Calidad.de(intent.getStringExtra(LA_CALIDAD))
        val conBurbuja = intent.getBooleanExtra(LA_BURBUJA, true)
        perfil = PerfilDeVideo.deClave(intent.getStringExtra(EL_PERFIL))
        @Suppress("DEPRECATION")
        val datos = intent.getParcelableExtra<Intent>(LOS_DATOS)
        val codigo = intent.getIntExtra(EL_CODIGO, 0)
        scope.launch { arrancar(conBurbuja, datos?.let { codigo to it }) }
        // STICKY: si Android mata la app, al volver a crear el servicio se avisa de lo que quedó.
        return START_STICKY
    }

    // --- Grabar sin perder nada ---------------------------------------------------------------

    /**
     * **Empieza el vídeo de la sala** en [mp4], sin sonido (la nota lo graba aparte, con su respaldo) y
     * **en trozos** ([CamaraDeLaReunion.empezarVideo]): cada trozo cerrado queda entero en el disco.
     */
    private suspend fun empezarElVideo(cam: CamaraDeLaReunion, mp4: File, alto: Int, desdeMs: Long): Boolean {
        val g = grupo
        val bien = cam.empezarVideo(
            mp4, alto, conSonido = false,
            siguienteTrozo = { File(carpeta, "reunion_${marca}_${++numeroDeTrozo}.mp4") },
            alCambiarDeTrozo = { cerrado, nuevo -> alCambiarDeTrozo(cerrado, nuevo, g) }
        )
        if (bien) {
            tramos += TramoGrabado(mp4, desdeMs, g)
            diario?.cambiar { empezarTrozo(desdeMs, mp4.absolutePath, g) } ?: run { pendienteDelDiario += Triple(desdeMs, mp4, g) }
        }
        return bien
    }

    /** Los trozos que empezaron antes de abrir el diario (el vídeo arranca antes que la nota). */
    private val pendienteDelDiario = mutableListOf<Triple<Long, File, Int>>()

    private fun alCambiarDeTrozo(cerrado: File, nuevo: File?, g: Int) {
        val ms = cronometro?.llevadoMs() ?: 0
        val desde = tramos.lastOrNull { it.archivo == cerrado }?.desdeMs ?: ms
        diario?.cambiar {
            cerrarTrozo(cerrado.absolutePath, ms - desde)
            if (nuevo != null) empezarTrozo(ms, nuevo.absolutePath, g)
        }
        if (nuevo != null) tramos += TramoGrabado(nuevo, ms, g)
        else {
            // El teléfono no aceptó el trozo siguiente: el vídeo acaba aquí, la nota sigue.
            videoCortado = true
            avisar(getString(R.string.sin_perder_video_cortado))
        }
    }

    /** Por dónde va la nota de verdad, o el cronómetro. */
    private fun msDeLaNota(): Long = grabador?.llevadoMs() ?: cronometro?.llevadoMs() ?: 0

    /** **Un latido del diario**; si apunta un hueco (Android congeló la app), se dice. */
    private fun latir() {
        val c = cronometro ?: return
        val hueco = diario?.latir(msDeLaNota(), c.enPausa) ?: return
        avisar(getString(R.string.sin_perder_hueco, hueco))
    }

    /** **La guarda del disco**: poco sitio, se avisa; casi nada, se cierra el vídeo y la nota sigue. */
    private fun mirarElDisco() {
        when (diario?.espacio()) {
            GrabacionAMedias.Espacio.POCO -> if (!avisadoPocoSitio) {
                avisadoPocoSitio = true
                avisar(getString(R.string.sin_perder_poco_sitio))
            }
            GrabacionAMedias.Espacio.CRITICO -> {
                if (pantalla != null) pararLaPantalla(getString(R.string.sin_perder_disco_lleno))
                val cam = camara
                if (forma == Presencial.Forma.VIDEO && cam != null && cam.grabando && !videoCortado && estado.value.clipDesde == null) {
                    videoCortado = true
                    scope.launch {
                        val ultimo = tramos.lastOrNull()
                        cam.pararVideo()
                        val ms = cronometro?.llevadoMs() ?: 0
                        ultimo?.let { t -> diario?.cambiar { cerrarTrozo(t.archivo.absolutePath, ms - t.desdeMs) } }
                        avisar(getString(R.string.sin_perder_disco_lleno))
                    }
                }
            }
            else -> Unit
        }
    }

    /**
     * **La cámara, cuando hace falta** (29-oct-2026, la grabación única): ya no se abre al empezar (en una
     * clase por pantalla no se usa, y abierta gasta y enciende el punto verde de Android), sino con la
     * primera foto o el primer clip, o al empezar si se graba el vídeo de la sala. El servicio ya está en
     * primer plano con el tipo `camera`, así que Android deja abrirla con la app fuera.
     */
    private suspend fun asegurarLaCamara(): CamaraDeLaReunion? {
        camara?.let { return it }
        if (!conCamara || terminado) return null
        val a = abriendo ?: scope.async { abrirLaCamara() }.also { abriendo = it }
        return a.await()
    }

    private var abriendo: Deferred<CamaraDeLaReunion?>? = null

    private suspend fun abrirLaCamara(): CamaraDeLaReunion? {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val cam = CamaraDeLaReunion(this, this)
        cam.giro = giroDeLaPantalla()
        val abierta = cam.abrir(runCatching { prefs.getString(CAMARA, null) }.getOrNull())
        abriendo = null
        if (!abierta || terminado) {
            cam.cerrar()
            if (!abierta) avisar(getString(R.string.reunion_sin_camara), fuera = true)
            return null
        }
        camara = cam
        cam.camaraId?.let { id -> runCatching { prefs.edit().putString(CAMARA, id).apply() } }
        if (orientacion == null) vigilarElGiro()
        elegirMirilla()
        return cam
    }

    private suspend fun arrancar(conBurbuja: Boolean, permisoDePantalla: Pair<Int, Intent>?) {
        val cam = if (forma == Presencial.Forma.VIDEO) asegurarLaCamara() else null
        val abierta = cam != null
        if (terminado) {
            // Se terminó mientras se abría la cámara: no hay nada grabado.
            cam?.cerrar()
            return
        }
        val ahora = System.currentTimeMillis()
        marca = ahora
        // «Reunión …» con el vídeo de la sala, como el PC; si no, «Clase …» (la grabación de siempre).
        nombre = getString(if (forma == Presencial.Forma.VIDEO) R.string.reunion_nombre else R.string.clase_nombre, GrabarClase.fechaDelNombre(ahora))
        var empezoElVideo = false
        // **El disco antes de empezar** (como el PC): con menos de 500 MB, el vídeo ni empieza.
        val sitio = GrabacionAMedias.espacio(DiarioEnMarcha.libres(this))
        if (forma == Presencial.Forma.VIDEO && abierta && sitio != GrabacionAMedias.Espacio.CRITICO) {
            val mp4 = File(carpeta, "reunion_${ahora}_${++numeroDeTrozo}.mp4")
            empezoElVideo = empezarElVideo(cam!!, mp4, Presencial.altoDelVideo(calidad, 0), 0)
            if (!empezoElVideo) mp4.delete()
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
        // **La nota, siempre** (28-oct-2026, «Grabar sin perder nada»): también con el vídeo de la sala,
        // que ya no lleva el micrófono. Con su respaldo WAV, por si Android mata la app.
        val m4a = File(carpeta, "reunion_$ahora.m4a")
        val g = Voz.empezar(this, GuardarLaGrabacion.parteDe(m4a), paraUnaClase = true, respaldo = RespaldoWav.rutaDelRespaldo(m4a))
        if (g == null) {
            if (empezoElVideo) cam?.pararVideo()
            tramos.forEach { it.archivo.delete() }
            tramos.clear()
            GuardarLaGrabacion.parteDe(m4a).delete()
            RespaldoWav.rutaDelRespaldo(m4a).delete()
            toast(getString(R.string.clase_no_arranca))
            terminado = true
            enMarcha = false
            camara?.cerrar()
            stopSelf()
            return
        }
        grabador = g
        nota = m4a
        val d = GrabacionAMedias.Diario.nuevo(ahora, proyecto.orEmpty(), nombre, m4a.absolutePath).apply {
            nombreProyecto = GuardarLaGrabacion.nombreDelChat(this@ReunionPresencialService, proyecto)
            modo = Presencial.Modo.PRESENCIAL.palabra
            microfono = true
            if (empezoElVideo) video = File(carpeta, "reunion_$ahora.mp4").absolutePath
            pendienteDelDiario.forEach { (ms, f, gr) -> empezarTrozo(ms, f.absolutePath, gr) }
        }
        pendienteDelDiario.clear()
        diario = DiarioEnMarcha(this, d)
        val c = GrabarClase.Cronometro()
        cronometro = c
        // **La reunión con Jev** (29-oct-2026): los temas de «Preparar» y la escucha en vivo, si toca.
        ReunionConJev.empezar(this, nombre, proyecto, g, diario) { msDeLaNota() }
        // El PC puede mandar capturas a esta grabación (§13 de la guía del PC). Ver [GrabacionDelMovil].
        GrabacionDelMovil.empezar(this, nombre) { c.llevadoMs() to c.enPausa }
        estado.update {
            it.copy(
                lista = true, conCamara = conCamara, video = forma == Presencial.Forma.VIDEO, mirilla = camara?.conMirilla == true,
                sePuedePantalla = forma != Presencial.Forma.VIDEO
            )
        }
        actualizarAviso()
        if (conBurbuja) ensenarLaBurbuja()
        // **La pantalla desde el principio**, si se pidió al empezar (el permiso ya concedido).
        permisoDePantalla?.let { (codigo, datos) -> empezarLaPantalla(codigo, datos) }
        // El reloj, la onda y, con la calidad automática, el paso a 720p pasada la hora.
        scope.launch {
            var ultimoSegundo = -1L
            while (isActive && !terminado) {
                if (!c.enPausa) picos.anota(Voz.pico(grabador))
                val llevado = c.llevadoMs()
                val s = llevado / 1000
                if (s != ultimoSegundo) {
                    ultimoSegundo = s
                    estado.update { it.copy(segundos = s) }
                    val cam2 = camara
                    if (forma == Presencial.Forma.VIDEO && cam2 != null && !cambiandoDeTramo && cam2.grabando && !videoCortado &&
                        Presencial.tocaBajar(calidad, llevado, cam2.altoDelVideo)
                    ) scope.launch { siguienteTramo() }
                }
                delay(Voz.MS_ENTRE_PICOS)
            }
        }
        // **El latido y el disco** (28-oct-2026, «Grabar sin perder nada»).
        scope.launch {
            var desdeElEspacio = 0L
            while (isActive && !terminado) {
                delay(GrabacionAMedias.CADA_LATIDO_MS)
                if (terminado) break
                latir()
                desdeElEspacio += GrabacionAMedias.CADA_LATIDO_MS
                if (desdeElEspacio >= GrabacionAMedias.CADA_ESPACIO_MS) {
                    desdeElEspacio = 0
                    mirarElDisco()
                }
                if (pantalla != null && !c.enPausa && c.llevadoMs() - pantallaDesde >= TrozosDeVideo.TROZO_MS) cambiarDeTrozoDePantalla()
            }
        }
    }

    override fun onDestroy() {
        // Cerrar no es tirar: si el sistema nos para a media reunión, se guarda.
        if (!terminado && empezado) terminar()
        quitarLaBurbuja()
        zona.quitar()
        orientacion?.disable()
        enMarcha = false
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
        if (!conCamara) {
            avisar(getString(R.string.reunion_sin_camara), fuera = true)
            return
        }
        val ms = Presencial.msDeLaFoto(c.llevadoMs())
        val cuando = System.currentTimeMillis()
        val n = ++numeroDeFoto
        // **En la carpeta del diario, en el acto** (28-oct-2026): si la app muere, la foto está.
        val d = diario
        val f = if (d != null) File(d.dir, d.diario.ficheroDeCaptura(n, "jpg"))
        else File(carpeta, "reunion_foto_${System.currentTimeMillis()}_$n.jpg")
        fotosPendientes++
        // **Se nota al momento** (28-oct-2026): el obturador, una vibración y el destello en la mirilla,
        // antes de que la cámara devuelva la foto (eso tarda: es la resolución entera).
        sentir(MediaActionSound.SHUTTER_CLICK)
        estado.update { it.copy(destellos = it.destellos + 1, fotosEnCurso = fotosPendientes) }
        scope.launch {
            try {
                // La primera foto abre la cámara (un momento); las demás salen al instante.
                val cam = asegurarLaCamara()
                val bien = cam != null && cam.foto(f) && withContext(Dispatchers.IO) { FotoDeLaCamara.enSuSitio(f) || f.length() > 0 }
                if (bien) {
                    d?.cambiar { apuntarCaptura(ms, f.name, true) }
                    fotos += FotoHecha(ms, f, cuando)
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
                diario?.cambiar { empezarClip(ms, "") }
                avisar(getString(R.string.reunion_clip_empezado), fuera = true)
                actualizarAviso()
                return
            }
            if (!conCamara) return avisar(getString(R.string.reunion_sin_camara), fuera = true)
            clipCambiando = true
            val mp4 = File(carpeta, "reunion_clip_${System.currentTimeMillis()}.mp4")
            scope.launch {
                try {
                    val cam = asegurarLaCamara()
                    if (cam != null && cam.empezarVideo(mp4, 1080, conSonido = false)) {
                        if (c.enPausa) cam.pausar(true)
                        clipArchivo = mp4
                        diario?.cambiar { empezarClip(ms, mp4.absolutePath) }
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
                diario?.cambiar { cerrarClip("", dura) }
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
                    // **Un clip que no se pudo parar bien no se borra** (28-oct-2026, PC `9b61bda7`): si lo
                    // grabado se abre, entra; si no, se queda en el disco (y en el diario) para recuperarlo.
                    val seAbre = f != null && (bien || GrabacionAMedias.mirar(f).seAbre)
                    if (seAbre) {
                        clips += ClipHecho(desde, dura, f)
                        diario?.cambiar { cerrarClip(f!!.absolutePath, dura) }
                        avisar(getString(R.string.reunion_clip_hecho, GrabarClase.minuto(desde)), fuera = true)
                    } else {
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
        pantalla?.pausar(p)
        GrabacionDelMovil.pausa(this)
        // La pausa, en el reloj de pared en el acto.
        diario?.latir(msDeLaNota(), p)
        estado.update { it.copy(enPausa = p) }
        sentir(null)
        avisar(getString(if (p) R.string.clase_en_pausa else R.string.reunion_sigue))
        actualizarAviso()
    }

    // --- La pantalla: capturas y vídeo (29-oct-2026, la grabación única) -----------------------

    /** Los tipos del servicio en primer plano: el micrófono siempre, la cámara si hay permiso y la pantalla si se graba. */
    private fun tipos(conLaPantalla: Boolean): Int =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (conCamara) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
            (if (conLaPantalla) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)

    /** Una captura ahora o dentro de [retrasoMs], con el minuto de ahora (el del toque). */
    fun capturaLuego(cambiarZona: Boolean, retrasoMs: Long) {
        val ms = cronometro?.llevadoMs() ?: return
        if (retrasoMs <= 0) captura(cambiarZona, ms) else scope.launch { delay(retrasoMs); captura(cambiarZona, ms) }
    }

    /**
     * **Una captura de la pantalla** (como la barra de la clase del PC): la pantalla entera al instante y,
     * con [cambiarZona], el recorte. Con la pantalla grabándose, el permiso de Android da para un solo
     * `VirtualDisplay` (el del vídeo): se apunta el minuto y la imagen sale del vídeo al terminar.
     */
    private fun captura(cambiarZona: Boolean, alPulsar: Long? = null) {
        val c = cronometro ?: return
        if (fotografiando || terminado) return
        val ms = alPulsar ?: c.llevadoMs()
        if (pantalla != null) {
            minutosDelVideo += ms
            diario?.cambiar { capturasDelVideo += ms }
            sentir(MediaActionSound.SHUTTER_CLICK)
            estado.update { it.copy(capturas = capturas.size + minutosDelVideo.size, destellos = it.destellos + 1) }
            avisar(getString(R.string.clase_captura_hecha, GrabarClase.minuto(ms)), fuera = true)
            actualizarAviso()
            return
        }
        if (!ProjectionSession.isAlive) abrimosLaSesion = true
        CaptureFlow.conSesion(this) { fotografiar(ms, cambiarZona) }
    }

    private suspend fun fotografiar(ms: Long, cambiarZona: Boolean) {
        if (fotografiando || terminado) return
        fotografiando = true
        try {
            val app = applicationContext as PixPinApp
            val foto = try {
                // Ni la burbuja, ni la barra de la app, ni la bola en la foto.
                burbuja?.isContentVisible = false
                estado.update { it.copy(oculta = true) }
                app.overlayManager.setOverlaysVisible(false)
                // Lo que tarda el compositor en dejar de pintarlas (ver [CaptureFlow.captureNow]).
                delay(150)
                ProjectionSession.grab()
            } finally {
                app.overlayManager.setOverlaysVisible(true)
            }
            if (foto == null) {
                mostrarLosMandos()
                avisar(getString(R.string.clase_sin_zona), fuera = true)
                return
            }
            val elegida = if (cambiarZona) {
                val eleccion = zona.elegir(foto)
                mostrarLosMandos()
                if (eleccion == null || terminado) {
                    foto.recycle()
                    return
                }
                eleccion.zona
            } else {
                mostrarLosMandos()
                null
            }
            // **En la carpeta del diario, en el acto**: si la app muere, la captura está.
            val d = diario
            val n = ++numeroDeFoto
            val fichero = d?.diario?.ficheroDeCaptura(n, "png") ?: "captura-${System.currentTimeMillis()}-$ms.png"
            val dir = d?.dir ?: File(cacheDir, "clase")
            val archivo = withContext(Dispatchers.IO) { ZonaDeLaCaptura.recortarYGuardar(foto, elegida, File(dir, fichero)) }
            if (archivo == null) {
                avisar(getString(R.string.clase_sin_zona), fuera = true)
                return
            }
            d?.cambiar { apuntarCaptura(ms, fichero, false) }
            capturas += FotoHecha(ms, archivo, System.currentTimeMillis(), deCamara = false)
            sentir(MediaActionSound.SHUTTER_CLICK)
            estado.update { it.copy(capturas = capturas.size + minutosDelVideo.size) }
            avisar(getString(R.string.clase_captura_hecha, GrabarClase.minuto(ms)), fuera = true)
            actualizarAviso()
        } finally {
            fotografiando = false
        }
    }

    private fun mostrarLosMandos() {
        burbuja?.isContentVisible = true
        estado.update { it.copy(oculta = false) }
    }

    /**
     * **«Pantalla»: enciende o apaga el vídeo de la pantalla** (29-oct-2026, el usuario: «grabar la pantalla
     * o pausar la grabación, pero se sigue grabando el audio»). El audio va siempre aparte; el vídeo,
     * sin sonido y en trozos, y al terminar se le pone el de la nota. Encenderlo pide el permiso de Android
     * ([PermisoDePantallaActivity]): uno cada vez, que Android 14 no deja guardarlo.
     */
    fun alternarLaPantalla() {
        if (terminado || cronometro == null) return
        if (pantalla != null) return pararLaPantalla(null)
        if (forma == Presencial.Forma.VIDEO) return avisar(getString(R.string.grabar_pantalla_con_sala), fuera = true)
        if (GrabacionAMedias.espacio(DiarioEnMarcha.libres(this)) == GrabacionAMedias.Espacio.CRITICO) {
            return avisar(getString(R.string.sin_perder_sin_sitio_para_video), fuera = true)
        }
        runCatching { startActivity(Intent(this, PermisoDePantallaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { avisar(getString(R.string.clase_sin_video), fuera = true) }
    }

    /** **Empieza el vídeo de la pantalla** con el permiso recién concedido. */
    fun empezarLaPantalla(codigo: Int, datos: Intent) {
        val c = cronometro ?: return
        if (terminado || pantalla != null || forma == Presencial.Forma.VIDEO) return
        // Un permiso de la pantalla a la vez: la sesión de las capturas (si la abrió esta grabación) se
        // cierra; mientras se graba, las capturas salen del vídeo.
        if (abrimosLaSesion) {
            CaptureService.stopSession(applicationContext)
            abrimosLaSesion = false
        }
        // Android 14: primero en primer plano con el tipo `mediaProjection`, luego el permiso.
        val enPrimerPlano = runCatching { startForeground(AVISO, aviso(), tipos(true)) }.isSuccess
        val ms = c.llevadoMs()
        val g = ++grupoDePantalla
        val trozo = File(carpeta, "reunion_${marca}_p${++numeroDePantalla}.mp4")
        val v = if (enPrimerPlano) VideoDeLaClase.empezar(this, codigo, datos, trozo, perfil) {
            // El sistema cortó la pantalla (el chip, bloquear): el vídeo acaba ahí y el audio sigue.
            scope.launch { pararLaPantalla(getString(R.string.sin_perder_video_cortado)) }
        } else null
        if (v == null) {
            trozo.delete()
            runCatching { startForeground(AVISO, aviso(), tipos(false)) }
            avisar(getString(R.string.clase_sin_video), fuera = true)
            return
        }
        if (c.enPausa) v.pausar(true)
        pantalla = v
        pantallaDesde = ms
        tramosDePantalla += TramoGrabado(trozo, ms, g)
        val final = File(carpeta, "reunion_${marca}_pantalla.mp4").absolutePath
        diario?.cambiar {
            if (video == null) video = final
            empezarTrozo(ms, trozo.absolutePath, g)
        }
        sentir(MediaActionSound.START_VIDEO_RECORDING)
        estado.update { it.copy(pantalla = true) }
        avisar(getString(R.string.grabar_pantalla_empieza), fuera = true)
        actualizarAviso()
    }

    /** **Apaga el vídeo de la pantalla** (cierra el trozo en curso); el audio sigue. */
    private fun pararLaPantalla(porQue: String?) {
        val v = pantalla ?: return
        pantalla = null
        val ms = cronometro?.llevadoMs() ?: 0
        val desde = pantallaDesde
        val d = diario
        parandoLaPantalla = (applicationContext as PixPinApp).scope.launch(Dispatchers.IO) {
            runCatching { v.parar() }
            d?.cambiar { cerrarTrozo(v.trozo.absolutePath, ms - desde) }
        }
        estado.update { it.copy(pantalla = false) }
        if (terminado) return
        // Sin la pantalla, el servicio vuelve a sus tipos de siempre (si Android no deja, se queda como está).
        runCatching { startForeground(AVISO, aviso(), tipos(false)) }
        sentir(MediaActionSound.STOP_VIDEO_RECORDING)
        avisar(porQue ?: getString(R.string.grabar_pantalla_parada), fuera = true)
        actualizarAviso()
    }

    /** **El trozo siguiente del vídeo de la pantalla** ([TrozosDeVideo.TROZO_MS]). */
    private fun cambiarDeTrozoDePantalla() {
        val v = pantalla ?: return
        val c = cronometro ?: return
        if (c.enPausa) return
        val desde = c.llevadoMs()
        val g = grupoDePantalla
        val siguiente = File(carpeta, "reunion_${marca}_p${++numeroDePantalla}.mp4")
        val antes = pantallaDesde
        pantallaDesde = desde
        scope.launch {
            val viejo = v.trozo
            val bien = withContext(Dispatchers.IO) { v.cambiarDeTrozo(siguiente) }
            if (bien == null) {
                if (pantalla === v) pantallaDesde = antes
                return@launch
            }
            tramosDePantalla += TramoGrabado(siguiente, desde, g)
            diario?.cambiar {
                cerrarTrozo(viejo.absolutePath, desde - antes)
                empezarTrozo(desde, siguiente.absolutePath, g)
            }
        }
    }

    /**
     * **Descartar** (desde la pantalla completa, con su confirmación): lo único que borra lo grabado. Como
     * el ✕ dos veces de la barra de la clase.
     */
    private fun descartar() {
        if (terminado) return
        if (!empezado || cronometro == null) return terminar()
        terminado = true
        enMarcha = false
        GrabacionDelMovil.tirar(this)
        quitarLaBurbuja()
        zona.quitar()
        orientacion?.disable()
        ReunionConJev.tirar()
        val g = grabador
        grabador = null
        val v = pantalla
        pantalla = null
        val cam = camara
        val d = diario
        val app = applicationContext as PixPinApp
        val cerrarLaSesion = abrimosLaSesion
        val sobran = (tramos + tramosDePantalla).map { it.archivo } + clips.mapNotNull { it.archivo } + listOfNotNull(clipArchivo)
        estado.update { it.copy(terminando = true) }
        app.scope.launch(Dispatchers.Main.immediate) {
            if (cam?.grabando == true) runCatching { cam.pararVideo() }
            cam?.cerrar()
            withContext(Dispatchers.IO) {
                Voz.parar(g)
                runCatching { v?.parar() }
                d?.cerrar(borrar = false)
                d?.let { GrabacionAMedias.descartar(it.dir, it.diario) }
                sobran.forEach { it.delete() }
                nota?.let { GuardarLaGrabacion.parteDe(it).delete(); RespaldoWav.rutaDelRespaldo(it).delete(); it.delete() }
            }
            if (cerrarLaSesion) CaptureService.stopSession(app)
            toast(getString(R.string.clase_tirada))
            estado.update { EstadoDeLaReunion() }
            stopSelf()
        }
    }

    /**
     * **Pasada la hora, a 720p** (la calidad automática): se cierra el tramo de 1080p y se abre otro, de
     * otro grupo (no se puede pegar con el de antes: otro tamaño). Desde el 28-oct-2026 el cronómetro
     * no se para: la nota sigue grabando (lleva el sonido) y el tramo nuevo va en su minuto.
     */
    private suspend fun siguienteTramo() {
        val cam = camara ?: return
        val c = cronometro ?: return
        if (cambiandoDeTramo || terminado || videoCortado) return
        cambiandoDeTramo = true
        try {
            val ultimo = tramos.lastOrNull()
            cam.pararVideo()
            val desde = c.llevadoMs()
            ultimo?.let { t -> diario?.cambiar { cerrarTrozo(t.archivo.absolutePath, desde - t.desdeMs) } }
            grupo++
            val mp4 = File(carpeta, "reunion_${marca}_${++numeroDeTrozo}.mp4")
            if (empezarElVideo(cam, mp4, Presencial.altoDelVideo(calidad, desde), desde)) {
                if (c.enPausa) cam.pausar(true)
            } else {
                mp4.delete()
                videoCortado = true
                avisar(getString(R.string.reunion_sin_video))
            }
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
        GrabacionDelMovil.acabar(this)
        sentir(MediaActionSound.STOP_VIDEO_RECORDING)
        estado.update { it.copy(terminando = true) }
        quitarLaBurbuja()
        zona.quitar()
        orientacion?.disable()
        // El vídeo de la pantalla, cerrado ya (se espera abajo a que acabe de cerrarse).
        pararLaPantalla(null)
        val cerrarLaSesion = abrimosLaSesion
        val app = applicationContext as PixPinApp
        // **El servicio de pasar a texto, arrancado ya** (28-oct-2026): el toque de «Terminar» deja
        // arrancarlo; cuando la reunión esté guardada (segundos después), quizá ya no. Ver [ColaDeTexto].
        val reserva = PasarATextoService.reservar(app, getString(R.string.pasar_a_texto_titulo))
        // La escucha se para en el acto; lo de la reunión va a la nota en cuanto esté (29-oct-2026).
        val laReunion = ReunionConJev.terminar()
        val c = cronometro
        val clipAbierto = estado.value.clipDesde
        val elProyecto = proyecto
        val laForma = forma
        val lasPicos = picos.lista()
        val d = diario
        d?.acabar()
        // En el alcance de la app y no en el del servicio: si el sistema para el servicio a media
        // reunión, esto sigue hasta guardarlo (el del servicio se cancela al destruirse).
        app.scope.launch(Dispatchers.Main.immediate) {
            // Lo que estaba a medias: las fotos que aún no han vuelto y el clip que se grababa.
            withTimeoutOrNull(5_000) { while (fotosPendientes > 0) delay(50) }
            val llevado = c?.llevadoMs() ?: 0
            val cam = camara
            if (clipAbierto != null) {
                val dura = (llevado - clipAbierto).coerceAtLeast(0)
                if (laForma == Presencial.Forma.VIDEO) {
                    clips += ClipHecho(clipAbierto, dura, null)
                    d?.cambiar { cerrarClip("", dura) }
                } else {
                    val f = clipArchivo
                    clipArchivo = null
                    val bien = cam?.pararVideo() == true
                    // Un clip que no se pudo parar bien no se borra (PC `9b61bda7`): entra si se abre.
                    if (f != null && (bien || GrabacionAMedias.mirar(f).seAbre)) {
                        clips += ClipHecho(clipAbierto, dura, f)
                        d?.cambiar { cerrarClip(f.absolutePath, dura) }
                    }
                }
            }
            if (laForma == Presencial.Forma.VIDEO && cam?.grabando == true) {
                val ultimo = tramos.lastOrNull()
                cam.pararVideo()
                ultimo?.let { t -> d?.cambiar { cerrarTrozo(t.archivo.absolutePath, llevado - t.desdeMs) } }
            }
            cam?.cerrar()
            parandoLaPantalla?.join()
            val bienLaNota = Voz.parar(grabador)
            grabador = null
            val lasFotos = (fotos + capturas).sortedBy { it.ms }.map { GuardarLaGrabacion.Imagen(it.ms, it.archivo, it.cuando, it.deCamara) }
            val losDePantalla = tramosDePantalla.map { GuardarLaGrabacion.TrozoDeGrupo(it.archivo, it.desdeMs, it.grupo) }
            val minutos = minutosDelVideo.toList()
            val losClips = clips.sortedBy { it.ms }.map { GuardarLaGrabacion.ClipHecho(it.ms, it.duracionMs, it.archivo) }
            val losTramos = tramos.map { GuardarLaGrabacion.TrozoDeGrupo(it.archivo, it.desdeMs, it.grupo) }
            val laNota = nota
            val elNombre = nombre
            val laMarca = marca
            app.scope.launch(Dispatchers.IO) {
                val r = runCatching {
                    val m4a = laNota?.let { GuardarLaGrabacion.cerrarLaNota(it, bienLaNota) }
                    val partes = GuardarLaGrabacion.juntarVideo(losTramos, m4a) { File(carpeta, "reunion_${laMarca}_video$it.mp4") }
                    // **La pantalla** (29-oct-2026): sus partes con el sonido de la nota, y las capturas de sus fotogramas.
                    val dePantalla = GuardarLaGrabacion.juntarVideo(losDePantalla, m4a) { File(carpeta, "reunion_${laMarca}_pantalla$it.mp4") }
                    val sacadas = GuardarLaGrabacion.fotogramasDePartes(dePantalla, minutos, File(cacheDir, "clase"))
                    val r = GuardarLaGrabacion.reunion(
                        app, m4a, null, elProyecto, elNombre, System.currentTimeMillis(), lasPicos, lasFotos + sacadas, losClips, partes, d?.reloj(),
                        pantalla = dePantalla
                    )
                    sacadas.forEach { it.archivo.delete() }
                    notaGuardada = r.notaId
                    val notaId = r.notaId
                    if (notaId != null && laReunion != null) runCatching { ReunionConJev.guardar(app, notaId, laReunion) }
                        .onFailure { android.util.Log.w("PixPinReunion", "los temas de la reunión no entraron en la nota", it) }
                    when (r.que) {
                        GuardarLaGrabacion.Que.LISTA, GuardarLaGrabacion.Que.SIN_NOTA -> {
                            laNota?.let { RespaldoWav.rutaDelRespaldo(it).delete(); GuardarLaGrabacion.parteDe(it).delete() }
                            if (r.que == GuardarLaGrabacion.Que.SIN_NOTA) m4a?.delete()
                            d?.cerrar(borrar = true)
                        }
                        GuardarLaGrabacion.Que.MUY_CORTA -> {
                            d?.cerrar(borrar = false)
                            d?.let { GrabacionAMedias.descartar(it.dir, it.diario) }
                            m4a?.delete()
                        }
                        GuardarLaGrabacion.Que.NADA -> d?.cerrar(borrar = false)
                    }
                    r
                }.onFailure {
                    android.util.Log.e("PixPinReunion", "la reunión no se guardó: queda en su diario", it)
                    d?.cerrar(borrar = false)
                }.getOrNull()
                val dicho = when (r?.que) {
                    GuardarLaGrabacion.Que.LISTA -> getString(
                        if (r!!.conTexto) R.string.reunion_lista_con_texto else R.string.reunion_lista_sin_texto, r.capturas, r.clips
                    )
                    GuardarLaGrabacion.Que.SIN_NOTA -> getString(R.string.reunion_sin_nota)
                    GuardarLaGrabacion.Que.MUY_CORTA -> getString(R.string.voz_muy_corta)
                    else -> getString(R.string.sin_perder_quedo_para_recuperar)
                }
                withContext(Dispatchers.Main) {
                    if (cerrarLaSesion) CaptureService.stopSession(app)
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
     * **La burbuja** (28-oct-2026; reordenada el 29-oct-2026, el usuario: «los iconos están muy
     * aglomerados, organízalos de una mejor forma»). Dos filas con aire:
     *
     * - arriba, el asa para moverla, **el reloj** (un toque pausa o sigue, mantenerlo termina: ver
     *   [MandosDeLaGrabacion.Reloj]), la pantalla completa ([ReunionEnCursoActivity]) y «☑ 2/5» si hay temas;
     * - debajo, **Foto, Captura, Pantalla y Clip**, cada uno con su nombre ([MandosDeLaGrabacion.Acciones]).
     *
     * Grabando un clip, encima, el cuadro con la mirilla (lo que se graba, en vivo). Con PixPin delante no
     * se pinta: manda la barra de la app ([ReunionEnLaApp]). Mientras se hace una captura, tampoco.
     */
    @Composable
    private fun LaBurbuja() {
        val e by estado.collectAsState()
        val delante by appDelante.collectAsState()
        val fondo = Color(0xEE1E1E22)
        val claro = Color.White
        if (!delante) Column(horizontalAlignment = Alignment.Start) {
            if (e.mirilla && e.clipDesde != null) {
                Box(
                    Modifier.padding(bottom = 4.dp).size(120.dp, 160.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)
                        .border(3.dp, MandosDeLaGrabacion.ROJO, RoundedCornerShape(16.dp))
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
                }
            }
            Column(Modifier.clip(RoundedCornerShape(22.dp)).background(fondo).padding(horizontal = 4.dp, vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.DragIndicator, contentDescription = null, tint = claro.copy(alpha = 0.6f),
                        modifier = Modifier.size(28.dp).pointerInput(Unit) {
                            detectDragGestures { cambio, d -> cambio.consume(); mover(d.x, d.y) }
                        }
                    )
                    MandosDeLaGrabacion.Reloj(e, claro)
                    Spacer(Modifier.width(4.dp))
                    TemasEnLaBarra.Boton(claro)
                    IconButton(onClick = { ReunionEnCursoActivity.abrir(this@ReunionPresencialService) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Fullscreen, getString(R.string.grabar_pantalla_completa), tint = claro)
                    }
                }
                MandosDeLaGrabacion.Acciones(e, claro)
            }
            e.aviso?.let {
                Text(
                    it, color = claro, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(fondo)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            // Los temas de la reunión y las fichas de Jev (29-oct-2026).
            TemasEnLaBarra.Panel(Modifier.padding(top = 4.dp))
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
            append(
                when {
                    forma == Presencial.Forma.VIDEO -> getString(R.string.reunion_forma_video)
                    e.pantalla -> "● " + getString(R.string.grabar_pantalla)
                    else -> getString(R.string.reunion_forma_audio)
                }
            )
            append(" · ").append(resources.getQuantityString(R.plurals.reunion_fotos, e.fotos, e.fotos))
            if (e.capturas > 0) append(" · ").append(getString(R.string.clase_captura)).append(" ").append(e.capturas)
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
            // **Foto, Captura y Terminar** a la vista (29-oct-2026, la grabación única); desplegada, la pausa,
            // la pantalla y el clip.
            b.addAction(boton(R.drawable.ic_reunion_foto, R.string.reunion_foto, ACCION_FOTO, 1))
            b.addAction(boton(R.drawable.ic_reunion_captura, R.string.clase_captura, ACCION_CAPTURA, 6))
            b.addAction(boton(R.drawable.ic_reunion_terminar, R.string.clase_terminar, ACCION_TERMINAR, 3))
            b.addAction(
                if (e.enPausa) boton(R.drawable.ic_reunion_seguir, R.string.clase_seguir, ACCION_PAUSA, 5)
                else boton(R.drawable.ic_reunion_pausa, R.string.clase_pausa, ACCION_PAUSA, 5)
            )
            if (e.sePuedePantalla) b.addAction(boton(R.drawable.ic_reunion_pantalla, R.string.grabar_pantalla, ACCION_PANTALLA, 7))
            else b.addAction(
                if (desde != null) boton(R.drawable.ic_reunion_parar_clip, R.string.reunion_parar_clip, ACCION_CLIP, 2)
                else boton(R.drawable.ic_reunion_clip, R.string.reunion_clip, ACCION_CLIP, 2)
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
    val guardada: String? = null,
    /** **Las capturas de la pantalla** hechas (29-oct-2026, la grabación única). */
    val capturas: Int = 0,
    /** Si se está grabando el vídeo de la pantalla. */
    val pantalla: Boolean = false,
    /** Si se puede grabar la pantalla (no con el vídeo de la sala). */
    val sePuedePantalla: Boolean = true,
    /** Haciendo una captura: los mandos se esconden para no salir en ella. */
    val oculta: Boolean = false
)
