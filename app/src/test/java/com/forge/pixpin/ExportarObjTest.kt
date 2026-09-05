package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **El croquis como modelo 3D.** Ver [ExportarObj]. */
class ExportarObjTest {

    private val recta = Trazo3D(
        "t1", listOf(Pt3(0.0, 0.0, 0.0), Pt3(10.0, 0.0, 0.0), Pt3(20.0, 0.0, 0.0)),
        "#ff0000", 4.0, calibre = 2.0
    )

    @Test
    fun `un trazo sale como tubo cerrado con su material`() {
        val s = ExportarObj.escribir(Croquis(trazos = listOf(recta)), "prueba")!!
        // Tres anillos de ocho más dos centros.
        assertEquals(3 * ExportarObj.LADOS + 2, s.vertices)
        // Dos tramos de dieciséis triángulos más dos tapas de ocho.
        assertEquals(2 * 2 * ExportarObj.LADOS + 2 * ExportarObj.LADOS, s.caras)
        assertTrue(s.obj.contains("mtllib prueba.mtl"))
        assertTrue(s.obj.contains("usemtl m_ff0000_100"))
        assertTrue(s.mtl.contains("newmtl m_ff0000_100"))
        assertTrue(s.mtl.contains("Kd 1 0 0"))
        // El color de vértice, detrás de cada v.
        assertTrue(s.obj.lines().any { it.startsWith("v ") && it.endsWith(" 1 0 0") })
    }

    /** El anillo mide lo que dice el calibre: radio uno alrededor del eje. */
    @Test
    fun `el radio del tubo es medio calibre`() {
        val s = ExportarObj.escribir(Croquis(trazos = listOf(recta)), "p")!!
        val primerAnillo = s.obj.lines().filter { it.startsWith("v ") }.take(ExportarObj.LADOS)
        for (l in primerAnillo) {
            val (x, y, z) = l.split(' ').drop(1).take(3).map { it.toDouble() }
            // Eje x en el origen: el anillo está en el plano x = 0 a distancia 1.
            assertEquals(0.0, x, 1e-3)
            assertEquals(1.0, kotlin.math.hypot(y, z), 1e-3)
        }
    }

    /** Los ejes cambian: la z del croquis es la y del modelo. */
    @Test
    fun `la z del croquis sale como y hacia arriba`() {
        val alto = Trazo3D("a", listOf(Pt3(0.0, 0.0, 0.0), Pt3(0.0, 0.0, 50.0)), "#00ff00", 2.0, calibre = 1.0)
        val s = ExportarObj.escribir(Croquis(trazos = listOf(alto)), "p")!!
        val ys = s.obj.lines().filter { it.startsWith("v ") }.map { it.split(' ')[2].toDouble() }
        assertTrue(ys.any { it > 49.0 })
        val zs = s.obj.lines().filter { it.startsWith("v ") }.map { it.split(' ')[3].toDouble() }
        assertTrue(zs.all { kotlin.math.abs(it) <= 1.0 })
    }

    @Test
    fun `lo escondido y lo vacio no salen`() {
        assertNull(ExportarObj.escribir(Croquis(), "p"))
        assertNull(ExportarObj.escribir(Croquis(trazos = listOf(recta.copy(oculto = true))), "p"))
        val punto = recta.copy(puntos = listOf(Pt3(1.0, 1.0, 1.0)))
        assertNull(ExportarObj.escribir(Croquis(trazos = listOf(punto)), "p"))
    }

    /** Las hojas salen como sus tiras, translúcidas. */
    @Test
    fun `una hoja sale como caras translucidas`() {
        val hoja = Lamina3D(
            "h", perfil = listOf(Pt3(0.0, 0.0, 0.0), Pt3(10.0, 0.0, 0.0)),
            direccion = Pt3(0.0, 1.0, 0.0), fondo = 5.0, color = "#3366ff", grosor = 1.0
        )
        val s = ExportarObj.escribir(Croquis(laminas = listOf(hoja)), "p")!!
        assertEquals(2, s.caras)
        assertTrue(s.obj.contains("o hoja_h"))
        assertTrue(s.mtl.contains("d 0.35"))
    }

    /** Los marcos son perpendiculares a la tangente y entre sí, y no se retuercen. */
    @Test
    fun `los marcos acompanan la curva sin retorcerse`() {
        val curva = (0..20).map { i ->
            val a = i / 20.0 * Math.PI
            Pt3(kotlin.math.cos(a) * 10, kotlin.math.sin(a) * 10, i * 0.5)
        }
        val marcos = ExportarObj.marcos(curva)
        for (i in curva.indices) {
            val (u, v) = marcos[i]
            assertEquals(1.0, largo(u), 1e-9)
            assertEquals(1.0, largo(v), 1e-9)
            assertEquals(0.0, escalar(u, v), 1e-9)
            if (i > 0) {
                // De un punto al siguiente el normal gira poco: no hay vuelcos.
                assertTrue(escalar(marcos[i - 1].first, u) > 0.9)
            }
        }
    }
}
