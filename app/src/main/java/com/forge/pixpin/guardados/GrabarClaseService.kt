package com.forge.pixpin.guardados

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.audio.Grabador
import com.forge.pixpin.sincro.GrabacionDelMovil
import com.forge.pixpin.capture.CaptureFlow
import com.forge.pixpin.capture.CaptureService
import com.forge.pixpin.capture.ProjectionSession
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * **Grabar una clase o una reunión: el micrófono y la barra flotante** (25-oct-2026, como
 * `grabar_clase.rs` del PC). Ver [GrabarClase] para lo puro y el formato.
 *
 * ## Un servicio de micrófono, y la pantalla por el camino de siempre
 *
 * Mientras se graba uno está en Meet, en Zoom o en las diapositivas, no en PixPin: el micrófono
 * solo sigue abierto con un servicio en primer plano **de tipo `microphone`**, y Android 14 solo
 * deja arrancarlo con la app a la vista. Por eso lo arranca [GrabarClaseActivity] y no la barra.
 *
 * Las capturas **no abren otra proyección**: van por [CaptureFlow.conSesion], que pide el permiso
 * una vez y lo mantiene en [CaptureService] (tipo `mediaProjection`), como la bola. Así se respeta
 * la regla de Android 14 de un consentimiento por sesión, y si la bola ya tenía la sesión abierta
 * no se vuelve a preguntar. Si la abrió esta grabación, se cierra al acabar.
 *
 * ## La pantalla entera al instante, y el recorte para un trozo (25-oct-2026, como el PC)
 *
 * En el modo «Audio y capturas», «Captura» guarda la pantalla entera sin preguntar nada; el
 * botón del recorte congela la pantalla y deja arrastrar la zona, solo para esa captura. La barra y la bola se esconden un instante para no salir en la foto, como en
 * [CaptureFlow.captureNow]. El minuto de cada captura es el **de cuando se pulsó**, sin pausas
 * ([GrabarClase.Cronometro]).
 *
 * ## Con vídeo de la pantalla (25-oct-2026, como el PC desde `20162e9`)
 *
 * Si se pide en [GrabarClaseActivity], el permiso de la pantalla llega aquí, el servicio pasa a
 * `microphone|mediaProjection` y graba [VideoDeLaClase]: la nota se saca del vídeo y las capturas,
 * de sus fotogramas (a pantalla entera; el único `VirtualDisplay` del permiso es del vídeo).
 *
 * ## Al terminar
 *
 * La nota entra en el chat con `capturas` y `grabacion: "clase"`, cada imagen detrás con su
 * minuto en el pie y `enGrabacion`, y la nota se pasa a texto ([MensajesStore.transcribir]).
 * Cerrar no es tirar: si el sistema para el servicio, lo grabado se guarda. Tirar es ✕ dos veces.
 */
class GrabarClaseService : Service() {

    companion object {
        private const val EL_PROYECTO = "proyecto"
        private const val EL_CODIGO = "codigo"
        private const val EL_PERFIL = "perfil_video"
        private const val LOS_DATOS = "datos"
        private const val ACCION_TERMINAR = "com.forge.pixpin.action.CLASE_TERMINAR"
        private const val CANAL = "pixpin_clase"
        private const val AVISO = 11

        /**
         * Una sola a la vez, como el PC: dos grabaciones se pelearían por el micrófono, y las
         * capturas no sabrían a cuál van.
         */
        @Volatile
        var enMarcha = false
            private set

        /**
         * Empieza a grabar para el chat [proyecto] (null, el general). Con la app a la vista. Con
         * [datos] (el permiso de grabar la pantalla recién concedido, [codigo] su resultado) graba
         * además el vídeo de la pantalla ([VideoDeLaClase]).
         */
        fun empezar(
            context: Context, proyecto: String?, codigo: Int = 0, datos: Intent? = null,
            /** La calidad del vídeo (25-oct-2026, como el PC). Ver [PerfilDeVideo]. */
            perfil: PerfilDeVideo = PerfilDeVideo.NORMAL
        ) {
            context.startForegroundService(
                Intent(context, GrabarClaseService::class.java)
                    .putExtra(EL_PROYECTO, proyecto)
                    .putExtra(EL_CODIGO, codigo)
                    .putExtra(EL_PERFIL, perfil.clave)
                    .apply { if (datos != null) putExtra(LOS_DATOS, datos) }
            )
        }
    }

