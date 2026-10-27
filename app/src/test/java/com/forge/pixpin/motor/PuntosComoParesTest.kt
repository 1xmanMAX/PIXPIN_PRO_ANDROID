package com.forge.pixpin.motor

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Los puntos se leen como `{x, y}` y como `[x, y]` (los del PC); se escriben como objeto. */
class PuntosComoParesTest {
    private val lista = ListSerializer(Pt.serializer())

    @Test
    fun `lee pares del PC y objetos`() {
        assertEquals(listOf(Pt(1.5, 2.0), Pt(-3.0, 4.25)), ExcalidrawJson.decodeFromString(lista, "[[1.5,2],[-3,4.25]]"))
        assertEquals(listOf(Pt(1.5, 2.0)), ExcalidrawJson.decodeFromString(lista, """[{"x":1.5,"y":2}]"""))
    }

    @Test
    fun `escribe objetos y vuelve igual`() {
        val texto = ExcalidrawJson.encodeToString(lista, listOf(Pt(1.0, 2.5)))
        assertEquals("""[{"x":1.0,"y":2.5}]""", texto)
        assertEquals(listOf(Pt(1.0, 2.5)), ExcalidrawJson.decodeFromString(lista, texto))
    }
}
