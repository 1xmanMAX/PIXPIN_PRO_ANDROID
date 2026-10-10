package com.forge.pixpin

import com.forge.pixpin.planos.ElegirEn3D
import com.forge.pixpin.planos.Modelo3D
import com.forge.pixpin.planos.Orbita
import com.forge.pixpin.planos.Visor3DActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La caja de sección, aislar y elegir en el visor 3D (lo del PC `1e5be50`), con un IFC de Revit leído. */
class CajaDeSeccionTest {
    private val m = Modelo3D.abrir(File(javaClass.classLoader!!.getResource("ifc/revit-ejemplo.px3d")!!.toURI()))

    @Test fun `una cara no cruza la de enfrente ni se va lejos`() {
        val c = floatArrayOf(0f, 0f, 0f, 10f, 10f, 10f)
        val limite = floatArrayOf(-1f, -1f, -1f, 11f, 11f, 11f)
        assertEquals(5f, Visor3DActivity.moverCara(c, 5, 5.0, limite)[5], 1e-6f)
        // La de arriba no baja de la de abajo.
        assertTrue(Visor3DActivity.moverCara(c, 5, -50.0, limite)[5] > 0f)
        // Ni sube más allá del límite.
        assertEquals(11f, Visor3DActivity.moverCara(c, 5, 99.0, limite)[5], 1e-6f)
        // La de abajo no sube por encima de la de arriba.
        assertTrue(Visor3DActivity.moverCara(c, 2, 50.0, limite)[2] < 10f)
        // Las demás, quietas.
        val n = Visor3DActivity.moverCara(c, 0, 3.0, limite)
        assertEquals(3f, n[0], 1e-6f); assertEquals(10f, n[3], 1e-6f); assertEquals(0f, n[1], 1e-6f)
    }

    @Test fun `la caja tiene doce aristas y la recien puesta no corta nada`() {
        val c = floatArrayOf(0f, 0f, 0f, 2f, 4f, 6f)
        assertEquals(12, Visor3DActivity.aristasDeCaja(c).size)
        val h = Visor3DActivity.cajaHolgada(c)
        for (k in 0 until 3) { assertTrue(h[k] < c[k]); assertTrue(h[k + 3] > c[k + 3]) }
        assertEquals(6.0, Visor3DActivity.centroDeCara(c, 5)[2], 1e-9)
        assertEquals(-1.0, Visor3DActivity.fueraDeCara(1)[1], 1e-9)
    }

    /** Un rayo de arriba abajo por el centro del modelo. */
    private fun desdeArriba(): Pair<DoubleArray, DoubleArray> {
        val c = m.cajaUtil()
        val x = (c[0] + c[3]) / 2.0; val y = (c[1] + c[4]) / 2.0
        return doubleArrayOf(x, y, c[5] + 50.0) to doubleArrayOf(0.0, 0.0, -1.0)
    }

    @Test fun `con la caja lo de fuera no se toca`() {
        val (ojo, dir) = desdeArriba()
        val sin = ElegirEn3D.elegir(m, ojo, dir, null, 0.0)
        assertNotNull(sin)
        // Una caja que deja fuera lo tocado: por debajo de ese punto ya no es lo mismo.
        val c = m.caja.copyOf().also { it[5] = (sin!!.punto[2] - 0.5).toFloat() }
        val con = ElegirEn3D.elegir(m, ojo, dir, c, 0.0)
        if (con != null) assertTrue("lo tocado queda dentro de la caja", con.punto[2] <= c[5] + 1e-3)
    }

    @Test fun `lo escondido no se toca`() {
        val (ojo, dir) = desdeArriba()
        val t = ElegirEn3D.elegir(m, ojo, dir, null, 0.0)!!
        val ocultos = ByteArray(m.elementos.size).also { it[t.elemento] = 1 }
        val otra = ElegirEn3D.elegir(m, ojo, dir, null, 0.0, ocultos)
        assertTrue(otra == null || otra.elemento != t.elemento)
        val todos = ByteArray(m.elementos.size) { 1 }
        assertNull(ElegirEn3D.elegir(m, ojo, dir, null, 0.0, todos))
    }

    @Test fun `aislar encuadra la caja del elemento`() {
        val (ojo, dir) = desdeArriba()
        val t = ElegirEn3D.elegir(m, ojo, dir, null, 0.0)!!
        val c = ElegirEn3D.cajaDe(m, t.elemento)!!
        for (k in 0 until 3) assertTrue(c[k] <= c[k + 3])
        assertNull(ElegirEn3D.cajaDe(m, m.elementos.size + 5))
        val o = Orbita.encuadrar(m.caja, 1000, 2000).encuadrarCon(c, 1000, 2000)
        assertEquals((c[0] + c[3]) / 2.0, o.objetivo[0], 1e-4)
        assertEquals(-0.8, o.rumbo, 1e-9) // sin cambiar desde dónde se mira
    }
}
