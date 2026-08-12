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
     * Mete [scene] como **una hoja más** al final de un PDF.
     *
     * La hoja sale del tamaño de un A4 y el dibujo se encaja dentro, con su
     * margen: es una lámina del documento, así que tiene que poder imprimirse
     * con las demás sin que nadie ajuste nada.
     */
    fun aniadirComoPagina(
        context: Context,
        original: ByteArray,
        scene: Scene,
        imageProvider: (String) -> Bitmap? = { null }
    ): ByteArray? = runCatching {
        val archivo = leerPdf(original) ?: return null
        if (archivo.cifrado) return null

        val visible = scene.contenidoVisible
        if (visible.isEmpty()) return null
        val caja = scene.marco?.let { getElementBounds(it) } ?: getCommonBounds(visible)
        if (caja.width <= 0 || caja.height <= 0) return null

        // Apaisada si el dibujo lo es, como hace la exportación de siempre.
        val apaisado = caja.width > caja.height
        val anchoPagina = (if (apaisado) A4_ALTO else A4_ANCHO).toDouble()
        val altoPagina = (if (apaisado) A4_ANCHO else A4_ALTO).toDouble()

        // El dibujo, encajado y centrado, con la Y al revés: el papel mide de
        // abajo arriba y la escena de arriba abajo.
        val escala = minOf(
            (anchoPagina - MARGEN * 2) / caja.width,
            (altoPagina - MARGEN * 2) / caja.height
        )
        // La matriz: escalar, voltear la Y y centrar. `dy` sale de la esquina de
        // abajo de la escena porque en el papel la Y crece hacia arriba y en la
        // escena hacia abajo, así que lo que en el dibujo estaba abajo es lo que
        // marca dónde empieza a subir.
        val dx = (anchoPagina - caja.width * escala) / 2 - caja.x1 * escala
        val dy = (altoPagina + caja.height * escala) / 2 + caja.y1 * escala

        val dibujo = PdfLienzo.deEscena(
            context, scene,
            doubleArrayOf(escala, 0.0, 0.0, -escala, dx, dy),
            primerObjeto = PdfEscritura.primerNumeroLibre(archivo),
            imageProvider = imageProvider
        ) ?: return null

        PdfAnotado.aniadirPagina(
            archivo, dibujo.contenido, anchoPagina, altoPagina,
            recursos = dibujo.recursos, extra = dibujo.extra
        )
    }.getOrNull()

    /**
     * Mete una nota escrita al final del PDF, como texto de verdad.
     *
     * Ver [PdfDeNota]: sale seleccionable y buscable, no dibujada.
     */
    fun aniadirNota(original: ByteArray, texto: String): ByteArray? =
        PdfDeNota.aniadir(original, texto)

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

    /**
     * Pega [scene] sobre la página [pagina] de un PDF **ajeno**, y devuelve el
     * archivo entero.
     *
     * Es la puerta de todo el camino, y no hace casi nada por su cuenta: lee el
     * archivo ([leerPdf]), calcula la matriz de las cuatro esquinas
     * ([PdfAnotado.matrizDePagina]), traduce el dibujo a órdenes ([PdfLienzo]) y
     * lo cuelga como capa ([PdfAnotado.anotar]). Que sea tan corta es la señal
     * de que las cuatro piezas encajan.
     *
     * [anchoImagen] y [altoImagen] son los de la imagen de la página sobre la
     * que se dibujó: es lo que dice a qué escala está lo anotado. Salen de
     * `PdfDoc.render`, así que quien llame ya los tiene.
     *
     * Devuelve null si el PDF está cifrado, si la página no existe o si no había
     * nada dibujado. **Nunca devuelve un archivo a medias.**
     */
    fun anotarPagina(
        context: Context,
        original: ByteArray,
        pagina: Int,
        scene: Scene,
        anchoImagen: Double,
        altoImagen: Double,
        nombreDeLaCapa: String = "PixPin",
        imageProvider: (String) -> Bitmap? = { null },
        /**
         * La página ya pintada, si quien llama la tiene.
         *
         * Solo la necesita el mosaico, que no inventa píxeles: los coge de lo
         * que tapa. Sin ella un mosaico sale como una placa esmerilada — sigue
         * tapando, que es lo suyo, pero no se parece al de la pantalla.
         */
        hojaPintada: Bitmap? = null
    ): ByteArray? = runCatching {
        val archivo = leerPdf(original) ?: return null
        if (archivo.cifrado) return null
        val matriz = PdfAnotado.matrizDePagina(archivo, pagina, anchoImagen, altoImagen)
            ?: return null

        val dibujo = PdfLienzo.deEscena(
            context, scene, matriz,
            // Las imágenes se numeran desde el primer libre; [PdfAnotado] pide
            // los suyos con `max(primerLibre, elMayorDeExtra + 1)`, así que se
            // colocan detrás solas y no hace falta apartarles sitio.
            primerObjeto = PdfEscritura.primerNumeroLibre(archivo),
            imageProvider = imageProvider,
            fondo = hojaPintada
        ) ?: return null

        PdfAnotado.anotar(
            archivo, pagina, dibujo.contenido,
            recursos = dibujo.recursos,
            extra = dibujo.extra,
            nombre = nombreDeLaCapa
        )
    }.getOrNull()


}
