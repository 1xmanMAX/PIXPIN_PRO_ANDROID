package com.forge.pixpin.guardados

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * **La cámara de la reunión presencial** (28-oct-2026, §3.3 de las novedades del PC): las fotos a la
 * resolución entera de la cámara de atrás y el vídeo (la reunión entera o un clip), **con la app
 * fuera y la pantalla apagada**. Se usa desde [ReunionPresencialService], que es el [LifecycleOwner]
 * al que se ata CameraX (un servicio en primer plano de tipo `camera`: Android no da la cámara en
 * segundo plano a nadie más). Todo se llama desde el hilo principal.
 *
 * ## Las fotos y el vídeo a la vez, con una sola cámara
 *
 * Una cámara solo la puede tener un sitio. Por eso **no hay dos grabaciones**: se atan juntos a la
 * misma cámara un `ImageCapture` (las fotos, a la mayor resolución 4:3 que da, con
 * `CAPTURE_MODE_MAXIMIZE_QUALITY`) y, cuando hace falta vídeo, un `Preview` cuya superficie es la de
 * un [MediaRecorder] (`VideoSource.SURFACE`). Así la foto sale del mismo flujo de la cámara mientras
 * se graba, sin soltar nada. Es la combinación «PRIV PREVIEW + JPEG MAXIMUM», la que todo Android
 * garantiza desde el nivel LEGACY: la foto no baja de resolución por grabar.
 *
 * Y el codificador es el [MediaRecorder] y no el `Recorder` de CameraX (`camera-video`) porque este
 * no deja elegir HEVC ni el sonido: aquí va **HEVC por hardware si el teléfono lo tiene, si no H.264**,
 * a 30 fps y a los bits de [Presencial.bitsDeLaReunion], con **AAC mono a 48 000 y 96 kb/s**, como el
 * PC. El giro del sensor se apunta en el MP4 ([MediaRecorder.setOrientationHint]), sin recomprimir.
 *
 * ## La mirilla: ver lo que se fotografía (28-oct-2026)
 *
 * El usuario: «puede ser un pequeño cuadro donde vea que estoy tomando fotos o grabando». Se ata un
 * **segundo `Preview`, pequeño** ([vista], a lo más 640×480), cuya superficie la pone quien la
 * enseña: la burbuja flotante o la pantalla de la reunión en la app ([ponerMirilla]); sin nadie
 * mirando, se le quita la superficie y la cámara no la alimenta. Con la cámara de las fotos son dos
 * flujos (PRIV + JPEG, lo que da cualquier Android); grabando vídeo, tres. **Si el teléfono no ata
 * los tres**, se suelta la mirilla mientras dura el vídeo ([mirillaParada]) y vuelve al pararlo: el
 * vídeo y las fotos mandan; la burbuja enseña entonces solo el contador.
 */
class CamaraDeLaReunion(private val context: Context, private val duenio: LifecycleOwner) {

    private val principal = ContextCompat.getMainExecutor(context)
    private var proveedor: ProcessCameraProvider? = null
    private var info: CameraInfo? = null
    private var selector: CameraSelector? = null
    private var fotos: ImageCapture? = null
    private var mirilla: Preview? = null
    private var grabadora: MediaRecorder? = null
    private var superficieLibre: CompletableDeferred<Unit>? = null

    /** La mirilla atada (null si el teléfono no la deja). */
    private var vista: Preview? = null
    private var proveedorDeLaVista: Preview.SurfaceProvider? = null

    /** Si hay mirilla que enseñar ahora (atada y no parada por el vídeo). */
    val conMirilla: Boolean get() = vista != null && !mirillaParada

    /** Si se soltó la mirilla para poder grabar el vídeo (el teléfono no daba para los tres flujos). */
    var mirillaParada = false
        private set

    /** El giro de la pantalla al empezar (`Surface.ROTATION_*`): el del vídeo y el de las fotos. */
    var giro: Int = android.view.Surface.ROTATION_0
        set(v) {
            field = v
            runCatching { fotos?.targetRotation = v }
            runCatching { vista?.targetRotation = v }
        }

