package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** **La ecuación tipografiada en el dibujo.** Ver [Ecuacion]. */
class EcuacionTest {

    private val medir: MedidaDeTexto = { texto, tamano -> texto.length * tamano * 0.6 to tamano * 1.2 }
    private val estilo = ItemStyle(fontSize = 20.0)

    private fun elementos(formula: String): List<Element> =
        Ecuacion.elementos("y", Formula.compilar(formula)!!, Pt(0.0, 0.0), estilo, medir)

    private fun textos(e: List<Element>) = e.filter { it.type == ElementType.TEXT }.map { it.text }
    private fun lineas(e: List<Element>) = e.filter { it.type == ElementType.LINE }

    @Test
    fun `una raiz lleva su signo dibujado y la raya encima`() {
        val e = elementos("sqrt(2x)")
        assertEquals(listOf("y =", "2", "x"), textos(e))
        val signo = lineas(e).single()
        // Cuatro puntos: el pie, la bajada, la subida y la raya que cubre lo de dentro.
        assertEquals(4, signo.points!!.size)
        val dentro = e.first { it.text == "2" }
        // La raya llega hasta pasado el final del argumento.
        val finDeLaRaya = signo.x + signo.points!!.last().x
        assertTrue(finDeLaRaya >= dentro.x + dentro.width)
        // Y nada de «sqrt» escrito.
        assertTrue(textos(e).none { it?.contains("sqrt") == true })
    }

    @Test
    fun `una fraccion pone el numerador sobre la barra y el denominador debajo`() {
        val e = elementos("1/x")
        val uno = e.first { it.text == "1" }
        val equis = e.first { it.text == "x" }
        val barra = lineas(e).single()
        assertTrue(uno.y + uno.height <= barra.y + 1e-9)
        assertTrue(equis.y >= barra.y - 1e-9)
        // La barra cubre a los dos.
        assertTrue(barra.width >= uno.width && barra.width >= equis.width)
        // La variable va en cursiva y el número no.
        assertTrue(equis.cursiva)
        assertTrue(!uno.cursiva)
    }

    @Test
    fun `el exponente es mas pequeno y va en alto`() {
        val e = elementos("x^2")
        val base = e.first { it.text == "x" }
        val exp = e.first { it.text == "2" }
        assertTrue(exp.fontSize!! < base.fontSize!!)
        assertTrue(exp.y < base.y)
        assertTrue(exp.x >= base.x + base.width)
    }

    @Test
    fun `por partes sale con una llave y sus condiciones`() {
        val e = elementos("x^2 si x < 0; 2x si x >= 0")
        assertEquals(2, textos(e).count { it == "si" })
        assertTrue(textos(e).contains("<"))
        assertTrue(textos(e).contains("≥"))
        // La llave: una polilínea de siete puntos, más alta que una letra.
        val llave = lineas(e).first { it.points!!.size == 7 }
        assertTrue(llave.height > 20.0 * 1.2)
    }

    @Test
    fun `una funcion y el valor absoluto se leen como se escriben`() {
        val e = elementos("sin(x) + |x|")
        assertTrue(textos(e).contains("sin"))
        assertTrue(textos(e).contains("("))
        // Las dos barras del valor absoluto, verticales.
        assertEquals(2, lineas(e).count { it.width < 1e-9 && it.height > 0 })
    }
}
