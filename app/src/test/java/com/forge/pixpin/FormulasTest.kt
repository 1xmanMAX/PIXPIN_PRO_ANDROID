package com.forge.pixpin.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El intérprete de fórmulas.
 *
 * Lo que se comprueba es la **forma del árbol**, porque de ahí sale lo que se
 * ve: si `x^2` no queda como una base con algo encima, el 2 se pintará al lado y
 * del mismo tamaño, que es justo lo que pasaba antes.
 */
class FormulasTest {

    private fun texto(t: Pieza): String = when (t) {
        is Pieza.Texto -> t.texto
        is Pieza.Fila -> t.partes.joinToString("") { texto(it) }
        is Pieza.Fraccion -> "(${texto(t.arriba)}/${texto(t.abajo)})"
        is Pieza.ConIndices ->
            texto(t.base) + (t.arriba?.let { "^${texto(it)}" } ?: "") +
                (t.abajo?.let { "_${texto(it)}" } ?: "")
        is Pieza.Raiz -> "sqrt(${texto(t.dentro)})"
        is Pieza.Agrupado -> t.abre + texto(t.dentro) + t.cierra
    }

    // ---- Exponentes y subíndices ----

    @Test
    fun `un exponente se cuelga de su base`() {
        val t = Formulas.leer("x^2")
        assertTrue("salio ${t::class.simpleName}", t is Pieza.ConIndices)
        assertEquals("x^2", texto(t))
    }

    @Test
    fun `el exponente entre llaves puede ser largo`() {
        assertEquals("x^n+1", texto(Formulas.leer("x^{n+1}")))
    }

    @Test
    fun `arriba y abajo a la vez sobre la misma base`() {
        val t = Formulas.leer("x^2_i") as Pieza.ConIndices
        assertEquals("2", texto(t.arriba!!))
        assertEquals("i", texto(t.abajo!!))
    }

    @Test
    fun `un subindice solo`() {
        val t = Formulas.leer("a_1") as Pieza.ConIndices
        assertEquals("1", texto(t.abajo!!))
        assertEquals(null, t.arriba)
    }

    // ---- Fracciones ----

    @Test
    fun `una fraccion tiene arriba y abajo`() {
        val t = Formulas.leer("\\frac{a}{b}") as Pieza.Fraccion
        assertEquals("a", texto(t.arriba))
        assertEquals("b", texto(t.abajo))
    }

    @Test
    fun `una fraccion dentro de otra`() {
        val t = Formulas.leer("\\frac{\\frac{a}{b}}{c}") as Pieza.Fraccion
        assertTrue(t.arriba is Pieza.Fraccion)
        assertEquals("((a/b)/c)", texto(t))
    }

    @Test
    fun `la fraccion sin llaves coge un caracter de cada lado`() {
        assertEquals("(1/2)", texto(Formulas.leer("\\frac12")))
    }

    // ---- Raíces ----

    @Test
    fun `una raiz normal y una con indice`() {
        assertEquals("sqrt(x+1)", texto(Formulas.leer("\\sqrt{x+1}")))
        val cubica = Formulas.leer("\\sqrt[3]{x}") as Pieza.Raiz
        assertEquals("3", texto(cubica.indice!!))
        assertEquals("x", texto(cubica.dentro))
    }

    // ---- Símbolos ----

    @Test
    fun `las letras griegas y los operadores salen como simbolos`() {
        assertEquals("π", texto(Formulas.leer("\\pi")))
        assertEquals("≤", texto(Formulas.leer("\\leq")))
        assertEquals("∑", texto(Formulas.leer("\\sum")))
        assertEquals("∞", texto(Formulas.leer("\\infty")))
        assertEquals("Δ", texto(Formulas.leer("\\Delta")))
    }

    @Test
    fun `las funciones se quedan como palabra`() {
        assertEquals("sin", texto(Formulas.leer("\\sin")))
        assertEquals("log", texto(Formulas.leer("\\log")))
    }

    /** La promesa de siempre: lo que no se entienda, a la vista, no perdido. */
    @Test
    fun `un mandato desconocido se enseña tal cual`() {
        assertTrue(texto(Formulas.leer("\\loquesea")).contains("loquesea"))
    }

    // ---- Letras en cursiva, números derechos ----

    @Test
    fun `las letras van en cursiva y los numeros no`() {
        val t = Formulas.leer("2x") as Pieza.Fila
        val dos = t.partes[0] as Pieza.Texto
        val equis = t.partes[1] as Pieza.Texto
        assertTrue(!dos.cursiva)
        assertTrue(equis.cursiva)
    }

    // ---- Cosas de verdad ----

    @Test
    fun `una formula completa se entiende entera`() {
        val t = Formulas.leer("x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}")
        val plano = texto(t)
        assertTrue(plano, plano.contains("±"))
        assertTrue(plano, plano.contains("sqrt("))
        assertTrue(plano, plano.contains("b^2"))
        assertTrue(plano, plano.contains("/2"))
    }

    @Test
    fun `los parentesis agrupan`() {
        val t = Formulas.leer("(a+b)^2") as Pieza.ConIndices
        assertTrue(t.base is Pieza.Agrupado)
        assertEquals("2", texto(t.arriba!!))
    }

    // ---- Basura ----

    @Test
    fun `nada de esto revienta`() {
        listOf("", "^", "_", "{", "}", "\\", "\\frac", "\\sqrt[", "((((", "x^^2")
            .forEach { Formulas.leer(it) }
    }

    @Test
    fun `una llave sin cerrar no pierde lo de dentro`() {
        assertTrue(texto(Formulas.leer("x^{2")).contains("2"))
    }
}
