package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **El historial no salta al subir** (1-nov-2026, `ancla` y `hueco_de_pagina` del PC): la caja que ocupa
 * una foto que aún se lee, y en qué orden se precargan sus medidas.
 */
class HuecoDeLaFotoTest {

    @Test
    fun `la caja es la de la foto encajada sin deformarla`() {
        // Cabe: su medida.
        assertEquals(300 to 200, HuecoDeLaFoto.caja(300, 200, 1000, 1000))
        // Ancha: manda el ancho.
        assertEquals(800 to 400, HuecoDeLaFoto.caja(1600, 800, 800, 1000))
        // Alta (una captura de móvil): manda el alto.
        assertEquals(500 to 1000, HuecoDeLaFoto.caja(900, 1800, 1080, 1000))
        // Caso negativo: sin medida o sin sitio, nada.
        assertEquals(0 to 0, HuecoDeLaFoto.caja(0, 200, 1000, 1000))
        assertEquals(0 to 0, HuecoDeLaFoto.caja(300, 200, 0, 1000))
    }

    @Test
    fun `se precarga primero por el final y sin repetir lo sabido`() {
        val sabidas = setOf("b")
        assertEquals(listOf("d", "c", "a"), HuecoDeLaFoto.enOrdenDePrecarga(listOf("a", "b", "c", "c", "d"), sabida = { it in sabidas }))
    }

    @Test
    fun `lo apuntado se recuerda por su clave y lo raro no`() {
        HuecoDeLaFoto.apuntar("/fotos/x.jpg", 1600, 900)
        assertEquals(1600 to 900, HuecoDeLaFoto.sabida("/fotos/x.jpg"))
        HuecoDeLaFoto.apuntar(HuecoDeLaFoto.deHoja("/a.pdf", 3), 700, 990)
        assertEquals(700 to 990, HuecoDeLaFoto.sabida("/a.pdf#3"))
        // Caso negativo: una medida vacía no se apunta, y otra página es otra clave.
        HuecoDeLaFoto.apuntar("/fotos/rota.jpg", 0, 0)
        assertNull(HuecoDeLaFoto.sabida("/fotos/rota.jpg"))
        assertNull(HuecoDeLaFoto.sabida(HuecoDeLaFoto.deHoja("/a.pdf", 4)))
        assertNull(HuecoDeLaFoto.sabida(null))
    }
}
