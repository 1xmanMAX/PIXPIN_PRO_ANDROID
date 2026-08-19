package com.forge.pixpin.motor

/**
 * La tabla de una hoja de cálculo, **dibujada en el lienzo**.
 *
 * Copiar unas celdas de Excel o de Sheets y que aparezcan aquí como una tabla de
 * verdad es de las cosas que más tiempo ahorran: lo alternativo es teclear otra
 * vez lo que ya está escrito, o pegar una captura —que no se puede corregir, no
 * se puede exportar a vectores y se ve borrosa en cuanto se amplía.
 *
 * ## Se dibuja con lo que ya hay, y es a propósito
 *
 * No hay un tipo de elemento «tabla». Una tabla es **un grupo de rayas y
 * textos**, de los de siempre, y eso trae tres cosas gratis que un tipo nuevo
 * habría que ganarse una por una: se exporta a SVG y a PDF sin tocar nada, se
 * mueve y se estira con las herramientas de siempre, y un `.excalidraw` que
 * salga de aquí se abre en el navegador con su tabla puesta.
 *
 * Lo que se paga es que **no se le añade una fila después**: para eso se vuelve
 * a pegar. Es el trato de una implementación ligera, y para lo que se usa —traer
 * una tabla que ya existe— sale a cuenta.
 *
 * ## Y se dibuja recta
 *
 * A tiralíneas, sin el temblor del resto del motor ([Element.ROUGHNESS_ARCHITECT]
 * y sin esquinas redondeadas). Una rejilla temblorosa no se lee como una tabla,
 * se lee como suciedad: son muchas rectas paralelas y muy juntas, que es justo
 * donde el ruido canta. El color y el grosor sí salen del pincel, para que la
 * tabla pegue con el resto del dibujo.
 *
 * Sin Android: medir letras es lo único que necesita dispositivo y llega por
 * [MedidaDeTexto], igual que en [repartirEnLineas]. Así se puede comprobar sin
 * pantalla que las columnas salen de lo que ocupa el texto más largo, que es
 * donde se cuela el descuadre.
 */

/**
 * Lo que ocupa un texto: ancho y alto, con una letra y un tamaño ya decididos.
 *
 * Es la firma de `DrawFonts.medirTexto` con la familia ya puesta.
 */
typealias MedidaDeTexto = (texto: String, tamano: Double) -> Pair<Double, Double>

/** El aire que deja cada celda alrededor de su texto, en px de escena. */
const val AIRE_DE_CELDA = 8.0

/**
 * Lo menos que puede medir una columna.
 *
 * Una columna de celdas vacías mediría cero y la tabla saldría con dos rayas
 * pegadas. Con un mínimo, una tabla en blanco sigue siendo una tabla donde se
 * puede escribir después.
 */
const val ANCHO_MINIMO_DE_COLUMNA = 44.0

/** Y lo menos que puede medir una fila, por lo mismo. */
const val ALTO_MINIMO_DE_FILA = 28.0

/**
 * El fondo de la fila de títulos.
 *
 * Un gris muy claro, no un color: la cabecera tiene que distinguirse de un
 * vistazo sin competir con lo que haya dibujado alrededor.
 */
const val FONDO_DE_CABECERA = "#f1f3f5"

/**
 * La tabla [filas] dibujada con su esquina superior izquierda en [origen].
 *
 * Las columnas se anchan según **el texto más largo de cada una**, no a partes
 * iguales: una tabla con una columna de nombres y tres de cifras repartida a
 * partes iguales desperdicia media anchura y aun así parte los nombres.
 *
 * Todo sale agrupado, así que en el lienzo se coge y se mueve como una sola
 * cosa. Devuelve la lista vacía si no hay nada que dibujar.
 */
