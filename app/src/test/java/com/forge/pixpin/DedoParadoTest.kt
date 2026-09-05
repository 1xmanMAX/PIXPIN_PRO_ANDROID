package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Se para y sale recta; se clava y sale redonda.**
 *
 * Los dos gestos del dedo parado sobre el lienzo plano. Se comprueban aquí y no en la
 * pantalla porque son cuentas: dónde estaba el dedo, cuánto lleva quieto y en qué se
 * convierte lo trazado. Ver [DrawController.latido].
 */
class DedoParadoTest {

    private fun aMano() = DrawController().apply { selectTool(Tool.FREEDRAW) }

    /** Los puntos del trazo que se está haciendo, ya en coordenadas de la escena. */
    private fun DrawController.loQueSeLleva(): List<Pt> =
        absolutePoints(scene.visible.last())

    // ---------------------------------------------------------------------
    // La recta
    // ---------------------------------------------------------------------

    @Test
    fun `una raya torcida se endereza al parar el dedo`() {
        val c = aMano()
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        // Un recorrido con un bulto en medio: a mano nadie traza recto.
        c.pointerMove(Pt(40.0, 12.0), cuando = 100L)
        c.pointerMove(Pt(80.0, -9.0), cuando = 200L)
        c.pointerMove(Pt(120.0, 4.0), cuando = 300L)
        assertTrue("todavía no toca", c.loQueSeLleva().size > 2)

        // El dedo se queda donde está y pasa el tiempo.
        assertTrue(c.latido(300L + ESPERA_PARA_LA_RECTA))

        assertTrue(c.enderezandoSolo)
        val puntos = c.loQueSeLleva()
        assertEquals("una recta son dos puntos", 2, puntos.size)
        assertEquals(0.0, puntos.first().x, 1e-6)
        assertEquals(0.0, puntos.first().y, 1e-6)
        assertEquals(120.0, puntos.last().x, 1e-6)
        assertEquals(4.0, puntos.last().y, 1e-6)

        // **Y es una raya de verdad, la misma que deja la herramienta de línea.** Un
        // elemento de lápiz con dos puntos se le parece y no lo es: no admite el estilo de
        // línea, no engancha flechas y sale de otro grosor. Ver [DrawController.comoUnaRaya].
        val raya = c.scene.visible.last()
        assertEquals(ElementType.LINE, raya.type)
        assertEquals(c.scene.style.strokeWidth, raya.strokeWidth, 1e-9)
        assertEquals(null, raya.pressures)
    }

    @Test
    fun `enderezada, el dedo sigue estirando la raya sin acumular puntos`() {
        val c = aMano()
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        c.pointerMove(Pt(60.0, 20.0), cuando = 100L)
        c.latido(100L + ESPERA_PARA_LA_RECTA)
        assertTrue(c.enderezandoSolo)

        c.pointerMove(Pt(200.0, 90.0), cuando = 900L)
        val puntos = c.loQueSeLleva()
        assertEquals(2, puntos.size)
        assertEquals(200.0, puntos.last().x, 1e-6)
        assertEquals(90.0, puntos.last().y, 1e-6)
    }

    @Test
    fun `el dedo que se mueve no endereza nada`() {
        val c = aMano()
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        // Se mueve más que el temblor en cada muestra: el contador no llega a cumplirse.
        var t = 0L
        var x = 0.0
        repeat(20) {
            t += 100L
            x += TEMBLOR_DEL_DEDO * 3
            c.pointerMove(Pt(x, 0.0), cuando = t)
            assertFalse(c.latido(t))
        }
        assertFalse(c.enderezandoSolo)
    }

    // ---------------------------------------------------------------------
    // El compás
    // ---------------------------------------------------------------------

    @Test
    fun `clavar el dedo sin ir a ningun sitio abre el compas`() {
        val c = aMano()
        c.pointerDown(Pt(50.0, 50.0), cuando = 0L)
        // Un temblor de nada: sigue siendo un toque, no un recorrido.
        c.pointerMove(Pt(52.0, 51.0), cuando = 200L)

        assertTrue(c.latido(ESPERA_PARA_LA_RECTA + 200L))
        assertTrue(c.redondeandoSolo)
        assertFalse(c.enderezandoSolo)

        // Y desde ahí el dedo la abre: un óvalo clavado en el centro y de ese radio.
        c.pointerMove(Pt(50.0, 150.0), cuando = 900L)
        val rueda = c.scene.visible.last()
        // **Un óvalo de verdad, no un polígono de cuarenta y ocho lados.** Es la misma
        // figura que deja la herramienta de círculo. Ver [DrawController.comoUnOvalo].
        assertEquals(ElementType.ELLIPSE, rueda.type)
        assertEquals(null, rueda.points)
        assertEquals(c.scene.style.strokeWidth, rueda.strokeWidth, 1e-9)
        assertEquals("clavado en su centro", -50.0, rueda.x, 1e-6)
        assertEquals(-50.0, rueda.y, 1e-6)
        assertEquals("y abierto hasta el dedo", 200.0, rueda.width, 1e-6)
        assertEquals(200.0, rueda.height, 1e-6)
    }

    /** Abriéndolo más, el óvalo sigue clavado donde se clavó la punta del compás. */
    @Test
    fun `la rueda crece desde su centro`() {
        val c = aMano()
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        c.latido(ESPERA_PARA_LA_RECTA)
        c.pointerMove(Pt(30.0, 0.0), cuando = 800L)
        c.pointerMove(Pt(80.0, 0.0), cuando = 900L)
        val rueda = c.scene.visible.last()
        assertEquals(-80.0, rueda.x, 1e-6)
        assertEquals(-80.0, rueda.y, 1e-6)
        assertEquals(160.0, rueda.width, 1e-6)
        assertEquals(160.0, rueda.height, 1e-6)
        // El centro sigue siendo donde se clavó.
        assertEquals(0.0, rueda.x + rueda.width / 2, 1e-6)
        assertEquals(0.0, rueda.y + rueda.height / 2, 1e-6)
    }

    @Test
    fun `levantar el dedo olvida el gesto`() {
        val c = aMano()
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        c.pointerMove(Pt(70.0, 5.0), cuando = 100L)
        c.latido(100L + ESPERA_PARA_LA_RECTA)
        assertTrue(c.enderezandoSolo)

        c.pointerUp(Pt(70.0, 5.0))
        assertFalse(c.enderezandoSolo)
        assertFalse(c.redondeandoSolo)

        // Y el trazo siguiente nace normal, no enderezado.
        c.pointerDown(Pt(0.0, 200.0), cuando = 2000L)
        c.pointerMove(Pt(30.0, 240.0), cuando = 2100L)
        c.pointerMove(Pt(60.0, 200.0), cuando = 2200L)
        assertTrue(c.loQueSeLleva().size > 2)
    }

    @Test
    fun `el gesto solo es del lapiz, no de las formas`() {
        val c = DrawController().apply { selectTool(Tool.RECTANGLE) }
        c.pointerDown(Pt(0.0, 0.0), cuando = 0L)
        c.pointerMove(Pt(80.0, 40.0), cuando = 100L)
        assertFalse(c.latido(100L + ESPERA_PARA_LA_RECTA))
        assertFalse(c.enderezandoSolo)
    }
}
