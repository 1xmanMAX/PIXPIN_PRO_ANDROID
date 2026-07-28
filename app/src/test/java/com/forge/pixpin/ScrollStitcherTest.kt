package com.forge.pixpin.capture

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * El cosido sobre bitmaps de verdad: hace falta Robolectric en modo gráfico
 * nativo porque aquí se leen y se copian píxeles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollStitcherTest {

    private val width = 60

    /**
     * Página larga con textura. Deliberadamente "ruidosa" y no un degradado:
     * un degradado desplazado se parece a sí mismo en cualquier posición, y el
     * cosido lo rechaza a propósito por ambiguo. El contenido real de una
     * pantalla —texto, iconos— tiene esta pinta, no la de una rampa.
     */
    private fun page(rows: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, rows, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * rows)
        for (y in 0 until rows) {
            val g = Random(y).nextInt(256)
            val color = 0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
            for (x in 0 until width) pixels[y * width + x] = color
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, rows)
        return bmp
    }

    private fun window(source: Bitmap, top: Int, rows: Int): Bitmap =
        Bitmap.createBitmap(source, 0, top, width, rows)

    @Test
    fun `el primer fotograma es la base`() {
        val stitcher = ScrollStitcher(width, maxHeight = 2000)
        val result = stitcher.addFrame(page(200))
        assertEquals(ScrollStitcher.Result.FIRST, result)
        assertEquals(200, stitcher.height)
    }

    @Test
    fun `solo se añade lo que hay de nuevo`() {
        val source = page(600)
        val stitcher = ScrollStitcher(width, maxHeight = 2000)

        stitcher.addFrame(window(source, 0, 200))
        val result = stitcher.addFrame(window(source, 100, 200))

        assertEquals(ScrollStitcher.Result.APPENDED, result)
        assertEquals("200 filas + las 100 nuevas", 300, stitcher.height)
    }

    @Test
    fun `la imagen final reproduce la pagina original`() {
        val source = page(600)
        val stitcher = ScrollStitcher(width, maxHeight = 2000)
        stitcher.addFrame(window(source, 0, 200))
        stitcher.addFrame(window(source, 100, 200))
        stitcher.addFrame(window(source, 200, 200))

        val out = stitcher.build()
        assertNotNull(out)
        assertEquals(400, out!!.height)
        // Se comprueba una fila de cada tramo cosido.
        listOf(10, 150, 250, 399).forEach { y ->
            assertEquals("fila $y", source.getPixel(0, y), out.getPixel(0, y))
        }
    }

    @Test
    fun `si la pantalla no se movio no se añade nada`() {
        val source = page(600)
        val stitcher = ScrollStitcher(width, maxHeight = 2000)
        stitcher.addFrame(window(source, 0, 200))
        val result = stitcher.addFrame(window(source, 0, 200))
        assertEquals(ScrollStitcher.Result.NO_MOVEMENT, result)
        assertEquals(200, stitcher.height)
    }

    @Test
    fun `un fotograma que no encaja se descarta`() {
        val stitcher = ScrollStitcher(width, maxHeight = 2000)
        stitcher.addFrame(page(200))
        // Contenido sin ninguna relación: el usuario cambió de app.
        val otro = Bitmap.createBitmap(width, 200, Bitmap.Config.ARGB_8888)
        otro.eraseColor(0xFF3366CC.toInt())
        assertEquals(ScrollStitcher.Result.UNCERTAIN, stitcher.addFrame(otro))
        assertEquals("no se ha ensuciado lo acumulado", 200, stitcher.height)
    }

    @Test
    fun `al llegar al alto maximo deja de acumular`() {
        val source = page(600)
        val stitcher = ScrollStitcher(width, maxHeight = 250)
        stitcher.addFrame(window(source, 0, 200))
        val result = stitcher.addFrame(window(source, 100, 200))
        assertEquals(ScrollStitcher.Result.FULL, result)
        assertEquals("lo ya cosido se conserva", 200, stitcher.height)
    }

    @Test
    fun `un fotograma mas bajo que la referencia se descarta`() {
        val stitcher = ScrollStitcher(width, maxHeight = 2000)
        stitcher.addFrame(page(200))
        assertEquals(ScrollStitcher.Result.UNCERTAIN, stitcher.addFrame(page(20)))
    }
}
