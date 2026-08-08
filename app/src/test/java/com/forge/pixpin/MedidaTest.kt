package com.forge.pixpin.motor

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Medir. Es lo que más caro sale equivocar de todo el motor: un color mal
 * elegido se ve, una cota mal calculada se cree.
 *
 * Todo esto se comprueba sin dispositivo porque es geometría pura, que es la
 * razón de que la escala y la cota vivan en el núcleo del motor y no en la capa
 * que pinta. Mismo criterio que traía el croquis, de donde viene.
 */
class MedidaTest {

    private val es = Locale.forLanguageTag("es-ES")

    private fun cota(x1: Double, y1: Double, x2: Double, y2: Double): Element =
        newElement(ElementType.MEASURE, x1, y1, ItemStyle())
            .copy(points = listOf(Pt(0.0, 0.0), Pt(x2 - x1, y2 - y1)))

    // ---- La calibración ----

    @Test
    fun `calibrar reparte la medida entre los pixeles`() {
        val escala = Escala.calibrar(largoPx = 200.0, medidaReal = 4.0)
        assertEquals(0.02, escala!!.unidadesPorPixel, 1e-9)
        assertTrue(escala.valida)
    }

    /** Sin escala válida no se entra a medir: más vale no medir que medir mal. */
    @Test
    fun `una calibración imposible no devuelve escala`() {
        assertNull(Escala.calibrar(largoPx = 0.0, medidaReal = 4.0))
        assertNull(Escala.calibrar(largoPx = 200.0, medidaReal = 0.0))
        assertNull(Escala.calibrar(largoPx = 200.0, medidaReal = -3.0))
        assertNull(Escala.calibrar(largoPx = Double.NaN, medidaReal = 4.0))
        assertNull(Escala.calibrar(largoPx = 200.0, medidaReal = Double.POSITIVE_INFINITY))
    }

    @Test
    fun `una escala que no es un número positivo no vale para medir`() {
        assertFalse(Escala(0.0).valida)
        assertFalse(Escala(-1.0).valida)
        assertFalse(Escala(Double.NaN).valida)
    }

    /** El teclado del diálogo escribe coma; lo pegado de fuera llega con punto. */
    @Test
    fun `el número se lee con coma y con punto`() {
        assertEquals(4.2, Escala.leerNumero("4,2")!!, 1e-9)
        assertEquals(4.2, Escala.leerNumero("4.2")!!, 1e-9)
        assertEquals(4.0, Escala.leerNumero(" 4 ")!!, 1e-9)
        assertNull(Escala.leerNumero(""))
        assertNull(Escala.leerNumero("cuatro"))
    }

    // ---- El largo ----

    @Test
    fun `el largo es de punta a punta`() {
        assertEquals(5.0, longitudDe(cota(0.0, 0.0, 3.0, 4.0)), 1e-9)
        assertEquals(0.0, longitudDe(cota(7.0, 7.0, 7.0, 7.0)), 1e-9)
    }

    /** Un elemento sin puntos no mide: no es que mida cero, es que no es una cota. */
    @Test
    fun `sin puntos no hay largo`() {
        assertEquals(0.0, longitudDe(newElement(ElementType.RECTANGLE, 0.0, 0.0, ItemStyle())), 1e-9)
    }

    // ---- El rótulo ----

    @Test
    fun `con escala se rotula en su unidad`() {
        val escala = Escala.calibrar(100.0, 2.0)!!
        assertEquals("4,00 m", textoDeCota(cota(0.0, 0.0, 200.0, 0.0), escala, es))
    }

    /**
     * **Sin escala se dice que son píxeles.** Una cota que pareciera metros sin
     * haber calibrado nada es la peor forma de equivocarse que tiene esto.
     */
    @Test
    fun `sin escala se rotula en píxeles y se avisa`() {
        val texto = textoDeCota(cota(0.0, 0.0, 200.0, 0.0), null, es)
        assertEquals("200 px", texto)
        assertTrue(texto.endsWith("px"))
    }

    @Test
    fun `los decimales y la unidad son los de la escala`() {
        val escala = Escala(unidadesPorPixel = 0.5, unidad = "cm", decimales = 1)
        assertEquals("50,0 cm", textoDeMedida(100.0, escala, es))
    }

    /** La medida en número, para quien la sume; null mientras no haya escala. */
    @Test
    fun `la medida en unidades solo existe con escala`() {
        val c = cota(0.0, 0.0, 0.0, 50.0)
        assertNull(medidaDe(c, null))
        assertEquals(1.0, medidaDe(c, Escala.calibrar(100.0, 2.0))!!, 1e-9)
    }