    /** Lo que pinta la barra. Los segundos y no los milisegundos: así solo se repinta al cambiar. */
    private data class Barra(
        val segundos: Long = 0,
        val enPausa: Boolean = false,
        val capturas: Int = 0,
        val conZona: Boolean = false,
        val aviso: String? = null,
        val tirarArmado: Boolean = false
    )

    /**
     * Una captura: ya recortada en la caché, o —con vídeo— solo su minuto ([archivo] nulo), que se
     * saca del `.mp4` al terminar.
     */
    private class Hecha(val ms: Long, val archivo: File?)

    /** Lo que sale de elegir la zona: [zona] nula es la pantalla entera. */
    private class Eleccion(val zona: GrabarClase.Zona?)

    private val barra = MutableStateFlow(Barra())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wm by lazy { getSystemService(WindowManager::class.java) }

    private var grabador: Grabador? = null
    private var destino: File? = null
    /** Con vídeo: [destino] es el `.mp4` y la nota se saca de él al terminar. */
    private var conVideo = false
    private var proyecto: String? = null
    private var cronometro: GrabarClase.Cronometro? = null
    private val picos = Picos()
    private val hechas = mutableListOf<Hecha>()
    private var fotografiando = false
    private var abrimosLaSesion = false
    private var terminado = false

