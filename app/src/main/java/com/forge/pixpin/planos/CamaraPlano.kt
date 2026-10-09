package com.forge.pixpin.planos

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * **Dónde mira el visor de planos**: el punto del plano en el centro de la pantalla y cuántas
 * unidades del plano caben en un píxel. Igual que `Camara` de `crates/pixpin-cad/src/ventana.rs`
 * del PC (y con sus mismas pruebas): la `y` del plano sube y la de la pantalla baja.
 */
data class CamaraPlano(val centroX: Double, val centroY: Double, val px: Double) {
    /** El punto del plano bajo el píxel ([x], [y]) de una pantalla [w] × [h]. */
    fun planoX(x: Double, w: Int): Double = centroX + (x - w / 2.0) * px
    fun planoY(y: Double, h: Int): Double = centroY - (y - h / 2.0) * px

    /** El píxel de un punto del plano. */
    fun pantallaX(px0: Double, w: Int): Double = (px0 - centroX) / px + w / 2.0
    fun pantallaY(py0: Double, h: Int): Double = h / 2.0 - (py0 - centroY) / px

    /** Acercar [factor] (>1 aleja) dejando quieto el punto bajo ([x], [y]). */
    fun zoom(factor: Double, x: Double, y: Double, w: Int, h: Int): CamaraPlano {
        val ax = planoX(x, w); val ay = planoY(y, h)
        val c = copy(px = (px * factor).coerceIn(1e-9, 1e9))
        return c.copy(centroX = c.centroX + ax - c.planoX(x, w), centroY = c.centroY + ay - c.planoY(y, h))
    }

    /** Correr la vista lo que se arrastró el dedo ([dx], [dy] en píxeles). */
    fun mover(dx: Double, dy: Double): CamaraPlano = copy(centroX = centroX - dx * px, centroY = centroY + dy * px)

    companion object {
        /** El plano entero en una pantalla de [w] × [h], con un margen. */
        fun encuadrar(caja: FloatArray, w: Int, h: Int): CamaraPlano {
            val cw = (caja[2] - caja[0]).toDouble().coerceAtLeast(1e-9)
            val ch = (caja[3] - caja[1]).toDouble().coerceAtLeast(1e-9)
            val px = maxOf(cw / (w.coerceAtLeast(1) * 0.92), ch / (h.coerceAtLeast(1) * 0.92))
            return CamaraPlano(
                (caja[0] + caja[2]) / 2.0, (caja[1] + caja[3]) / 2.0,
                if (px.isFinite() && px > 0) px else 1.0
            )
        }
    }
}

/**
 * **Dónde se engancha la cota**: los vértices de lo dibujado y los centros y extremos de los
 * círculos y arcos (`Enganches` del PC). Aquí repartidos en una rejilla de 256 × 256 por
 * recuento —sin ordenar: un plano grande trae millones de vértices— para mirar solo cerca del dedo.
 */
