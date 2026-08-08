package com.forge.pixpin.motor

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las flechas siguen a lo que atan **mientras se mueve**, no al soltar.
 *
 * Era la queja: arrastras una caja y su flecha se queda clavada donde estaba
 * hasta que levantas el dedo, momento en el que pega un salto. Un esquema no se
 * puede reorganizar así — mientras mueves necesitas ver hacia dónde va quedando
 * la conexión.
 */
class ArrowFollowTest {

    /** Un cuadrado dibujado con la herramienta, para que nazca como en la app. */
    private fun DrawController.dibujaCaja(x: Double, y: Double, w: Double, h: Double): String {
        selectTool(Tool.RECTANGLE)
        pointerDown(Pt(x, y))
        pointerMove(Pt(x + w, y + h))
        pointerUp(Pt(x + w, y + h))
        return scene.visible.last().id
    }

    /**
     * Escena de partida: dos cajas unidas por una flecha, atada a las dos.
     *
     * Las cajas van **con relleno** a propósito. Una forma transparente no se
     * agarra por dentro —hay que cogerla por el borde—, y eso es correcto: si
     * no, un rectángulo grande y vacío se tragaría todos los toques de lo que
     * hay debajo. Pero para **anclar** una flecha sí cuenta el interior, que es
     * otra decisión distinta. Con relleno se puede arrastrar desde el centro,
     * que es lo que esta prueba necesita hacer.
     */
    private fun escenaConFlecha(): Triple<DrawController, String, String> {
        val c = DrawController()
        c.changeStyle({ it.copy(backgroundColor = "#a5d8ff") }, { it })
        val a = c.dibujaCaja(0.0, 0.0, 100.0, 100.0)
        val b = c.dibujaCaja(400.0, 0.0, 100.0, 100.0)
        c.deselect()

        c.selectTool(Tool.ARROW)
        c.pointerDown(Pt(50.0, 50.0))     // dentro de la primera
        c.pointerMove(Pt(450.0, 50.0))    // dentro de la segunda
        c.pointerUp(Pt(450.0, 50.0))

        val flecha = c.scene.visible.last()
        assertEquals("no se ató el origen", a, flecha.startBinding?.elementId)
        assertEquals("no se ató el destino", b, flecha.endBinding?.elementId)
        return Triple(c, a, b)
    }

    private fun DrawController.flecha(): Element =
        scene.visible.first { it.type == ElementType.ARROW }

    private fun puntaDe(e: Element): Pt = absolutePoints(e).last()

    // ---- Lo que faltaba ----

    @Test
    fun `la flecha se mueve en cada paso del arrastre, no solo al soltar`() {
        val (c, _, b) = escenaConFlecha()

        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf(b))
        c.pointerDown(Pt(450.0, 50.0))

        val antes = puntaDe(c.flecha())
        // Un solo paso del arrastre: la punta ya tiene que haberse movido.
        c.pointerMove(Pt(550.0, 250.0))
        val durante = puntaDe(c.flecha())

        assertTrue(
            "la punta no se movió durante el arrastre (${antes.x} → ${durante.x})",
            hypot(durante.x - antes.x, durante.y - antes.y) > 1.0
        )
    }

    /**
     * Y no da un salto al soltar: donde estaba en el último fotograma del
     * arrastre es donde se queda. El salto era el síntoma de recalcular solo al
     * final.
     */
    @Test
    fun `al soltar no da un salto`() {
        val (c, _, b) = escenaConFlecha()

        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf(b))
        c.pointerDown(Pt(450.0, 50.0))
        c.pointerMove(Pt(550.0, 250.0))
        val ultimoFotograma = puntaDe(c.flecha())
        c.pointerUp(Pt(550.0, 250.0))
        val alSoltar = puntaDe(c.flecha())

        assertEquals(ultimoFotograma.x, alSoltar.x, 0.5)
        assertEquals(ultimoFotograma.y, alSoltar.y, 0.5)
    }

    /** La punta acaba apuntando a donde se fue la caja, no a donde estaba. */
    @Test
    fun `la punta persigue a la caja`() {
        val (c, _, b) = escenaConFlecha()

        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf(b))
        c.pointerDown(Pt(450.0, 50.0))
        c.pointerMove(Pt(450.0, 500.0))
        c.pointerUp(Pt(450.0, 500.0))

        val caja = c.scene.byId(b)!!
        val punta = puntaDe(c.flecha())
        val centro = getElementAbsoluteCoords(caja)
        // La punta tiene que haber bajado con la caja.
        assertTrue(
            "la punta se quedó arriba (y=${punta.y}, caja en y=${centro.cy})",
            punta.y > 300.0
        )
    }

    /** Redimensionar también recoloca: la flecha se pega al borde nuevo. */
    @Test
    fun `redimensionar tambien recoloca la flecha`() {
        val (c, _, b) = escenaConFlecha()
        val antes = puntaDe(c.flecha())

        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf(b))
        val tirador = getSelectionTransformHandles(listOf(c.scene.byId(b)!!), 1.0)
            .first { it.type == HandleType.NW }
        c.pointerDown(Pt(tirador.centerX, tirador.centerY))
        c.pointerMove(Pt(300.0, 30.0))
        c.pointerUp(Pt(300.0, 30.0))

        val despues = puntaDe(c.flecha())
        assertTrue(
            "la flecha no siguió al borde nuevo",
            hypot(despues.x - antes.x, despues.y - antes.y) > 1.0
        )
    }

    /** Mover la caja del ORIGEN mueve la cola, no solo la punta. */
    @Test
    fun `mover el origen recoloca la cola`() {
        val (c, a, _) = escenaConFlecha()
        val antes = absolutePoints(c.flecha()).first()

        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf(a))
        c.pointerDown(Pt(50.0, 50.0))
        c.pointerMove(Pt(50.0, 400.0))
        c.pointerUp(Pt(50.0, 400.0))

        val despues = absolutePoints(c.flecha()).first()
        assertTrue("la cola no siguió", despues.y - antes.y > 100.0)
    }
}
