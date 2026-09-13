package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La tinta se adapta al papel** (13-sep-2026): se deja la que ya se lee, y la que no, cambia de
 * claridad conservando el tono hasta leerse. Sustituye al filtro de noche, que guardaba de noche
 * colores que de día salían casi negros. Ver [DrawTheme.adaptar].
 */
class TemaNocheTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val blanco = rgb(255, 255, 255)
    private val negro = rgb(0, 0, 0)

    private fun seLee(tinta: Int, papel: Int) =
        DrawTheme.contraste(DrawTheme.luminancia(tinta), DrawTheme.luminancia(papel)) >= DrawTheme.CONTRASTE_QUE_VALE

    @Test
    fun `lo que ya se lee no se toca`() {
        val rojo = rgb(0xE5, 0x39, 0x35)
        assertEquals(rojo, DrawTheme.adaptar(rojo, blanco))
        assertEquals(rojo, DrawTheme.adaptar(rojo, negro))
        assertEquals(negro, DrawTheme.adaptar(negro, blanco))
        assertEquals(blanco, DrawTheme.adaptar(blanco, negro))
    }

    @Test
    fun `el blanco sobre papel blanco sale oscuro y el negro sobre negro claro`() {
        val a = DrawTheme.adaptar(blanco, blanco)
        assertTrue(DrawTheme.luminancia(a) < 0.1)
        val b = DrawTheme.adaptar(negro, rgb(0x12, 0x12, 0x12))
        assertTrue(DrawTheme.luminancia(b) > 0.3)
    }

    @Test
    fun `un color que no se lee cambia de claridad y sigue siendo de su tono`() {
        val amarillo = rgb(0xFF, 0xEB, 0x3B)
        val sobreBlanco = DrawTheme.adaptar(amarillo, blanco)
        assertNotEquals(amarillo, sobreBlanco)
        assertTrue(seLee(sobreBlanco, blanco))
        // Sigue siendo amarillento: más rojo y verde que azul.
        assertTrue(((sobreBlanco shr 16) and 0xFF) > (sobreBlanco and 0xFF))

        val marino = rgb(0x0D, 0x1B, 0x5E)
        val sobreNegro = DrawTheme.adaptar(marino, negro)
        assertTrue(seLee(sobreNegro, negro))
        assertTrue("sigue siendo azul", (sobreNegro and 0xFF) > ((sobreNegro shr 16) and 0xFF))
    }

    @Test
    fun `un morado sobre papel morado se cambia`() {
        val morado = rgb(0x7B, 0x1F, 0xA2)
        val papelMorado = rgb(0x6A, 0x1B, 0x9A)
        assertTrue(seLee(DrawTheme.adaptar(morado, papelMorado), papelMorado))
    }

    @Test
    fun `cambiar de papel y volver deja el color como estaba`() {
        // Es pintura: el color guardado no cambia, así que adaptar al papel de antes da lo de antes.
        val tinta = rgb(0x20, 0x40, 0x90)
        assertEquals(DrawTheme.adaptar(tinta, blanco), DrawTheme.adaptar(tinta, blanco))
    }
}
