package com.forge.pixpin.ocr.indra

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/**
 * **Orden de lectura y bloques de maquetación de Indra** (26-oct-2026, `IndraOrder.kt` del
 * escáner, de `order_post.py` y `layout_post.py`): rasgos de línea para `order.onnx`, el orden
 * desde los logits por pares, la agrupación de líneas en bloques (enlace medio) y el XY-cut de
 * respaldo. Cajas: `FloatArray(4)` = x0, y0, x1, y1.
 */
object OrdenIndra {
    val CLASES_DE_BLOQUE = listOf(
        "Text", "Section-Header", "Page-Header", "Page-Footer", "List-Group", "Table", "Caption", "Footnote",
        "Table-Of-Contents", "Form"
    )
    private val REJILLA = setOf(CLASES_DE_BLOQUE.indexOf("Table"), CLASES_DE_BLOQUE.indexOf("Form"), CLASES_DE_BLOQUE.indexOf("Table-Of-Contents"))
    const val N_RASGOS = 16
    private const val FIN_DE_FRASE = ".!?:;。！？：；．"

    fun mediana(v: FloatArray): Float {
        if (v.isEmpty()) return 0f
        val s = v.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
    }

    private fun ancha(cp: Int): Boolean =
        cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
            cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x20000..0x3FFFD

    private fun largoVisible(t: String): Int {
        var n = 0
        var i = 0
        while (i < t.length) { val cp = t.codePointAt(i); n += if (ancha(cp)) 2 else 1; i += Character.charCount(cp) }
        return n
    }

    private fun soloCifras(s: String) = s.isNotEmpty() && s.all { Character.isDigit(it) }

    /** `[N, 16]` rasgos (`line_features`). */
    fun rasgos(cajas: List<FloatArray>, anchoPag: Float, altoPag: Float, textos: List<String>?): FloatArray {
        val n = cajas.size
        val out = FloatArray(n * N_RASGOS)
        if (n == 0) return out
        val hs = FloatArray(n) { max(cajas[it][3] - cajas[it][1], 1e-3f) }
        val medH = mediana(hs)
        for (i in 0 until n) {
            val b = cajas[i]
            val w = max(b[2] - b[0], 1e-3f)
            val h = hs[i]
            val o = i * N_RASGOS
            out[o] = b[0] / anchoPag; out[o + 1] = b[1] / altoPag; out[o + 2] = b[2] / anchoPag; out[o + 3] = b[3] / altoPag
            out[o + 4] = (b[0] + b[2]) / (2 * anchoPag); out[o + 5] = (b[1] + b[3]) / (2 * altoPag)
            out[o + 6] = w / anchoPag
            out[o + 7] = ln(h / medH)
            out[o + 8] = ln(w / h) / 4f
            out[o + 9] = medH / altoPag * 20f
            val t = textos?.get(i)?.trim() ?: continue
            if (t.isEmpty()) continue
            out[o + 10] = (ln1p(largoVisible(t).toDouble()) / 5.0).toFloat()
            out[o + 11] = t.count { Character.isDigit(it) }.toFloat() / t.length
            val cabeza = t.take(3).trimEnd('.', ')')
            out[o + 12] = if (t[0] in "•-–▪◦*·→" || (soloCifras(cabeza) && t.length > 3)) 1f else 0f
            out[o + 13] = if (t.endsWith("-")) 1f else 0f
            out[o + 14] = if (t.last() in FIN_DE_FRASE) 1f else 0f
            out[o + 15] = if (Character.isUpperCase(t[0])) 1f else 0f
        }
        return out
    }

    /** Orden desde los logits por pares `[n, n]` (a[i][j] > 0: i antes que j) — `decode_order`. */
    fun decodificarOrden(a: Array<DoubleArray>, barridos: Int = 4): IntArray {
        val n = a.size
        if (n <= 1) return IntArray(n) { it }
        val lp = Array(n) { i -> DoubleArray(n) { j -> -softplus(-a[i][j]) } }
        val clave = DoubleArray(n) { i -> var r = 0.0; var c = 0.0; for (j in 0 until n) { r += lp[i][j]; c += lp[j][i] }; -r + c }
        var orden = (0 until n).sortedWith(compareBy<Int> { clave[it] }.thenBy { it }).toIntArray()
        repeat(barridos) {
            var mejora = false
            for (x in orden.copyOf()) {
                val resto = orden.filter { it != x }.toIntArray()
                val r = resto.size
                val pre = DoubleArray(r + 1)
                var cum = 0.0
                for (k in 0 until r) { pre[k] = cum; cum += lp[resto[k]][x] }
                pre[r] = cum
                val suf = DoubleArray(r + 1)
                var s = 0.0
                for (k in r - 1 downTo 0) { s += lp[x][resto[k]]; suf[k] = s }
                val ganancias = DoubleArray(r + 1)
                var mejorK = 0
                for (k in 0..r) { ganancias[k] = pre[k] + suf[k]; if (ganancias[k] > ganancias[mejorK]) mejorK = k }
                val actual = orden.indexOf(x)
                if (ganancias[mejorK] > ganancias[actual] + 1e-6) {
                    orden = IntArray(r + 1).also { o ->
                        var p = 0
                        for (k in 0..r) { if (k == mejorK) o[p++] = x; if (k < r) o[p++] = resto[k] }
                    }
                    mejora = true
                }
            }
            if (!mejora) return orden
        }
        return orden
    }

