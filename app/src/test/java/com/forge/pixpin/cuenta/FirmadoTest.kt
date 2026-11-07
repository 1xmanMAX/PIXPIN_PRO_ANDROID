package com.forge.pixpin.cuenta

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **Lo que firma el servidor (Node) lo acepta la app, y lo tocado no** (7-nov-2026). Los documentos de aquí los firmó
 * `servidor/pindesk/src/firma.mjs` de verdad, con una clave de prueba: así se prueba que los dos lados hablan igual.
 */
class FirmadoTest {
    private val publica = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEJg+Q9GiF2WnyM1GOG/RwTJjzDO+c8Uk2JuRqTKXuXq/lmI70Nqz5WIkAzsbkyaSiRtWzKztcvBcfzKI9PVwKSg=="
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
    private val catalogo = obj("{\"datos\": \"eyJ0aXBvIjoiY2F0YWxvZ28iLCJlbWl0aWRvIjoxLCJyZXZpc2lvbiI6MiwiZnVuY2lvbmVzIjp7InZpc29yZXMiOnsiZXN0YWRvIjoiZ3JhdGlzIiwibm9tYnJlIjoiVmlzb3JlcyAoUERGLCBPZmZpY2UsIHBsYW5vcywgM0TigKYpIn0sImFzaXN0ZW50ZSI6eyJlc3RhZG8iOiJncmF0aXMiLCJub21icmUiOiJBc2lzdGVudGUgY29uIElBIn0sInRhcmVhcyI6eyJlc3RhZG8iOiJncmF0aXMiLCJub21icmUiOiJUYXJlYXMifSwiY3VlbnRhIjp7ImVzdGFkbyI6ImdyYXRpcyIsIm5vbWJyZSI6IkN1ZW50YSBkZSBHb29nbGUifSwicGluZXMiOnsiZXN0YWRvIjoiZ3JhdGlzIiwibm9tYnJlIjoiUGluZXMgZmxvdGFudGVzIn0sInJpZWwubWFyY2Fkb3IiOnsiZXN0YWRvIjoicHJvIiwibm9tYnJlIjoiTWFyY2Fkb3JlcyBlbiBsb3Mgdmlzb3JlcyIsInByb3hpbW8iOnsiZXN0YWRvIjoiZ3JhdGlzIiwiZGVzZGUiOjE4MDAwMDAwMDAwMDB9fSwicmllbC5sYXBpeiI6eyJlc3RhZG8iOiJvY3VsdGEiLCJub21icmUiOiJEaWJ1amFyIHkgYW5vdGFyIGVuIGxvcyB2aXNvcmVzIn0sInJpZWwubWFyY28iOnsiZXN0YWRvIjoib2N1bHRhIiwibm9tYnJlIjoiRnJhbWVzIChtYXJjb3MpIGVuIGxvcyB2aXNvcmVzIn0sIm1hcmNhcy50ZXh0byI6eyJlc3RhZG8iOiJvY3VsdGEiLCJub21icmUiOiJSZXNhbHRhciB0ZXh0byJ9LCJjb21lbnRhcmlvcyI6eyJlc3RhZG8iOiJvY3VsdGEiLCJub21icmUiOiJDb21lbnRhcmlvcyBlbiBsb3MgZG9jdW1lbnRvcyJ9LCJleHBvcnRhciI6eyJlc3RhZG8iOiJvY3VsdGEiLCJub21icmUiOiJFeHBvcnRhciBjb24gYW5vdGFjaW9uZXMifSwiY2hhdHMiOnsiZXN0YWRvIjoib2N1bHRhIiwibm9tYnJlIjoiQ2hhdHMgeSBwcm95ZWN0b3MifSwiY2FwdHVyYXMiOnsiZXN0YWRvIjoib2N1bHRhIiwibm9tYnJlIjoiQ2FwdHVyYXMifSwiZ3JhYmFyIjp7ImVzdGFkbyI6Im9jdWx0YSIsIm5vbWJyZSI6IkdyYWJhciBjbGFzZXMgeSBub3RhcyBkZSB2b3oifSwibGllbnpvIjp7ImVzdGFkbyI6Im9jdWx0YSIsIm5vbWJyZSI6IkxpZW56byB5IGZpZ3VyYXMifSwiaG9yYXJpbyI6eyJlc3RhZG8iOiJvY3VsdGEiLCJub21icmUiOiJIb3JhcmlvIn0sInNpbmNyb25pemFyIjp7ImVzdGFkbyI6Im9jdWx0YSIsIm5vbWJyZSI6IlNpbmNyb25pemFyIGNvbiBlbCBQQyB5IGxhIG51YmUifSwiZXNjYW5lciI6eyJlc3RhZG8iOiJvY3VsdGEiLCJub21icmUiOiJFc2PDoW5lciJ9LCJtdXNpY2EiOnsiZXN0YWRvIjoib2N1bHRhIiwibm9tYnJlIjoiTcO6c2ljYSB5IGthcmFva2UifSwiYW5raSI6eyJlc3RhZG8iOiJvY3VsdGEiLCJub21icmUiOiJUYXJqZXRhcyBBbmtpIn0sInBsYW5vcy5tZWRpciI6eyJlc3RhZG8iOiJvY3VsdGEiLCJub21icmUiOiJNZWRpciBlbiBwbGFub3MifSwid2lkZ2V0cyI6eyJlc3RhZG8iOiJvY3VsdGEiLCJub21icmUiOiJXaWRnZXRzIn19fQ\", \"firma\": \"MEUCIGHMbfMY7-di0Bb59opqp8uf7SmydKU8QXBciPJi_w97AiEA8NHTDSk4Hdh4N6fsrZSfa1qTRUhzEtIIuyFHKcpAb4A\", \"clave\": \"f0acba9f02d7\"}")
    private val licencia = obj("{\"datos\": \"eyJ0aXBvIjoibGljZW5jaWEiLCJ1aWQiOiJnLTEiLCJjdWVudGEiOiJhbmFAeC5wZSIsInBsYW4iOiJwcnVlYmEiLCJoYXN0YSI6NDEwMjQ0NDgwMDAwMCwicmVnYWxhZGFzIjpbInJpZWwubGFwaXoiXSwiYmxvcXVlYWRhIjpmYWxzZSwiZW1pdGlkYSI6MSwidmVuY2UiOjQxMDI0NDQ4MDAwMDB9\", \"firma\": \"MEQCIBoBCQcperxe_AhmgTUt4Gbq_RIL5A30i_CHyMi7cM7wAiAyvYBjn-931QE7KOy_XOGSf95-flYK7zri8ARzQ0sGgQ\", \"clave\": \"f0acba9f02d7\"}")
    private val licenciaVencida = obj("{\"datos\": \"eyJ0aXBvIjoibGljZW5jaWEiLCJ1aWQiOiJnLTEiLCJjdWVudGEiOiJhbmFAeC5wZSIsInBsYW4iOiJwcm8iLCJoYXN0YSI6NDEwMjQ0NDgwMDAwMCwicmVnYWxhZGFzIjpbXSwiYmxvcXVlYWRhIjpmYWxzZSwiZW1pdGlkYSI6MSwidmVuY2UiOjEwMDB9\", \"firma\": \"MEYCIQCQtd2npfYNyZXoHDytCOpoDJzg_SKVAdRB7HNjtuBBUAIhALbIFzv5Wgx3NlYig6sI5QEIVrXqGhgXuJ8mydySR76Y\", \"clave\": \"f0acba9f02d7\"}")

