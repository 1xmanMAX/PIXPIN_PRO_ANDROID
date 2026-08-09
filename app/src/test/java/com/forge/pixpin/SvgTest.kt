package com.forge.pixpin.motor

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La sintaxis del SVG.
 *
 * Es la mitad del exportador que **no** necesita Android, y es la mitad donde
 * están los fallos que no se ven mirando: un punto decimal que sale coma, un
 * `Z` que falta, una comilla dentro de un título. Todo eso da un archivo que
 * abre mal en el ordenador de otro y bien en el móvil de uno.
 */
class SvgTest {

    private val idioma = Locale.getDefault()

    @After
    fun devolverElIdioma() {
        Locale.setDefault(idioma)
    }

    // ---- Los números ----

    @Test
    fun `los enteros se escriben sin decimales`() {
        assertEquals("3", Svg.num(3.0))
        assertEquals("-12", Svg.num(-12.0))
        assertEquals("0", Svg.num(0.0))
    }

    @Test
    fun `se redondea a dos decimales y sin ceros de relleno`() {
        assertEquals("3.14", Svg.num(3.14159))
        assertEquals("3.1", Svg.num(3.1))
        assertEquals("0.01", Svg.num(0.0051))
    }

    /**
     * **El fallo que solo se ve en el móvil de otro.**
     *
     * Con el idioma en español, `"%.2f"` sin locale escribe `3,14`: el visor lee
     * dos números donde había uno y el camino sale reventado. Aquí se pone el
     * idioma a español a propósito, que es la única forma de que la prueba
     * pueda fallar si alguien quita el `Locale.ROOT`.
     */
    @Test
    fun `el decimal es un punto aunque el móvil esté en español`() {
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        assertEquals("3.14", Svg.num(3.14159))
        assertFalse(Svg.num(2.5).contains(','))
    }

    @Test
    fun `un número imposible no rompe el archivo`() {
        assertEquals("0", Svg.num(Double.NaN))
        assertEquals("0", Svg.num(Double.POSITIVE_INFINITY))
    }

    // ---- Los caminos ----

    @Test
    fun `las órdenes del generador salen como M L y C`() {
        val d = Svg.camino(
            listOf(
                Op.Move(0.0, 0.0),
                Op.LineTo(10.0, 0.0),
                Op.CurveTo(12.0, 2.0, 14.0, 8.0, 10.0, 10.0)
            )
        )
        assertEquals("M0 0L10 0C12 2 14 8 10 10", d)
    }

    @Test
    fun `una polilínea cerrada acaba en Z`() {
        val d = Svg.caminoCerrado(listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 4.0)))
        assertEquals("M0 0L4 0L4 4Z", d)
    }

    /**
     * El contorno del lápiz se cose con cuadráticas por los puntos medios, que
     * es lo que hace el renderizador en pantalla. Si esta fórmula y la de allí
     * se separan, el lápiz exportado deja de parecerse al de la pantalla.
     */
    @Test
    fun `el contorno del lápiz se suaviza con cuadráticas`() {
        val pts = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(20.0, 10.0), Pt(0.0, 10.0))
        val d = Svg.caminoSuaveCerrado(pts)
        assertTrue("no empieza donde el trazo: $d", d.startsWith("M0 0"))
        assertTrue("no cose con cuadráticas: $d", d.contains("Q"))
        assertTrue("no cierra: $d", d.endsWith("Z"))
        // El primer tirador es el segundo punto y el destino, el medio del 2.º y el 3.º.
        assertTrue("el primer tramo no va por el punto medio: $d", d.contains("Q10 0 15 5"))
    }

    @Test
    fun `un trazo de dos puntos no se suaviza, se cierra a rectas`() {
        val d = Svg.caminoSuaveCerrado(listOf(Pt(0.0, 0.0), Pt(5.0, 5.0)))
        assertEquals("M0 0L5 5Z", d)
        assertEquals("", Svg.caminoSuaveCerrado(emptyList()))
    }

    /**
     * Los agujeros de un relleno van como anillos del mismo camino: es lo que
     * `fill-rule="evenodd"` convierte en agujeros de verdad. Como caminos
     * separados serían manchas, y el hueco quedaría tapado.
     */
    @Test
    fun `los anillos de una región van en un solo camino`() {
        val fuera = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))
        val dentro = listOf(Pt(3.0, 3.0), Pt(7.0, 3.0), Pt(7.0, 7.0), Pt(3.0, 7.0))
        val d = Svg.caminoDeAnillos(listOf(fuera, dentro))
        assertEquals(2, d.count { it == 'Z' })
        assertEquals(2, d.count { it == 'M' })
    }

    @Test
    fun `un anillo degenerado se descarta en vez de escribirse mal`() {
        val d = Svg.caminoDeAnillos(listOf(listOf(Pt(0.0, 0.0), Pt(1.0, 1.0))))
        assertEquals("", d)
    }

    // ---- Colores y guiones ----

    @Test
    fun `el color sale en hexadecimal y la transparencia aparte`() {
        assertEquals("#1971c2", Svg.hex(0xFF1971C2.toInt()))
        assertEquals(1.0, Svg.alfa(0xFF000000.toInt()), 1e-9)
        assertEquals(0.5, Svg.alfa(0x80000000.toInt()), 0.01)
    }

    @Test
    fun `un trazo continuo no lleva guiones`() {
        assertEquals(null, Svg.guionesDe(StrokeStyle.SOLID, 2.0))
        assertEquals("8 10", Svg.guionesDe(StrokeStyle.DASHED, 2.0))
        assertEquals("1.5 8", Svg.guionesDe(StrokeStyle.DOTTED, 2.0))
    }

    // ---- El documento ----

    @Test
    fun `el viewBox lleva las coordenadas de la escena tal cual`() {
        val doc = Svg.documento(Bounds(-40.0, 10.0, 60.0, 60.0), null, "")
        assertTrue(doc, doc.contains("viewBox=\"-40 10 100 50\""))
        assertTrue(doc, doc.contains("width=\"100\""))
        assertTrue(doc, doc.contains("height=\"50\""))
        assertTrue("no declara el espacio de nombres", doc.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(doc.trimEnd().endsWith("</svg>"))
    }

    @Test
    fun `el fondo se puede quitar`() {
        assertFalse(Svg.documento(Bounds(0.0, 0.0, 10.0, 10.0), null, "").contains("<rect"))
        assertTrue(
            Svg.documento(Bounds(0.0, 0.0, 10.0, 10.0), "#ffffff", "").contains("fill=\"#ffffff\"")
        )
    }

    /**
     * Un dibujo llamado `<script>` no puede salir del atributo.
     *
     * No es paranoia de seguridad: es que un nombre con un `&` o unas comillas
     * —que los pone cualquiera— rompe el XML y el archivo deja de abrirse.
     */
    @Test
    fun `lo que va escrito se escapa`() {
        val doc = Svg.documento(Bounds(0.0, 0.0, 1.0, 1.0), null, "", "a<b>&\"c\"")
        assertTrue(doc, doc.contains("<title>a&lt;b&gt;&amp;&quot;c&quot;</title>"))
        assertFalse("se ha colado una etiqueta", doc.contains("<b>"))
    }
}
