package com.forge.pixpin

import com.forge.pixpin.motor.Bounds
import com.forge.pixpin.motor.DrawController
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.Hojita
import com.forge.pixpin.motor.ItemStyle
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.Tool
import com.forge.pixpin.motor.figuraQueSeRellenaSola
import com.forge.pixpin.motor.newElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El bote dentro de una sola figura cerrada la rellena a ella, exacta; entre varias, la rejilla. */
class RellenoExactoTest {
    private val estilo = ItemStyle()
    private fun caja(id: String, x: Double, y: Double, w: Double, h: Double, tipo: ElementType = ElementType.RECTANGLE) =
        newElement(tipo, x, y, estilo, w, h).copy(id = id)

    @Test
    fun `dentro de una figura sola se rellena ella, y de varias la más pequeña que contiene el toque`() {
        val grande = caja("grande", 0.0, 0.0, 400.0, 300.0)
        val elipse = caja("elipse", 500.0, 0.0, 200.0, 100.0, ElementType.ELLIPSE)
        assertEquals("grande", figuraQueSeRellenaSola(listOf(grande, elipse), Pt(200.0, 150.0))?.id)
        assertEquals("elipse", figuraQueSeRellenaSola(listOf(grande, elipse), Pt(600.0, 50.0))?.id)
        // Fuera de todo, o en la esquina del rectángulo que envuelve a la elipse pero fuera de ella.
        assertNull(figuraQueSeRellenaSola(listOf(grande, elipse), Pt(450.0, 150.0)))
        assertNull(figuraQueSeRellenaSola(listOf(elipse), Pt(503.0, 3.0)))
    }

    @Test
    fun `si algo se mete en la figura decide la rejilla`() {
        val grande = caja("grande", 0.0, 0.0, 400.0, 300.0)
        val dentro = caja("dentro", 50.0, 50.0, 60.0, 60.0)
        val raya = newElement(ElementType.LINE, 200.0, -50.0, estilo, 0.0, 400.0)
            .copy(id = "raya", points = listOf(Pt(0.0, 0.0), Pt(0.0, 400.0)))
        // Un cuadro dentro: tocar fuera de él ya no es «la figura entera». Dentro de él, sí es él.
        assertNull(figuraQueSeRellenaSola(listOf(grande, dentro), Pt(300.0, 200.0)))
        assertEquals("dentro", figuraQueSeRellenaSola(listOf(grande, dentro), Pt(80.0, 80.0))?.id)
        // Una raya que la parte en dos.
        assertNull(figuraQueSeRellenaSola(listOf(grande, raya), Pt(100.0, 150.0)))
    }

    @Test
    fun `el bote pone el fondo de la figura y no crea una mancha aparte`() {
        val c = DrawController(Scene(elements = listOf(caja("caja", 0.0, 0.0, 200.0, 100.0))))
        c.selectTool(Tool.RELLENO)
        c.pointerDown(Pt(100.0, 50.0)); c.pointerUp(Pt(100.0, 50.0))
        assertEquals(1, c.scene.elements.size)
        val fondo = c.scene.elements.single().backgroundColor
        assertTrue("sigue transparente: $fondo", fondo != Element.TRANSPARENT)
    }

    @Test
    fun `la hojita entra como un papel con lo anotado encima, agrupado y en su sitio`() {
        val trazo = caja("t", 30.0, 20.0, 50.0, 10.0)
        val borrado = caja("b", 0.0, 0.0, 5.0, 5.0).copy(isDeleted = true)
        val piezas = Hojita.paraInsertar(listOf(trazo, borrado), Bounds(10.0, 10.0, 310.0, 210.0), "#fff3a3", Pt(1000.0, 500.0), estilo)
        assertEquals(2, piezas.size)
        val hoja = piezas.first()
        assertEquals(ElementType.RECTANGLE, hoja.type)
        assertEquals("#fff3a3", hoja.backgroundColor)
        assertEquals(300.0, hoja.width, 1e-9); assertEquals(200.0, hoja.height, 1e-9)
        assertEquals(1000.0, hoja.x, 1e-9)
        // Lo anotado conserva su sitio respecto del papel, con id nuevo, y va en el mismo grupo.
        val encima = piezas[1]
        assertEquals(1020.0, encima.x, 1e-9); assertEquals(510.0, encima.y, 1e-9)
        assertTrue(encima.id != "t")
        assertNotNull(hoja.groupIds.singleOrNull())
        assertEquals(hoja.groupIds.single(), encima.groupIds.last())
    }
}
