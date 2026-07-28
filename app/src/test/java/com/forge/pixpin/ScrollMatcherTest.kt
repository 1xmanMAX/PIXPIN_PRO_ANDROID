package com.forge.pixpin.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScrollMatcherTest {

    private val tolerance = 40

    /** Una "página" con textura: cada fila tiene su propia firma. */
    private fun page(rows: Int, seed: Int = 7): IntArray {
        val rnd = Random(seed)
        return IntArray(rows) { rnd.nextInt(0, 100_000) }
    }

    @Test
    fun `sin desplazamiento el encaje es cero`() {
        val content = page(200)
        val tail = content.copyOfRange(160, 200)
        assertEquals(160, ScrollMatcher.findOffset(tail, content, tolerance))
    }

    @Test
    fun `detecta cuanto ha subido el contenido`() {
        val content = page(400)
        // El fotograma nuevo enseña la página 120 filas más abajo.
        val frame = content.copyOfRange(120, 320)
        val tail = content.copyOfRange(280, 320) // final de lo ya acumulado
        val d = ScrollMatcher.findOffset(tail, frame, tolerance)
        // Dentro del fotograma nuevo, esas filas empiezan en 280-120 = 160.
        assertEquals(160, d)
    }

    @Test
    fun `aguanta ruido de compresion`() {
        val content = page(400)
        val frame = IntArray(200) { content[120 + it] + Random(1).nextInt(-8, 9) }
        val tail = content.copyOfRange(280, 320)
        assertEquals(160, ScrollMatcher.findOffset(tail, frame, tolerance))
    }

    @Test
    fun `una banda lisa se rechaza en vez de encajar en cualquier sitio`() {
        val flat = IntArray(40) { 5_000 }
        assertTrue(ScrollMatcher.isFlat(flat, minVariation = 100))
        // Y con textura, no.
        assertFalse(ScrollMatcher.isFlat(page(40), minVariation = 100))
    }

    @Test
    fun `un patron que se repite se considera ambiguo`() {
        // Cabecera repetida cada 20 filas: hay varios encajes igual de buenos.
        val repeating = IntArray(200) { (it % 20) * 1000 }
        val tail = IntArray(40) { (it % 20) * 1000 }
        assertEquals(ScrollMatcher.NO_MATCH, ScrollMatcher.findOffset(tail, repeating, tolerance))
    }

    @Test
    fun `contenido totalmente distinto no encaja`() {
        val tail = page(40, seed = 1)
        val frame = page(200, seed = 99)
        assertEquals(ScrollMatcher.NO_MATCH, ScrollMatcher.findOffset(tail, frame, tolerance))
    }

    @Test
    fun `un fotograma mas corto que la referencia no encaja`() {
        assertEquals(
            ScrollMatcher.NO_MATCH,
            ScrollMatcher.findOffset(page(40), page(20), tolerance)
        )
    }

    @Test
    fun `la firma de una fila resume su contenido`() {
        val width = 4
        val negro = IntArray(width) { 0xFF000000.toInt() }
        val blanco = IntArray(width) { 0xFFFFFFFF.toInt() }
        assertEquals(0, ScrollMatcher.rowSignature(negro, 0, width, 1))
        assertTrue(ScrollMatcher.rowSignature(blanco, 0, width, 1) > 900)
    }

    @Test
    fun `las firmas se calculan fila a fila`() {
        val width = 2
        val pixels = intArrayOf(
            0xFF000000.toInt(), 0xFF000000.toInt(), // fila 0: negra
            0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()  // fila 1: blanca
        )
        val sigs = ScrollMatcher.signatures(pixels, width, height = 2, step = 1)
        assertEquals(2, sigs.size)
        assertEquals(0, sigs[0])
        assertTrue(sigs[1] > sigs[0])
    }
}
