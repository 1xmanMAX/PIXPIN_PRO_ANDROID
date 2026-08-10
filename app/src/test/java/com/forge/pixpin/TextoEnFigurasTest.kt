package com.forge.pixpin.motor

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Texto dentro de una figura: el rectángulo con su palabra.
 *
 * Las cuentas son las de Excalidraw, cotejadas contra `textElement.ts`. Se
 * comprueban aquí porque son **cuentas que fallan en silencio**: un texto mal
 * centrado se ve raro y ya, pero un ancho mal calculado deja el texto saliéndose
 * del rombo y eso no se nota hasta que alguien dibuja un rombo.
 */
class TextoEnFigurasTest {

    private fun figura(tipo: ElementType, w: Double = 200.0, h: Double = 100.0) = Element(
        id = "c", type = tipo, x = 50.0, y = 30.0, width = w, height = h, seed = 1
    )

    // ---- Cuánto cabe en cada figura ----

    @Test
    fun `en un rectángulo cabe todo menos el aire de los lados`() {
        val r = figura(ElementType.RECTANGLE)
        assertEquals(200.0 - 10.0, anchoQueCabe(r), 0.001)
        assertEquals(100.0 - 10.0, altoQueCabe(r), 0.001)
    }

    /**
     * **En un círculo cabe menos, y por geometría.**
     *
     * El rectángulo más grande que cabe dentro de una elipse mide `ancho/2·√2`,
     * o sea un 70 % del ancho. Reinventar esto daría un texto que cabe en un
     * rectángulo y se sale de un círculo.
     */
    @Test
    fun `en una elipse cabe el rectángulo inscrito`() {
        val e = figura(ElementType.ELLIPSE)
        assertEquals(Math.round(100.0 * sqrt(2.0)) - 10.0, anchoQueCabe(e), 0.001)
        assertTrue("debería caber menos que en un rectángulo", anchoQueCabe(e) < 190.0)
    }

    /** Y en un rombo, la mitad: es el que menos sitio tiene dentro. */
    @Test
    fun `en un rombo cabe la mitad`() {
        val d = figura(ElementType.DIAMOND)
        assertEquals(100.0 - 10.0, anchoQueCabe(d), 0.001)
        assertTrue("el rombo tiene menos sitio que la elipse",
            anchoQueCabe(d) < anchoQueCabe(figura(ElementType.ELLIPSE)))
    }

    @Test
    fun `solo tres figuras admiten texto dentro`() {
        for (t in listOf(ElementType.RECTANGLE, ElementType.ELLIPSE, ElementType.DIAMOND)) {
            assertTrue("$t debería admitirlo", admiteTextoDentro(t))
        }
        for (t in listOf(ElementType.LINE, ElementType.FREEDRAW, ElementType.IMAGE)) {
            assertTrue("$t no debería admitirlo", !admiteTextoDentro(t))
        }
    }

    // ---- La caja crece ----

    /**
     * La vuelta de la cuenta: si el texto mide tanto, la caja tiene que medir
     * esto. Es lo que hace que **la caja crezca sola** al escribir de más.
     */
    @Test
    fun `la figura que contiene un texto es más grande que él`() {
        for (tipo in listOf(ElementType.RECTANGLE, ElementType.ELLIPSE, ElementType.DIAMOND)) {
            val necesaria = figuraQueLoContiene(80.0, tipo)
            assertTrue("$tipo: la caja no envuelve al texto", necesaria > 80.0)
            // Y es coherente con la de ida: en una caja de ese tamaño, cabe.
            val caja = figura(tipo, w = necesaria, h = necesaria)
            assertTrue(
                "$tipo: la caja calculada no da sitio ($necesaria)",
                anchoQueCabe(caja) >= 80.0 - 1.0
            )
        }
    }

    // ---- Dónde va ----

    @Test
    fun `el texto queda centrado en su figura`() {
        val r = figura(ElementType.RECTANGLE)
        val donde = sitioDelTextoDentro(r, 100.0 to 20.0)
        // Centro de la caja menos medio texto.
        assertEquals(50.0 + 100.0 - 50.0, donde.x, 0.001)
        assertEquals(30.0 + 50.0 - 10.0, donde.y, 0.001)
    }

