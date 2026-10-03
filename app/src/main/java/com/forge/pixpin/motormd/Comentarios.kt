package com.forge.pixpin.motormd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * **Los comentarios de una nota Markdown** (acordado con el PC el 30-sep-2026; puerto de
 * `pixpin-docs/src/md_comentarios.rs`, mismas reglas y mismas pruebas).
 *
 * Nada va dentro del `.md`: los comentarios viven en `pins/draw/anot-<uid>.comentarios.json`
 * (ver [com.forge.pixpin.sincro.AnotacionesDelAdjunto.comentarios]) y cada uno se **ancla con
 * una cita**, no con posiciones: el texto comentado y hasta [CONTEXTO] letras a cada lado. Así
 * sigue a su frase aunque se escriba delante o dentro, en este aparato o en el otro.
 *
 * Todas las posiciones son índices de `String` de Kotlin (UTF-16), las mismas que las del
 * `RichEdit` del PC, sobre el Markdown tal como se guarda.
 *
 * **Lo que no se entienda se conserva**: cualquier campo desconocido, arriba, en un hilo, en una
 * respuesta o en el ancla, se vuelve a escribir tal cual ([resto]).
 */
object Comentarios {
    const val VERSION = 1
    const val CONTEXTO = 32

    data class Ancla(
        val cita: String = "",
        val antes: String = "",
        val despues: String = "",
        val pos: Int = 0,
        val resto: Map<String, JsonElement> = emptyMap()
    )

    data class Respuesta(
        val id: String = "",
        val autor: String = "",
        val aparato: String = "",
        val cuando: Long = 0,
        val editado: Long? = null,
        val texto: String = "",
        val resto: Map<String, JsonElement> = emptyMap()
    )

    data class Hilo(
        val id: String = "",
        val ancla: Ancla = Ancla(),
        val autor: String = "",
        val aparato: String = "",
        val cuando: Long = 0,
        val editado: Long? = null,
        val texto: String = "",
        val resuelto: Boolean = false,
        val resueltoPor: String? = null,
        val resueltoCuando: Long? = null,
        val respuestas: List<Respuesta> = emptyList(),
        val resto: Map<String, JsonElement> = emptyMap()
    )

    data class Fichero(
        val version: Int = VERSION,
        val comentarios: List<Hilo> = emptyList(),
        val resto: Map<String, JsonElement> = emptyMap()
    ) {
        fun hilo(id: String) = comentarios.firstOrNull { it.id == id }
        val abiertos: Int get() = comentarios.count { !it.resuelto }
    }

    /** Quién escribe: el nombre del aparato y su código. */
    data class Quien(val autor: String, val aparato: String)

    // ------------------------------------------------------------------ JSON

    private val JSON = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /**
     * Lee un fichero. Vacío es «sin comentarios»; un JSON que no se entiende es un error
     * (null), y quien lo lee **no debe escribir encima**: se perderían los comentarios.
     */
    fun leer(t: String): Fichero? {
        val s = t.trimStart('﻿')
        if (s.isBlank()) return Fichero()
        return runCatching {
            val o = Json.parseToJsonElement(s) as? JsonObject ?: return null
            Fichero(
                version = (o["version"] as? JsonPrimitive)?.intOrNull ?: VERSION,
                comentarios = (o["comentarios"] as? JsonArray)?.map { hiloDe(it.jsonObject) }.orEmpty(),
                resto = o.filterKeys { it != "version" && it != "comentarios" }
            )
        }.getOrNull()
    }

    fun escribir(f: Fichero): String = JSON.encodeToString(JsonObject.serializer(), aJson(f)) + "\n"

