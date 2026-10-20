package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * **Las automatizaciones y las recetas del asistente** (`automatizaciones.rs` y `asistente/recetas.rs`
 * del PC): el fichero `guardados/asistente/automatizaciones.json` que viaja por el chat «Asistente»
 * con su mensaje `auto-automatizaciones` (§3.5).
 *
 * **Conserva lo que no conoce**, como pide el PC: cada automatización se guarda como su JSON crudo
 * —se cambian solo las claves que se tocan—, una entrada que no se entiende (de una versión más
 * nueva) se deja tal cual y se lista como rota, y las claves extra de la raíz también siguen.
 *
 * Una **receta** es una automatización `a_mano`, `activa`, con un solo paso `asistente` y
 * `boton: true`, más `icono` y `orden` (topes 40 letras, 2000 de pedido, 40 botones).
 */
object Automatizaciones {
    const val VERSION = 1L
    const val ID_MENSAJE = "auto-automatizaciones"
    const val RUTA = "guardados/asistente/automatizaciones.json"
    const val TEXTO_DEL_MENSAJE = "⚙️ Las automatizaciones del asistente (se cambian en Ajustes → Asistente)"
    const val MAX_PASOS = 10
    const val TOPE_NOMBRE = 40
    const val TOPE_PEDIDO = 2000
    const val MAXIMO_RECETAS = 40
    val ICONOS = listOf("⭐", "📝", "📊", "🎨", "🔎", "📄", "🖼️", "✅", "📅", "💡", "🌐", "🤖")
    private val DISPARADORES = setOf("al_capturar", "a_la_hora", "al_sincronizar", "a_mano")

    /** Una entrada del fichero, cruda; [motivo] no es null si no se entiende (se conserva igual). */
    data class Entrada(val valor: JsonElement, val motivo: String?) {
        val obj: JsonObject? get() = valor as? JsonObject
        val id: String? get() = (obj?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val buena: Boolean get() = motivo == null
    }

    data class Lista(val entradas: MutableList<Entrada> = mutableListOf(), val resto: MutableMap<String, JsonElement> = LinkedHashMap()) {
        fun buenas() = entradas.filter { it.buena }.mapNotNull { it.obj }
        fun buscar(id: String) = buenas().firstOrNull { (it["id"] as? JsonPrimitive)?.content == id }

        fun aJson(): String {
            val o = LinkedHashMap<String, JsonElement>(resto)
            o["version"] = JsonPrimitive(VERSION)
            o["automatizaciones"] = JsonArray(entradas.map { it.valor })
            return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), ordenado(JsonObject(o)) as JsonObject)
        }

        fun borrar(id: String): Boolean = entradas.removeAll { it.id == id }

        fun reemplazar(id: String, nuevo: JsonObject) {
            val i = entradas.indexOfFirst { it.id == id && it.buena }
            if (i >= 0) entradas[i] = Entrada(nuevo, null)
        }

        fun anadir(a: JsonObject) {
            motivo(a)?.let { error(it) }
            val id = (a["id"] as? JsonPrimitive)?.content
            if (buscar(id.orEmpty()) != null) error("ya hay una automatización «$id»")
            entradas += Entrada(a, null)
        }

