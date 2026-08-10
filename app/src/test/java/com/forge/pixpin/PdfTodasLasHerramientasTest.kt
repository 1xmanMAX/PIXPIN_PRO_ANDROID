package com.forge.pixpin.motor

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **Todo lo que dibuja el motor tiene que llegar al PDF.**
 *
 * Tres tipos estuvieron sin escribirse sin que nadie se enterara —el mosaico, la
 * escala gráfica y el número de la cota— y se descubrieron usándolo. El mosaico
 * era el peligroso: dabas por tapado un dato que seguía a la vista.
 *
 * Esta prueba recorre **el enum entero**, así que un tipo nuevo que nadie
 * escriba la rompe el día que se añade, y no meses después con un documento ya
 * entregado. Es la misma idea que la prueba de que todas las herramientas están
 * en la barra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfTodasLasHerramientasTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /**
     * Los que **a propósito** no pintan nada, con su motivo.
     *
     * Van aquí y no simplemente ignorados para que añadir uno sea una decisión
     * que alguien escribe, no un olvido que pasa desapercibido.
     */
    private val noPintanNada = mapOf(
        // La hoja delimita hasta dónde llega el dibujo; no es algo dibujado.
        // Es el único que no deja rastro, y a propósito.
        ElementType.FRAME to "es la hoja, no una raya"
    )

    /** Un elemento de cada tipo, con lo mínimo para que tenga algo que dibujar. */
    private fun ejemplar(tipo: ElementType): Element = Element(
        id = "e-$tipo",
        type = tipo,
        x = 60.0, y = 60.0, width = 220.0, height = 120.0,
        seed = 1234,
        strokeColor = "#1971c2",
        backgroundColor = "#a5d8ff",
        fillStyle = FillStyle.HACHURE,
        strokeWidth = 2.0,
        text = "Ab 12",
        fontSize = 20.0,
        points = listOf(Pt(0.0, 0.0), Pt(120.0, 40.0), Pt(220.0, 120.0)),
        pressures = listOf(0.5, 0.8, 0.4),
        huecos = emptyList(),
        arcStart = 0.0,
        arcSweep = Math.PI,
        etiquetaAngulo = -Math.PI / 4,
        etiquetaRadio = 22.0
    )

    private fun escribe(e: Element): Int {
        val escena = Scene(
            escala = Escala(unidadesPorPixel = 0.01, unidad = "m"),
            elements = listOf(e)
        )
        val salida = PdfLienzo.deEscena(
            context, escena,
            matriz = doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, 842.0),
            primerObjeto = 20
        )
        return salida?.contenido?.size ?: 0
    }

    /**
     * El recorrido entero. Lo que dibuja tiene que dejar órdenes; lo que no
     * dibuja tiene que estar en la lista de excepciones **con su motivo**.
     */
    @Test
    fun `todos los tipos del motor se escriben en el PDF`() {
        val mudos = mutableListOf<ElementType>()
        for (tipo in ElementType.entries) {
            val escrito = escribe(ejemplar(tipo))
            val esperado = tipo !in noPintanNada
            if (esperado && escrito == 0) mudos += tipo
            if (!esperado && escrito > 0) {
                // Que uno de los apartados empiece a pintar tampoco es normal:
                // querría decir que la decisión de dejarlo fuera se ha roto.
                mudos += tipo
            }
        }
        assertTrue(
            "estos tipos no llegan al PDF (o llegan y no debían): $mudos",
            mudos.isEmpty()
        )
    }

    /**
     * Y lo mismo para el SVG, que es la otra salida vectorial.
     *
     * Las dos recorren la escena por su cuenta, así que un tipo nuevo puede
     * entrar en una y quedarse fuera de la otra sin que nada se queje. Es la
     * deuda que queda de tener dos recorredores en paralelo, y esta prueba es lo
     * que la mantiene a raya mientras siga habiendo dos.
     */
    @Test
    fun `todos los tipos del motor se escriben en el SVG`() {
        val mudos = mutableListOf<ElementType>()
        for (tipo in ElementType.entries) {
            if (tipo in noPintanNada) continue
            val escena = Scene(
                escala = Escala(unidadesPorPixel = 0.01, unidad = "m"),
                elements = listOf(ejemplar(tipo))
            )
            val svg = DrawSvg.aTexto(context, escena)
            // Algo tiene que haber además del fondo y el envoltorio.
            val cuerpo = svg?.substringAfter("</rect>")?.substringBefore("</svg>").orEmpty()
            if (cuerpo.isBlank()) mudos += tipo
        }
        assertTrue("estos tipos no llegan al SVG: $mudos", mudos.isEmpty())
    }

    /**
     * **Y por herramienta, no solo por tipo.**
     *
     * Es como lo mira quien usa la app: no piensa en «un elemento de tipo
     * REGION», piensa en «el bote». Se recorre el enum de herramientas y, la que
     * cree algo, ese algo tiene que llegar al PDF.
     */
    @Test
    fun `lo que crea cada herramienta llega al PDF`() {
        val mudas = mutableListOf<Tool>()
        for (herramienta in Tool.entries) {
            val tipo = tipoQueCrea(herramienta) ?: continue
            if (tipo in noPintanNada) continue
            if (escribe(ejemplar(tipo)) == 0) mudas += herramienta
        }
        assertTrue("lo que hacen estas herramientas no llega al PDF: $mudas", mudas.isEmpty())
    }

    /**
     * Los rellenos, uno por uno.
     *
     * Cada uno pasa por un camino distinto —el sólido pinta la silueta, los
     * demás trazan un barrido recortado a ella— así que uno puede romperse sin
     * tocar a los otros. El de rayas rectas es propio y no viene del original,
     * que es justo el que más fácil se queda fuera de una traducción.
     */
    @Test
    fun `todos los rellenos llegan al PDF`() {
        val mudos = mutableListOf<FillStyle>()
        for (relleno in FillStyle.entries) {
            val e = ejemplar(ElementType.RECTANGLE).copy(fillStyle = relleno)
            if (escribe(e) == 0) mudos += relleno
        }
        assertTrue("estos rellenos no llegan al PDF: $mudos", mudos.isEmpty())
    }

    /** Y los tres tipos de raya: continua, a trazos y de puntos. */
    @Test
    fun `todos los estilos de trazo llegan al PDF`() {
        val mudos = mutableListOf<StrokeStyle>()
        for (estilo in StrokeStyle.entries) {
            val e = ejemplar(ElementType.LINE).copy(strokeStyle = estilo)
            if (escribe(e) == 0) mudos += estilo
        }
        assertTrue("estos trazos no llegan al PDF: $mudos", mudos.isEmpty())
    }

    /** Y las puntas de flecha, que son ocho y cada una se dibuja distinta. */
    @Test
    fun `todas las puntas de flecha llegan al PDF`() {
        val mudas = mutableListOf<Arrowhead>()
        for (punta in Arrowhead.entries) {
            val e = ejemplar(ElementType.ARROW).copy(endArrowhead = punta)
            if (escribe(e) == 0) mudas += punta
        }
        assertTrue("estas puntas no llegan al PDF: $mudas", mudas.isEmpty())
    }

    /**
     * Girado y translúcido siguen saliendo.
     *
     * Son dos cosas que se aplican **fuera** de la geometría —una matriz y un
     * estado gráfico— así que no se rompen igual que lo demás y hay que mirarlas
     * aparte.
     */
    @Test
    fun `lo girado y lo translúcido llegan al PDF`() {
        val girado = ejemplar(ElementType.RECTANGLE).copy(angle = Math.toRadians(30.0))
        assertTrue("lo girado no llega", escribe(girado) > 0)
        val translucido = ejemplar(ElementType.RECTANGLE).copy(opacity = 40)
        assertTrue("lo translúcido no llega", escribe(translucido) > 0)
        // Y el giro tiene que quedar escrito como matriz, no perdido.
        val escena = Scene(elements = listOf(girado))
        val salida = PdfLienzo.deEscena(
            context, escena, doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, 842.0), 20
        )!!
        val ordenes = String(salida.contenido, Charsets.ISO_8859_1)
        // Dos «cm»: el de la página y el del giro.
        assertTrue("el giro no se ha escrito", ordenes.split(" cm").size > 2)
    }

    /** El foco sí se pinta, pero por su camino: aquí se comprueba ese. */
    @Test
    fun `el foco se escribe por su propio camino`() {
        val escena = Scene(
            elements = listOf(
                ejemplar(ElementType.RECTANGLE),
                ejemplar(ElementType.SPOTLIGHT).copy(id = "foco")
            )
        )
        val salida = PdfLienzo.deEscena(
            context, escena,
            matriz = doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, 842.0),
            primerObjeto = 20
        )
        assertNotNull(salida)
        val ordenes = String(salida!!.contenido, Charsets.ISO_8859_1)
        assertTrue("el foco no oscurece nada", ordenes.contains("f*"))
    }
}