    /** El id de la cámara que se usa (para recordarla), si hay. */
    var camaraId: String? = null
        private set

    /** Si el vídeo que se graba va en HEVC (si no, H.264). */
    var hevc = false
        private set

    /** El lado corto del vídeo que se graba ahora (0 si no se graba). */
    var altoDelVideo = 0
        private set

    /** Si hay vídeo grabándose. */
    val grabando: Boolean get() = grabadora != null

    /**
     * **Abre la cámara para las fotos**: la recordada si sigue, si no la de atrás
     * ([Presencial.camaraElegida]). False si no hay cámara o no se deja.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun abrir(recordada: String?): Boolean {
        val p = proveedor ?: obtenerProveedor() ?: return false
        proveedor = p
        val camaras = runCatching { p.availableCameraInfos }.getOrNull().orEmpty()
        if (camaras.isEmpty()) return false
        val ids = camaras.map { c ->
            val id = runCatching { Camera2CameraInfo.from(c).cameraId }.getOrNull().orEmpty()
            val lado = when (c.lensFacing) {
                CameraSelector.LENS_FACING_BACK -> Presencial.Lado.DETRAS
                CameraSelector.LENS_FACING_FRONT -> Presencial.Lado.DELANTE
                else -> Presencial.Lado.FUERA
            }
            id to lado
        }
        val i = Presencial.camaraElegida(ids, recordada) ?: return false
        info = camaras[i]
        selector = camaras[i].cameraSelector
        camaraId = ids[i].first.ifEmpty { null }
        return atarFotos()
    }

    /** Ata el `ImageCapture` de las fotos a la mayor resolución. */
    private fun atarFotos(): Boolean {
        val p = proveedor ?: return false
        val s = selector ?: return false
        val ic = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    // La de más píxeles de la cámara, en 4:3 (el sensor entero): `mejor_modo` del PC.
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .setTargetRotation(giro)
            .build()
        val bien = runCatching {
            p.bindToLifecycle(duenio, s, ic)
            fotos = ic
        }.onFailure { Log.w(TAG, "no se pudo atar la cámara de las fotos", it) }.isSuccess
        if (bien) atarMirilla()
        return bien
    }

