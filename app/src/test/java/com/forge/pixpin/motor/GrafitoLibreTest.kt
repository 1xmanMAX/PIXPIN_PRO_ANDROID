package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El grafito es escritura libre** (23-sep-2026): pararse no lo convierte en raya, círculo ni
 * rectángulo, y cada muestra queda donde se puso. Y nace con la dureza elegida.
 */
class GrafitoLibreTest {

    @Test
    fun `pararse con el grafito no endereza nada y las muestras quedan tal cual`() {
        val c = DrawController().apply { selectTool(Tool.GRAFITO) }
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        var t = 0L
        val muestras = (1..20).map { Pt(it * 10.0, if (it % 2 == 0) 3.0 else -3.0) }
        for (p in muestras) { t += 20; c.pointerMove(p, cuando = t) }
        // Quieto mucho más de lo que antes bastaba para enderezar.
        assertFalse(c.latido(t + ESPERA_PARA_LA_RECTA * 4))
        assertFalse(c.enderezandoSolo || c.redondeandoSolo || c.rectangulandoSolo)
        c.pointerUp(muestras.last(), 1.0)
        val e = c.scene.elements.last()
        assertEquals(ElementType.FREEDRAW, e.type)
        assertEquals(MaterialDeTinta.CUADRITOS, e.material)
        val sitios = e.points!!.map { Pt(e.x + it.x, e.y + it.y) }
        for (p in muestras) assertTrue("se movió $p", sitios.any { Math.hypot(it.x - p.x, it.y - p.y) < 1e-9 })
    }

    @Test
    fun `el lapiz de siempre sigue enderezando al pararse`() {
        val c = DrawController().apply { selectTool(Tool.FREEDRAW) }
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        var t = 0L
        for (i in 1..20) { t += 20; c.pointerMove(Pt(i * 10.0, 1.0), cuando = t) }
        assertTrue(c.latido(t + ESPERA_PARA_LA_RECTA))
    }

    @Test
    fun `el grafito nace con la dureza elegida y el panel la ofrece`() {
        val c = DrawController().apply { selectTool(Tool.GRAFITO) }
        c.cambiarEstilo(c.scene.style.copy(dureza = DurezaDeGrafito.B4))
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        c.pointerMove(Pt(30.0, 30.0), cuando = 20L)
        c.pointerUp(Pt(30.0, 30.0), 1.0)
        val e = c.scene.elements.last()
        assertEquals(DurezaDeGrafito.B4, e.dureza)
        assertTrue(Propiedad.DUREZA in propiedadesPara(Tool.GRAFITO, emptyList()))
        assertTrue(Propiedad.DUREZA in propiedadesDe(e))
        assertFalse(Propiedad.DUREZA in propiedadesPara(Tool.FREEDRAW, emptyList()))
    }
}
