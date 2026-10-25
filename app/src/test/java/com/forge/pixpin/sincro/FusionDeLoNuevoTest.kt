package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.CarpetaDePaginas
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las tres reglas de fusión nuevas del PC** (25-oct-2026, PC `f3a1f37`; §7 de su
 * `docs/manual-sincronizar-lo-nuevo.md`): marcas y marcadores por milisegundo, la carpeta de páginas
 * página a página y la biblioteca de figuras figura a figura. Ver [Fusion].
 *
 * Dos partes:
 * - las pruebas de `crates/pixpin-sincro/src/fusion.rs` portadas tal cual (mismos datos, mismas
 *   comprobaciones);
 * - **lo mismo byte a byte que el PC**: `sincro/fusion-de-lo-nuevo.json` lleva casos con su
 *   `esperado`, que es lo que sacó el `fusion::json` / `fusion::biblioteca` del PC en `d56a74a` con
 *   esos datos (`a_texto`, JSON compacto). Si alguna regla de aquí se separa de la del PC, el texto
 *   sale distinto y la prueba lo dice: quien dirige escribe en los dos aparatos, así que tienen que
 *   dar lo mismo.
 */
class FusionDeLoNuevoTest {

    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun junto(base: JsonElement?, mio: JsonElement, suyo: JsonElement): JsonElement =
        Fusion.json(base, mio, suyo)!!

    /** Una clave del mensaje juntado, en texto; «-» si no está (el `en` del PC). */
    private fun en(e: JsonElement, k: String): String = e.jsonObject[k]?.toString() ?: "-"

    private fun fig(id: String, x: Int = 0, color: String = "#000", v: Int = 1, updated: Long = 1, nonce: Int = 5) =
        """{"id":"$id","type":"rectangle","x":$x,"strokeColor":"$color","version":$v,"versionNonce":$nonce,"updated":$updated}"""

    private fun f(id: String) = fig(id)

    // ------------------------------------------------------------ las del PC

    @Test
    fun `un marcador en cada aparato quedan los dos en orden y quitar uno viaja`() {
        // La nota de voz de una clase: el PC pone uno al minuto 1 y el móvil otro al 3 desde la misma
        // base (el del segundo 10).
        val base = j("""{"id":"v","marcadores":[{"ms":10000,"texto":"a"}],"marcas":[5]}""")
        var mio = j("""{"id":"v","marcadores":[{"ms":10000,"texto":"a"},{"ms":60000,"texto":"pc"}],"marcas":[5,7]}""")
        val suyo = j("""{"id":"v","marcadores":[{"ms":10000,"texto":"a"},{"ms":180000,"texto":"movil"}],"marcas":[5,9]}""")
        var r = junto(base, mio, suyo)
        assertEquals("""[{"ms":10000,"texto":"a"},{"ms":60000,"texto":"pc"},{"ms":180000,"texto":"movil"}]""", en(r, "marcadores"))
        assertEquals("[5,7,9]", en(r, "marcas"))
        // Quitado en uno (el del segundo 10) y añadido otro en el otro: el quitado se va, el añadido entra.
        mio = j("""{"id":"v","marcadores":[],"marcas":[]}""")
        r = junto(base, mio, suyo)
        assertEquals("""[{"ms":180000,"texto":"movil"}]""", en(r, "marcadores"))
        assertEquals("[9]", en(r, "marcas"))
        // Caso negativo: quitarlos todos en los dos lados quita la clave `marcadores` (como `clase.rs`),
        // y `marcas` se queda en `[]`.
        r = junto(base, j("""{"id":"v","marcas":[],"x":1}"""), j("""{"id":"v","marcas":[],"x":2}"""))
        assertEquals("-", en(r, "marcadores"))
        assertEquals("[]", en(r, "marcas"))
    }

    @Test
    fun `la misma nota del mismo minuto cambiada en los dos gana una sin duplicar`() {
        val base = j("""{"marcadores":[{"ms":1,"texto":"a"}]}""")
        val mio = j("""{"marcadores":[{"ms":1,"texto":"pc"}]}""")
        val suyo = j("""{"marcadores":[{"ms":1,"texto":"movil"}]}""")
        assertEquals("""[{"ms":1,"texto":"pc"}]""", en(junto(base, mio, suyo), "marcadores"))
        // Caso negativo: una lista que no es de marcadores (sin `ms`) sigue la regla general, entera.
        val r = junto(null, j("""{"marcadores":[{"x":1}]}"""), j("""{"marcadores":[{"x":2}]}"""))
        assertEquals("""[{"x":1}]""", en(r, "marcadores"))
    }

