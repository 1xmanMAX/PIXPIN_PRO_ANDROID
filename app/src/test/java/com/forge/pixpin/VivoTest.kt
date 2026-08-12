package com.forge.pixpin.motormd

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El puente entre el Markdown que se guarda y el texto limpio que se edita.
 *
 * Lo que se vigila aquí es que **nadie vea nunca una marca** y que aun así el
 * archivo siga siendo Markdown correcto: escribir en el editor y volver a leer
 * tiene que dejar el documento como estaba, salvo por lo que se escribió.
 */
class VivoTest {

    private val doc = """
        # Un título con **negrita**

        Un párrafo con *cursiva* y un [enlace](https://a.com).

        - una viñeta
        - [ ] una casilla

        | a | b |
        |---|---|
        | 1 | 2 |
    """.trimIndent()

    // ---- Lo que se ve no lleva marcas ----

    @Test
    fun `el contenido editable sale sin marcas`() {
        val titulo = Vivo.contenido(doc, 0)
        assertNotNull(titulo)
        assertEquals("Un título con negrita", titulo!!.text)
        assertTrue(titulo.tramos().any { it.tiene(SpanKind.BOLD) })
    }

    @Test
    fun `ningun bloque editable ensena una marca`() {
        trozosDe(doc).indices.forEach { i ->
            val c = Vivo.contenido(doc, i) ?: return@forEach
            listOf("**", "##", "](", "~~", "||").forEach { marca ->
                assertTrue("el bloque $i ensena '$marca': ${c.text}", !c.text.contains(marca))
            }
        }
    }

    // ---- Escribir y volver a guardar ----

    @Test
    fun `guardar sin cambiar nada deja el documento igual`() {
        var actual = doc
        trozosDe(doc).indices.forEach { i ->
            val c = Vivo.contenido(actual, i) ?: return@forEach
            actual = Vivo.conContenido(actual, i, c)
        }
        // Los bloques se vuelven a escribir en forma canónica, así que se
        // compara lo interpretado, que es lo que de verdad tiene que coincidir.
        assertEquals(Markdown.parse(doc), Markdown.parse(actual))
    }

    @Test
    fun `escribir una letra no toca los estilos de al lado`() {
        val c = Vivo.contenido(doc, 0)!!
        val nuevo = c.text + "!"
        val spans = Inline.desplazar(c.text, nuevo, c.spans)
        val guardado = Vivo.conContenido(doc, 0, InlineText(nuevo, spans))

        val vuelta = Vivo.contenido(guardado, 0)!!
        assertEquals("Un título con negrita!", vuelta.text)
        assertTrue(vuelta.tramos().any { it.tiene(SpanKind.BOLD) })
        assertTrue(Markdown.parse(guardado).first() is MarkdownBlock.Heading)
    }

    @Test
    fun `poner negrita no escribe asteriscos en lo que se ve`() {
        val c = Vivo.contenido(doc, 1)!!
        val spans = Inline.alternar(c.spans, 0, 2, SpanKind.BOLD)
        val guardado = Vivo.conContenido(doc, 1, InlineText(c.text, spans))

        val vuelta = Vivo.contenido(guardado, 1)!!
        assertEquals(c.text, vuelta.text)
        assertTrue(Inline.cubre(vuelta.spans, 0, 2, SpanKind.BOLD))
        // Y en el archivo sí están, que es lo que hace que el PDF y el SVG sigan
        // funcionando.
        assertTrue(guardado.contains("**"))
    }

    @Test
    fun `el bloque conserva su tipo al escribir`() {
        listOf(0 to TipoDeBloque.TITULO_1, 2 to TipoDeBloque.LISTA).forEach { (i, tipo) ->
            assertEquals(tipo, Vivo.tipo(doc, i))
            val c = Vivo.contenido(doc, i)!!
            val guardado = Vivo.conContenido(doc, i, InlineText(c.text + "x", c.spans))
            assertEquals("el bloque $i cambio de tipo", tipo, Vivo.tipo(guardado, i))
        }
    }

    @Test
    fun `el codigo no se escapa al guardarlo`() {
        val fuente = "```\nval a = b * c\n```"
        val c = Vivo.contenidoDelTrozo(fuente)!!
        assertEquals("val a = b * c", c.text)
        val guardado = Vivo.conContenido(fuente, 0, c)
        assertEquals("val a = b * c", (Markdown.parse(guardado).first() as MarkdownBlock.Code).text)
    }

    // ---- Bloques nuevos ----

    @Test
    fun `intro abre un bloque nuevo`() {
        val (nuevo, donde) = Vivo.bloqueNuevo(doc, 0)
        assertEquals(1, donde.bloque)
        assertTrue(trozosDe(nuevo).size > trozosDe(doc).size)
    }

    /** En una lista, el bloque nuevo sigue siendo lista. */
    @Test
    fun `la lista continua al abrir bloque`() {
        val texto = "- uno"
        val (nuevo, donde) = Vivo.bloqueNuevo(texto, 0)
        assertEquals(TipoDeBloque.LISTA, Vivo.tipo(nuevo, donde.bloque))
    }

    @Test
    fun `un titulo no continua como titulo`() {
        val texto = "# uno"
        val (nuevo, donde) = Vivo.bloqueNuevo(texto, 0)
        assertEquals(null, Vivo.tipo(nuevo, donde.bloque))
    }

    @Test
    fun `juntar con el de arriba conserva los dos textos`() {
        val texto = "uno\n\ndos"
        val (nuevo, donde) = Vivo.juntarConElDeArriba(texto, 1)!!
        assertEquals("unodos", Vivo.contenido(nuevo, 0)!!.text)
        assertEquals(TextRange(3), donde.seleccion)
    }

    @Test
    fun `en el primer bloque no hay nada arriba`() {
        assertEquals(null, Vivo.juntarConElDeArriba("uno", 0))
    }

    @Test
    fun `indices imposibles no revientan`() {
        Vivo.contenido(doc, 99)
        Vivo.conContenido(doc, 99, InlineText("x"))
        Vivo.bloqueNuevo("", 0)
        Vivo.juntarConElDeArriba("", 5)
    }
}
