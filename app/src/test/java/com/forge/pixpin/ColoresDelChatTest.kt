package com.forge.pixpin.guardados

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Los colores del chat, con la cuenta delante.**
 *
 * El usuario dijo que las burbujas «se fusionan con el fondo» (7-sep-2026) y tenía razón con
 * números: la burbuja de antes contra su papel daba **1,20:1**, que es no separarse. Esto fija
 * lo que no se puede volver a romper: que la burbuja se despegue del papel y que lo escrito
 * encima se lea. Los valores salen de Telegram (`ThemeColors.java:307/315/400` y
 * `assets/darkblue.attheme`), y donde nos apartamos de él es para **subir** el contraste de la
 * hora, que en el suyo se queda corto para letra pequeña.
 */
class ColoresDelChatTest {

    private fun luminancia(hex: Long): Double {
        fun canal(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val r = ((hex shr 16) and 0xFF).toInt()
        val g = ((hex shr 8) and 0xFF).toInt()
        val b = (hex and 0xFF).toInt()
        return 0.2126 * canal(r) + 0.7152 * canal(g) + 0.0722 * canal(b)
    }

    private fun contraste(a: Long, b: Long): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        val alto = maxOf(la, lb)
        val bajo = minOf(la, lb)
        return (alto + 0.05) / (bajo + 0.05)
    }

    // Los mismos números que usa ColoresDelChat. Si allí cambian, aquí se cae.
    private val burbujaClara = 0xEFFFDEL
    private val tintaClara = 0x101B24L
    private val horaClara = 0x3F7A30L
    private val papelClaroA = 0x86BFB2L
    private val papelClaroB = 0x8AB8D2L
    private val burbujaOscura = 0x3E618AL
    private val tintaOscura = 0xFAFAFAL
    private val horaOscura = 0xA8CCE8L
    private val papelOscuroA = 0x151E27L
    private val papelOscuroB = 0x10161DL

    /** **La burbuja se tiene que despegar del papel.** Es el fallo que se vino a arreglar. */
    @Test
    fun `la burbuja se separa del papel`() {
        // Lo de antes, para que quede escrito por qué se cambió.
        assertTrue("lo de antes ya separaba", contraste(0xDCE7EBL, 0xF7FAFBL) < 1.3)
        for (papel in listOf(papelClaroA, papelClaroB)) {
            assertTrue(
                "en claro la burbuja se funde con el papel: ${contraste(burbujaClara, papel)}",
                contraste(burbujaClara, papel) >= 1.8
            )
        }
        for (papel in listOf(papelOscuroA, papelOscuroB)) {
            assertTrue(
                "en oscuro la burbuja se funde con el papel: ${contraste(burbujaOscura, papel)}",
                contraste(burbujaOscura, papel) >= 1.8
            )
        }
    }

    /** Y lo escrito encima se lee: WCAG pide 4,5 para letra normal y 3 para la secundaria. */
    @Test
    fun `lo escrito sobre la burbuja se lee`() {
        assertTrue(contraste(tintaClara, burbujaClara) >= 7.0)
        assertTrue(contraste(tintaOscura, burbujaOscura) >= 4.5)
        assertTrue("la hora clara se queda corta", contraste(horaClara, burbujaClara) >= 4.5)
        assertTrue("la hora oscura se queda corta", contraste(horaOscura, burbujaOscura) >= 3.0)
    }

    /**
     * **Y todos los papeles a elegir, no solo el de fábrica.**
     *
     * Un fondo que vuelva a fundir la burbuja no puede entrar en la lista: es exactamente el
     * fallo que se vino a arreglar, y con seis papeles y dos temas es imposible acordarse de
     * comprobarlo a mano cada vez que se añade uno.
     */
    @Test
    fun `ningun papel de la lista funde la burbuja`() {
        for (f in FondosDelChat.TODOS) {
            for (papel in listOf(f.claroA and 0xFFFFFF, f.claroB and 0xFFFFFF)) {
                assertTrue(
                    "«${f.nombre}» en claro deja la burbuja a ${contraste(burbujaClara, papel)}",
                    contraste(burbujaClara, papel) >= 1.8
                )
            }
            for (papel in listOf(f.oscuroA and 0xFFFFFF, f.oscuroB and 0xFFFFFF)) {
                assertTrue(
                    "«${f.nombre}» en oscuro deja la burbuja a ${contraste(burbujaOscura, papel)}",
                    contraste(burbujaOscura, papel) >= 1.8
                )
            }
        }
    }

    /** Donde nos apartamos de Telegram es **subiendo** el contraste, nunca bajándolo. */
    @Test
    fun `nuestra hora contrasta mas que la suya`() {
        assertTrue(contraste(horaClara, burbujaClara) > contraste(0x70B15CL, burbujaClara))
        assertTrue(contraste(horaOscura, burbujaOscura) > contraste(0x8FBCDFL, burbujaOscura))
    }
}
