package com.forge.pixpin.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import com.forge.pixpin.motor.DocumentoAnotado
import com.forge.pixpin.motor.DocxAHtml
import com.forge.pixpin.motor.DrawSvg
import com.forge.pixpin.motor.EpubAHtml
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Lectura
import com.forge.pixpin.pin.ImageStore
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * **Un Word o un libro, con lo anotado y los marcadores, como página web: desde donde sea.**
 *
 * El usuario lo pidió así (20-sep-2026): exportar desde el chat o desde dentro del documento
 * «debería ser lo mismo», y con la hoja de compartir de toda la aplicación, no con un botón
 * aparte. Así que aquí está todo lo que hace falta **sin tener el visor abierto**: lo anotado es
 * un dibujo guardado ([idDeLaCapa]), y la columna, la letra, los marcadores y las medidas de los
 * bloques viven en las preferencias `lectura`, con la ruta del documento por clave. El visor
 * deja las medidas al salir ([guardarMedidas]); si nunca se midió, lo anotado sale donde estaba
 * y los marcadores por su fracción del alto.
 */
object ExportarDocumentoAnotado {
    private const val ANCHO_DE_FOTO = 1100
    private const val COLUMNA_SIN_MEDIR = 420

    fun esDocumento(nombre: String?, ruta: String?): Boolean =
        DocxAHtml.esDocx(nombre) || DocxAHtml.esDocx(ruta) || EpubAHtml.esEpub(nombre) || EpubAHtml.esEpub(ruta)

    fun claveDe(original: File): String = "doc:" + original.absolutePath
    fun idDeLaCapa(original: File): String = "capa-doc-" + claveDe(original).hashCode().toUInt().toString(16)

    private fun prefs(c: Context) = c.getSharedPreferences("lectura", Context.MODE_PRIVATE)

    /** Lo que devolvió [DocumentoAnotado.MEDIR], tal cual: se guarda para exportar sin el visor. */
    fun guardarMedidas(c: Context, original: File, json: String) {
        prefs(c).edit().putString(claveDe(original) + ":medidas", json).apply()
    }

    /** El formato «Página web» de la hoja de compartir. [antes] deja al visor medir justo antes. */
    fun formato(c: Context, original: File, nombre: String, paginaVista: File? = null, antes: (suspend () -> Unit)? = null) =
        Compartible.Formato(
            "web-anotada", Icons.Filled.Language, "Página web", Compartible.NINGUNA,
            generar = {
                antes?.invoke()
                val funciones = (c.applicationContext as? com.forge.pixpin.PixPinApp)?.settings?.settings?.first()?.funcionesWeb
                hacer(c.applicationContext, original, nombre, paginaVista, funciones)?.let {
                    Compartible.Salida(it, "text/html", "El texto, con lo anotado, y se sigue anotando")
                }
            }
        )

