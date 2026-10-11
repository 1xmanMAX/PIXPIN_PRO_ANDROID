package com.forge.pixpin

import com.forge.pixpin.motor.DrawController
import com.forge.pixpin.motor.ItemStyle
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.Tool
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Anotar un plano (PC 11bd1f6): el grosor y la letra que se eligen en el panel van a la escala de
 * lo que se ve —al nacer algo y al cambiarle el tamaño a lo marcado—, no con el valor de una hoja.
 */
class EscalaDeLoNuevoTest {
    private fun rectangulo(c: DrawController) {
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(0.0, 0.0)); c.pointerMove(Pt(10.0, 6.0)); c.pointerUp(Pt(10.0, 6.0))
    }

    @Test fun `lo nuevo nace a la escala de lo que se ve`() {
        val c = DrawController(Scene())
        c.escalaDeLoNuevo = 0.01
        rectangulo(c)
        val e = c.scene.elements.last()
        assertEquals(c.scene.style.strokeWidth * 0.01, e.strokeWidth, 1e-12)
        // El pincel no cambia: el panel sigue enseñando su grosor.
        assertEquals(ItemStyle().strokeWidth, c.scene.style.strokeWidth, 1e-12)
    }

    @Test fun `elegir otro grosor con algo marcado tambien va a escala, y el panel lo ve en su medida`() {
        val c = DrawController(Scene())
        c.escalaDeLoNuevo = 0.01
        rectangulo(c)
        val id = c.scene.elements.last().id
        c.selectAll()
        // El panel ve el grosor de lo marcado en su medida, no en la del dibujo.
        assertEquals(ItemStyle().strokeWidth, c.estiloActivo().strokeWidth, 1e-9)
        c.cambiarEstilo(c.estiloActivo().copy(strokeWidth = 8.0))
        assertEquals(0.08, c.scene.elements.first { it.id == id }.strokeWidth, 1e-9)
    }

    @Test fun `fuera del plano no cambia nada`() {
        val c = DrawController(Scene())
        rectangulo(c)
        assertEquals(c.scene.style.strokeWidth, c.scene.elements.last().strokeWidth, 1e-12)
        // Caso negativo: una escala imposible se queda en 1.
        c.escalaDeLoNuevo = Double.NaN
        assertEquals(1.0, c.escalaDeLoNuevo, 0.0)
    }
}
