package com.forge.pixpin.motor

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las flechas de codos.
 *
 * Lo que hay que comprobar no es que «se vea bien» sino la propiedad de la que
 * depende que sirva para un mapa mental: **todos sus tramos son horizontales o
 * verticales**. En cuanto uno se va en diagonal, el esquema deja de leerse como
 * una estructura y pasa a parecer un garabato ordenado a medias.
 */
class ElbowTest {

    /** ¿Todos los tramos van por un eje? Los codos redondeados no cuentan. */
    private fun todoOrtogonal(pts: List<Pt>): Boolean =
        pts.zipWithNext().all { (a, b) ->
            abs(a.x - b.x) < 0.001 || abs(a.y - b.y) < 0.001
        }

    // ---- El trazado ----

    @Test
    fun `todos los tramos van por un eje`() {
        val casos = listOf(
            Pt(0.0, 0.0) to Pt(200.0, 100.0),
            Pt(0.0, 0.0) to Pt(100.0, 300.0),
            Pt(300.0, 200.0) to Pt(50.0, 40.0),
            Pt(0.0, 100.0) to Pt(400.0, 20.0)
        )
        for ((desde, hasta) in casos) {
            val pts = elbowPoints(desde, hasta)
            assertTrue("$desde → $hasta se va en diagonal: $pts", todoOrtogonal(pts))
        }
    }

    @Test
    fun `empieza y acaba donde se le dice`() {
        val desde = Pt(30.0, 40.0)
        val hasta = Pt(300.0, 500.0)
        val pts = elbowPoints(desde, hasta)
        assertEquals(desde, pts.first())
        assertEquals(hasta, pts.last())
    }

    /**
     * Manda el eje con más distancia: dos cajas una al lado de la otra se unen
     * saliendo en horizontal, no subiendo primero.
     */
    @Test
    fun `sale por el eje que mas hay que recorrer`() {
        val aLoAncho = elbowPoints(Pt(0.0, 0.0), Pt(400.0, 60.0))
        // El primer tramo es horizontal: misma y.
        assertEquals(aLoAncho[0].y, aLoAncho[1].y, 0.001)

        val aLoAlto = elbowPoints(Pt(0.0, 0.0), Pt(60.0, 400.0))
        // El primer tramo es vertical: misma x.
        assertEquals(aLoAlto[0].x, aLoAlto[1].x, 0.001)
    }

    /**
     * Dos cajas casi a la misma altura se unen con **una recta**, no con una
     * escalera de tramos de un píxel — que es un borrón y lo contrario de lo
     * que se busca al ordenar.
     */
    @Test
    fun `casi alineadas se unen con una recta`() {
        val pts = elbowPoints(Pt(0.0, 100.0), Pt(400.0, 103.0))
        assertEquals("debería ser un solo tramo", 2, pts.size)
        assertEquals(pts[0].y, pts[1].y, 0.001)

        val verticales = elbowPoints(Pt(100.0, 0.0), Pt(102.0, 400.0))
        assertEquals(2, verticales.size)
        assertEquals(verticales[0].x, verticales[1].x, 0.001)
    }

    @Test
    fun `el cruce va por la mitad del camino`() {
        val pts = elbowPoints(Pt(0.0, 0.0), Pt(400.0, 200.0))
        assertEquals(4, pts.size)
        assertEquals("el cruce no está a mitad", 200.0, pts[1].x, 0.001)
        assertEquals(200.0, pts[2].x, 0.001)
    }

    // ---- Los codos redondeados ----

    @Test
    fun `redondear conserva los extremos`() {
        val vertices = elbowPoints(Pt(0.0, 0.0), Pt(400.0, 200.0))
        val redondeado = elbowRounded(vertices)
        assertEquals(vertices.first(), redondeado.first())
        assertEquals(vertices.last(), redondeado.last())
        assertTrue("no ha redondeado nada", redondeado.size > vertices.size)
    }

    /**
     * El radio se acota al tramo más corto. Sin eso, en un codo entre dos
     * tramos de 10 px un radio de 16 se los comería y el camino se cruzaría
     * consigo mismo.
     */
    @Test
    fun `un codo entre tramos cortos no se desborda`() {
        val vertices = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0))
        val redondeado = elbowRounded(vertices, radio = 16.0)
        for (p in redondeado) {
            assertTrue("se sale por x: ${p.x}", p.x in -0.01..10.01)
            assertTrue("se sale por y: ${p.y}", p.y in -0.01..10.01)
        }
    }

    @Test
    fun `dos puntos no tienen codo que redondear`() {
        val rectos = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0))
        assertEquals(rectos, elbowRounded(rectos))
    }

    // ---- Dibujando ----

    @Test
    fun `una flecha normal no se traza en codos`() {
        val f = Element(
            id = "f", type = ElementType.ARROW, x = 0.0, y = 0.0,
            width = 100.0, height = 100.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(100.0, 100.0))
        )
        assertEquals(absolutePoints(f), puntosDeTrazado(f))
    }

    @Test
    fun `una flecha de codos si`() {
        val f = Element(
            id = "f", type = ElementType.ARROW, x = 0.0, y = 0.0,
            width = 300.0, height = 200.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(300.0, 200.0)),
            elbowed = true
        )
        val pts = puntosDeTrazado(f)
        assertTrue("no ha trazado codos", pts.size > 2)
        assertEquals(Pt(0.0, 0.0), pts.first())
        assertEquals(Pt(300.0, 200.0), pts.last())
    }

    /** Se dibuja con la herramienta y sale de codos si el estilo lo pide. */
    @Test
    fun `el estilo decide si la flecha nace de codos`() {
        val c = DrawController()
        c.changeStyle({ it.copy(elbowed = true) }, { it })
        c.selectTool(Tool.ARROW)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(300.0, 200.0))
        c.pointerUp(Pt(300.0, 200.0))

        assertTrue("no nació de codos", c.scene.visible.last().elbowed)
    }

    /** Y solo la flecha: una línea o un rectángulo no llevan codos. */
    @Test
    fun `solo la flecha puede ser de codos`() {
        val c = DrawController()
        c.changeStyle({ it.copy(elbowed = true) }, { it })
        c.selectTool(Tool.LINE)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(300.0, 200.0))
        c.pointerUp(Pt(300.0, 200.0))

        assertTrue(!c.scene.visible.last().elbowed)
    }
}
