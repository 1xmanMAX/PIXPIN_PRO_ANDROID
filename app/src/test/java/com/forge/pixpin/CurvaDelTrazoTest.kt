package com.forge.pixpin

import com.forge.pixpin.motor.CurvaDelTrazo
import com.forge.pixpin.motor.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class CurvaDelTrazoTest {

    @Test
    fun `pasa por todas las muestras sin mover ninguna`() {
        val muestras = listOf(Pt(0.0, 0.0), Pt(10.0, 3.0), Pt(18.0, 15.0), Pt(20.0, 30.0), Pt(12.0, 41.0))
        val (curva, _) = CurvaDelTrazo.porLosPuntos(muestras, null, 1.0)
        for (m in muestras) assertTrue("falta $m", curva.any { it.x == m.x && it.y == m.y })
        assertEquals(muestras.first(), curva.first())
        assertEquals(muestras.last(), curva.last())
    }

    @Test
    fun `una circunferencia con pocas muestras sale redonda y no un poligono`() {
        // Doce muestras en una vuelta de radio 100: unidas con rectas, el centro de cada lado
        // se queda a 100·(1 − cos 15°) ≈ 3,4 unidades por dentro.
        val r = 100.0
        val muestras = (0..12).map { val a = it * Math.PI / 6; Pt(r * cos(a), r * sin(a)) }
        val (curva, _) = CurvaDelTrazo.porLosPuntos(muestras, null, 2.0)
        fun aparta(p: Pt) = kotlin.math.abs(hypot(p.x, p.y) - r)
        val deLasRectas = r * (1 - cos(Math.PI / 12))
        // Por dentro —donde cada tramo tiene vecinos a los dos lados— casi encima del círculo.
        val dentro = curva.filter { val a = Math.atan2(it.y, it.x).let { x -> if (x < 0) x + 2 * Math.PI else x }; a > Math.PI / 6 && a < 2 * Math.PI - Math.PI / 6 }
        val peor = dentro.maxOf(::aparta)
        assertTrue("se aparta $peor del círculo", peor < 0.6)
        assertTrue(peor < deLasRectas / 5)
        // En el primer y el último tramo no hay vecino del que sacar la curva: no mejor, pero nunca peor que la recta.
        assertTrue(curva.maxOf(::aparta) <= deLasRectas + 1e-9)
    }

    @Test
    fun `la presion se reparte y cuadra punto a punto`() {
        val muestras = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(20.0, 5.0))
        val (curva, presiones) = CurvaDelTrazo.porLosPuntos(muestras, listOf(0.2, 0.6, 1.0), 2.5)
        assertEquals(curva.size, presiones!!.size)
        assertEquals(0.2, presiones.first(), 1e-9)
        assertEquals(1.0, presiones.last(), 1e-9)
        assertTrue(presiones.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `las muestras repetidas no rompen nada y dos puntos quedan tal cual`() {
        val (curva, _) = CurvaDelTrazo.porLosPuntos(listOf(Pt(0.0, 0.0), Pt(0.0, 0.0), Pt(5.0, 5.0), Pt(5.0, 5.0), Pt(9.0, 2.0)), null, 1.0)
        assertTrue(curva.all { !it.x.isNaN() && !it.y.isNaN() })
        assertEquals(listOf(Pt(0.0, 0.0), Pt(3.0, 4.0)), CurvaDelTrazo.porLosPuntos(listOf(Pt(0.0, 0.0), Pt(3.0, 4.0)), null, 1.0).first)
    }
}
