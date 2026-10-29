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

    /**
     * **Pausa o sigue** sin soltar el micrófono (25-oct-2026, «Grabar clase», como la pausa del
     * PC): lo que suena en pausa no entra en el fichero, y al seguir la nota continúa sin hueco.
     * Por omisión no hace nada: quien no la necesita no la implementa.
     */
    fun pausar(pausa: Boolean) {}

    /**
     * **Por dónde va la nota**, en ms de sonido escrito (silencios de relleno incluidos, pausas no), o
     * null si este grabador no lo sabe (28-oct-2026, el latido del diario de «Grabar sin perder nada»:
     * compara esto con la hora de pared para ver si el teléfono congeló la app).
     */
    fun llevadoMs(): Long? = null

    /**
     * **La toma del PCM de la nota** (29-oct-2026, la escucha en vivo de la reunión: «no se abre otro
     * micrófono», `Grabadora::suscribir_pcm` del PC). [oido] recibe cada bloque ya realzado (mono, a
     * [muestreo]) con su posición en muestras de la nota (pausas fuera, silencios de relleno dentro),
     * **en el hilo del micrófono**: tiene que copiarlo y volver en el acto (una cola que tira lo que no
     * cabe), nunca esperar. null la quita. Sin PCM (la grabadora del sistema), no hace nada y da false.
     */
    fun oir(oido: ((muestras: ShortArray, cuantas: Int, posicion: Long) -> Unit)?): Boolean = false

    /** Las muestras por segundo de la nota (0 si no se sabe). */
    fun muestreo(): Int = 0

    /** La sesión de audio del micrófono (para ver si Android lo silencia por otra app), o null. */
    fun sesionDeAudio(): Int? = null

    class DelSistema(private val r: MediaRecorder) : Grabador {
        override fun pico() = runCatching { r.maxAmplitude }.getOrDefault(0).coerceAtLeast(0)
        override fun pausar(pausa: Boolean) {
            runCatching { if (pausa) r.pause() else r.resume() }
        }
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
        private var record: AudioRecord,
        private val codec: MediaCodec,
        private val muxer: MediaMuxer,
        private val muestreo: Int,
        private val fuente: Int,
        /**
         * **El respaldo WAV** de lo mismo que va al `.m4a` (28-oct-2026, «Grabar sin perder nada»): si
         * la app muere, de él se rehace la nota ([RehacerM4a]). Null en las notas cortas de siempre.
         */
        private val respaldo: RespaldoWav.EnHilo? = null,
        private val context: android.content.Context? = null
    ) : Grabador {
        @Volatile private var corriendo = true
        @Volatile private var maximo = 0
        /** En pausa se sigue leyendo el micrófono (no se suelta) pero no se escribe nada. */
        @Volatile private var enPausa = false
        private var pista = -1
        private var arrancado = false
        private var muestras = 0L
        private var escritas = 0
        private val info = MediaCodec.BufferInfo()
        private val realce = Realce(muestreo)
        private val hilo = Thread({ bucle() }, "pixpin-voz")
        /** Lo que se ha metido en el codificador, en muestras (lo lee el latido desde otro hilo). */
        @Volatile private var metidas = 0L
        /** Lo pide el aviso de aparatos: el micrófono que se usaba se fue (unos cascos Bluetooth). */
        @Volatile private var reabrirYa = false
        private val vigia: android.media.AudioDeviceCallback? = context?.let { vigilarAparatos(it) }

        init { hilo.start() }

        override fun pico(): Int { val m = maximo; maximo = 0; return m }

        override fun llevadoMs(): Long = metidas * 1000 / muestreo

        /** La escucha en vivo de la reunión (29-oct-2026). Ver [Grabador.oir]. */
        @Volatile private var oido: ((ShortArray, Int, Long) -> Unit)? = null

        override fun oir(oido: ((muestras: ShortArray, cuantas: Int, posicion: Long) -> Unit)?): Boolean {
            this.oido = oido
            return true
        }

        override fun muestreo(): Int = muestreo

        override fun sesionDeAudio(): Int? = runCatching { record.audioSessionId }.getOrNull()

        /**
         * **Los cascos que se van** (28-oct-2026): si el aparato por el que entraba el sonido desaparece
         * (Bluetooth fuera de alcance, el cable que se quita), se vuelve a abrir el micrófono en el acto
         * ([reabrir], que pone silencio al paso del reloj), sin esperar a que `read` falle: hay
         * teléfonos que siguen dando ceros del aparato muerto.
         */
        private fun vigilarAparatos(c: android.content.Context): android.media.AudioDeviceCallback? = runCatching {
            val am = c.getSystemService(android.media.AudioManager::class.java) ?: return null
            val cb = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(quitados: Array<out android.media.AudioDeviceInfo>?) {
                    val usado = runCatching { record.routedDevice?.id }.getOrNull()
                    if (quitados.orEmpty().any { it.isSource && (usado == null || it.id == usado) }) reabrirYa = true
                }
            }
            am.registerAudioDeviceCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
            cb
        }.getOrNull()

        override fun pausar(pausa: Boolean) { enPausa = pausa }

        private fun bucle() {
            // **Con la prioridad del audio** (25-oct-2026, lo que aprendió el PC de OBS y Oboe,
            // `docs/motor-de-audio.md` §8): un hilo normal pierde bloques cuando el teléfono va
            // cargado, y lo perdido no se recupera.
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
            val buf = ShortArray(muestreo / 10)
            try {
                while (corriendo) {
                    if (reabrirYa) { reabrirYa = false; reabrir(); continue }
                    val n = record.read(buf, 0, buf.size)
                    if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                        // El micrófono se fue (unos cascos que se quitan, otra app que lo coge): se
                        // vuelve a abrir, y el hueco se rellena con silencio **por el reloj**, como
                        // el motor del PC, para que lo de después siga en su minuto (las capturas
                        // de una clase van atadas a él).
                        reabrir()
                        continue
                    }
                    if (n <= 0) continue
                    if (enPausa) { sacar(false); continue }
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

        /** Vuelve a abrir el micrófono tras perderlo, con el hueco en silencio. Ver [bucle]. */
        private fun reabrir() {
            val desde = System.nanoTime()
            runCatching { record.release() }
            var nuevo: AudioRecord? = null
            var intentos = 0
            while (corriendo && nuevo == null && intentos++ < 50) {
                nuevo = abrirMicrofono(fuente, muestreo)?.takeIf {
                    runCatching { it.startRecording() }.isSuccess && it.recordingState == AudioRecord.RECORDSTATE_RECORDING
                } ?: run { runCatching { Thread.sleep(200) }; null }
            }
            if (nuevo == null) { corriendo = false; return }
            record = nuevo
            if (enPausa) return
            val perdidas = ((System.nanoTime() - desde) / 1_000_000L * muestreo / 1000L).coerceIn(0L, muestreo * 60L)
            val cero = ShortArray(muestreo / 10)
            var quedan = perdidas
            while (quedan > 0) {
                val k = minOf(quedan, cero.size.toLong()).toInt()
                meter(cero, k, fin = false)
                sacar(false)
                quedan -= k
            }
        }

        private fun meter(buf: ShortArray, n: Int, fin: Boolean) {
            // El mismo PCM al respaldo, en su hilo (no espera nunca), a la escucha en vivo (copia y
            // vuelve: si va tarde, pierde bloques ella sola) y la cuenta para el latido.
            if (n > 0) {
                respaldo?.escribir(buf, n)
                oido?.let { o -> runCatching { o(buf, n, metidas) } }
                metidas += n
            }
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
            vigia?.let { cb -> runCatching { context?.getSystemService(android.media.AudioManager::class.java)?.unregisterAudioDeviceCallback(cb) } }
            runCatching { record.stop() }
            runCatching { hilo.join(3000) }
            runCatching { respaldo?.cerrar() }
            runCatching { record.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            val bien = arrancado && escritas > 0
            runCatching { if (arrancado) muxer.stop() }
            runCatching { muxer.release() }
            return bien
        }

        companion object {
            /**
             * **Notas de voz y dictado**: `VOICE_RECOGNITION`, sin el control de ganancia ni la
             * supresión agresiva del fabricante; el realce lo pone [Realce], como el motor del PC.
             */
            const val PRESET_DE_VOZ = MediaRecorder.AudioSource.VOICE_RECOGNITION

            /** **Una clase** (el teléfono lejos, en la mesa): `CAMCORDER`, orientado al frente y con su AGC. */
            const val PRESET_DE_CLASE = MediaRecorder.AudioSource.CAMCORDER

            /**
             * **El micrófono con su preset** (25-oct-2026, `docs/motor-de-audio.md` §8 del PC): si
             * este teléfono no lo tiene, el `MIC` de siempre. Null si no hay micrófono.
             */
            @SuppressLint("MissingPermission")
            internal fun abrirMicrofono(fuente: Int, muestreo: Int): AudioRecord? {
                val minimo = AudioRecord.getMinBufferSize(muestreo, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimo <= 0) return null
                for (f in listOf(fuente, MediaRecorder.AudioSource.MIC).distinct()) {
                    val r = runCatching {
                        AudioRecord(f, muestreo, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, muestreo / 5) * 2)
                    }.getOrNull() ?: continue
                    if (r.state == AudioRecord.STATE_INITIALIZED) return r
                    runCatching { r.release() }
                }
                return null
            }

            /**
             * Empieza a grabar en [destino] con el realce; null si este teléfono no deja. [fuente]
             * es el preset del micrófono ([PRESET_DE_VOZ] o [PRESET_DE_CLASE]).
             */
            @SuppressLint("MissingPermission")
            fun empezar(
                destino: File, muestreo: Int, bitsPorSegundo: Int, fuente: Int = PRESET_DE_VOZ,
                /** Con respaldo WAV (una clase o una reunión): dónde. Ver [RespaldoWav]. */
                respaldo: File? = null,
                /** Para vigilar los aparatos de sonido (los cascos que se van). */
                context: android.content.Context? = null
            ): Realzado? {
                var record: AudioRecord? = null
                var codec: MediaCodec? = null
                var muxer: MediaMuxer? = null
                return runCatching {
                    record = abrirMicrofono(fuente, muestreo) ?: error("sin micrófono")
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
                    if (record!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        // Con el preset ocupado o rechazado, el micrófono de siempre.
                        runCatching { record?.release() }
                        record = abrirMicrofono(MediaRecorder.AudioSource.MIC, muestreo)?.also { it.startRecording() }
                        if (record?.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("ocupado")
                    }
                    // El respaldo, si no se puede crear (sin sitio), no para nada: la nota se graba igual.
                    val wav = respaldo?.let { r -> runCatching { RespaldoWav.EnHilo(r, muestreo) }.getOrNull() }
                    Realzado(record!!, codec!!, muxer!!, muestreo, fuente, wav, context?.applicationContext)
                }.getOrElse {
                    runCatching { record?.release() }; runCatching { codec?.release() }; runCatching { muxer?.release() }
                    destino.delete()
                    null
                }
            }
        }
    }
}
