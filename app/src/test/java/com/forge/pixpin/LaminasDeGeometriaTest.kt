package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * **Las láminas de geometría del PC** (25-oct-2026): las claves `cierreArco`, `marcaAngulo`,
 * `degradado` y `trazoRayaPunto` se leen y se escriben como el PC; la herramienta Ángulo encuentra
 * el ángulo, pone el cuadradito o los arquitos y la letra griega libre; y el cuentagotas pega solo
 * lo que admiten los dos.
 */
class LaminasDeGeometriaTest {

    private fun linea(id: String, a: Pt, b: Pt) = Element(
        id = id, type = ElementType.LINE, x = a.x, y = a.y,
        width = abs(b.x - a.x), height = abs(b.y - a.y), seed = 1,
        points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
    )

    // -----------------------------------------------------------------
    // Las claves
    // -----------------------------------------------------------------

    /** Lo que escribe el PC (`laminas_hacia`) para una marca y un sector con degradado. */
    private val marcaDelPc = """{"id":"m","seed":1,"type":"pixpin-arc","x":78,"y":78,"width":44,"height":44,
        "arcStart":0,"arcSweep":0.7853982,"backgroundColor":"#1e1e1e4d","strokeWidth":1.5,
        "marcaAngulo":{"arcos":2,"recto":false,"valor":"θ"},"fontSize":16.0}"""
    private val sectorDelPc = """{"id":"s","seed":1,"type":"pixpin-arc","x":0,"y":0,"width":200,"height":200,
        "arcStart":0,"arcSweep":1.5707964,"backgroundColor":"#ff0000","fillStyle":"solid","cierreArco":"sector",
        "degradado":{"tipo":"radial","desde":"#ff0000","hasta":"#ffcccc","origen":[0.5,0.5],"destino":[1.0,1.0]},
        "strokeStyle":"dashed","trazoRayaPunto":true}"""

    @Test
    fun laMarcaYElSectorDelPcSeEntiendenYVuelvenIguales() {
        val m = ExcalidrawJson.decodeFromString(Element.serializer(), marcaDelPc)
        assertEquals(Laminas.MarcaAngulo(arcos = 2, recto = false, valor = "θ"), Laminas.marcaDe(m))
        assertEquals("""{"arcos":2,"recto":false,"valor":"θ"}""", Laminas.marcaJson(Laminas.marcaDe(m)!!).toString())
        val s = ExcalidrawJson.decodeFromString(Element.serializer(), sectorDelPc)
        assertEquals(Laminas.CierreArco.SECTOR, Laminas.cierreDe(s))
        val d = Laminas.degradadoDe(s)!!
        assertEquals(Laminas.TipoDegradado.RADIAL, d.tipo)
        assertEquals("#ffcccc", d.hasta)
        assertEquals(1.0 to 1.0, d.destino)
        assertEquals(
            """{"tipo":"radial","desde":"#ff0000","hasta":"#ffcccc","origen":[0.5,0.5],"destino":[1.0,1.0]}""",
            Laminas.degradadoJson(d).toString()
        )
        assertTrue(Laminas.esRayaPunto(s))
        val vuelta = ExcalidrawJson.encodeToString(Element.serializer(), s.copy(x = 5.0))
        assertTrue(vuelta.contains("\"cierreArco\":\"sector\""))
        assertTrue(vuelta.contains("\"trazoRayaPunto\":true"))
    }

    @Test
    fun lasClavesConAliasYLasRotasSeLeenComoElPc() {
        val e = ExcalidrawJson.decodeFromString(
            Element.serializer(),
            """{"id":"x","seed":1,"type":"rectangle","x":0,"y":0,"width":10,"height":10,"cierreArco":"segment",
            "degradado":{"tipo":"linear","desde":"#00ff00","hasta":"#ffffff"},"marcaAngulo":{"arcos":9}}"""
        )
        assertEquals(Laminas.CierreArco.SEGMENTO, Laminas.cierreDe(e))
        val d = Laminas.degradadoDe(e)!!
        assertEquals(Laminas.TipoDegradado.LINEAL, d.tipo)
        assertEquals((0.0 to 0.5) to (1.0 to 0.5), d.origen to d.destino)
        assertEquals(3, Laminas.marcaDe(e)!!.arcos)
        assertNull(Laminas.cierreDe(e.copy(cierreArco = "espiral")))
    }

