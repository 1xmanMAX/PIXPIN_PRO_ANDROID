package com.forge.pixpin.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEditTest {

    @Test
    fun `envuelve la seleccion en negrita`() {
        val r = MarkdownEdit.wrap("hola mundo", 5, 10, "**")
        assertEquals("hola **mundo**", r.text)
        assertEquals("mundo", r.text.substring(r.selStart, r.selEnd))
    }

    /** El mismo botón pone y quita: si ya estaba en negrita, se la lleva. */
    @Test
    fun `desenvuelve si la seleccion ya incluye las marcas`() {
        val r = MarkdownEdit.wrap("hola **mundo**", 5, 14, "**")
        assertEquals("hola mundo", r.text)
        assertEquals("mundo", r.text.substring(r.selStart, r.selEnd))
    }

    @Test
    fun `desenvuelve tambien con las marcas justo fuera de la seleccion`() {
        val r = MarkdownEdit.wrap("hola **mundo**", 7, 12, "**")
        assertEquals("hola mundo", r.text)
        assertEquals("mundo", r.text.substring(r.selStart, r.selEnd))
    }

    @Test
    fun `sin seleccion deja el cursor entre las marcas`() {
        val r = MarkdownEdit.wrap("hola ", 5, 5, "**")
        assertEquals("hola ****", r.text)
        assertEquals(7, r.selStart)
        assertEquals(7, r.selEnd)
    }

    @Test
    fun `cursiva y codigo usan la misma mecanica`() {
        assertEquals("*eco*", MarkdownEdit.wrap("eco", 0, 3, "*").text)
        assertEquals("`eco`", MarkdownEdit.wrap("eco", 0, 3, "`").text)
    }

    @Test
    fun `pone el prefijo en la linea del cursor`() {
        val r = MarkdownEdit.togglePrefix("uno\ndos\ntres", 5, "- ")
        assertEquals("uno\n- dos\ntres", r.text)
    }

    @Test
    fun `quita el prefijo si ya estaba`() {
        val r = MarkdownEdit.togglePrefix("uno\n- dos\ntres", 7, "- ")
        assertEquals("uno\ndos\ntres", r.text)
    }

    @Test
    fun `el prefijo en la primera linea`() {
        val r = MarkdownEdit.togglePrefix("titulo\notro", 2, "# ")
        assertEquals("# titulo\notro", r.text)
    }

    @Test
    fun `texto vacio no revienta`() {
        assertEquals("****", MarkdownEdit.wrap("", 0, 0, "**").text)
        assertEquals("# ", MarkdownEdit.togglePrefix("", 0, "# ").text)
    }

    /** Índices fuera de rango llegan de verdad cuando el texto cambia bajo el cursor. */
    @Test
    fun `indices desbordados se recortan en vez de reventar`() {
        val r = MarkdownEdit.wrap("hola", 99, 200, "**")
        assertEquals("hola****", r.text)
    }
}
