package com.forge.pixpin.motor

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Document

/**
 * El exportador de SVG entero, con el `Paint` de Android de por medio.
 *
 * Lo que aquí se comprueba y en ningún otro sitio se puede es **la promesa del
 * texto**: que ni una letra sale como fuente. Si una se colara, el archivo
 * seguiría abriéndose —y por eso no lo cazaría ninguna otra prueba— pero en un
 * ordenador sin Excalifont se vería con otra letra, en otro sitio y de otro
 * ancho.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawSvgTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun escenaCon(vararg e: Element) = Scene(elements = e.toList())

    private fun leer(svg: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(svg.byteInputStream())

    private fun cuantos(doc: Document, etiqueta: String) =
        doc.getElementsByTagName(etiqueta).length

    /**
     * ¿Esta máquina sabe sacar el perfil de una letra?
     *
     * Robolectric solo dibuja de verdad si puede cargar su runtime nativo, y
     * **en Linux ARM64 no existe**: `Paint.getTextPath` devuelve un camino vacío
     * sin quejarse. Las pruebas del texto en curvas se saltan ahí en vez de
     * fallar —el fallo sería de la máquina, no del código— y se ejecutan enteras
     * donde sí hay glifos, que es donde tienen que cazar el error.
     */
    private fun hayGlifos(): Boolean {
        val p = android.graphics.Paint()
        p.textSize = 40f
        val ruta = android.graphics.Path()
        return runCatching {
            p.getTextPath("Ao", 0, 2, 0f, 0f, ruta)
            !ruta.isEmpty
        }.getOrDefault(false)
    }

    private val rectangulo = Element(
        id = "r", type = ElementType.RECTANGLE,
        x = 20.0, y = 20.0, width = 120.0, height = 80.0, seed = 4321
    )

    private val texto = Element(
        id = "t", type = ElementType.TEXT,
        x = 20.0, y = 140.0, width = 200.0, height = 25.0, seed = 77,
        text = "Muro 1", fontSize = 20.0, strokeColor = "#e03131"
    )

    // ---- El archivo ----

    @Test
    fun `un dibujo vacío no da archivo`() {
        assertEquals(null, DrawSvg.aTexto(context, Scene()))
    }

    @Test
    fun `el dibujo sale como XML bien formado`() {
        val svg = DrawSvg.aTexto(context, escenaCon(rectangulo))
        assertNotNull("no ha salido nada", svg)
        val doc = leer(svg!!)
        assertEquals("svg", doc.documentElement.tagName)
        assertTrue("no hay ningún trazo", cuantos(doc, "path") >= 1)
    }

    /** El encuadre es el mismo que el del PNG: el contenido más su margen. */
    @Test
    fun `encuadra el contenido con margen`() {
        val svg = DrawSvg.aTexto(context, escenaCon(rectangulo))!!
        val caja = leer(svg).documentElement.getAttribute("viewBox").split(' ')
        assertEquals(4, caja.size)
        assertEquals(10.0, caja[0].toDouble(), 0.01)   // 20 - 10 de margen
        assertEquals(10.0, caja[1].toDouble(), 0.01)
        assertEquals(140.0, caja[2].toDouble(), 0.01)  // 120 + 2 × 10
        assertEquals(100.0, caja[3].toDouble(), 0.01)
    }

    // ---- La promesa del texto ----

    /**
     * **Ni una letra como fuente.**
     *
     * Es la razón de ser de este exportador frente al PNG: un SVG viaja solo, y
     * si dentro pusiera «esto va en Excalifont» el ordenador que lo abra la
     * sustituiría por otra cosa.
     */
    @Test
    fun `el texto no sale como texto`() {
        assumeTrue("esta máquina no sabe sacar perfiles de letras", hayGlifos())
        val svg = DrawSvg.aTexto(context, escenaCon(texto))!!
        val doc = leer(svg)
        assertEquals("hay texto sin convertir", 0, cuantos(doc, "text"))
        assertFalse("se ha colado una fuente", svg.contains("font-family"))
        assertFalse("se ha colado una fuente", svg.contains("@font-face"))
        assertTrue("el texto no ha dejado curvas", cuantos(doc, "path") >= 1)
    }

    /**
     * Y las curvas son las letras de verdad, no una caja.
     *
     * «Muro 1» tiene seis glifos y una «o» con agujero: el perfil tiene que
     * traer bastantes más de seis subcaminos... o al menos varios, y desde luego
     * más de un puñado de puntos. Un exportador que dibujara la caja del texto
     * pasaría todas las pruebas anteriores y fallaría esta.
     */
    @Test
    fun `las curvas del texto tienen la forma de las letras`() {
        assumeTrue("esta máquina no sabe sacar perfiles de letras", hayGlifos())
        val svg = DrawSvg.aTexto(context, escenaCon(texto))!!
        val doc = leer(svg)
        val caminos = doc.getElementsByTagName("path")
        var mejor = ""
        for (i in 0 until caminos.length) {
            val d = (caminos.item(i) as org.w3c.dom.Element).getAttribute("d")
            if (d.length > mejor.length) mejor = d
        }
        assertTrue("no hay perfil de letras: $mejor", mejor.count { it == 'M' } >= 5)
        assertTrue("el perfil tiene muy pocos puntos", mejor.count { it == 'L' } >= 40)
    }

    /** El color del texto es el suyo, no el negro por defecto. */
    @Test
    fun `el texto conserva su color`() {
        assumeTrue("esta máquina no sabe sacar perfiles de letras", hayGlifos())
        val svg = DrawSvg.aTexto(context, escenaCon(texto))!!
        assertTrue("no lleva su color: $svg", svg.contains("#e03131"))
    }

    // ---- Lo demás ----

    @Test
    fun `una raya a trazos sale a trazos`() {
        val raya = Element(
            id = "l", type = ElementType.LINE, x = 0.0, y = 0.0,
            width = 100.0, height = 0.0, seed = 5,
            points = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)),
            strokeStyle = StrokeStyle.DASHED
        )
        val svg = DrawSvg.aTexto(context, escenaCon(raya))!!
        assertTrue("no lleva guiones: $svg", svg.contains("stroke-dasharray"))
    }

    @Test
    fun `un elemento girado se envuelve en su giro`() {
        val girado = rectangulo.copy(angle = Math.toRadians(30.0))
        val svg = DrawSvg.aTexto(context, escenaCon(girado))!!
        assertTrue("no gira: $svg", svg.contains("rotate(30 80 60)"))
    }

    /** El foco oscurece con un solo camino par/impar, como en pantalla. */
    @Test
    fun `el foco sale de una pieza y con agujero`() {
        val foco = Element(
            id = "f", type = ElementType.SPOTLIGHT,
            x = 40.0, y = 40.0, width = 60.0, height = 40.0, seed = 3
        )
        val svg = DrawSvg.aTexto(context, escenaCon(rectangulo, foco))!!
        assertTrue("no usa par/impar: $svg", svg.contains("fill-rule=\"evenodd\""))
        // El marco más el hueco: dos subcaminos, ni uno más.
        val sombra = svg.lines().first { it.contains("fill-rule=\"evenodd\"") }
        assertEquals(2, sombra.count { it == 'M' })
    }

    /** Las guías escondidas no se exportan; el dibujo, sí. */
    @Test
    fun `una guía escondida no llega al archivo`() {
        val guia = rectangulo.copy(id = "g", reference = true, x = 400.0)
        val svg = DrawSvg.aTexto(
            context, Scene(elements = listOf(rectangulo, guia), referenciasVisibles = false)
        )!!
        val ancho = leer(svg).documentElement.getAttribute("viewBox").split(' ')[2].toDouble()
        assertTrue("la guía ha estirado el encuadre: $ancho", ancho < 200.0)
    }

    /** Un dibujo con lo de siempre no se cae por el camino. */
    @Test
    fun `un dibujo variado se escribe entero`() {
        val svg = DrawSvg.aTexto(
            context,
            escenaCon(
                rectangulo,
                texto,
                Element(
                    id = "e", type = ElementType.ELLIPSE, x = 200.0, y = 20.0,
                    width = 90.0, height = 90.0, seed = 12,
                    backgroundColor = "#a5d8ff", fillStyle = FillStyle.HACHURE
                ),
                Element(
                    id = "n", type = ElementType.SERIAL, x = 320.0, y = 20.0,
                    width = 40.0, height = 40.0, seed = 8, text = "3",
                    strokeColor = "#e03131"
                ),
                Element(
                    id = "f", type = ElementType.FREEDRAW, x = 20.0, y = 200.0,
                    width = 100.0, height = 40.0, seed = 9,
                    points = listOf(
                        Pt(0.0, 0.0), Pt(30.0, 20.0), Pt(60.0, 5.0), Pt(100.0, 40.0)
                    )
                )
            )
        )
        assertNotNull("se ha caído por el camino", svg)
        val doc = leer(svg!!)
        assertEquals("alguien ha dejado texto sin convertir", 0, cuantos(doc, "text"))
        assertTrue(cuantos(doc, "path") >= 5)
        assertTrue("falta el círculo del número", cuantos(doc, "circle") >= 1)
    }
}
