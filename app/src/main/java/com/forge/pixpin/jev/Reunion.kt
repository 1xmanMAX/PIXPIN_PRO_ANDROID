package com.forge.pixpin.jev

import com.forge.pixpin.jev.Preguntas.NO
import com.forge.pixpin.jev.Preguntas.SI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.forge.pixpin.jev.Preguntas.Reunion as Q

/**
 * **Las partes importantes de una clase o reunión grabada** (28-oct-2026, `reunion.rs` del PC): por
 * cada renglón de la transcripción (`[m:ss] texto`), si encarga una tarea, si anuncia una decisión, si
 * alguien pregunta algo o si dice algo de examen o un dato clave. Una petición por renglón con todas
 * sus preguntas juntas (comparten el estado: el renglón, sus vecinos y el título), varias a la vez.
 *
 * **Tabla de decisión** ([decidir]), con [SI] = 0.7:
 *
 * | charla ≥ SI | tarea ≥ SI | decisión ≥ SI | pregunta ≥ SI | examen o dato ≥ SI | se marca |
 * |---|---|---|---|---|---|
 * | no | sí | — | — | — | tarea |
 * | no | — | sí | — | — | decisión |
 * | no | — | — | sí | — | pregunta |
 * | no | — | — | — | sí | examen |
 * | sí | sí | — | — | — | solo tarea («traigan la calculadora» es logística y es tarea) |
 * | sí | no | * | * | * | nada |
 *
 * Un renglón puede llevar varias marcas (son Nouls separados). Lo que queda entre 0.3 y 0.7 no se
 * marca. `tarea_quien` y `tarea_cuando` solo se leen si hay tarea (preguntas especulativas).
 */
object Reunion {

    /** Un renglón de la transcripción: su minuto (si lo lleva) y lo que dice. */
    data class Renglon(val ms: Long?, val texto: String)

    /** Qué clase de parte importante es. */
    enum class Tipo(val id: String) { TAREA("tarea"), DECISION("decision"), PREGUNTA("pregunta"), EXAMEN("examen") }

    /** Lo que Jev dijo de un renglón. */
    data class Senales(
        val tarea: Double, val quien: Double, val cuando: Double, val decision: Double,
        val pregunta: Double, val examen: Double, val dato: Double, val charla: Double
    ) {
        companion object {
            fun de(r: Respuestas) = Senales(
                tarea = r.noul(Q.TAREA), quien = r.noul(Q.TAREA_QUIEN), cuando = r.noul(Q.TAREA_CUANDO),
                decision = r.noul(Q.DECISION), pregunta = r.noul(Q.PREGUNTA), examen = r.noul(Q.EXAMEN),
                dato = r.noul(Q.DATO), charla = r.noul(Q.CHARLA)
            )
        }
    }

    /** **La tabla**: qué marcas lleva un renglón y con qué probabilidad. */
    fun decidir(s: Senales): List<Pair<Tipo, Double>> {
        val v = mutableListOf<Pair<Tipo, Double>>()
        if (s.tarea >= SI) v += Tipo.TAREA to s.tarea
        if (s.charla >= SI) return v
        if (s.decision >= SI) v += Tipo.DECISION to s.decision
        if (s.pregunta >= SI) v += Tipo.PREGUNTA to s.pregunta
        val examen = maxOf(s.examen, s.dato)
        if (examen >= SI) v += Tipo.EXAMEN to examen
        return v
    }

    /**
     * Una parte importante hallada: el minuto de su renglón, qué es y con qué probabilidad. Las tareas
     * dicen además si se sabe quién y cuándo (null si no se sabe: entre 0.3 y 0.7).
     */
    data class Hallazgo(val ms: Long, val tipo: Tipo, val p: Double, val quien: Boolean?, val cuando: Boolean?)

    internal fun siONo(p: Double): Boolean? = when {
        p >= SI -> true
        p <= NO -> false
        else -> null
    }

    /** Lo largo que va un renglón (los de una clase son párrafos de 20-30 s), y cada vecino. */
    private const val LARGO = 700
    private const val LARGO_VECINO = 300

    /** **El estado de un renglón**: el título, el renglón y sus vecinos (sin vecinos el campo no va). */
    fun estado(clase: String, renglones: List<Renglon>, i: Int): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        if (clase.isNotBlank()) m["clase"] = JsonPrimitive(recortar(clase, 120))
        val antes = renglones.subList(maxOf(0, i - Q.VECINOS), i).map { JsonPrimitive(recortar(it.texto, LARGO_VECINO)) }
        if (antes.isNotEmpty()) m["antes"] = JsonArray(antes)
        m["linea"] = JsonPrimitive(recortar(renglones[i].texto, LARGO))
        val despues = renglones.drop(i + 1).take(Q.VECINOS).map { JsonPrimitive(recortar(it.texto, LARGO_VECINO)) }
        if (despues.isNotEmpty()) m["despues"] = JsonArray(despues)
        return JsonObject(m)
    }

    /** La petición de un renglón. */
    fun peticion(clase: String, renglones: List<Renglon>, i: Int): Peticion = Peticion(estado(clase, renglones, i)).conTodas(Q.preguntas())

    /** Si un renglón merece pregunta: con minuto y con algo dicho (dos palabras o más; un «eh» no). */
    fun seMira(r: Renglon): Boolean = r.ms != null && r.texto.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 2

    /**
     * **Busca las partes importantes** de toda la transcripción, [hilos] peticiones a la vez. Devuelve
     * lo hallado en orden y el modelo que contestó. Un fallo en cualquier renglón es un error del
     * conjunto: nada a medias se guarda como si estuviera entero.
     */
    fun buscar(cliente: Cliente, clase: String, renglones: List<Renglon>, hilos: Int): Pair<List<Hallazgo>, String> {
        val indices = renglones.indices.filter { seMira(renglones[it]) }
        val respuestas = enParalelo(indices, hilos) { i ->
            val r = cliente.preguntar(peticion(clase, renglones, i))
            Triple(i, Senales.de(r), r.modelo)
        }
        var modelo = ""
        val v = mutableListOf<Hallazgo>()
        for ((i, s, m) in respuestas) {
            modelo = m
            val ms = renglones[i].ms ?: continue
            for ((tipo, p) in decidir(s)) {
                val tarea = tipo == Tipo.TAREA
                v += Hallazgo(ms, tipo, p, if (tarea) siONo(s.quien) else null, if (tarea) siONo(s.cuando) else null)
            }
        }
        return v to modelo
    }
}
