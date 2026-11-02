package com.forge.pixpin.motormd

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * **Anclar una marca de texto por su cita y volver a encontrarla** (2-nov-2026): puerto de
 * `herramientas/lector-md/src/marcas-texto.mjs` del PC (el que va dentro de `marcas-pagina.js`),
 * con las mismas cuentas y en el mismo orden (§3.10 de `sincronizar-marcos-vistas-y-tinta.md`):
 *
 * 1. la cita tal cual en su `start` con todo su contexto;
 * 2. la aparición con más contexto igual y, si empatan, la más cercana a `start`;
 * 3. lo que haya entre su `prefix` y su `suffix` (también con solo 16 u 8 letras);
 * 4. la **parecida**: el trozo con menos cambios (uno por cada cinco letras como mucho).
 *
 * Si nada, la marca queda sin sitio: no se pinta ni se borra. Posiciones en UTF-16.
 */
object AnclaDeTexto {
    const val CONTEXTO = 32
    /** El grupo de las marcas hechas tinta (PDF, Word, libros, hojas): `marca-texto-<ms>`. */
    const val GRUPO_TINTA = "marca-texto"
    val COLORES = listOf("#F47286", "#B99CF5", "#5BA4F5", "#3FC88C", "#F5C218")

    private fun alta(c: Char) = c.code in 0xD800..0xDBFF
    private fun baja(c: Char) = c.code in 0xDC00..0xDFFF

    /** El trozo `[t, r)` sin partir un par sustituto (el `P` del PC). */
    internal fun sinPartir(e: String, t0: Int, r0: Int): Pair<Int, Int> {
        var t = t0.coerceIn(0, e.length)
        var r = max(t, min(r0, e.length))
        if (t > 0 && t < e.length && baja(e[t]) && alta(e[t - 1])) t--
        if (r > 0 && r < e.length && baja(e[r]) && alta(e[r - 1])) r++
        return t to r
    }

    data class Cita(val exact: String, val prefix: String, val suffix: String, val start: Int, val end: Int)

    /** La cita de lo elegido, sin blancos en los bordes; null si no queda nada. */
    fun citar(e: String, a: Int, b: Int): Cita? {
        var (n, c) = sinPartir(e, min(a, b), max(a, b))
        while (n < c && e[n].isWhitespace()) n++
        while (c > n && e[c - 1].isWhitespace()) c--
        if (n >= c) return null
        val (i, _) = sinPartir(e, n - CONTEXTO, n)
        val (_, f) = sinPartir(e, c, c + CONTEXTO)
        return Cita(e.substring(n, c), e.substring(i, n), e.substring(c, f), n, c)
    }

    private fun todas(e: String, t: String, tope: Int = 2000): List<Int> {
        val salida = ArrayList<Int>()
        if (t.isEmpty()) return salida
        var c = e.indexOf(t)
        while (c >= 0 && salida.size < tope) { salida += c; c = e.indexOf(t, c + 1) }
        return salida
    }

    private fun finComun(e: String, t: String): Int {
        var r = 0
        while (r < e.length && r < t.length && e[e.length - 1 - r] == t[t.length - 1 - r]) r++
        return r
    }

    private fun principioComun(e: String, t: String): Int {
        var r = 0
        while (r < e.length && r < t.length && e[r] == t[r]) r++
        return r
    }

    private fun trozo(e: String, a: Int, b: Int): String {
        val x = a.coerceIn(0, e.length)
        return e.substring(x, b.coerceIn(x, e.length))
    }

    /** Cuánto del contexto coincide alrededor de `[t, r)`. */
    private fun contexto(e: String, t: Int, r: Int, n: String, c: String): Int =
        finComun(trozo(e, max(0, t - n.length), t), n) + principioComun(trozo(e, r, r + c.length), c)

