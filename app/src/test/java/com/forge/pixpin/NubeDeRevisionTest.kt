package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * **La nube de revisión** (25-oct-2026): los festones no cambian al estirar la caja, caben más; y
 * va y vuelve del PC con sus mismas claves (`nubeRadio`, `nubeBase`), que antes se perdían al
 * guardar aquí. Las cuentas son las de las pruebas de `nube_revision.rs` del PC.
 */
class NubeDeRevisionTest {

    private val rect = NubeDeRevision.Base.RECTANGULO
    private val elipse = NubeDeRevision.Base.ELIPSE

    @Test
    fun alDoblarLaCajaElFestonNoCambiaYCabenElDoble() {
        val pequena = NubeDeRevision.festones(rect, 0.0, 0.0, 300.0, 120.0, 12.0)
        val grande = NubeDeRevision.festones(rect, 0.0, 0.0, 600.0, 240.0, 12.0)
        assertTrue(abs(grande.size.toDouble() / pequena.size - 2.0) < 0.15)
        for (f in pequena + grande) {
            assertTrue("una cuerda de ${f.cuerda}", f.cuerda in 19.0..29.0)
            // La panza: lo que sale la mitad del arco de su cuerda, hacia fuera.
            val fx = (f.hasta.y - f.desde.y) / f.cuerda
            val fy = -(f.hasta.x - f.desde.x) / f.cuerda
            val m = f.punto(0.5)
            val panza = (m.x - (f.desde.x + f.hasta.x) / 2) * fx + (m.y - (f.desde.y + f.hasta.y) / 2) * fy
            assertEquals(12.0 * NubeDeRevision.PANZA, panza, 0.01)
        }
    }

    @Test
    fun estirarNoDeformaLosFestones() {
        val f = NubeDeRevision.festones(rect, 0.0, 0.0, 1000.0, 200.0, 12.0)
        val h = f.filter { abs(it.desde.y - it.hasta.y) < 1e-3 }.map { it.cuerda }.average()
        val v = f.filter { abs(it.desde.x - it.hasta.x) < 1e-3 }.map { it.cuerda }.average()
        assertTrue(abs(h / v - 1.0) < 0.15)
    }

    @Test
    fun elContornoCierraCabeEnSuCajaYLasEsquinasEmpalman() {
        for ((w, h) in listOf(80.0 to 40.0, 300.0 to 120.0, 600.0 to 600.0, 1000.0 to 200.0)) {
            for (base in listOf(rect, elipse)) {
                val f = NubeDeRevision.festones(base, 10.0, 20.0, w, h, 12.0)
                assertTrue(f.isNotEmpty())
                for (i in f.indices) {
                    val a = f[i]
                    val b = f[(i + 1) % f.size]
                    assertTrue("$base ${w}x$h: hueco en $i", Math.hypot(a.hasta.x - b.desde.x, a.hasta.y - b.desde.y) < 1e-3)
                    assertTrue(Math.hypot(a.punto(1.0).x - a.hasta.x, a.punto(1.0).y - a.hasta.y) < 1e-2)
                }
                val c = NubeDeRevision.contornoDeFestones(f)
                assertEquals("cerrado", c.first(), c.last())
                val x0 = c.minOf { it.x }; val x1 = c.maxOf { it.x }
                val y0 = c.minOf { it.y }; val y1 = c.maxOf { it.y }
                assertTrue(x0 >= 10 - 0.01 && y0 >= 20 - 0.01 && x1 <= 10 + w + 0.01 && y1 <= 20 + h + 0.01)
                if (base == rect) {
                    assertEquals(10.0, x0, 0.05); assertEquals(20.0, y0, 0.05)
                    assertEquals(10 + w, x1, 0.05); assertEquals(20 + h, y1, 0.05)
                }
            }
        }
    }

    @Test
    fun unaCajaDelRevesDaLaMismaNubeYUnRadioRotoNoRompe() {
        val a = NubeDeRevision.contorno(rect, 0.0, 0.0, 200.0, 100.0, 12.0)
        val b = NubeDeRevision.contorno(rect, 200.0, 100.0, -200.0, -100.0, 12.0)
        assertEquals(a, b)
        assertTrue(NubeDeRevision.festones(rect, 0.0, 0.0, 0.0, 50.0, 12.0).isEmpty())
        assertEquals(5, NubeDeRevision.contorno(rect, 0.0, 0.0, 0.0, 50.0, 12.0).size)
        assertEquals(NubeDeRevision.RADIO_DE_FABRICA, NubeDeRevision.radioUtil(Double.NaN), 0.0)
        assertEquals(NubeDeRevision.RADIO_DE_FABRICA, NubeDeRevision.radioUtil(-3.0), 0.0)
    }

    /** La nube tal como la escribe el PC (`nube_hacia`): una `line` cerrada con sus dos claves. */
    private val delPc = """{"id":"n1","seed":7,"type":"line","x":40.0,"y":30.0,"width":300.0,"height":120.0,
        "strokeColor":"#cc3333","backgroundColor":"#ffff00","strokeWidth":2.5,"roughness":0,"angle":0.3,
        "points":[{"x":9.6,"y":9.6},{"x":290.4,"y":9.6},{"x":290.4,"y":110.4},{"x":9.6,"y":110.4},{"x":9.6,"y":9.6}],
        "roundness":null,"nubeRadio":12.0,"nubeBase":"rectangulo"}"""

