package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * **El hueco entre varias figuras, exacto** (20-sep-2026).
 *
 * El relleno de una sola figura ya era el suyo ([figuraQueSeRellenaSola]); lo que quedaba a
 * cargo de la rejilla eran los huecos **entre** figuras —dos círculos que se pisan, un rectángulo
 * partido por una raya—, y ahí el usuario seguía viendo lo mismo: «hay zonas en las que se traza
 * de forma extraña o se sale de su contenedor; son líneas vectoriales, no debería pasar». Tiene
 * razón: una rejilla de celdas arrimada a las paredes se come los picos y se abomba en las curvas.
 *
 * Así que aquí no hay celdas. Los tramos de todas las paredes se **cortan entre sí de verdad**,
 * con eso se arma el plano —nudos y aristas— y se recorre **la cara que contiene el punto
 * tocado**: su borde son los propios tramos, con sus esquinas donde se cruzan. Las islas que
 * queden dentro salen como agujeros.
 *
 * Lo dibujado a mano no cierra al milímetro, así que **un cabo suelto** que se queda a menos de
 * [tolerancia] de otra raya se da por unido a ella. Si aun así la cara no cierra, se devuelve null
 * y decide la rejilla, que tapa rendijas más gordas.
 */
internal fun caraExacta(segmentos: List<Pair<Pt, Pt>>, p: Pt, tolerancia: Double = 2.0): Region? {
    val n = segmentos.size
    if (n < 3 || n > MAXIMO_DE_TRAMOS) return null
    val plano = Plano(segmentos, tolerancia)
    return plano.caraDe(p)
}

private const val MAXIMO_DE_TRAMOS = 12_000
private const val JUNTO = 1e-3

private class Plano(private val segmentos: List<Pair<Pt, Pt>>, private val tolerancia: Double) {
    // ---- Nudos: un punto, un número. Los que caen a menos de [JUNTO] son el mismo. ----
    private val xs = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private val casillas = HashMap<Long, MutableList<Int>>()
    private var jefe = IntArray(0)

    private fun clave(ix: Long, iy: Long) = ix * 2_000_003L + iy

    private fun nudo(x: Double, y: Double): Int {
        val ix = floor(x / JUNTO / 4).toLong()
        val iy = floor(y / JUNTO / 4).toLong()
        for (dx in -1L..1L) for (dy in -1L..1L) {
            casillas[clave(ix + dx, iy + dy)]?.forEach { k ->
                if (abs(xs[k] - x) <= JUNTO && abs(ys[k] - y) <= JUNTO) return k
            }
        }
        xs.add(x); ys.add(y)
        casillas.getOrPut(clave(ix, iy)) { ArrayList(2) }.add(xs.size - 1)
        return xs.size - 1
    }

    private fun raiz(a: Int): Int {
        var r = a
        while (jefe[r] != r) { jefe[r] = jefe[jefe[r]]; r = jefe[r] }
        return r
    }

    // ---- Cortes: por cada tramo, dónde lo tocan los demás. ----
    private val n = segmentos.size
    private val cortesT = Array(n) { ArrayList<Double>(4) }
    private val cortesN = Array(n) { ArrayList<Int>(4) }
    private val uniones = ArrayList<Int>()

    private fun cortar(i: Int, t: Double, nudo: Int) { cortesT[i].add(t); cortesN[i].add(nudo) }

    private lateinit var vecinos: Array<IntArray>

