package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * **El reconocedor de Google que trae el teléfono, leyendo de archivo.**
 *
 * Es el más preciso de los tres y no pesa nada, pero tiene sus condiciones, y por eso va
 * como opción y no como único camino:
 *
 * - Solo desde **Android 13** existe la puerta para darle un archivo en vez del micrófono
 *   ([RecognizerIntent.EXTRA_AUDIO_SOURCE]); antes de eso no hay manera.
 * - **El idioma tiene que ser uno de los instalados en el reconocedor**, con su etiqueta
 *   exacta: el teléfono dice «es-PE» y el paquete instalado es «es-US» o «es-ES». Se
 *   pregunta ([SpeechRecognizer.checkRecognitionSupport]) y se elige el instalado que más
 *   se parezca; si no hay ninguno del idioma pero sí se puede instalar, se pide la descarga.
 * - **Devuelve una frase por sesión.** Por eso el audio se parte por frases
 *   ([Transcriptor.cortes]) y cada trozo va en su sesión, uno tras otro.
 *
 * Todo lo del reconocedor vive en el hilo principal; desde el hilo de la transcripción se
 * espera con un cerrojo.
 */
object MotorGoogle {

    private const val TOPE_POR_TROZO_MS = 40_000L
    private const val SILENCIO_TRAS_ENTREGAR_MS = 2_500L

