package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las tres figuras de la barra (plano, recta, espacio) no se estropean de refilón.** El borrador
 * no las quita y un arrastre rápido no las mueve: hay que dejar el dedo quieto encima. Lo pidió el
 * usuario el 13-sep-2026. Un rectángulo sigue comportándose como siempre.
 */
class FigurasProtegidasTest {

    private fun plano() = Element(
        id = "plano", type = ElementType.PLANO, x = 0.0, y = 0.0, width = 400.0, height = 400.0, seed = 1,
        unidad = 40.0, pasoDeNumeros = 1.0, pasoDeCuadros = 1.0
    )

    private fun caja() = Element(id = "caja", type = ElementType.RECTANGLE, x = 0.0, y = 0.0, width = 400.0, height = 400.0, seed = 1)

    private fun con(e: Element) = DrawController().apply {
        insertar(listOf(e))
        selectTool(Tool.SELECTION)
    }

    private fun DrawController.xDe(id: String) = scene.elements.first { it.id == id }.x

    @Test
    fun `el borrador no se lleva un plano`() {
        val c = con(plano())
        c.selectTool(Tool.ERASER)
        c.pointerDown(Pt(200.0, 200.0), cuando = 1000L)
        c.pointerMove(Pt(210.0, 210.0), cuando = 1010L)
        c.pointerUp(Pt(210.0, 210.0))
        assertFalse(c.scene.elements.first { it.id == "plano" }.isDeleted)
        assertTrue("es un instrumento", plano().esInstrumento)
        assertFalse("un rectángulo no", caja().esInstrumento)
    }

    @Test
    fun `arrastrar un plano sin esperar no lo mueve`() {
        val c = con(plano())
        c.pointerDown(Pt(200.0, 200.0), cuando = 1000L)
        c.pointerMove(Pt(260.0, 200.0), cuando = 1050L)
        c.pointerMove(Pt(320.0, 200.0), cuando = 1100L)
        c.pointerUp(Pt(320.0, 200.0))
        assertEquals(0.0, c.xDe("plano"), 0.001)
    }

    @Test
    fun `dejando el dedo quieto un momento el plano si se mueve`() {
        val c = con(plano())
        c.pointerDown(Pt(200.0, 200.0), cuando = 1000L)
        c.pointerMove(Pt(260.0, 200.0), cuando = 1000L + ESPERA_PARA_MOVER_UN_INSTRUMENTO + 10)
        c.pointerUp(Pt(260.0, 200.0))
        assertEquals(60.0, c.xDe("plano"), 0.001)
    }

    @Test
    fun `un rectangulo se arrastra como siempre`() {
        val c = con(caja())
        // En el borde: un rectángulo sin relleno se coge por su línea.
        c.pointerDown(Pt(1.0, 200.0), cuando = 1000L)
        c.pointerMove(Pt(61.0, 200.0), cuando = 1050L)
        c.pointerUp(Pt(61.0, 200.0))
        assertEquals(60.0, c.xDe("caja"), 0.001)
    }
}

/** **La herramienta Zona y la marca que deja al mandarla al chat.** Ver [Tool.ZONA]. */
class ZonaTest {

    @Test
    fun `arrastrar con la zona entrega el rectangulo al soltar`() {
        val c = DrawController()
        var soltada: Bounds? = null
        c.alSoltarLaZona = { soltada = it }
        c.selectTool(Tool.ZONA)
        c.pointerDown(Pt(10.0, 20.0), cuando = 1L)
        c.pointerMove(Pt(110.0, 90.0), cuando = 2L)
        assertEquals("se ve el rectángulo mientras se arrastra", Bounds(10.0, 20.0, 110.0, 90.0), c.selectionBox)
        c.pointerUp(Pt(110.0, 90.0))
        assertEquals(Bounds(10.0, 20.0, 110.0, 90.0), soltada)
        assertEquals(null, c.selectionBox)
        assertTrue("no dibuja nada", c.scene.elements.isEmpty())
    }

    @Test
    fun `un toque con la zona no cuenta`() {
        val c = DrawController()
        var soltada: Bounds? = null
        c.alSoltarLaZona = { soltada = it }
        c.selectTool(Tool.ZONA)
        c.pointerDown(Pt(10.0, 20.0), cuando = 1L)
        c.pointerUp(Pt(12.0, 21.0))
        assertEquals(null, soltada)
    }

    @Test
    fun `la marca de la zona no se borra y su icono abre el sublienzo`() {
        val c = DrawController()
        c.marcarZona(Bounds(0.0, 0.0, 200.0, 100.0), "foto-123")
        val marca = c.scene.elements.single()
        assertEquals("foto-123", marca.enlace)
        assertEquals(StrokeStyle.DASHED, marca.strokeStyle)

        c.selectTool(Tool.ERASER)
        c.pointerDown(Pt(0.0, 50.0), cuando = 1L)
        c.pointerUp(Pt(0.0, 50.0))
        assertFalse(c.scene.elements.single().isDeleted)

        var abierto: String? = null
        c.alTocarEnlace = { abierto = it }
        c.selectTool(Tool.FREEDRAW)
        // El icono va en la esquina de arriba a la derecha.
        c.pointerDown(Pt(200.0, 0.0), cuando = 1L)
        c.pointerUp(Pt(200.0, 0.0))
        assertEquals("foto-123", abierto)
        assertEquals("tocar el icono no dibuja", 1, c.scene.elements.size)
    }
}

/** **Las copias de la Zona**: foto y marco juntos, y se arrastran sin salir de la herramienta. */
class CopiaDeZonaTest {
    @Test
    fun `la copia lleva marco, va agrupada y se mueve con la zona puesta`() {
        val c = DrawController()
        c.selectTool(Tool.ZONA)
        c.ponerCopiaDeZona(SceneFile("f1", "image/png", "/x.png", 0L), Bounds(0.0, 0.0, 100.0, 50.0), 10.0, "#1e1e1e")
        assertEquals(2, c.scene.elements.size)
        assertEquals("sigue la Zona", Tool.ZONA, c.tool)
        val grupos = c.scene.elements.map { it.groupIds.single() }.toSet()
        assertEquals(1, grupos.size)
        assertTrue(grupos.single().startsWith(GRUPO_DE_ZONA))
        // Arrastrar desde dentro de la copia la mueve, no abre otra zona.
        c.pointerDown(Pt(50.0, 30.0), cuando = 1L)
        c.pointerMove(Pt(150.0, 30.0), cuando = 2L)
        c.pointerUp(Pt(150.0, 30.0))
        assertTrue(c.scene.elements.all { it.x == 110.0 })
    }

    @Test
    fun `sin color de marco la copia es solo la foto, que ya lo trae pintado`() {
        val c = DrawController()
        c.selectTool(Tool.ZONA)
        c.ponerCopiaDeZona(SceneFile("f1", "image/png", "/x.png", 0L), Bounds(0.0, 0.0, 100.0, 50.0), 10.0, null)
        assertEquals(1, c.scene.elements.size)
        assertTrue(c.scene.elements.single().groupIds.single().startsWith(GRUPO_DE_ZONA))
        assertEquals(setOf(c.scene.elements.single().id), c.selectedIds)
    }
}
