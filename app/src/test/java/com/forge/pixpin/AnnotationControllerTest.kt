package com.forge.pixpin.annotate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Regresión del lápiz óptico: un toque seco —un punto, una tilde, la barra
     * de una «t»— no llegaba a dos muestras y se descartaba entero.
     */
    @Test
    fun `un toque sin arrastrar deja un punto dibujado`() {
        val c = AnnotationController()
        c.tool.value = AnnotationType.PENCIL
        c.begin(Pt(30f, 40f))
        c.end()
        assertEquals(1, c.annotations.size)
        assertEquals(1, c.annotations[0].points.size)
    }

    @Test
    fun `el trazo vivo no ensucia la lista hasta soltar`() {
        val c = AnnotationController()
        c.tool.value = AnnotationType.PENCIL
        c.begin(Pt(0f, 0f))
        c.drag(Pt(5f, 5f))
        assertEquals("aún no está commiteado", 0, c.annotations.size)
        assertEquals("pero ya se está dibujando", 2, c.liveStroke.size)
        c.end()
        assertEquals(1, c.annotations.size)
        assertEquals("el búfer queda libre", 0, c.liveStroke.size)
    }

    @Test
    fun `cancelar descarta el trazo en curso`() {
        val c = AnnotationController()
        c.tool.value = AnnotationType.PENCIL
        c.begin(Pt(0f, 0f))
        c.drag(Pt(5f, 5f))
        c.cancel()
        c.end()
        assertEquals(0, c.annotations.size)
        assertTrue(c.liveStroke.isEmpty)
    }

    @Test
    fun `la presion del lapiz viaja hasta la anotacion`() {
        val c = AnnotationController()
        c.tool.value = AnnotationType.PENCIL
        c.begin(Pt(0f, 0f, 0.2f))
        c.drag(Pt(5f, 5f, 0.9f))
        c.end()
        assertEquals(0.2f, c.annotations[0].points[0].p, 0.001f)
        assertEquals(0.9f, c.annotations[0].points[1].p, 0.001f)
    }

    @Test
    fun `borrar sin tocar nada no gasta un paso de deshacer`() {
        val c = AnnotationController()
        c.tool.value = AnnotationType.ERASER
        c.begin(Pt(900f, 900f))
        c.drag(Pt(950f, 950f))
        c.end()
        assertFalse(c.canUndo.value)
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

    // ---- Herramientas nuevas ----

    @Test
    fun `el numero de serie va contando toque a toque`() {
        val c = AnnotationController()
        c.selectTool(AnnotationType.SERIAL)
        listOf(Pt(10f, 10f), Pt(50f, 10f), Pt(90f, 10f)).forEach {
            c.begin(it)
            c.end()
        }
        assertEquals(listOf("1", "2", "3"), c.annotations.map { it.text })
    }

    @Test
    fun `al deshacer un numero la cuenta vuelve atras`() {
        val c = AnnotationController()
        c.selectTool(AnnotationType.SERIAL)
        c.begin(Pt(10f, 10f)); c.end()
        c.begin(Pt(50f, 10f)); c.end()
        c.undo()
        c.begin(Pt(90f, 10f)); c.end()
        assertEquals("el hueco se reutiliza", listOf("1", "2"), c.annotations.map { it.text })
    }

    @Test
    fun `la polilinea encadena vertices y no se cierra sola`() {
        val c = AnnotationController()
        c.selectTool(AnnotationType.POLYLINE)
        c.begin(Pt(0f, 0f)); c.end()
        c.begin(Pt(50f, 0f)); c.end()
        c.begin(Pt(50f, 50f)); c.end()
        assertEquals("sigue abierta", 0, c.annotations.size)
        assertTrue(c.polylineOpen)

        c.finishPolyline()
        assertEquals(1, c.annotations.size)
        // Tres vértices; el punto de previsualización no se guarda.
        assertEquals(3, c.annotations[0].points.size)
        assertFalse(c.polylineOpen)
    }

    @Test
    fun `arrastrar solo mueve la previsualizacion de la polilinea`() {
        val c = AnnotationController()
        c.selectTool(AnnotationType.POLYLINE)
        c.begin(Pt(0f, 0f))
        c.drag(Pt(80f, 80f))
        c.end()
        c.finishPolyline()
        assertEquals(1, c.annotations.size)
        assertEquals(Pt(0f, 0f), c.annotations[0].points[0])
        assertEquals(Pt(80f, 80f), c.annotations[0].points[1])
    }

    @Test
    fun `cambiar de herramienta cierra la polilinea abierta`() {
        val c = AnnotationController()
        c.selectTool(AnnotationType.POLYLINE)
        c.begin(Pt(0f, 0f)); c.end()
        c.begin(Pt(30f, 30f)); c.end()
        c.selectTool(AnnotationType.PENCIL)
        assertEquals("no se pierde lo trazado", 1, c.annotations.size)
        assertFalse(c.polylineOpen)
    }

    @Test
    fun `una polilinea de un solo vertice se descarta`() {
        val c = AnnotationController()
        c.selectTool(AnnotationType.POLYLINE)
        c.begin(Pt(0f, 0f))
        c.end()
        c.finishPolyline()
        assertEquals(0, c.annotations.size)
    }

    @Test
    fun `el foco se dibuja como un rectangulo`() {
        val c = AnnotationController()
        c.selectTool(AnnotationType.SPOTLIGHT)
        c.begin(Pt(10f, 10f))
        c.drag(Pt(200f, 150f))
        c.end()
        assertEquals(1, c.annotations.size)
        assertEquals(AnnotationType.SPOTLIGHT, c.annotations[0].type)
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
