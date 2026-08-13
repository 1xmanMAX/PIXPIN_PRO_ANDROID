package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Las cuentas de los controles que se manejan arrastrando.
 *
 * ## Por qué esto está aparte de la interfaz
 *
 * Un deslizador es dos cosas: unos píxeles que se pintan y **una regla de tres**
 * que convierte dónde está el dedo en qué valor sale. La segunda es donde se
 * cuelan los fallos —el que se pasa de rango, el que salta de dos en dos, el que
 * al soltar en el borde elige la opción de al lado— y es la que no se puede
 * comprobar sin un móvil si vive dentro de un `Composable`.
 *
 * Así que vive aquí, en funciones que no saben de Compose ni de Android, y la
 * interfaz solo las llama. Ver [PanelLateralDeEstilo].
 */

/**
 * Qué opción cae bajo un arrastre de [dx] píxeles.
 *
 * [paso] es lo que hay que arrastrar para pasar de una opción a la siguiente.
 * [haciaLaIzquierda] invierte el sentido: el panel puede estar a la derecha de
 * la pantalla, y entonces las opciones salen hacia la izquierda y arrastrar
 * hacia allí es avanzar, no retroceder.
 *
 * El resultado nunca se sale de la lista: pasarse arrastrando deja en la última,
 * que es lo que espera la mano —se empuja hasta el final y se suelta— y no que
 * la selección dé la vuelta.
 */
fun opcionArrastrada(dx: Float, paso: Float, cuantas: Int, haciaLaIzquierda: Boolean): Int {
    if (cuantas <= 0) return -1
    if (paso <= 0f) return 0
    val avance = if (haciaLaIzquierda) -dx else dx
    // Media casilla de margen: el centro de cada opción es su punto, así que se
    // redondea. Sin esto habría que pasar la opción entera para llegar a ella.
    val i = (avance / paso).roundToInt()
    return i.coerceIn(0, cuantas - 1)
}

/**
 * Si un arrastre de [dx] píxeles cuenta ya como abrir el desplegable.
 *
 * Hace falta un mínimo porque **un toque también arrastra**: el dedo nunca se
 * levanta exactamente donde cayó, y sin margen cualquier toque abriría el
 * desplegable y elegiría algo. Ver `viewConfiguration.touchSlop`, que es de
 * donde sale el número que se pasa aquí.
 */
fun abreElDesplegable(dx: Float, minimo: Float): Boolean = abs(dx) >= minimo

/**
 * De dónde está el dedo a qué fracción del recorrido, con **arriba = 1**.
 *
 * Se invierte a propósito: en un deslizador vertical, arriba es más —más grosor,
 * más opacidad— porque es como se lee un termómetro y como se entiende el
 * volumen. En coordenadas de pantalla arriba es cero, así que la vuelta se da
 * aquí y no en cada control.
 */
fun fraccionVertical(y: Float, alto: Float): Float {
    if (alto <= 0f) return 0f
    return (1f - y / alto).coerceIn(0f, 1f)
}

/**
 * La casilla de [cuantas] que le toca a una fracción del recorrido.
 *
 * Reparto en partes iguales y con el redondeo en el centro de cada casilla: con
 * cuatro grosores, el primer cuarto de arriba es el cuarto grosor entero, no
 * solo su borde.
 */
fun casillaDe(fraccion: Float, cuantas: Int): Int {
    if (cuantas <= 0) return -1
    if (cuantas == 1) return 0
    return (fraccion.coerceIn(0f, 1f) * (cuantas - 1)).roundToInt().coerceIn(0, cuantas - 1)
}

/** Dónde queda el mango de un deslizador de casillas, en fracción del alto. */
fun fraccionDeLaCasilla(indice: Int, cuantas: Int): Float {
    if (cuantas <= 1) return 1f
    return (indice.coerceIn(0, cuantas - 1)).toFloat() / (cuantas - 1)
}

/**
 * Un valor continuo redondeado a su paso.
 *
 * La opacidad va de 0 a 100 y no tiene sentido dejarla en 63: se salta de cinco
 * en cinco para que el número sea redondo y para que el mismo sitio del dedo dé
 * siempre el mismo valor.
 */
fun valorConPaso(fraccion: Float, minimo: Int, maximo: Int, paso: Int): Int {
    if (maximo <= minimo || paso <= 0) return minimo
    val crudo = minimo + fraccion.coerceIn(0f, 1f) * (maximo - minimo)
    val redondo = (crudo / paso).roundToInt() * paso
    return redondo.coerceIn(minimo, maximo)
}

/** Y la vuelta: en qué fracción del recorrido queda un valor. */
fun fraccionDelValor(valor: Int, minimo: Int, maximo: Int): Float {
    if (maximo <= minimo) return 0f
    return ((valor - minimo).toFloat() / (maximo - minimo)).coerceIn(0f, 1f)
}

/**
 * Cuál de los valores de una lista es el que hay puesto.
 *
 * Se busca **el más cercano** y no el igual: un dibujo abierto de fuera puede
 * traer un grosor de 3,5 que no está en la lista, y el deslizador tiene que
 * colocarse en algún sitio en vez de saltar al primero.
 */
fun masCercano(valor: Double, valores: List<Double>): Int {
    if (valores.isEmpty()) return -1
    var mejor = 0
    var distancia = Double.MAX_VALUE
    valores.forEachIndexed { i, v ->
        val d = abs(v - valor)
        if (d < distancia) {
            distancia = d
            mejor = i
        }
    }
    return mejor
}
