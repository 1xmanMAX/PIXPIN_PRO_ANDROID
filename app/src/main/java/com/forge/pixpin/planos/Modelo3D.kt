package com.forge.pixpin.planos

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * **Un modelo 3D ya leído** —un plano DWG/DXF visto en 3D, un LandXML o un fichero de puntos de
 * Civil 3D, un IFC o un Revit—, en el formato del visor de modelos del PC (PX3D v3,
 * `Modelo3d::a_bytes` de `crates/pixpin-cad/src/modelo3d.rs`). Lo escribe `libpixpincad.so` en el
 * proceso `:planos`; aquí se lee proyectado en memoria y se sube tal cual a la tarjeta.
 *
 * Un vértice son 24 bytes: posición (3 × f32, relativa a [origen]: un modelo georreferenciado está
 * a cientos de kilómetros del cero), normal (3 bytes con signo), color RGBA y el elemento del que
 * sale. Z es arriba.
 */
class Modelo3D private constructor(
    val origen: DoubleArray,
    /** Mínimo y máximo (x, y, z), relativos al origen. */
    val caja: FloatArray,
    val metros: Float,
    val vertices: ByteBuffer,
    val nVertices: Int,
    val opacos: ByteBuffer, val nOpacos: Int,
    val transparentes: ByteBuffer, val nTransparentes: Int,
    val aristas: ByteBuffer, val nAristas: Int,
    val lineas: ByteBuffer, val nLineas: Int,
    val puntos: ByteBuffer, val nPuntos: Int,
    /** De cada elemento: su clase (`IfcWall`, `Superficie`…), su nombre, su nivel y lo que mide. */
    val elementos: List<Elemento>,
    /** Los niveles (plantas), de abajo arriba; vacío si el modelo no los tiene. */
    val niveles: List<Nivel>,
) {
    /** Un elemento: [nivel] es su índice en [niveles] o -1. */
    data class Elemento(val tipo: String, val nombre: String, val nivel: Int = -1, val medidas: Medidas = Medidas())

    /**
     * Lo que mide un elemento, sacado de sus triángulos en el PC (`medir` de `modelo3d.rs`), en
     * unidades del modelo: el volumen (exacto solo si la malla es [cerrado]), el área en planta (lo
     * que mira arriba), la de una cara en alzado (lo vertical, entre dos) y toda la superficie.
     */
    data class Medidas(val volumen: Float = 0f, val planta: Float = 0f, val alzado: Float = 0f, val superficie: Float = 0f, val cerrado: Boolean = false)

    /** Un nivel: el `IfcBuildingStorey` o el Level de Revit, con su cota en metros. */
    data class Nivel(val nombre: String, val cota: Double)

    fun x(v: Int) = vertices.getFloat(v * 24)
    fun y(v: Int) = vertices.getFloat(v * 24 + 4)
    fun z(v: Int) = vertices.getFloat(v * 24 + 8)
    fun elemento(v: Int) = vertices.getInt(v * 24 + 20)

    val vacio: Boolean get() = nOpacos == 0 && nTransparentes == 0 && nLineas == 0 && nPuntos == 0

    /**
     * La caja de lo que importa (`caja_util` del PC): sin los pocos puntos sueltos muy lejos (una
     * raya con una cota absurda) que harían ver el resto diminuto. Del 0,5 % al 99,5 % por eje.
     */
    fun cajaUtil(): FloatArray {
        if (nVertices < 200) return caja
        val paso = (nVertices / 20_000).coerceAtLeast(1)
        val out = caja.copyOf()
        for (k in 0 until 3) {
            val v = FloatArray((nVertices + paso - 1) / paso)
            var n = 0
            var i = 0
            while (i < nVertices) { val f = vertices.getFloat(i * 24 + k * 4); if (f.isFinite()) v[n++] = f; i += paso }
            if (n < 100) continue
            val s = v.copyOf(n).also { it.sort() }
            val a = s[n / 200]; val b = s[n - 1 - n / 200]
            val m = (b - a) * 0.05f
            out[k] = maxOf(a - m, caja[k]); out[k + 3] = minOf(b + m, caja[k + 3])
        }
        return out
    }

    class NoSeLee(mensaje: String) : Exception(mensaje)

    companion object {
        private val MAGIA = byteArrayOf('P'.code.toByte(), 'X'.code.toByte(), '3'.code.toByte(), 'D'.code.toByte(), 0, 0, 0, LectorDePlanos.VERSION_3D.toByte())

        fun abrir(archivo: File): Modelo3D {
            val datos = RandomAccessFile(archivo, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            return deBytes(datos)
        }

        fun deBytes(b: ByteBuffer): Modelo3D {
            val d = b.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (d.remaining() < 8) throw NoSeLee("No es un modelo leído")
            for (i in 0 until 8) if (d.get(i) != MAGIA[i]) throw NoSeLee("No es un modelo leído (o es de otra versión)")
            var i = 8
            fun hay(n: Long) { if (i + n > d.limit()) throw NoSeLee("Modelo leído cortado") }
            fun u(): Int { hay(4); return d.getInt(i).also { i += 4 } }
            fun f(): Float { hay(4); return d.getFloat(i).also { i += 4 } }
            val origen = DoubleArray(3) { hay(8); d.getDouble(i).also { i += 8 } }
            val caja = FloatArray(6) { f() }
            val metros = f()
            val nv = u()
            if (nv < 0) throw NoSeLee("Modelo leído roto")
            hay(nv * 24L)
            val vertices = trozo(d, i, nv * 24); i += nv * 24
            fun lista(): Pair<ByteBuffer, Int> {
                val n = u()
                if (n < 0) throw NoSeLee("Modelo leído roto")
                hay(n * 4L)
                val t = trozo(d, i, n * 4)
                for (k in 0 until n) { val v = t.getInt(k * 4); if (v < 0 || v >= nv) throw NoSeLee("Modelo leído roto") }
                i += n * 4
                return t to n
            }
            val (op, nOp) = lista(); val (tr, nTr) = lista(); val (ar, nAr) = lista(); val (li, nLi) = lista(); val (pu, nPu) = lista()
            val ne = u()
            if (ne < 0 || ne > d.limit()) throw NoSeLee("Modelo leído roto")
            fun texto(): String { val n = u(); if (n < 0) throw NoSeLee("Modelo leído roto"); hay(n.toLong()); val by = ByteArray(n); for (k in 0 until n) by[k] = d.get(i + k); i += n; return String(by, Charsets.UTF_8) }
            val elementos = List(ne) {
                val tipo = texto(); val nombre = texto()
                val nivel = u()
                hay(17)
                val md = Medidas(f(), f(), f(), f(), d.get(i) != 0.toByte()).also { i += 1 }
                Elemento(tipo, nombre, if (nivel == -1) -1 else nivel, md)
            }
            val nn = u()
            if (nn < 0 || nn > d.limit()) throw NoSeLee("Modelo leído roto")
            val niveles = List(nn) { val n = texto(); hay(8); Nivel(n, d.getDouble(i).also { i += 8 }) }
            if (elementos.any { it.nivel < -1 || it.nivel >= nn }) throw NoSeLee("Modelo leído roto")
            return Modelo3D(origen, caja, metros, vertices, nv, op, nOp, tr, nTr, ar, nAr, li, nLi, pu, nPu, elementos, niveles)
        }

        private fun trozo(d: ByteBuffer, desde: Int, largo: Int): ByteBuffer {
            val t = d.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            t.position(desde); t.limit(desde + largo)
            return t.slice().order(ByteOrder.LITTLE_ENDIAN)
        }
    }
}

/**
 * **La cámara del visor 3D** (`Orbita` de `crates/pixpin-cad/src/ventana3d.rs` del PC, con sus
 * mismas cuentas): mira a [objetivo] desde [dist], girada [rumbo] alrededor de Z y [altura] sobre
 * el horizonte (radianes). Z es arriba.
 */
data class Orbita(val objetivo: DoubleArray, val rumbo: Double, val altura: Double, val dist: Double) {
    private fun adelante(): DoubleArray {
        val c = cos(altura); val s = sin(altura)
        return doubleArrayOf(-c * cos(rumbo), -c * sin(rumbo), -s)
    }

    fun ojo(): DoubleArray = sub(objetivo, por(adelante(), dist))

    /** Derecha, arriba y adelante de la pantalla. */
    fun ejes(): Array<DoubleArray> {
        val f = adelante()
        var r = cruz(f, doubleArrayOf(0.0, 0.0, 1.0))
        if (punto(r, r) < 1e-12) r = doubleArrayOf(-sin(rumbo), cos(rumbo), 0.0)
        r = unit(r)
        return arrayOf(r, cruz(r, f), f)
    }

    private fun distanciaPara(caja: FloatArray, ancho: Int, alto: Int): Double {
        val (r, u, f) = ejes()
        val ty = tan(FOV / 2) * 0.94
        val tx = ty * ancho.coerceAtLeast(1) / alto.coerceAtLeast(1)
        var d = 0.0
        for (i in 0 until 8) {
            val p = doubleArrayOf(
                caja[if (i and 1 == 0) 0 else 3].toDouble(), caja[if (i and 2 == 0) 1 else 4].toDouble(), caja[if (i and 4 == 0) 2 else 5].toDouble()
            )
            val q = sub(p, objetivo)
            val z = punto(q, f)
            d = maxOf(d, kotlin.math.abs(punto(q, r)) / tx - z, kotlin.math.abs(punto(q, u)) / ty - z)
        }
        return d
    }

    /** De mundo (relativo al origen) a recorte, por columnas (para OpenGL). */
    fun matriz(ancho: Int, alto: Int, radio: Double): FloatArray {
        val (r, u, f) = ejes()
        val e = ojo()
        val cerca = maxOf(dist * 0.01, radio * 1e-5, 1e-4)
        val lejos = dist + radio * 4.0 + 1.0
        val ys = 1.0 / tan(FOV / 2)
        val xs = ys * alto.coerceAtLeast(1) / ancho.coerceAtLeast(1)
        // Vista: x = r·(p-e), y = u·(p-e), z = f·(p-e) (z hacia dentro). Proyección de OpenGL
        // (z de -w a w), con la profundidad hacia dentro: clip.z = (z·(l+c) - 2lc)/(l-c), w = z.
        val a = (lejos + cerca) / (lejos - cerca)
        val b = -2 * lejos * cerca / (lejos - cerca)
        val m = FloatArray(16)
        fun fila(k: Int, v: DoubleArray, t: Double, s: Double) {
            // columna j = coordenada de entrada (x, y, z, 1); fila k = salida.
            m[0 * 4 + k] = (v[0] * s).toFloat(); m[1 * 4 + k] = (v[1] * s).toFloat(); m[2 * 4 + k] = (v[2] * s).toFloat(); m[3 * 4 + k] = t.toFloat()
        }
        fila(0, r, -punto(r, e) * xs, xs)
        fila(1, u, -punto(u, e) * ys, ys)
        fila(2, f, -punto(f, e) * a + b, a)
        fila(3, f, -punto(f, e), 1.0)
        return m
    }

    /** La dirección del rayo que sale del ojo por el píxel ([x], [y]). */
    fun rayo(x: Double, y: Double, ancho: Int, alto: Int): DoubleArray {
        val (r, u, f) = ejes()
        val t = tan(FOV / 2)
        val nx = (x / ancho.coerceAtLeast(1) * 2 - 1) * t * ancho.coerceAtLeast(1) / alto.coerceAtLeast(1)
        val ny = (1 - y / alto.coerceAtLeast(1) * 2) * t
        return unit(suma(f, suma(por(r, nx), por(u, ny))))
    }

    private fun porPixel(alto: Int) = 2 * dist * tan(FOV / 2) / alto.coerceAtLeast(1)

    /** Acercar [factor] (<1 acerca) dejando quieto [fijo] en la pantalla. */
    fun zoom(factor: Double, fijo: DoubleArray): Orbita {
        val k = factor.coerceIn(1e-3, 1e3)
        return copy(objetivo = suma(fijo, por(sub(objetivo, fijo), k)), dist = (dist * k).coerceAtLeast(1e-4))
    }

    /** Girar alrededor de [pivote]. */
    fun girar(dRumbo: Double, dAltura0: Double, pivote: DoubleArray): Orbita {
        val nuevaAltura = (altura + dAltura0).coerceIn(-ALTURA_MAX, ALTURA_MAX)
        val dAltura = nuevaAltura - altura
        fun rz(p: DoubleArray): DoubleArray {
            val q = sub(p, pivote); val c = cos(dRumbo); val s = sin(dRumbo)
            return suma(pivote, doubleArrayOf(q[0] * c - q[1] * s, q[0] * s + q[1] * c, q[2]))
        }
        var ojo = rz(ojo()); var obj = rz(objetivo)
        var o = Orbita(obj, rumbo + dRumbo, altura, dist)
        val r = o.ejes()[0]
        fun rot(p: DoubleArray): DoubleArray {
            val q = sub(p, pivote); val c = cos(dAltura); val s = sin(dAltura)
            val k = por(r, -1.0)
            return suma(pivote, suma(suma(por(q, c), por(cruz(k, q), s)), por(k, punto(k, q) * (1 - c))))
        }
        ojo = rot(ojo); obj = rot(obj)
        o = o.copy(objetivo = obj, altura = nuevaAltura)
        return o.copy(objetivo = suma(ojo, por(o.adelante(), dist)))
    }

    /** Mover la cámara [dx], [dy] píxeles (el modelo sigue al dedo). */
    fun mover(dx: Double, dy: Double, alto: Int): Orbita {
        val (r, u, _) = ejes()
        val k = porPixel(alto)
        return copy(objetivo = suma(objetivo, suma(por(r, -dx * k), por(u, dy * k))))
    }

    /** [caja] entera en pantalla **sin cambiar desde dónde se mira** (aislar un elemento). */
    fun encuadrarCon(caja: FloatArray, ancho: Int, alto: Int): Orbita {
        val c = doubleArrayOf((caja[0] + caja[3]) / 2.0, (caja[1] + caja[4]) / 2.0, (caja[2] + caja[5]) / 2.0)
        val d = doubleArrayOf((caja[3] - caja[0]).toDouble(), (caja[4] - caja[1]).toDouble(), (caja[5] - caja[2]).toDouble())
        val o = copy(objetivo = c, dist = 1.0)
        return o.copy(dist = maxOf(o.distanciaPara(caja, ancho, alto), sqrt(punto(d, d)) * 1e-3, 1e-3))
    }

    /** La planta: desde arriba, con el norte arriba. */
    fun enPlanta(): Orbita = copy(rumbo = -Math.PI / 2, altura = ALTURA_MAX)

    override fun equals(other: Any?) = other is Orbita && objetivo.contentEquals(other.objetivo) && rumbo == other.rumbo && altura == other.altura && dist == other.dist
    override fun hashCode() = objetivo.contentHashCode() * 31 + dist.hashCode()

    companion object {
        const val FOV = 0.785398 // 45 grados
        const val ALTURA_MAX = 1.5697

        /** El modelo entero en pantalla, visto desde el sureste y algo de arriba. */
        fun encuadrar(caja: FloatArray, ancho: Int, alto: Int): Orbita {
            val c = doubleArrayOf((caja[0] + caja[3]) / 2.0, (caja[1] + caja[4]) / 2.0, (caja[2] + caja[5]) / 2.0)
            val d = doubleArrayOf((caja[3] - caja[0]).toDouble(), (caja[4] - caja[1]).toDouble(), (caja[5] - caja[2]).toDouble())
            val o = Orbita(c, -0.8, 0.5, 1.0)
            return o.copy(dist = maxOf(o.distanciaPara(caja, ancho, alto), sqrt(punto(d, d)) * 1e-3, 1e-3))
        }

        fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        fun suma(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
        fun por(a: DoubleArray, k: Double) = doubleArrayOf(a[0] * k, a[1] * k, a[2] * k)
        fun punto(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun cruz(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun unit(a: DoubleArray): DoubleArray { val l = sqrt(punto(a, a)).coerceAtLeast(1e-300); return por(a, 1 / l) }
    }
}

/**
 * **Qué hay bajo el dedo** en el visor 3D: el triángulo (o la raya, o el punto) más cercano por el
 * rayo del ojo, recorriendo el modelo en la CPU. En el PC lo dice la tarjeta (un búfer de
 * elementos); aquí, con el rayo, que no necesita texturas de coma flotante que no todos los
 * teléfonos tienen. Devuelve el elemento y el punto del mundo, o null.
 */
object ElegirEn3D {
    class Tocado(val elemento: Int, val punto: DoubleArray)

    /**
     * [seccion]: la caja de sección (mín. y máx. x, y, z), o null; lo de fuera no se toca. Si lo
     * tocado es la cara de dentro de un sólido cortado (la tapa), el punto es el del corte: allí
     * se gira y se acerca, como en el PC. [ocultos]: un byte por elemento, 1 = escondido (no se
     * puede tocar lo que no se ve).
     */
    fun elegir(m: Modelo3D, ojo: DoubleArray, dir: DoubleArray, seccion: FloatArray?, toleranciaRaya: Double, ocultos: ByteArray? = null): Tocado? {
        var mejor = Double.MAX_VALUE
        var el = -1
        var tapa = false
        fun fuera(t: Double): Boolean {
            val c = seccion ?: return false
            for (k in 0 until 3) { val v = ojo[k] + dir[k] * t; if (v < c[k] || v > c[k + 3]) return true }
            return false
        }
        fun escondido(e: Int) = ocultos != null && e in ocultos.indices && ocultos[e].toInt() != 0
        fun triangulos(l: ByteBuffer, n: Int) {
            var k = 0
            while (k + 2 < n) {
                val a = l.getInt(k * 4); val b = l.getInt(k * 4 + 4); val c = l.getInt(k * 4 + 8)
                k += 3
                val t = rayoTriangulo(ojo, dir, m, a, b, c) ?: continue
                if (t >= mejor) continue
                if (fuera(t)) continue
                if (escondido(m.elemento(a))) continue
                mejor = t; el = m.elemento(a)
                tapa = seccion != null && deEspaldas(m, a, b, c, dir)
            }
        }
        triangulos(m.opacos, m.nOpacos)
        triangulos(m.transparentes, m.nTransparentes)
        // Las rayas (curvas de nivel, ejes, tuberías): la más cercana al rayo, dentro de la tolerancia.
        var k = 0
        while (k + 1 < m.nLineas) {
            val a = m.lineas.getInt(k * 4); val b = m.lineas.getInt(k * 4 + 4)
            k += 2
            val r = rayoSegmento(ojo, dir, m, a, b) ?: continue
            val (t, d) = r
            if (t > 0 && t < mejor && d <= toleranciaRaya * t) {
                if (fuera(t)) continue
                if (escondido(m.elemento(a))) continue
                mejor = t; el = m.elemento(a); tapa = false
            }
        }
        if (el < 0) return null
        if (tapa && seccion != null) {
            // Por dónde entra el rayo en la caja.
            var t0 = 0.0
            for (k in 0 until 3) {
                if (kotlin.math.abs(dir[k]) < 1e-12) continue
                val a = (seccion[k] - ojo[k]) / dir[k]; val b = (seccion[k + 3] - ojo[k]) / dir[k]
                t0 = maxOf(t0, minOf(a, b))
            }
            if (t0 in 0.0..mejor) mejor = t0
        }
        return Tocado(el, Orbita.suma(ojo, Orbita.por(dir, mejor)))
    }

    /** Si el triángulo da la espalda al rayo (se le ve la cara de dentro): con la normal guardada. */
    private fun deEspaldas(m: Modelo3D, a: Int, b: Int, c: Int, dir: DoubleArray): Boolean {
        val n = m.vertices.getInt(a * 24 + 12)
        val nx = (n shl 24 shr 24).toDouble(); val ny = (n shl 16 shr 24).toDouble(); val nz = (n shl 8 shr 24).toDouble()
        if (nx == 0.0 && ny == 0.0 && nz == 0.0) return false
        // La normal geométrica, vuelta hacia el ojo; la guardada contra ella (como el sombreador).
        val ux = (m.x(b) - m.x(a)).toDouble(); val uy = (m.y(b) - m.y(a)).toDouble(); val uz = (m.z(b) - m.z(a)).toDouble()
        val wx = (m.x(c) - m.x(a)).toDouble(); val wy = (m.y(c) - m.y(a)).toDouble(); val wz = (m.z(c) - m.z(a)).toDouble()
        var gx = uy * wz - uz * wy; var gy = uz * wx - ux * wz; var gz = ux * wy - uy * wx
        if (gx * dir[0] + gy * dir[1] + gz * dir[2] > 0) { gx = -gx; gy = -gy; gz = -gz }
        return nx * gx + ny * gy + nz * gz < 0
    }

    /** La caja de un elemento (relativa al origen), para encuadrarlo al aislarlo; null si no tiene vértices. */
    fun cajaDe(m: Modelo3D, e: Int): FloatArray? {
        var c: FloatArray? = null
        for (v in 0 until m.nVertices) {
            if (m.elemento(v) != e) continue
            val x = m.x(v); val y = m.y(v); val z = m.z(v)
            val k = c ?: floatArrayOf(x, y, z, x, y, z).also { c = it }
            k[0] = minOf(k[0], x); k[1] = minOf(k[1], y); k[2] = minOf(k[2], z)
            k[3] = maxOf(k[3], x); k[4] = maxOf(k[4], y); k[5] = maxOf(k[5], z)
        }
        return c
    }

    /** Möller–Trumbore: la distancia por el rayo hasta el triángulo, o null. */
    private fun rayoTriangulo(o: DoubleArray, d: DoubleArray, m: Modelo3D, a: Int, b: Int, c: Int): Double? {
        val ax = m.x(a).toDouble(); val ay = m.y(a).toDouble(); val az = m.z(a).toDouble()
        val e1x = m.x(b) - ax; val e1y = m.y(b) - ay; val e1z = m.z(b) - az
        val e2x = m.x(c) - ax; val e2y = m.y(c) - ay; val e2z = m.z(c) - az
        val px = d[1] * e2z - d[2] * e2y; val py = d[2] * e2x - d[0] * e2z; val pz = d[0] * e2y - d[1] * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (kotlin.math.abs(det) < 1e-18) return null
        val inv = 1 / det
        val tx = o[0] - ax; val ty = o[1] - ay; val tz = o[2] - az
        val u = (tx * px + ty * py + tz * pz) * inv
        if (u < 0 || u > 1) return null
        val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
        val v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
        if (v < 0 || u + v > 1) return null
        val t = (e2x * qx + e2y * qy + e2z * qz) * inv
        return if (t > 0) t else null
    }

    /** La distancia por el rayo al punto más cercano de un segmento y lo lejos que pasa. */
    private fun rayoSegmento(o: DoubleArray, d: DoubleArray, m: Modelo3D, a: Int, b: Int): Pair<Double, Double>? {
        val p = doubleArrayOf(m.x(a).toDouble(), m.y(a).toDouble(), m.z(a).toDouble())
        val q = doubleArrayOf(m.x(b).toDouble(), m.y(b).toDouble(), m.z(b).toDouble())
        val u = Orbita.sub(q, p)
        val w = Orbita.sub(o, p)
        val aa = Orbita.punto(d, d); val bb = Orbita.punto(d, u); val cc = Orbita.punto(u, u)
        val dd = Orbita.punto(d, w); val ee = Orbita.punto(u, w)
        val den = aa * cc - bb * bb
        if (cc < 1e-24) return null
        var s = if (kotlin.math.abs(den) < 1e-18) 0.0 else (aa * ee - bb * dd) / den
        s = s.coerceIn(0.0, 1.0)
        val t = (bb * s - dd) / aa
        val enRayo = Orbita.suma(o, Orbita.por(d, t))
        val enSeg = Orbita.suma(p, Orbita.por(u, s))
        val dif = Orbita.sub(enRayo, enSeg)
        return t to sqrt(Orbita.punto(dif, dif))
    }
}

/**
 * **Del visor 3D al croquis** (10-oct-2026): el modelo leído con la lectura del PC (ifc-lite para
 * IFC, rvt-rs para Revit) pasado a la malla del croquis ([com.forge.pixpin.motor.Malla3D]: metros,
 * Z arriba, un ARGB por triángulo y los triángulos de cada elemento seguidos). El croquis leía los
 * IFC con su propio lector y el usuario los veía descolocados; así los dos enseñan lo mismo que
 * Windows.
 *
 * Las medidas van relativas al origen del modelo (un edificio georreferenciado está a cientos de
 * kilómetros del cero, y en `Float` sus centímetros se perderían). Solo las caras: las rayas y los
 * puntos sueltos (de Civil 3D) no tienen sitio en la malla.
 */
object ModeloAMalla {
    fun malla(m: Modelo3D): com.forge.pixpin.motor.Malla3D {
        val k = m.metros.toDouble().takeIf { it > 0 && it.isFinite() } ?: 1.0
        // Los triángulos, agrupados por elemento (la malla quiere cada pieza en un tramo seguido).
        val nt = m.nOpacos / 3 + m.nTransparentes / 3
        val elem = IntArray(nt)
        val primero = IntArray(nt)
        var t = 0
        fun juntar(l: ByteBuffer, n: Int) {
            var i = 0
            while (i + 2 < n) { primero[t] = i; elem[t] = m.elemento(l.getInt(i * 4)); t++; i += 3 }
        }
        juntar(m.opacos, m.nOpacos)
        val opacos = t
        juntar(m.transparentes, m.nTransparentes)
        val orden = (0 until t).sortedWith(compareBy({ elem[it] }, { it }))
        val c = com.forge.pixpin.motor.Malla3D.Constructor()
        val nuevo = IntArray(m.nVertices) { -1 }
        fun v(i: Int): Int {
            if (nuevo[i] < 0) nuevo[i] = c.vertice(m.x(i) * k, m.y(i) * k, m.z(i) * k)
            return nuevo[i]
        }
        var actual = if (orden.isEmpty()) -1 else elem[orden[0]]
        fun cerrar(e: Int) {
            val (tipo, nombre) = m.elementos.getOrNull(e) ?: Modelo3D.Elemento("", "")
            c.cerrarPieza(nombre.ifBlank { TiposIfc.legible(tipo) }, tipo.uppercase())
        }
        for (tt in orden) {
            if (elem[tt] != actual) { cerrar(actual); actual = elem[tt] }
            val (l, i) = if (tt < opacos) m.opacos to primero[tt] else m.transparentes to primero[tt]
            val a = l.getInt(i * 4); val b = l.getInt(i * 4 + 4); val cc = l.getInt(i * 4 + 8)
            // RGBA (rojo en el byte bajo) → ARGB.
            val rgba = m.vertices.getInt(a * 24 + 16)
            val r = rgba and 0xff; val g = (rgba shr 8) and 0xff; val bl = (rgba shr 16) and 0xff; val al = (rgba ushr 24) and 0xff
            c.triangulo(v(a), v(b), v(cc), (al shl 24) or (r shl 16) or (g shl 8) or bl)
        }
        if (actual >= 0) cerrar(actual)
        return c.malla()
    }
}