    @Test
    fun `una pagina anadida a la carpeta en cada aparato quedan las dos`() {
        fun carpeta(vararg paginas: String) = JsonPrimitive(
            """{"formato":1,"paginas":[${paginas.joinToString(",") { """{"id":"$it","titulo":"Día $it"}""" }}]}"""
        )
        fun mensaje(texto: JsonPrimitive) = JsonObject(mapOf("miniapp" to JsonPrimitive("carpeta"), "texto" to texto))
        val base = mensaje(carpeta("1"))
        var r = junto(base, mensaje(carpeta("1", "2")), mensaje(carpeta("1", "3")))
        val doc = j(r.jsonObject["texto"]!!.jsonPrimitive.content)
        assertEquals(listOf("1", "2", "3"), doc.jsonObject["paginas"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        // Y lo lee la carpeta de aquí igual que la del PC.
        assertEquals(listOf("1", "2", "3"), CarpetaDePaginas.leer(r.jsonObject["texto"]!!.jsonPrimitive.content).paginas.map { it.id })
        // La carpeta recién creada (texto vacío) cuenta como sin páginas.
        r = junto(mensaje(JsonPrimitive("")), mensaje(carpeta("2")), mensaje(carpeta("3")))
        val t = r.jsonObject["texto"].toString()
        assertTrue(t, t.contains("""\"2\"""") && t.contains("""\"3\""""))
        // Caso negativo: un texto que no es una carpeta sigue por párrafos.
        r = junto(mensaje(JsonPrimitive("a")), mensaje(JsonPrimitive("a\nb")), mensaje(JsonPrimitive("c\na")))
        assertEquals("c\na\nb", r.jsonObject["texto"]!!.jsonPrimitive.content)
    }

    @Test
    fun `la biblioteca de figuras guardada en los dos no pierde ninguna`() {
        fun figura(id: String, nombre: String) = """{"id":"$id","nombre":"$nombre","elementos":[${f("$id-pieza")}],"propia":true}"""
        val aqui = "[${figura("serie-1", "Norte")},${figura("pc", "Sello PC")}]"
        val alli = "[${figura("serie-1", "Norte")},${figura("movil", "Sello movil")}]"
        val junta = Fusion.biblioteca(aqui, alli, Fusion.Criterio())!!
        assertEquals(listOf("serie-1", "pc", "movil"), j(junta).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        // Caso negativo: algo que no es una lista de figuras no se junta.
        assertNull(Fusion.biblioteca("{}", alli, Fusion.Criterio()))
        assertNull(Fusion.biblioteca(aqui, "no es json", Fusion.Criterio()))
    }

    // ------------------------------------------------------------ byte a byte con el PC

    @Test
    fun `los casos del PC dan aqui el mismo texto byte a byte`() {
        val texto = requireNotNull(javaClass.classLoader?.getResourceAsStream("sincro/fusion-de-lo-nuevo.json")) { "falta sincro/fusion-de-lo-nuevo.json" }
            .use { it.readBytes().decodeToString() }
        val casos = j(texto).jsonArray
        assertTrue("hay ${casos.size} casos", casos.size >= 25)
        for (caso in casos) {
            val o = caso.jsonObject
            val nombre = o["nombre"]!!.jsonPrimitive.content
            val criterio = Fusion.Criterio(mioMasNuevo = o["ganaMio"]!!.jsonPrimitive.content == "true")
            val sale = when (o["tipo"]!!.jsonPrimitive.content) {
                "json" -> Fusion.json(o["base"], o["mio"], o["suyo"], criterio)?.toString()
                else -> Fusion.biblioteca(o["mio"]!!.jsonPrimitive.content, o["suyo"]!!.jsonPrimitive.content, criterio)
            }
            val esperado = o["esperado"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(nombre, esperado, sale)
        }
    }

    @Test
    fun `una carpeta juntada se lee y se vuelve a escribir sin cambiar un byte`() {
        // El documento que sale de la fusión es el que escribe [CarpetaDePaginas.escribir]: si no,
        // abrir la carpeta y guardarla sin tocar nada sería un cambio que viajaría en cada vuelta.
        val base = j("""{"texto":"{\"formato\":1,\"paginas\":[{\"id\":\"1\",\"titulo\":\"A\"}]}"}""")
        val mio = j("""{"texto":"{\"formato\":1,\"paginas\":[{\"id\":\"1\",\"titulo\":\"A\"},{\"id\":\"2\",\"titulo\":\"Día 2\"}]}"}""")
        val suyo = j("""{"texto":"{\"formato\":1,\"paginas\":[{\"id\":\"3\",\"titulo\":\"Foto del móvil\"},{\"id\":\"1\",\"titulo\":\"A\"}]}"}""")
        val t = junto(base, mio, suyo).jsonObject["texto"]!!.jsonPrimitive.content
        assertEquals(t, CarpetaDePaginas.leer(t).escribir())
        assertEquals(listOf("3", "1", "2"), CarpetaDePaginas.leer(t).paginas.map { it.id })
    }

    @Test
    fun `sin base y con marcas solo en un lado no se pierde ninguna`() {
        val r = Fusion.json(null, j("""{"marcas":[3]}"""), j("""{"marcas":[1,2]}"""))!!
        assertEquals("[1,2,3]", en(r, "marcas"))
        // Y una lista de marcas que no es una lista (un aparato roto) no rompe nada: regla general.
        val r2 = Fusion.json(j("""{"marcas":[]}"""), j("""{"marcas":"x"}"""), j("""{"marcas":[1]}"""), Fusion.Criterio(mioMasNuevo = false))!!
        assertEquals(JsonArray(listOf(JsonPrimitive(1))), r2.jsonObject["marcas"])
    }
}
