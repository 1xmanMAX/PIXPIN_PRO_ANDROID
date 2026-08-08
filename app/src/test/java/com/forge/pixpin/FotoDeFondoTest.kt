package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La foto sobre la que se anota **no se toca**.
 *
 * Un pin de imagen pinta su foto él mismo, por debajo del dibujo. Si al abrirlo
 * en la edición avanzada esa misma foto entra como un elemento más y se puede
 * arrastrar, pasan dos cosas y las dos malas: al volver al pin se ven **las dos
 * imágenes** —la que pinta el pin y la que se movió—, y todo lo anotado deja de
 * caer sobre aquello sobre lo que se anotó, que era lo único que le daba
 * sentido.
 *
 * Lo que se puede hacer con ella es dibujar encima y, si hace falta más sitio,
 * estirar la hoja.
 */
class FotoDeFondoTest {

    private fun escenaConFoto(bloqueada: Boolean = false): Scene {
        val foto = Element(
            id = "foto", type = ElementType.IMAGE, x = 0.0, y = 0.0,
            width = 400.0, height = 300.0, seed = 1, fileId = "f", locked = bloqueada
        )
        val trazo = Element(
            id = "t", type = ElementType.LINE, x = 100.0, y = 100.0,
            width = 100.0, height = 0.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0))
        )
        return Scene(elements = listOf(trazo, foto))
    }

    @Test
    fun `fijarla la bloquea y la manda al fondo`() {
        val c = DrawController(escenaConFoto())
        c.fijarAlFondo(setOf("foto"))

        assertEquals("no ha ido al fondo", "foto", c.scene.elements.first().id)
        assertTrue("no está bloqueada", c.scene.elements.first().locked)
    }

    /** Bloqueada de verdad: no se coge ni tocándola. */
    @Test
    fun `una vez fijada no se puede seleccionar`() {
        val c = DrawController(escenaConFoto())
        c.fijarAlFondo(setOf("foto"))
        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(300.0, 250.0))
        c.pointerUp(Pt(300.0, 250.0))

        assertTrue("se ha cogido la foto", c.selectedIds.isEmpty())
    }

    /** Ni se arrastra: es lo que hacía aparecer dos imágenes en el pin. */
    @Test
    fun `una vez fijada no se puede mover`() {
        val c = DrawController(escenaConFoto())
        c.fijarAlFondo(setOf("foto"))
        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(300.0, 250.0))
        c.pointerMove(Pt(340.0, 280.0))
        c.pointerUp(Pt(340.0, 280.0))

        val foto = c.scene.byId("foto")!!
        assertEquals(0.0, foto.x, 0.001)
        assertEquals(0.0, foto.y, 0.001)
    }

    /** Ni se la lleva el borrador al pasar por encima. */
    @Test
    fun `el borrador no se lleva la foto`() {
        val c = DrawController(escenaConFoto())
        c.fijarAlFondo(setOf("foto"))
        c.selectTool(Tool.ERASER)
        c.pointerDown(Pt(300.0, 250.0))
        c.pointerUp(Pt(300.0, 250.0))

        assertTrue(c.scene.visible.any { it.id == "foto" })
    }

    /** Ni la coge «seleccionar todo», que es la otra puerta de atrás. */
    @Test
    fun `seleccionar todo no coge la foto`() {
        val c = DrawController(escenaConFoto())
        c.fijarAlFondo(setOf("foto"))
        c.selectAll()
        assertEquals(setOf("t"), c.selectedIds)
    }

    /** Pero encima de ella se sigue dibujando con normalidad. */
    @Test
    fun `encima de la foto se dibuja igual`() {
        val c = DrawController(escenaConFoto())
        c.fijarAlFondo(setOf("foto"))
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(50.0, 50.0))
        c.pointerMove(Pt(150.0, 120.0))
        c.pointerUp(Pt(150.0, 120.0))

        assertTrue(c.scene.visible.any { it.type == ElementType.RECTANGLE })
        // Y lo dibujado va **encima** de la foto, no debajo.
        assertTrue(
            c.scene.elements.indexOfFirst { it.type == ElementType.RECTANGLE } >
                c.scene.elements.indexOfFirst { it.id == "foto" }
        )
    }

    /** Y la hoja se puede seguir estirando, que es la vía para tener más sitio. */
    @Test
    fun `la hoja se puede poner encima de la foto`() {
        val c = DrawController(escenaConFoto())
        c.fijarAlFondo(setOf("foto"))
        c.selectTool(Tool.FRAME)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(500.0, 400.0))
        c.pointerUp(Pt(500.0, 400.0))

        val marco = c.scene.marco
        assertTrue("no ha quedado hoja", marco != null)
        assertEquals(500.0, marco!!.width, 0.001)
    }

    /**
     * Fijarla **no entra en el historial**: no es una edición del dibujo sino
     * cómo está montada la escena, y deshacer hasta desbloquear la foto sería
     * deshacer algo que nadie hizo.
     */
    @Test
    fun `fijarla no se puede deshacer`() {
        val c = DrawController(escenaConFoto())
        c.fijarAlFondo(setOf("foto"))
        assertTrue(!c.canUndo)
    }

    /** Fijar lo que ya estaba fijado no descoloca nada. */
    @Test
    fun `fijar dos veces es lo mismo que fijar una`() {
        val c = DrawController(escenaConFoto(bloqueada = true))
        val antes = c.scene.elements.map { it.id }
        c.fijarAlFondo(setOf("foto"))
        c.fijarAlFondo(setOf("foto"))
        assertEquals(antes.size, c.scene.elements.size)
        assertEquals("foto", c.scene.elements.first().id)
    }
}
