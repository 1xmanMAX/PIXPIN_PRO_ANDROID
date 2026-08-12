package com.forge.pixpin.mini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La ruleta: quién entra, a quién le toca y dónde para la rueda. */
class RuletaTest {

    @Test
    fun `los nombres salen limpios y sin renglones vacios`() {
        val r = Ruleta.nombres("  Ana \n\n Beto\n\n  \nCarla  ")
        assertEquals(listOf("Ana", "Beto", "Carla"), r)
    }

    /** Poner a alguien dos veces es querer que tenga el doble de opciones. */
    @Test
    fun `los repetidos se quedan`() {
        assertEquals(listOf("Ana", "Ana", "Beto"), Ruleta.nombres("Ana\nAna\nBeto"))
    }

    @Test
    fun `con menos de dos no hay sorteo`() {
        assertFalse(Ruleta.sePuedeGirar(null))
        assertFalse(Ruleta.sePuedeGirar(""))
        assertFalse(Ruleta.sePuedeGirar("Ana"))
        assertTrue(Ruleta.sePuedeGirar("Ana\nBeto"))
    }

    @Test
    fun `elegir devuelve un indice de la lista`() {
        val nombres = listOf("a", "b", "c", "d")
        assertEquals(0, Ruleta.elegir(nombres) { 0.0 })
        assertEquals(1, Ruleta.elegir(nombres) { 0.26 })
        assertEquals(3, Ruleta.elegir(nombres) { 0.99 })
    }

    /** El azar puede devolver justo 1.0; el índice no puede salirse por eso. */
    @Test
    fun `el azar en el borde no se sale de la lista`() {
        val nombres = listOf("a", "b", "c")
        assertEquals(2, Ruleta.elegir(nombres) { 1.0 })
        assertEquals(0, Ruleta.elegir(nombres) { -0.5 })
        assertEquals(-1, Ruleta.elegir(emptyList()) { 0.5 })
    }

    @Test
    fun `todos pueden salir elegidos`() {
        val nombres = listOf("a", "b", "c", "d", "e")
        val salieron = (0 until 500)
            .map { Ruleta.elegir(nombres) { it / 500.0 } }
            .toSet()
        assertEquals(nombres.indices.toSet(), salieron)
    }

    // ---- La rueda ----

    @Test
    fun `las porciones reparten la vuelta entera`() {
        assertEquals(90f, Ruleta.anguloDePorcion(4), 0.001f)
        assertEquals(360f, Ruleta.anguloDePorcion(1), 0.001f)
        assertEquals(360f, Ruleta.anguloDePorcion(0), 0.001f)
    }

    /**
     * Lo que de verdad importa de la rueda: que al parar, la porción del
     * elegido quede **debajo de la aguja**, que está arriba del todo.
     */
    @Test
    fun `la rueda para con el elegido bajo la aguja`() {
        val cuantos = 6
        val porcion = Ruleta.anguloDePorcion(cuantos)
        for (elegido in 0 until cuantos) {
            val giro = Ruleta.anguloFinal(elegido, cuantos)
            // Dónde acaba el centro de esa porción tras girar la rueda.
            val centro = (porcion * elegido + porcion / 2f + giro).mod(360f)
            assertEquals("elegido $elegido", 0f, centro, 0.001f)
        }
    }

    @Test
    fun `la rueda da vueltas antes de parar`() {
        val giro = Ruleta.anguloFinal(0, 4, vueltas = 3)
        assertTrue("apenas se movio: $giro", giro > 2 * 360f)
    }

    // ---- Quitar al que salió ----

    @Test
    fun `quitar deja a los demas en orden`() {
        assertEquals("Ana\nCarla", Ruleta.sinElNombre("Ana\nBeto\nCarla", 1))
        assertEquals("Beto\nCarla", Ruleta.sinElNombre("Ana\nBeto\nCarla", 0))
    }

    @Test
    fun `quitar limpia de paso los renglones sueltos`() {
        assertEquals("Ana\nCarla", Ruleta.sinElNombre("  Ana \n\nBeto\nCarla ", 1))
    }

    @Test
    fun `un indice imposible no quita nada`() {
        val texto = "Ana\nBeto"
        assertEquals(texto, Ruleta.sinElNombre(texto, 9))
        assertEquals(texto, Ruleta.sinElNombre(texto, -1))
    }

    @Test
    fun `quitando uno a uno se acaba la lista`() {
        var texto = "a\nb\nc"
        repeat(3) { texto = Ruleta.sinElNombre(texto, 0) }
        assertTrue(Ruleta.nombres(texto).isEmpty())
    }
}