    @Before fun antes() { Disponible.enPruebas = false; Disponible.todoAbierto = false; Disponible.abrirTodo(false); Disponible.aplicar(null, null, quitarLicencia = true) }
    @After fun despues() { Disponible.enPruebas = true; Disponible.todoAbierto = com.forge.pixpin.BuildConfig.EDICION != "restringida" }

    @Test
    fun lo_firmado_por_el_servidor_vale_y_lo_tocado_no() {
        assertNotNull(Firmado.comprobar(catalogo, publica))
        // El «hombre en el medio» cambia lápiz a gratis dentro de los datos: la firma ya no cuadra.
        val datos = String(java.util.Base64.getUrlDecoder().decode((catalogo["datos"] as JsonPrimitive).content)).replace("\"riel.lapiz\":{\"estado\":\"oculta\"", "\"riel.lapiz\":{\"estado\":\"gratis\"")
        val tocado = JsonObject(catalogo + ("datos" to JsonPrimitive(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(datos.toByteArray()))))
        assertNull(Firmado.comprobar(tocado, publica))
        // Con otra clave pública (otro servidor), tampoco.
        assertNull(Firmado.comprobar(catalogo, "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbliPCwiRJPlrksqI+89K2KWguxzgtPC9U3Y+1GkgXi7CMaHZ8Duv3IiLLP3wnhe8qLOL9KvlmlmibXBjN6vaUw=="))
        assertNull(Firmado.comprobar(null as JsonObject?, publica))
    }

    @Test
    fun el_catalogo_y_la_licencia_deciden_que_se_ve_y_que_se_puede() {
        // Sin nada del servidor: lo de fábrica (el relanzamiento).
        assertTrue(Disponible.ve(Disponible.VISORES)); assertTrue(Disponible.puede(Disponible.ASISTENTE))
        assertFalse(Disponible.ve(Disponible.RIEL_LAPIZ)); assertFalse(Disponible.ve(Disponible.CHATS))
        assertFalse(Disponible.ve("una-que-no-existe"))
        Disponible.aplicar(Firmado.comprobar(catalogo, publica), null)
        // Marcador en Pro: se ve, pero sin Pro no se puede.
        assertTrue(Disponible.ve(Disponible.RIEL_MARCADOR)); assertFalse(Disponible.puede(Disponible.RIEL_MARCADOR))
        assertEquals(Disponible.Estado.GRATIS to 1800000000000L, Disponible.ahora.value.proximos[Disponible.RIEL_MARCADOR])
        assertEquals("Marcadores en los visores", Disponible.ahora.value.nombres[Disponible.RIEL_MARCADOR])
        // Con la prueba: puede; y el lápiz, oculto para todos, a ella se lo regalaron.
        Disponible.aplicar(null, Firmado.comprobar(licencia, publica))
        assertTrue(Disponible.conPro()); assertTrue(Disponible.puede(Disponible.RIEL_MARCADOR))
        assertTrue(Disponible.ve(Disponible.RIEL_LAPIZ)); assertTrue(Disponible.puede(Disponible.RIEL_LAPIZ))
        assertFalse(Disponible.ve(Disponible.CHATS))
        // Una licencia vencida (sin renovar) no da nada, aunque diga Pro.
        Disponible.aplicar(null, Firmado.comprobar(licenciaVencida, publica))
        assertFalse(Disponible.conPro()); assertFalse(Disponible.puede(Disponible.RIEL_MARCADOR))
    }
}
