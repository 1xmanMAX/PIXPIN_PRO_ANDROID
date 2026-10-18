package com.forge.pixpin.motor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
 * **Las láminas de geometría del PC** (15/17-oct-2026, `geometria_exacta.rs`): leer y escribir
 * sus claves tal cual, conservar las que no se entienden, y la geometría de las herramientas
 * (marca de ángulo, letras griegas, arco por tres puntos, extender un arco). Sin Android.
 */
class LaminasTest {

    /** Lo que escribe el PC para un sector con degradado radial (cuadrante coloreado). */
    private val sectorDelPc = """
        {"id":"s1","type":"pixpin-arc","x":-100,"y":-100,"width":200,"height":200,"angle":0,
         "strokeColor":"#1f1f1f","backgroundColor":"#ffb233","fillStyle":"solid","strokeWidth":2,
         "strokeStyle":"solid","roughness":0,"opacity":100,"seed":7,"arcStart":-1.5707964,
         "arcSweep":1.5707964,"cierreArco":"sector",
         "degradado":{"tipo":"radial","desde":"#ffb233","hasta":"#ffffff","origen":[0.5,0.5],"destino":[1.0,1.0]}}
    """.trimIndent()

    /** Una marca de ángulo de 45° con dos arquitos y su valor a 16. */
    private val marcaDelPc = """
        {"id":"m1","type":"pixpin-arc","x":-20,"y":-20,"width":40,"height":40,"angle":0,
         "strokeColor":"#1f1f1f","backgroundColor":"#1f1f1f4d","fillStyle":"solid","strokeWidth":1.5,
         "strokeStyle":"solid","roughness":0,"opacity":100,"seed":8,"arcStart":0,"arcSweep":-0.7853982,
         "cierreArco":"sector","marcaAngulo":{"arcos":2,"recto":false,"valor":"45°"},"fontSize":16}
    """.trimIndent()

    /** Una recta raya-punto: `dashed` con su marca. */
    private val rectaDelPc = """
        {"id":"r1","type":"line","x":0,"y":0,"width":100,"height":100,"angle":0,
         "strokeColor":"#1f1f1f","backgroundColor":"transparent","fillStyle":"solid","strokeWidth":2,
         "strokeStyle":"dashed","roughness":0,"opacity":100,"seed":9,
         "points":[{"x":0,"y":0},{"x":100,"y":100}],"trazoRayaPunto":true}
    """.trimIndent()

