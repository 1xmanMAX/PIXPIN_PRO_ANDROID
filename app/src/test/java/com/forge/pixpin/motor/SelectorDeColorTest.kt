package com.forge.pixpin.motor

import com.forge.pixpin.motor.SelectorDeColor.Hsv
import com.forge.pixpin.motor.SelectorDeColor.Rgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **El color avanzado** (2-nov-2026): las pruebas de `selector_color.rs` del PC. */
class SelectorDeColorTest {
    private fun hex(v: Int) = Rgb(((v shr 16) and 0xff) / 255f, ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f)

    @Test
    fun `los primarios tienen su tono`() {
        fun h(v: Int) = Math.round(Hsv.de(hex(v)).h).toFloat()
        assertEquals(0f, h(0xff0000))
        assertEquals(60f, h(0xffff00))
        assertEquals(120f, h(0x00ff00))
        assertEquals(180f, h(0x00ffff))
        assertEquals(240f, h(0x0000ff))
        assertEquals(300f, h(0xff00ff))
    }

    @Test
    fun `de rgb a hsv y vuelta da el mismo color`() {
        for (v in listOf(0x1e1e1e, 0xe03131, 0x2f9e44, 0x1971c2, 0xf08c00, 0xffc9c9, 0xb2f2bb, 0xa5d8ff, 0xffec99, 0xffffff, 0x000000, 0x123456, 0x7f7f7f, 0xc2255c, 0x099268)) {
            val c = hex(v)
            assertTrue(Integer.toHexString(v), SelectorDeColor.iguales(c, Hsv.de(c).color()))
        }
    }

    @Test
    fun `un gris conserva el tono que se estaba eligiendo`() {
        val gris = Hsv.de(hex(0x808080))
        assertEquals(0f, gris.s)
        assertEquals(210f, gris.conTonoDe(210f).h)
        val azul = Hsv.de(hex(0x1971c2))
        assertEquals(azul.h, azul.conTonoDe(10f).h, 0.01f)
        assertEquals(0f, gris.conTonoDe(null).h)
    }

    @Test
    fun `el codigo se lee con tres seis u ocho digitos`() {
        assertTrue(SelectorDeColor.iguales(hex(0xe03131), SelectorDeColor.leerCodigo("#e03131")!!.first))
        assertNull(SelectorDeColor.leerCodigo("E03131")!!.second)
        assertTrue(SelectorDeColor.iguales(hex(0xff00aa), SelectorDeColor.leerCodigo("#f0a")!!.first))
        val (c, a) = SelectorDeColor.leerCodigo(" #1971c280 ")!!
        assertTrue(SelectorDeColor.iguales(c, hex(0x1971c2)))
        assertEquals(128f / 255f, a!!, 1e-6f)
        for (malo in listOf("", "#", "#ff", "#ffff", "1234567", "#12345g", "transparent", "#1234567890")) assertNull(malo, SelectorDeColor.leerCodigo(malo))
    }

    @Test
    fun `el codigo lleva la opacidad solo si no es entera y el contraste`() {
        assertEquals("1971c2", SelectorDeColor.escribirCodigo(hex(0x1971c2)))
        assertEquals("1971c280", SelectorDeColor.escribirCodigo(hex(0x1971c2), 128f / 255f))
        assertEquals(21f, SelectorDeColor.contraste(hex(0), hex(0xffffff)), 0.01f)
        assertTrue(SelectorDeColor.marcaClara(hex(0x1e1e1e)))
        assertFalse(SelectorDeColor.marcaClara(hex(0xffec99)))
    }

    @Test
    fun `los recientes son cinco el ultimo delante y sin repetir`() {
        val r = SelectorDeColor.Recientes.deCodigos(emptyList())
        for (v in listOf(0x111111, 0x222222, 0x333333, 0x444444, 0x555555, 0x666666)) r.apuntar(hex(v))
        assertEquals(listOf("#666666", "#555555", "#444444", "#333333", "#222222"), r.codigos())
        assertFalse(r.apuntar(hex(0x666666)))
        assertTrue(r.apuntar(hex(0x333333)))
        assertEquals("#333333", r.codigos().first())
        assertEquals(5, r.codigos().size)
        assertEquals(r.codigos(), SelectorDeColor.Recientes.deTexto(r.codigos().joinToString(",")).codigos())
    }
}
