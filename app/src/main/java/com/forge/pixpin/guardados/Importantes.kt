package com.forge.pixpin.guardados

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

/**
 * **Lo importante de una clase o reunión** (28-oct-2026, Jev de TypeSafe; porte de la parte
 * «importantes» de `crates/pixpin-proyecto/src/clase.rs` del PC, `df8884e2` y `d8ddb756`; el campo,
 * en `docs/manual-sincronizar-lo-nuevo.md` §14 del PC).
 *
 * Lo que Jev halló en la transcripción al acabar de pasarla a texto: [Mensaje.importantes], una lista
 * `[{ms, tipo, p}]` en orden, con `tipo` = `tarea`, `decision`, `pregunta` o `examen`, `p` la
 * probabilidad (0-1, dos decimales) y, en las tareas, `quien`/`cuando` (`true`/`false`, solo si se
 * sabe) y `enTareas: true` una vez apuntada en Tareas. `ms` es el del renglón de la transcripción.
 * [Mensaje.importantesDe] dice qué modelo lo halló.
 *
 * **La clave sin lista es «no se ha mirado»; una lista vacía es «se miró y no hay nada»**: así no se
 * vuelve a preguntar. Esto se lee y se enseña **sin clave ni red**: lo escribe el PC (o este teléfono
 * con su propia clave, ver `jev/`). Lo prueba `ImportantesTest` con las pruebas del PC.
 */
object Importantes {

    /** Qué clase de parte importante (`TipoImportante` del PC), en el orden en que se enseñan. */
    enum class Tipo(val id: String, val rotulo: String, val color: Long) {
        TAREA("tarea", "Tarea", 0xFF3B82F6),
        DECISION("decision", "Decisión", 0xFF22A06B),
        PREGUNTA("pregunta", "Pregunta", 0xFF8B5CF6),
        EXAMEN("examen", "Examen", 0xFFE5534B);

        companion object {
            fun deId(s: String?): Tipo? = entries.firstOrNull { it.id == s }
        }
    }

    /** Una parte importante de la clase. */
    data class Importante(
        val ms: Long,
        val tipo: Tipo,
        val p: Double,
        /** En una tarea: si dice quién la hace (null: no se sabe). */
        val quien: Boolean? = null,
        /** En una tarea: si dice para cuándo. */
        val cuando: Boolean? = null,
        /** En una tarea: si ya se apuntó en Tareas (no se ofrece otra vez). */
        val enTareas: Boolean = false
    )

    private val ORDEN = compareBy<Importante>({ it.ms }, { it.tipo.ordinal })

    private fun prim(e: JsonElement?): JsonPrimitive? = (e as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }

