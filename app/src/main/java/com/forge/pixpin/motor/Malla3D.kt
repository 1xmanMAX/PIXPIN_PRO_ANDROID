package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **Un modelo 3D importado, como triángulos** (14-sep-2026): lo que sale de leer un IFC de
 * Revit, un OBJ o un glTF, ya en metros y con la `z` hacia arriba, que es el convenio del
 * croquis 3D.
 *
 * Va en arrays planos y no en objetos por triángulo a propósito: un edificio de Revit trae
 * cientos de miles, y un objeto por triángulo son decenas de megas de más y un recorrido que
 * el recolector de basura se come en cada fotograma. Ver [pixpin-js-en-bucles]: lo mismo vale
 * aquí.
 *
 * - [vertices]: `x, y, z` seguidos, tres por vértice.
 * - [triangulos]: tres índices de vértice por triángulo.
 * - [colores]: un ARGB por triángulo.
 * - [piezas]: qué triángulos son de qué elemento (un muro, una losa…), en tramos seguidos.
 */
class Malla3D(
    val vertices: FloatArray,
    val triangulos: IntArray,
    val colores: IntArray,
    val piezas: List<Pieza>
) {
    class Pieza(val nombre: String, val tipo: String, val desde: Int, val hasta: Int)

    val cuantosTriangulos: Int get() = triangulos.size / 3

    /** La caja que lo encierra: `minX, minY, minZ, maxX, maxY, maxZ`. */
    fun caja(): DoubleArray {
        if (vertices.isEmpty()) return DoubleArray(6)
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = Float.MAX_VALUE
        var d = -Float.MAX_VALUE; var e = -Float.MAX_VALUE; var f = -Float.MAX_VALUE
        var i = 0
        val v = vertices
        while (i < v.size) {
            val x = v[i]; val y = v[i + 1]; val z = v[i + 2]
            if (x < a) a = x; if (y < b) b = y; if (z < c) c = z
            if (x > d) d = x; if (y > e) e = y; if (z > f) f = z
            i += 3
        }
        return doubleArrayOf(a.toDouble(), b.toDouble(), c.toDouble(), d.toDouble(), e.toDouble(), f.toDouble())
    }

    /**
     * **En disco, en binario y a pelo**: cuatro bytes por número. Un modelo de Revit en JSON
     * serían diez veces más y un análisis de texto de varios segundos cada vez que se abre el
     * croquis; así se lee con una sola copia de memoria.
     */
    fun guardar(archivo: java.io.File) {
        val tmp = java.io.File(archivo.parentFile, archivo.name + ".tmp")
        java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
            o.writeInt(MAGICO); o.writeInt(1)
            o.writeInt(vertices.size); o.writeInt(triangulos.size); o.writeInt(colores.size); o.writeInt(piezas.size)
            val buf = java.nio.ByteBuffer.allocate(maxOf(vertices.size, triangulos.size, colores.size) * 4)
            buf.clear(); buf.asFloatBuffer().put(vertices); o.write(buf.array(), 0, vertices.size * 4)
            buf.clear(); buf.asIntBuffer().put(triangulos); o.write(buf.array(), 0, triangulos.size * 4)
            buf.clear(); buf.asIntBuffer().put(colores); o.write(buf.array(), 0, colores.size * 4)
            for (p in piezas) { o.writeUTF(p.nombre.take(200)); o.writeUTF(p.tipo.take(80)); o.writeInt(p.desde); o.writeInt(p.hasta) }
        }
        if (!tmp.renameTo(archivo)) { tmp.copyTo(archivo, overwrite = true); tmp.delete() }
    }

    companion object {
        private const val MAGICO = 0x50584D33 // "PXM3"

        fun cargar(archivo: java.io.File): Malla3D? = runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(archivo.inputStream(), 1 shl 16)).use { i ->
                if (i.readInt() != MAGICO) return null
                i.readInt()
                val nv = i.readInt(); val nt = i.readInt(); val nc = i.readInt(); val np = i.readInt()
                val bytes = ByteArray(maxOf(nv, nt, nc) * 4)
                val buf = java.nio.ByteBuffer.wrap(bytes)
                i.readFully(bytes, 0, nv * 4); buf.clear(); val v = FloatArray(nv).also { buf.asFloatBuffer().get(it) }
                i.readFully(bytes, 0, nt * 4); buf.clear(); val t = IntArray(nt).also { buf.asIntBuffer().get(it) }
                i.readFully(bytes, 0, nc * 4); buf.clear(); val c = IntArray(nc).also { buf.asIntBuffer().get(it) }
                val piezas = List(np) { Pieza(i.readUTF(), i.readUTF(), i.readInt(), i.readInt()) }
                Malla3D(v, t, c, piezas)
            }
        }.getOrNull()
    }

    /** Lo que se va juntando mientras se lee. */
    class Constructor {
        private var v = FloatArray(3 * 4096)
        private var nv = 0
        private var t = IntArray(3 * 4096)
        private var nt = 0
        private var c = IntArray(4096)
        val piezas = ArrayList<Pieza>()
        private var piezaDesde = 0

        val cuantosVertices get() = nv / 3
        val cuantosTriangulos get() = nt / 3

        fun vertice(x: Double, y: Double, z: Double): Int {
            if (nv + 3 > v.size) v = v.copyOf(v.size * 2)
            v[nv] = x.toFloat(); v[nv + 1] = y.toFloat(); v[nv + 2] = z.toFloat()
            nv += 3
            return nv / 3 - 1
        }

        fun triangulo(a: Int, b: Int, cc: Int, color: Int) {
            if (a == b || b == cc || a == cc) return
            if (nt + 3 > t.size) t = t.copyOf(t.size * 2)
            val k = nt / 3
            if (k >= c.size) c = c.copyOf(c.size * 2)
            t[nt] = a; t[nt + 1] = b; t[nt + 2] = cc
            c[k] = color
            nt += 3
        }

        /** Cierra el elemento en curso: sus triángulos van desde el último cierre hasta aquí. */
        fun cerrarPieza(nombre: String, tipo: String) {
            val hasta = nt / 3
            if (hasta > piezaDesde) piezas += Pieza(nombre, tipo, piezaDesde, hasta)
            piezaDesde = hasta
        }

        fun malla(): Malla3D = Malla3D(v.copyOf(nv), t.copyOf(nt), c.copyOf(nt / 3), piezas.toList())
    }
}