    /** En una elipse el hueco empieza más adentro, así que el centro cuadra igual. */
    @Test
    fun `en una elipse también queda centrado`() {
        val e = figura(ElementType.ELLIPSE)
        val donde = sitioDelTextoDentro(e, 60.0 to 20.0)
        val centroTexto = Pt(donde.x + 30.0, donde.y + 10.0)
        assertEquals("no está centrado en x", 50.0 + 100.0, centroTexto.x, 1.0)
        assertEquals("no está centrado en y", 30.0 + 50.0, centroTexto.y, 1.0)
    }

    // ---- El reparto en líneas ----

    /** Se corta por palabras, que es como se lee. */
    @Test
    fun `el texto se parte por palabras`() {
        // Cada carácter mide 10: «uno dos» son 70.
        val mide: (String) -> Double = { it.length * 10.0 }
        assertEquals(listOf("uno", "dos"), repartirEnLineas("uno dos", 40.0, mide))
        assertEquals(listOf("uno dos"), repartirEnLineas("uno dos", 100.0, mide))
    }

    /** Los saltos que escribió el usuario son suyos y se respetan. */
    @Test
    fun `los saltos de línea propios se conservan`() {
        val mide: (String) -> Double = { it.length * 10.0 }
        assertEquals(listOf("a", "b"), repartirEnLineas("a\nb", 500.0, mide))
        assertEquals(listOf("a", "", "b"), repartirEnLineas("a\n\nb", 500.0, mide))
    }

    /**
     * Una palabra que no cabe entera se parte.
     *
     * Un nombre largo en una caja estrecha tiene que verse aunque quede
     * partido: desbordar sería peor.
     */
    @Test
    fun `una palabra sola que no cabe se parte`() {
        val mide: (String) -> Double = { it.length * 10.0 }
        val lineas = repartirEnLineas("abcdefgh", 30.0, mide)
        assertTrue("no la ha partido: $lineas", lineas.size > 1)
        assertEquals("abcdefgh", lineas.joinToString(""))
    }

    @Test
    fun `sin sitio no se cuelga`() {
        val mide: (String) -> Double = { it.length * 10.0 }
        assertEquals(listOf("hola"), repartirEnLineas("hola", 0.0, mide))
        assertEquals(listOf(""), repartirEnLineas("", 100.0, mide))
    }

    // ---- El vínculo ----

    /**
     * **Se atan por los dos lados.**
     *
     * El texto guarda de quién es para saber dónde colocarse; la figura guarda
     * qué lleva dentro para poder arrastrarlo y borrarlo con ella. Con un solo
     * lado, mover la figura dejaría el texto atrás.
     */
    @Test
    fun `atar deja constancia en los dos`() {
        val caja = figura(ElementType.RECTANGLE)
        val texto = Element(id = "t", type = ElementType.TEXT, x = 0.0, y = 0.0,
            width = 10.0, height = 10.0, seed = 1, text = "hola")
        val (c, t) = atados(caja, texto)
        assertEquals("c", t.containerId)
        assertEquals(listOf("t"), c.boundElements!!.map { it.id })

        val escena = listOf(c, t)
        assertEquals("t", textoDe(c, escena)?.id)
        assertEquals("c", contenedorDe(t, escena)?.id)
    }

    /** Y atar dos veces no lo apunta dos veces. */
    @Test
    fun `atar lo que ya estaba atado no duplica`() {
        val caja = figura(ElementType.RECTANGLE)
        val texto = Element(id = "t", type = ElementType.TEXT, x = 0.0, y = 0.0,
            width = 10.0, height = 10.0, seed = 1)
        val (una, _) = atados(caja, texto)
        val (dos, _) = atados(una, texto)
        assertEquals(1, dos.boundElements!!.size)
    }

    @Test
    fun `una figura sin texto no lo tiene`() {
        assertNull(textoDe(figura(ElementType.RECTANGLE), emptyList()))
    }
}

