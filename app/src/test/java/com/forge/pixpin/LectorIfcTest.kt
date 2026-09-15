package com.forge.pixpin

import com.forge.pixpin.motor.LectorIfc
import com.forge.pixpin.motor.Triangular
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** **El lector de IFC y la triangulación.** Ver [LectorIfc] y [Triangular]. */
class LectorIfcTest {

    private fun area(p: DoubleArray, t: IntArray): Double {
        var s = 0.0
        var i = 0
        while (i < t.size) {
            val a = t[i] * 3; val b = t[i + 1] * 3; val c = t[i + 2] * 3
            s += ((p[b] - p[a]) * (p[c + 1] - p[a + 1]) - (p[b + 1] - p[a + 1]) * (p[c] - p[a])) / 2
            i += 3
        }
        return s
    }

    @Test
    fun `un cuadrado con un agujero cuadrado deja el area de la corona`() {
        val fuera = doubleArrayOf(0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 10.0, 10.0, 0.0, 0.0, 10.0, 0.0)
        val hueco = doubleArrayOf(3.0, 3.0, 0.0, 3.0, 7.0, 0.0, 7.0, 7.0, 0.0, 7.0, 3.0, 0.0)
        val t = Triangular.poligono(fuera, listOf(hueco))
        assertEquals(100.0 - 16.0, area(fuera + hueco, t), 1e-9)
    }

    @Test
    fun `una L concava se triangula sin salirse`() {
        val l = doubleArrayOf(0.0, 0.0, 0.0, 4.0, 0.0, 0.0, 4.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0, 4.0, 0.0, 0.0, 4.0, 0.0)
        val t = Triangular.poligono(l)
        assertEquals(4 * 3, t.size)
        assertEquals(7.0, area(l, t), 1e-9)
    }

    @Test
    fun `un poligono horario sigue siendo horario`() {
        val l = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 4.0, 0.0, 1.0, 4.0, 0.0, 1.0, 1.0, 0.0, 4.0, 1.0, 0.0, 4.0, 0.0, 0.0)
        val t = Triangular.poligono(l)
        assertEquals(-7.0, area(l, t), 1e-9)
    }

    @Test
    fun `el modelo de Revit sale entero y en metros`() {
        val archivo = File(javaClass.classLoader!!.getResource("ifc/revit-ejemplo.ifc")!!.toURI())
        val malla = LectorIfc.leer(archivo)
        println("triángulos=${malla.cuantosTriangulos} piezas=${malla.piezas.size} tipos=${malla.piezas.groupingBy { it.tipo }.eachCount()}")
        val c = malla.caja()
        println("caja=" + c.joinToString { "%.2f".format(it) })
        assertTrue(malla.cuantosTriangulos > 500)
        assertTrue(malla.piezas.any { it.tipo.startsWith("IFCWALL") })
        // Un edificio, no un grano de arroz ni un continente.
        val ancho = maxOf(c[3] - c[0], c[4] - c[1])
        assertTrue("ancho $ancho", ancho in 2.0..500.0)
        // Todos los índices dentro.
        val nv = malla.vertices.size / 3
        assertTrue(malla.triangulos.all { it in 0 until nv })
        assertTrue(malla.vertices.none { it.isNaN() || abs(it) > 1e6 })
    }
}
