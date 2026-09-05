package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La caja, **después de dibujarla**.
 *
 * Tenía dos fases de gesto para nacer y ninguna para corregirse: una vez fijada solo se
 * podía mover, así que ajustar una proporción era borrarla y volver a trazarla. Aquí se
 * comprueba lo que arregla eso —los tiradores y lo que hace cada uno— y de paso que su
 * envoltura sea la de lo que se ve, que es lo que decide si el recuadro de selección la
 * coge o barre media pantalla al vacío.
 */
class TiradoresDelSolidoTest {

    private val tol = 1e-9

    private fun caja(
        x: Double = 100.0, y: Double = 100.0,
        ancho: Double = 60.0, fondo: Double = 40.0, alto: Double? = 50.0
    ): Element = newElement(ElementType.SOLIDO, x, y, ItemStyle(), ancho, fondo)
        .copy(altura = alto, backgroundColor = "#808080")

    private fun tirador(e: Element, tipo: HandleType, vista: Vista = Vista.CERO): Pt =
        tiradoresDelSolido(e, vista).first { it.first == tipo }.second

    // ---- Dónde caen ----

    /** El del alto va **encima de la esquina del origen**, el punto más alto del dibujo. */
    @Test
    fun `el tirador del alto sube lo que mide la caja`() {
        val e = caja(alto = 50.0)
        val p = tirador(e, HandleType.SOLIDO_ALTURA)
        assertEquals(e.x, p.x, tol)
        assertEquals(e.y - 50.0, p.y, tol)
    }

