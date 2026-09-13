package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * **Una «L» y el dedo parado: sale un rectángulo.** Lo pidió el usuario el 12-sep-2026, como
 * hermano del compás —clavar la punta y parar da un círculo— y de la recta —trazar y parar la
 * endereza—: se baja, se gira noventa grados, se para, y lo trazado se vuelve un rectángulo con
 * una esquina donde empezó y la otra donde está el dedo, que sigue tirando de ella.
 *
 * Aquí solo se decide si lo trazado **es** una L. Se simplifica el trazo con Douglas-Peucker —lo
 * que tiembla la mano se va y quedan los vértices— y se pide lo que tiene una L de verdad: dos
 * brazos, cada uno con cuerpo, y un codo de entre sesenta y ciento veinte grados. Una raya con
 * una curva suave no llega a codo, y una V cerrada tampoco es una L: esas siguen siendo rectas.
 */
object GestoDeRectangulo {

    /** Lo que tiene que medir cada brazo, en píxeles de pantalla. */
    const val BRAZO_MINIMO = 36.0

    fun esUnaEle(puntos: List<Pt>, zoom: Double): Boolean {
        if (puntos.size < 3) return false
        val z = zoom.coerceAtLeast(0.0001)
        var largo = 0.0
        for (i in 1 until puntos.size) largo += hypot(puntos[i].x - puntos[i - 1].x, puntos[i].y - puntos[i - 1].y)
        var s = douglasPeucker(puntos, max(10.0 / z, largo * 0.07))
        // Un codo redondeado deja dos vértices juntos: se funden en uno.
        if (s.size == 4) {
            val entre = hypot(s[2].x - s[1].x, s[2].y - s[1].y)
            val corto = min(hypot(s[1].x - s[0].x, s[1].y - s[0].y), hypot(s[3].x - s[2].x, s[3].y - s[2].y))
            if (entre <= corto * 0.35) s = listOf(s[0], Pt((s[1].x + s[2].x) / 2, (s[1].y + s[2].y) / 2), s[3])
        }
        if (s.size != 3) return false
        val ax = s[1].x - s[0].x
        val ay = s[1].y - s[0].y
        val bx = s[2].x - s[1].x
        val by = s[2].y - s[1].y
        val la = hypot(ax, ay)
        val lb = hypot(bx, by)
        if (la * z < BRAZO_MINIMO || lb * z < BRAZO_MINIMO) return false
        if (min(la, lb) < 0.25 * max(la, lb)) return false
        // El coseno entre los dos brazos: cero es un codo recto; hasta 0,5 son 60–120°.
        return abs((ax * bx + ay * by) / (la * lb)) <= 0.5
    }
}
