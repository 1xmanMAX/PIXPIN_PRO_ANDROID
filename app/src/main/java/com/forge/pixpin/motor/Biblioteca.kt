package com.forge.pixpin.motor

import kotlinx.serialization.Serializable

/**
 * La lista de figuras: **lo que no hay que volver a dibujar**.
 *
 * Hay cosas que se dibujan siempre igual y siempre desde cero. Unos ejes de
 * coordenadas para plantear un problema son dos flechas, dos letras y un cero,
 * y cuestan medio minuto cada vez; una tabla de tres columnas, veinte rayas. Al
 * cabo de una tarde, la mitad del tiempo se ha ido en repetir andamiaje.
 *
 * Así que se guardan hechas y se estampan de un toque. Y **lo que uno dibuja
 * también se puede guardar**, que es lo que hace que la lista sirva de verdad:
 * las figuras de fábrica son las que yo creo que hacen falta, y las que hacen
 * falta de verdad las sabe quien dibuja.
 *
 * ## Una figura es una lista de elementos, y ya
 *
 * No hay tipo nuevo ni formato propio: una figura guardada son los mismos
 * elementos del dibujo, normalizados a que su esquina caiga en el (0, 0). Eso
 * hace que estampar sea copiar y trasladar, que guardar sea quedarse con la
 * selección, y que una figura pueda contener **cualquier cosa** que el motor
 * sepa dibujar el día de mañana sin tocar nada de aquí.
 *
 * Sin Android: medir letras llega por [MedidaDeTexto], como en [TablaDibujada].
 */

/** Una figura de la lista, lista para estampar. */
@Serializable
data class FiguraGuardada(
    val id: String,
    val nombre: String,
    /** Sus elementos, con la esquina de su caja común en el (0, 0). */
    val elementos: List<Element> = emptyList(),
    /**
     * La ha guardado quien dibuja, no viene de fábrica.
     *
     * Solo las propias se pueden borrar: quitar una de fábrica dejaría un hueco
     * que no habría forma de recuperar sin reinstalar.
     */
    val propia: Boolean = false
) {
    /** Lo que ocupa, para pintar su miniatura a escala. */
    val caja: Bounds get() = getCommonBounds(elementos)
}

/**
 * Lleva la figura al origen.
 *
 * Se guarda normalizada y no donde estaba: una figura recordada con las
 * coordenadas del dibujo del que salió llevaría dentro un sitio que ya no
 * significa nada, y estamparla obligaría a restar ese sitio en cada uso.
 */
fun normalizarFigura(elementos: List<Element>): List<Element> {
    if (elementos.isEmpty()) return elementos
    val caja = getCommonBounds(elementos)
    return elementos.map { it.copy(x = it.x - caja.x1, y = it.y - caja.y1) }
}

/**
 * La figura puesta en el dibujo, **centrada en [centro]**.
 *
 * Centrada y no por su esquina porque quien la coloca está mirando un sitio, no
 * una esquina: se toca la figura de la lista y aparece donde se está mirando.
 *
 * Sale con ids y semillas nuevos —si no, estampar dos veces la misma daría dos
 * elementos con el mismo id y el segundo pisaría al primero— y **en un grupo
 * propio**, para que se coja de una vez y se pueda separar después si hace
 * falta. Ver [duplicateElements], que ya sabe hacer copias limpias.
 */
fun estampar(figura: FiguraGuardada, centro: Pt): List<Element> {
    if (figura.elementos.isEmpty()) return emptyList()
    val copias = agrupados(duplicateElements(figura.elementos, offset = 0.0))
    val caja = getCommonBounds(copias)
    return dragElements(copias, centro.x - caja.midX, centro.y - caja.midY)
}

/**
 * Una figura nueva a partir de lo que hay seleccionado.
 *
 * Se normaliza al guardarla, así que da igual en qué parte del lienzo estuviera
 * dibujada. Devuelve null si no hay nada que guardar.
 */
fun figuraDeLaSeleccion(nombre: String, seleccion: List<Element>): FiguraGuardada? {
    val utiles = seleccion.filter { !it.isDeleted }
    if (utiles.isEmpty()) return null
    return FiguraGuardada(
        id = randomId(),
        nombre = nombre.trim().ifBlank { NOMBRE_POR_DEFECTO },
        elementos = normalizarFigura(utiles),
        propia = true
    )
}

/** Como se llama una figura a la que no se le puso nombre. */
const val NOMBRE_POR_DEFECTO = "Figura"

// -------------------------------------------------------------------------
// Las de fábrica
// -------------------------------------------------------------------------

