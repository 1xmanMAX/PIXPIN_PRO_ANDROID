package com.forge.pixpin.motormd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El aplanado de marcas solapadas a tramos pintables.
 *
 * Lo que se comprueba una y otra vez aquí es la **propiedad**, no un reparto
 * concreto: los tramos no se pisan, van en orden, y cada letra acaba con
 * exactamente los estilos de las marcas que la cubrían. Mientras eso se cumpla da
 * igual por dónde se hayan hecho los cortes.
 */
class TramosTest {

    private fun estilosEn(tramos: List<Tramo>, pos: Int): Int =
        tramos.firstOrNull { pos >= it.inicio && pos < it.fin }?.estilos ?: 0

    private fun asserta(tramos: List<Tramo>) {
        var anterior = -1
        tramos.forEach {
            assertTrue("tramo vacio o invertido: $it", it.inicio < it.fin)
            assertTrue("tramos solapados o desordenados: $it", it.inicio >= anterior)
            anterior = it.fin
        }
    }

    @Test
    fun `sin marcas no hay tramos`() {
        assertTrue(tramosDe(emptyList(), 10).isEmpty())
    }

    @Test
    fun `una marca sola sale tal cual`() {
        val t = tramosDe(listOf(InlineSpan(2, 5, SpanKind.BOLD)), 10)
        assertEquals(1, t.size)
        assertEquals(2, t[0].inicio)
        assertEquals(5, t[0].fin)
        assertTrue(t[0].tiene(SpanKind.BOLD))
    }

    /** El caso de Telegram: dos marcas que se cruzan a medias dan tres trozos. */
    @Test
    fun `dos marcas cruzadas dan tres tramos`() {
        val t = tramosDe(
            listOf(
                InlineSpan(0, 6, SpanKind.BOLD),
                InlineSpan(3, 10, SpanKind.ITALIC)
            ),
            10
        )
        asserta(t)
        assertEquals(3, t.size)

        assertEquals(SpanKind.BOLD.bandera, estilosEn(t, 1))
        assertEquals(SpanKind.BOLD.bandera or SpanKind.ITALIC.bandera, estilosEn(t, 4))
        assertEquals(SpanKind.ITALIC.bandera, estilosEn(t, 8))
    }

    @Test
    fun `una marca dentro de otra parte la de fuera en tres`() {
        val t = tramosDe(
            listOf(
                InlineSpan(0, 10, SpanKind.BOLD),
                InlineSpan(4, 6, SpanKind.ITALIC)
            ),
            10
        )
        asserta(t)
        assertEquals(3, t.size)
        assertEquals(SpanKind.BOLD.bandera, estilosEn(t, 0))
        assertEquals(SpanKind.BOLD.bandera or SpanKind.ITALIC.bandera, estilosEn(t, 5))
        assertEquals(SpanKind.BOLD.bandera, estilosEn(t, 9))
    }

    /** Dos marcas con los mismos extremos son un tramo, no dos pegados. */
    @Test
    fun `marcas con el mismo tramo se funden en uno`() {
        val t = tramosDe(
            listOf(
                InlineSpan(1, 4, SpanKind.BOLD),
                InlineSpan(1, 4, SpanKind.STRIKE)
            ),
            10
        )
        assertEquals(1, t.size)
        assertTrue(t[0].tiene(SpanKind.BOLD))
        assertTrue(t[0].tiene(SpanKind.STRIKE))
    }

    /**
     * Sin la fusión de contiguos, una marca que empieza donde acaba otra dejaría
     * una costura invisible pero real, y el que pinta vería dos trozos iguales.
     */
    @Test
    fun `los tramos contiguos iguales se juntan`() {
        val t = tramosDe(
            listOf(
                InlineSpan(0, 5, SpanKind.BOLD),
                InlineSpan(5, 9, SpanKind.BOLD)
            ),
            10
        )
        assertEquals(1, t.size)
        assertEquals(0, t[0].inicio)
        assertEquals(9, t[0].fin)
    }

    @Test
    fun `los huecos sin estilo no se emiten`() {
        val t = tramosDe(
            listOf(
                InlineSpan(0, 2, SpanKind.BOLD),
                InlineSpan(6, 8, SpanKind.BOLD)
            ),
            10
        )
        assertEquals(2, t.size)
        assertEquals(0, estilosEn(t, 4))
    }

    @Test
    fun `el enlace lleva su url al tramo`() {
        val t = tramosDe(
            listOf(InlineSpan(0, 4, SpanKind.LINK, url = "https://a.com")),
            10
        )
        assertEquals("https://a.com", t[0].url)
    }

    @Test
    fun `la negrita dentro de un enlace conserva la url`() {
        val t = tramosDe(
            listOf(
                InlineSpan(0, 8, SpanKind.LINK, url = "https://a.com"),
                InlineSpan(2, 5, SpanKind.BOLD)
            ),
            10
        )
        asserta(t)
        t.forEach { assertEquals("https://a.com", it.url) }
        assertTrue(t.any { it.tiene(SpanKind.BOLD) && it.tiene(SpanKind.LINK) })
    }

    @Test
    fun `un tramo sin enlace no se inventa una url`() {
        val t = tramosDe(listOf(InlineSpan(0, 4, SpanKind.BOLD)), 10)
        assertNull(t[0].url)
    }

    // ---- Basura de entrada: nada de esto puede tumbar un pin ----

    @Test
    fun `un tramo que se sale por la derecha se recorta`() {
        val t = tramosDe(listOf(InlineSpan(2, 99, SpanKind.BOLD)), 10)
        assertEquals(1, t.size)
        assertEquals(10, t[0].fin)
    }

    @Test
    fun `los tramos imposibles se descartan`() {
        val t = tramosDe(
            listOf(
                InlineSpan(-5, 3, SpanKind.BOLD),
                InlineSpan(7, 7, SpanKind.ITALIC),
                InlineSpan(20, 25, SpanKind.STRIKE),
                InlineSpan(5, 2, SpanKind.CODE)
            ),
            10
        )
        assertTrue(t.isEmpty())
    }

    @Test
    fun `longitud cero no revienta`() {
        assertTrue(tramosDe(listOf(InlineSpan(0, 3, SpanKind.BOLD)), 0).isEmpty())
    }

    /**
     * La prueba de fuego: un montón de marcas cruzadas al azar pero fijas, y se
     * comprueba letra a letra que el aplanado dice justo lo que dicen las marcas.
     */
    @Test
    fun `letra a letra el aplanado coincide con las marcas`() {
        val longitud = 40
        val spans = listOf(
            InlineSpan(0, 12, SpanKind.BOLD),
            InlineSpan(5, 20, SpanKind.ITALIC),
            InlineSpan(18, 30, SpanKind.STRIKE),
            InlineSpan(10, 11, SpanKind.CODE),
            InlineSpan(25, 40, SpanKind.BOLD),
            InlineSpan(0, 40, SpanKind.SPOILER)
        )
        val t = tramosDe(spans, longitud)
        asserta(t)

        for (pos in 0 until longitud) {
            val esperado = spans
                .filter { pos >= it.start && pos < it.end }
                .fold(0) { acc, s -> acc or s.kind.bandera }
            assertEquals("en la posicion $pos", esperado, estilosEn(t, pos))
        }
    }
}