    // ---- Dictar la longitud ----

    @Test
    fun `dictar el largo conserva la dirección`() {
        val ajustada = conLongitud(cota(10.0, 10.0, 13.0, 14.0), 10.0)
        assertEquals(10.0, longitudDe(ajustada), 1e-9)
        // Misma dirección: el punto final va sobre la recta original.
        val p = ajustada.points!!.last()
        assertEquals(6.0, p.x, 1e-9)
        assertEquals(8.0, p.y, 1e-9)
    }

    @Test
    fun `un largo imposible deja la cota como estaba`() {
        val c = cota(0.0, 0.0, 3.0, 4.0)
        assertEquals(c, conLongitud(c, 0.0))
        assertEquals(c, conLongitud(c, -1.0))
        // Sin dirección que conservar tampoco hay nada que hacer.
        val punto = cota(1.0, 1.0, 1.0, 1.0)
        assertEquals(punto, conLongitud(punto, 5.0))
    }

    // ---- Cómo se lee ----

    /** Una cota se lee de izquierda a derecha o no se lee. */
    @Test
    fun `el rótulo se voltea cuando la línea va hacia atrás`() {
        assertFalse(rotuloDelReves(0.0))
        assertFalse(rotuloDelReves(89.0))
        assertFalse(rotuloDelReves(-89.0))
        assertTrue(rotuloDelReves(91.0))
        assertTrue(rotuloDelReves(-91.0))
        assertTrue(rotuloDelReves(180.0))
        // Y da igual por cuántas vueltas venga el ángulo.
        assertTrue(rotuloDelReves(360.0 + 170.0))
        assertFalse(rotuloDelReves(-360.0 + 10.0))
    }

    @Test
    fun `los grados se normalizan al medio giro`() {
        assertEquals(10.0, normalizarGrados(370.0), 1e-9)
        assertEquals(-10.0, normalizarGrados(-370.0), 1e-9)
        assertEquals(180.0, normalizarGrados(-180.0), 1e-9)
        assertEquals(0.0, normalizarGrados(Double.NaN), 1e-9)
    }

    // ---- El teclado del diálogo ----

    @Test
    fun `el teclado no deja escribir un número imposible`() {
        assertEquals("4", teclear("", "4"))
        assertEquals("4,", teclear("4", ","))
        // Una sola coma, y nunca la primera.
        assertEquals("4,2", teclear("4,2", ","))
        assertEquals("", teclear("", ","))
        // Sin ceros a la izquierda.
        assertEquals("5", teclear("0", "5"))
        assertEquals("", teclear("4", "⌫"))
        assertEquals("", teclear("", "⌫"))
    }

    // ---- El tamaño del número ----

    /**
     * En pasos proporcionales: la cota se rotula en píxeles de la escena, así
     * que el salto que se nota en un lienzo del tamaño de la pantalla no se ve
     * en una captura de cuatro mil píxeles de ancho.
     */
    @Test
    fun `el tamaño del número sube y baja en proporción`() {
        assertEquals(25.0, tamanoDeMedidaMayor(20.0), 1e-9)
        assertEquals(20.0, tamanoDeMedidaMenor(25.0), 1e-9)
        // Ida y vuelta deja lo mismo: subir y bajar no debe derivar.
        assertEquals(20.0, tamanoDeMedidaMenor(tamanoDeMedidaMayor(20.0)), 1e-9)
    }

    @Test
    fun `el tamaño del número tiene topes`() {
        assertEquals(MEDIDA_MAX, tamanoDeMedidaMayor(MEDIDA_MAX), 1e-9)
        assertEquals(MEDIDA_MIN, tamanoDeMedidaMenor(MEDIDA_MIN), 1e-9)
        assertEquals(MEDIDA_MAX, tamanoDeMedidaMayor(10_000.0), 1e-9)
        assertEquals(MEDIDA_MIN, tamanoDeMedidaMenor(0.001), 1e-9)
    }

