package com.forge.pixpin.ia

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ExcalidrawJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * **Las gráficas, la física y los problemas salen como en el PC** (el oráculo, `oraculo.sh elementos`):
 * los mismos avisos (lo calculado, palabra por palabra) o el mismo error; y las mismas piezas, en el
 * mismo orden, del mismo tipo, con los mismos textos y en el mismo sitio (a un píxel: el PC cuenta en
 * `f32`, y una fracción de la leyenda compone apenas distinta). Se mide con la tabla del PC sin pantalla ([AnchosDeLetra]), como él en sus pruebas.
 */
class PizarraDelPcTest {
    private fun lineas(r: String) = javaClass.getResource(r)!!.readText().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    private fun num(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private val fallos = mutableListOf<String>()

    private fun falla(m: String) { if (fallos.size < 60) fallos += m }

    private fun igual(donde: String, a: Any?, b: Any?) { if (a != b) falla("$donde: PC $a / teléfono $b") }

    private fun cerca(donde: String, a: Double?, b: Double?, tol: Double = 1.0) {
        if (a == null && b == null) return
        if (a == null || b == null || abs(a - b) > tol) falla("$donde: PC $a / teléfono $b")
    }

    private fun puntos(e: JsonElement?): List<Pair<Double, Double>> = (e as? JsonArray)?.map { p ->
        if (p is JsonArray) num(p[0])!! to num(p[1])!! else p.jsonObject.let { num(it["x"])!! to num(it["y"])!! }
    }.orEmpty()

    private fun comparar(donde: String, pc: JsonObject, mio: JsonObject) {
        igual("$donde: tipo", pc["type"]!!.jsonPrimitive.content, mio["type"]!!.jsonPrimitive.content)
        pc["text"]?.let { igual("$donde: texto", it.jsonPrimitive.content, mio["text"]?.jsonPrimitive?.content.orEmpty()) }
        for (k in listOf("x", "y", "width", "height")) cerca("$donde.$k", num(pc[k]), num(mio[k]))
        for (k in listOf("fontSize", "strokeWidth", "unidad", "pasoDeNumeros", "pasoDeCuadros")) cerca("$donde.$k", num(pc[k]), num(mio[k]), 1e-3)
        igual("$donde: color", pc["strokeColor"]?.jsonPrimitive?.content, mio["strokeColor"]?.jsonPrimitive?.content)
        igual("$donde: trazo", pc["strokeStyle"]?.jsonPrimitive?.content ?: "solid", mio["strokeStyle"]?.jsonPrimitive?.content ?: "solid")
        val a = puntos(pc["points"]); val b = puntos(mio["points"])
        igual("$donde: puntos", a.size, b.size)
        if (a.size != b.size) return
        for ((i, par) in a.zip(b).withIndex()) { cerca("$donde.points[$i].x", par.first.first, par.second.first); cerca("$donde.points[$i].y", par.first.second, par.second.second) }
    }

    private fun correr(casos: String, delPc: String, dibujar: (Llamada) -> List<Element>) {
        val cs = lineas(casos); val pcs = lineas(delPc)
        assertEquals(cs.size, pcs.size)
        for ((i, par) in cs.zip(pcs).withIndex()) {
            val (c, pc) = par
            val donde = "caso $i ${c["args"].toString().take(60)}"
            val r = runCatching { Herramientas.validar(LlamadaCruda("o", c["nombre"]!!.jsonPrimitive.content, c["args"].toString()), Herramientas.catalogo()) }
            pc["error"]?.let { e ->
                igual(donde, e.jsonPrimitive.content, (r.exceptionOrNull() as? ErrorHerramienta)?.message ?: "salió bien")
                return@let
            } ?: run {
                val v = r.getOrElse { falla("$donde: ${it.message}"); return@run }
                igual("$donde: avisos", pc["avisos"]!!.jsonArray.map { it.jsonPrimitive.content }, v.avisos)
                val mios = dibujar(v.llamada).map { ExcalidrawJson.encodeToJsonElement(Element.serializer(), it).jsonObject }
                val suyos = pc["elementos"]!!.jsonArray.map { it.jsonObject }
                val tipos = { l: List<JsonObject> -> l.map { it["type"]!!.jsonPrimitive.content } }
                igual("$donde: piezas", tipos(suyos), tipos(mios))
                if (tipos(suyos) == tipos(mios)) for ((k, p2) in suyos.zip(mios).withIndex()) comparar("$donde #$k", p2.first, p2.second)
            }
        }
        if (fallos.isNotEmpty()) fail(fallos.joinToString("\n"))
    }

    @Test fun `las graficas de funciones salen como en el PC`() = correr("/ia/dibujos/funciones.jsonl", "/ia/dibujos/funciones-del-pc.jsonl") { l ->
        Funciones.piezas((l as Llamada.Funciones).pedido, AnchosDeLetra.MEDIDA)
    }

    @Test fun `los diagramas de fisica salen como en el PC`() = correr("/ia/dibujos/fisica.jsonl", "/ia/dibujos/fisica-del-pc.jsonl") { l ->
        Fisica.piezas((l as Llamada.Fisica).diagrama, AnchosDeLetra.MEDIDA)
    }

    @Test fun `los problemas en limpio salen como en el PC`() = correr("/ia/dibujos/problema.jsonl", "/ia/dibujos/problema-del-pc.jsonl") { l ->
        Problema.piezas((l as Llamada.Problema).problema, AnchosDeLetra.MEDIDA).first
    }
}
