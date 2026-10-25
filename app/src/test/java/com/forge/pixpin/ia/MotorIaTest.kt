package com.forge.pixpin.ia

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El protocolo, sin red de verdad**: el JSON de OpenAI y el lector de SSE (con paquetes
 * partidos y tildes cortadas).
 */
class MotorIaTest {

    // ---------------------------------------------------------------- mensajes

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
}
