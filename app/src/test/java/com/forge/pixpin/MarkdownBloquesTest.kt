package com.forge.pixpin.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El parser de los bloques nuevos: casillas, tablas, cajas, fórmulas y medios. */
class MarkdownBloquesTest {

    // ---- Títulos ----

    @Test
    fun `los seis niveles de titulo`() {
        val texto = (1..6).joinToString("\n") { "${"#".repeat(it)} nivel $it" }
        val bloques = Markdown.parse(texto)
        assertEquals(6, bloques.size)
        bloques.forEachIndexed { i, b ->
            assertEquals(i + 1, (b as MarkdownBlock.Heading).level)
            assertEquals("nivel ${i + 1}", b.content.text)
        }
    }

    @Test
    fun `siete almohadillas ya no es titulo`() {
        val bloques = Markdown.parse("####### nope")
        assertTrue(bloques[0] is MarkdownBlock.Paragraph)
    }

    // ---- Casillas ----

    @Test
    fun `las casillas marcadas y sin marcar`() {
        val bloques = Markdown.parse("- [ ] pendiente\n- [x] hecha\n- [X] tambien")
        assertEquals(3, bloques.size)
        assertFalse((bloques[0] as MarkdownBlock.Tarea).hecha)
        assertEquals("pendiente", (bloques[0] as MarkdownBlock.Tarea).content.text)
        assertTrue((bloques[1] as MarkdownBlock.Tarea).hecha)
        assertTrue((bloques[2] as MarkdownBlock.Tarea).hecha)
    }

    /** `- [ ] x` empieza por `- `, así que la casilla tiene que ir antes. */
    @Test
    fun `una casilla no se confunde con una vinieta`() {
        assertTrue(Markdown.parse("- [ ] tarea")[0] is MarkdownBlock.Tarea)
        assertTrue(Markdown.parse("- normal")[0] is MarkdownBlock.Bullet)
    }

    @Test
    fun `una casilla admite formato dentro`() {
        val t = Markdown.parse("- [x] **hecho** del todo")[0] as MarkdownBlock.Tarea
        assertEquals("hecho del todo", t.content.text)
        assertTrue(t.content.tramos().any { it.tiene(SpanKind.BOLD) })
    }

    // ---- Tablas ----

    @Test
    fun `una tabla con cabecera y alineaciones`() {
        val texto = """
            | Nombre | Cantidad | Precio |
            |:---|:---:|---:|
            | Tornillo | 12 | 0,30 |
            | Tuerca | 8 | 0,10 |
        """.trimIndent()
        val t = Markdown.parse(texto)[0] as MarkdownBlock.Tabla

        assertEquals(3, t.filas.size)
        assertTrue(t.cabecera)
        assertEquals("Nombre", t.filas[0][0].text)
        assertEquals("Tornillo", t.filas[1][0].text)
        assertEquals("0,10", t.filas[2][2].text)
        assertEquals(
            listOf(Alineacion.IZQUIERDA, Alineacion.CENTRO, Alineacion.DERECHA),
            t.alineaciones
        )
    }

    @Test
    fun `sin la fila de guiones no es una tabla`() {
        val bloques = Markdown.parse("| esto | no |\n| es tabla |")
        assertTrue(bloques.none { it is MarkdownBlock.Tabla })
    }

    /** Una expresión con barras no puede acabar descuartizada en columnas. */
    @Test
    fun `una linea suelta con barras sigue siendo texto`() {
        val bloques = Markdown.parse("el valor es |x| + |y|")
        assertTrue(bloques[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `las celdas admiten formato`() {
        val texto = "| **a** | b |\n|---|---|\n| c | d |"
        val t = Markdown.parse(texto)[0] as MarkdownBlock.Tabla
        assertEquals("a", t.filas[0][0].text)
        assertTrue(t.filas[0][0].tramos().any { it.tiene(SpanKind.BOLD) })
    }

    // ---- Fórmulas ----

    @Test
    fun `una formula entre dobles dolares`() {
        val f = Markdown.parse("$$\nx^2 + y^2 = r^2\n$$")[0] as MarkdownBlock.Formula
        assertEquals("x^2 + y^2 = r^2", f.latex)
    }

    @Test
    fun `una formula sin cerrar llega hasta el final sin perder nada`() {
        val f = Markdown.parse("$$\na + b")[0] as MarkdownBlock.Formula
        assertEquals("a + b", f.latex)
    }

    // ---- Medios ----

    @Test
    fun `la extension decide la clase del medio`() {
        val casos = mapOf(
            "/a/foto.jpg" to ClaseDeMedio.IMAGEN,
            "/a/clip.mp4" to ClaseDeMedio.VIDEO,
            "/a/voz.m4a" to ClaseDeMedio.AUDIO,
            "/a/informe.pdf" to ClaseDeMedio.ARCHIVO,
            "/a/sin_extension" to ClaseDeMedio.ARCHIVO
        )
        casos.forEach { (ruta, clase) ->
            val m = Markdown.parse("![x]($ruta)")[0] as MarkdownBlock.Medio
            assertEquals(ruta, clase, m.clase)
            assertEquals(ruta, m.ruta)
            assertEquals("x", m.alt)
        }
    }

    @Test
    fun `un medio sin ruta no es un medio`() {
        assertTrue(Markdown.parse("![vacio]()")[0] is MarkdownBlock.Paragraph)
    }

    // ---- Cajas ----

    @Test
    fun `un plegable con titulo y contenido`() {
        val c = Markdown.parse(":::plegable Ver mas\ndentro\n:::")[0] as MarkdownBlock.Caja
        assertEquals(TipoDeCaja.PLEGABLE, c.tipo)
        assertEquals("Ver mas", c.titulo)
        assertEquals("dentro", (c.dentro[0] as MarkdownBlock.Paragraph).content.text)
    }

    @Test
    fun `las cinco clases de caja`() {
        val casos = mapOf(
            "plegable" to TipoDeCaja.PLEGABLE,
            "detalles" to TipoDeCaja.PLEGABLE,
            "pie" to TipoDeCaja.PIE,
            "destacado" to TipoDeCaja.DESTACADO,
            "centro" to TipoDeCaja.CENTRO,
            "derecha" to TipoDeCaja.DERECHA
        )
        casos.forEach { (nombre, tipo) ->
            val c = Markdown.parse(":::$nombre\nx\n:::")[0] as MarkdownBlock.Caja
            assertEquals(nombre, tipo, c.tipo)
        }
    }

    @Test
    fun `una caja desconocida se queda como texto`() {
        assertTrue(Markdown.parse(":::loquesea\nx\n:::")[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `una caja dentro de otra`() {
        val texto = ":::centro\n:::destacado\nhola\n:::\n:::"
        val fuera = Markdown.parse(texto)[0] as MarkdownBlock.Caja
        assertEquals(TipoDeCaja.CENTRO, fuera.tipo)
        val dentro = fuera.dentro[0] as MarkdownBlock.Caja
        assertEquals(TipoDeCaja.DESTACADO, dentro.tipo)
        assertEquals("hola", (dentro.dentro[0] as MarkdownBlock.Paragraph).content.text)
    }

    @Test
    fun `una caja sin cerrar llega hasta el final sin perder nada`() {
        val c = Markdown.parse(":::pie\nqueda esto")[0] as MarkdownBlock.Caja
        assertEquals("queda esto", (c.dentro[0] as MarkdownBlock.Paragraph).content.text)
    }

    @Test
    fun `una caja lleva cualquier bloque dentro`() {
        val texto = ":::plegable Tabla\n| a | b |\n|---|---|\n| 1 | 2 |\n:::"
        val c = Markdown.parse(texto)[0] as MarkdownBlock.Caja
        assertTrue(c.dentro[0] is MarkdownBlock.Tabla)
    }

    // ---- Que nada de esto rompa lo de antes ----

    @Test
    fun `el texto plano sigue saliendo intacto`() {
        val plano = "Comprar 2 * 3 tornillos en a/b y mirar el 12/03."
        val bloques = Markdown.parse(plano)
        assertEquals(1, bloques.size)
        assertEquals(plano, (bloques[0] as MarkdownBlock.Paragraph).content.text)
    }

    @Test
    fun `texto vacio no revienta`() {
        assertTrue(Markdown.parse("").isEmpty())
    }
}
