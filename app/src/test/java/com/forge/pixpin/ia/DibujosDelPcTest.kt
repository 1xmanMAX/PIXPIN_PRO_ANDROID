package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * **Lo que dibuja el asistente sale igual que en el PC**: cada llamada de `casos.jsonl` (cronograma…)
 * se dibujó con el PC de verdad (el oráculo, `oraculo.sh elementos`) y aquí: los mismos elementos, en
 * el mismo orden, con los mismos campos (los números, a una millonésima), o el mismo error.
 */
class DibujosDelPcTest {
    private fun lineas(r: String) = javaClass.getResource(r)!!.readText().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    /** Un punto: `[x, y]` en el PC, `{x, y}` en el teléfono (los dos lectores entienden los dos). */
    private fun punto(e: JsonElement?): JsonElement? =
        if (e is JsonObject && e.keys == setOf("x", "y")) JsonArray(listOf(e["x"]!!, e["y"]!!)) else e

    private fun igual(donde: String, a0: JsonElement?, b0: JsonElement?) {
        val a = punto(a0); val b = punto(b0)
        when {
            a is JsonObject && b is JsonObject -> {
                assertEquals("$donde: campos", a.keys.sorted(), b.keys.sorted())
                for (k in a.keys) igual("$donde.$k", a[k], b[k])
            }
            a is JsonArray && b is JsonArray -> {
                assertEquals("$donde: largo", a.size, b.size)
                for (i in a.indices) igual("$donde[$i]", a[i], b[i])
            }
            a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a.doubleOrNull != null && b.doubleOrNull != null ->
                if (abs(a.doubleOrNull!! - b.doubleOrNull!!) > 1e-6) fail("$donde: ${a.content} / ${b.content}")
            (a == null || a is JsonNull) && (b == null || b is JsonNull) -> {}
            else -> assertEquals(donde, a.toString(), b.toString())
        }
    }

    @Test fun `los dibujos salen igual que en el PC`() {
        val casos = lineas("/ia/dibujos/casos.jsonl")
        val delPc = lineas("/ia/dibujos/del-pc.jsonl")
        assertEquals(casos.size, delPc.size)
        for ((i, par) in casos.zip(delPc).withIndex()) {
            val (c, pc) = par
            val nombre = c["nombre"]!!.jsonPrimitive.content
            val donde = "caso $i ($nombre)"
            val semilla = c["semilla"]?.jsonPrimitive?.longOrNull ?: 7
            val r = runCatching { Herramientas.validar(LlamadaCruda("o", nombre, c["args"].toString()), Herramientas.catalogo()) }
            val error = pc["error"]?.jsonPrimitive?.content
            if (error != null) {
                assertEquals(donde, error, (r.exceptionOrNull() as? ErrorHerramienta)?.message ?: "salió bien: ${r.getOrNull()}")
                continue
            }
            val v = r.getOrElse { fail("$donde: ${it.message}"); return }
            assertEquals("$donde: avisos", pc["avisos"].toString(), JsonArray(v.avisos.map { JsonPrimitive(it) }).toString())
            val mios = JsonArray(Lienzo.elementos(v.llamada, 0.0 to 0.0, semilla))
            igual(donde, pc["elementos"], mios)
        }
    }
}
