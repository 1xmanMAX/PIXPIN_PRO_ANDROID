package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Las herramientas de lámina en el editor: la marca de ángulo y el arco, de punta a punta. */
class LaminasEnElEditorTest {

    /** Un triángulo de lados rectos: A(0,0), B(200,0), C(60,-150). Ningún ángulo es recto. */
    private fun conTriangulo(): DrawController {
        fun lado(id: String, a: Pt, b: Pt) = Element(
            id = id, type = ElementType.LINE, x = a.x, y = a.y,
            width = kotlin.math.abs(b.x - a.x), height = kotlin.math.abs(b.y - a.y), seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
        )
        val c = DrawController()
        c.load(
            Scene(
                elements = listOf(
                    lado("ab", Pt(0.0, 0.0), Pt(200.0, 0.0)),
                    lado("bc", Pt(200.0, 0.0), Pt(60.0, -150.0)),
                    lado("ca", Pt(60.0, -150.0), Pt(0.0, 0.0))
                )
            )
        )
        return c
    }

    private fun toque(c: DrawController, p: Pt) { c.pointerDown(p); c.pointerUp(p) }
    private fun marcas(c: DrawController) = c.scene.elements.filter { !it.isDeleted && it.marcaAngulo != null }

    @Test
    fun `el angulo pone alfa, beta y theta y un deshacer se lleva la ultima`() {
        val c = conTriangulo()
        c.selectTool(Tool.ANGULO)
        toque(c, Pt(25.0, -8.0))      // en A
        toque(c, Pt(175.0, -8.0))     // en B
        toque(c, Pt(60.0, -120.0))    // en C
        assertEquals(listOf("α", "β", "θ"), marcas(c).map { it.marca!!.valor })
        assertTrue(marcas(c).all { it.cierre == CierreArco.SECTOR && !isTransparent(it.backgroundColor) })
        c.undo()
        assertEquals(2, marcas(c).size)
    }

    @Test
    fun `tocar una marca le cambia la letra sin repetir`() {
        val c = conTriangulo()
        c.selectTool(Tool.ANGULO)
        toque(c, Pt(25.0, -8.0))
        toque(c, Pt(175.0, -8.0))
        // Dentro de la marca de A: pasa a la siguiente libre (la β la tiene B).
        toque(c, Pt(12.0, -3.0))
        assertEquals(listOf("θ", "β"), marcas(c).map { it.marca!!.valor })
        assertTrue(c.cambiarLetraDeAngulo(-1))
        assertEquals("α", marcas(c).first().marca!!.valor)
    }

    @Test
    fun `el arco por el centro sale de un toque y un arrastre`() {
        val c = DrawController()
        c.selectTool(Tool.ARCO)
        c.cambiarEstilo(c.estiloActivo().copy(modoArco = ModoArco.CENTRO, cierreArco = CierreArco.SECTOR))
        toque(c, Pt(500.0, 500.0))
        assertEquals(1, c.clicsDeArco.size)
        c.pointerDown(Pt(600.0, 500.0))
        c.pointerMove(Pt(580.0, 560.0))
        c.pointerMove(Pt(500.0, 600.0))
        c.pointerUp(Pt(500.0, 600.0))
        val arco = c.scene.elements.single { it.type == ElementType.ARC }
        assertEquals(200.0, arco.width, 1e-6)
        assertEquals(0.0, arco.arcStart!!, 1e-6)
        assertEquals(PI / 2, arco.arcSweep!!, 1e-3)
        assertEquals(CierreArco.SECTOR, arco.cierre)
        assertTrue(c.clicsDeArco.isEmpty())
        c.undo()
        assertTrue(c.scene.elements.none { it.type == ElementType.ARC && !it.isDeleted })
    }

    @Test
    fun `el bote con degradado rellena degradado y liso lo quita`() {
        val c = DrawController()
        c.load(
            Scene(
                elements = listOf(
                    Element(id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0, width = 200.0, height = 100.0, seed = 1)
                )
            )
        )
        c.selectTool(Tool.RELLENO)
        c.cambiarEstilo(c.estiloActivo().copy(strokeColor = "#1971c2", degradado = TipoDegradado.RADIAL))
        toque(c, Pt(100.0, 50.0))
        val r = c.scene.elements.filter { !it.isDeleted }
        val pintado = r.firstOrNull { it.degradado != null }
        assertTrue("sin degradado: $r", pintado != null)
        assertEquals("#1971c2", pintado!!.degradadoDe!!.desde)
        c.cambiarEstilo(c.estiloActivo().copy(degradado = null))
        toque(c, Pt(100.0, 50.0))
        assertTrue(c.scene.elements.filter { !it.isDeleted }.none { it.degradado != null })
    }

    @Test
    fun `el arco por tres puntos pasa por los tres`() {
        val c = DrawController()
        c.selectTool(Tool.ARCO)
        c.cambiarEstilo(c.estiloActivo().copy(modoArco = ModoArco.TRES_PUNTOS))
        toque(c, Pt(400.0, 500.0))
        toque(c, Pt(500.0, 400.0))
        c.pointerDown(Pt(550.0, 450.0))
        c.pointerMove(Pt(580.0, 480.0))
        c.pointerUp(Pt(600.0, 500.0))
        val arco = c.scene.elements.single { it.type == ElementType.ARC }
        assertEquals(500.0, arco.x + arco.width / 2, 1e-6)
        assertEquals(500.0, arco.y + arco.height / 2, 1e-6)
        assertEquals(PI, kotlin.math.abs(arco.arcSweep!!), 1e-6)
    }
}
