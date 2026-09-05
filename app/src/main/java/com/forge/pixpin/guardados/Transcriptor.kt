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
import java.io.RandomAccessFile
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
 * **Cualquier formato**: el archivo —`.m4a` nuestro, `.ogg` de WhatsApp, `.mp3`, `.wav`,
 * `.amr`— se decodifica con [MediaCodec] a PCM mono de 16 kHz, que es lo que el reconocedor
 * quiere, y se guarda en un archivo temporal.
 *
 * **Por trozos**: una grabación de una hora no se le manda de una vez. Se parte en trozos de
 * menos de un minuto, cortando **donde hay silencio** —la ventana más callada de los últimos
 * segundos del trozo— para no partir una palabra, y se reconoce trozo a trozo, uno detrás de
 * otro; el texto es la suma. Ver [cortes].
 *
 * Todo lo del reconocedor pasa por el hilo principal, que es donde exige vivir.
 */
object Transcriptor {

    /** El reconocedor lee a esta frecuencia; lo demás se remuestrea aquí. */
    const val HERCIOS = 16_000
    private const val BYTES_POR_SEGUNDO = HERCIOS * 2

    /** Lo que mide un trozo como mucho, y dónde se busca el silencio para cortarlo. */
    private const val TROZO_SEGUNDOS = 15
    private const val BUSQUEDA_SEGUNDOS = 5
    private const val VENTANA_MS = 20
    /** Un silencio cuenta a partir de aquí; y un trozo no baja de esto (se pega al anterior). */
    private const val SILENCIO_MS = 250
    private const val MINIMO_SEGUNDOS = 1
    /** Silencio: menos que esta parte de la energía típica, y nunca por debajo de un suelo fijo. */
    private const val PARTE_DE_SILENCIO = 0.12
    private const val UMBRAL_MINIMO = 120.0

