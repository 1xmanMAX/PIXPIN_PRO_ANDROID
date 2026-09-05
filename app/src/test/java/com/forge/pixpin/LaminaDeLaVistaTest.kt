package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.Pt3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La vista congelada, pasada a lámina.** Ver [HojaDeLaVista.proyectada].
 *
 * Lo que se comprueba aquí es que la lámina enseñe lo mismo que el croquis: el espacio de
 * su color, lo subrayado translúcido y debajo de la tinta, las sombras puestas y nada de
 * lo que caía fuera del encuadre.
 */
class LaminaDeLaVistaTest {

    private val camara = Camara3D()

    private fun vista(recorte: Recorte? = null) =
        Vista3D("v1", "1", camara, recorte = recorte)

    private fun trazo(
        id: String,
        de: Pt3,
        a: Pt3,
        color: String = "#1e1e1e",
        pincel: Pincel = Pincel.REDONDO
    ) = Trazo3D(id, listOf(de, a), color, 6.0, pincel = pincel)

    private fun proyectar(croquis: Croquis, recorte: Recorte? = null) =
        HojaDeLaVista.proyectada(
            croquis, vista(recorte),
            escala = 1.0, alto = 1000.0, densidad = 1.0,
            anchoDeLaVista = 1000.0, altoDeLaVista = 1000.0
        )

    // ---------------------------------------------------------------------
    // El resaltador
    // ---------------------------------------------------------------------

    @Test
    fun `el rodillo sale con lo que tape su tinta, no aclarado`() {
        // Es pintura, no un marcador: una mano de color tapa lo que hay debajo. Ver
        // [Croquis3DLienzo] y su alfa del rodillo.
        val croquis = Croquis(
            trazos = listOf(
                trazo("t", Pt3(-50.0, 0.0, 0.0), Pt3(50.0, 0.0, 0.0), pincel = Pincel.RESALTADOR)
            )
        )
        val banda = proyectar(croquis).single { it.type == ElementType.FREEDRAW }
        assertEquals("una mano de color tapa", 100, banda.opacity)
        // Y de ancho parejo: sin pulso que se invente el generador a partir de la velocidad.
        assertTrue("una banda afilada por las puntas no es un rodillo", banda.presionFirme)
        assertEquals(null, banda.pressures)
    }

    @Test
    fun `el rodillo va debajo de la tinta`() {
        val croquis = Croquis(
            trazos = listOf(
                trazo("tinta", Pt3(-50.0, 0.0, 0.0), Pt3(50.0, 0.0, 0.0)),
                trazo(
                    "banda", Pt3(-50.0, 0.0, 0.0), Pt3(50.0, 0.0, 0.0),
                    pincel = Pincel.RESALTADOR
                )
            )
        )
        val fuera = proyectar(croquis).filter { it.type == ElementType.FREEDRAW }
        assertEquals(2, fuera.size)
        // Se da la mano de color y encima se dibuja: al revés, la pintura taparía el dibujo.
        assertTrue("el rodillo no va debajo", fuera.first().presionFirme)
        assertTrue(!fuera.last().presionFirme)
    }

    // ---------------------------------------------------------------------
    // El fondo
    // ---------------------------------------------------------------------

    @Test
    fun `el fondo del croquis va a la lamina`() {
        val croquis = Croquis(
            colorDelFondo = "#101418",
            trazos = listOf(trazo("t", Pt3(-50.0, 0.0, 0.0), Pt3(50.0, 0.0, 0.0)))
        )
        val fondo = proyectar(croquis).firstOrNull()
        assertNotNull(fondo)
        assertEquals(ElementType.RECTANGLE, fondo!!.type)
        assertEquals("#101418", fondo.backgroundColor)
        assertEquals(0.0, fondo.x, 1e-9)
        assertEquals(0.0, fondo.y, 1e-9)
    }

    @Test
    fun `sin fondo elegido no se inventa ninguno`() {
        val croquis = Croquis(trazos = listOf(trazo("t", Pt3(-50.0, 0.0, 0.0), Pt3(50.0, 0.0, 0.0))))
        assertTrue(proyectar(croquis).none { it.type == ElementType.RECTANGLE })
    }

    @Test
    fun `un croquis vacio no da lamina`() {
        assertTrue(proyectar(Croquis(colorDelFondo = "#ffffff")).isEmpty())
    }

    // ---------------------------------------------------------------------
    // Las sombras
    // ---------------------------------------------------------------------

    @Test
    fun `con sol, lo dibujado tira su sombra en la lamina`() {
        val conSol = Croquis(
            sol = Sol3D(),
            trazos = listOf(trazo("t", Pt3(-50.0, 0.0, 60.0), Pt3(50.0, 0.0, 60.0)))
        )
        val sinSol = conSol.copy(sol = null)
        val trazosConSol = proyectar(conSol).count { it.type == ElementType.FREEDRAW }
        val trazosSinSol = proyectar(sinSol).count { it.type == ElementType.FREEDRAW }
        assertEquals("la sombra tiene que ser un trazo más", trazosSinSol + 1, trazosConSol)
    }

    // ---------------------------------------------------------------------
    // El encuadre
    // ---------------------------------------------------------------------

    @Test
    fun `lo que cae fuera del encuadre no se guarda`() {
        val croquis = Croquis(
            trazos = listOf(
                trazo("dentro", Pt3(-20.0, 0.0, 0.0), Pt3(20.0, 0.0, 0.0)),
                // Muy lejos: no cabe en la lámina ni de lejos.
                trazo("lejos", Pt3(90000.0, 0.0, 0.0), Pt3(90100.0, 0.0, 0.0))
            )
        )
        val fuera = proyectar(croquis).filter { it.type == ElementType.FREEDRAW }
        assertEquals(1, fuera.size)
    }
}
