package com.forge.pixpin.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGroupsTest {

    private fun pin(id: String, x: Int = 0, y: Int = 0, group: String? = null) =
        PinState(id = id, type = PinType.IMAGE, x = x, y = y, groupId = group)

    @Test
    fun `el grupo se mueve conservando las distancias`() {
        // B y C están a distinta distancia de A, que es el que se arrastra.
        val anchors = mapOf("b" to (100 to 200), "c" to (400 to 50))
        val moved = PinGroups.followPositions(anchors, dx = 30, dy = -70)
        assertEquals(130 to 130, moved["b"])
        assertEquals(430 to -20, moved["c"])
    }

    /**
     * Se calcula siempre desde la posición de arranque y el desplazamiento
     * total: si se acumulasen incrementos, un evento perdido desalinearía el
     * grupo para siempre.
     */
    @Test
    fun `el desplazamiento es absoluto, no acumulativo`() {
        val anchors = mapOf("b" to (100 to 100))
        PinGroups.followPositions(anchors, 10, 10)
        PinGroups.followPositions(anchors, 20, 20)
        assertEquals(130 to 130, PinGroups.followPositions(anchors, 30, 30)["b"])
    }

    @Test
    fun `agrupar solo marca a los elegidos`() {
        val pins = listOf(pin("a"), pin("b"), pin("c"))
        val result = PinGroups.assign(pins, setOf("a", "c"), "g1")
        assertEquals("g1", result[0].groupId)
        assertNull(result[1].groupId)
        assertEquals("g1", result[2].groupId)
    }

    @Test
    fun `desagrupar borra la pertenencia`() {
        val pins = listOf(pin("a", group = "g1"), pin("b", group = "g1"))
        val result = PinGroups.assign(pins, setOf("a", "b"), null)
        assertTrue(result.all { it.groupId == null })
    }

    @Test
    fun `los miembros son los del mismo grupo`() {
        val pins = listOf(
            pin("a", group = "g1"), pin("b", group = "g2"), pin("c", group = "g1")
        )
        assertEquals(listOf("a", "c"), PinGroups.membersOf(pins, "g1").map { it.id })
        assertEquals(emptyList<PinState>(), PinGroups.membersOf(pins, null))
    }

    @Test
    fun `hace falta mas de un pin para formar grupo`() {
        assertFalse(PinGroups.canGroup(listOf(pin("a"))))
        assertTrue(PinGroups.canGroup(listOf(pin("a"), pin("b"))))
    }

    @Test
    fun `desagrupar se ofrece si alguno ya pertenece a un grupo`() {
        assertFalse(PinGroups.canUngroup(listOf(pin("a"), pin("b"))))
        assertTrue(PinGroups.canUngroup(listOf(pin("a"), pin("b", group = "g1"))))
    }

    @Test
    fun `el color del grupo es estable y valido`() {
        val id = "3f2b91a0-0000-4444-8888-aaaabbbbcccc"
        assertEquals(PinGroups.colorFor(id), PinGroups.colorFor(id))
        // Sea cual sea el id, el índice no puede salirse por el resto negativo.
        listOf("", "a", "zzz", "-1", "😀").forEach {
            assertTrue("opaco", (PinGroups.colorFor(it).toLong() and 0xFF000000L) == 0xFF000000L)
        }
    }
}
