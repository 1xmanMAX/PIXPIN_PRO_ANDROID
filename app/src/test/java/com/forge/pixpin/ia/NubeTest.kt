package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.Collections

/**
 * **La nube, como en el PC** (`nube/pruebas.rs`): un servidor HTTP falso en 127.0.0.1 que imita el
 * flujo de cada API, por el mismo OkHttp que la app (lo único distinto es la dirección). Claude y
 * DeepSeek: texto y pensamiento en vivo, herramientas con el pensamiento devuelto intacto, sin
 * `strict`, JSON roto que se corrige, cortes por `max_tokens`, rechazos, reintentos que no duplican,
 * errores en palabras del usuario, el respaldo local, probar la clave y que la clave no salga.
 */
class NubeTest {
    private class Falsa(val codigo: Int, val cuerpo: String, val cabeceras: String = "")
    private class Recibida(val cabeceras: String, val cuerpo: JsonObject)

    private val sockets = mutableListOf<ServerSocket>()

    @After fun cerrar() { sockets.forEach { runCatching { it.close() } } }

    private inner class Servidor(ruta: String, guion: List<Falsa>) {
        val recibidas: MutableList<Recibida> = Collections.synchronizedList(mutableListOf())
        private val s = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).also { sockets += it }
        val url = "http://127.0.0.1:${s.localPort}$ruta"

