package com.forge.pixpin.motor

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * La escala gráfica: **la reglita a cuadros blancos y negros de los planos**.
 *
 * Un número —«escala 1:50»— solo vale mientras nadie toque el papel. En cuanto
 * el plano se fotocopia al 80%, se manda por WhatsApp o se recorta, el número
 * miente y nadie se entera. La barra a cuadros no puede mentir: se encoge y se
 * estira **con el dibujo**, así que quien reciba la imagen puede medir sobre
 * ella con una regla, o con dos dedos en la pantalla, y acertar.
 *
 * Por eso esto es un elemento del dibujo y no un adorno del editor: sale en la
 * exportación, se guarda con la escena y se puede mover y estirar como cualquier
 * otra cosa.
 *
 * ## Qué se calcula aquí
 *
 * Dado lo ancha que es la barra y cuánto vale un píxel, hay que elegir **cuánto
 * mide cada cuadro**. No vale repartir el ancho a partes iguales: saldrían
 * cuadros de «3,7 m» y una barra que hay que leer con calculadora. Se elige un
 * paso redondo —1, 2 o 5 por una potencia de diez— y se ponen los cuadros que
 * quepan. Es lo que hace un plano de verdad, y es geometría pura, así que se
 * comprueba sin pintar nada.
 */

/** Cómo queda repartida una barra de escala. */
data class BarraDeEscala(
    /** Cuánto mide cada cuadro en unidades de mundo. */
    val porTramo: Double,
    /** Cuántos cuadros entran. */
    val tramos: Int,
    /** Lo que ocupa cada cuadro en píxeles de escena. */
    val anchoDeTramo: Double,
    val unidad: String
) {
    /** Lo que mide la barra entera, en unidades de mundo. */
    val total: Double get() = porTramo * tramos

    /** Y lo que ocupa en la escena. Puede ser menos que la caja: sobra a la derecha. */
    val ancho: Double get() = anchoDeTramo * tramos

    /** Lo que va escrito bajo la marca [i], contando desde la izquierda. */
    fun etiqueta(i: Int): String = formatearMedida(porTramo * i)
}

/**
 * Cómo repartir una barra de [anchoPx] de ancho.
 *
 * Devuelve null si no hay nada que repartir. **Sin escala también se reparte**,
 * en píxeles: una barra que diga «200 px» sigue sirviendo para comparar dos
 * distancias de la misma imagen, y esconderla hasta calibrar dejaría la
 * herramienta muda justo cuando alguien la está descubriendo.
 */
fun barraDeEscala(
    anchoPx: Double,
    escala: Escala?,
    tramosDeseados: Int = TRAMOS_DESEADOS
): BarraDeEscala? {
    if (!anchoPx.isFinite() || anchoPx <= 0.0) return null
    val porPixel = if (escala != null && escala.valida) escala.unidadesPorPixel else 1.0
    val unidad = if (escala != null && escala.valida) escala.unidad else "px"

    val totalReal = anchoPx * porPixel
    if (!totalReal.isFinite() || totalReal <= 0.0) return null

    val paso = pasoRedondo(totalReal / tramosDeseados.coerceAtLeast(1))
    if (paso <= 0.0) return null

    // Los que quepan **enteros**: media casilla al final no se puede leer, y
    // dejar el último cuadro a medias es la forma más fácil de que alguien mida
    // de más sin darse cuenta.
    val tramos = floor(totalReal / paso).toInt().coerceIn(1, MAX_TRAMOS)
    return BarraDeEscala(
        porTramo = paso,
        tramos = tramos,
        anchoDeTramo = paso / porPixel,
        unidad = unidad
    )
}

/**
 * El número redondo más cercano por debajo de [valor]: 1, 2 o 5 por una potencia
 * de diez.
 *
 * Son los tres que la cabeza divide sin pensar. Con 2,5 o con 3 hay que hacer
 * cuentas para leer la barra, que es exactamente lo que la barra viene a evitar.
 */
internal fun pasoRedondo(valor: Double): Double {
    if (!valor.isFinite() || valor <= 0.0) return 0.0
    val magnitud = 10.0.pow(floor(log10(valor)))
    val normalizado = valor / magnitud
    val bonito = when {
        normalizado >= 5.0 -> 5.0
        normalizado >= 2.0 -> 2.0
        else -> 1.0
    }
    return bonito * magnitud
}

/**
 * Un número escrito para una etiqueta: sin decimales si no hacen falta.
 *
 * En una barra caben cuatro caracteres por marca antes de que las cifras se
 * pisen entre sí, así que «10» y no «10,00».
 */
internal fun formatearMedida(valor: Double): String {
    if (!valor.isFinite()) return ""
    val redondeado = Math.round(valor * 100.0) / 100.0
    return if (redondeado == floor(redondeado)) redondeado.toLong().toString()
    else redondeado.toString().trimEnd('0').trimEnd('.')
}

/**
 * Cuántos cuadros se buscan de partida.
 *
 * Cuatro es lo que se ve en la mayoría de los planos: suficientes para leer una
 * medida intermedia a ojo y pocos para que las cifras no se amontonen.
 */
const val TRAMOS_DESEADOS = 4

/** Tope duro, por si alguien estira la barra a lo ancho de la escena. */
private const val MAX_TRAMOS = 12

/** Lo alto que se dibuja la barra respecto de su caja. El resto es para las cifras. */
const val ALTO_DE_LA_BARRA = 0.45
