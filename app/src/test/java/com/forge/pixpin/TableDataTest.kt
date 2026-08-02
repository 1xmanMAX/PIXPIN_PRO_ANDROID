package com.forge.pixpin.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableDataTest {

    private val hoja = "Nombre\tEdad\tCiudad\nAna\t31\tMadrid\nLuis\t45\tSevilla"

    @Test
    fun `reconoce lo pegado de una hoja de calculo`() {
        assertTrue(TableData.looksLikeTable(hoja))
    }

    @Test
    fun `separa filas y columnas`() {
        val t = TableData.parse(hoja)
        assertEquals(3, t.size)
        assertEquals(listOf("Nombre", "Edad", "Ciudad"), t[0])
        assertEquals(listOf("Luis", "45", "Sevilla"), t[2])
    }

    /**
     * Las filas cortas se rellenan o la rejilla se descuadra al pintarla. Pasa
     * de verdad: la última fila de un rango copiado suele venir incompleta.
     */
    @Test
    fun `las filas cortas se rellenan con celdas vacias`() {
        val t = TableData.parse("a\tb\tc\nd\te\tf\ng\th")
        assertEquals(3, t.size)
        assertEquals(3, t[2].size)
        assertEquals("", t[2][2])
    }

    /** Un tabulador suelto en texto corriente no puede convertirlo en tabla. */
    @Test
    fun `texto con algun tabulador suelto no es una tabla`() {
        assertFalse(TableData.looksLikeTable("hola\tmundo"))
        assertFalse(TableData.looksLikeTable("una linea normal\notra linea normal"))
        assertFalse(TableData.looksLikeTable("if (x) {\n\treturn 1\n}"))
    }

    @Test
    fun `una sola fila no es una tabla`() {
        assertFalse(TableData.looksLikeTable("a\tb\tc"))
    }

    @Test
    fun `texto vacio o nulo no es una tabla`() {
        assertFalse(TableData.looksLikeTable(null))
        assertFalse(TableData.looksLikeTable(""))
        assertTrue(TableData.parse(null).isEmpty())
    }

    // ---- Otros separadores: no todo lo que se copia trae tabuladores ----

    /** Copiar una tabla de una web o de un PDF suele dar barras verticales. */
    @Test
    fun `tabla con barras verticales`() {
        val md = "| Nombre | Edad |\n| --- | --- |\n| Ana | 31 |\n| Luis | 45 |"
        assertTrue(TableData.looksLikeTable(md))
        val t = TableData.parse(md)
        assertEquals(3, t.size) // la fila de guiones no cuenta
        assertEquals(listOf("Nombre", "Edad"), t[0])
        assertEquals(listOf("Ana", "31"), t[1])
    }

    @Test
    fun `tabla alineada con espacios`() {
        val txt = "Nombre    Edad\nAna       31\nLuis      45"
        assertTrue(TableData.looksLikeTable(txt))
        assertEquals(listOf("Ana", "31"), TableData.parse(txt)[1])
    }

    /** Una frase con guiones o barras sueltas no puede volverse tabla. */
    @Test
    fun `texto corriente con alguna barra no es tabla`() {
        assertFalse(TableData.looksLikeTable("esto o | aquello\nuna frase normal"))
        assertFalse(TableData.looksLikeTable("hola mundo\nadios mundo"))
    }

    @Test
    fun `gana el separador que estructura de verdad`() {
        // Tabuladores reales y una barra suelta dentro de una celda.
        val txt = "a\tb|c\nd\te|f"
        assertEquals(listOf("a", "b|c"), TableData.parse(txt)[0])
    }

    @Test
    fun `los saltos de Windows no estorban`() {
        assertTrue(TableData.looksLikeTable("a\tb\r\nc\td"))
        assertEquals(listOf("c", "d"), TableData.parse("a\tb\r\nc\td")[1])
    }
}
