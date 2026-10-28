package com.forge.pixpin.jev

import com.forge.pixpin.jev.Preguntas.NO
import com.forge.pixpin.jev.Preguntas.SI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.forge.pixpin.jev.Preguntas.Modelo as Q

/**
 * **Qué modelo contesta al asistente** (28-oct-2026, `modelo.rs` del PC, `e51da395`): antes de cada
 * pedido, lo difícil que es (Score), si es una orden simple para la app, si pide razonar en varios
 * pasos y si un error tendría consecuencias (Nouls). El código elige el modelo rápido (Claude Haiku,
 * DeepSeek Flash: el de siempre) o el fuerte (Claude Sonnet, DeepSeek V4 Pro).
 *
 * **Tabla de decisión** ([decidir]), por orden; `dificil` = probabilidad de nivel 2 o 3,
 * `muy_dificil` = de nivel 3:
 *
 * | condición | modelo | motivo |
 * |---|---|---|
 * | `orden_simple` ≥ 0.7 y `dificil` ≤ 0.3 | rápido | orden |
 * | `razonar` ≥ 0.7 | fuerte | razonar |
 * | `muy_dificil` ≥ 0.5 | fuerte | difícil |
 * | `importa` ≥ 0.7 y `dificil` ≥ 0.5 | fuerte | importante |
 * | `dificil` ≤ 0.3 | rápido | fácil |
 * | lo demás (sin decidir) | rápido | sin decidir (lo de siempre) |
 */
object Modelo {

    enum class Ruta { RAPIDO, FUERTE }

    enum class Motivo { ORDEN, RAZONAR, DIFICIL, IMPORTANTE, FACIL, SIN_DECIDIR }

    /** El pedido y, si hay, los últimos mensajes (`quien` a `texto`). */
    data class Contexto(val pedido: String, val recientes: List<Pair<String, String>> = emptyList())

    data class Senales(val dificultad: Escala, val orden: Double, val razonar: Double, val importa: Double) {
        companion object {
            fun de(r: Respuestas) = Senales(r.score(Q.DIFICULTAD, Q.NIVELES.size), r.noul(Q.ORDEN), r.noul(Q.RAZONAR), r.noul(Q.IMPORTA))
        }
    }

    /** **La tabla.** */
    fun decidir(s: Senales): Pair<Ruta, Motivo> {
        val dificil = s.dificultad.alMenos(2)
        val muyDificil = s.dificultad.alMenos(3)
        return when {
            s.orden >= SI && dificil <= NO -> Ruta.RAPIDO to Motivo.ORDEN
            s.razonar >= SI -> Ruta.FUERTE to Motivo.RAZONAR
            muyDificil >= 0.5 -> Ruta.FUERTE to Motivo.DIFICIL
            s.importa >= SI && dificil >= 0.5 -> Ruta.FUERTE to Motivo.IMPORTANTE
            dificil <= NO -> Ruta.RAPIDO to Motivo.FACIL
            else -> Ruta.RAPIDO to Motivo.SIN_DECIDIR
        }
    }

    /** Los mensajes de antes que se mandan como mucho. */
    const val RECIENTES = 3

    fun estado(c: Contexto): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["pedido"] = JsonPrimitive(recortar(c.pedido, 2000))
        val recientes = c.recientes.takeLast(RECIENTES).map { (quien, texto) ->
            JsonObject(linkedMapOf("de" to JsonPrimitive(quien), "texto" to JsonPrimitive(recortar(texto, 400))))
        }
        if (recientes.isNotEmpty()) m["conversacion_reciente"] = JsonArray(recientes)
        return JsonObject(m)
    }

    fun peticion(c: Contexto): Peticion = Peticion(estado(c)).conTodas(Q.preguntas())

    /** **Pregunta y decide.** Lanza [ErrorJev]: quien llama usa el de siempre. */
    fun elegir(cliente: Cliente, c: Contexto): Pair<Ruta, Motivo> = decidir(Senales.de(cliente.preguntar(peticion(c))))
}
