package com.forge.pixpin.motormd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deshacer y rehacer, con el agrupado por palabras de cualquier editor. */
class HistorialTest {

    private fun conPasos(vararg textos: String): Historial {
        val h = Historial()
        h.empezar(textos.first())
        textos.drop(1).forEach { h.anota(it) }
        return h
    }

    @Test
    fun `recien empezado no hay nada que deshacer`() {
        val h = Historial().also { it.empezar("hola") }
        assertFalse(h.puedeDeshacer)
        assertFalse(h.puedeRehacer)
        assertNull(h.deshacer("hola"))
    }

    @Test
    fun `un cambio grande es un paso`() {
        val h = conPasos("", "**todo en negrita**")
        assertTrue(h.puedeDeshacer)
        assertEquals("", h.deshacer("**todo en negrita**"))
    }

    /** Escribir «hola» son cuatro cambios y UN paso atrás, no cuatro. */
    @Test
    fun `escribir una palabra se agrupa en un solo paso`() {
        val h = conPasos("", "h", "ho", "hol", "hola")
        assertEquals("", h.deshacer("hola"))
        assertFalse(h.puedeDeshacer)
    }

    @Test
    fun `el espacio corta el grupo`() {
        val h = conPasos("", "h", "ho", "hola", "hola ", "hola m", "hola mundo")
        // Atrás una vez deja la primera palabra, no el texto vacío.
        val atras = h.deshacer("hola mundo")
        assertTrue("quedó '$atras'", atras!!.startsWith("hola"))
        assertTrue(atras.length < "hola mundo".length)
    }

    @Test
    fun `borrar letra a letra tambien se agrupa`() {
        val h = conPasos("hola", "hol", "ho", "h", "")
        assertEquals("hola", h.deshacer(""))
    }

    @Test
    fun `rehacer devuelve lo deshecho`() {
        val h = conPasos("uno", "uno y dos")
        assertEquals("uno", h.deshacer("uno y dos"))
        assertTrue(h.puedeRehacer)
        assertEquals("uno y dos", h.rehacer("uno"))
    }

    /** Escribir tras deshacer tira la rama: es lo que hace todo el mundo. */
    @Test
    fun `un cambio nuevo se lleva lo rehecho`() {
        val h = conPasos("uno", "uno y dos")
        h.deshacer("uno y dos")
        assertTrue(h.puedeRehacer)
        h.anota("otra cosa distinta")
        assertFalse(h.puedeRehacer)
    }

    @Test
    fun `anotar lo mismo no cuenta como paso`() {
        val h = conPasos("uno", "uno", "uno")
        assertFalse(h.puedeDeshacer)
    }

    @Test
    fun `el historial no crece sin limite`() {
        val h = Historial(maximo = 5)
        h.empezar("")
        // Cambios grandes para que no se agrupen entre sí.
        repeat(40) { h.anota("linea $it\n".repeat(it + 1)) }
        var vueltas = 0
        var actual = "x"
        while (h.puedeDeshacer && vueltas < 100) {
            actual = h.deshacer(actual)!!
            vueltas++
        }
        assertTrue("dio $vueltas vueltas", vueltas <= 5)
    }

    @Test
    fun `deshacer y rehacer alternados no se descuadran`() {
        val h = conPasos("a", "a b", "a b c")
        assertEquals("a b", h.deshacer("a b c"))
        assertEquals("a b c", h.rehacer("a b"))
        assertEquals("a b", h.deshacer("a b c"))
        assertEquals("a", h.deshacer("a b"))
        assertEquals("a b", h.rehacer("a"))
    }
}
