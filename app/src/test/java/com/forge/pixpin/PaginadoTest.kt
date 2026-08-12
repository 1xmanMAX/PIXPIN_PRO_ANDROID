package com.forge.pixpin.motormd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El troceado de una nota en páginas.
 *
 * Lo que se vigila es que **no se pierda ni se repita nada**: las páginas
 * pegadas tienen que dar exactamente los bloques de partida, en el mismo orden.
 * Si eso se cumple, dónde caiga el corte es cuestión de gusto; si no, la nota
 * que alguien entrega no es la que escribió.
 */
class PaginadoTest {

    private fun nota(renglones: Int): List<MarkdownBlock> =
        (1..renglones).map { MarkdownBlock.Paragraph(InlineText("párrafo $it")) }

    @Test
    fun `una nota corta cabe en una pagina`() {
        val p = Paginado.paginas(nota(5))
        assertEquals(1, p.size)
        assertEquals(5, p[0].size)
    }

    @Test
    fun `una nota vacia sigue teniendo una pagina`() {
        assertEquals(1, Paginado.paginas(emptyList()).size)
        assertEquals(1, Paginado.cuantasPaginas(""))
    }

    /** La invariante que importa: pegadas, las páginas son la nota entera. */
    @Test
    fun `las paginas pegadas dan la nota original`() {
        val original = nota(200)
        val juntas = Paginado.paginas(original).flatten()
        assertEquals(original, juntas)
    }

    @Test
    fun `ninguna pagina se pasa del tope, salvo un bloque enorme`() {
        val original = nota(200)
        Paginado.paginas(original, porPagina = 20).forEach { pagina ->
            val alto = pagina.sumOf { Paginado.renglones(it) }
            assertTrue("una pagina de $alto renglones", alto <= 20 || pagina.size == 1)
        }
    }

    @Test
    fun `una nota larga se parte en varias`() {
        assertTrue(Paginado.paginas(nota(200)).size > 3)
    }

    /**
     * Un bloque no se parte por la mitad. Una tabla partida pierde su cabecera y
     * un párrafo partido deja una frase a medias.
     */
    @Test
    fun `un bloque que no cabe se va entero a la pagina siguiente`() {
        val tabla = Tablas.nueva(10, 3)
        val bloques = nota(15) + tabla + nota(2)
        val paginas = Paginado.paginas(bloques, porPagina = 20)

        val conLaTabla = paginas.first { p -> p.any { it is MarkdownBlock.Tabla } }
        assertEquals("la tabla no empezo pagina", tabla, conLaTabla.first())
    }

    @Test
    fun `un bloque mas grande que la pagina se queda solo en la suya`() {
        val enorme = MarkdownBlock.Code("línea\n".repeat(60))
        val paginas = Paginado.paginas(nota(3) + enorme + nota(3), porPagina = 20)
        val suya = paginas.first { it.contains(enorme) }
        assertEquals(1, suya.size)
    }

    // ---- Lo que ocupa cada cosa ----

    @Test
    fun `un titulo ocupa mas que un parrafo`() {
        val titulo = MarkdownBlock.Heading(1, InlineText("Título"))
        val parrafo = MarkdownBlock.Paragraph(InlineText("Título"))
        assertTrue(Paginado.renglones(titulo) > Paginado.renglones(parrafo))
    }

    @Test
    fun `un parrafo largo ocupa mas de un renglon`() {
        val corto = MarkdownBlock.Paragraph(InlineText("hola"))
        val largo = MarkdownBlock.Paragraph(InlineText("hola ".repeat(60)))
        assertTrue(Paginado.renglones(largo) > Paginado.renglones(corto))
    }

    @Test
    fun `una tabla ocupa por filas`() {
        val chica = Tablas.nueva(2, 2)
        val grande = Tablas.nueva(12, 2)
        assertTrue(Paginado.renglones(grande) > Paginado.renglones(chica))
    }

    /** Un plegable está cerrado: ocupa su título, no lo que esconde. */
    @Test
    fun `un plegable cuenta solo su titulo`() {
        val dentro = nota(20)
        val plegable = MarkdownBlock.Caja(TipoDeCaja.PLEGABLE, "Ver", dentro)
        assertTrue(Paginado.renglones(plegable) < 5)
    }

    @Test
    fun `una caja abierta cuenta lo que lleva dentro`() {
        val dentro = nota(10)
        val caja = MarkdownBlock.Caja(TipoDeCaja.PIE, "", dentro)
        assertTrue(Paginado.renglones(caja) >= 10)
    }

    @Test
    fun `contar paginas de un texto de verdad`() {
        val texto = (1..120).joinToString("\n\n") { "Párrafo número $it de la nota." }
        assertTrue(Paginado.cuantasPaginas(texto) >= 2)
    }

    // ---- Las páginas que saben de dónde salen ----

    private fun textoLargo(cuantos: Int = 120): String =
        (1..cuantos).joinToString("\n\n") { "Párrafo número $it de la nota." }

    @Test
    fun `un texto vacio tiene una pagina`() {
        assertEquals(1, Paginado.deTexto("").size)
        assertEquals(1, Paginado.deTexto(null).size)
    }

    /**
     * La misma invariante de antes, dicha en letras: las páginas pegadas por sus
     * extremos tienen que dar el texto entero, sin huecos ni solapes.
     */
    @Test
    fun `las paginas cubren el texto entero sin pisarse`() {
        val texto = textoLargo()
        val paginas = Paginado.deTexto(texto)
        assertTrue(paginas.size > 1)
        assertEquals(0, paginas.first().desde)
        assertEquals(texto.length, paginas.last().hasta)
        paginas.zipWithNext { a, b -> assertEquals(a.hasta, b.desde) }
        assertEquals(texto, paginas.joinToString("") { texto.substring(it.desde, it.hasta) })
    }

    @Test
    fun `cada pagina empieza donde empieza un bloque`() {
        val texto = textoLargo()
        val inicios = trozosDe(texto).map { it.desde }.toSet()
        Paginado.deTexto(texto).forEach { assertTrue(it.desde in inicios) }
    }

    @Test
    fun `contar paginas coincide con las paginas que salen`() {
        val texto = textoLargo()
        assertEquals(Paginado.cuantasPaginas(texto), Paginado.deTexto(texto).size)
    }

    /**
     * Lo que se pidió expresamente: una tabla grande se lleva **su** hoja, y ahí
     * es donde se ve entera en vez de repartida entre dos.
     */
    @Test
    fun `una tabla grande se lleva su propia pagina`() {
        val tabla = Tablas.aTexto(Tablas.nueva(14, 3))
        val texto = textoLargo(6) + "\n\n" + tabla + "\n\ncola"
        val paginas = Paginado.deTexto(texto, porPagina = 20)

        val suya = paginas.first { p -> p.bloques.any { it is MarkdownBlock.Tabla } }
        assertTrue("la tabla no empezó página", suya.bloques.first() is MarkdownBlock.Tabla)
    }

    @Test
    fun `los bloques de la pagina son los de su trozo de texto`() {
        val texto = textoLargo()
        Paginado.deTexto(texto).forEach { pagina ->
            val suyo = Markdown.parse(texto.substring(pagina.desde, pagina.hasta))
            assertEquals(suyo.size, pagina.bloques.size)
        }
    }
}
