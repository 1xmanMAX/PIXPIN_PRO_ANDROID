package com.forge.pixpin.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteOrder

/**
 * **Rehacer la nota desde su respaldo WAV** (28-oct-2026, «Grabar sin perder nada»; `rehacer_m4a` de
 * `pixpin_motor_audio` del PC): el PCM del [RespaldoWav] → AAC con `MediaCodec` → `.m4a` con
 * `MediaMuxer`, el formato de siempre (el chat, el reproductor y el PC no ven otra cosa). Una hora de
 * nota en pocos segundos: el codificador AAC por hardware va muy por delante del tiempo real.
 *
 * Se escribe primero a `<destino>.rehecho.parte` y se renombra al acabar: un corte a mitad no deja
 * un `.m4a` roto en su nombre.
 */
object RehacerM4a {
    private const val TAG = "PixPinRespaldo"

    /** Los bits de la nota rehecha: los de la nota de siempre. */
    const val BITS = 32_000

    /**
     * **Rehace [destino] desde el WAV de [respaldo].** Devuelve lo que dura (ms), o null si no se
     * pudo (el WAV no se lee, el codificador no arranca). No borra el respaldo.
     */
    fun rehacer(respaldo: File, destino: File): Long? {
        val wav = runCatching { RespaldoWav.leer(respaldo) }.getOrNull() ?: return null
        if (wav.marcos <= 0) return null
        val tmp = File(destino.path + ".rehecho.parte")
        tmp.delete()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return runCatching {
            val formato = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, wav.muestreo, wav.canales).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (wav.muestreo >= 44_100) 96_000 else BITS)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = c
            c.configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val m = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val info = MediaCodec.BufferInfo()
            var pista = -1
            var arrancado = false
            var escritas = 0
            var marcos = 0L
            fun sacar(hastaElFinal: Boolean): Boolean {
                var esperas = 0
                while (true) {
                    val o = c.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!hastaElFinal || ++esperas > 500) return false
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (!arrancado) {
                            pista = m.addTrack(c.outputFormat); m.start(); arrancado = true
                        }
                        o >= 0 -> {
                            val salida = c.getOutputBuffer(o)
                            if (salida != null && info.size > 0 && arrancado && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                salida.position(info.offset); salida.limit(info.offset + info.size)
                                m.writeSampleData(pista, salida, info)
                                escritas++
                            }
                            c.releaseOutputBuffer(o, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                        }
                    }
                }
            }
            // Bloques de una décima de segundo: lo que cabe en un búfer de entrada de cualquier AAC.
            val porBloque = maxOf(wav.muestreo / 10, 256)
            RespaldoWav.recorrer(respaldo, wav, porBloque) { muestras, n ->
                var hecho = 0
                while (hecho < n) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i < 0) { sacar(false); continue }
                    val entrada = c.getInputBuffer(i) ?: continue
                    entrada.clear()
                    val bb = entrada.order(ByteOrder.LITTLE_ENDIAN)
                    val caben = minOf(n - hecho, bb.remaining() / 2) / wav.canales * wav.canales
                    for (k in 0 until caben) bb.putShort(muestras[hecho + k])
                    c.queueInputBuffer(i, 0, caben * 2, RespaldoWav.microsegundos(marcos, wav.muestreo), 0)
                    marcos += caben / wav.canales
                    hecho += caben
                    sacar(false)
                }
            }
            while (true) {
                val i = c.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    c.queueInputBuffer(i, 0, 0, RespaldoWav.microsegundos(marcos, wav.muestreo), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                sacar(false)
            }
            sacar(true)
            if (!arrancado || escritas == 0) error("el codificador no dio nada")
            m.stop()
            m.release(); muxer = null
            c.stop(); c.release(); codec = null
            destino.delete()
            if (!tmp.renameTo(destino)) error("no se pudo renombrar")
            wav.duracionMs()
        }.onFailure { Log.w(TAG, "no se pudo rehacer la nota del respaldo", it) }
            .also {
                runCatching { muxer?.release() }
                runCatching { codec?.release() }
                if (it.isFailure) tmp.delete()
            }
            .getOrNull()
    }
}
