package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las páginas que enseña un proyecto, ya desplegadas de sus lienzos. */
class HojasDelProyectoTest {

    private fun marco(id: String, nombre: String? = null) = Element(
        id = id, type = ElementType.FRAME,
        x = 0.0, y = 0.0, width = 100.0, height = 100.0, seed = 1, text = nombre
    )

    private fun escenaCon(vararg marcos: Element) = Scene(elements = marcos.toList())

    @Test
    fun `una nota es una pagina`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "n1", nota = "algo")))
        val paginas = HojasDelProyecto.paginas(p) { null }
        assertEquals(1, paginas.size)
        assertNull(paginas[0].marco)
    }

    @Test
    fun `una pagina del pdf se numera`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", pagina = 4)))
        assertEquals("5", HojasDelProyecto.paginas(p) { null }[0].nombre)
    }

    /** Un lienzo con cuatro marcos son cuatro láminas, no una. */
    @Test
    fun `un lienzo se abre en sus marcos`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", dibujo = "d1")))
        val paginas = HojasDelProyecto.paginas(p) {
            escenaCon(marco("m1"), marco("m2"), marco("m3"))
        }
        assertEquals(3, paginas.size)
        assertEquals(listOf("m1", "m2", "m3"), paginas.map { it.marco })
    }

    @Test
    fun `un lienzo sin marcos es una sola lamina`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", dibujo = "d1")))
        val paginas = HojasDelProyecto.paginas(p) { escenaCon() }
        assertEquals(1, paginas.size)
        assertNull(paginas[0].marco)
    }

    @Test
    fun `un lienzo que no se puede leer sigue siendo una lamina`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", dibujo = "d1")))
        assertEquals(1, HojasDelProyecto.paginas(p) { null }.size)
    }

    @Test
    fun `el marco con nombre lo enseña`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", dibujo = "d1")))
        val paginas = HojasDelProyecto.paginas(p) { escenaCon(marco("m1", "Planta baja")) }
        assertEquals("Planta baja", paginas[0].nombre)
    }

    /** Dos marcos del mismo lienzo son la misma hoja: sin el marco en la clave,
     * marcar uno marcaría los dos. */
    @Test
    fun `cada marco tiene su propia clave`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", dibujo = "d1")))
        val paginas = HojasDelProyecto.paginas(p) { escenaCon(marco("m1"), marco("m2")) }
        assertNotEquals(paginas[0].clave, paginas[1].clave)
    }

    @Test
    fun `se desplegan en el orden del proyecto`() {
        val p = Proyecto(
            id = "p",
            nombre = "Obra",
            hojas = listOf(
                Hoja(id = "n1", nota = "nota"),
                Hoja(id = "h1", dibujo = "d1"),
                Hoja(id = "h2", pagina = 0)
            )
        )
        val paginas = HojasDelProyecto.paginas(p) { escenaCon(marco("m1"), marco("m2")) }
        assertEquals(listOf("n1", "h1", "h1", "h2"), paginas.map { it.hoja.id })
    }

    // ---- El color ----

    /**
     * Lo que importa del color es que **no cambie**: el mismo lienzo, el mismo
     * color siempre y en todas partes. Que dos lienzos distintos coincidan es
     * posible —la paleta es finita— y no es un fallo; lo que sí lo sería es que
     * un proyecto con varios lienzos los pintara todos igual.
     */
    @Test
    fun `el color de un lienzo no cambia nunca`() {
        val uno = Hoja(id = "a", dibujo = "dib-uno")
        assertNotNull(HojasDelProyecto.colorDe(uno))
        repeat(5) {
            assertEquals(HojasDelProyecto.colorDe(uno), HojasDelProyecto.colorDe(uno))
        }
        // Y no depende de la hoja, solo del lienzo: la misma tela, el mismo color.
        val otraHoja = Hoja(id = "z", dibujo = "dib-uno")
        assertEquals(HojasDelProyecto.colorDe(uno), HojasDelProyecto.colorDe(otraHoja))
    }

    @Test
    fun `varios lienzos no salen todos del mismo color`() {
        val colores = (1..20)
            .map { HojasDelProyecto.colorDe(Hoja(id = "h$it", dibujo = "dib-$it")) }
            .toSet()
        assertTrue("todos iguales: $colores", colores.size >= 4)
    }

    @Test
    fun `las paginas del pdf y las notas no llevan color`() {
        assertNull(HojasDelProyecto.colorDe(Hoja(id = "h", pagina = 2, dibujo = "d")))
        assertNull(HojasDelProyecto.colorDe(Hoja(id = "n", nota = "x")))
    }

    private fun assertNotNull(v: Any?) = assertTrue(v != null)
}
