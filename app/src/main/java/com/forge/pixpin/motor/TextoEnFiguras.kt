package com.forge.pixpin.motor

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Texto **dentro** de una figura: el rectángulo con su palabra, el círculo con
 * su nombre.
 *
 * Es lo que convierte el motor en algo con lo que se hacen diagramas. Un
 * organigrama, un esquema de flujo o un mapa conceptual no son formas y textos
 * sueltos puestos uno encima de otro: son **cajas que dicen algo**, y la
 * diferencia se nota en cuanto mueves una. Un texto suelto se queda donde
 * estaba; un texto de la caja se va con ella.
 *
 * ## Portado de Excalidraw, con sus números
 *
 * Su modelo ya estaba aquí —[Element.containerId] y [Element.boundElements]
 * existen desde el port inicial— y las cuentas son las suyas, cotejadas contra
 * `packages/element/src/textElement.ts`. No es pereza: son cuentas que alguien
 * dedujo con cuidado y que están **explicadas en sus propias fusiones**, y
 * reinventarlas daría un texto que cabe en un rectángulo y se sale de un rombo.
 *
 * | Figura | Cuánto cabe de ancho |
 * |---|---|
 * | Rectángulo | `ancho − 2·relleno` |
 * | Elipse | `redondear(ancho/2 · √2) − 2·relleno` |
 * | Rombo | `redondear(ancho/2) − 2·relleno` |
 *
 * Las dos raras tienen su porqué geométrico: el rectángulo más grande que cabe
 * dentro de una elipse mide `ancho/2·√2`, y dentro de un rombo, `ancho/2`. Puesto
 * de otro modo: en un círculo del mismo ancho cabe un 70 % del texto, y en un
 * rombo, la mitad. Quien escriba en un rombo verá que se le queda pequeño antes,
 * y eso no es un fallo — es que un rombo tiene menos sitio dentro.
 */

/** El aire entre el texto y el borde de su figura. El de Excalidraw. */
const val RELLENO_DEL_TEXTO = 5.0

/** Qué figuras admiten texto dentro. */
fun admiteTextoDentro(tipo: ElementType): Boolean =
    tipo == ElementType.RECTANGLE || tipo == ElementType.ELLIPSE ||
        tipo == ElementType.DIAMOND

/**
 * La esquina de arriba a la izquierda del hueco donde va el texto.
 *
 * En una elipse y en un rombo el hueco no empieza en la esquina de la caja: hay
 * que meterse hacia dentro hasta donde la figura deja sitio de verdad.
 */
fun esquinaDelHueco(contenedor: Element): Pt {
    var dx = RELLENO_DEL_TEXTO
    var dy = RELLENO_DEL_TEXTO
    when (contenedor.type) {
        ElementType.ELLIPSE -> {
            dx += (contenedor.width / 2) * (1 - sqrt(2.0) / 2)
            dy += (contenedor.height / 2) * (1 - sqrt(2.0) / 2)
        }
        ElementType.DIAMOND -> {
            dx += contenedor.width / 4
            dy += contenedor.height / 4
        }
        else -> Unit
    }
    return Pt(contenedor.x + dx, contenedor.y + dy)
}

/** Lo ancho que puede ser el texto dentro de [contenedor]. */
fun anchoQueCabe(contenedor: Element): Double = when (contenedor.type) {
    ElementType.ELLIPSE ->
        (contenedor.width / 2 * sqrt(2.0)).roundToInt() - RELLENO_DEL_TEXTO * 2
    ElementType.DIAMOND ->
        (contenedor.width / 2).roundToInt() - RELLENO_DEL_TEXTO * 2
    else -> contenedor.width - RELLENO_DEL_TEXTO * 2
}

/** Y lo alto. */
fun altoQueCabe(contenedor: Element): Double = when (contenedor.type) {
    ElementType.ELLIPSE ->
        (contenedor.height / 2 * sqrt(2.0)).roundToInt() - RELLENO_DEL_TEXTO * 2
    ElementType.DIAMOND ->
        (contenedor.height / 2).roundToInt() - RELLENO_DEL_TEXTO * 2
    else -> contenedor.height - RELLENO_DEL_TEXTO * 2
}

/**
 * Lo que tiene que medir la figura para que quepa un texto de [medida].
 *
 * Es la vuelta de [anchoQueCabe], y es lo que hace que **la caja crezca sola**
 * cuando escribes más de lo que cabe. Sin esto, al llegar al borde el texto
 * seguiría saliendo por fuera del rectángulo y habría que estirarlo a mano cada
 * vez — que es justo el trabajo que un diagrama no debería dar.
 */
fun figuraQueLoContiene(medida: Double, tipo: ElementType): Double {
    val d = kotlin.math.ceil(medida)
    val relleno = RELLENO_DEL_TEXTO * 2
    return when (tipo) {
        ElementType.ELLIPSE -> ((d + relleno) / sqrt(2.0) * 2).roundToInt().toDouble()
        ElementType.DIAMOND -> 2 * (d + relleno)
        else -> d + relleno
    }
}

