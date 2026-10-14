package com.forge.pixpin.motor

import com.forge.pixpin.sincro.Fusion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Dos figuras con el mismo id**: se arreglan al abrir y al guardar, y la fusión las junta bien.
 * Ver [IdsRepetidos].
 */
class IdsRepetidosTest {

    private fun rect(id: String, x: Double, version: Int, updated: Long = 1L) =
        newElement(ElementType.RECTANGLE, x, 0.0, ItemStyle(), 50.0, 40.0)
            .copy(id = id, version = version, updated = updated)

    @Test
    fun `de dos con el mismo id el de version mayor conserva el suyo y el otro sube de version`() {
        val vieja = rect("pc5", 0.0, version = 3)
        val nueva = rect("pc5", 200.0, version = 1)
        val otra = rect("A", 400.0, version = 2)
        val arreglado = IdsRepetidos.arreglar(listOf(otra, nueva, vieja), ahora = 777L)

        assertEquals(3, arreglado.size)
        assertEquals("ids únicos", 3, arreglado.map { it.id }.toSet().size)
        // La de versión mayor (la vieja) se queda con pc5, aunque vaya detrás.
        assertEquals("pc5", arreglado[2].id)
        assertEquals(3, arreglado[2].version)
        assertEquals(1L, arreglado[2].updated)
        // La otra, con id nuevo, versión + 1 y la hora de ahora; lo demás, igual.
        assertEquals("pc5-1", arreglado[1].id)
        assertEquals(2, arreglado[1].version)
        assertEquals(777L, arreglado[1].updated)
        assertEquals(200.0, arreglado[1].x, 0.0)
        // La que no estaba repetida no se toca.
        assertSame(otra, arreglado[0])
    }

    @Test
    fun `a igual version conserva el id el primero, y tres iguales dan tres ids`() {
        val a = rect("X", 0.0, 1); val b = rect("X", 10.0, 1); val c = rect("X", 20.0, 1)
        val arreglado = IdsRepetidos.arreglar(listOf(a, b, c), ahora = 5L)
        assertEquals(listOf("X", "X-2", "X-3"), arreglado.map { it.id })
    }

    @Test
    fun `el id nuevo no pisa uno que ya existe`() {
        val arreglado = IdsRepetidos.arreglar(listOf(rect("X", 0.0, 2), rect("X", 1.0, 1), rect("X-2", 2.0, 1)))
        assertEquals(listOf("X", "X-3", "X-2"), arreglado.map { it.id })
    }

    @Test
    fun `sin repetidos no cambia nada - la misma lista y las mismas versiones`() {
        val lista = listOf(rect("A", 0.0, 4), rect("B", 1.0, 7))
        val arreglado = IdsRepetidos.arreglar(lista, ahora = 999L)
        assertSame(lista, arreglado)
        assertEquals(listOf(4, 7), arreglado.map { it.version })
        val escena = Scene(elements = lista)
        assertSame(escena, IdsRepetidos.arreglar(escena))
    }

    @Test
    fun `el controlador abre la escena con ids unicos y tocar una no marca las dos`() {
        val c = DrawController(Scene(elements = listOf(rect("pc5", 0.0, 3), rect("pc5", 300.0, 1))))
        assertEquals(listOf("pc5", "pc5-2"), c.scene.elements.map { it.id })
        // Al recargar, igual.
        c.load(Scene(elements = listOf(rect("Z", 0.0, 1), rect("Z", 300.0, 2))))
        assertEquals(listOf("Z-1", "Z"), c.scene.elements.map { it.id })
        // Marcar la de la izquierda marca solo esa.
        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(-10.0, -10.0))
        c.pointerMove(Pt(100.0, 100.0))
        c.pointerUp(Pt(100.0, 100.0))
        assertEquals(setOf("Z-1"), c.selectedIds)
    }

    // ------------------------------------------------------------------ con la fusión

    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)
    private fun fig(id: String, x: Int, v: Int, updated: Long = 1) =
        """{"id":"$id","type":"rectangle","x":$x,"version":$v,"versionNonce":3,"updated":$updated}"""
    private fun lienzo(vararg f: String) = j("""{"elements":[${f.joinToString(",")}]}""")
    private fun ids(e: JsonElement?) = e!!.jsonObject["elements"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    private fun xDe(e: JsonElement?, id: String) =
        e!!.jsonObject["elements"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject["x"]!!.jsonPrimitive.content

    /**
     * El caso del PC (13-oct-2026): los dos tenían `A` y `pc5`; el PC dibujó un trazo nuevo que salió
     * con `pc5` otra vez, y el móvil añadió `B`. Antes la fusión se quedaba con una lista entera y
     * el trazo del PC o lo del móvil se perdía.
     */
    @Test
    fun `la fusion junta figura por figura aunque un lado traiga ids repetidos`() {
        val base = lienzo(fig("A", 0, 1), fig("pc5", 10, 2))
        val mio = lienzo(fig("A", 0, 1), fig("pc5", 10, 2), fig("B", 20, 1))
        val suyo = lienzo(fig("A", 0, 1), fig("pc5", 10, 2), fig("pc5", 500, 1))
        val junto = Fusion.json(base, mio, suyo)
        assertEquals(listOf("A", "pc5", "B", "pc5-2"), ids(junto))
        assertEquals("500", xDe(junto, "pc5-2"))
        assertEquals("10", xDe(junto, "pc5"))
    }

    /**
     * El móvil abrió y guardó el dibujo (arreglado, con la versión subida); el PC lo tiene aún con
     * los repetidos y además movió algo. **La figura arreglada no sale dos veces**: la regla da el
     * mismo id aquí y allí.
     */
    @Test
    fun `lo arreglado en el movil y lo repetido del otro se juntan como una sola figura`() {
        val base = lienzo(fig("A", 0, 1), fig("pc5", 10, 2), fig("pc5", 500, 1))
        // Como lo deja [IdsRepetidos.arreglar] al guardar: el de versión baja, renombrado y + 1.
        val arreglado = IdsRepetidos.arreglar(
            listOf(rect("A", 0.0, 1), rect("pc5", 10.0, 2), rect("pc5", 500.0, 1)), ahora = 50L
        )
        assertEquals(listOf("A", "pc5", "pc5-2"), arreglado.map { it.id })
        val mio = lienzo(fig("A", 0, 1), fig("pc5", 10, 2), fig("pc5-2", 500, 2, updated = 50))
        val suyo = lienzo(fig("A", 77, 2, updated = 60), fig("pc5", 10, 2), fig("pc5", 500, 1))
        val junto = Fusion.json(base, mio, suyo)
        assertEquals(listOf("A", "pc5", "pc5-2"), ids(junto))
        assertEquals("lo movido en el otro se queda", "77", xDe(junto, "A"))
    }

    @Test
    fun `fuera de las figuras la fusion no renombra nada`() {
        val base = j("""{"hojas":[{"id":"h","n":1},{"id":"h","n":2}]}""")
        val mio = j("""{"hojas":[{"id":"h","n":1},{"id":"h","n":3}]}""")
        val suyo = j("""{"hojas":[{"id":"h","n":4},{"id":"h","n":2}]}""")
        val junto = Fusion.json(base, mio, suyo)!!.toString()
        assertTrue(junto, !junto.contains("h-"))
    }
}
