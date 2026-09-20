package com.forge.pixpin

import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.areaDe
import com.forge.pixpin.motor.asentarLoRapido
import com.forge.pixpin.motor.caraExacta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** El relleno exacto entre varias figuras ([caraExacta]) y el asiento de lo trazado deprisa. */
class CaraExactaTest {
    private fun cerrado(vararg p: Pt) = (p.toList() + p.first()).zipWithNext()
    private fun caja(x1: Double, y1: Double, x2: Double, y2: Double) = cerrado(Pt(x1, y1), Pt(x2, y1), Pt(x2, y2), Pt(x1, y2))
    private fun circulo(cx: Double, cy: Double, r: Double, n: Int = 72) =
        cerrado(*Array(n) { Pt(cx + r * cos(it * 2 * Math.PI / n), cy + r * sin(it * 2 * Math.PI / n)) })
    private fun area(a: List<Pt>) = abs(areaDe(a))

    @Test fun `una caja partida por una raya da justo sus dos trozos`() {
        val tramos = caja(0.0, 0.0, 100.0, 100.0) + listOf(Pt(40.0, -20.0) to Pt(40.0, 120.0))
        val izq = caraExacta(tramos, Pt(10.0, 50.0))!!
        val der = caraExacta(tramos, Pt(90.0, 50.0))!!
        assertEquals(4000.0, area(izq.contorno), 1e-6)
        assertEquals(6000.0, area(der.contorno), 1e-6)
        assertTrue(izq.huecos.isEmpty())
    }

    @Test fun `la esquina del cruce es un vertice exacto`() {
        val tramos = caja(0.0, 0.0, 100.0, 100.0) + caja(50.0, 50.0, 150.0, 150.0)
        val medio = caraExacta(tramos, Pt(75.0, 75.0))!!
        assertEquals(2500.0, area(medio.contorno), 1e-6)
        assertTrue(medio.contorno.any { abs(it.x - 100.0) < 1e-6 && abs(it.y - 50.0) < 1e-6 })
        assertEquals(7500.0, area(caraExacta(tramos, Pt(10.0, 10.0))!!.contorno), 1e-6)
    }

    @Test fun `una isla dentro sale como agujero y la de mas adentro no`() {
        val tramos = caja(0.0, 0.0, 200.0, 200.0) + caja(50.0, 50.0, 150.0, 150.0) + caja(80.0, 80.0, 120.0, 120.0)
        val r = caraExacta(tramos, Pt(10.0, 10.0))!!
        assertEquals(40000.0, area(r.contorno), 1e-6)
        assertEquals(10000.0, area(r.huecos.single()), 1e-6)
        val anillo = caraExacta(tramos, Pt(60.0, 60.0))!!
        assertEquals(10000.0, area(anillo.contorno), 1e-6)
        assertEquals(1600.0, area(anillo.huecos.single()), 1e-6)
    }

    @Test fun `dos circulos que se pisan dan la lente sin salirse`() {
        val tramos = circulo(0.0, 0.0, 100.0) + circulo(100.0, 0.0, 100.0)
        val lente = caraExacta(tramos, Pt(50.0, 0.0))!!
        for (q in lente.contorno) {
            assertTrue(hypot(q.x, q.y) <= 100.0 + 1e-6)
            assertTrue(hypot(q.x - 100.0, q.y) <= 100.0 + 1e-6)
        }
        // La lente de dos círculos de radio r a distancia r: r²(2π/3 − √3/2).
        assertEquals(10000 * (2 * Math.PI / 3 - Math.sqrt(3.0) / 2), area(lente.contorno), 60.0)
    }

    @Test fun `una raya que entra y no parte no estorba`() {
        val tramos = caja(0.0, 0.0, 100.0, 100.0) + listOf(Pt(50.0, -10.0) to Pt(50.0, 60.0))
        assertEquals(10000.0, area(caraExacta(tramos, Pt(10.0, 10.0))!!.contorno), 1e-6)
    }

    @Test fun `cuatro rayas que casi hacen esquina cierran`() {
        val tramos = listOf(
            Pt(0.0, 0.0) to Pt(100.0, 0.5), Pt(100.8, 1.0) to Pt(100.0, 100.0),
            Pt(99.5, 100.9) to Pt(0.0, 100.0), Pt(-0.7, 99.0) to Pt(0.5, 0.9)
        )
        val r = caraExacta(tramos, Pt(50.0, 50.0))
        assertNotNull(r)
        assertEquals(10000.0, area(r!!.contorno), 250.0)
    }

    @Test fun `lo abierto no es cara`() {
        val tramos = listOf(Pt(0.0, 0.0) to Pt(100.0, 0.0), Pt(100.0, 0.0) to Pt(100.0, 100.0), Pt(100.0, 100.0) to Pt(0.0, 100.0))
        assertNull(caraExacta(tramos, Pt(50.0, 50.0)))
        assertNull(caraExacta(caja(0.0, 0.0, 10.0, 10.0), Pt(50.0, 50.0)))
    }

    @Test fun `dos cajas con un lado comun`() {
        val tramos = caja(0.0, 0.0, 100.0, 100.0) + caja(100.0, 20.0, 160.0, 80.0)
        assertEquals(3600.0, area(caraExacta(tramos, Pt(130.0, 50.0))!!.contorno), 1e-6)
        assertEquals(10000.0, area(caraExacta(tramos, Pt(50.0, 50.0))!!.contorno), 1e-6)
    }

    @Test fun `lo rapido se asienta y lo lento no se toca`() {
        // Una recta tirada deprisa, con el error del digitalizador: ±1,5 alternando.
        val rapida = List(30) { Pt(it * 20.0, if (it % 2 == 0) 1.5 else -1.5) }
        val asentada = asentarLoRapido(rapida)
        assertEquals(rapida.size, asentada.size)
        assertEquals(rapida.first(), asentada.first()); assertEquals(rapida.last(), asentada.last())
        assertTrue(asentada.subList(3, 27).maxOf { abs(it.y) } < 0.25)
        // Letra: muestras a dos unidades. Ni se mueve.
        val lenta = List(30) { Pt(it * 2.0, if (it % 2 == 0) 1.0 else -1.0) }
        assertEquals(lenta, asentarLoRapido(lenta))
    }
}
