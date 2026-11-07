package com.forge.pixpin.planos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaDeLasCotasTest {
    private fun p(x: Double, y: Double) = doubleArrayOf(x, y)

    @Test
    fun un_rectangulo_cerrado_da_su_area_y_su_centro() {
        val c = listOf(p(0.0, 0.0), p(4.0, 0.0), p(4.0, 3.0), p(0.0, 3.0), p(0.0, 0.0))
        assertTrue(Medidas.cerrada(c))
        val (a, centro) = Medidas.areaYCentro(c)
        assertEquals(12.0, a, 1e-9)
        assertEquals(2.0, centro[0], 1e-9)
        assertEquals(1.5, centro[1], 1e-9)
        assertEquals("12.000 m²", Medidas.area(a, 6))
    }

    @Test
    fun en_coordenadas_grandes_y_al_reves_tambien() {
        val o = 500_000.0
        val c = listOf(p(o, o), p(o, o + 10.0), p(o + 10.0, o + 10.0), p(o + 10.0, o), p(o, o))
        val (a, centro) = Medidas.areaYCentro(c)
        assertEquals(100.0, a, 1e-6)
        assertEquals(o + 5.0, centro[0], 1e-6)
    }

    @Test
    fun abierta_o_con_dos_tramos_no_es_cerrada() {
        assertFalse(Medidas.cerrada(listOf(p(0.0, 0.0), p(1.0, 0.0), p(1.0, 1.0))))
        assertFalse(Medidas.cerrada(listOf(p(0.0, 0.0), p(1.0, 0.0), p(0.0, 0.0))))
    }
}
