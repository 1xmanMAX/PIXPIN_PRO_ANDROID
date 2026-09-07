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

/**
 * **El minuto de un párrafo de una transcripción**, que es lo que deja tocarlo para saltar
 * ahí dentro de la nota. Lo pidió el usuario (7-sep-2026): que el audio y los minutos sirvan
 * también en el Markdown y no solo en la pantalla de la letra.
 */
class MinutoDelParrafoTest {
    @Test
    fun `se lee el minuto con el que empieza un parrafo`() {
        assertEquals(0, com.forge.pixpin.motormd.minutoDelParrafo("[0:00] llama al taller"))
        assertEquals(83_000, com.forge.pixpin.motormd.minutoDelParrafo("[1:23] lo que se dijo"))
        assertEquals(3_725_000, com.forge.pixpin.motormd.minutoDelParrafo("[1:02:05] al final"))
        // Con el prefijo de un nombre delante ya no es un salto: el minuto va el primero.
        assertEquals(-1, com.forge.pixpin.motormd.minutoDelParrafo("**Ana:** [1:23] hola"))
        assertEquals(-1, com.forge.pixpin.motormd.minutoDelParrafo("un párrafo normal"))
        assertEquals(-1, com.forge.pixpin.motormd.minutoDelParrafo("[algo] entre corchetes"))
        assertEquals(-1, com.forge.pixpin.motormd.minutoDelParrafo(""))
    }
}
