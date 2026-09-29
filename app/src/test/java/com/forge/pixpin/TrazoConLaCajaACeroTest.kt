package com.forge.pixpin.motor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Apunta si se pidió el trazo entero (un camino) o la raya de lo diminuto. */
private class LienzoQueApunta(b: Bitmap) : Canvas(b) {
    var caminos = 0
    var rayas = 0
    override fun drawPath(path: Path, paint: Paint) { caminos++ }
    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) { rayas++ }
}

/**
 * **Un trazo de PixPin para Windows llega con la caja a cero** (29-sep-2026).
 *
 * Windows saca la caja de los puntos y escribía `width`/`height` a cero. El pintor, fiándose de
 * la caja, lo tomaba por diminuto y lo pintaba como la raya de la primera punta a la última: en
 * el lector salían rectas y en el HTML exportado, los trazos enteros.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrazoConLaCajaACeroTest {

    private fun pinta(e: Element): LienzoQueApunta {
        val lienzo = LienzoQueApunta(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888))
        Renderer().renderScene(lienzo, Scene(elements = listOf(e)), 400.0, 300.0)
        return lienzo
    }

    private val ese = Element(
        id = "pc1", type = ElementType.FREEDRAW,
        x = 50.0, y = 100.0, width = 0.0, height = 0.0,
        seed = 1, strokeWidth = 2.0,
        points = (0..40).map { i ->
            val t = i / 40.0
            Pt(10.0 + 200 * t, 60 * kotlin.math.sin(t * 2 * Math.PI))
        }
    )

    @Test
    fun `con la caja a cero se pinta el trazo entero y no una raya`() {
        val l = pinta(ese)
        assertEquals("rayas", 0, l.rayas)
        assertEquals("caminos", 1, l.caminos)
    }

    @Test
    fun `lo diminuto de verdad sigue siendo una raya`() {
        val l = pinta(ese.copy(points = listOf(Pt(0.0, 0.0), Pt(0.2, 0.1), Pt(0.3, 0.3))))
        assertEquals("rayas", 1, l.rayas)
        assertEquals("caminos", 0, l.caminos)
    }
}
