package com.forge.pixpin.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El troceado por bloques, las tablas y el menú de tipos.
 *
 * Lo que más se vigila aquí es que el troceo y el parser **no se separen**: son
 * dos recorridos del mismo texto con las mismas reglas, y si uno cambia y el
 * otro no, el editor en vivo pinta un bloque donde hay otro.
 */
class EditorVivoTest {

    private val documento = """
        # Un título

        Un párrafo normal
        que sigue en la línea de abajo.

        - una viñeta
        - [ ] una casilla

        | a | b |
        |:---|---:|
        | 1 | 2 |

        > una cita
        > de dos líneas

        ```kotlin
        val x = 1
        ```

        $$
        x^2
        $$

        :::plegable Detalles
        dentro
        :::

        ![foto](/tmp/a.png)

        ---
    """.trimIndent()

    // ---- Troceado ----

    /** Los trozos pegados en orden tienen que dar exactamente el original. */
    @Test
    fun `los trozos cubren el documento entero sin pisarse`() {
        val trozos = trozosDe(documento)
        assertEquals(documento, trozos.joinToString("") { it.de(documento) })

        var anterior = 0
        trozos.forEach {
            assertEquals("hay un hueco o un solape", anterior, it.desde)
            assertTrue(it.hasta > it.desde)
            anterior = it.hasta
        }
    }

    /**
     * La prueba que impide que el troceo y el parser se separen: interpretar
     * trozo a trozo tiene que dar lo mismo que interpretar de una vez.
     */
    @Test
    fun `interpretar por trozos da lo mismo que de una vez`() {
        val deUnaVez = Markdown.parse(documento)
        val porTrozos = trozosDe(documento).flatMap { Markdown.parse(it.de(documento)) }
        assertEquals(deUnaVez, porTrozos)
    }

    @Test
    fun `cada trozo lleva como mucho un bloque`() {
        trozosDe(documento).forEach { t ->
            val n = Markdown.parse(t.de(documento)).size
            assertTrue("un trozo trajo $n bloques: '${t.de(documento)}'", n <= 1)
        }
    }

    @Test
    fun `el cursor cae siempre en algun trozo`() {
        val trozos = trozosDe(documento)
        for (pos in documento.indices) {
            assertTrue("posicion $pos", trozoEn(trozos, pos) >= 0)
        }
    }

    @Test
    fun `texto vacio no revienta`() {
        assertTrue(trozosDe("").isEmpty())
        assertEquals(-1, trozoEn(emptyList(), 0))
    }

    // ---- El menú de tipos ----

    @Test
    fun `el menu sabe que es cada bloque`() {
        val casos = mapOf(
            "# hola" to TipoDeBloque.TITULO_1,
            "###### hola" to TipoDeBloque.TITULO_6,
            "> hola" to TipoDeBloque.CITA,
            "- hola" to TipoDeBloque.LISTA,
            "1. hola" to TipoDeBloque.NUMERADA,
            "- [ ] hola" to TipoDeBloque.TAREAS,
            ":::pie\nx\n:::" to TipoDeBloque.PIE,
            "```\nx\n```" to TipoDeBloque.CODIGO,
            "| a | b |\n|---|---|\n| 1 | 2 |" to TipoDeBloque.TABLA
        )
        casos.forEach { (texto, tipo) -> assertEquals(texto, tipo, Menus.tipoDe(texto)) }
        // Un párrafo es «ninguno», que es lo que marca «Texto» en el menú.
        assertEquals(null, Menus.tipoDe("solo texto"))
    }

    /** Su `turnInto`: cambia el envoltorio y respeta lo escrito. */
    @Test
    fun `convertir conserva el contenido`() {
        assertEquals("# hola", Menus.convertir("hola", TipoDeBloque.TITULO_1))
        assertEquals("> hola", Menus.convertir("# hola", TipoDeBloque.CITA))
        assertEquals("- [ ] hola", Menus.convertir("- hola", TipoDeBloque.TAREAS))
        assertEquals("hola", Menus.convertir("### hola", null))
    }

    @Test
    fun `convertir ida y vuelta deja el texto como estaba`() {
        val original = "una frase con **negrita**"
        TipoDeBloque.entries
            // El separador no lleva texto y los medios lo sustituyen: no hay
            // ida y vuelta que comprobar en ellos.
            .filterNot {
                Bloques.pideArchivo(it) ||
                    it == TipoDeBloque.SEPARADOR ||
                    it == TipoDeBloque.TABLA
            }
            .forEach { tipo ->
                val ida = Menus.convertir(original, tipo)
                val vuelta = Menus.convertir(ida, null)
                assertEquals("por $tipo", original, vuelta)
            }
    }

    /**
     * El fallo reportado: darle a «título» estando en una tabla escribía el
     * HTML de la tabla a la vista, como si fuera texto. Una tabla no es una
     * frase que se pueda envolver en una almohadilla.
     */
    @Test
    fun `una tabla no se convierte en titulo ni en otra cosa`() {
        val tabla = "| a | b |\n|---|---|\n| 1 | 2 |"
        listOf(
            TipoDeBloque.TITULO_1, TipoDeBloque.CITA, TipoDeBloque.LISTA,
            TipoDeBloque.TAREAS, TipoDeBloque.CODIGO, null
        ).forEach { tipo ->
            assertEquals("por $tipo", tabla, Menus.convertir(tabla, tipo))
        }
        assertTrue(Markdown.parse(Menus.convertir(tabla, null)).first() is MarkdownBlock.Tabla)
    }

    @Test
    fun `todos los bloques se alcanzan desde algun boton`() {
        val fuera = TipoDeBloque.entries.toSet() - Menus.alcanzables
        assertTrue("no se llega a: $fuera", fuera.isEmpty())
    }

    @Test
    fun `la barra ensena seis botones`() {
        assertEquals(6, Menus.familias.size)
        Menus.familias.forEach { assertTrue(Menus.de(it).isNotEmpty()) }
    }
}
