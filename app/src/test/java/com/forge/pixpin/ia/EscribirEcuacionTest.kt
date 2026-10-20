package com.forge.pixpin.ia

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** **Ecuaciones compuestas** (PC `ecuacion.rs`, sus pruebas): fracciones, raíces, matrices y lo roto. */
class EscribirEcuacionTest {
    private fun v(s: String, avisos: MutableList<String> = mutableListOf()) = EscribirEcuacion.validar(Json.parseToJsonElement(s) as JsonObject, avisos)
    private fun piezas(e: EscribirEcuacion.Pedido) = EscribirEcuacion.piezas(e, EscribirEcuacion.MEDIDA_ESTIMADA)
    private fun textos(els: List<Element>) = els.filter { it.type == ElementType.TEXT }.map { it.text.orEmpty() }
    private fun rayas(els: List<Element>) = els.count { it.type == ElementType.LINE }

    @Test fun `la formula general sale compuesta`() {
        val e = v("""{"ecuacion":"x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}"}""")
        val (els, medida) = piezas(e)
        assertTrue("la raya de la fracción y el signo de la raíz", rayas(els) >= 2)
        assertTrue(textos(els).toString(), textos(els).any { it.contains('±') })
        assertTrue("el exponente: ${textos(els)}", textos(els).any { it == "2" })
        assertTrue("alta: lleva una fracción", medida.second > e.tam * 1.8)
        assertEquals("todo en un grupo", 1, els.flatMap { it.groupIds }.distinct().size)
    }

    @Test fun `el latex escapado de mas sigue siendo una linea`() {
        val e = v("""{"ecuacion":"x = \\\\frac{-b \\\\pm \\\\sqrt{b^2 - 4ac}}{2a}"}""")
        assertEquals(e.lineas.toString(), 1, e.lineas.size)
        assertTrue(rayas(piezas(e).first) >= 2)
    }

    @Test fun `varias lineas y escritura corriente`() {
        val e = v("""{"ecuacion":"E = mc^2 \\\\ a^2 + b^2 = c^2\nsqrt(2)/2","x":10,"y":20}""")
        assertEquals(3, e.lineas.size)
        val y0 = piezas(e).first.minOf { it.y }
        assertTrue("$y0", kotlin.math.abs(y0 - 20.0) < 30.0)
    }

    @Test fun `matrices sistemas y texto salen compuestos sin barras`() {
        val e = v("""{"ecuacion":"A = \\begin{bmatrix} 2 & -1 & 0 \\\\ 1 & 3 & 2 \\\\ 0 & 1 & 4 \\end{bmatrix}"}""")
        assertEquals("la matriz no se parte en renglones: ${e.lineas}", 1, e.lineas.size)
        val (els, medida) = piezas(e)
        val t = textos(els)
        assertTrue(t.toString(), t.all { !it.contains('\\') && !it.contains('&') })
        assertTrue(t.toString(), t.count { it.trim() in setOf("-1", "−1", "1") } >= 3)
        assertTrue("tres filas de alto", medida.second > e.tam * 2.5)
        assertTrue("los corchetes, dibujados", rayas(els) >= 2)
        val avisos = mutableListOf<String>()
        val s = v("""{"ecuacion":"\\begin{cases} 2x + y = 5 \\\\ x - y = 1 \\end{cases} \\qquad \\text{si } x \\in \\mathbb{R}, \\operatorname{rang}(A) = 3, \\ln x \\to \\infty"}""", avisos)
        assertTrue(avisos.toString(), avisos.isEmpty())
        assertEquals(1, s.lineas.size)
        val ts = textos(piezas(s).first)
        assertTrue(ts.toString(), ts.all { !it.contains('\\') })
        assertTrue(ts.toString(), ts.any { it.contains("si") } && ts.any { it.contains('ℝ') } && ts.any { it.contains("rang") })
        assertEquals(listOf("a = 1", "b = 2"), EscribirEcuacion.partirLineas("a = 1 \\\\ b = 2"))
        assertEquals(listOf("x = 2", "y = 3"), EscribirEcuacion.partirLineas("x &= 2\ny &= 3"))
    }

    @Test fun `lo roto se rechaza`() {
        for ((a, motivo) in listOf("{}" to "falta", """{"ecuacion":"   "}""" to "falta", """{"ecuacion":"\\frac{a}{b"}""" to "llaves",
            """{"ecuacion":"${"x".repeat(EscribirEcuacion.MAX_LARGO + 1)}"}""" to "pasa de", """{"ecuacion":"x","tam_letra":900}""" to "tam_letra")) {
            try { v(a); fail(a) } catch (e: ErrorHerramienta) { assertTrue("$a: ${e.message}", e.message!!.contains(motivo)) }
        }
        // Un mandato desconocido no rompe: sale tal cual, con aviso.
        val avisos = mutableListOf<String>()
        v("""{"ecuacion":"\\inventado{x} + 1"}""", avisos)
        assertEquals(avisos.toString(), 1, avisos.size)
    }
}
