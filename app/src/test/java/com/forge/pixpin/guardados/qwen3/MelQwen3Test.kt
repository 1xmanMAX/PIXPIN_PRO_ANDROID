package com.forge.pixpin.guardados.qwen3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** El log-mel de Qwen3-ASR contra los mismos valores de numpy que `qwen3/mel.rs` del PC. */
class MelQwen3Test {

    /** Dos tonos y un barrido, 0,5 s: la misma señal que se pasó por numpy para sacar [REFERENCIA]. */
    private fun senal() = FloatArray(8_000) { i ->
        val t = i.toDouble() / MelQwen3.HERCIOS
        (0.3 * sin(2.0 * PI * 440.0 * t) + 0.2 * sin(2.0 * PI * 3_000.0 * t) + 0.1 * sin(2.0 * PI * (100.0 + 4_000.0 * t) * t)).toFloat()
    }

    @Test
    fun `salen tantos marcos como saltos y nada de un trozo minimo`() {
        assertEquals(100, MelQwen3.marcos(16_000))
        assertEquals(100 * MelQwen3.BANDAS, MelQwen3.logMel(FloatArray(16_000) { 0.1f }).size)
        assertTrue(MelQwen3.logMel(FloatArray(100) { 0.5f }).isEmpty())
        assertEquals("un marco, sin reflejo", MelQwen3.BANDAS, MelQwen3.logMel(FloatArray(160) { 0.5f }).size)
    }

    @Test
    fun `los filtros son los de transformers`() {
        // `mel_filter_bank(201, 128, 0, 8000, 16000, "slaney", "slaney")`: la banda 0 vale 0,01237399 en el cubo 1.
        val w = MelQwen3.filtros
        assertEquals(0f, w[0])
        assertTrue("${w[1]}", abs(w[1] - 0.01237399f) < 1e-7f)
        assertTrue(w.all { it >= 0f })
        for (b in 0 until MelQwen3.BANDAS) {
            assertTrue("$b", (0 until MelQwen3.CUBOS).any { w[b * MelQwen3.CUBOS + it] > 0f })
        }
    }

    @Test
    fun `coincide con numpy en la senal de prueba`() {
        // `WhisperFeatureExtractor(feature_size=128)` con `padding="do_not_pad"` sobre `senal()`:
        // marco, banda, valor (los de `REFERENCIA` de mel.rs).
        val mel = MelQwen3.logMel(senal())
        assertEquals(50 * MelQwen3.BANDAS, mel.size)
        for ((m, b, v) in REFERENCIA) {
            val x = mel[m * MelQwen3.BANDAS + b]
            assertTrue("marco $m banda $b: $x frente a $v", abs(x - v) < 2e-3f)
        }
        val media = mel.sum() / mel.size
        assertTrue("$media", abs(media - MEDIA_REFERENCIA) < 1e-3f)
    }

    @Test
    fun `el silencio entero da menos uno y medio`() {
        assertTrue(MelQwen3.logMel(FloatArray(3_200)).all { abs(it + 1.5f) < 1e-6f })
    }

    private companion object {
        val REFERENCIA = listOf(
            Triple(0, 0, 1.012514f), Triple(0, 10, 0.864424f), Triple(5, 20, 1.262612f), Triple(10, 30, 0.216630f),
            Triple(25, 64, -0.542875f), Triple(40, 90, 0.768601f), Triple(49, 127, -0.469444f), Triple(20, 5, -0.590586f)
        )
        const val MEDIA_REFERENCIA = -0.3371337f
    }
}
