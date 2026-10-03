package com.forge.pixpin.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **Lo que graba una nota de voz.** Dos formas detrás de lo mismo: la que realza la voz
 * ([Realzado], como el PC desde el 3-oct-2026) y la del sistema ([DelSistema]), que queda de
 * reserva si en algún teléfono el codificador no arranca. Quien graba no nota la diferencia.
 */
interface Grabador {
    /** Lo más alto que ha sonado desde la última vez que se preguntó, de 0 a 32767. */
    fun pico(): Int
    /** Para y suelta el micrófono. False si no llegó a grabarse nada que sirva. */
    fun parar(): Boolean

    class DelSistema(private val r: MediaRecorder) : Grabador {
        override fun pico() = runCatching { r.maxAmplitude }.getOrDefault(0).coerceAtLeast(0)
        override fun parar(): Boolean {
            val bien = runCatching { r.stop() }.isSuccess
            runCatching { r.release() }
            return bien
        }
    }

    /**
     * Micrófono → [Realce] → AAC en un `.m4a`, en un hilo propio. El realce va **antes** de los
     * picos y de comprimir: la onda que se dibuja es la de lo que se oirá.
     */
    class Realzado private constructor(
        private val record: AudioRecord,
        private val codec: MediaCodec,
        private val muxer: MediaMuxer,
        private val muestreo: Int
    ) : Grabador {
        @Volatile private var corriendo = true
        @Volatile private var maximo = 0
        private var pista = -1
        private var arrancado = false
        private var muestras = 0L
        private var escritas = 0
        private val info = MediaCodec.BufferInfo()
        private val realce = Realce(muestreo)
        private val hilo = Thread({ bucle() }, "pixpin-voz")

        init { hilo.start() }

        override fun pico(): Int { val m = maximo; maximo = 0; return m }

        private fun bucle() {
            val buf = ShortArray(muestreo / 10)
            try {
                while (corriendo) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    realce.procesar(buf, 0, n)
                    var m = maximo
                    for (i in 0 until n) { val a = kotlin.math.abs(buf[i].toInt()); if (a > m) m = a }
                    maximo = m
                    meter(buf, n, fin = false)
                    sacar(false)
                }
                meter(buf, 0, fin = true)
                sacar(true)
            } catch (_: Throwable) {
            }
        }

        private fun meter(buf: ShortArray, n: Int, fin: Boolean) {
            var hecho = 0
            var intentos = 0
            while (true) {
                if (hecho >= n && !fin) return
                val i = codec.dequeueInputBuffer(10_000)
                if (i < 0) {
                    sacar(false)
                    if (++intentos > 300) return // tres segundos sin sitio: algo va mal, no se cuelga
                    continue
                }
                val entrada: ByteBuffer = codec.getInputBuffer(i) ?: return
                entrada.clear()
                val caben = minOf(n - hecho, entrada.remaining() / 2)
                val bb = entrada.order(ByteOrder.LITTLE_ENDIAN)
                for (k in 0 until caben) bb.putShort(buf[hecho + k])
                val pts = muestras * 1_000_000L / muestreo
                muestras += caben
                hecho += caben
                val ultimo = fin && hecho >= n
                codec.queueInputBuffer(i, 0, caben * 2, pts, if (ultimo) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                if (ultimo || (hecho >= n)) return
            }
        }

        private fun sacar(hastaElFinal: Boolean) {
            var esperas = 0
            while (true) {
                val o = codec.dequeueOutputBuffer(info, if (hastaElFinal) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!hastaElFinal || ++esperas > 300) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!arrancado) { pista = muxer.addTrack(codec.outputFormat); muxer.start(); arrancado = true }
                    }
                    o >= 0 -> {
                        val salida = codec.getOutputBuffer(o)
                        if (salida != null && info.size > 0 && arrancado && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            salida.position(info.offset); salida.limit(info.offset + info.size)
                            muxer.writeSampleData(pista, salida, info)
                            escritas++
                        }
                        codec.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }
        }

        override fun parar(): Boolean {
            corriendo = false
            runCatching { record.stop() }
            runCatching { hilo.join(3000) }
            runCatching { record.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            val bien = arrancado && escritas > 0
            runCatching { if (arrancado) muxer.stop() }
            runCatching { muxer.release() }
            return bien
        }

        companion object {
            /** Empieza a grabar en [destino] con el realce; null si este teléfono no deja. */
            @SuppressLint("MissingPermission")
            fun empezar(destino: File, muestreo: Int, bitsPorSegundo: Int): Realzado? {
                var record: AudioRecord? = null
                var codec: MediaCodec? = null
                var muxer: MediaMuxer? = null
                return runCatching {
                    val minimo = AudioRecord.getMinBufferSize(muestreo, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimo <= 0) return null
                    record = AudioRecord(MediaRecorder.AudioSource.MIC, muestreo, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, muestreo / 5) * 2)
                    if (record!!.state != AudioRecord.STATE_INITIALIZED) error("sin micrófono")
                    val formato = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, muestreo, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitsPorSegundo)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, muestreo / 5 * 2)
                    }
                    codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                        configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start()
                    }
                    muxer = MediaMuxer(destino.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    record!!.startRecording()
                    if (record!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("ocupado")
                    Realzado(record!!, codec!!, muxer!!, muestreo)
                }.getOrElse {
                    runCatching { record?.release() }; runCatching { codec?.release() }; runCatching { muxer?.release() }
                    destino.delete()
                    null
                }
            }
        }
    }
}