    /** **La mirilla**, pequeña y al lado de las fotos. Si no se deja, sin ella (y nada más cambia). */
    private fun atarMirilla(): Boolean {
        val p = proveedor ?: return false
        val s = selector ?: return false
        val pv = vista ?: Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
            )
            .setTargetRotation(giro)
            .build()
        val bien = runCatching {
            p.bindToLifecycle(duenio, s, pv)
            pv.setSurfaceProvider(principal, proveedorDeLaVista)
        }.onFailure {
            Log.w(TAG, "sin mirilla: el teléfono no la ata con lo demás", it)
            runCatching { p.unbind(pv) }
        }.isSuccess
        vista = if (bien) pv else null
        return bien
    }

    /**
     * **Dónde se ve la mirilla**: la superficie de un `PreviewView` (la burbuja o la pantalla de la
     * reunión), o null para que la cámara deje de alimentarla.
     */
    fun ponerMirilla(proveedor: Preview.SurfaceProvider?) {
        proveedorDeLaVista = proveedor
        runCatching { vista?.setSurfaceProvider(principal, proveedor) }
    }

    /**
     * **Una foto a [destino]**, en JPEG tal cual la da la cámara (con su EXIF). False si no se pudo
     * (la cámara se cerró, otra app la tiene).
     */
    suspend fun foto(destino: File): Boolean {
        val ic = fotos ?: return false
        return suspendCancellableCoroutine { cont ->
            val opciones = ImageCapture.OutputFileOptions.Builder(destino).build()
            runCatching {
                ic.takePicture(opciones, principal, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(destino.length() > 0)
                    }

                    override fun onError(e: ImageCaptureException) {
                        Log.w(TAG, "la foto no salió (${e.imageCaptureError})", e)
                        destino.delete()
                        if (cont.isActive) cont.resume(false)
                    }
                })
            }.onFailure { if (cont.isActive) cont.resume(false) }
        }
    }

    /**
     * **Empieza a grabar vídeo** a [destino], con el lado corto [alto] (1080 o 720) y, si [conSonido],
     * el micrófono en el mismo MP4. Se ata un `Preview` cuya superficie es la del [MediaRecorder]: la
     * cámara le da sus fotogramas al codificador y sigue haciendo fotos. False si no se pudo.
     */
    suspend fun empezarVideo(destino: File, alto: Int, conSonido: Boolean): Boolean {
        val p = proveedor ?: return false
        val s = selector ?: return false
        if (grabadora != null) return false
        val listo = CompletableDeferred<Boolean>()
        val ancho = alto * 16 / 9
        val pv = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(ancho, alto), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
            )
            .setTargetRotation(giro)
            .build()
        val rotacion = runCatching { info?.getSensorRotationDegrees(giro) ?: 0 }.getOrDefault(0)
        pv.setSurfaceProvider(principal) { pedido ->
            val ya = grabadora
            if (ya != null) {
                // CameraX vuelve a pedir la superficie (se reconfiguró la sesión): la misma del
                // codificador, que sigue grabando. Un segundo codificador pisaría el primero.
                val libre = CompletableDeferred<Unit>()
                superficieLibre = libre
                pedido.provideSurface(ya.surface, principal) { libre.complete(Unit) }
                return@setSurfaceProvider
            }
            val tam = pedido.resolution
            val g = prepararGrabadora(destino, tam.width, tam.height, rotacion, conSonido)
            if (g == null) {
                pedido.willNotProvideSurface()
                listo.complete(false)
            } else {
                val libre = CompletableDeferred<Unit>()
                superficieLibre = libre
                grabadora = g
                altoDelVideo = minOf(tam.width, tam.height)
                pedido.provideSurface(g.surface, principal) { libre.complete(Unit) }
                val bien = runCatching { g.start() }.onFailure { Log.w(TAG, "el vídeo no arrancó", it) }.isSuccess
                if (!bien) {
                    grabadora = null
                    altoDelVideo = 0
                    runCatching { g.release() }
                }
                listo.complete(bien)
            }
        }
        var atado = runCatching { p.bindToLifecycle(duenio, s, pv) }
            .onFailure { Log.w(TAG, "no se pudo atar el vídeo con las fotos", it) }.isSuccess
        if (!atado && vista != null) {
            // Tres flujos no caben en este teléfono: fuera la mirilla mientras se graba.
            vista?.let { v -> runCatching { p.unbind(v) } }
            mirillaParada = true
            atado = runCatching { p.bindToLifecycle(duenio, s, pv) }
                .onFailure { Log.w(TAG, "no se pudo atar el vídeo ni sin la mirilla", it) }.isSuccess
        }
        if (!atado) {
            volverLaMirilla()
            return false
        }
        mirilla = pv
        val bien = withTimeoutOrNull(5_000) { listo.await() } ?: false
        if (!bien) {
            runCatching { p.unbind(pv) }
            mirilla = null
            volverLaMirilla()
        }
        return bien
    }

    /** La mirilla, otra vez atada si se soltó para el vídeo. */
    private fun volverLaMirilla() {
        if (!mirillaParada) return
        mirillaParada = false
        val v = vista ?: return
        val bien = runCatching {
            proveedor?.bindToLifecycle(duenio, selector ?: return, v)
            v.setSurfaceProvider(principal, proveedorDeLaVista)
        }.isSuccess
        if (!bien) vista = null
    }

    /**
     * **Para el vídeo** y lo cierra. Primero se suelta el `Preview` (la cámara deja de escribir en la
     * superficie) y luego se para el codificador: al revés, la cámara escribiría en una superficie
     * muerta y se caería también la de las fotos. False si no quedó nada que sirva.
     */
    suspend fun pararVideo(): Boolean {
        val g = grabadora ?: return false
        mirilla?.let { pv -> runCatching { proveedor?.unbind(pv) } }
        mirilla = null
        superficieLibre?.let { withTimeoutOrNull(2_000) { it.await() } }
        superficieLibre = null
        grabadora = null
        altoDelVideo = 0
        val bien = runCatching { g.stop() }.isSuccess
        runCatching { g.release() }
        volverLaMirilla()
        return bien
    }

    /** Pausa o sigue el vídeo que se graba (la reunión o un clip), sin soltar nada. */
    fun pausar(pausa: Boolean) {
        val g = grabadora ?: return
        runCatching { if (pausa) g.pause() else g.resume() }
    }

    /** Lo más alto que sonó desde la última vez (con el vídeo de la reunión, para su onda). */
    fun pico(): Int = runCatching { grabadora?.maxAmplitude ?: 0 }.getOrDefault(0).coerceAtLeast(0)

    /** Suelta la cámara entera. Un vídeo a medias se para antes ([pararVideo]). */
    fun cerrar() {
        runCatching { vista?.setSurfaceProvider(null) }
        runCatching { proveedor?.unbindAll() }
        fotos = null
        mirilla = null
        vista = null
        proveedorDeLaVista = null
    }

    /**
     * El [MediaRecorder] del vídeo, ya preparado. **HEVC si el teléfono lo codifica por hardware** a
     * ese tamaño; si no (o si `prepare` falla), H.264. Null si ninguno.
     */
    private fun prepararGrabadora(destino: File, ancho: Int, alto: Int, rotacion: Int, conSonido: Boolean): MediaRecorder? {
        val probar = buildList {
            if (hevcPorHardware(ancho, alto)) add(true)
            add(false)
        }
        for (conHevc in probar) {
            val g = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) MediaRecorder(context)
            else @Suppress("DEPRECATION") MediaRecorder()
            val bien = runCatching {
                g.apply {
                    // El micrófono de una clase: `CAMCORDER`, como `VideoDeLaClase`.
                    if (conSonido) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(destino.absolutePath)
                    setVideoEncoder(if (conHevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
                    if (conSonido) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setVideoSize(ancho, alto)
                    setVideoFrameRate(Presencial.FPS)
                    setVideoEncodingBitRate(Presencial.bitsDeLaReunion(minOf(ancho, alto), conHevc))
                    if (conSonido) {
                        setAudioChannels(1)
                        setAudioSamplingRate(Presencial.SONIDO_MUESTREO)
                        setAudioEncodingBitRate(Presencial.SONIDO_BITS)
                    }
                    setOrientationHint(((rotacion % 360) + 360) % 360)
                    prepare()
                }
            }.onFailure { Log.w(TAG, "el codificador ${if (conHevc) "HEVC" else "H.264"} no se preparó", it) }.isSuccess
            if (bien) {
                hevc = conHevc
                return g
            }
            runCatching { g.release() }
            destino.delete()
        }
        return null
    }

    private suspend fun obtenerProveedor(): ProcessCameraProvider? = suspendCancellableCoroutine { cont ->
        val futuro = ProcessCameraProvider.getInstance(context)
        futuro.addListener({
            val p = runCatching { futuro.get() }.getOrNull()
            if (cont.isActive) cont.resume(p)
        }, principal)
    }

    companion object {
        private const val TAG = "PixPinReunion"

        /** Si el teléfono tiene un codificador HEVC **por hardware** para ese tamaño a 30 fps. */
        fun hevcPorHardware(ancho: Int, alto: Int): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c ->
                c.isEncoder && c.isHardwareAccelerated &&
                    c.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } &&
                    runCatching {
                        val v = c.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities ?: return@runCatching false
                        v.areSizeAndRateSupported(ancho, alto, Presencial.FPS.toDouble()) ||
                            v.areSizeAndRateSupported(alto, ancho, Presencial.FPS.toDouble())
                    }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }
}