/**
 * **Triangular un polígono plano en el espacio, con agujeros.** Las caras de un sólido de
 * Revit no son triángulos: un muro con un hueco es una cara de ocho lados con un agujero.
 *
 * Se proyecta al plano en el que más se extiende (quitando la coordenada de la normal mayor)
 * y se corta por orejas; los agujeros se unen antes al contorno por el puente más corto que
 * no cruza nada, que es lo que hace `earcut` y la mayoría de visores.
 */
object Triangular {

    /**
     * [contorno] y cada uno de [agujeros]: puntos `x,y,z` seguidos. Devuelve los triángulos
     * como índices sobre la lista unida `contorno + agujeros[0] + agujeros[1] + …`.
     */
    fun poligono(contorno: DoubleArray, agujeros: List<DoubleArray> = emptyList()): IntArray {
        val n = contorno.size / 3
        if (n < 3) return IntArray(0)
        // La normal por Newell: robusta con polígonos cóncavos y con puntos casi alineados.
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val xi = contorno[i * 3]; val yi = contorno[i * 3 + 1]; val zi = contorno[i * 3 + 2]
            val xj = contorno[j * 3]; val yj = contorno[j * 3 + 1]; val zj = contorno[j * 3 + 2]
            nx += (yi - yj) * (zi + zj)
            ny += (zi - zj) * (xi + xj)
            nz += (xi - xj) * (yi + yj)
        }
        val ax = abs(nx); val ay = abs(ny); val az = abs(nz)
        // Qué dos coordenadas se quedan.
        val (u, w) = when {
            az >= ax && az >= ay -> 0 to 1
            ay >= ax -> 2 to 0
            else -> 1 to 2
        }
        val signo = when {
            az >= ax && az >= ay -> nz
            ay >= ax -> ny
            else -> nx
        }
        val todos = ArrayList<DoubleArray>(1 + agujeros.size)
        todos += contorno
        todos.addAll(agujeros.filter { it.size >= 9 })
        val total = todos.sumOf { it.size / 3 }
        val px = DoubleArray(total); val py = DoubleArray(total)
        var k = 0
        for (anillo in todos) {
            for (i in 0 until anillo.size / 3) {
                px[k] = anillo[i * 3 + u]; py[k] = anillo[i * 3 + w]; k++
            }
        }
        if (n == 3 && agujeros.isEmpty()) return intArrayOf(0, 1, 2)
        if (n == 4 && agujeros.isEmpty() && convexo4(px, py)) return intArrayOf(0, 1, 2, 0, 2, 3)
        val r = EarCut.cortar(px, py, todos.map { it.size / 3 })
        // **Con la misma orientación que el contorno de partida**: la proyección conserva el
        // sentido, así que cada triángulo tiene que girar hacia donde giraba el contorno.
        var area = 0.0
        for (i in 0 until n) { val j = (i + 1) % n; area += px[i] * py[j] - px[j] * py[i] }
        var i = 0
        while (i < r.size) {
            val a = r[i]; val b = r[i + 1]; val c = r[i + 2]
            val t = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
            if ((t > 0) != (area > 0)) { r[i + 1] = c; r[i + 2] = b }
            i += 3
        }
        @Suppress("UNUSED_VARIABLE") val nada = signo
        return r
    }

    private fun convexo4(x: DoubleArray, y: DoubleArray): Boolean {
        var pos = 0; var neg = 0
        for (i in 0 until 4) {
            val a = i; val b = (i + 1) % 4; val c = (i + 2) % 4
            val cr = (x[b] - x[a]) * (y[c] - y[b]) - (y[b] - y[a]) * (x[c] - x[b])
            if (cr > 1e-12) pos++ else if (cr < -1e-12) neg++
        }
        return pos == 0 || neg == 0
    }
}

