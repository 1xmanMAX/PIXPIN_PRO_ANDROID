package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El texto **cabe en su caja**, que es lo único que se le pide a una caja.
 *
 * Antes solo había tope por abajo: se partía por los saltos escritos y nada más, así que
 * estrechar la caja dejaba el texto saliéndose por la derecha por encima de lo que hubiera
 * al lado. Aquí se comprueba dónde se corta, con una medida de mentira —una letra, un
 * punto— para que el resultado no dependa de la fuente del móvil.
 */
class RenglonesTest {

    /** Cada letra mide uno: así el ancho es el número de letras y se lee la prueba. */
    private val unaPorLetra: (String) -> Double = { it.length.toDouble() }

    @Test
    fun `lo que cabe no se parte`() {
        assertEquals(listOf("hola"), renglonesQueCaben("hola", 10.0, unaPorLetra))
    }

    /** Se corta por el espacio, no a mitad de palabra. */
    @Test
    fun `se parte por las palabras`() {
        val r = renglonesQueCaben("uno dos tres", 8.0, unaPorLetra)
        assertEquals(listOf("uno dos ", "tres"), r)
    }

    /** Los saltos escritos son del autor: se respetan siempre. */
    @Test
    fun `los saltos escritos se respetan`() {
        val r = renglonesQueCaben("a\nb", 100.0, unaPorLetra)
        assertEquals(listOf("a", "b"), r)
    }

    /**
     * Una palabra más larga que la caja **se parte por letras**.
     *
     * Dejarla entera sería volver al problema de siempre, y justo en el caso que más se
     * nota: una dirección web, una fórmula, un número largo.
     */
    @Test
    fun `una palabra larguisima se parte`() {
        val r = renglonesQueCaben("abcdefghij", 4.0, unaPorLetra)
        assertEquals(listOf("abcd", "efgh", "ij"), r)
        assertTrue(r.all { it.length <= 4 })
    }

    /** Al juntarlos otra vez sale el texto original: no se come ningún espacio. */
    @Test
    fun `los renglones rearman el texto`() {
        val texto = "uno dos tres cuatro cinco"
        val r = renglonesQueCaben(texto, 9.0, unaPorLetra)
        assertEquals(texto, r.joinToString(""))
    }

    /** Sin ancho no se parte nada: es lo que pasa mientras se traza la caja. */
    @Test
    fun `sin ancho no se parte`() {
        assertEquals(listOf("uno dos tres"), renglonesQueCaben("uno dos tres", 0.0, unaPorLetra))
    }

    /** El vacío sigue siendo un renglón: si no, la caja mediría cero de alto. */
    @Test
    fun `el texto vacio es un renglon`() {
        assertEquals(listOf(""), renglonesQueCaben("", 10.0, unaPorLetra))
        assertEquals(20.0 * INTERLINEADO, altoDeRenglones(0, 20.0), 0.001)
    }

    /** El alto sale de la misma cuenta que usa el pintado. */
    @Test
    fun `el alto es el interlineado por renglones`() {
        assertEquals(3 * 16.0 * INTERLINEADO, altoDeRenglones(3, 16.0), 0.001)
    }
}
