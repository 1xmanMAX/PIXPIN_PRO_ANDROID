package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.PosicionDelChat.Accion
import com.forge.pixpin.guardados.PosicionDelChat.AlAbrir
import com.forge.pixpin.guardados.PosicionDelChat.Foto
import com.forge.pixpin.guardados.PosicionDelChat.Guardada
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **Dónde se abre cada chat y cuándo sigue abajo** (27-oct-2026): «configura todos los chats para que
 * siempre aparezcan en el último mensaje o donde lo dejaste». Ver [PosicionDelChat].
 */
class PosicionDelChatTest {

    private val ids = listOf("a", "b", "c", "d")

    @Test
    fun `sin nada guardado o dejado abajo se abre abajo`() {
        assertEquals(AlAbrir.Abajo, PosicionDelChat.alAbrir(null, ids))
        assertEquals(AlAbrir.Abajo, PosicionDelChat.alAbrir(Guardada(null, 0, "d"), ids))
    }

    @Test
    fun `dejado subido se vuelve a ese mensaje y a ese punto`() {
        assertEquals(AlAbrir.En("b", 120), PosicionDelChat.alAbrir(Guardada("b", 120, "c"), ids))
        // Un desplazamiento negativo (el mensaje empezaba por debajo del borde) se queda en 0.
        assertEquals(AlAbrir.En("a", 0), PosicionDelChat.alAbrir(Guardada("a", -40, null), ids))
    }

    @Test
    fun `si aquel mensaje ya no esta o es el ultimo se abre abajo`() {
        // Borrado, o quitado por una sincronización: mejor el final que un sitio cualquiera.
        assertEquals(AlAbrir.Abajo, PosicionDelChat.alAbrir(Guardada("x", 50, "c"), ids))
        assertEquals(AlAbrir.Abajo, PosicionDelChat.alAbrir(Guardada("d", 50, "d"), ids))
        assertEquals(AlAbrir.Abajo, PosicionDelChat.alAbrir(Guardada("a", 50, null), emptyList()))
    }

    @Test
    fun `lo no visto es lo que hay detras de lo ultimo que se vio`() {
        assertEquals(2, PosicionDelChat.sinLeer(ids, "b"))
        assertEquals(0, PosicionDelChat.sinLeer(ids, "d"))
        // Casos negativos: sin saberlo, o borrado, la chapa no se inventa un número.
        assertEquals(0, PosicionDelChat.sinLeer(ids, null))
        assertEquals(0, PosicionDelChat.sinLeer(ids, "x"))
    }

    @Test
    fun `lo visto solo avanza`() {
        val indice = ids.withIndex().associate { it.value to it.index }
        assertEquals("c", PosicionDelChat.masAvanzado(indice, "b", "c"))
        assertEquals("c", PosicionDelChat.masAvanzado(indice, "c", "a"))
        assertEquals("b", PosicionDelChat.masAvanzado(indice, null, "b"))
        assertEquals("b", PosicionDelChat.masAvanzado(indice, "x", "b"))
        assertNull(PosicionDelChat.masAvanzado(indice, "x", null))
    }

    private fun foto(filas: Int, ultima: String?, alto: Int, pie: Int = 100, abajo: Boolean) = Foto(filas, ultima, alto, pie, abajo)

    @Test
    fun `abajo y llega un mensaje se sigue abajo`() {
        val antes = foto(10, "m9", 80, abajo = true)
        // La última nueva queda por debajo del borde: la anterior se ve, la nueva aún no.
        assertEquals(Accion.BAJAR, PosicionDelChat.queHacer(antes, foto(11, null, -1, abajo = false), pegado = true))
        // Subido a leer: lo nuevo no lo mueve.
        assertEquals(Accion.NADA, PosicionDelChat.queHacer(antes, foto(11, null, -1, abajo = false), pegado = false))
        // Si cabe sin moverse, no hay nada que hacer.
        assertEquals(Accion.NADA, PosicionDelChat.queHacer(antes, foto(11, "m10", 80, abajo = true), pegado = true))
    }

    @Test
    fun `la respuesta que crece y el pie que cambia arrastran si se estaba abajo`() {
        val antes = foto(10, "m9", 80, abajo = true)
        assertEquals(Accion.BAJAR, PosicionDelChat.queHacer(antes, foto(10, "m9", 400, abajo = false), pegado = true))
        assertEquals(Accion.BAJAR, PosicionDelChat.queHacer(antes, foto(10, "m9", 80, pie = 380, abajo = false), pegado = true))
        assertEquals(Accion.NADA, PosicionDelChat.queHacer(antes, foto(10, "m9", 400, abajo = false), pegado = false))
    }

    @Test
    fun `desplazarse apunta si se esta abajo`() {
        val abajo = foto(10, "m9", 80, abajo = true)
        // La última sale de la pantalla al subir (-1): es desplazarse, no crecer.
        assertEquals(Accion.APUNTAR, PosicionDelChat.queHacer(abajo, foto(10, "m9", -1, abajo = false), pegado = true))
        // Y vuelve a entrar al bajar.
        assertEquals(Accion.APUNTAR, PosicionDelChat.queHacer(foto(10, "m9", -1, abajo = false), abajo, pegado = false))
        assertEquals(Accion.APUNTAR, PosicionDelChat.queHacer(null, abajo, pegado = false))
    }

    @Test
    fun `buscar o ver en contexto no baja`() {
        val todo = foto(40, "m39", 80, abajo = true)
        // La búsqueda deja menos filas: ni bajar ni cambiar lo apuntado.
        assertEquals(Accion.NADA, PosicionDelChat.queHacer(todo, foto(3, "m12", 80, abajo = false), pegado = true))
        // Quitar la búsqueda con la misma última y más filas tampoco es «ha llegado algo».
        assertEquals(Accion.NADA, PosicionDelChat.queHacer(foto(3, "m39", 80, abajo = true), foto(40, "m39", 80, abajo = false), pegado = true))
    }

    @Test
    fun `lo guardado va y vuelve como texto`() {
        val g = Guardada("msg-1", 37, "msg-9")
        assertEquals(g, PosicionDelChat.deTexto(PosicionDelChat.aTexto(g)))
        val abajo = Guardada(null, 0, null)
        assertEquals(abajo, PosicionDelChat.deTexto(PosicionDelChat.aTexto(abajo)))
        assertNull(PosicionDelChat.deTexto(null))
        assertNull(PosicionDelChat.deTexto("basura"))
        assertNull(PosicionDelChat.deTexto("a\tno-numero\tb"))
    }
}
