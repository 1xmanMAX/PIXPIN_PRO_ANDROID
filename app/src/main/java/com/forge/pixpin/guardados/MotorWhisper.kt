package com.forge.pixpin.guardados

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * **Whisper (tiny, multilingüe) en el teléfono, como alternativa a Vosk.**
 *
 * Whisper entiende mejor el habla natural y la puntuación; a cambio es más lento y el
 * modelo pesa más (unos 105 MB frente a 38). Va con sherpa-onnx (k2-fsa, Apache-2), que
 * trae Whisper convertido a ONNX y una biblioteca nativa para Android; los tres archivos
 * del modelo se bajan una vez de Hugging Face. Solo para teléfonos de 64 bits
 * (`arm64-v8a`): en uno de 32 no carga y se dice.
 *
 * Whisper oye **como mucho 30 segundos** de una vez: el audio se parte por frases con
 * [Transcriptor.cortes] y cada trozo se reconoce por su cuenta, con su minuto. Lo pidió el
 * usuario (6-sep-2026) para poder elegir entre los dos motores.
 */
object MotorWhisper {

    private const val REPOSITORIO = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/"
    private val ARCHIVOS = listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt")
    const val PESO_APROX_MB = 105
    const val PAGINA_DE_MODELOS = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models"

    @Volatile private var reconocedor: OfflineRecognizer? = null
    @Volatile private var idiomaCargado: String? = null

    fun carpeta(context: Context) = File(context.filesDir, "whisper/tiny").apply { mkdirs() }

    fun modeloListo(context: Context): Boolean = ARCHIVOS.all { File(carpeta(context), it).length() > 0 }

    /** Si este teléfono puede cargar la biblioteca nativa (solo 64 bits). */
    fun soportado(): Boolean = runCatching { System.loadLibrary("sherpa-onnx-jni"); true }.getOrDefault(false)

    /** Baja los archivos que falten. [avance] de 0 a 1. Null si no se pudo. */
    fun asegurarModelo(context: Context, avance: (Float) -> Unit = {}): File? {
        val destino = carpeta(context)
        if (modeloListo(context)) return destino
        val total = 105_000_000L
        var acumulado = 0L
        return runCatching {
            for (nombre in ARCHIVOS) {
                val archivo = File(destino, nombre)
                if (archivo.length() > 0) continue
                val temporal = File(destino, "$nombre.parte")
                val conexion = URL(REPOSITORIO + nombre).openConnection() as HttpURLConnection
                conexion.instanceFollowRedirects = true
                conexion.connectTimeout = 15_000; conexion.readTimeout = 60_000
                conexion.inputStream.use { entrada ->
                    temporal.outputStream().buffered().use { salida ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = entrada.read(buf); if (n < 0) break
                            salida.write(buf, 0, n); acumulado += n
                            avance((acumulado.toFloat() / total).coerceAtMost(0.99f))
                        }
                    }
                }
                if (!temporal.renameTo(archivo)) throw IllegalStateException("no se pudo dejar $nombre")
            }
            avance(1f)
            destino
        }.getOrNull()
    }

    /** Los enlaces directos de los tres archivos, para bajarlos con el navegador e importarlos. */
    fun enlaces(): List<String> = ARCHIVOS.map { REPOSITORIO + it }

    /** Un archivo del modelo bajado a mano: se guarda por su nombre si es uno de los tres. */
    fun instalarArchivo(context: Context, nombre: String, entrada: java.io.InputStream): Boolean {
        if (nombre !in ARCHIVOS) return false
        return runCatching {
            val destino = File(carpeta(context), nombre)
            val temporal = File(carpeta(context), "$nombre.parte")
            temporal.outputStream().use { entrada.copyTo(it) }
            temporal.renameTo(destino) || run { temporal.copyTo(destino, overwrite = true); temporal.delete(); true }
        }.getOrDefault(false).also { if (it) { reconocedor?.release(); reconocedor = null; idiomaCargado = null } }
    }

    private fun idiomaDe(idioma: String): String = idioma.substringBefore('-').lowercase().ifBlank { "es" }

    private fun reconocedorPara(context: Context, idioma: String): OfflineRecognizer {
        val lengua = idiomaDe(idioma)
        reconocedor?.takeIf { idiomaCargado == lengua }?.let { return it }
        synchronized(this) {
            reconocedor?.takeIf { idiomaCargado == lengua }?.let { return it }
            reconocedor?.release()
            val dir = carpeta(context)
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(dir, ARCHIVOS[0]).absolutePath,
                        decoder = File(dir, ARCHIVOS[1]).absolutePath,
                        language = lengua,
                        task = "transcribe"
                    ),
                    tokens = File(dir, ARCHIVOS[2]).absolutePath,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                    modelType = "whisper"
                )
            )
            val r = OfflineRecognizer(null, config)
            reconocedor = r; idiomaCargado = lengua
            return r
        }
    }

    /**
     * Reconoce un PCM (16 bits, mono, 16 kHz) entero, por frases. Devuelve un segmento por
     * trozo con su milisegundo, o null si no hay modelo. Hilo de fondo.
     */
    fun reconocer(context: Context, pcm: File, idioma: String, avance: (Float) -> Unit = {}): List<Transcriptor.Segmento>? {
        asegurarModelo(context) ?: return null
        val rec = reconocedorPara(context, idioma)
        val trozos = Transcriptor.cortes(pcm)
        if (trozos.isEmpty()) return emptyList()
        val salida = ArrayList<Transcriptor.Segmento>()
        RandomAccessFile(pcm, "r").use { raf ->
            for ((i, t) in trozos.withIndex()) {
                val largo = (t.last - t.first + 1).toInt()
                val bytes = ByteArray(largo)
                raf.seek(t.first); raf.readFully(bytes)
                val muestras = FloatArray(largo / 2)
                var j = 0
                while (j < muestras.size) {
                    val v = (bytes[2 * j].toInt() and 0xFF) or (bytes[2 * j + 1].toInt() shl 8)
                    muestras[j] = v.toShort() / 32768f
                    j++
                }
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(muestras, 16000)
                    rec.decode(stream)
                    val texto = rec.getResult(stream).text.trim()
                    if (texto.isNotEmpty()) salida += Transcriptor.Segmento((t.first * 1000 / (Transcriptor.HERCIOS * 2)).toInt(), texto)
                } finally {
                    stream.release()
                }
                avance((i + 1).toFloat() / trozos.size)
            }
        }
        return salida
    }
}
