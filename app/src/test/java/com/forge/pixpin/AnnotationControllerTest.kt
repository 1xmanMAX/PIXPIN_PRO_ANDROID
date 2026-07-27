package com.forge.pixpin.annotate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationControllerTest {

    @Test
    fun `trazo de lapiz se commitea al soltar`() {
        val c = AnnotationController()
        c.tool.value = AnnotationType.PENCIL
        c.begin(Pt(0f, 0f))
        c.drag(Pt(10f, 10f))
        c.drag(Pt(20f, 5f))
        c.end()
        assertEquals(1, c.annotations.size)
        assertEquals(3, c.annotations[0].points.size)
        assertTrue(c.canUndo.value)
    }

    @Test
    fun `rectangulo diminuto se descarta`() {
        val c = AnnotationController()
        c.tool.value = AnnotationType.RECT
        c.begin(Pt(0f, 0f))
        c.drag(Pt(2f, 2f))
        c.end()
        assertEquals(0, c.annotations.size)
    }

    @Test
    fun `borrador elimina la anotacion tocada`() {
        val c = AnnotationController()
        c.tool.value = AnnotationType.RECT
        c.begin(Pt(0f, 0f))
        c.drag(Pt(100f, 100f))
        c.end()
        assertEquals(1, c.annotations.size)

        c.tool.value = AnnotationType.ERASER
        c.begin(Pt(-10f, 50f))
        c.drag(Pt(50f, 50f))
        c.end()
        assertEquals(0, c.annotations.size)
    }

    @Test
    fun `undo tras borrar restaura`() {
        val c = AnnotationController()
        c.tool.value = AnnotationType.RECT
        c.begin(Pt(0f, 0f))
        c.drag(Pt(100f, 100f))
        c.end()

        c.tool.value = AnnotationType.ERASER
        c.begin(Pt(0f, 0f))
        c.drag(Pt(50f, 50f))
        c.end()
        assertEquals(0, c.annotations.size)

        c.undo()
        assertEquals(1, c.annotations.size)
    }

    @Test
    fun `resaltador lleva alpha y trazo grueso`() {
        val c = AnnotationController()
        c.tool.value = AnnotationType.HIGHLIGHT
        c.begin(Pt(0f, 0f))
        c.drag(Pt(50f, 0f))
        c.end()
        val a = c.annotations[0]
        val alpha = (a.color ushr 24) and 0xFF
        assertTrue(alpha in 80..100)
        assertEquals(c.strokeWidth.value * 4, a.strokeWidth, 0.001f)
    }

    @Test
    fun `texto en blanco no se añade`() {
        val c = AnnotationController()
        c.addText(Pt(10f, 10f), "   ")
        assertEquals(0, c.annotations.size)
        c.addText(Pt(10f, 10f), "hola")
        assertEquals(1, c.annotations.size)
    }
}
