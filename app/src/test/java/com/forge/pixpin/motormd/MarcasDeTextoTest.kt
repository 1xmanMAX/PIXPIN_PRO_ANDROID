package com.forge.pixpin.motormd

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** **Las marcas de texto** (2-nov-2026): las pruebas de `ancla_texto.rs` y `marcas_web.rs` del PC. */
class MarcasDeTextoTest {

    private fun marca(id: String, exact: String, color: String): JsonObject = buildJsonObject {
        put("id", id); put("marca", "resaltar"); put("color", color); put("cuando", 1)
        putJsonObject("cita") { put("type", "TextQuoteSelector"); put("exact", exact); put("prefix", ""); put("suffix", "") }
        putJsonObject("posicion") { put("type", "TextPositionSelector"); put("start", 3); put("end", 3 + exact.length) }
    }

    private fun lista(vararg m: JsonObject) = MarcasDeTexto.Archivo(sobre = MarcasDeTexto.SOBRE_MARKDOWN, marcas = m.toList())

    @Test
    fun `el archivo de la pagina se lee y se escribe igual`() {
        val t = """{"version":1,"sobre":"html","nuevo":true,"marcas":[{"id":"marca-texto-5","marca":"ondulada","color":"#5BA4F5","cuando":5,"otra":7,
            "cita":{"type":"TextQuoteSelector","exact":"el curado","prefix":"y ","suffix":" empieza"},
            "posicion":{"type":"TextPositionSelector","start":44,"end":53}}]}"""
        val m = MarcasDeTexto.leer(t).getOrThrow()
        assertEquals(MarcasDeTexto.SOBRE_HTML, m.sobre)
        assertEquals(1, m.marcas.size)
        val k = m.marcas[0]
        assertEquals("ondulada", k["marca"]!!.jsonPrimitive.content)
        assertEquals("44", k["posicion"]!!.jsonObject["start"]!!.jsonPrimitive.content)
        assertEquals("y ", k["cita"]!!.jsonObject["prefix"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(7), k["otra"])
        val e = MarcasDeTexto.escribir(m)
        assertEquals(m, MarcasDeTexto.leer(e).getOrThrow())
        assertTrue(e, e.contains("\"nuevo\": true"))
        assertTrue(e.contains("\"type\": \"TextQuoteSelector\""))
    }

    @Test
    fun `caso negativo lo roto es error y lo vacio no`() {
        assertTrue(MarcasDeTexto.leer("{no es json").isFailure)
        assertEquals(0, MarcasDeTexto.leer("").getOrThrow().marcas.size)
        val m = MarcasDeTexto.leer("""{"marcas":[{"id":"a","cita":{"exact":""}},{"id":"b","cita":{"exact":"x"}}]}""").getOrThrow()
        assertEquals(listOf("b"), m.marcas.map(MarcasDeTexto::id))
        assertEquals(1, MarcasDeTexto.deLaPagina("""[{"id":"a","cita":{"exact":"x"}},{"id":""},7]""").size)
    }

    @Test
    fun `juntar conserva lo nuevo de los dos lados y lo quitado`() {
        val a = marca("marca-texto-1", "losa", "#F5C218")
        val b = marca("marca-texto-2", "curado", "#F5C218")
        val base = lista(a, b)
        val c = marca("marca-texto-3", "obra", "#F47286")
        val bAzul = marca("marca-texto-2", "curado", "#5BA4F5")
        val mio = lista(bAzul, c)
        val d = marca("marca-texto-4", "piso", "#3FC88C")
        val disco = lista(a, b, d)
        val j = MarcasDeTexto.fusionar(base, mio, disco)
        assertEquals(listOf("marca-texto-2", "marca-texto-3", "marca-texto-4"), j.marcas.map(MarcasDeTexto::id))
        assertEquals("#5BA4F5", j.marcas[0]["color"]!!.jsonPrimitive.content)
        assertEquals(listOf(b), MarcasDeTexto.fusionar(base, base, lista(b)).marcas)
        assertEquals(base, MarcasDeTexto.fusionar(base, base, base))
    }

    @Test
    fun `la pagina abierta guarda junta con lo llegado y no crea vacio`() {
        val dir = Files.createTempDirectory("mt").toFile()
        val f = File(dir, "pins/draw/anot-ABCDE23456.marcas-texto.json")
        val p = MarcasDeTexto.DeLaPagina(f, MarcasDeTexto.SOBRE_HTML)
        assertNull(p.deLaPagina("[]"))
        assertTrue("sin marcas no se crea", !f.exists())
        val a = marca("marca-texto-1", "losa", "#F5C218")
        assertNull(p.deLaPagina(MarcasDeTexto.lista(listOf(a))))
        assertTrue(f.readText().contains("\"sobre\": \"html\""))
        // Llega del otro aparato una marca nueva mientras está abierta.
        val d = marca("marca-texto-4", "piso", "#3FC88C")
        f.writeText(MarcasDeTexto.escribir(MarcasDeTexto.Archivo(sobre = "html", marcas = listOf(a, d))))
        f.setLastModified(f.lastModified() + 5000)
        val nueva = p.mirar()
        assertTrue(nueva!!.contains("marca-texto-4"))
        // Se quita la de aquí: la otra se queda, y el archivo vacío se escribe (no se borra).
        assertNull(p.deLaPagina(MarcasDeTexto.lista(listOf(d))))
        assertNull(p.deLaPagina("[]"))
        assertTrue(f.exists())
        assertEquals(0, MarcasDeTexto.leer(f.readText()).getOrThrow().marcas.size)
        // Uno roto no se pisa.
        f.writeText("{roto")
        MarcasDeTexto.DeLaPagina(f, "html").deLaPagina(MarcasDeTexto.lista(listOf(a)))
        assertEquals("{roto", f.readText())
        dir.deleteRecursively()
    }
}
