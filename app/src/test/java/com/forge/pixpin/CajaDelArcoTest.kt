package com.forge.pixpin.motor

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La caja de un arco es la del **trozo que pinta**, no la del óvalo del que salió.
 *
 * Es lo que se rompía al recortar un círculo: quedaba una uña de trazo arrastrando la
 * caja del círculo entero, así que el recuadro de la selección salía enorme y vacío y
 * encerrar la uña con el dedo no la cogía nunca —el recuadro tenía que contener una caja
 * que no se veía por ningún lado.
 */
class CajaDelArcoTest {

    /** Un arco sobre el círculo de radio 100 centrado en (100, 100). */
    private fun arco(inicio: Double, barrido: Double, angle: Double = 0.0) = Element(
        id = "a", type = ElementType.ARC,
        x = 0.0, y = 0.0, width = 200.0, height = 200.0,
        arcStart = inicio, arcSweep = barrido, angle = angle, seed = 1
    )

    @Test
    fun `la vuelta entera sigue siendo la caja del ovalo`() {
        val b = cajaDelArco(arco(0.0, 2 * PI))
        assertEquals(0.0, b.x1, 1e-6)
        assertEquals(0.0, b.y1, 1e-6)
        assertEquals(200.0, b.x2, 1e-6)
        assertEquals(200.0, b.y2, 1e-6)
    }

    /**
     * El cuadrante de las tres a las seis en punto —en pantalla, la esquina de abajo a
     * la derecha— ocupa exactamente un cuarto de la caja, no la caja entera.
     */
    @Test
    fun `un cuadrante ocupa un cuarto`() {
        val b = cajaDelArco(arco(0.0, PI / 2))
        assertEquals(100.0, b.x1, 1e-6)
        assertEquals(100.0, b.y1, 1e-6)
        assertEquals(200.0, b.x2, 1e-6)
        assertEquals(200.0, b.y2, 1e-6)
    }

    /** Y una uña de arco es una caja diminuta, no uno de doscientos de lado. */
    @Test
    fun `una uña no arrastra el circulo entero`() {
        val b = cajaDelArco(arco(0.0, 0.1))
        assertTrue("ancho $b", b.width < 5.0)
        assertTrue("alto $b", b.height < 15.0)
    }

    /**
     * Los extremos del óvalo cuentan aunque no sean puntas: un arco que pasa por las
     * doce en punto llega hasta arriba del todo, esté donde esté su principio.
     */
    @Test
    fun `el extremo por el que pasa entra en la caja`() {
        // De las nueve a las tres pasando por arriba: −π a 0 en el sentido de la pantalla.
        val b = cajaDelArco(arco(-PI, PI))
        assertEquals(0.0, b.x1, 1e-6)
        assertEquals(0.0, b.y1, 1e-6)
        assertEquals(200.0, b.x2, 1e-6)
        assertEquals(100.0, b.y2, 1e-6)
    }

    /** Girado, la envoltura crece pero sigue siendo la del trozo. */
    @Test
    fun `la envoltura de un arco girado no es la del ovalo`() {
        val e = arco(0.0, PI / 2, angle = PI / 4)
        val b = envolturaVisible(e)
        assertTrue("$b", b.width < 160.0 && b.height < 160.0)
    }

    // ---- Estirarlo por esa caja ----

    /**
     * Arrastrando la esquina de abajo a la derecha al doble, la uña dobla su tamaño y
     * **la esquina de arriba a la izquierda se queda clavada**, que es la regla de
     * cualquier redimensionado. El óvalo de debajo crece con ella.
     */
    @Test
    fun `estirar un cuadrante deja el ancla quieta`() {
        val e = arco(0.0, PI / 2)
        val antes = cajaDelArco(e)
        val estirado = resizeSingleElement(
            e, HandleType.SE, Pt(antes.x1 + antes.width * 2, antes.y1 + antes.height * 2)
        )
        val ahora = cajaDelArco(estirado)
        assertEquals(antes.x1, ahora.x1, 1e-6)
        assertEquals(antes.y1, ahora.y1, 1e-6)
        assertEquals(antes.width * 2, ahora.width, 1e-6)
        assertEquals(antes.height * 2, ahora.height, 1e-6)
    }

    /** Y sigue siendo el mismo trozo de arco: el barrido no se toca al estirar. */
    @Test
    fun `estirar no cambia el barrido`() {
        val e = arco(0.3, 1.1)
        val estirado = resizeSingleElement(e, HandleType.SE, Pt(400.0, 400.0))
        assertEquals(0.3, estirado.arcStart!!, 1e-9)
        assertEquals(1.1, estirado.arcSweep!!, 1e-9)
    }

    /**
     * Y el recuadro con el dedo lo coge: antes había que encerrar el círculo entero
     * —doscientos de lado— para llevarse una uña de diez.
     */
    @Test
    fun `encerrar la uña con el recuadro la coge`() {
        val e = arco(0.0, 0.2)
        val b = cajaDelArco(e)
        val recuadro = Bounds(b.x1 - 5, b.y1 - 5, b.x2 + 5, b.y2 + 5)
        assertTrue(getElementsWithinSelection(listOf(e), recuadro).contains(e))
    }

    /** Y el dedo en mitad del círculo, donde no hay nada pintado, no lo agarra. */
    @Test
    fun `el centro del circulo ya no coge el arco`() {
        val e = arco(0.0, 0.2)
        assertFalse(hitElementItself(Pt(100.0, 100.0), e, 10.0))
    }
}
