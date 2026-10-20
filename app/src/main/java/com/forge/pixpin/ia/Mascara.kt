package com.forge.pixpin.ia

import com.forge.pixpin.ia.Trazado.P
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **Las máscaras de un dibujo** (PC `pixpin-ia/src/diagrama/mascara.rs`, 15-oct-2026): el papel, la
 * tinta, distancias, componentes, el esqueleto de los trazos y sus cadenas de píxeles. Todo a la
 * resolución del recorte (sin reducir: una raya de un píxel tiene que seguir siendo una raya). Con
 * `Float` donde el PC usa `f32`, para dar lo mismo.
 */
object Mascara {
    /** La imagen en RGB sobre blanco (lo transparente es papel); `px[i]` = r, g, b. */
    class Img(val w: Int, val h: Int, val px: Array<FloatArray>) {
        companion object {
            fun deRgba(rgba: ByteArray, w: Int, h: Int, desde: Int = 0, paso: Int = w): Img {
                val px = Array(w * h) { floatArrayOf(255f, 255f, 255f) }
                for (y in 0 until h) for (x in 0 until w) {
                    val o = (desde + y * paso + x) * 4
                    if (o + 4 > rgba.size) continue
                    val a = (rgba[o + 3].toInt() and 255).toFloat() / 255f
                    val p = px[y * w + x]
                    for (c in 0..2) p[c] = (rgba[o + c].toInt() and 255).toFloat() * a + 255f * (1f - a)
                }
                return Img(w, h, px)
            }
        }
    }

    fun d2(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return x * x + y * y + z * z
    }

    fun distColor(a: FloatArray, b: FloatArray): Float = sqrt(d2(a, b))

    private fun u8(f: Float): Int = if (f.isNaN()) 0 else f.toInt().coerceIn(0, 255)

    /** **El color del papel**: el más común (cuantizado) del borde del recorte. */
    fun fondo(img: Img): FloatArray {
        val cuenta = LinkedHashMap<Int, Pair<IntArray, DoubleArray>>()
        fun ver(p: FloatArray) {
            val k = ((u8(p[0]) shr 4) shl 8) or ((u8(p[1]) shr 4) shl 4) or (u8(p[2]) shr 4)
            val e = cuenta.getOrPut(k) { IntArray(1) to DoubleArray(3) }
            e.first[0]++
            for (c in 0..2) e.second[c] += p[c].toDouble()
        }
        val w = img.w; val h = img.h
        if (w == 0 || h == 0) return floatArrayOf(255f, 255f, 255f)
        for (x in 0 until w) { ver(img.px[x]); ver(img.px[(h - 1) * w + x]) }
        for (y in 0 until h) { ver(img.px[y * w]); ver(img.px[y * w + w - 1]) }
        // Con poco borde (o borde con tinta), también el interior cuenta.
        if ((cuenta.values.maxOfOrNull { it.first[0] } ?: 0) < (w + h) / 2) {
            var i = 0
            while (i < w * h) { ver(img.px[i]); i += 7 }
        }
        val e = cuenta.values.maxByOrNull { it.first[0] } ?: return floatArrayOf(255f, 255f, 255f)
        val n = e.first[0].toDouble()
        return floatArrayOf((e.second[0] / n).toFloat(), (e.second[1] / n).toFloat(), (e.second[2] / n).toFloat())
    }

    fun media(v: Sequence<FloatArray>): FloatArray {
        val s = DoubleArray(3); var n = 0
        for (c in v) { for (q in 0..2) s[q] += c[q].toDouble(); n++ }
        if (n == 0) return FloatArray(3)
        return floatArrayOf((s[0] / n).toFloat(), (s[1] / n).toFloat(), (s[2] / n).toFloat())
    }

