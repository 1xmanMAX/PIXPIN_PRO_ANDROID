package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * **Una nota de voz, pasada a texto en el propio teléfono.**
 *
 * Sin servidores y sin pagar: el reconocedor que Google trae en Android desde la
 * versión 13 —el mismo que dicta en el teclado— sabe leer **un archivo** en vez del
 * micrófono ([RecognizerIntent.EXTRA_AUDIO_SOURCE]) y trocearlo en frases
 * ([RecognizerIntent.EXTRA_SEGMENTED_SESSION]), y con [SpeechRecognizer.createOnDeviceSpeechRecognizer]
 * lo hace **sin red**, con el idioma que el usuario tenga descargado. Es lo más preciso que
 * hay gratis y en local: el modelo es el de Google, no uno pequeño metido en la aplicación.
 *
 * El reconocedor quiere PCM de 16 bits, así que el `.m4a` de la nota se decodifica con
 * [MediaCodec] a mono de 16 kHz y se le manda por una tubería mientras se va decodificando:
 * una nota de diez minutos no se carga entera en memoria.
 *
 * Todo lo del reconocedor pasa por el hilo principal, que es donde exige vivir.
 */
object Transcriptor {

    /** El reconocedor lee a esta frecuencia; lo demás se remuestrea aquí. */
    private const val HERCIOS = 16_000

    /** Si este aparato sabe transcribir un archivo. Hace falta Android 13. */
    fun disponible(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isRecognitionAvailable(context)

    /** Si lo hace sin red, con el modelo descargado en el aparato. */
    fun enLocal(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** En qué acabó: el texto, o por qué no. */
    sealed class Resultado {
        class Texto(val texto: String) : Resultado()
        /** El idioma no está en el aparato; se ha pedido su descarga. */
        object DescargandoIdioma : Resultado()
        class Fallo(val codigo: Int) : Resultado()
    }

    /**
     * Transcribe [archivo] y llama a [alTerminar] en el hilo principal. Puede llamarse
     * desde cualquier hilo.
     */
    fun transcribir(
        context: Context,
        archivo: File,
        idioma: String = Locale.getDefault().toLanguageTag(),
        alTerminar: (Resultado) -> Unit
    ) {
        val principal = Handler(Looper.getMainLooper())
        if (!disponible(context)) {
            principal.post { alTerminar(Resultado.Fallo(-1)) }
            return
        }
        principal.post { arrancar(context.applicationContext, archivo, idioma, alTerminar) }
    }

    private fun arrancar(context: Context, archivo: File, idioma: String, alTerminar: (Resultado) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { alTerminar(Resultado.Fallo(-1)); return }
        val tubo = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
        if (tubo == null) { alTerminar(Resultado.Fallo(-2)); return }
        val (lectura, escritura) = tubo

        val reconocedor = runCatching {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        if (reconocedor == null) { lectura.close(); escritura.close(); alTerminar(Resultado.Fallo(-3)); return }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, lectura)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, HERCIOS)
            // Por frases: sin esto el reconocedor se para en el primer silencio y de una
            // nota de dos minutos sale la primera frase.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

        val trozos = ArrayList<String>()
        var acabado = false
        fun terminar(r: Resultado) {
            if (acabado) return
            acabado = true
            runCatching { reconocedor.destroy() }
            runCatching { lectura.close() }
            alTerminar(r)
        }
        fun mejorDe(b: Bundle?): String? =
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        fun loQueHay(): Resultado =
            if (trozos.isEmpty()) Resultado.Fallo(SpeechRecognizer.ERROR_NO_MATCH) else Resultado.Texto(trozos.joinToString(" "))

        reconocedor.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(segmentResults: Bundle) { mejorDe(segmentResults)?.let { trozos += it } }
            override fun onEndOfSegmentedSession() { terminar(loQueHay()) }
            override fun onResults(results: Bundle?) { mejorDe(results)?.let { trozos += it }; terminar(loQueHay()) }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                    // El modelo del idioma no está en el aparato: se pide, y la próxima vez sale.
                    runCatching { reconocedor.triggerModelDownload(intent) }
                    terminar(Resultado.DescargandoIdioma)
                } else if (trozos.isNotEmpty()) {
                    terminar(loQueHay())
                } else {
                    terminar(Resultado.Fallo(error))
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        reconocedor.startListening(intent)

        // La decodificación, aparte: escribe en la tubería y la cierra al acabar, que es lo
        // que le dice al reconocedor que la nota se ha terminado.
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(escritura).use { salida ->
                runCatching { decodificar(archivo, salida) }
            }
        }.start()
    }

    /**
     * De lo que haya en el archivo a PCM de 16 bits, mono, a [HERCIOS]. Mezcla los canales
     * y remuestrea a saltos (interpolación lineal): para la voz sobra.
     */
    internal fun decodificar(archivo: File, salida: OutputStream) {
        val extractor = MediaExtractor()
        extractor.setDataSource(archivo.absolutePath)
        var pista = -1
        var formato: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { pista = i; formato = f; break }
        }
        if (pista < 0 || formato == null) { extractor.release(); return }
        extractor.selectTrack(pista)
        val codec = MediaCodec.createDecoderByType(formato.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(formato, null, null, 0)
        codec.start()
        var hercios = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var canales = formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var entradaAcabada = false
        var salidaAcabada = false
        val remuestreador = Remuestreador(salida)
        try {
            while (!salidaAcabada) {
                if (!entradaAcabada) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            entradaAcabada = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        remuestreador.meter(buf, hercios, canales)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) salidaAcabada = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        hercios = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        canales = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            remuestreador.vaciar()
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { extractor.release() }
        }
    }

    /** Mezcla a mono y lleva a [HERCIOS] con interpolación lineal, escribiendo PCM de 16 bits. */
    private class Remuestreador(private val salida: OutputStream) {
        private var posicion = 0.0     // en muestras de entrada
        private var ultima = 0f
        private var hayUltima = false
        private val paquete = ByteArray(4096)
        private var n = 0

        fun meter(buf: ByteBuffer, hercios: Int, canales: Int) {
            val cortos = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
            val cuantas = cortos.remaining() / canales.coerceAtLeast(1)
            if (cuantas == 0) return
            val mono = FloatArray(cuantas)
            for (i in 0 until cuantas) {
                var suma = 0f
                for (c in 0 until canales) suma += cortos.get(i * canales + c).toFloat()
                mono[i] = suma / canales
            }
            val paso = hercios.toDouble() / HERCIOS
            // La muestra anterior al primer bloque, para interpolar sin corte entre bloques.
            var p = posicion
            while (p < cuantas) {
                val i = p.toInt()
                val f = (p - i).toFloat()
                val a = if (i == 0) (if (hayUltima) ultima else mono[0]) else mono[i - 1]
                val b = mono[i]
                // `a` es la muestra anterior a `b`; se interpola entre las dos al avanzar.
                val v = (a + (b - a) * f)
                escribir(v)
                p += paso
            }
            posicion = p - cuantas
            ultima = mono[cuantas - 1]
            hayUltima = true
        }

        private fun escribir(v: Float) {
            val s = v.toInt().coerceIn(-32768, 32767)
            paquete[n++] = (s and 0xFF).toByte()
            paquete[n++] = ((s shr 8) and 0xFF).toByte()
            if (n >= paquete.size) { salida.write(paquete, 0, n); n = 0 }
        }

        fun vaciar() { if (n > 0) { salida.write(paquete, 0, n); n = 0 }; salida.flush() }
    }
}