    init {
        val ax = DoubleArray(n) { segmentos[it].first.x }
        val ay = DoubleArray(n) { segmentos[it].first.y }
        val bx = DoubleArray(n) { segmentos[it].second.x }
        val by = DoubleArray(n) { segmentos[it].second.y }
        val na = IntArray(n) { nudo(ax[it], ay[it]) }
        val nb = IntArray(n) { nudo(bx[it], by[it]) }
        for (i in 0 until n) { cortar(i, 0.0, na[i]); cortar(i, 1.0, nb[i]) }

        // Una criba por casillas, para no cruzar todos con todos.
        var x1 = Double.MAX_VALUE; var y1 = Double.MAX_VALUE; var x2 = -Double.MAX_VALUE; var y2 = -Double.MAX_VALUE
        for (i in 0 until n) {
            x1 = min(x1, min(ax[i], bx[i])); y1 = min(y1, min(ay[i], by[i]))
            x2 = max(x2, max(ax[i], bx[i])); y2 = max(y2, max(ay[i], by[i]))
        }
        val lado = max(max(x2 - x1, y2 - y1) / 96.0, tolerancia * 2).coerceAtLeast(1e-6)
        val criba = HashMap<Long, MutableList<Int>>()
        fun enCasillas(i: Int, holgura: Double, cada: (Long) -> Unit) {
            val cx1 = floor((min(ax[i], bx[i]) - holgura - x1) / lado).toLong()
            val cx2 = floor((max(ax[i], bx[i]) + holgura - x1) / lado).toLong()
            val cy1 = floor((min(ay[i], by[i]) - holgura - y1) / lado).toLong()
            val cy2 = floor((max(ay[i], by[i]) + holgura - y1) / lado).toLong()
            for (cx in cx1..cx2) for (cy in cy1..cy2) cada(clave(cx, cy))
        }
        for (i in 0 until n) enCasillas(i, 0.0) { criba.getOrPut(it) { ArrayList(4) }.add(i) }

        // Cuántos tramos salen de cada nudo: con uno solo, es un cabo suelto.
        val grado = HashMap<Int, Int>()
        for (i in 0 until n) { grado.merge(na[i], 1, Int::plus); grado.merge(nb[i], 1, Int::plus) }

        val visto = IntArray(n) { -1 }
        for (i in 0 until n) {
            enCasillas(i, tolerancia) { c ->
                criba[c]?.forEach { j ->
                    if (j != i && visto[j] != i) {
                        visto[j] = i
                        if (j > i) cruzar(i, j, ax, ay, bx, by)
                        // Los cabos sueltos de i, contra j.
                        if (grado[na[i]] == 1) arrimar(na[i], ax[i], ay[i], j, ax, ay, bx, by, na, nb)
                        if (grado[nb[i]] == 1) arrimar(nb[i], bx[i], by[i], j, ax, ay, bx, by, na, nb)
                    }
                }
            }
        }

        jefe = IntArray(xs.size) { it }
        var k = 0
        while (k < uniones.size) { jefe[raiz(uniones[k])] = raiz(uniones[k + 1]); k += 2 }

        // Aristas sin repetir, y los vecinos de cada nudo ordenados por ángulo.
        val aristas = HashSet<Long>()
        val listas = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val orden = cortesT[i].indices.sortedBy { cortesT[i][it] }
            var antes = -1
            for (o in orden) {
                val t = cortesT[i][o]
                if (t < -1e-9 || t > 1 + 1e-9) continue
                val u = raiz(cortesN[i][o])
                if (antes != -1 && antes != u) {
                    val llave = min(antes, u).toLong() * 4_000_000L + max(antes, u)
                    if (aristas.add(llave)) {
                        listas.getOrPut(antes) { ArrayList(3) }.add(u)
                        listas.getOrPut(u) { ArrayList(3) }.add(antes)
                    }
                }
                antes = u
            }
        }
        vecinos = Array(xs.size) { u ->
            val l = listas[u] ?: return@Array IntArray(0)
            l.sortedBy { v -> atan2(ys[v] - ys[u], xs[v] - xs[u]) }.toIntArray()
        }
    }

    private fun cruzar(i: Int, j: Int, ax: DoubleArray, ay: DoubleArray, bx: DoubleArray, by: DoubleArray) {
        val rx = bx[i] - ax[i]; val ry = by[i] - ay[i]
        val sx = bx[j] - ax[j]; val sy = by[j] - ay[j]
        val den = rx * sy - ry * sx
        val li = hypot(rx, ry); val lj = hypot(sx, sy)
        if (li < 1e-12 || lj < 1e-12) return
        if (abs(den) < 1e-12 * li * lj) {
            // Paralelos. Si además van por la misma recta y se pisan, cada uno corta al otro
            // en los extremos que le caen encima: dos rectángulos con un lado común.
            if (abs((ax[j] - ax[i]) * ry - (ay[j] - ay[i]) * rx) / li > JUNTO) return
            sobre(i, ax[j], ay[j], ax, ay, rx, ry, li); sobre(i, bx[j], by[j], ax, ay, rx, ry, li)
            sobre(j, ax[i], ay[i], ax, ay, sx, sy, lj); sobre(j, bx[i], by[i], ax, ay, sx, sy, lj)
            return
        }
        val qx = ax[j] - ax[i]; val qy = ay[j] - ay[i]
        val t = (qx * sy - qy * sx) / den
        val u = (qx * ry - qy * rx) / den
        val ei = JUNTO / li; val ej = JUNTO / lj
        if (t < -ei || t > 1 + ei || u < -ej || u > 1 + ej) return
        val tt = t.coerceIn(0.0, 1.0); val uu = u.coerceIn(0.0, 1.0)
        val k = nudo(ax[i] + rx * tt, ay[i] + ry * tt)
        cortar(i, tt, k); cortar(j, uu, k)
    }

    private fun sobre(i: Int, x: Double, y: Double, ax: DoubleArray, ay: DoubleArray, rx: Double, ry: Double, l: Double) {
        val t = ((x - ax[i]) * rx + (y - ay[i]) * ry) / (l * l)
        if (t < 0.0 || t > 1.0) return
        cortar(i, t, nudo(x, y))
    }

    /** El cabo suelto ([cabo], en x·y) se une a la raya j si le queda a menos de la tolerancia. */
    private fun arrimar(
        cabo: Int, x: Double, y: Double, j: Int,
        ax: DoubleArray, ay: DoubleArray, bx: DoubleArray, by: DoubleArray, na: IntArray, nb: IntArray
    ) {
        if (na[j] == cabo || nb[j] == cabo) return
        val sx = bx[j] - ax[j]; val sy = by[j] - ay[j]
        val l2 = sx * sx + sy * sy
        if (l2 < 1e-18) return
        val t = (((x - ax[j]) * sx + (y - ay[j]) * sy) / l2).coerceIn(0.0, 1.0)
        val px = ax[j] + sx * t; val py = ay[j] + sy * t
        if (hypot(px - x, py - y) > tolerancia) return
        // Cerca de una punta de j, a la punta: dos rayas que casi hacen esquina, la hacen.
        val l = kotlin.math.sqrt(l2)
        val destino = when {
            t * l <= tolerancia -> na[j]
            (1 - t) * l <= tolerancia -> nb[j]
            else -> nudo(px, py).also { cortar(j, t, it) }
        }
        uniones.add(cabo); uniones.add(destino)
    }

    // ---- Caras ----

    private fun area(c: IntArray): Double {
        var s = 0.0
        for (i in c.indices) {
            val a = c[i]; val b = c[(i + 1) % c.size]
            s += xs[a] * ys[b] - xs[b] * ys[a]
        }
        return s / 2
    }

    private fun dentro(x: Double, y: Double, c: IntArray): Boolean {
        var si = false
        var j = c.size - 1
        for (i in c.indices) {
            val yi = ys[c[i]]; val yj = ys[c[j]]
            if ((yi > y) != (yj > y) && x < (xs[c[j]] - xs[c[i]]) * (y - yi) / (yj - yi) + xs[c[i]]) si = !si
            j = i
        }
        return si
    }

    fun caraDe(p: Pt): Region? {
        // Todas las caras: cada media arista se recorre una vez, girando siempre a la derecha
        // de la que se viene — la cara queda a la izquierda.
        val desde = IntArray(vecinos.size + 1)
        for (u in vecinos.indices) desde[u + 1] = desde[u] + vecinos[u].size
        val hecha = BooleanArray(desde[vecinos.size])
        val llenas = ArrayList<IntArray>()
        val islas = ArrayList<IntArray>()
        for (u0 in vecinos.indices) for (k0 in vecinos[u0].indices) {
            if (hecha[desde[u0] + k0]) continue
            val ciclo = ArrayList<Int>()
            var u = u0; var k = k0
            while (!hecha[desde[u] + k]) {
                hecha[desde[u] + k] = true
                ciclo.add(u)
                val v = vecinos[u][k]
                val vuelta = vecinos[v].indexOf(u)
                k = (vuelta - 1 + vecinos[v].size) % vecinos[v].size
                u = v
            }
            val c = sinRabos(ciclo)
            if (c.size < 3) continue
            val a = area(c)
            if (a > 1e-9) llenas.add(c) else if (a < -1e-9) islas.add(c)
        }
        val fuera = llenas.filter { dentro(p.x, p.y, it) }.minByOrNull { area(it) } ?: return null
        val delBorde = fuera.toHashSet()
        val candidatas = islas.filter { isla -> isla.none { it in delBorde } && dentro(xs[isla[0]], ys[isla[0]], fuera) }
        // Una isla dentro de otra isla no es agujero de esta cara: está en una de más adentro.
        val huecos = candidatas.filter { h ->
            candidatas.none { k -> k !== h && abs(area(k)) > abs(area(h)) && dentro(xs[h[0]], ys[h[0]], k) }
        }
        // El punto no puede estar dentro de un agujero: entonces su cara sería otra más pequeña.
        return Region(fuera.map { Pt(xs[it], ys[it]) }, huecos.map { h -> h.map { Pt(xs[it], ys[it]) } })
    }

    /** Quita las idas y vueltas por una raya que se mete en la cara y no la parte: a, b, a → a. */
    private fun sinRabos(ciclo: List<Int>): IntArray {
        val pila = ArrayList<Int>(ciclo.size)
        for (u in ciclo) {
            if (pila.size >= 2 && pila[pila.size - 2] == u) pila.removeAt(pila.size - 1) else pila.add(u)
        }
        // Y por la costura, donde el ciclo se cierra.
        var cambia = true
        while (cambia && pila.size >= 3) {
            cambia = false
            if (pila[pila.size - 1] == pila[1]) { pila.removeAt(0); pila.removeAt(pila.size - 1); cambia = true; continue }
            if (pila[pila.size - 2] == pila[0]) { pila.removeAt(pila.size - 1); pila.removeAt(pila.size - 1); cambia = true }
        }
        return pila.toIntArray()
    }
}
