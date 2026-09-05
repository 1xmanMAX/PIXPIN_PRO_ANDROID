package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * **La captura del Motor Pluma**: la cuarta lista de tiempos, el 1€ del pincel y el clavado
 * de la punta al levantar.
 *
 * Tres promesas y cada una con su prueba: los tiempos van UNO POR PUNTO y se aligeran por
 * los mismos índices que todo lo demás (la lista descolocada es el único fallo de verdad
 * posible aquí); el compás y la recta enderezada —que no tienen tiempos de mano— salen con
 * los suyos bien puestos; y la punta del trazo acaba EXACTAMENTE bajo el lápiz aunque el
 * filtro fuera retrasado, que es el momento en que el retardo del 1€ más se vería.
 */
class MotorPlumaCapturaTest {

    private val ancho = 1080.0
    private val alto = 2400.0

    /** Un controlador con su hoja puesta y el lápiz en la mano, como en las demás pruebas. */
    private fun conLapiz(): Croquis3DControlador {
        val c = Croquis3DControlador().apply { medida(ancho, alto) }
        c.herramienta = Herramienta3D.LAMINA
        c.tocar(Pt(200.0, 1000.0), Fase.BAJA, 0L)
        c.tocar(Pt(800.0, 1000.0), Fase.MUEVE, 16L)
        c.tocar(Pt(800.0, 1000.0), Fase.LEVANTA, 32L)
        c.navegar(VUELTA_ENTERA / 4.0, 0.0, 1.0, ancho / 2, alto / 2, Gesto.GIRAR)
        c.herramienta = Herramienta3D.LAPIZ
        return c
    }

    @Test
    fun `los tiempos van uno por punto y aligerados juntos`() {
        val c = conLapiz()
        var reloj = 1_000L
        c.tocar(Pt(460.0, 960.0), Fase.BAJA, reloj)
        // Muestras densas a propósito: el aligerado de 3px tira varias, y si los tiempos no
        // se aligeraran por los mismos índices las listas quedarían descolocadas.
        for (i in 1..60) {
            reloj += 8
            c.tocar(Pt(460.0 + i * 1.5, 960.0 + i * 1.5), Fase.MUEVE, reloj, presion = 0.7)
        }
        reloj += 8
        c.tocar(Pt(460.0 + 61 * 1.5, 960.0 + 61 * 1.5), Fase.LEVANTA, reloj)

        val trazo = c.croquis.trazos.single()
        val tiempos = trazo.tiempos
        assertNotNull("el trazo nuevo lleva tiempos", tiempos)
        assertEquals("uno por punto", trazo.puntos.size, tiempos!!.size)
        assertEquals("el primero es el arranque", 0.0, tiempos.first(), 1e-9)
        for (i in 1 until tiempos.size) {
            assertTrue("no van hacia atrás", tiempos[i] >= tiempos[i - 1])
        }
        assertEquals("y la presión sigue emparejada", trazo.puntos.size, trazo.presiones!!.size)
    }

    @Test
    fun `el compas sale con tiempos uniformes y sin pulso`() {
        val c = conLapiz()
        var reloj = 1_000L
        // Clavar la punta del compás: el dedo baja y se queda quieto más de medio segundo.
        c.tocar(Pt(540.0, 1050.0), Fase.BAJA, reloj)
        repeat(40) {
            reloj += 16
            c.latido(reloj)
        }
        assertTrue("el compás se ha clavado", c.redondeandoSolo)
        // Y se abre hasta el radio querido.
        reloj += 16
        c.tocar(Pt(590.0, 1050.0), Fase.MUEVE, reloj)
        reloj += 16
        c.tocar(Pt(590.0, 1050.0), Fase.LEVANTA, reloj)

        val rueda = c.croquis.trazos.single()
        assertNull("una figura de compás no lleva pulso", rueda.presiones)
        val tiempos = rueda.tiempos
        assertNotNull("pero sí tiempos: son su llave del motor nuevo", tiempos)
        assertEquals(rueda.puntos.size, tiempos!!.size)
        // Uniformes: el compás los sintetiza, porque sus puntos no los puso la mano.
        val paso = tiempos[1] - tiempos[0]
        for (i in 1 until tiempos.size) {
            assertEquals("parejos de punta a punta", paso, tiempos[i] - tiempos[i - 1], 1e-9)
        }
    }

    @Test
    fun `la recta enderezada guarda dos puntos y dos tiempos`() {
        val c = conLapiz()
        var reloj = 1_000L
        c.tocar(Pt(460.0, 960.0), Fase.BAJA, reloj)
        for (i in 1..8) {
            reloj += 16
            val temblor = if (i == 8) 0.0 else if (i % 2 == 0) 4.0 else -4.0
            c.tocar(Pt(460.0 + i * 10.0, 960.0 + i * 11.25 + temblor), Fase.MUEVE, reloj)
        }
        // El dedo se para y la raya se endereza.
        repeat(40) {
            reloj += 16
            c.tocar(Pt(540.0, 1050.0), Fase.MUEVE, reloj)
        }
        assertTrue(c.enderezandoSolo)
        reloj += 16
        c.tocar(Pt(540.0, 1050.0), Fase.LEVANTA, reloj)

        val recta = c.croquis.trazos.single()
        assertEquals("la recta sigue siendo dos puntos", 2, recta.puntos.size)
        assertEquals("y dos tiempos", 2, recta.tiempos!!.size)
        assertEquals(0.0, recta.tiempos!![0], 1e-9)
        assertTrue(recta.tiempos!![1] > 0.0)
    }

    @Test
    fun `la punta acaba bajo el lapiz aunque el filtro vaya retrasado`() {
        val c = conLapiz()
        var reloj = 1_000L
        c.tocar(Pt(460.0, 960.0), Fase.BAJA, reloj)
        // Despacio a propósito: a 250 px/s el corte del 1€ va bajo y el retardo se nota —
        // que es justo el caso en que la raya se quedaría corta sin el clavado.
        var x = 460.0
        for (i in 1..40) {
            reloj += 16
            x += 4.0
            c.tocar(Pt(x, 960.0), Fase.MUEVE, reloj, presion = 0.8)
        }
        val dondeSeLevanta = Pt(x, 960.0)
        reloj += 16
        c.tocar(dondeSeLevanta, Fase.LEVANTA, reloj, presion = 0.8)

        val trazo = c.croquis.trazos.single()
        // La última punta guardada, devuelta a la pantalla, cae donde se levantó el lápiz.
        val punta = c.camara.aPantalla(trazo.puntos.last(), ancho, alto)
        assertEquals(dondeSeLevanta.x, punta.x, 1e-6)
        assertEquals(dondeSeLevanta.y, punta.y, 1e-6)
        // Y la promesa no es vacía: el punto anterior sí venía retrasado por el filtro.
        val penultima = c.camara.aPantalla(
            trazo.puntos[trazo.puntos.size - 2], ancho, alto
        )
        assertTrue(
            "el filtro de verdad retrasaba (${hypot(punta.x - penultima.x, punta.y - penultima.y)} px)",
            hypot(punta.x - penultima.x, punta.y - penultima.y) > 2.0
        )
        // Las cuatro listas siguen emparejadas después del remate.
        assertEquals(trazo.puntos.size, trazo.presiones!!.size)
        assertEquals(trazo.puntos.size, trazo.tiempos!!.size)
    }
}
