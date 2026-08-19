package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Que lo de cada conversación se quede en la suya.
 *
 * El fallo que esto vigila no da error: da una nota de la reforma apareciendo entre la
 * lista de la compra, o —peor— una búsqueda en el proyecto que saca cosas de otro. Y el
 * caso importante es el de los mensajes de antes de que las conversaciones existieran,
 * que tienen que seguir donde estaban.
 */
class ChatDeProyectoTest {

    private fun m(id: String, proyecto: String? = null) = Mensaje(
        id = id, cuando = 0L, clase = Clase.NOTA, texto = id, proyecto = proyecto
    )

    @Test
    fun `cada conversacion se queda con lo suyo`() {
        val todos = listOf(m("a"), m("b", "pr-1"), m("c", "pr-2"), m("d", "pr-1"))
        assertEquals(listOf("b", "d"), delChat(todos, "pr-1").map { it.texto })
        assertEquals(listOf("c"), delChat(todos, "pr-2").map { it.texto })
    }

    /** Lo guardado antes de que esto existiera no lleva proyecto: es de la general. */
    @Test
    fun `lo de antes sigue en la general`() {
        val todos = listOf(m("viejo"), m("delProyecto", "pr-1"))
        assertEquals(listOf("viejo"), delChat(todos, null).map { it.texto })
    }

    @Test
    fun `una conversacion sin nada no saca nada de otra`() {
        val todos = listOf(m("a"), m("b", "pr-1"))
        assertTrue(delChat(todos, "pr-9").isEmpty())
    }

    /** Las secciones cuelgan de la conversación, no al revés. */
    @Test
    fun `las secciones se calculan dentro de su conversacion`() {
        val todos = listOf(
            m("nota general"),
            m("nota del proyecto", "pr-1"),
            Mensaje(id = "f", cuando = 0L, clase = Clase.NOTA, texto = "fijada", fijado = true)
        )
        val delProyecto = deSeccion(delChat(todos, "pr-1"), Seccion.FIJADOS)
        assertTrue(delProyecto.isEmpty())
        assertEquals(1, deSeccion(delChat(todos, null), Seccion.FIJADOS).size)
    }
}
