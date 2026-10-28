package com.forge.pixpin.sincro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El modo prioritario mientras se graba** (28-oct-2026, §11.6): grabando alguien del grupo no
 * arranca ninguna vuelta; al acabar, el chat de la grabación primero y lo demás ya.
 */
class ModoPrioritarioTest {

    @Test
    fun grabando_alguien_no_arranca_ninguna_vuelta() {
        val m = ModoPrioritario()
        assertNull("ni tareas, ni la entera, ni personas", m.queToca(true, tareas = true, vuelta = true, personas = true))
        // Ni siquiera la grabación ya guardada de antes: la cámara va primero.
        m.grabacionGuardada("obra")
        assertNull(m.queToca(true, tareas = true, vuelta = true, personas = true))
        // Caso negativo: sin nadie grabando, sí.
        assertEquals(Baliza.Trabajo.ARCHIVOS_DE_LA_GRABACION, m.queToca(false, tareas = true, vuelta = true, personas = true))
    }

    @Test
    fun al_acabar_el_chat_de_la_grabacion_primero_y_luego_lo_demas() {
        val m = ModoPrioritario()
        assertFalse(m.mirar(true))
        assertTrue("acaba de acabar", m.mirar(false))
        assertFalse("y solo una vez", m.mirar(false))
        m.grabacionGuardada("general")
        m.grabacionGuardada("obra")
        assertEquals(Baliza.Trabajo.ARCHIVOS_DE_LA_GRABACION, m.queToca(false, tareas = true, vuelta = true, personas = true))
        assertEquals(setOf("general", "obra"), m.tomarGrabadas())
        assertFalse(m.hayGrabadas())
        assertEquals(Baliza.Trabajo.TAREAS, m.queToca(false, tareas = true, vuelta = true, personas = true))
        assertEquals(Baliza.Trabajo.VUELTA_ENTERA, m.queToca(false, tareas = false, vuelta = true, personas = true))
    }

    @Test
    fun si_no_sale_vuelve_a_esperar_primera() {
        val m = ModoPrioritario()
        m.grabacionGuardada("obra")
        val chats = m.tomarGrabadas()
        // Se cortó (o el otro estaba ocupado): nunca se da por hecha sin acabar bien.
        m.noSalieron(chats)
        assertTrue(m.hayGrabadas())
        assertEquals(Baliza.Trabajo.ARCHIVOS_DE_LA_GRABACION, m.queToca(false, tareas = true, vuelta = true, personas = true))
    }

    @Test
    fun al_acabar_de_grabar_lo_aplazado_sale_ya_sin_esperar_al_periodo() {
        val r = RelojDeSincro()
        val tel = setOf("pc")
        assertEquals(RelojDeSincro.Motivo.APARECIO, r.toca(0, tel, null)?.first)
        assertNull("lejos del periodo", r.toca(60_000, tel, null))
        r.acaboDeGrabar()
        assertEquals(RelojDeSincro.Motivo.TRAS_GRABAR to tel, r.toca(61_000, tel, null))
        assertNull("y una sola vez", r.toca(62_000, tel, null))
        // Caso negativo: sin nadie que conteste no hay nada que sacar, y espera a que aparezca.
        val solo = RelojDeSincro()
        solo.acaboDeGrabar()
        assertNull(solo.toca(0, emptySet(), null))
        // «Apareció» va solo con el nuevo: lo de tras grabar sigue para todos.
        val dos = RelojDeSincro()
        dos.toca(0, setOf("pc"), null)
        dos.acaboDeGrabar()
        assertEquals(RelojDeSincro.Motivo.APARECIO to setOf("tablet"), dos.toca(1_000, setOf("pc", "tablet"), null))
        assertEquals(RelojDeSincro.Motivo.TRAS_GRABAR to setOf("pc", "tablet"), dos.toca(2_000, setOf("pc", "tablet"), null))
    }
}
