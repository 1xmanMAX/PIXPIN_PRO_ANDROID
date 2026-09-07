package com.forge.pixpin.motor

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * **Un audio más ligero para la página web.**
 *
 * Una nota de voz de diez minutos en `.m4a` son unos cinco megas; metida en base64 dentro
 * del HTML, siete. Para la web se vuelve a codificar a AAC mono a 16 kHz —que para voz
 * es transparente— con el caudal que se elija: [LIGERO] deja el archivo en un cuarto,
 * [ULTRALIGERO] en un décimo (se nota, pero se entiende). [ORIGINAL] no toca nada. Lo
 * pidió el usuario (5-sep-2026): poder elegir entre calidad y peso.
 *
 * Decodifica con el mismo camino que la transcripción (cualquier formato → PCM 16 kHz
 * mono) y codifica con el AAC del sistema.
 */
object AudioLigero {
    /**
     * **Si un texto de nota lleva algún audio dentro.**
     *
     * Es lo que decide si la pregunta por la calidad del audio tiene sentido al exportar:
     * preguntarla en un documento que no tiene ni un audio es una opción que no hace nada, y
     * el usuario la veía siempre (6-sep-2026). Se mira la extensión del destino de cada
     * medio del Markdown, la misma lista que usa `motormd.Markdown.claseDeMedio` para
     * llamarlo audio; si allí se añade una, aquí también (lo comprueba `AudioLigeroTest`).
     */
    fun hayAudioEn(markdown: String?): Boolean {
        if (markdown.isNullOrBlank()) return false
        for (m in DESTINO_DE_UN_MEDIO.findAll(markdown)) {
            val ruta = m.groupValues[1].trim()
            val ext = ruta.substringAfterLast('.', "").lowercase().substringBefore('?')
            if (ext in EXTENSIONES_DE_AUDIO) return true
        }
        return false
    }

    /** `![algo](destino)`: el destino de un medio del Markdown. */
    private val DESTINO_DE_UN_MEDIO = Regex("""!\[[^\]]*]\(([^)]*)\)""")

    internal val EXTENSIONES_DE_AUDIO =
        setOf("mp3", "ogg", "oga", "m4a", "wav", "flac", "opus", "aac")

    const val ORIGINAL = "original"
    const val LIGERO = "ligero"
    const val ULTRALIGERO = "ultraligero"
    const val SIN_AUDIO = "sin"

    private const val HERCIOS = 16_000

    fun bitsPorSegundo(calidad: String): Int? = when (calidad) {
        LIGERO -> 32_000
        ULTRALIGERO -> 12_000
        else -> null
    }

    /** El `.m4a` recodificado en [destino], o null si no se pudo (se deja el original). */
    fun comprimir(origen: File, destino: File, calidad: String): File? {
        val bps = bitsPorSegundo(calidad) ?: return null
        return runCatching {
            val pcm = File(destino.parentFile, destino.name + ".pcm")
            pcm.outputStream().buffered().use { com.forge.pixpin.guardados.Transcriptor.decodificar(origen, it) }
            if (pcm.length() <= 0) { pcm.delete(); return null }
            codificar(pcm, destino, bps)
            pcm.delete()
            destino.takeIf { it.length() > 0 }
        }.getOrNull()
    }

    private fun codificar(pcm: File, destino: File, bps: Int) {
        val formato = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, HERCIOS, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(destino.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var pista = -1
        val info = MediaCodec.BufferInfo()
        var entradaAcabada = false
        var salidaAcabada = false
        var muestrasMetidas = 0L
        try {
            pcm.inputStream().buffered().use { entrada ->
                val trozo = ByteArray(16 * 1024)
                while (!salidaAcabada) {
                    var hizoAlgo = false
                    if (!entradaAcabada) {
                        val i = codec.dequeueInputBuffer(0)
                        if (i >= 0) {
                            hizoAlgo = true
                            val buf = codec.getInputBuffer(i)!!
                            val n = entrada.read(trozo, 0, minOf(trozo.size, buf.capacity()))
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                entradaAcabada = true
                            } else {
                                buf.clear(); buf.put(trozo, 0, n)
                                val tiempoUs = muestrasMetidas * 1_000_000L / HERCIOS
                                codec.queueInputBuffer(i, 0, n, tiempoUs, 0)
                                muestrasMetidas += n / 2
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 0)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { hizoAlgo = true; pista = muxer.addTrack(codec.outputFormat); muxer.start() }
                        o >= 0 -> {
                            hizoAlgo = true
                            val buf = codec.getOutputBuffer(o)!!
                            if (info.size > 0 && pista >= 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                muxer.writeSampleData(pista, buf, info)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) salidaAcabada = true
                        }
                    }
                    // Sin esperas de diez milisegundos por vuelta: solo se descansa si no hubo nada.
                    if (!hizoAlgo) Thread.sleep(1)
                }
            }
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }
            runCatching { if (pista >= 0) muxer.stop() }; runCatching { muxer.release() }
        }
    }
}
