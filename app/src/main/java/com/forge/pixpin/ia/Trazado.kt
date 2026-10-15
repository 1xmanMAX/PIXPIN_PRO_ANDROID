package com.forge.pixpin.ia

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **Del borde de una mancha a curvas lisas** (`calcar/trazado.rs` del PC, portado tal cual: «calcar
 * sin escaleras», 14-oct): contorno sub-pixel con *marching squares* interpolado, esquinas por el
 * giro a dos escalas, y Bézier cúbicas de Schneider aplanadas con una tolerancia de pantalla.
 */
object Trazado {
    data class P(val x: Double, val y: Double)

    private fun resta(a: P, b: P) = P(a.x - b.x, a.y - b.y)
    private fun suma(a: P, b: P) = P(a.x + b.x, a.y + b.y)
    private fun por(a: P, k: Double) = P(a.x * k, a.y * k)
    private fun punto(a: P, b: P) = a.x * b.x + a.y * b.y
    private fun largo(a: P) = sqrt(punto(a, a))
    fun dist(a: P, b: P) = largo(resta(a, b))
    private fun unitario(a: P): P { val l = largo(a); return if (l > 1e-12) por(a, 1.0 / l) else P(0.0, 0.0) }
    private val CERO = P(0.0, 0.0)

    /** Quita los seguidos casi iguales (`dedup_by` de Rust). */
    private fun sinRepetidos(v: List<P>): MutableList<P> {
        val out = ArrayList<P>(v.size)
        for (q in v) if (out.isEmpty() || dist(q, out.last()) >= 1e-9) out += q
        return out
    }

    // ------------------------------------------------------------------ 1. Contorno sub-pixel

    /** La máscara como campo 0/1 con [borde] de ceros y un suavizado [1 2 1] en x y en y. */
    fun campoSuave(mascara: BooleanArray, w: Int, h: Int, borde: Int): Triple<FloatArray, Int, Int> {
        val gw = w + 2 * borde
        val gh = h + 2 * borde
        val a = FloatArray(gw * gh)
        for (y in 0 until h) for (x in 0 until w) if (mascara[y * w + x]) a[(y + borde) * gw + x + borde] = 1.0f
        val b = FloatArray(gw * gh)
        for (y in 0 until gh) for (x in 1 until gw - 1) { val i = y * gw + x; b[i] = 0.25f * a[i - 1] + 0.5f * a[i] + 0.25f * a[i + 1] }
        for (y in 1 until gh - 1) for (x in 0 until gw) { val i = y * gw + x; a[i] = 0.25f * b[i - gw] + 0.5f * b[i] + 0.25f * b[i + gw] }
        return Triple(a, gw, gh)
    }

    /** **Las isolíneas de [umbral]** (marching squares interpolado), en anillos cerrados. */
    fun isolineas(campo: FloatArray, w: Int, h: Int, umbral: Float): List<List<P>> {
        if (w < 2 || h < 2) return emptyList()
        val horizontales = (w - 1) * h
        fun idH(i: Int, j: Int) = j * (w - 1) + i
        fun idV(i: Int, j: Int) = horizontales + j * w + i
        val nAristas = horizontales + w * (h - 1)
        val nada = -1
        val siguiente = IntArray(nAristas) { nada }
        fun v(i: Int, j: Int) = campo[j * w + i]
        for (j in 0 until h - 1) for (i in 0 until w - 1) {
            val a = v(i, j); val b = v(i + 1, j); val c = v(i + 1, j + 1); val d = v(i, j + 1)
            val caso = ((if (a > umbral) 1 else 0) shl 3) or ((if (b > umbral) 1 else 0) shl 2) or ((if (c > umbral) 1 else 0) shl 1) or (if (d > umbral) 1 else 0)
            if (caso == 0 || caso == 15) continue
            val arriba = idH(i, j); val abajo = idH(i, j + 1); val izq = idV(i, j); val der = idV(i + 1, j)
            fun une(de: Int, x: Int) { siguiente[de] = x }
            val centroDentro = (a + b + c + d) / 4.0f > umbral
            when (caso) {
                1 -> une(abajo, izq)
                2 -> une(der, abajo)
                3 -> une(der, izq)
                4 -> une(arriba, der)
                6 -> une(arriba, abajo)
                7 -> une(arriba, izq)
                8 -> une(izq, arriba)
                9 -> une(abajo, arriba)
                11 -> une(der, arriba)
                12 -> une(izq, der)
                13 -> une(abajo, der)
                14 -> une(izq, abajo)
                5 -> if (centroDentro) { une(arriba, izq); une(abajo, der) } else { une(arriba, der); une(abajo, izq) }
                10 -> if (centroDentro) { une(der, arriba); une(izq, abajo) } else { une(izq, arriba); une(der, abajo) }
            }
        }
        fun interp(v0: Float, v1: Float): Double {
            val d = v1 - v0
            return if (abs(d) < 1e-9f) 0.5 else ((umbral - v0) / d).coerceIn(0.0f, 1.0f).toDouble()
        }
        fun posicion(e: Int): P = if (e < horizontales) {
            val i = e % (w - 1); val j = e / (w - 1)
            P(i + interp(v(i, j), v(i + 1, j)), j.toDouble())
        } else {
            val k = e - horizontales; val i = k % w; val j = k / w
            P(i.toDouble(), j + interp(v(i, j), v(i, j + 1)))
        }
        val anillos = ArrayList<List<P>>()
        for (inicio in 0 until nAristas) {
            if (siguiente[inicio] == nada) continue
            val anillo = ArrayList<P>()
            var e = inicio
            while (true) {
                val s = siguiente[e]
                if (s == nada) break
                siguiente[e] = nada
                anillo += posicion(e)
                e = s
                if (e == inicio) break
            }
            val limpio = sinRepetidos(anillo)
            if (limpio.size >= 3) anillos += limpio
        }
        return anillos
    }

