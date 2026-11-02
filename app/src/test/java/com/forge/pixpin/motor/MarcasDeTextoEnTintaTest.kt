package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Las marcas de texto hechas tinta** (2-nov-2026): las formas de `marcas_texto.rs` del PC (§3.9). */
class MarcasDeTextoEnTintaTest {
    private val caja = MarcasDeTextoEnTinta.Caja(10.0, 20.0, 110.0, 40.0)

    @Test
    fun `resaltar y tapar son un rectangulo con aire relleno y sin borde`() {
        val r = MarcasDeTextoEnTinta.elementos("resaltar", "#F5C218", listOf(caja), "marca-texto-5", 1).single()
        assertEquals(ElementType.RECTANGLE, r.type)
        assertEquals(10.0 - 2.4, r.x, 1e-9)
        assertEquals(20.0 - 2.4, r.y, 1e-9)
        assertEquals(100.0 + 4.8, r.width, 1e-9)
        assertEquals(40, r.opacity)
        assertEquals("#F5C218", r.backgroundColor)
        assertEquals(Element.TRANSPARENT, r.strokeColor)
        assertEquals(listOf("marca-texto-5"), r.groupIds)
        assertEquals(0, r.roughness)
        assertEquals(100, MarcasDeTextoEnTinta.elementos("tapar", "#F47286", listOf(caja), "g", 1).single().opacity)
    }

    @Test
    fun `subrayar tachar y ondulada son rayas en su sitio`() {
        val g = 20.0 * 0.09
        val s = MarcasDeTextoEnTinta.elementos("subrayar", "#5BA4F5", listOf(caja), "g", 1).single()
        assertEquals(ElementType.LINE, s.type)
        assertEquals(40.0 + g, s.y, 1e-9)
        assertEquals(g, s.strokeWidth, 1e-9)
        assertEquals(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)), s.points)
        val t = MarcasDeTextoEnTinta.elementos("tachar", "#5BA4F5", listOf(caja), "g", 1).single()
        assertEquals(20.0 + 20.0 * 0.55, t.y, 1e-9)
        val o = MarcasDeTextoEnTinta.elementos("ondulada", "#5BA4F5", listOf(caja), "g", 1).single()
        assertTrue(o.points!!.size > 10)
        assertEquals(g * 0.8, o.strokeWidth, 1e-9)
        assertEquals(2 * g, o.height, 1e-6)
    }

    @Test
    fun `caso negativo cajas vacias no dan nada y quitar se lleva el grupo entero`() {
        assertTrue(MarcasDeTextoEnTinta.elementos("resaltar", "#F5C218", listOf(MarcasDeTextoEnTinta.Caja(5.0, 5.0, 5.0, 9.0)), "g", 1).isEmpty())
        val dos = MarcasDeTextoEnTinta.elementos("subrayar", "#F5C218", listOf(caja, MarcasDeTextoEnTinta.Caja(10.0, 50.0, 60.0, 70.0)), "marca-texto-9", 1)
        val grupos = MarcasDeTextoEnTinta.gruposQueTocan(dos, listOf(MarcasDeTextoEnTinta.Caja(20.0, 40.0, 30.0, 45.0)))
        assertEquals(setOf("marca-texto-9"), grupos)
        assertTrue(MarcasDeTextoEnTinta.gruposQueTocan(dos, listOf(MarcasDeTextoEnTinta.Caja(500.0, 500.0, 510.0, 510.0))).isEmpty())
    }
}
