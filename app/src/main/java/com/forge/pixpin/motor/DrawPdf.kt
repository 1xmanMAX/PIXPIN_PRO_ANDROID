package com.forge.pixpin.motor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * El dibujo como PDF.
 *
 * **No es una imagen metida en un PDF.** Se pinta con el mismo renderizador que
 * la pantalla, directamente sobre el lienzo de la página, así que lo que sale
 * son trazos y texto de verdad: se amplía sin pixelarse y se imprime a la
 * escala que sea. Un PNG de 4000 px dentro de un PDF pesa diez veces más y se
 * ve peor en papel.
 *
 * Por qué existe, además del PNG: un PDF **se abre en cualquier parte y se
 * manda a cualquier parte** —correo, mensajería, una impresora— sin que nadie
 * tenga que preguntarse con qué se ve. Es el formato que no hay que explicar.
 */
object DrawPdf {

    /**
     * A4 en puntos PostScript, que es la unidad del PDF: 72 por pulgada.
     *
     * No son píxeles y no dependen de la pantalla de nadie; son las medidas del
     * papel. 595 × 842 pt son los 210 × 297 mm de un A4.
     */
    const val A4_ANCHO = 595
    const val A4_ALTO = 842

    /** Margen de la página, en puntos. Un dedo por cada lado. */
    private const val MARGEN = 28.0

    /**
     * Escribe el PDF y devuelve el archivo, o null si no había nada que pintar.
     *
     * Va a `cache/share`, que es la única carpeta que publica el `FileProvider`:
     * desde fuera, un archivo de cualquier otro sitio daría un fallo de permisos
     * en cuanto el destinatario intentara abrirlo.
     */
    fun aArchivo(
        context: Context,
        scene: Scene,
        nombre: String,
        imageProvider: (String) -> Bitmap? = { null }
    ): File? = runCatching {
        // **Una hoja del dibujo, una página del PDF.**
        //
        // Es lo que uno espera de un documento: si has puesto tres marcos es
        // porque estás montando tres láminas —una planta, un alzado, un
        // detalle— y quieres mandarlas juntas. Sin marcos sigue saliendo una
        // página con todo, que es el caso corriente.
        //
        // Cada página se encuadra por su cuenta y decide si va apaisada por su
        // cuenta: un documento puede llevar una lámina ancha y otra alta, y
        // forzarlas a la misma orientación dejaría una de las dos a media
        // escala con medio folio en blanco.
        val paginas: List<Pair<Bounds, List<Element>>> = scene.marcos
            .map { getElementBounds(it) to scene.contenidoDe(it) }
            .filter { (caja, contenido) ->
                caja.width > 0 && caja.height > 0 && contenido.isNotEmpty()
            }
            .ifEmpty {
                val visible = scene.contenidoVisible
                if (visible.isEmpty()) return null
                listOf(getCommonBounds(visible) to visible)
            }
        if (paginas.isEmpty()) return null

        val documento = PdfDocument()
        val renderizador = Renderer(imageProvider, DrawFonts.provider(context), paraExportar = true)

        for ((numero, hoja) in paginas.withIndex()) {
            val (caja, contenido) = hoja
            // La página se gira si el dibujo es apaisado, y el encaje lo decide
            // `encuadreEnPagina`: son cuentas que se pueden comprobar sin papel.
            val apaisado = paginaApaisada(caja)
            val anchoPagina = if (apaisado) A4_ALTO else A4_ANCHO
            val altoPagina = if (apaisado) A4_ANCHO else A4_ALTO

            val vista = encuadreEnPagina(
                caja, anchoPagina.toDouble(), altoPagina.toDouble(), MARGEN
            ) ?: continue

            val pagina = documento.startPage(
                PdfDocument.PageInfo.Builder(anchoPagina, altoPagina, numero + 1).create()
            )
            renderizador.renderScene(
                pagina.canvas,
                scene.copy(elements = contenido, viewport = vista),
                anchoPagina.toDouble(),
                altoPagina.toDouble()
            )
            documento.finishPage(pagina)
        }

        if (documento.pages.isEmpty()) {
            documento.close()
            return null
        }

        val carpeta = File(context.cacheDir, "share").apply { mkdirs() }
        val destino = File(carpeta, "${nombre.ifBlank { "pixpin" }}.pdf")
        destino.outputStream().use { documento.writeTo(it) }
        documento.close()
        destino
    }.getOrNull()

    const val MIME_TYPE = "application/pdf"
}
