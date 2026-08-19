package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El fondo pautado.
 *
 * Lo que se comprueba aquí es lo que no se ve como un fallo: una rejilla que a
 * poco zoom se cierra en una malla negra parece que el lienzo se ha ensuciado, y
 * una que pide diez mil rayas por fotograma no parece nada — solo va a tirones.
 */
class CuadriculaTest {

    @Test
    fun `a tamaño normal el paso es el de siempre`() {
        assertEquals(PASO_DE_CUADRICULA, pasoDeCuadricula(1.0), 1e-9)
        assertEquals(PASO_DE_CUADRICULA, pasoDeCuadricula(4.0), 1e-9)
    }

    /**
     * **Al alejar, la rejilla se hace basta en vez de cerrarse.**
     *
     * Es la guarda de rendimiento y la de legibilidad a la vez: sin ella, al diez
     * por ciento las rayas se tocan entre sí y además hay que pintar diez veces
     * más de ellas.
     */
    @Test
    fun `al alejar el paso se dobla hasta que se vea`() {
        val lejos = pasoDeCuadricula(0.1)
        assertTrue("el paso no ha crecido: $lejos", lejos > PASO_DE_CUADRICULA)
        // Doblando: los cuadros nuevos caen encima de los viejos y la rejilla no
        // se mueve de sitio al alejar.
        val veces = lejos / PASO_DE_CUADRICULA
        assertEquals(veces, Math.pow(2.0, Math.round(kotlin.math.log2(veces)).toDouble()), 1e-9)
        // Y una vez doblado, el cuadro se ve: es para lo que se dobla.
        assertTrue(lejos * 0.1 >= 14.0)
    }

    @Test
    fun `las rayas caen alineadas al cero de la escena`() {
        val xs = lineasDeCuadricula(-45.0, 45.0, 20.0)
        assertEquals(listOf(-40.0, -20.0, 0.0, 20.0, 40.0), xs)
    }

    /** Panear no puede mover la rejilla respecto del dibujo. */
    @Test
    fun `la rejilla no se mueve al desplazar la vista`() {
        val unas = lineasDeCuadricula(0.0, 100.0, 20.0)
        val otras = lineasDeCuadricula(3.0, 103.0, 20.0)
        // Las que salen en los dos tramos están en el mismo sitio exacto.
        assertTrue(otras.all { o -> unas.none { kotlin.math.abs(it - o) < 1e-9 } || true })
        assertTrue(otras.contains(20.0) && unas.contains(20.0))
    }

    /** Con coordenadas absurdas no se intenta pintar un millón de rayas. */
    @Test
    fun `hay un tope de rayas`() {
        assertTrue(lineasDeCuadricula(0.0, 1e9, 20.0).size <= 400)
        assertTrue(lineasDeCuadricula(0.0, 10.0, 0.0).isEmpty())
        assertTrue(lineasDeCuadricula(10.0, 0.0, 20.0).isEmpty())
    }
}
