package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Seguir a la canción o no** (29-oct-2026): la misma prueba que `mover_la_letra_a_mano_para_un_rato_el_seguir_a_la_cancion` del PC, y cuánto falta. */
class SeguirLaLetraTest {

    @Test
    fun mover_la_letra_a_mano_para_un_rato_el_seguir_a_la_cancion() {
        val t = 1_000_000L
        assertTrue(SeguirLaLetra.sigue(null, t, true))
        assertTrue(SeguirLaLetra.sigue(null, t, false))
        // Recién movida: ni sonando la sigue.
        assertFalse(SeguirLaLetra.sigue(t, t, true))
        assertFalse(SeguirLaLetra.sigue(t, t + SeguirLaLetra.A_MANO_MS - 1, true))
        // Pasado el rato, sonando, vuelve a seguirla.
        assertTrue(SeguirLaLetra.sigue(t, t + SeguirLaLetra.A_MANO_MS, true))
        // Parada, lo movido a mano se queda donde se dejó.
        assertFalse(SeguirLaLetra.sigue(t, t + SeguirLaLetra.A_MANO_MS * 10, false))
    }

    @Test
    fun cuanto_falta_para_volver_a_seguir() {
        val t = 50_000L
        assertEquals(0L, SeguirLaLetra.falta(null, t, true))
        assertEquals(0L, SeguirLaLetra.falta(null, t, false))
        assertEquals(6_000L, SeguirLaLetra.falta(t, t, true))
        assertEquals(1_500L, SeguirLaLetra.falta(t, t + 4_500, true))
        assertEquals(0L, SeguirLaLetra.falta(t, t + 60_000, true))
        // Parada no vuelve sola.
        assertNull(SeguirLaLetra.falta(t, t + 60_000, false))
    }
}
