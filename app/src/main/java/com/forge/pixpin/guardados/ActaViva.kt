package com.forge.pixpin.guardados

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * **Lo que Jev sacó de la reunión mientras se grababa** (29-oct-2026; porte de
 * `crates/pixpin-proyecto/src/acta_viva.rs` del PC, `50e87978`, y de `docs/jev.md` §8): las
 * decisiones, las preguntas que nadie contestó, los minutos de cada tema, las tareas y eventos
 * propuestos (con lo que hizo la persona con ellos) y los cambios de urgencia de tareas que ya existían.
 * Va en la nota de voz, como los temas, para que «Estudiar la clase», la página compartida y el PC lo
 * lean igual. **Cada clave sale solo si tiene algo**: una reunión sin nada de esto deja la nota byte a
 * byte como antes.
 *
 * ```json
 * "decisiones": [{"ms": 125000, "texto": "queda decidido: adelantamos el pedido"}],
 * "preguntasAbiertas": [{"ms": 300000, "texto": "¿quién paga el autobús?"}],
 * "tiempoPorTema": {"temas": [{"id": "t1", "ms": 360000}], "otro": 120000},
 * "propuestas": [{"id": "p1", "tipo": "tarea", "ms": 90000, "texto": "yo mando la cotización el viernes",
 *                 "responsable": "Luis", "fecha": "2026-10-30", "hora": "15:00", "prioridad": "alta",
 *                 "estado": "aceptada"}],
 * "cambiosDeUrgencia": [{"ms": 400000, "tarea": "Pagar al proveedor", "antes": "normal", "despues": "urgente",
 *                        "fechaAntes": "2026-11-05", "fechaDespues": "2026-10-29", "deshecho": false}]
 * ```
 *
 * `ms` es siempre el milisegundo de la grabación en que empezó la frase (en `cambiosDeUrgencia`, cuando
 * se aplicó). `tiempoPorTema.temas[].id` es el `id` de `temas`. Una propuesta es `tarea` o `evento`;
 * `estado`, `pendiente`, `aceptada` (ya está en Tareas: no apuntarla otra vez) o `descartada`. Lo que no
 * se entiende se salta (no tira lo demás).
 */
object ActaViva {

    /** Algo dicho en un minuto: una decisión o una pregunta. */
    data class Dicho(val ms: Long, val texto: String)

    /** Lo que llevó un tema. */
    data class TiempoDeTema(val id: String, val ms: Long)

    data class TiempoPorTema(val temas: List<TiempoDeTema>, val otro: Long = 0) {
        /** Lo que llevó el tema [id], si se contó. */
        fun de(id: String): Long? = temas.firstOrNull { it.id == id }?.ms
    }

    enum class Tipo(val id: String) { TAREA("tarea"), EVENTO("evento") }

    enum class Estado(val id: String) { PENDIENTE("pendiente"), ACEPTADA("aceptada"), DESCARTADA("descartada") }

    /** **Una tarea o un evento propuesto** por lo dicho. [fecha] `AAAA-MM-DD`, [hora] `HH:MM`, [prioridad] la de [com.forge.pixpin.jev.Urgencia.Prioridad]. */
    data class Propuesta(
        val id: String,
        val tipo: Tipo,
        val ms: Long,
        val texto: String,
        val responsable: String? = null,
        val fecha: String? = null,
        val hora: String? = null,
        val prioridad: String? = null,
        val estado: Estado = Estado.PENDIENTE
    )

    /** **Un cambio de urgencia** de una tarea que ya existía. */
    data class CambioDeUrgencia(
        val ms: Long,
        val tarea: String,
        val antes: String,
        val despues: String,
        val fechaAntes: String? = null,
        val fechaDespues: String? = null,
        val deshecho: Boolean = false
    )

    /** **Todo lo de la reunión**, en el orden en que se dijo. */
    data class Acta(
        val decisiones: List<Dicho> = emptyList(),
        val preguntasAbiertas: List<Dicho> = emptyList(),
        val tiempoPorTema: TiempoPorTema? = null,
        val propuestas: List<Propuesta> = emptyList(),
        val cambios: List<CambioDeUrgencia> = emptyList()
    ) {
        fun vacia(): Boolean = decisiones.isEmpty() && preguntasAbiertas.isEmpty() && tiempoPorTema == null && propuestas.isEmpty() && cambios.isEmpty()

        /** Las propuestas que nadie aceptó ni descartó. */
        fun pendientes(): List<Propuesta> = propuestas.filter { it.estado == Estado.PENDIENTE }
    }

    // --- Leer: como `serde`, lo que no encaja se salta -----------------------------------------

    private class NoEncaja : Exception()

