package com.forge.pixpin.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * **La revisión de urgencia que ya se hizo, guardada y de viaje** (4-nov-2026, el usuario: «si una tarea
 * ya fue revisada —normal, difícil, poco urgente—, que no se vuelva a hacer; lo revisado en el PC se
 * sincroniza al celular y lo del celular al PC; y no gastar tokens»).
 *
 * Lo que dijo Jev de cada tarea de una lista va en `pins/draw/anot-<uid de la lista>.urgencia.json`, al
 * lado de lo demás anotado de ese mensaje: viaja con su chat como los comentarios o los marcos (ver
 * [com.forge.pixpin.sincro.AnotacionesDelAdjunto]). Se guardan **las señales** (no la prioridad), así que
 * la prioridad se sigue recalculando gratis cada día con su plazo, y Jev no se vuelve a preguntar.
 *
 * ```json
 * {
 *   "version": 1,
 *   "tareas": [
 *     {"texto": "Mandar la cotización (al cliente)", "consecuencia": [0.05, 0.15, 0.5, 0.3],
 *      "confianza": 0.6, "bloquea": 0.8, "plazo_ajeno": 0.9, "piden_ya": 0.2,
 *      "cuando": 1793582613265, "aparato": "pc1"}
 *   ]
 * }
 * ```
 *
 * - `texto`: lo que se le manda a Jev ([UrgenciaEnLaApp.textoParaJev]: el título sin marcas y su nota
 *   entre paréntesis). Es la clave: si el texto cambia, es otra tarea y se pregunta otra vez.
 * - `consecuencia`: la probabilidad de cada nivel del Score (0-3); `piden_ya` falta si se preguntó sin
 *   mensajes del chat. Los demás, el Noul de 0 a 1.
 * - **Juntar**: por `texto`; la misma en los dos, gana la de `cuando` mayor y, a igualdad, la de `aparato`
 *   mayor. Quien escribe junta antes con lo que hay en el disco. Lo que no se conoce se conserva.
 */
object UrgenciasGuardadas {

    const val VERSION = 1

    /** Lo dicho de una tarea, cuándo y en qué aparato. */
    data class Guardada(val senales: Urgencia.Senales, val cuando: Long, val aparato: String = "")

    private val JSON = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** Lo guardado en [f], por texto. Vacío si no está o está roto. */
    fun leer(f: File): Map<String, Guardada> = runCatching { deTexto(f.readText()) }.getOrDefault(emptyMap())

    fun deTexto(texto: String): Map<String, Guardada> {
        val raiz = runCatching { Json.parseToJsonElement(texto).jsonObject }.getOrNull() ?: return emptyMap()
        val salida = LinkedHashMap<String, Guardada>()
        val lista = (raiz["tareas"] as? JsonArray) ?: return emptyMap()
        for (e in lista) {
            val o = e as? JsonObject ?: continue
            val t = (o["texto"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: continue
            val g = guardadaDe(o) ?: continue
            salida[t] = salida[t]?.let { gana(it, g) } ?: g
        }
        return salida
    }

    private fun num(o: JsonObject, k: String): Double? = (o[k] as? JsonPrimitive)?.doubleOrNull

    private fun guardadaDe(o: JsonObject): Guardada? {
        val niveles = runCatching { o["consecuencia"]!!.jsonArray.map { (it as JsonPrimitive).doubleOrNull ?: 0.0 } }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val confianza = num(o, "confianza") ?: 0.6
        val escala = Escala(niveles.withIndex().sumOf { (i, p) -> i * p }, confianza, niveles)
        val s = Urgencia.Senales(escala, num(o, "bloquea") ?: 0.0, num(o, "plazo_ajeno") ?: 0.0, num(o, "piden_ya"))
        val cuando = (o["cuando"] as? JsonPrimitive)?.longOrNull ?: 0L
        val aparato = (o["aparato"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        return Guardada(s, cuando, aparato)
    }

    /** De dos de la misma tarea, la que se queda (las dos partes eligen la misma sin hablar). */
    fun gana(a: Guardada, b: Guardada): Guardada = when {
        a.cuando != b.cuando -> if (a.cuando > b.cuando) a else b
        else -> if (a.aparato >= b.aparato) a else b
    }

    /** Las dos, juntas. */
    fun juntar(a: Map<String, Guardada>, b: Map<String, Guardada>): Map<String, Guardada> {
        val salida = LinkedHashMap(a)
        for ((t, g) in b) salida[t] = salida[t]?.let { gana(it, g) } ?: g
        return salida
    }

    private fun aJson(t: String, g: Guardada): JsonObject = JsonObject(buildMap {
        put("texto", JsonPrimitive(t))
        put("consecuencia", JsonArray(g.senales.consecuencia.niveles.map { JsonPrimitive(redondo(it)) }))
        put("confianza", JsonPrimitive(redondo(g.senales.consecuencia.confianza)))
        put("bloquea", JsonPrimitive(redondo(g.senales.bloquea)))
        put("plazo_ajeno", JsonPrimitive(redondo(g.senales.plazoAjeno)))
        g.senales.pidenYa?.let { put("piden_ya", JsonPrimitive(redondo(it))) }
        put("cuando", JsonPrimitive(g.cuando))
        if (g.aparato.isNotEmpty()) put("aparato", JsonPrimitive(g.aparato))
    })

    private fun redondo(v: Double) = Math.round(v * 10_000.0) / 10_000.0

    /** El texto del fichero: las tareas por orden de texto, y el resto de claves de [antes] tal cual. */
    fun texto(tareas: Map<String, Guardada>, antes: JsonObject? = null): String {
        val resto: Map<String, JsonElement> = antes?.filterKeys { it != "version" && it != "tareas" } ?: emptyMap()
        val raiz = JsonObject(buildMap {
            put("version", JsonPrimitive(VERSION))
            put("tareas", JsonArray(tareas.entries.sortedBy { it.key }.map { (t, g) -> aJson(t, g) }))
            putAll(resto)
        })
        return JSON.encodeToString(JsonElement.serializer(), raiz) + "\n"
    }

    /**
     * **Añade [nuevas] a [f]**, juntadas con lo que ya hay. No escribe si no cambia nada, ni encima de un
     * JSON roto (se perdería lo que hubiera). Devuelve si escribió.
     */
    fun anadir(f: File, nuevas: Map<String, Guardada>): Boolean {
        if (nuevas.isEmpty()) return false
        val crudo = if (f.isFile) runCatching { f.readText() }.getOrNull() ?: return false else null
        val antes = crudo?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() ?: return false }
        val previas = crudo?.let { deTexto(it) } ?: emptyMap()
        val juntas = juntar(previas, nuevas)
        if (juntas == previas) return false
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(texto(juntas, antes))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        return true
    }
}
