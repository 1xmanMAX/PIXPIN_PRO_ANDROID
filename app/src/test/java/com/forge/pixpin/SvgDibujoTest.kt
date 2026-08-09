package com.forge.pixpin.motor

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El SVG que sale de la geometría de verdad.
 *
 * [SvgTest] comprueba la sintaxis suelta; esto comprueba **la cadena entera**:
 * se construyen elementos como los que hace la app, se les pide su geometría al
 * mismo generador que usa la pantalla y se escribe el archivo. Si el garabato
 * de un rectángulo dejara de llegar hasta aquí, o si el resultado no fuera XML
 * válido, se ve en esta prueba y no al abrirlo en el ordenador de otro.
 *
 * Se queda en lo que no necesita Android —formas, líneas, rellenos y el
 * documento—, que es casi todo. El texto en curvas, las imágenes y el mosaico
 * necesitan `Paint` y `Bitmap` y solo se pueden ver en un móvil.
 *
 * De paso deja el archivo escrito en `build/svg-de-prueba.svg`, que es la forma
 * más rápida de mirar con los ojos si algo se torció.
 */
class SvgDibujoTest {

    private fun forma(
        id: String, tipo: ElementType, x: Double, y: Double, w: Double, h: Double,
        color: String = "#1971c2", fondo: String = "transparent",
        relleno: FillStyle = FillStyle.SOLID
    ) = Element(
        id = id, type = tipo, x = x, y = y, width = w, height = h,
        seed = id.hashCode() and 0x7FFFFFFF,
        strokeColor = color, backgroundColor = fondo, fillStyle = relleno
    )

    private fun linea(id: String, a: Pt, b: Pt, punta: Arrowhead? = null) = Element(
        id = id, type = if (punta == null) ElementType.LINE else ElementType.ARROW,
        x = a.x, y = a.y, width = b.x - a.x, height = b.y - a.y,
        seed = id.hashCode() and 0x7FFFFFFF,
        points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y)),
        endArrowhead = punta
    )

    /** Lo que hace [DrawSvg] con una forma, pero sin la parte que pide Android. */
    private fun cuerpoDe(e: Element): String {
        val g = buildShapeGeometry(e) ?: return ""
        val s = StringBuilder()
        if (e.hasBackground && !isTransparent(e.backgroundColor)) {
            val silueta = Svg.caminoCerrado(g.outline)
            s.append(
                if (g.fill == null) {
                    "<path d=\"$silueta\" fill=\"${e.backgroundColor}\"/>\n"
                } else {
                    "<g><path d=\"${Svg.camino(g.fill)}\" fill=\"none\" " +
                        "stroke=\"${e.backgroundColor}\" stroke-width=\"1\"/></g>\n"
                }
            )
        }
        s.append(
            "<path d=\"${Svg.camino(g.stroke)}\" fill=\"none\" " +
                "stroke=\"${e.strokeColor}\" stroke-width=\"${Svg.num(e.strokeWidth)}\" " +
                "stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n"
        )
        return s.toString()
    }

    private val muestra: List<Element> = listOf(
        forma("rect", ElementType.RECTANGLE, 20.0, 20.0, 160.0, 100.0),
        forma("rombo", ElementType.DIAMOND, 210.0, 20.0, 140.0, 100.0, fondo = "#a5d8ff"),
        forma(
            "ovalo", ElementType.ELLIPSE, 380.0, 20.0, 150.0, 100.0,
            color = "#e03131", fondo = "#ffc9c9", relleno = FillStyle.HACHURE
        ),
        linea("raya", Pt(20.0, 170.0), Pt(200.0, 220.0)),
        linea("flecha", Pt(230.0, 220.0), Pt(420.0, 170.0), Arrowhead.ARROW),
        forma(
            "rayado", ElementType.RECTANGLE, 20.0, 260.0, 200.0, 90.0,
            fondo = "#b2f2bb", relleno = FillStyle.CROSS_HATCH
        ),
        forma(
            "lineas", ElementType.RECTANGLE, 250.0, 260.0, 200.0, 90.0,
            fondo = "#ffec99", relleno = FillStyle.LINEAS
        )
    )

    private fun documentoDeMuestra(): String {
        val caja = getCommonBounds(muestra)
        val cuerpo = muestra.joinToString("") { cuerpoDe(it) }
        return Svg.documento(
            Bounds(caja.x1 - 10, caja.y1 - 10, caja.x2 + 10, caja.y2 + 10),
            "#ffffff", cuerpo, "muestra"
        )
    }

    /** Lo primero que tiene que cumplir un archivo: que se pueda abrir. */
    @Test
    fun `el documento es XML bien formado`() {
        val doc = documentoDeMuestra()
        val leido = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(doc.byteInputStream())
        assertEquals("svg", leido.documentElement.tagName)
        assertTrue(
            "no hay caminos dentro",
            leido.getElementsByTagName("path").length >= muestra.size
        )
    }

    /**
     * El garabato llega hasta el archivo.
     *
     * Un rectángulo dibujado a mano **no** es cuatro rectas: son ocho tramos con
     * temblor, porque el generador pasa dos veces por cada lado. Si esto sale con
     * cuatro `L`, es que en algún punto se perdió la rugosidad y lo que se está
     * exportando es la caja, no el dibujo.
     */
    @Test
    fun `un rectángulo exportado tiembla como el de la pantalla`() {
        val d = Svg.camino(buildShapeGeometry(muestra[0])!!.stroke)
        assertTrue("el camino sale vacío", d.isNotEmpty())
        assertTrue("no hay bastantes tramos para un trazo a mano: $d", d.count { it == 'C' } >= 8)
        assertTrue("no arranca con un desplazamiento: $d", d.startsWith("M"))
    }

    /** La misma semilla, el mismo garabato: exportar dos veces da lo mismo. */
    @Test
    fun `exportar dos veces da el mismo archivo`() {
        assertEquals(documentoDeMuestra(), documentoDeMuestra())
    }

    /** Y semillas distintas, garabatos distintos: no se está escribiendo la caja. */
    @Test
    fun `dos formas iguales con semilla distinta no salen idénticas`() {
        val a = forma("a", ElementType.RECTANGLE, 0.0, 0.0, 100.0, 50.0).copy(seed = 11)
        val b = a.copy(id = "b", seed = 9871)
        assertTrue(
            Svg.camino(buildShapeGeometry(a)!!.stroke) !=
                Svg.camino(buildShapeGeometry(b)!!.stroke)
        )
    }

    /** El rayado sale como trazos, no como una mancha lisa. */
    @Test
    fun `el rayado se escribe raya a raya`() {
        val g = buildShapeGeometry(muestra[5])!!
        assertNotNull("una trama sin trazos no es una trama", g.fill)
        val d = Svg.camino(g.fill!!)
        assertTrue("muy pocas rayas para un rayado cruzado: $d", d.count { it == 'M' } >= 6)
    }

    /**
     * Deja la muestra escrita para poder mirarla.
     *
     * No comprueba nada por su cuenta a propósito: es la salida que se abre en
     * un navegador cuando algo no cuadra y hay que verlo, no leerlo.
     */
    @Test
    fun `deja una muestra que se pueda abrir`() {
        val destino = File("build/svg-de-prueba.svg")
        destino.parentFile?.mkdirs()
        destino.writeText(documentoDeMuestra())
        assertTrue(destino.length() > 0)
    }
}