        companion object {
            fun deJson(texto: String): Lista {
                val v = runCatching { Json.parseToJsonElement(texto) }.getOrElse { throw IllegalArgumentException("no es JSON") }
                val o = (v as? JsonObject)?.toMutableMap() ?: throw IllegalArgumentException("no es un objeto JSON")
                val items = when (val a = o.remove("automatizaciones")) {
                    is JsonArray -> a
                    null -> JsonArray(emptyList())
                    else -> throw IllegalArgumentException("«automatizaciones» no es una lista")
                }
                o.remove("version")
                return Lista(items.map { Entrada(it, motivo(it)) }.toMutableList(), LinkedHashMap(o))
            }
        }
    }

    private fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** null si se entiende; si no, por qué (y se conserva tal cual). */
    fun motivo(v: JsonElement): String? {
        val o = v as? JsonObject ?: return "no es un objeto"
        if (s(o, "id").isNullOrEmpty()) return "le falta «id»"
        if (s(o, "nombre") == null) return "le falta «nombre»"
        val d = o["disparador"] as? JsonObject ?: return "le falta «disparador»"
        val tipo = s(d, "tipo") ?: return "le falta «tipo»"
        if (tipo !in DISPARADORES) return "«$tipo» es de una versión más nueva"
        if (tipo == "a_la_hora" && minutoDelDia(s(d, "hora").orEmpty()) == null) return "la hora no vale"
        val pasos = o["pasos"] as? JsonArray ?: return "le falta «pasos»"
        if (pasos.isEmpty()) return "no tiene pasos"
        if (pasos.size > MAX_PASOS) return "tiene más de $MAX_PASOS pasos"
        for (p in pasos) {
            val po = p as? JsonObject ?: return "un paso no es un objeto"
            when (s(po, "tipo")) {
                "accion" -> if (s(po, "herramienta").isNullOrEmpty()) return "un paso sin «herramienta»"
                "asistente" -> if (s(po, "pedido").isNullOrBlank()) return "un paso sin «pedido»"
                null -> return "un paso sin «tipo»"
                else -> return "«${s(po, "tipo")}» es de una versión más nueva"
            }
        }
        return null
    }

    /**
     * **Una automatización desde la receta del pedido** (PC `de_receta`, la fila `crear_automatizacion`):
     * `nombre`, `disparador.tipo` (y `hora`, `dias`) y `pasos` (una lista o un texto con una lista JSON;
     * cada paso, un texto —un pedido al asistente— u `{herramienta, args?, proyecto?, imagenes?}` o
     * `{asistente: "…"}`). El JSON sale con la forma del PC (`tipo` en el disparador y en cada paso). Sin
     * `aparato`: el teléfono no corre automatizaciones, así que corre en el PC. Lanza el motivo si no vale.
     */
    fun deReceta(receta: JsonObject, id: String, ahora: Long): JsonObject {
        fun cad(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
        val nombre = cad(receta, "nombre")?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("falta el nombre")
        val d = receta["disparador"]
        val tipo = (if (d is JsonObject) cad(d, "tipo") else (d as? JsonPrimitive)?.takeIf { it.isString }?.content).orEmpty()
        val disparador = when (Acciones.normalizar(tipo.trim()).replace(' ', '_').replace('-', '_')) {
            "al_capturar" -> buildJsonObject { put("tipo", "al_capturar") }
            "al_sincronizar" -> buildJsonObject { put("tipo", "al_sincronizar") }
            "a_mano" -> buildJsonObject { put("tipo", "a_mano") }
            "a_la_hora" -> {
                val hora = cad(d as? JsonObject, "hora").orEmpty().trim()
                if (minutoDelDia(hora) == null) throw IllegalArgumentException("la hora «$hora» no se entiende (HH:MM)")
                val dias = ((d as? JsonObject)?.get("dias") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()
                if (dias.any { it !in 1..7 }) throw IllegalArgumentException("los días van del 1 (lunes) al 7 (domingo)")
                buildJsonObject { put("tipo", "a_la_hora"); put("hora", hora); if (dias.isNotEmpty()) put("dias", JsonArray(dias.map { JsonPrimitive(it) })) }
            }
            else -> throw IllegalArgumentException("disparador desconocido «$tipo»")
        }
        val pasosV: JsonArray = when (val p = receta["pasos"]) {
            is JsonArray -> p
            is JsonPrimitive -> if (p.isString) runCatching { Json.parseToJsonElement(p.content) as JsonArray }.getOrNull()
                ?: p.content.trim().takeIf { it.isNotEmpty() }?.let { JsonArray(listOf(JsonPrimitive(it))) }
                ?: throw IllegalArgumentException("faltan los pasos") else throw IllegalArgumentException("faltan los pasos")
            else -> throw IllegalArgumentException("faltan los pasos")
        }
        val pasos = pasosV.mapIndexed { i, v ->
            fun malo(m: String): Nothing = throw IllegalArgumentException("paso ${i + 1}: $m")
            when {
                v is JsonPrimitive && v.isString && v.content.isNotBlank() -> buildJsonObject { put("tipo", "asistente"); put("pedido", v.content.trim()) }
                v is JsonObject -> {
                    val imagenes = (v["imagenes"] as? JsonArray)?.filter { it is JsonPrimitive && it.isString }.orEmpty()
                    if (cad(v, "tipo") == "asistente" || cad(v, "tipo") == "accion") v
                    else (cad(v, "asistente") ?: cad(v, "pedido"))?.let { pedido ->
                        buildJsonObject { put("tipo", "asistente"); put("pedido", pedido); cad(v, "proyecto")?.let { put("proyecto", it) }; if (imagenes.isNotEmpty()) put("imagenes", JsonArray(imagenes)) }
                    } ?: run {
                        val herramienta = cad(v, "herramienta") ?: cad(v, "accion") ?: malo("ni «herramienta» ni «asistente»")
                        val fila = Acciones.porNombre(herramienta) ?: malo("no hay ninguna acción «$herramienta»")
                        // Que no se dispare a sí misma ni cree otras (PC `bloqueado`).
                        if (fila.accion == "automatizacion") malo("una automatización no puede crear ni tocar automatizaciones")
                        val tipoDisparo = (disparador["tipo"] as JsonPrimitive).content
                        if ((tipoDisparo == "al_capturar" && fila.accion == "capturar") || (tipoDisparo == "al_sincronizar" && fila.accion == "sincronizar")) {
                            malo("«${fila.accion}» haría saltar otra vez esta automatización: sería un bucle")
                        }
                        try { Acciones.validar(fila, (v["args"] as? JsonObject) ?: JsonObject(emptyMap())) } catch (e: ErrorHerramienta) {
                            // Las variables ({captura}, {resultado}) se ponen al correr: aquí solo cuentan los que faltan.
                            if (e.message?.startsWith("falta") == true) malo(e.message!!)
                        }
                        buildJsonObject {
                            put("tipo", "accion"); put("herramienta", herramienta)
                            (v["args"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { put("args", it) }
                            cad(v, "proyecto")?.let { put("proyecto", it) }
                            if (imagenes.isNotEmpty()) put("imagenes", JsonArray(imagenes))
                        }
                    }
                }
                else -> malo("un paso es un texto o un objeto")
            }
        }
        if (pasos.isEmpty()) throw IllegalArgumentException("no tiene pasos")
        if (pasos.size > MAX_PASOS) throw IllegalArgumentException("tiene ${pasos.size} pasos; como mucho $MAX_PASOS")
        return buildJsonObject {
            put("id", id); put("nombre", nombre); put("activa", true); put("disparador", disparador)
            put("pasos", JsonArray(pasos)); put("creada", ahora); put("tocada", ahora)
        }
    }

    fun minutoDelDia(hora: String): Int? {
        val (h, m) = hora.trim().split(':').takeIf { it.size == 2 } ?: return null
        val hh = h.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        return if (hh in 0..23 && mm in 0..59) hh * 60 + mm else null
    }

    /** Si corre en este aparato: sin `aparato`, en todos. */
    fun correAqui(a: JsonObject, aparato: String) = s(a, "aparato").let { it.isNullOrEmpty() || it == aparato }

    // ------------------------------------------------------------------------------ recetas

    data class Receta(val id: String, val nombre: String, val icono: String, val pedido: String, val orden: Long)

    fun receta(a: JsonObject): Receta? {
        if (s(a["disparador"] as? JsonObject, "tipo") != "a_mano") return null
        if ((a["activa"] as? JsonPrimitive)?.booleanOrNull == false) return null
        if ((a["boton"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val pasos = a["pasos"] as? JsonArray ?: return null
        val p = pasos.singleOrNull() as? JsonObject ?: return null
        if (s(p, "tipo") != "asistente") return null
        return Receta(s(a, "id").orEmpty(), s(a, "nombre").orEmpty(), s(a, "icono").orEmpty(), s(p, "pedido").orEmpty(),
            (a["orden"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE)
    }

    fun recetas(l: Lista): List<Receta> = l.buenas().mapNotNull { a -> receta(a)?.let { it to ((a["creada"] as? JsonPrimitive)?.longOrNull ?: 0L) } }
        .sortedWith(compareBy<Pair<Receta, Long>>({ it.first.orden }, { it.second }, { it.first.id })).map { it.first }

    private fun recortar(t: String, n: Int) = t.replace('\n', ' ').trim().let { if (it.codePointCount(0, it.length) <= n) it else it.substring(0, it.offsetByCodePoints(0, n)) }

    fun idNuevo(ahora: Long, n: Int = 0) = "auto-$ahora-$n"

    /** **Una receta nueva** al final de la fila. Lanza si está vacía o hay 40. */
    fun anadirReceta(l: Lista, nombre: String, icono: String, pedido0: String, ahora: Long): Receta {
        val pedido = pedido0.trim().take(TOPE_PEDIDO)
        if (pedido.isBlank()) error("la receta está vacía")
        val fila = recetas(l)
        if (fila.size >= MAXIMO_RECETAS) error("ya hay $MAXIMO_RECETAS recetas")
        val orden = fila.map { it.orden + 1 }.filter { it != Long.MAX_VALUE }.maxOrNull() ?: 0L
        var n = 0
        while (l.entradas.any { it.id == idNuevo(ahora, n) }) n++
        val a = buildJsonObject {
            put("id", idNuevo(ahora, n))
            put("nombre", if (nombre.isBlank()) nombrePara(pedido) else recortar(nombre, TOPE_NOMBRE))
            put("activa", true)
            put("disparador", buildJsonObject { put("tipo", "a_mano") })
            put("pasos", buildJsonArray { add(buildJsonObject { put("tipo", "asistente"); put("pedido", pedido) }) })
            put("creada", ahora); put("tocada", ahora)
            put("boton", true); put("icono", icono.trim().ifEmpty { iconoPara(pedido) }); put("orden", orden)
        }
        l.anadir(a)
        return receta(a)!!
    }

    /** Cambia nombre, icono, pedido u orden de una receta, conservando todo lo demás del JSON. */
    fun cambiarReceta(l: Lista, id: String, ahora: Long, cambio: (Receta) -> Receta) {
        val a = l.buscar(id) ?: error("no está")
        val r = cambio(receta(a) ?: error("no es una receta"))
        val nombre = recortar(r.nombre, TOPE_NOMBRE)
        val pedido = r.pedido.trim().take(TOPE_PEDIDO)
        if (pedido.isEmpty() || nombre.isEmpty()) error("la receta está vacía")
        val paso = ((a["pasos"] as JsonArray)[0] as JsonObject).toMutableMap().also { it["pedido"] = JsonPrimitive(pedido) }
        val nuevo = a.toMutableMap().also {
            it["nombre"] = JsonPrimitive(nombre); it["pasos"] = JsonArray(listOf(JsonObject(paso)))
            it["icono"] = JsonPrimitive(r.icono.trim()); it["orden"] = JsonPrimitive(r.orden); it["tocada"] = JsonPrimitive(ahora)
        }
        l.reemplazar(id, JsonObject(nuevo))
    }

    /** Mueve una receta [paso] puestos (a la izquierda negativo) y renumera el orden. */
    fun moverReceta(l: Lista, id: String, paso: Int, ahora: Long) {
        val fila = recetas(l).map { it.id }.toMutableList()
        val i = fila.indexOf(id).takeIf { it >= 0 } ?: error("no está")
        val j = (i + paso).coerceIn(0, fila.size - 1)
        fila.add(j, fila.removeAt(i))
        fila.forEachIndexed { n, x ->
            val a = l.buscar(x) ?: return@forEachIndexed
            if ((a["orden"] as? JsonPrimitive)?.longOrNull != n.toLong())
                l.reemplazar(x, JsonObject(a.toMutableMap().also { it["orden"] = JsonPrimitive(n); it["tocada"] = JsonPrimitive(ahora) }))
        }
    }

    fun borrarReceta(l: Lista, id: String) {
        if (l.buscar(id)?.let { receta(it) } == null) error("no está")
        l.borrar(id)
    }

    /** El nombre corto de un pedido (hasta 22 letras, en palabras enteras). */
    fun nombrePara(pedido: String): String {
        val s = StringBuilder()
        for (p in pedido.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            if (s.length + p.length + 1 > 22) break
            if (s.isNotEmpty()) s.append(' ')
            s.append(p.trim { !it.isLetterOrDigit() && it != '¿' && it != '?' })
        }
        return s.toString().ifEmpty { pedido.take(22) }
    }

    fun iconoPara(pedido: String): String {
        val q = pedido.lowercase()
        fun tiene(vararg v: String) = v.any { q.contains(it) }
        return when {
            tiene("resum", "apunt", "nota", "escrib", "redact") -> "📝"
            tiene("gráfic", "grafic", "barras", "tabla", "datos") -> "📊"
            tiene("dibuj", "lienzo", "pinta", "calca") -> "🎨"
            tiene("foto", "imagen", "describ", "qué pone", "que pone") -> "🖼️"
            tiene("lee", "pdf", "ocr", "texto") -> "📄"
            tiene("tarea", "lista", "pendiente") -> "✅"
            tiene("traduc", "idioma", "inglés", "ingles") -> "🌐"
            else -> "⭐"
        }
    }
}
