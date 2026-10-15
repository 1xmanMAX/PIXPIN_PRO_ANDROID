package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * **Las ilustraciones en SVG salen como en el PC** (`svg.rs` + `lienzo.rs`): 10 llamadas a
 * `dibujar_svg` (curvas, arcos, transformaciones, `<style>`, degradados, `use`, grafito, a mano,
 * `script` ignorado y cuatro que se rechazan) pasadas por el PC de verdad y por aquí, con la misma
 * semilla: los mismos elementos de Excalidraw, campo a campo (números con tolerancia 1e-6).
 */
class SvgTest {
    private fun iguales(donde: String, a: JsonElement, b: JsonElement) {
        when {
            a is JsonObject && b is JsonObject -> {
                assertEquals("$donde: claves", a.keys.sorted(), b.keys.sorted())
                for (k in a.keys) iguales("$donde.$k", a[k]!!, b[k]!!)
            }
            a is JsonArray && b is JsonArray && donde.endsWith(".points") -> cercanos(donde, a, b)
            a is JsonArray && b is JsonArray -> {
                assertEquals("$donde: largo", a.size, b.size)
                a.indices.forEach { iguales("$donde[$it]", a[it], b[it]) }
            }
            a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a !is JsonNull && b !is JsonNull && a.doubleOrNull != null && b.doubleOrNull != null ->
                assertEquals(donde, a.doubleOrNull!!, b.doubleOrNull!!, 1e-6 * maxOf(1.0, kotlin.math.abs(a.doubleOrNull!!)))
            else -> assertEquals(donde, a, b)
        }
    }

    /**
     * Los puntos de una curva tras Ramer-Douglas-Peucker: en un arco perfecto hay empates de
     * distancia que el seno/coseno del PC y el de Java (un ulp de diferencia) rompen distinto. Se
     * pide lo que importa: casi los mismos puntos y todos sobre la línea del PC (medio píxel).
     */
    private fun cercanos(donde: String, pc: JsonArray, mios: JsonArray) {
        fun pts(a: JsonArray) = a.map { p -> p.jsonArray.let { it[0].jsonPrimitive.content.toDouble() to it[1].jsonPrimitive.content.toDouble() } }
        val a = pts(pc); val b = pts(mios)
        assertTrue("$donde: ${a.size} contra ${b.size} puntos", kotlin.math.abs(a.size - b.size) <= 2)
        fun distancia(p: Pair<Double, Double>, l: List<Pair<Double, Double>>) = l.zipWithNext().minOf { (u, v) ->
            val dx = v.first - u.first; val dy = v.second - u.second
            val t = if (dx * dx + dy * dy < 1e-12) 0.0 else (((p.first - u.first) * dx + (p.second - u.second) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            kotlin.math.hypot(p.first - u.first - t * dx, p.second - u.second - t * dy)
        }
        for (p in b) assertTrue("$donde: $p lejos de la línea del PC", distancia(p, a) < 0.5)
        for (p in a) assertTrue("$donde: $p del PC lejos de la mía", distancia(p, b) < 0.5)
    }

    @Test fun `las mismas ilustraciones dan los mismos elementos que el PC`() {
        val entradas = javaClass.getResource("/ia/svg-entrada.jsonl")!!.readText().lines().filter { it.isNotBlank() }
        val delPc = javaClass.getResource("/ia/svg-del-pc.jsonl")!!.readText().lines().filter { it.isNotBlank() }
        assertEquals(10, entradas.size)
        for ((i, linea) in entradas.withIndex()) {
            val e = Json.parseToJsonElement(linea).jsonObject
            val pc = Json.parseToJsonElement(delPc[i]).jsonObject
            val cruda = LlamadaCruda("1", e["nombre"]!!.jsonPrimitive.content, e["argumentos"].toString())
            val r = runCatching { Herramientas.validar(cruda, Herramientas.catalogo()) }
            val error = pc["error"]?.jsonPrimitive?.content
            if (error != null) {
                val mio = (r.exceptionOrNull() as? ErrorHerramienta)?.paraElModelo(Herramientas.catalogo()) ?: fail("caso $i tenía que fallar: $error")
                assertEquals("caso $i", error.substringBefore(" ("), mio.toString().substringBefore(" ("))
                continue
            }
            val v = r.getOrThrow()
            assertEquals("caso $i avisos", pc["avisos"]!!.jsonArray.map { it.jsonPrimitive.content }, v.avisos)
            val mios = JsonArray(Lienzo.elementos(v.llamada, 0.0 to 0.0, 0xABC).map(::puntosComoElPc))
            iguales("caso $i", pc["elementos"]!!, mios)
        }
    }

    @Test fun `lo hostil se rechaza y lo de fuera no se toca`() {
        val avisos = mutableListOf<String>()
        assertTrue(runCatching { Svg.leer("<svg><!DOCTYPE a [<!ENTITY b SYSTEM 'file:///etc/passwd'>]><text>&b;</text></svg>", doubleArrayOf(0.0, 0.0, 800.0, 600.0), avisos) }.isFailure)
        assertTrue(runCatching { Svg.leer("<svg>" + "<g>".repeat(40) + "<rect width='1' height='1'/>" + "</g>".repeat(40) + "</svg>", doubleArrayOf(0.0, 0.0, 800.0, 600.0), avisos) }.exceptionOrNull()!!.message!!.contains("anida"))
        val p = Svg.leer("<svg viewBox='0 0 10 10'><image href='http://x/y.png'/><use href='http://x/a.svg#b'/><rect width='5' height='5'/></svg>", doubleArrayOf(0.0, 0.0, 800.0, 600.0), avisos)
        assertEquals(1, p.size)
        assertTrue(avisos.last(), avisos.last().contains("image") && avisos.last().contains("use externo"))
        assertEquals(listOf(1.0, 2.0, 3.0, -4.5e1, 0.5), Svg.numerosSueltos("1,2 3-4.5e1.5"))
    }
}
