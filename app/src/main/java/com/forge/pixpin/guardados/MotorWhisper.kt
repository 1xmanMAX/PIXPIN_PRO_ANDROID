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
 * **Whisper (multilingüe) en el teléfono, como alternativa a Vosk.**
 *
 * Whisper entiende mejor el habla natural y la puntuación; a cambio es más lento y el
 * modelo pesa más. Va con sherpa-onnx (k2-fsa, Apache-2), que trae Whisper convertido a
 * ONNX y una biblioteca nativa para Android; los tres archivos del modelo se bajan una
 * vez de Hugging Face. Solo para teléfonos de 64 bits (`arm64-v8a`): en uno de 32 no
 * carga y se dice.
 *
 * **Tres tamaños** ([MODELOS]): `tiny` (104 MB, rápido, se equivoca bastante), `base`
 * (161 MB, el punto medio) y `small` (375 MB, el más fino y el más lento). El usuario
 * probó el `tiny` (6-sep-2026) y no era «el mejor» como esperaba: es el más pequeño que
 * existe.
 *
 * **El idioma se le dice**, no se le deja adivinar: con 99 idiomas y un modelo pequeño,
 * adivinaba lenguas que nada tenían que ver. Con un segundo idioma puesto, se le deja
 * adivinar en cada tanda y se acepta lo que diga únicamente si es uno de los dos; si no,
 * se repite con el primero. Ver [reconocer].
 *
 * **Y traduce.** Con el idioma forzado, lo que oye en otro lo pone en ese: se le dice
 * «español» y una frase dicha en inglés sale en español. Es un efecto del modelo, no una
 * función documentada, pero funciona y al usuario le gustó (6-sep-2026): por eso hay dos
 * modos, cada trozo en su idioma o todo en el primero. Para «todo en inglés» está la
 * tarea `translate` de verdad, que es la única que Whisper trae de fábrica.
 *
 * **Tandas de hasta [TANDA_SEGUNDOS]**: Whisper oye como mucho 30 segundos de una vez y
 * rellena con silencio hasta diez segundos más de lo que se le da, así que cuesta casi lo
 * mismo un trozo de dos segundos que uno de veinte. Las frases ([Transcriptor.cortes]) se
 * juntan en tandas ([Transcriptor.agrupar]) y cada tanda se reconoce por su cuenta, con
 * su minuto.
 */
object MotorWhisper {

    /** Los modelos que se pueden bajar, con su peso en MB. */
    val MODELOS = linkedMapOf("tiny" to 104, "base" to 161, "small" to 375)
    const val MODELO_POR_DEFECTO = "tiny"
    const val PAGINA_DE_MODELOS = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models"
    private const val TANDA_SEGUNDOS = 20
    private const val MINIMO_PARA_ADIVINAR_SEGUNDOS = 3

    private fun repositorio(modelo: String) = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$modelo/resolve/main/"
    private fun archivos(modelo: String) = listOf("$modelo-encoder.int8.onnx", "$modelo-decoder.int8.onnx", "$modelo-tokens.txt")

    @Volatile private var reconocedor: OfflineRecognizer? = null
    @Volatile private var modeloCargado: String? = null

    /** El modelo elegido en Ajustes («tiny» si no hay o no es uno de los tres). */
    fun modelo(context: Context): String =
        (context.applicationContext as? com.forge.pixpin.PixPinApp)?.ajustes?.modeloWhisper?.takeIf { it in MODELOS } ?: MODELO_POR_DEFECTO

    fun carpeta(context: Context, modelo: String = modelo(context)) = File(context.filesDir, "whisper/$modelo").apply { mkdirs() }

    fun modeloListo(context: Context, modelo: String = modelo(context)): Boolean =
        archivos(modelo).all { File(carpeta(context, modelo), it).length() > 0 }

    /** Si este teléfono puede cargar la biblioteca nativa (solo 64 bits). */
    fun soportado(): Boolean = runCatching { System.loadLibrary("sherpa-onnx-jni"); true }.getOrDefault(false)

