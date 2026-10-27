package com.forge.pixpin.lecciones

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.ui.Compartible
import com.forge.pixpin.ui.ExportarVisor
import com.forge.pixpin.ui.FormatosDeCompartir
import java.io.File

/**
 * **Exportar una lección** (15-oct-2026; el PC lo hizo el 14-oct en `lecciones/exportar.rs`): por la
 * hoja de compartir común, como cualquier visor. Antes el teléfono solo la compartía como texto.
 *
 * - **Las hojas**: el resumen de la lección (el mismo texto que lleva su mensaje en el chat,
 *   [LeccionesStore.resumen], con sus campos) y detrás una hoja por cada foto suya. De ellas salen
 *   PDF (el texto como texto, con [com.forge.pixpin.motor.PdfDeNota]), página web, PNG y JPG.
 * - **Original**: su `<id>.leccion` tal cual (el JSON que lee el PC).
 * - **.pixpin**: el chat donde vive, entero (la lección es un mensaje suyo con sus fotos y audios);
 *   si vive en la conversación general, la lección con sus fotos y audios.
 * - **Propios**: el texto en `.txt` y en `.md`.
 */
object ExportarLeccion {

    /** La clave de la hoja del resumen; las fotos son `foto0`, `foto1`… */
    private const val RESUMEN = "resumen"

    fun titulo(l: Leccion): String = l.titulo.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: l.id

    /** **El texto de la lección**, como el de su mensaje pero con todo lo que se escribió. */
    fun texto(l: Leccion): String = buildString {
        append(LeccionesStore.resumen(l))
        if (l.area.isNotBlank()) append("\nÁrea: ").append(l.area)
        if (l.causas.isNotEmpty()) append("\nCausas: ").append(l.causas.joinToString(", "))
    }

    /** **La lección en Markdown**: el título como encabezado y cada campo con su nombre en negrita. */
    fun markdown(l: Leccion, fotos: List<String> = emptyList()): String = buildString {
        val tipo = when (l.tipo) { Leccion.TIPO_ERROR -> "⚠️ Error que no repetir"; Leccion.TIPO_ACIERTO -> "✅ Lo que funcionó"; else -> "💡 Lección" }
        append("# ").append(titulo(l)).append("\n\n").append(tipo)
        if (l.area.isNotBlank()) append(" · ").append(l.area)
        append(" · ").append(when (l.gravedad) { 3 -> "Grave"; 2 -> "Importante"; else -> "Leve" }).append("\n\n")
        if (l.titulo.trim() != titulo(l)) append(l.titulo.trim()).append("\n\n")
        fun campo(nombre: String, valor: String) { if (valor.isNotBlank()) append("**").append(nombre).append(":** ").append(valor.trim()).append("\n\n") }
        campo("Qué pasó", l.quePaso)
        campo("Por qué", l.porQue)
        campo("La próxima vez", l.proxima)
        if (l.causas.isNotEmpty()) campo("Causas", l.causas.joinToString(", "))
        if (l.repeticiones.isNotEmpty()) append("🔁 Pasó ").append(l.vecesQuePaso).append(" veces\n\n")
        val etiquetas = l.todasLasEtiquetas
        if (etiquetas.isNotEmpty()) append(etiquetas.joinToString(" ") { "#$it" }).append("\n\n")
        for (f in fotos) append("![foto](").append(f).append(")\n\n")
    }.trimEnd() + "\n"

