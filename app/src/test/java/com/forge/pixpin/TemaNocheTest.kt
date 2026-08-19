package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El modo noche: **la tinta se da la vuelta, el color solo se aclara**.
 *
 * Lo que se comprueba aquí es lo que se veía mal: que el negro salía plomo, y
 * que los colores pasaban por una fórmula de giro de tono que no tenía en cuenta
 * si el resultado se leía o no sobre un fondo oscuro.
 */
class TemaNocheTest {

    // A pelo, sin `android.graphics.Color`: el filtro vive fuera de Android
    // justamente para poder comprobarlo aquí.
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun rojoDe(c: Int) = (c shr 16) and 0xFF
    private fun verdeDe(c: Int) = (c shr 8) and 0xFF
    private fun azulDe(c: Int) = c and 0xFF

    /** De día no se toca nada: el dibujo es el que es. */
    @Test
    fun `de dia el color es el mismo`() {
        val negro = rgb(0, 0, 0)
        assertEquals(negro, DrawTheme.filtrar(negro, noche = false))
    }

    /** **El negro es blanco.** Era el que salía plomo. */
    @Test
    fun `el negro se vuelve blanco`() {
        val c = DrawTheme.filtrar(rgb(0, 0, 0), noche = true)
        assertEquals(255, rojoDe(c))
        assertEquals(255, verdeDe(c))
        assertEquals(255, azulDe(c))
    }

    /** Y el blanco, negro: es tinta pensada para papel. */
    @Test
    fun `el blanco se vuelve negro`() {
        val c = DrawTheme.filtrar(rgb(255, 255, 255), noche = true)
        assertEquals(0, rojoDe(c))
    }

    /** Un rojo sigue siendo rojo, **no cian ni rosa**: solo se aclara. */
    @Test
    fun `un rojo sigue siendo rojo`() {
        val c = DrawTheme.filtrar(rgb(200, 30, 30), noche = true)
        assertTrue("ha dejado de ser rojo", rojoDe(c) > verdeDe(c) + 40)
        assertTrue("ha dejado de ser rojo", rojoDe(c) > azulDe(c) + 40)
        // Y se lee sobre oscuro: más claro que el original.
        assertTrue("no se ha aclarado", rojoDe(c) + verdeDe(c) + azulDe(c) > 200 + 30 + 30)
    }

    /** Un azul oscuro también se aclara, que si no no se ve. */
    @Test
    fun `un azul oscuro se aclara`() {
        val c = DrawTheme.filtrar(rgb(20, 30, 120), noche = true)
        assertTrue("sigue siendo ilegible", azulDe(c) > 150)
        assertTrue("ha dejado de ser azul", azulDe(c) > rojoDe(c) + 40)
    }

    /** La transparencia se respeta: es del dibujo, no del modo. */
    @Test
    fun `el alfa no se toca`() {
        val c = DrawTheme.filtrar((128 shl 24) or (10 shl 16) or (10 shl 8) or 10, noche = true)
        assertEquals(128, (c ushr 24) and 0xFF)
    }
}
