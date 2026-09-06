package com.forge.pixpin.guardados

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * **Vosk: el reconocedor de voz que sí lee un archivo entero, en el teléfono.**
 *
 * El de Google que trae Android se quedaba con la primera frase de cada sesión y decía
 * «idioma no disponible» con el idioma descargado, porque el paquete que instala el
 * teléfono («es-PE», «es-419») no cuadra con la etiqueta que se le pide. Vosk (Kaldi, código
 * abierto, Apache-2) no tiene nada de eso: se le da el audio en PCM de 16 kHz y devuelve
 * el texto **con el tiempo de cada palabra**, frase a frase, hasta el final. Es lo que
 * usan los transcriptores gratuitos de escritorio. Lo pidió el usuario (5-sep-2026):
 * «busca soluciones en la web, GitHub y foros».
 *
 * El modelo del idioma (unos 40 MB) se baja **una sola vez** de alphacephei.com a la
 * carpeta de la aplicación; después todo va sin red.
 */
object MotorVosk {

    /** Los modelos pequeños de Vosk, por idioma. Ver https://alphacephei.com/vosk/models */
    private val MODELOS = mapOf(
        "es" to "vosk-model-small-es-0.42",
        "en" to "vosk-model-small-en-us-0.15",
        "pt" to "vosk-model-small-pt-0.3",
        "fr" to "vosk-model-small-fr-0.22",
        "de" to "vosk-model-small-de-0.15",
        "it" to "vosk-model-small-it-0.22",
        "ca" to "vosk-model-small-ca-0.4"
    )
    private const val DESCARGAS = "https://alphacephei.com/vosk/models/"
    const val HERCIOS = 16_000f

    /** El modelo cargado en memoria: cargar tarda un par de segundos y se reutiliza. */
    @Volatile private var cargado: Pair<String, Model>? = null

    /** El nombre del modelo para un idioma («es-PE» → el de español); español si no hay. */
    fun modeloPara(idioma: String): String = MODELOS[idioma.substringBefore('-').lowercase()] ?: MODELOS.getValue("es")

    private fun carpeta(context: Context) = File(context.filesDir, "vosk").apply { mkdirs() }
    private fun carpetaDe(context: Context, modelo: String) = File(carpeta(context), modelo)

    /** Si el modelo de ese idioma ya está en el aparato. */
    fun modeloListo(context: Context, idioma: String): Boolean =
        File(carpetaDe(context, modeloPara(idioma)), ".listo").exists()

