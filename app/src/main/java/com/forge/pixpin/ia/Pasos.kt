package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * **Los pasos del pedido** (`pasos.rs` del PC, 25-oct-2026, «que sea un agente como Claude Code»;
 * portados el mismo día): la lista de tareas que el propio modelo escribe al empezar un pedido de
 * varias partes y va marcando, como el `TodoWrite` de Claude Code.
 *
 * - **Que no se le olvide nada ni haga de más**: los pasos salen solo de lo que pidió el usuario y
 *   se ven en la burbuja, marcándose ([Agente.Evento.Tareas]).
 * - **Que no acabe con pasos sin hacer**: si contesta sin herramientas con pasos pendientes, el
 *   turno le devuelve [empujon] con cuáles faltan (la cuenta es la de [Guardas.EMPUJONES]).
 *
 * Cada llamada manda la lista ENTERA; la contesta el propio turno, sin el ejecutor.
 */
object Pasos {
    const val NOMBRE = "pasos_del_pedido"
    const val MAX_PASOS = 10
    private const val LARGO_PASO = 120

    enum class Estado { PENDIENTE, EN_CURSO, HECHO, NO_SE_PUDO }

    data class Paso(val paso: String, val estado: Estado, val nota: String = "") {
        /** Si aún queda por hacer. */
        val abierto get() = estado == Estado.PENDIENTE || estado == Estado.EN_CURSO
    }

    /** La herramienta para el modelo (la del PC, letra a letra). */
    fun herramienta(): Herramienta = Herramienta(
        NOMBRE,
        "Tu lista de pasos para ESTE pedido (como una lista de tareas que vas marcando). Úsala cuando el pedido tenga " +
            "3 o más pasos o varias partes: al empezar, escribe los pasos (solo lo que pidió el usuario, sin añadir nada), y cada vez que acabes uno, " +
            "vuelve a mandar la lista entera con su estado. Un solo paso en_curso a la vez. Antes de terminar, todos deben estar hecho o no_se_pudo " +
            "(con su nota de por qué). No la uses para un pedido de un solo paso. " +
            "Ejemplo: {\"pasos\":[{\"paso\":\"Agrandar el cronograma a 30 actividades\",\"estado\":\"hecho\"},{\"paso\":\"Agrandarlo a 30 semanas\",\"estado\":\"en_curso\"}]}",
        ordenado(buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add(JsonPrimitive("pasos")) })
            put("properties", buildJsonObject {
                put("pasos", buildJsonObject {
                    put("type", "array"); put("maxItems", MAX_PASOS)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", buildJsonArray { add(JsonPrimitive("paso")); add(JsonPrimitive("estado")) })
                        put("properties", buildJsonObject {
                            put("paso", buildJsonObject { put("type", "string"); put("description", "lo que hay que hacer, corto") })
                            put("estado", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("pendiente", "en_curso", "hecho", "no_se_pudo").map { JsonPrimitive(it) })) })
                            put("nota", buildJsonObject { put("type", "string"); put("description", "no_se_pudo: por qué") })
                        })
                    })
                })
            })
        }) as JsonObject
    )

    private fun estadoDe(s: String): Estado? = when (Acciones.normalizar(s.trim()).replace(' ', '_').replace('-', '_')) {
        "pendiente" -> Estado.PENDIENTE
        "en_curso", "haciendo" -> Estado.EN_CURSO
        "hecho", "hecha", "completado", "completada" -> Estado.HECHO
        "no_se_pudo", "no_se_puede", "imposible" -> Estado.NO_SE_PUDO
        else -> null
    }

    private fun cadena(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** **Lee la lista** que manda el modelo; el fallo lleva lo que tiene que corregir. */
    fun leer(argumentos: String): Result<List<Paso>> = runCatching {
        val v = runCatching { Json.parseToJsonElement(argumentos) as? JsonObject }.getOrNull()
            ?: error("los argumentos no son un JSON que se lea: manda {\"pasos\":[...]}")
        val lista = v["pasos"] as? JsonArray ?: error("falta «pasos»: una lista de {\"paso\":\"...\",\"estado\":\"pendiente|en_curso|hecho|no_se_pudo\"}")
        if (lista.isEmpty()) error("«pasos» está vacía: escribe los pasos del pedido")
        if (lista.size > MAX_PASOS) error("como mucho $MAX_PASOS pasos: junta los parecidos")
        lista.mapIndexed { i, x ->
            val o = x as? JsonObject
            val paso = cadena(o, "paso")?.trim()?.takeIf { it.isNotEmpty() } ?: error("el paso ${i + 1} no tiene «paso»")
            val estado = cadena(o, "estado")?.let(::estadoDe)
                ?: error("el paso ${i + 1} («$paso»): «estado» tiene que ser pendiente, en_curso, hecho o no_se_pudo")
            Paso(if (paso.codePointCount(0, paso.length) > LARGO_PASO) paso.substring(0, paso.offsetByCodePoints(0, LARGO_PASO)) else paso, estado, cadena(o, "nota").orEmpty().trim())
        }
    }

    private fun nombres(pasos: List<Paso>) = pasos.joinToString(", ") { "«${it.paso}»" }

    /** Lo que vuelve al modelo: cuántos quedan y cuáles. */
    fun resultado(pasos: List<Paso>): String {
        val abiertos = pasos.filter { it.abierto }
        if (abiertos.isEmpty()) return "hecho: los ${pasos.size} pasos están cerrados."
        return "hecho: ${pasos.size - abiertos.size} de ${pasos.size} pasos cerrados; quedan: ${nombres(abiertos)}."
    }

    /** **El empujón** si va a acabar con pasos abiertos; null si no queda ninguno (o no hay lista). */
    fun empujon(pasos: List<Paso>): String? {
        val abiertos = pasos.filter { it.abierto }
        if (abiertos.isEmpty()) return null
        return "${Guardas.MARCA_DEL_TURNO} Vas a terminar con pasos sin cerrar en $NOMBRE: ${nombres(abiertos)}. Hazlos ahora con las herramientas y márcalos hecho; " +
            "si alguno de verdad no se puede, márcalo no_se_pudo con su nota y dilo en la respuesta. No anuncies lo que vas a hacer: hazlo."
    }

    /** Cómo se ve la lista en la burbuja mientras trabaja: un renglón por paso. */
    fun lineas(pasos: List<Paso>): String = pasos.joinToString("\n") { p ->
        val marca = when (p.estado) { Estado.HECHO -> "☑"; Estado.EN_CURSO -> "▶"; Estado.NO_SE_PUDO -> "✖"; Estado.PENDIENTE -> "☐" }
        "$marca ${p.paso}"
    }
}
