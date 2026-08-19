package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirar el lienzo **a la escala de una hoja**.
 *
 * El lienzo es infinito, pero lo que se entrega casi siempre es una hoja. Ver el
 * dibujo al tamaño al que va a salir impreso es la única forma de saber si la
 * línea fina se va a perder o si el texto se va a leer.
 */
class PapelTest {

    /** Una hoja cabe entera, y con margen: pegada al borde no se ve dónde acaba. */
    @Test
    fun `la hoja cabe en la pantalla`() {
        val z = Papel.A4.zoomPara(1080.0, 2400.0)
        assertTrue("no cabe de ancho", Papel.A4.ancho * z <= 1080.0)
        assertTrue("no cabe de alto", Papel.A4.alto * z <= 2400.0)
        assertTrue("va pegada al borde", Papel.A4.ancho * z < 1080.0 * 0.95)
    }

    /** Manda el lado que peor va, que es lo que hace que quepa de verdad. */
    @Test
    fun `manda el lado mas apretado`() {
        // Pantalla ancha y baja: el que aprieta es el alto.
        val z = Papel.A4.zoomPara(4000.0, 900.0)
        assertEquals(900.0 / Papel.A4.alto * 0.9, z, 0.0001)
    }

    /** Cuanto más grande el papel, más pequeño hay que verlo. */
    @Test
    fun `un papel mayor pide menos zoom`() {
        val pantalla = 1080.0 to 2400.0
        val zA5 = Papel.A5.zoomPara(pantalla.first, pantalla.second)
        val zA1 = Papel.A1.zoomPara(pantalla.first, pantalla.second)
        assertTrue("A1 tendría que verse más pequeño", zA1 < zA5)
    }

    /** Los cinco formatos, en orden y sin repetirse. */
    @Test
    fun `los formatos van de menor a mayor`() {
        val areas = Papel.entries.map { it.ancho * it.alto }
        assertEquals(areas.sorted(), areas)
        // Y cada uno es aproximadamente el doble del anterior, como manda la
        // serie A: si alguno se desviara, no sería ese formato.
        areas.zipWithNext().forEach { (a, b) -> assertEquals(2.0, b / a, 0.02) }
    }
}
