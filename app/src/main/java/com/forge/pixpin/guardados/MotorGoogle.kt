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
 * - **Cada sesión cuesta**: crear el reconocedor, que arranque, y esperar a que remate.
 *   Al principio iba una frase por sesión y una nota de cinco minutos tardaba otros
 *   cinco; el usuario lo notó (6-sep-2026). Ahora las frases ([Transcriptor.cortes]) se
 *   juntan en tandas de hasta [TANDA_SEGUNDOS] ([Transcriptor.agrupar]) y cada tanda va
 *   en una sesión **por tramos** ([RecognizerIntent.EXTRA_SEGMENTED_SESSION]): el
 *   reconocedor devuelve un resultado por cada pausa que oye, y esos resultados se casan
 *   en orden con las frases de la tanda para ponerles su minuto ([casar]).
 * - **El audio se le da a toda prisa**, no al ritmo del habla: la tubería se llena (unos
 *   dos segundos) y la escritura se queda esperando a que el reconocedor lea, así que no
 *   se pierde nada y el que marca el paso es él.
 *
 * Todo lo del reconocedor vive en el hilo principal; desde el hilo de la transcripción se
 * espera con un cerrojo.
 */
object MotorGoogle {

    private const val TANDA_SEGUNDOS = 20
    /** Margen sobre lo que dura la tanda: si el reconocedor la lee al ritmo del habla, aún le sobra. */
    private const val MARGEN_POR_TANDA_MS = 30_000L
    /** Tras darle todo el audio, cuánto silencio de eventos (ni parciales ni resultados) es «acabó». */
    private const val SILENCIO_TRAS_ENTREGAR_MS = 2_000L

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
        val tandas = Transcriptor.agrupar(Transcriptor.cortes(pcm), TANDA_SEGUNDOS)
        if (tandas.isEmpty()) return emptyList()
        val salida = ArrayList<Transcriptor.Segmento>()
        var fallosSeguidos = 0
        for ((i, tanda) in tandas.withIndex()) {
            val textos = unaSesion(context, pcm, tanda.first().first..tanda.last().last, etiqueta)
            if (textos == null) { fallosSeguidos++; if (fallosSeguidos >= 3 && salida.isEmpty()) return null }
            else {
                fallosSeguidos = 0
                salida += casar(tanda, textos)
            }
            avance((i + 1).toFloat() / tandas.size)
        }
        return salida
    }

    /**
     * **Qué minuto le toca a cada resultado** de una tanda. El reconocedor devuelve un
     * texto por pausa y la tanda son frases cortadas por pausa, así que casi siempre son
     * tantos textos como frases y van uno a uno. Si vienen menos (juntó dos frases), se
     * reparten a lo largo de la tanda; si vienen más, los sobrantes van con la última.
     */
    internal fun casar(tanda: List<LongRange>, textos: List<String>): List<Transcriptor.Segmento> {
        val limpios = textos.map { it.trim() }.filter { it.isNotEmpty() }
        if (limpios.isEmpty()) return emptyList()
        return limpios.mapIndexed { k, texto ->
            val frase = if (limpios.size <= tanda.size) k * tanda.size / limpios.size else minOf(k, tanda.size - 1)
            Transcriptor.Segmento(Transcriptor.msDe(tanda[frase].first), texto)
        }
    }

    /** Una sesión del reconocedor sobre un tramo: sus textos, uno por pausa (vacío si no entendió nada), o null si falló. */
    private fun unaSesion(context: Context, pcm: File, tramo: LongRange, etiqueta: String): List<String>? {
        val principal = Handler(Looper.getMainLooper())
        val cerrojo = CountDownLatch(1)
        val resultado = AtomicReference<List<String>?>(null)
        val topeMs = MARGEN_POR_TANDA_MS + Transcriptor.msDe(tramo.last - tramo.first + 1)
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
                if (ok) resultado.set(ArrayList(trozos)) else fallo.set(true)
                cerrojo.countDown()
            }
            fun mejorDe(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val vigilante = object : Runnable {
                override fun run() {
                    if (acabado) return
                    val callado = System.currentTimeMillis() - ultimoEvento
                    if ((entregado.get() && callado > SILENCIO_TRAS_ENTREGAR_MS) || callado > topeMs) terminar(true)
                    else reloj.postDelayed(this, 300)
                }
            }
            reloj.postDelayed(vigilante, 300)
            reconocedor.setRecognitionListener(object : RecognitionListener {
                override fun onSegmentResults(segmentResults: Bundle) { ultimoEvento = System.currentTimeMillis(); mejorDe(segmentResults)?.let { trozos += it } }
                override fun onEndOfSegmentedSession() { terminar(true) }
                // Un resultado «final» no cierra la sesión: en una sesión por tramos puede venir
                // uno por pausa; la cierra el fin de sesión o el silencio tras entregar todo.
                override fun onResults(results: Bundle?) { ultimoEvento = System.currentTimeMillis(); mejorDe(results)?.let { trozos += it } }
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
        // El tramo, tan deprisa como el reconocedor lo lea (la tubería frena la escritura
        // cuando se llena), y la tubería cerrada al final para que sepa que no hay más.
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(escritura).use { out ->
                runCatching {
                    RandomAccessFile(pcm, "r").use { raf ->
                        raf.seek(tramo.first)
                        var quedan = tramo.last - tramo.first + 1
                        val buf = ByteArray(Transcriptor.HERCIOS * 2 / 4)
                        while (quedan > 0) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), quedan).toInt())
                            if (n <= 0) break
                            out.write(buf, 0, n); quedan -= n
                        }
                    }
                }
            }
            entregado.set(true)
        }.start()
        cerrojo.await(topeMs + 10_000, TimeUnit.MILLISECONDS)
        return if (fallo.get()) null else resultado.get() ?: emptyList()
    }
}
