package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cómo se agarra un círculo mientras se dibuja.
 *
 * Un rectángulo se piensa por sus esquinas, pero **un círculo no tiene**: se
 * piensa por su centro y por su borde. Dibujarlo agarrando la esquina de una
 * caja que no se ve deja el trazo lejos de la yema —a la diagonal, un 41% más
 * afuera— y obliga a compensar esa distancia a ojo en cada círculo.
 *
 * Lo que se comprueba aquí es una sola cosa, y de ella salen todas las demás:
 * **la circunferencia pasa por donde está el dedo**.
 */
class CirculoTest {

    /** Dibuja un óvalo desde [centro] arrastrando hasta [dedo]. */
    private fun dibujar(
        centro: Pt, dedo: Pt, perfecto: Boolean = false, escena: List<Element> = emptyList()
    ): Element {
        val c = DrawController(Scene(elements = escena))
        c.selectTool(Tool.ELLIPSE)
        c.keepAspectRatio = perfecto
        c.pointerDown(centro)
        c.pointerMove(dedo)
        c.pointerUp(dedo)
        return c.scene.visible.last { it.type == ElementType.ELLIPSE }
    }

    /** Cuánto se aparta [p] del borde del óvalo: 0 es estar justo encima. */
    private fun fueraDelBorde(e: Element, p: Pt): Double {
        val c = getElementAbsoluteCoords(e)
        val a = (c.x2 - c.x1) / 2
        val b = (c.y2 - c.y1) / 2
        if (a <= 0.0 || b <= 0.0) return Double.MAX_VALUE
        val dx = (p.x - c.cx) / a
        val dy = (p.y - c.cy) / b
        return dx * dx + dy * dy - 1.0
    }

    // ---- El dedo va sobre el borde ----

    @Test
    fun `el circulo perfecto llega hasta el dedo`() {
        val e = dibujar(Pt(100.0, 100.0), Pt(160.0, 180.0), perfecto = true)
        val c = getElementAbsoluteCoords(e)
        // El radio es lo que hay del centro al dedo: 3-4-5, o sea 100.
        assertEquals(100.0, (c.x2 - c.x1) / 2, 0.001)
        assertEquals(100.0, (c.y2 - c.y1) / 2, 0.001)
        assertEquals(100.0, c.cx, 0.001)
        assertEquals(100.0, c.cy, 0.001)
        assertEquals(0.0, fueraDelBorde(e, Pt(160.0, 180.0)), 0.001)
    }

    @Test
    fun `el ovalo libre tambien pasa por el dedo`() {
        val e = dibujar(Pt(100.0, 100.0), Pt(220.0, 160.0))
        assertEquals(0.0, fueraDelBorde(e, Pt(220.0, 160.0)), 0.001)
        // Y conserva la proporción de arrastrar por la esquina: el doble de
        // ancho que de alto, porque se arrastró el doble en x que en y.
        assertEquals(2.0, e.width / e.height, 0.001)
    }

    /**
     * **El punto por el que se agarra se corre por el borde.** Tirando hacia
     * arriba se sujeta por arriba, y hacia el lado por el lado: es lo que hace
     * la mano con un compás, y lo que no pasaba agarrando una esquina fija.
     */
    @Test
    fun `el punto de agarre se mueve por la circunferencia`() {
        val centro = Pt(100.0, 100.0)
        for (grados in 0..330 step 30) {
            val a = Math.toRadians(grados.toDouble())
            val dedo = Pt(centro.x + 90 * kotlin.math.cos(a), centro.y + 90 * kotlin.math.sin(a))
            val e = dibujar(centro, dedo, perfecto = true)
            assertEquals("a $grados° el borde no llega al dedo", 0.0, fueraDelBorde(e, dedo), 0.001)
        }
    }

    /** En diagonal es donde más se notaba: el borde quedaba un 41% corto. */
    @Test
    fun `en diagonal el borde ya no se queda corto`() {
        val e = dibujar(Pt(0.0, 0.0), Pt(100.0, 100.0), perfecto = true)
        val c = getElementAbsoluteCoords(e)
        assertEquals(hypot(100.0, 100.0), (c.x2 - c.x1) / 2, 0.001)
        // Agarrando la esquina, el radio habría sido 100 y el dedo se habría
        // quedado 41 píxeles fuera del trazo.
        assertTrue("el radio no ha crecido hasta el dedo", (c.x2 - c.x1) / 2 > 140.0)
    }

    // ---- Con el imán ----

    /**
     * **Ahora el óvalo se puede enganchar.** El imán pega el punto del dedo, y
     * como el dedo estaba en la esquina de una caja invisible, enganchar un
     * círculo a un vértice o a un cruce no servía de nada: lo que se pegaba ahí
     * era el vértice, y la circunferencia pasaba por otro sitio.
     */
    @Test
    fun `la circunferencia se engancha a una esquina`() {
        val caja = Element(
            id = "r", type = ElementType.RECTANGLE, x = 200.0, y = 100.0,
            width = 100.0, height = 100.0, seed = 1
        )
        // Se dibuja desde (100,100) tirando hacia la esquina (200,100), fallando
        // por tres píxeles: el imán la acerca y el borde tiene que pasar por ella.
        val e = dibujar(Pt(100.0, 100.0), Pt(197.0, 102.0), perfecto = true, escena = listOf(caja))
        assertEquals(0.0, fueraDelBorde(e, Pt(200.0, 100.0)), 0.005)
    }

