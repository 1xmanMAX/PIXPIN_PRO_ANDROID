package com.forge.pixpin.sincro.nube

import com.forge.pixpin.sincro.sha256
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * **Una nube de mentira en memoria** (como `nube/falsa.rs` del PC), con las reglas de §10 del
 * manual: revisiones, 409 con la revisión, `faltan`, `cambios` y el inventario.
 */
class NubeFalsa(private val conDeltas: Boolean = true) : Transporte {
    val objetos = HashMap<String, ByteArray>()
    var bytesSubidos = 0L
    val cuerpos = ArrayList<String>()

    private class Chat {
        var revision = 0L
        var nombre = ""
        val mensajes = LinkedHashMap<String, JsonElement>()
        var proyecto: JsonElement? = null
        val archivos = LinkedHashMap<String, JsonObject>()
        val log = ArrayList<JsonObject>()
        var reinicio = 0L
        var compartido: JsonElement? = null
    }

    private val chats = HashMap<String, Chat>()

    private fun ok(o: JsonElement) = Contestado(200, o.toString().toByteArray())
    private fun error(c: Int, extra: Map<String, JsonElement> = emptyMap()) = Contestado(c, JsonObject(mapOf("error" to JsonPrimitive("e$c")) + extra).toString().toByteArray())

    private fun faltan(archivos: Collection<JsonObject>): List<String> = archivos.flatMap { a ->
        listOf((a["sha256"] as JsonPrimitive).content) + (a["deltas"] as? JsonArray).orEmpty().map { ((it as JsonObject)["sha256"] as JsonPrimitive).content }
    }.filter { it !in objetos }.distinct()

    @Synchronized
    override fun enviar(metodo: String, url: String, cabeceras: List<Pair<String, String>>, cuerpo: ByteArray, segundos: Long): Contestado {
        if (cabeceras.none { it.first == "Authorization" && it.second.startsWith("Bearer pxn_") }) return error(401)
        val ruta = url.substringAfter("/nube/api/v1/")
        val camino = ruta.substringBefore('?')
        val q = ruta.substringAfter('?', "").split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
        if (metodo == "PUT" || metodo == "PATCH") { bytesSubidos += cuerpo.size; if (!camino.startsWith("objetos/")) cuerpos += cuerpo.decodeToString() }
        if (camino == "hola") return ok(buildJsonObject {
            put("protocolo", "pixpin-nube"); put("version", 1)
            put("aparato", buildJsonObject { put("id", "ap1"); put("nombre", "Tel"); put("espacio", "max") })
            put("capacidades", JsonArray(if (conDeltas) listOf(JsonPrimitive("deltas1")) else emptyList()))
            put("novedades", 0)
        })
        if (camino == "yo") return ok(buildJsonObject { put("ok", true) })
        if (camino == "chats" && metodo == "GET") return ok(buildJsonObject {
            put("chats", JsonArray(chats.map { (k, c) -> buildJsonObject { put("espacio", "max"); put("chat", k); put("nombre", c.nombre); put("revision", c.revision); put("compartido", c.compartido != null) } }))
        })
        if (camino.startsWith("objetos/")) {
            val sha = camino.removePrefix("objetos/")
            return when (metodo) {
                "HEAD" -> if (sha in objetos) Contestado(200, ByteArray(0)) else error(404)
                "PUT" -> { require(sha256(cuerpo) == sha); objetos[sha] = cuerpo; ok(buildJsonObject { put("ok", true) }) }
                else -> objetos[sha]?.let { Contestado(200, it) } ?: error(404)
            }
        }
        if (camino.startsWith("chats/")) {
            val resto = camino.removePrefix("chats/")
            val chat = java.net.URLDecoder.decode(resto.substringBefore('/'), "UTF-8")
            val c = chats[chat]
            if (resto.endsWith("/cambios")) {
                if (!conDeltas) return error(404)
                val desde = q["desde"]?.toLong() ?: 0
                if (c == null) return error(404)
                return ok(buildJsonObject {
                    put("revision", c.revision)
                    put("reiniciar", desde < c.reinicio)
                    put("cambios", JsonArray(c.log.filter { ((it["revision"] as JsonPrimitive).content.toLong()) > desde }))
                })
            }
            when (metodo) {
                "GET" -> {
                    c ?: return error(404)
                    return ok(buildJsonObject {
                        put("nombre", c.nombre); put("mensajes", JsonArray(c.mensajes.values.toList())); put("proyecto", c.proyecto ?: JsonNull)
                        put("archivos", JsonArray(c.archivos.values.toList())); put("revision", c.revision); put("espacio", "max")
                        c.compartido?.let { put("compartido", it) }
                    })
                }
                "PUT" -> {
                    val inv = Nube.JSON.parseToJsonElement(cuerpo.decodeToString()) as JsonObject
                    val ch = chats.getOrPut(chat) { Chat() }
                    val base = (inv["base"] as? JsonPrimitive)?.content?.toLong() ?: 0
                    if (ch.revision > 0 && base != ch.revision) return error(409, mapOf("revision" to JsonPrimitive(ch.revision)))
                    val arch = (inv["archivos"] as JsonArray).map { it as JsonObject }
                    val f = faltan(arch)
                    ch.nombre = (inv["nombre"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    ch.mensajes.clear(); (inv["mensajes"] as JsonArray).forEach { ch.mensajes[((it as JsonObject)["id"] as JsonPrimitive).content] = it }
                    ch.proyecto = inv["proyecto"]?.takeIf { it !is JsonNull }
                    ch.archivos.clear(); arch.forEach { ch.archivos[(it["rel"] as JsonPrimitive).content] = it }
                    inv["compartido"]?.let { ch.compartido = it }
                    ch.revision++
                    ch.reinicio = ch.revision
                    return ok(buildJsonObject { put("ok", true); put("espacio", "max"); put("revision", ch.revision); put("faltan", JsonArray(f.map(::JsonPrimitive))) })
                }
                "PATCH" -> {
                    if (!conDeltas) return error(405)
                    val p = Nube.JSON.parseToJsonElement(cuerpo.decodeToString()) as JsonObject
                    val ch = chats.getOrPut(chat) { Chat() }
                    val base = (p["base"] as JsonPrimitive).content.toLong()
                    if (base != ch.revision) return error(409, mapOf("revision" to JsonPrimitive(ch.revision)))
                    val pr = Parche.de(p)
                    pr.nombre?.let { ch.nombre = it }
                    pr.compartido?.let { ch.compartido = it }
                    pr.poner.forEach { ch.mensajes[((it as JsonObject)["id"] as JsonPrimitive).content] = it }
                    pr.quitar.forEach { ch.mensajes.remove(it) }
                    if (p.containsKey("proyecto")) ch.proyecto = p["proyecto"]?.takeIf { it !is JsonNull }
                    pr.archivosPoner.forEach { ch.archivos[it.rel] = it.json() }
                    pr.archivosQuitar.forEach { ch.archivos.remove(it) }
                    ch.revision++
                    ch.log += JsonObject(p + ("revision" to JsonPrimitive(ch.revision)))
                    val f = faltan(pr.archivosPoner.map { it.json() })
                    return ok(buildJsonObject { put("ok", true); put("espacio", "max"); put("revision", ch.revision); put("faltan", JsonArray(f.map(::JsonPrimitive))) })
                }
            }
        }
        if (camino == "novedades") return ok(buildJsonObject { put("seq", 0); put("reiniciar", false); put("cambios", JsonArray(emptyList())) })
        return error(404)
    }
}