class Enganches private constructor(
    private val x0: Double, private val y0: Double, private val celda: Double,
    /** Dónde empieza cada celda en [xs]/[ys] (una más al final). */
    private val inicio: IntArray,
    private val xs: FloatArray, private val ys: FloatArray,
) {
    /** El punto más cercano a ([x], [y]) a menos de [radio] (unidades del plano), o null. */
    fun cerca(x: Double, y: Double, radio: Double): DoubleArray? {
        val n = ceil(radio / celda).toInt()
        if (n > 8) return null // muy de lejos: no vale la pena
        val cx = floor((x - x0) / celda).toInt(); val cy = floor((y - y0) / celda).toInt()
        var mejor: DoubleArray? = null
        var dm = radio
        for (j in (cy - n).coerceAtLeast(0)..(cy + n).coerceAtMost(LADO - 1)) for (i in (cx - n).coerceAtLeast(0)..(cx + n).coerceAtMost(LADO - 1)) {
            val c = j * LADO + i
            for (k in inicio[c] until inicio[c + 1]) {
                val d = hypot(xs[k] - x, ys[k] - y)
                if (d <= dm) { dm = d; mejor = doubleArrayOf(xs[k].toDouble(), ys[k].toDouble()) }
            }
        }
        return mejor
    }

    companion object {
        private const val LADO = 256

        fun de(m: ModeloCad): Enganches {
            val puntos = FloatArrayList(m.vertices.cuantos + m.arcos.cuantos * 5)
            for (k in 0 until m.vertices.cuantos) puntos.add(m.vertices.real(k, 0), m.vertices.real(k, 1))
            for (k in 0 until m.arcos.cuantos) {
                val cx = m.arcos.real(k, 0); val cy = m.arcos.real(k, 1); val r = m.arcos.real(k, 2)
                val a0 = m.arcos.real(k, 3); val b = m.arcos.real(k, 4)
                puntos.add(cx, cy)
                if (b < 6.28f) for (a in floatArrayOf(a0, a0 + b)) puntos.add(cx + r * kotlin.math.cos(a), cy + r * kotlin.math.sin(a))
                else for (q in 0 until 4) {
                    val a = q * (Math.PI / 2).toFloat()
                    puntos.add(cx + r * kotlin.math.cos(a), cy + r * kotlin.math.sin(a))
                }
            }
            return de(m.caja, puntos.n, puntos.xs, puntos.ys)
        }

        /** Con los puntos ya sacados (lo que se prueba). */
        fun de(caja: FloatArray, n: Int, px: FloatArray, py: FloatArray): Enganches {
            val lado = maxOf(caja[2] - caja[0], caja[3] - caja[1]).toDouble().coerceAtLeast(1e-6)
            val celda = lado / LADO * 1.0001
            val x0 = caja[0].toDouble(); val y0 = caja[1].toDouble()
            fun celdaDe(k: Int): Int {
                val i = floor((px[k] - x0) / celda).toInt().coerceIn(0, LADO - 1)
                val j = floor((py[k] - y0) / celda).toInt().coerceIn(0, LADO - 1)
                return j * LADO + i
            }
            val inicio = IntArray(LADO * LADO + 1)
            for (k in 0 until n) inicio[celdaDe(k) + 1]++
            for (c in 1..LADO * LADO) inicio[c] += inicio[c - 1]
            val puesto = inicio.copyOf()
            val xs = FloatArray(n); val ys = FloatArray(n)
            for (k in 0 until n) {
                val c = celdaDe(k)
                val i = puesto[c]++
                xs[i] = px[k]; ys[i] = py[k]
            }
            return Enganches(x0, y0, celda, inicio, xs, ys)
        }
    }

    private class FloatArrayList(capacidad: Int) {
        var xs = FloatArray(capacidad.coerceAtLeast(8)); var ys = FloatArray(capacidad.coerceAtLeast(8)); var n = 0
        fun add(x: Float, y: Float) {
            if (!x.isFinite() || !y.isFinite()) return
            if (n == xs.size) { xs = xs.copyOf(n * 2); ys = ys.copyOf(n * 2) }
            xs[n] = x; ys[n] = y; n++
        }
    }
}

/** Las medidas de las cotas, como en el PC (`sufijo` y `medida` de su visor). */
object Medidas {
    fun sufijo(unidades: Int): String = when (unidades) {
        1 -> " in"
        2 -> " ft"
        4 -> " mm"
        5 -> " cm"
        6 -> " m"
        7 -> " km"
        14 -> " dm"
        else -> ""
    }

    /** Con sus decimales justos y su unidad; con punto decimal, como en el PC. */
    fun medida(d: Double, unidades: Int): String {
        val dec = when {
            d >= 1000 -> 1
            d >= 100 -> 2
            else -> 3
        }
        return String.format(java.util.Locale.ROOT, "%.${dec}f", d) + sufijo(unidades)
    }
}

/**
 * **Agarrar y mover los puntos de las cotas** (9-oct-2026). Puro, sin Compose, para probarlo.
 * Las cadenas son listas de puntos del plano; la última es la que se está poniendo.
 */
object Acotar {
    /**
     * El punto (cadena, índice) más cercano a [mira] (en la pantalla) a menos de [radio] píxeles,
     * o null. [aPantalla] pasa un punto del plano a la pantalla. Si dos están igual de cerca, gana
     * el último puesto: es el que se acaba de poner mal.
     */
    fun agarrar(
        cadenas: List<List<DoubleArray>>, mira: androidx.compose.ui.geometry.Offset, radio: Float,
        aPantalla: (DoubleArray) -> androidx.compose.ui.geometry.Offset
    ): Pair<Int, Int>? {
        var mejor: Pair<Int, Int>? = null
        var dm = radio
        cadenas.forEachIndexed { k, cadena ->
            cadena.forEachIndexed { i, p ->
                val d = (aPantalla(p) - mira).getDistance()
                if (d <= dm) { dm = d; mejor = k to i }
            }
        }
        return mejor
    }

    /** Las cadenas con el punto [i] de la [k] puesto en [donde]. */
    fun mover(cadenas: List<List<DoubleArray>>, k: Int, i: Int, donde: DoubleArray): List<List<DoubleArray>> =
        cadenas.mapIndexed { kk, c -> if (kk != k) c else c.mapIndexed { ii, p -> if (ii == i) donde.copyOf() else p } }
}

