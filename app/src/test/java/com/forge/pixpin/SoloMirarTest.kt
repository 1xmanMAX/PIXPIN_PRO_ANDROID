package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El modo visualización no altera el lienzo.**
 *
 * Lo pidió el usuario con estas palabras (18-sep-2026): «el modo visualización es para que no se
 * altere el canvas, no importa cuántas veces lo mueva». Lo que pasaba era que el modo vivía solo
 * en la pantalla —se le ponía la mano al controlador que mandaba— y en la tira hay **un
 * controlador por lienzo**: los vecinos seguían con su herramienta, así que arrastrar encima de
 * uno le movía lo que hubiera debajo del dedo.
 *
 * Aquí se comprueba lo que arregla eso: con [DrawController.soloMirar] puesto, **da igual la
 * herramienta**. Nada de lo que haga el dedo cambia la escena, y lo que hace es encuadrar.
 */
class SoloMirarTest {

    /** Un lienzo con una raya de (0,0) a (100,0), y el dedo justo encima de ella. */
    private fun conUnaRaya(herramienta: Tool): DrawController {
        val c = DrawController()
        c.selectTool(Tool.LINE)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(100.0, 0.0))
        c.pointerUp(Pt(100.0, 0.0))
        c.setSelection(emptySet())
        c.selectTool(herramienta)
        return c
    }

    /** Arrastra el dedo por encima de la raya, como lo hace el lienzo. */
    private fun arrastrarPorLaRaya(c: DrawController) {
        c.pointerDown(c.scene.viewport.toScene(50.0, 0.0))
        c.pointerMove(c.scene.viewport.toScene(90.0, 40.0))
        c.pointerMove(c.scene.viewport.toScene(130.0, 80.0))
        c.pointerUp(c.scene.viewport.toScene(130.0, 80.0))
    }

    /**
     * **La prueba de fondo**: con cualquier herramienta de las que tocan el dibujo, mirando no
     * cambia ni un elemento. Se recorren todas las que el usuario puede dejarse puestas al entrar
     * en el modo visualización — es justo el caso que fallaba: el vecino de la tira se quedaba
     * con la suya.
     */
    @Test
    fun `mirando, ninguna herramienta altera el dibujo`() {
        for (herramienta in listOf(
            Tool.SELECTION, Tool.FREEDRAW, Tool.ERASER, Tool.RECTANGLE, Tool.LINE, Tool.LASSO
        )) {
            val c = conUnaRaya(herramienta)
            val antes = c.scene.elements
            c.soloMirar = true

            arrastrarPorLaRaya(c)

            assertEquals("con $herramienta", antes, c.scene.elements)
            assertTrue("con $herramienta no debería marcar nada", c.selectedIds.isEmpty())
        }
    }

    /** Y lo que sí hace el dedo es mover el papel: el punto que había debajo se viene con él. */
    @Test
    fun `mirando, el dedo encuadra`() {
        val c = conUnaRaya(Tool.SELECTION)
        c.soloMirar = true
        val bajoElDedo = c.scene.viewport.toScene(50.0, 0.0)

        arrastrarPorLaRaya(c)

        val donde = c.scene.viewport.toScreen(bajoElDedo)
        assertEquals(130.0, donde.x, 0.01)
        assertEquals(80.0, donde.y, 0.01)
    }

    /**
     * **Ni por mucho que se repita**: «no importa cuántas veces lo mueva». Diez arrastres
     * seguidos por encima de la raya y la escena sigue siendo la misma.
     */
    @Test
    fun `mirando, diez arrastres seguidos no tocan nada`() {
        val c = conUnaRaya(Tool.SELECTION)
        c.soloMirar = true
        val antes = c.scene.elements

        repeat(10) { arrastrarPorLaRaya(c) }

        assertEquals(antes, c.scene.elements)
    }

    /** Aguantar el dedo tampoco marca lo clavado: mirando, un dedo quieto es un dedo quieto. */
    @Test
    fun `mirando, la pulsacion larga no rescata lo clavado`() {
        val c = conUnaRaya(Tool.SELECTION)
        c.setSelection(setOf(c.scene.visible.single().id))
        c.clavarSeleccion()
        c.setSelection(emptySet())
        c.soloMirar = true

        assertTrue(!c.marcarClavadoEn(Pt(50.0, 0.0), 10.0))
        assertTrue(c.selectedIds.isEmpty())
    }

    /** Y al volver a editar, todo sigue funcionando: la llave abre y cierra. */
    @Test
    fun `al salir de mirar se vuelve a dibujar`() {
        val c = conUnaRaya(Tool.FREEDRAW)
        c.soloMirar = true
        arrastrarPorLaRaya(c)
        val mirando = c.scene.visible.size

        c.soloMirar = false
        arrastrarPorLaRaya(c)

        assertEquals(1, mirando)
        assertEquals(2, c.scene.visible.size)
    }
}
