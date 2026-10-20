package com.forge.pixpin

import com.forge.pixpin.audio.Volumen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los casos de `pixpin-audio/src/volumen.rs` del PC (20-oct-2026). */
class VolumenTest {
    private fun seno(amplitud: Float, n: Int) = FloatArray(n) { amplitud * kotlin.math.sin(it * 0.05f) }

    @Test
    fun `lo bajito sube hasta el objetivo y lo fuerte no`() {
        val g = Volumen.gananciaPara(seno(0.1f, 48_000))
        assertEquals(8.9f, g, 0.3f)
        assertTrue(Volumen.gananciaPara(seno(0.8f, 48_000)) < Volumen.GANANCIA_MINIMA)
    }

    @Test
    fun `un chasquido suelto no frena el realce`() {
        val v = seno(0.05f, 100_000); v[500] = 1f
        assertTrue(Volumen.gananciaPara(v) > 10f)
    }

    @Test
    fun `el silencio no se sube y hay tope`() {
        assertEquals(1f, Volumen.gananciaPara(FloatArray(1000)), 0f)
        assertEquals(Volumen.GANANCIA_MAXIMA, Volumen.gananciaPara(seno(0.01f, 48_000)), 0f)
    }

    @Test
    fun `el limitador no deja saturar`() {
        val a = Volumen.aplicar(0.5f, 4f)
        assertTrue(a < 1f && a > Volumen.UMBRAL)
        val b = Volumen.aplicar(-0.5f, 4f)
        assertTrue(b > -1f && b < -Volumen.UMBRAL)
        assertEquals(0.2f, Volumen.aplicar(0.05f, 4f), 1e-6f)
        assertEquals(2408, Volumen.aMilibelios(16f))
    }
}
