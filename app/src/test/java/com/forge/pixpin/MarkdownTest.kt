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

    // ---- Enlaces sueltos: lo que se pega de verdad no viene en [texto](url) ----

    @Test
    fun `una url suelta se reconoce como enlace`() {
        val r = Markdown.parseInline("mira https://ejemplo.com/a?b=1 y ya")
        assertEquals("mira https://ejemplo.com/a?b=1 y ya", r.text)
        assertEquals(1, r.spans.size)
        assertEquals(SpanKind.LINK, r.spans[0].kind)
        assertEquals("https://ejemplo.com/a?b=1", r.spans[0].url)
        assertEquals(
            "https://ejemplo.com/a?b=1",
            r.text.substring(r.spans[0].start, r.spans[0].end)
        )
    }

    @Test
    fun `www sin protocolo tambien es enlace y se le pone https`() {
        val r = Markdown.parseInline("entra en www.ejemplo.com hoy")
        assertEquals("entra en www.ejemplo.com hoy", r.text)
        assertEquals(SpanKind.LINK, r.spans[0].kind)
        assertEquals("https://www.ejemplo.com", r.spans[0].url)
    }

    /** Un punto o una coma detrás de la url son puntuación de la frase, no parte del enlace. */
    @Test
    fun `la puntuacion final no entra en el enlace`() {
        val r = Markdown.parseInline("visita https://ejemplo.com.")
        assertEquals("visita https://ejemplo.com.", r.text)
        assertEquals("https://ejemplo.com", r.spans[0].url)
        assertEquals("https://ejemplo.com", r.text.substring(r.spans[0].start, r.spans[0].end))
    }

    @Test
    fun `dos urls en la misma linea`() {
        val r = Markdown.parseInline("http://a.com y http://b.com")
        assertEquals(2, r.spans.size)
        assertEquals("http://a.com", r.spans[0].url)
        assertEquals("http://b.com", r.spans[1].url)
    }

    /** El formato explícito manda: su texto no se toca aunque la url vaya dentro. */
    @Test
    fun `un enlace con corchetes sigue ganando al automatico`() {
        val r = Markdown.parseInline("mira [aqui](https://ejemplo.com) ya")
        assertEquals("mira aqui ya", r.text)
        assertEquals(1, r.spans.size)
        assertEquals("https://ejemplo.com", r.spans[0].url)
    }

    @Test
    fun `una url dentro de codigo en linea no se enlaza`() {
        val r = Markdown.parseInline("usa `https://ejemplo.com` asi")
        assertEquals("usa https://ejemplo.com asi", r.text)
        assertEquals(1, r.spans.size)
        assertEquals(SpanKind.CODE, r.spans[0].kind)
    }

    @Test
    fun `una palabra que empieza por http pero no es url no se enlaza`() {
        val r = Markdown.parseInline("httpsomething no es nada")
        assertEquals("httpsomething no es nada", r.text)
        assertTrue(r.spans.isEmpty())
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

    // ---- Estilos anidados y combinados ----

    /**
     * El caso que no se sabía hacer antes: la marca de fuera se quedaba con el
     * texto crudo y los guiones bajos de dentro se veían tal cual.
     */
    @Test
    fun `la cursiva dentro de la negrita se interpreta`() {
        val r = Markdown.parseInline("**muy _importante_**")
        assertEquals("muy importante", r.text)

        val t = r.tramos()
        // "muy " solo negrita, "importante" negrita y cursiva.
        assertEquals(2, t.size)
        assertEquals(0, t[0].inicio)
        assertEquals(4, t[0].fin)
        assertTrue(t[0].tiene(SpanKind.BOLD))
        assertTrue(!t[0].tiene(SpanKind.ITALIC))

        assertEquals(4, t[1].inicio)
        assertEquals(14, t[1].fin)
        assertTrue(t[1].tiene(SpanKind.BOLD))
        assertTrue(t[1].tiene(SpanKind.ITALIC))
    }

    @Test
    fun `tres estilos a la vez sobre las mismas letras`() {
        val r = Markdown.parseInline("**~~_todo_~~**")
        assertEquals("todo", r.text)
        val t = r.tramos()
        assertEquals(1, t.size)
        assertTrue(t[0].tiene(SpanKind.BOLD))
        assertTrue(t[0].tiene(SpanKind.STRIKE))
        assertTrue(t[0].tiene(SpanKind.ITALIC))
    }

    @Test
    fun `dentro del codigo no se interpreta nada, ni anidado`() {
        val r = Markdown.parseInline("**a `b _c_ d` e**")
        assertEquals("a b _c_ d e", r.text)
        val t = r.tramos()
        assertTrue(t.none { it.tiene(SpanKind.ITALIC) })
        assertTrue(t.any { it.tiene(SpanKind.CODE) && it.tiene(SpanKind.BOLD) })
    }

    @Test
    fun `el doble guion bajo es negrita, como en Markdown`() {
        val r = Markdown.parseInline("esto __pesa__ mas")
        assertEquals("esto pesa mas", r.text)
        assertEquals(1, r.spans.size)
        assertEquals(SpanKind.BOLD, r.spans[0].kind)
    }

    /**
     * Una ristra de asteriscos pegados de cualquier sitio no puede tumbar la app
     * por recursión. Pasado el tope se copia tal cual, sin perder ni un carácter.
     */
    @Test
    fun `mil asteriscos no desbordan la pila`() {
        val bomba = "*".repeat(2000) + "x" + "*".repeat(2000)
        val r = Markdown.parseInline(bomba)
        assertTrue(r.text.contains("x"))
    }

    // ---- Tapado ----

    @Test
    fun `el tapado se reconoce`() {
        val r = Markdown.parseInline("la clave es ||hunter2|| ojo")
        assertEquals("la clave es hunter2 ojo", r.text)
        assertEquals(1, r.spans.size)
        assertEquals(SpanKind.SPOILER, r.spans[0].kind)
        assertEquals("hunter2", r.text.substring(r.spans[0].start, r.spans[0].end))
    }

    @Test
    fun `una barra suelta no es un tapado`() {
        val r = Markdown.parseInline("a | b || c")
        assertEquals("a | b || c", r.text)
        assertTrue(r.spans.isEmpty())
    }

    // ---- Bloques ----

    @Test
    fun `las lineas de cita seguidas son una sola cita`() {
        val blocks = Markdown.parse("> primera\n> segunda\n\nsuelto")
        assertEquals(2, blocks.size)
        assertEquals("primera\nsegunda", (blocks[0] as MarkdownBlock.Quote).content.text)
        assertEquals("suelto", (blocks[1] as MarkdownBlock.Paragraph).content.text)
    }

    @Test
    fun `la cita sin espacio tras el mayor que tambien vale`() {
        val blocks = Markdown.parse(">pegado")
        assertEquals("pegado", (blocks[0] as MarkdownBlock.Quote).content.text)
    }

    @Test
    fun `el lenguaje del bloque de codigo se conserva`() {
        val blocks = Markdown.parse("```kotlin\nval x = 1\n```")
        val code = blocks[0] as MarkdownBlock.Code
        assertEquals("kotlin", code.lenguaje)
        assertEquals("val x = 1", code.text)
    }

    @Test
    fun `un bloque de codigo sin lenguaje lo deja vacio`() {
        val blocks = Markdown.parse("```\nsuelto\n```")
        assertEquals("", (blocks[0] as MarkdownBlock.Code).lenguaje)
    }
}
