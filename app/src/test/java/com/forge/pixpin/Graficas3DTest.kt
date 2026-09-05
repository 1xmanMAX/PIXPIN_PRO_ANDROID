package com.forge.pixpin.croquis3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Superficies y curvas en el espacio.** Ver [Graficas3D]. */
class Graficas3DTest {

    @Test
    fun `una superficie sale como alambrada con sus tres ejes`() {
        val s = Graficas3D.Superficie("x^2 + y^2", -1.0, 1.0, -1.0, 1.0, 0.0, 2.0, 10.0, lineas = 4)
        val t = Graficas3D.superficie(s, "#ff0000", 4.0)!!
        // 5 líneas por dirección (sin cortes: el paraboloide cabe) + 3 ejes.
        assertEquals(5 + 5 + Graficas3D.TRAZOS_DE_LOS_EJES, t.size)
        val alambre = t.filter { it.color == "#ff0000" }
        assertTrue(alambre.all { it.puntos.size == Graficas3D.MUESTRAS + 1 })
        // z = x² + y² en la escala: en (1, 1) vale 2 → (10, 10, 20).
        val esquina = alambre.flatMap { it.puntos }.first { kotlin.math.abs(it.x - 10.0) < 1e-9 && kotlin.math.abs(it.y - 10.0) < 1e-9 }
        assertEquals(20.0, esquina.z, 1e-9)
        assertEquals(Graficas3D.TRAZOS_DE_LOS_EJES, t.count { it.color == Graficas3D.COLOR_DE_LOS_EJES })
    }

    /** Donde la función se sale de la ventana de z, la alambrada se corta. */
    @Test
    fun `la ventana de z corta la alambrada`() {
        val s = Graficas3D.Superficie("1/(x*y)", -2.0, 2.0, -2.0, 2.0, -3.0, 3.0, 10.0, lineas = 4)
        val t = Graficas3D.superficie(s, "#000000", 4.0)!!
        assertTrue(t.filter { it.color == "#000000" }.all { r -> r.puntos.all { kotlin.math.abs(it.z) <= 30.0 + 1e-9 } })
    }

    @Test
    fun `una curva parametrica es un trazo y sus ejes`() {
        val c = Graficas3D.Curva("cos(t)", "sin(t)", "t/(2pi)", 0.0, 2 * Math.PI, 10.0)
        val t = Graficas3D.curva(c, "#0000ff", 3.0)!!
        assertEquals(1 + Graficas3D.TRAZOS_DE_LOS_EJES, t.size)
        val helice = t.first { it.color == "#0000ff" }
        assertEquals(Graficas3D.MUESTRAS_DE_CURVA + 1, helice.puntos.size)
        assertEquals(10.0, helice.puntos.first().x, 1e-9)
        assertEquals(10.0, helice.puntos.last().z, 1e-9)
        // Todos a radio 10 del eje z.
        assertTrue(helice.puntos.all { kotlin.math.abs(kotlin.math.hypot(it.x, it.y) - 10.0) < 1e-9 })
    }

    @Test
    fun `las formulas con la variable equivocada no valen`() {
        assertNull(Graficas3D.superficie(Graficas3D.Superficie("t^2"), "#000", 2.0))
        assertNull(Graficas3D.curva(Graficas3D.Curva("x", "t", "t"), "#000", 2.0))
        assertNull(Graficas3D.superficie(Graficas3D.Superficie("nada(x)"), "#000", 2.0))
    }
}
