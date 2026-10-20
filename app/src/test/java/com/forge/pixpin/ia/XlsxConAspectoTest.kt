package com.forge.pixpin.ia

import com.forge.pixpin.asistente.DocumentosAcciones
import com.forge.pixpin.motor.ImportarHojas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** **La tabla de una foto con su aspecto, al Excel** (PC `escribir_excel_con`): fondos y anchos que vuelven. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XlsxConAspectoTest {
    @Test fun `los fondos y los anchos van al excel y se vuelven a leer`() {
        val filas = listOf(listOf("Nombre", "Edad"), listOf("Ana", "30"), listOf("", "41"))
        val fondos = listOf(listOf("#1f4e79", "#1F4E79"), listOf(null, null), listOf("#ffeeaa", null))
        val f = File.createTempFile("aspecto", ".xlsx")
        f.outputStream().use { Xlsx.escribir(it, "Tabla", filas, true, fondos, listOf(140, 70)) }
        val t = ImportarHojas.leer(f).single().tabla
        assertEquals("#1f4e79", t.estilos["A1"]?.f); assertEquals("#1f4e79", t.estilos["B1"]?.f)
        assertTrue("la cabecera sigue en negrita", t.estilos["A1"]?.n == true)
        // La celda vacía con fondo también está (el color es lo que se ve).
        assertEquals("#ffeeaa", t.estilos["A3"]?.f)
        assertNull(t.estilos["A2"]?.f)
        assertEquals(145, t.anchos["A"]); assertEquals(75, t.anchos["B"])
        // Sin aspecto, como antes: sin columnas ni colores.
        val g = File.createTempFile("llano", ".xlsx")
        g.outputStream().use { Xlsx.escribir(it, "Tabla", filas, true) }
        assertTrue(ImportarHojas.leer(g).single().tabla.estilos.values.none { it.f != null })
    }

    @Test fun `el json de la biblioteca`() {
        val t = DocumentosAcciones.leerTablaNativa("""{"filas":[["a","b"],["1","2"]],"cabecera":true,"fondos":[["#ff0000",null],[null,null]],"anchos":[90,60]}""")!!
        assertEquals(listOf("a", "b"), t.filas[0]); assertTrue(t.cabecera)
        assertEquals("#ff0000", t.fondos!![0][0]); assertNull(t.fondos!![0][1]); assertEquals(listOf(90, 60), t.anchos)
        // Sin rejilla que cuadre: sin fondos ni anchos. Y lo que no es una tabla, nada.
        assertNull(DocumentosAcciones.leerTablaNativa("""{"filas":[["a","b"],["1","2"]],"cabecera":false}""")!!.fondos)
        assertNull(DocumentosAcciones.leerTablaNativa("""{"error":"no se ve una tabla"}"""))
        assertNull(DocumentosAcciones.leerTablaNativa("""{"filas":[["solo"]],"cabecera":false}"""))
    }
}