    private fun softplus(x: Double): Double = if (x > 0) x + ln1p(exp(-x)) else ln1p(exp(x))

    /** Filas de arriba abajo, de izquierda a derecha (`order_top_down`). */
    fun ordenDeArribaAbajo(cajas: List<FloatArray>): List<Int> {
        if (cajas.isEmpty()) return emptyList()
        val h = mediana(FloatArray(cajas.size) { cajas[it][3] - cajas[it][1] })
        val q = max(h * 0.5f, 1e-3f).toDouble()
        return cajas.indices.sortedWith(compareBy<Int>({ Math.rint(((cajas[it][1] + cajas[it][3]) / 2) / q) }, { cajas[it][0] }))
    }

    private class Corte(val hueco: Float, val k: Int, val o: List<Int>)

    private fun mejorCorte(b: List<FloatArray>, eje: Int): Corte {
        val lo = if (eje == 1) 1 else 0
        val hi = if (eje == 1) 3 else 2
        val o = b.indices.sortedWith(compareBy<Int> { b[it][lo] }.thenBy { it })
        var fin = b[o[0]][hi]
        var mejorHueco = 0f
        var mejorK = -1
        for (k in 1 until o.size) {
            val hueco = b[o[k]][lo] - fin
            if (hueco > mejorHueco) { mejorHueco = hueco; mejorK = k }
            fin = max(fin, b[o[k]][hi])
        }
        return Corte(mejorHueco, mejorK, o)
    }

    /** XY-cut recursivo (`order_xycut`). */
    fun ordenXy(cajas: List<FloatArray>): List<Int> {
        fun rec(ids: List<Int>): List<Int> {
            if (ids.size <= 1) return ids
            val b = ids.map { cajas[it] }
            val cy = mejorCorte(b, 1)
            val cx = mejorCorte(b, 0)
            if (cy.k < 0 && cx.k < 0) return ordenDeArribaAbajo(b).map { ids[it] }
            val c = if (cy.hueco >= cx.hueco) cy else cx
            return rec(c.o.subList(0, c.k).map { ids[it] }) + rec(c.o.subList(c.k, c.o.size).map { ids[it] })
        }
        return rec(cajas.indices.toList())
    }