    /** Área con signo (fórmula del cordón). */
    fun area(pol: List<P>): Double {
        val n = pol.size
        if (n < 3) return 0.0
        var s = 0.0
        for (i in 0 until n) { val a = pol[i]; val b = pol[(i + 1) % n]; s += a.x * b.y - b.x * a.y }
        return s / 2.0
    }

    // ------------------------------------------------------------- 2 y 3. Esquinas y Bézier

    sealed class Tramo {
        data class Recta(val a: P, val b: P) : Tramo()
        data class Cubica(val c: List<P>) : Tramo()
    }

    private fun acumulada(p: List<P>): DoubleArray {
        val s = DoubleArray(p.size)
        var l = 0.0
        for (i in p.indices) { if (i > 0) l += dist(p[i - 1], p[i]); s[i] = l }
        return s
    }

    private fun giroAEscala(p: List<P>, s: DoubleArray, total: Double, cerrado: Boolean, i: Int, escala: Double): Double? {
        val n = p.size
        var j = i
        var recorrido = 0.0
        val atras: P
        while (true) {
            val k = if (j == 0) { if (!cerrado) return null; n - 1 } else j - 1
            recorrido += if (k + 1 == j) s[j] - s[k] else total - s[k]
            j = k
            if (recorrido >= escala) { atras = p[j]; break }
            if (j == i) return null
        }
        j = i
        recorrido = 0.0
        val delante: P
        while (true) {
            val k = if (j + 1 == n) { if (!cerrado) return null; 0 } else j + 1
            recorrido += if (k == j + 1) s[k] - s[j] else total - s[j]
            j = k
            if (recorrido >= escala) { delante = p[j]; break }
            if (j == i) return null
        }
        val u = resta(p[i], atras); val v = resta(delante, p[i])
        val lu = largo(u); val lv = largo(v)
        if (lu < 1e-9 || lv < 1e-9) return null
        return acos((punto(u, v) / (lu * lv)).coerceIn(-1.0, 1.0))
    }

    private fun esquinas(p: List<P>, cerrado: Boolean): List<Pair<Int, Double>> {
        val grande = 4.0
        val pequena = 2.0
        val umbral = Math.toRadians(55.0)
        val s = acumulada(p)
        val total = (s.lastOrNull() ?: 0.0) + if (cerrado && p.size > 1) dist(p[p.size - 1], p[0]) else 0.0
        if (total < 4.0 * grande) return emptyList()
        val giros = DoubleArray(p.size) { i ->
            val g = giroAEscala(p, s, total, cerrado, i, grande) ?: 0.0
            if (g <= umbral) return@DoubleArray 0.0
            val c = giroAEscala(p, s, total, cerrado, i, pequena) ?: 0.0
            if (c > 0.6 * g) return@DoubleArray g
            val doble = giroAEscala(p, s, total, cerrado, i, 2.0 * grande) ?: Double.MAX_VALUE
            if (g > Math.toRadians(100.0) && doble < 1.25 * g) g else 0.0
        }
        val n = p.size
        val salida = ArrayList<Pair<Int, Double>>()
        for (i in 0 until n) {
            if (giros[i] == 0.0) continue
            var esMax = true
            for (paso in intArrayOf(1, -1)) {
                var j = i
                while (true) {
                    j += paso
                    val k = if (cerrado) Math.floorMod(j, n) else if (j < 0 || j >= n) break else j
                    if (k == i) break
                    val ds = abs(s[k] - s[i])
                    val d = if (cerrado) min(ds, total - ds) else ds
                    if (d > grande) break
                    if (giros[k] > giros[i] || (giros[k] == giros[i] && paso < 0)) { esMax = false; break }
                }
                if (!esMax) break
            }
            if (esMax) salida += i to giros[i]
        }
        return salida
    }

