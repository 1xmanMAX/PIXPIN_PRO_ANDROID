package com.forge.pixpin.pin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El hueco que la ventana le deja a lo que se dibuja FUERA del recuadro del pin:
 * la sombra y la pegatina. Una ventana overlay recorta todo lo que salga de sus
 * límites, así que si esta cuenta no cuadra, la sombra sale cortada por el borde
 * o el pin da un salto al ponerle un emoji.
 */
class PinChromeTest {

    @Test
    fun `sin emoji solo hace falta hueco para la sombra`() {
        val i = PinChrome.insetsFor(hasEmoji = false)
        assertEquals(PinChrome.SHADOW_DP, i.left)
        assertEquals(PinChrome.SHADOW_DP, i.top)
        assertEquals(PinChrome.SHADOW_DP, i.right)
        assertEquals(PinChrome.SHADOW_DP, i.bottom)
    }

    /** La pegatina va arriba a la derecha, así que solo esos dos lados crecen. */
    @Test
    fun `con emoji crecen arriba y a la derecha`() {
        val i = PinChrome.insetsFor(hasEmoji = true)
        assertEquals(PinChrome.SHADOW_DP, i.left)
        assertEquals(PinChrome.SHADOW_DP + PinChrome.STICKER_INSET_DP, i.top)
        assertEquals(PinChrome.SHADOW_DP + PinChrome.STICKER_INSET_DP, i.right)
        assertEquals(PinChrome.SHADOW_DP, i.bottom)
    }

    @Test
    fun `el ancho y el alto extra son la suma de los lados opuestos`() {
        val i = PinChrome.insetsFor(hasEmoji = true)
        assertEquals(i.left + i.right, i.horizontal)
        assertEquals(i.top + i.bottom, i.vertical)
    }

    /**
     * La ventana se coloca por su esquina superior izquierda, así que al aparecer
     * el hueco hay que retroceder justo esos dos lados para que el contenido se
     * quede donde estaba.
     */
    @Test
    fun `poner el emoji desplaza la ventana lo que crece por arriba y por la izquierda`() {
        val sin = PinChrome.insetsFor(hasEmoji = false)
        val con = PinChrome.insetsFor(hasEmoji = true)
        assertEquals(0, con.left - sin.left)
        assertEquals(PinChrome.STICKER_INSET_DP, con.top - sin.top)
    }
}
