package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La bolita de seleccionar**: se pasa por encima y va cogiendo. Ver [Tool.BOLITA].
 */
class BolitaTest {

    private fun conDosRayas(): Pair<DrawController, List<Element>> {
        val c = DrawController()
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(0.0, 0.0)); c.pointerMove(Pt(40.0, 40.0)); c.pointerUp(Pt(40.0, 40.0))
        c.pointerDown(Pt(300.0, 0.0)); c.pointerMove(Pt(340.0, 40.0)); c.pointerUp(Pt(340.0, 40.0))
        c.deselect()
        return c to c.scene.visible
    }

    @Test
    fun `pasar la bolita por algo lo coge`() {
        val (c, figuras) = conDosRayas()
        c.selectTool(Tool.BOLITA)
        c.pointerDown(Pt(20.0, 20.0))
        assertEquals(setOf(figuras[0].id), c.selectedIds)
    }

    @Test
    fun `un barrido coge todo lo que toca y nada mas`() {
        val (c, figuras) = conDosRayas()
        c.selectTool(Tool.BOLITA)
        c.pointerDown(Pt(20.0, 20.0))
        // Se barre hasta la segunda, pasando por el hueco de en medio.
        for (x in 20..320 step 10) c.pointerMove(Pt(x.toDouble(), 20.0))
        c.pointerUp(Pt(320.0, 20.0))
        assertEquals(figuras.map { it.id }.toSet(), c.selectedIds)
    }

    @Test
    fun `volver a pasar por algo que ya estaba dentro lo saca`() {
        val (c, figuras) = conDosRayas()
        c.selectTool(Tool.BOLITA)
        c.pointerDown(Pt(20.0, 20.0))
        c.pointerUp(Pt(20.0, 20.0))
        assertEquals(setOf(figuras[0].id), c.selectedIds)

        // Segundo barrido por lo mismo: sale.
        c.pointerDown(Pt(20.0, 20.0))
        c.pointerUp(Pt(20.0, 20.0))
        assertTrue(c.selectedIds.isEmpty())
    }

    @Test
    fun `el dedo parado encima no la enciende y la apaga a cada fotograma`() {
        val (c, figuras) = conDosRayas()
        c.selectTool(Tool.BOLITA)
        c.pointerDown(Pt(20.0, 20.0))
        repeat(30) { c.pointerMove(Pt(20.0, 20.0)) }
        assertEquals(setOf(figuras[0].id), c.selectedIds)
    }

    @Test
    fun `lo elegido se queda al levantar el dedo, la bolita no`() {
        val (c, figuras) = conDosRayas()
        c.selectTool(Tool.BOLITA)
        c.pointerDown(Pt(20.0, 20.0))
        assertTrue(c.bolita != null)
        c.pointerUp(Pt(20.0, 20.0))
        assertNull(c.bolita)
        assertEquals(setOf(figuras[0].id), c.selectedIds)
    }

    @Test
    fun `tocar una de un grupo las coge todas`() {
        val (c, figuras) = conDosRayas()
        c.setSelection(figuras.map { it.id }.toSet())
        c.group()
        c.deselect()

        c.selectTool(Tool.BOLITA)
        c.pointerDown(Pt(20.0, 20.0))
        assertEquals(figuras.map { it.id }.toSet(), c.selectedIds)
    }
}