    /**
     * **Las partes importantes de una nota**, en orden (`importantes_de` del PC). Null si nunca se
     * miraron; lo que no se entiende (un tipo de otra versión) se salta sin llevarse lo demás.
     */
    fun de(lista: JsonArray?): List<Importante>? {
        val a = lista ?: return null
        return a.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val ms = prim(o["ms"])?.takeIf { !it.isString }?.longOrNull ?: return@mapNotNull null
            val tipo = Tipo.deId(prim(o["tipo"])?.takeIf { it.isString }?.contentOrNull) ?: return@mapNotNull null
            Importante(
                ms = ms.coerceAtLeast(0),
                tipo = tipo,
                p = (prim(o["p"])?.takeIf { !it.isString }?.doubleOrNull ?: 1.0).coerceIn(0.0, 1.0),
                quien = prim(o["quien"])?.takeIf { !it.isString }?.booleanOrNull,
                cuando = prim(o["cuando"])?.takeIf { !it.isString }?.booleanOrNull,
                enTareas = prim(o["enTareas"])?.takeIf { !it.isString }?.booleanOrNull == true
            )
        }.sortedWith(ORDEN)
    }

    fun de(m: Mensaje?): List<Importante>? = de(m?.importantes)

    /**
     * **Lo que se escribe** (también una lista vacía: «ya se miró»), en orden (`poner_importantes`
     * del PC). Las claves de cada uno van en orden alfabético, el del `Map` del PC. Devuelve la lista
     * y el modelo (null si viene vacío: la clave se quita).
     */
    fun poner(v: List<Importante>, modelo: String): Pair<JsonArray, String?> {
        val lista = v.sortedWith(ORDEN).map { i ->
            val o = sortedMapOf<String, JsonElement>()
            o["ms"] = JsonPrimitive(i.ms.coerceAtLeast(0))
            o["tipo"] = JsonPrimitive(i.tipo.id)
            o["p"] = JsonPrimitive((i.p.coerceIn(0.0, 1.0) * 100.0).roundToLong() / 100.0)
            i.quien?.let { o["quien"] = JsonPrimitive(it) }
            i.cuando?.let { o["cuando"] = JsonPrimitive(it) }
            if (i.enTareas) o["enTareas"] = JsonPrimitive(true)
            JsonObject(o)
        }
        return JsonArray(lista) to modelo.trim().ifEmpty { null }
    }

    /** [poner] sobre un mensaje. */
    fun ponerEn(m: Mensaje, v: List<Importante>, modelo: String): Mensaje {
        val (lista, de) = poner(v, modelo)
        return m.copy(importantes = lista, importantesDe = de)
    }

    /**
     * **Marca como apuntadas en Tareas** las tareas de esos minutos (`marcar_en_tareas` del PC).
     * Devuelve la lista nueva, o null si no cambió nada. Lo demás de cada entrada se respeta tal cual.
     */
    fun marcarEnTareas(lista: JsonArray?, minutos: Collection<Long>): JsonArray? {
        val a = lista ?: return null
        var cambio = false
        val nueva = a.map { x ->
            val o = x as? JsonObject ?: return@map x
            val esTarea = prim(o["tipo"])?.takeIf { it.isString }?.contentOrNull == Tipo.TAREA.id
            val ms = prim(o["ms"])?.takeIf { !it.isString }?.longOrNull
            val ya = prim(o["enTareas"])?.takeIf { !it.isString }?.booleanOrNull == true
            if (esTarea && ms != null && ms in minutos && !ya) {
                cambio = true
                JsonObject(sortedMapOf<String, JsonElement>().apply { putAll(o); put("enTareas", JsonPrimitive(true)) })
            } else x
        }
        return if (cambio) JsonArray(nueva) else null
    }

    /** Los tipos de las partes importantes de un renglón que empieza en [ms] (`tipos_en` del PC). */
    fun tiposEn(importantes: List<Importante>, ms: Long?): List<Tipo> {
        if (ms == null) return emptyList()
        return importantes.filter { it.ms == ms }.map { it.tipo }.distinct().sortedBy { it.ordinal }
    }

    /**
     * **El filtro de lo importante**: qué renglones se ven con [filtro] (null: todos). Un renglón sin
     * minuto solo se ve sin filtro (`se_ve_con_filtro` del PC).
     */
    fun seVeConFiltro(importantes: List<Importante>, ms: Long?, filtro: Tipo?): Boolean =
        filtro == null || filtro in tiposEn(importantes, ms)

    /** Cuántos renglones (minutos distintos) hay de cada tipo, para el filtro «Tarea 3 · …». */
    fun cuentas(importantes: List<Importante>): Map<Tipo, Int> =
        Tipo.entries.associateWith { t -> importantes.filter { it.tipo == t }.map { it.ms }.distinct().size }

    /** Los minutos de las tareas aún sin apuntar en Tareas (lo que ofrece «+N tareas a Tareas»). */
    fun tareasSinApuntar(importantes: List<Importante>): List<Long> =
        importantes.filter { it.tipo == Tipo.TAREA && !it.enTareas }.map { it.ms }.distinct()

    /**
     * **El texto de la tarea apuntada desde la clase** (`texto_de_tarea` del PC): lo dicho (los blancos
     * juntos, recortado a 140) y, en su nota (`💬`), de qué clase y en qué minuto, para volver a él.
     */
    fun textoDeTarea(dicho: String, clase: String, ms: Long): String {
        val junto = dicho.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val n = junto.codePointCount(0, junto.length)
        val corto = if (n > 140) junto.substring(0, junto.offsetByCodePoints(0, 140)) + "…" else junto
        return "$corto ${com.forge.pixpin.mini.TareaInteligente.NOTA} 🎙 ${clase.trim()} ⏱ ${Transcriptor.marcaDeTiempo(ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())}"
    }
}
