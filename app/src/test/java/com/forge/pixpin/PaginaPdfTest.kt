package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cómo cae un dibujo dentro de un folio.
 *
 * Se comprueba aquí porque es el tipo de error que no se nota: un PDF con el
 * dibujo recortado por el borde, o pegado a una esquina con media página en
 * blanco, no falla ni avisa — se descubre cuando alguien lo imprime, que suele
 * ser el peor momento.
 */
class PaginaPdfTest {

    private val a4Ancho = 595.0
    private val a4Alto = 842.0
    private val margen = 28.0

    /** Dónde acaba en la página un punto de la escena, con esta vista. */
    private fun enPapel(v: Viewport, x: Double, y: Double): Pt = v.toScreen(Pt(x, y))

    @Test
    fun `un dibujo grande se reduce hasta caber entero`() {
        // Más ancho y más alto que el folio: tiene que encogerse.
        val caja = Bounds(0.0, 0.0, 2000.0, 1000.0)
        val v = encuadreEnPagina(caja, a4Alto, a4Ancho, margen)!! // apaisado
        assertTrue("tendría que haber encogido", v.zoom < 1.0)

        val esquina = enPapel(v, caja.x1, caja.y1)
        val opuesta = enPapel(v, caja.x2, caja.y2)
        assertTrue("se sale por la izquierda", esquina.x >= margen - 0.01)
        assertTrue("se sale por arriba", esquina.y >= margen - 0.01)
        assertTrue("se sale por la derecha", opuesta.x <= a4Alto - margen + 0.01)
        assertTrue("se sale por abajo", opuesta.y <= a4Ancho - margen + 0.01)
    }

    /**
     * **Nunca por encima de 1:1.** Ampliar un garabato de cuatro píxeles hasta
     * llenar el folio solo enseña sus imperfecciones.
     */
    @Test
    fun `un dibujo pequeño no se estira para llenar el folio`() {
        val v = encuadreEnPagina(Bounds(0.0, 0.0, 40.0, 30.0), a4Ancho, a4Alto, margen)!!
        assertEquals(1.0, v.zoom, 1e-9)
    }

    @Test
    fun `lo que sobra de folio se reparte a los dos lados`() {
        val caja = Bounds(0.0, 0.0, 100.0, 100.0)
        val v = encuadreEnPagina(caja, a4Ancho, a4Alto, margen)!!
        val a = enPapel(v, caja.x1, caja.y1)
        val b = enPapel(v, caja.x2, caja.y2)
        assertEquals("no está centrado a lo ancho", a.x - 0.0, a4Ancho - b.x, 0.01)
        assertEquals("no está centrado a lo alto", a.y - 0.0, a4Alto - b.y, 0.01)
    }

    /** El origen de la escena no tiene por qué ser (0,0): un dibujo vive donde vive. */
    @Test
    fun `un dibujo lejos del origen cae igual en la página`() {
        val caja = Bounds(5000.0, -3000.0, 5100.0, -2900.0)
        val v = encuadreEnPagina(caja, a4Ancho, a4Alto, margen)!!
        val a = enPapel(v, caja.x1, caja.y1)
        assertTrue("no puede quedar fuera del margen", a.x >= margen - 0.01)
        assertTrue("no puede quedar fuera del margen", a.y >= margen - 0.01)
    }

    @Test
    fun `sin nada que encuadrar no hay página`() {
        assertNull(encuadreEnPagina(Bounds(0.0, 0.0, 0.0, 0.0), a4Ancho, a4Alto, margen))
        assertNull(encuadreEnPagina(Bounds(0.0, 0.0, 10.0, 0.0), a4Ancho, a4Alto, margen))
        // Un margen que se come la página entera: mejor nada que un dibujo del
        // revés, que es lo que saldría con el zoom en negativo.
        assertNull(encuadreEnPagina(Bounds(0.0, 0.0, 10.0, 10.0), 40.0, 40.0, 30.0))
    }

    @Test
    fun `la página se tumba solo cuando el dibujo es más ancho que alto`() {
        assertTrue(paginaApaisada(Bounds(0.0, 0.0, 200.0, 100.0)))
        assertTrue(!paginaApaisada(Bounds(0.0, 0.0, 100.0, 200.0)))
        // En duda, vertical: es como se sostiene un folio por defecto.
        assertTrue(!paginaApaisada(Bounds(0.0, 0.0, 100.0, 100.0)))
    }

    /** El A4 en puntos son las medidas del papel, no de la pantalla de nadie. */
    @Test
    fun `el A4 mide lo que mide un A4`() {
        assertEquals(595, DrawPdf.A4_ANCHO)
        assertEquals(842, DrawPdf.A4_ALTO)
    }
}