    @Test
    fun losColoresConAlfaAlFinalYElDegradadoDeFabrica() {
        val c = Laminas.rgbaDe("#1e1e1e4d")!!
        assertEquals(0.3, c[3], 0.01)
        assertEquals("#1e1e1e4d", Laminas.hexDe(c))
        assertEquals(0x4d1e1e1e, Laminas.argbDe("#1e1e1e4d"))
        // De ese color a uno un 80 % más claro (`desde_relleno`).
        val d = Laminas.degradadoDesdeRelleno(Laminas.TipoDegradado.LINEAL, "#ff0000")!!
        assertEquals("#ff0000", d.desde)
        assertEquals("#ffcccc", d.hasta)
        assertNull(Laminas.rgbaDe("transparent"))
    }

    @Test
    fun elRayaPuntoTieneElPatronDelPc() {
        assertEquals(listOf(14.0, 6.0, 2.0, 6.0), Laminas.patronRayaPunto(2.0).toList())
        // Por debajo de uno, como uno.
        assertEquals(listOf(9.0, 4.0, 1.0, 4.0), Laminas.patronRayaPunto(0.3).toList())
    }

    @Test
    fun elRayaPuntoDelPanelEsDiscontinuoConLaMarcaYSeLeeDeVuelta() {
        val r = newElement(ElementType.RECTANGLE, 0.0, 0.0, ItemStyle(), 100.0, 50.0)
        val conRayaPunto = conEstilo(r, estiloDe(r).copy(rayaPunto = true))
        assertEquals(StrokeStyle.DASHED, conRayaPunto.strokeStyle)
        assertEquals(true, conRayaPunto.trazoRayaPunto)
        assertTrue(estiloDe(conRayaPunto).rayaPunto)
        // Y quitarlo deja el discontinuo sin la clave: el PC la quita igual.
        val sin = conEstilo(conRayaPunto, estiloDe(conRayaPunto).copy(rayaPunto = false))
        assertNull(sin.trazoRayaPunto)
        // Lo nuevo nace con el raya-punto del pincel.
        assertEquals(true, newElement(ElementType.LINE, 0.0, 0.0, ItemStyle(rayaPunto = true)).trazoRayaPunto)
    }

    @Test
    fun elDegradadoDelPanelSaleDelFondoYUnoDesconocidoNoSeToca() {
        val r = newElement(ElementType.RECTANGLE, 0.0, 0.0, ItemStyle(backgroundColor = "#ff0000"), 100.0, 50.0)
        val conRadial = estiloAplicado(r, estiloDe(r), estiloDe(r).copy(degradado = "radial"))
        val d = Laminas.degradadoDe(conRadial)!!
        assertEquals(Laminas.TipoDegradado.RADIAL, d.tipo)
        assertEquals("#ff0000", d.desde)
        // Liso otra vez: se quita.
        assertNull(estiloAplicado(conRadial, estiloDe(conRadial), estiloDe(conRadial).copy(degradado = null)).degradado)
        // Un cierre de una versión futura vuelve intacto aunque se cambie otra cosa.
        val arco = Element(id = "a", type = ElementType.ARC, x = 0.0, y = 0.0, width = 10.0, height = 10.0, seed = 1, cierreArco = "espiral")
        assertEquals("espiral", estiloAplicado(arco, estiloDe(arco), estiloDe(arco).copy(opacity = 50)).cierreArco)
    }

    @Test
    fun losMandosSalenDondeAplican() {
        val abierto = Element(id = "a", type = ElementType.ARC, x = 0.0, y = 0.0, width = 10.0, height = 10.0, seed = 1, arcStart = 0.0, arcSweep = 1.0)
        assertTrue(Propiedad.CIERRE_ARCO in propiedadesDe(abierto))
        assertFalse(Propiedad.FONDO in propiedadesDe(abierto))
        val sector = abierto.copy(cierreArco = "sector")
        assertTrue(Propiedad.FONDO in propiedadesDe(sector) && Propiedad.DEGRADADO in propiedadesDe(sector))
        val marca = ExcalidrawJson.decodeFromString(Element.serializer(), marcaDelPc)
        assertTrue(Propiedad.ANGULO in propiedadesDe(marca) && Propiedad.LETRA_ANGULO in propiedadesDe(marca))
        assertFalse(Propiedad.CIERRE_ARCO in propiedadesDe(marca))
        assertTrue(Propiedad.MODO_ANGULO in propiedadesPara(Tool.ANGULO, emptyList()))
        assertTrue(Propiedad.NUBE in propiedadesPara(Tool.NUBE, emptyList()))
        assertTrue(propiedadesPara(Tool.LASER, emptyList()).isEmpty())
    }

    // -----------------------------------------------------------------
    // Las cuentas de la marca
    // -----------------------------------------------------------------

