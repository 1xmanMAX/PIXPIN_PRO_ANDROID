package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las cuentas de los controles que se manejan arrastrando.
 *
 * Lo que se vigila es lo de siempre en un deslizador: que **no se salga nunca**
 * del rango, que el mismo sitio del dedo dé siempre el mismo valor, y que ir y
 * volver no cambie nada. Un control que se pasa de rango no falla con un error:
 * falla poniendo un estilo que no existe.
 */
class DeslizadoresTest {

    // ---- La bolita que se arrastra ----

    @Test
    fun `sin moverse se queda en la primera`() {
        assertEquals(0, opcionArrastrada(0f, 40f, 3, haciaLaIzquierda = false))
    }

    @Test
    fun `cada paso avanza una opcion`() {
        assertEquals(1, opcionArrastrada(40f, 40f, 3, haciaLaIzquierda = false))
        assertEquals(2, opcionArrastrada(80f, 40f, 3, haciaLaIzquierda = false))
    }

    /** El centro de cada opción es su punto: media casilla ya cuenta. */
    @Test
    fun `se redondea a la opcion mas cercana`() {
        assertEquals(1, opcionArrastrada(25f, 40f, 3, haciaLaIzquierda = false))
        assertEquals(0, opcionArrastrada(15f, 40f, 3, haciaLaIzquierda = false))
    }

    /** Pasarse arrastrando deja en la última: la selección no da la vuelta. */
    @Test
    fun `pasarse no da la vuelta`() {
        assertEquals(2, opcionArrastrada(9999f, 40f, 3, haciaLaIzquierda = false))
        assertEquals(0, opcionArrastrada(-9999f, 40f, 3, haciaLaIzquierda = false))
    }

    /** Con el panel a la derecha se arrastra hacia la izquierda, y eso avanza. */
    @Test
    fun `hacia la izquierda avanza al reves`() {
        assertEquals(1, opcionArrastrada(-40f, 40f, 3, haciaLaIzquierda = true))
        assertEquals(0, opcionArrastrada(40f, 40f, 3, haciaLaIzquierda = true))
    }

    @Test
    fun `sin opciones no hay nada que elegir`() {
        assertEquals(-1, opcionArrastrada(50f, 40f, 0, haciaLaIzquierda = false))
    }

    /**
     * El margen que separa un toque de un arrastre: el dedo nunca se levanta
     * exactamente donde cayó, y sin esto cualquier toque elegiría algo.
     */
    @Test
    fun `un temblor no abre el desplegable`() {
        assertFalse(abreElDesplegable(3f, 8f))
        assertFalse(abreElDesplegable(-3f, 8f))
        assertTrue(abreElDesplegable(9f, 8f))
        assertTrue(abreElDesplegable(-9f, 8f))
    }

    // ---- El deslizador vertical ----

    /** Arriba es más: es como se lee un termómetro. */
    @Test
    fun `arriba es uno y abajo es cero`() {
        assertEquals(1f, fraccionVertical(0f, 100f), 0.001f)
        assertEquals(0f, fraccionVertical(100f, 100f), 0.001f)
        assertEquals(0.5f, fraccionVertical(50f, 100f), 0.001f)
    }

    @Test
    fun `el dedo fuera del recorrido se queda en el tope`() {
        assertEquals(1f, fraccionVertical(-50f, 100f), 0.001f)
        assertEquals(0f, fraccionVertical(300f, 100f), 0.001f)
    }

    @Test
    fun `sin alto no se divide por cero`() {
        assertEquals(0f, fraccionVertical(10f, 0f), 0.001f)
    }

    @Test
    fun `las casillas se reparten el recorrido`() {
        assertEquals(0, casillaDe(0f, 4))
        assertEquals(3, casillaDe(1f, 4))
        assertEquals(1, casillaDe(0.34f, 4))
        assertEquals(2, casillaDe(0.66f, 4))
    }

    /** Ir y volver: la casilla que sale de una fracción vuelve a esa fracción. */
    @Test
    fun `de casilla a fraccion y de vuelta`() {
        val cuantas = 4
        (0 until cuantas).forEach { i ->
            assertEquals(i, casillaDe(fraccionDeLaCasilla(i, cuantas), cuantas))
        }
    }

    @Test
    fun `una sola casilla no revienta`() {
        assertEquals(0, casillaDe(0.5f, 1))
        assertEquals(1f, fraccionDeLaCasilla(0, 1), 0.001f)
    }

    // ---- El valor con paso ----

    @Test
    fun `la opacidad salta de cinco en cinco`() {
        assertEquals(100, valorConPaso(1f, 10, 100, 5))
        assertEquals(10, valorConPaso(0f, 10, 100, 5))
        // 10 + 0.5*90 = 55, que ya es redondo
        assertEquals(55, valorConPaso(0.5f, 10, 100, 5))
        // 10 + 0.51*90 = 55,9 → 55
        assertEquals(55, valorConPaso(0.51f, 10, 100, 5))
    }

    @Test
    fun `el valor nunca se sale del rango`() {
        (0..20).forEach { i ->
            val v = valorConPaso(i / 10f - 0.5f, 10, 100, 5)
            assertTrue("$v fuera de rango", v in 10..100)
        }
    }

    @Test
    fun `de valor a fraccion y de vuelta`() {
        listOf(10, 30, 55, 100).forEach { v ->
            assertEquals(v, valorConPaso(fraccionDelValor(v, 10, 100), 10, 100, 5))
        }
    }

    @Test
    fun `un rango imposible no revienta`() {
        assertEquals(10, valorConPaso(0.5f, 10, 10, 5))
        assertEquals(0f, fraccionDelValor(5, 10, 10), 0.001f)
    }

    // ---- Encontrar el valor puesto ----

    @Test
    fun `encuentra el grosor exacto`() {
        val anchos = listOf(1.0, 2.0, 4.0, 8.0)
        assertEquals(0, masCercano(1.0, anchos))
        assertEquals(3, masCercano(8.0, anchos))
    }

    /**
     * Un dibujo abierto de fuera puede traer un grosor que no está en la lista.
     * El deslizador tiene que colocarse en algún sitio, no saltar al primero.
     */
    @Test
    fun `un grosor de fuera se coloca en el mas parecido`() {
        val anchos = listOf(1.0, 2.0, 4.0, 8.0)
        assertEquals(2, masCercano(3.6, anchos))
        assertEquals(3, masCercano(20.0, anchos))
        assertEquals(0, masCercano(0.1, anchos))
    }

    @Test
    fun `sin valores no hay ninguno cercano`() {
        assertEquals(-1, masCercano(3.0, emptyList()))
    }
}
