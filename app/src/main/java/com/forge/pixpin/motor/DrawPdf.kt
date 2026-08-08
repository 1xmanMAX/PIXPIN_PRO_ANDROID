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
        val visible = scene.contenidoVisible
        if (visible.isEmpty()) return null

        // Con hoja manda la hoja; sin ella, lo dibujado. Mismo criterio que al
        // exportar a imagen: el marco es la hoja, y si lo has puesto es porque
        // querías decidir tú el encuadre.
        val marco = scene.marco
        val caja = if (marco != null) getElementBounds(marco) else getCommonBounds(visible)
        if (caja.width <= 0 || caja.height <= 0) return null

        // La página se gira si el dibujo es apaisado, y el encaje lo decide
        // `encuadreEnPagina`: son cuentas que se pueden comprobar sin papel.
        val apaisado = paginaApaisada(caja)
        val anchoPagina = if (apaisado) A4_ALTO else A4_ANCHO
        val altoPagina = if (apaisado) A4_ANCHO else A4_ALTO

        val vista = encuadreEnPagina(
            caja, anchoPagina.toDouble(), altoPagina.toDouble(), MARGEN
        ) ?: return null

        val documento = PdfDocument()
        val pagina = documento.startPage(
            PdfDocument.PageInfo.Builder(anchoPagina, altoPagina, 1).create()
        )

        Renderer(imageProvider, DrawFonts.provider(context)).renderScene(
            pagina.canvas,
            scene.copy(elements = visible, viewport = vista),
            anchoPagina.toDouble(),
            altoPagina.toDouble()
        )

        documento.finishPage(pagina)

        val carpeta = File(context.cacheDir, "share").apply { mkdirs() }
        val destino = File(carpeta, "${nombre.ifBlank { "pixpin" }}.pdf")
        destino.outputStream().use { documento.writeTo(it) }
        documento.close()
        destino
    }.getOrNull()

    const val MIME_TYPE = "application/pdf"
}