    @Test
    fun elAnguloMenorYElContornoDelSectorYDelSegmento() {
        val (inicio, barrido) = Laminas.anguloMenor(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(0.0, -10.0))
        assertEquals(0.0, inicio, 1e-9)
        assertEquals(-PI / 2, barrido, 1e-9)
        val s = ExcalidrawJson.decodeFromString(Element.serializer(), sectorDelPc)
        val sector = Laminas.contornoCerrado(s)!!
        assertEquals(Pt(100.0, 100.0), sector.last())
        val segmento = Laminas.contornoCerrado(s.copy(cierreArco = "segmento"))!!
        assertTrue(segmento.none { it == Pt(100.0, 100.0) })
        assertNull(Laminas.contornoCerrado(s.copy(cierreArco = null)))
    }

    @Test
    fun laMarcaPintaSusArquitosSeparadosYElValorFuera() {
        val m = ExcalidrawJson.decodeFromString(Element.serializer(), marcaDelPc)
        val d = Laminas.dibujoDeMarca(m, Laminas.marcaDe(m)!!)!!
        assertEquals(2, d.rayas.size)
        val r0 = hypot(d.rayas[0][0].x - 100, d.rayas[0][0].y - 100)
        val r1 = hypot(d.rayas[1][0].x - 100, d.rayas[1][0].y - 100)
        assertEquals(22.0, r0, 1e-9)
        assertEquals(3.3, r0 - r1, 1e-9) // max(3, 2,2 · 1,5)
        val valor = Laminas.sitioDelValor(d, 10.0, 14.0)
        assertTrue(hypot(valor.x - 100, valor.y - 100) > 22.0 + 4)
        // El recto: el cuadradito, con su lado igual al radio.
        val recto = Laminas.dibujoDeMarca(m, Laminas.MarcaAngulo(recto = true))!!
        assertEquals(1, recto.rayas.size)
        assertEquals(3, recto.rayas[0].size)
    }

    @Test
    fun laHerramientaEncuentraElAnguloEntreDosRectasYPoneElCuadradito() {
        val h = linea("h", Pt(0.0, 100.0), Pt(200.0, 100.0))
        val v = linea("v", Pt(100.0, 0.0), Pt(100.0, 200.0))
        // Un toque en el cuarto de arriba a la derecha.
        val (vertice, p1, p2) = Laminas.anguloEn(listOf(h, v), Pt(115.0, 85.0), 60.0)!!
        assertEquals(100.0, vertice.x, 1e-6); assertEquals(100.0, vertice.y, 1e-6)
        val (_, barrido) = Laminas.anguloMenor(vertice, p1, p2)
        assertEquals(PI / 2, abs(barrido), 1e-6)
        // Las vecinas que encierran el toque: la de arriba y la de la derecha.
        assertTrue(setOf(p1, p2).containsAll(listOf(Pt(100.0, 0.0), Pt(200.0, 100.0))))
        // Lejos del cruce, nada.
        assertNull(Laminas.anguloEn(listOf(h, v), Pt(190.0, 10.0), 60.0))
    }

    @Test
    fun conLaHerramientaUnToqueDejaLaMarcaConLetraYOtroLeCambiaLaLetra() {
        val a = linea("a", Pt(0.0, 100.0), Pt(200.0, 100.0))
        val b = linea("b", Pt(100.0, 100.0), Pt(200.0, 0.0)) // 45°
        val c = DrawController(Scene(elements = listOf(a, b)))
        c.selectTool(Tool.ANGULO)
        c.pointerDown(Pt(130.0, 92.0))
        c.pointerUp(Pt(130.0, 92.0))
        val marca = c.scene.elements.single { it.type == ElementType.ARC }
        val m = Laminas.marcaDe(marca)!!
        assertFalse(m.recto)
        assertEquals("α", m.valor)
        assertEquals(PI / 4, abs(marca.arcSweep!!), 1e-6)
        assertEquals(1.5, marca.strokeWidth, 0.0)
        // Relleno: el trazo al 30 %.
        assertEquals("#1e1e1e4d", marca.backgroundColor)
        // Tocarla otra vez pasa a la letra siguiente libre, en un paso que se deshace.
        c.pointerDown(Pt(110.0, 97.0))
        c.pointerUp(Pt(110.0, 97.0))
        assertEquals("β", Laminas.marcaDe(c.scene.elements.single { it.type == ElementType.ARC })!!.valor)
        c.undo()
        assertEquals("α", Laminas.marcaDe(c.scene.elements.single { it.type == ElementType.ARC })!!.valor)
    }