    /** **Distancia de cada píxel de la máscara a lo de fuera** (chamfer 3-4, en píxeles). Fuera, 0. */
    fun distancia(m: BooleanArray, w: Int, h: Int): FloatArray {
        val inf = 1e6f
        val d = FloatArray(w * h) { if (m[it]) inf else 0f }
        fun en(x: Int, y: Int): Float = if (x < 0 || y < 0 || x >= w || y >= h) 0f else d[y * w + x]
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0f) continue
            d[i] = min(min(min(min(d[i], en(x - 1, y) + 3f), en(x, y - 1) + 3f), en(x - 1, y - 1) + 4f), en(x + 1, y - 1) + 4f)
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (d[i] == 0f) continue
            d[i] = min(min(min(min(d[i], en(x + 1, y) + 3f), en(x, y + 1) + 3f), en(x + 1, y + 1) + 4f), en(x - 1, y + 1) + 4f)
        }
        for (i in d.indices) d[i] = d[i] / 3f
        return d
    }

    /** Lo que está a menos de [r] de la máscara (dilatación redonda). */
    fun dilatar(m: BooleanArray, w: Int, h: Int, r: Float): BooleanArray {
        val fuera = BooleanArray(m.size) { !m[it] }
        val d = distancia(fuera, w, h)
        return BooleanArray(m.size) { d[it] <= r }
    }

    /** Lo que está a más de [r] del borde (erosión redonda). */
    fun erosionar(m: BooleanArray, w: Int, h: Int, r: Float): BooleanArray {
        val d = distancia(m, w, h)
        return BooleanArray(m.size) { d[it] > r }
    }

    /** Componentes conexas (8 vecinos): los índices de sus píxeles. */
    fun componentes(m: BooleanArray, w: Int, h: Int): List<IntArray> {
        val visto = BooleanArray(w * h)
        val salida = ArrayList<IntArray>()
        val pila = IntArray(w * h)
        for (i in 0 until w * h) {
            if (!m[i] || visto[i]) continue
            val c = ArrayList<Int>()
            var n = 0
            visto[i] = true
            pila[n++] = i
            while (n > 0) {
                val j = pila[--n]
                c += j
                val x = j % w; val y = j / w
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val k = yy * w + xx
                    if (m[k] && !visto[k]) { visto[k] = true; pila[n++] = k }
                }
            }
            salida += c.toIntArray()
        }
        return salida
    }

    /** La caja de unos píxeles: (x0, y0, x1, y1) inclusiva. */
    fun cajaDe(px: IntArray, w: Int): IntArray {
        val c = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, 0, 0)
        for (i in px) {
            val x = i % w; val y = i / w
            c[0] = min(c[0], x); c[1] = min(c[1], y); c[2] = maxOf(c[2], x); c[3] = maxOf(c[3], y)
        }
        return c
    }

    /** **Adelgazamiento de Zhang-Suen**: el esqueleto de un píxel de ancho. */
    fun esqueleto(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val s = m.copyOf()
        for (x in 0 until w) { s[x] = false; s[(h - 1) * w + x] = false }
        for (y in 0 until h) { s[y * w] = false; s[y * w + w - 1] = false }
        var cambio = true
        val quitar = ArrayList<Int>()
        val v = BooleanArray(8)
        while (cambio) {
            cambio = false
            for (paso in 0..1) {
                quitar.clear()
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val i = y * w + x
                    if (!s[i]) continue
                    // p2..p9: N, NE, E, SE, S, SO, O, NO.
                    v[0] = s[i - w]; v[1] = s[i - w + 1]; v[2] = s[i + 1]; v[3] = s[i + w + 1]
                    v[4] = s[i + w]; v[5] = s[i + w - 1]; v[6] = s[i - 1]; v[7] = s[i - w - 1]
                    val b = v.count { it }
                    if (b !in 2..6) continue
                    val a = (0 until 8).count { !v[it] && v[(it + 1) % 8] }
                    if (a != 1) continue
                    val p2 = v[0]; val p4 = v[2]; val p6 = v[4]; val p8 = v[6]
                    val ok = if (paso == 0) !(p2 && p4 && p6) && !(p4 && p6 && p8) else !(p2 && p4 && p8) && !(p2 && p6 && p8)
                    if (ok) quitar += i
                }
                if (quitar.isNotEmpty()) { cambio = true; for (i in quitar) s[i] = false }
            }
        }
        // Las escaleras: un píxel con dos vecinos en L que se tocan entre sí sobra.
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!s[i]) continue
            val n = s[i - w]; val e = s[i + 1]; val so = s[i + w]; val o = s[i - 1]
            val esquina = (n && e && !s[i - w + 1]) || (e && so && !s[i + w + 1]) || (so && o && !s[i + w - 1]) || (o && n && !s[i - w - 1])
            if (esquina) {
                val vecinos = listOf(s[i - w], s[i - w + 1], s[i + 1], s[i + w + 1], s[i + w], s[i + w - 1], s[i - 1], s[i - w - 1]).count { it }
                if (vecinos == 2) s[i] = false
            }
        }
        return s
    }

    private val VECINOS = arrayOf(0 to -1, 1 to 0, 0 to 1, -1 to 0, 1 to -1, 1 to 1, -1 to 1, -1 to -1)

    private fun vecinosDe(s: BooleanArray, w: Int, h: Int, i: Int): List<Int> {
        val x = i % w; val y = i / w
        val v = ArrayList<Int>(8)
        for ((dx, dy) in VECINOS) {
            val xx = x + dx; val yy = y + dy
            if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
            val k = yy * w + xx
            if (s[k]) v += k
        }
        return v
    }

    /** Cuántas ramas salen de un píxel del esqueleto (el «crossing number»). */
    private fun ramas(s: BooleanArray, w: Int, h: Int, i: Int): Int {
        val x = i % w; val y = i / w
        val anillo = arrayOf(0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1)
        fun en(k: Int): Boolean {
            val xx = x + anillo[k].first; val yy = y + anillo[k].second
            return xx >= 0 && yy >= 0 && xx < w && yy < h && s[yy * w + xx]
        }
        return (0 until 8).count { !en(it) && en((it + 1) % 8) }
    }

    /**
     * **Las cadenas del esqueleto**: de cruce (o punta) a cruce (o punta), y los anillos sueltos, en
     * centros de píxel. Cada cadena que llega a un cruce lo incluye como punta.
     */
    fun cadenas(s: BooleanArray, w: Int, h: Int): List<List<P>> {
        val nVec = IntArray(w * h) { if (s[it]) vecinosDe(s, w, h, it).size else 0 }
        val cruces = IntArray(w * h) { if (s[it]) ramas(s, w, h, it) else 0 }
        fun esNodo(i: Int) = nVec[i] != 2 && (cruces[i] != 2 || nVec[i] < 2)
        val visto = BooleanArray(w * h)
        fun centro(i: Int) = P((i % w) + 0.5, (i / w) + 0.5)
        val salida = ArrayList<List<P>>()
        fun recorrer(a: Int, b: Int): List<P>? {
            if (visto[b] && !esNodo(b)) return null
            val camino = arrayListOf(centro(a))
            var prev = a; var cur = b
            while (true) {
                camino += centro(cur)
                if (esNodo(cur)) break
                visto[cur] = true
                val c = cur; val pr = prev
                val sig = vecinosDe(s, w, h, c)
                    .filter { k -> k != pr && (esNodo(k) || !visto[k]) }
                    .filter { k -> k != a || camino.size > 3 }
                    .minByOrNull { k -> abs(k % w - c % w) + abs(k / w - c / w) }
                    ?: break
                prev = cur; cur = sig
            }
            return camino
        }
        val pares = HashSet<Long>()
        for (i in 0 until w * h) {
            if (!s[i] || !esNodo(i) || nVec[i] == 0) continue
            for (b in vecinosDe(s, w, h, i)) {
                if (esNodo(b)) {
                    if (!pares.add(min(i, b).toLong() shl 32 or maxOf(i, b).toLong())) continue
                    salida += listOf(centro(i), centro(b))
                    continue
                }
                if (visto[b]) continue
                recorrer(i, b)?.let { salida += it }
            }
        }
        // Anillos sin nodos.
        for (i in 0 until w * h) {
            if (s[i] && !visto[i] && !esNodo(i)) {
                visto[i] = true
                val b = vecinosDe(s, w, h, i).firstOrNull() ?: continue
                val c = recorrer(i, b) ?: continue
                salida += c + c.first()
            }
        }
        return salida
    }
}
