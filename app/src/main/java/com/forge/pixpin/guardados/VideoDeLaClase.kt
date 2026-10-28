package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import java.io.File
import java.nio.ByteBuffer

/**
 * **El vídeo de la pantalla de una clase** (25-oct-2026, como el PC desde `20162e9`): MediaProjection →
 * `MediaRecorder` (H.264) en un `.mp4`.
 *
 * ## Sin sonido y en trozos (28-oct-2026, «Grabar sin perder nada»)
 *
 * Hasta hoy el `MediaRecorder` grababa también el micrófono y la nota se sacaba del vídeo al terminar:
 * si Android mataba la app a media clase, el `.mp4` sin cerrar (sin `moov`) no lo abría nadie y **se
 * perdía todo, sonido incluido**. Ahora, como pide la guía del PC («el sonido nunca depende del
 * vídeo»):
 *
 * - **El sonido lo graba la nota** ([com.forge.pixpin.audio.Grabador.Realzado], con su respaldo WAV): el
 *   micrófono lo sigue abriendo uno solo, así que no hay dos capturas peleándose por él.
 * - **El vídeo va en trozos** de [TrozosDeVideo.TROZO_MS]: [cambiarDeTrozo] prepara otra
 *   `MediaRecorder`, le da la pantalla (`VirtualDisplay.setSurface`) y cierra la de antes, que queda
 *   como un MP4 entero. Si Android mata la app, se pierde como mucho el trozo en curso.
 * - Al terminar (o al recuperar) se juntan sin recodificar y con el sonido de la nota
 *   ([GuardarLaGrabacion.juntarVideo]).
 *
 * ## Las capturas, del propio vídeo
 *
 * El permiso de Android 14 vale para **un** `VirtualDisplay`, y ese lo ocupa el vídeo: no queda otro
 * para la captura de siempre ([com.forge.pixpin.capture.ProjectionSession]), y pedir otro permiso
 * cortaría este. Por eso con vídeo «Captura» apunta el minuto y la imagen se saca del `.mp4` al
 * terminar ([fotograma]), a pantalla entera.
 */