    @Test
    fun aNoventaGradosSaleElCuadraditoSinLetra() {
        val base = newElement(ElementType.ARC, 0.0, 0.0, ItemStyle())
        val m = Laminas.marcaNueva(base, Laminas.ModoAngulo.GRIEGA, Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(0.0, 10.0), 30.0, 2, emptyList())
        val marca = Laminas.marcaDe(m)!!
        assertTrue(marca.recto)
        assertNull(marca.valor)
        assertEquals(36.0, m.width, 1e-9) // el lado es 0,6 del radio
        val simple = Laminas.marcaNueva(base, Laminas.ModoAngulo.SIMPLE, Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), 30.0, 2, emptyList())
        assertEquals(Laminas.MarcaAngulo(arcos = 2), Laminas.marcaDe(simple))
        val forzada = Laminas.marcaNueva(base, Laminas.ModoAngulo.RECTO, Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), 30.0, 2, emptyList())
        assertTrue(Laminas.marcaDe(forzada)!!.recto)
    }

    @Test
    fun lasLetrasGriegasVanEnSuOrdenYSaltanLasUsadas() {
        assertEquals("α", Laminas.siguienteLetra(emptyList()))
        assertEquals("θ", Laminas.siguienteLetra(listOf("α", "β")))
        assertEquals("α1", Laminas.letraDeAngulo(16))
        assertEquals("γ", Laminas.letraVecina("β", 1, listOf("θ")))
        assertEquals("κ", Laminas.letraVecina("α", -1, emptyList()))
        assertEquals("β", Laminas.letraVecina("x", 1, listOf("α")))
    }

    // -----------------------------------------------------------------
    // Copiar el estilo
    // -----------------------------------------------------------------

    @Test
    fun elCuentagotasPegaSoloLoQueAdmitenLosDos() {
        val modelo = newElement(ElementType.RECTANGLE, 0.0, 0.0, ItemStyle(), 50.0, 50.0).copy(
            strokeColor = "#e03131", backgroundColor = "#ffec99", fillStyle = FillStyle.CROSS_HATCH,
            strokeWidth = 4.0, opacity = 60
        )
        val texto = newElement(ElementType.TEXT, 0.0, 0.0, ItemStyle(), 50.0, 20.0).copy(text = "hola")
        val tomado = EstiloCopiado.de(modelo)
        val pegado = tomado.pegarEn(texto)
        assertEquals("#e03131", pegado.strokeColor)
        assertEquals(60, pegado.opacity)
        assertEquals(texto.backgroundColor, pegado.backgroundColor)
        val otro = newElement(ElementType.ELLIPSE, 0.0, 0.0, ItemStyle(), 50.0, 50.0)
        val elipse = tomado.pegarEn(otro)
        assertEquals("#ffec99", elipse.backgroundColor)
        assertEquals(FillStyle.CROSS_HATCH, elipse.fillStyle)
        assertEquals(4.0, elipse.strokeWidth, 0.0)
        // Pegar sobre lo que ya es igual no cambia nada (no ensucia el historial).
        assertTrue(tomado.pegarEn(elipse) === elipse)
    }

    @Test
    fun conLaHerramientaElPrimerToqueTomaYElSegundoPega() {
        val rojo = newElement(ElementType.RECTANGLE, 0.0, 0.0, ItemStyle(), 50.0, 50.0)
            .copy(id = "r", strokeColor = "#e03131", backgroundColor = "#ffec99")
        val gris = newElement(ElementType.RECTANGLE, 200.0, 0.0, ItemStyle(), 50.0, 50.0)
            .copy(id = "g", backgroundColor = "#dddddd")
        val c = DrawController(Scene(elements = listOf(rojo, gris)))
        c.selectTool(Tool.COPIAR_ESTILO)
        c.pointerDown(Pt(25.0, 25.0)); c.pointerUp(Pt(25.0, 25.0))
        assertNotNull(c.estiloTomado)
        c.pointerDown(Pt(225.0, 25.0)); c.pointerUp(Pt(225.0, 25.0))
        val g = c.scene.byId("g")!!
        assertEquals("#e03131", g.strokeColor)
        assertEquals("#ffec99", g.backgroundColor)
        // El modelo no cambia, y tocar el vacío suelta lo tomado.
        assertEquals(rojo.strokeColor, c.scene.byId("r")!!.strokeColor)
        c.pointerDown(Pt(500.0, 500.0)); c.pointerUp(Pt(500.0, 500.0))
        assertNull(c.estiloTomado)
    }
}
