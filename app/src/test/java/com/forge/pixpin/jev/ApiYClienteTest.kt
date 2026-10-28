package com.forge.pixpin.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * **El JSON de TypeSafe y el cliente** (28-oct-2026): las pruebas de `api.rs`, `cliente.rs` y `lib.rs`
 * del PC, con un transporte grabado ([Grabado]): nunca sale nada a la red.
 */
class ApiYClienteTest {

    private fun estado(vararg p: Pair<String, String>) = JsonObject(p.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun json(b: ByteArray) = Json.parseToJsonElement(String(b)).jsonObject

    private inline fun <reified E : ErrorJev> lanza(f: () -> Unit): E {
        try { f() } catch (e: Throwable) {
            if (e is E) return e
            fail("se esperaba ${E::class.simpleName} y llegó $e")
        }
        fail("se esperaba ${E::class.simpleName} y no falló")
        throw AssertionError()
    }

    // --- api.rs -----------------------------------------------------------------------------

    @Test
    fun `la peticion sale como dice el OpenAPI`() {
        val p = Peticion(estado("linea" to "para el lunes el ejercicio 3"))
            .con("tarea", Pregunta.noul("Is it a task?", "Someone must do it.", "Nobody must."))
            .con("tono", Pregunta.choice("Tone?", listOf("zeta" to "z", "alfa" to "a")))
            .con("nivel", Pregunta.score("How much?", listOf("Low", "High")))
        val v = json(p.cuerpo())
        assertEquals("jev-latest", v["model"]!!.jsonPrimitive.content)
        assertEquals("para el lunes el ejercicio 3", v["state"]!!.jsonObject["linea"]!!.jsonPrimitive.content)
        val q = v["questions"]!!.jsonObject
        assertEquals("noul", q["tarea"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Someone must do it.", q["tarea"]!!.jsonObject["criteria"]!!.jsonObject["true"]!!.jsonPrimitive.content)
        assertEquals("Nobody must.", q["tarea"]!!.jsonObject["criteria"]!!.jsonObject["false"]!!.jsonPrimitive.content)
        assertEquals(listOf("Low", "High"), q["nivel"]!!.jsonObject["criteria"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(v.containsKey("assets"))
        assertFalse(p.conImagenes())
        // Las opciones de la choice, en el orden escrito (no por nombre).
        val texto = String(p.cuerpo())
        assertTrue(texto.indexOf("\"zeta\"") < texto.indexOf("\"alfa\""))
        // Las preguntas, por nombre (el `BTreeMap` del PC); `type` primero en cada una.
        assertTrue(texto.indexOf("\"nivel\"") < texto.indexOf("\"tarea\"") && texto.indexOf("\"tarea\"") < texto.indexOf("\"tono\""))
        assertTrue(texto.contains("\"tarea\":{\"type\":\"noul\",\"instructions\""))
    }

    @Test
    fun `una peticion sin preguntas es un error`() {
        lanza<ErrorJev.Formato> { Peticion(estado("x" to "y")).cuerpo() }
    }

    @Test
    fun `el estado va con sus claves en orden`() {
        val p = Peticion(JsonObject(linkedMapOf("linea" to JsonPrimitive("b"), "antes" to JsonArray(listOf(JsonPrimitive("a"))), "clase" to JsonPrimitive("c"))))
            .con("t", Pregunta.noul("`linea`?", "s", "n"))
        assertTrue(String(p.cuerpo()).contains("\"state\":{\"antes\":[\"a\"],\"clase\":\"c\",\"linea\":\"b\"}"))
    }

    @Test
    fun `una imagen va en base64 y no al registro`() {
        val p = Peticion(estado("foto" to "img-1"))
            .con("donde", Pregunta.caja("Locate the red rectangle."))
            .conImagen("img-1", Imagen("png", byteArrayOf(1, 2, 3)))
        assertTrue(p.conImagenes())
        val v = json(p.cuerpo())
        assertEquals("AQID", v["assets"]!!.jsonObject["img-1"]!!.jsonObject["data_base64"]!!.jsonPrimitive.content)
        assertEquals("image", v["assets"]!!.jsonObject["img-1"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("box", v["questions"]!!.jsonObject["donde"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val r = p.paraElRegistro()
        assertFalse(r.contains("AQID"))
        assertTrue(r.contains("3 bytes png"))
    }

    @Test
    fun `se leen las respuestas del ejemplo del OpenAPI`() {
        val r = Respuestas.deJson(
            """{"model":"jev-1.14.0","answers":{
                "billing":{"type":"noul","noul":0.98},
                "tone":{"type":"choice","choice":"calm","confidence":0.7,"probabilities":{"angry":0.15,"calm":0.8,"excited":0.05}},
                "urgency":{"type":"score","score":1.7,"confidence":0.55,"legend":{"0":"Can wait","1":"This week","2":"Today"},"probabilities":{"0":0.1,"1":0.1,"2":0.8}},
                "red":{"type":"box","box":{"top":0.2,"bottom":0.66,"left":0.19,"right":0.68},"confidence":null}
            },"usage":{"input_tokens":120,"output_tokens":12}}""".toByteArray()
        )
        assertEquals("jev-1.14.0", r.modelo)
        assertEquals(120L, r.tokensDeEntrada)
        assertEquals(0.98, r.noul("billing"), 0.0)
        assertEquals("calm", r.choice("tone").first)
        val e = r.score("urgency", 3)
        assertEquals(2, e.masProbable())
        assertEquals(0.9, e.alMenos(1), 1e-9)
        assertEquals(0.19, r.caja("red").izquierda, 0.0)
        // Lo que falta o es de otro tipo es un error, no un «no».
        lanza<ErrorJev.FaltaRespuesta> { r.noul("nada") }
        lanza<ErrorJev.OtroTipo> { r.noul("tone") }
        lanza<ErrorJev.Formato> { r.score("urgency", 2) }
    }

    @Test
    fun `una probabilidad rota es un error`() {
        val r = Respuestas.deJson("""{"model":"jev","answers":{"x":{"type":"noul","noul":1.5}},"usage":{"input_tokens":1,"output_tokens":1}}""".toByteArray())
        lanza<ErrorJev.Formato> { r.noul("x") }
        lanza<ErrorJev.Formato> { Respuestas.deJson("esto no es json".toByteArray()) }
    }

    @Test
    fun `recortar no parte caracteres`() {
        assertEquals("ñandú", recortar("  ñandú  ", 10))
        assertEquals("ñan…", recortar("ñandúes", 3))
        assertEquals("😀😀…", recortar("😀😀😀", 2))
    }

    // --- lib.rs -----------------------------------------------------------------------------

    @Test
    fun `en paralelo conserva el orden y corta al primer error`() {
        val v = (0 until 40).toList()
        assertEquals(v.map { it * 2 }, enParalelo(v, 4) { it * 2 })
        try {
            enParalelo(v, 3) { if (it == 7) throw ErrorJev.Red("caida") else it }
            fail("debía fallar")
        } catch (e: ErrorJev.Red) {
            assertEquals("caida", e.detalle)
        }
        assertTrue(enParalelo(emptyList<Int>(), 4) { it }.isEmpty())
    }

    // --- cliente.rs ---------------------------------------------------------------------------

    private val BIEN = """{"model":"jev-1.14.0","answers":{"billing":{"type":"noul","noul":0.98}},"usage":{"input_tokens":120,"output_tokens":12}}"""

    private fun peticion(texto: String) = Peticion(estado("message" to texto)).con("billing", Pregunta.noul("Is `message` about billing?", "yes", "no"))

    private val registro = mutableListOf<String>()

    private fun cliente(clave: String, t: Grabado) = Cliente(clave, t, cache = Cache(), registro = { registro += it }, dormir = {})

    @Test
    fun `manda la clave y lee la respuesta`() {
        val t = Grabado(listOf(Grabado.ok(BIEN)))
        val r = cliente("ts-clave-de-prueba", t).preguntar(peticion("cobro doble"))
        assertEquals(0.98, r.noul("billing"), 0.0)
        val p = t.pedidos[0]
        assertEquals("POST", p.metodo)
        assertEquals("https://api.typesafe.ai/v1/systemone", p.url)
        assertTrue(p.cabeceras.contains("Authorization" to "Bearer ts-clave-de-prueba"))
        assertFalse(p.cabeceras.any { it.first.startsWith("TypeSafe-Danger") })
        // Lo que viajó es exactamente la petición.
        assertTrue(p.cuerpo.contentEquals(peticion("cobro doble").cuerpo()))
        // El registro lleva el cuerpo y la respuesta, nunca la clave.
        assertTrue(registro.any { it.contains("cobro doble") })
        assertTrue(registro.any { it.contains("jev-1.14.0") })
        assertFalse(registro.any { it.contains("ts-clave-de-prueba") })
    }

    @Test
    fun `la misma peticion sale del cache`() {
        val t = Grabado(listOf(Grabado.ok(BIEN), Grabado.ok(BIEN)))
        val c = cliente("k", t)
        c.preguntar(peticion("a"))
        c.preguntar(peticion("a"))
        assertEquals(1, t.llamadas)
        c.preguntar(peticion("b"))
        assertEquals(2, t.llamadas)
        assertEquals(2, c.cacheDeEste().cuantas)
    }

    @Test
    fun `el cache guarda como mucho 2048 y sale la mas vieja`() {
        val cache = Cache()
        val r = Respuestas.deJson(BIEN.toByteArray())
        for (i in 0..Cache.CABEN) cache.guardar("h$i", r)
        assertEquals(Cache.CABEN, cache.cuantas)
        assertEquals(null, cache.leer("h0"))
        assertTrue(cache.leer("h${Cache.CABEN}") != null)
        // La huella es el SHA-256 del cuerpo.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Cliente.huella(ByteArray(0)))
    }

    @Test
    fun `los fallos son errores y no se guardan`() {
        val t = Grabado(
            listOf(
                Grabado.codigo(401, """{"detail":"Invalid API key"}"""),
                Grabado.caida("sin red"),
                Grabado.ok("""{"model":"jev","answers":{},"usage":{"input_tokens":1,"output_tokens":0}}"""),
                Grabado.ok("esto no es json")
            )
        )
        val c = cliente("k", t)
        val e = lanza<ErrorJev.Http> { c.preguntar(peticion("x")) }
        assertEquals(401, e.codigo)
        assertEquals("Invalid API key", e.detalle)
        lanza<ErrorJev.Red> { c.preguntar(peticion("x")) }
        lanza<ErrorJev.FaltaRespuesta> { c.preguntar(peticion("x")) }
        lanza<ErrorJev.Formato> { c.preguntar(peticion("x")) }
        assertEquals(0, c.cacheDeEste().cuantas)
    }

    @Test
    fun `sin clave ni se intenta`() {
        val t = Grabado(listOf(Grabado.ok(BIEN)))
        lanza<ErrorJev.SinClave> { cliente("  ", t).preguntar(peticion("x")) }
        assertEquals(0, t.llamadas)
    }

    @Test
    fun `un 529 o un 429 se reintenta una vez y no mas`() {
        val t = Grabado(listOf(Grabado.codigo(529), Grabado.ok(BIEN)))
        assertEquals(0.98, cliente("k", t).preguntar(peticion("y")).noul("billing"), 0.0)
        assertEquals(2, t.llamadas)
        val dos = Grabado(listOf(Grabado.codigo(429), Grabado.codigo(429), Grabado.ok(BIEN)))
        val e = lanza<ErrorJev.Http> { cliente("k", dos).preguntar(peticion("z")) }
        assertEquals(429, e.codigo)
        assertEquals(2, dos.llamadas)
    }

    @Test
    fun `sin tiempo no se reintenta`() {
        var ahora = 0L
        val t = Grabado(listOf(Grabado.codigo(529), Grabado.ok(BIEN)))
        val lento = Transporte { m, u, c, b, tiempo -> ahora += 1_500; t.enviar(m, u, c, b, tiempo) }
        val c = Cliente("k", lento, cache = Cache(), registro = {}, dormir = {}, reloj = { ahora })
        lanza<ErrorJev.Http> { c.preguntar(peticion("w")) }
        assertEquals(1, t.llamadas)
    }

    @Test
    fun `las imagenes llevan su cabecera`() {
        val t = Grabado(listOf(Grabado.ok("""{"model":"jev","answers":{"d":{"type":"box","box":{"top":0,"bottom":1,"left":0,"right":1},"confidence":null}},"usage":{"input_tokens":1,"output_tokens":0}}""")))
        val p = Peticion(estado("foto" to "f1")).con("d", Pregunta.caja("Where?"))
        cliente("k", t).preguntar(p)
        assertTrue(t.pedidos[0].cabeceras.contains("TypeSafe-Danger-Zone-Images" to "true"))
    }

    @Test
    fun `la lista de modelos`() {
        val t = Grabado(listOf(Grabado.ok("""{"models":[{"name":"jev-latest","description":"Jev","release_date":"2026-10-14"}]}""")))
        assertEquals(listOf("jev-latest"), cliente("k", t).modelos())
        assertEquals("GET", t.pedidos[0].metodo)
        assertEquals("https://api.typesafe.ai/v1/models", t.pedidos[0].url)
    }

    @Test
    fun `el cliente no ensena la clave`() {
        assertFalse(Cliente("ts-secreta-123", Grabado()).toString().contains("secreta"))
    }
}
