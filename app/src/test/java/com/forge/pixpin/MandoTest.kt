package com.forge.pixpin.motor

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El mando de lo elegido.** Ver [Mando].
 *
 * Lo que se comprueba son las cuentas: qué se agarra al tocar cada sitio del disco, y qué le
 * pasa a lo elegido con cada gesto. Lo que se pinta no se comprueba aquí.
 */
class MandoTest {

    private val lado = 176f

    // Los mismos números que el mando: el disco no está centrado en su cuadro.
    private val centro = Offset(lado * 0.44f, lado * 0.56f)
    private val radio = lado * 0.32f

    // ---------------------------------------------------------------------
    // Qué se agarra
    // ---------------------------------------------------------------------

    @Test
    fun `el hueco del disco mueve libre`() {
        // Entre las dos flechas: abajo a la izquierda no hay ninguna.
        val donde = centro + Offset(-radio * 0.5f, radio * 0.5f)
        assertEquals(AgarreDelMando.LIBRE, queSeHaAgarrado(donde, lado))
    }

    @Test
    fun `sobre la raya de la x se agarra la x`() {
        assertEquals(
            AgarreDelMando.FLECHA_X,
            queSeHaAgarrado(centro + Offset(radio * 0.4f, 0f), lado)
        )
    }

    @Test
    fun `sobre la raya de la y se agarra la y`() {
        assertEquals(
            AgarreDelMando.FLECHA_Y,
            queSeHaAgarrado(centro + Offset(0f, -radio * 0.4f), lado)
        )
    }

    @Test
    fun `el borde del disco gira`() {
        // Abajo, donde no hay flechas ni cajitas: solo el aro.
        assertEquals(
            AgarreDelMando.GIRAR,
            queSeHaAgarrado(centro + Offset(0f, radio * 0.95f), lado)
        )
    }

    @Test
    fun `fuera del mando no se agarra nada`() {
        assertEquals(null, queSeHaAgarrado(Offset(lado * 0.98f, lado * 0.98f), lado))
    }

    // ---------------------------------------------------------------------
    // Qué hace cada gesto
    // ---------------------------------------------------------------------

    private fun conUnaCaja(): Pair<DrawController, Element> {
        val c = DrawController()
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(0.0, 0.0)); c.pointerMove(Pt(100.0, 50.0)); c.pointerUp(Pt(100.0, 50.0))
        val e = c.scene.visible.last()
        c.setSelection(setOf(e.id))
        return c to e
    }

    @Test
    fun `la flecha mueve por su eje y por ninguno mas`() {
        val (c, e) = conUnaCaja()
        c.empezarAManejar()
        c.moverLaSeleccionPorElEje(enHorizontal = true, cuanto = 30.0, zoom = 1.0)
        c.terminarDeManejar()
        val despues = c.scene.byId(e.id)!!
        assertEquals(e.x + 30.0, despues.x, 1e-6)
        assertEquals(e.y, despues.y, 1e-6)
    }

    @Test
    fun `el dedo mueve lo que se ve que mueve, no unidades de la escena`() {
        val (c, e) = conUnaCaja()
        c.empezarAManejar()
        // Al doble de aumento, cien píxeles de dedo son cincuenta de dibujo.
        c.moverLaSeleccion(100.0, 0.0, zoom = 2.0)
        c.terminarDeManejar()
        assertEquals(e.x + 50.0, c.scene.byId(e.id)!!.x, 1e-6)
    }

    @Test
    fun `escalar deja el centro donde estaba`() {
        val (c, e) = conUnaCaja()
        val antes = getElementAbsoluteCoords(e)
        c.empezarAManejar()
        c.escalarLaSeleccion(2.0)
        c.terminarDeManejar()
        val despues = getElementAbsoluteCoords(c.scene.byId(e.id)!!)
        assertEquals(antes.cx, despues.cx, 1e-6)
        assertEquals(antes.cy, despues.cy, 1e-6)
        assertEquals(e.width * 2, c.scene.byId(e.id)!!.width, 1e-6)
    }

    @Test
    fun `estirar toca cada lado por su cuenta`() {
        val (c, e) = conUnaCaja()
        c.empezarAManejar()
        c.deformarLaSeleccion(aLoAncho = 2.0, aLoAlto = 1.0)
        c.terminarDeManejar()
        val despues = c.scene.byId(e.id)!!
        assertEquals(e.width * 2, despues.width, 1e-6)
        assertEquals(e.height, despues.height, 1e-6)
    }

    @Test
    fun `girar cambia el angulo`() {
        val (c, e) = conUnaCaja()
        c.empezarAManejar()
        c.girarLaSeleccion(Math.PI / 4)
        c.terminarDeManejar()
        assertNotEquals(e.angle, c.scene.byId(e.id)!!.angle, 1e-6)
    }

    @Test
    fun `todo el arrastre entra en un solo deshacer`() {
        val (c, e) = conUnaCaja()
        c.empezarAManejar()
        // Doscientas muestras, como un arrastre de dos segundos.
        repeat(200) { c.moverLaSeleccion(1.0, 0.0, 1.0) }
        c.terminarDeManejar()
        assertEquals(e.x + 200.0, c.scene.byId(e.id)!!.x, 1e-6)

        // Un solo deshacer tiene que devolverlo entero a donde estaba.
        c.undo()
        assertEquals(e.x, c.scene.byId(e.id)!!.x, 1e-6)
    }

    @Test
    fun `un arrastre que no mueve nada no ensucia el historial`() {
        val (c, e) = conUnaCaja()
        c.empezarAManejar()
        c.terminarDeManejar()
        c.undo()
        // Lo último anotado es la creación de la caja: deshacer se la lleva.
        assertTrue(c.scene.visible.none { it.id == e.id })
    }
}