/**
 * El corte por orejas con agujeros, sobre puntos 2D. [tamanos]: cuántos puntos tiene cada
 * anillo, el primero el contorno. [antihorario]: si el contorno va en sentido antihorario
 * visto desde su normal (para devolver los triángulos con la misma orientación).
 */
internal object EarCut {

    private class Nodo(val i: Int, val x: Double, val y: Double) {
        var prev: Nodo = this
        var next: Nodo = this
    }

    fun cortar(x: DoubleArray, y: DoubleArray, tamanos: List<Int>): IntArray {
        val salida = ArrayList<Int>()
        var inicio = 0
        var exterior = anillo(x, y, inicio, tamanos[0], true) ?: return IntArray(0)
        inicio += tamanos[0]
        if (tamanos.size > 1) {
            val cabezas = ArrayList<Nodo>()
            for (t in tamanos.drop(1)) {
                val h = anillo(x, y, inicio, t, false)
                inicio += t
                if (h != null) cabezas += izquierdo(h)
            }
            cabezas.sortBy { it.x }
            for (h in cabezas) {
                exterior = unirAgujero(h, exterior) ?: exterior
            }
        }
        orejas(exterior, salida)
        return salida.toIntArray()
    }

    private fun area(x: DoubleArray, y: DoubleArray, desde: Int, n: Int): Double {
        var s = 0.0
        for (i in 0 until n) {
            val a = desde + i; val b = desde + (i + 1) % n
            s += x[a] * y[b] - x[b] * y[a]
        }
        return s / 2
    }

    /** Un anillo como lista circular: el contorno antihorario y los agujeros horarios. */
    private fun anillo(x: DoubleArray, y: DoubleArray, desde: Int, n: Int, esContorno: Boolean): Nodo? {
        if (n < 3) return null
        val ccw = area(x, y, desde, n) > 0
        var ultimo: Nodo? = null
        val orden = if (ccw == esContorno) (0 until n) else (n - 1 downTo 0)
        for (k in orden) {
            val i = desde + k
            val nodo = Nodo(i, x[i], y[i])
            if (ultimo == null) { ultimo = nodo } else {
                nodo.next = ultimo.next; nodo.prev = ultimo
                ultimo.next.prev = nodo; ultimo.next = nodo
                ultimo = nodo
            }
        }
        var l = ultimo!!
        // Fuera los repetidos seguidos.
        var p = l; var vueltas = 0
        do {
            if (igual(p, p.next) && p.next !== p) { quitar(p.next); vueltas = 0 } else { p = p.next; vueltas++ }
        } while (vueltas < n + 2 && p.next !== p)
        l = p
        return if (l.next === l || l.next.next === l) null else l
    }

    private fun igual(a: Nodo, b: Nodo) = a.x == b.x && a.y == b.y
    private fun quitar(p: Nodo) { p.next.prev = p.prev; p.prev.next = p.next }

    private fun cruz(a: Nodo, b: Nodo, c: Nodo) = (b.y - a.y) * (c.x - b.x) - (b.x - a.x) * (c.y - b.y)