    private fun JsonObject.cadena(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""
    private fun JsonObject.largo(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private val CLAVES_ANCLA = setOf("cita", "antes", "despues", "pos")
    private val CLAVES_RESPUESTA = setOf("id", "autor", "aparato", "cuando", "editado", "texto")
    private val CLAVES_HILO = setOf("id", "ancla", "autor", "aparato", "cuando", "editado", "texto", "resuelto", "resueltoPor", "resueltoCuando", "respuestas")

    private fun anclaDe(o: JsonObject) = Ancla(
        o.cadena("cita"), o.cadena("antes"), o.cadena("despues"), (o.largo("pos") ?: 0).toInt(),
        o.filterKeys { it !in CLAVES_ANCLA }
    )

    private fun respuestaDe(o: JsonObject) = Respuesta(
        o.cadena("id"), o.cadena("autor"), o.cadena("aparato"), o.largo("cuando") ?: 0, o.largo("editado"), o.cadena("texto"),
        o.filterKeys { it !in CLAVES_RESPUESTA }
    )

    private fun hiloDe(o: JsonObject) = Hilo(
        id = o.cadena("id"),
        ancla = (o["ancla"] as? JsonObject)?.let(::anclaDe) ?: Ancla(),
        autor = o.cadena("autor"), aparato = o.cadena("aparato"),
        cuando = o.largo("cuando") ?: 0, editado = o.largo("editado"), texto = o.cadena("texto"),
        resuelto = (o["resuelto"] as? JsonPrimitive)?.booleanOrNull ?: false,
        resueltoPor = (o["resueltoPor"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
        resueltoCuando = o.largo("resueltoCuando"),
        respuestas = (o["respuestas"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::respuestaDe) }.orEmpty(),
        resto = o.filterKeys { it !in CLAVES_HILO }
    )

    private fun objeto(vararg pares: Pair<String, JsonElement?>, resto: Map<String, JsonElement>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        for ((k, v) in pares) if (v != null && v !is JsonNull) m[k] = v
        for ((k, v) in resto) if (k !in m) m[k] = v
        return JsonObject(m)
    }

    private fun aJson(a: Ancla) = objeto(
        "cita" to JsonPrimitive(a.cita), "antes" to JsonPrimitive(a.antes), "despues" to JsonPrimitive(a.despues),
        "pos" to JsonPrimitive(a.pos), resto = a.resto
    )

    private fun aJson(r: Respuesta) = objeto(
        "id" to JsonPrimitive(r.id), "autor" to JsonPrimitive(r.autor), "aparato" to JsonPrimitive(r.aparato),
        "cuando" to JsonPrimitive(r.cuando), "editado" to r.editado?.let { JsonPrimitive(it) }, "texto" to JsonPrimitive(r.texto),
        resto = r.resto
    )

    private fun aJson(h: Hilo) = objeto(
        "id" to JsonPrimitive(h.id), "ancla" to aJson(h.ancla), "autor" to JsonPrimitive(h.autor),
        "aparato" to JsonPrimitive(h.aparato), "cuando" to JsonPrimitive(h.cuando),
        "editado" to h.editado?.let { JsonPrimitive(it) }, "texto" to JsonPrimitive(h.texto),
        "resuelto" to JsonPrimitive(h.resuelto), "resueltoPor" to h.resueltoPor?.let { JsonPrimitive(it) },
        "resueltoCuando" to h.resueltoCuando?.let { JsonPrimitive(it) },
        "respuestas" to JsonArray(h.respuestas.map { aJson(it) }),
        resto = h.resto
    )

    private fun aJson(f: Fichero) = objeto(
        "version" to JsonPrimitive(f.version), "comentarios" to JsonArray(f.comentarios.map { aJson(it) }), resto = f.resto
    )

    // ------------------------------------------------------- crear y cambiar

    private fun limpio(t: String): String? = t.trim().takeIf { it.isNotEmpty() }?.replace("\r\n", "\n")

    /** Un id que no está en el fichero: el aparato, la hora en hexadecimal y un contador. */
    private fun idNuevo(f: Fichero, aparato: String, cuando: Long): String {
        val usados = f.comentarios.flatMap { h -> listOf(h.id) + h.respuestas.map { it.id } }.toSet()
        val a = aparato.ifEmpty { "x" }
        var n = 0
        while (true) {
            val id = "$a-${java.lang.Long.toHexString(cuando)}-$n"
            if (id !in usados) return id
            n++
        }
    }

    /** Un comentario nuevo, o null si el texto o la cita están vacíos. */
    fun nuevo(f: Fichero, ancla: Ancla, quien: Quien, cuando: Long, texto: String): Pair<Fichero, String>? {
        val t = limpio(texto) ?: return null
        if (ancla.cita.isBlank()) return null
        val id = idNuevo(f, quien.aparato, cuando)
        return f.copy(comentarios = f.comentarios + Hilo(id = id, ancla = ancla, autor = quien.autor, aparato = quien.aparato, cuando = cuando, texto = t)) to id
    }

    /** Responder a un hilo; responder a uno resuelto lo reabre, como en Google Docs. */
    fun responder(f: Fichero, hilo: String, quien: Quien, cuando: Long, texto: String): Pair<Fichero, String>? {
        val t = limpio(texto) ?: return null
        val id = idNuevo(f, quien.aparato, cuando)
        if (f.hilo(hilo) == null) return null
        return f.copy(comentarios = f.comentarios.map {
            if (it.id != hilo) it else it.copy(respuestas = it.respuestas + Respuesta(id, quien.autor, quien.aparato, cuando, texto = t), resuelto = false)
        }) to id
    }

    /** Cambia el texto de un comentario o de una respuesta. Null si no está o si el texto queda vacío. */
    fun editar(f: Fichero, id: String, texto: String, cuando: Long): Fichero? {
        val t = limpio(texto) ?: return null
        var hecho = false
        val hilos = f.comentarios.map { h ->
            when {
                h.id == id -> { hecho = true; if (h.texto != t) h.copy(texto = t, editado = cuando) else h }
                h.respuestas.any { it.id == id } -> {
                    hecho = true
                    h.copy(respuestas = h.respuestas.map { r -> if (r.id == id && r.texto != t) r.copy(texto = t, editado = cuando) else r })
                }
                else -> h
            }
        }
        return if (hecho) f.copy(comentarios = hilos) else null
    }

    /** Borra un hilo entero (su id) o una respuesta (la suya). Null si no estaba. */
    fun borrar(f: Fichero, id: String): Fichero? {
        if (f.comentarios.any { it.id == id }) return f.copy(comentarios = f.comentarios.filter { it.id != id })
        if (f.comentarios.none { h -> h.respuestas.any { it.id == id } }) return null
        return f.copy(comentarios = f.comentarios.map { h -> h.copy(respuestas = h.respuestas.filter { it.id != id }) })
    }

    fun resolver(f: Fichero, hilo: String, si: Boolean, quien: Quien, cuando: Long): Fichero? {
        if (f.hilo(hilo) == null) return null
        return f.copy(comentarios = f.comentarios.map {
            if (it.id != hilo) it else it.copy(resuelto = si, resueltoPor = if (si) quien.autor else null, resueltoCuando = cuando)
        })
    }

    /** Pone al día el ancla de cada hilo contra [texto]; devuelve dónde cae cada uno (null = sin ancla). */
    fun reanclar(f: Fichero, texto: String): Pair<Fichero, List<IntRange?>> {
        val sitios = ArrayList<IntRange?>()
        val hilos = f.comentarios.map { h ->
            val (a, r) = reanclar(texto, h.ancla)
            sitios += r
            h.copy(ancla = a)
        }
        return f.copy(comentarios = hilos) to sitios
    }

    // ---------------------------------------------------------------- ancla

    private fun alta(c: Char) = c.code in 0xD800 until 0xDC00
    private fun baja(c: Char) = c.code in 0xDC00 until 0xE000

    /** Un tramo `[a, b)` sin partir una pareja sustituta por ningún lado. */
    private fun entero(t: String, a0: Int, b0: Int): Pair<Int, Int> {
        val b = b0.coerceAtMost(t.length).let { if (it > 0 && it < t.length && baja(t[it]) && alta(t[it - 1])) it - 1 else it }
        var a = a0.coerceAtMost(b0.coerceAtMost(t.length))
        if (a > 0 && a < t.length && baja(t[a])) a++
        return minOf(a, b) to b
    }

    private fun esBlanco(c: Char) = c.isWhitespace()

    /** **El ancla de lo elegido** `[desde, hasta)`, sin los blancos de los bordes. Null si no queda nada. */
    fun anclaDe(texto: String, desde: Int, hasta: Int): Ancla? {
        var (a, b) = entero(texto, minOf(desde, hasta), maxOf(desde, hasta))
        while (a < b && esBlanco(texto[a])) a++
        while (b > a && esBlanco(texto[b - 1])) b--
        if (a >= b) return null
        val ca = entero(texto, (a - CONTEXTO).coerceAtLeast(0), a).first
        val cb = entero(texto, b, b + CONTEXTO).second
        return Ancla(texto.substring(a, b), texto.substring(ca, a), texto.substring(b, cb), a)
    }

    private fun apariciones(texto: String, aguja: String): List<Int> {
        if (aguja.isEmpty() || aguja.length > texto.length) return emptyList()
        val salida = ArrayList<Int>()
        var i = texto.indexOf(aguja)
        while (i >= 0) { salida += i; i = texto.indexOf(aguja, i + 1) }
        return salida
    }

    private fun comunDetras(a: String, b: String): Int {
        var n = 0
        while (n < a.length && n < b.length && a[a.length - 1 - n] == b[b.length - 1 - n]) n++
        return n
    }

    private fun comunDelante(a: String, b: String): Int {
        var n = 0
        while (n < a.length && n < b.length && a[n] == b[n]) n++
        return n
    }

    /**
     * **Dónde está hoy lo comentado**: la cita tal cual (con más contexto común y lo más cerca de
     * donde estaba), y si ya no está, lo que haya entre su contexto de antes y el de después.
     * Null si no se encuentra: el comentario queda sin ancla, pero no se pierde.
     */
    fun ubicar(texto: String, a: Ancla): IntRange? {
        val cita = a.cita
        if (cita.isEmpty()) return null
        val exacta = apariciones(texto, cita).maxWithOrNull(
            compareBy<Int> { i -> comunDetras(texto.substring(0, i), a.antes) + comunDelante(texto.substring(i + cita.length), a.despues) }
                .thenByDescending { i -> kotlin.math.abs(i - a.pos) }
        )
        if (exacta != null) return exacta until exacta + cita.length
        fun largos(l: Int) = listOf(l) + listOf(16, 8).filter { it < l }
        for (na in largos(a.antes.length)) for (nd in largos(a.despues.length)) {
            porContexto(texto, a.pos, cita, a.antes.substring(a.antes.length - na), a.despues.substring(0, nd))?.let { return it }
        }
        return null
    }

    private fun porContexto(texto: String, pos: Int, cita: String, antes: String, despues: String): IntRange? {
        if (antes.length + despues.length < 8) return null
        val limite = cita.length * 2 + 64
        val inicios = if (antes.isEmpty()) {
            if (pos > limite) return null
            listOf(0)
        } else apariciones(texto, antes).map { it + antes.length }
        val candidatos = ArrayList<IntRange>()
        for (inicio in inicios) {
            val fin = if (despues.isEmpty()) {
                texto.length.takeIf { it - inicio <= limite }
            } else {
                val hasta = (inicio + limite + despues.length).coerceAtMost(texto.length)
                apariciones(texto.substring(inicio, hasta), despues).firstOrNull()?.let { inicio + it }
            }
            if (fin != null && fin > inicio && texto.substring(inicio, fin).any { !esBlanco(it) }) candidatos += inicio until fin
        }
        return candidatos.minByOrNull { kotlin.math.abs(it.first - pos) }
    }

    /** Busca lo comentado y, si está, devuelve el ancla puesta al día y dónde cae. */
    fun reanclar(texto: String, a: Ancla): Pair<Ancla, IntRange?> {
        val r = ubicar(texto, a) ?: return a to null
        val x = r.first; val y = r.last + 1
        return Ancla(
            cita = texto.substring(x, y),
            antes = texto.substring(entero(texto, (x - CONTEXTO).coerceAtLeast(0), x).first, x),
            despues = texto.substring(y, entero(texto, y, y + CONTEXTO).second),
            pos = x,
            resto = a.resto
        ) to r
    }

    /** La palabra bajo [pos] (comentar sin elegir nada comenta la palabra). */
    fun palabraEn(texto: String, pos: Int): IntRange? {
        fun letra(c: Char) = alta(c) || baja(c) || c.isLetterOrDigit() || c == '_'
        val p = pos.coerceIn(0, texto.length)
        var a = p
        while (a > 0 && letra(texto[a - 1])) a--
        var b = p
        while (b < texto.length && letra(texto[b])) b++
        return if (a < b) a until b else null
    }

    // ------------------------------------------------------------- juntar

    private fun ultimo(h: Hilo): Long {
        val r = h.respuestas.maxOfOrNull { it.editado ?: it.cuando } ?: 0
        return maxOf(h.cuando, h.editado ?: 0, h.resueltoCuando ?: 0, r)
    }

    private fun <T> juntar(base: List<T>, mio: List<T>, disco: List<T>, id: (T) -> String, losDos: (T, T, T) -> T): List<T> {
        val salida = ArrayList<T>()
        for (m in mio) {
            val k = id(m)
            val b = base.firstOrNull { id(it) == k }
            val d = disco.firstOrNull { id(it) == k }
            when {
                b == null -> salida += m
                d == null -> if (b != m) salida += m
                else -> salida += if (m == b) d else if (d == b) m else losDos(b, m, d)
            }
        }
        for (d in disco) {
            val k = id(d)
            if (mio.none { id(it) == k } && base.none { id(it) == k }) salida += d
        }
        return salida
    }

    /**
     * **Lo de aquí y lo que llegó mientras tanto, juntos**, por id: lo que había al abrir ([base]),
     * lo de este aparato ([mio]) y lo que hay ahora en disco ([disco]). Lo nuevo de cada lado entra;
     * lo borrado en un lado se va si el otro no lo cambió; cambiado en los dos, gana el más reciente
     * y sus respuestas se juntan igual.
     */
    fun fusionar(base: Fichero, mio: Fichero, disco: Fichero): Fichero {
        val hilos = juntar(base.comentarios, mio.comentarios, disco.comentarios, { it.id }) { b, m, d ->
            val h = if (ultimo(d) > ultimo(m)) d else m
            h.copy(respuestas = juntar(b.respuestas, m.respuestas, d.respuestas, { it.id }) { _, mm, dd ->
                if ((dd.editado ?: dd.cuando) > (mm.editado ?: mm.cuando)) dd else mm
            })
        }
        return Fichero(version = maxOf(mio.version, disco.version), comentarios = hilos, resto = disco.resto + mio.resto)
    }
}
