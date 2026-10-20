package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** **Crear una automatización desde el asistente** (PC `automatizaciones::de_receta` y sus pruebas): la forma del PC y lo que no vale. */
class CrearAutomatizacionTest {
    private fun crear(s: String) = Automatizaciones.deReceta(Json.parseToJsonElement(s) as JsonObject, "auto-1", 7)
    private fun mal(s: String) = try { crear(s); fail(s); "" } catch (e: IllegalArgumentException) { e.message!! }

    @Test fun `la receta sale con la forma del PC`() {
        val a = crear("""{"nombre":"Hora","disparador":{"tipo":"a_la_hora","hora":"08:00","dias":[1,5]},"pasos":"dime la hora"}""")
        assertEquals("""{"tipo":"a_la_hora","hora":"08:00","dias":[1,5]}""", a["disparador"].toString())
        assertEquals("""[{"tipo":"asistente","pedido":"dime la hora"}]""", a["pasos"].toString())
        assertEquals(null, a["aparato"])
        val mixto = crear("""{"nombre":"x","disparador":{"tipo":"al_capturar"},"pasos":[
            {"herramienta":"escribir_en_chat","args":{"texto":"{captura}"},"proyecto":"Obra"},
            {"asistente":"hazle OCR","imagenes":["{captura}"]},
            {"tipo":"accion","herramienta":"anadir_tarea","args":{"texto":"{resultado}"}}]}""")
        val pasos = mixto["pasos"] as JsonArray
        assertEquals(3, pasos.size)
        assertEquals("accion", (pasos[0] as JsonObject)["tipo"]!!.jsonPrimitive.content)
        assertEquals("asistente", (pasos[1] as JsonObject)["tipo"]!!.jsonPrimitive.content)
        // Y entra en la lista (la que viaja por el chat «Asistente»).
        val l = Automatizaciones.Lista(); l.anadir(mixto)
        assertEquals(1, l.buenas().size)
    }

    @Test fun `lo que no vale se dice`() {
        assertTrue(mal("""{"nombre":"x","disparador":"a_mano","pasos":[{"herramienta":"volar","args":{}}]}""").contains("no hay ninguna acción «volar»"))
        assertTrue(mal("""{"nombre":"x","disparador":{"tipo":"a_la_hora","hora":"25:00"},"pasos":"hola"}""").contains("hora"))
        assertTrue(mal("""{"nombre":"x","disparador":"cada_luna","pasos":"hola"}""").contains("disparador"))
        assertTrue(mal("""{"nombre":"x","disparador":"a_mano","pasos":[]}""").contains("no tiene pasos"))
        assertTrue(mal("""{"nombre":"","disparador":"a_mano","pasos":"hola"}""").contains("nombre"))
        assertTrue(mal("""{"nombre":"x","disparador":"a_mano","pasos":[3]}""").contains("paso 1"))
        assertTrue(mal("""{"nombre":"x","disparador":"a_mano","pasos":[${(0..10).joinToString(",") { "\"paso $it\"" }}]}""").contains("como mucho"))
        // Los bucles y las que crean otras.
        assertTrue(mal("""{"nombre":"x","disparador":{"tipo":"al_capturar"},"pasos":[{"herramienta":"capturar_pantalla"}]}""").contains("bucle"))
        assertTrue(mal("""{"nombre":"x","disparador":{"tipo":"al_sincronizar"},"pasos":[{"herramienta":"sincronizar_movil"}]}""").contains("bucle"))
        crear("""{"nombre":"x","disparador":"a_mano","pasos":[{"herramienta":"sincronizar_movil"}]}""")
        assertTrue(mal("""{"nombre":"x","disparador":"a_mano","pasos":[{"herramienta":"crear_automatizacion","args":{"nombre":"y","disparador":"a_mano","pasos":"hola"}}]}""").contains("automatizaciones"))
    }
}
