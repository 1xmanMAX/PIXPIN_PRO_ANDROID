package com.forge.pixpin.sincro

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.sincro.AnotacionesDelAdjunto.Marco
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **El marco de la tinta** ([Marco]). Los números son los de las pruebas del PC
 * (`pixpin-sincro/src/anotado.rs`, `anotado_del_adjunto.rs::pruebas_del_marco`): si salen iguales
 * en los dos, la tinta cae igual.
 */
class MarcoDeLaHojaTest {

    @get:Rule val carpeta = TemporaryFolder()

    private fun cerca(esperado: Double, real: Double) = assertEquals(esperado, real, 1e-6)
    private fun cerca(esperado: Marco, real: Marco) {
        cerca(esperado.x0, real.x0); cerca(esperado.y0, real.y0); cerca(esperado.x1, real.x1); cerca(esperado.y1, real.y1)
    }

    @Test
    fun `se escribe como el PC y se vuelve a leer`() {
        val m = Marco(-1050.0, 0.0, 2450.0, 4950.0)
        assertEquals("-1050,0,2450,4950\nv1\n", m.aTexto())
        assertEquals(m, Marco.deTexto(m.aTexto()))
        assertEquals("0.5,0,1414.286,2\nv1\n", Marco(0.5, -0.0, 1414.2857, 2.0).aTexto())
        assertNotNull(Marco.deTexto("1400.0,0.0,2800.0,1980.0"))
    }

    @Test
    fun `lo mal formado no vale`() {
        listOf(
            "", "1,2,3", "1,2,3,4,5", "0,0,x,10", "0,0,1400,1980\nv2", "10,0,10,1980", "0,0,NaN,1980",
            "0;0;1400;1980", "0,0,1400,1980\nv1\nmas"
        ).forEach { assertNull(it, Marco.deTexto(it)) }
    }

    @Test
    fun `la regla del lector de antes es la del PC`() {
        val p = 1400.0 / 1980.0
        cerca(Marco(-1050.0, 0.0, 2450.0, 4950.0), Marco.delLectorViejo(0, 1400.0, 0.75, p))
        cerca(Marco(0.0, 0.0, 1400.0, 1980.0), Marco.delLectorViejo(3, 1400.0, 0.75, p))
        val k = 2.5 / 1.75
        cerca(Marco(-1050.0, 0.0, -1050.0 + 1400 * k, 1980 * k), Marco.delLectorViejo(2, 1400.0, 0.75, p))
        cerca(Marco(1050 * k - 1050, 0.0, 1050 * k - 1050 + 1400 * k, 1980 * k), Marco.delLectorViejo(1, 1400.0, 0.75, p))
        cerca(Marco(0.0, 0.0, 1400.0, 1980.0), Marco.deHoja(1400.0, p))
    }

    @Test
    fun `la tinta de otro marco cae en las esquinas de la hoja`() {
        val trazo = Element(
            id = "t", type = ElementType.FREEDRAW, x = 500.0, y = 300.0, width = 4200.0, height = 5940.0, seed = 1,
            strokeWidth = 6.0, points = listOf(Pt(0.0, 0.0), Pt(4200.0, 5940.0))
        )
        val escena = Marco(500.0, 300.0, 4700.0, 6240.0).llevar(Scene(elements = listOf(trazo)), Marco(0.0, 0.0, 1400.0, 1980.0))
        val e = escena.elements.single()
        cerca(0.0, e.x); cerca(0.0, e.y); cerca(1400.0, e.width); cerca(1980.0, e.height); cerca(2.0, e.strokeWidth)
        val fin = e.points!!.last()
        cerca(1400.0, e.x + fin.x); cerca(1980.0, e.y + fin.y)
    }

    @Test
    fun `del lector sin espacios a la hoja, y de vuelta`() {
        val p = 1400.0 / 1980.0
        val viejo = Marco.delLectorViejo(0, 1400.0, 0.75, p)
        val hoja = Marco.deHoja(1400.0, p)
        val texto = Element(id = "x", type = ElementType.TEXT, x = 0.0, y = 0.0, width = 100.0, height = 50.0, seed = 1, text = "a", fontSize = 25.0)
        val alaHoja = viejo.llevar(Scene(elements = listOf(texto)), hoja).elements.single()
        cerca(1050 / 2.5, alaHoja.x); cerca(10.0, alaHoja.fontSize!!)
        val deVuelta = hoja.llevar(Scene(elements = listOf(alaHoja)), viejo).elements.single()
        cerca(0.0, deVuelta.x); cerca(25.0, deVuelta.fontSize!!)
    }

    @Test
    fun `el marco viaja y se borra con su mensaje`() {
        val files = carpeta.root
        val uid = "ABCDEFGHJK"
        val draw = java.io.File(files, AnotacionesDelAdjunto.CARPETA).apply { mkdirs() }
        listOf("anot-$uid-p0.hoja", "anot-$uid.hoja", "anot-$uid-p0.hoja.tmp").forEach { java.io.File(draw, it).writeText("0,0,1,1") }
        val suyos = AnotacionesDelAdjunto.porUid(files)[uid].orEmpty()
        assertEquals(setOf("pins/draw/anot-$uid-p0.hoja", "pins/draw/anot-$uid.hoja"), suyos.toSet())
        AnotacionesDelAdjunto.todoDe(files, uid).forEach { it.delete() }
        assertTrue(AnotacionesDelAdjunto.porUid(files)[uid].isNullOrEmpty())
    }

    @Test
    fun `la ida y vuelta no reescribe el marco`() {
        val f = java.io.File(carpeta.root, "pins/draw/anot-ABCDEFGHJK-p0.hoja")
        AnotacionesDelAdjunto.escribirMarco(f, Marco(0.0, 0.0, 1400.0, 1980.0))
        f.setLastModified(1_000_000_000_000)
        AnotacionesDelAdjunto.escribirMarco(f, Marco(0.0, 0.0, 1400.0005, 1980.0))
        assertEquals(1_000_000_000_000, f.lastModified())
        AnotacionesDelAdjunto.escribirMarco(f, Marco(0.0, 0.0, 1400.0, 1990.0))
        assertEquals("0,0,1400,1990\nv1\n", f.readText())
    }
}
