package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las etiquetas de emoji y el reenvío entre conversaciones. */
class EtiquetasTest {

    private fun m(
        id: String, emoji: String? = null, proyecto: String? = null,
        fijado: Boolean = false, buzon: Boolean = false, responde: String? = null
    ) = Mensaje(
        id = id, cuando = 1L, clase = Clase.NOTA, texto = id, emoji = emoji,
        proyecto = proyecto, fijado = fijado, enBuzon = buzon, respondeA = responde
    )

    @Test
    fun `las etiquetas mas usadas van primero`() {
        val todos = listOf(
            m("a", "⭐"), m("b", "🔥"), m("c", "⭐"), m("d", "🔥"), m("e", "⭐"), m("f", "📌")
        )
        assertEquals(listOf("⭐", "🔥", "📌"), emojisUsados(todos))
    }

    /** Sin etiquetas no hay fila que enseñar, y eso tiene que notarse. */
    @Test
    fun `sin etiquetas la lista sale vacia`() {
        assertTrue(emojisUsados(listOf(m("a"), m("b"))).isEmpty())
    }

    @Test
    fun `filtrar por una etiqueta deja solo los suyos`() {
        val todos = listOf(m("a", "⭐"), m("b", "🔥"), m("c", "⭐"))
        assertEquals(listOf("a", "c"), porEmoji(todos, "⭐").map { it.texto })
    }

    @Test
    fun `sin etiqueta elegida no se filtra nada`() {
        val todos = listOf(m("a", "⭐"), m("b"))
        assertEquals(2, porEmoji(todos, null).size)
    }

    @Test
    fun `reenviar copia y no mueve`() {
        val original = m("a", proyecto = null)
        val copia = reenviado(original, "pr-1", ahora = 99L, idNuevo = "nuevo")
        assertEquals("pr-1", copia.proyecto)
        assertEquals("nuevo", copia.id)
        assertEquals(99L, copia.cuando)
        // El original no se toca: sigue siendo suyo y donde estaba.
        assertEquals("a", original.id)
        assertNull(original.proyecto)
    }

    /** Lo fijado, el buzón y la cita son de la conversación de origen. */
    @Test
    fun `lo que no viaja con el reenvio`() {
        val original = m("a", fijado = true, buzon = true, responde = "otro")
        val copia = reenviado(original, "pr-1", 99L, "nuevo")
        assertTrue(!copia.fijado)
        assertTrue(!copia.enBuzon)
        assertNull(copia.respondeA)
    }

    /** La etiqueta sí viaja: es del contenido, no del sitio. */
    @Test
    fun `la etiqueta viaja con el mensaje`() {
        assertEquals("⭐", reenviado(m("a", "⭐"), "pr-1", 99L, "nuevo").emoji)
    }

    @Test
    fun `reenviar a la general se queda sin proyecto`() {
        assertNull(reenviado(m("a", proyecto = "pr-1"), null, 99L, "nuevo").proyecto)
    }
}
