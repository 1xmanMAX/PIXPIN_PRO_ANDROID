package com.forge.pixpin.motor

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recortar y extender.
 *
 * Lo que se comprueba aquí es **dónde acaba la raya**, que es lo único que
 * importa de estas dos y lo único que no se ve mirando la pantalla: un recorte
 * que se queda dos píxeles largo parece bien hecho hasta que uno se acerca, y
 * para entonces ya hay veinte rayas igual de mal.
 */
class RecorteTest {

    private fun linea(id: String, a: Pt, b: Pt) = Element(
        id = id, type = ElementType.LINE, x = a.x, y = a.y,
        width = b.x - a.x, height = b.y - a.y, seed = 1,
        points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
    )

    private fun vertical(id: String, x: Double) = linea(id, Pt(x, -100.0), Pt(x, 100.0))

    private fun extremos(e: Element): Pair<Pt, Pt> =
        absolutePoints(e).let { it.first() to it.last() }

    // ---- Recortar ----

    /**
     * Lo normal: una raya que sobresale de una caja y se toca el rabo. Se va el
     * rabo y la raya queda clavada en el borde.
     */
    @Test
    fun `recorta el rabo que sobresale`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0))
        val pared = vertical("v", 120.0)

        val trozos = recortarEn(raya, listOf(pared), Pt(180.0, 0.0))
        assertNotNull(trozos)
        assertEquals(1, trozos!!.size)
        val (a, b) = extremos(trozos.first())
        assertEquals(0.0, a.x, 0.001)
        assertEquals(120.0, b.x, 0.001)
    }

    /** Tocando el otro lado se va el otro lado. */
    @Test
    fun `recorta por el principio si es lo que se toca`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0))
        val trozos = recortarEn(raya, listOf(vertical("v", 120.0)), Pt(40.0, 0.0))
        assertEquals(1, trozos!!.size)
        val (a, b) = extremos(trozos.first())
        assertEquals(120.0, a.x, 0.001)
        assertEquals(200.0, b.x, 0.001)
    }

    /**
     * **El trozo del medio parte la raya en dos.** Es el caso que distingue un
     * recorte de verdad de un simple «acortar»: quitar el tramo entre dos cruces
     * deja dos rayas, no una.
     */
    @Test
    fun `el trozo del medio parte la raya en dos`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0))
        val paredes = listOf(vertical("a", 60.0), vertical("b", 140.0))

        val trozos = recortarEn(raya, paredes, Pt(100.0, 0.0))
        assertEquals(2, trozos!!.size)
        val (a1, b1) = extremos(trozos[0])
        val (a2, b2) = extremos(trozos[1])
        assertEquals(0.0, a1.x, 0.001)
        assertEquals(60.0, b1.x, 0.001)
        assertEquals(140.0, a2.x, 0.001)
        assertEquals(200.0, b2.x, 0.001)
        // La mitad de atrás es una raya nueva: no puede llevar el mismo id.
        assertTrue("las dos mitades comparten id", trozos[0].id != trozos[1].id)
    }

    /** Una raya que no cruza nada se va entera: no hay por dónde cortarla. */
    @Test
    fun `sin cruces la raya se va entera`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0))
        assertEquals(emptyList<Element>(), recortarEn(raya, emptyList(), Pt(100.0, 0.0)))
    }

    /** Los cruces salen ordenados y sin repetir el mismo punto dos veces. */
    @Test
    fun `los cruces salen ordenados y sin repetidos`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0))
        val caja = Element(
            id = "c", type = ElementType.RECTANGLE, x = 50.0, y = -20.0,
            width = 60.0, height = 40.0, seed = 1
        )
        // La caja la cruza dos veces: entrando en x=50 y saliendo en x=110.
        val cortes = cortesDe(raya, listOf(caja))
        assertEquals(2, cortes.size)
        assertEquals(50.0, cortes[0], 0.001)
        assertEquals(110.0, cortes[1], 0.001)
    }

    /** Y una curva corta como cualquier otra cosa: es el perímetro de siempre. */
    @Test
    fun `una circunferencia tambien corta`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0))
        val circulo = Element(
            id = "o", type = ElementType.ELLIPSE, x = 50.0, y = -50.0,
            width = 100.0, height = 100.0, seed = 1
        )
        val trozos = recortarEn(raya, listOf(circulo), Pt(180.0, 0.0))
        assertEquals(1, trozos!!.size)
        // Se corta donde el círculo cruza el eje: en x = 150.
        assertEquals(150.0, extremos(trozos.first()).second.x, 0.5)
    }

    /** Los dobleces de una polilínea se conservan al recortar. */
    @Test
    fun `una polilinea conserva sus dobleces`() {
        val codo = Element(
            id = "l", type = ElementType.LINE, x = 0.0, y = 0.0,
            width = 200.0, height = 100.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(200.0, 100.0))
        )
        // Una vertical que corta el último tramo en x = 150.
        val pared = linea("v", Pt(150.0, 50.0), Pt(150.0, 150.0))
        val trozos = recortarEn(codo, listOf(pared), Pt(190.0, 100.0))
        val puntos = absolutePoints(trozos!!.single())
        assertEquals(4, puntos.size)
        assertEquals(150.0, puntos.last().x, 0.001)
        assertEquals(100.0, puntos.last().y, 0.001)
    }

    // ---- Extender ----

    /** Se toca la punta que se queda corta y llega hasta lo primero que topa. */
    @Test
    fun `extiende hasta la primera pared`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(100.0, 0.0))
        val estirada = extenderEn(raya, listOf(vertical("v", 160.0)), Pt(95.0, 0.0))
        assertNotNull(estirada)
        assertEquals(160.0, extremos(estirada!!).second.x, 0.001)
        assertEquals(0.0, extremos(estirada).second.y, 0.001)
    }

    /** **La primera, no la más lejana**: extender es «hasta que topes». */
    @Test
    fun `se para en la primera y no en la segunda`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(100.0, 0.0))
        val paredes = listOf(vertical("lejos", 300.0), vertical("cerca", 160.0))
        val estirada = extenderEn(raya, paredes, Pt(95.0, 0.0))
        assertEquals(160.0, extremos(estirada!!).second.x, 0.001)
    }

    /** Tocando por el otro extremo se estira hacia atrás. */
    @Test
    fun `extiende por el extremo que se toca`() {
        val raya = linea("l", Pt(100.0, 0.0), Pt(200.0, 0.0))
        val estirada = extenderEn(raya, listOf(vertical("v", 20.0)), Pt(105.0, 0.0))
        assertEquals(20.0, extremos(estirada!!).first.x, 0.001)
        // Y la otra punta no se mueve.
        assertEquals(200.0, extremos(estirada).second.x, 0.001)
    }

    /** Sin nada delante no se estira: una raya al vacío ya se hace dibujando. */
    @Test
    fun `sin nada delante no se estira`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(100.0, 0.0))
        assertNull(extenderEn(raya, listOf(vertical("detras", -50.0)), Pt(95.0, 0.0)))
    }

    /** Se prolonga en la dirección del último tramo, no en la de toda la raya. */
    @Test
    fun `una polilinea se estira por su ultimo tramo`() {
        val codo = Element(
            id = "l", type = ElementType.LINE, x = 0.0, y = 0.0,
            width = 100.0, height = 100.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0))
        )
        // El último tramo baja; la pared es una horizontal más abajo.
        val suelo = linea("s", Pt(0.0, 180.0), Pt(200.0, 180.0))
        val estirada = extenderEn(codo, listOf(suelo), Pt(100.0, 95.0))
        val fin = extremos(estirada!!).second
        assertEquals(100.0, fin.x, 0.001)
        assertEquals(180.0, fin.y, 0.001)
    }

    /** El origen se recoloca al recortar: si no, la raya se iría de sitio. */
    @Test
    fun `el recorte no mueve la raya de sitio`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0))
        val trozos = recortarEn(raya, listOf(vertical("v", 120.0)), Pt(40.0, 0.0))
        val trozo = trozos!!.single()
        assertEquals(120.0, trozo.x, 0.001)
        assertEquals(Pt(0.0, 0.0), trozo.points!!.first())
        assertEquals(80.0, trozo.width, 0.001)
    }

    // ---- Desde el controlador ----

    private fun conLaHerramienta(t: Tool, escena: List<Element>, donde: Pt): DrawController {
        val c = DrawController(Scene(elements = escena))
        c.selectTool(t)
        c.pointerDown(donde)
        c.pointerUp(donde)
        return c
    }

    @Test
    fun `un toque con recortar quita el trozo tocado`() {
        val c = conLaHerramienta(
            Tool.RECORTAR,
            listOf(linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0)), vertical("v", 120.0)),
            Pt(180.0, 0.0)
        )
        val raya = c.scene.visible.first { it.id == "l" }
        assertEquals(120.0, absolutePoints(raya).last().x, 0.001)
        assertTrue("tiene que poder deshacerse", c.canUndo)
    }

    /**
     * **Arrastrando no se recorta.** Es la misma regla que salvó al bote: el
     * primer dedo de un pellizco para hacer zoom baja igual que un toque, y una
     * herramienta que actúa al bajarlo se dispara sola.
     */
    @Test
    fun `arrastrando no hace nada`() {
        val escena = listOf(linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0)), vertical("v", 120.0))
        val c = DrawController(Scene(elements = escena))
        c.selectTool(Tool.RECORTAR)
        c.pointerDown(Pt(180.0, 0.0))
        c.pointerMove(Pt(180.0, 60.0))
        c.pointerUp(Pt(180.0, 60.0))

        assertEquals(200.0, absolutePoints(c.scene.visible.first { it.id == "l" }).last().x, 0.001)
        assertTrue("no debería haber tocado la escena", !c.canUndo)
    }

    @Test
    fun `un toque con extender estira hasta lo que topa`() {
        val c = conLaHerramienta(
            Tool.EXTENDER,
            listOf(linea("l", Pt(0.0, 0.0), Pt(100.0, 0.0)), vertical("v", 160.0)),
            Pt(98.0, 0.0)
        )
        val raya = c.scene.visible.first { it.id == "l" }
        assertEquals(160.0, absolutePoints(raya).last().x, 0.001)
    }

    /** Tocando donde no hay ninguna raya no pasa nada. */
    @Test
    fun `tocando el vacio no pasa nada`() {
        val c = conLaHerramienta(
            Tool.RECORTAR,
            listOf(linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0))),
            Pt(100.0, 400.0)
        )
        assertEquals(1, c.scene.visible.size)
        assertTrue(!c.canUndo)
    }

    /** El margen para agarrar una raya fina es generoso: el dedo tapa mucho. */
    @Test
    fun `se agarra la raya aunque el dedo caiga un poco fuera`() {
        val c = conLaHerramienta(
            Tool.RECORTAR,
            listOf(linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0)), vertical("v", 120.0)),
            Pt(180.0, 18.0)
        )
        assertEquals(
            120.0,
            absolutePoints(c.scene.visible.first { it.id == "l" }).last().x,
            0.001
        )
    }

    /** Y lo que se recorta se puede deshacer de una vez. */
    @Test
    fun `deshacer devuelve la raya entera`() {
        val c = conLaHerramienta(
            Tool.RECORTAR,
            listOf(linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0)), vertical("v", 120.0)),
            Pt(180.0, 0.0)
        )
        c.undo()
        val raya = c.scene.visible.first { it.id == "l" }
        assertEquals(200.0, absolutePoints(raya).last().x, 0.001)
    }

    /** Recortar contra una guía vale: el andamio sirve para eso. */
    @Test
    fun `se recorta contra una guia`() {
        val guia = vertical("v", 120.0).copy(reference = true)
        val c = conLaHerramienta(
            Tool.RECORTAR,
            listOf(linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0)), guia),
            Pt(180.0, 0.0)
        )
        assertEquals(
            120.0,
            absolutePoints(c.scene.visible.first { it.id == "l" }).last().x,
            0.001
        )
    }

    /** Pero fuera del modo guía, la guía no se puede recortar a sí misma. */
    @Test
    fun `fuera del modo guia no se recorta una guia`() {
        val guia = linea("g", Pt(0.0, 0.0), Pt(200.0, 0.0)).copy(reference = true)
        val c = conLaHerramienta(Tool.RECORTAR, listOf(guia, vertical("v", 120.0)), Pt(180.0, 0.0))
        assertEquals(
            200.0,
            absolutePoints(c.scene.visible.first { it.id == "g" }).last().x,
            0.001
        )
    }

    /** El largo de una polilínea, que es de lo que se fía todo lo anterior. */
    @Test
    fun `el recorrido se mide por los tramos`() {
        val puntos = listOf(Pt(0.0, 0.0), Pt(30.0, 40.0), Pt(30.0, 90.0))
        assertEquals(100.0, largoDe(puntos), 0.001)
        assertEquals(50.0, hypot(30.0, 40.0), 0.001)
    }
}