/**
 * Las figuras que vienen puestas.
 *
 * **Son las del cuaderno de matemáticas**, y esa es la elección: unos ejes, un
 * plano cuadriculado, una recta numérica, el triángulo con su ángulo recto
 * marcado, la circunferencia con su radio y una tabla en blanco. Es el andamiaje
 * que aparece en el noventa por ciento de los problemas, el que no dice nada por
 * sí mismo y hay que dibujar igualmente antes de poder empezar.
 *
 * Van **a tiralíneas** ([Element.ROUGHNESS_ARCHITECT]): un eje tembloroso no se
 * lee como un eje. El color y el grosor sí salen del pincel que haya puesto,
 * para que la figura pegue con el resto del dibujo.
 */
fun figurasDeFabrica(estilo: ItemStyle, medir: MedidaDeTexto): List<FiguraGuardada> {
    val trazo = estilo.copy(
        roughness = Element.ROUGHNESS_ARCHITECT,
        roundness = null,
        backgroundColor = Element.TRANSPARENT,
        startArrowhead = null,
        endArrowhead = Arrowhead.ARROW
    )
    return listOf(
        figura(ID_EJES, "Ejes", ejes(trazo, medir)),
        figura(ID_CUADRICULA, "Cuadrícula", cuadricula(trazo, medir)),
        figura(ID_RECTA, "Recta numérica", rectaNumerica(trazo, medir)),
        figura(ID_TRIANGULO, "Triángulo rectángulo", trianguloRectangulo(trazo)),
        figura(ID_CIRCUNFERENCIA, "Circunferencia y radio", circunferencia(trazo, medir)),
        figura(ID_TABLA, "Tabla en blanco", tablaEnBlanco(estilo, medir)),
        figura(ID_PLANO, "Plano de funciones", listOf(planoDeFunciones(estilo)))
    ).filter { it.elementos.isNotEmpty() }
}

const val ID_EJES = "fabrica-ejes"
const val ID_CUADRICULA = "fabrica-cuadricula"
const val ID_RECTA = "fabrica-recta"
const val ID_TRIANGULO = "fabrica-triangulo"
const val ID_CIRCUNFERENCIA = "fabrica-circunferencia"
const val ID_TABLA = "fabrica-tabla"
const val ID_PLANO = "fabrica-plano"

private fun figura(id: String, nombre: String, elementos: List<Element>) =
    FiguraGuardada(id, nombre, normalizarFigura(elementos), propia = false)

/**
 * Los ejes, con sus letras.
 *
 * La Y va **hacia arriba**, que en coordenadas de escena es hacia el menos: es
 * lo único que se puede hacer aquí y hay que tenerlo presente, porque escrito
 * parece del revés.
 */
private fun ejes(estilo: ItemStyle, medir: MedidaDeTexto): List<Element> = listOf(
    flecha(Pt(-BRAZO, 0.0), Pt(BRAZO, 0.0), estilo),
    flecha(Pt(0.0, BRAZO), Pt(0.0, -BRAZO), estilo),
    rotulo("X", Pt(BRAZO + 10, 0.0), estilo, medir),
    rotulo("Y", Pt(14.0, -BRAZO - 10), estilo, medir),
    rotulo("O", Pt(-14.0, 14.0), estilo, medir)
)

/** El plano cuadriculado: la rejilla en gris claro y los ejes encima. */
private fun cuadricula(estilo: ItemStyle, medir: MedidaDeTexto): List<Element> {
    val fino = estilo.copy(strokeColor = GRIS_DE_REJILLA, strokeWidth = 1.0)
    val pasos = (-CELDAS..CELDAS).map { it * CELDA }
    val rejilla = pasos.flatMap { p ->
        listOf(
            raya(Pt(-BRAZO, p), Pt(BRAZO, p), fino),
            raya(Pt(p, -BRAZO), Pt(p, BRAZO), fino)
        )
    }
    // La rejilla primero: el orden de la lista es el orden de pintado y los ejes
    // tienen que verse por encima de ella.
    return rejilla + ejes(estilo, medir)
}

/** La recta numérica, con sus marcas y sus números. */
private fun rectaNumerica(estilo: ItemStyle, medir: MedidaDeTexto): List<Element> {
    val doble = estilo.copy(startArrowhead = Arrowhead.ARROW, endArrowhead = Arrowhead.ARROW)
    val marcas = (-2..2).flatMap { n ->
        val x = n * PASO_DE_RECTA
        listOf(
            raya(Pt(x, -6.0), Pt(x, 6.0), estilo),
            rotulo(n.toString(), Pt(x, 22.0), estilo, medir, centrado = true)
        )
    }
    return listOf(flecha(Pt(-BRAZO, 0.0), Pt(BRAZO, 0.0), doble)) + marcas
}

/**
 * El triángulo rectángulo, **con el cuadradito del ángulo recto**.
 *
 * Sin esa marca es un triángulo cualquiera, y el enunciado deja de estar
 * dibujado: hay que decir aparte cuál es el ángulo recto.
 */
