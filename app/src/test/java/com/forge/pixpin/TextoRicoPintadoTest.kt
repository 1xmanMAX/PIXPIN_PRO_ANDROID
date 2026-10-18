package com.forge.pixpin.motor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **El texto rico llega pintado** a la pantalla, al SVG (y con él a la página web) y al PDF.
 *
 * Robolectric aquí no rasteriza: se cuentan las órdenes que el renderizador le pide al lienzo
 * (qué textos y de qué color, cuántos caminos), como en [RendererTodoLoQuePintaTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextoRicoPintadoTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** Un lienzo que apunta lo que se le pide escribir, con su color, y los caminos. */
    private class LienzoQueApunta(b: Bitmap) : Canvas(b) {
        val textos = mutableListOf<Pair<String, Int>>()
        var caminos = 0
        override fun drawText(t: String, x: Float, y: Float, p: Paint) { textos += t to p.color }
        override fun drawPath(path: Path, paint: Paint) { caminos++ }
    }

    private fun texto(fuente: String?, plano: String) = newElement(
        ElementType.TEXT, 20.0, 20.0, ItemStyle(fontSize = 20.0, strokeColor = "#1e1e1e")
    ).copy(id = "t", text = plano, textoRico = fuente, width = 300.0, height = 40.0)

    private fun pinta(e: Element): LienzoQueApunta {
        val lienzo = LienzoQueApunta(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888))
        Renderer().renderScene(lienzo, Scene(elements = listOf(e)), 400.0, 200.0)
        return lienzo
    }

    @Test
    fun `la X sale de su color y ninguna barra a la vista`() {
        val fuente = "Nos piden \$m\\sphericalangle COD = \\color{red}{X}\$"
        val l = pinta(texto(fuente, TextoRico.plano(fuente)))
        val x = l.textos.first { it.first == "X" }
        val resto = l.textos.first { it.first.startsWith("Nos") }
        assertNotEquals("la X no es del color del resto", resto.second, x.second)
        assertFalse(l.textos.any { '\\' in it.first || '$' in it.first })
        assertTrue("el signo del ángulo, dibujado", l.caminos >= 2)
    }

    @Test
    fun `un texto sin fuente se pinta como siempre`() {
        val l = pinta(texto(null, "Nos piden \$x^2\$"))
        assertEquals(listOf("Nos piden \$x^2\$"), l.textos.map { it.first })
        assertEquals(0, l.caminos)
    }

    @Test
    fun `con el texto cambiado por otro la fuente vieja no se pinta`() {
        val l = pinta(texto("**viejo**", "nuevo"))
        assertEquals(listOf("nuevo"), l.textos.map { it.first })
    }

    @Test
    fun `mientras se escribe no se pinta compuesto debajo`() {
        val fuente = "\$x^2\$"
        val lienzo = LienzoQueApunta(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888))
        val r = Renderer()
        r.escondido = "t"
        r.renderScene(lienzo, Scene(elements = listOf(texto(fuente, TextoRico.plano(fuente)))), 400.0, 200.0)
        assertTrue(lienzo.textos.isEmpty())
    }

    @Test
    fun `el SVG y el PDF sacan lo compuesto`() {
        val fuente = "Si \$\\color{red}{\\overline{AB}}\$ y X"
        val escena = Scene(elements = listOf(texto(fuente, TextoRico.plano(fuente))))
        val svg = DrawSvg.aTexto(context, escena).orEmpty()
        assertTrue("la raya roja, como polilínea", svg.contains("<polyline") && svg.contains("#e03131"))
        assertFalse("sin la fuente a la vista", svg.contains("overline"))
        val pdf = PdfLienzo.deEscena(
            context, escena, matriz = doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, 842.0), primerObjeto = 20
        )
        val contenido = String(pdf?.contenido ?: ByteArray(0), Charsets.ISO_8859_1)
        assertTrue("la raya llega al PDF como trazo", contenido.contains("\nS\n") && contenido.contains("1 J 1 j"))
    }
}
