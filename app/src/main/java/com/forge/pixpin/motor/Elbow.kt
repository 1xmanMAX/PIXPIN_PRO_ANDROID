package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Flechas de codos: el trazado en ángulos de 90°.
 *
 * Es la otra forma que tiene que tener una flecha, y sirve para algo distinto
 * que la curva. Una flecha curva **va** de un sitio a otro: dice «esto lleva a
 * esto». Una de codos **estructura**: en un mapa mental o un organigrama las
 * conexiones ortogonales dejan ver la jerarquía de un vistazo, porque todas
 * comparten los mismos ejes y la vista las agrupa sola.
 *
 * El trazado es el conector ortogonal clásico: se sale por el eje dominante, se
 * cruza a mitad de camino y se entra por el mismo eje. Con eso se resuelven los
 * casos que aparecen de verdad al ordenar cajas —al lado, encima, en diagonal—
 * sin meter un algoritmo de búsqueda de caminos que aquí no se ganaría nada.
 *
 * Sin Android: se comprueba sin dispositivo, como el resto del motor.
 */

/** Radio con el que se redondean los codos (`generateElbowArrowShape`). */
const val ELBOW_RADIUS = 16.0

/**
 * El camino en ángulos rectos de [desde] a [hasta].
 *
 * Devuelve los vértices, **sin redondear**: el redondeo es cosa de quien
 * dibuja, y separarlo permite comprobar el trazado por sus esquinas exactas.
 *
 * Manda el eje en el que más distancia hay que salvar. Yendo sobre todo a lo
 * ancho se sale en horizontal, se cruza por el medio y se entra en horizontal;
 * yendo a lo alto, al revés. Es lo que hace que dos cajas una al lado de la
 * otra se unan con una raya recta y no con una escalera.
 */
fun elbowPoints(desde: Pt, hasta: Pt): List<Pt> {
    val dx = hasta.x - desde.x
    val dy = hasta.y - desde.y

    // Prácticamente alineadas: una recta es el mejor codo posible.
    if (abs(dy) < ALINEADO) return listOf(desde, Pt(hasta.x, desde.y))
    if (abs(dx) < ALINEADO) return listOf(desde, Pt(desde.x, hasta.y))

    return if (abs(dx) >= abs(dy)) {
        val medio = desde.x + dx / 2
        listOf(desde, Pt(medio, desde.y), Pt(medio, hasta.y), hasta)
    } else {
        val medio = desde.y + dy / 2
        listOf(desde, Pt(desde.x, medio), Pt(hasta.x, medio), hasta)
    }
}

/**
 * Debajo de esto los dos puntos se dan por alineados.
 *
 * Sin este margen, dos cajas casi a la misma altura se unían con una escalera
 * de tres tramos de un píxel — visualmente un borrón, y justo lo contrario de
 * lo que se busca al ordenar un esquema.
 */
private const val ALINEADO = 8.0

/**
 * Los vértices con los codos redondeados, muestreados a puntos.
 *
 * Un codo en pico se ve duro y no es lo que hace el original: recorta la
 * esquina y la cose con un cuarto de vuelta. El radio se acota a la mitad del
 * tramo más corto que llega a esa esquina, porque en un codo entre dos tramos
 * de 10 px un radio de 16 se comería los dos y el camino se cruzaría consigo
 * mismo.
 */
fun elbowRounded(vertices: List<Pt>, radio: Double = ELBOW_RADIUS): List<Pt> {
    if (vertices.size < 3) return vertices

    val out = mutableListOf(vertices.first())
    for (i in 1 until vertices.size - 1) {
        val previo = vertices[i - 1]
        val v = vertices[i]
        val siguiente = vertices[i + 1]

        val r = min(
            radio,
            min(hypot(v.x - previo.x, v.y - previo.y), hypot(siguiente.x - v.x, siguiente.y - v.y)) / 2
        )
        if (r <= 0.01) {
            out += v
            continue
        }

        val entrada = avanzar(v, previo, r)
        val salida = avanzar(v, siguiente, r)
        out += entrada
        // Cuarto de vuelta con el vértice de tirador: es la misma cuadrática
        // que redondea las puntas del rombo.
        for (s in 1..PASOS_CODO) {
            val t = s.toDouble() / PASOS_CODO
            val u = 1 - t
            out += Pt(
                u * u * entrada.x + 2 * u * t * v.x + t * t * salida.x,
                u * u * entrada.y + 2 * u * t * v.y + t * t * salida.y
            )
        }
    }
    out += vertices.last()
    return out
}

/** Cuántos puntos por codo. Seis basta: es un cuarto de vuelta corto. */
private const val PASOS_CODO = 6

private fun avanzar(desde: Pt, hacia: Pt, distancia: Double): Pt {
    val dx = hacia.x - desde.x
    val dy = hacia.y - desde.y
    val len = hypot(dx, dy)
    if (len <= 0.0) return desde
    val t = (distancia / len).coerceAtMost(1.0)
    return Pt(desde.x + dx * t, desde.y + dy * t)
}

/**
 * Los puntos que hay que dibujar para [e], según su forma.
 *
 * Es el único sitio donde se decide, así que el renderizador, el picado y la
 * exportación no pueden discrepar sobre por dónde va una flecha.
 */
fun puntosDeTrazado(e: Element): List<Pt> {
    val pts = absolutePoints(e)
    if (!e.elbowed || pts.size < 2) return pts
    return elbowRounded(elbowPoints(pts.first(), pts.last()))
}