private fun trianguloRectangulo(estilo: ItemStyle): List<Element> {
    val a = Pt(0.0, 0.0)
    val b = Pt(160.0, 0.0)
    val c = Pt(0.0, -120.0)
    return listOf(
        camino(listOf(a, b, c, a), estilo),
        camino(listOf(Pt(16.0, 0.0), Pt(16.0, -16.0), Pt(0.0, -16.0)), estilo)
    )
}

/** La circunferencia con su radio marcado. */
private fun circunferencia(estilo: ItemStyle, medir: MedidaDeTexto): List<Element> {
    val r = 80.0
    return listOf(
        newElement(ElementType.ELLIPSE, -r, -r, estilo, width = r * 2, height = r * 2),
        raya(Pt(0.0, 0.0), Pt(r, 0.0), estilo),
        rotulo("r", Pt(r / 2, -22.0), estilo, medir, centrado = true)
    )
}

/**
 * El plano cartesiano: **un instrumento, no un dibujo**.
 *
 * A diferencia de los ejes de más arriba —que son dos flechas y tres letras—
 * este sabe cuánto vale una unidad, así que se le puede preguntar dónde cae el
 * (3, −2) y se comporta distinto según de dónde se le estire. Ver [Plano].
 *
 * **Es la única forma de fabricar un plano y por eso es pública.** Nace con
 * `unidad` puesta, que es de donde sale todo lo demás; un plano creado a mano
 * con `newElement` y sin ese campo se dibujaría con la unidad por defecto pero
 * no la llevaría dentro, y al estirarlo por una esquina —que es lo que la
 * multiplica— se quedaría como estaba. Quien necesite uno, que llame aquí.
 *
 * [lado] es lo que mide de ancho y de alto. La unidad **no** cambia con él: un
 * plano más grande enseña más números, que es justo lo que se espera al pedir
 * más sitio. Ver [Element.unidad].
 */
fun planoDeFunciones(estilo: ItemStyle, lado: Double = UNIDAD_POR_DEFECTO * 8): Element {
    return newElement(ElementType.PLANO, 0.0, 0.0, estilo, width = lado, height = lado)
        .copy(
            roughness = Element.ROUGHNESS_ARCHITECT,
            unidad = UNIDAD_POR_DEFECTO,
            pasoDeNumeros = PASO_POR_DEFECTO,
            pasoDeCuadros = PASO_POR_DEFECTO
        )
}

/** Una tabla vacía, para rellenarla escribiendo encima. Ver [elementosDeTabla]. */
private fun tablaEnBlanco(estilo: ItemStyle, medir: MedidaDeTexto): List<Element> =
    elementosDeTabla(
        filas = List(4) { List(3) { "" } },
        estilo = estilo,
        origen = Pt(0.0, 0.0),
        medir = medir,
        conCabecera = true
    )

// ---- Piezas ----

private fun flecha(desde: Pt, hasta: Pt, estilo: ItemStyle): Element =
    newElement(ElementType.ARROW, desde.x, desde.y, estilo).copy(
        points = listOf(Pt(0.0, 0.0), Pt(hasta.x - desde.x, hasta.y - desde.y)),
        width = kotlin.math.abs(hasta.x - desde.x),
        height = kotlin.math.abs(hasta.y - desde.y)
    )

private fun raya(desde: Pt, hasta: Pt, estilo: ItemStyle): Element =
    camino(listOf(desde, hasta), estilo)

private fun camino(puntos: List<Pt>, estilo: ItemStyle): Element {
    val origen = puntos.first()
    val relativos = puntos.map { Pt(it.x - origen.x, it.y - origen.y) }
    val caja = boundsOfPoints(relativos)
    return newElement(ElementType.LINE, origen.x, origen.y, estilo).copy(
        points = relativos, width = caja.width, height = caja.height
    )
}

/**
 * Una letra suelta, colocada por su centro o por su esquina.
 *
 * Por el centro cuando hay que dejarla debajo de una marca —los números de la
 * recta— y por la esquina cuando lo que importa es de qué lado del eje cae.
 */
private fun rotulo(
    texto: String,
    donde: Pt,
    estilo: ItemStyle,
    medir: MedidaDeTexto,
    centrado: Boolean = false
): Element {
    val (ancho, alto) = medir(texto, estilo.fontSize)
    val x = if (centrado) donde.x - ancho / 2 else donde.x
    val y = if (centrado) donde.y - alto / 2 else donde.y - alto / 2
    return newElement(ElementType.TEXT, x, y, estilo, width = ancho, height = alto)
        .copy(text = texto)
}

/** Lo que miden los ejes de largo, en px de escena. */
private const val BRAZO = 150.0

/** El lado de un cuadro de la rejilla, y cuántos caben a cada lado del cero. */
private const val CELDA = 25.0
private const val CELDAS = 5

/** Lo que separa dos números de la recta numérica. */
private const val PASO_DE_RECTA = 55.0

/** El gris de la rejilla: se tiene que ver sin competir con lo que se dibuje. */
private const val GRIS_DE_REJILLA = "#ced4da"
