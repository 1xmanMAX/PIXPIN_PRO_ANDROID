package com.forge.pixpin.excel

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import com.forge.pixpin.motor.ExportarHtml
import com.forge.pixpin.motor.ImportarHojas
import com.forge.pixpin.ui.Compartible
import com.forge.pixpin.ui.ExportarVisor
import java.io.File

/**
 * **Exportar desde el visor de hojas de cálculo** (14-oct-2026; el PC lo hizo el 13-oct). La hoja de
 * compartir de siempre con:
 *
 * - **Página web**: las hojas elegidas como tablas que **siguen calculando** (el documento web de
 *   las tablas de PixPin, [ExportarHtml.HojaWeb.Tabla]).
 * - **PDF**: los valores de las hojas elegidas como tablas de texto (lo mismo que hace el PDF de un
 *   proyecto con una tabla: [com.forge.pixpin.motor.PdfDeNota]).
 * - **CSV**: una hoja, para Excel o Sheets.
 * - **PNG / JPG**: lo que se ve.
 * - **Original** y **.pixpin**: los comunes ([ExportarVisor]).
 *
 * Las tablas se leen del archivo como «A tabla» ([ImportarHojas]), una vez y solo al exportar: el
 * visor no las necesita.
 */
object ExportarExcel {

    /** [hojas]: los nombres de las hojas que se ven (las pestañas). [vista]: la hoja en pantalla. */
    fun compartible(c: Context, ruta: String, nombre: String, hojas: List<String>, vista: () -> android.view.View?): Compartible {
        val titulo = nombre.substringBeforeLast('.').ifBlank { "Libro" }
        val paginas = hojas.mapIndexed { i, n -> Compartible.Pagina("h$i", n.ifBlank { "Hoja ${i + 1}" }) }
        var leidas: List<ImportarHojas.HojaImportada>? = null
        val cerrojo = Any()
        fun tablas(): List<ImportarHojas.HojaImportada> = synchronized(cerrojo) {
            leidas ?: ImportarHojas.leer(File(ruta), nombre).also { leidas = it }
        }
        /** Las tablas de las páginas elegidas (por su nombre; si no casa ninguno, todas). */
        fun elegidas(claves: List<String>): List<ImportarHojas.HojaImportada> {
            val todas = tablas()
            val nombres = claves.mapNotNull { k -> k.removePrefix("h").toIntOrNull()?.let { hojas.getOrNull(it) } }.toSet()
            return todas.filter { it.nombre in nombres }.ifEmpty { if (claves.isEmpty()) todas else todas.filterIndexed { i, _ -> "h$i" in claves } }
        }
        val formatos = buildList {
            add(Compartible.Formato("web", Icons.Filled.Language, "Página web", Compartible.VARIAS, generar = { claves ->
                val t = elegidas(claves).takeIf { it.isNotEmpty() } ?: return@Formato null
                val marcadas = (c.applicationContext as? com.forge.pixpin.PixPinApp)?.ajustes?.funcionesWeb ?: ExportarHtml.Opciones.NOMBRES.toSet()
                val html = ExportarHtml.paginas(t.map { ExportarHtml.HojaWeb.Tabla(it.nombre, it.tabla) }, titulo, com.forge.pixpin.motor.ExportarProyecto.nombreDeArchivo(titulo), ExportarHtml.Opciones.de(marcadas))
                val destino = File(ExportarVisor.carpeta(c), ExportarVisor.nombre(titulo, "html")).also { it.writeText(html) }
                Compartible.Salida(destino, ExportarHtml.MIME_TYPE, "${t.size} ${if (t.size == 1) "hoja" else "hojas"} con sus fórmulas, que siguen calculando")
            }))
            add(Compartible.Formato("pdf", Icons.Filled.PictureAsPdf, "PDF", Compartible.VARIAS, generar = { claves ->
                val t = elegidas(claves).takeIf { it.isNotEmpty() } ?: return@Formato null
                val texto = t.joinToString("\n\n") { h -> "## ${h.nombre}\n\n" + com.forge.pixpin.motor.TablaViva.comoMarkdown(h.tabla) }
                val pdf = com.forge.pixpin.motor.PdfDeNota.aniadir(com.forge.pixpin.motor.PdfUnion.enBlanco(), texto) ?: return@Formato null
                val destino = File(ExportarVisor.carpeta(c), ExportarVisor.nombre(titulo, "pdf")).also { it.writeBytes(pdf) }
                Compartible.Salida(destino, "application/pdf", "Los valores, como texto que se busca y se copia")
            }))
            add(Compartible.Formato("csv", Icons.Filled.TableChart, "CSV", Compartible.UNA, generar = { claves ->
                val h = elegidas(claves).firstOrNull() ?: return@Formato null
                com.forge.pixpin.tabla.archivoDeLaTabla(c, h.tabla, false)?.let { Compartible.Salida(it, "text/csv", "«${h.nombre}»: los valores, para Excel o Sheets") }
            }))
            addAll(ExportarVisor.imagenes(c, { titulo }) { vista()?.let { ExportarVisor.fotoDe(it) } })
            add(ExportarVisor.original(c, ruta, nombre))
            add(ExportarVisor.pixpin(c, ruta, nombre))
        }
        return Compartible(titulo, paginas, formatos)
    }
}
