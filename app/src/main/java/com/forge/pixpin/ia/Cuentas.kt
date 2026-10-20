package com.forge.pixpin.ia

import kotlin.math.abs
import kotlin.math.max

/**
 * **Las cuentas de las gráficas** (PC `pixpin-ia/src/calculo.rs`): raíces, extremos, polos, asíntotas,
 * áreas y derivadas de una `f(x)`. Regla del usuario: los cálculos nunca los hace el modelo. Todo
 * numérico y robusto antes que exacto: tolerancias relativas a la escala de la función.
 */
object Cuentas {
    const val MUESTRAS = 4000

    private fun linspace(a: Double, b: Double, n: Int) = DoubleArray(n + 1) { a + (b - a) * it / n }

    /** Bisección de un cambio de signo de [g] en `[a, b]`. */
    private fun biseccion(g: (Double) -> Double, a0: Double, b0: Double): Double {
        var a = a0; var b = b0
        var ga = g(a)
        repeat(90) {
            val m = 0.5 * (a + b)
            val gm = g(m)
            if (!gm.isFinite()) { b = m; return@repeat }
            if (gm == 0.0) return m
            if ((gm > 0.0) == (ga > 0.0)) { a = m; ga = gm } else b = m
        }
        return 0.5 * (a + b)
    }

    /** La escala típica de [f] en `[a, b]` (la mediana de |f|). */
    fun escala(f: (Double) -> Double, a: Double, b: Double): Double {
        val v = linspace(a, b, 400).map(f).filter { it.isFinite() }.map(::abs).sorted()
        if (v.isEmpty()) return 1.0
        return max(v[v.size / 2], 1e-9)
    }

    /** `sort` + `dedup_by` de Rust: quita el que está a menos de [cerca] del último que se quedó. */
    private fun sinRepetir(v: MutableList<Double>, cerca: Double) {
        v.sortWith { x, y -> x.compareTo(y) }
        val salida = mutableListOf<Double>()
        for (x in v) if (salida.isEmpty() || abs(x - salida.last()) > cerca) salida += x
        v.clear(); v += salida
    }

    /** La derivada numérica (diferencia centrada). */
    fun derivada(f: (Double) -> Double, x: Double): Double {
        val h = 1e-5 * (1.0 + abs(x))
        return (f(x + h) - f(x - h)) / (2.0 * h)
    }

    /** Si [f] es derivable en [x]: las pendientes por la izquierda y por la derecha coinciden. */
    fun derivable(f: (Double) -> Double, x: Double): Boolean {
        val h = 1e-4 * (1.0 + abs(x))
        val izq = (f(x) - f(x - h)) / h
        val der = (f(x + h) - f(x)) / h
        return izq.isFinite() && der.isFinite() && abs(izq - der) <= 1e-2 * (1.0 + max(abs(izq), abs(der)))
    }

    /** **Los ceros de [f] en `[a, b]`**, y aparte los cambios de signo que resultaron ser polos. */
    fun ceros(f: (Double) -> Double, a: Double, b: Double): Pair<List<Double>, List<Double>> {
        val s = escala(f, a, b)
        val tol = 1e-6 * (1.0 + s)
        val xs = linspace(a, b, MUESTRAS)
        val ys = DoubleArray(xs.size) { f(xs[it]) }
        val raices = mutableListOf<Double>(); val polos = mutableListOf<Double>()
        for (i in xs.indices) {
            if (ys[i] == 0.0) { raices += xs[i]; continue }
            if (i + 1 < xs.size && ys[i].isFinite() && ys[i + 1].isFinite() && ys[i + 1] != 0.0 && (ys[i] > 0.0) != (ys[i + 1] > 0.0)) {
                val r = biseccion(f, xs[i], xs[i + 1])
                val fr = f(r)
                if (fr.isFinite() && abs(fr) <= max(tol, 1e-9)) raices += r
                else if (!fr.isFinite() || abs(fr) > 1e3 * (1.0 + s)) polos += r
            }
        }
        // Los ceros que tocan sin cruzar (x^2 en el 0): un extremo sobre el eje.
        for ((x, _) in extremos(f, a, b)) if (abs(f(x)) <= max(tol, 1e-9) * 10.0) raices += x
        val cerca = (b - a) * 1e-6
        sinRepetir(raices, max(cerca, 1e-9))
        sinRepetir(polos, max(cerca, 1e-9))
        return raices.map(::limpio) to polos.map(::limpio)
    }

    /** Un valor casi entero, entero (la bisección deja 1.9999999). Redondeo de Rust: lejos del cero. */
    fun limpio(v: Double): Double {
        val r = redondear(v)
        return if (abs(v - r) < 1e-7 * (1.0 + abs(v))) r else v
    }

    /** `f64::round` de Rust (la mitad, lejos del cero), no el de Kotlin (la mitad, al par). */
    fun redondear(v: Double): Double = if (v < 0) -Math.floor(-v + 0.5) else Math.floor(v + 0.5)