    private fun rectaDe(p: List<P>): Pair<P, P>? {
        if (p.size < 2) return null
        val n = p.size.toDouble()
        var m = CERO
        for (q in p) m = suma(m, q)
        m = por(m, 1.0 / n)
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (q in p) { val d = resta(q, m); sxx += d.x * d.x; sxy += d.x * d.y; syy += d.y * d.y }
        val ang = 0.5 * atan2(2.0 * sxy, sxx - syy)
        return m to P(cos(ang), sin(ang))
    }

    private fun corte(a: Pair<P, P>, b: Pair<P, P>): P? {
        val (p, r) = a
        val (q, s) = b
        val den = r.x * s.y - r.y * s.x
        if (abs(den) < 0.2) return null
        val t = ((q.x - p.x) * s.y - (q.y - p.y) * s.x) / den
        return suma(p, por(r, t))
    }

    private fun puntosADistancia(p: List<P>, cerrado: Boolean, i: Int, paso: Int, desde: Double, hasta: Double): List<P> {
        val n = p.size
        val v = ArrayList<P>()
        var j = i
        var d = 0.0
        var previo = p[i]
        while (true) {
            j += paso
            if (!cerrado && (j < 0 || j >= n)) break
            val q = p[Math.floorMod(j, n)]
            d += dist(previo, q)
            previo = q
            if (d > hasta || Math.floorMod(j, n) == i) break
            if (d >= desde) v += q
        }
        return v
    }

    /** **Ajusta** una polilínea con rectas y cúbicas que no se apartan más de [tol0]. */
    fun ajustar(p0: List<P>, cerrado: Boolean, tol0: Double): List<Tramo> {
        val p = sinRepetidos(p0)
        if (cerrado && p.size > 1 && dist(p[0], p[p.size - 1]) < 1e-9) p.removeAt(p.size - 1)
        val n = p.size
        if (n < 2) return emptyList()
        if (n == 2) return listOf(Tramo.Recta(p[0], p[1]))
        val tol = max(tol0, 0.05)
        var esq = esquinas(p, cerrado)
        if (cerrado && esq.isEmpty()) {
            val t = ArrayList(p).also { it += p[0] }
            val c = tangenteCentro(p, true, 0)
            if (c == CERO) return emptyList()
            val tramos = ArrayList<Tramo>()
            ajustarTrozo(t, c, por(c, -1.0), tol, tramos)
            return tramos
        }
        esq = esq.filter { (i, _) -> cerrado || (i != 0 && i != n - 1) }
        val afiladas = esq.map { (i, giro) -> val zona = zonaDeEsquina(giro); Triple(i, afiladaDe(p, cerrado, i, zona), zona) }
        val cortes = if (cerrado) afiladas else listOf(Triple(0, p[0], 0.0)) + afiladas + listOf(Triple(n - 1, p[n - 1], 0.0))
        val pares = if (cerrado) cortes.size else cortes.size - 1
        val tramos = ArrayList<Tramo>()
        for (k in 0 until pares) {
            val (ia, xa, za) = cortes[k]
            val (ib, xb, zb) = cortes[(k + 1) % cortes.size]
            val t = arrayListOf(xa)
            var j = ia
            while (true) {
                j = (j + 1) % n
                if (j == ib) break
                val q = p[j]
                if (dist(q, p[ia]) < za || dist(q, p[ib]) < zb) continue
                t += q
            }
            t += xb
            ajustarTrozo(t, null, null, tol, tramos)
        }
        return tramos
    }

    private fun zonaDeEsquina(giro: Double): Double {
        val angulo = max(Math.PI - giro, 0.2)
        return (0.6 / sin(angulo / 2.0)).coerceIn(1.2, 4.0)
    }

    private fun afiladaDe(p: List<P>, cerrado: Boolean, i: Int, zona: Double): P {
        val antes = puntosADistancia(p, cerrado, i, -1, zona, zona + 4.0)
        val despues = puntosADistancia(p, cerrado, i, 1, zona, zona + 4.0)
        val a = rectaDe(antes)
        val b = rectaDe(despues)
        if (a != null && b != null && antes.size >= 2 && despues.size >= 2) {
            return corte(a, b)?.takeIf { dist(it, p[i]) < zona * 1.5 + 0.5 } ?: p[i]
        }
        return p[i]
    }

