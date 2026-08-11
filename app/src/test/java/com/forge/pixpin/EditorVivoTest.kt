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

    // ---- Tablas ----

    private val tabla = "| a | b |\n|:---|---:|\n| 1 | 2 |"

    @Test
    fun `anadir fila deja la tabla cuadrada`() {
        val r = Tablas.añadirFila(tabla)
        assertEquals(3 to 2, Tablas.tamaño(r))
        assertTrue(Tablas.esTabla(r))
        assertEquals(listOf(Alineacion.IZQUIERDA, Alineacion.DERECHA), Tablas.alineacionesDe(r))
    }

    @Test
    fun `anadir columna la mete en todas las filas`() {
        val r = Tablas.añadirColumna(tabla)
        assertEquals(2 to 3, Tablas.tamaño(r))
        Markdown.parse(r).first().let {
            val t = it as MarkdownBlock.Tabla
            t.filas.forEach { fila -> assertEquals(3, fila.size) }
        }
    }

    @Test
    fun `quitar columna se lleva su alineacion`() {
        val r = Tablas.quitarColumna(tabla, 0)
        assertEquals(2 to 1, Tablas.tamaño(r))
        assertEquals(listOf(Alineacion.DERECHA), Tablas.alineacionesDe(r))
    }

    @Test
    fun `la ultima fila y la ultima columna no se quitan`() {
        val minima = "| a |\n|---|"
        assertEquals(minima, Tablas.quitarFila(minima, 0))
        assertEquals(minima, Tablas.quitarColumna(minima, 0))
    }

    @Test
    fun `la alineacion rota y vuelve a empezar`() {
        var t = "| a |\n|---|\n| 1 |"
        assertEquals(Alineacion.IZQUIERDA, Tablas.alineacionesDe(t)[0])
        t = Tablas.rotarAlineacion(t, 0)
        assertEquals(Alineacion.CENTRO, Tablas.alineacionesDe(t)[0])
        t = Tablas.rotarAlineacion(t, 0)
        assertEquals(Alineacion.DERECHA, Tablas.alineacionesDe(t)[0])
        t = Tablas.rotarAlineacion(t, 0)
        assertEquals(Alineacion.IZQUIERDA, Tablas.alineacionesDe(t)[0])
    }

    /** Lo más fácil de estropear a mano: una fila corta y otra larga. */
    @Test
    fun `una tabla torcida se endereza al tocarla`() {
        val torcida = "| a | b | c |\n|---|---|---|\n| 1 |"
        val r = Tablas.añadirFila(torcida)
        val t = Markdown.parse(r).first() as MarkdownBlock.Tabla
        t.filas.forEach { assertEquals(3, it.size) }
    }

    @Test
    fun `lo que no es tabla se queda igual`() {
        val texto = "un párrafo"
        assertEquals(texto, Tablas.añadirFila(texto))
        assertEquals(texto, Tablas.quitarColumna(texto, 0))
        assertTrue(!Tablas.esTabla(texto))
    }

    @Test
    fun `la columna del cursor se calcula desde el principio de su linea`() {
        val t = "| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals(0, Tablas.columnaDe(t, t.indexOf("a")))
        assertEquals(1, Tablas.columnaDe(t, t.indexOf("b")))
        assertEquals(1, Tablas.columnaDe(t, t.indexOf("2")))
    }

    @Test
    fun `una tabla nueva sale usable`() {
        val t = Tablas.nueva(3, 2)
        assertTrue(Tablas.esTabla(t))
        assertEquals(3 to 2, Tablas.tamaño(t))
        assertTrue(Markdown.parse(t).first() is MarkdownBlock.Tabla)
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
            tabla to TipoDeBloque.TABLA
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
