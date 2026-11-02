package com.forge.pixpin.mini

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import com.forge.pixpin.R
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.motor.Detalle
import com.forge.pixpin.motor.PdfDeNota
import com.forge.pixpin.motor.PdfUnion
import com.forge.pixpin.motormd.ExportarDocx
import com.forge.pixpin.ui.Compartible
import com.forge.pixpin.ui.ExportarVisor
import com.forge.pixpin.ui.FormatosDeCompartir
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * **Una lista de tareas en la hoja de compartir** (2-nov-2026, `compartir/tareas.rs` del PC `bc4533bd`).
 * Antes la mini-app `tareas` salía como cualquier texto, con las marcas crudas a la vista (`⏫`,
 * `➕ 2026-10-28`, los enlaces de las fotos). Ahora cada formato sale de lo ya entendido
 * ([ExportarTareas]):
 *
 * - **Página web**: la página propia de las tareas, con el resumen, las chapitas, las fotos dentro y lo
 *   hecho plegado.
 * - **PDF** (del Markdown con sus fotos, [PdfDeNota]) y **PNG/JPG** (sus páginas, una debajo de otra).
 * - **Word**, **Markdown** y **texto**.
 *
 * Lo que dice cada página, en el idioma de la app ([rotulos], los del PC en los 14 idiomas).
 */
object ExportarTareasEnLaApp {

    /** Si el mensaje es una lista de tareas. */
    fun esLista(m: Mensaje): Boolean = m.miniapp == MiniApp.TAREAS.id

    /** Lo que dicen las páginas, de los recursos (`strings_tareas_exportar.xml`). */
    fun rotulos(c: Context): ExportarTareas.Rotulos {
        val s = { id: Int -> c.getString(id) }
        return ExportarTareas.Rotulos(
            idioma = s(R.string.te_grabacion_idioma),
            tareas = s(R.string.te_tareas_exportar_titulo),
            exportado = s(R.string.te_tareas_exportar_exportado),
            pendientes = s(R.string.te_tareas_exportar_pendientes),
            hechas = s(R.string.te_tareas_exportar_hechas),
            completado = s(R.string.te_tareas_exportar_completado),
            pendiente = s(R.string.te_tareas_exportar_pendiente),
            hecha = s(R.string.te_tareas_exportar_hecha),
            todoHecho = s(R.string.te_tareas_exportar_todo_hecho),
            vacia = s(R.string.te_tareas_exportar_vacia),
            urgente = s(R.string.te_tareas_urgente),
            muyUrgente = s(R.string.te_tareas_muy_urgente),
            recordatorio = s(R.string.te_tareas_exportar_recordatorio),
            pasada = s(R.string.te_tareas_exportar_pasada),
            creada = s(R.string.te_tareas_exportar_creada),
            hoy = s(R.string.te_tareas_selector_hoy),
            manana = s(R.string.te_tareas_selector_manana),
            ayer = s(R.string.te_chat_ayer),
            de = s(R.string.te_tareas_exportar_de),
            foto = s(R.string.te_tareas_exportar_foto),
            dias = listOf(
                R.string.te_tareas_dia_0, R.string.te_tareas_dia_1, R.string.te_tareas_dia_2, R.string.te_tareas_dia_3,
                R.string.te_tareas_dia_4, R.string.te_tareas_dia_5, R.string.te_tareas_dia_6
            ).map(s),
            meses = listOf(
                R.string.te_chat_mes_1, R.string.te_chat_mes_2, R.string.te_chat_mes_3, R.string.te_chat_mes_4,
                R.string.te_chat_mes_5, R.string.te_chat_mes_6, R.string.te_chat_mes_7, R.string.te_chat_mes_8,
                R.string.te_chat_mes_9, R.string.te_chat_mes_10, R.string.te_chat_mes_11, R.string.te_chat_mes_12
            ).map(s),
            fecha = s(R.string.te_chat_fecha),
            fechaConAno = s(R.string.te_chat_fecha_con_ano)
        )
    }

