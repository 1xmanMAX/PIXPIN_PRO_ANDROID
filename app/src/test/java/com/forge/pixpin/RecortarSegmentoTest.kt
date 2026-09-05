package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El recorte de segmentos del pintado a mucho aumento.
 *
 * De esto depende que al rasterizador no le lleguen coordenadas enormes: si el
 * recorte deja pasar un extremo lejano, la app vuelve a reventar a mucho zoom;
 * si corta de más, el plano se ve a trozos. Geometría pura, sin pantalla.
 */
class RecortarSegmentoTest {

    private val fuera = DoubleArray(4)

    private fun recorta(
        ax: Double, ay: Double, bx: Double, by: Double
    ): DoubleArray? =
        if (recortarSegmento(ax, ay, bx, by, 0.0, 0.0, 100.0, 100.0, fuera)) {
            fuera.copyOf()
        } else null

    @Test
    fun `el segmento entero dentro sale tal cual`() {
        val r = recorta(10.0, 10.0, 90.0, 90.0)!!
        assertEquals(10.0, r[0], 1e-9); assertEquals(10.0, r[1], 1e-9)
        assertEquals(90.0, r[2], 1e-9); assertEquals(90.0, r[3], 1e-9)
    }

    @Test
    fun `el que cruza de punta a punta queda acotado al rectangulo`() {
        // Es el caso por el que existe esto: una cadena del plano que cruza la
        // pantalla con los extremos a cientos de miles de píxeles.
        val r = recorta(-1e6, 50.0, 1e6, 50.0)!!
        assertEquals(0.0, r[0], 1e-9); assertEquals(50.0, r[1], 1e-9)
        assertEquals(100.0, r[2], 1e-9); assertEquals(50.0, r[3], 1e-9)
    }

    @Test
    fun `el que entra por un lado se corta solo por ese lado`() {
        val r = recorta(-100.0, 0.0, 50.0, 75.0)!!
        assertEquals(0.0, r[0], 1e-9); assertEquals(50.0, r[1], 1e-9)
        assertEquals(50.0, r[2], 1e-9); assertEquals(75.0, r[3], 1e-9)
    }

    @Test
    fun `el que pasa de largo no entra`() {
        assertFalse(recortarSegmento(-10.0, -10.0, 200.0, -10.0, 0.0, 0.0, 100.0, 100.0, fuera))
        assertFalse(recortarSegmento(150.0, -50.0, 250.0, 300.0, 0.0, 0.0, 100.0, 100.0, fuera))
    }

    @Test
    fun `la diagonal que roza la esquina entra por la esquina`() {
        val r = recorta(-50.0, 50.0, 50.0, -50.0)!!
        // Cruza justo por (0, 0): el trozo dentro es un punto.
        assertEquals(r[0], r[2], 1e-9)
        assertEquals(r[1], r[3], 1e-9)
        assertEquals(0.0, r[0], 1e-9)
    }

    @Test
    fun `el vertical y el horizontal exactos no se pierden`() {
        val v = recorta(30.0, -1e6, 30.0, 1e6)!!
        assertEquals(30.0, v[0], 1e-9); assertEquals(0.0, v[1], 1e-9)
        assertEquals(30.0, v[2], 1e-9); assertEquals(100.0, v[3], 1e-9)
        assertTrue(recortarSegmento(0.0, 40.0, 100.0, 40.0, 0.0, 0.0, 100.0, 100.0, fuera))
    }
}
