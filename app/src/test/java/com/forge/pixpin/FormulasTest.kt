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

    /** El árbol en texto plano, sin los espacios que pone el compositor. */
    private fun texto(t: Pieza): String = plano(t).replace(" ", "")

    private fun plano(t: Pieza): String = when (t) {
        is Pieza.Texto -> t.texto
        is Pieza.Fila -> t.partes.joinToString("") { plano(it) }
        is Pieza.Fraccion -> "(${plano(t.arriba)}/${plano(t.abajo)})"
        is Pieza.ConIndices ->
            plano(t.base) + (t.arriba?.let { "^${plano(it)}" } ?: "") +
                (t.abajo?.let { "_${plano(it)}" } ?: "")
        is Pieza.Raiz -> "sqrt(${plano(t.dentro)})"
        is Pieza.Agrupado -> t.abre + plano(t.dentro) + t.cierra
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

    // ---- Escritura corriente: sin LaTeX ----

    /**
     * El cambio que quita el LaTeX de en medio: la barra hace una fracción de
     * verdad. `1/2` son dos teclas; `\frac{1}{2}` son doce y cuadrar llaves en
     * una pantalla táctil.
     */
    @Test
    fun `la barra hace una fraccion`() {
        val t = Formulas.leer("1/2")
        assertTrue("salio ${t::class.simpleName}", t is Pieza.Fraccion)
        assertEquals("(1/2)", texto(t))
    }

    /** En `1/2 + x` se suma la fracción entera, no el 2. */
    @Test
    fun `la fraccion se ata mas fuerte que la suma`() {
        val t = Formulas.leer("1/2 + x") as Pieza.Fila
        assertTrue(t.partes[0] is Pieza.Fraccion)
        assertTrue(texto(t).contains("(1/2)"))
    }

    @Test
    fun `los exponentes se escriben con el circunflejo de siempre`() {
        assertEquals("x^2", texto(Formulas.leer("x^2")))
        assertEquals("x^10", texto(Formulas.leer("x^{10}")))
    }

    @Test
    fun `las palabras corrientes hacen su simbolo`() {
        assertEquals("π", texto(Formulas.leer("pi")))
        assertEquals("∞", texto(Formulas.leer("inf")))
        assertEquals("α", texto(Formulas.leer("alpha")))
    }

    @Test
    fun `raiz y sqrt hacen lo mismo`() {
        assertTrue(Formulas.leer("raiz(9)") is Pieza.Raiz)
        assertTrue(Formulas.leer("sqrt(9)") is Pieza.Raiz)
        assertEquals("sqrt(x+1)", texto(Formulas.leer("raiz(x+1)")))
    }

    @Test
    fun `las parejas de signos se convierten`() {
        assertTrue(texto(Formulas.leer("a <= b")).contains("≤"))
        assertTrue(texto(Formulas.leer("a != b")).contains("≠"))
        assertTrue(texto(Formulas.leer("a +- b")).contains("±"))
        assertTrue(texto(Formulas.leer("a -> b")).contains("→"))
    }

    @Test
    fun `pegado significa multiplicado`() {
        val plano = texto(Formulas.leer("2x"))
        assertEquals("2x", plano)
        // Y sin signo de por medio: en un libro tampoco se pinta.
        assertTrue(!plano.contains("·"))
    }

    @Test
    fun `el asterisco si pinta su punto`() {
        assertTrue(texto(Formulas.leer("2*x")).contains("·"))
    }

    @Test
    fun `una funcion sale derecha y con su argumento`() {
        val plano = texto(Formulas.leer("sin(x)"))
        assertTrue(plano, plano.startsWith("sin"))
        assertTrue(plano, plano.contains("x"))
    }

    /** Lo de siempre tiene que seguir yendo: quien sepa LaTeX no pierde nada. */
    @Test
    fun `el latex de antes sigue funcionando`() {
        assertEquals("(a/b)", texto(Formulas.leer("\\frac{a}{b}")))
        assertEquals("π", texto(Formulas.leer("\\pi")))
        assertTrue(Formulas.leer("\\sqrt{x}") is Pieza.Raiz)
    }

    @Test
    fun `una formula de verdad escrita a lo facil`() {
        val plano = texto(Formulas.leer("x = (-b +- sqrt(b^2 - 4ac)) / (2a)"))
        assertTrue(plano, plano.contains("±"))
        assertTrue(plano, plano.contains("sqrt("))
        assertTrue(plano, plano.contains("b^2"))
    }

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