    /**
     * **Lo que se ofrece al exportar [e]**, con [l] la lección tal como se ve ahora (la ficha la
     * guarda sola, pero puede ir un paso por detrás). Lee el chat: fuera del hilo de la pantalla.
     */
    fun compartible(c: Context, e: LeccionesStore.Entrada, l: Leccion = e.leccion): Compartible {
        val adjuntos = runCatching { LeccionesStore(c).adjuntosDe(e) }.getOrDefault(emptyList())
        val fotos = adjuntos.filter { it.clase == Clase.IMAGEN }.mapNotNull { m -> m.ruta?.takeIf { File(it).isFile } }
        val nombre = titulo(l)
        val paginas = listOf(Compartible.Pagina(RESUMEN, "Resumen", "La lección")) +
            fotos.indices.map { Compartible.Pagina("foto$it", "Foto ${it + 1}") }
        fun fotosDe(claves: List<String>) = fotos.filterIndexed { i, _ -> "foto$i" in claves }
        val ruta = e.mensaje.ruta.orEmpty()
        val formatos = buildList {
            add(Compartible.Formato("pdf", Icons.Filled.PictureAsPdf, "PDF", Compartible.VARIAS, generar = { claves -> pdf(c, l, claves, fotos, nombre) }))
            add(Compartible.Formato("web", Icons.Filled.Language, "Página web", Compartible.VARIAS, generar = { claves ->
                val destino = File(ExportarVisor.carpeta(c), ExportarVisor.nombre(nombre, "html"))
                destino.writeText(html(l, RESUMEN in claves, fotosDe(claves)))
                Compartible.Salida(destino, com.forge.pixpin.motor.ExportarHtml.MIME_TYPE, "Se abre en cualquier navegador")
            }))
            addAll(ExportarVisor.imagenes(c, { claves -> nombre + if (claves.firstOrNull() == RESUMEN) "" else " - ${claves.firstOrNull()}" }, Compartible.UNA) { claves ->
                when (val k = claves.firstOrNull() ?: RESUMEN) {
                    RESUMEN -> hojaDelResumen(l)
                    else -> fotos.getOrNull(k.removePrefix("foto").toIntOrNull() ?: -1)?.let { com.forge.pixpin.pin.ImageStore.load(it)?.copy(Bitmap.Config.ARGB_8888, false) }
                }
            })
            if (ruta.isNotBlank()) {
                add(ExportarVisor.pixpin(c, ruta, "$nombre.leccion", junto = adjuntos.map { it.id }))
                add(ExportarVisor.original(c, ruta, "$nombre.leccion", "Su archivo .leccion, el que leen PixPin y el PC"))
            }
            add(Compartible.Formato("texto", Icons.Filled.TextFields, "TXT", Compartible.NINGUNA, generar = {
                val destino = File(ExportarVisor.carpeta(c), ExportarVisor.nombre(nombre, "txt")).also { it.writeText(texto(l)) }
                Compartible.Salida(destino, "text/plain", "El texto, sin formato")
            }))
            add(Compartible.Formato("md", Icons.Filled.Notes, "Markdown", Compartible.NINGUNA, generar = {
                val destino = File(ExportarVisor.carpeta(c), ExportarVisor.nombre(nombre, "md")).also { it.writeText(markdown(l)) }
                Compartible.Salida(destino, "text/markdown", "Con sus títulos y negritas")
            }))
        }
        return Compartible(nombre, paginas, formatos, contenido = FormatosDeCompartir.Contenido.LECCION)
    }

    /** El PDF: el resumen como texto de verdad y una hoja por foto. */
    private fun pdf(c: Context, l: Leccion, claves: List<String>, fotos: List<String>, nombre: String): Compartible.Salida? {
        val letras = com.forge.pixpin.motor.Detalle.letrasParaPdf()
        var bytes = com.forge.pixpin.motor.PdfUnion.enBlanco()
        var hojas = 0
        fun poner(md: String) {
            com.forge.pixpin.motor.PdfDeNota.aniadir(bytes, md, letras, com.forge.pixpin.motor.Detalle::medioParaPdf)?.let { bytes = it; hojas++ }
        }
        if (RESUMEN in claves) poner(markdown(l))
        fotos.forEachIndexed { i, f -> if ("foto$i" in claves) poner("![foto ${i + 1}]($f)\n") }
        if (hojas == 0) return null
        val destino = File(ExportarVisor.carpeta(c), ExportarVisor.nombre(nombre, "pdf")).also { it.writeBytes(bytes) }
        return Compartible.Salida(destino, "application/pdf", "$hojas ${if (hojas == 1) "hoja" else "hojas"}, el texto se busca y se copia")
    }