    /** La hoja de estilo de la página, la del PC (`assets/tareas-exportar.css`). */
    private fun css(c: Context): String = runCatching { c.assets.open("tareas-exportar.css").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("")

    /** El lado mayor de una foto dentro de la página (se enseña en miniatura). */
    private const val LADO_FOTO = 360

    /** Una foto como `data:` JPEG ya reducida, para la página. */
    private fun dataDe(ruta: String): String? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ruta, o)
        var paso = 1
        while (maxOf(o.outWidth, o.outHeight) / (paso * 2) >= LADO_FOTO) paso *= 2
        val b = BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = paso }) ?: return null
        val f = LADO_FOTO.toFloat() / maxOf(b.width, b.height)
        val r = if (f < 1f) Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true) else b
        val out = ByteArrayOutputStream()
        r.compress(Bitmap.CompressFormat.JPEG, 82, out)
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    /**
     * **Lo que ofrece la hoja** para la lista del mensaje [m]. [extras] al final (el `.pindesk`, la
     * Wi-Fi). Lee el disco: fuera del hilo de la pantalla.
     */
    fun compartible(c: Context, m: Mensaje, nombre: String, extras: List<Compartible.Formato> = emptyList()): Compartible {
        val lista = ExportarTareas.ListaParaExportar.delDocumento(m.texto, nombre)
        val unas = listOf(lista)
        val r = rotulos(c)
        val ahora = Momento.ahora()
        val titulo = lista.titulo.ifBlank { r.tareas }
        val base = ExportarVisor.nombre(titulo, "x").removeSuffix(".x")
        fun destino(n: String) = File(ExportarVisor.carpeta(c), n)
        val filesDir = c.filesDir.absolutePath
        val rutas = HashMap<String, String>()
        for (e in lista.tareas.flatMap { it.imagenes }) Tareas.rutaDeImagen(e, filesDir)?.takeIf { File(it).isFile }?.let { rutas[e] = it }
        fun pdf(): ByteArray? {
            val md = ExportarTareas.markdown(unas, titulo, r, ahora) { e -> e.takeIf { it in rutas } }
            return PdfDeNota.aniadir(PdfUnion.enBlanco(), md, Detalle.letrasParaPdf()) { k -> rutas[k]?.let(Detalle::medioParaPdf) }
        }
        val formatos = buildList {
            add(Compartible.Formato("png", Icons.Filled.Image, "Imagen PNG", Compartible.NINGUNA, generar = {
                val img = imagenDe(c, pdf() ?: return@Formato null) ?: return@Formato null
                val f = destino("$base.png")
                f.outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Compartible.Salida(f, "image/png", c.getString(R.string.te_compartir_tipo_tareas))
            }))
            add(Compartible.Formato("jpg", Icons.Filled.Image, "Imagen JPG", Compartible.NINGUNA, generar = {
                val img = imagenDe(c, pdf() ?: return@Formato null) ?: return@Formato null
                val f = destino("$base.jpg")
                f.outputStream().use { img.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                Compartible.Salida(f, "image/jpeg", c.getString(R.string.te_compartir_tipo_tareas))
            }))
            add(Compartible.Formato("pdf", Icons.Filled.PictureAsPdf, "PDF", Compartible.NINGUNA, generar = {
                val bytes = pdf() ?: return@Formato null
                Compartible.Salida(destino("$base.pdf").also { it.writeBytes(bytes) }, "application/pdf", c.getString(R.string.te_compartir_tipo_tareas))
            }))
            add(Compartible.Formato("web", Icons.Filled.Language, "Página web", Compartible.NINGUNA, generar = {
                val h = ExportarTareas.html(unas, titulo, r, ahora, css(c)) { e -> rutas[e]?.let(::dataDe) }
                Compartible.Salida(destino("$base.html").also { it.writeText(h) }, "text/html", c.getString(R.string.te_compartir_tipo_tareas))
            }))
            add(Compartible.Formato("word", Icons.Filled.Article, "Word", Compartible.NINGUNA, generar = {
                val md = ExportarTareas.markdown(unas, titulo, r, ahora) { e -> e.takeIf { it in rutas } }
                val bytes = ExportarDocx.exportar(ExportarDocx.Nota(md, titulo, imagen = { k -> rutas[k]?.let { runCatching { File(it).readBytes() }.getOrNull() } }))
                Compartible.Salida(destino("$base.docx").also { it.writeBytes(bytes) }, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", c.getString(R.string.te_compartir_tipo_tareas))
            }))
            add(Compartible.Formato("md", Icons.AutoMirrored.Filled.Notes, "Markdown", Compartible.NINGUNA, generar = {
                Compartible.Salida(destino("$base.md").also { it.writeText(ExportarTareas.markdown(unas, titulo, r, ahora)) }, "text/markdown", c.getString(R.string.te_compartir_tipo_tareas))
            }))
            add(Compartible.Formato("texto", Icons.Filled.TextFields, "Texto", Compartible.NINGUNA, generar = {
                Compartible.Salida(destino("$base.txt").also { it.writeText(ExportarTareas.texto(unas, titulo, r, ahora)) }, "text/plain", c.getString(R.string.te_compartir_tipo_tareas))
            }))
            addAll(extras)
        }
        return Compartible(titulo, formatos = formatos, contenido = FormatosDeCompartir.Contenido.NOTA)
    }

    /** Las páginas del PDF, una debajo de otra, en una imagen (a 2× de su tamaño en puntos, como mucho 12 páginas). */
    private fun imagenDe(c: Context, pdf: ByteArray): Bitmap? = runCatching {
        val f = File.createTempFile("tareas", ".pdf", c.cacheDir).apply { writeBytes(pdf) }
        try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { pr ->
                    val n = minOf(pr.pageCount, 12)
                    val paginas = (0 until n).map { i ->
                        pr.openPage(i).use { p ->
                            val b = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                            b.eraseColor(Color.WHITE)
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            b
                        }
                    }
                    if (paginas.isEmpty()) return null
                    val todo = Bitmap.createBitmap(paginas.maxOf { it.width }, paginas.sumOf { it.height }, Bitmap.Config.ARGB_8888)
                    val lienzo = Canvas(todo)
                    lienzo.drawColor(Color.WHITE)
                    var y = 0f
                    for (b in paginas) { lienzo.drawBitmap(b, 0f, y, null); y += b.height; b.recycle() }
                    todo
                }
            }
        } finally { f.delete() }
    }.getOrNull()
}
