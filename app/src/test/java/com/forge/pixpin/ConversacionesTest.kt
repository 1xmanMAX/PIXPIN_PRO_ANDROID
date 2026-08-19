package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cómo se ordena la lista de conversaciones, que es lo que se copia de Telegram. */
class ConversacionesTest {

    private fun m(id: String, cuando: Long, proyecto: String? = null, buzon: Boolean = false) =
        Mensaje(
            id = id, cuando = cuando, clase = Clase.NOTA, texto = id,
            proyecto = proyecto, enBuzon = buzon
        )

    private val nombres = mapOf("pr-1" to "Reforma", "pr-2" to "Tesis")

    @Test
    fun `la general va primero aunque lleve tiempo sin tocarse`() {
        val lista = conversaciones(
            listOf(m("vieja", 10L), m("nueva", 500L, "pr-1")), nombres, "Guardados"
        )
        assertNull(lista.first().proyecto)
        assertEquals("Guardados", lista.first().nombre)
    }

    @Test
    fun `el resto se ordena por lo mas reciente`() {
        val lista = conversaciones(
            listOf(m("a", 100L, "pr-1"), m("b", 900L, "pr-2")), nombres, "Guardados"
        )
        assertEquals(listOf("Tesis", "Reforma"), lista.drop(1).map { it.nombre })
    }

    @Test
    fun `un proyecto sin mensajes tambien sale`() {
        val lista = conversaciones(listOf(m("a", 100L, "pr-1")), nombres, "Guardados")
        val tesis = lista.first { it.nombre == "Tesis" }
        assertEquals(0, tesis.cuantos)
        assertNull(tesis.ultimo)
    }

    /** Lo del buzón no cuenta: está a punto de irse solo y no es actividad de nadie. */
    @Test
    fun `lo del buzon no cuenta ni ordena`() {
        val lista = conversaciones(
            listOf(m("a", 100L, "pr-1"), m("b", 9_000L, "pr-1", buzon = true)),
            nombres, "Guardados"
        )
        val reforma = lista.first { it.nombre == "Reforma" }
        assertEquals(1, reforma.cuantos)
        assertEquals(100L, reforma.cuando)
    }

    /** Un proyecto borrado que dejó mensajes no puede llevarse lo escrito por delante. */
    @Test
    fun `los mensajes de un proyecto que ya no esta siguen teniendo conversacion`() {
        val lista = conversaciones(listOf(m("a", 100L, "pr-borrado")), emptyMap(), "Guardados")
        assertEquals("pr-borrado", lista.drop(1).single().nombre)
        assertEquals(1, lista.drop(1).single().cuantos)
    }

    @Test
    fun `el ultimo mensaje de cada una es el mas nuevo`() {
        val lista = conversaciones(
            listOf(m("viejo", 1L, "pr-1"), m("nuevo", 99L, "pr-1")), nombres, "Guardados"
        )
        assertEquals("nuevo", lista.first { it.nombre == "Reforma" }.ultimo?.texto)
    }

    @Test
    fun `sin nada de nada sigue estando la general`() {
        val lista = conversaciones(emptyList(), emptyMap(), "Guardados")
        assertEquals(1, lista.size)
        assertEquals(0, lista.single().cuantos)
    }
}
