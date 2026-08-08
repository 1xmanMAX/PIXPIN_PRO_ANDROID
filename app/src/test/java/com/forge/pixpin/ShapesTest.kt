package com.forge.pixpin.motor

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que tiene que cumplir el contorno de una figura para que el trazo rugoso
 * no se deforme.
 *
 * Las dos primeras pruebas son las que atrapan el fallo del rizo en las
 * esquinas: no comprueban el aspecto —eso hay que mirarlo— sino la condición
 * geométrica de la que dependía, que sí es medible.
 */
class ShapesTest {

    private fun element(
        type: ElementType,
        w: Double = 200.0,
        h: Double = 200.0,
        roundness: Roundness? = Roundness(Roundness.ADAPTIVE_RADIUS)
    ) = Element(
        id = "x", type = type, x = 40.0, y = 40.0, width = w, height = h,
        seed = 123456, roundness = roundness
    )

    private fun coords(e: Element) = getElementAbsoluteCoords(e)

    private fun spacings(pts: List<Pt>): List<Double> =
        (pts + pts.first()).zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }

    // ---- La condición que evita el rizo ----

    /**
     * El rizo salía cuando un punto tenía un vecino dieciséis veces más lejos
     * que el otro: la tangente de Catmull-Rom, `(P[i+1] − P[i−1]) / 6`, daba un
     * tirador mucho más largo que el segmento que gobernaba. La cuenta aguanta
     * hasta un factor cinco.
     */
    @Test
    fun `en el rectangulo redondeado ningun vecino queda cinco veces mas lejos que el otro`() {
        for ((w, h) in listOf(200.0 to 200.0, 400.0 to 120.0, 60.0 to 300.0, 1200.0 to 800.0)) {
            val e = element(ElementType.RECTANGLE, w, h)
            val gaps = spacings(roundedRectPoints(coords(e), e))
            val peor = gaps.zipWithNext { a, b -> maxOf(a, b) / minOf(a, b) }.max()
            assertTrue("caja ${w}x$h: vecinos ${"%.1f".format(peor)}× desiguales", peor <= 5.0)
        }
    }

    @Test
    fun `en el rombo redondeado tampoco`() {
        for ((w, h) in listOf(200.0 to 200.0, 400.0 to 120.0, 60.0 to 300.0, 1200.0 to 800.0)) {
            val e = element(ElementType.DIAMOND, w, h)
            val gaps = spacings(roundedDiamondPoints(coords(e), e))
            val peor = gaps.zipWithNext { a, b -> maxOf(a, b) / minOf(a, b) }.max()
            assertTrue("caja ${w}x$h: vecinos ${"%.1f".format(peor)}× desiguales", peor <= 5.0)
        }
    }

    /**
     * El punto de partida no puede repetirse al final: quien traza cierra el
     * camino añadiendo el primero, y un duplicado dejaría dos vecinos a
     * distancia cero — la tangente se dispararía otra vez.
     */
    @Test
    fun `el contorno no repite el punto de partida`() {
        for (type in listOf(ElementType.RECTANGLE, ElementType.DIAMOND)) {
            val e = element(type)
            val pts = if (type == ElementType.RECTANGLE) {
                roundedRectPoints(coords(e), e)
            } else {
                roundedDiamondPoints(coords(e), e)
            }
            val d = hypot(pts.last().x - pts.first().x, pts.last().y - pts.first().y)
            assertTrue("$type cierra sobre sí mismo (d=$d)", d > 1e-3)
        }
    }

    // ---- El rombo redondeado, que antes no existía ----

    @Test
    fun `el rombo con roundness sale redondeado y sin roundness en pico`() {
        val redondo = element(ElementType.DIAMOND)
        val enPico = element(ElementType.DIAMOND, roundness = null)

        assertEquals(4, buildShapeGeometry(enPico)!!.outline.size)
        assertTrue(
            "el rombo redondeado tiene que muestrear sus puntas",
            buildShapeGeometry(redondo)!!.outline.size > 20
        )
    }

    /**
     * Redondear recorta las puntas, así que el contorno queda **dentro** de la
     * caja del elemento, nunca fuera.
     */
    @Test
    fun `el rombo redondeado no se sale de su caja`() {
        val e = element(ElementType.DIAMOND, 240.0, 110.0)
        val c = coords(e)
        for (p in roundedDiamondPoints(c, e)) {
            assertTrue("x fuera: ${p.x}", p.x >= c.x1 - 0.01 && p.x <= c.x2 + 0.01)
            assertTrue("y fuera: ${p.y}", p.y >= c.y1 - 0.01 && p.y <= c.y2 + 0.01)
        }
    }

    /**
     * El rombo redondeado no se sale de su caja, sea cual sea la proporción.
     *
     * Se comprueba contra la **caja** y no contra la inecuación del rombo
     * (`|x|/a + |y|/b ≤ 1`) porque el original recorta cada punta moviéndose
     * `vr` en horizontal y `hr` en vertical, y en una figura muy aplastada ese
     * desplazamiento no cae sobre la arista sino un poco por fuera: el rombo
     * redondeado queda algo más «lleno» que el de picos. Es del original, no un
     * fallo, y por eso la prueba mide lo que sí tiene que cumplirse siempre.
     *
     * Tampoco se comprueba la convexidad: la unión entre cada cúbica y su recta
     * tiene un quiebro minúsculo de tangente que el original también tiene.
     */
    @Test
    fun `el rombo redondeado no se sale de su caja en ninguna proporcion`() {
        for ((w, h) in listOf(400.0 to 40.0, 40.0 to 400.0, 200.0 to 200.0, 500.0 to 90.0)) {
            val e = element(ElementType.DIAMOND, w, h)
            val c = coords(e)
            for (p in roundedDiamondPoints(c, e)) {
                assertTrue("caja ${w}x$h: x fuera ${p.x}", p.x >= c.x1 - 0.01 && p.x <= c.x2 + 0.01)
                assertTrue("caja ${w}x$h: y fuera ${p.y}", p.y >= c.y1 - 0.01 && p.y <= c.y2 + 0.01)
            }
        }
    }

    // ---- El rectángulo ----

    @Test
    fun `el rectangulo sin roundness son sus cuatro esquinas`() {
        val e = element(ElementType.RECTANGLE, roundness = null)
        assertEquals(4, buildShapeGeometry(e)!!.outline.size)
    }

    /** Una figura enorme no puede generar un contorno sin freno. */
    @Test
    fun `una figura enorme no dispara el numero de puntos`() {
        val e = element(ElementType.RECTANGLE, 20000.0, 20000.0)
        assertTrue(roundedRectPoints(coords(e), e).size < 600)
    }
}
