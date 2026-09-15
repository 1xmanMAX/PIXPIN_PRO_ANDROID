package com.forge.pixpin.guardados

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import com.forge.pixpin.motor.Bounds
import com.forge.pixpin.motor.DrawFonts
import com.forge.pixpin.motor.DrawPdf
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.PdfUnion
import com.forge.pixpin.motor.Renderer
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.encuadreEnPagina
import com.forge.pixpin.motor.getCommonBounds
import com.forge.pixpin.motor.getElementBounds
import java.io.FileOutputStream

/**
 * **Imprimir el lienzo** (14-sep-2026): un toque y sale el diálogo de impresión del sistema,
 * con el papel a elegir —A4, A3, carta…— y la impresora, o «Guardar como PDF».
 *
 * El papel lo elige el diálogo y **el dibujo se vuelve a encajar en el papel elegido**, en
 * vectores: no es una imagen A4 estirada a A3, así que a A3 sale igual de nítido. Cada marco
 * es una hoja, como en la exportación a PDF ([DrawPdf.aArchivo]); sin marcos, todo en una.
 *
 * Con un PDF debajo se imprime **esa página del documento** con lo anotado encima, también en
 * vectores, y es el servicio de impresión quien la encaja en el papel.
 */
object Imprimir {

    /** Margen del papel, en puntos: un dedo, como en el PDF exportado. */
    private const val MARGEN = 28.0

    fun lienzo(
        actividad: Activity,
        escena: Scene,
        nombre: String,
        imagenes: (String) -> Bitmap?
    ) {
        val hojas = hojasDe(escena)
        if (hojas.isEmpty()) {
            android.widget.Toast.makeText(actividad, "No hay nada que imprimir", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val apaisada = hojas.first().first.let { it.width > it.height }
        val manager = actividad.getSystemService(Activity.PRINT_SERVICE) as PrintManager
        manager.print(
            nombre.ifBlank { "PixPin" },
            AdaptadorDeEscena(actividad, escena, hojas, imagenes, nombre),
            PrintAttributes.Builder()
                .setMediaSize(
                    if (apaisada) PrintAttributes.MediaSize.ISO_A4.asLandscape()
                    else PrintAttributes.MediaSize.ISO_A4.asPortrait()
                )
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
        )
    }

    /**
     * La página [pagina] de un PDF con lo dibujado encima. [pdfConAnotacion] es el documento
     * entero ya anotado —o el original si no hay nada dibujado—; se imprime solo esa página.
     */
    fun paginaDePdf(actividad: Activity, pdfConAnotacion: ByteArray, pagina: Int, nombre: String) {
        val solo = PdfUnion.soloPaginas(pdfConAnotacion, listOf(pagina)) ?: pdfConAnotacion
        val manager = actividad.getSystemService(Activity.PRINT_SERVICE) as PrintManager
        manager.print(nombre.ifBlank { "PixPin" }, AdaptadorDeBytes(solo, nombre), null)
    }

    /** Las hojas a imprimir: una por marco con contenido; si no hay, una con todo. */
    private fun hojasDe(escena: Scene): List<Pair<Bounds, List<Element>>> =
        escena.marcos
            .filter { m ->
                val c = getElementBounds(m)
                c.width > 0 && c.height > 0 && escena.contenidoDe(m).isNotEmpty()
            }
            .map { m ->
                getElementBounds(m) to (listOfNotNull(m.takeIf { it.papel != null }) + escena.contenidoDe(m))
            }
            .ifEmpty {
                val visible = escena.contenidoVisible
                if (visible.isEmpty()) emptyList()
                else listOf(getCommonBounds(visible) to visible)
            }

    private class AdaptadorDeEscena(
        val actividad: Activity,
        val escena: Scene,
        val hojas: List<Pair<Bounds, List<Element>>>,
        val imagenes: (String) -> Bitmap?,
        val nombre: String
    ) : PrintDocumentAdapter() {

        private var atributos: PrintAttributes? = null

        override fun onLayout(
            viejos: PrintAttributes?, nuevos: PrintAttributes, cancelar: CancellationSignal,
            respuesta: LayoutResultCallback, extras: Bundle?
        ) {
            if (cancelar.isCanceled) { respuesta.onLayoutCancelled(); return }
            atributos = nuevos
            val info = PrintDocumentInfo.Builder("${nombre.ifBlank { "pixpin" }}.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(hojas.size)
                .build()
            respuesta.onLayoutFinished(info, nuevos != viejos)
        }

        override fun onWrite(
            paginas: Array<out PageRange>, destino: ParcelFileDescriptor,
            cancelar: CancellationSignal, respuesta: WriteResultCallback
        ) {
            val attrs = atributos ?: run { respuesta.onWriteFailed("Sin papel elegido"); return }
            val documento = PrintedPdfDocument(actividad, attrs)
            val renderizador = Renderer(imagenes, DrawFonts.provider(actividad), paraExportar = true)
            val escritas = ArrayList<PageRange>()
            try {
                for ((i, hoja) in hojas.withIndex()) {
                    if (cancelar.isCanceled) { respuesta.onWriteCancelled(); return }
                    if (paginas.none { it == PageRange.ALL_PAGES || i in it.start..it.end }) continue
                    val pagina = documento.startPage(i)
                    val ancho = pagina.info.pageWidth.toDouble()
                    val alto = pagina.info.pageHeight.toDouble()
                    val vista = encuadreEnPagina(hoja.first, ancho, alto, MARGEN)
                    if (vista != null) {
                        renderizador.renderScene(
                            pagina.canvas, escena.copy(elements = hoja.second, viewport = vista), ancho, alto
                        )
                    }
                    documento.finishPage(pagina)
                    escritas += PageRange(i, i)
                }
                FileOutputStream(destino.fileDescriptor).use { documento.writeTo(it) }
                respuesta.onWriteFinished(escritas.toTypedArray())
            } catch (e: Exception) {
                respuesta.onWriteFailed(e.message)
            } finally {
                documento.close()
            }
        }
    }

    private class AdaptadorDeBytes(val bytes: ByteArray, val nombre: String) : PrintDocumentAdapter() {
        override fun onLayout(
            viejos: PrintAttributes?, nuevos: PrintAttributes, cancelar: CancellationSignal,
            respuesta: LayoutResultCallback, extras: Bundle?
        ) {
            if (cancelar.isCanceled) { respuesta.onLayoutCancelled(); return }
            respuesta.onLayoutFinished(
                PrintDocumentInfo.Builder("${nombre.ifBlank { "pixpin" }}.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build(),
                true
            )
        }

        override fun onWrite(
            paginas: Array<out PageRange>, destino: ParcelFileDescriptor,
            cancelar: CancellationSignal, respuesta: WriteResultCallback
        ) {
            try {
                FileOutputStream(destino.fileDescriptor).use { it.write(bytes) }
                respuesta.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                respuesta.onWriteFailed(e.message)
            }
        }
    }
}
