package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Pegar texto en el lienzo**, como el PC (`pixpin_motor2d::texto_pegado`, commit fdc9095): las
 * mismas pruebas que allí, más la de que el controlador lo deja como un texto del motor con el
 * estilo del panel, en el centro y elegido. Ver [TextoPegado] y [DrawController.pegarTexto].
 */
class TextoPegadoTest {

    /** Diez por carácter: cuentas fáciles. */
    private val diez: (String) -> Double = { it.codePointCount(0, it.length) * 10.0 }

    @Test
    fun `unifica los finales de linea y quita los extremos`() {
        assertEquals("hola\nmundo\nfin", TextoPegado.preparar("\r\n\r\n  hola\r\nmundo  \rfin\n\n"))
        assertEquals("a    b", TextoPegado.preparar("a\tb"))
        // La sangría de los renglones de dentro se queda (puede ser código).
        assertEquals("fn x() {\n    y\n}", TextoPegado.preparar("fn x() {\n    y\n}"))
    }

    @Test
    fun `tildes y emojis pasan tal cual`() {
        assertEquals("Canción ñandú 😀👍🏽", TextoPegado.preparar("Canción ñandú 😀👍🏽"))
    }

    @Test
    fun `sin nada visible no hay pegado`() {
        assertNull(TextoPegado.preparar(""))
        assertNull(TextoPegado.preparar("   \r\n\t \n"))
        assertNull(TextoPegado.preparar("\u0000﻿\u0016"))
    }

    @Test
    fun `un texto enorme se corta en el tope sin partir letras`() {
        val enorme = "😀".repeat(TextoPegado.TOPE_CARACTERES + 500)
        val p = TextoPegado.preparar(enorme)!!
        assertEquals(TextoPegado.TOPE_CARACTERES + 1, p.codePointCount(0, p.length))
        assertTrue(p.endsWith("…"))
        val justo = "a".repeat(TextoPegado.TOPE_CARACTERES)
        assertEquals(justo, TextoPegado.preparar(justo))
    }

    @Test
    fun `el ancho maximo es la mitad de lo que se ve entre 200 y 800`() {
        assertEquals(500.0, TextoPegado.anchoMaximo(1000.0), 0.0)
        assertEquals(200.0, TextoPegado.anchoMaximo(100.0), 0.0)
        assertEquals(800.0, TextoPegado.anchoMaximo(10_000.0), 0.0)
        assertEquals(800.0, TextoPegado.anchoMaximo(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test
    fun `ajusta por palabras y respeta los saltos`() {
        assertEquals("uno dos\ntres", TextoPegado.ajustar("uno dos tres", 80.0, diez))
        assertEquals("corto\nuno dos\ntres", TextoPegado.ajustar("corto\nuno dos tres", 80.0, diez))
        assertEquals("uno dos", TextoPegado.ajustar("uno dos", 80.0, diez))
    }

    @Test
    fun `una url larga no se parte nunca`() {
        val url = "https://ejemplo.com/una/ruta/muy/larga?con=parametros"
        val a = TextoPegado.ajustar("mira $url ya", 100.0, diez)
        assertTrue(a, a.lines().any { it == url })
        assertEquals(url, TextoPegado.ajustar(url, 100.0, diez))
    }

    @Test
    fun `el controlador lo deja como un texto del motor con el estilo del panel, centrado y elegido`() {
        val c = DrawController()
        c.cambiarEstilo(
            c.estiloActivo().copy(fontSize = 36.0, fontFamily = ItemStyle.FONT_NUNITO, strokeColor = "#e03131", textAlign = TextAlign.CENTER)
        ) { it }
        c.selectTool(Tool.RECTANGLE)
        val antes = c.scene.elements.size
        val medidas = ArrayList<Pair<Int, Double>>()
        val e = c.pegarTexto(
            "\r\n  uno dos tres\tcuatro\r\n",
            centro = Pt(500.0, 300.0),
            anchoVisible = 1000.0,
            mide = { r, f, t -> medidas += f to t; r.length * 10.0 },
            medida = { t, _, tam -> (t.lines().maxOf { it.length } * 10.0) to t.lines().size * tam * 1.25 }
        )!!
        assertEquals(antes + 1, c.scene.elements.size)
        assertEquals(ElementType.TEXT, e.type)
        assertEquals("uno dos tres    cuatro", e.text)
        assertEquals(36.0, e.fontSize!!, 0.0)
        assertEquals(ItemStyle.FONT_NUNITO, e.fontFamily)
        assertEquals("#e03131", e.strokeColor)
        assertEquals(TextAlign.CENTER, e.textAlign)
        assertTrue("se mide con la letra y el tamaño del panel", medidas.all { it == ItemStyle.FONT_NUNITO to 36.0 })
        // Centrado donde se pega.
        assertEquals(500.0, e.x + e.width / 2, 1e-9)
        assertEquals(300.0, e.y + e.height / 2, 1e-9)
        assertEquals(setOf(e.id), c.selectedIds)
        assertEquals(Tool.SELECTION, c.tool)
        // Un solo paso de deshacer.
        c.undo()
        assertEquals(antes, c.scene.elements.count { !it.isDeleted })
    }

    @Test
    fun `lo que no se ve no pega nada`() {
        val c = DrawController()
        assertNull(c.pegarTexto(" \n\t", Pt(0.0, 0.0), 1000.0, { _, _, _ -> 0.0 }, { _, _, _ -> 0.0 to 0.0 }))
        assertTrue(c.scene.elements.isEmpty())
    }

    @Test
    fun `un parrafo largo se parte a la mitad de lo que se ve`() {
        val c = DrawController()
        val e = c.pegarTexto(
            "a".repeat(30) + " " + "b".repeat(30) + " " + "c".repeat(30),
            Pt(0.0, 0.0), anchoVisible = 1000.0,
            mide = { r, _, _ -> r.length * 10.0 },
            medida = { t, _, tam -> (t.lines().maxOf { it.length } * 10.0) to t.lines().size * tam * 1.25 }
        )!!
        // A 10 por letra y 500 de ancho caben 50 letras: una palabra de 30 por renglón.
        assertEquals(3, e.text!!.lines().size)
    }
}