    /**
     * El estilo llega a la cota **con su letra**. Es lo que hacía que subir el
     * tamaño del número funcionara en el editor y no en el pin: cada barra
     * traía su propia lista de campos que copiar.
     */
    @Test
    fun `el estilo lleva el tamaño de letra a la cota y no al resto`() {
        val nuevo = ItemStyle(strokeColor = "#e03131", strokeWidth = 4.0, fontSize = 64.0)

        val c = estiloAplicado(cota(0.0, 0.0, 10.0, 0.0), nuevo)
        assertEquals(64.0, c.fontSize!!, 1e-9)
        assertEquals("#e03131", c.strokeColor)
        assertEquals(4.0, c.strokeWidth, 1e-9)

        // Un rectángulo no tiene letra que cambiar; y el texto se queda igual
        // aquí porque cambiarle la fuente obliga a re-medir su caja, y eso lo
        // hace quien tiene contexto de Android.
        val r = estiloAplicado(newElement(ElementType.RECTANGLE, 0.0, 0.0, ItemStyle()), nuevo)
        assertEquals(null, r.fontSize)
        assertEquals("#e03131", r.strokeColor)
    }

    // ---- El mosaico ----

    /**
     * El grano es **fijo**, no una fracción del recuadro.
     *
     * Ha ido y ha vuelto. Con «tantos bloques en el lado mayor», agrandar el
     * recuadro para tapar un poco más engordaba los bloques y convertía en
     * cuatro manchas lo que ya estaba tapado: tapar más no puede significar
     * tapar distinto. Con el lado fijo, el mosaico se comporta igual sea cual
     * sea el recuadro, y el grosor sigue siendo el mando.
     */
    @Test
    fun `mas grosor es mas grano y no depende del recuadro`() {
        val granos = ItemStyle.STROKE_WIDTHS.map { mosaicoGrano(it) }
        assertEquals(
            "de más fino a más gordo, el grano solo puede ir a más",
            granos.sorted(), granos
        )
        assertTrue("el grano más fino tiene que tapar algo", granos.first() >= 4.0)
        assertTrue("el más gordo no puede ser una sola mancha", granos.last() <= 128.0)
    }

    /** Cualquier grosor da un grano utilizable, incluso los raros. */
    @Test
    fun `el grano nunca se va a cero`() {
        for (w in listOf(0.0, 0.1, 1.0, 2.0, 4.0, 8.0, 40.0)) {
            assertTrue("grosor $w da un grano imposible", mosaicoGrano(w) >= 1.0)
        }
    }

    /**
     * Pixelar o desenfocar se elige una vez y lo siguiente sale igual, como el
     * color. El campo estaba en el elemento desde el principio pero no había
     * forma de ponerlo: se pixelaba y punto.
     */
    @Test
    fun `el modo de tapar viaja en el estilo y llega al mosaico`() {
        val estilo = ItemStyle(mosaicBlur = true)
        assertTrue(newElement(ElementType.MOSAIC, 0.0, 0.0, estilo).mosaicBlur)
        // Y no se le pega a quien no lo usa: sería un campo suelto en el JSON.
        assertFalse(newElement(ElementType.RECTANGLE, 0.0, 0.0, estilo).mosaicBlur)
    }

    // ---- La cota dentro del motor ----

    /**
     * La cota nace con letra propia. Si no, el renderizador la rotularía con el
     * tamaño por defecto y el ajuste de fuente del panel no haría nada.
     */
    @Test
    fun `la cota nace con tamaño y familia de letra`() {
        val estilo = ItemStyle(fontSize = 28.0, fontFamily = ItemStyle.FONT_NUNITO)
        val c = newElement(ElementType.MEASURE, 0.0, 0.0, estilo)
        assertEquals(28.0, c.fontSize!!, 1e-9)
        assertEquals(ItemStyle.FONT_NUNITO, c.fontFamily)
        // Y con puntos, como cualquier lineal: sin ellos no se podría arrastrar.
        assertTrue(c.points!!.isNotEmpty())
        assertTrue(c.isLinear)
        assertTrue(c.isMeasure)
    }

    /** La escala es del dibujo entero, no de cada cota: recalibrar las corrige todas. */
    @Test
    fun `la escala viaja con la escena`() {
        val escena = Scene(escala = Escala.calibrar(100.0, 2.0))
        val ida = ExcalidrawJson.encodeToString(escena)
        val vuelta = ExcalidrawJson.decodeFromString<Scene>(ida)
        assertEquals(escena.escala, vuelta.escala)
    }

    /** Un dibujo de antes de que existiera la escala sigue abriendo. */
    @Test
    fun `una escena sin escala se lee igual`() {
        val vuelta = ExcalidrawJson.decodeFromString<Scene>("""{"elements":[]}""")
        assertNull(vuelta.escala)
    }
}