    private fun entre(e: String, t: Int, r: Int, n: String, c: String): Pair<Int, Int>? {
        if (n.length + c.length < 8) return null
        val i = r * 2 + 64
        val f = if (n.isNotEmpty()) todas(e, n).map { it + n.length } else if (t > i) emptyList() else listOf(0)
        var d: Pair<Int, Int>? = null
        for (l in f) {
            val p = if (c.isEmpty()) {
                if (e.length - l <= i) e.length else -1
            } else {
                val m = trozo(e, l, l + i + c.length).indexOf(c)
                if (m < 0) -1 else l + m
            }
            if (p > l && trozo(e, l, p).any { !it.isWhitespace() } && (d == null || abs(l - t) < abs(d.first - t))) d = l to p
        }
        return d
    }

    /** La distancia de [e] al mejor trozo de [t] que empieza al principio: (cambios, dónde acaba). */
    private fun parecido(e: String, t: String): Pair<Int, Int> {
        val r = e.length
        var n = IntArray(r + 1) { it }
        var c = n[r] to 0
        for (i in 1..t.length) {
            val f = IntArray(r + 1)
            f[0] = 0
            for (d in 1..r) {
                val l = if (e[d - 1] == t[i - 1]) 0 else 1
                f[d] = minOf(n[d - 1] + l, n[d] + 1, f[d - 1] + 1)
            }
            if (f[r] < c.first) c = f[r] to i
            n = f
        }
        return c
    }

    private fun parecida(e: String, t: String, r: Int, n: String, c: String): Pair<Int, Int>? {
        val i = t.length
        if (i < 5 || i > 2000) return null
        val f = max(1, i / 5)
        val d = max(3, i / (f + 1))
        val vistos = HashSet<Int>()
        data class Mejor(val a: Int, val b: Int, val d: Int, val ctx: Int, val lejos: Int)
        var p: Mejor? = null
        var m = 0
        while (m + d <= i) {
            for (a0 in todas(e, t.substring(m, m + d), 200)) {
                val mm = a0 - m
                val clave = Math.floorDiv(mm, 4)
                if (!vistos.add(clave)) continue
                val tt = max(0, mm - f)
                val nn = trozo(e, tt, min(e.length, mm + i + f))
                val (l, cc) = parecido(t, nn)
                if (l > f) continue
                val k = nn.substring(0, cc)
                val (_, atras) = parecido(t.reversed(), k.reversed())
                val (w, v) = sinPartir(e, tt + k.length - atras, tt + cc)
                if (w >= v) continue
                val j = contexto(e, w, v, n, c)
                val nuevo = Mejor(w, v, l, j, abs(w - r))
                val q = p
                if (q == null || nuevo.d < q.d || (nuevo.d == q.d && (nuevo.ctx > q.ctx || (nuevo.ctx == q.ctx && nuevo.lejos < q.lejos)))) p = nuevo
            }
            m += d
        }
        return p?.let { it.a to it.b }
    }

