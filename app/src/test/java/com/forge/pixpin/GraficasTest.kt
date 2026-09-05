package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Fórmulas y gráficas.** Ver [Formula] y [Graficas]. */
class GraficasTest {

    private fun f(texto: String, x: Double): Double = Formula.compilar(texto)!!.en(x)

    @Test
    fun `la aritmetica de siempre y su precedencia`() {
        assertEquals(7.0, f("1 + 2 * 3", 0.0), 1e-12)
        assertEquals(9.0, f("(1 + 2) * 3", 0.0), 1e-12)
        assertEquals(2.0, f("2^3^0", 0.0), 1e-12) // a la derecha: 2^(3^0) = 2
        assertEquals(-4.0, f("-2^2", 0.0), 1e-12) // -(2^2)
        assertEquals(0.5, f("1/2", 0.0), 1e-12)
        assertEquals(0.5, f("0,5", 0.0), 1e-12)
        assertEquals(1.0, f("7 % 3", 0.0), 1e-12)
    }

    @Test
    fun `la x y las funciones de calculadora`() {
        assertEquals(9.0, f("x^2", 3.0), 1e-12)
        assertEquals(1.0, f("sin(pi/2)", 0.0), 1e-12)
        assertEquals(2.0, f("sqrt(x)", 4.0), 1e-12)
        assertEquals(1.0, f("ln(e)", 0.0), 1e-12)
        assertEquals(2.0, f("log(100)", 0.0), 1e-12)
        assertEquals(3.0, f("|x|", -3.0), 1e-12)
        assertEquals(5.0, f("max(x; 5)", 2.0), 1e-12)
        assertEquals(5.0, f("max(x, 5)", 2.0), 1e-12)
        assertEquals(8.0, f("pow(2, x)", 3.0), 1e-12)
    }

    @Test
    fun `la multiplicacion implicita y el prefijo y igual`() {
        assertEquals(6.0, f("2x", 3.0), 1e-12)
        assertEquals(8.0, f("2(x+1)", 3.0), 1e-12)
        assertEquals(16.0, f("(x+1)(x+1)", 3.0), 1e-12)
        assertEquals(0.0, f("x sin(x)", 0.0), 1e-12)
        assertEquals(2.0, f("2 sin x", Math.PI / 2), 1e-12)
        assertEquals(9.0, f("y = x^2", 3.0), 1e-12)
        assertEquals(9.0, f("f(x) = x²".replace("²", "^2"), 3.0), 1e-12)
        assertEquals(2 * Math.PI, f("2pi", 0.0), 1e-12)
    }

    /** Por partes: cada trozo con su condición, y fuera de todas no hay valor. */
    @Test
    fun `una funcion por partes elige el trozo que cumple`() {
        val c = Formula.compilar("x^2 si x < 0; 2x si x >= 0")!!
        assertEquals(4.0, c.en(-2.0), 1e-12)
        assertEquals(6.0, c.en(3.0), 1e-12)
        assertEquals(0.0, c.en(0.0), 1e-12)
        val hueco = Formula.compilar("x si x > 1")!!
        assertTrue(hueco.en(0.0).isNaN())
        assertEquals(2.0, hueco.en(2.0), 1e-12)
        // Con «si(cond, a, b)» dentro de la propia expresión, lo mismo.
        assertEquals(4.0, f("si(x<0, x^2, 2x)", -2.0), 1e-12)
        assertEquals(1.0, f("x <= 3", 3.0), 1e-12)
        assertEquals(0.0, f("x != 3", 3.0), 1e-12)
    }

    /** La ecuación como es: lo que se enseña mientras se teclea. */
    @Test
    fun `la formula se escribe en latex de bolsillo`() {
        assertEquals("\\frac{1}{x}", Formula.compilar("1/x")!!.latex[0].first)
        assertEquals("x^{2} + 2x", Formula.compilar("x^2 + 2x")!!.latex[0].first)
        assertEquals("\\sqrt{x}", Formula.compilar("sqrt(x)")!!.latex[0].first)
        assertEquals("\\sin(\\pi x)", Formula.compilar("sin(pi x)")!!.latex[0].first)
        assertEquals("(x + 1)^{3}", Formula.compilar("(x+1)^3")!!.latex[0].first)
        assertEquals("e^{-x}", Formula.compilar("exp(-x)")!!.latex[0].first)
        val partes = Formula.compilar("x^2 si x < 0; 2x si x >= 0")!!.latex
        assertEquals(2, partes.size)
        assertEquals("x < 0", partes[0].second)
        assertEquals("x \\ge 0", partes[1].second)
    }

    /** Las variables que usa, para saber en qué gráfica cabe. */
    @Test
    fun `las variables se conocen`() {
        assertEquals(setOf("x"), Formula.compilar("x^2")!!.variables)
        assertEquals(setOf("x", "y"), Formula.compilar("sin(x) cos(y)")!!.variables)
        assertEquals(setOf("t"), Formula.compilar("cos(t)")!!.variables)
        assertEquals(emptySet<String>(), Formula.compilar("2")!!.variables)
        assertEquals(3.0, Formula.compilar("x + y")!!.en(1.0, 2.0), 1e-12)
        assertEquals(0.5, Formula.compilar("t/2")!!.enT(1.0), 1e-12)
    }

