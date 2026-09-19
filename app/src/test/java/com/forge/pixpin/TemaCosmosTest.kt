package com.forge.pixpin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.forge.pixpin.motor.DrawTheme
import com.forge.pixpin.ui.theme.CosmosBase
import com.forge.pixpin.ui.theme.esquemaCosmos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El tema Cosmos: que se lea, y que el cielo solo se pida donde hay cielo. */
class TemaCosmosTest {

    private fun contraste(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (maxOf(la, lb) / minOf(la, lb)).toDouble()
    }

    /** El peor fondo sobre el que va el texto: el centro del cielo, el más claro. */
    private val cieloMasClaro = Color(0xFF151B3F)

    @Test
    fun `el texto y el dorado se leen sobre el cielo y sobre las tarjetas`() {
        val e = esquemaCosmos(conCielo = true)
        val tarjeta = e.surfaceContainerHighest.compositeOver(cieloMasClaro)
        for (fondo in listOf(cieloMasClaro, tarjeta, e.surface, e.surfaceContainerHigh)) {
            assertTrue("texto $fondo", contraste(e.onSurface, fondo) >= 7.0)
            assertTrue("texto suave $fondo", contraste(e.onSurfaceVariant, fondo) >= 4.5)
            assertTrue("dorado $fondo", contraste(e.primary, fondo) >= 4.5)
        }
        assertTrue(contraste(e.onPrimary, e.primary) >= 7.0)
    }

    @Test
    fun `sin cielo detrás no hay nada transparente`() {
        val e = esquemaCosmos(conCielo = false)
        assertEquals(1f, e.background.alpha)
        assertEquals(1f, e.surfaceContainerHighest.alpha)
        assertEquals(CosmosBase, e.background)
        assertEquals(0f, esquemaCosmos(conCielo = true).background.alpha)
    }

    @Test
    fun `el papel cosmos es de noche y solo él y la pizarra se ven como cielo`() {
        assertTrue(DrawTheme.esDeNoche(DrawTheme.FONDO_COSMOS))
        assertTrue(DrawTheme.seVeComoCielo("#0B0F24"))
        assertTrue(DrawTheme.seVeComoCielo(DrawTheme.FONDO_NOCHE))
        assertFalse(DrawTheme.seVeComoCielo(DrawTheme.FONDO_OLED))
        assertFalse(DrawTheme.seVeComoCielo(DrawTheme.FONDO_DIA))
        assertTrue(DrawTheme.PAPELES.any { it.second == DrawTheme.FONDO_COSMOS })
    }
}
