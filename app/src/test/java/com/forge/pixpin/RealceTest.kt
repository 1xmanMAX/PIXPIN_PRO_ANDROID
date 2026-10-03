package com.forge.pixpin.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** El realce de la voz: las mismas pruebas que el PC (`realce::pruebas`). */
class RealceTest {
    private val FS = 48_000

    private fun seno(amplitud: Float, hz: Float, segundos: Float): ShortArray {
        val n = (segundos * FS).toInt()
        return ShortArray(n) { i -> Math.round(amplitud * 32767f * sin(2.0 * PI * hz * i / FS).toFloat()).toShort() }
    }

    private fun pico(v: ShortArray, desde: Int = 0) = (desde until v.size).maxOfOrNull { abs(v[it].toInt()) } ?: 0

    private fun realzar(v: ShortArray): ShortArray {
        val r = Realce(FS)
        var i = 0
        while (i < v.size) { r.procesar(v, i, minOf(i + 480, v.size)); i += 480 }
        return v
    }

    @Test
    fun `una voz baja sale mas alta`() {
        val entrada = seno(0.02f, 440f, 2f)
        val salida = realzar(entrada.copyOf())
        val antes = pico(entrada, FS); val despues = pico(salida, FS)
        assertTrue("$antes -> $despues", despues > 10 * antes)
        assertTrue(despues <= antes * Realce.GANANCIA_MAXIMA * 1.05f)
    }

    @Test
    fun `una voz ya alta no se recorta`() {
        val salida = realzar(seno(0.97f, 440f, 1f))
        val p = pico(salida)
        assertTrue("llegó al techo: $p", p < 32767)
        assertEquals(0, salida.count { abs(it.toInt()) >= 32700 })
        assertTrue("$p", p > (0.85 * 32767).toInt())
    }

    @Test
    fun `el silencio sigue en silencio`() {
        assertTrue(realzar(ShortArray(FS)).all { it.toInt() == 0 })
        var semilla = 12345L
        val ruido = ShortArray(FS) {
            semilla = (semilla * 1_103_515_245L + 12345L) and 0xFFFFFFFFL
            (((semilla shr 16) % 21) - 10).toShort()
        }
        val p = pico(realzar(ruido))
        assertTrue("$p", p <= 30)
    }

    @Test
    fun `el paso alto quita la continua`() {
        val salida = realzar(ShortArray(FS) { 8000 })
        val cola = salida.copyOfRange(FS / 2, FS)
        val media = cola.map { it.toDouble() }.average()
        assertTrue("media $media", abs(media) < 20.0)
        assertTrue(pico(cola) < 50)
    }

    @Test
    fun `el limitador nunca llega al techo y es simetrico`() {
        for (x in listOf(0f, 0.5f, 0.85f, 0.9f, 1f, 2f, 15f, 1.0e6f)) {
            val y = Realce.limitar(x)
            assertTrue("$x -> $y", y < 1f && y >= 0f)
            assertEquals(-y, Realce.limitar(-x))
        }
        assertEquals(0.5f, Realce.limitar(0.5f))
        assertTrue(Realce.limitar(0.9f) < 0.9f && Realce.limitar(0.9f) > 0.85f)
    }
}
