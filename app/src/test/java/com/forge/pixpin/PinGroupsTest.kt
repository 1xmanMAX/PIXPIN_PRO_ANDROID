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

    // ---- Una sola burbuja por grupo minimizado ----

    @Test
    fun `un grupo minimizado deja una sola burbuja`() {
        val hidden = PinGroups.collapsedIds(listOf("a", "b", "c"))
        assertEquals("se esconden todos menos uno", setOf("b", "c"), hidden)
    }

    @Test
    fun `la burbuja es la del pin con el que actuo el usuario`() {
        val hidden = PinGroups.collapsedIds(listOf("a", "b", "c"), owner = "b")
        assertEquals(setOf("a", "c"), hidden)
    }

    @Test
    fun `si el pin que actuo ya no esta minimizado manda el primero`() {
        val hidden = PinGroups.collapsedIds(listOf("a", "b"), owner = "z")
        assertEquals(setOf("b"), hidden)
    }

    @Test
    fun `sin nadie minimizado no se esconde nada`() {
        assertTrue(PinGroups.collapsedIds(emptyList()).isEmpty())
    }

    @Test
    fun `un solo pin minimizado enseña su burbuja`() {
        assertTrue(PinGroups.collapsedIds(listOf("a")).isEmpty())
    }

    /**
     * Regresión: al minimizar un grupo, mover la burbuja y volver a desplegarlo,
     * solo el pin de la burbuja aparecía en su sitio nuevo y el resto se quedaba
     * donde estaba, deshaciendo la disposición del grupo.
     */
    @Test
    fun `el grupo conserva su forma tras moverlo en burbuja`() {
        // A en (100,100) enseña la burbuja; B y C están alrededor.
        val posiciones = mapOf("b" to (160 to 100), "c" to (100 to 260))
        val offsets = PinGroups.offsetsFrom(posiciones, ownerX = 100, ownerY = 100)

        // La burbuja acaba en (700, 900) tras arrastrarla.
        val sitios = PinGroups.followPositions(offsets, 700, 900)

        assertEquals("B sigue 60 px a la derecha de A", 760 to 900, sitios["b"])
        assertEquals("C sigue 160 px por debajo de A", 700 to 1060, sitios["c"])
    }

    @Test
    fun `sin mover la burbuja el grupo vuelve donde estaba`() {
        val posiciones = mapOf("b" to (160 to 100))
        val offsets = PinGroups.offsetsFrom(posiciones, 100, 100)
        assertEquals(160 to 100, PinGroups.followPositions(offsets, 100, 100)["b"])
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