    private fun dentro(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, px: Double, py: Double) =
        (cx - px) * (ay - py) >= (ax - px) * (cy - py) &&
            (ax - px) * (by - py) >= (bx - px) * (ay - py) &&
            (bx - px) * (cy - py) >= (cx - px) * (by - py)

    private fun esOreja(e: Nodo): Boolean {
        val a = e.prev; val b = e; val c = e.next
        if (cruz(a, b, c) >= 0) return false
        var p = c.next
        while (p !== a) {
            if (dentro(a.x, a.y, b.x, b.y, c.x, c.y, p.x, p.y) && cruz(p.prev, p, p.next) >= 0) return false
            p = p.next
        }
        return true
    }

    private fun orejas(inicio: Nodo, salida: MutableList<Int>) {
        var oreja = inicio
        var parada = inicio
        var vueltasSinOreja = 0
        var fase = 0
        while (oreja.prev !== oreja.next) {
            val prev = oreja.prev; val next = oreja.next
            if (esOreja(oreja)) {
                salida += prev.i; salida += oreja.i; salida += next.i
                quitar(oreja)
                oreja = next.next
                parada = next.next
                vueltasSinOreja = 0
                continue
            }
            oreja = next
            if (oreja === parada) {
                vueltasSinOreja++
                if (fase == 0) {
                    // Quitar los puntos alineados y probar otra vez.
                    var p = oreja; var cambios = false
                    var n = 0
                    do {
                        if (cruz(p.prev, p, p.next) == 0.0 && p.next !== p.prev) { quitar(p); cambios = true; p = p.prev }
                        p = p.next; n++
                    } while (p !== oreja && n < 100000)
                    fase = 1
                    if (cambios) { parada = oreja; continue }
                }
                // Un polígono raro (se corta a sí mismo): se cierra en abanico lo que quede.
                val a = oreja
                var p = a.next
                while (p.next !== a) {
                    salida += a.i; salida += p.i; salida += p.next.i
                    p = p.next
                }
                return
            }
        }
    }

    private fun izquierdo(inicio: Nodo): Nodo {
        var p = inicio; var mejor = inicio
        do {
            if (p.x < mejor.x || (p.x == mejor.x && p.y < mejor.y)) mejor = p
            p = p.next
        } while (p !== inicio)
        return mejor
    }

    /** Une un agujero al contorno por un puente hacia la izquierda, como `earcut`. */
    private fun unirAgujero(agujero: Nodo, exterior: Nodo): Nodo? {
        val puente = buscarPuente(agujero, exterior) ?: return null
        val a = puente; val b = agujero
        val a2 = Nodo(a.i, a.x, a.y); val b2 = Nodo(b.i, b.x, b.y)
        val an = a.next; val bp = b.prev
        a.next = b; b.prev = a
        a2.next = an; an.prev = a2
        b2.next = a2; a2.prev = b2
        bp.next = b2; b2.prev = bp
        return exterior
    }

    private fun buscarPuente(h: Nodo, exterior: Nodo): Nodo? {
        var p = exterior
        val hx = h.x; val hy = h.y
        var qx = -Double.MAX_VALUE
        var m: Nodo? = null
        do {
            if (hy <= p.y && hy >= p.next.y && p.next.y != p.y) {
                val x = p.x + (hy - p.y) * (p.next.x - p.x) / (p.next.y - p.y)
                if (x <= hx && x > qx) {
                    qx = x
                    m = if (p.x < p.next.x) p else p.next
                    if (x == hx) return m
                }
            }
            p = p.next
        } while (p !== exterior)
        m ?: return null
        // El más cercano en ángulo dentro del triángulo (h, q, m), para no cruzar el contorno.
        val parada = m
        val mx = m.x; val my = m.y
        var mejor = Double.MAX_VALUE
        p = m
        do {
            if (hx >= p.x && p.x >= mx && hx != p.x &&
                dentro(if (hy < my) hx else qx, hy, mx, my, if (hy < my) qx else hx, hy, p.x, p.y)
            ) {
                val tan = abs(hy - p.y) / (hx - p.x)
                if (tan < mejor || (tan == mejor && p.x > m!!.x)) { m = p; mejor = tan }
            }
            p = p.next
        } while (p !== parada)
        return m
    }
}

/** Largo de un vector. */
internal fun largo3(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
