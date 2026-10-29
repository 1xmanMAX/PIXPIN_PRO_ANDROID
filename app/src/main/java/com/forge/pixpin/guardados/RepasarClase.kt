package com.forge.pixpin.guardados

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * **Lo puro de «Repasar en voz alta»** (29-oct-2026; `puntos_clave` y `lo_ultimo` de
 * `apps/pixpin/src/ventana_chat/repaso_oral.rs` del PC, `5282d2f5`, y `temas_de` de
 * `crates/pixpin-proyecto/src/temas.rs`). La pantalla es [RepasarActivity]; las preguntas y la tabla,
 * [com.forge.pixpin.jev.RepasoOral].
 *
 * **Los puntos clave** salen de lo que ya viaja en la nota de voz: lo que Jev marcó como «entra en el
 * examen» y como decisión (`importantes`, con el renglón de ese minuto), los marcadores con nota y los
 * temas de la reunión tratados (`temas`); doce como mucho (los de examen y los marcadores primero), sin
 * repetir, en el orden de la clase. Nada de esto se guarda. Lo prueba `DictadoYRepasoTest`.
 */
object RepasarClase {

    /** Un punto clave: lo que dice y el minuto de la clase donde se explicó. */
    data class PuntoClave(val texto: String, val ms: Long?)

    /** Un tema de la reunión (`pixpin_proyecto::temas::Tema`, lo que hace falta aquí). */
    data class Tema(val id: String, val texto: String, val hecho: Boolean, val ms: Long?)

    /** Lo largo de un punto en pantalla y en la pregunta. */
    const val LARGO_PUNTO = 200

    /** Lo último de lo explicado que se enseña abajo. */
    const val LARGO_DICHO = 220

    /** Lo de Markdown que no se lee (`voz::sin_marcas` del PC): las imágenes fuera, sin `#`, `**` ni `__`. */
    fun sinMarcas(texto: String): String =
        texto.lines()
            .filterNot { it.trimStart().startsWith("![") }
            .joinToString("\n") { it.trimStart('#', ' ').replace("**", "").replace("__", "") }
            .trim()

    /** **Los puntos clave de una clase** (ver la cabecera; `puntos_clave` del PC, regla a regla). */
    fun puntosClave(
        lineas: List<EstudiarClase.Linea>,
        importantes: List<Importantes.Importante>,
        marcadores: List<EstudiarClase.Marcador>,
        temas: List<Tema>
    ): List<PuntoClave> {
        val tiempos = lineas.map { it.ms }
        fun renglon(ms: Long): String? =
            EstudiarClase.lineaEn(tiempos, ms)?.let { lineas.getOrNull(it) }?.let { sinMarcas(it.texto).trim() }?.takeIf { it.isNotEmpty() }
        // (prioridad, punto): menos es antes.
        val v = mutableListOf<Pair<Int, PuntoClave>>()
        for (i in importantes) {
            val prioridad = when (i.tipo) {
                Importantes.Tipo.EXAMEN -> 0
                Importantes.Tipo.DECISION -> 2
                Importantes.Tipo.TAREA, Importantes.Tipo.PREGUNTA -> continue
            }
            renglon(i.ms)?.let { v += prioridad to PuntoClave(it, i.ms) }
        }
        for (m in marcadores) {
            val nota = m.texto.trim()
            if (nota.isNotEmpty()) v += 1 to PuntoClave(nota, m.ms)
        }
        for (t in temas) if (t.hecho && t.texto.isNotBlank()) v += 3 to PuntoClave(t.texto.trim(), t.ms)
        // Sin repetir: el mismo renglón puede ser examen y decisión. (Un minuto que falta va antes, como el
        // `None` del PC.)
        val vistos = HashSet<String>()
        return v.sortedWith(compareBy<Pair<Int, PuntoClave>> { it.first }.thenBy(nullsFirst()) { it.second.ms })
            .filter { vistos.add(it.second.texto.lowercase()) }
            .take(com.forge.pixpin.jev.Preguntas.Repaso.POR_PETICION)
            .map { it.second.copy(texto = recortar(it.second.texto, LARGO_PUNTO)) }
            .sortedBy { it.ms ?: Long.MAX_VALUE }
    }

    /** Recorta un texto a [n] letras, con «…». */
    fun recortar(s0: String, n: Int): String {
        val s = s0.trim()
        val total = s.codePointCount(0, s.length)
        return if (total <= n) s else s.substring(0, s.offsetByCodePoints(0, n)) + "…"
    }

    /** Lo último de un texto, con «…» delante si se cortó (`lo_ultimo` del PC). */
    fun loUltimo(texto: String, largo: Int): String {
        val s = texto.trim()
        val total = s.codePointCount(0, s.length)
        return if (total <= largo) s else "…" + s.substring(s.offsetByCodePoints(0, total - largo))
    }

    /** «m:ss» (o «h:mm:ss») de un milisegundo, para «▶ 12:34». */
    fun minuto(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /**
     * **Los temas de una nota** (`temas_de` del PC): `[{id, texto, hecho, ms, …}]`; los que no tienen
     * `id` o `texto`, o repiten `id`, se saltan.
     */
    fun temasDe(a: JsonArray?): List<Tema> {
        val v = mutableListOf<Tema>()
        for (x in a.orEmpty()) {
            val o = x as? JsonObject ?: continue
            fun prim(k: String) = o[k] as? JsonPrimitive
            val id = prim("id")?.takeIf { it.isString }?.contentOrNull ?: continue
            val texto = prim("texto")?.takeIf { it.isString }?.contentOrNull ?: continue
            if (id.isBlank() || texto.isBlank() || v.any { it.id == id }) continue
            v += Tema(
                id = id,
                texto = texto.trim(),
                hecho = prim("hecho")?.takeIf { !it.isString }?.booleanOrNull == true,
                ms = prim("ms")?.takeIf { !it.isString }?.longOrNull?.coerceAtLeast(0)
            )
        }
        return v
    }

    /**
     * **Los temas de la nota [id], leídos de su renglón en el disco.** El campo `temas` lo escribe el PC
     * (y la reunión del móvil); esta pantalla no depende de que [Mensaje] lo lleve: lo busca en la línea
     * tal cual de `guardados.jsonl`. Trabajo de disco; sin nada, una lista vacía.
     */
    fun temasDeLaNota(context: Context, id: String): List<Tema> = runCatching {
        val f = File(context.filesDir, "guardados.jsonl")
        if (!f.isFile) return@runCatching emptyList()
        var temas: JsonArray? = null
        val marca = "\"$id\""
        f.forEachLine { linea ->
            if (!linea.contains(marca)) return@forEachLine
            val o = runCatching { Json.parseToJsonElement(linea) as? JsonObject }.getOrNull() ?: return@forEachLine
            if ((o["id"] as? JsonPrimitive)?.contentOrNull != id) return@forEachLine
            // La última versión del renglón manda (el almacén reescribe al final).
            temas = o["temas"] as? JsonArray
        }
        temasDe(temas)
    }.getOrDefault(emptyList())
}
