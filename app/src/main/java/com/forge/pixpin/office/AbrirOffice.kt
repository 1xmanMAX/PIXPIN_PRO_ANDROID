package com.forge.pixpin.office

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Por dónde se abre un Word o un PowerPoint** (11-oct-2026).
 *
 * Con la maquetación del PC (`libpixpinoffice.so`), **en el lector de PDF**: las páginas como las
 * imprime Word y las diapositivas como las proyecta PowerPoint, con la misma tinta, marcadores,
 * negativo y aumento nítido que un PDF ([com.forge.pixpin.pdf.LectorPdfActivity]).
 *
 * **El camino de antes queda de reserva** ([comoAntes]) y es además el «Modo lectura» del lector:
 * el Word pasado a texto que se refluye ([com.forge.pixpin.motor.DocxAHtml] en el visor de
 * documentos, con su voz, su letra y su propia tinta), y el PowerPoint pasado a PDF en un proyecto
 * ([com.forge.pixpin.guardados.DiapositivasAPdf]).
 */
object AbrirOffice {
    /** Si [nombre] (o [ruta]) se abre maquetado: es de Office y el .so está. */
    fun seMaqueta(nombre: String?, ruta: String?): Boolean =
        (DocumentoOffice.esDeOffice(nombre) || DocumentoOffice.esDeOffice(ruta)) && OfficeNativo.disponible

    /**
     * Lo de antes: el Word en el visor de documentos (reflujado) y el PowerPoint a un proyecto.
     * [mensaje] es el del chat, si se viene de uno. [fuera] abre el archivo con otra aplicación
     * (si tampoco se puede leer así). [escuchar] empieza a leer el Word en alto al abrir.
     */
    fun comoAntes(
        a: ComponentActivity, ruta: String, nombre: String, mensaje: String?,
        escuchar: Boolean = false, fuera: (String) -> Unit
    ) {
        val esPowerPoint = com.forge.pixpin.motor.Diapositivas.esPresentacion(nombre) || com.forge.pixpin.motor.Diapositivas.esPresentacion(ruta)
        if (esPowerPoint) diapositivasAProyecto(a, ruta, nombre) else wordComoTexto(a, ruta, nombre, mensaje, escuchar, fuera)
    }

    /**
     * **Un Word se lee como texto** (19-sep-2026): lo que dice —texto, tablas e imágenes, sin la
     * maquetación de la página— a un HTML que enseña el visor de documentos. Convertir es abrir un
     * ZIP y leer XML: fuera del hilo que pinta. La conversión se guarda por ruta, fecha y peso.
     */
    fun wordComoTexto(a: ComponentActivity, ruta: String, nombre: String, mensaje: String?, escuchar: Boolean = false, fuera: (String) -> Unit) {
        a.lifecycleScope.launch {
            val titulo = nombre.ifBlank { File(ruta).name }
            val hecho = withContext(Dispatchers.IO) {
                runCatching {
                    val original = File(ruta)
                    val huella = "$ruta|${original.lastModified()}|${original.length()}".hashCode()
                    val carpeta = File(a.cacheDir, "visor-docx").apply { mkdirs() }
                    val pagina = File(carpeta, "$huella.html")
                    if (!pagina.exists() || pagina.length() == 0L) {
                        // Caché de usar y tirar: solo se guarda la última.
                        carpeta.listFiles()?.forEach { it.delete() }
                        pagina.writeText(com.forge.pixpin.motor.DocxAHtml.convertir(original, titulo))
                    }
                    pagina
                }
            }
            hecho.onSuccess { pagina ->
                // Arriba, el nombre del documento; al compartir sale el `.docx` y no la página
                // fabricada; y sin JavaScript, que esa página no lo lleva.
                com.forge.pixpin.ui.VisorHtmlActivity.abrir(
                    a, pagina.absolutePath, titulo,
                    comparte = ruta, sinGuion = true, mensaje = mensaje, documento = true, escuchar = escuchar
                )
            }.onFailure { e ->
                Toast.makeText(a, (e as? com.forge.pixpin.motor.DocxAHtml.NoSeLee)?.message ?: "No se pudo leer el documento", Toast.LENGTH_LONG).show()
                fuera(ruta)
            }
        }
    }

    /** **Un PowerPoint se convierte en proyecto** (14-sep-2026): una hoja por diapositiva. Ver [com.forge.pixpin.guardados.DiapositivasAPdf]. */
    fun diapositivasAProyecto(a: ComponentActivity, ruta: String, nombre: String) {
        a.lifecycleScope.launch {
            val app = a.application as? com.forge.pixpin.PixPinApp ?: return@launch
            Toast.makeText(a, "Preparando las diapositivas…", Toast.LENGTH_SHORT).show()
            val ahora = System.currentTimeMillis()
            val hecho = withContext(Dispatchers.IO) {
                runCatching {
                    val documento = File(File(a.filesDir, "proyectos").apply { mkdirs() }, "doc-$ahora.pdf")
                    val paginas = com.forge.pixpin.guardados.DiapositivasAPdf.convertir(File(ruta), nombre.ifBlank { File(ruta).name }, documento)
                    app.proyectos.deEstePdf(documento.absolutePath, nombre.substringBeforeLast('.').ifBlank { "Presentación" }, paginas, ahora)
                }
            }
            hecho.onSuccess { p ->
                // Su chat, que es el proyecto (26-oct-2026); sin proyecto, la lista de chats.
                if (p != null) com.forge.pixpin.Volver.alChat(a, p.id) else com.forge.pixpin.Volver.aLaLista(a)
            }.onFailure { e ->
                Toast.makeText(a, (e as? com.forge.pixpin.motor.Diapositivas.NoSeLee)?.message ?: "No se pudo leer la presentación", Toast.LENGTH_LONG).show()
            }
        }
    }
}
