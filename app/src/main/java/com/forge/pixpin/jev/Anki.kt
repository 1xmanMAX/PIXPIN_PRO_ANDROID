package com.forge.pixpin.jev

import com.forge.pixpin.jev.Preguntas.NO
import com.forge.pixpin.jev.Preguntas.SI
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.forge.pixpin.jev.Preguntas.Anki as Q

/**
 * **Qué tarjetas de una clase valen la pena** (28-oct-2026, `anki.rs` del PC): menos y mejores. Por cada
 * tarjeta (una captura y lo dicho en su tramo), un Score de lo útil que es para estudiar y un Noul de
 * si dice que entra en el examen.
 *
 * **Tabla de decisión** ([veredicto]); `util` = probabilidad de que el nivel sea 2 o 3:
 *
 * | examen ≥ 0.7 | util ≥ 0.7 | util ≤ 0.3 | veredicto |
 * |---|---|---|---|
 * | sí | * | * | queda |
 * | no | sí | — | queda |
 * | no | — | sí | sobra |
 * | no | entre 0.3 y 0.7 | | dudosa |
 *
 * Y [elegir]: quedan las que «quedan»; las dudosas, de más a menos útil, solo hasta llegar a [MINIMO];
 * si aun así no quedara ninguna, la más útil. Una tarjeta sin nada dicho no se pregunta y queda (la
 * imagen es lo que se repasa).
 */
object Anki {

    /** Cuántas tarjetas se procura dejar, como poco, si hay dudosas. */
    const val MINIMO = 3

    /** Una tarjeta para valorar: su minuto («12:30») y lo dicho en su tramo. */
    data class Tramo(val minuto: String, val dicho: String)

    data class Senales(val valor: Escala, val examen: Double) {
        /** La probabilidad de que sirva para estudiar (nivel 2 o 3). */
        fun util(): Double = valor.alMenos(2)

        companion object {
            fun de(r: Respuestas) = Senales(r.score(Q.VALOR, Q.NIVELES.size), r.noul(Q.EXAMEN))
        }
    }

    enum class Veredicto { QUEDA, DUDOSA, SOBRA }

    /** **La tabla** de una tarjeta. */
    fun veredicto(s: Senales): Veredicto = when {
        s.examen >= SI || s.util() >= SI -> Veredicto.QUEDA
        s.util() <= NO -> Veredicto.SOBRA
        else -> Veredicto.DUDOSA
    }

    /** **Cuáles quedan**, en el orden de las tarjetas. null: no se preguntó (sin nada dicho) y queda. */
    fun elegir(senales: List<Senales?>): List<Boolean> {
        val quedan = senales.map { it == null || veredicto(it) == Veredicto.QUEDA }.toMutableList()
        val dudosas = senales.withIndex()
            .filter { (_, s) -> s != null && veredicto(s) == Veredicto.DUDOSA }
            .map { (i, s) -> i to s!!.util() }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
        for ((i, _) in dudosas) {
            if (quedan.count { it } >= MINIMO) break
            quedan[i] = true
        }
        if (quedan.none { it }) {
            senales.withIndex().filter { it.value != null }.maxByOrNull { it.value!!.util() }?.let { quedan[it.index] = true }
        }
        return quedan
    }

    /** El estado de una tarjeta. */
    fun estado(clase: String, t: Tramo): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["minuto"] = JsonPrimitive(t.minuto)
        m["dicho"] = JsonPrimitive(recortar(t.dicho, 1500))
        if (clase.isNotBlank()) m["clase"] = JsonPrimitive(recortar(clase, 120))
        return JsonObject(m)
    }

    fun peticion(clase: String, t: Tramo): Peticion = Peticion(estado(clase, t)).conTodas(Q.preguntas())

    /**
     * **Valora cada tarjeta**, [hilos] a la vez. Las que no tienen nada dicho (menos de tres palabras)
     * no se preguntan (null). Un fallo es un error del conjunto: quien llama manda entonces todas.
     */
    fun valorar(cliente: Cliente, clase: String, tramos: List<Tramo>, hilos: Int): List<Senales?> =
        enParalelo(tramos, hilos) { t ->
            if (t.dicho.trim().split(Regex("\\s+")).count { it.isNotEmpty() } < 3) null
            else Senales.de(cliente.preguntar(peticion(clase, t)))
        }
}
