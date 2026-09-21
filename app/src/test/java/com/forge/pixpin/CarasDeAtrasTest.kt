package com.forge.pixpin

import com.forge.pixpin.motor.Malla3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Un cubo mirado desde fuera enseña tres caras, no seis.** Es lo que hace que un modelo se vea
 * bien desde cualquier lado sin búfer de profundidad. Ver `PintorDeMalla`.
 */
class CarasDeAtrasTest {
    /** Un cubo de lado 1 con las normales hacia fuera, como lo escribe un exportador correcto. */
    private fun cubo(alReves: Boolean = false): Malla3D {
        val c = Malla3D.Constructor()
        val v = Array(8) { i -> c.vertice((i and 1).toDouble(), ((i shr 1) and 1).toDouble(), ((i shr 2) and 1).toDouble()) }
        fun cara(a: Int, b: Int, d: Int, e: Int) {
            if (alReves) { c.triangulo(v[a], v[d], v[b], 0xFF888888.toInt()); c.triangulo(v[a], v[e], v[d], 0xFF888888.toInt()) }
            else { c.triangulo(v[a], v[b], v[d], 0xFF888888.toInt()); c.triangulo(v[a], v[d], v[e], 0xFF888888.toInt()) }
        }
        cara(0, 2, 3, 1)  // z = 0, normal −Z
        cara(4, 5, 7, 6)  // z = 1, normal +Z
        cara(0, 1, 5, 4)  // y = 0, normal −Y
        cara(2, 6, 7, 3)  // y = 1, normal +Y
        cara(0, 4, 6, 2)  // x = 0, normal −X
        cara(1, 3, 7, 5)  // x = 1, normal +X
        c.cerrarPieza("cubo", "PRUEBA")
        return c.malla()
    }

    /** Lo mismo que hace el pintor: signo del volumen y, con él, qué caras se van. */
    private fun cuantasSePintan(m: Malla3D, fx: Double, fy: Double, fz: Double): Int {
        val v = m.vertices; val ix = m.triangulos
        var seis = 0.0
        for (k in 0 until m.cuantosTriangulos) {
            val a = ix[k * 3] * 3; val b = ix[k * 3 + 1] * 3; val c = ix[k * 3 + 2] * 3
            seis += v[a] * (v[b + 1] * v[c + 2] - v[b + 2] * v[c + 1]) -
                v[a + 1] * (v[b] * v[c + 2] - v[b + 2] * v[c]) +
                v[a + 2] * (v[b] * v[c + 1] - v[b + 1] * v[c])
        }
        val haciaFuera = if (seis < 0) -1.0 else 1.0
        var pintados = 0
        for (k in 0 until m.cuantosTriangulos) {
            val a = ix[k * 3] * 3; val b = ix[k * 3 + 1] * 3; val c = ix[k * 3 + 2] * 3
            val ux = (v[b] - v[a]).toDouble(); val uy = (v[b + 1] - v[a + 1]).toDouble(); val uz = (v[b + 2] - v[a + 2]).toDouble()
            val wx = (v[c] - v[a]).toDouble(); val wy = (v[c + 1] - v[a + 1]).toDouble(); val wz = (v[c + 2] - v[a + 2]).toDouble()
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            if ((nx * fx + ny * fy + nz * fz) * haciaFuera <= 0) pintados++
        }
        return pintados
    }

    @Test fun `de un cubo se pinta la mitad, mire por donde se mire`() {
        val m = cubo()
        assertEquals(12, m.cuantosTriangulos)
        // De frente a una cara: se va la de atrás (2 triángulos) y las cuatro laterales quedan
        // justo de perfil, que no estorban porque no ocupan ni un píxel.
        assertEquals(10, cuantasSePintan(m, 0.0, 0.0, 1.0))
        // En diagonal: exactamente la mitad, las tres que dan al que mira.
        assertEquals(6, cuantasSePintan(m, 0.577, 0.577, 0.577))
        assertEquals(6, cuantasSePintan(m, -0.577, 0.577, -0.577))
    }

    @Test fun `con las caras al reves se compensa solo`() {
        // Un exportador que las escriba al contrario no puede hacer desaparecer el modelo: el
        // signo del volumen lo dice, y se pintan las mismas.
        assertEquals(
            cuantasSePintan(cubo(), 0.577, 0.577, 0.577),
            cuantasSePintan(cubo(alReves = true), 0.577, 0.577, 0.577)
        )
    }

    @Test fun `una lamina suelta no se recorta`() {
        val c = Malla3D.Constructor()
        val a = c.vertice(0.0, 0.0, 0.0); val b = c.vertice(1.0, 0.0, 0.0)
        val d = c.vertice(1.0, 1.0, 0.0); val e = c.vertice(0.0, 1.0, 0.0)
        c.triangulo(a, b, d, 0xFF888888.toInt()); c.triangulo(a, d, e, 0xFF888888.toInt())
        c.cerrarPieza("lamina", "PRUEBA")
        assertTrue("una lámina tiene todas sus aristas al aire", c.malla().tieneCarasSueltas())
        assertTrue("un cubo está cosido", !cubo().tieneCarasSueltas())
    }

    @Test fun `las agujas y los degenerados no entran en la malla`() {
        val c = Malla3D.Constructor()
        val a = c.vertice(0.0, 0.0, 0.0); val b = c.vertice(10.0, 0.0, 0.0)
        val casi = c.vertice(5.0, 1e-7, 0.0)     // una aguja de 10 m de largo
        val encima = c.vertice(10.0, 0.0, 0.0)   // el mismo punto que b
        val bueno = c.vertice(0.0, 10.0, 0.0)
        c.triangulo(a, b, casi, 0xFF888888.toInt())
        c.triangulo(a, b, encima, 0xFF888888.toInt())
        c.triangulo(a, b, bueno, 0xFF888888.toInt())
        assertEquals("solo el que tiene superficie", 1, c.malla().cuantosTriangulos)
    }
}
