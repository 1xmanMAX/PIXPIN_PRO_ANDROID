package com.forge.pixpin.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Invertir colores en el lector de PDF (PC 11bd1f6): el papel se oscurece y los colores conservan su tono. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColoresInvertidosTest {
    private fun aplicar(r: Int, g: Int, b: Int): IntArray {
        val m = MATRIZ_INVERTIDA.array
        fun c(k: Int) = (m[k * 5] * r + m[k * 5 + 1] * g + m[k * 5 + 2] * b + m[k * 5 + 4]).toInt().coerceIn(0, 255)
        return intArrayOf(c(0), c(1), c(2))
    }

    @Test fun `el blanco pasa a negro y el negro a blanco`() {
        assertEquals(listOf(0, 0, 0), aplicar(255, 255, 255).toList())
        assertEquals(listOf(255, 255, 255), aplicar(0, 0, 0).toList())
        // Un gris sigue gris.
        val g = aplicar(100, 100, 100)
        assertTrue(g.toList().toString(), g.all { kotlin.math.abs(it - 155) <= 1 })
    }

    @Test fun `una raya roja sigue siendo roja`() {
        val (r, g, b) = aplicar(220, 30, 30).toList()
        assertTrue("$r $g $b", r > g + 60 && r > b + 60)
        // Caso negativo: invertir a secas la dejaría turquesa.
        val (r2, g2) = listOf(255 - 220, 255 - 30)
        assertTrue(g2 > r2)
    }
}