    fun disponible(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /** Qué pasó al buscar idioma: el instalado que vale, o por qué no. */
    sealed class Idioma {
        class Vale(val etiqueta: String) : Idioma()
        /** Ninguno instalado de esa lengua; se ha pedido la descarga de [etiqueta]. */
        class Descargando(val etiqueta: String) : Idioma()
        class NoHay(val instalados: List<String>) : Idioma()
    }

    /** El idioma instalado que más se parece a [pedido] («es-PE» → «es-US» si es lo que hay). Bloquea. */
    fun idiomaInstalado(context: Context, pedido: String): Idioma {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Idioma.NoHay(emptyList())
        val principal = Handler(Looper.getMainLooper())
        val cerrojo = CountDownLatch(1)
        val soporte = AtomicReference<RecognitionSupport?>(null)
        val reconocedor = AtomicReference<SpeechRecognizer?>(null)
        principal.post {
            runCatching {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                reconocedor.set(r)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, pedido)
                }
                r.checkRecognitionSupport(intent, { it.run() }, object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) { soporte.set(recognitionSupport); cerrojo.countDown() }
                    override fun onError(error: Int) { cerrojo.countDown() }
                })
            }.onFailure { cerrojo.countDown() }
        }
        cerrojo.await(8, TimeUnit.SECONDS)
        val s = soporte.get()
        principal.post { runCatching { reconocedor.get()?.destroy() } }
        if (s == null) return Idioma.NoHay(emptyList())
        val instalados = s.installedOnDeviceLanguages
        val lengua = pedido.substringBefore('-').lowercase()
        fun parecido(lista: List<String>): String? =
            lista.firstOrNull { it.equals(pedido, true) }
                ?: lista.firstOrNull { it.substringBefore('-').equals(lengua, true) }
        parecido(instalados)?.let { return Idioma.Vale(it) }
        val instalable = parecido(s.supportedOnDeviceLanguages) ?: return Idioma.NoHay(instalados)
        // Se pide su descarga: la próxima vez estará.
        principal.post {
            runCatching {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, instalable)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                }
                r.triggerModelDownload(intent)
                Handler(Looper.getMainLooper()).postDelayed({ runCatching { r.destroy() } }, 5_000)
            }
        }
        return Idioma.Descargando(instalable)
    }

    /**
     * Reconoce el PCM (16 bits, mono, 16 kHz) por frases. Devuelve los segmentos con su
     * milisegundo, o null si el reconocedor falló del todo. Bloquea: hilo de fondo.
     */
    fun reconocer(context: Context, pcm: File, etiqueta: String, avance: (Float) -> Unit = {}): List<Transcriptor.Segmento>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val trozos = Transcriptor.cortes(pcm)
        if (trozos.isEmpty()) return emptyList()
        val salida = ArrayList<Transcriptor.Segmento>()
        var fallosSeguidos = 0
        for ((i, t) in trozos.withIndex()) {
            val texto = unaSesion(context, pcm, t, etiqueta)
            if (texto == null) { fallosSeguidos++; if (fallosSeguidos >= 3 && salida.isEmpty()) return null }
            else {
                fallosSeguidos = 0
                if (texto.isNotBlank()) salida += Transcriptor.Segmento((t.first * 1000 / (Transcriptor.HERCIOS * 2)).toInt(), texto)
            }
            avance((i + 1).toFloat() / trozos.size)
        }
        return salida
    }

    /** Una sesión del reconocedor sobre un tramo: su texto («» si no entendió nada), o null si falló. */
    private fun unaSesion(context: Context, pcm: File, tramo: LongRange, etiqueta: String): String? {
        val principal = Handler(Looper.getMainLooper())
        val cerrojo = CountDownLatch(1)
        val resultado = AtomicReference<String?>(null)
        val fallo = AtomicBoolean(false)
        val tubo = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        val (lectura, escritura) = tubo
        val entregado = AtomicBoolean(false)

        principal.post {
            val reconocedor = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            if (reconocedor == null) { fallo.set(true); runCatching { lectura.close() }; cerrojo.countDown(); return@post }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, etiqueta)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, lectura)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Transcriptor.HERCIOS)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            val trozos = ArrayList<String>()
            var acabado = false
            var ultimoEvento = System.currentTimeMillis()
            val reloj = Handler(Looper.getMainLooper())
            fun terminar(ok: Boolean) {
                if (acabado) return
                acabado = true
                reloj.removeCallbacksAndMessages(null)
                runCatching { reconocedor.destroy() }
                runCatching { lectura.close() }
                if (ok) resultado.set(trozos.joinToString(" ")) else fallo.set(true)
                cerrojo.countDown()
            }
            fun mejorDe(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val vigilante = object : Runnable {
                override fun run() {
                    if (acabado) return
                    val callado = System.currentTimeMillis() - ultimoEvento
                    if ((entregado.get() && callado > SILENCIO_TRAS_ENTREGAR_MS) || callado > TOPE_POR_TROZO_MS) terminar(true)
                    else reloj.postDelayed(this, 300)
                }
            }
            reloj.postDelayed(vigilante, 300)
            reconocedor.setRecognitionListener(object : RecognitionListener {
                override fun onSegmentResults(segmentResults: Bundle) { ultimoEvento = System.currentTimeMillis(); mejorDe(segmentResults)?.let { trozos += it } }
                override fun onEndOfSegmentedSession() { terminar(true) }
                override fun onResults(results: Bundle?) { ultimoEvento = System.currentTimeMillis(); mejorDe(results)?.let { trozos += it }; if (entregado.get()) terminar(true) }
                override fun onError(error: Int) {
                    // Sin coincidencia o tiempo agotado es «no dijo nada en este trozo», no un fallo.
                    val nada = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    terminar(nada || trozos.isNotEmpty())
                }
                override fun onReadyForSpeech(params: Bundle?) { ultimoEvento = System.currentTimeMillis() }
                override fun onBeginningOfSpeech() { ultimoEvento = System.currentTimeMillis() }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { ultimoEvento = System.currentTimeMillis() }
                override fun onPartialResults(partialResults: Bundle?) { ultimoEvento = System.currentTimeMillis() }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            runCatching { reconocedor.startListening(intent) }.onFailure { terminar(false) }
        }
        // El tramo, a ritmo (el doble del habla), y la tubería cerrada al final.
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(escritura).use { out ->
                runCatching {
                    RandomAccessFile(pcm, "r").use { raf ->
                        raf.seek(tramo.first)
                        var quedan = tramo.last - tramo.first + 1
                        val buf = ByteArray(Transcriptor.HERCIOS * 2 / 10)
                        while (quedan > 0) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), quedan).toInt())
                            if (n <= 0) break
                            out.write(buf, 0, n); quedan -= n
                            Thread.sleep(50)
                        }
                    }
                }
            }
            entregado.set(true)
        }.start()
        cerrojo.await(TOPE_POR_TROZO_MS + 10_000, TimeUnit.MILLISECONDS)
        return if (fallo.get()) null else resultado.get() ?: ""
    }
}
