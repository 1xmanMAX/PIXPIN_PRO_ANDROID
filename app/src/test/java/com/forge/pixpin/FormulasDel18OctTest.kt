package com.forge.pixpin

import com.forge.pixpin.motor.Ecuacion
import com.forge.pixpin.motormd.Adorno
import com.forge.pixpin.motormd.Formulas
import com.forge.pixpin.motormd.Pieza
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo de las fórmulas del PC del 18-oct (`5d9a1b3`): llaves dentro de subíndices, underbrace, underset, overbrace, phantom y el subíndice más alto. */
class FormulasDel18OctTest {
    private fun tiene(p: Pieza, que: (Pieza) -> Boolean): Boolean = que(p) || when (p) {
        is Pieza.Fila -> p.partes.any { tiene(it, que) }
        is Pieza.ConIndices -> tiene(p.base, que) || p.arriba?.let { tiene(it, que) } == true || p.abajo?.let { tiene(it, que) } == true
        is Pieza.Adornada -> tiene(p.dentro, que)
        is Pieza.Debajo -> tiene(p.base, que) || tiene(p.abajo, que)
        is Pieza.Encima -> tiene(p.base, que) || tiene(p.arriba, que)
        else -> false
    }

    @Test fun `el subindice con punto y coma no suelta la llave`() {
        val p = Formulas.leer("F_{(x; y)}=x^{2}")
        assertFalse(p.toString(), tiene(p) { it is Pieza.Texto && it.texto == "}" })
        // Todo el «(x; y)» va abajo, como subíndice de la F.
        val conIndices = (p as Pieza.Fila).partes.first() as Pieza.ConIndices
        assertTrue(Formulas.plano(conIndices.abajo!!).contains("y"))
        assertEquals("F_(x; y) = x^2", Formulas.plano(p))
    }

    @Test fun `underbrace con su rotulo y underset`() {
        val a = Formulas.leer("""\underbrace{SS\ldots S}_{\text{"n" veces}}=\alpha""")
        assertTrue(a.toString(), tiene(a) { it is Pieza.Adornada && it.adorno == Adorno.LLAVE_DEBAJO })
        assertTrue(tiene(a) { it is Pieza.Debajo })
        assertTrue("no sale como código", Formulas.desconocidos(a).none { it.contains("underbrace") })
        assertTrue(tiene(Formulas.leer("""\underset{variables}{(x; y)}""")) { it is Pieza.Debajo })
        assertTrue(tiene(Formulas.leer("""\overbrace{a+b}^{n}""")) { it is Pieza.Encima })
        assertEquals("el hueco de «xx»: dos espacios", "a  b", Formulas.plano(Formulas.leer("""a\phantom{xx}b""")))
    }

    @Test fun `el subindice sube y la llave de debajo se dibuja`() {
        val medir: com.forge.pixpin.motor.MedidaDeTexto = { s, t -> s.length * t * 0.5 to t * 1.25 }
        val c = Ecuacion.deFormula(Formulas.leer("x_1"), 18.0, medir, false)
        // La base mide 22,5 de alto; el índice (12,6 de letra, 15,75 de alto) baja el 72 % de su alto.
        val indice = c.piezas.filterIsInstance<Ecuacion.Pieza.Texto>().first { it.texto == "1" }
        assertEquals(22.5 - 15.75 * 0.72, indice.y, 1e-9)
        val llave = Ecuacion.deFormula(Formulas.leer("""\underbrace{abc}_{n}"""), 18.0, medir, false)
        assertTrue(llave.piezas.count { it is Ecuacion.Pieza.Raya } >= 2)
        assertTrue(llave.piezas.filterIsInstance<Ecuacion.Pieza.Texto>().any { it.texto == "n" })
    }
}
