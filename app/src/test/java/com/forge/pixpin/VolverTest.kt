package com.forge.pixpin

import com.forge.pixpin.Volver.Destino
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Atrás sigue la pila natural**: visor → el chat de donde se abrió → la lista de chats → fuera
 * (26-oct-2026, el usuario: «al retroceder en el lienzo se cierra la app»). Ver [Volver].
 */
class VolverTest {

    @Test
    fun `sin ser la raiz de su tarea, lo de debajo ya es de donde se vino`() {
        // Un visor apilado sobre el chat: `finish()` a secas deja en el chat.
        assertEquals(Destino.Quedarse, Volver.adonde(esRaiz = false, desdeChat = "p1", desdeProyecto = null, desdeDentro = true))
        assertEquals(Destino.Quedarse, Volver.adonde(esRaiz = false, desdeChat = null, desdeProyecto = "p1", desdeDentro = true))
    }

    @Test
    fun `el lienzo abierto desde un chat vuelve a ese chat`() {
        assertEquals(Destino.AlChat("p1"), Volver.adonde(esRaiz = true, desdeChat = "p1", desdeProyecto = null, desdeDentro = true))
        assertEquals(Destino.AlChat(CHAT_GENERAL), Volver.adonde(esRaiz = true, desdeChat = CHAT_GENERAL, desdeProyecto = null, desdeDentro = true))
    }

    @Test
    fun `el chat del que se vino manda sobre el proyecto de la hoja`() {
        // Una página del proyecto A adjunta en el chat B: se vuelve a B, que es donde se estaba.
        assertEquals(Destino.AlChat("b"), Volver.adonde(esRaiz = true, desdeChat = "b", desdeProyecto = "a", desdeDentro = true))
    }

    @Test
    fun `sin chat, el proyecto de siempre es su chat`() {
        // Un atajo o la rueda de lo abierto: traen el proyecto (EXTRA_DESDE_PROYECTO), que es un chat.
        assertEquals(Destino.AlChat("a"), Volver.adonde(esRaiz = true, desdeChat = null, desdeProyecto = "a", desdeDentro = false))
    }

    @Test
    fun `de dentro pero sin chat ni proyecto, a la lista de chats`() {
        // El lienzo de la bola o de Funciones; y el vacío de antes, que era «la lista».
        assertEquals(Destino.ALaLista, Volver.adonde(esRaiz = true, desdeChat = null, desdeProyecto = null, desdeDentro = true))
        assertEquals(Destino.ALaLista, Volver.adonde(esRaiz = true, desdeChat = null, desdeProyecto = "", desdeDentro = false))
        assertEquals(Destino.ALaLista, Volver.adonde(esRaiz = true, desdeChat = " ", desdeProyecto = null, desdeDentro = true))
    }

    @Test
    fun `lo que llega de fuera vuelve a quien lo mando`() {
        assertEquals(Destino.Quedarse, Volver.adonde(esRaiz = true, desdeChat = null, desdeProyecto = null, desdeDentro = false))
    }

    @Test
    fun `un visor desde una pantalla se apila sobre ella`() {
        assertFalse(Volver.conLaListaDebajo(desdeUnaPantalla = true, raicesVivas = emptyList()))
    }

    @Test
    fun `desde un pin con la aplicacion abierta va a su tarea`() {
        assertFalse(Volver.conLaListaDebajo(false, listOf(MainActivity::class.java.name)))
        assertFalse(Volver.conLaListaDebajo(false, listOf("otra.Cosa", com.forge.pixpin.guardados.MensajesActivity::class.java.name)))
    }

    @Test
    fun `desde un pin sin la aplicacion abierta, con la lista debajo`() {
        assertTrue(Volver.conLaListaDebajo(false, emptyList()))
        // Solo un lienzo en su tarea (`DrawEditorActivity`) no es la tarea de la aplicación.
        assertTrue(Volver.conLaListaDebajo(false, listOf(com.forge.pixpin.motor.DrawEditorActivity::class.java.name, null)))
    }

    @Test
    fun `lo abierto desde la busqueda de la lista vuelve a la lista`() {
        // 27-oct-2026: un PDF abierto directamente desde la búsqueda. La marca no es un chat.
        assertEquals(Destino.ALaLista, Volver.adonde(esRaiz = true, desdeChat = Volver.DESDE_LA_LISTA, desdeProyecto = null, desdeDentro = true))
        // Ni aunque traiga el proyecto de su hoja: se vino de la lista, no de ese chat.
        assertEquals(Destino.ALaLista, Volver.adonde(esRaiz = true, desdeChat = Volver.DESDE_LA_LISTA, desdeProyecto = "a", desdeDentro = true))
        // Apilado encima de la lista, `finish()` ya deja en ella.
        assertEquals(Destino.Quedarse, Volver.adonde(esRaiz = false, desdeChat = Volver.DESDE_LA_LISTA, desdeProyecto = null, desdeDentro = true))
    }
}