    /** Agrupación por enlace medio de los logits «mismo grupo» (`_cluster`). */
    fun agrupar(logits: Array<FloatArray>): List<List<Int>> {
        val n = logits.size
        if (n == 0) return emptyList()
        val s = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else logits[i][j].toDouble() } }
        val tam = DoubleArray(n) { 1.0 }
        val vivo = BooleanArray(n) { true }
        val miembros = Array(n) { mutableListOf(it) }
        val m = Array(n) { i -> DoubleArray(n) { j -> if (i == j) Double.NEGATIVE_INFINITY else s[i][j] } }
        val mejor = IntArray(n) { i -> argmax(m[i]) }
        val mejorV = DoubleArray(n) { m[it][mejor[it]] }
        for (iter in 0 until n - 1) {
            var a = argmax(mejorV)
            var b = mejor[a]
            if (mejorV[a] <= 0) break
            val lo = min(a, b); val hi = max(a, b); a = lo; b = hi
            for (j in 0 until n) s[a][j] += s[b][j]
            for (j in 0 until n) s[j][a] += s[j][b]
            s[a][a] = 0.0
            tam[a] += tam[b]
            vivo[b] = false
            miembros[a].addAll(miembros[b])
            val fila = DoubleArray(n) { j -> if (!vivo[j] || j == a) Double.NEGATIVE_INFINITY else s[a][j] / (tam[a] * tam[j]) }
            for (j in 0 until n) { m[a][j] = fila[j]; m[j][a] = fila[j] }
            for (j in 0 until n) { m[b][j] = Double.NEGATIVE_INFINITY; m[j][b] = Double.NEGATIVE_INFINITY }
            mejorV[b] = Double.NEGATIVE_INFINITY
            mejor[a] = argmax(fila)
            mejorV[a] = fila[mejor[a]]
            for (j in 0 until n) {
                if (!vivo[j] || j == a) continue
                if (mejor[j] == a || mejor[j] == b) { mejor[j] = argmax(m[j]); mejorV[j] = m[j][mejor[j]] }
            }
            for (j in 0 until n) {
                if (!vivo[j] || j == a) continue
                if (fila[j] > mejorV[j]) { mejor[j] = a; mejorV[j] = fila[j] }
            }
        }
        return (0 until n).filter { vivo[it] }.map { miembros[it].sorted() }
    }

    private fun argmax(v: DoubleArray): Int {
        var k = 0
        for (i in 1 until v.size) if (v[i] > v[k]) k = i
        return k
    }

    /** Orden con las cabezas de maquetación: bloques y luego líneas dentro (`decode_blocks_order`). */
    fun decodificarOrdenPorBloques(a: Array<FloatArray>, mismo: Array<FloatArray>, cajas: List<FloatArray>, cls: Array<FloatArray>?): IntArray {
        val n = a.size
        if (n <= 1) return IntArray(n) { it }
        val bloques = agrupar(mismo)
        val dentro = bloques.map { b ->
            val bx = b.map { cajas[it] }
            val local = if (cls != null && claseDeBloque(cls, b) in REJILLA) ordenDeArribaAbajo(bx) else ordenXy(bx)
            local.map { b[it] }
        }
        val m = bloques.size
        val ab = Array(m) { DoubleArray(m) }
        for (p in 0 until m) for (q in p + 1 until m) {
            var s = 0.0
            for (i in bloques[p]) for (j in bloques[q]) s += a[i][j]
            val v = s / (bloques[p].size.toDouble() * bloques[q].size)
            ab[p][q] = v.toFloat().toDouble(); ab[q][p] = -v.toFloat().toDouble()
        }
        return decodificarOrden(ab).flatMap { dentro[it] }.toIntArray()
    }

    /** Clase de un bloque: argmax de la suma de logits de sus líneas. */
    fun claseDeBloque(cls: Array<FloatArray>, bloque: List<Int>): Int {
        val c = cls[0].size
        val suma = DoubleArray(c)
        for (i in bloque) for (k in 0 until c) suma[k] += cls[i][k]
        return argmax(suma)
    }

    /** Tramos del orden de lectura con logit «mismo bloque» positivo entre vecinos (`split_blocks`). */
    fun partirBloques(orden: IntArray, mismo: Array<FloatArray>): List<List<Int>> {
        val out = ArrayList<MutableList<Int>>()
        for (i in orden) {
            val ultimo = out.lastOrNull()
            if (ultimo != null && mismo[ultimo.last()][i] > 0) ultimo += i else out += mutableListOf(i)
        }
        return out
    }

    /** Un bloque en orden de lectura: etiqueta, caja unión de sus líneas e índices de línea. */
    class Bloque(val etiqueta: String, val caja: FloatArray, val lineas: List<Int>)

    fun cajaDe(cajas: List<FloatArray>, ids: List<Int>): FloatArray {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in ids) { val b = cajas[i]; x0 = min(x0, b[0]); y0 = min(y0, b[1]); x1 = max(x1, b[2]); y1 = max(y1, b[3]) }
        return floatArrayOf(x0, y0, x1, y1)
    }

    /** Bloques en orden de lectura (`page_blocks`). */
    fun bloquesDePagina(cajas: List<FloatArray>, orden: IntArray, cls: Array<FloatArray>, mismo: Array<FloatArray>): List<Bloque> =
        partirBloques(orden, mismo).map { blk -> Bloque(CLASES_DE_BLOQUE[claseDeBloque(cls, blk)], cajaDe(cajas, blk), blk) }

    /** Bloques por XY-cut (sin modelo de orden): huecos de [huecoMin] alturas medianas o más (`xycut_blocks`). */
    fun bloquesXy(cajas: List<FloatArray>, huecoMin: Float = 1f): List<List<Int>> {
        if (cajas.isEmpty()) return emptyList()
        val h = mediana(FloatArray(cajas.size) { cajas[it][3] - cajas[it][1] })
        fun rec(ids: List<Int>): List<List<Int>> {
            val b = ids.map { cajas[it] }
            if (ids.size > 1) {
                val cy = mejorCorte(b, 1)
                val cx = mejorCorte(b, 0)
                val c = if (cy.hueco >= cx.hueco) cy else cx
                if (c.k >= 0 && c.hueco >= huecoMin * h) return rec(c.o.subList(0, c.k).map { ids[it] }) + rec(c.o.subList(c.k, c.o.size).map { ids[it] })
            }
            return listOf(ordenXy(b).map { ids[it] })
        }
        return rec(cajas.indices.toList())
    }
}
