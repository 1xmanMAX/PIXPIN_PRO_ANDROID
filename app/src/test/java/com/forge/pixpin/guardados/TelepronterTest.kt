package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Test

/** El texto del teleprónter: párrafos limpios, sin lo que no se lee. */
class TelepronterTest {
    @Test
    fun `parrafos sin marcas de Markdown`() {
        val md = "# Título\n\n![audio](voz.m4a)\n\nHola **a todos**, `hoy`\nseguimos.\n\n\n- punto uno\n\n   \n> cita"
        assertEquals(listOf("Título", "Hola a todos, hoy seguimos.", "punto uno", "cita"), TelepronterActivity.parrafosDe(md))
        assertEquals(emptyList<String>(), TelepronterActivity.parrafosDe("  \n\n "))
    }
}