    /** Si este aparato sabe transcribir un archivo. Hace falta Android 13. */
    fun disponible(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isRecognitionAvailable(context)

    /** Si lo hace sin red, con el modelo descargado en el aparato. */
    fun enLocal(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** En qué acabó: el texto, o por qué no. */
    sealed class Resultado {
        /** [avisos]: cuántos trozos no se entendieron; con alguno, el texto tiene huecos. */
        class Texto(val texto: String, val avisos: Int = 0) : Resultado()
        /** El idioma no está en el aparato; se ha pedido su descarga. */
        object DescargandoIdioma : Resultado()
        class Fallo(val codigo: Int) : Resultado()
    }

    /**
     * Transcribe [archivo] y llama a [alTerminar] en el hilo principal. Puede llamarse
     * desde cualquier hilo. [avance] recibe, de 0 a 1, cuánto lleva.
     */
    fun transcribir(
        context: Context,
        archivo: File,
        idioma: String = Locale.getDefault().toLanguageTag(),
        avance: (Float) -> Unit = {},
        alTerminar: (Resultado) -> Unit
    ) {
        val principal = Handler(Looper.getMainLooper())
        if (!disponible(context)) {
            principal.post { alTerminar(Resultado.Fallo(-1)) }
            return
        }
        val app = context.applicationContext
        Thread {
            val pcm = File(app.cacheDir, "pcm-${System.nanoTime()}.raw")
            val bien = runCatching { pcm.outputStream().buffered().use { decodificar(archivo, it) } }.isSuccess && pcm.length() > 0
            if (!bien) {
                pcm.delete()
                principal.post { alTerminar(Resultado.Fallo(-2)) }
                return@Thread
            }
            val trozos = cortes(pcm)
            principal.post {
                reconocerTrozos(app, pcm, trozos, 0, idioma, ArrayList(), avance, 0) { r ->
                    pcm.delete()
                    alTerminar(r)
                }
            }
        }.start()
    }

    /** Un trozo tras otro; el resultado se junta al final. En el hilo principal. */
    private fun reconocerTrozos(
        context: Context, pcm: File, trozos: List<LongRange>, i: Int, idioma: String,
        textos: ArrayList<String>, avance: (Float) -> Unit, avisos: Int, alTerminar: (Resultado) -> Unit
    ) {
        if (i >= trozos.size) {
            alTerminar(if (textos.isEmpty()) Resultado.Fallo(SpeechRecognizer.ERROR_NO_MATCH) else Resultado.Texto(textos.joinToString(" "), avisos))
            return
        }
        var fallos = avisos
        reconocer(context, pcm, trozos[i], idioma) { r ->
            when (r) {
                is Resultado.Texto -> textos += r.texto
                is Resultado.DescargandoIdioma -> { alTerminar(r); return@reconocer }
                is Resultado.Fallo -> {
                    // Un trozo que no se entiende (silencio, ruido) no tira los demás; se
                    // cuenta, para decir que el texto tiene huecos.
                    if (r.codigo != SpeechRecognizer.ERROR_NO_MATCH && r.codigo != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && textos.isEmpty() && i == 0) {
                        alTerminar(r); return@reconocer
                    }
                    if (r.codigo != SpeechRecognizer.ERROR_NO_MATCH && r.codigo != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) fallos++
                }
            }
            avance((i + 1).toFloat() / trozos.size)
            reconocerTrozos(context, pcm, trozos, i + 1, idioma, textos, avance, fallos, alTerminar)
        }
    }

    /** Una sesión del reconocedor sobre un tramo del PCM. En el hilo principal. */
    private fun reconocer(context: Context, pcm: File, tramo: LongRange, idioma: String, alTerminar: (Resultado) -> Unit) {
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

        // El tramo del PCM, por la tubería y aparte; cerrarla es decirle al reconocedor
        // que el trozo se ha terminado.
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(escritura).use { salida ->
                runCatching {
                    RandomAccessFile(pcm, "r").use { raf ->
                        raf.seek(tramo.first)
                        var quedan = tramo.last - tramo.first + 1
                        val buf = ByteArray(16 * 1024)
                        while (quedan > 0) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), quedan).toInt())
                            if (n <= 0) break
                            salida.write(buf, 0, n)
                            quedan -= n
                        }
                    }
                }
            }
        }.start()
    }

    /**
     * En qué tramos (bytes, PCM de 16 bits mono a [HERCIOS]) se parte el archivo: **por
     * frases**.
     *
     * El reconocedor devuelve **una** frase por sesión aunque se le pida la sesión por
     * segmentos —así se comportaba en el teléfono: de una nota de un minuto salían las tres
     * primeras palabras—. De modo que cada trozo tiene que ser una frase: se mide la energía
     * cada 20 ms, se llama silencio a lo que queda por debajo de una fracción de la energía
     * típica de la nota (es adaptativo: una grabación baja no es todo silencio), y se corta
     * en mitad de cada silencio de más de un cuarto de segundo. Los trozos cortos se pegan
     * al anterior y los largos se parten en su punto más callado. Cada trozo se reconoce
     * por su cuenta y el texto es la suma. Ver [reconocerTrozos].
     */
    internal fun cortes(pcm: File): List<LongRange> {
        val total = pcm.length() and 1L.inv()
        if (total <= 0) return emptyList()
        val ventana = (VENTANA_MS * BYTES_POR_SEGUNDO / 1000) and 1.inv()
        // La energía (RMS) de cada ventana, en un solo paseo.
        val energias = ArrayList<Double>((total / ventana).toInt() + 1)
        RandomAccessFile(pcm, "r").use { raf ->
            val buf = ByteArray(ventana)
            var pos = 0L
            while (pos + ventana <= total) {
                raf.seek(pos); raf.readFully(buf)
                val cortos = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                var suma = 0.0
                for (k in 0 until cortos.remaining()) { val v = cortos.get(k).toDouble(); suma += v * v }
                energias += Math.sqrt(suma / cortos.remaining().coerceAtLeast(1))
                pos += ventana
            }
        }
        if (energias.isEmpty()) return listOf(0 until total)
        val ordenadas = energias.sorted()
        val tipica = ordenadas[(ordenadas.size * 0.6).toInt().coerceIn(0, ordenadas.size - 1)]
        val umbral = maxOf(UMBRAL_MINIMO, tipica * PARTE_DE_SILENCIO)
        val minimoDeSilencio = SILENCIO_MS / VENTANA_MS
        // Dónde acaba cada frase: la mitad de cada silencio suficientemente largo.
        val puntos = ArrayList<Long>()
        var calladasDesde = -1
        for ((k, e) in energias.withIndex()) {
            val callada = e < umbral
            if (callada && calladasDesde < 0) calladasDesde = k
            if (!callada && calladasDesde >= 0) {
                if (k - calladasDesde >= minimoDeSilencio) puntos += ((calladasDesde + k) / 2).toLong() * ventana
                calladasDesde = -1
            }
        }
        // Trozos entre puntos; los cortos se pegan al anterior, los largos se parten.
        val trozo = TROZO_SEGUNDOS.toLong() * BYTES_POR_SEGUNDO
        val minimo = MINIMO_SEGUNDOS.toLong() * BYTES_POR_SEGUNDO
        val salida = ArrayList<LongRange>()
        var desde = 0L
        RandomAccessFile(pcm, "r").use { raf ->
            for (corte in puntos + total) {
                var fin = corte
                if (fin <= desde) continue
                if (fin - desde < minimo && salida.isNotEmpty() && fin != total) continue
                while (fin - desde > trozo) {
                    val tope = desde + trozo
                    val enMedio = puntoMasCallado(raf, tope - BUSQUEDA_SEGUNDOS.toLong() * BYTES_POR_SEGUNDO, tope)
                    salida += desde until enMedio
                    desde = enMedio
                }
                if (fin - desde < minimo && salida.isNotEmpty()) {
                    val anterior = salida.removeAt(salida.size - 1)
                    salida += anterior.first until fin
                } else salida += desde until fin
                desde = fin
            }
        }
        return salida.filter { !it.isEmpty() }
    }

    /** El principio de la ventana de [VENTANA_MS] con menos energía entre [a] y [b] (bytes, pares). */
    private fun puntoMasCallado(raf: RandomAccessFile, a: Long, b: Long): Long {
        val ventana = (VENTANA_MS * BYTES_POR_SEGUNDO / 1000) and 1.inv()
        val desde = a.coerceAtLeast(0) and 1L.inv()
        val largo = (b - desde).toInt()
        if (largo <= ventana) return b and 1L.inv()
        val datos = ByteArray(largo)
        raf.seek(desde)
        raf.readFully(datos)
        val cortos = ByteBuffer.wrap(datos).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var mejor = b
        var menor = Double.MAX_VALUE
        var i = 0
        while (i + ventana <= largo) {
            var energia = 0.0
            var j = i / 2
            val fin = (i + ventana) / 2
            while (j < fin) { val v = cortos.get(j).toDouble(); energia += v * v; j++ }
            if (energia < menor) { menor = energia; mejor = desde + i }
            i += ventana / 2
        }
        return mejor and 1L.inv()
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
            var p = posicion
            while (p < cuantas) {
                val i = p.toInt()
                val f = (p - i).toFloat()
                val a = if (i == 0) (if (hayUltima) ultima else mono[0]) else mono[i - 1]
                val b = mono[i]
                escribir(a + (b - a) * f)
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
