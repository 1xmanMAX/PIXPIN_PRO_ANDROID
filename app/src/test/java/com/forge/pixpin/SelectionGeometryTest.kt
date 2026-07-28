package com.forge.pixpin.capture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionGeometryTest {

    private val screen = Rect(0f, 0f, 1000f, 2000f)

    /** El bug clásico: arrastrar hacia arriba/izquierda tiene que encoger, no crecer. */
    @Test
    fun `selección nueva en cualquier dirección`() {
        val start = Offset(500f, 900f)
        val mode = SelectionGeometry.classifyDrag(start, null, 3f)
        val anchor = SelectionGeometry.anchorFor(mode, null, start)

        val hastaArribaIzquierda = SelectionGeometry.update(
            mode, Rect(start, start), anchor, Offset(200f, 400f), Offset.Zero, screen
        )
        assertEquals(Rect(200f, 400f, 500f, 900f), hastaArribaIzquierda)

        val hastaAbajoDerecha = SelectionGeometry.update(
            mode, Rect(start, start), anchor, Offset(800f, 1500f), Offset.Zero, screen
        )
        assertEquals(Rect(500f, 900f, 800f, 1500f), hastaAbajoDerecha)
    }

    @Test
    fun `arrastrar la esquina superior izquierda ancla la inferior derecha`() {
        val sel = Rect(100f, 100f, 400f, 400f)
        val mode = SelectionGeometry.classifyDrag(Offset(102f, 104f), sel, 3f)
        assertEquals(DragMode.TL, mode)

        val anchor = SelectionGeometry.anchorFor(mode, sel, Offset(102f, 104f))
        assertEquals(Offset(400f, 400f), anchor)

        val moved = SelectionGeometry.update(mode, sel, anchor, Offset(50f, 60f), Offset.Zero, screen)
        assertEquals(Rect(50f, 60f, 400f, 400f), moved)
    }

    @Test
    fun `mover no se sale de la pantalla`() {
        val sel = Rect(0f, 0f, 300f, 300f)
        val moved = SelectionGeometry.update(
            DragMode.MOVE, sel, Offset.Zero, Offset.Zero, Offset(-100f, -100f), screen
        )
        assertEquals(Rect(0f, 0f, 300f, 300f), moved)
    }

    @Test
    fun `tocar dentro mueve y fuera crea`() {
        val sel = Rect(100f, 100f, 400f, 400f)
        assertEquals(DragMode.MOVE, SelectionGeometry.classifyDrag(Offset(250f, 250f), sel, 3f))
        assertEquals(DragMode.NEW, SelectionGeometry.classifyDrag(Offset(900f, 1800f), sel, 3f))
    }

    @Test
    fun `la selección nunca queda degenerada`() {
        val start = Offset(500f, 500f)
        val tiny = SelectionGeometry.update(
            DragMode.NEW, Rect(start, start), start, Offset(502f, 501f), Offset.Zero, screen
        )
        assertEquals(24f, tiny.width)
        assertEquals(24f, tiny.height)
    }
}
