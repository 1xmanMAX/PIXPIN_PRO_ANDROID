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

    /**
     * Ancho de la hoja grande del carrusel.
     *
     * La miniatura de la rejilla ampliada a este tamaño se ve emborronada —es
     * la diferencia entre reconocer una página y **leerla**—, y pasarse tampoco
     * sirve: por encima de esto la pantalla ya no tiene puntos donde ponerlo.
     */
    const val CARD_WIDTH = 720

    /**
     * Ancho al que se redibuja una página mientras se amplía con los dedos.
     *
     * Solo se pide de la que se está mirando, y solo cuando se amplía: es la
     * única página del documento que merece este tamaño.
     */
    const val ZOOM_WIDTH = 1080

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

    /**
     * Dibuja **varias páginas con el documento abierto una sola vez**.
     *
     * Abrir un PDF cuesta leer su tabla de referencias cruzadas, que en un
     * documento largo son miles de entradas; hacerlo una vez por página —que es
     * lo que sale de llamar a [render] en un bucle— multiplica ese trabajo por
     * el número de páginas y es lo que hacía que una lista larga tardara.
     *
     * Cada página se entrega según sale, para que quien la espere no tenga que
     * aguardar a la última. Ver [PdfMiniaturas].
     */
    fun porTandas(
        path: String,
        indices: Iterable<Int>,
        targetWidth: Int,
        /**
         * Si conviene seguir. Se pregunta **antes de cada página**.
         *
         * Hojear rápido pide tandas nuevas más deprisa de lo que se dibujan, y
         * sin esta pregunta cada tanda abandonada seguía dibujando hasta el
         * final —con el candado cogido— mientras la que de verdad se está
         * mirando esperaba su turno detrás. Cuanto más rápido hojeabas, más
         * tardaba en aparecer lo de delante.
         */
        sigue: () -> Boolean = { true },
        cada: (indice: Int, bitmap: Bitmap) -> Unit
    ) {
        withDoc(path) { doc ->
            for (i in indices) {
                if (!sigue()) break
                if (i < 0 || i >= doc.pageCount) continue
                runCatching {
                    doc.openPage(i).use { page ->
                        val w = targetWidth.coerceAtLeast(1)
                        val h = (w.toLong() * page.height / page.width.coerceAtLeast(1))
                            .toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cada(i, bmp)
                    }
                }
            }
            Unit
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
