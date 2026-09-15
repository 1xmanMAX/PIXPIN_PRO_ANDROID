package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **Restar un sólido de otro** (15-sep-2026): los huecos de puertas y ventanas de un modelo de
 * Revit. En el IFC un muro es un bloque entero y el hueco va aparte —un `IfcOpeningElement`, o
 * el segundo operando de un `IfcBooleanResult`—; sin restarlo, las ventanas salían tapadas por
 * el muro.
 *
 * Es el método de `csg.js` (Evan Wallace): cada sólido se parte en un árbol BSP por los planos
 * de sus caras, y cada árbol recorta los polígonos del otro. Funciona con cualquier sólido
 * cerrado y con las caras mirando hacia fuera —[solido] les da la vuelta si no— y es exacto
 * para lo que exporta Revit, que son casi siempre prismas.
 *
 * **Sin recursión**: la de `csg.js` baja un nivel por plano, y un sólido curvo tiene cientos;
 * en un hilo de Android eso revienta la pila. Aquí cada recorrido lleva su propia pila.
 *
 * Un polígono es convexo, en metros, con un color: el de la cara de la que sale.
 */
object Csg {

    /** Más cerca que esto de un plano es estar en él. En metros: una centésima de milímetro. */
    private const val EPS = 1e-5

    class Poligono(val v: DoubleArray, val color: Int) {
        val nx: Double; val ny: Double; val nz: Double; val w: Double
        init {
            // La normal por Newell: aguanta puntos casi alineados.
            var x = 0.0; var y = 0.0; var z = 0.0
            val n = v.size / 3
            for (i in 0 until n) {
                val j = (i + 1) % n
                val xi = v[i * 3]; val yi = v[i * 3 + 1]; val zi = v[i * 3 + 2]
                val xj = v[j * 3]; val yj = v[j * 3 + 1]; val zj = v[j * 3 + 2]
                x += (yi - yj) * (zi + zj); y += (zi - zj) * (xi + xj); z += (xi - xj) * (yi + yj)
            }
            val l = sqrt(x * x + y * y + z * z)
            if (l > 1e-18) { x /= l; y /= l; z /= l }
            nx = x; ny = y; nz = z
            w = x * v[0] + y * v[1] + z * v[2]
        }
        val valido: Boolean get() = nx != 0.0 || ny != 0.0 || nz != 0.0
        fun volteado(): Poligono {
            val n = v.size / 3
            val r = DoubleArray(v.size)
            for (i in 0 until n) { val k = n - 1 - i; r[i * 3] = v[k * 3]; r[i * 3 + 1] = v[k * 3 + 1]; r[i * 3 + 2] = v[k * 3 + 2] }
            return Poligono(r, color)
        }
        fun conColor(c: Int) = Poligono(v, c)
    }

    private class Plano(var nx: Double, var ny: Double, var nz: Double, var w: Double) {
        fun voltear() { nx = -nx; ny = -ny; nz = -nz; w = -w }
    }

    private class Nodo {
        var plano: Plano? = null
        var delante: Nodo? = null
        var detras: Nodo? = null
        val poligonos = ArrayList<Poligono>()
    }

    // ---- De mallas a sólidos y vuelta ----

    /**
     * Los triángulos [desde]–[hasta] de una malla como sólido, **con las caras hacia fuera**: si
     * el volumen con signo sale negativo es que vienen al revés, y se les da la vuelta a todas.
     */
    fun solido(vertices: FloatArray, triangulos: IntArray, colores: IntArray, desde: Int = 0, hasta: Int = triangulos.size / 3): List<Poligono> {
        val r = ArrayList<Poligono>(hasta - desde)
        var volumen = 0.0
        for (t in desde until hasta) {
            val a = triangulos[t * 3] * 3; val b = triangulos[t * 3 + 1] * 3; val c = triangulos[t * 3 + 2] * 3
            val v = doubleArrayOf(
                vertices[a].toDouble(), vertices[a + 1].toDouble(), vertices[a + 2].toDouble(),
                vertices[b].toDouble(), vertices[b + 1].toDouble(), vertices[b + 2].toDouble(),
                vertices[c].toDouble(), vertices[c + 1].toDouble(), vertices[c + 2].toDouble()
            )
            val p = Poligono(v, colores.getOrElse(t) { 0 })
            if (!p.valido) continue
            volumen += v[0] * (v[4] * v[8] - v[5] * v[7]) - v[1] * (v[3] * v[8] - v[5] * v[6]) + v[2] * (v[3] * v[7] - v[4] * v[6])
            r += p
        }
        return if (volumen < 0) r.map { it.volteado() } else r
    }

