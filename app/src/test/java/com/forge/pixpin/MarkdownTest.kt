package com.forge.pixpin.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    // ---- Texto plano: lo que más se rompería al tener el formateo siempre activo ----

    /**
     * El caso que justifica no poner interruptor: un texto sin marcas tiene que
     * salir intacto, carácter a carácter.
     */
    @Test
    fun `el texto plano sale intacto`() {
        val plano = "Recordar llamar al fontanero el martes a las 9:30."
        val blocks = Markdown.parse(plano)
        assertEquals(1, blocks.size)
        val p = blocks[0] as MarkdownBlock.Paragraph
        assertEquals(plano, p.content.text)
        assertTrue("no debe inventar estilos", p.content.spans.isEmpty())
    }

    @Test
    fun `las lineas seguidas forman un solo parrafo`() {
        val blocks = Markdown.parse("una linea\notra linea\n\nsegundo parrafo")
        assertEquals(2, blocks.size)
        assertEquals("una linea otra linea", (blocks[0] as MarkdownBlock.Paragraph).content.text)
        assertEquals("segundo parrafo", (blocks[1] as MarkdownBlock.Paragraph).content.text)
    }

    // ---- Bloques ----

    @Test
    fun `titulos de tres niveles`() {
        val blocks = Markdown.parse("# uno\n## dos\n### tres")
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as MarkdownBlock.Heading).level)
        assertEquals("uno", (blocks[0] as MarkdownBlock.Heading).content.text)
        assertEquals(2, (blocks[1] as MarkdownBlock.Heading).level)
        assertEquals(3, (blocks[2] as MarkdownBlock.Heading).level)
    }

    @Test
    fun `vinetas con los tres marcadores`() {
        val blocks = Markdown.parse("- uno\n* dos\n+ tres")
        assertEquals(3, blocks.size)
        assertEquals("uno", (blocks[0] as MarkdownBlock.Bullet).content.text)
        assertEquals("dos", (blocks[1] as MarkdownBlock.Bullet).content.text)
        assertEquals("tres", (blocks[2] as MarkdownBlock.Bullet).content.text)
    }

    @Test
    fun `listas numeradas conservan su numero`() {
        val blocks = Markdown.parse("1. primero\n2) segundo\n10. decimo")
        assertEquals(1, (blocks[0] as MarkdownBlock.Numbered).number)
        assertEquals("primero", (blocks[0] as MarkdownBlock.Numbered).content.text)
        assertEquals(2, (blocks[1] as MarkdownBlock.Numbered).number)
        assertEquals(10, (blocks[2] as MarkdownBlock.Numbered).number)
    }

    @Test
    fun `cita y regla horizontal`() {
        val blocks = Markdown.parse("> citado\n\n---")
        assertEquals("citado", (blocks[0] as MarkdownBlock.Quote).content.text)
        assertEquals(MarkdownBlock.Rule, blocks[1])
    }

    @Test
    fun `el bloque de codigo conserva su contenido en crudo`() {
        val blocks = Markdown.parse("```\nval x = **no negrita**\nsegunda\n```")
        assertEquals(1, blocks.size)
        assertEquals("val x = **no negrita**\nsegunda", (blocks[0] as MarkdownBlock.Code).text)
    }

    /** Una valla sin cerrar no puede tragarse el resto del pin en silencio. */
    @Test
    fun `un bloque de codigo sin cerrar llega hasta el final`() {
        val blocks = Markdown.parse("```\nsuelto")
        assertEquals(1, blocks.size)
        assertEquals("suelto", (blocks[0] as MarkdownBlock.Code).text)
    }

    // ---- Inline ----

    @Test
    fun `negrita limpia las marcas y marca el tramo`() {
        val r = Markdown.parseInline("hola **mundo** adios")
        assertEquals("hola mundo adios", r.text)
        assertEquals(1, r.spans.size)
        val s = r.spans[0]
        assertEquals(SpanKind.BOLD, s.kind)
        assertEquals("mundo", r.text.substring(s.start, s.end))
    }

    @Test
    fun `cursiva con asterisco y con guion bajo`() {
        val a = Markdown.parseInline("un *eco* aqui")
        assertEquals("un eco aqui", a.text)
        assertEquals(SpanKind.ITALIC, a.spans[0].kind)
        assertEquals("eco", a.text.substring(a.spans[0].start, a.spans[0].end))

        val b = Markdown.parseInline("un _eco_ aqui")
        assertEquals("un eco aqui", b.text)
        assertEquals(SpanKind.ITALIC, b.spans[0].kind)
    }

    @Test
    fun `tachado`() {
        val r = Markdown.parseInline("esto ~~sobra~~ ya")
        assertEquals("esto sobra ya", r.text)
        assertEquals(SpanKind.STRIKE, r.spans[0].kind)
        assertEquals("sobra", r.text.substring(r.spans[0].start, r.spans[0].end))
    }

    @Test
    fun `enlace guarda texto y url por separado`() {
        val r = Markdown.parseInline("mira [esto](https://ejemplo.com) ahora")
        assertEquals("mira esto ahora", r.text)
        assertEquals(1, r.spans.size)
        assertEquals(SpanKind.LINK, r.spans[0].kind)
        assertEquals("https://ejemplo.com", r.spans[0].url)
        assertEquals("esto", r.text.substring(r.spans[0].start, r.spans[0].end))
    }

    /** Lo que protege pegar una ruta o una expresión sin que se la coman. */
    @Test
    fun `el codigo inline no interpreta lo que lleva dentro`() {
        val r = Markdown.parseInline("usa `a **b** c` y ya")
        assertEquals("usa a **b** c y ya", r.text)
        assertEquals(1, r.spans.size)
        assertEquals(SpanKind.CODE, r.spans[0].kind)
        assertEquals("a **b** c", r.text.substring(r.spans[0].start, r.spans[0].end))
    }

    @Test
    fun `negrita y cursiva juntas en la misma linea`() {
        val r = Markdown.parseInline("**uno** y *dos*")
        assertEquals("uno y dos", r.text)
        assertEquals(2, r.spans.size)
        assertEquals(SpanKind.BOLD, r.spans[0].kind)
        assertEquals(SpanKind.ITALIC, r.spans[1].kind)
    }

    // ---- Marcas rotas: no pueden comerse texto ----

    @Test
    fun `una marca sin cerrar se queda tal cual`() {
        val r = Markdown.parseInline("esto **no cierra")
        assertEquals("esto **no cierra", r.text)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun `un asterisco suelto no rompe nada`() {
        val r = Markdown.parseInline("2 * 3 = 6")
        assertEquals("2 * 3 = 6", r.text)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun `un corchete que no es enlace se queda tal cual`() {
        val r = Markdown.parseInline("array[0] vale 3")
        assertEquals("array[0] vale 3", r.text)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun `texto vacio no revienta`() {
        assertTrue(Markdown.parse("").isEmpty())
        assertEquals("", Markdown.parseInline("").text)
    }
}