    /** Varias curvas: cada fórmula con su color y su rótulo, sobre los mismos ejes. */
    @Test
    fun `varias formulas son varias curvas`() {
        val e = Graficas.elementos(
            Graficas.Peticion(listOf("x", "-x"), -2.0, 2.0, -2.0, 2.0, 30.0), ItemStyle(), medir
        )!!
        val curvas = e.filter { it.type == ElementType.LINE && (it.points?.size ?: 0) > 3 }
        assertEquals(2, curvas.size)
        assertEquals(ItemStyle().strokeColor, curvas[0].strokeColor)
        assertEquals(Graficas.COLORES_DE_CURVAS[0], curvas[1].strokeColor)
        assertEquals(2, e.count { it.type == ElementType.TEXT && it.text == "y =" })
        // Una fórmula con «y» no cabe en una gráfica plana.
        assertNull(Graficas.elementos(Graficas.Peticion("x + y"), ItemStyle(), medir))
    }

    @Test
    fun `lo que no se entiende no compila y lo que no existe da NaN`() {
        assertNull(Formula.compilar(""))
        assertNull(Formula.compilar("x +"))
        assertNull(Formula.compilar("foo(x)"))
        assertNull(Formula.compilar("(x"))
        assertNull(Formula.compilar("x $ 2"))
        assertTrue(f("sqrt(x)", -1.0).isNaN())
        assertTrue(f("1/x", 0.0).isInfinite())
    }

    private val medir: MedidaDeTexto = { texto, tamano -> texto.length * tamano * 0.6 to tamano * 1.2 }

    @Test
    fun `una grafica trae ejes, marcas, rotulo y curva agrupables`() {
        val e = Graficas.elementos(Graficas.Peticion("x^2", -3.0, 3.0, -1.0, 9.0, 40.0), ItemStyle(), medir)!!
        // Los ejes no son flechas de diagrama (punta de 25 px) sino rayas con una
        // uve pequeña dibujada aparte, y cada uno lleva su letra en cursiva.
        assertEquals(0, e.count { it.type == ElementType.ARROW })
        val puntas = e.filter { it.type == ElementType.LINE && it.points?.size == 3 }
        assertEquals(2, puntas.size)
        assertTrue(puntas.all { it.width <= Graficas.PUNTA && it.height <= Graficas.PUNTA })
        val letras = e.filter { it.type == ElementType.TEXT && (it.text == "x" || it.text == "y") && it.cursiva }
        assertEquals(setOf("x", "y"), letras.map { it.text }.toSet())
        // La x, a la derecha de la punta del eje x; la y, arriba, junto a la punta del eje y.
        assertTrue(letras.first { it.text == "x" }.x >= 240.0)
        assertTrue(letras.first { it.text == "y" }.y >= 0.0 && letras.first { it.text == "y" }.y < 4.0)
        // El rótulo va tipografiado: «y =», la x y el 2 como exponente, no «x^2» a máquina.
        assertTrue(e.any { it.type == ElementType.TEXT && it.text == "y =" })
        assertTrue(e.none { it.type == ElementType.TEXT && it.text?.contains("^") == true })
        // La parábola entra entera en la ventana: una sola rama.
        val curvas = e.filter { it.type == ElementType.LINE && (it.points?.size ?: 0) > 3 }
        assertEquals(1, curvas.size)
        assertEquals(Graficas.MUESTRAS + 1, curvas[0].points!!.size)
        // Sin rugosidad: una gráfica sale limpia.
        assertTrue(curvas.all { it.roughness == 0 })
        // La curva, dentro de la ventana de 240 × 400 px; los rótulos de las marcas
        // pueden colgar un poco por fuera, que es donde van.
        val caja = getCommonBounds(curvas)
        assertTrue(caja.x1 >= -1.0 && caja.y1 >= -1.0)
        assertTrue("${caja.x2} × ${caja.y2}", caja.x2 <= 241.0 && caja.y2 <= 401.0)
        val todo = getCommonBounds(e)
        assertTrue(todo.x1 > -60.0 && todo.y1 > -20.0 && todo.x2 < 300.0 && todo.y2 < 440.0)
    }

    /** `1/x` se corta en el cero: dos ramas, y ninguna une los dos infinitos. */
    @Test
    fun `una asintota parte la curva en ramas`() {
        val e = Graficas.elementos(Graficas.Peticion("1/x", -4.0, 4.0, -4.0, 4.0, 30.0), ItemStyle(), medir)!!
        val curvas = e.filter { it.type == ElementType.LINE && (it.points?.size ?: 0) > 3 }
        assertEquals(2, curvas.size)
        for (c in curvas) {
            val ys = c.points!!.map { it.y }
            // Ninguna rama salta más que la ventana entera de una muestra a la siguiente.
            assertTrue(ys.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) < 240.0 })
        }
    }

    @Test
    fun `sin formula o con limites del reves no hay grafica`() {
        assertNull(Graficas.elementos(Graficas.Peticion("nada(x)"), ItemStyle(), medir))
        assertNull(Graficas.elementos(Graficas.Peticion("x", 5.0, -5.0), ItemStyle(), medir))
        assertNotNull(Graficas.elementos(Graficas.Peticion("x"), ItemStyle(), medir))
    }

    @Test
    fun `el paso de las marcas es redondo`() {
        assertEquals(1.0, Graficas.pasoBonito(10.0), 1e-12)
        assertEquals(0.5, Graficas.pasoBonito(4.0), 1e-12)
        assertEquals(10.0, Graficas.pasoBonito(100.0), 1e-12)
        assertEquals(0.02, Graficas.pasoBonito(0.2), 1e-12)
    }
}