    fun hacer(c: Context, original: File, nombre: String, paginaVista: File? = null, funciones: Set<String>? = null): File? = runCatching {
        val p = prefs(c)
        val clave = claveDe(original)
        val columna = p.getInt("$clave:columna", 0).takeIf { it > 0 }
        var tamano = p.getInt("tamano", 100); var grosor = p.getInt("grosor", 1); var tipo = p.getInt("tipo", 0)
        if (columna != null) p.getString("$clave:letra", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 3 }?.let { (t, g, l) -> tamano = t; grosor = g; tipo = l }
        val deNoche = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // La página: la que el visor tiene delante, o convertida aquí igual que la convierte el chat.
        val titulo = nombre.substringBeforeLast('.').ifBlank { "documento" }
        val (html, carpeta) = when {
            paginaVista != null && paginaVista.exists() -> paginaVista.readText() to paginaVista.parentFile
            EpubAHtml.esEpub(nombre) || EpubAHtml.esEpub(original.name) -> {
                val dir = File(File(c.cacheDir, "exportar-epub"), original.absolutePath.hashCode().toString())
                File(c.cacheDir, "exportar-epub").listFiles()?.forEach { if (it != dir) it.deleteRecursively() }
                val pagina = EpubAHtml.convertir(original, dir, titulo)
                pagina.readText() to dir
            }
            else -> DocxAHtml.convertir(original, nombre) to null
        }
        // La columna la pone la hoja del documento web (ver [DocumentoAnotado.hoja]); aquí, solo la letra y el papel.
        val conLetra = Lectura.conEstilo(html, grosor, tipo, null, deNoche)

        val medidas = p.getString("$clave:medidas", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        val tops = medidas?.optJSONArray("t")?.let { a -> List(a.length()) { a.getDouble(it) } }.orEmpty()
        val alto = medidas?.optDouble("h", 0.0) ?: 0.0

        val escena = if (columna != null) ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(c, idDeLaCapa(original))) else null
        val piezas = escena?.let { e ->
            val papel = e.copy(backgroundColor = if (deNoche) "#15171c" else "#ffffff")
            val foto: (String) -> Bitmap? = { f -> e.files[f]?.path?.let { ImageStore.load(it) } }
            DocumentoAnotado.porAnclas(e.elements, tops).mapNotNull { (ancla, grupo) ->
                val svg = DrawSvg.aTexto(c, papel.copy(elements = grupo), foto, papelAparte = true) ?: return@mapNotNull null
                val caja = DocumentoAnotado.cajaDe(svg) ?: return@mapNotNull null
                DocumentoAnotado.Pieza(svg, caja[0], caja[1], caja[2], caja[3], ancla)
            }
        }.orEmpty()
        val senales = Lectura.deTexto(p.getString("$clave:marcadores", null)).sortedBy { it.fraccion }.map { m ->
            val y = m.fraccion * alto
            DocumentoAnotado.Senal(m.emoji, y, if (alto > 0) DocumentoAnotado.anclaDe(y, tops) else -1, m.fraccion.toDouble())
        }
        // **Con los mandos de toda página web de la aplicación**: lápiz, resaltador, borrador,
        // deshacer y guardar, los que estén puestos en Ajustes → Exportar. Un documento que
        // nunca se abrió para anotar no tiene columna medida: va con una de lectura cómoda.
        val ancho = columna ?: COLUMNA_SIN_MEDIR
        val hoja = DocumentoAnotado.hoja(
            titulo, conLasFotosDentro(conLetra, carpeta), ancho, Lectura.margenDe(ancho), tops, piezas, senales,
            tamano, if (deNoche) "#15171c" else "#ffffff"
        )
        val hecha = com.forge.pixpin.motor.ExportarHtml.paginas(
            listOf(hoja), titulo, "$titulo (anotado)", com.forge.pixpin.motor.ExportarHtml.Opciones.de(funciones)
        )
        val limpio = ("$titulo (anotado).html").replace(Regex("""[^\p{L}\p{N} ()._-]"""), "_").takeLast(80)
        File(File(c.cacheDir, "share").apply { mkdirs() }, limpio).also { it.writeText(hecha) }
    }.getOrNull()

    /**
     * **Las fotos del libro, dentro de la página y a dieta.** El EPUB las tiene sueltas en su
     * carpeta, y una página que se manda sola las perdería. Entran en base64; las grandes —más
     * anchas de lo que ninguna columna enseña— se encogen y se recomprimen antes, que es lo que
     * hace que el archivo «sea de bajo peso» aunque el libro traiga fotos de cámara.
     */
    private fun conLasFotosDentro(html: String, carpeta: File?): String {
        if (carpeta == null) return html
        val raiz = runCatching { carpeta.canonicalPath }.getOrDefault(carpeta.absolutePath) + File.separator
        val hechas = HashMap<String, String?>()
        return Regex("(<img\\b[^>]*?\\ssrc=)([\"'])([^\"']+)\\2", RegexOption.IGNORE_CASE).replace(html) { m ->
            val ruta = m.groupValues[3]
            if (ruta.startsWith("data:", true) || ruta.startsWith("http", true)) return@replace m.value
            val dentro = hechas.getOrPut(ruta) {
                runCatching {
                    val f = File(carpeta, Uri.decode(ruta.substringBefore('#').substringBefore('?')))
                    if (!f.canonicalPath.startsWith(raiz) || !f.isFile) return@runCatching null
                    if (f.extension.equals("svg", true)) return@runCatching "data:image/svg+xml;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                    val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, medidas)
                    if (medidas.outWidth <= 0) return@runCatching null
                    if (medidas.outWidth <= ANCHO_DE_FOTO && f.length() < 120_000) {
                        return@runCatching "data:${medidas.outMimeType ?: "image/*"};base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                    }
                    var muestra = 1
                    while (medidas.outWidth / (muestra * 2) >= ANCHO_DE_FOTO) muestra *= 2
                    val foto = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = muestra }) ?: return@runCatching null
                    val justa = if (foto.width > ANCHO_DE_FOTO)
                        Bitmap.createScaledBitmap(foto, ANCHO_DE_FOTO, (foto.height.toLong() * ANCHO_DE_FOTO / foto.width).toInt().coerceAtLeast(1), true)
                    else foto
                    val salida = ByteArrayOutputStream()
                    @Suppress("DEPRECATION")
                    val webp = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                    justa.compress(webp, 74, salida)
                    "data:image/webp;base64," + Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP)
                }.getOrNull()
            } ?: return@replace m.value
            m.groupValues[1] + "\"" + dentro + "\""
        }
    }
}
