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

    /**
     * **Cuántos píxeles de ancho se le dan a una página que va a una página web.**
     *
     * En pantalla el papel es [PAGE_WIDTH] porque esas son las unidades del dibujo, y con eso
     * basta: lo que se lee de cerca lo pone el mosaico. Pero en un documento web el papel es
     * **una imagen y nada más**, y con mil cuatrocientos píxeles un plano es una mancha en
     * cuanto se amplía. Cuatro mil son unos ciento veinte puntos por pulgada en un A0 —tres
     * veces lo de antes— y el archivo sigue abriéndose en un teléfono.
     */
    const val ANCHO_PARA_LA_WEB = 4000

    /**
     * Los anchos que se prueban, de más a menos. Una página de cuatro mil píxeles en un A0
     * son noventa megas mientras se monta, y en un teléfono justo eso puede no haberlo: si no
     * cabe se prueba el siguiente, y en el peor caso sale la de siempre.
     */
    val ANCHOS_PARA_LA_WEB = listOf(ANCHO_PARA_LA_WEB, 2800, 2000, PAGE_WIDTH)

    /** **La página al mayor detalle que quepa**, para meterla en un documento web. */
    fun paraLaWeb(path: String, index: Int): Bitmap? {
        for (ancho in ANCHOS_PARA_LA_WEB) {
            render(path, index, ancho)?.let { return it }
        }
        return null
    }

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

    /**
     * **Lo que mide una página, en puntos PDF.** De aquí sale si un plano pide mosaico.
     * Ver [MosaicoDePdf].
     */
    fun medidaEnPuntos(path: String, index: Int): Pair<Double, Double>? = withDoc(path) { doc ->
        if (index < 0 || index >= doc.pageCount) return@withDoc null
        doc.openPage(index).use { it.width.toDouble() to it.height.toDouble() }
    }

    /**
     * **Un trozo de una página, rasterizado al detalle que se pida.**
     *
     * [enUnidades] es el rectángulo del dibujo que se quiere, midiendo la página entera de
     * `(0,0)` a `(anchoDelPapel, altoDelPapel)` —o sea, en píxeles de la imagen de una sola
     * pieza—, y sale un mapa de bits de [ladoEnPixeles] de lado con ese trozo dentro.
     *
     * Se hace con la matriz de `PdfRenderer`, que es lo que permite rasterizar **un pedazo a
     * la resolución que haga falta** sin montar la página entera: montarla entera a treinta y
     * dos aumentos serían cuarenta mil píxeles de lado y no cabe en memoria de ninguna manera.
     * Ver [MosaicoDePdf].
     */
    fun trozo(
        path: String,
        index: Int,
        enUnidades: Bounds,
        anchoDelPapel: Double,
        ladoEnPixeles: Int
    ): Bitmap? {
        var salida: Bitmap? = null
        trozos(path, index, listOf(enUnidades), anchoDelPapel, ladoEnPixeles) { _, bmp ->
            salida = bmp
            true
        }
        return salida
    }

    /**
     * **Un pedazo de la página a la resolución que se pida, con la forma que se pida.**
     *
     * Es [trozo] sin la atadura del cuadro: aquí el rectángulo no tiene por qué ser cuadrado
     * ni medir un lado fijo, porque lo que se pide es **lo que se está mirando** — una
     * pantalla, que es más alta que ancha. De aquí sale la lámina de cerca, que es lo único
     * que deja leer una letra del PDF pasado el aumento al que llegan los cuadros del
     * mosaico. Ver `LaminaDeCerca` y [MosaicoDePdf.laminaPara].
     *
     * [enUnidades] es el rectángulo del dibujo que se quiere, midiendo la página entera de
     * `(0,0)` a `(anchoDelPapel, altoDelPapel)`; [anchoEnPixeles] y [altoEnPixeles], el mapa
     * de bits que sale.
     */
    fun lamina(
        path: String,
        index: Int,
        enUnidades: Bounds,
        anchoDelPapel: Double,
        anchoEnPixeles: Int,
        altoEnPixeles: Int
    ): Bitmap? {
        if (anchoEnPixeles <= 0 || altoEnPixeles <= 0) return null
        if (enUnidades.width <= 0 || enUnidades.height <= 0 || anchoDelPapel <= 0) return null
        return withDoc(path) { doc ->
            if (index < 0 || index >= doc.pageCount) return@withDoc null
            doc.openPage(index).use { page ->
                val unidadesPorPunto = anchoDelPapel / page.width.coerceAtLeast(1)
                val pxPorUnidadX = anchoEnPixeles / enUnidades.width
                val pxPorUnidadY = altoEnPixeles / enUnidades.height
                val m = android.graphics.Matrix()
                m.postScale(
                    (pxPorUnidadX * unidadesPorPunto).toFloat(),
                    (pxPorUnidadY * unidadesPorPunto).toFloat()
                )
                m.postTranslate(
                    (-enUnidades.x1 * pxPorUnidadX).toFloat(),
                    (-enUnidades.y1 * pxPorUnidadY).toFloat()
                )
                // ARGB_8888 porque es el único formato que acepta `render`. Ver [trozos].
                val bmp =
                    Bitmap.createBitmap(anchoEnPixeles, altoEnPixeles, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    /**
     * **Varios trozos de la misma página, con el documento abierto una sola vez.**
     *
     * Abrir el archivo, montar el `PdfRenderer` y abrir la página cuesta más que rasterizar
     * un cuadro de quinientos píxeles, y haciéndolo por trozo se pagaba **una vez por cada
     * uno**: con doce trozos a la vista, eso es doce veces el mismo trabajo tirado. Con el
     * documento abierto de una vez, lo que queda es el rasterizado, que es lo único que de
     * verdad hay que hacer. Ver [MosaicoDePdf].
     *
     * [cada] recibe el índice dentro de [enUnidades] y su mapa de bits, y devuelve si hay que
     * seguir: así quien pide puede parar en cuanto lo que quería deje de estar a la vista.
     */
    fun trozos(
        path: String,
        index: Int,
        enUnidades: List<Bounds>,
        anchoDelPapel: Double,
        ladoEnPixeles: Int,
        cada: (Int, Bitmap) -> Boolean
    ) {
        if (enUnidades.isEmpty() || anchoDelPapel <= 0) return
        withDoc(path) { doc ->
            if (index < 0 || index >= doc.pageCount) return@withDoc null
            doc.openPage(index).use { page ->
                val unidadesPorPunto = anchoDelPapel / page.width.coerceAtLeast(1)
                for ((i, donde) in enUnidades.withIndex()) {
                    if (donde.width <= 0) continue
                    val pxPorUnidad = ladoEnPixeles / donde.width
                    val escala = (pxPorUnidad * unidadesPorPunto).toFloat()
                    val m = android.graphics.Matrix()
                    m.postScale(escala, escala)
                    m.postTranslate(
                        (-donde.x1 * pxPorUnidad).toFloat(),
                        (-donde.y1 * pxPorUnidad).toFloat()
                    )
                    // **ARGB_8888 y no RGB_565.** `PdfRenderer.render` solo acepta ese
                    // formato: con cualquier otro lanza «Unsupported pixel format», y como
                    // aquí eso se recogía sin decir nada, el resultado era que **no se
                    // rasterizaba ni un cuadro** y el plano se quedaba con la imagen de una
                    // pieza, o sea con grano. Ahorrar memoria se hace luego, al guardarlos
                    // comprimidos y al abrirlos en 565.
                    val bmp =
                        Bitmap.createBitmap(ladoEnPixeles, ladoEnPixeles, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    if (!cada(i, bmp)) break
                }
            }
            Unit
        }
    }

    /**
     * **La página entera por franjas anchas, en una pasada cada una.**
     *
     * Aquí está lo que de verdad cuesta de un plano: **recorrer sus órdenes de dibujo**. En un
     * plano de arquitectura de verdad —un A0 con ochocientas mil líneas— rasterizar la página
     * tarda casi lo mismo a dieciocho puntos por pulgada que a ciento cuarenta y cuatro,
     * porque el trabajo no es pintar píxeles sino pasar por la lista. Y eso significa que
     * **pedir la página en ochocientos cuadros es pagar ochocientas veces esa pasada**: horas.
     *
     * Con franjas se paga una pasada por franja —unas treinta en vez de ochocientas— y de cada
     * franja salen sus cuadros con un recorte, que es copiar memoria y no cuesta nada.
     *
     * [anchoEnPixeles] y [altoEnPixeles] son la página entera ya rasterizada; [altoDeFranja],
     * lo que se hace de una vez; [cuales], qué franjas y **en qué orden** —quien pide empieza
     * por la que está mirando—. [cada] devuelve si hay que seguir.
     */
    fun franjas(
        path: String,
        index: Int,
        anchoEnPixeles: Int,
        altoEnPixeles: Int,
        /**
         * Lo que mide **el papel** rasterizado, de ancho y en píxeles. Va aparte porque el
         * mapa de bits de la franja se hace a la medida de la rejilla de cuadros, que se pasa
         * un poco: lo que sobra queda en blanco, y así ningún cuadro sale estirado.
         */
        anchoDelPapel: Double,
        altoDeFranja: Int,
        cuales: List<Int>,
        cada: (Int, Bitmap) -> Boolean
    ) {
        if (anchoEnPixeles <= 0 || altoEnPixeles <= 0 || altoDeFranja <= 0) return
        withDoc(path) { doc ->
            if (index < 0 || index >= doc.pageCount) return@withDoc null
            doc.openPage(index).use { page ->
                val escala = (anchoDelPapel / page.width.coerceAtLeast(1)).toFloat()
                for (f in cuales) {
                    val arriba = f.toLong() * altoDeFranja
                    if (arriba >= altoEnPixeles) continue
                    val alto = minOf(altoDeFranja.toLong(), altoEnPixeles - arriba).toInt()
                    if (alto <= 0) continue
                    val m = android.graphics.Matrix()
                    m.postScale(escala, escala)
                    m.postTranslate(0f, -arriba.toFloat())
                    // ARGB_8888 porque es el único formato que acepta `render`. Ver [trozos].
                    val bmp = Bitmap.createBitmap(anchoEnPixeles, alto, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val sigue = cada(f, bmp)
                    if (!sigue) break
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