    private fun media(v: List<P>, si: P): P {
        if (v.isEmpty()) return si
        var s = CERO
        for (q in v) s = suma(s, q)
        return por(s, 1.0 / v.size)
    }

    private fun tangenteCentro(p: List<P>, cerrado: Boolean, i: Int): P {
        val atras = puntosADistancia(p, cerrado, i, -1, 0.0, 2.5)
        val delante = puntosADistancia(p, cerrado, i, 1, 0.0, 2.5)
        return unitario(resta(media(delante, p[i]), media(atras, p[i])))
    }

    private fun tangenteExtremo(p: List<P>): P {
        var d = CERO
        var l = 0.0
        for (k in 1 until p.size) {
            l += dist(p[k - 1], p[k])
            d = suma(d, unitario(resta(p[k], p[0])))
            if (l > 3.0) break
        }
        return unitario(d)
    }

    private fun ajustarTrozo(t: List<P>, t1: P?, t2: P?, tol: Double, salida: MutableList<Tramo>) {
        val n = t.size
        if (n < 2) return
        val a = t[0]; val b = t[n - 1]
        fun recto(): Boolean {
            val cuerda = t.fold(0.0) { m, q -> max(m, distARecta(q, a, b)) }
            return cuerda <= tol * 0.5 || (cuerda <= tol && rectaDe(t)?.let { (m, d) -> t.all { distARecta(it, m, suma(m, d)) <= tol * 0.35 } } == true)
        }
        if (n == 2 || (t1 == null && recto())) {
            if (dist(a, b) > 1e-9) salida += Tramo.Recta(a, b)
            return
        }
        val u1 = t1 ?: tangenteExtremo(t)
        val u2 = t2 ?: tangenteExtremo(t.asReversed())
        schneider(t, u1, u2, tol, salida, 0)
    }

    private fun distARecta(p: P, a: P, b: P): Double {
        val d = resta(b, a)
        val l = largo(d)
        if (l < 1e-12) return dist(p, a)
        return abs((p.x - a.x) * d.y - (p.y - a.y) * d.x) / l
    }

    fun bezier(c: List<P>, u: Double): P {
        val v = 1.0 - u
        val b0 = v * v * v; val b1 = 3.0 * v * v * u; val b2 = 3.0 * v * u * u; val b3 = u * u * u
        return P(c[0].x * b0 + c[1].x * b1 + c[2].x * b2 + c[3].x * b3, c[0].y * b0 + c[1].y * b1 + c[2].y * b2 + c[3].y * b3)
    }

    private fun cuerda(t: List<P>): DoubleArray {
        val s = acumulada(t)
        val total = s.lastOrNull() ?: 0.0
        if (total <= 0.0) return DoubleArray(t.size)
        return DoubleArray(s.size) { s[it] / total }
    }

    private fun generar(t: List<P>, u: DoubleArray, t1: P, t2: P): List<P> {
        val p0 = t[0]; val p3 = t[t.size - 1]
        var c00 = 0.0; var c01 = 0.0; var c11 = 0.0; var x0 = 0.0; var x1 = 0.0
        for (i in t.indices) {
            val q = t[i]; val ui = u[i]
            val v = 1.0 - ui
            val b0 = v * v * v; val b1 = 3.0 * v * v * ui; val b2 = 3.0 * v * ui * ui; val b3 = ui * ui * ui
            val a1 = por(t1, b1); val a2 = por(t2, b2)
            c00 += punto(a1, a1); c01 += punto(a1, a2); c11 += punto(a2, a2)
            val resto = resta(q, suma(por(p0, b0 + b1), por(p3, b2 + b3)))
            x0 += punto(a1, resto); x1 += punto(a2, resto)
        }
        val det = c00 * c11 - c01 * c01
        val seg = dist(p0, p3)
        var al: Double; var ar: Double
        if (abs(det) > 1e-12) { al = (x0 * c11 - x1 * c01) / det; ar = (c00 * x1 - c01 * x0) / det } else { al = 0.0; ar = 0.0 }
        val eps = 1e-6 * seg
        if (al < eps || ar < eps) { al = seg / 3.0; ar = seg / 3.0 }
        al = min(al, seg * 2.0); ar = min(ar, seg * 2.0)
        return listOf(p0, suma(p0, por(t1, al)), suma(p3, por(t2, ar)), p3)
    }

