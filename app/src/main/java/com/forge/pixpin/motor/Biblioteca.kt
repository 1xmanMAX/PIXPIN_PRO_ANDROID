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
 * **Son las del cuaderno de matemáticas**, y esa es la elección: el plano de
 * funciones, la recta numérica, el espacio de tres ejes y una tabla en blanco.
 * Es el andamiaje que aparece en el noventa por ciento de los problemas, el
 * que no dice nada por sí mismo y hay que dibujar igualmente antes de poder
 * empezar.
 *
 * Los tres primeros son **instrumentos**, no dibujos (ver [Plano] y
 * [Espacio]): saben cuánto vale una unidad y al alargarlos por un lado
 * enseñan más números en vez de estirar las puntas y las cifras. Hubo antes
 * unos «Ejes», una «Cuadrícula» y una «Recta numérica» dibujados con flechas y
 * letras sueltas, y un triángulo rectángulo y una circunferencia con su radio;
 * el usuario los quitó (2-sep-2026): los primeros se deformaban al alargarlos
 * y los instrumentos hacen lo mismo bien, y los otros dos se dibujan igual de
 * rápido con las herramientas de siempre.
 *
 * Van **a tiralíneas** ([Element.ROUGHNESS_ARCHITECT]): un eje tembloroso no se
 * lee como un eje. El color y el grosor sí salen del pincel que haya puesto,
 * para que la figura pegue con el resto del dibujo.
 */
fun figurasDeFabrica(estilo: ItemStyle, medir: MedidaDeTexto): List<FiguraGuardada> {
    return listOf(
        figura(ID_PLANO, "Plano de funciones", listOf(planoDeFunciones(estilo))),
        figura(ID_RECTA, "Recta de funciones", listOf(rectaDeFunciones(estilo))),
        figura(ID_ESPACIO, "Espacio xyz", listOf(espacioDeFunciones(estilo))),
        figura(ID_TABLA, "Tabla en blanco", tablaEnBlanco(estilo, medir))
    ).filter { it.elementos.isNotEmpty() }
}

const val ID_RECTA = "fabrica-recta"
const val ID_ESPACIO = "fabrica-espacio"
const val ID_TABLA = "fabrica-tabla"
const val ID_PLANO = "fabrica-plano"

private fun figura(id: String, nombre: String, elementos: List<Element>) =
    FiguraGuardada(id, nombre, normalizarFigura(elementos), propia = false)

/** La circunferencia con su radio marcado. */

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

/**
 * La recta numérica: **el plano en una dimensión**, y por eso instrumento.
 *
 * Nace con la misma unidad y los mismos pasos que el plano. [ancho] es lo que
 * enseña; [alto] solo da sitio a las marcas y a las cifras de debajo. Al
 * alargarla por un lado salen más números, y las puntas y las cifras se
 * quedan como estaban: es lo que no hacía la recta dibujada. Ver [Plano].
 */
fun rectaDeFunciones(
    estilo: ItemStyle,
    ancho: Double = UNIDAD_POR_DEFECTO * 8,
    alto: Double = ALTO_DE_LA_RECTA
): Element {
    return newElement(ElementType.RECTA, 0.0, 0.0, estilo, width = ancho, height = alto)
        .copy(
            roughness = Element.ROUGHNESS_ARCHITECT,
            unidad = UNIDAD_POR_DEFECTO,
            pasoDeNumeros = PASO_POR_DEFECTO,
            pasoDeCuadros = PASO_POR_DEFECTO
        )
}

/**
 * El espacio de tres ejes, en la vista del cuaderno y con su unidad puesta.
 * Se gira con el tirador de giro y se estira como el plano. Ver [Espacio].
 */
fun espacioDeFunciones(estilo: ItemStyle, lado: Double = UNIDAD_POR_DEFECTO * 8): Element {
    return newElement(ElementType.ESPACIO, 0.0, 0.0, estilo, width = lado, height = lado)
        .copy(
            roughness = Element.ROUGHNESS_ARCHITECT,
            unidad = UNIDAD_POR_DEFECTO,
            pasoDeNumeros = PASO_POR_DEFECTO,
            pasoDeCuadros = PASO_POR_DEFECTO,
            azimut = 0.0,
            elevacion = ELEVACION_POR_DEFECTO
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

/** Lo que mide de alto la recta de funciones: sitio para las marcas y las cifras. */
const val ALTO_DE_LA_RECTA = 56.0
