package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`automatizaciones.json` va y vuelve sin perder lo que no se entiende** (§3.5;
 * `el_formato_va_y_vuelve_sin_perder_lo_que_no_entiende` del PC) y las recetas son
 * automatizaciones `a_mano` con botón (`recetas.rs`).
 */
class AutomatizacionesTest {
    // El ejemplo del manual del PC, más una entrada de una versión del futuro y claves extra.
    private val delPc = """{ "version": 1, "deOtraVersion": {"x": 1},
      "automatizaciones": [
        { "id": "auto-1789412424738-0", "nombre": "Capturas a la obra con OCR", "activa": true,
          "disparador": { "tipo": "al_capturar" },
          "pasos": [
            { "tipo": "accion", "herramienta": "escribir_en_chat", "args": { "texto": "Captura {fecha} {hora}" }, "proyecto": "Obra", "imagenes": ["{captura}"] },
            { "tipo": "asistente", "pedido": "Hazle OCR a la captura", "imagenes": ["{captura}"] }
          ],
          "aparato": "K7Q2", "creada": 1789412424738, "tocada": 1789412424738 },
        { "id": "auto-1760390000000-0", "nombre": "Resumir", "activa": true,
          "disparador": { "tipo": "a_mano" },
          "pasos": [ { "tipo": "asistente", "pedido": "Resume lo último en 5 puntos" } ],
          "creada": 1760390000000, "tocada": 1760390000000, "boton": true, "icono": "📝", "orden": 0, "delMovil": "algo" },
        { "id": "auto-9", "nombre": "Del futuro", "disparador": { "tipo": "al_amanecer" }, "pasos": [] }
      ] }"""

    private fun canon(s: String) = ordenado(Json.parseToJsonElement(s)).toString()

    @Test fun `el formato va y vuelve sin perder lo que no entiende`() {
        val l = Automatizaciones.Lista.deJson(delPc)
        assertEquals(3, l.entradas.size)
        assertEquals(2, l.buenas().size)
        assertTrue(l.entradas[2].motivo!!.contains("al_amanecer"))
        assertEquals(canon(delPc), canon(l.aJson()))
        assertTrue(Automatizaciones.correAqui(l.buenas()[0], "K7Q2"))
        assertTrue(!Automatizaciones.correAqui(l.buenas()[0], "PC01"))
        assertTrue(Automatizaciones.correAqui(l.buenas()[1], "PC01"))
    }

    @Test fun `una receta es una automatizacion a mano con boton, y se mueve y se borra conservando lo demas`() {
        val l = Automatizaciones.Lista.deJson(delPc)
        assertEquals(listOf("Resumir"), Automatizaciones.recetas(l).map { it.nombre })
        val r = Automatizaciones.anadirReceta(l, "", "", "Dibuja un plano de la casa", 10)
        assertEquals("🎨", r.icono); assertEquals("Dibuja un plano de la", r.nombre); assertEquals(1L, r.orden)
        val json = Json.parseToJsonElement(l.aJson()).jsonObject
        val nueva = json["automatizaciones"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == r.id }
        assertEquals("a_mano", nueva["disparador"]!!.jsonObject["tipo"]!!.jsonPrimitive.content)
        assertEquals("true", nueva["boton"].toString())
        assertNull(Automatizaciones.motivo(nueva))
        Automatizaciones.moverReceta(l, r.id, -1, 20)
        assertEquals(listOf(r.id, "auto-1760390000000-0"), Automatizaciones.recetas(l).map { it.id })
        // Mover no tira lo que el móvil o el PC añadieron.
        assertEquals("\"algo\"", l.buscar("auto-1760390000000-0")!!["delMovil"].toString())
        Automatizaciones.cambiarReceta(l, r.id, 30) { it.copy(nombre = "Plano") }
        assertEquals("Plano", Automatizaciones.recetas(l).first().nombre)
        Automatizaciones.borrarReceta(l, r.id)
        assertEquals(listOf("Resumir"), Automatizaciones.recetas(l).map { it.nombre })
        // Lo que no es receta no se borra como receta.
        assertTrue(runCatching { Automatizaciones.borrarReceta(l, "auto-1789412424738-0") }.isFailure)
        assertTrue(runCatching { Automatizaciones.anadirReceta(l, "", "", "   ", 1) }.isFailure)
    }

    @Test fun `sin boton o con dos pasos no es receta, y la hora se valida`() {
        val a = Json.parseToJsonElement("""{"id":"a","nombre":"x","disparador":{"tipo":"a_mano"},"pasos":[{"tipo":"asistente","pedido":"p"}]}""").jsonObject
        assertNull(Automatizaciones.receta(a))
        assertNotNull(Automatizaciones.receta(JsonObject(a + ("boton" to kotlinx.serialization.json.JsonPrimitive(true)))))
        assertEquals(8 * 60 + 5, Automatizaciones.minutoDelDia("08:05")); assertNull(Automatizaciones.minutoDelDia("25:00"))
        assertTrue(Automatizaciones.motivo(Json.parseToJsonElement("""{"id":"h","nombre":"x","disparador":{"tipo":"a_la_hora","hora":"7"},"pasos":[{"tipo":"asistente","pedido":"p"}]}"""))!!.contains("hora"))
    }
}
