package com.forge.pixpin.ia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Las cuentas de una tabla** (PC `revision_tabla.rs`), con sus mismas pruebas. */
class RevisionTablaTest {
    private val METRADOS = listOf(
        listOf("Partida", "Unidad", "Metrado", "P. unit. (S/)", "Parcial (S/)"),
        listOf("Muro de ladrillo KK", "m2", "180", "62.00", "11160.00"),
        listOf("Tarrajeo de muros", "m2", "120", "28.50", "3240.00"),
        listOf("Contrapiso e=4cm", "m2", "95", "34.00", "3230.00"),
        listOf("Pintura látex 2 manos", "m2", "300", "12.50", "3750.00"),
        listOf("Puerta contraplacada", "und", "8", "380.00", "3040.00"),
        listOf("Total", "", "", "", "24420.00")
    )

    @Test fun `el parcial mal y el total que cambia salen con lo que deberian decir`() {
        val e = RevisionTabla.revisar(METRADOS)
        assertEquals(e.toString(), 2, e.size)
        assertEquals(2 to 4, e[0].fila to e[0].columna)
        assertEquals(3420.0, e[0].deberia, 1e-6)
        assertTrue(e[0].texto.contains("Tarrajeo") && e[0].texto.contains("3420"))
        assertEquals(24600.0, e[1].deberia, 1e-6)
        val t = RevisionTabla.paraElModelo(METRADOS)!!
        assertTrue(t.contains("3420") && t.contains("24600"))
    }

    @Test fun `una tabla que cuadra no da errores y un texto no se revisa`() {
        val bien = METRADOS.map { it.toMutableList() }
        bien[2][4] = "3420.00"; bien[6][4] = "24600"
        assertTrue(RevisionTabla.revisar(bien).isEmpty())
        assertTrue(RevisionTabla.paraElModelo(bien)!!.contains("todo cuadra"))
        assertNull(RevisionTabla.paraElModelo(listOf(listOf("a", "b", "c"), listOf("d", "e", "f"), listOf("g", "h", "i"))))
        val notas = listOf(listOf("Item", "Costo", "x"), listOf("a", "100", "1"), listOf("b", "50", "1"), listOf("c", "25", "1"), listOf("Total", "180", "3"))
        assertTrue(RevisionTabla.revisar(notas).any { it.columna == 1 && kotlin.math.abs(it.deberia - 175.0) < 1e-6 })
    }
}
