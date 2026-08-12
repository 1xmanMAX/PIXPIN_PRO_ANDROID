package com.forge.pixpin.motor

import kotlin.math.floor

/**
 * El lienzo infinito, dividido en hojas A4 **invisibles**.
 *
 * ## La idea
 *
 * Un lienzo infinito es cómodo para trabajar y malo para entregar: al final hay
 * que sacar un documento, y un documento son páginas. Hasta ahora eso se
 * resolvía dibujando marcos a mano, que es acordarse de hacerlo y colocarlos.
 *
 * Con esto el lienzo **ya está paginado desde el primer día**, solo que no se ve:
 * hay una cuadrícula de folios A4 pegados, en todas direcciones, y **la hoja que
 * tiene algo dibujado existe**. Las demás no están ahí molestando. Dibujas donde
 * quieras y el documento sale solo, con una página por cada zona que hayas usado.
 *
 * ## Por qué se cuenta desde el cero del lienzo
 *
 * La cuadrícula está anclada al origen de coordenadas, no a lo primero que se
 * dibuje. Es lo que hace que las hojas **no se muevan nunca**: si se anclara al
 * primer trazo, borrarlo recolocaría la cuadrícula entera y todo lo demás
 * cambiaría de página sin que nadie lo tocara.
 *
 * Las hojas se numeran en **orden de lectura** —de arriba abajo y de izquierda a
 * derecha—, que es el orden en que saldrán del PDF.
 */
object HojasA4 {

    /**
     * Lo que mide una hoja en el lienzo.
     *
     * Son los puntos de un A4, los mismos que usa el PDF. Así lo que se dibuja
     * dentro de una hoja sale del mismo tamaño en el papel, sin escalar nada, y
     * un trazo de dos puntos de grosor mide dos puntos también impreso.
     */
    const val ANCHO = 595.0
    const val ALTO = 842.0

    /** Una hoja de la cuadrícula, por su columna y su fila. */
    data class Celda(val columna: Int, val fila: Int)

    /** En qué hoja cae un punto del lienzo. */
    fun celdaEn(x: Double, y: Double): Celda =
        Celda(floor(x / ANCHO).toInt(), floor(y / ALTO).toInt())

    /** Dónde está esa hoja en el lienzo. */
    fun cajaDe(celda: Celda): Bounds = Bounds(
        celda.columna * ANCHO,
        celda.fila * ALTO,
        (celda.columna + 1) * ANCHO,
        (celda.fila + 1) * ALTO
    )

    /**
     * Las hojas que tocan a [caja], que pueden ser varias.
     *
     * Un dibujo grande cruza el borde de una hoja y aparece en las dos, como
     * pasaría en el papel. No se parte ni se elige una: **si algo asoma en una
     * hoja, esa hoja existe**, y al exportarla se verá esa parte.
     */
    fun celdasDe(caja: Bounds): List<Celda> {
        if (caja.width < 0 || caja.height < 0) return emptyList()
        val desde = celdaEn(caja.x1, caja.y1)
        val hasta = celdaEn(caja.x2, caja.y2)
        // Un tope por si llega una caja absurda: mil hojas no las quiere nadie,
        // y sin él una coordenada disparatada colgaría la app llenando memoria.
        if ((hasta.columna - desde.columna + 1).toLong() *
            (hasta.fila - desde.fila + 1).toLong() > MAXIMO
        ) {
            return listOf(desde)
        }
        val salida = mutableListOf<Celda>()
        for (f in desde.fila..hasta.fila) {
            for (c in desde.columna..hasta.columna) salida += Celda(c, f)
        }
        return salida
    }

    /** Cuántas hojas se admiten de una sola vez. */
    private const val MAXIMO = 400L

    /**
     * Las hojas con algo dibujado, en orden de lectura.
     *
     * Arriba antes que abajo, e izquierda antes que derecha: el orden en que se
     * mira una mesa llena de folios, y el que tendrán las páginas del PDF.
     */
    fun ocupadas(elementos: List<Element>): List<Celda> {
        val vistas = LinkedHashSet<Celda>()
        elementos.forEach { e ->
            // Los marcos no cuentan: son una forma distinta de paginar, y si
            // contaran, dibujar un marco crearía hojas por su cuenta.
            if (e.isFrame) return@forEach
            celdasDe(getElementBounds(e)).forEach { vistas += it }
        }
        return vistas.sortedWith(compareBy({ it.fila }, { it.columna }))
    }

    /** Lo que hay dibujado dentro de una hoja, aunque asome por fuera. */
    fun contenidoDe(elementos: List<Element>, celda: Celda): List<Element> {
        val caja = cajaDe(celda)
        return elementos.filter { e ->
            if (e.isFrame) return@filter false
            val suya = getElementBounds(e)
            suya.x1 < caja.x2 && suya.x2 > caja.x1 && suya.y1 < caja.y2 && suya.y2 > caja.y1
        }
    }

    /**
     * Las páginas del documento: una por hoja ocupada, con su caja y su
     * contenido.
     *
     * Es lo que consume la exportación y lo que enseña la previsualización, para
     * que las dos digan lo mismo: lo que se ve en la vista previa es
     * exactamente lo que va a salir en el PDF.
     */
    fun paginas(elementos: List<Element>): List<Pair<Bounds, List<Element>>> =
        ocupadas(elementos).map { cajaDe(it) to contenidoDe(elementos, it) }

    /**
     * Una escena que **es solo esa hoja**, para pintarla suelta.
     *
     * Lleva un marco del tamaño del folio, y con marco el pintado ya sabe
     * encuadrar sin margen ni sorpresas —ver `DrawExport.aBitmap`—. Así la
     * miniatura de la vista previa sale por el mismo camino que cualquier otra
     * exportación, sin un pintor aparte que se desincronice.
     */
    fun escenaDe(escena: Scene, celda: Celda): Scene {
        val caja = cajaDe(celda)
        val marco = Element(
            id = "hoja-${celda.columna}-${celda.fila}",
            type = ElementType.FRAME,
            x = caja.x1, y = caja.y1,
            width = caja.width, height = caja.height,
            seed = 1
        )
        return escena.copy(
            elements = contenidoDe(escena.contenidoVisible, celda) + marco
        )
    }
}
