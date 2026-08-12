package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El lienzo infinito partido en hojas A4 invisibles. */
class HojasA4Test {

    private fun trazo(x: Double, y: Double, ancho: Double = 10.0, alto: Double = 10.0) =
        Element(
            id = "e-$x-$y",
            type = ElementType.RECTANGLE,
            x = x, y = y, width = ancho, height = alto, seed = 1
        )

    // ---- La cuadrícula ----

    @Test
    fun `el origen cae en la hoja cero`() {
        assertEquals(HojasA4.Celda(0, 0), HojasA4.celdaEn(0.0, 0.0))
        assertEquals(HojasA4.Celda(0, 0), HojasA4.celdaEn(100.0, 100.0))
    }

    @Test
    fun `pasado el borde se cambia de hoja`() {
        assertEquals(HojasA4.Celda(1, 0), HojasA4.celdaEn(HojasA4.ANCHO + 1, 10.0))
        assertEquals(HojasA4.Celda(0, 1), HojasA4.celdaEn(10.0, HojasA4.ALTO + 1))
    }

    /** El lienzo es infinito en todas direcciones, también hacia atrás. */
    @Test
    fun `en negativo tambien hay hojas`() {
        assertEquals(HojasA4.Celda(-1, -1), HojasA4.celdaEn(-10.0, -10.0))
        assertEquals(HojasA4.Celda(-2, 0), HojasA4.celdaEn(-HojasA4.ANCHO - 1, 10.0))
    }

    @Test
    fun `la caja de una hoja mide un A4`() {
        val caja = HojasA4.cajaDe(HojasA4.Celda(2, 3))
        assertEquals(HojasA4.ANCHO, caja.width, 0.001)
        assertEquals(HojasA4.ALTO, caja.height, 0.001)
        assertEquals(2 * HojasA4.ANCHO, caja.x1, 0.001)
        assertEquals(3 * HojasA4.ALTO, caja.y1, 0.001)
    }

    /**
     * La cuadrícula está anclada al cero del lienzo, no a lo primero que se
     * dibuje: si se moviera, borrar un trazo recolocaría todas las páginas.
     */
    @Test
    fun `la cuadricula no se mueve con lo que haya dibujado`() {
        val soloLejos = listOf(trazo(5000.0, 5000.0))
        val celda = HojasA4.ocupadas(soloLejos).single()
        assertEquals(HojasA4.celdaEn(5000.0, 5000.0), celda)
    }

    // ---- Qué hojas existen ----

    @Test
    fun `un lienzo vacio no tiene hojas`() {
        assertTrue(HojasA4.ocupadas(emptyList()).isEmpty())
        assertTrue(HojasA4.paginas(emptyList()).isEmpty())
    }

    @Test
    fun `una hoja existe en cuanto hay algo en ella`() {
        assertEquals(1, HojasA4.ocupadas(listOf(trazo(10.0, 10.0))).size)
    }

    @Test
    fun `dos trazos en la misma hoja son una sola hoja`() {
        val hojas = HojasA4.ocupadas(listOf(trazo(10.0, 10.0), trazo(200.0, 300.0)))
        assertEquals(1, hojas.size)
    }

    /** Un dibujo que cruza el borde aparece en las dos, como en el papel. */
    @Test
    fun `un dibujo a caballo ocupa las dos hojas`() {
        val ancho = trazo(HojasA4.ANCHO - 20, 10.0, ancho = 60.0)
        val hojas = HojasA4.ocupadas(listOf(ancho))
        assertEquals(2, hojas.size)
        assertTrue(hojas.contains(HojasA4.Celda(0, 0)))
        assertTrue(hojas.contains(HojasA4.Celda(1, 0)))
    }

    /** Los marcos son otra forma de paginar: no crean hojas por su cuenta. */
    @Test
    fun `un marco no crea hoja`() {
        val marco = Element(
            id = "m", type = ElementType.FRAME,
            x = 4000.0, y = 4000.0, width = 100.0, height = 100.0, seed = 1
        )
        assertTrue(HojasA4.ocupadas(listOf(marco)).isEmpty())
    }

    // ---- El orden ----

    @Test
    fun `las hojas salen en orden de lectura`() {
        val elementos = listOf(
            trazo(HojasA4.ANCHO + 10, HojasA4.ALTO + 10), // (1,1)
            trazo(10.0, HojasA4.ALTO + 10),               // (0,1)
            trazo(HojasA4.ANCHO + 10, 10.0),              // (1,0)
            trazo(10.0, 10.0)                             // (0,0)
        )
        assertEquals(
            listOf(
                HojasA4.Celda(0, 0), HojasA4.Celda(1, 0),
                HojasA4.Celda(0, 1), HojasA4.Celda(1, 1)
            ),
            HojasA4.ocupadas(elementos)
        )
    }

    // ---- Qué lleva cada hoja ----

    @Test
    fun `cada hoja se lleva lo que hay dentro`() {
        val aqui = trazo(10.0, 10.0)
        val alla = trazo(HojasA4.ANCHO + 10, 10.0)
        val dentro = HojasA4.contenidoDe(listOf(aqui, alla), HojasA4.Celda(0, 0))
        assertEquals(listOf(aqui), dentro)
    }

    @Test
    fun `lo que asoma sale en las dos hojas`() {
        val ancho = trazo(HojasA4.ANCHO - 20, 10.0, ancho = 60.0)
        assertEquals(1, HojasA4.contenidoDe(listOf(ancho), HojasA4.Celda(0, 0)).size)
        assertEquals(1, HojasA4.contenidoDe(listOf(ancho), HojasA4.Celda(1, 0)).size)
    }

    /** Lo que enseña la vista previa y lo que sale al exportar: la misma lista. */
    @Test
    fun `las paginas traen su caja y su contenido`() {
        val elementos = listOf(trazo(10.0, 10.0), trazo(HojasA4.ANCHO + 10, 10.0))
        val paginas = HojasA4.paginas(elementos)
        assertEquals(2, paginas.size)
        paginas.forEach { (caja, contenido) ->
            assertEquals(HojasA4.ANCHO, caja.width, 0.001)
            assertTrue(contenido.isNotEmpty())
        }
    }

    // ---- Basura ----

    @Test
    fun `una caja disparatada no llena la memoria`() {
        val enorme = Bounds(-1e9, -1e9, 1e9, 1e9)
        assertTrue(HojasA4.celdasDe(enorme).size <= 1)
    }

    @Test
    fun `una caja del reves no revienta`() {
        HojasA4.celdasDe(Bounds(100.0, 100.0, 0.0, 0.0))
    }
}