    /** **Dónde está ahora** la cita de la marca [m] en [e]; null si no se encuentra. */
    fun ubicar(e: String, m: JsonObject): Pair<Int, Int>? {
        val cita = m["cita"] as? JsonObject ?: return null
        fun s(k: String) = (cita[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val r = s("exact")
        if (r.isEmpty()) return null
        val n = s("prefix")
        val c = s("suffix")
        val i = ((m["posicion"] as? JsonObject)?.get("start") as? JsonPrimitive)?.intOrNull ?: 0
        if (i >= 0 && e.startsWith(r, i) && contexto(e, i, i + r.length, n, c) == n.length + c.length) return i to i + r.length
        var mejor: Pair<Int, Int>? = null
        for (l in todas(e, r)) {
            val p = contexto(e, l, l + r.length, n, c)
            val q = mejor
            if (q == null || p > q.second || (p == q.second && abs(l - i) < abs(q.first - i))) mejor = l to p
        }
        mejor?.let { return it.first to it.first + r.length }
        fun largos(l: Int) = listOf(l) + listOf(16, 8).filter { it < l }
        for (l in largos(n.length)) for (p in largos(c.length)) {
            entre(e, i, r.length, n.substring(n.length - l), c.substring(0, p))?.let { return it }
        }
        return parecida(e, r, i, n, c)
    }

    /** Un id libre `marca-texto-<ms>`. */
    fun idLibre(ms: Long, marcas: List<JsonObject>): String {
        val ids = marcas.map(MarcasDeTexto::id).toHashSet()
        var n = ms
        while ("${MarcasDeTexto.GRUPO}-$n" in ids) n++
        return "${MarcasDeTexto.GRUPO}-$n"
    }

    /** **Una marca nueva** sobre `[a, b)` de [e]; null si no hay nada que citar o la marca no existe. */
    fun nueva(e: String, a: Int, b: Int, marca: String, color: Int, ms: Long, marcas: List<JsonObject>): JsonObject? {
        val d = citar(e, a, b) ?: return null
        if (marca !in MarcasDeTexto.MARCAS) return null
        return buildJsonObject {
            put("id", idLibre(ms, marcas))
            put("marca", marca)
            put("color", COLORES.getOrElse(color) { COLORES[4] })
            put("cuando", ms)
            putJsonObject("cita") { put("type", "TextQuoteSelector"); put("exact", d.exact); put("prefix", d.prefix); put("suffix", d.suffix) }
            putJsonObject("posicion") { put("type", "TextPositionSelector"); put("start", d.start); put("end", d.end) }
        }
    }

    /** La marca con su cita al día con lo que hay ahora en `[a, b)` (sus demás campos, tal cual). */
    fun conCita(e: String, m: JsonObject, a: Int, b: Int): JsonObject? {
        val d = citar(e, a, b) ?: return null
        val cita = (m["cita"] as? JsonObject).orEmpty() + mapOf(
            "type" to JsonPrimitive("TextQuoteSelector"), "exact" to JsonPrimitive(d.exact),
            "prefix" to JsonPrimitive(d.prefix), "suffix" to JsonPrimitive(d.suffix)
        )
        val pos = (m["posicion"] as? JsonObject).orEmpty() + mapOf(
            "type" to JsonPrimitive("TextPositionSelector"), "start" to JsonPrimitive(d.start), "end" to JsonPrimitive(d.end)
        )
        return JsonObject(m + mapOf("cita" to JsonObject(cita), "posicion" to JsonObject(pos)))
    }

    /** `reanclar` del PC: dónde está ahora y la marca con el ancla al día; null si ya no está. */
    fun reanclar(e: String, m: JsonObject): Pair<Pair<Int, Int>, JsonObject>? {
        val r = ubicar(e, m) ?: return null
        val n = conCita(e, m, r.first, r.second) ?: return null
        val p = n["posicion"] as JsonObject
        return ((p["start"] as JsonPrimitive).intOrNull!! to (p["end"] as JsonPrimitive).intOrNull!!) to n
    }

    /**
     * **Las anclas al día** tras escribir en la nota (`actualizar` de `marcas-nota.js`): la lista
     * nueva si alguna cita cambió (hay que guardarla); null si no.
     */
    fun alDia(e: String, marcas: List<JsonObject>): List<JsonObject>? {
        var cambio = false
        val nuevas = marcas.map { m ->
            val r = reanclar(e, m) ?: return@map m
            val antes = ((m["cita"] as? JsonObject)?.get("exact") as? JsonPrimitive)?.contentOrNull
            val ahora = ((r.second["cita"] as JsonObject)["exact"] as JsonPrimitive).contentOrNull
            if (antes != ahora) cambio = true
            r.second
        }
        return if (cambio) nuevas else null
    }

    /** Las marcas que toca `[a, b)` (o que tienen dentro el punto `a`, si `a == b`). */
    fun tocadas(marcas: List<JsonObject>, sitios: Map<String, Pair<Int, Int>>, a: Int, b: Int): List<JsonObject> =
        marcas.filter { m ->
            val s = sitios[MarcasDeTexto.id(m)] ?: return@filter false
            if (a == b) s.first <= a && a < s.second else s.first < b && a < s.second
        }
}
