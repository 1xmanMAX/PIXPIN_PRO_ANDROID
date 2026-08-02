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

    /** Las filas cortas se rellenan o la rejilla se descuadra al pintarla. */
    @Test
    fun `las filas cortas se rellenan con celdas vacias`() {
        val t = TableData.parse("a\tb\tc\nd\te")
        assertEquals(3, t[1].size)
        assertEquals("", t[1][2])
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

    @Test
    fun `los saltos de Windows no estorban`() {
        assertTrue(TableData.looksLikeTable("a\tb\r\nc\td"))
        assertEquals(listOf("c", "d"), TableData.parse("a\tb\r\nc\td")[1])
    }
}