/**
 * Dónde va el texto dentro de su figura: **centrado**.
 *
 * Centrado en los dos ejes y no arriba a la izquierda, que es lo que hace que
 * una caja de diagrama se lea como una etiqueta y no como un párrafo metido en
 * un marco. Excalidraw permite las nueve posiciones; aquí va una sola porque las
 * otras ocho son ocho opciones más para algo que casi nadie cambia.
 *
 * [medidaDelTexto] es lo que ocupa ya compuesto: llega de fuera porque medir
 * letras necesita Android y esto se comprueba sin dispositivo.
 */
fun sitioDelTextoDentro(contenedor: Element, medidaDelTexto: Pair<Double, Double>): Pt {
    val hueco = esquinaDelHueco(contenedor)
    val (ancho, alto) = medidaDelTexto
    return Pt(
        hueco.x + (anchoQueCabe(contenedor) / 2 - ancho / 2),
        hueco.y + (altoQueCabe(contenedor) / 2 - alto / 2)
    )
}

/**
 * Parte el texto en líneas que quepan en [ancho].
 *
 * Se corta **por palabras**, y solo se parte una palabra cuando ella sola no
 * cabe: un nombre largo en una caja estrecha tiene que verse aunque quede
 * partido, y desbordar sería peor. Los saltos de línea que haya escrito el
 * usuario se respetan tal cual — son suyos.
 *
 * [mide] devuelve lo que ocupa un trozo de texto. Va como parámetro por lo
 * mismo que arriba: medir letras es de Android y esto no lo es.
 */
fun repartirEnLineas(texto: String, ancho: Double, mide: (String) -> Double): List<String> {
    if (texto.isEmpty()) return listOf("")
    if (ancho <= 0) return texto.split('\n')

    val salida = mutableListOf<String>()
    for (parrafo in texto.split('\n')) {
        if (parrafo.isEmpty()) {
            salida.add("")
            continue
        }
        var linea = StringBuilder()
        for (palabra in parrafo.split(' ')) {
            val probar = if (linea.isEmpty()) palabra else "$linea $palabra"
            if (mide(probar) <= ancho || linea.isEmpty()) {
                // Cabe, o es la primera palabra de la línea y no queda otra que
                // ponerla aunque se pase: partirla ya se hace más abajo.
                linea = StringBuilder(probar)
            } else {
                salida.add(linea.toString())
                linea = StringBuilder(palabra)
            }
        }
        // La palabra suelta que no cabe se parte por donde toque.
        for (trozo in partirSiNoCabe(linea.toString(), ancho, mide)) salida.add(trozo)
    }
    return if (salida.isEmpty()) listOf("") else salida
}

/** Parte una palabra que no cabe entera, letra a letra. */
private fun partirSiNoCabe(linea: String, ancho: Double, mide: (String) -> Double): List<String> {
    if (linea.isEmpty() || mide(linea) <= ancho) return listOf(linea)
    val trozos = mutableListOf<String>()
    var actual = StringBuilder()
    for (c in linea) {
        val probar = actual.toString() + c
        if (mide(probar) > ancho && actual.isNotEmpty()) {
            trozos.add(actual.toString())
            actual = StringBuilder(c.toString())
        } else {
            actual = StringBuilder(probar)
        }
    }
    if (actual.isNotEmpty()) trozos.add(actual.toString())
    return trozos
}

// -------------------------------------------------------------------------
// El vínculo entre la figura y su texto
// -------------------------------------------------------------------------

/** El texto que vive dentro de [contenedor], si lo hay. */
fun textoDe(contenedor: Element, elementos: List<Element>): Element? =
    elementos.firstOrNull {
        it.type == ElementType.TEXT && !it.isDeleted && it.containerId == contenedor.id
    }

/** Y al revés: la figura en la que vive [texto]. */
fun contenedorDe(texto: Element, elementos: List<Element>): Element? {
    val id = texto.containerId ?: return null
    return elementos.firstOrNull { it.id == id && !it.isDeleted }
}

/**
 * Ata un texto a una figura, por los dos lados.
 *
 * **Los dos lados hacen falta.** El texto guarda de quién es para saber dónde
 * colocarse; la figura guarda qué lleva dentro para poder arrastrarlo, borrarlo
 * con ella y crecer cuando haga falta. Con un solo lado, mover la figura dejaría
 * el texto atrás o borrarla dejaría un texto huérfano flotando.
 */
fun atados(contenedor: Element, texto: Element): Pair<Element, Element> {
    val yaEstaba = contenedor.boundElements.orEmpty()
    val nuevos =
        if (yaEstaba.any { it.id == texto.id }) yaEstaba
        else yaEstaba + BoundElement(id = texto.id, type = ElementType.TEXT)
    return contenedor.copy(boundElements = nuevos) to texto.copy(containerId = contenedor.id)
}
