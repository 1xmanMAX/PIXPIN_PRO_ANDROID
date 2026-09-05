package com.forge.pixpin.croquis3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El motor de tintas.** Ver [Tinta].
 *
 * Se comprueba entero sin dispositivo, que es para lo que está aparte: lo que una tinta *es*
 * son señas y cuentas, y no hace falta pintar nada para saber si están bien.
 */
class TintaTest {

    @Test
    fun `todas las puntas que se ofrecen tienen sus señas`() {
        // Si alguien añade una punta y no la pone en la tabla, esto lo dice: se comportaría
        // como la redonda sin que nadie se entere.
        for (p in Pincel.LAS_QUE_HAY) {
            val suya = Tinta.de(p)
            val comoLaRedonda = Tinta.de(Pincel.REDONDO)
            if (p != Pincel.REDONDO) {
                assertTrue(
                    "$p no está en la tabla de tintas: se está pintando como la redonda",
                    suya != comoLaRedonda
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Cómo se apoyan
    // ---------------------------------------------------------------------

    @Test
    fun `el rodillo se tumba en la hoja y la cuchilla se pone de pie`() {
        assertEquals(ComoSeApoya.TUMBADA_EN_LA_HOJA, Tinta.de(Pincel.RESALTADOR).seApoya)
        assertEquals(ComoSeApoya.DE_PIE_EN_LA_HOJA, Tinta.de(Pincel.CUCHILLA).seApoya)
        // Y las dos barren una superficie, no un cuerpo: es lo que tienen en común.
        assertEquals(Seccion.LAMINA, Tinta.de(Pincel.RESALTADOR).seccion)
        assertEquals(Seccion.LAMINA, Tinta.de(Pincel.CUCHILLA).seccion)
    }

    @Test
    fun `las de dibujar miran al que mira`() {
        for (p in listOf(Pincel.REDONDO, Pincel.CUADRADO, Pincel.LUZ)) {
            assertEquals(ComoSeApoya.MIRANDO_AL_QUE_MIRA, Tinta.de(p).seApoya)
        }
    }

    // ---------------------------------------------------------------------
    // Qué manda el selector
    // ---------------------------------------------------------------------

    @Test
    fun `el selector ensancha las de dibujar tal cual`() {
        assertEquals(6.0, Tinta.de(Pincel.REDONDO).gordoDe(6.0), 1e-9)
        assertEquals(6.0, Tinta.de(Pincel.CUADRADO).gordoDe(6.0), 1e-9)
    }

    @Test
    fun `el rodillo sale mucho mas ancho que el numero`() {
        val gordo = Tinta.de(Pincel.RESALTADOR).gordoDe(6.0)
        assertTrue("un rodillo a los grosores de dibujar no pinta, raya: $gordo", gordo > 20.0)
    }

    @Test
    fun `la cuchilla ahonda, y por eso tambien sale de otro orden`() {
        assertEquals(LoQueMandaElSelector.LO_HONDO, Tinta.de(Pincel.CUCHILLA).manda)
        assertTrue(Tinta.de(Pincel.CUCHILLA).gordoDe(6.0) > 6.0)
    }

    // ---------------------------------------------------------------------
    // Qué hace cada una
    // ---------------------------------------------------------------------

    @Test
    fun `solo la luz alumbra, salvo que la punta lo diga`() {
        assertTrue(Tinta.de(Pincel.LUZ).alumbra)
        assertFalse(Tinta.de(Pincel.REDONDO).alumbra)
        // Y cualquier punta puede alumbrar si se le pone: es una seña de la punta, no de la
        // tinta. Ver [PuntaDelPincel.alumbra].
        assertTrue(Tinta.de(Pincel.REDONDO, PuntaDelPincel(alumbra = true)).alumbra)
    }

    @Test
    fun `solo el rodillo se funde en una sola mano`() {
        assertTrue(Tinta.de(Pincel.RESALTADOR).unaSolaMano)
        for (p in listOf(Pincel.REDONDO, Pincel.CUADRADO, Pincel.CUCHILLA, Pincel.LUZ)) {
            assertFalse("$p no puede fundir sus pasadas", Tinta.de(p).unaSolaMano)
        }
    }

    @Test
    fun `solo la redonda obedece al pulso`() {
        assertTrue(Tinta.de(Pincel.REDONDO).obedeceAlPulso)
        for (p in listOf(Pincel.CUADRADO, Pincel.CUCHILLA, Pincel.RESALTADOR, Pincel.LUZ)) {
            assertFalse("$p no lleva pulso", Tinta.de(p).obedeceAlPulso)
        }
        // Y se le puede quitar a la redonda desde la punta.
        assertFalse(Tinta.de(Pincel.REDONDO, PuntaDelPincel(pulso = false)).obedeceAlPulso)
    }

    @Test
    fun `una punta con seccion dibujada manda sobre la de fabrica`() {
        val conPerfil = PuntaDelPincel(
            perfil = listOf(
                com.forge.pixpin.motor.Pt(0.0, 0.0),
                com.forge.pixpin.motor.Pt(1.0, 0.0),
                com.forge.pixpin.motor.Pt(0.5, 1.0)
            )
        )
        assertEquals(Seccion.DIBUJADA, Tinta.de(Pincel.REDONDO, conPerfil).seccion)
        assertEquals(Seccion.DIBUJADA, Tinta.de(Pincel.CUADRADO, conPerfil).seccion)
    }

    @Test
    fun `las puntas retiradas se abren como la redonda`() {
        assertEquals(Tinta.de(Pincel.REDONDO), Tinta.de(Pincel.PLANO))
        assertEquals(Tinta.de(Pincel.REDONDO), Tinta.de(Pincel.LAPIZ))
    }

    // ---------------------------------------------------------------------
    // El material: de qué está hecha la tinta
    // ---------------------------------------------------------------------

    /**
     * **La forma y el material son dos preguntas.**
     *
     * Es lo que estaba mal cuando la luz era una punta: elegir «luz» tiraba la sección que
     * uno tuviera. Aquí se comprueba lo contrario: cualquier punta puede llevar cualquier
     * material sin perder su sección.
     */
    @Test
    fun `cualquier punta puede ser de cualquier material`() {
        for (p in Pincel.LAS_QUE_HAY) {
            val suSeccion = Tinta.de(p).seccion
            for (m in MaterialDeLaTinta.entries) {
                val tinta = Tinta.de(p, m.puestoEn(PuntaDelPincel()))
                assertEquals(
                    "$p con material $m ha perdido su sección",
                    suSeccion, tinta.seccion
                )
            }
        }
    }

    @Test
    fun `el material que lleva una punta es el que se le puso`() {
        for (m in MaterialDeLaTinta.entries) {
            val punta = m.puestoEn(PuntaDelPincel())
            assertEquals(m, MaterialDeLaTinta.de(punta))
            assertTrue(m.esElDe(punta))
        }
    }

    /** La luz y el grano no se llevan: lo que se ve de un tubo encendido es el resplandor. */
    @Test
    fun `el material es uno cada vez`() {
        val rayada = MaterialDeLaTinta.RAYADO.puestoEn(PuntaDelPincel())
        val yLuego= MaterialDeLaTinta.LUZ.puestoEn(rayada)
        assertTrue(yLuego.alumbra)
        assertEquals(Trama.NINGUNA, yLuego.trama)
        assertFalse(MaterialDeLaTinta.LISO.puestoEn(yLuego).alumbra)
    }

    /**
     * **El grano llega a la tinta por todas las puntas.**
     *
     * El rodillo también: dar una mano de color rayada es lo que se hace para decir «esto
     * está cortado» sin cambiar de tinta.
     */
    @Test
    fun `el grano de la punta llega a su tinta`() {
        for (p in Pincel.LAS_QUE_HAY) {
            val punta = PuntaDelPincel(trama = Trama.CRUZADO)
            assertEquals(Trama.CRUZADO, Tinta.de(p, punta).trama)
            assertTrue(Tinta.de(p, punta).trama.hayQuePintarla)
        }
        assertFalse(Tinta.de(Pincel.REDONDO).trama.hayQuePintarla)
    }

    /**
     * **El motor y el controlador dicen lo mismo.**
     *
     * Es la comprobación que da sentido a tener el motor: que lo gordo que sale un trazo no
     * se decida en dos sitios. Ver [Croquis3DControlador.grosorDeVerdad].
     */
    @Test
    fun `lo gordo que sale sale del motor y de ningun otro sitio`() {
        val c = Croquis3DControlador()
        for (p in Pincel.LAS_QUE_HAY) {
            c.pincel = p
            c.grosor = 7.0
            assertEquals(Tinta.de(p, c.punta).gordoDe(7.0), c.grosorDeVerdad, 1e-9)
        }
    }
}
