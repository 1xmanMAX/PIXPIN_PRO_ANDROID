package com.forge.pixpin.motor

import kotlin.math.max

/**
 * El cronograma: **un plan dibujado, no una hoja de cálculo**.
 *
 * Una columna de nombres a la izquierda, una escala arriba y una barra por fila, todo
 * repartido dentro de la caja de la figura: nada está en coordenadas, así que estirar la
 * figura estira el plan sin descuadrarlo.
 *
 * **Ya no se crea ni se edita** (retirado el 11-oct-2026, como en el PC). Lo que queda
 * aquí es solo **cómo se reparte para pintarlo** —en pantalla, en SVG y en PDF—, para que
 * los dibujos que ya tienen uno se sigan viendo igual. Ver [ElementType.CRONOGRAMA].
 */

/** Qué parte del ancho se lleva la columna de los nombres. */
const val ANCHO_DE_LOS_NOMBRES = 0.32

/** Lo que ocupa la fila de la escala, arriba, en tanto por uno del alto. */
const val ALTO_DE_LA_ESCALA = 0.16

/** Lo más fina que se deja una barra, en columnas: por debajo no se puede ni agarrar. */
const val MINIMA_BARRA = 0.25

/** La caja del elemento, que es donde vive todo lo demás. */
private fun caja(e: Element): Bounds = getElementAbsoluteCoords(e).toBounds()

/** Dónde empieza la zona de las barras: a la derecha de los nombres. */
fun xDeLaEscala(e: Element): Double = caja(e).let { it.x1 + it.width * ANCHO_DE_LOS_NOMBRES }

/** Dónde empieza la primera fila: debajo de la escala. */
fun yDeLasFilas(e: Element): Double = caja(e).let { it.y1 + it.height * ALTO_DE_LA_ESCALA }

/** Lo que mide una columna de la escala. */
fun anchoDeColumna(e: Element): Double {
    val b = caja(e)
    val periodos = max(1, e.periodos)
    return (b.x2 - xDeLaEscala(e)) / periodos
}

/** Lo que mide una fila. Con cero tareas, la caja entera: es el hueco donde caerá la primera. */
fun altoDeFila(e: Element): Double {
    val b = caja(e)
    val filas = max(1, e.tareas.size)
    return (b.y2 - yDeLasFilas(e)) / filas
}

/**
 * La caja de la barra de la tarea [i], en coordenadas de escena.
 *
 * La barra no ocupa la fila entera de alto: deja un respiro arriba y abajo para que dos
 * filas seguidas no se lean como un bloque. Es el mismo motivo por el que una tabla lleva
 * relleno en las celdas.
 */
fun barraDeTarea(e: Element, i: Int): Bounds? {
    val t = e.tareas.getOrNull(i) ?: return null
    val col = anchoDeColumna(e)
    val fila = altoDeFila(e)
    if (col <= 0.0 || fila <= 0.0) return null
    val x0 = xDeLaEscala(e) + t.desde * col
    val y0 = yDeLasFilas(e) + i * fila
    val respiro = fila * RESPIRO_DE_LA_BARRA
    return Bounds(
        x0,
        y0 + respiro,
        x0 + max(t.cuanto, MINIMA_BARRA) * col,
        y0 + fila - respiro
    )
}

private const val RESPIRO_DE_LA_BARRA = 0.18

/** Las rayas verticales de la escala, de la primera a la última. */
fun columnasDelCronograma(e: Element): List<Double> {
    val col = anchoDeColumna(e)
    if (col <= 0.0) return emptyList()
    return (0..max(1, e.periodos)).map { xDeLaEscala(e) + it * col }
}

/**
 * De qué tamaño va la letra de un cronograma, **sin poder reventar**.
 *
 * Aquí vivía un fallo que tiró la aplicación en un móvil de verdad. La cuenta era
 * `coerceIn(1.0, alto * 0.8)`, y `coerceIn` **no recorta cuando el máximo baja del
 * mínimo: lanza**. Con la figura pequeña o con muchas filas, una fila mide menos de un
 * píxel y medio y la excepción saltaba — y saltaba **al dibujar**, así que se llevaba la
 * aplicación desde el hilo de la pantalla, lejos de donde uno estaba tocando.
 *
 * Está aquí y no repetida en los tres sitios que la necesitan —la pantalla, el SVG y el
 * PDF— justamente por eso: una cuenta que puede reventar no puede estar copiada tres
 * veces, porque se arregla en dos.
 */
fun letraDelCronograma(e: Element, alto: Double): Double {
    val pedida = e.fontSize ?: (alto * 0.5)
    val tope = max(1.0, alto * 0.8)
    return pedida.coerceIn(1.0, tope)
}
