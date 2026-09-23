package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El grafito es escritura libre** (23-sep-2026): escribiendo, pararse no convierte la letra y
 * cada muestra queda donde se puso; pero los gestos siguen, con más espera y solo en trazos
 * grandes. Y nace con la dureza elegida.
 */
class GrafitoLibreTest {

    @Test
    fun `escribiendo con grafito, pararse no convierte la letra y las muestras quedan tal cual`() {
        val c = DrawController().apply { selectTool(Tool.GRAFITO) }
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        var t = 0L
        // Una letra: cabe en 40 px de pantalla.
        val muestras = (1..12).map { Pt(it * 3.0, if (it % 2 == 0) 20.0 else 0.0) }
        for (p in muestras) { t += 20; c.pointerMove(p, cuando = t) }
        // Ni con el medio segundo del lápiz, ni con más de un segundo.
        assertFalse(c.latido(t + ESPERA_PARA_LA_RECTA))
        assertFalse(c.latido(t + ESPERA_DEL_GRAFITO * 2))
        assertFalse(c.enderezandoSolo || c.redondeandoSolo || c.rectangulandoSolo)
        c.pointerUp(muestras.last(), 1.0)
        val e = c.scene.elements.last()
        assertEquals(ElementType.FREEDRAW, e.type)
        assertEquals(MaterialDeTinta.CUADRITOS, e.material)
        val sitios = e.points!!.map { Pt(e.x + it.x, e.y + it.y) }
        for (p in muestras) assertTrue("se movió $p", sitios.any { Math.hypot(it.x - p.x, it.y - p.y) < 1e-9 })
    }

    @Test
    fun `con grafito, una raya larga y un segundo quieto sale recta de grafito`() {
        val c = DrawController().apply { selectTool(Tool.GRAFITO) }
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        var t = 0L
        for (i in 1..20) { t += 20; c.pointerMove(Pt(i * 15.0, if (i % 2 == 0) 2.0 else -2.0), cuando = t) }
        // Medio segundo no basta con el grafito…
        assertFalse(c.latido(t + ESPERA_PARA_LA_RECTA))
        // …un segundo sí.
        assertTrue(c.latido(t + ESPERA_DEL_GRAFITO))
        assertTrue(c.enderezandoSolo)
        c.pointerUp(Pt(300.0, 0.0), 1.0)
        val e = c.scene.elements.last()
        assertEquals(ElementType.LINE, e.type)
        assertEquals(MaterialDeTinta.CUADRITOS, e.material)
    }

    @Test
    fun `con grafito, clavar la punta y esperar abre el compas`() {
        val c = DrawController().apply { selectTool(Tool.GRAFITO) }
        c.pointerDown(Pt(100.0, 100.0), cuando = 0L)
        c.pointerMove(Pt(101.0, 100.0), cuando = 16L)
        assertTrue(c.latido(16L + ESPERA_DEL_GRAFITO))
        assertTrue(c.redondeandoSolo)
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
