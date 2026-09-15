package com.forge.pixpin.sincro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La fusión a tres bandas y los parches, sin aparatos. Ver [Fusion]. */
class FusionTest {

    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)
    private fun ids(e: JsonElement?): List<String> =
        e!!.jsonObject["elements"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    private fun figura(e: JsonElement?, id: String): JsonObject =
        e!!.jsonObject["elements"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject

    private fun fig(id: String, x: Int = 0, color: String = "#000", v: Int = 1, updated: Long = 1, nonce: Int = 5) =
        """{"id":"$id","type":"rectangle","x":$x,"strokeColor":"$color","version":$v,"versionNonce":$nonce,"updated":$updated}"""

    private fun lienzo(vararg figuras: String) = j("""{"elements":[${figuras.joinToString(",")}],"backgroundColor":"#fff"}""")

    @Test
    fun `A+B y A+C dan A+B+C`() {
        val base = lienzo(fig("a"))
        val mio = lienzo(fig("a"), fig("b"))
        val suyo = lienzo(fig("a"), fig("c"))
        assertEquals(listOf("a", "b", "c"), ids(Fusion.json(base, mio, suyo)))
    }

    @Test
    fun `movida en uno y con otro color en el otro queda movida y con el color nuevo`() {
        val base = lienzo(fig("a", x = 0, color = "#000", v = 1, updated = 10))
        val mio = lienzo(fig("a", x = 50, color = "#000", v = 2, updated = 20))
        val suyo = lienzo(fig("a", x = 0, color = "#f00", v = 2, updated = 30))
        val a = figura(Fusion.json(base, mio, suyo), "a")
        assertEquals("50", a["x"]!!.jsonPrimitive.content)
        assertEquals("#f00", a["strokeColor"]!!.jsonPrimitive.content)
        // Hecha de las dos: sube de versión por encima de las dos.
        assertEquals("3", a["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `la misma propiedad cambiada en los dos la gana el ultimo cambio`() {
        val base = lienzo(fig("a", x = 0, updated = 10))
        val mio = lienzo(fig("a", x = 50, v = 2, updated = 40))
        val suyo = lienzo(fig("a", x = 90, v = 2, updated = 30))
        val cuenta = Fusion.Cuenta()
        assertEquals("50", figura(Fusion.json(base, mio, suyo, cuenta = cuenta), "a")["x"]!!.jsonPrimitive.content)
        assertTrue(cuenta.choques > 0)
    }

    @Test
    fun `un reloj adelantado no gana por ir adelantado`() {
        val base = lienzo(fig("a", x = 0, updated = 10))
        val mio = lienzo(fig("a", x = 50, v = 2, updated = 1_000))
        // El otro va una hora adelantado: su 1.500 es mi 1.500 - 3.600.000.
        val suyo = lienzo(fig("a", x = 90, v = 2, updated = 1_500 + 3_600_000))
        val r = Fusion.json(base, mio, suyo, Fusion.Criterio(desfase = 3_600_000))
        assertEquals("90", figura(r, "a")["x"]!!.jsonPrimitive.content)
        val sinCorregir = Fusion.json(base, lienzo(fig("a", x = 50, v = 2, updated = 2_000)), suyo, Fusion.Criterio(desfase = 3_600_000))
        assertEquals("50", figura(sinCorregir, "a")["x"]!!.jsonPrimitive.content)
    }

    @Test
    fun `borrada en uno y cambiada en el otro vuelve a aparecer`() {
        val base = lienzo(fig("a"), fig("b"))
        val mio = lienzo(fig("a"))
        val suyo = lienzo(fig("a"), fig("b", color = "#0f0", v = 2))
        val cuenta = Fusion.Cuenta()
        val r = Fusion.json(base, mio, suyo, cuenta = cuenta)
        assertEquals(listOf("a", "b"), ids(r))
        assertEquals("#0f0", figura(r, "b")["strokeColor"]!!.jsonPrimitive.content)
        assertEquals(1, cuenta.rescatados)
    }

    @Test
    fun `borrada en uno y sin tocar en el otro se borra`() {
        val base = lienzo(fig("a"), fig("b"))
        assertEquals(listOf("a"), ids(Fusion.json(base, lienzo(fig("a")), base)))
        assertEquals(listOf("a"), ids(Fusion.json(base, base, lienzo(fig("a")))))
    }

    @Test
    fun `sin base se junta todo y no se pierde nada`() {
        val mio = lienzo(fig("a"), fig("b"))
        val suyo = lienzo(fig("a"), fig("c"))
        assertEquals(listOf("a", "b", "c"), ids(Fusion.json(null, mio, suyo)))
    }

    @Test
    fun `lo nuevo del otro cae junto a su vecina, no al final`() {
        val base = lienzo(fig("a"), fig("z"))
        val mio = lienzo(fig("a"), fig("z"), fig("m"))
        val suyo = lienzo(fig("a"), fig("c"), fig("z"))
        assertEquals(listOf("a", "c", "z", "m"), ids(Fusion.json(base, mio, suyo)))
    }

    @Test
    fun `una tabla se junta celda por celda`() {
        val base = j("""{"celdas":{"A1":"1","B1":"2"},"tocado":10}""")
        val mio = j("""{"celdas":{"A1":"uno","B1":"2"},"tocado":20}""")
        val suyo = j("""{"celdas":{"A1":"1","B1":"2","C1":"=A1+B1"},"tocado":30}""")
        val celdas = Fusion.json(base, mio, suyo)!!.jsonObject["celdas"]!!.jsonObject
        assertEquals("uno", celdas["A1"]!!.jsonPrimitive.content)
        assertEquals("=A1+B1", celdas["C1"]!!.jsonPrimitive.content)
        // La misma celda en los dos: la tabla tocada más tarde.
        val otra = Fusion.json(base, j("""{"celdas":{"A1":"mia"},"tocado":20}"""), j("""{"celdas":{"A1":"suya"},"tocado":30}"""))
        assertEquals("suya", otra!!.jsonObject["celdas"]!!.jsonObject["A1"]!!.jsonPrimitive.content)
    }

    @Test
    fun `una nota se junta parrafo por parrafo`() {
        val base = "Título\n\nPrimero.\n\nSegundo.\n\nTercero."
        val mio = "Título\n\nPrimero, corregido.\n\nSegundo.\n\nTercero."
        val suyo = "Título\n\nPrimero.\n\nSegundo.\n\nTercero.\n\nCuarto nuevo."
        assertEquals("Título\n\nPrimero, corregido.\n\nSegundo.\n\nTercero.\n\nCuarto nuevo.", Fusion.parrafos(base, mio, suyo, ganaMio = true))
        // Dentro de un objeto, por su clave.
        val r = Fusion.json(j("""{"nota":${Json.encodeToString(kotlinx.serialization.serializer<String>(), base)}}"""),
            j("""{"nota":${Json.encodeToString(kotlinx.serialization.serializer<String>(), mio)}}"""),
            j("""{"nota":${Json.encodeToString(kotlinx.serialization.serializer<String>(), suyo)}}"""))
        assertTrue(r!!.jsonObject["nota"]!!.jsonPrimitive.content.contains("corregido"))
        assertTrue(r.jsonObject["nota"]!!.jsonPrimitive.content.contains("Cuarto"))
    }

    @Test
    fun `el mismo parrafo cambiado en los dos lo gana el mas reciente`() {
        val cuenta = Fusion.Cuenta()
        assertEquals("a\nB suyo\nc", Fusion.parrafos("a\nb\nc", "a\nB mío\nc", "a\nB suyo\nc", ganaMio = false, cuenta = cuenta))
        assertEquals(1, cuenta.choques)
    }

    @Test
    fun `los grupos de una figura se juntan como conjunto`() {
        val r = Fusion.json(j("""{"g":["x"]}"""), j("""{"g":["x","y"]}"""), j("""{"g":[]}"""))
        assertEquals(listOf("y"), r!!.jsonObject["g"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `el parche lleva solo lo cambiado y al aplicarlo sale lo mismo`() {
        val muchas = (1..300).map { fig("f$it", x = it) }
        val antes = lienzo(*muchas.toTypedArray())
        val despues = lienzo(*(muchas.drop(1).map { if (it.contains("\"f7\"")) fig("f7", x = 999, v = 2) else it } + fig("nueva")).toTypedArray())
        val parche = Fusion.diferencia(antes, despues)!!
        assertTrue("el parche ocupa ${parche.toString().length} y el lienzo ${despues.toString().length}",
            parche.toString().length * 10 < despues.toString().length)
        assertEquals(Canonico.de(despues.toString()), Canonico.de(Fusion.aplicar(antes, parche).toString()))
    }

    @Test
    fun `el parche de un orden cambiado lo reordena`() {
        val antes = lienzo(fig("a"), fig("b"), fig("c"))
        val despues = lienzo(fig("c"), fig("a"), fig("b", x = 3))
        assertEquals(despues, Fusion.aplicar(antes, Fusion.diferencia(antes, despues)))
    }

    @Test
    fun `sin cambios no hay parche`() {
        val a = lienzo(fig("a"))
        assertNull(Fusion.diferencia(a, a))
        assertEquals(a, Fusion.aplicar(a, null))
    }

    @Test
    fun `quitar una clave viaja en el parche`() {
        val antes = j("""{"a":1,"b":{"c":2,"d":3}}""")
        val despues = j("""{"a":1,"b":{"c":2}}""")
        assertEquals(despues, Fusion.aplicar(antes, Fusion.diferencia(antes, despues)))
        assertEquals(JsonArray(emptyList()), Fusion.aplicar(j("[1]"), Fusion.diferencia(j("[1]"), JsonArray(emptyList()))))
    }
}