    /** La página web: el texto y las fotos dentro (sin nada fuera: se abre sin internet). */
    fun html(l: Leccion, conResumen: Boolean, fotos: List<String>): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val t = esc(titulo(l))
        return buildString {
            append("<!DOCTYPE html>\n<html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>").append(t).append("</title><style>body{margin:0 auto;max-width:760px;padding:18px;font:16px/1.5 system-ui,sans-serif;color:#1d1d22;background:#fafafa}")
            append("h1{font-size:22px;margin:0 0 6px}.tipo{color:#666;margin-bottom:14px}dt{font-weight:600;margin-top:10px}dd{margin:2px 0 0}")
            append(".etq span{display:inline-block;background:#eee;border-radius:10px;padding:1px 8px;margin:2px}img{display:block;max-width:100%;height:auto;margin:14px auto;border-radius:8px}")
            append("@media (prefers-color-scheme:dark){body{background:#17181c;color:#e8e8ec}.tipo{color:#aaa}.etq span{background:#2a2b31}}</style></head><body>")
            if (conResumen) {
                append("<h1>").append(t).append("</h1><div class=\"tipo\">")
                append(esc(when (l.tipo) { Leccion.TIPO_ERROR -> "⚠️ Error que no repetir"; Leccion.TIPO_ACIERTO -> "✅ Lo que funcionó"; else -> "💡 Lección" }))
                if (l.area.isNotBlank()) append(" · ").append(esc(l.area))
                append("</div>")
                if (l.titulo.trim() != titulo(l)) append("<p>").append(esc(l.titulo.trim())).append("</p>")
                append("<dl>")
                for ((n, v) in listOf("Qué pasó" to l.quePaso, "Por qué" to l.porQue, "La próxima vez" to l.proxima, "Causas" to l.causas.joinToString(", "))) {
                    if (v.isNotBlank()) append("<dt>").append(esc(n)).append("</dt><dd>").append(esc(v.trim()).replace("\n", "<br>")).append("</dd>")
                }
                append("</dl>")
                if (l.repeticiones.isNotEmpty()) append("<p>🔁 Pasó ").append(l.vecesQuePaso).append(" veces</p>")
                if (l.todasLasEtiquetas.isNotEmpty()) append("<p class=\"etq\">").append(l.todasLasEtiquetas.joinToString("") { "<span>#${esc(it)}</span>" }).append("</p>")
            }
            for (f in fotos) {
                val datos = runCatching { File(f).readBytes() }.getOrNull() ?: continue
                val tipo = when (f.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg" }
                append("<img alt=\"foto\" src=\"data:").append(tipo).append(";base64,").append(java.util.Base64.getEncoder().encodeToString(datos)).append("\">")
            }
            append("</body></html>\n")
        }
    }

    /** **La hoja del resumen como imagen**: un A4 a 150 ppp con el texto. */
    private fun hojaDelResumen(l: Leccion): Bitmap {
        val ancho = 1240; val alto = 1754; val margen = 110
        val mapa = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val lienzo = Canvas(mapa).apply { drawColor(Color.WHITE) }
        val titulo = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1d, 0x1d, 0x22); textSize = 54f; isFakeBoldText = true }
        val cuerpo = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2a, 0x2a, 0x30); textSize = 34f }
        var y = margen.toFloat()
        fun bloque(texto: String, pincel: TextPaint, despues: Float) {
            if (texto.isBlank() || y > alto - margen) return
            val capa = StaticLayout.Builder.obtain(texto, 0, texto.length, pincel, ancho - 2 * margen)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f).build()
            lienzo.save(); lienzo.translate(margen.toFloat(), y); capa.draw(lienzo); lienzo.restore()
            y += capa.height + despues
        }
        bloque(titulo(l), titulo, 30f)
        bloque(texto(l).lineSequence().drop(1).joinToString("\n").ifBlank { LeccionesStore.resumen(l) }, cuerpo, 0f)
        return mapa
    }
}
