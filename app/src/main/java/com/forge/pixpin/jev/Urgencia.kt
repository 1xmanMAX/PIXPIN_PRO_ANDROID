package com.forge.pixpin.jev

import com.forge.pixpin.jev.Preguntas.NO
import com.forge.pixpin.jev.Preguntas.SI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.forge.pixpin.jev.Preguntas.Urgencia as Q

/**
 * **La urgencia de una tarea según su contexto** (28-oct-2026, `urgencia.rs` del PC, `fa4ca12b`): lo que
 * pasa si se hace tarde (Score), si otro la espera, si el plazo lo puso otro y si en el chat la piden
 * ya (Nouls). Cuánto falta para su hora lo cuenta el código (las fechas nunca se le preguntan a Jev): la
 * de la tarea (`⏰`) o, si no tiene, la que dice el título de su lista ([diasDeLaLista]: «HOY SÁBADO»,
 * «Para el lunes», «Entrega 15/11»; 2-nov-2026, `4fe2730b` del PC).
 *
 * **Lo marcado por el usuario manda** (⏫ «muy urgente» es urgente; 🔼 «urgente», alta). Sin marca,
 * unos puntos ([puntos]):
 *
 * | señal | puntos |
 * |---|---|
 * | consecuencia (valor esperado del Score, 0-3) | 0 a 3 |
 * | `bloquea` ≥ 0.7 | +1 |
 * | `plazo_ajeno` ≥ 0.7 | +1 |
 * | `piden_ya` ≥ 0.7 | +1 |
 * | su hora (o la de su lista) es hoy o ya pasó | +2 |
 * | mañana | +1.5 |
 * | en 2-3 días | +1 |
 * | en 4-7 días | +0.5 |
 *
 * ≥ 5 urgente; ≥ 3 alta, **y también alta si la consecuencia es grave** (valor esperado ≥ [GRAVE])
 * aunque no tenga fecha (2-nov-2026); **baja** si P(consecuencia 2-3) ≤ 0.3, nadie la espera, ningún
 * plazo ajeno ni pedido y sin hora en 7 días; lo demás, normal. Sin respuesta de Jev, la marca del
 * usuario o «normal». Con respuesta, Tareas enseña las cuatro (también «normal», en gris: si no, una
 * tarea juzgada parecía sin juzgar) y, con un toque en la chapita, el porqué ([Motivo]).
 *
 * **La fecha de la lista**: si la tarea no lleva `⏰`, se buscan en el título de su lista los días dichos
 * ([FechasDichas.buscar]: «hoy», «mañana», «el lunes», «15/11», «el 3 de noviembre»...), resueltos contra
 * el día en que se creó la lista (una lista «HOY SÁBADO» de ayer es de ayer: ya pasó), y vale el más
 * cercano. Sin ninguno, sin fecha.
 */
object Urgencia {

    /** La prioridad que se enseña y con la que se ordena (en este orden). */
    enum class Prioridad(val id: String, val rotulo: String) {
        URGENTE("urgente", "Urgente"), ALTA("alta", "Alta"), NORMAL("normal", "Normal"), BAJA("baja", "Baja");

        companion object {
            fun deId(s: String?) = entries.firstOrNull { it.id == s }
        }
    }

    /** Lo que el usuario marcó en la tarea. */
    enum class Marca { NINGUNA, URGENTE, MUY_URGENTE }

    /** Lo que hace falta para preguntar por una tarea. */
    data class Contexto(
        /** Su texto, sin las marcas ni la fecha. */
        val tarea: String,
        val lista: String = "",
        val chat: String = "",
        /** Los últimos mensajes del chat, del más viejo al más nuevo. */
        val recientes: List<String> = emptyList()
    )

    data class Senales(val consecuencia: Escala, val bloquea: Double, val plazoAjeno: Double, val pidenYa: Double?) {
        companion object {
            fun de(r: Respuestas, conMensajes: Boolean) = Senales(
                r.score(Q.CONSECUENCIA, Q.NIVELES.size), r.noul(Q.BLOQUEA), r.noul(Q.PLAZO_AJENO),
                if (conMensajes) r.noul(Q.PIDEN_YA) else null
            )
        }
    }

    /** Los puntos de lo que falta para su hora (días desde hoy; negativo, ya pasó). */
    fun puntosDelPlazo(dias: Long?): Double = when {
        dias == null -> 0.0
        dias <= 0 -> 2.0
        dias == 1L -> 1.5
        dias in 2..3 -> 1.0
        dias in 4..7 -> 0.5
        else -> 0.0
    }

    /** Los puntos de la tabla. */
    fun puntos(s: Senales, dias: Long?): Double {
        fun mas(p: Double) = if (p >= SI) 1.0 else 0.0
        return s.consecuencia.valor.coerceIn(0.0, 3.0) + mas(s.bloquea) + mas(s.plazoAjeno) + mas(s.pidenYa ?: 0.0) + puntosDelPlazo(dias)
    }

    /**
     * La consecuencia que basta para «alta» sin fecha ni nadie esperando (2-nov-2026, `urgencia::GRAVE`
     * del PC): el valor esperado del Score cerca del nivel 3 («grave»).
     */
    const val GRAVE = 2.5

