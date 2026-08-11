package com.forge.pixpin.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La ida y la vuelta entre Markdown y texto limpio con estilos.
 *
 * Es la pieza de la que depende que no se vea un asterisco en el editor: si
 * escribir y volver a leer no da lo mismo, el texto de alguien cambia solo.
 */
class InlineTest {

    /** Escribir y volver a leer tiene que dar los mismos tramos. */
    private fun idaYVuelta(markdown: String) {
        val original = Markdown.parseInline(markdown)
        val escrito = Inline.aTexto(original)
        val vuelta = Markdown.parseInline(escrito)
        assertEquals("texto, por '$markdown' -> '$escrito'", original.text, vuelta.text)
        assertEquals("tramos, por '$markdown' -> '$escrito'", original.tramos(), vuelta.tramos())
    }

    @Test
    fun `texto plano`() = idaYVuelta("una frase normal")

    @Test
    fun `cada estilo por separado`() {
        idaYVuelta("con **negrita** dentro")
        idaYVuelta("con *cursiva* dentro")
        idaYVuelta("con ~~tachado~~ dentro")
        idaYVuelta("con `codigo` dentro")
        idaYVuelta("con ||tapado|| dentro")
    }

    @Test
    fun `estilos combinados y anidados`() {
        idaYVuelta("**muy _importante_**")
        idaYVuelta("**~~_todo a la vez_~~**")
        idaYVuelta("a **b *c* d** e")
    }

    @Test
    fun `enlaces con y sin estilo dentro`() {
        idaYVuelta("mira [esto](https://a.com) ya")
        idaYVuelta("mira [**esto**](https://a.com) ya")
    }

    /**
     * El caso que obliga a escapar: sin barra invertida, esto volvería en
     * cursiva sin que nadie la hubiera puesto.
     */
    @Test
    fun `los asteriscos sueltos no se convierten en cursiva`() {
        val limpio = InlineText("2 * 3 * 4 = 24")
        val escrito = Inline.aTexto(limpio)
        val vuelta = Markdown.parseInline(escrito)
        assertEquals("2 * 3 * 4 = 24", vuelta.text)
        assertTrue("salio con estilos: ${vuelta.tramos()}", vuelta.tramos().isEmpty())
    }

    @Test
    fun `los corchetes y las barras sueltas sobreviven`() {
        listOf("array[0] vale 3", "a | b || c", "guion_bajo_suelto", "100% seguro").forEach {
            val vuelta = Markdown.parseInline(Inline.aTexto(InlineText(it)))
            assertEquals(it, vuelta.text)
        }
    }

    @Test
    fun `una barra invertida escrita a mano sobrevive`() {
        val vuelta = Markdown.parseInline(Inline.aTexto(InlineText("""C:\ruta\x""")))
        assertEquals("""C:\ruta\x""", vuelta.text)
    }

    // ---- Recolocar estilos al escribir ----

    @Test
    fun `escribir dentro de la negrita la hace crecer`() {
        val spans = listOf(InlineSpan(0, 4, SpanKind.BOLD))
        val r = Inline.desplazar("hola", "hoola", spans)
        assertEquals(0, r[0].start)
        assertEquals(5, r[0].end)
    }

    @Test
    fun `escribir delante la empuja entera`() {
        val spans = listOf(InlineSpan(5, 9, SpanKind.BOLD))
        val r = Inline.desplazar("hola mund", "XXhola mund", spans)
        assertEquals(7, r[0].start)
        assertEquals(11, r[0].end)
    }

    @Test
    fun `escribir detras no la toca`() {
        val spans = listOf(InlineSpan(0, 4, SpanKind.BOLD))
        val r = Inline.desplazar("hola", "hola mundo", spans)
        assertEquals(0, r[0].start)
        assertEquals(4, r[0].end)
    }

    @Test
    fun `borrar la palabra entera se lleva su estilo`() {
        val spans = listOf(InlineSpan(5, 10, SpanKind.BOLD))
        val r = Inline.desplazar("hola mundo", "hola ", spans)
        assertTrue("quedo $r", r.isEmpty())
    }

    @Test
    fun `borrar un trozo la encoge`() {
        val spans = listOf(InlineSpan(0, 10, SpanKind.BOLD))
        val r = Inline.desplazar("hola mundo", "hola", spans)
        assertEquals(0, r[0].start)
        assertEquals(4, r[0].end)
    }

    // ---- Alternar estilos ----

    @Test
    fun `alternar pone y quita`() {
        val puesto = Inline.alternar(emptyList(), 0, 4, SpanKind.BOLD)
        assertTrue(Inline.cubre(puesto, 0, 4, SpanKind.BOLD))

        val quitado = Inline.alternar(puesto, 0, 4, SpanKind.BOLD)
        assertFalse(Inline.cubre(quitado, 0, 4, SpanKind.BOLD))
    }

    @Test
    fun `quitar en medio parte el estilo en dos`() {
        val entero = listOf(InlineSpan(0, 10, SpanKind.BOLD))
        val r = Inline.alternar(entero, 3, 6, SpanKind.BOLD)
        assertTrue(Inline.cubre(r, 0, 3, SpanKind.BOLD))
        assertFalse(Inline.cubre(r, 3, 6, SpanKind.BOLD))
        assertTrue(Inline.cubre(r, 6, 10, SpanKind.BOLD))
    }

    @Test
    fun `poner sobre algo a medias lo completa`() {
        val medio = listOf(InlineSpan(0, 5, SpanKind.BOLD))
        val r = Inline.alternar(medio, 0, 10, SpanKind.BOLD)
        assertTrue(Inline.cubre(r, 0, 10, SpanKind.BOLD))
        // Y sin duplicar: al escribirlo no pueden salir marcas de más.
        val texto = Inline.aTexto(InlineText("0123456789", r))
        assertEquals("**0123456789**", texto)
    }

    @Test
    fun `dos estilos conviven sobre las mismas letras`() {
        var spans = Inline.alternar(emptyList(), 0, 5, SpanKind.BOLD)
        spans = Inline.alternar(spans, 2, 5, SpanKind.ITALIC)
        assertEquals(setOf(SpanKind.BOLD, SpanKind.ITALIC), Inline.estilosDe(spans, 2, 5))
        assertEquals(setOf(SpanKind.BOLD), Inline.estilosDe(spans, 0, 2))
    }

    @Test
    fun `indices imposibles no revientan`() {
        Inline.alternar(emptyList(), 5, 2, SpanKind.BOLD)
        Inline.desplazar("", "", emptyList())
        assertEquals("", Inline.aTexto(InlineText("")))
    }
}
