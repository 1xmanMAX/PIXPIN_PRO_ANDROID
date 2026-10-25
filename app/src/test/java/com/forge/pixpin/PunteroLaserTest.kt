package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El puntero láser** (25-oct-2026): la estela se ve mientras se arrastra, se apaga en un
 * segundo y no deja nada en el dibujo ni en el historial. Las mismas cuentas que la prueba de
 * `puntero_laser.rs` del PC.
 */
class PunteroLaserTest {

    @Test
    fun laEstelaSeVeMientrasSeArrastraYSeApagaEnUnSegundo() {
        val l = PunteroLaser()
        l.pulsar(Pt(0.0, 0.0), 0)
        for (i in 1 until 20) l.mover(Pt(i * 5.0, 0.0), i * 10L)
        assertTrue(l.vivo(200))
        assertTrue(l.tramos(200).isNotEmpty())
        assertNotNull("la punta, con el dedo puesto", l.punta())
        l.soltar(200)
        assertTrue("recién soltado todavía se ve", l.vivo(900))
        assertNull(l.punta())
        // La cola está más apagada que la cabeza.
        val t = l.tramos(900)
        assertTrue(t.first().grueso < t.last().grueso)
        // Pasado el segundo del último punto, nada.
        assertFalse(l.vivo(1300))
        l.soltar(1300)
        assertTrue(l.tramos(1300).isEmpty())
    }

    @Test
    fun sinElDedoApoyadoMoverNoHaceNada() {
        val l = PunteroLaser()
        assertFalse(l.mover(Pt(1.0, 1.0), 0))
        assertFalse(l.vivo(0))
        assertEquals(1.0, PunteroLaser.suave(1.0), 0.0)
        assertEquals(0.0, PunteroLaser.suave(0.0), 0.0)
    }

    @Test
    fun conLaHerramientaNoSeTocaLaEscenaNiElHistorial() {
        val c = DrawController()
        c.selectTool(Tool.LASER)
        c.pointerDown(Pt(10.0, 10.0))
        c.pointerMove(Pt(60.0, 40.0))
        c.pointerMove(Pt(120.0, 80.0))
        assertTrue(c.punteroLaser.vivo(PunteroLaser.ahora()))
        c.pointerUp(Pt(120.0, 80.0))
        assertTrue(c.scene.elements.isEmpty())
        assertFalse(c.canUndo)
        // Cambiar de herramienta la apaga del todo.
        c.selectTool(Tool.SELECTION)
        assertFalse(c.punteroLaser.vivo(PunteroLaser.ahora()))
    }
}
