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

    /**
     * **La viñeta vacía se puede quitar.** Intro dentro de una lista abre «- »,
     * y de ahí se sale con intro otra vez o con retroceso: las dos pasan por
     * quitarle el tipo, y a la vacía no se lo quitaba — el prefijo solo se
     * reconocía con algo detrás. Era la viñeta que no había forma de borrar.
     */
    @Test
    fun `intro en una vineta vacia la quita`() {
        val (conVineta, donde) = Vivo.bloqueNuevo("- uno", 0)
        assertEquals("- uno\n\n- \n", conVineta)
        val (doc, sitio) = Vivo.partir(conVineta, donde.bloque, InlineText(""), 0)
        assertEquals(null, Vivo.tipo(doc, sitio.bloque))
        assertEquals("", Vivo.contenido(doc, sitio.bloque)?.text)
        // Y no queda un renglón vacío de más detrás.
        assertEquals(2, trozosDe(doc).size)
        // Se puede seguir escribiendo ahí como párrafo.
        assertEquals("- uno\n\nhola", Vivo.conContenido(doc, sitio.bloque, InlineText("hola")))
    }

    @Test
    fun `retroceso en una vineta vacia la deja en parrafo`() {
        val doc = "- uno\n\n- \n"
        assertEquals(TipoDeBloque.LISTA, Vivo.tipo(doc, 1))
        val sinTipo = Vivo.quitarTipo(doc, 1)
        assertEquals(null, Vivo.tipo(sinTipo, 1))
        assertEquals("", Vivo.contenido(sinTipo, 1)?.text)
        // Y un segundo retroceso ya se junta con la de arriba.
        val (juntos, sitio) = Vivo.juntarConElDeArriba(sinTipo, 1)!!
        assertEquals("- uno", juntos)
        assertEquals(0, sitio.bloque)
    }

    /** Las tres marcas de viñeta, vacías, se reconocen igual. */
    @Test
    fun `las tres vinetas vacias se quitan`() {
        for (marca in listOf("-", "*", "+")) {
            val doc = "$marca uno\n\n$marca \n"
            val sinTipo = Vivo.quitarTipo(doc, 1)
            assertEquals("con «$marca»", null, Vivo.tipo(sinTipo, 1))
        }
    }

    /** En medio del documento, quitar la viñeta vacía conserva el bloque de abajo. */
    @Test
    fun `quitar una vineta vacia en medio no toca lo de abajo`() {
        val doc = "- uno\n\n- \n\n- tres"
        val sinTipo = Vivo.quitarTipo(doc, 1)
        // El renglón vacío se queda como párrafo vacío (más su separador, que en el
        // modelo de trozos es otro renglón) y lo de abajo sigue siendo la misma viñeta.
        assertEquals(null, Vivo.tipo(sinTipo, 1))
        assertEquals("", Vivo.contenido(sinTipo, 1)?.text)
        val ultimo = trozosDe(sinTipo).size - 1
        assertEquals("tres", Vivo.contenido(sinTipo, ultimo)?.text)
        assertEquals(TipoDeBloque.LISTA, Vivo.tipo(sinTipo, ultimo))
        // Y escribir en el hueco no se come la viñeta de abajo.
        val escrito = Vivo.conContenido(sinTipo, 1, InlineText("x"))
        assertEquals("tres", Vivo.contenido(escrito, trozosDe(escrito).size - 1)?.text)
    }

    /**
     * **Una nota vacía se puede escribir.** El documento vacío no tenía ningún
     * bloque, así que no había campo ni teclado: era «no puedo escribir».
     */
    @Test
    fun `un documento vacio es un bloque vacio donde escribir`() {
        assertEquals(1, Vivo.trozos("").size)
        assertEquals("", Vivo.contenido("", 0)?.text)
        assertEquals(null, Vivo.tipo("", 0))
        assertEquals("hola", Vivo.conContenido("", 0, InlineText("hola")))
        // Se le puede dar tipo directamente.
        val titulo = Vivo.conContenido(Menus.convertir("", TipoDeBloque.TITULO_1), 0, InlineText("Mi nota"))
        assertEquals("# Mi nota", titulo)
        // E intro abre el segundo renglón sin fabricar tres vacíos.
        val (doc, sitio) = Vivo.bloqueNuevo("", 0)
        assertEquals(1, sitio.bloque)
        assertEquals(2, Vivo.trozos(doc).size)
        assertEquals("", Vivo.contenido(doc, 1)?.text)
    }

    /** Y lo que ya existía sigue igual: con texto, los trozos son los de siempre. */
    @Test
    fun `con texto los trozos de editar son los de leer`() {
        assertEquals(trozosDe(doc), Vivo.trozos(doc))
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
