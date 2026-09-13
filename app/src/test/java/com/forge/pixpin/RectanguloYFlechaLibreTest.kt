package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * La «L» que se vuelve rectángulo al parar el dedo, y la punta de la flecha libre que sigue a
 * su raya en vez de saltar entre unas pocas posturas.
 */
class RectanguloYFlechaLibreTest {

    private fun aMano() = DrawController().apply { selectTool(Tool.FREEDRAW) }

    @Test
    fun `una ele y el dedo parado dan un rectangulo de esquina a esquina`() {
        val c = aMano()
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        var t = 0L
        // Abajo, con algo de temblor…
        for (y in 10..200 step 10) { t += 20; c.pointerMove(Pt(if (y % 20 == 0) 1.5 else -1.0, y.toDouble()), cuando = t) }
        // …y a la derecha.
        for (x in 10..150 step 10) { t += 20; c.pointerMove(Pt(x.toDouble(), 201.0 + (x % 3)), cuando = t) }
        assertTrue(c.latido(t + ESPERA_PARA_LA_RECTA))
        assertTrue(c.rectangulandoSolo)
        assertFalse(c.enderezandoSolo)
        val r = c.scene.visible.last()
        assertEquals(ElementType.RECTANGLE, r.type)
        assertEquals(0.0, r.x, 1e-6)
        assertEquals(0.0, r.y, 1e-6)
        assertEquals(150.0, r.width, 1e-6)
        assertEquals(201.0, r.height, 1.0)

        // El primer punto es una esquina; el dedo, la otra, y tira de ella.
        c.pointerMove(Pt(300.0, 120.0), cuando = t + 900)
        val r2 = c.scene.visible.last()
        assertEquals(ElementType.RECTANGLE, r2.type)
        assertEquals(r.id, r2.id)
        assertEquals(0.0, r2.x, 1e-6)
        assertEquals(300.0, r2.width, 1e-6)
        assertEquals(120.0, r2.height, 1e-6)
        // Y hacia atrás, la esquina de salida se queda donde estaba.
        c.pointerMove(Pt(-50.0, -40.0), cuando = t + 1000)
        val r3 = c.scene.visible.last()
        assertEquals(-50.0, r3.x, 1e-6)
        assertEquals(-40.0, r3.y, 1e-6)
        assertEquals(50.0, r3.width, 1e-6)
        assertEquals(40.0, r3.height, 1e-6)
    }

    @Test
    fun `una raya y una V abierta siguen siendo rectas`() {
        // La raya torcida de siempre.
        val recta = listOf(Pt(0.0, 0.0), Pt(40.0, 12.0), Pt(80.0, -9.0), Pt(120.0, 4.0))
        assertFalse(GestoDeRectangulo.esUnaEle(recta, 1.0))
        // Un ángulo de 150°: no es un codo.
        val abierta = (0..10).map { Pt(it * 10.0, 0.0) } + (1..10).map { Pt(100.0 + it * 10.0 * cos(Math.toRadians(30.0)), it * 10.0 * sin(Math.toRadians(30.0))) }
        assertFalse(GestoDeRectangulo.esUnaEle(abierta, 1.0))
        // Un gancho diminuto al final no es un brazo.
        val gancho = (0..20).map { Pt(0.0, it * 10.0) } + listOf(Pt(8.0, 200.0), Pt(15.0, 200.0))
        assertFalse(GestoDeRectangulo.esUnaEle(gancho, 1.0))
        // Y una L de verdad, con el codo redondeado, sí.
        val ele = (0..18).map { Pt(0.0, it * 10.0) } + listOf(Pt(4.0, 188.0), Pt(12.0, 195.0)) + (2..15).map { Pt(it * 10.0, 197.0) }
        assertTrue(GestoDeRectangulo.esUnaEle(ele, 1.0))
    }

    private fun anguloDeLaPunta(puntos: List<Pt>): Double {
        val e = newElement(ElementType.ARROW, 0.0, 0.0, ItemStyle()).copy(points = puntos, endArrowhead = Arrowhead.ARROW)
        val s = getArrowheadPoints(e, ArrowEnd.END, Arrowhead.ARROW)!!
        val medio = Pt((s.wings.first.x + s.wings.second.x) / 2, (s.wings.first.y + s.wings.second.y) / 2)
        return Math.toDegrees(atan2(s.tip.y - medio.y, s.tip.x - medio.x))
    }

    private fun diferencia(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return kotlin.math.abs(d)
    }

    @Test
    fun `la punta de la flecha libre gira seguida con la raya`() {
        // Una raya a pulso en cada dirección, con los puntos a un píxel: la punta tiene que
        // apuntar a donde va la raya, sin escalones.
        var angulo = 0.0
        while (angulo < 360.0) {
            val r = Math.toRadians(angulo)
            val puntos = (0..120).map { i ->
                val temblor = if (i % 2 == 0) 0.4 else -0.4
                Pt(i * cos(r) - temblor * sin(r), i * sin(r) + temblor * cos(r))
            }
            assertTrue("a $angulo° la punta miraba a ${anguloDeLaPunta(puntos)}", diferencia(anguloDeLaPunta(puntos), angulo) < 3.0)
            angulo += 7.0
        }
    }

    @Test
    fun `el gancho de la mano al soltar no tuerce la punta`() {
        val puntos = (0..100).map { Pt(it.toDouble(), 0.0) } + listOf(Pt(101.0, 1.0), Pt(101.5, 2.5), Pt(101.5, 4.0))
        assertTrue("miraba a ${anguloDeLaPunta(puntos)}", diferencia(anguloDeLaPunta(puntos), 0.0) < 12.0)
    }
}