    /** Y el de la huella, en la esquina de enfrente: donde se soltó el dedo al dibujarla. */
    @Test
    fun `el tirador de la huella cae en la esquina de enfrente`() {
        val e = caja(ancho = 60.0, fondo = 40.0)
        val p = tirador(e, HandleType.SOLIDO_HUELLA)
        val esperado = proyectar(60.0, 40.0, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
        assertEquals(e.x + esperado.x, p.x, tol)
        assertEquals(e.y + esperado.y, p.y, tol)
    }

    /** Los cuatro están **sobre el dibujo**, sea cual sea la vista puesta. */
    @Test
    fun `los tiradores caen dentro de lo que ocupa la caja`() {
        val e = caja()
        for (vista in Vista.entries) {
            val caja = envolturaDeSolido(e, vista)
            for ((tipo, p) in tiradoresDelSolido(e, vista)) {
                assertTrue(
                    "$tipo fuera en $vista: $p vs $caja",
                    p.x >= caja.x1 - tol && p.x <= caja.x2 + tol &&
                        p.y >= caja.y1 - tol && p.y <= caja.y2 + tol
                )
            }
        }
    }

    // ---- Qué cambia cada uno ----

    /** El de la huella cambia los dos ejes; el dedo cae justo en la esquina nueva. */
    @Test
    fun `la huella sigue al dedo por los dos ejes`() {
        val e = caja(ancho = 60.0, fondo = 40.0)
        // Cien de ancho y veinte de fondo, en coordenadas del mundo.
        val destino = proyectar(100.0, 20.0, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
        val h = huellaMoldeada(
            Pt(e.x, e.y), Pt(e.x + destino.x, e.y + destino.y),
            e.width, e.height, HandleType.SOLIDO_HUELLA, Vista.CERO
        )
        assertEquals(100.0, h.ancho, tol)
        assertEquals(20.0, h.fondo, tol)
        assertEquals(e.x, h.x, tol)
        assertEquals(e.y, h.y, tol)
    }

    /** El del ancho **no toca el fondo**, aunque el dedo se vaya por ese eje. */
    @Test
    fun `el tirador del ancho deja el fondo como estaba`() {
        val e = caja(ancho = 60.0, fondo = 40.0)
        val destino = proyectar(100.0, 200.0, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
        val h = huellaMoldeada(
            Pt(e.x, e.y), Pt(e.x + destino.x, e.y + destino.y),
            e.width, e.height, HandleType.SOLIDO_ANCHO, Vista.CERO
        )
        assertEquals(100.0, h.ancho, tol)
        assertEquals(40.0, h.fondo, tol)
    }

    /** Y el del fondo, al revés. */
    @Test
    fun `el tirador del fondo deja el ancho como estaba`() {
        val e = caja(ancho = 60.0, fondo = 40.0)
        val destino = proyectar(200.0, 100.0, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
        val h = huellaMoldeada(
            Pt(e.x, e.y), Pt(e.x + destino.x, e.y + destino.y),
            e.width, e.height, HandleType.SOLIDO_FONDO, Vista.CERO
        )
        assertEquals(60.0, h.ancho, tol)
        assertEquals(100.0, h.fondo, tol)
    }

    /**
     * Arrastrando el tirador **más allá del origen**, la huella no sale con el ancho en
     * negativo: se recoloca la esquina, igual que al dibujarla. Una caja con la anchura
     * negativa rompe todo lo que mide cajas, empezando por la selección.
     */
    @Test
    fun `arrastrar hacia atras recoloca la esquina`() {
        val e = caja(ancho = 60.0, fondo = 40.0)
        val destino = proyectar(-60.0, 40.0, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
        val h = huellaMoldeada(
            Pt(e.x, e.y), Pt(e.x + destino.x, e.y + destino.y),
            e.width, e.height, HandleType.SOLIDO_HUELLA, Vista.CERO
        )
        assertTrue(h.ancho > 0 && h.fondo > 0)
        assertEquals(60.0, h.ancho, tol)
        // El origen se ha ido a donde ahora empieza la huella.
        val esperado = proyectar(-60.0, 0.0, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
        assertEquals(e.x + esperado.x, h.x, tol)
    }

    // ---- La envoltura ----

    /**
     * La caja exacta es **bastante más pequeña** que la unión de las cuatro vistas, que
     * es lo que se devolvía antes de saber desde dónde se mira. Esa diferencia era el
     * recuadro de selección enorme y vacío alrededor de una caja pequeña.
     */
    @Test
    fun `la envoltura con vista aprieta mas que la de sin vista`() {
        val e = caja(ancho = 60.0, fondo = 40.0, alto = 50.0)
        val exacta = envolturaDeSolido(e, Vista.CERO)
        val floja = envolturaDeSolidoSinVista(e)
        assertTrue("${exacta.width} vs ${floja.width}", exacta.width < floja.width)
        assertTrue(exacta.x1 >= floja.x1 - tol && exacta.x2 <= floja.x2 + tol)
        assertTrue(exacta.y1 >= floja.y1 - tol && exacta.y2 <= floja.y2 + tol)
    }

    /** Y envuelve de verdad lo que se pinta: las caras visibles caen dentro. */
    @Test
    fun `la envoltura contiene las caras que se pintan`() {
        val e = caja()
        for (vista in Vista.entries) {
            val b = envolturaDeSolido(e, vista)
            for (cara in carasDeElemento(e, vista)) {
                for (p in cara.poligono) {
                    assertTrue(
                        "cara fuera en $vista: $p vs $b",
                        p.x >= b.x1 - tol && p.x <= b.x2 + tol &&
                            p.y >= b.y1 - tol && p.y <= b.y2 + tol
                    )
                }
            }
        }
    }

    /** Encerrarla con el dedo la coge sin tener que barrer de más. */
    @Test
    fun `el recuadro ajustado a lo que se ve la selecciona`() {
        val e = caja()
        val b = envolturaDeSolido(e, Vista.CERO)
        val recuadro = Bounds(b.x1 - 2, b.y1 - 2, b.x2 + 2, b.y2 + 2)
        assertTrue(
            getElementsWithinSelection(listOf(e), recuadro, vista = Vista.CERO).contains(e)
        )
    }
}
