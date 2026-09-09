package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quitar páginas de un proyecto: qué se va y qué se queda. Ver [Proyectos.sinPaginas]. */
class QuitarPaginasTest {
    private val pdf = Hoja(id = "p1", pagina = 3)
    private val lienzo = Hoja(id = "l1", dibujo = "d1")
    private val notaCorta = Hoja(id = "n1", nota = "hola")
    private val notaLarga = Hoja(id = "n2", nota = (1..400).joinToString("\n\n") { "renglón $it" })
    private val proyecto = Proyecto(id = "x", nombre = "X", tocado = 0, hojas = listOf(pdf, lienzo, notaCorta, notaLarga))

    @Test fun `una pagina de pdf se va del proyecto`() {
        val q = Proyectos.sinPaginas(proyecto, setOf("p1//"), 5)
        assertEquals(listOf("l1", "n1", "n2"), q.proyecto.hojas.map { it.id })
        assertTrue(q.marcosPorDibujo.isEmpty())
        assertEquals(5, q.proyecto.tocado)
    }

    @Test fun `un marco de un lienzo borra el marco y la hoja se queda`() {
        val q = Proyectos.sinPaginas(proyecto, setOf("l1/m7/"), 5)
        assertEquals(4, q.proyecto.hojas.size)
        assertEquals(mapOf("d1" to setOf("m7")), q.marcosPorDibujo)
    }

    @Test fun `un lienzo sin marcos se va entero`() {
        val q = Proyectos.sinPaginas(proyecto, setOf("l1//"), 5)
        assertTrue(q.proyecto.hojas.none { it.id == "l1" })
        assertTrue(q.marcosPorDibujo.isEmpty())
    }

    @Test fun `una nota partida solo se va con todas sus paginas`() {
        val paginas = com.forge.pixpin.motormd.Paginado.deTexto(notaLarga.nota).size
        assertTrue("la nota de prueba tiene que estar partida", paginas > 1)
        val unaSola = Proyectos.sinPaginas(proyecto, setOf("n2//0"), 5)
        assertTrue(unaSola.proyecto.hojas.any { it.id == "n2" })
        val todas = (0 until paginas).map { "n2//$it" }.toSet()
        assertTrue(Proyectos.sinPaginas(proyecto, todas, 5).proyecto.hojas.none { it.id == "n2" })
    }

    @Test fun `lo que no esta marcado no se toca`() {
        val q = Proyectos.sinPaginas(proyecto, emptySet(), 5)
        assertEquals(proyecto, q.proyecto)
    }

    @Test fun `la escena sin marcos los marca borrados y deja lo demas`() {
        val marco = Element(id = "m7", type = ElementType.FRAME, x = 0.0, y = 0.0, width = 10.0, height = 10.0, seed = 1)
        val raya = Element(id = "r1", type = ElementType.LINE, x = 1.0, y = 1.0, width = 2.0, height = 2.0, seed = 2)
        val s = Scene(elements = listOf(marco, raya)).sinMarcos(setOf("m7"))
        assertTrue(s.elements.first { it.id == "m7" }.isDeleted)
        assertTrue(!s.elements.first { it.id == "r1" }.isDeleted)
        assertTrue(s.marcos.isEmpty())
    }
}
