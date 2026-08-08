package com.forge.pixpin.motor

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La herramienta de mano.
 *
 * Estas pruebas reproducen **el bucle** que hacía temblar el lienzo: el lienzo
 * convierte el dedo a coordenadas de escena con el viewport de cada fotograma,
 * y el viewport es justo lo que el gesto está cambiando. Por eso aquí no se le
 * pasan puntos de escena fijos al controlador —eso ocultaría el fallo— sino que
 * se hace la conversión igual que la hace `DrawCanvas`, leyendo el viewport
 * actualizado en cada paso.
 */
class PanTest {

    /** Arrastra el dedo por la pantalla, convirtiendo como lo hace el lienzo. */
    private fun arrastrar(
        c: DrawController,
        desde: Pair<Double, Double>,
        pasos: List<Pair<Double, Double>>
    ) {
        c.pointerDown(c.scene.viewport.toScene(desde.first, desde.second))
        for ((x, y) in pasos) {
            c.pointerMove(c.scene.viewport.toScene(x, y))
        }
        val ultimo = pasos.lastOrNull() ?: desde
        c.pointerUp(c.scene.viewport.toScene(ultimo.first, ultimo.second))
    }

    private fun controlador(zoom: Double = 1.0): DrawController {
        val c = DrawController()
        c.selectTool(Tool.HAND)
        c.setViewport(Viewport(zoom = zoom))
        return c
    }

    /**
     * Lo esencial: el punto del dibujo que había bajo el dedo sigue ahí al
     * acabar. Es la definición de encuadrar.
     */
    @Test
    fun `el punto bajo el dedo se queda bajo el dedo`() {
        val c = controlador()
        val inicioPantalla = 300.0 to 400.0
        val puntoBajoElDedo = c.scene.viewport.toScene(inicioPantalla.first, inicioPantalla.second)

        arrastrar(c, inicioPantalla, listOf(340.0 to 420.0, 400.0 to 460.0, 500.0 to 520.0))

        // Ese mismo punto de escena tiene que caer ahora bajo (500, 520).
        val donde = c.scene.viewport.toScreen(puntoBajoElDedo)
        assertEquals(500.0, donde.x, 0.01)
        assertEquals(520.0, donde.y, 0.01)
    }

    /**
     * El fallo del temblor, medido.
     *
     * Con el dedo quieto el lienzo no puede moverse ni un píxel. Antes, cada
     * fotograma sin movimiento seguía cambiando el desplazamiento porque la
     * referencia se recalculaba con el viewport ya modificado: el lienzo
     * oscilaba solo.
     */
    @Test
    fun `con el dedo quieto el lienzo no se mueve`() {
        val c = controlador()
        c.pointerDown(c.scene.viewport.toScene(300.0, 400.0))
        repeat(30) { c.pointerMove(c.scene.viewport.toScene(300.0, 400.0)) }

        assertEquals(0.0, c.scene.viewport.scrollX, 0.001)
        assertEquals(0.0, c.scene.viewport.scrollY, 0.001)
    }

    /**
     * Y avanzando de píxel en píxel, el lienzo avanza igual: ni se pasa, ni se
     * queda corto, ni da tirones. Un tirón sería un paso que no mide lo mismo
     * que los demás.
     */
    @Test
    fun `arrastrar suave da un movimiento suave`() {
        val c = controlador()
        c.pointerDown(c.scene.viewport.toScene(100.0, 100.0))

        val saltos = mutableListOf<Double>()
        var anterior = c.scene.viewport.scrollX
        for (i in 1..40) {
            c.pointerMove(c.scene.viewport.toScene(100.0 + i, 100.0))
            saltos += c.scene.viewport.scrollX - anterior
            anterior = c.scene.viewport.scrollX
        }

        for ((i, s) in saltos.withIndex()) {
            assertTrue("paso $i mide $s en vez de 1", abs(s - 1.0) < 0.001)
        }
    }

    /** Con zoom, un dedo que recorre 100 px de pantalla mueve 100 px de pantalla. */
    @Test
    fun `el encuadre respeta el zoom`() {
        for (zoom in listOf(0.5, 1.0, 2.5, 8.0)) {
            val c = controlador(zoom)
            val bajoElDedo = c.scene.viewport.toScene(200.0, 200.0)
            arrastrar(c, 200.0 to 200.0, listOf(250.0 to 230.0, 300.0 to 260.0))

            val donde = c.scene.viewport.toScreen(bajoElDedo)
            assertEquals("zoom $zoom", 300.0, donde.x, 0.01)
            assertEquals("zoom $zoom", 260.0, donde.y, 0.01)
        }
    }

    /** Encuadrar no toca el dibujo: ni crea, ni mueve, ni selecciona nada. */
    @Test
    fun `la mano no toca el dibujo`() {
        val c = controlador()
        val antes = c.scene.elements
        arrastrar(c, 100.0 to 100.0, listOf(200.0 to 200.0))
        assertEquals(antes, c.scene.elements)
        assertTrue(c.selectedIds.isEmpty())
    }
}
