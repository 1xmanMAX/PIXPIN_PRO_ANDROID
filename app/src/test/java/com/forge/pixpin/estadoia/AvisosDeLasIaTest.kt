package com.forge.pixpin.estadoia

import com.forge.pixpin.ia.MensajeIa
import com.forge.pixpin.ia.Nube
import com.forge.pixpin.ia.Peticion
import com.forge.pixpin.ia.Seguir
import com.forge.pixpin.jev.Cache
import com.forge.pixpin.jev.Cliente
import com.forge.pixpin.jev.Grabado
import com.forge.pixpin.jev.Pregunta
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * **Cada petición de verdad avisa de cómo acabó** (1-nov-2026, `al_contestar` del PC): la nube del
 * asistente y Jev cuentan su código (o «sin red») al estado de las IA; lo que sale del cache de Jev
 * no cuenta. Y la comprobación que no gasta pide la lista de modelos de cada API.
 */
class AvisosDeLasIaTest {
    private val oidos: MutableList<Pair<String, Int?>> = Collections.synchronizedList(mutableListOf())

    @After fun quitar() {
        Nube.alContestar = null
        Cliente.alContestar = null
    }

    private fun oirNube() { Nube.alContestar = { p, c -> oidos += p.id to c } }
    private fun oirJev() { Cliente.alContestar = { c -> oidos += "jev" to c } }

    private fun peticion() = Peticion(mutableListOf(MensajeIa.sistema("Eres el asistente de PixPin."), MensajeIa.usuario("hola")))

    @Test fun `la comprobacion pide la lista de modelos sin gastar y avisa`() {
        oirNube()
        val pedidas = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        val c = Nube.comprobar(Nube.Proveedor.CLAUDE, " sk-ant-clave-0123456789 ", get = { url, cab -> pedidas += url to cab; 200 to "{}" })
        assertEquals(200, c)
        assertEquals("https://api.anthropic.com/v1/models?limit=1", pedidas[0].first)
        assertTrue(pedidas[0].second.contains("x-api-key" to "sk-ant-clave-0123456789"))
        assertTrue(pedidas[0].second.any { it.first == "anthropic-version" })
        Nube.comprobar(Nube.Proveedor.DEEPSEEK, "sk-deepseek-0123456789", get = { url, cab -> pedidas += url to cab; 401 to "{}" })
        assertEquals("https://api.deepseek.com/models", pedidas[1].first)
        assertTrue(pedidas[1].second.contains("Authorization" to "Bearer sk-deepseek-0123456789"))
        // Sin red: null. Y el 400 de «sin saldo» de Claude cuenta como un 402.
        Nube.comprobar(Nube.Proveedor.CLAUDE, "k", get = { _, _ -> throw Nube.Fallo.Red("sin red") })
        Nube.comprobar(Nube.Proveedor.CLAUDE, "k", get = { _, _ -> 400 to """{"error":{"message":"Your credit balance is too low"}}""" })
        assertEquals(listOf("claude" to 200, "deepseek" to 401, "claude" to null, "claude" to 402), oidos.toList())
        assertEquals(Estado.Falla(Motivo.SinSaldo), Desenlace.deCodigo(oidos.last().second).estado)
    }

    @Test fun `cada respuesta del asistente avisa de su codigo`() {
        oirNube()
        val m = Nube.Motor(
            Nube.Proveedor.DEEPSEEK, "sk-deepseekfalsa0123456789abcdef",
            http = { _, _, _, _ -> throw Nube.Fallo.Http(401, "{}", null) }, esperas = emptyList()
        )
        runCatching { m.completar(peticion()) { Seguir.SI } }
        val sinRed = Nube.Motor(
            Nube.Proveedor.CLAUDE, "sk-ant-api03-CLAVEFALSA0123456789",
            http = { _, _, _, _ -> throw Nube.Fallo.Red("x") }, esperas = emptyList()
        )
        runCatching { sinRed.completar(peticion()) { Seguir.SI } }
        assertEquals(listOf("deepseek" to 401, "claude" to null), oidos.toList())
    }

    @Test fun `jev avisa de cada peticion y no de lo que sale del cache`() {
        oirJev()
        val t = Grabado(listOf(Grabado.codigo(403, """{"detail":"Forbidden"}"""), Grabado.caida(), Grabado.ok("""{"models":[{"name":"jev-latest"}]}""")))
        val c = Cliente("ts-clave", t, Cache())
        runCatching { c.modelos() }
        val p = com.forge.pixpin.jev.Peticion(JsonObject(mapOf("linea" to JsonPrimitive("hola"))))
            .con("t", Pregunta.noul("Is it a task?", "Yes.", "No."))
        runCatching { c.preguntar(p) }
        c.modelos()
        assertEquals(listOf("jev" to 403, "jev" to null, "jev" to 200), oidos.toList())
        // Del cache: no sale a la red y no cuenta.
        val cache = Cache()
        val buena = Cliente("ts-clave", Grabado(listOf(Grabado.ok(Grabado.nouls(listOf("t" to 0.9))))), cache)
        buena.preguntar(p)
        buena.preguntar(p)
        assertEquals(listOf("jev" to 403, "jev" to null, "jev" to 200, "jev" to 200), oidos.toList())
    }
}