    /** Y a la intersección de dos figuras, que es la que no se acierta a pulso. */
    @Test
    fun `la circunferencia se engancha a un cruce`() {
        val horizontal = Element(
            id = "h", type = ElementType.LINE, x = 0.0, y = 200.0,
            width = 400.0, height = 0.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(400.0, 0.0))
        )
        val vertical = Element(
            id = "v", type = ElementType.LINE, x = 250.0, y = 0.0,
            width = 0.0, height = 400.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(0.0, 400.0))
        )
        val e = dibujar(
            Pt(100.0, 200.0), Pt(247.0, 203.0),
            perfecto = true, escena = listOf(horizontal, vertical)
        )
        assertEquals(0.0, fueraDelBorde(e, Pt(250.0, 200.0)), 0.005)
    }

    // ---- Lo que no cambia ----

    /** El centro sigue siendo donde se posó el dedo. */
    @Test
    fun `el centro es donde se toco`() {
        val e = dibujar(Pt(80.0, 140.0), Pt(200.0, 60.0))
        val c = getElementAbsoluteCoords(e)
        assertEquals(80.0, c.cx, 0.001)
        assertEquals(140.0, c.cy, 0.001)
    }

    /**
     * El rectángulo **no** cambia: se piensa por sus esquinas, y agarrarlo por
     * un punto de su borde no significa nada.
     */
    @Test
    fun `el rectangulo se sigue agarrando por la esquina`() {
        val c = DrawController()
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(100.0, 60.0))
        c.pointerUp(Pt(100.0, 60.0))

        val e = c.scene.visible.last()
        assertEquals(100.0, e.width, 0.001)
        assertEquals(60.0, e.height, 0.001)
    }

    /** Un toque seco sigue sin dejar nada: un círculo de radio cero no es nada. */
    @Test
    fun `un toque seco no deja circulo`() {
        val c = DrawController()
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(50.0, 50.0))
        c.pointerUp(Pt(50.0, 50.0))
        assertTrue(c.scene.visible.isEmpty())
    }

    // ---- La figura perfecta ----

    /**
     * El segundo dedo apoyado cuadra lo que se está trazando **en el momento**,
     * sin esperar a que el dedo se mueva.
     *
     * El lienzo lo pide reenviando el punto donde está el dedo en cuanto se
     * apoya el segundo; si no, la forma se quedaba como estaba hasta el
     * siguiente movimiento y parecía que el gesto no hacía nada.
     */
    @Test
    fun `apoyar el segundo dedo cuadra la forma sin mover`() {
        val c = DrawController()
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerMove(Pt(200.0, 140.0))
        val libre = c.scene.visible.last()
        assertTrue("todavía no tiene que ser redondo", libre.width != libre.height)

        // Se apoya el segundo dedo: el lienzo reenvía el mismo punto.
        c.keepAspectRatio = true
        c.pointerMove(Pt(200.0, 140.0))
        val redondo = c.scene.visible.last()
        assertEquals(redondo.width, redondo.height, 0.001)
        assertEquals(0.0, fueraDelBorde(redondo, Pt(200.0, 140.0)), 0.001)
    }

    /** Levantándolo se sigue trazando a mano suelta, sin cortar el gesto. */
    @Test
    fun `levantar el segundo dedo devuelve la forma libre`() {
        val c = DrawController()
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(0.0, 0.0))
        c.keepAspectRatio = true
        c.pointerMove(Pt(100.0, 40.0))
        assertEquals(c.scene.visible.last().width, c.scene.visible.last().height, 0.001)

        c.keepAspectRatio = false
        c.pointerMove(Pt(100.0, 40.0))
        val libre = c.scene.visible.last()
        assertEquals(100.0, libre.width, 0.001)
        assertEquals(40.0, libre.height, 0.001)
    }

    /** El rombo perfecto también: alto igual a ancho, naciendo de su vértice. */
    @Test
    fun `el rombo perfecto sale simetrico`() {
        val c = DrawController()
        c.selectTool(Tool.DIAMOND)
        c.keepAspectRatio = true
        c.pointerDown(Pt(100.0, 0.0))
        c.pointerMove(Pt(160.0, 200.0))
        c.pointerUp(Pt(160.0, 200.0))

        val e = c.scene.visible.last()
        assertEquals(e.width, e.height, 0.001)
    }

    /** Y con el segundo dedo el trazo del arco no se cancela: sigue barriendo. */
    @Test
    fun `el arco sigue vivo con el segundo dedo apoyado`() {
        val guia = Element(
            id = "g", type = ElementType.ELLIPSE, x = 0.0, y = 0.0,
            width = 200.0, height = 200.0, seed = 1, reference = true
        )
        val c = DrawController(Scene(elements = listOf(guia)))
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(200.0, 100.0))
        assertTrue("el arco tiene que contar como «dibujando»", c.dibujando)
        c.pointerMove(Pt(100.0, 200.0))
        c.pointerUp(Pt(100.0, 200.0))
        assertTrue(c.scene.visible.any { it.type == ElementType.ARC })
    }

    /** Y con nada empezado, el segundo dedo no significa figura perfecta. */
    @Test
    fun `sin nada naciendo no hay figura perfecta`() {
        val c = DrawController()
        c.selectTool(Tool.ELLIPSE)
        assertTrue(!c.dibujando)
    }

    /** Y con el centro enganchado, el círculo nace centrado donde se pidió. */
    @Test
    fun `el centro tambien se engancha`() {
        val caja = Element(
            id = "r", type = ElementType.RECTANGLE, x = 100.0, y = 100.0,
            width = 100.0, height = 100.0, seed = 1
        )
        val e = dibujar(Pt(103.0, 102.0), Pt(300.0, 100.0), perfecto = true, escena = listOf(caja))
        val c = getElementAbsoluteCoords(e)
        assertTrue(
            "el centro no se ha pegado a la esquina",
            abs(c.cx - 100.0) < 0.001 && abs(c.cy - 100.0) < 0.001
        )
    }
}