        init {
            Thread {
                for (f in guion) {
                    val c = runCatching { s.accept() }.getOrNull() ?: return@Thread
                    c.use { con ->
                        con.soTimeout = 10_000
                        val entrada = con.getInputStream()
                        val buf = ByteArrayOutputStream()
                        var finCabeza = -1
                        val tmp = ByteArray(8192)
                        while (finCabeza < 0) {
                            val n = entrada.read(tmp); if (n < 0) break
                            buf.write(tmp, 0, n)
                            val b = buf.toByteArray()
                            for (i in 0..b.size - 4) if (b[i] == '\r'.code.toByte() && b[i + 1] == '\n'.code.toByte() && b[i + 2] == '\r'.code.toByte() && b[i + 3] == '\n'.code.toByte()) { finCabeza = i + 4; break }
                        }
                        if (finCabeza < 0) return@use
                        val cabeza = String(buf.toByteArray(), 0, finCabeza).lowercase()
                        val largo = cabeza.lines().firstOrNull { it.startsWith("content-length:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                        while (buf.size() < finCabeza + largo) { val n = entrada.read(tmp); if (n < 0) break; buf.write(tmp, 0, n) }
                        val cuerpo = String(buf.toByteArray(), finCabeza, largo, Charsets.UTF_8)
                        recibidas += Recibida(cabeza, Json.parseToJsonElement(cuerpo).jsonObject)
                        val texto = f.cuerpo.toByteArray()
                        val tipo = if (f.cuerpo.startsWith("{")) "application/json" else "text/event-stream"
                        con.getOutputStream().write(("HTTP/1.1 ${f.codigo} X\r\nContent-Type: $tipo\r\nContent-Length: ${texto.size}\r\n${f.cabeceras}Connection: close\r\n\r\n").toByteArray() + texto)
                        con.getOutputStream().flush()
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    // ------------------------------------------------------------------ guiones de Claude

    private fun sse(eventos: List<JsonObject>) = Falsa(200, eventos.joinToString("") { e -> "event: ${e["type"]!!.jsonPrimitive.content}\ndata: $e\n\n" })
    private fun j(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun inicio() = j("""{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","usage":{"input_tokens":50,"cache_read_input_tokens":1500,"cache_creation_input_tokens":0,"output_tokens":1}}}""")
    private fun pensar(i: Int, t: String) = listOf(
        j("""{"type":"content_block_start","index":$i,"content_block":{"type":"thinking","thinking":"","signature":""}}"""),
        buildJsonObject { put("type", "content_block_delta"); put("index", i); put("delta", buildJsonObject { put("type", "thinking_delta"); put("thinking", t) }) },
        j("""{"type":"content_block_delta","index":$i,"delta":{"type":"signature_delta","signature":"FIRMA-ORIGINAL"}}"""),
        j("""{"type":"content_block_stop","index":$i}""")
    )
    private fun texto(i: Int, vararg partes: String) = listOf(j("""{"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""")) +
        partes.map { p -> buildJsonObject { put("type", "content_block_delta"); put("index", i); put("delta", buildJsonObject { put("type", "text_delta"); put("text", p) }) } } +
        j("""{"type":"content_block_stop","index":$i}""")
    private fun herramienta(i: Int, id: String, nombre: String, args: String) =
        listOf(buildJsonObject { put("type", "content_block_start"); put("index", i); put("content_block", buildJsonObject { put("type", "tool_use"); put("id", id); put("name", nombre); put("input", buildJsonObject {}) }) }) +
            args.chunked(9).map { s -> buildJsonObject { put("type", "content_block_delta"); put("index", i); put("delta", buildJsonObject { put("type", "input_json_delta"); put("partial_json", s) }) } } +
            j("""{"type":"content_block_stop","index":$i}""")
    private fun guionClaude(bloques: List<List<JsonObject>>, motivo: String) = sse(listOf(inicio(), j("""{"type":"ping"}""")) + bloques.flatten() +
        listOf(j("""{"type":"message_delta","delta":{"stop_reason":"$motivo","stop_sequence":null},"usage":{"output_tokens":77}}"""), j("""{"type":"message_stop"}""")))

    private fun trozoDs(delta: JsonObject, fin: String?) = "data: " + buildJsonObject {
        put("id", "x"); put("object", "chat.completion.chunk"); put("model", "deepseek-flash")
        put("choices", buildJsonArray { add(buildJsonObject { put("index", 0); put("delta", delta); put("finish_reason", fin?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull) }) })
    } + "\n\n"
    private fun guionDeepSeek(trozos: List<String>) = Falsa(200, ": keep-alive\n\n" + trozos.joinToString("") +
        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":900,\"completion_tokens\":40}}\n\ndata: [DONE]\n\n")

    private fun claude(url: String) = Nube.Motor(Nube.Proveedor.CLAUDE, "sk-ant-api03-CLAVEFALSA0123456789", url = url, esperas = listOf(10L, 10L))
    private fun deepseek(url: String) = Nube.Motor(Nube.Proveedor.DEEPSEEK, "sk-deepseekfalsa0123456789abcdef", url = url, esperas = listOf(10L, 10L))

    private fun historial(pedido: String) = mutableListOf(MensajeIa.sistema(Agente.instrucciones()), MensajeIa.usuario(pedido))
    private fun peticion(pedido: String) = Peticion(historial(pedido))

    private val DIBUJO = """{"elementos":[{"tipo":"rectangulo","x":10,"y":10,"ancho":100,"alto":80,"color":"rojo"}]}"""

    private class Apunta : Agente.Ejecutor {
        val hechas = mutableListOf<Llamada>()
        override fun ejecutar(llamada: Llamada): Result<String> { hechas += llamada; return Result.success("dibujadas 1 figuras") }
    }

    private fun turno(m: Motor, pedido: String): Triple<Agente.Final, Apunta, List<Agente.Evento>> {
        val ej = Apunta()
        val ev = mutableListOf<Agente.Evento>()
        val f = Agente.turno(m, historial(pedido), Herramientas.catalogo(), ej) { ev += it; Seguir.SI }
        return Triple(f, ej, ev)
    }

    private fun error(bloque: () -> Unit): ErrorIa = try { bloque(); throw AssertionError("tenía que fallar") } catch (e: ErrorIa) { e }

    // --------------------------------------------------------------------------------- Claude

    @Test fun `claude texto y pensamiento en vivo`() {
        val s = Servidor("/v1/messages", listOf(guionClaude(listOf(pensar(0, "El usuario saluda."), texto(1, "Ho", "la, ¿qué ", "tal?")), "end_turn")))
        val trozos = mutableListOf<Trozo>()
        val r = claude(s.url).completar(peticion("hola")) { trozos += it; Seguir.SI }
        assertEquals("Hola, ¿qué tal?", r.texto)
        assertEquals("El usuario saluda.", r.pensamiento)
        assertEquals(Fin.NATURAL, r.fin)
        assertEquals(1550, r.medidas.tokensEntrada); assertEquals(77, r.medidas.tokensSalida)
        assertEquals(Trozo.Pensando("El usuario saluda."), trozos[0])
        assertTrue(Trozo.Texto("la, ¿qué ") in trozos)
        val rec = s.recibidas
        assertEquals(1, rec.size)
        assertTrue(rec[0].cabeceras.contains("x-api-key: sk-ant-api03-clavefalsa0123456789"))
        assertTrue(rec[0].cabeceras.contains("anthropic-version: 2023-06-01"))
        assertEquals("claude-haiku-5-5", rec[0].cuerpo["model"]!!.jsonPrimitive.content)
        assertEquals(1024 + Nube.RESERVA_PENSAR, rec[0].cuerpo["max_tokens"]!!.jsonPrimitive.content.toInt())
        for (prohibido in listOf("temperature", "top_p", "top_k", "chat_template_kwargs", "presence_penalty")) assertNull(prohibido, rec[0].cuerpo[prohibido])
    }

    @Test fun `claude llama una herramienta y devuelve el pensamiento intacto, sin strict`() {
        val s = Servidor("/v1/messages", listOf(
            guionClaude(listOf(pensar(0, "Un rectangulo rojo."), texto(1, "Lo dibujo."), herramienta(2, "toolu_01", "dibujar_en_lienzo", DIBUJO)), "tool_use"),
            guionClaude(listOf(texto(0, "Listo: un rectángulo rojo.")), "end_turn")
        ))
        val (f, ej, ev) = turno(claude(s.url), "dibuja un rectángulo rojo")
        assertEquals(1, f.herramientasHechas)
        assertTrue(f.texto.contains("rectángulo rojo"))
        assertEquals("#e03131", (ej.hechas[0] as Llamada.Dibujar).figuras[0].color)
        assertTrue(ev.any { it is Agente.Evento.Llamando && it.nombre == "dibujar_en_lienzo" })
        val rec = s.recibidas
        val tools = rec[0].cuerpo["tools"]!!.jsonArray
        assertTrue(tools.all { it.jsonObject["strict"] == null })
        assertEquals("true", tools[0].jsonObject["eager_input_streaming"].toString())
        val mensajes = rec[1].cuerpo["messages"]!!.jsonArray
        assertEquals(3, mensajes.size)
        val asis = mensajes[1].jsonObject["content"]!!.jsonArray
        assertEquals("thinking", asis[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("FIRMA-ORIGINAL", asis[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("rectangulo", asis[2].jsonObject["input"]!!.jsonObject["elementos"]!!.jsonArray[0].jsonObject["tipo"]!!.jsonPrimitive.content)
        val res = mensajes[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", res["type"]!!.jsonPrimitive.content); assertEquals("toolu_01", res["tool_use_id"]!!.jsonPrimitive.content)
        assertNull(res["is_error"])
        // El prefijo estable (sistema y herramientas) es idéntico byte a byte: se cachea.
        assertEquals(rec[0].cuerpo["system"], rec[1].cuerpo["system"]); assertEquals(rec[0].cuerpo["tools"], rec[1].cuerpo["tools"])
    }

    @Test fun `claude entrada invalida no se ejecuta y se corrige`() {
        val s = Servidor("/v1/messages", listOf(
            guionClaude(listOf(herramienta(0, "toolu_a", "dibujar_en_lienzo", """{"elementos":[{"tipo":"dragon","x":1,"y":1}]}""")), "tool_use"),
            guionClaude(listOf(herramienta(0, "toolu_b", "dibujar_en_lienzo", """{"elementos":[{"tipo":""")), "tool_use"),
            guionClaude(listOf(herramienta(0, "toolu_c", "dibujar_en_lienzo", DIBUJO)), "tool_use"),
            guionClaude(listOf(texto(0, "Hecho.")), "end_turn")
        ))
        val (f, ej, _) = turno(claude(s.url), "dibuja")
        assertEquals(1, ej.hechas.size); assertEquals(2, f.reintentos)
        val r1 = s.recibidas[1].cuerpo["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("true", r1["is_error"].toString()); assertTrue(r1["content"]!!.jsonPrimitive.content.contains("dragon"))
    }

    @Test fun `claude max_tokens con una herramienta a medias no se ejecuta`() {
        val s = Servidor("/v1/messages", listOf(
            guionClaude(listOf(herramienta(0, "toolu_x", "dibujar_en_lienzo", """{"elementos":[{"tipo":"rectangulo","x":1,"y":1}""")), "max_tokens"),
            guionClaude(listOf(herramienta(0, "toolu_y", "dibujar_en_lienzo", DIBUJO)), "tool_use"),
            guionClaude(listOf(texto(0, "Hecho.")), "end_turn")
        ))
        val (f, ej, _) = turno(claude(s.url), "dibuja mucho")
        assertEquals(1, f.reintentos); assertEquals(1, ej.hechas.size)
        val segundo = s.recibidas[1].cuerpo["messages"].toString()
        assertTrue(segundo.contains("salio cortada")); assertFalse(segundo.contains("toolu_x"))
    }

    @Test fun `claude rechazo no ejecuta nada y lo dice`() {
        val s = Servidor("/v1/messages", listOf(sse(listOf(inicio()) + herramienta(0, "toolu_r", "dibujar_en_lienzo", """{"elementos":""") +
            j("""{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"category":"cyber"}},"usage":{"output_tokens":3}}""") + j("""{"type":"message_stop"}"""))))
        val (f, ej, ev) = turno(claude(s.url), "algo raro")
        assertTrue(ej.hechas.isEmpty())
        assertTrue(f.texto, f.texto.contains("no quiso") && f.texto.contains("filtro") && !f.texto.contains("cyber"))
        assertTrue(ev.any { it is Agente.Evento.Texto && it.texto.contains("no quiso") })
    }

    @Test fun `errores en palabras del usuario, sin la clave, y cuando se reintenta`() {
        val s401 = Servidor("/v1/messages", listOf(Falsa(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")))
        val e = error { claude(s401.url).completar(peticion("hola")) { Seguir.SI } }
        assertTrue(e is ErrorIa.Nube && e.aviso.contains("no acepta la clave") && e.aviso.contains("Ajustes"))
        assertFalse(e.paraElUsuario().contains("CLAVEFALSA") || e.paraElUsuario().contains("401") || e.paraElUsuario().contains("{"))
        assertEquals(1, s401.recibidas.size)
        val saldo = Servidor("/v1/messages", listOf(Falsa(400, """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}""")))
        assertTrue(error { claude(saldo.url).completar(peticion("hola")) { Seguir.SI } }.message!!.contains("no tiene saldo"))
        val espera = Servidor("/v1/messages", listOf(Falsa(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""", "retry-after: 0\r\n"), guionClaude(listOf(texto(0, "ok")), "end_turn")))
        assertEquals("ok", claude(espera.url).completar(peticion("hola")) { Seguir.SI }.texto)
        assertEquals(2, espera.recibidas.size)
        val err = Falsa(500, """{"type":"error","error":{"type":"api_error","message":"boom"}}""")
        val s500 = Servidor("/v1/messages", listOf(err, err, err, err))
        assertTrue(error { claude(s500.url).completar(peticion("hola")) { Seguir.SI } }.message!!.contains("saturado"))
        assertEquals(3, s500.recibidas.size)
        val esquema = Servidor("/v1/messages", listOf(Falsa(400, """{"type":"error","error":{"type":"invalid_request_error","message":"tools.0.input_schema: bad schema"}}""")))
        assertFalse(error { claude(esquema.url).completar(peticion("hola").copy(herramientas = Herramientas.catalogo())) { Seguir.SI } }.message!!.contains("schema"))
        assertEquals(1, esquema.recibidas.size)
    }

    @Test fun `saturado a mitad reintenta solo si no se enseño nada`() {
        val antes = Servidor("/v1/messages", listOf(sse(listOf(inicio(), j("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))), guionClaude(listOf(texto(0, "bien")), "end_turn")))
        assertEquals("bien", claude(antes.url).completar(peticion("hola")) { Seguir.SI }.texto)
        val tras = Servidor("/v1/messages", listOf(sse(listOf(inicio()) + texto(0, "medio") + j("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")), guionClaude(listOf(texto(0, "otra")), "end_turn")))
        error { claude(tras.url).completar(peticion("hola")) { Seguir.SI } }
        assertEquals(1, tras.recibidas.size)
        val roto = Servidor("/v1/messages", listOf(Falsa(200, "event: x\ndata: {roto\n\n")))
        assertTrue(error { claude(roto.url).completar(peticion("hola")) { Seguir.SI } } is ErrorIa.Protocolo)
        val parar = Servidor("/v1/messages", listOf(guionClaude(listOf(texto(0, "uno ", "dos ", "tres")), "end_turn")))
        assertEquals(Fin.CORTADO, claude(parar.url).completar(peticion("hola")) { Seguir.PARAR }.fin)
    }

    // ------------------------------------------------------------------------------- DeepSeek

    @Test fun `deepseek razona, llama y devuelve el razonamiento`() {
        val s = Servidor("/chat/completions", listOf(
            guionDeepSeek(listOf(
                trozoDs(j("""{"role":"assistant","reasoning_content":"Pide un "}"""), null),
                trozoDs(j("""{"reasoning_content":"rectangulo."}"""), null),
                trozoDs(j("""{"tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"dibujar_en_lienzo","arguments":"{\"elementos\":[{\"tipo\":\"rectangulo\","}}]}"""), null),
                trozoDs(j("""{"tool_calls":[{"index":0,"function":{"arguments":"\"x\":5,\"y\":5,\"color\":\"azul\"}]}"}}]}"""), "tool_calls")
            )),
            guionDeepSeek(listOf(trozoDs(j("""{"content":"Dibujado."}"""), "stop")))
        ))
        val (f, ej, _) = turno(deepseek(s.url), "un rectangulo azul")
        assertEquals("Dibujado.", f.texto); assertEquals(1, ej.hechas.size)
        assertTrue(s.recibidas[0].cabeceras.contains("authorization: bearer sk-deepseekfalsa"))
        assertEquals("deepseek-flash", s.recibidas[0].cuerpo["model"]!!.jsonPrimitive.content)
        assertEquals("enabled", s.recibidas[0].cuerpo["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", s.recibidas[0].cuerpo["reasoning_effort"]!!.jsonPrimitive.content)
        val m = s.recibidas[1].cuerpo["messages"]!!.jsonArray
        assertEquals(1, m.count { it.jsonObject["role"]!!.jsonPrimitive.content == "system" })
        val asis = m[2].jsonObject
        assertEquals("Pide un rectangulo.", asis["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("call_9", asis["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("tool", m[3].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test fun `deepseek 400 por el razonamiento sigue sin pensar, y sin saldo o clave mala lo dice`() {
        val s = Servidor("/chat/completions", listOf(
            Falsa(400, """{"error":{"message":"Missing reasoning_content field in the assistant message","type":"invalid_request_error"}}"""),
            guionDeepSeek(listOf(trozoDs(j("""{"content":"ok"}"""), "stop")))
        ))
        assertEquals("ok", deepseek(s.url).completar(peticion("hola")) { Seguir.SI }.texto)
        assertEquals("disabled", s.recibidas[1].cuerpo["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val m = Servidor("/chat/completions", listOf(
            Falsa(402, """{"error":{"message":"Insufficient Balance","type":"unknown_error"}}"""),
            Falsa(401, """{"error":{"message":"Authentication Fails","type":"authentication_error"}}""")
        ))
        val d = deepseek(m.url)
        assertTrue(error { d.completar(peticion("a")) { Seguir.SI } }.message!!.contains("no tiene saldo"))
        assertTrue(error { d.completar(peticion("a")) { Seguir.SI } }.message!!.contains("no acepta la clave"))
    }

    // --------------------------------------------------------------- probar, respaldo, sin red

    @Test fun `probar la clave, sin red y sin clave`() {
        val s = Servidor("/v1/messages", listOf(
            Falsa(200, """{"id":"msg","type":"message","model":"claude-haiku-5-5","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""),
            Falsa(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        ))
        assertTrue(Nube.Motor(Nube.Proveedor.CLAUDE, "sk-ant-buena-0123456789", url = s.url).probar().getOrThrow().contains("funciona"))
        assertTrue(Nube.Motor(Nube.Proveedor.CLAUDE, "sk-ant-mala-0123456789", url = s.url).probar().exceptionOrNull()!!.message!!.contains("no acepta la clave"))
        assertTrue(Nube.Motor(Nube.Proveedor.CLAUDE, "  ", url = s.url).probar().isFailure)
        assertEquals("16", s.recibidas[0].cuerpo["max_tokens"].toString())
        val libre = ServerSocket(0).use { it.localPort }
        assertTrue(error { claude("http://127.0.0.1:$libre/v1/messages").completar(peticion("hola")) { Seguir.SI } }.message!!.contains("No hay conexion con Claude"))
        assertTrue(error { Nube.Motor(Nube.Proveedor.DEEPSEEK, "  ").completar(peticion("hola")) { Seguir.SI } }.message!!.contains("Falta la clave de DeepSeek"))
        // Solo HTTPS hacia fuera.
        assertTrue(error { Nube.Motor(Nube.Proveedor.CLAUDE, "sk-ant-x-0123456789abcd", url = "http://example.com/v1/messages", esperas = emptyList()).completar(peticion("a")) { Seguir.SI } } is ErrorIa.Nube)
    }

    @Test fun `si la nube falla sigue el local, y la siguiente va directa`() {
        val s = Servidor("/v1/messages", listOf(Falsa(401, """{"type":"error","error":{"type":"authentication_error","message":"x"}}""")))
        val local = MotorGuionado(listOf(Respuesta.deTexto("respuesta local"), Respuesta.deTexto("otra local")))
        val m = Nube.ConRespaldo(claude(s.url), local, "Claude Haiku 5.5")
        val visto = StringBuilder()
        assertEquals("respuesta local", m.completar(peticion("hola")) { t -> if (t is Trozo.Texto) visto.append(t.texto); Seguir.SI }.texto)
        assertTrue(visto.toString(), visto.contains("no acepta la clave") && visto.contains("Sigo con el asistente local"))
        assertEquals("otra local", m.completar(peticion("hola")) { Seguir.SI }.texto)
        assertEquals(1, s.recibidas.size)
        val solo = Nube.ConRespaldo(Nube.Motor(Nube.Proveedor.CLAUDE, ""), null, "Claude")
        assertTrue(error { solo.completar(peticion("a")) { Seguir.SI } } is ErrorIa.Nube)
    }

    @Test fun `la clave no sale por toString, y se enmascara como en el PC`() {
        val d = claude("http://127.0.0.1:1/").toString()
        assertFalse(d.contains("CLAVEFALSA0123456789")); assertTrue(d.contains("6789") && d.contains("sk-ant-"))
        assertEquals("sk-ant-…6789", Nube.enmascarar("sk-ant-api03-CLAVEFALSA0123456789"))
        assertEquals("sk-…cdef", Nube.enmascarar("sk-deepseekfalsa0123456789abcdef"))
        assertEquals("••••", Nube.enmascarar("abc"))
        assertTrue(Nube.pareceClave("sk-ant-api03-0123456789abcdef")); assertFalse(Nube.pareceClave("con espacio dentro de la clave"))
        assertFalse(Nube.pareceClave("corta"))
    }

    @Test fun `el catalogo entero va a la nube y el respaldo solo lo que toca`() {
        assertTrue(Nube.Motor(Nube.Proveedor.CLAUDE, "x").catalogoCompleto)
        assertFalse(MotorGuionado(emptyList()).catalogoCompleto)
        val p = peticion("dibuja una estrella roja").copy(herramientas = Herramientas.catalogo())
        val q = Nube.paraElLocal(p, Herramientas.DISPONIBLES)
        assertTrue(q.herramientas.size <= Seleccion.MAXIMO && q.herramientas.any { it.nombre == Herramientas.DIBUJAR })
        val c = Nube.Claude.cuerpo(p, emptyMap(), 100)
        assertEquals(Herramientas.catalogo().size, c["tools"]!!.jsonArray.size)
        assertTrue(c["tools"]!!.jsonArray.all { it.jsonObject["strict"] == null })
    }

    @Test fun `las instrucciones son las del PC con el entorno del telefono`() {
        val base = Agente.instrucciones()
        val c = Nube.para(Nube.Proveedor.CLAUDE, base, Herramientas.catalogo())
        val d = Nube.para(Nube.Proveedor.DEEPSEEK, base, Herramientas.catalogo())
        for (s in listOf(c, d)) {
            assertTrue(s.contains(base.trim())); assertTrue(s.contains("800x600")); assertTrue(s.contains("calcar_imagen"))
            assertFalse(s.contains("{ancho}") || s.contains("{n_herramientas}") || s.contains("{max"))
            assertTrue(s.contains("Android"))
        }
        assertTrue(c.startsWith("<rol>") && c.contains("</reglas>"))
        assertTrue(d.contains("# Reglas\n1. ") && d.contains("Recuerda:"))
        assertEquals(1, Nube.reglas(emptyList()).size)
        assertEquals(c, Nube.para(Nube.Proveedor.CLAUDE, base, Herramientas.catalogo()))
        // Lo que cambia cada turno va detrás: lo fijo no cambia y se cachea.
        val (f1, v1) = Nube.enDos(Nube.Proveedor.CLAUDE, "$base\n\n# Hoja de trabajo\nobjetivo: casa", Herramientas.catalogo())
        assertEquals(Nube.enDos(Nube.Proveedor.CLAUDE, base, Herramientas.catalogo()).first, f1)
        assertTrue(v1.contains("objetivo: casa"))
    }

    @Test fun `la casa grabada sin strict trae tamaños y colores, y la de strict no pasa`() {
        fun validar(nombre: String): Result<Validada> {
            val l = Nube.Claude.Lector()
            l.meter(javaClass.getResource("/ia/nube/$nombre")!!.readBytes())
            val s = l.acabar(false)
            return runCatching { Herramientas.validar(s.respuesta.llamadas[0], Herramientas.catalogo()) }
        }
        val buena = validar("casa-sin-strict.sse").getOrThrow().llamada as Llamada.Dibujar
        assertEquals(3, buena.figuras.size)
        assertTrue(validar("casa-con-strict.sse").isFailure)
    }
}
