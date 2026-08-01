package com.forge.pixpin.pin

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBoxSizeTest {

    @Test
    fun `arrastrar en diagonal crece en los dos ejes`() {
        val dims = TextBoxSize.resize(startWidth = 200, startHeight = 100, dxDp = 60f, dyDp = 40f)
        assertEquals(260, dims.width)
        assertEquals(140, dims.height)
    }

    @Test
    fun `arrastrar hacia dentro encoge`() {
        val dims = TextBoxSize.resize(startWidth = 300, startHeight = 200, dxDp = -80f, dyDp = -50f)
        assertEquals(220, dims.width)
        assertEquals(150, dims.height)
    }

    @Test
    fun `no baja de los minimos por mucho que se arrastre`() {
        val dims = TextBoxSize.resize(startWidth = 200, startHeight = 100, dxDp = -9000f, dyDp = -9000f)
        assertEquals(TextBoxSize.MIN_WIDTH, dims.width)
        assertEquals(TextBoxSize.MIN_HEIGHT, dims.height)
    }

    @Test
    fun `no pasa de los maximos por mucho que se arrastre`() {
        val dims = TextBoxSize.resize(startWidth = 200, startHeight = 100, dxDp = 9000f, dyDp = 9000f)
        assertEquals(TextBoxSize.MAX_WIDTH, dims.width)
        assertEquals(TextBoxSize.MAX_HEIGHT, dims.height)
    }

    @Test
    fun `sin arrastre no cambia nada`() {
        val dims = TextBoxSize.resize(startWidth = 330, startHeight = 120, dxDp = 0f, dyDp = 0f)
        assertEquals(330, dims.width)
        assertEquals(120, dims.height)
    }
}