    private fun JsonObject.cadena(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw NoEncaja()

    private fun JsonObject.cadenaONada(k: String): String? = when (val v = this[k]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else throw NoEncaja()
        else -> throw NoEncaja()
    }

    private fun JsonObject.entero(k: String): Long =
        (this[k] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.longOrNull ?: throw NoEncaja()

    private fun JsonObject.enteroO(k: String, otro: Long): Long = if (k in this) entero(k) else otro

    private fun JsonObject.siO(k: String, otro: Boolean): Boolean =
        if (k !in this) otro else (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw NoEncaja()

    private fun <T> lista(a: JsonArray?, f: (JsonObject) -> T): List<T> =
        a.orEmpty().mapNotNull { x -> (x as? JsonObject)?.let { o -> try { f(o) } catch (_: NoEncaja) { null } } }

    private fun dicho(o: JsonObject) = Dicho(o.entero("ms"), o.cadena("texto"))

    private fun propuesta(o: JsonObject) = Propuesta(
        id = o.cadena("id"),
        tipo = Tipo.entries.firstOrNull { it.id == o.cadena("tipo") } ?: throw NoEncaja(),
        ms = o.entero("ms"),
        texto = o.cadena("texto"),
        responsable = o.cadenaONada("responsable"),
        fecha = o.cadenaONada("fecha"),
        hora = o.cadenaONada("hora"),
        prioridad = o.cadenaONada("prioridad"),
        estado = if ("estado" in o) Estado.entries.firstOrNull { it.id == o.cadena("estado") } ?: throw NoEncaja() else Estado.PENDIENTE
    )

    private fun cambio(o: JsonObject) = CambioDeUrgencia(
        ms = o.entero("ms"), tarea = o.cadena("tarea"), antes = o.cadena("antes"), despues = o.cadena("despues"),
        fechaAntes = o.cadenaONada("fechaAntes"), fechaDespues = o.cadenaONada("fechaDespues"),
        deshecho = o.siO("deshecho", false)
    )

    private fun tiempo(o: JsonObject?): TiempoPorTema? {
        if (o == null) return null
        val temas = lista(o["temas"] as? JsonArray) { TiempoDeTema(it.cadena("id"), it.entero("ms").coerceAtLeast(0)) }
        val otro = runCatching { o.enteroO("otro", 0) }.getOrDefault(0).coerceAtLeast(0)
        return TiempoPorTema(temas, otro)
    }

    /** **Lee el acta** de una nota (vacía si no tiene). */
    fun de(m: Mensaje?): Acta {
        if (m == null) return Acta()
        return Acta(
            decisiones = lista(m.decisiones, ::dicho),
            preguntasAbiertas = lista(m.preguntasAbiertas, ::dicho),
            tiempoPorTema = tiempo(m.tiempoPorTema),
            propuestas = lista(m.propuestas, ::propuesta),
            cambios = lista(m.cambiosDeUrgencia, ::cambio)
        )
    }

    // --- Escribir: el orden de los campos del PC ----------------------------------------------

    private fun dichoEnJson(d: Dicho) = JsonObject(linkedMapOf("ms" to JsonPrimitive(d.ms), "texto" to JsonPrimitive(d.texto)))

    fun propuestaEnJson(p: Propuesta): JsonObject {
        val o = LinkedHashMap<String, JsonElement>()
        o["id"] = JsonPrimitive(p.id)
        o["tipo"] = JsonPrimitive(p.tipo.id)
        o["ms"] = JsonPrimitive(p.ms)
        o["texto"] = JsonPrimitive(p.texto)
        p.responsable?.let { o["responsable"] = JsonPrimitive(it) }
        p.fecha?.let { o["fecha"] = JsonPrimitive(it) }
        p.hora?.let { o["hora"] = JsonPrimitive(it) }
        p.prioridad?.let { o["prioridad"] = JsonPrimitive(it) }
        o["estado"] = JsonPrimitive(p.estado.id)
        return JsonObject(o)
    }

    private fun cambioEnJson(c: CambioDeUrgencia): JsonObject {
        val o = LinkedHashMap<String, JsonElement>()
        o["ms"] = JsonPrimitive(c.ms)
        o["tarea"] = JsonPrimitive(c.tarea)
        o["antes"] = JsonPrimitive(c.antes)
        o["despues"] = JsonPrimitive(c.despues)
        c.fechaAntes?.let { o["fechaAntes"] = JsonPrimitive(it) }
        c.fechaDespues?.let { o["fechaDespues"] = JsonPrimitive(it) }
        o["deshecho"] = JsonPrimitive(c.deshecho)
        return JsonObject(o)
    }

    private fun tiempoEnJson(t: TiempoPorTema) = JsonObject(
        linkedMapOf(
            "temas" to JsonArray(t.temas.map { JsonObject(linkedMapOf("id" to JsonPrimitive(it.id), "ms" to JsonPrimitive(it.ms))) }),
            "otro" to JsonPrimitive(t.otro)
        )
    )

    /** **Escribe el acta** en la nota. Lo vacío se quita (null). */
    fun poner(m: Mensaje, a: Acta): Mensaje = m.copy(
        decisiones = a.decisiones.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::dichoEnJson)) },
        preguntasAbiertas = a.preguntasAbiertas.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::dichoEnJson)) },
        propuestas = a.propuestas.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::propuestaEnJson)) },
        cambiosDeUrgencia = a.cambios.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::cambioEnJson)) },
        tiempoPorTema = a.tiempoPorTema?.let(::tiempoEnJson)
    )

    /** «6 min», «45 s»: lo que llevó un tema, redondeado al minuto (por debajo de uno, en segundos). */
    fun duracionCorta(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        return if (s < 60) "$s s" else "${(s + 30) / 60} min"
    }
}