    /** **La tabla.** [dias]: cuántos faltan para la hora de la tarea (`⏰`), contados por el código. */
    fun decidir(marca: Marca, s: Senales?, dias: Long?): Prioridad {
        when (marca) {
            Marca.MUY_URGENTE -> return Prioridad.URGENTE
            Marca.URGENTE -> return Prioridad.ALTA
            Marca.NINGUNA -> {}
        }
        if (s == null) return Prioridad.NORMAL
        val n = puntos(s, dias)
        return when {
            n >= 5.0 -> Prioridad.URGENTE
            n >= 3.0 || s.consecuencia.valor >= GRAVE -> Prioridad.ALTA
            s.consecuencia.alMenos(2) <= NO && s.bloquea <= NO && s.plazoAjeno <= NO &&
                (s.pidenYa == null || s.pidenYa < SI) && (dias == null || dias > 7) -> Prioridad.BAJA
            else -> Prioridad.NORMAL
        }
    }

    /**
     * **Los días que faltan para la fecha del título de una lista** (el más cercano de los dichos;
     * 2-nov-2026, `urgencia::dias_de_la_lista` del PC), con los días resueltos contra [creada] (el día en
     * que se creó la lista) y contados desde [hoy]. Negativo: ya pasó.
     */
    fun diasDeLaLista(titulo: String, creada: FechasDichas.Fecha, hoy: FechasDichas.Fecha): Long? {
        // Un reloj atrasado no hace la lista del futuro.
        val desde = minOf(creada, hoy)
        return FechasDichas.buscar(titulo, desde).minOfOrNull { hoy.hasta(it.momento.fecha) }
    }

    /**
     * **El porqué de una prioridad de Jev** (2-nov-2026, `urgencia::Motivo` del PC), para la pista de su
     * chapita («Jev: consecuencia media · nadie la espera · sin fecha»). Se calcula en cada aparato y no
     * se guarda ni viaja.
     */
    data class Motivo(
        /** El nivel de la consecuencia más cercano a su valor esperado (0-3). */
        val consecuencia: Int,
        /** Otro la espera (`bloquea` ≥ 0.7). */
        val espera: Boolean,
        /** El plazo lo puso otro (`plazo_ajeno` ≥ 0.7). */
        val plazoAjeno: Boolean,
        /** La piden en el chat (`piden_ya` ≥ 0.7). */
        val pidenYa: Boolean,
        /** Los días que faltan (los de [decidir]). */
        val dias: Long?,
        /** Si esos días salen del título de la lista y no de la tarea. */
        val deLaLista: Boolean
    ) {
        companion object {
            fun de(s: Senales, dias: Long?, deLaLista: Boolean) = Motivo(
                consecuencia = Math.round(s.consecuencia.valor.coerceIn(0.0, 3.0)).toInt(),
                espera = s.bloquea >= SI,
                plazoAjeno = s.plazoAjeno >= SI,
                pidenYa = (s.pidenYa ?: 0.0) >= SI,
                dias = dias,
                deLaLista = deLaLista && dias != null
            )
        }
    }

    /** Los mensajes recientes que se mandan como mucho, y lo largo de cada uno. */
    const val RECIENTES = 5
    private const val LARGO_MENSAJE = 300

    /** El estado de una tarea. */
    fun estado(c: Contexto): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["tarea"] = JsonPrimitive(recortar(c.tarea, 500))
        if (c.lista.isNotBlank()) m["lista"] = JsonPrimitive(recortar(c.lista, 120))
        if (c.chat.isNotBlank()) m["chat"] = JsonPrimitive(recortar(c.chat, 120))
        val recientes = c.recientes.filter { it.isNotBlank() }.takeLast(RECIENTES).map { JsonPrimitive(recortar(it, LARGO_MENSAJE)) }
        if (recientes.isNotEmpty()) m["mensajes_recientes"] = JsonArray(recientes)
        return JsonObject(m)
    }

    /** La petición y si lleva mensajes (entonces pregunta también `piden_ya`). */
    fun peticion(c: Contexto): Pair<Peticion, Boolean> {
        val e = estado(c)
        val conMensajes = "mensajes_recientes" in e
        return Peticion(e).conTodas(Q.preguntas(conMensajes)) to conMensajes
    }

    /** **Pregunta por una tarea.** */
    fun preguntar(cliente: Cliente, c: Contexto): Senales {
        val (p, conMensajes) = peticion(c)
        return Senales.de(cliente.preguntar(p), conMensajes)
    }

    /**
     * **Pregunta por varias**, [hilos] a la vez, cada una con su resultado (un fallo en una no se lleva
     * las demás: su prioridad será la de siempre).
     */
    fun preguntarVarias(cliente: Cliente, tareas: List<Contexto>, hilos: Int): List<Result<Senales>> =
        runCatching { enParalelo(tareas, hilos) { c -> runCatching { preguntar(cliente, c) } } }
            .getOrElse { e -> tareas.map { Result.failure(e) } }
}
