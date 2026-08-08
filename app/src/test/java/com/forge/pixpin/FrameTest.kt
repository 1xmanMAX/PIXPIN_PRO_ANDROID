package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La hoja.
 *
 * La idea, en las palabras del usuario: tienes un papel lleno de notas y
 * quieres más sitio, así que lo estiras un poco para que quepan las siguientes.
 * En un lienzo infinito eso se traduce en un marco que decide **qué trozo es la
 * hoja**: dentro del editor se sigue viendo y dibujando todo, pero el pin y la
 * exportación enseñan solo lo de dentro.
 */
class FrameTest {

    private fun caja(id: String, x: Double, y: Double, w: Double, h: Double) = Element(
        id = id, type = ElementType.RECTANGLE, x = x, y = y, width = w, height = h, seed = 1
    )

    private fun marco(x: Double, y: Double, w: Double, h: Double) = Element(
        id = "hoja", type = ElementType.FRAME, x = x, y = y, width = w, height = h, seed = 1
    )

    // ---- Qué es la hoja ----

    @Test
    fun `sin marco no hay hoja y se ve todo`() {
        val s = Scene(elements = listOf(caja("a", 0.0, 0.0, 10.0, 10.0)))
        assertNull(s.marco)
        assertEquals(1, s.contenidoVisible.size)
    }

    @Test
    fun `el marco es el primero de la escena`() {
        val s = Scene(elements = listOf(caja("a", 0.0, 0.0, 10.0, 10.0), marco(0.0, 0.0, 100.0, 100.0)))
        assertEquals("hoja", s.marco?.id)
    }

    // ---- Qué entra en el pin ----

    @Test
    fun `solo entra lo que cae dentro de la hoja`() {
        val s = Scene(
            elements = listOf(
                marco(0.0, 0.0, 100.0, 100.0),
                caja("dentro", 10.0, 10.0, 30.0, 30.0),
                caja("fuera", 500.0, 500.0, 30.0, 30.0)
            )
        )
        val ids = s.contenidoVisible.map { it.id }
        assertTrue("lo de dentro tiene que estar", "dentro" in ids)
        assertTrue("lo de fuera no", "fuera" !in ids)
    }

    /** Lo que asoma por el borde cuenta: se recorta al encuadrar, no se tira. */
    @Test
    fun `lo que asoma por el borde sigue contando`() {
        val s = Scene(
            elements = listOf(marco(0.0, 0.0, 100.0, 100.0), caja("medio", 80.0, 80.0, 60.0, 60.0))
        )
        assertTrue("medio" in s.contenidoVisible.map { it.id })
    }

    /**
     * **La hoja no se dibuja a sí misma.** Es un límite, no una raya: si saliera
     * en el pin, cada dibujo llevaría un recuadro gris alrededor.
     */
    @Test
    fun `el marco no forma parte del contenido`() {
        val s = Scene(
            elements = listOf(marco(0.0, 0.0, 100.0, 100.0), caja("a", 10.0, 10.0, 10.0, 10.0))
        )
        assertTrue(s.contenidoVisible.none { it.isFrame })
    }

    @Test
    fun `lo borrado no entra`() {
        val s = Scene(
            elements = listOf(
                marco(0.0, 0.0, 100.0, 100.0),
                caja("a", 10.0, 10.0, 10.0, 10.0).copy(isDeleted = true)
            )
        )
        assertTrue(s.contenidoVisible.isEmpty())
    }

    // ---- Estirar la hoja ----

    /**
     * Lo que pedía el usuario: más sitio **sin mover lo que ya hay**. Estirar el
     * marco por una esquina no toca ni una nota, solo hace entrar más.
     */
    @Test
    fun `estirar la hoja no mueve nada y hace entrar mas`() {
        val notas = listOf(
            caja("a", 10.0, 10.0, 20.0, 20.0),
            caja("b", 200.0, 200.0, 20.0, 20.0)
        )
        val antes = Scene(elements = listOf(marco(0.0, 0.0, 100.0, 100.0)) + notas)
        assertEquals(listOf("a"), antes.contenidoVisible.map { it.id })

        // La misma escena con el marco estirado: las notas están intactas.
        val despues = Scene(elements = listOf(marco(0.0, 0.0, 400.0, 400.0)) + notas)
        assertEquals(listOf("a", "b"), despues.contenidoVisible.map { it.id })
        assertEquals(
            antes.elements.filter { !it.isFrame },
            despues.elements.filter { !it.isFrame }
        )
    }

    // ---- La herramienta ----

    @Test
    fun `la hoja se dibuja arrastrando, como un rectangulo`() {
        val c = DrawController()
        c.selectTool(Tool.FRAME)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(300.0, 200.0))
        c.pointerUp(Pt(300.0, 200.0))

        val m = c.scene.marco
        assertEquals(300.0, m?.width ?: 0.0, 0.01)
        assertEquals(200.0, m?.height ?: 0.0, 0.01)
    }

    /** La hoja no tiene estilo que tocar: no es un dibujo. */
    @Test
    fun `la hoja no ofrece ajustes de estilo`() {
        assertTrue(propiedadesPara(Tool.FRAME, emptyList()).isEmpty())
    }

    /**
     * **No se agarra por dentro.** Si se pudiera, la hoja quedaría por encima de
     * todo al picar y no habría forma de tocar una nota que esté dentro.
     */
    @Test
    fun `la hoja se coge por el borde y no por dentro`() {
        val m = marco(0.0, 0.0, 200.0, 200.0)
        assertTrue("por dentro no", !isPointInElement(Pt(100.0, 100.0), m))
        assertTrue("por el borde sí", isPointOnElementOutline(Pt(0.0, 100.0), m, 5.0))
    }
}