fun elementosDeTabla(
    filas: List<List<String>>,
    estilo: ItemStyle,
    origen: Pt,
    medir: MedidaDeTexto,
    /** La primera fila es de títulos: se dibuja con fondo. */
    conCabecera: Boolean = true
): List<Element> {
    val rejilla = rejillaRegular(filas)
    if (rejilla.isEmpty() || rejilla[0].isEmpty()) return emptyList()

    val tamanoDeLetra = estilo.fontSize
    val medidas = rejilla.map { fila -> fila.map { medir(it, tamanoDeLetra) } }

    val anchos = (0 until rejilla[0].size).map { col ->
        maxOf(
            medidas.maxOf { it[col].first } + AIRE_DE_CELDA * 2,
            ANCHO_MINIMO_DE_COLUMNA
        )
    }
    val altos = medidas.map { fila ->
        maxOf(fila.maxOf { it.second } + AIRE_DE_CELDA * 2, ALTO_MINIMO_DE_FILA)
    }

    val anchoTotal = anchos.sum()
    val altoTotal = altos.sum()

    // Dónde empieza cada columna y cada fila, ya acumulado: se necesita tantas
    // veces —cada raya, cada celda— que calcularlo dos veces es pedir que una de
    // las dos se quede a medio píxel de la otra.
    val izquierdas = anchos.runningFold(origen.x) { acc, ancho -> acc + ancho }
    val arribas = altos.runningFold(origen.y) { acc, alto -> acc + alto }

    // Recto y sin redondear: ver la nota de arriba.
    val trazo = estilo.copy(
        roughness = Element.ROUGHNESS_ARCHITECT,
        roundness = null,
        backgroundColor = Element.TRANSPARENT
    )

    val out = mutableListOf<Element>()

    // La cabecera va **la primera** para que quede debajo de las rayas y de su
    // propio texto. El orden de la lista es el orden de pintado.
    if (conCabecera && rejilla.size > 1) {
        out += newElement(
            ElementType.RECTANGLE, origen.x, origen.y,
            trazo.copy(
                backgroundColor = FONDO_DE_CABECERA,
                fillStyle = FillStyle.SOLID,
                strokeColor = Element.TRANSPARENT
            ),
            width = anchoTotal, height = altos.first()
        )
    }

    // El marco de fuera, y dentro las rayas que separan.
    out += newElement(
        ElementType.RECTANGLE, origen.x, origen.y, trazo,
        width = anchoTotal, height = altoTotal
    )
    for (col in 1 until anchos.size) {
        out += raya(
            Pt(izquierdas[col], origen.y),
            Pt(izquierdas[col], origen.y + altoTotal),
            trazo
        )
    }
    for (fila in 1 until altos.size) {
        out += raya(
            Pt(origen.x, arribas[fila]),
            Pt(origen.x + anchoTotal, arribas[fila]),
            trazo
        )
    }

    // Y el contenido. Las celdas vacías no dejan elemento: un texto sin nada
    // escrito es un elemento invisible que luego roba toques al picar.
    rejilla.forEachIndexed { fila, columnas ->
        columnas.forEachIndexed { col, contenido ->
            if (contenido.isBlank()) return@forEachIndexed
            val (ancho, alto) = medidas[fila][col]
            out += newElement(
                ElementType.TEXT,
                izquierdas[col] + AIRE_DE_CELDA,
                // Centrado en el alto de su fila: pegado arriba, una fila alta
                // por culpa de otra columna deja el texto flotando.
                arribas[fila] + (altos[fila] - alto) / 2,
                estilo,
                width = ancho, height = alto
            ).copy(text = contenido, textAlign = TextAlign.LEFT)
        }
    }

    return agrupados(out)
}

/**
 * Lo que va a ocupar la tabla, sin llegar a construirla.
 *
 * Lo pregunta quien tiene que colocarla —para centrarla en la vista— antes de
 * saber si va a caber.
 */
fun tamanoDeTabla(
    filas: List<List<String>>, estilo: ItemStyle, medir: MedidaDeTexto
): Pair<Double, Double> {
    val elementos = elementosDeTabla(filas, estilo, Pt(0.0, 0.0), medir)
    if (elementos.isEmpty()) return 0.0 to 0.0
    val caja = getCommonBounds(elementos)
    return caja.width to caja.height
}

/**
 * La rejilla con todas las filas del mismo ancho y sin filas vacías al final.
 *
 * Lo que llega del portapapeles no viene cuadrado: una fila con una celda vacía
 * al final llega más corta, y pegar una selección deja casi siempre una línea en
 * blanco. Sin esto, la tabla saldría con una fila de nada y las columnas
 * descuadradas a partir de la primera fila corta.
 */
internal fun rejillaRegular(filas: List<List<String>>): List<List<String>> {
    val utiles = filas.dropLastWhile { fila -> fila.all { it.isBlank() } }
    if (utiles.isEmpty()) return emptyList()
    val ancho = utiles.maxOf { it.size }
    if (ancho == 0) return emptyList()
    return utiles.map { fila -> List(ancho) { fila.getOrElse(it) { "" }.trim() } }
}

/** Una raya de dos puntos entre dos sitios de la escena. */
private fun raya(desde: Pt, hasta: Pt, estilo: ItemStyle): Element =
    newElement(ElementType.LINE, desde.x, desde.y, estilo).copy(
        points = listOf(Pt(0.0, 0.0), Pt(hasta.x - desde.x, hasta.y - desde.y)),
        width = kotlin.math.abs(hasta.x - desde.x),
        height = kotlin.math.abs(hasta.y - desde.y)
    )

/**
 * Todos en un grupo, para que se cojan de una vez.
 *
 * Sin esto, mover una tabla de cuatro columnas serían veinte arrastres, y el
 * primero que se olvidara dejaría una raya suelta en medio del dibujo.
 */
internal fun agrupados(elementos: List<Element>): List<Element> {
    if (elementos.size < 2) return elementos
    val grupo = randomId()
    return elementos.map { it.copy(groupIds = it.groupIds + grupo) }
}