/**
 * **Engancharse a las rayas, no solo a sus puntas** (9-oct-2026, el usuario: «que la regla se
 * enganche a una parte de la línea, y que reconozca los ángulos rectos»). Los tramos rectos del
 * plano (las tiras de rayas partidas en segmentos) repartidos en una rejilla de 256 × 256 —cada
 * uno en las celdas que pisa su caja; los que pisan demasiadas, aparte, que son pocos— y los
 * círculos y arcos. Dice el punto de raya más cercano y el pie de la perpendicular desde un punto.
 */
class Rayas private constructor(
    private val x0: Double, private val y0: Double, private val celda: Double,
    private val inicio: IntArray, private val enCelda: IntArray, private val grandes: IntArray,
    private val ax: FloatArray, private val ay: FloatArray, private val bx: FloatArray, private val by: FloatArray,
    /** Círculos y arcos: centro, radio, inicio, barrido. */
    private val arcos: FloatArray,
) {
    /** Un enganche: dónde y de qué clase. */
    class Ajuste(val x: Double, val y: Double, val tipo: Tipo)
    enum class Tipo { PUNTO, PERPENDICULAR, EN_LA_RAYA, LIBRE }

    private fun candidatos(x: Double, y: Double, radio: Double, haz: (Int) -> Unit) {
        val n = kotlin.math.ceil(radio / celda).toInt().coerceAtMost(8)
        val cx = floor((x - x0) / celda).toInt(); val cy = floor((y - y0) / celda).toInt()
        for (j in (cy - n).coerceAtLeast(0)..(cy + n).coerceAtMost(LADO - 1)) for (i in (cx - n).coerceAtLeast(0)..(cx + n).coerceAtMost(LADO - 1)) {
            val c = j * LADO + i
            for (k in inicio[c] until inicio[c + 1]) haz(enCelda[k])
        }
        for (s in grandes) haz(s)
    }

    /** El segmento más cercano a (x, y) a menos de [radio], y el punto suyo más cercano; o null. */
    fun cercano(x: Double, y: Double, radio: Double): Pair<Int, DoubleArray>? {
        var mejor: Pair<Int, DoubleArray>? = null
        var dm = radio
        candidatos(x, y, radio) { s ->
            val p = Geometria.alSegmento(x, y, ax[s].toDouble(), ay[s].toDouble(), bx[s].toDouble(), by[s].toDouble())
            val d = hypot(p[0] - x, p[1] - y)
            if (d <= dm) { dm = d; mejor = s to p }
        }
        return mejor
    }

    /** El punto de círculo o arco más cercano a (x, y) a menos de [radio], o null. */
    fun enArco(x: Double, y: Double, radio: Double): DoubleArray? {
        var mejor: DoubleArray? = null
        var dm = radio
        var k = 0
        while (k + 4 < arcos.size) {
            val cx = arcos[k].toDouble(); val cy = arcos[k + 1].toDouble(); val r = arcos[k + 2].toDouble()
            val a0 = arcos[k + 3].toDouble(); val b = arcos[k + 4].toDouble()
            k += 5
            val d0 = hypot(x - cx, y - cy)
            if (kotlin.math.abs(d0 - r) > dm || d0 < 1e-12) continue
            var ang = kotlin.math.atan2(y - cy, x - cx)
            if (b < 6.2831) {
                var rel = ang - a0
                rel -= floor(rel / (2 * Math.PI)) * 2 * Math.PI
                if (rel > b) continue
            }
            val p = doubleArrayOf(cx + r * kotlin.math.cos(ang), cy + r * kotlin.math.sin(ang))
            val d = hypot(p[0] - x, p[1] - y)
            if (d <= dm) { dm = d; mejor = p }
        }
        return mejor
    }

    /**
     * El enganche para la mira en (x, y) con el punto anterior [desde] (o null), como AutoCAD:
     * primero un punto ([puntos]: vértice, extremo, centro), luego la **perpendicular** desde
     * [desde] a la raya bajo la mira (si su pie cae en la raya y cerca de la mira), luego el punto
     * más cercano de una raya o un arco. Si nada, libre.
     */
    fun ajustar(x: Double, y: Double, radio: Double, desde: DoubleArray?, puntos: Enganches?): Ajuste {
        puntos?.cerca(x, y, radio)?.let { return Ajuste(it[0], it[1], Tipo.PUNTO) }
        val cerca = cercano(x, y, radio)
        if (cerca != null && desde != null) {
            val s = cerca.first
            val pie = Geometria.pieDePerpendicular(desde[0], desde[1], ax[s].toDouble(), ay[s].toDouble(), bx[s].toDouble(), by[s].toDouble())
            if (pie != null && hypot(pie[0] - x, pie[1] - y) <= radio * 1.5 && hypot(pie[0] - desde[0], pie[1] - desde[1]) > 1e-9) {
                return Ajuste(pie[0], pie[1], Tipo.PERPENDICULAR)
            }
        }
        val arco = enArco(x, y, radio)
        if (cerca != null && (arco == null || hypot(cerca.second[0] - x, cerca.second[1] - y) <= hypot(arco[0] - x, arco[1] - y))) {
            return Ajuste(cerca.second[0], cerca.second[1], Tipo.EN_LA_RAYA)
        }
        if (arco != null) return Ajuste(arco[0], arco[1], Tipo.EN_LA_RAYA)
        return Ajuste(x, y, Tipo.LIBRE)
    }

    companion object {
        private const val LADO = 256
        private const val TOPE_DE_CELDAS = 64

        fun de(m: ModeloCad): Rayas {
            // Los segmentos de las tiras (los dos vértices seguidos de una tira).
            val n = m.lineas.cuantos
            var cuantos = 0
            run {
                var antes = ModeloCad.CORTE
                for (i in 0 until n) { val v = m.lineas.palabra(i, 0); if (v != ModeloCad.CORTE && antes != ModeloCad.CORTE) cuantos++; antes = v }
            }
            val ax = FloatArray(cuantos); val ay = FloatArray(cuantos); val bx = FloatArray(cuantos); val by = FloatArray(cuantos)
            run {
                var antes = ModeloCad.CORTE; var k = 0
                for (i in 0 until n) {
                    val v = m.lineas.palabra(i, 0)
                    if (v != ModeloCad.CORTE && antes != ModeloCad.CORTE) {
                        ax[k] = m.vertices.real(antes, 0); ay[k] = m.vertices.real(antes, 1)
                        bx[k] = m.vertices.real(v, 0); by[k] = m.vertices.real(v, 1); k++
                    }
                    antes = v
                }
            }
            val arcos = FloatArray(m.arcos.cuantos * 5) { m.arcos.real(it / 5, it % 5) }
            return de(m.caja, ax, ay, bx, by, arcos)
        }

        fun de(caja: FloatArray, ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray, arcos: FloatArray = FloatArray(0)): Rayas {
            val lado = maxOf(caja[2] - caja[0], caja[3] - caja[1]).toDouble().coerceAtLeast(1e-6)
            val celda = lado / LADO * 1.0001
            val x0 = caja[0].toDouble(); val y0 = caja[1].toDouble()
            fun c(v: Float, o: Double) = floor((v - o) / celda).toInt().coerceIn(0, LADO - 1)
            val n = ax.size
            val inicio = IntArray(LADO * LADO + 1)
            val grandes = ArrayList<Int>()
            fun recorrer(s: Int, haz: (Int) -> Unit): Boolean {
                val i0 = c(minOf(ax[s], bx[s]), x0); val i1 = c(maxOf(ax[s], bx[s]), x0)
                val j0 = c(minOf(ay[s], by[s]), y0); val j1 = c(maxOf(ay[s], by[s]), y0)
                if ((i1 - i0 + 1).toLong() * (j1 - j0 + 1) > TOPE_DE_CELDAS) return false
                for (j in j0..j1) for (i in i0..i1) haz(j * LADO + i)
                return true
            }
            for (s in 0 until n) if (!recorrer(s) { inicio[it + 1]++ }) grandes += s
            for (k in 1..LADO * LADO) inicio[k] += inicio[k - 1]
            val puesto = inicio.copyOf()
            val enCelda = IntArray(inicio[LADO * LADO])
            for (s in 0 until n) recorrer(s) { enCelda[puesto[it]++] = s }
            return Rayas(x0, y0, celda, inicio, enCelda, grandes.toIntArray(), ax, ay, bx, by, arcos)
        }
    }
}

/** Cuentas de geometría de la regla, puras. */
object Geometria {
    /** El punto del segmento a–b más cercano a p. */
    fun alSegmento(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): DoubleArray {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-24) return doubleArrayOf(ax, ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return doubleArrayOf(ax + t * dx, ay + t * dy)
    }

    /** El pie de la perpendicular desde p a la recta a–b, si cae dentro del segmento (o null). */
    fun pieDePerpendicular(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): DoubleArray? {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-24) return null
        val t = ((px - ax) * dx + (py - ay) * dy) / l2
        if (t < -1e-9 || t > 1 + 1e-9) return null
        return doubleArrayOf(ax + t * dx, ay + t * dy)
    }
}
