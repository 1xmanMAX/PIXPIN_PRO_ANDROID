package com.forge.pixpin.motor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * El arco: **la parte de una circunferencia que uno quiere**.
 *
 * Se traza como con un transportador de verdad: se pone un círculo **de
 * referencia** —el instrumento, translúcido— y después se repasa con la
 * herramienta de elipse solo el trozo que hace falta. Lo que queda dibujado es
 * ese trozo; la referencia se esconde o se borra al final, como el lápiz azul de
 * los planos de toda la vida.
 *
 * Es de las pocas cosas que a mano alzada salen mal siempre. Un arco de
 * ochenta grados a pulso queda ovalado, y con la herramienta de elipse hay que
 * conformarse con el círculo entero o borrar a mano lo que sobra.
 *
 * Toda la geometría vive aquí y no en el renderizador: dónde empieza el arco y
 * cuánto barre decide qué se ve, y eso se comprueba sin pantalla.
 */

/**
 * El ángulo **paramétrico** de un punto respecto al centro del óvalo.
 *
 * Paramétrico y no geométrico: se divide por cada semieje antes del `atan2`, de
 * modo que el ángulo es el de la circunferencia de la que sale el óvalo al
 * estirarlo. Es la única forma de que recorrer el borde de una elipse achatada
 * avance a ritmo constante bajo el dedo; con el ángulo geométrico, el trazo
 * corre en los extremos y se arrastra en los lados.
 *
 * La rotación del elemento se deshace antes, así que un óvalo girado se recorre
 * igual que uno recto.
 */
fun anguloEnElOvalo(element: Element, p: Pt): Double {
    val c = getElementAbsoluteCoords(element)
    val local = pointRotateRads(p, Pt(c.cx, c.cy), -element.angle)
    val rx = (c.x2 - c.x1) / 2
    val ry = (c.y2 - c.y1) / 2
    if (rx <= 0.0 || ry <= 0.0) return 0.0
    return atan2((local.y - c.cy) / ry, (local.x - c.cx) / rx)
}

/**
 * Suma al barrido lo que se ha movido el dedo, **sin saltos**.
 *
 * Es el problema clásico del transportador: al pasar por la izquierda del
 * círculo el ángulo salta de +π a −π, y sumando ángulos en bruto el arco se
 * daba la vuelta entera de golpe. Lo que se acumula es la diferencia más corta
 * entre el ángulo anterior y el nuevo, que nunca pasa de media vuelta por
 * fotograma — y con eso se puede dar tantas vueltas como se quiera.
 *
 * Se topa en una vuelta completa: más que eso vuelve a ser el óvalo entero.
 */
fun barridoAcumulado(barridoPrevio: Double, anguloPrevio: Double, anguloNuevo: Double): Double {
    var delta = anguloNuevo - anguloPrevio
    while (delta > PI) delta -= 2 * PI
    while (delta < -PI) delta += 2 * PI
    val total = barridoPrevio + delta
    return total.coerceIn(-2 * PI, 2 * PI)
}

/**
 * Los puntos del arco, **relativos a `x`/`y`** como cualquier elemento de
 * puntos.
 *
 * Devolverlos así no es un detalle: el renderizador los pasa por el mismo
 * generador de trazo rugoso que una línea, y por eso un arco sale con el mismo
 * pulso tembloroso que el resto del dibujo en vez de con una curva de compás
 * que cantaría al lado de un rectángulo a mano alzada.
 */
fun puntosDelArco(element: Element, pasosPorVuelta: Int = ARCO_PASOS): List<Pt> {
    val c = getElementAbsoluteCoords(element)
    val rx = (c.x2 - c.x1) / 2
    val ry = (c.y2 - c.y1) / 2
    if (rx <= 0.0 || ry <= 0.0) return emptyList()

    val inicio = element.arcStart ?: 0.0
    // Sin barrido guardado, la vuelta entera: es lo que menos sorprende si
    // alguna vez llega un arco de fuera sin ese dato.
    val barrido = element.arcSweep ?: (2 * PI)
    if (abs(barrido) < 1e-6) return emptyList()

    val pasos = (pasosPorVuelta * abs(barrido) / (2 * PI)).toInt().coerceAtLeast(2)
    return (0..pasos).map { i ->
        val t = inicio + barrido * i / pasos
        // El punto sobre el óvalo, girado con el elemento y llevado a relativo.
        val enEscena = pointRotateRads(
            Pt(c.cx + rx * cos(t), c.cy + ry * sin(t)),
            Pt(c.cx, c.cy),
            element.angle
        )
        Pt(enEscena.x - element.x, enEscena.y - element.y)
    }
}

/**
 * Cuántos tramos tiene una vuelta completa.
 *
 * Setenta y dos son cinco grados por tramo: por debajo se ven los vértices en un
 * arco grande, y por encima no se gana nada que el ojo note y sí se paga en cada
 * fotograma.
 */
const val ARCO_PASOS = 72
