package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.OpcionesDeAdjuntar.Adjunto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** **La hoja del clip, como la del «+» del PC** (27-oct-2026). Ver [OpcionesDeAdjuntar]. */
class OpcionesDeAdjuntarTest {

    @Test
    fun `en un proyecto lo de todos los dias va en el orden del PC y nada se pierde`() {
        val h = OpcionesDeAdjuntar.hoja(conProyecto = true, asistente = false)
        assertEquals(
            listOf(Adjunto.FOTO, Adjunto.ARCHIVO, Adjunto.LIENZO, Adjunto.NOTA, Adjunto.TAREAS, Adjunto.CONVERSACION, Adjunto.ESCANEAR, Adjunto.CLASE),
            h.deSiempre
        )
        assertEquals(Adjunto.FIGURA, h.mas.first())
        // Todas las de antes siguen a mano, cada una una vez.
        assertEquals(Adjunto.entries.toSet(), (h.deSiempre + h.mas).toSet())
        assertEquals(Adjunto.entries.size, (h.deSiempre + h.mas).size)
    }

    @Test
    fun `sin proyecto no hay nota ni croquis`() {
        val h = OpcionesDeAdjuntar.hoja(conProyecto = false, asistente = false)
        val todas = h.deSiempre + h.mas
        assertFalse(Adjunto.NOTA in todas)
        assertFalse(Adjunto.CROQUIS in todas)
        // Caso de control: el lienzo sí (va suelto en «Mensajes guardados»).
        assertTrue(Adjunto.LIENZO in h.deSiempre)
    }

    @Test
    fun `en el asistente la figura va primero`() {
        val h = OpcionesDeAdjuntar.hoja(conProyecto = true, asistente = true)
        assertEquals(listOf(Adjunto.FIGURA, Adjunto.FOTO, Adjunto.ARCHIVO, Adjunto.ESCANEAR, Adjunto.LIENZO, Adjunto.NOTA), h.deSiempre)
        assertFalse(Adjunto.FIGURA in h.mas)
        assertEquals(Adjunto.entries.toSet(), (h.deSiempre + h.mas).toSet())
    }

    @Test
    fun `el buscador mira nombres y sinonimos sin tildes`() {
        val h = OpcionesDeAdjuntar.hoja(conProyecto = true, asistente = false)
        assertEquals(listOf(Adjunto.ARCHIVO), OpcionesDeAdjuntar.filtrar(h, "PDF").deSiempre)
        assertEquals(listOf(Adjunto.MINIAPPS), OpcionesDeAdjuntar.filtrar(h, "cronómetro").mas)
        // «pagina» está en «Página de un proyecto» y en «Carpeta de páginas».
        assertEquals(listOf(Adjunto.PAGINA, Adjunto.CARPETA), OpcionesDeAdjuntar.filtrar(h, "pagina").mas)
        // Sin nada escrito, todo; con algo que no está, nada.
        assertEquals(h, OpcionesDeAdjuntar.filtrar(h, "  "))
        val nada = OpcionesDeAdjuntar.filtrar(h, "zzz")
        assertTrue(nada.deSiempre.isEmpty() && nada.mas.isEmpty())
    }

    /**
     * **La cámara del PC ya no está en el clip** (29-oct-2026): va junto al «+» de la lista de
     * chats mientras el PC graba. «cámara» solo encuentra Escanear.
     */
    @Test
    fun `la camara para la reunion del PC ya no esta en el clip`() {
        val h = OpcionesDeAdjuntar.hoja(conProyecto = true, asistente = false)
        val camara = OpcionesDeAdjuntar.filtrar(h, "cámara")
        assertEquals(listOf(Adjunto.ESCANEAR), camara.deSiempre)
        assertTrue(camara.mas.isEmpty())
        assertTrue(OpcionesDeAdjuntar.filtrar(h, "reunión del PC").let { it.deSiempre.isEmpty() && it.mas.isEmpty() })
    }
}
