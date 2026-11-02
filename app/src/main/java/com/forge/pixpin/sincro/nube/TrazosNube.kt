package com.forge.pixpin.sincro.nube

import com.forge.pixpin.sincro.Canonico
import com.forge.pixpin.sincro.sha256
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * **Lo que viaja de un lienzo o una tinta: solo los trazos nuevos** (`deltas1`, puerto de
 * `nube/trazos.rs` del PC). Un delta es un JSON pequeño
 * `{pixpinDelta:1, tipo:"excalidraw", rel, base, elementos, borrados, archivos?, appState?}` que se
 * apunta en `deltas` de la línea del archivo; lo que vale es el original con los deltas en orden.
 *
 * **La regla** (la de Excalidraw): por cada id gana la `version` mayor y, a igualdad, el
 * `versionNonce` menor. Lo borrado es una lápida (`isDeleted: true`). Puro, sobre JSON.
 */
object TrazosNube {
    const val MAX_DELTAS = 20

    fun esTrazos(rel: String) = rel.endsWith(".excalidraw.gz") || rel.endsWith(".excalidraw")

    /** `version` y `versionNonce`. */
    data class Version(val version: Long, val nonce: Long)

    private fun entero(v: JsonElement?): Long = (v as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L

    fun versionDe(e: JsonObject) = Version(entero(e["version"]), entero(e["versionNonce"]))

    /** Si [a] gana a [b]. Iguales del todo: no gana ninguna. */
    fun gana(a: Version, b: Version) = a.version > b.version || (a.version == b.version && a.nonce < b.nonce)

    private fun idDe(e: JsonObject) = (e["id"] as? JsonPrimitive)?.contentOrNull

    private fun elementos(escena: JsonObject): List<JsonObject> = (escena["elements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun versiones(escena: JsonObject): Map<String, Version> = elementos(escena).mapNotNull { e -> idDe(e)?.let { it to versionDe(e) } }.toMap()

    fun imagenes(escena: JsonObject): Set<String> = (escena["files"] as? JsonObject)?.keys.orEmpty()

    fun resumenDeEstado(escena: JsonObject): String? = escena["appState"]?.let { sha256(Canonico.de(it.toString()).toByteArray()) }

    /** Lo que la nube ya tiene de un archivo de trazos. */
    data class Conocido(val versiones: Map<String, Version> = emptyMap(), val imagenes: Set<String> = emptySet(), val estado: String? = null) {
        /** Lo conocido después de poner encima [delta]. */
        fun con(delta: JsonObject): Conocido {
            val v = versiones.toMutableMap()
            for (e in (delta["elementos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }) {
                val id = idDe(e) ?: continue
                val ve = versionDe(e)
                val k = v[id]
                if (k == null || gana(ve, k)) v[id] = ve
            }
            for (id in (delta["borrados"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }) {
                val k = v[id] ?: Version(0, 0)
                v[id] = Version(k.version + 1, k.nonce)
            }
            val im = imagenes + (delta["archivos"] as? JsonObject)?.keys.orEmpty()
            val es = delta["appState"]?.let { sha256(Canonico.de(it.toString()).toByteArray()) } ?: estado
            return Conocido(v, im, es)
        }

        companion object {
            fun de(escena: JsonObject) = Conocido(versiones(escena), imagenes(escena), resumenDeEstado(escena))
        }
    }

    /**
     * **El delta de [escena] sobre lo [conocido]**, o null si no hay nada que subir: los elementos
     * nuevos y los que ganan, las fotos nuevas y el `appState` si cambió. Lo que falta no se manda
     * como borrado (suele ser lo que aún no llegó).
     */
    fun calcular(rel: String, base: String, conocido: Conocido, escena: JsonObject): JsonObject? {
        val nuevos = elementos(escena).filter { e ->
            val id = idDe(e) ?: return@filter false
            val k = conocido.versiones[id]
            k == null || gana(versionDe(e), k)
        }
        val archivos = (escena["files"] as? JsonObject).orEmpty().filterKeys { it !in conocido.imagenes }
        val estado = resumenDeEstado(escena)?.takeIf { it != conocido.estado }?.let { escena["appState"] }
        if (nuevos.isEmpty() && archivos.isEmpty() && estado == null) return null
        val m = linkedMapOf<String, JsonElement>(
            "pixpinDelta" to JsonPrimitive(1), "tipo" to JsonPrimitive("excalidraw"), "rel" to JsonPrimitive(rel),
            "base" to JsonPrimitive(base), "elementos" to JsonArray(nuevos), "borrados" to JsonArray(emptyList())
        )
        if (archivos.isNotEmpty()) m["archivos"] = JsonObject(archivos)
        if (estado != null) m["appState"] = estado
        return JsonObject(m)
    }

    fun esDelta(v: JsonObject) = entero(v["pixpinDelta"]) == 1L && (v["tipo"] as? JsonPrimitive)?.contentOrNull == "excalidraw"

    /** **Pone un delta encima de una escena**, con la regla. Devuelve la escena y si cambió algo. */
    fun aplicar(escena0: JsonObject?, delta: JsonObject): Pair<JsonObject, Boolean> {
        val escena = escena0 ?: JsonObject(mapOf("type" to JsonPrimitive("excalidraw"), "version" to JsonPrimitive(2), "elements" to JsonArray(emptyList()), "files" to JsonObject(emptyMap())))
        val lista = elementos(escena).toMutableList()
        val donde = HashMap<String, Int>()
        lista.forEachIndexed { i, e -> idDe(e)?.let { donde.putIfAbsent(it, i) } }
        var cambio = false
        for (e in (delta["elementos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }) {
            val id = idDe(e) ?: continue
            val i = donde[id]
            if (i == null) { donde[id] = lista.size; lista += e; cambio = true }
            else if (gana(versionDe(e), versionDe(lista[i]))) { lista[i] = e; cambio = true }
        }
        for (id in (delta["borrados"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }) {
            val i = donde[id] ?: continue
            val e = lista[i]
            if ((e["isDeleted"] as? JsonPrimitive)?.booleanOrNull != true) {
                lista[i] = JsonObject(e + mapOf("isDeleted" to JsonPrimitive(true), "version" to JsonPrimitive(versionDe(e).version + 1)))
                cambio = true
            }
        }
        val salida = LinkedHashMap<String, JsonElement>(escena)
        salida["elements"] = JsonArray(lista)
        (delta["archivos"] as? JsonObject)?.let { m ->
            val f = LinkedHashMap((salida["files"] as? JsonObject).orEmpty())
            for ((k, v) in m) if (k !in f) { f[k] = v; cambio = true }
            salida["files"] = JsonObject(f)
        }
        (delta["appState"] as? JsonObject)?.let { a ->
            val s = LinkedHashMap((salida["appState"] as? JsonObject).orEmpty())
            for ((k, v) in a) if (s[k] != v) { s[k] = v; cambio = true }
            salida["appState"] = JsonObject(s)
        }
        return JsonObject(salida) to cambio
    }

    /** **Lo que vale un archivo**: el original con sus deltas en orden. */
    fun efectivo(base: JsonObject, deltas: List<JsonObject>): JsonObject = deltas.fold(base) { e, d -> aplicar(e, d).first }

    /** **Junta dos escenas enteras** elemento a elemento con la regla (los dos trazos quedan). */
    fun juntar(mia: JsonObject, suya: JsonObject): JsonObject {
        val m = linkedMapOf<String, JsonElement>("elementos" to JsonArray(elementos(suya)))
        suya["files"]?.let { m["archivos"] = it }
        var salida = aplicar(mia, JsonObject(m)).first
        if (salida["appState"] !is JsonObject) suya["appState"]?.let { salida = JsonObject(salida + ("appState" to it)) }
        return salida
    }

    /** Si toca compactar: con [cuantos] deltas que pesan [bytesDeltas] sobre un original de [bytesBase]. */
    fun compactar(cuantos: Int, bytesDeltas: Long, bytesBase: Long) = cuantos > MAX_DELTAS || bytesDeltas * 2 > bytesBase

    /** La escena de un texto (lo que no se entiende es una escena vacía). */
    fun escenaDe(texto: String): JsonObject = runCatching { Nube.JSON.parseToJsonElement(texto) as JsonObject }
        .getOrElse { JsonObject(mapOf("elements" to JsonArray(emptyList()), "files" to JsonObject(emptyMap()))) }
}