    /** Baja los archivos que falten del modelo. [avance] de 0 a 1. Null si no se pudo. */
    fun asegurarModelo(context: Context, modelo: String = modelo(context), avance: (Float) -> Unit = {}): File? {
        val destino = carpeta(context, modelo)
        if (modeloListo(context, modelo)) return destino
        val total = (MODELOS[modelo] ?: 100) * 1_000_000L
        var acumulado = 0L
        return runCatching {
            for (nombre in archivos(modelo)) {
                val archivo = File(destino, nombre)
                if (archivo.length() > 0) continue
                val temporal = File(destino, "$nombre.parte")
                val conexion = URL(repositorio(modelo) + nombre).openConnection() as HttpURLConnection
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

    /** Los enlaces directos de los tres archivos del modelo, para bajarlos con el navegador e importarlos. */
    fun enlaces(modelo: String): List<String> = archivos(modelo).map { repositorio(modelo) + it }

    /** Un archivo del modelo bajado a mano: se guarda por su nombre si es de alguno de los tres modelos. */
    fun instalarArchivo(context: Context, nombre: String, entrada: java.io.InputStream): Boolean {
        val modelo = MODELOS.keys.firstOrNull { nombre in archivos(it) } ?: return false
        return runCatching {
            val destino = File(carpeta(context, modelo), nombre)
            val temporal = File(carpeta(context, modelo), "$nombre.parte")
            temporal.outputStream().use { entrada.copyTo(it) }
            temporal.renameTo(destino) || run { temporal.copyTo(destino, overwrite = true); temporal.delete(); true }
        }.getOrDefault(false).also { if (it) soltar() }
    }

    /** Se descarga el modelo de memoria (cambio de modelo, archivo nuevo). */
    private fun soltar() = synchronized(this) { reconocedor?.release(); reconocedor = null; modeloCargado = null }

    /** Los 99 códigos que entiende Whisper; otro cualquiera tumbaría la biblioteca nativa (`SHERPA_ONNX_EXIT`). */
    private val IDIOMAS = ("en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su yue").split(' ').toSet()

    private fun idiomaDe(idioma: String): String = idioma.substringBefore('-').lowercase().takeIf { it in IDIOMAS } ?: "es"

    private fun configuracion(context: Context, modelo: String, lengua: String, tarea: String = "transcribe"): OfflineRecognizerConfig {
        val dir = carpeta(context, modelo)
        val a = archivos(modelo)
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, a[0]).absolutePath,
                    decoder = File(dir, a[1]).absolutePath,
                    language = lengua,
                    task = tarea
                ),
                tokens = File(dir, a[2]).absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                modelType = "whisper"
            )
        )
    }

    /** El reconocedor con el modelo cargado (cargar cuesta segundos; se reutiliza). El idioma se cambia luego con `setConfig`. */
    private fun reconocedorPara(context: Context, modelo: String): OfflineRecognizer {
        reconocedor?.takeIf { modeloCargado == modelo }?.let { return it }
        synchronized(this) {
            reconocedor?.takeIf { modeloCargado == modelo }?.let { return it }
            reconocedor?.release()
            val r = OfflineRecognizer(null, configuracion(context, modelo, "es"))
            reconocedor = r; modeloCargado = modelo
            return r
        }
    }

    /**
     * Reconoce un PCM (16 bits, mono, 16 kHz) entero, por tandas de frases. Devuelve un
     * segmento por tanda con su milisegundo, o null si no hay modelo. Hilo de fondo.
     *
     * [idioma] es el primero. Con [segundo] no vacío y distinto:
     * - **cada uno en el suyo** ([todoEnUno] falso): cada tanda se reconoce sin idioma
     *   —Whisper adivina y devuelve el que oyó, en el mismo pase—; si es uno de los dos,
     *   vale tal cual; si dice otro, se repite la tanda con el primero. Solo se paga dos
     *   veces cuando se equivoca.
     * - **todo en uno** ([todoEnUno] cierto): todas las tandas con el primero forzado, que
     *   es lo que hace que lo dicho en el otro salga traducido; si el primero es inglés,
     *   con la tarea `translate`, que es la propia de Whisper.
     * Sin segundo idioma, todo con el primero. Cambiar de idioma es cambiar la
     * configuración del mismo reconocedor, no cargar otro modelo.
     */
    fun reconocer(context: Context, pcm: File, idioma: String, segundo: String = "", todoEnUno: Boolean = false, avance: (Float) -> Unit = {}): List<Transcriptor.Segmento>? {
        val modelo = modelo(context)
        asegurarModelo(context, modelo) ?: return null
        val rec = reconocedorPara(context, modelo)
        val tandas = Transcriptor.agrupar(Transcriptor.cortes(pcm), TANDA_SEGUNDOS)
        if (tandas.isEmpty()) return emptyList()
        val principal = idiomaDe(idioma)
        val otro = segundo.takeIf { it.isNotBlank() }?.let { idiomaDe(it) }?.takeIf { it != principal }
        val adivinar = otro != null && !todoEnUno
        val tarea = if (todoEnUno && otro != null && principal == "en") "translate" else "transcribe"
        val salida = ArrayList<Transcriptor.Segmento>()
        synchronized(this) {
            val conElPrincipal = configuracion(context, modelo, principal, tarea)
            val sinIdioma = configuracion(context, modelo, "")
            rec.setConfig(if (adivinar) sinIdioma else conElPrincipal)
            RandomAccessFile(pcm, "r").use { raf ->
                for ((i, tanda) in tandas.withIndex()) {
                    val desde = tanda.first().first
                    val hasta = tanda.last().last
                    val muestras = muestras(raf, desde, hasta)
                    var lengua = principal
                    var texto: String
                    if (adivinar && muestras.size >= MINIMO_PARA_ADIVINAR_SEGUNDOS * Transcriptor.HERCIOS) {
                        val r = decodificar(rec, muestras)
                        val oido = r.lang.trim().lowercase()
                        if (oido == principal || oido == otro) {
                            lengua = oido
                            texto = r.text
                        } else {
                            rec.setConfig(conElPrincipal)
                            texto = decodificar(rec, muestras).text
                            rec.setConfig(sinIdioma)
                        }
                    } else if (adivinar) {
                        // Muy corta para adivinar con tino: con el primero.
                        rec.setConfig(conElPrincipal)
                        texto = decodificar(rec, muestras).text
                        rec.setConfig(sinIdioma)
                    } else {
                        texto = decodificar(rec, muestras).text
                    }
                    texto = texto.trim()
                    if (Transcriptor.creible(texto, lengua)) salida += Transcriptor.Segmento(Transcriptor.msDe(desde), texto)
                    avance((i + 1).toFloat() / tandas.size)
                }
            }
        }
        return salida
    }

    private fun decodificar(rec: OfflineRecognizer, muestras: FloatArray): com.k2fsa.sherpa.onnx.OfflineRecognizerResult {
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(muestras, 16000)
            rec.decode(stream)
            return rec.getResult(stream)
        } finally {
            stream.release()
        }
    }

    /** Los bytes de [desde] a [hasta] como muestras de -1 a 1. */
    private fun muestras(raf: RandomAccessFile, desde: Long, hasta: Long): FloatArray {
        val largo = ((hasta - desde + 1) and 1L.inv()).toInt()
        val bytes = ByteArray(largo)
        raf.seek(desde); raf.readFully(bytes)
        val muestras = FloatArray(largo / 2)
        var j = 0
        while (j < muestras.size) {
            val v = (bytes[2 * j].toInt() and 0xFF) or (bytes[2 * j + 1].toInt() shl 8)
            muestras[j] = v.toShort() / 32768f
            j++
        }
        return muestras
    }
}
