package com.forge.pixpin.ia

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * **El protocolo y la descarga, sin red de verdad**: el JSON de OpenAI, el lector de SSE (con
 * paquetes partidos y tildes cortadas), el cliente HTTP contra un servidor de mentira que habla
 * como `llama-server`, y la descarga con huella distinta, cancelada y reanudada tras un corte.
 */
class MotorIaTest {

    // ---------------------------------------------------------------- mensajes y petición

    @Test fun `un mensaje con imagen va como lista de partes, la imagen primero`() {
        val j = MensajeIa.usuarioConImagen("dibujala", byteArrayOf(1, 2, 3), "image/png").aJson()
        assertEquals("user", j["role"]!!.jsonPrimitive.content)
        val partes = j["content"]!!.jsonArray
        assertEquals("image_url", partes[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,AQID", partes[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("dibujala", partes[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("hola", MensajeIa.usuario("hola").aJson()["content"]!!.jsonPrimitive.content)
        val r = MensajeIa.resultado("abc", "hecho").aJson()
        assertEquals("tool", r["role"]!!.jsonPrimitive.content)
        assertEquals("abc", r["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test fun `la peticion apaga el pensar, pide el uso en vivo y lleva los ajustes de Qwen`() {
        val j = Peticion(listOf(MensajeIa.usuario("hola"))).aJson(true)
        assertEquals("false", j["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.content)
        assertEquals("true", j["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content)
        assertEquals("20", j["top_k"]!!.jsonPrimitive.content)
        assertNull(j["tools"])
        val conTools = Peticion(listOf(MensajeIa.usuario("x")), Herramientas.catalogo()).aJson(false)
        assertEquals(3, (conTools["tools"] as JsonArray).size)
        assertNull(conTools["stream_options"])
    }

    // ---------------------------------------------------------------- SSE

    private val flujo = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Ho\"}}]}\n\n" +
        "data: {\"choices\":[{\"delta\":{\"content\":\"la\"}}]}\n\n" +
        ": latido\n\n" +
        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"abc\",\"type\":\"function\",\"function\":{\"name\":\"grafica\",\"arguments\":\"{\\\"tipo\\\":\"}}]}}]}\n\n" +
        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"barras\\\"}\"}}]}}]}\n\n" +
        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":775,\"completion_tokens\":79},\"timings\":{\"prompt_per_second\":63.2,\"predicted_per_second\":15.9}}\n\n" +
        "data: [DONE]\n\n"

    @Test fun `junta texto y llamadas partidas entre paquetes`() {
        val a = Acumulador()
        val trozos = mutableListOf<Trozo>()
        // A pedacitos de 7 bytes, cortando líneas y UTF-8 por cualquier sitio.
        flujo.toByteArray().toList().chunked(7).forEach { trozos += a.meter(it.toByteArray()) }
        assertTrue(a.acabado)
        val r = a.respuesta(false)
        assertEquals("Hola", r.texto)
        assertEquals(listOf(LlamadaCruda("abc", "grafica", "{\"tipo\":\"barras\"}")), r.llamadas)
        assertEquals(Fin.HERRAMIENTAS, r.fin)
        assertEquals(775, r.medidas.tokensEntrada)
        assertEquals(15.9, r.medidas.tokensPorSegundoEscritura, 1e-9)
        assertEquals(listOf(Trozo.Texto("Ho"), Trozo.Texto("la"), Trozo.Llamando("grafica")), trozos)
    }

    @Test fun `las tildes partidas entre paquetes no se rompen`() {
        val b = "data: {\"choices\":[{\"delta\":{\"content\":\"canción\"}}]}\n".toByteArray()
        val corte = String(b).indexOf('ó') + 1
        val a = Acumulador()
        a.meter(b.copyOfRange(0, corte)); a.meter(b.copyOfRange(corte, b.size))
        assertEquals("canción", a.respuesta(false).texto)
    }

    @Test fun `errores del flujo, ids que faltan y respuesta cortada`() {
        assertTrue(runCatching { Acumulador().meter("data: {\"error\":{\"message\":\"contexto lleno\"}}\n".toByteArray()) }
            .exceptionOrNull()!!.message!!.contains("contexto lleno"))
        assertTrue(runCatching { Acumulador().meter("data: {roto\n".toByteArray()) }.exceptionOrNull() is ErrorIa.Protocolo)
        val a = Acumulador()
        a.meter("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"ocr\",\"arguments\":\"{}\"}}]}}]}\n".toByteArray())
        assertEquals("llamada-0", a.respuesta(false).llamadas[0].id)
        val b = Acumulador()
        b.meter("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n".toByteArray())
        assertEquals(Fin.CORTADO, b.respuesta(true).fin)
        assertTrue(runCatching { Acumulador().meter("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":99}]}}]}\n".toByteArray()) }.exceptionOrNull() is ErrorIa.Protocolo)
    }

    // ---------------------------------------------------------------- cliente HTTP

    /** Un `llama-server` de mentira: comprueba la clave y contesta el flujo de arriba, despacio. */
    private fun servidorDeMentira(clave: String, pausaMs: Long = 0, vistos: MutableList<String> = mutableListOf()): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1/chat/completions") { ex ->
            vistos += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            if (ex.requestHeaders.getFirst("Authorization") != "Bearer $clave") {
                val b = "{\"error\":{\"message\":\"Invalid API Key\"}}".toByteArray()
                ex.sendResponseHeaders(401, b.size.toLong()); ex.responseBody.use { it.write(b) }
                return@createContext
            }
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            runCatching {
                ex.responseBody.use { o ->
                    for (linea in flujo.split("\n\n").filter { it.isNotEmpty() }) {
                        o.write((linea + "\n\n").toByteArray()); o.flush()
                        if (pausaMs > 0) Thread.sleep(pausaMs)
                    }
                }
            }
        }
        s.start()
        return s
    }

    @Test fun `el cliente habla con un servidor como llama-server`() {
        val vistos = mutableListOf<String>()
        val s = servidorDeMentira("secreta", vistos = vistos)
        try {
            val trozos = mutableListOf<Trozo>()
            val r = ServidorLlama.pedir(s.address.port, "secreta", Peticion(listOf(MensajeIa.usuario("hola")))) { trozos += it; Seguir.SI }
            assertEquals("Hola", r.texto)
            assertEquals("grafica", r.llamadas.single().nombre)
            assertEquals(3, trozos.size)
            assertTrue(vistos.single().contains("\"stream\":true"))
            // Con otra clave: error de protocolo con el código, nunca la respuesta.
            val e = runCatching { ServidorLlama.pedir(s.address.port, "otra", Peticion(listOf(MensajeIa.usuario("hola")))) { Seguir.SI } }.exceptionOrNull()
            assertTrue(e is ErrorIa.Protocolo && e.detalle.startsWith("HTTP 401"))
        } finally {
            s.stop(0)
        }
    }

    @Test fun `parar a mitad corta la respuesta`() {
        val s = servidorDeMentira("k", pausaMs = 50)
        try {
            val r = ServidorLlama.pedir(s.address.port, "k", Peticion(listOf(MensajeIa.usuario("hola")))) { Seguir.PARAR }
            assertEquals(Fin.CORTADO, r.fin)
            assertEquals("Ho", r.texto)
        } finally {
            s.stop(0)
        }
    }

    @Test fun `sin servidor es un error de conexion`() {
        val puerto = ServidorLlama.puertoLibre()
        assertTrue(runCatching { ServidorLlama.pedir(puerto, "k", Peticion(listOf(MensajeIa.usuario("x")))) { Seguir.SI } }.exceptionOrNull() is ErrorIa.Conexion)
    }

    @Test fun `argumentos del servidor solo en 127_0_0_1 y con la clave por fichero`() {
        val c = ServidorLlama.Config(File("/x/libllamaserver.so"), File("/m/modelo.gguf"), File("/m/mmproj-F16.gguf"), File("/t"), hilos = 4)
        val a = c.argumentos(1234, File("/t/clave"))
        assertEquals("127.0.0.1", a[a.indexOf("--host") + 1])
        assertEquals("/t/clave", a[a.indexOf("--api-key-file") + 1])
        assertFalse(a.contains("--api-key"))
        assertEquals("4096", a[a.indexOf("-c") + 1])
        assertEquals("/m/mmproj-F16.gguf", a[a.indexOf("--mmproj") + 1])
        assertEquals("4", a[a.indexOf("-t") + 1])
        assertTrue(a.contains("--jinja") && a.contains("--no-ui"))
        assertEquals(64, ServidorLlama.claveNueva().length)
        assertTrue(ServidorLlama.claveNueva() != ServidorLlama.claveNueva())
    }

    @Test fun `sin el ejecutable o el modelo dice que falta`() {
        val t = kotlin.io.path.createTempDirectory("ia").toFile()
        val m = ServidorLlama(ServidorLlama.Config(File(t, "no-esta"), File(t, "tampoco.gguf"), null, t))
        assertTrue(runCatching { m.precargar() }.exceptionOrNull() is ErrorIa.Falta)
        assertFalse(m.cargado)
        t.deleteRecursively()
    }

    @Test fun `hilos los nucleos grandes`() {
        // Un Snapdragon 8 Gen 3: 2 pequeños, 5 medianos y 1 grande.
        assertEquals(6, ServidorLlama.hilosPorNucleos(listOf(2265600, 2265600, 3148800, 3148800, 3148800, 2956800, 2956800, 3398400)))
        assertEquals(4, ServidorLlama.hilosPorNucleos(listOf(1800000, 1800000, 1800000, 1800000)))
        assertNull(ServidorLlama.hilosPorNucleos(emptyList()))
    }

    // ---------------------------------------------------------------- descarga

    private fun fichero(nombre: String, datos: ByteArray, url: String, sha: String? = null) = Fichero(
        nombre, url, datos.size.toLong(),
        sha ?: MessageDigest.getInstance("SHA-256").digest(datos).joinToString("") { "%02x".format(it) }
    )

    /** Un servidor que sirve [datos] con rangos y corta la conexión a mitad las [cortes] primeras veces. */
    private fun servidorDeFicheros(datos: ByteArray, cortes: Int = 0, rangos: Boolean = true): Pair<HttpServer, AtomicInteger> {
        val peticiones = AtomicInteger()
        val quedanCortes = AtomicInteger(cortes)
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/f") { ex ->
            peticiones.incrementAndGet()
            val rango = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
            val desde = if (rangos) rango ?: 0 else 0
            val trozo = datos.copyOfRange(desde, datos.size)
            ex.sendResponseHeaders(if (desde > 0) 206 else 200, trozo.size.toLong())
            runCatching {
                ex.responseBody.use { o ->
                    if (quedanCortes.getAndDecrement() > 0) {
                        o.write(trozo, 0, trozo.size / 3); o.flush()
                        // Corte de red: se cierra sin mandar lo demás.
                        ex.close()
                    } else o.write(trozo)
                }
            }
        }
        s.start()
        return s to peticiones
    }

    private fun modelo(f: Fichero) = Modelo("prueba", "Prueba", "MIT", f, null, 1)

    @Test fun `baja, verifica la huella y deja la marca`() {
        val datos = ByteArray(300_000) { (it % 251).toByte() }
        val (s, _) = servidorDeFicheros(datos)
        val raiz = kotlin.io.path.createTempDirectory("ia").toFile()
        try {
            val m = modelo(fichero("pesos.gguf", datos, "http://127.0.0.1:${s.address.port}/f"))
            assertFalse(Descarga.modeloListo(raiz, m))
            val avisos = mutableListOf<Descarga.Progreso>()
            Descarga.asegurar(raiz, m, { avisos += it }, AtomicBoolean(false))
            assertTrue(Descarga.modeloListo(raiz, m))
            assertTrue(avisos.last().verificando)
            assertEquals(1f, avisos.last().fraccion, 0f)
            assertEquals(0L, Descarga.faltaPorBajar(raiz, m))
            assertFalse(File(raiz, "prueba/pesos.gguf.parte").exists())
        } finally { s.stop(0); raiz.deleteRecursively() }
    }

    @Test fun `con huella distinta se borra y se dice`() {
        val datos = ByteArray(10_000) { 7 }
        val (s, _) = servidorDeFicheros(datos)
        val raiz = kotlin.io.path.createTempDirectory("ia").toFile()
        try {
            val m = modelo(fichero("pesos.gguf", datos, "http://127.0.0.1:${s.address.port}/f", sha = "0".repeat(64)))
            val e = runCatching { Descarga.asegurar(raiz, m, {}, AtomicBoolean(false)) }.exceptionOrNull()
            assertTrue(e is ErrorIa.HuellaDistinta)
            assertFalse(File(raiz, "prueba/pesos.gguf").exists())
            assertFalse(Descarga.modeloListo(raiz, m))
        } finally { s.stop(0); raiz.deleteRecursively() }
    }

    @Test fun `cancelada se para y lo bajado espera en el parte`() {
        val datos = ByteArray(3_000_000) { (it % 13).toByte() }
        val (s, _) = servidorDeFicheros(datos)
        val raiz = kotlin.io.path.createTempDirectory("ia").toFile()
        try {
            val m = modelo(fichero("pesos.gguf", datos, "http://127.0.0.1:${s.address.port}/f"))
            val cancelado = AtomicBoolean(false)
            val e = runCatching { Descarga.asegurar(raiz, m, { if (it.hecho > 1_000_000) cancelado.set(true) }, cancelado) }.exceptionOrNull()
            assertTrue(e === ErrorIa.Cancelado)
            assertFalse(Descarga.modeloListo(raiz, m))
            val parte = File(raiz, "prueba/pesos.gguf.parte")
            assertTrue(parte.length() in 1..datos.size - 1L)
            // Y sigue donde se quedó.
            Descarga.asegurar(raiz, m, {}, AtomicBoolean(false))
            assertTrue(Descarga.modeloListo(raiz, m))
        } finally { s.stop(0); raiz.deleteRecursively() }
    }

    @Test fun `un corte de red se reanuda con rangos`() {
        val datos = ByteArray(600_000) { (it % 97).toByte() }
        val (s, peticiones) = servidorDeFicheros(datos, cortes = 2)
        val raiz = kotlin.io.path.createTempDirectory("ia").toFile()
        try {
            val m = modelo(fichero("pesos.gguf", datos, "http://127.0.0.1:${s.address.port}/f"))
            Descarga.asegurar(raiz, m, {}, AtomicBoolean(false))
            assertTrue(Descarga.modeloListo(raiz, m))
            assertEquals(3, peticiones.get())
        } finally { s.stop(0); raiz.deleteRecursively() }
    }

    @Test fun `un servidor sin rangos empieza de cero sin corromper`() {
        val datos = ByteArray(200_000) { (it % 31).toByte() }
        val (s, _) = servidorDeFicheros(datos, cortes = 1, rangos = false)
        val raiz = kotlin.io.path.createTempDirectory("ia").toFile()
        try {
            val m = modelo(fichero("pesos.gguf", datos, "http://127.0.0.1:${s.address.port}/f"))
            Descarga.asegurar(raiz, m, {}, AtomicBoolean(false))
            assertTrue(Descarga.modeloListo(raiz, m))
        } finally { s.stop(0); raiz.deleteRecursively() }
    }

    // ---------------------------------------------------------------- modelos

    @Test fun `los modelos son los del PC y se elige por la RAM`() {
        assertEquals("aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", Modelos.QWEN35_2B.pesos.sha256)
        assertEquals(532_517_120L, Modelos.QWEN35_08B.pesos.bytes)
        for (f in Modelos.TODOS.flatMap { it.ficheros }) {
            assertEquals(64, f.sha256.length)
            assertTrue(f.sha256.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue(f.url.startsWith("https://huggingface.co/unsloth/"))
        }
        val gb = 1024L * 1024 * 1024
        assertEquals(Modelos.QWEN35_08B, Modelos.elegido(null, 6 * gb))
        assertEquals(Modelos.QWEN35_2B, Modelos.elegido(null, 7_400_000_000L))
        assertEquals(Modelos.QWEN35_2B, Modelos.elegido(null, 12 * gb))
        assertEquals(Modelos.QWEN35_08B, Modelos.elegido("qwen3.5-0.8b", 12 * gb))
        assertEquals(Modelos.QWEN35_2B, Modelos.elegido("qwen3.5-2b", 4 * gb))
        assertEquals(Modelos.QWEN35_08B, Modelos.elegido("gpt-9", 4 * gb))
        assertEquals("1,9 GB", Modelos.tamanoLegible(Modelos.QWEN35_2B.bytes))
        assertEquals("737 MB", Modelos.tamanoLegible(Modelos.QWEN35_08B.bytes))
    }
}