    fun solido(m: Malla3D): List<Poligono> = solido(m.vertices, m.triangulos, m.colores)

    /** Polígonos sueltos (una caja, un prisma) con las caras hacia fuera, por su volumen. */
    fun orientado(pols: List<Poligono>): List<Poligono> {
        var volumen = 0.0
        for (p in pols) {
            val v = p.v
            for (i in 1 until v.size / 3 - 1) {
                val b = i * 3; val c = (i + 1) * 3
                volumen += v[0] * (v[b + 1] * v[c + 2] - v[b + 2] * v[c + 1]) - v[1] * (v[b] * v[c + 2] - v[b + 2] * v[c]) + v[2] * (v[b] * v[c + 1] - v[b + 1] * v[c])
            }
        }
        return if (volumen < 0) pols.map { it.volteado() } else pols
    }

    /** Los polígonos a la malla que se está armando, en abanico (son convexos). */
    fun volcar(pols: List<Poligono>, salida: Malla3D.Constructor) {
        for (p in pols) {
            val v = p.v
            val n = v.size / 3
            if (n < 3) continue
            val base = salida.cuantosVertices
            for (i in 0 until n) salida.vertice(v[i * 3], v[i * 3 + 1], v[i * 3 + 2])
            for (i in 1 until n - 1) salida.triangulo(base, base + i, base + i + 1, p.color)
        }
    }

    /** La caja `minX, minY, minZ, maxX, maxY, maxZ` de unos polígonos. */
    fun caja(pols: List<Poligono>): DoubleArray {
        val c = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (p in pols) {
            val v = p.v
            var i = 0
            while (i < v.size) {
                if (v[i] < c[0]) c[0] = v[i]; if (v[i] > c[3]) c[3] = v[i]
                if (v[i + 1] < c[1]) c[1] = v[i + 1]; if (v[i + 1] > c[4]) c[4] = v[i + 1]
                if (v[i + 2] < c[2]) c[2] = v[i + 2]; if (v[i + 2] > c[5]) c[5] = v[i + 2]
                i += 3
            }
        }
        return c
    }

    fun seTocan(a: DoubleArray, b: DoubleArray): Boolean =
        a[0] <= b[3] + EPS && b[0] <= a[3] + EPS && a[1] <= b[4] + EPS && b[1] <= a[4] + EPS && a[2] <= b[5] + EPS && b[2] <= a[5] + EPS

    // ---- Las operaciones ----

    /** [a] menos [b]. Las caras que el corte abre en [a] salen del color [colorDelCorte]. */
    fun restar(a: List<Poligono>, b: List<Poligono>, colorDelCorte: Int? = null): List<Poligono> {
        if (a.isEmpty() || b.isEmpty() || !seTocan(caja(a), caja(b))) return a
        val bb = if (colorDelCorte != null) b.map { it.conColor(colorDelCorte) } else b
        val na = construir(a); val nb = construir(bb)
        invertir(na)
        recortarCon(na, nb)
        recortarCon(nb, na)
        invertir(nb)
        recortarCon(nb, na)
        invertir(nb)
        anadir(na, todos(nb))
        invertir(na)
        return todos(na)
    }

    /** Lo que [a] y [b] tienen en común. */
    fun intersecar(a: List<Poligono>, b: List<Poligono>): List<Poligono> {
        if (a.isEmpty() || b.isEmpty() || !seTocan(caja(a), caja(b))) return emptyList()
        val na = construir(a); val nb = construir(b)
        invertir(na)
        recortarCon(nb, na)
        invertir(nb)
        recortarCon(na, nb)
        recortarCon(nb, na)
        anadir(na, todos(nb))
        invertir(na)
        return todos(na)
    }

    // ---- El árbol ----

    private fun construir(pols: List<Poligono>): Nodo {
        val raiz = Nodo()
        anadir(raiz, pols)
        return raiz
    }

