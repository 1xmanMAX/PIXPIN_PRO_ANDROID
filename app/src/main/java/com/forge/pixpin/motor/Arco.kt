package com.forge.pixpin.motor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
 * Los puntos del arco, **relativos a `x`/`y` y sin girar**, como cualquier
 * elemento de puntos.
 *
 * Devolverlos así no es un detalle: el renderizador los pasa por el mismo
 * generador de trazo rugoso que una línea, y por eso un arco sale con el mismo
 * pulso tembloroso que el resto del dibujo en vez de con una curva de compás
 * que cantaría al lado de un rectángulo a mano alzada.
 *
 * ## Sin girar, y esto costó un fallo
 *
 * Antes salían **ya girados**, y parecía lo cómodo. Pero en este motor la
 * inclinación no vive en la geometría: la aplica quien pinta, con una matriz —el
 * lienzo, el `<g transform>` del SVG y el flujo del PDF hacen los tres lo mismo,
 * y lo hacen para todos los elementos por igual. Un arco que ya venía girado se
 * giraba **dos veces**, así que aparecía en un sitio distinto del que decía su
 * geometría: se picaba donde no se veía, y recortar un óvalo girado dejaba el
 * trozo bueno en otra parte.
 *
 * Girando aquí lo que había que hacer era acordarse de **no** girar en los otros
 * cuatro sitios. Sin girar, el arco es un elemento más y no hay nada que
 * recordar. Quien necesite sus puntos en el mundo —cruzarlos con otra figura—
 * los gira, como haría con cualquier otro. Ver [contornosDe].
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
        // El punto sobre el óvalo, llevado a relativo. Sin girar: ver arriba.
        Pt(c.cx + rx * cos(t) - element.x, c.cy + ry * sin(t) - element.y)
    }
}

/**
 * La caja del **trozo que se ve**, no la del óvalo del que salió.
 *
 * Un arco guarda el óvalo entero —`x`, `y`, `width`, `height`— y aparte por dónde empieza
 * y cuánto barre: eso es lo que le permite seguir creciendo con el compás sin degenerar en
 * cien rayas. Pero significa que `getElementAbsoluteCoords` de un arco devuelve **el
 * círculo completo**, y recortar un círculo dejaba una uña de trazo con la caja del
 * círculo entero puesta: el recuadro de selección salía enorme y vacío, y encerrar la uña
 * con el dedo no la cogía nunca, porque el recuadro tenía que contener una caja que no se
 * veía por ninguna parte.
 *
 * Aquí sale la caja de verdad, y sale exacta en vez de muestreando: de un arco solo pueden
 * asomar sus dos puntas y los cuatro puntos donde el óvalo toca sus ejes, así que basta
 * mirar los que caigan dentro del barrido. Sin rotar —como todo lo demás en este mundo—,
 * que el ángulo se pone después y alrededor del centro del óvalo.
 */
fun cajaDelArco(element: Element): Bounds {
    val c = getElementAbsoluteCoords(element)
    val rx = (c.x2 - c.x1) / 2
    val ry = (c.y2 - c.y1) / 2
    if (rx <= 0.0 || ry <= 0.0) return c.toBounds()

    val inicio = element.arcStart ?: 0.0
    val barrido = element.arcSweep ?: (2 * PI)
    // La vuelta entera ya es el óvalo: no hay nada que recortar de su caja.
    if (abs(barrido) >= 2 * PI - 1e-9) return c.toBounds()

    val desde = min(inicio, inicio + barrido)
    val hasta = max(inicio, inicio + barrido)

    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    fun mete(t: Double) {
        val x = c.cx + rx * cos(t)
        val y = c.cy + ry * sin(t)
        minX = min(minX, x); minY = min(minY, y)
        maxX = max(maxX, x); maxY = max(maxY, y)
    }
    mete(desde)
    mete(hasta)
    // Los cuatro extremos del óvalo, los que caigan dentro del barrido: son los
    // únicos sitios donde la curva puede sobresalir de sus propias puntas.
    var k = ceil(desde / (PI / 2))
    while (k * (PI / 2) <= hasta) {
        mete(k * (PI / 2))
        k += 1
    }
    return Bounds(minX, minY, maxX, maxY)
}

/**
 * Cuántos tramos tiene una vuelta completa.
 *
 * Setenta y dos son cinco grados por tramo: por debajo se ven los vértices en un
 * arco grande, y por encima no se gana nada que el ojo note y sí se paga en cada
 * fotograma.
 */
const val ARCO_PASOS = 72
