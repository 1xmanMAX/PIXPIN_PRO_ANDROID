package com.forge.pixpin

import com.forge.pixpin.audio.RealceDeVolumen
import com.forge.pixpin.audio.RealceDeVolumen.GANANCIA_MAXIMA
import com.forge.pixpin.audio.RealceDeVolumen.GANANCIA_MINIMA
import com.forge.pixpin.audio.RealceDeVolumen.UMBRAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * **El realce al oír** (24-oct-2026): las mismas pruebas que `volumen.rs` del PC (commit cd0cc30),
 * más la de los milibelios que pide `LoudnessEnhancer` y la del medidor a trozos, que es como lo
 * usa Android al descodificar.
 */
class RealceDeVolumenTest {

    private fun seno(amplitud: Float, n: Int) = FloatArray(n) { amplitud * sin(it * 0.05f) }

    @Test
    fun lo_bajito_sube_hasta_el_objetivo_y_lo_fuerte_no() {
        val g = RealceDeVolumen.gananciaPara(seno(0.1f, 48_000))
        assertTrue("$g", abs(g - 8.9f) < 0.3f)
        val fuerte = RealceDeVolumen.gananciaPara(seno(0.8f, 48_000))
        assertTrue("$fuerte", fuerte < GANANCIA_MINIMA)
        assertFalse(RealceDeVolumen.merece(fuerte))
        assertTrue(RealceDeVolumen.merece(g))
    }

    @Test
    fun un_chasquido_suelto_no_frena_el_realce() {
        val v = seno(0.05f, 100_000)
        v[500] = 1f
        assertTrue("el golpe suelto no cuenta como pico", RealceDeVolumen.gananciaPara(v) > 10f)
    }

    @Test
    fun el_silencio_no_se_sube_y_hay_tope() {
        assertEquals(1f, RealceDeVolumen.gananciaPara(FloatArray(1000)), 0f)
        assertEquals(1f, RealceDeVolumen.gananciaPara(FloatArray(0)), 0f)
        assertEquals(GANANCIA_MAXIMA, RealceDeVolumen.gananciaPara(seno(0.01f, 48_000)), 0f)
    }

    @Test
    fun el_limitador_no_deja_saturar() {
        val v = floatArrayOf(0.5f, -0.5f, 0.05f)
        RealceDeVolumen.aplicar(v, 4f)
        assertTrue(v[0] < 1f && v[0] > UMBRAL)
        assertTrue(v[1] > -1f && v[1] < -UMBRAL)
        assertEquals("lo que no llega al umbral sube tal cual", 0.2f, v[2], 1e-6f)
    }

    @Test
    fun el_medidor_a_trozos_da_lo_mismo_que_de_golpe() {
        val todo = seno(0.1f, 48_000)
        val m = RealceDeVolumen.Medidor()
        m.anadir(todo, 0, 10_000)
        m.anadir(todo, 10_000, todo.size)
        assertEquals(RealceDeVolumen.gananciaPara(todo), m.ganancia(), 0f)
        // Y en PCM de 16 bits, como sale de MediaCodec.
        val cortos = ShortArray(todo.size) { (todo[it] * 32767f).toInt().toShort() }
        val m16 = RealceDeVolumen.Medidor().apply { anadir(cortos) }
        assertEquals(m.ganancia(), m16.ganancia(), 0.1f)
    }

    @Test
    fun los_milibelios_de_loudness_enhancer() {
        assertEquals(0, RealceDeVolumen.milibelios(1f))
        assertEquals(0, RealceDeVolumen.milibelios(0.5f))
        assertEquals(602, RealceDeVolumen.milibelios(2f))
        assertEquals(2408, RealceDeVolumen.milibelios(GANANCIA_MAXIMA))
    }
}
