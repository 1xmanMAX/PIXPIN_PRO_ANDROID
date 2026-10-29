package com.forge.pixpin.jev

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **Repaso oral** (29-oct-2026; porte de `crates/pixpin-typesafe/src/repaso.rs` del PC, `origin/main`
 * `50e87978`): un alumno explica en voz alta lo de la clase y se ve qué puntos clave cubre, cuáles dice
 * mal y cuáles faltan. Una petición por frase con lo explicado hasta ahora (`explicacion`) y dos Nouls
 * por punto con el punto dentro ([Preguntas.Repaso]): «¿lo explica?» y «¿dice algo que lo contradice?»
 * (dos juicios, dos preguntas).
 *
 * **Tabla de una petición** ([leerPunto]):
 *
 * | `contradice` | `cubre` | estado |
 * |---|---|---|
 * | ≥ SI | — | mal |
 * | < SI | ≥ SI | cubierto |
 * | < SI | ≤ NO | falta |
 * | < SI | entre NO y SI | dudoso |
 *
 * **Entre peticiones** ([decidir]): «mal» se queda (lo dicho mal no se borra al seguir hablando);
 * «cubierto» solo pasa a «mal»; «dudoso» sube a «cubierto» o «mal»; «falta» puede pasar a cualquiera.
 *
 * **Nada se guarda**: es un ejercicio. Lo prueba `DictadoYRepasoTest` con las pruebas del PC.
 */
class RepasoOral(val clase: String, textos: List<String>) {

    enum class Estado(val id: String) { FALTA("falta"), DUDOSO("dudoso"), CUBIERTO("cubierto"), MAL("mal") }

    /** Lo que dijo Jev de un punto. */
    data class SenalPunto(val cubre: Double, val contradice: Double)

    /** Los puntos: (id, texto); los id son `p1`, `p2`… */
    val puntos: List<Pair<String, String>> = textos.mapIndexed { i, t -> "p${i + 1}" to t }

    /** El estado de cada punto, en el orden de [puntos]. */
    val estados: MutableList<Estado> = MutableList(textos.size) { Estado.FALTA }

    /** Lo explicado hasta ahora. */
    var explicacion: String = ""
        private set

    /** Lo nuevo que dijo el alumno. */
    fun oir(texto: String) {
        if (explicacion.isNotEmpty()) explicacion += " "
        explicacion += texto.trim()
    }

    /** Los puntos que van en la siguiente petición (los «mal» ya no). */
    fun porPreguntar(): List<Pair<String, String>> = puntos.filterIndexed { i, _ -> estados[i] != Estado.MAL }

    /** **La tabla entre peticiones**: lo que cambió (id, antes, ahora). */
    fun decidir(senales: List<Pair<String, SenalPunto>>): List<Triple<String, Estado, Estado>> {
        val v = mutableListOf<Triple<String, Estado, Estado>>()
        for ((id, s) in senales) {
            val i = puntos.indexOfFirst { it.first == id }
            if (i < 0) continue
            val antes = estados[i]
            val nuevo = leerPunto(s)
            val ahora = when {
                antes == Estado.MAL -> Estado.MAL
                nuevo == Estado.MAL -> Estado.MAL
                antes == Estado.CUBIERTO -> Estado.CUBIERTO
                antes == Estado.DUDOSO && nuevo == Estado.CUBIERTO -> Estado.CUBIERTO
                antes == Estado.DUDOSO -> Estado.DUDOSO
                else -> nuevo
            }
            if (ahora != antes) {
                estados[i] = ahora
                v += Triple(id, antes, ahora)
            }
        }
        return v
    }

    companion object {
        /** Lo largo de la explicación que se manda (lo último). */
        const val LARGO = 4000

        /** La tabla de una petición. */
        fun leerPunto(s: SenalPunto): Estado = when {
            s.contradice >= Preguntas.SI -> Estado.MAL
            s.cubre >= Preguntas.SI -> Estado.CUBIERTO
            s.cubre <= Preguntas.NO -> Estado.FALTA
            else -> Estado.DUDOSO
        }

        /** **La petición**: la clase, lo explicado (lo último, [LARGO] letras) y los puntos (id, texto). */
        fun peticion(clase: String, explicacion: String, puntos: List<Pair<String, String>>): Peticion {
            var largo = explicacion.trim()
            val n = largo.codePointCount(0, largo.length)
            if (n > LARGO) largo = "…" + largo.substring(largo.offsetByCodePoints(0, n - LARGO))
            var p = Peticion(JsonObject(mapOf(
                "clase" to JsonPrimitive(recortar(clase, 120)),
                "explicacion" to JsonPrimitive(largo)
            )))
            for ((id, t0) in puntos.take(Preguntas.Repaso.POR_PETICION)) {
                val t = recortar(t0, 200)
                p = p.con(Preguntas.Repaso.cubre(id), Preguntas.Repaso.explica(t))
                    .con(Preguntas.Repaso.contradice(id), Preguntas.Repaso.loContradice(t))
            }
            return p
        }

        /** **Pregunta**: la señal de cada punto que fue. Bloquea; un fallo es un [ErrorJev], nunca un «falta». */
        fun preguntar(cliente: Cliente, clase: String, explicacion: String, puntos: List<Pair<String, String>>): List<Pair<String, SenalPunto>> {
            val r = cliente.preguntar(peticion(clase, explicacion, puntos))
            return puntos.take(Preguntas.Repaso.POR_PETICION).map { (id, _) ->
                id to SenalPunto(
                    cubre = r.noul(Preguntas.Repaso.cubre(id)),
                    contradice = r.noul(Preguntas.Repaso.contradice(id))
                )
            }
        }
    }
}
