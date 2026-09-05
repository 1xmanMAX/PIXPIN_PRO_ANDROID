package com.forge.pixpin.ui

import android.content.Context
import com.forge.pixpin.croquis3d.Camara3D
import com.forge.pixpin.croquis3d.Croquis3DAlmacen
import com.forge.pixpin.croquis3d.ExportarCroquisHtml
import com.forge.pixpin.guardados.paginaAnotada
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.ExportarHtml
import com.forge.pixpin.motor.ExportarProyecto
import com.forge.pixpin.motor.ExportarProyectoWeb
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.PlanoWeb
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import com.forge.pixpin.pin.ImageStore
import java.io.File

/**
 * **La página web de unas hojas, con todo lo que hay que enchufar.**
 *
 * Exportar a web necesita saber leer escenas, fotos, páginas del PDF (finas y como líneas),
 * páginas anotadas y croquis 3D. Eso estaba cableado dentro de la pantalla de proyectos y
 * el chat, que también comparte documentos, no lo tenía: desde el chat solo salía imagen o
 * PDF. Aquí vive una sola vez y lo usan los dos (lo pidió el usuario el 5-sep-2026).
 */
object ExportarWebDe {

    /** Las hojas web de las [claves] marcadas de un proyecto. Trabajo de disco: fuera del hilo principal. */
    fun hojas(contexto: Context, proyecto: Proyecto, claves: Set<String>, deNoche: Boolean): List<ExportarHtml.HojaWeb> {
        val pdf = Proyectos.rutaDelDocumento(proyecto) { File(it).exists() }
        return ExportarProyectoWeb.paginas(
            contexto, proyecto, claves,
            escenaDe = { dibujo -> ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, dibujo)) },
            imagenDeRuta = { ruta -> ImageStore.load(ruta) },
            // La misma página al detalle, para verla en la web; sin lo anotado, que lo
            // pinta el SVG. Ver [com.forge.pixpin.motor.DrawSvg.aTexto].
            paginaFinaDelPdf = { pagina, _ -> pdf?.let { PdfDoc.paraLaWeb(it, pagina) } },
            // Y como líneas si el PDF es vectorial. Ver [PlanoWeb].
            planoDelPdf = { pagina -> pdf?.let { PlanoWeb.deArchivo(it, pagina) } },
            paginaDelPdf = { pagina, dibujo ->
                pdf?.let {
                    kotlinx.coroutines.runBlocking {
                        paginaAnotada(contexto, it, pagina, dibujo, dibujo?.let { d -> ExcalidrawStore.rutaDe(contexto, d) }, PdfDoc.PAGE_WIDTH)
                    }
                }
            },
            croquisComoHoja = { id ->
                Croquis3DAlmacen.cargar(contexto, id)?.let { croquis ->
                    ExportarCroquisHtml.hoja(
                        croquis, Camara3D(), "Croquis",
                        imagenIncrustada = { ruta -> imagenIncrustada(ruta) },
                        // El papel que se ve en la aplicación cuando el croquis no eligió uno.
                        papel = if (deNoche) ExportarCroquisHtml.FONDO_DE_FABRICA else "#ffffff"
                    )
                }
            }
        )
    }

    /** El `.html` listo para compartir, en la carpeta que publica el FileProvider. Null si no hay nada. */
    fun archivo(contexto: Context, seleccion: List<Pair<Proyecto, Set<String>>>, opciones: ExportarHtml.Opciones, deNoche: Boolean): File? {
        val hojas = seleccion.flatMap { (p, claves) -> hojas(contexto, p, claves, deNoche) }
        if (hojas.isEmpty()) return null
        val nombre = if (seleccion.size == 1) seleccion[0].first.nombre else seleccion.joinToString(" + ") { it.first.nombre }
        return runCatching {
            val pagina = ExportarHtml.paginas(
                hojas, titulo = nombre.ifBlank { "Proyecto" },
                nombre = ExportarProyecto.nombreDeArchivo(nombre), opciones = opciones
            )
            val carpeta = File(contexto.cacheDir, "share").apply { mkdirs() }
            File(carpeta, ExportarProyecto.nombreDeArchivo(nombre) + ".html").also { it.writeText(pagina) }
        }.getOrNull()
    }
}