    private fun anadir(raiz: Nodo, pols: List<Poligono>) {
        if (pols.isEmpty()) return
        val pila = ArrayDeque<Pair<Nodo, List<Poligono>>>()
        pila.addLast(raiz to pols)
        val delante = ArrayList<Poligono>(); val detras = ArrayList<Poligono>()
        while (pila.isNotEmpty()) {
            val (nodo, lista) = pila.removeLast()
            if (lista.isEmpty()) continue
            val pl = nodo.plano ?: lista[0].let { Plano(it.nx, it.ny, it.nz, it.w) }.also { nodo.plano = it }
            delante.clear(); detras.clear()
            for (p in lista) partir(pl, p, nodo.poligonos, nodo.poligonos, delante, detras)
            if (delante.isNotEmpty()) pila.addLast((nodo.delante ?: Nodo().also { nodo.delante = it }) to ArrayList(delante))
            if (detras.isNotEmpty()) pila.addLast((nodo.detras ?: Nodo().also { nodo.detras = it }) to ArrayList(detras))
        }
    }

    private fun nodos(raiz: Nodo): List<Nodo> {
        val r = ArrayList<Nodo>()
        val pila = ArrayDeque<Nodo>()
        pila.addLast(raiz)
        while (pila.isNotEmpty()) {
            val n = pila.removeLast()
            r += n
            n.delante?.let { pila.addLast(it) }
            n.detras?.let { pila.addLast(it) }
        }
        return r
    }

    private fun invertir(raiz: Nodo) {
        for (n in nodos(raiz)) {
            for (i in n.poligonos.indices) n.poligonos[i] = n.poligonos[i].volteado()
            n.plano?.voltear()
            val t = n.delante; n.delante = n.detras; n.detras = t
        }
    }

    private fun todos(raiz: Nodo): List<Poligono> = nodos(raiz).flatMap { it.poligonos }

    /** Lo de [pols] que queda fuera del sólido de [raiz]. */
    private fun recortar(raiz: Nodo, pols: List<Poligono>): List<Poligono> {
        val salida = ArrayList<Poligono>()
        val pila = ArrayDeque<Pair<Nodo, List<Poligono>>>()
        pila.addLast(raiz to pols)
        while (pila.isNotEmpty()) {
            val (nodo, lista) = pila.removeLast()
            val pl = nodo.plano
            if (pl == null) { salida.addAll(lista); continue }
            val delante = ArrayList<Poligono>(); val detras = ArrayList<Poligono>()
            for (p in lista) partir(pl, p, delante, detras, delante, detras)
            val d = nodo.delante
            if (d != null) pila.addLast(d to delante) else salida.addAll(delante)
            val t = nodo.detras
            if (t != null) pila.addLast(t to detras)
            // Sin nada detrás, lo de detrás está dentro del sólido: se va.
        }
        return salida
    }

    private fun recortarCon(esto: Nodo, otro: Nodo) {
        for (n in nodos(esto)) {
            val quedan = recortar(otro, n.poligonos)
            n.poligonos.clear(); n.poligonos.addAll(quedan)
        }
    }