    private var ventana: OverlayComposeWindow? = null
    private var lp: WindowManager.LayoutParams? = null
    private var selector: OverlayComposeWindow? = null
    private var avisoJob: Job? = null
    private var tirarJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val canal = NotificationChannel(CANAL, getString(R.string.clase_canal), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        // Con la app a la vista (la arranca [GrabarClaseActivity]): si no, Android 14 no da el
        // micrófono a un servicio y esto lanza. Entonces no se graba nada y se dice.
        val bien = runCatching {
            startForeground(AVISO, aviso(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }.isSuccess
        if (!bien) {
            toast(getString(R.string.clase_no_arranca))
            terminado = true
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_TERMINAR) {
            terminar()
            return START_NOT_STICKY
        }
        if (grabador != null || terminado) return START_NOT_STICKY
        proyecto = intent?.getStringExtra(EL_PROYECTO)
        val carpeta = MensajesStore(this).carpetaDeAdjuntos()
        val ahora = System.currentTimeMillis()
        var f = File(carpeta, "clase_$ahora.m4a")
        @Suppress("DEPRECATION")
        val datos = intent?.getParcelableExtra<Intent>(LOS_DATOS)
        var g: Grabador? = null
        if (datos != null) {
            // Android 14: primero en primer plano con el tipo `mediaProjection`, luego el permiso.
            val enPrimerPlano = runCatching {
                startForeground(
                    AVISO, aviso(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }.isSuccess
            val mp4 = File(carpeta, "clase_$ahora.mp4")
            g = if (enPrimerPlano) {
                VideoDeLaClase.empezar(
                    this, intent.getIntExtra(EL_CODIGO, 0), datos, mp4,
                    PerfilDeVideo.deClave(intent.getStringExtra(EL_PERFIL))
                ) {
                    // El sistema cortó la pantalla (el chip, bloquear): se guarda lo que haya.
                    scope.launch { terminar() }
                }
            } else null
            if (g != null) {
                f = mp4
                conVideo = true
            } else {
                toast(getString(R.string.clase_sin_video))
            }
        }
        if (g == null) g = Voz.empezar(this, f, paraUnaClase = true)
        if (g == null) {
            f.delete()
            toast(getString(R.string.clase_no_arranca))
            terminado = true
            stopSelf()
            return START_NOT_STICKY
        }
        grabador = g
        destino = f
        val c = GrabarClase.Cronometro()
        cronometro = c
        enMarcha = true
        // El PC puede mandar capturas a esta grabación (§13 de la guía del PC). Ver [GrabacionDelMovil].
        GrabacionDelMovil.empezar(this, getString(R.string.clase_nombre, GrabarClase.fechaDelNombre(ahora))) { c.llevadoMs() to c.enPausa }
        ensenarLaBarra()
        // El mismo bucle que el chat para la onda; y el reloj de la barra.
        scope.launch {
            while (isActive && !terminado) {
                if (!c.enPausa) picos.anota(Voz.pico(grabador))
                val s = c.llevadoMs() / 1000
                barra.update { if (it.segundos == s) it else it.copy(segundos = s) }
                delay(Voz.MS_ENTRE_PICOS)
            }
        }
        // NOT_STICKY: un servicio recreado sin la grabación no tendría nada que hacer.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Cerrar no es tirar (como el PC): si el sistema nos para a media clase, se guarda.
        if (!terminado && grabador != null) terminar()
        quitarVentanas()
        enMarcha = false
        scope.cancel()
        super.onDestroy()
    }

    // --- Las acciones de la barra ---------------------------------------------------------

    private fun captura(cambiarZona: Boolean) {
        val c = cronometro ?: return
        if (fotografiando || terminado) return
        // El minuto es el de cuando se pulsa, no el de cuando acaba de elegirse la zona.
        val ms = c.llevadoMs()
        if (conVideo) {
            // Con vídeo la imagen sale del propio `.mp4` al terminar (ver [VideoDeLaClase]).
            hechas += Hecha(ms, null)
            barra.update { it.copy(capturas = hechas.size) }
            avisar(getString(R.string.clase_captura_hecha, GrabarClase.minuto(ms)))
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
                ventana?.isContentVisible = false
                app.overlayManager.setOverlaysVisible(false)
                // Lo que tarda el compositor en dejar de pintar la barra (ver [CaptureFlow.captureNow]).
                delay(90)
                ProjectionSession.grab()
            } finally {
                app.overlayManager.setOverlaysVisible(true)
            }
            if (foto == null) {
                ventana?.isContentVisible = true
                avisar(getString(R.string.clase_sin_zona))
                return
            }
            // **«Captura», la pantalla entera al instante** (25-oct-2026, el modo «Audio y capturas»
            // del PC): sin pedir zona. El recorte de la barra elige un trozo para esa sola captura.
            val elegida = if (cambiarZona) {
                val eleccion = elegirZona(foto)
                ventana?.isContentVisible = true
                if (eleccion == null || terminado) {
                    foto.recycle()
                    return
                }
                eleccion.zona
            } else {
                ventana?.isContentVisible = true
                null
            }
            val archivo = withContext(Dispatchers.IO) { recortarYGuardar(foto, elegida, ms) }
            if (archivo == null) {
                avisar(getString(R.string.clase_sin_zona))
                return
            }
            hechas += Hecha(ms, archivo)
            barra.update { it.copy(capturas = hechas.size) }
            avisar(getString(R.string.clase_captura_hecha, GrabarClase.minuto(ms)))
        } finally {
            fotografiando = false
        }
    }

    /** El recorte de [foto] a la zona, en PNG en la caché. Si la zona ya no cabe (giró), la pantalla. */
    private fun recortarYGuardar(foto: Bitmap, zona: GrabarClase.Zona?, ms: Long): File? = runCatching {
        val r = GrabarClase.recorte(zona, foto.width, foto.height)
            ?: GrabarClase.recorte(null, foto.width, foto.height)
            ?: return null
        val recorte = if (r.x == 0 && r.y == 0 && r.ancho == foto.width && r.alto == foto.height) foto
        else Bitmap.createBitmap(foto, r.x, r.y, r.ancho, r.alto)
        val carpeta = File(cacheDir, "clase").apply { mkdirs() }
        val f = File(carpeta, "captura-${System.currentTimeMillis()}-$ms.png")
        f.outputStream().use { recorte.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (recorte !== foto) recorte.recycle()
        foto.recycle()
        f
    }.getOrNull()

    private fun pausa() {
        val c = cronometro ?: return
        if (terminado) return
        val p = !c.enPausa
        c.pausar(p)
        grabador?.pausar(p)
        GrabacionDelMovil.pausa(this)
        barra.update { it.copy(enPausa = p) }
        if (p) avisar(getString(R.string.clase_en_pausa))
    }

    /** ✕ una vez avisa; otra vez dentro de [GrabarClase.TIRAR_DURA_MS], tira. Como el PC. */
    private fun tirar() {
        if (terminado) return
        if (barra.value.tirarArmado) {
            descartar()
            return
        }
        barra.update { it.copy(tirarArmado = true) }
        avisar(getString(R.string.clase_tirar))
        tirarJob?.cancel()
        tirarJob = scope.launch {
            delay(GrabarClase.TIRAR_DURA_MS)
            barra.update { it.copy(tirarArmado = false) }
        }
    }

    private fun descartar() {
        terminado = true
        enMarcha = false
        GrabacionDelMovil.tirar(this)
        quitarVentanas()
        val g = grabador
        grabador = null
        val f = destino
        val sueltas = hechas.toList()
        hechas.clear()
        val app = applicationContext as PixPinApp
        app.scope.launch(Dispatchers.IO) {
            Voz.parar(g)
            f?.delete()
            sueltas.forEach { it.archivo?.delete() }
        }
        if (abrimosLaSesion) CaptureService.stopSession(app)
        toast(getString(R.string.clase_tirada))
        stopSelf()
    }

    /** Para el micrófono y mete la clase en el chat, fuera del servicio (que se va enseguida). */
    private fun terminar() {
        if (terminado) return
        terminado = true
        enMarcha = false
        GrabacionDelMovil.acabar(this)
        quitarVentanas()
        val g = grabador
        grabador = null
        val archivo = destino
        val lasPicos = picos.lista()
        val lasHechas = hechas.toList()
        hechas.clear()
        val elProyecto = proyecto
        val app = applicationContext as PixPinApp
        val cerrarLaSesion = abrimosLaSesion
        val elVideo = conVideo
        // **El servicio de pasar a texto, arrancado ya** (28-oct-2026): ahora Android deja (es el toque
        // de «Terminar»); dentro de unos segundos, con la clase ya guardada, quizá no. Ver [ColaDeTexto].
        val reserva = PasarATextoService.reservar(app, getString(R.string.pasar_a_texto_titulo))
        app.scope.launch(Dispatchers.IO) {
            val bien = Voz.parar(g)
            val dicho = runCatching {
                if (elVideo && archivo != null) {
                    // La nota sale del vídeo, y las capturas, de sus fotogramas.
                    val m4a = File(archivo.parentFile, archivo.nameWithoutExtension + ".m4a")
                    val conAudio = bien && VideoDeLaClase.sacarElAudio(archivo, m4a)
                    val sacadas = if (bien) fotogramas(archivo, lasHechas) else emptyList()
                    guardar(app, conAudio, m4a, elProyecto, lasPicos, sacadas, video = archivo.takeIf { bien && it.exists() })
                } else {
                    guardar(app, bien, archivo, elProyecto, lasPicos, lasHechas, video = null)
                }
            }
                .getOrElse { getString(R.string.clase_no_arranca) }
            withContext(Dispatchers.Main) {
                if (cerrarLaSesion) CaptureService.stopSession(app)
                toast(dicho)
                notaGuardada?.let { avisarParaEstudiar(it) }
                // La cola ya cogió la nota (o la cogerá con el servicio aún en pie).
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ PasarATextoService.soltar(reserva) }, 10_000)
                stopSelf()
            }
        }
    }

    /**
     * **Mete la clase en el chat**: la nota y detrás cada captura, enlazadas (como `guardar` del
     * PC). Los identificadores se sacan antes, así que la nota se escribe ya con sus `capturas` y
     * no hay que reescribirla. Devuelve lo que se le dice al usuario.
     */
    private fun guardar(
        context: Context, bien: Boolean, archivo: File?, proyecto: String?,
        picos: List<Int>, hechas: List<Hecha>, video: File?
    ): String {
        val almacen = MensajesStore(context)
        fun soltar() { archivo?.delete(); video?.delete(); hechas.forEach { it.archivo?.delete() }; GrabacionDelMovil.tirar(context) }
        if ((!bien || archivo == null || !archivo.exists()) && video != null && video.length() > 0) {
            // Vídeo sin pista de audio que sacar: el vídeo entra solo, como un archivo más.
            archivo?.delete()
            hechas.forEach { it.archivo?.delete() }
            AlChat.meterArchivo(context, video, GrabarClase.nombreDelVideo(
                context.getString(R.string.clase_nombre, GrabarClase.fechaDelNombre(System.currentTimeMillis()))
            ), "video/mp4", proyecto)
            video.delete()
            GrabacionDelMovil.tirar(context)
            return context.getString(R.string.clase_sin_audio)
        }
        if (!bien || archivo == null || !archivo.exists()) {
            soltar()
            return context.getString(R.string.clase_no_arranca)
        }
        val duracion = Voz.duracion(archivo.absolutePath)
        if (duracion < Voz.MINIMO_MS) {
            soltar()
            return context.getString(R.string.voz_muy_corta)
        }
        val ahora = System.currentTimeMillis()
        val nombre = context.getString(R.string.clase_nombre, GrabarClase.fechaDelNombre(ahora))
        val vozId = UUID.randomUUID().toString()
        notaGuardada = vozId
        // **El vídeo, justo detrás de la nota** (como el PC): se renombra en su sitio, sin copiar
        // un fichero que puede pesar un giga.
        val elVideo = video?.let { v ->
            val nombreDelVideo = GrabarClase.nombreDelVideo(nombre)
            val final = File(v.parentFile, "${ahora}_${nombreConExtension(nombreDelVideo, "mp4")}")
            val ruta = if (v.renameTo(final)) final else v
            Mensaje(
                id = UUID.randomUUID().toString(),
                cuando = ahora + 1,
                clase = Clase.ARCHIVO,
                ruta = ruta.absolutePath,
                nombre = nombreDelVideo,
                bytes = ruta.length(),
                duracionMs = Voz.duracion(ruta.absolutePath),
                proyecto = proyecto,
                enGrabacion = GrabarClase.enGrabacion(vozId, 0)
            )
        }
        val primera = if (elVideo != null) 2 else 1
        val enlazadas = ArrayList<GrabarClase.Enlace>()
        val imagenes = ArrayList<Mensaje>()
        hechas.sortedBy { it.ms }.forEachIndexed { i, h ->
            val origen = h.archivo ?: return@forEachIndexed
            val fichero = "clase-$ahora-${i + 1}.png"
            // Ni se aligera ni se endereza: es una captura de pantalla recién hecha.
            val ruta = almacen.copiarAdjunto(origen, fichero, "png", aligerar = false, enderezar = false)
            origen.delete()
            if (ruta == null) return@forEachIndexed
            val id = UUID.randomUUID().toString()
            imagenes += Mensaje(
                id = id,
                cuando = ahora + primera + i,
                clase = Clase.IMAGEN,
                texto = GrabarClase.pie(h.ms, nombre),
                ruta = ruta,
                nombre = fichero,
                bytes = File(ruta).length(),
                proyecto = proyecto,
                enGrabacion = GrabarClase.enGrabacion(vozId, h.ms)
            )
            enlazadas += GrabarClase.Enlace(h.ms, id)
        }
        val voz = Mensaje(
            id = vozId,
            cuando = ahora,
            clase = Clase.VOZ,
            ruta = archivo.absolutePath,
            nombre = GrabarClase.nombreDeLaNota(nombre),
            bytes = archivo.length(),
            duracionMs = duracion,
            picos = picos,
            proyecto = proyecto,
            // Como el PC desde `df01da9`: siempre, aunque no haya capturas; es lo que dice que la
            // nota es de una clase.
            capturas = GrabarClase.capturasEnJson(enlazadas),
            grabacion = GrabarClase.CLASE,
            video = elVideo?.let { GrabarClase.videoEnJson(it.id) }
        )
        // En orden y con números seguidos, como el PC: la nota, el vídeo y las capturas.
        almacen.anadir(voz)
        elVideo?.let { almacen.anadir(it) }
        imagenes.forEach { almacen.anadir(it) }
        // **Y todo dentro de su carpeta** (25-oct-2026, como el PC desde `e015e2b`): en el chat se ve
        // UNA tarjeta y no la nota, el vídeo y cada captura sueltos. Los enlaces de arriba no cambian
        // («Estudiar la clase» va por ellos); si la carpeta no entra, lo de dentro sigue suelto.
        val laCarpeta = runCatching {
            val carpeta = GrabarClase.carpetaDeLaGrabacion(vozId, duracion.toLong(), elVideo?.id, enlazadas)
            val cuando = CarpetaDePaginas.cuandoLibre(System.currentTimeMillis(), almacen.leer())
            almacen.anadir(CarpetaDePaginas.mensajeNuevo(nombre, cuando, proyecto, carpeta))
            cuando.toString()
        }.onFailure { android.util.Log.w("PixPinClase", "la clase quedó sin su carpeta", it) }.getOrNull()
        // Las capturas del PC, el reloj de pared y los colaboradores, en la nota (§12-§14).
        GrabacionDelMovil.guardada(context, proyecto, vozId, laCarpeta, duracion.toLong())
        // **Y se pasa a texto**, como el PC al terminar: es lo que se va a querer leer junto a
        // las imágenes. Si no hay reconocedor, se guarda igual y se dice dónde pedirlo luego.
        // Por la cola ([ColaDeTexto]), en primer plano y retomada si el proceso muere (28-oct-2026).
        val conTexto = Transcriptor.disponible(context)
        if (conTexto && ColaDeTexto.entraSola(voz, hayAudio = true, grabadoAqui = true)) almacen.transcribir(voz)
        return context.getString(
            if (conTexto) R.string.clase_lista_con_texto else R.string.clase_lista_sin_texto,
            enlazadas.size
        )
    }

    /** Las capturas de una clase con vídeo, sacadas de sus fotogramas a PNG en la caché. */
    private fun fotogramas(mp4: File, hechas: List<Hecha>): List<Hecha> {
        if (hechas.isEmpty()) return emptyList()
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(mp4.absolutePath)
            hechas.mapNotNull { h ->
                val foto = VideoDeLaClase.fotograma(r, h.ms) ?: return@mapNotNull null
                recortarYGuardar(foto, null, h.ms)?.let { Hecha(h.ms, it) }
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { r.release() }
        }
    }

    // --- Las ventanas ---------------------------------------------------------------------

    private fun ensenarLaBarra() {
        if (ventana != null) return
        val alto = resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Sin foco: tocar la barra no le quita el teclado a la reunión (`poner_sin_activar` del PC).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = alto / 8
        }
        val w = OverlayComposeWindow(this) { LaBarra() }
        runCatching {
            wm.añadirVistaFluida(w.view, params)
            w.onAttached()
            ventana = w
            lp = params
        }.onFailure {
            // Sin permiso de dibujar encima no hay barra, pero queda la notificación para terminar.
            toast(getString(R.string.need_overlay_toast))
        }
    }

    private fun mover(dx: Float, dy: Float) {
        val params = lp ?: return
        val v = ventana?.view ?: return
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        runCatching { wm.updateViewLayout(v, params) }
    }

    private fun quitarVentanas() {
        ventana?.let { w -> runCatching { wm.removeView(w.view) }; w.onDetached() }
        ventana = null
        lp = null
        selector?.let { w -> runCatching { wm.removeView(w.view) }; w.onDetached() }
        selector = null
    }

    /** Enseña [foto] a pantalla completa y espera la zona. Null si se cancela. */
    private suspend fun elegirZona(foto: Bitmap): Eleccion? = suspendCancellableCoroutine { cont ->
        var hecho = false
        fun acabar(e: Eleccion?) {
            if (hecho) return
            hecho = true
            selector?.let { w -> runCatching { wm.removeView(w.view) }; w.onDetached() }
            selector = null
            if (cont.isActive) cont.resume(e)
        }
        val (ancho, alto) = pantalla()
        val params = WindowManager.LayoutParams(
            ancho, alto,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val w = OverlayComposeWindow(this, matchParent = true) { SelectorDeZona(foto) { acabar(it) } }
        val puesto = runCatching {
            wm.añadirVistaFluida(w.view, params)
            w.onAttached()
            selector = w
        }.isSuccess
        if (!puesto) acabar(null)
        cont.invokeOnCancellation { scope.launch { acabar(null) } }
    }

    /** El tamaño real de la pantalla, barras incluidas: el del fotograma. Ver `FloatingBallController.pantalla`. */
    private fun pantalla(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    // --- Lo que se pinta --------------------------------------------------------------------

    @Composable
    private fun LaBarra() {
        val b by barra.collectAsState()
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
                        .background(if (b.enPausa) Color.Gray else Color(0xFFE53935))
                )
                Text(
                    GrabarClase.minuto(b.segundos * 1000), color = claro, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp)
                )
                TextButton(onClick = { captura(cambiarZona = false) }) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = claro, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (b.capturas == 0) getString(R.string.clase_captura) else "${getString(R.string.clase_captura)} · ${b.capturas}",
                        color = claro, fontSize = 13.sp
                    )
                }
                // El recorte, siempre a mano en el modo de capturas (con vídeo no hay zonas).
                if (!conVideo) {
                    IconButton(onClick = { captura(cambiarZona = true) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Crop, contentDescription = getString(R.string.clase_zona), tint = claro)
                    }
                }
                IconButton(onClick = { pausa() }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (b.enPausa) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = getString(if (b.enPausa) R.string.clase_seguir else R.string.clase_pausa),
                        tint = claro
                    )
                }
                IconButton(onClick = { terminar() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = getString(R.string.clase_terminar), tint = Color(0xFF81C784))
                }
                IconButton(onClick = { tirar() }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Close, contentDescription = getString(R.string.clase_tirar_boton),
                        tint = if (b.tirarArmado) Color(0xFFFF5252) else claro.copy(alpha = 0.7f)
                    )
                }
            }
            b.aviso?.let {
                Text(
                    it, color = claro, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(fondo)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }

    /**
     * **Elegir la zona sobre la pantalla congelada**: se arrastra un rectángulo; fuera de él se
     * oscurece. «Pantalla entera» no pide nada más.
     */
    @Composable
    private fun SelectorDeZona(foto: Bitmap, alElegir: (Eleccion?) -> Unit) {
        val imagen = remember(foto) { foto.asImageBitmap() }
        var a by remember { mutableStateOf<Offset?>(null) }
        var b by remember { mutableStateOf<Offset?>(null) }
        var tam by remember { mutableStateOf(IntSize.Zero) }
        val elegida = run {
            val p = a
            val q = b
            if (p == null || q == null) null
            else GrabarClase.zonaArrastrada(
                p.x, p.y, q.x, q.y, tam.width.toFloat(), tam.height.toFloat(), foto.width, foto.height
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(imagen, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(
                Modifier.fillMaxSize().onSizeChanged { tam = it }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { a = it; b = it },
                        onDrag = { cambio, _ -> cambio.consume(); b = cambio.position }
                    )
                }
            ) {
                val sombra = Color.Black.copy(alpha = 0.45f)
                val p = a
                val q = b
                if (p == null || q == null) {
                    drawRect(sombra)
                } else {
                    val l = minOf(p.x, q.x); val t = minOf(p.y, q.y)
                    val r = maxOf(p.x, q.x); val btm = maxOf(p.y, q.y)
                    drawRect(sombra, Offset(0f, 0f), Size(size.width, t))
                    drawRect(sombra, Offset(0f, btm), Size(size.width, size.height - btm))
                    drawRect(sombra, Offset(0f, t), Size(l, btm - t))
                    drawRect(sombra, Offset(r, t), Size(size.width - r, btm - t))
                    drawRect(Color.White, Offset(l, t), Size(r - l, btm - t), style = Stroke(width = 3.dp.toPx()))
                }
            }
            Text(
                getString(R.string.clase_elige_zona), color = Color.White, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Color(0xCC000000)).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { alElegir(null) }) { Text(getString(R.string.cancel), color = Color.White) }
                OutlinedButton(onClick = { alElegir(Eleccion(null)) }) {
                    Text(getString(R.string.clase_pantalla_entera), color = Color.White)
                }
                Button(enabled = elegida != null, onClick = { elegida?.let { alElegir(Eleccion(it)) } }) {
                    Text(getString(R.string.clase_usar_zona), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    // --- Avisos ---------------------------------------------------------------------------

    private fun avisar(texto: String) {
        barra.update { it.copy(aviso = texto) }
        avisoJob?.cancel()
        avisoJob = scope.launch {
            delay(2_500)
            barra.update { if (it.aviso == texto) it.copy(aviso = null) else it }
        }
    }

    private fun toast(texto: String) {
        Toast.makeText(applicationContext, texto, Toast.LENGTH_LONG).show()
    }

    /** La nota de la clase recién guardada, para el aviso de «Abrir para estudiar». */
    @Volatile private var notaGuardada: String? = null

    /**
     * **«Abrir para estudiar»** (25-oct-2026, como el final de la grabación del PC): un aviso que
     * abre la clase en [EstudiarClaseActivity]. Sin permiso de avisos, no sale y no pasa nada: la
     * nota ya está en el chat, con su «Estudiar la clase» en el menú.
     */
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

    private fun aviso(): Notification {
        val terminarPi = PendingIntent.getService(
            this, 0,
            Intent(this, GrabarClaseService::class.java).setAction(ACCION_TERMINAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.clase_notif))
            .setContentText(getString(R.string.clase_sub))
            .addAction(0, getString(R.string.clase_terminar), terminarPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
