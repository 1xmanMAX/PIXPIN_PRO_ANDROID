package com.forge.pixpin.motor

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    /**
     * **De noche la tinta se da la vuelta, también al exportar.**
     *
     * El modo noche de la aplicación es un filtro de pintado: los colores guardados siguen
     * siendo los del día. El SVG no lo pasaba, así que un dibujo hecho sobre papel de
     * pizarra se exportaba con su tinta negra original —negro sobre negro— y no se veía
     * nada. Ver [DrawTheme.filtrar].
     */
    @Test
    fun `sobre papel oscuro la tinta se invierte al exportar`() {
        val negro = rectangulo.copy(strokeColor = "#1e1e1e")
        val deDia = DrawSvg.aTexto(
            context, Scene(elements = listOf(negro), backgroundColor = "#ffffff")
        )!!
        val deNoche = DrawSvg.aTexto(
            context, Scene(elements = listOf(negro), backgroundColor = "#121212")
        )!!
        assertTrue("de día la tinta se queda como está", deDia.contains("#1e1e1e"))
        assertFalse("de noche no puede salir la tinta del día", deNoche.contains("#1e1e1e"))
        // El fondo sí es el que se guardó: es el papel, no la tinta.
        assertTrue(deNoche.contains("#121212"))
        // Y lo que sale es claro, que es lo que se ve sobre pizarra.
        val tinta = Regex("stroke=\"#([0-9a-f]{6})\"").find(deNoche)?.groupValues?.get(1)
        assertNotNull("no encontré la tinta exportada", tinta)
        val n = tinta!!.toInt(16)
        val luz = 0.2126 * ((n shr 16) and 0xff) + 0.7152 * ((n shr 8) and 0xff) + 0.0722 * (n and 0xff)
        assertTrue("la tinta de noche tenía que salir clara, y salió $tinta", luz > 128)
    }

    /**
     * **Con el papel aparte, el SVG no puede tapar lo de debajo.**
     *
     * Un SVG empieza siempre por un rectángulo del color del lienzo que lo cubre entero. Con
     * el plano del PDF pintado debajo en su propio lienzo (ver `VisorPlano`), ese rectángulo
     * lo tapaba: la página web exportada salía **en blanco**, con las anotaciones flotando
     * sobre nada. Es justo lo que reportó el usuario.
     */
    @Test
    fun `con el papel aparte el svg no lleva fondo que tape el plano`() {
        val papel = android.graphics.Bitmap.createBitmap(200, 100, android.graphics.Bitmap.Config.ARGB_8888)
        val conFondo = DrawSvg.aTexto(context, escenaCon(rectangulo), papel = papel)!!
        val sinFondo = DrawSvg.aTexto(
            context, escenaCon(rectangulo), papel = papel, papelAparte = true
        )!!
        assertTrue("lo de siempre lleva su fondo", conFondo.contains("<rect x=\"0\" y=\"0\""))
        assertTrue("y su papel dentro", conFondo.contains("<image"))
        assertFalse("con el papel aparte no puede ir la imagen", sinFondo.contains("<image"))
        // Ni el rectángulo del fondo, que es el que tapaba el plano.
        val fondos = leer(sinFondo).getElementsByTagName("rect")
        for (i in 0 until fondos.length) {
            val r = fondos.item(i) as org.w3c.dom.Element
            assertFalse(
                "el rectángulo del fondo sigue ahí y tapa el plano",
                r.getAttribute("width") == "200" && r.getAttribute("height") == "100" &&
                    r.getAttribute("x") == "0" && r.getAttribute("y") == "0"
            )
        }
    }

    /**
     * **Una foto opaca va en JPEG aunque venga en ARGB.**
     *
     * `Bitmap.hasAlpha()` dice si el mapa de bits *podría* tener transparencia, y en Android
     * toda captura y toda foto pegada viene en ARGB: decía que sí siempre, todo iba en PNG a
     * calidad cien, y un documento con cuatro notas y dos capturas pesaba dos megas. Lo
     * reportó el usuario. Ahora se miran los píxeles; y lo que sí es transparente va en WEBP,
     * que pesa una fracción del PNG.
     */
    @Test
    fun `las fotos van en jpeg si son opacas y en webp si no`() {
        val opaca = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        opaca.eraseColor(android.graphics.Color.rgb(120, 130, 140))
        val conHueco = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        conHueco.eraseColor(android.graphics.Color.rgb(120, 130, 140))
        for (x in 0 until 32) for (y in 0 until 32) conHueco.setPixel(x, y, 0)
        assertTrue("las dos vienen en ARGB, que es la trampa", opaca.hasAlpha() && conHueco.hasAlpha())

        val foto = Element(
            id = "foto", type = ElementType.IMAGE, x = 0.0, y = 0.0,
            width = 64.0, height = 64.0, seed = 1, fileId = "f"
        )
        val svgOpaca = DrawSvg.aTexto(context, escenaCon(foto), imageProvider = { opaca })!!
        val svgHueco = DrawSvg.aTexto(context, escenaCon(foto), imageProvider = { conHueco })!!
        assertTrue("la opaca tenía que ir en JPEG", svgOpaca.contains("data:image/jpeg;base64,"))
        assertFalse(svgOpaca.contains("image/png"))
        assertTrue("la del hueco tenía que ir en WEBP", svgHueco.contains("data:image/webp;base64,"))
        assertFalse("y nunca en PNG", svgHueco.contains("image/png"))
    }

    @Test
    fun `un dibujo vacío no da archivo`() {
        assertEquals(null, DrawSvg.aTexto(context, Scene()))
    }

    /**
     * **Un fallo al escribir no es un dibujo vacío.**
     *
     * Esto era un `runCatching{…}.getOrNull()`: cualquier excepción —memoria al
     * incrustar una foto, un perfil que no carga— se convertía en `null`, y la
     * exportación anunciaba «el dibujo está vacío» con el lienzo lleno. El null
     * solo puede significar que no hay nada; el fallo sube para que quien llama
     * lo distinga (ver `DrawEditorActivity.exportando`).
     */
    @Test
    fun `un fallo al escribir no se anuncia como dibujo vacío`() {
        val foto = Element(
            id = "foto", type = ElementType.IMAGE, x = 0.0, y = 0.0,
            width = 64.0, height = 64.0, seed = 1, fileId = "f"
        )
        try {
            DrawSvg.aTexto(context, escenaCon(foto), imageProvider = {
                throw RuntimeException("se rompió al incrustar la foto")
            })
            fail("el fallo tenía que subir, no volver null como si no hubiera nada")
        } catch (esperado: RuntimeException) {
            assertEquals("se rompió al incrustar la foto", esperado.message)
        }
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
        // Cada letra es un perfil en `<defs>` y cada aparición un `<use>`: la forma se mira
        // sumando todos los perfiles, y la «o» con su agujero es un perfil de dos contornos.
        val caminos = doc.getElementsByTagName("path")
        var emes = 0
        var eles = 0
        for (i in 0 until caminos.length) {
            val d = (caminos.item(i) as org.w3c.dom.Element).getAttribute("d")
            emes += d.count { it == 'M' }
            eles += d.count { it == 'L' }
        }
        assertTrue("no hay perfil de letras", emes >= 5)
        assertTrue("el perfil tiene muy pocos puntos", eles >= 40)
        assertEquals("«Muro 1» son seis letras puestas", 6, cuantos(doc, "use"))
    }

    /**
     * **Una letra que se repite se define una sola vez.**
     *
     * Es lo que deja un documento de texto en kilobytes en vez de en megas: el perfil de la
     * «a» es el mismo en las ocho apariciones, así que va una vez en `<defs>` y ocho `<use>`.
     */
    @Test
    fun `las letras repetidas comparten su perfil`() {
        assumeTrue("esta máquina no sabe sacar perfiles de letras", hayGlifos())
        val repetido = texto.copy(text = "aaaa aaaa")
        val svg = DrawSvg.aTexto(context, escenaCon(repetido))!!
        val doc = leer(svg)
        assertEquals("un solo perfil para la «a»", 1, doc.getElementsByTagName("defs").length)
        assertEquals(1, cuantos(doc, "path"))
        assertEquals("y ocho apariciones", 8, cuantos(doc, "use"))
        // Y con otro tamaño es otro perfil: la letra no se estira, se vuelve a sacar.
        val grande = texto.copy(text = "a", fontSize = 40.0, id = "t2", y = 200.0)
        val svg2 = DrawSvg.aTexto(context, Scene(elements = listOf(repetido, grande)))!!
        assertEquals(2, cuantos(leer(svg2), "path"))
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

    /**
     * El foco, **como en pantalla**: la sombra es la caja del propio foco —no
     * el dibujo entero—, con el hueco de la figura marcada dentro y la
     * intensidad de su mando. Aquí estaba el fallo de que en lo exportado lo
     * oscuro tapaba toda la zona anotada.
     */
    @Test
    fun `el foco oscurece su caja con agujero, no el dibujo entero`() {
        val foco = Element(
            id = "f", type = ElementType.SPOTLIGHT,
            x = 40.0, y = 40.0, width = 60.0, height = 40.0, seed = 3,
            oscurecer = 60
        )
        val svg = DrawSvg.aTexto(context, escenaCon(rectangulo, foco))!!
        assertTrue("no usa par/impar: $svg", svg.contains("fill-rule=\"evenodd\""))
        val sombra = svg.lines().first { it.contains("fill-rule=\"evenodd\"") }
        // El marco más el hueco: dos subcaminos, ni uno más.
        assertEquals(2, sombra.count { it == 'M' })
        // La intensidad es la del mando del foco, no una cuenta aparte.
        assertTrue("la intensidad no es la del mando: $sombra", sombra.contains("fill-opacity=\"0.6\""))
        // Y la sombra arranca en la caja del foco (x=40), no en el borde del
        // encuadre exportado: el rectángulo de al lado tiene que quedar fuera.
        assertTrue("la sombra no es la caja del foco: $sombra", sombra.contains("M40 40"))
    }

    /**
     * La página del PDF que se ve de fondo **sale en el archivo**: incrustada
     * en (0,0) y con el encuadre de la página. Aquí estaba el fallo de que al
     * exportar la anotación de una página la página no aparecía.
     */
    @Test
    fun `el papel de fondo se incrusta y manda en el encuadre`() {
        val papel = android.graphics.Bitmap.createBitmap(
            300, 200, android.graphics.Bitmap.Config.ARGB_8888
        )
        val svg = DrawSvg.aTexto(context, escenaCon(rectangulo), papel = papel)!!
        assertTrue("no incrusta la página: $svg", svg.contains("<image x=\"0\" y=\"0\""))
        assertTrue(svg.contains("width=\"300\" height=\"200\""))
        assertTrue(svg.contains("data:image/"))
        // El encuadre es la página entera, no solo el contenido dibujado.
        val vb = leer(svg).documentElement.getAttribute("viewBox").split(' ')
        assertEquals(0.0, vb[0].toDouble(), 1e-6)
        assertEquals(0.0, vb[1].toDouble(), 1e-6)
        assertEquals(300.0, vb[2].toDouble(), 1e-6)
        assertEquals(200.0, vb[3].toDouble(), 1e-6)
    }

    /**
     * **El papel fino manda en el detalle y el de siempre en la geometría.**
     *
     * En la web el papel es una imagen y nada más: con los mil cuatrocientos píxeles de la
     * pantalla, un plano ampliado en el navegador es una mancha. Se mete el mismo papel con
     * todos los píxeles que se puedan, **colocado en el sitio y el tamaño del otro** — si
     * mandara su tamaño, lo anotado encima se descolocaría. Ver [DrawSvg.aTexto].
     */
    @Test
    fun `el papel fino se incrusta con el tamano del papel de la escena`() {
        val papel = android.graphics.Bitmap.createBitmap(
            300, 200, android.graphics.Bitmap.Config.ARGB_8888
        )
        val fino = android.graphics.Bitmap.createBitmap(
            1200, 800, android.graphics.Bitmap.Config.ARGB_8888
        )
        val svg = DrawSvg.aTexto(context, escenaCon(rectangulo), papel = papel, papelFino = fino)!!
        assertTrue("la página no se coloca en unidades de la escena: $svg",
            svg.contains("<image x=\"0\" y=\"0\" width=\"300\" height=\"200\""))
        assertFalse("se coló el tamaño del mapa de bits fino", svg.contains("width=\"1200\""))
    }

    /**
     * La lupa exportada **ve la página**: su cristal lleva una imagen dentro.
     * Aquí estaba el fallo de que en la página web la lupa saliera vacía: el
     * renderizador que la pinta para el archivo no recibía el papel.
     */
    @Test
    fun `la lupa sobre la pagina lleva su contenido dentro`() {
        val papel = android.graphics.Bitmap.createBitmap(
            300, 200, android.graphics.Bitmap.Config.ARGB_8888
        )
        val lupa = Element(
            id = "l", type = ElementType.LUPA, x = 40.0, y = 40.0,
            width = 120.0, height = 80.0, seed = 7, aumento = 2.0
        )
        val svg = DrawSvg.aTexto(context, escenaCon(rectangulo, lupa), papel = papel)!!
        assertTrue("la lupa no recorta nada: $svg", svg.contains("<clipPath id=\"lupa"))
        // Dos imágenes: la página y lo que enseña la lupa.
        assertEquals(2, cuantos(leer(svg), "image"))
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