    private fun errorMaximo(t: List<P>, c: List<P>, u: DoubleArray): Pair<Double, Int> {
        var peor = 0.0 to t.size / 2
        for (i in 1 until t.size - 1) { val d = dist(bezier(c, u[i]), t[i]); if (d > peor.first) peor = d to i }
        return peor
    }

    private fun reparametrizar(t: List<P>, c: List<P>, u: DoubleArray): DoubleArray {
        val d1 = listOf(por(resta(c[1], c[0]), 3.0), por(resta(c[2], c[1]), 3.0), por(resta(c[3], c[2]), 3.0))
        val d2 = listOf(por(resta(d1[1], d1[0]), 2.0), por(resta(d1[2], d1[1]), 2.0))
        return DoubleArray(t.size) { i ->
            val q = t[i]; val ui = u[i]; val v = 1.0 - ui
            val b = bezier(c, ui)
            val b1 = suma(suma(por(d1[0], v * v), por(d1[1], 2.0 * v * ui)), por(d1[2], ui * ui))
            val b2 = suma(por(d2[0], v), por(d2[1], ui))
            val dif = resta(b, q)
            val num = punto(dif, b1)
            val den = punto(b1, b1) + punto(dif, b2)
            if (abs(den) < 1e-12) ui else (ui - num / den).coerceIn(0.0, 1.0)
        }
    }

    private fun schneider(t: List<P>, t1: P, t2: P, tol: Double, salida: MutableList<Tramo>, hondo: Int) {
        val n = t.size
        if (n == 2) {
            val l = dist(t[0], t[1]) / 3.0
            salida += rectaOCubica(listOf(t[0], suma(t[0], por(t1, l)), suma(t[1], por(t2, l)), t[1]), tol)
            return
        }
        var u = cuerda(t)
        var c = generar(t, u, t1, t2)
        var (err, peor) = errorMaximo(t, c, u)
        if (err <= tol) { salida += rectaOCubica(c, tol); return }
        if (err < tol * 4.0) {
            repeat(4) {
                u = reparametrizar(t, c, u)
                c = generar(t, u, t1, t2)
                val e = errorMaximo(t, c, u)
                err = e.first; peor = e.second
                if (err <= tol) { salida += rectaOCubica(c, tol); return }
            }
        }
        if (hondo > 40) { salida += rectaOCubica(c, tol); return }
        val k = peor.coerceIn(1, n - 2)
        var tc = tangenteCentro(t, false, k)
        if (tc == CERO) tc = unitario(resta(t[k + 1], t[k - 1]))
        schneider(t.subList(0, k + 1), t1, por(tc, -1.0), tol, salida, hondo + 1)
        schneider(t.subList(k, n), tc, t2, tol, salida, hondo + 1)
    }

    private fun rectaOCubica(c: List<P>, tol: Double): Tramo {
        val plano = min(tol * 0.1, 0.05)
        if (distARecta(c[1], c[0], c[3]) <= plano && distARecta(c[2], c[0], c[3]) <= plano) {
            val d = resta(c[3], c[0])
            fun dentro(q: P): Boolean { val k = punto(resta(q, c[0]), d); return k >= 0.0 && k <= punto(d, d) }
            if (dentro(c[1]) && dentro(c[2])) return Tramo.Recta(c[0], c[3])
        }
        return Tramo.Cubica(c)
    }

    /** **Aplana** los tramos en una polilínea que no se aparta más de [plano0]. */
    fun aplanar(tramos: List<Tramo>, cerrado: Boolean, plano0: Double): List<P> {
        val plano = max(plano0, 1e-3)
        val v = ArrayList<P>()
        for (tr in tramos) when (tr) {
            is Tramo.Recta -> v += tr.a
            is Tramo.Cubica -> {
                val c = tr.c
                fun dd(a: P, b: P, cc: P) = largo(suma(resta(a, por(b, 2.0)), cc))
                val m = 6.0 * max(dd(c[0], c[1], c[2]), dd(c[1], c[2], c[3]))
                val n = ceil(sqrt(m / (8.0 * plano))).toInt().coerceIn(1, 64)
                for (k in 0 until n) v += bezier(c, k.toDouble() / n)
            }
        }
        tramos.lastOrNull()?.let { u -> if (!cerrado) v += if (u is Tramo.Recta) u.b else (u as Tramo.Cubica).c[3] }
        val out = sinRepetidos(v)
        if (cerrado && out.size > 1 && dist(out[0], out[out.size - 1]) < 1e-9) out.removeAt(out.size - 1)
        return out
    }
}