/** El texto dentro de una figura, usándolo desde el editor. */
class TextoEnFigurasEnElEditorTest {

    private fun conUnRectangulo(): DrawController {
        val c = DrawController()
        c.load(
            Scene(
                elements = listOf(
                    Element(
                        id = "caja", type = ElementType.RECTANGLE,
                        x = 0.0, y = 0.0, width = 200.0, height = 100.0, seed = 1
                    )
                )
            )
        )
        return c
    }

    private fun textos(c: DrawController) = c.scene.elements.filter { it.type == ElementType.TEXT }

    /** Tocar la figura con la herramienta de texto escribe dentro de ella. */
    @Test
    fun `el texto nace dentro de la figura`() {
        val c = conUnRectangulo()
        c.selectTool(Tool.TEXT)
        c.pointerDown(Pt(100.0, 50.0))
        c.pointerUp(Pt(100.0, 50.0))

        assertEquals(1, textos(c).size)
        val t = textos(c)[0]
        assertEquals("no se ha atado a la caja", "caja", t.containerId)
        val caja = c.scene.byId("caja")!!
        assertEquals(listOf(t.id), caja.boundElements!!.map { it.id })
    }

    /** Volver a tocarla sigue escribiendo en el suyo, no crea otro. */
    @Test
    fun `tocar dos veces no crea dos textos`() {
        val c = conUnRectangulo()
        c.selectTool(Tool.TEXT)
        c.pointerDown(Pt(100.0, 50.0)); c.pointerUp(Pt(100.0, 50.0))
        c.pointerDown(Pt(90.0, 40.0)); c.pointerUp(Pt(90.0, 40.0))
        assertEquals(1, textos(c).size)
    }

    /** Escribir de más engorda la caja, y borrar no la encoge. */
    @Test
    fun `la caja crece con el texto y no encoge`() {
        val c = conUnRectangulo()
        c.selectTool(Tool.TEXT)
        c.pointerDown(Pt(100.0, 50.0)); c.pointerUp(Pt(100.0, 50.0))
        val t = textos(c)[0].id

        c.updateText(t, "una línea", 100.0, 25.0)
        assertEquals("no debería crecer todavía", 100.0, c.scene.byId("caja")!!.height, 0.001)

        c.updateText(t, "muchas\nlíneas\nseguidas\naquí", 100.0, 200.0)
        val alta = c.scene.byId("caja")!!.height
        assertTrue("la caja no ha crecido: $alta", alta > 200.0)

        c.updateText(t, "poco", 40.0, 25.0)
        assertEquals("la caja ha encogido sola", alta, c.scene.byId("caja")!!.height, 0.001)
    }

    /** Mover la figura se lleva su texto centrado. */
    @Test
    fun `el texto se va con su figura`() {
        val c = conUnRectangulo()
        c.selectTool(Tool.TEXT)
        c.pointerDown(Pt(100.0, 50.0)); c.pointerUp(Pt(100.0, 50.0))
        c.updateText(textos(c)[0].id, "hola", 40.0, 20.0)
        val antes = textos(c)[0]

        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(100.0, 50.0))
        c.pointerMove(Pt(400.0, 250.0))
        c.pointerUp(Pt(400.0, 250.0))

        val despues = textos(c)[0]
        assertEquals("el texto se ha quedado atrás", 300.0, despues.x - antes.x, 1.0)
        assertEquals("el texto se ha quedado atrás", 200.0, despues.y - antes.y, 1.0)
    }

    /** Y borrar la figura se lleva su texto: nada de huérfanos flotando. */
    @Test
    fun `borrar la figura borra su texto`() {
        val c = conUnRectangulo()
        c.selectTool(Tool.TEXT)
        c.pointerDown(Pt(100.0, 50.0)); c.pointerUp(Pt(100.0, 50.0))
        c.updateText(textos(c)[0].id, "hola", 40.0, 20.0)

        c.setSelection(setOf("caja"))
        c.deleteSelection()
        assertTrue(
            "ha quedado un texto huérfano",
            c.scene.elements.none { it.type == ElementType.TEXT && !it.isDeleted }
        )
    }
}
