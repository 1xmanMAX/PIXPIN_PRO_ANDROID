package com.forge.pixpin.motor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.PI

/**
 * **Las láminas, pintadas**: el lienzo, el SVG (que es también el HTML) y el PDF. Aquí no se
 * rasteriza (ver `pixpin-pruebas-de-pintado`), así que se cuentan las órdenes al `Canvas` y se
 * mira qué se escribe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaminasPintadoTest {

    /** Cuenta caminos, textos y con qué pincel se pidieron. */
    private class Lienzo(b: Bitmap) : Canvas(b) {
        var caminos = 0
        var textos = mutableListOf<String>()
        var conShader = 0
        var guiones = 0
        override fun drawPath(path: Path, paint: Paint) {
            caminos++
            if (paint.shader != null) conShader++
            if (paint.pathEffect is DashPathEffect) guiones++
        }
        override fun drawText(t: String, x: Float, y: Float, p: Paint) { textos += t }
        override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) {}
        override fun drawCircle(cx: Float, cy: Float, radio: Float, paint: Paint) {}
    }

    private fun pinta(vararg e: Element): Lienzo {
        val lienzo = Lienzo(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888))
        Renderer().renderScene(lienzo, Scene(elements = e.toList()), 400.0, 300.0)
        return lienzo
    }

    private val s = ItemStyle(backgroundColor = "#ffc078", strokeColor = "#1e1e1e")

    private fun sector() = conCierre(arcoExacto(Pt(150.0, 150.0), 80.0, -PI / 2, PI / 2, s), CierreArco.SECTOR)

    private fun marca(valor: String?) =
        marcaDeAngulo(Pt(100.0, 150.0), Pt(250.0, 150.0), Pt(200.0, 50.0), 40.0, MarcaAngulo(arcos = 2, valor = valor), s)

    @Test
    fun `el sector se rellena y se repasa`() {
        val l = pinta(sector())
        // El relleno y el borde: dos caminos.
        assertEquals(2, l.caminos)
    }

    @Test
    fun `el sector con degradado pinta con shader`() {
        val e = conDegradado(sector(), Degradado.desdeRelleno(TipoDegradado.RADIAL, "#ffc078"))
        val l = pinta(e)
        assertEquals(1, l.conShader)
    }

    @Test
    fun `la marca pinta relleno, arquitos y su letra`() {
        val l = pinta(marca("α"))
        assertEquals(listOf("α"), l.textos)
        // El relleno y un camino con los dos arquitos.
        assertEquals(2, l.caminos)
        assertTrue(pinta(marca(null)).textos.isEmpty())
    }

    @Test
    fun `rectangulo, rombo, ovalo y region con degradado llevan shader`() {
        for (tipo in listOf(ElementType.RECTANGLE, ElementType.DIAMOND, ElementType.ELLIPSE)) {
            val e = newElement(tipo, 50.0, 50.0, s.copy(degradado = TipoDegradado.LINEAL), 200.0, 100.0)
            assertEquals("$tipo sin degradado", 1, pinta(e).conShader)
        }
        val region = newElement(ElementType.REGION, 50.0, 50.0, s.copy(degradado = TipoDegradado.RADIAL), 100.0, 100.0)
            .copy(points = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0)))
        assertEquals(1, pinta(region).conShader)
    }

    @Test
    fun `el raya-punto lleva guiones`() {
        val r = newElement(ElementType.LINE, 10.0, 10.0, s.copy(strokeStyle = StrokeStyle.DASHED, rayaPunto = true))
            .copy(points = listOf(Pt(0.0, 0.0), Pt(200.0, 100.0)), width = 200.0, height = 100.0)
        assertTrue(pinta(r).guiones > 0)
    }

    @Test
    fun `el svg y el html llevan el degradado, el raya-punto y la marca`() {
        val ctx = RuntimeEnvironment.getApplication()
        val deg = conDegradado(sector(), Degradado.desdeRelleno(TipoDegradado.LINEAL, "#ffc078"))
        val rect = newElement(ElementType.RECTANGLE, 220.0, 20.0, s.copy(degradado = TipoDegradado.RADIAL), 80.0, 60.0)
        val raya = conRayaPunto(
            newElement(ElementType.LINE, 10.0, 10.0, s).copy(points = listOf(Pt(0.0, 0.0), Pt(200.0, 100.0)), width = 200.0, height = 100.0),
            true
        )
        val svg = DrawSvg.aTexto(ctx, Scene(elements = listOf(deg, rect, raya, marca("β"))), { null })!!
        assertTrue("sin degradado lineal", svg.contains("<linearGradient"))
        assertTrue("sin degradado radial", svg.contains("<radialGradient"))
        val guiones = intervalosRayaPunto(raya.strokeWidth).joinToString(" ") { Svg.num(it) }
        assertTrue("sin raya-punto", svg.contains("stroke-dasharray=\"$guiones\""))
        assertTrue(!svg.contains("NaN"))
    }

    @Test
    fun `el pdf escribe las bandas del degradado recortadas a la figura`() {
        val ctx = RuntimeEnvironment.getApplication()
        val deg = conDegradado(sector(), Degradado.desdeRelleno(TipoDegradado.RADIAL, "#ffc078"))
        val salida = PdfLienzo.deEscena(ctx, Scene(elements = listOf(deg)), doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, 842.0), 20)!!
        val ordenes = String(salida.contenido, Charsets.ISO_8859_1)
        assertTrue("sin recorte", ordenes.contains("W n"))
        // Las bandas: muchas manchas lisas.
        assertTrue(ordenes.split("\nf\n").size > BANDAS_DEGRADADO)
        val raya = conRayaPunto(
            newElement(ElementType.LINE, 10.0, 10.0, s).copy(points = listOf(Pt(0.0, 0.0), Pt(200.0, 100.0)), width = 200.0, height = 100.0),
            true
        )
        val conRaya = String(
            PdfLienzo.deEscena(ctx, Scene(elements = listOf(raya)), doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, 842.0), 20)!!.contenido,
            Charsets.ISO_8859_1
        )
        // El patrón de cuatro tramos.
        assertTrue(Regex("\\[[0-9. ]+ [0-9. ]+ [0-9. ]+ [0-9. ]+\\] 0 d").containsMatchIn(conRaya))
    }
}