class VideoDeLaClase private constructor(
    private val proyeccion: MediaProjection,
    private val pantalla: VirtualDisplay,
    private var grabadora: MediaRecorder,
    private var actual: File,
    private val preparar: (File) -> MediaRecorder?
) {

    @Volatile var parada = false
        private set
    @Volatile private var enPausa = false

    /** El trozo que se graba ahora. */
    val trozo: File get() = actual

    fun pausar(pausa: Boolean) = synchronized(this) {
        if (parada) return@synchronized
        enPausa = pausa
        runCatching { if (pausa) grabadora.pause() else grabadora.resume() }
        Unit
    }

    /**
     * **Cierra el trozo en curso y sigue en [siguiente]**, sin soltar la pantalla. Devuelve si el trozo
     * cerrado quedó bien, o null si no se cambió (en pausa, parado, o la grabadora nueva no arrancó: se
     * sigue en el mismo).
     */
    fun cambiarDeTrozo(siguiente: File): Boolean? = synchronized(this) {
        if (parada || enPausa) return@synchronized null
        val nueva = preparar(siguiente) ?: return@synchronized null
        if (runCatching { nueva.start() }.isFailure) {
            runCatching { nueva.release() }
            siguiente.delete()
            return@synchronized null
        }
        runCatching { pantalla.surface = nueva.surface }
        val vieja = grabadora
        grabadora = nueva
        actual = siguiente
        val bien = runCatching { vieja.stop() }.isSuccess
        runCatching { vieja.release() }
        bien
    }

    /** Para, cierra el trozo en curso y suelta la pantalla. Devuelve si el trozo quedó bien. */
    fun parar(): Boolean = synchronized(this) {
        if (parada) return@synchronized false
        parada = true
        val bien = runCatching { grabadora.stop() }.isSuccess
        runCatching { grabadora.release() }
        runCatching { pantalla.release() }
        runCatching { proyeccion.stop() }
        bien
    }

    companion object {
        /**
         * Empieza a grabar la pantalla en [primerTrozo], con el permiso recién concedido ([codigo],
         * [datos]). **Antes** hay que estar en primer plano con el tipo `mediaProjection` (Android 14).
         * [alPerderse] se llama si el sistema corta la proyección (el chip de la barra, bloquear la
         * pantalla), no si se para aquí. Null si este teléfono no deja: entonces solo la nota.
         */
        fun empezar(
            context: Context, codigo: Int, datos: Intent, primerTrozo: File,
            perfil: PerfilDeVideo = PerfilDeVideo.NORMAL, alPerderse: () -> Unit
        ): VideoDeLaClase? {
            var grabadora: MediaRecorder? = null
            var proyeccion: MediaProjection? = null
            return runCatching {
                val metricas = metricas(context) ?: error("sin pantalla")
                // **Con la calidad elegida** (25-oct-2026, como el PC). Si el codificador del teléfono no
                // admite el tamaño de «Nítido», «Normal»: mejor un vídeo algo más blando que ninguno.
                val probar = listOf(perfil, PerfilDeVideo.NORMAL, PerfilDeVideo.LIGERO).distinct()
                var preparar: ((File) -> MediaRecorder?)? = null
                var medidas: Pair<Int, Int>? = null
                for (p in probar) {
                    val (ancho, alto) = GrabarClase.medidasDelVideo(metricas.widthPixels, metricas.heightPixels, p)
                    // **Antes de configurar, si el H.264 del teléfono lo admite** (lo que hace AOSP
                    // `screenrecord`): un `prepare` que pasa con un tamaño que el codificador no da
                    // revienta luego al empezar.
                    if (!admiteH264(ancho, alto, p.porSegundo)) continue
                    val (media, _) = PerfilDeVideo.bitsPorSegundo(p, ancho, alto)
                    val esta = { destino: File -> grabadoraDeVideo(context, destino, ancho, alto, p.porSegundo, media) }
                    val g = esta(primerTrozo)
                    if (g != null) {
                        grabadora = g
                        preparar = esta
                        medidas = ancho to alto
                        break
                    }
                }
                val (ancho, alto) = medidas ?: error("sin codificador")
                proyeccion = context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(codigo, datos)
                    ?: error("sin proyección")
                val instancia = arrayOfNulls<VideoDeLaClase>(1)
                // Android 14: el aviso de que se corta se pone **antes** de crear el display.
                proyeccion!!.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (instancia[0]?.parada != true) alPerderse()
                    }
                }, Handler(Looper.getMainLooper()))
                val display = proyeccion!!.createVirtualDisplay(
                    "pixpin-clase", ancho, alto, metricas.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    grabadora!!.surface, null, null
                ) ?: error("sin display")
                grabadora!!.start()
                VideoDeLaClase(proyeccion!!, display, grabadora!!, primerTrozo, preparar!!).also { instancia[0] = it }
            }.getOrElse {
                runCatching { grabadora?.release() }
                runCatching { proyeccion?.stop() }
                primerTrozo.delete()
                null
            }
        }

        /**
         * Una `MediaRecorder` de solo imagen ya preparada para [destino], o null. Sin micrófono: el sonido
         * es de la nota.
         */
        private fun grabadoraDeVideo(context: Context, destino: File, ancho: Int, alto: Int, fps: Int, bits: Int): MediaRecorder? {
            val g = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
            else @Suppress("DEPRECATION") MediaRecorder())
            val lista = runCatching {
                g.apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(destino.absolutePath)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(ancho, alto)
                    setVideoFrameRate(fps)
                    // La media del PC para este tamaño: una hora de «Normal» ≈ 300 MB.
                    setVideoEncodingBitRate(bits)
                    prepare()
                }
            }.isSuccess
            if (lista) return g
            runCatching { g.release() }
            destino.delete()
            return null
        }

        /**
         * Si algún codificador H.264 del teléfono (los de hardware primero no importa aquí: basta
         * uno) admite [ancho]×[alto] a [fps]. Si no se puede saber, sí: que lo diga `prepare`.
         */
        private fun admiteH264(ancho: Int, alto: Int, fps: Int): Boolean = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                .any { info ->
                    runCatching {
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                            .areSizeAndRateSupported(ancho, alto, fps.toDouble())
                    }.getOrDefault(false)
                }
        }.getOrDefault(true)

        private fun metricas(context: Context): DisplayMetrics? {
            val dm = context.getSystemService(DisplayManager::class.java) ?: return null
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            return DisplayMetrics().also {
                @Suppress("DEPRECATION")
                display.getRealMetrics(it)
            }
        }

        /**
         * **La pista de audio del vídeo, a un `.m4a`**, copiando las muestras AAC tal cual (sin
         * recomprimir: una hora tarda segundos). False si no hay audio o no se pudo.
         */
        fun sacarElAudio(mp4: File, m4a: File): Boolean = runCatching {
            val extractor = MediaExtractor()
            var muxer: MediaMuxer? = null
            try {
                extractor.setDataSource(mp4.absolutePath)
                val pista = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return false
                val formato = extractor.getTrackFormat(pista)
                extractor.selectTrack(pista)
                muxer = MediaMuxer(m4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val salida = muxer.addTrack(formato)
                muxer.start()
                val tam = if (formato.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) formato.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
                val buf = ByteBuffer.allocate(maxOf(tam, 64 * 1024))
                val info = MediaCodec.BufferInfo()
                var escritas = 0
                while (true) {
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) break
                    info.set(0, n, extractor.sampleTime, if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(salida, buf, info)
                    escritas++
                    extractor.advance()
                }
                muxer.stop()
                escritas > 0
            } finally {
                runCatching { muxer?.release() }
                runCatching { extractor.release() }
            }
        }.getOrElse { m4a.delete(); false }

        /** El fotograma del vídeo en [ms] (el más cercano), o null. */
        fun fotograma(retriever: MediaMetadataRetriever, ms: Long): Bitmap? = runCatching {
            retriever.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull()
    }
}