    @Test
    fun laNubeDelPcSeLeeComoNubeYVuelveConSusClaves() {
        val e = ExcalidrawJson.decodeFromString(Element.serializer(), delPc)
        assertTrue(e.esNube)
        assertEquals(12.0, e.nubeRadio!!, 0.0)
        assertEquals("rectangulo", e.nubeBase)
        // **Se coge por su caja**, no por cientos de vértices.
        assertFalse(e.isLinear)
        val c = getElementAbsoluteCoords(e)
        assertEquals(40.0, c.x1, 0.0); assertEquals(340.0, c.x2, 0.0)
        assertEquals(30.0, c.y1, 0.0); assertEquals(150.0, c.y2, 0.0)

        val vuelta = ExcalidrawJson.encodeToString(Element.serializer(), NubeDeRevision.conFestones(e))
        assertTrue(vuelta, vuelta.contains("\"type\":\"line\""))
        assertTrue(vuelta, vuelta.contains("\"nubeRadio\":12.0"))
        assertTrue(vuelta, vuelta.contains("\"nubeBase\":\"rectangulo\""))
        // Los puntos que viajan son los festones de la caja, cerrados y dentro de ella.
        val otra = ExcalidrawJson.decodeFromString(Element.serializer(), vuelta)
        val pts = otra.points!!
        assertTrue(pts.size > 40)
        assertEquals(pts.first(), pts.last())
        assertTrue(pts.all { it.x in -0.01..300.01 && it.y in -0.01..120.01 })
    }

    @Test
    fun unaLineaSinRadioValidoSigueSiendoUnaLinea() {
        for (extra in listOf("", ",\"nubeRadio\":-4", ",\"nubeRadio\":0")) {
            val e = ExcalidrawJson.decodeFromString(
                Element.serializer(),
                """{"id":"a","seed":1,"type":"line","x":0,"y":0,"width":10,"height":10,"points":[{"x":0,"y":0},{"x":10,"y":10}]$extra}"""
            )
            assertFalse(extra, e.esNube)
            assertTrue(extra, e.isLinear)
        }
        // Un rectángulo con la clave también es nube (lo que escriba otro), como en el PC.
        val r = ExcalidrawJson.decodeFromString(
            Element.serializer(),
            """{"id":"b","seed":1,"type":"rectangle","x":0,"y":0,"width":100,"height":60,"nubeRadio":8}"""
        )
        assertEquals(NubeDeRevision.Base.RECTANGULO, NubeDeRevision.baseDe(r))
        // Y sin la clave no se escribe nada.
        val sin = ExcalidrawJson.encodeToString(Element.serializer(), r.copy(nubeRadio = null))
        assertFalse(sin.contains("nubeRadio"))
    }

    @Test
    fun estiradaPorUnTiradorVuelveConMasFestonesYElMismoRadio() {
        val e = NubeDeRevision.conFestones(ExcalidrawJson.decodeFromString(Element.serializer(), delPc).copy(angle = 0.0))
        val antes = NubeDeRevision.festones(rect, e.x, e.y, e.width, e.height, 12.0).size
        val estirada = resizeSingleElement(e, HandleType.E, Pt(640.0, 90.0))
        assertEquals(600.0, estirada.width, 1e-6)
        assertEquals(40.0, estirada.x, 1e-6)
        val alDia = NubeDeRevision.nubesAlDia(listOf(estirada)).single()
        assertEquals(12.0, alDia.nubeRadio!!, 0.0)
        val ahora = NubeDeRevision.festones(rect, alDia.x, alDia.y, alDia.width, alDia.height, 12.0).size
        assertTrue("$antes y luego $ahora", ahora > antes + 20)
        // Los puntos se rehicieron con festones del mismo tamaño, no estirados.
        assertTrue(alDia.points!!.all { it.x in -0.01..600.01 })
        val c = NubeDeRevision.festones(rect, alDia.x, alDia.y, alDia.width, alDia.height, 12.0)
        assertTrue(c.all { it.cuerda in 19.0..29.0 })
    }

    @Test
    fun laHerramientaNubeDejaUnaLineaConSuFestonYSusPuntos() {
        val c = DrawController(Scene(style = ItemStyle(nubeRadio = 9.0, nubeBase = "elipse")))
        c.selectTool(Tool.NUBE)
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerMove(Pt(400.0, 250.0))
        c.pointerUp(Pt(400.0, 250.0))
        val e = c.scene.visible.singleOrNull()
        assertNotNull(e)
        assertEquals(ElementType.LINE, e!!.type)
        assertEquals(9.0, e.nubeRadio!!, 0.0)
        assertEquals("elipse", e.nubeBase)
        assertEquals(300.0, e.width, 1e-6)
        assertTrue((e.points?.size ?: 0) > 40)
        // Se toca por sus festones: el borde sí, el centro de una nube sin relleno no.
        val borde = NubeDeRevision.contornoDe(e)!![3]
        assertTrue(hitElementItself(borde, e, 2.0))
        assertFalse(hitElementItself(Pt(250.0, 175.0), e, 2.0))
    }
}