    /**
     * Baja y desempaqueta el modelo si falta. [avance] va de 0 a 1. Devuelve la carpeta, o
     * null si no se pudo (sin red, sin sitio). Hilo de fondo.
     */
    fun asegurarModelo(context: Context, idioma: String, avance: (Float) -> Unit = {}): File? {
        val modelo = modeloPara(idioma)
        val destino = carpetaDe(context, modelo)
        if (File(destino, ".listo").exists()) return destino
        val zip = File(context.cacheDir, "$modelo.zip")
        return runCatching {
            val conexion = URL(DESCARGAS + modelo + ".zip").openConnection() as HttpURLConnection
            conexion.connectTimeout = 15_000; conexion.readTimeout = 60_000
            val total = conexion.contentLengthLong.coerceAtLeast(1)
            conexion.inputStream.use { entrada ->
                zip.outputStream().buffered().use { salida ->
                    val buf = ByteArray(64 * 1024)
                    var leidos = 0L
                    while (true) {
                        val n = entrada.read(buf); if (n < 0) break
                        salida.write(buf, 0, n); leidos += n
                        avance(0.9f * leidos / total)
                    }
                }
            }
            // El zip trae una carpeta con el nombre del modelo; se desempaqueta en la nuestra.
            destino.deleteRecursively(); carpeta(context).mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val ruta = e.name.substringAfter('/', "")
                    if (ruta.isBlank()) { z.closeEntry(); continue }
                    val f = File(destino, ruta)
                    if (!f.canonicalPath.startsWith(destino.canonicalPath)) { z.closeEntry(); continue }
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    z.closeEntry()
                }
            }
            zip.delete()
            File(destino, ".listo").writeText(modelo)
            avance(1f)
            destino
        }.getOrElse { zip.delete(); null }
    }

    /** El enlace directo del modelo de un idioma, para bajarlo con el navegador e importarlo. */
    fun enlaceDelModelo(idioma: String): String = DESCARGAS + modeloPara(idioma) + ".zip"

    /**
     * Un `vosk-model-*.zip` bajado a mano (el navegador, otro aparato): se desempaqueta
     * como si lo hubiera bajado la aplicación. Devuelve el nombre del modelo, o null.
     */
    fun instalarZip(context: Context, entrada: java.io.InputStream): String? = runCatching {
        var modelo: String? = null
        val temporal = File(context.cacheDir, "vosk-importado-${System.nanoTime()}")
        temporal.mkdirs()
        ZipInputStream(entrada.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val primero = e.name.substringBefore('/')
                if (modelo == null && primero.startsWith("vosk-model")) modelo = primero
                val ruta = e.name.substringAfter('/', "")
                if (ruta.isBlank()) { z.closeEntry(); continue }
                val f = File(temporal, ruta)
                if (!f.canonicalPath.startsWith(temporal.canonicalPath)) { z.closeEntry(); continue }
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                z.closeEntry()
            }
        }
        val nombre = modelo ?: run { temporal.deleteRecursively(); return null }
        if (!File(temporal, "am").exists()) { temporal.deleteRecursively(); return null }
        val destino = carpetaDe(context, nombre)
        destino.deleteRecursively(); carpeta(context).mkdirs()
        if (!temporal.renameTo(destino)) { temporal.copyRecursively(destino, overwrite = true); temporal.deleteRecursively() }
        File(destino, ".listo").writeText(nombre)
        cargado = null
        nombre
    }.getOrNull()

    @Serializable
    private class PalabraVosk(val word: String = "", val start: Double = 0.0, val end: Double = 0.0, val conf: Double = 1.0)
    @Serializable
    private class ResultadoVosk(val text: String = "", val result: List<PalabraVosk> = emptyList())
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reconoce un archivo PCM (16 bits, mono, 16 kHz) entero. Devuelve una frase por
     * segmento con el milisegundo en que empieza. [avance] de 0 a 1. Hilo de fondo.
     */
    fun reconocer(context: Context, pcm: File, idioma: String, avance: (Float) -> Unit = {}): List<Transcriptor.Segmento>? {
        val carpeta = asegurarModelo(context, idioma) ?: return null
        val modelo = synchronized(this) {
            cargado?.takeIf { it.first == carpeta.absolutePath }?.second ?: run {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                Model(carpeta.absolutePath).also { cargado = carpeta.absolutePath to it }
            }
        }
        val segmentos = ArrayList<Transcriptor.Segmento>()
        val total = pcm.length().coerceAtLeast(1)
        fun apuntar(texto: String) {
            val r = runCatching { json.decodeFromString<ResultadoVosk>(texto) }.getOrNull() ?: return
            if (r.text.isBlank()) return
            val desde = ((r.result.firstOrNull()?.start ?: 0.0) * 1000).toInt()
            segmentos += Transcriptor.Segmento(desde, r.text.trim())
        }
        Recognizer(modelo, HERCIOS).use { rec ->
            rec.setWords(true)
            pcm.inputStream().buffered().use { entrada ->
                val buf = ByteArray(8000)   // un cuarto de segundo
                var leidos = 0L
                while (true) {
                    val n = entrada.read(buf); if (n < 0) break
                    if (rec.acceptWaveForm(buf, n)) apuntar(rec.result)
                    leidos += n
                    avance(leidos.toFloat() / total)
                }
            }
            apuntar(rec.finalResult)
        }
        return segmentos
    }
}