    private fun leer(json: String): Element = ExcalidrawJson.decodeFromString(Element.serializer(), json)
    private fun escribir(e: Element): JsonObject =
        Json.parseToJsonElement(ExcalidrawJson.encodeToString(Element.serializer(), e)).jsonObject
    private fun crudo(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `se leen las claves del PC`() {
        val s = leer(sectorDelPc)
        assertEquals(CierreArco.SECTOR, s.cierre)
        assertTrue(s.esArcoDeLamina)
        val d = s.degradadoDe!!
        assertEquals(TipoDegradado.RADIAL, d.tipo)
        assertEquals("#ffb233", d.desde)
        assertEquals(Pt(1.0, 1.0), d.destino)

        val m = leer(marcaDelPc)
        assertEquals(MarcaAngulo(arcos = 2, recto = false, valor = "45°"), m.marca)
        assertEquals(16.0, m.fontSize)

        val r = leer(rectaDelPc)
        assertTrue(r.esRayaPunto)
        assertEquals(StrokeStyle.DASHED, r.strokeStyle)
    }

    @Test
    fun `ida y vuelta deja las claves del PC tal cual`() {
        for (json in listOf(sectorDelPc, marcaDelPc, rectaDelPc)) {
            val original = crudo(json)
            val vuelta = escribir(leer(json))
            for (clave in listOf("cierreArco", "marcaAngulo", "degradado", "trazoRayaPunto", "fontSize", "arcStart", "arcSweep", "backgroundColor")) {
                val a = original[clave] ?: continue
                val b = vuelta[clave]
                assertNotNull("se perdió $clave", b)
                if (a is JsonPrimitive && b is JsonPrimitive && a.content.toDoubleOrNull() != null) {
                    assertEquals("$clave cambió", a.content.toDouble(), b.content.toDouble(), 1e-9)
                } else {
                    assertEquals("$clave cambió", a, b)
                }
            }
        }
    }

    @Test
    fun `lo que no se entiende vuelve intacto`() {
        val futuro = crudo(sectorDelPc).toMutableMap().apply {
            put("cierreArco", JsonPrimitive("espiral"))
            put("marcaAngulo", JsonPrimitive(7))
            put("degradado", crudo("""{"tipo":"conico","desde":"#ff0000","hasta":"#00ff00","giro":3}"""))
        }
        val e = leer(JsonObject(futuro).toString())
        assertNull(e.cierre)
        assertNull(e.marca)
        assertNull(e.degradadoDe)
        assertFalse(e.esArcoDeLamina)
        // Quitarlo desde aquí no quita lo que no se entendía (la regla del PC).
        val tocado = sinLamina(e)
        val vuelta = escribir(tocado)
        assertEquals(JsonPrimitive("espiral"), vuelta["cierreArco"])
        assertEquals(JsonPrimitive(7), vuelta["marcaAngulo"])
        assertEquals(futuro["degradado"], vuelta["degradado"])
    }

    @Test
    fun `un arco viejo sin claves nuevas se lee y se escribe como antes`() {
        val viejo = """{"id":"a","type":"pixpin-arc","x":0,"y":0,"width":100,"height":100,"angle":0,"strokeColor":"#1e1e1e","backgroundColor":"transparent","fillStyle":"solid","strokeWidth":2,"strokeStyle":"dashed","roughness":0,"opacity":100,"seed":1,"arcStart":0,"arcSweep":1.5}"""
        val e = leer(viejo)
        assertNull(e.cierre); assertNull(e.marca); assertNull(e.degradadoDe)
        assertFalse(e.esRayaPunto)
        val vuelta = escribir(e)
        for (clave in listOf("cierreArco", "marcaAngulo", "degradado", "trazoRayaPunto")) {
            assertFalse("$clave no debía escribirse", vuelta.containsKey(clave))
        }
    }

    @Test
    fun `al poner un degradado sin fondo se escribe la reserva`() {
        val r = Element(id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0, width = 100.0, height = 50.0, seed = 1)
        val con = conDegradado(r, Degradado.desdeRelleno(TipoDegradado.LINEAL, "#3366cc"))
        assertEquals("#3366cc", con.backgroundColor)
        assertEquals(FillStyle.SOLID, con.fillStyle)
        val d = con.degradadoDe!!
        assertEquals(TipoDegradado.LINEAL, d.tipo)
        // El de fábrica va a uno un 80 % más claro.
        assertEquals("#d6e0f5", d.hasta)
        val json = escribir(con)["degradado"]!!.jsonObject
        assertEquals("lineal", (json["tipo"] as JsonPrimitive).content)
        assertEquals(2, json["origen"]!!.jsonArray.size)
    }

    @Test
    fun `el color con alfa al final se lee como la web`() {
        assertEquals(listOf(0x1f, 0x1f, 0x1f, 0x4d), rgbaDeHex("#1f1f1f4d")!!.toList())
        assertEquals(listOf(0xff, 0x00, 0xaa, 255), rgbaDeHex("#f0a")!!.toList())
        assertNull(rgbaDeHex("transparent"))
        assertEquals("#1f1f1f4d", hexDeRgba(intArrayOf(0x1f, 0x1f, 0x1f, 0x4d)))
    }

    // -------------------------------------------------------------------------
    // Geometría

    private fun recta(id: String, a: Pt, b: Pt) = Element(
        id = id, type = ElementType.LINE, x = a.x, y = a.y,
        width = abs(b.x - a.x), height = abs(b.y - a.y), seed = 1,
        points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
    )

    @Test
    fun `el angulo de un toque elige los lados que lo encierran`() {
        val rayas = listOf(recta("1", Pt(0.0, 0.0), Pt(200.0, 0.0)), recta("2", Pt(0.0, 0.0), Pt(100.0, -100.0)))
        val (v, a, b) = anguloEn(rayas, Pt(30.0, -10.0), 60.0)!!
        assertTrue(hypot(v.x, v.y) < 0.01)
        assertEquals(PI / 4, abs(anguloMenor(v, a, b).second), 1e-3)
        assertNull(anguloEn(rayas, Pt(150.0, -60.0), 30.0))
        // Con el vértice en medio de una recta, a la izquierda queda el de 135°.
        val rayas2 = listOf(recta("1", Pt(-100.0, 0.0), Pt(200.0, 0.0)), rayas[1])
        val (v2, a2, b2) = anguloEn(rayas2, Pt(-20.0, -10.0), 60.0)!!
        assertEquals(3 * PI / 4, abs(anguloMenor(v2, a2, b2).second), 1e-3)
    }

    @Test
    fun `la marca recta lleva cuadradito y sin letra, la otra la siguiente griega`() {
        val s = ItemStyle()
        val recto = marcaConModo(ModoAngulo.GRIEGA, Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(0.0, -100.0), 30.0, 1, emptyList(), s)
        assertEquals(true, recto.marca!!.recto)
        assertNull(recto.marca!!.valor)
        assertEquals(CierreArco.SECTOR, recto.cierre)
        val otro = marcaConModo(ModoAngulo.GRIEGA, Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(70.0, -70.0), 30.0, 2, listOf("α"), s)
        assertEquals(MarcaAngulo(arcos = 2, recto = false, valor = "β"), otro.marca)
        assertEquals(1.5, otro.strokeWidth, 0.0)
        val simple = marcaConModo(ModoAngulo.SIMPLE, Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(70.0, -70.0), 30.0, 1, emptyList(), s)
        assertNull(simple.marca!!.valor)
    }

    @Test
    fun `la serie griega sigue el orden pedido y no repite`() {
        assertEquals(listOf("α", "β", "θ", "γ", "ω"), (0 until 5).map(::letraDeAngulo))
        assertEquals("α1", letraDeAngulo(16))
        assertEquals("θ", siguienteLetraDeAngulo(listOf("α", "β")))
        // Borrar la β la deja libre para la siguiente.
        assertEquals("β", siguienteLetraDeAngulo(listOf("α", "θ")))
        assertEquals("γ", letraVecina("β", 1, listOf("θ")))
        assertEquals("κ", letraVecina("α", -1, emptyList()))
        assertEquals("α", letraVecina(null, 1, emptyList()))
    }

    @Test
    fun `el arco por tres puntos pasa por los tres en su orden`() {
        val d = arcoPorTresPuntos(Pt(-100.0, 0.0), Pt(0.0, -100.0), Pt(100.0, 0.0))!!
        assertEquals(0.0, d[0], 1e-6); assertEquals(0.0, d[1], 1e-6)
        assertEquals(100.0, d[2], 1e-6)
        assertEquals(PI, abs(d[4]), 1e-6)
        // Por (0, -100) con la y hacia abajo: de π hacia 3π/2 es a favor del reloj.
        assertTrue(d[4] > 0)
        assertNull(arcoPorTresPuntos(Pt(0.0, 0.0), Pt(1.0, 1.0), Pt(2.0, 2.0)))
    }

    @Test
    fun `el sector cierra por el centro, el segmento por la cuerda y la marca pinta sus arquitos`() {
        val sector = leer(sectorDelPc)
        val f = figuraDeLamina(sector, conRelleno = true)!!
        assertTrue(f.primeroCerrado)
        val centro = Pt(0.0, 0.0)
        assertTrue("el sector llega al centro", f.relleno!!.any { hypot(it.x - centro.x, it.y - centro.y) < 1e-6 })
        val segmento = conCierre(sector, CierreArco.SEGMENTO)
        val g = figuraDeLamina(segmento, conRelleno = true)!!
        assertFalse(g.relleno!!.any { hypot(it.x, it.y) < 1e-6 })

        val marca = leer(marcaDelPc)
        val m = figuraDeLamina(marca, conRelleno = true)!!
        assertEquals(2, m.trazos.size)
        assertEquals("45°", m.valor)
        assertEquals(16.0, m.tamLetra, 0.0)
        // El valor va por fuera, sobre la bisectriz.
        val sitio = m.sitioDelValor(20.0, 14.0)
        assertTrue(hypot(sitio.x, sitio.y) > 20.0)
        val recta = conMarca(marca, MarcaAngulo(recto = true))
        assertEquals(3, figuraDeLamina(recta, conRelleno = false)!!.trazos.single().size)
        // Un arco de siempre no es de lámina.
        assertNull(figuraDeLamina(sinLamina(sector), conRelleno = false))
    }

    @Test
    fun `el raya-punto se ve con el patron del PC`() {
        val w = 2.0
        val i = intervalosRayaPunto(w)
        // La punta redonda suma w a cada guion: raya 5w+4, punto w, huecos 2w+2.
        assertEquals(5 * w + 4, i[0] + w, 1e-9)
        assertEquals(2 * w + 2, i[1] - w, 1e-9)
        assertEquals(w, i[2] + w, 0.02)
        assertEquals(2 * w + 2, i[3] - w, 1e-9)
    }

    @Test
    fun `extender un arco sigue por su circunferencia hasta la recta`() {
        // Un cuarto de circunferencia de radio 100 alrededor de (0, 0), de 0 a π/2 (abajo).
        val arco = arcoExacto(Pt(0.0, 0.0), 100.0, 0.0, PI / 2, ItemStyle())
        // Una recta vertical en x = -50 corta la circunferencia en y = ±86,6.
        val pared = recta("p", Pt(-50.0, -200.0), Pt(-50.0, 200.0))
        val estirado = extenderArco(arco, listOf(pared), Pt(0.0, 100.0))!!
        val fin = (estirado.arcStart ?: 0.0) + (estirado.arcSweep ?: 0.0)
        assertEquals(-50.0, 100 * kotlin.math.cos(fin), 0.5)
        assertEquals(0.0, estirado.arcStart!!, 1e-9)
    }

    @Test
    fun `el panel pone y quita cierre, degradado y marca`() {
        val arco = arcoExacto(Pt(0.0, 0.0), 50.0, 0.0, PI / 2, ItemStyle(backgroundColor = "#ffc078"))
        assertNull(arco.cierre)
        val cerrado = conEstilo(arco, estiloDe(arco).copy(cierreArco = CierreArco.SECTOR))
        assertEquals(CierreArco.SECTOR, cerrado.cierre)
        val conDeg = conEstilo(cerrado, estiloDe(cerrado).copy(degradado = TipoDegradado.RADIAL))
        assertEquals(TipoDegradado.RADIAL, conDeg.degradadoDe?.tipo)
        assertEquals("#ffc078", conDeg.degradadoDe?.desde)
        // Cambiar el color del trazo no le toca el degradado.
        val otroTrazo = conEstilo(conDeg, estiloDe(conDeg).copy(strokeColor = "#e03131"))
        assertEquals(conDeg.degradado, otroTrazo.degradado)
        // Abierto otra vez: fuera el cierre y el degradado.
        val abierto = conEstilo(conDeg, estiloDe(conDeg).copy(cierreArco = null))
        assertNull(abierto.cierreArco); assertNull(abierto.degradado)

        val marca = leer(marcaDelPc)
        assertEquals(ModoAngulo.GRIEGA, estiloDe(marca).modoAngulo)
        val recta = conEstilo(marca, estiloDe(marca).copy(modoAngulo = ModoAngulo.RECTO))
        assertEquals(true, recta.marca!!.recto)
        val tres = conEstilo(marca, estiloDe(marca).copy(arcosAngulo = 3))
        assertEquals(3, tres.marca!!.arcos)
        assertEquals("45°", tres.marca!!.valor)

        val raya = recta("r", Pt(0.0, 0.0), Pt(10.0, 0.0))
        val rp = conEstilo(raya, estiloDe(raya).copy(strokeStyle = StrokeStyle.DASHED, rayaPunto = true))
        assertTrue(rp.esRayaPunto)
        assertTrue(estiloDe(rp).rayaPunto)
        val lisa = conEstilo(rp, estiloDe(rp).copy(strokeStyle = StrokeStyle.SOLID, rayaPunto = false))
        assertNull(lisa.trazoRayaPunto)
    }

    @Test
    fun `lo nuevo nace con el estilo de las laminas`() {
        val s = ItemStyle(backgroundColor = "#a5d8ff", degradado = TipoDegradado.LINEAL, cierreArco = CierreArco.SEGMENTO,
            strokeStyle = StrokeStyle.DASHED, rayaPunto = true)
        val rect = newElement(ElementType.RECTANGLE, 0.0, 0.0, s)
        assertEquals(TipoDegradado.LINEAL, rect.degradadoDe?.tipo)
        assertTrue(rect.esRayaPunto)
        val arco = newElement(ElementType.ARC, 0.0, 0.0, s)
        assertEquals(CierreArco.SEGMENTO, arco.cierre)
        val texto = newElement(ElementType.TEXT, 0.0, 0.0, s)
        assertNull(texto.degradado)
        assertNull(texto.cierreArco)
    }

    @Test
    fun `el iman ofrece las puntas, el medio y el centro del arco`() {
        val a = arcoExacto(Pt(0.0, 0.0), 100.0, 0.0, PI / 2, ItemStyle())
        val puntos = anclajesDe(a, AjustesEnganche()).map { it.punto }
        assertEquals(4, puntos.size)
        for (q in listOf(Pt(100.0, 0.0), Pt(0.0, 100.0), Pt(70.71, 70.71), Pt(0.0, 0.0))) {
            assertTrue("falta $q", puntos.any { hypot(it.x - q.x, it.y - q.y) < 0.05 })
        }
    }

    @Test
    fun `la caja del sector llega al centro`() {
        val s = leer(sectorDelPc)
        val b = cajaDelArco(s)
        assertEquals(0.0, b.x1, 1e-3)
        assertEquals(0.0, b.y2, 1e-3)
        assertEquals(100.0, b.x2, 1e-3)
        assertEquals(-100.0, b.y1, 1e-3)
    }
}
