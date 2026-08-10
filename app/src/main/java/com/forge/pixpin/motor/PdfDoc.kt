package com.forge.pixpin.motor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Lectura de PDFs con [PdfRenderer], que viene en el propio Android: no hace
 * falta ninguna librería.
 *
 * **Vive en el motor y no en el pin**, aunque naciera allí para las miniaturas.
 * Pintar una página de un PDF es trabajo de edición —es el papel sobre el que
 * se anota— y el motor no puede depender del pin: si lo hiciera dejaría de
 * poder usarse en la captura y en la capa, que es justo lo que un motor tiene
 * que poder. La prueba de la frontera lo cazó en cuanto el editor lo necesitó.
 *
 * Cada operación abre y cierra el documento. Mantenerlo abierto sería más
 * rápido, pero `PdfRenderer` **no admite dos páginas abiertas a la vez** y un
 * descriptor de archivo vivo entre ventanas overlay es justo el tipo de cosa
 * que se queda colgando cuando algo se cierra por sorpresa. Un PDF se hojea a
 * ritmo humano; el coste no se nota.
 */
object PdfDoc {

    /** Ancho al que se dibujan las miniaturas de la rejilla, en px. */
    const val THUMB_WIDTH = 220

    /** Ancho al que se extrae una página para convertirla en pin. */
    const val PAGE_WIDTH = 1400

    fun pageCount(path: String): Int = withDoc(path) { it.pageCount } ?: 0

    /**
     * Dibuja una página a bitmap, respetando su proporción.
     *
     * @param targetWidth ancho deseado; el alto sale de la proporción real.
     */
    fun render(path: String, index: Int, targetWidth: Int): Bitmap? = withDoc(path) { doc ->
        if (index < 0 || index >= doc.pageCount) return@withDoc null
        doc.openPage(index).use { page ->
            val w = targetWidth.coerceAtLeast(1)
            val h = (w.toLong() * page.height / page.width.coerceAtLeast(1))
                .toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // Fondo blanco explícito: un PDF sin fondo se renderiza transparente
            // y sobre un pin oscuro el texto negro no se vería.
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    private fun <T> withDoc(path: String, block: (PdfRenderer) -> T?): T? = runCatching {
        val file = File(path)
        if (!file.exists()) return null
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { block(it) }
        }
    }.getOrNull()
}
