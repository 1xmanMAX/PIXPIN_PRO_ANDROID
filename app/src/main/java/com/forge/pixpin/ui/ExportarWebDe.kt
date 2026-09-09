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
    fun hojas(contexto: Context, proyecto: Proyecto, claves: Set<String>, deNoche: Boolean, calidadDeAudio: String = com.forge.pixpin.motor.AudioLigero.LIGERO): List<ExportarHtml.HojaWeb> {
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
            },
            calidadDeAudio = calidadDeAudio
        )
    }

    /**
     * Qué lleva la página, para decirlo al compartirla: hojas, audios, imágenes y peso.
     * Es lo que permite saber, sin abrir el archivo, si el audio de una nota viajó o no.
     */
    fun resumenDe(archivo: File, calidadDeAudio: String? = null): String {
        val html = runCatching { archivo.readText() }.getOrDefault("")
        val hojas = Regex("<div class=\"hoja\"").findAll(html).count()
        val audios = Regex("<audio ").findAll(html).count()
        val imagenes = Regex("<img ").findAll(html).count()
        val adjuntos = Regex("class=\"adjunto\"").findAll(html).count()
        val peso = com.forge.pixpin.motor.Detalle.legible(archivo.length())
        return buildString {
            append("Página web: ").append(hojas).append(if (hojas == 1) " hoja" else " hojas")
            if (audios > 0) append(" · ").append(audios).append(if (audios == 1) " audio" else " audios")
            if (imagenes > 0) append(" · ").append(imagenes).append(if (imagenes == 1) " imagen" else " imágenes")
            if (adjuntos > 0) append(" · ").append(adjuntos).append(if (adjuntos == 1) " adjunto" else " adjuntos")
            append(" · ").append(peso)
            // El audio solo se anuncia si de verdad viajó: contar los `<audio>` del archivo y
            // añadir «· audio: ligero» siempre hacía que un proyecto sin un solo audio dijera
            // que lo llevaba (lo reportó el usuario al compartir proyectos seleccionados).
            if (audios > 0 && calidadDeAudio != null) append(" · audio: ").append(calidadDeAudio)
        }
    }

    /** El `.html` listo para compartir, en la carpeta que publica el FileProvider. Null si no hay nada. */
    fun archivo(contexto: Context, seleccion: List<Pair<Proyecto, Set<String>>>, opciones: ExportarHtml.Opciones, deNoche: Boolean, calidadDeAudio: String = com.forge.pixpin.motor.AudioLigero.LIGERO): File? {
        val hojas = seleccion.flatMap { (p, claves) -> hojas(contexto, p, claves, deNoche, calidadDeAudio) }
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
