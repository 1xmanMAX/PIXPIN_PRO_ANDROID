package com.forge.pixpin.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentClassifierTest {

    @Test
    fun `hex de 6 digitos`() {
        assertEquals(0xFF29B8DB.toInt(), ContentClassifier.parseColor("#29B8DB"))
    }

    @Test
    fun `hex de 3 digitos se expande`() {
        assertEquals(0xFF2288BB.toInt(), ContentClassifier.parseColor("#28B"))
    }

    @Test
    fun `hex de 8 digitos con alpha`() {
        assertEquals(0x8029B8DB.toInt(), ContentClassifier.parseColor("#8029B8DB"))
    }

    @Test
    fun `funcion rgb`() {
        assertEquals(0xFF29B8DB.toInt(), ContentClassifier.parseColor("rgb(41,184,219)"))
        assertEquals(0xFF29B8DB.toInt(), ContentClassifier.parseColor("rgb(41, 184, 219)"))
    }

    @Test
    fun `funcion rgba con alpha decimal`() {
        assertEquals(0x7F112233.toInt(), ContentClassifier.parseColor("rgba(17, 34, 51, 0.5)"))
    }

    @Test
    fun `triples sueltos`() {
        assertEquals(0xFF29B8DB.toInt(), ContentClassifier.parseColor("41, 184, 219"))
    }

    @Test
    fun `nombres css`() {
        assertEquals(0xFFFF9800.toInt(), ContentClassifier.parseColor("orange"))
        assertEquals(0xFF2196F3.toInt(), ContentClassifier.parseColor("BLUE"))
    }

    @Test
    fun `no es color`() {
        assertNull(ContentClassifier.parseColor("hola mundo"))
        assertNull(ContentClassifier.parseColor("#12345"))
        assertNull(ContentClassifier.parseColor("rgb(300, 0, 0)"))
        assertNull(ContentClassifier.parseColor("1, 2, 300"))
        assertNull(ContentClassifier.parseColor(""))
    }

    @Test
    fun `classify devuelve el tipo correcto`() {
        assertTrue(ContentClassifier.classify("#29B8DB") is PinContent.ColorPin)
        assertTrue(ContentClassifier.classify("texto cualquiera") is PinContent.TextPin)
        assertTrue(ContentClassifier.classify("   ") is PinContent.Empty)
        assertTrue(ContentClassifier.classify(null) is PinContent.Empty)
    }

    @Test
    fun `formatos de salida`() {
        assertEquals("#29B8DB", ContentClassifier.toHex(0xFF29B8DB.toInt()))
        assertEquals("rgb(41, 184, 219)", ContentClassifier.toRgb(0xFF29B8DB.toInt()))
    }
}