    /** Parte [p] por [pl], echando cada trozo a su lista. */
    private fun partir(
        pl: Plano, p: Poligono,
        coplanarDelante: MutableList<Poligono>, coplanarDetras: MutableList<Poligono>,
        delante: MutableList<Poligono>, detras: MutableList<Poligono>
    ) {
        val v = p.v
        val n = v.size / 3
        var tipo = 0
        val tipos = IntArray(n)
        for (i in 0 until n) {
            val t = pl.nx * v[i * 3] + pl.ny * v[i * 3 + 1] + pl.nz * v[i * 3 + 2] - pl.w
            val ti = if (t < -EPS) 2 else if (t > EPS) 1 else 0
            tipo = tipo or ti
            tipos[i] = ti
        }
        when (tipo) {
            0 -> (if (pl.nx * p.nx + pl.ny * p.ny + pl.nz * p.nz > 0) coplanarDelante else coplanarDetras).add(p)
            1 -> delante.add(p)
            2 -> detras.add(p)
            else -> {
                val f = ArrayList<Double>(v.size + 6); val b = ArrayList<Double>(v.size + 6)
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val ti = tipos[i]; val tj = tipos[j]
                    val xi = v[i * 3]; val yi = v[i * 3 + 1]; val zi = v[i * 3 + 2]
                    if (ti != 2) { f += xi; f += yi; f += zi }
                    if (ti != 1) { b += xi; b += yi; b += zi }
                    if ((ti or tj) == 3) {
                        val xj = v[j * 3]; val yj = v[j * 3 + 1]; val zj = v[j * 3 + 2]
                        val den = pl.nx * (xj - xi) + pl.ny * (yj - yi) + pl.nz * (zj - zi)
                        val s = if (abs(den) < 1e-300) 0.0 else (pl.w - (pl.nx * xi + pl.ny * yi + pl.nz * zi)) / den
                        val x = xi + (xj - xi) * s; val y = yi + (yj - yi) * s; val z = zi + (zj - zi) * s
                        f += x; f += y; f += z
                        b += x; b += y; b += z
                    }
                }
                if (f.size >= 9) delante.add(Poligono(f.toDoubleArray(), p.color))
                if (b.size >= 9) detras.add(Poligono(b.toDoubleArray(), p.color))
            }
        }
    }

    // ---- Sólidos de apoyo ----

    /** Una caja alineada con los ejes locales de [m] (4×4 en filas), de [x0]…[z1]. */
    fun caja(m: DoubleArray, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, color: Int): List<Poligono> {
        fun p(x: Double, y: Double, z: Double) = doubleArrayOf(
            m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11]
        )
        fun cara(vararg q: DoubleArray) = Poligono(DoubleArray(q.size * 3) { q[it / 3][it % 3] }, color)
        val a = p(x0, y0, z0); val b = p(x1, y0, z0); val c = p(x1, y1, z0); val d = p(x0, y1, z0)
        val e = p(x0, y0, z1); val f = p(x1, y0, z1); val g = p(x1, y1, z1); val h = p(x0, y1, z1)
        return orientado(listOf(
            cara(a, d, c, b), cara(e, f, g, h), cara(a, b, f, e), cara(c, d, h, g), cara(b, c, g, f), cara(d, a, e, h)
        ).filter { it.valido })
    }

    /** Un prisma de base [contorno] (x, y seguidos, en los ejes de [m]) entre [z0] y [z1]. */
    fun prisma(m: DoubleArray, contorno: DoubleArray, z0: Double, z1: Double, color: Int): List<Poligono> {
        val n = contorno.size / 2
        if (n < 3) return emptyList()
        fun p(x: Double, y: Double, z: Double, o: DoubleArray, k: Int) {
            o[k] = m[0] * x + m[1] * y + m[2] * z + m[3]; o[k + 1] = m[4] * x + m[5] * y + m[6] * z + m[7]; o[k + 2] = m[8] * x + m[9] * y + m[10] * z + m[11]
        }
        val r = ArrayList<Poligono>()
        // Las tapas pueden ser cóncavas, y los polígonos del árbol tienen que ser convexos: en triángulos.
        val plano = DoubleArray(n * 3).also { for (i in 0 until n) { it[i * 3] = contorno[i * 2]; it[i * 3 + 1] = contorno[i * 2 + 1] } }
        val tri = Triangular.poligono(plano)
        var t = 0
        while (t < tri.size) {
            val abajo = DoubleArray(9); val arriba = DoubleArray(9)
            for (k in 0 until 3) {
                val i = tri[t + k]
                p(contorno[i * 2], contorno[i * 2 + 1], z0, abajo, (2 - k) * 3)
                p(contorno[i * 2], contorno[i * 2 + 1], z1, arriba, k * 3)
            }
            r += Poligono(abajo, color); r += Poligono(arriba, color)
            t += 3
        }
        for (i in 0 until n) {
            val j = (i + 1) % n
            val q = DoubleArray(12)
            p(contorno[i * 2], contorno[i * 2 + 1], z0, q, 0)
            p(contorno[j * 2], contorno[j * 2 + 1], z0, q, 3)
            p(contorno[j * 2], contorno[j * 2 + 1], z1, q, 6)
            p(contorno[i * 2], contorno[i * 2 + 1], z1, q, 9)
            r += Poligono(q, color)
        }
        // Cada cara, hacia fuera por su cuenta no: el contorno puede venir en cualquier sentido,
        // y tapas y paredes cambian a la vez. Se decide por el volumen del conjunto.
        return orientado(r.filter { it.valido })
    }

    /** El volumen de un sólido cerrado con las caras hacia fuera. Para las pruebas. */
    fun volumen(pols: List<Poligono>): Double {
        var s = 0.0
        for (p in pols) {
            val v = p.v
            for (i in 1 until v.size / 3 - 1) {
                val b = i * 3; val c = (i + 1) * 3
                s += v[0] * (v[b + 1] * v[c + 2] - v[b + 2] * v[c + 1]) - v[1] * (v[b] * v[c + 2] - v[b + 2] * v[c]) + v[2] * (v[b] * v[c + 1] - v[b + 1] * v[c])
            }
        }
        return s / 6
    }
}
