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
import com.forge.pixpin.audio.Grabador
import java.io.File
import java.nio.ByteBuffer

/**
 * **El vídeo de la pantalla de una clase, con el mismo audio** (25-oct-2026, como el PC desde
 * `20162e9`): MediaProjection → `MediaRecorder` (H.264 + AAC del micrófono) en un `.mp4`.
 *
 * ## Una grabadora para todo, y la nota sale del vídeo
 *
 * Con vídeo, **el micrófono lo abre solo el `MediaRecorder`**: dos capturas del micrófono a la vez
 * en la misma app no son fiables en todos los teléfonos. La nota de voz se saca al final del propio
 * vídeo copiando su pista AAC a un `.m4a` sin recomprimir ([sacarElAudio]), así que audio y vídeo
 * van al milisegundo y la transcripción sale de la nota, como en el PC.
 *
 * ## Las capturas, del propio vídeo
 *
 * El permiso de Android 14 vale para **un** `VirtualDisplay`, y ese lo ocupa el vídeo: no queda otro
 * para la captura de siempre ([com.forge.pixpin.capture.ProjectionSession]), y pedir otro permiso
 * cortaría este. Por eso con vídeo «Captura» apunta el minuto y la imagen se saca del `.mp4` al
 * terminar ([fotograma]), a pantalla entera.
 *
 * Implementa [Grabador] para que el servicio la trate como cualquier nota: picos, pausa y parar.
 */
class VideoDeLaClase private constructor(
    private val proyeccion: MediaProjection,
    private val pantalla: VirtualDisplay,
    private val grabadora: MediaRecorder
) : Grabador {

    @Volatile private var parada = false

    override fun pico(): Int = runCatching { grabadora.maxAmplitude }.getOrDefault(0).coerceAtLeast(0)

    override fun pausar(pausa: Boolean) {
        runCatching { if (pausa) grabadora.pause() else grabadora.resume() }
    }

    override fun parar(): Boolean {
        if (parada) return false
        parada = true
        val bien = runCatching { grabadora.stop() }.isSuccess
        runCatching { grabadora.release() }
        runCatching { pantalla.release() }
        runCatching { proyeccion.stop() }
        return bien
    }

    companion object {
        /**
         * Empieza a grabar la pantalla y el micrófono en [destino], con el permiso recién concedido
         * ([codigo], [datos]). **Antes** hay que estar en primer plano con el tipo `mediaProjection`
         * (Android 14). [alPerderse] se llama si el sistema corta la proyección (el chip de la barra,
         * bloquear la pantalla). Null si este teléfono no deja: entonces se graba solo el audio.
         */
        fun empezar(
            context: Context, codigo: Int, datos: Intent, destino: File,
            perfil: PerfilDeVideo = PerfilDeVideo.NORMAL, alPerderse: () -> Unit
        ): VideoDeLaClase? {
            var grabadora: MediaRecorder? = null
            var proyeccion: MediaProjection? = null
            return runCatching {
                val metricas = metricas(context) ?: error("sin pantalla")
                // **Con la calidad elegida** (25-oct-2026, como el PC: «Ligero», «Normal», «Nítido»).
                // Si el codificador del teléfono no admite el tamaño de «Nítido» (muchos no pasan
                // de 1080p), «Normal»: mejor un vídeo algo más blando que ninguno.
                val probar = listOf(perfil, PerfilDeVideo.NORMAL, PerfilDeVideo.LIGERO).distinct()
                var medidas: Pair<Int, Int>? = null
                for (p in probar) {
                    val (ancho, alto) = GrabarClase.medidasDelVideo(metricas.widthPixels, metricas.heightPixels, p)
                    // **Antes de configurar, si el H.264 del teléfono lo admite** (25-oct-2026, lo que
                    // hace AOSP `screenrecord`, `docs/motor-de-video.md` del PC): un `prepare` que pasa
                    // con un tamaño que el codificador no da revienta luego al empezar.
                    if (!admiteH264(ancho, alto, p.porSegundo)) continue
                    val (media, _) = PerfilDeVideo.bitsPorSegundo(p, ancho, alto)
                    val g = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                    else @Suppress("DEPRECATION") MediaRecorder())
                    val lista = runCatching {
                        g.apply {
                            // El micrófono de una clase: `CAMCORDER`, orientado al frente y con su AGC
                            // (`docs/motor-de-audio.md` §8 del PC).
                            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                            setVideoSource(MediaRecorder.VideoSource.SURFACE)
                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setOutputFile(destino.absolutePath)
                            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                            setVideoSize(ancho, alto)
                            setVideoFrameRate(p.porSegundo)
                            // La media del PC para este tamaño: una hora de «Normal» ≈ 300 MB.
                            setVideoEncodingBitRate(media)
                            // El `.m4a` de la nota sale de esta pista: mono a 48 000 y 96 kb/s, el de
                            // la Grabadora del PC.
                            setAudioChannels(1)
                            setAudioSamplingRate(48_000)
                            setAudioEncodingBitRate(96_000)
                            prepare()
                        }
                    }.isSuccess
                    if (lista) {
                        grabadora = g
                        medidas = ancho to alto
                        break
                    }
                    runCatching { g.release() }
                }
                val (ancho, alto) = medidas ?: error("sin codificador")
                proyeccion = context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(codigo, datos)
                    ?: error("sin proyección")
                // Android 14: el aviso de que se corta se pone **antes** de crear el display.
                proyeccion!!.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() = alPerderse()
                }, Handler(Looper.getMainLooper()))
                val display = proyeccion!!.createVirtualDisplay(
                    "pixpin-clase", ancho, alto, metricas.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    grabadora!!.surface, null, null
                ) ?: error("sin display")
                grabadora!!.start()
                VideoDeLaClase(proyeccion!!, display, grabadora!!)
            }.getOrElse {
                runCatching { grabadora?.release() }
                runCatching { proyeccion?.stop() }
                destino.delete()
                null
            }
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