    /** **Los extremos locales** de [f] en `[a, b]`: `(x, esMaximo)`. */
    fun extremos(f: (Double) -> Double, a: Double, b: Double): List<Pair<Double, Boolean>> {
        val g = { x: Double -> derivada(f, x) }
        val n = MUESTRAS / 2
        val xs = linspace(a, b, n)
        val gs = DoubleArray(xs.size) { g(xs[it]) }
        val s = escala(f, a, b)
        val v = mutableListOf<Pair<Double, Boolean>>()
        for (i in 0 until n) {
            val g0 = gs[i]; val g1 = gs[i + 1]
            if (!g0.isFinite() || !g1.isFinite() || (g0 == 0.0 && g1 == 0.0)) continue
            if ((g0 > 0.0) != (g1 > 0.0) || g1 == 0.0) {
                val r = if (g1 == 0.0) xs[i + 1] else biseccion(g, xs[i], xs[i + 1])
                val fr = f(r)
                val paso = (b - a) / n
                // Que exista alrededor y no sea un polo.
                if (!fr.isFinite() || !f(r - paso).isFinite() || !f(r + paso).isFinite() || abs(fr) > 1e4 * (1.0 + s)) continue
                val maximo = f(r - paso) <= fr && f(r + paso) <= fr
                val minimo = f(r - paso) >= fr && f(r + paso) >= fr
                if (maximo != minimo) v += limpio(r) to maximo
            }
        }
        val salida = mutableListOf<Pair<Double, Boolean>>()
        for (e in v) if (salida.isEmpty() || abs(e.first - salida.last().first) > (abs(salida.last().first) + 1.0) * 1e-6) salida += e
        return salida
    }

    /** **Los polos** (asíntotas verticales): los cambios de signo que se disparan y los picos de |f| que no acaban. */
    fun polos(f: (Double) -> Double, a: Double, b: Double): List<Double> {
        val v = ceros(f, a, b).second.toMutableList()
        val s = escala(f, a, b)
        val n = MUESTRAS
        val xs = linspace(a, b, n)
        val ys = DoubleArray(xs.size) { abs(f(xs[it])) }
        val grande = 1e3 * (1.0 + s)
        for (i in 1 until n) {
            val y = ys[i]
            val pico = if (y.isFinite()) y > grande && y >= ys[i - 1] && y >= ys[i + 1]
            else y.isInfinite() || (ys[i - 1].isFinite() && ys[i + 1].isFinite() && ys[i - 1] > grande && ys[i + 1] > grande)
            if (!pico) continue
            // Búsqueda ternaria del máximo de |f|.
            var lo = xs[i - 1]; var hi = xs[i + 1]
            repeat(80) {
                val m1 = lo + (hi - lo) / 3.0
                val m2 = hi - (hi - lo) / 3.0
                val f1 = abs(f(m1)).let { if (it.isFinite()) it else Double.MAX_VALUE }
                val f2 = abs(f(m2)).let { if (it.isFinite()) it else Double.MAX_VALUE }
                if (f1 < f2) lo = m1 else hi = m2
            }
            val x = 0.5 * (lo + hi)
            val fx = abs(f(x))
            if (!fx.isFinite() || fx > 1e6 * (1.0 + s)) v += limpio(x)
        }
        sinRepetir(v, max((b - a) * 1e-5, 1e-9))
        return v
    }

    /** Una asíntota horizontal u oblicua hacia [lado] (+1 o -1): `(m, b)` de `y = m x + b`. */
    fun asintotaAlInfinito(f: (Double) -> Double, lado: Double): Pair<Double, Double>? {
        val l1 = lado * 1e5; val l2 = lado * 1e6; val l3 = lado * 1e7
        val f1 = f(l1); val f2 = f(l2); val f3 = f(l3)
        if (!(f1.isFinite() && f2.isFinite() && f3.isFinite())) return null
        val m0 = (f3 - f2) / (l3 - l2)
        val b0 = f3 - m0 * l3
        val pred = m0 * l1 + b0
        if (abs(pred - f1) > 1e-3 * (1.0 + minOf(abs(f1), 1e9))) return null
        val m = if (abs(m0) < 1e-6) 0.0 else limpio(redondear(m0 * 1e4) / 1e4)
        val bb = if (m == 0.0) f3 else b0
        val b = limpio(redondear(bb * 1e4) / 1e4)
        return m to (if (b == 0.0) 0.0 else b)
    }

    /** Si [f] es (numéricamente) la recta `y = m x + q` en `[a, b]`. */
    fun esLaRecta(f: (Double) -> Double, mq: Pair<Double, Double>, a: Double, b: Double): Boolean =
        linspace(a, b, 50).all { x -> val y = f(x); !y.isFinite() || abs(y - (mq.first * x + mq.second)) < 1e-6 * (1.0 + abs(y)) }

    /** **El área entre [f] y [g]** de [a] a [b] (Simpson sobre |f - g|), o null si no es finita. */
    fun areaEntre(f: (Double) -> Double, g: (Double) -> Double, a: Double, b: Double): Double? {
        val n = 4000
        val h = (b - a) / n
        var s = 0.0
        for (i in 0..n) {
            val x = a + h * i
            val d = abs(f(x) - g(x))
            if (!d.isFinite()) return null
            val peso = if (i == 0 || i == n) 1.0 else if (i % 2 == 1) 4.0 else 2.0
            s += peso * d
        }
        val area = s * h / 3.0
        return area.takeIf { it.isFinite() && it < 1e9 }
    }
}
