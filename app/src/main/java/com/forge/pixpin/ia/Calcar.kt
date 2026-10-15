package com.forge.pixpin.ia

import com.forge.pixpin.ia.Trazado.P
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * **Calcar una imagen al lienzo** (`calcar.rs` del PC, portado tal cual, con lo nuevo «sin
 * escaleras» del 14-oct): un algoritmo clásico, determinista; el modelo solo elige los parámetros.
 *
 * - **Manchas de color**: k-medias en RGB sobre la imagen reducida por media de áreas, filtro de
 *   mayoría, colores de mezcla repartidos, campo sacado del color en el borde, contorno sub-pixel,
 *   esquinas y Bézier ([Trazado]). Salen como `pixpin-region`; la mancha de debajo se mete
 *   [SOLAPE] píxeles bajo las de encima (sin rendijas). El fondo no se dibuja.
 * - **Solo contornos**: Canny con posición sub-pixel de Devernay → cadenas → las mismas curvas.
 *
 * Con tope: la imagen se reduce a 160/256/384 px de lado y salen como mucho [Detalle.tope].
 */
object Calcar {
    private const val PLANO_EN_PANTALLA = 0.25
    private const val SOLAPE = 2
    private const val BORDE = 2

    enum class Detalle(val lado: Int, val colores: Int, val tolerancia: Double, val areaMinima: Double, val cadenaMinima: Int, val tope: Int) {
        BAJO(160, 5, 0.6, 0.0015, 14, 40),
        MEDIO(256, 8, 0.4, 0.0004, 9, 90),
        ALTO(384, 12, 0.35, 0.00015, 6, 160)
    }

    data class Parametros(
        val detalle: Detalle = Detalle.MEDIO,
        val colores: Boolean = true,
        val soloContornos: Boolean = false,
        /** Un trozo de la imagen, en fracciones `[x, y, ancho, alto]` de 0 a 1. */
        val zona: DoubleArray? = null,
        /** Qué adjunto calcar; null: la última imagen. */
        val imagen: String? = null
    ) {
        override fun equals(other: Any?) = other is Parametros && detalle == other.detalle && colores == other.colores &&
            soloContornos == other.soloContornos && imagen == other.imagen && (zona?.toList() == other.zona?.toList())
        override fun hashCode() = listOf(detalle, colores, soloContornos, imagen, zona?.toList()).hashCode()
    }

    /** **Lo que el modelo eligió sin que se lo pidieran, corregido con el pedido.** */
    fun ajustarAlPedido(p: Parametros, pedido: String): Parametros {
        val q = Acciones.normalizar(pedido)
        fun dice(r: List<String>) = r.any { q.contains(it) }
        var r = p
        if (r.soloContornos && !dice(listOf("contorno", "borde", "linea", "silueta", "trazo", "perfil", "solo las rayas"))) r = r.copy(soloContornos = false)
        if (!r.colores && !dice(listOf("blanco y negro", "sin color", "en negro", "monocrom", "gris"))) r = r.copy(colores = true)
        return r
    }

    sealed class Trazo {
        data class Mancha(val contorno: List<P>, val huecos: List<List<P>>, val color: String) : Trazo()
        data class Linea(val puntos: List<P>, val cerrada: Boolean, val color: String) : Trazo()
        data class Fondo(val caja: DoubleArray, val color: String) : Trazo() {
            override fun equals(other: Any?) = other is Fondo && color == other.color && caja.contentEquals(other.caja)
            override fun hashCode() = color.hashCode() * 31 + caja.contentHashCode()
        }
    }

    data class Calcado(val trazos: List<Trazo>, val escala: Double, val desplazamiento: Pair<Double, Double>)

    /** **Calca** una imagen RGBA (`ancho * alto * 4` bytes; lo transparente cuenta como blanco). */
    fun calcar(rgba: ByteArray, ancho: Int, alto: Int, p: Parametros): Calcado {
        val vacio = Calcado(emptyList(), 1.0, 0.0 to 0.0)
        if (ancho <= 0 || alto <= 0 || rgba.size < ancho.toLong() * alto * 4) return vacio
        val z = p.zona
        var zx: Int; var zy: Int; var zw: Int; var zh: Int
        if (z != null && z[2] > 0.0 && z[3] > 0.0) {
            val x0 = (z[0].coerceIn(0.0, 1.0) * ancho).toInt()
            val y0 = (z[1].coerceIn(0.0, 1.0) * alto).toInt()
            val x1 = ceil((z[0] + z[2]).coerceIn(0.0, 1.0) * ancho).toInt()
            val y1 = ceil((z[1] + z[3]).coerceIn(0.0, 1.0) * alto).toInt()
            zx = x0; zy = y0; zw = max(max(x1 - x0, 0), 1); zh = max(max(y1 - y0, 0), 1)
        } else { zx = 0; zy = 0; zw = ancho; zh = alto }
        zw = min(zw, ancho - min(zx, ancho - 1))
        zh = min(zh, alto - min(zy, alto - 1))
        val lado = p.detalle.lado
        val f = min(lado.toDouble() / max(zw, zh), 1.0)
        val w = max(round(zw * f).toInt(), 1)
        val h = max(round(zh * f).toInt(), 1)
        val img = reducir(rgba, ancho, zx, zy, zw, zh, w, h)
        val margen = 20.0
        val escRed = min((Herramientas.ANCHO_LIENZO - 2 * margen) / w, (Herramientas.ALTO_LIENZO - 2 * margen) / h)
        val dx = (Herramientas.ANCHO_LIENZO - w * escRed) / 2.0
        val dy = (Herramientas.ALTO_LIENZO - h * escRed) / 2.0
        fun aLienzo(q: P) = P(q.x * escRed + dx, q.y * escRed + dy)
        val sx = w.toDouble() / zw
        val escala = sx * escRed
        val desplazamiento = (dx - zx * escala) to (dy - zy * escala)
        val plano = PLANO_EN_PANTALLA / escRed
        var trazos: MutableList<Trazo> = if (p.soloContornos) contornos(img, w, h, p, plano) else manchas(img, w, h, p, plano)
        val tope = p.detalle.tope
        if (trazos.size > tope) {
            val fondo = trazos.firstOrNull() is Trazo.Fondo
            val resto = if (fondo) trazos.subList(1, trazos.size).toList() else trazos.toList()
            val conTam = resto.map { tamano(it) to it }.sortedWith { a, b -> b.first.compareTo(a.first) }.take(tope - (if (fondo) 1 else 0))
            trazos = (if (fondo) mutableListOf(trazos[0]) else mutableListOf<Trazo>()).also { l -> l += conTam.map { it.second } }
        }
        val salida = trazos.map { t ->
            when (t) {
                is Trazo.Mancha -> Trazo.Mancha(t.contorno.map(::aLienzo), t.huecos.map { it.map(::aLienzo) }, t.color)
                is Trazo.Linea -> Trazo.Linea(t.puntos.map(::aLienzo), t.cerrada, t.color)
                is Trazo.Fondo -> { val o = aLienzo(P(0.0, 0.0)); Trazo.Fondo(doubleArrayOf(o.x, o.y, w * escRed, h * escRed), t.color) }
            }
        }
        return Calcado(salida, escala, desplazamiento)
    }

    private fun tamano(t: Trazo): Double = when (t) {
        is Trazo.Mancha -> abs(Trazado.area(t.contorno))
        is Trazo.Linea -> t.puntos.zipWithNext { a, b -> Trazado.dist(a, b) }.sum() * 3.0
        is Trazo.Fondo -> Double.MAX_VALUE
    }

    /** La zona reducida a `w x h` por **media de áreas**, en RGB sobre blanco. */
    private fun reducir(rgba: ByteArray, ancho: Int, zx: Int, zy: Int, zw: Int, zh: Int, w: Int, h: Int): Array<FloatArray> {
        fun pesos(nFuera: Int, n: Int): List<List<Pair<Int, Float>>> {
            val paso = nFuera.toDouble() / n
            return (0 until n).map { i ->
                val a = i * paso
                val b = min((i + 1) * paso, nFuera.toDouble())
                val v = ArrayList<Pair<Int, Float>>()
                var k = floor(a).toInt()
                while (k.toDouble() < b && k < nFuera) {
                    val pp = (min(b, k + 1.0) - max(a, k.toDouble())).toFloat()
                    if (pp > 1e-6f) v += k to pp
                    k++
                }
                v
            }
        }
        val px = pesos(zw, w); val py = pesos(zh, h)
        val out = Array(w * h) { floatArrayOf(255f, 255f, 255f) }
        for (y in 0 until h) for (x in 0 until w) {
            val s = FloatArray(3)
            var n = 0f
            for ((yy, wy) in py[y]) for ((xx, wx) in px[x]) {
                val i = ((zy + yy) * ancho + zx + xx) * 4
                if (i < 0 || i + 4 > rgba.size) continue
                val a = (rgba[i + 3].toInt() and 255) / 255.0f
                val pp = wx * wy
                for (c in 0 until 3) s[c] += pp * ((rgba[i + c].toInt() and 255) * a + 255.0f * (1.0f - a))
                n += pp
            }
            if (n > 0f) out[y * w + x] = floatArrayOf(s[0] / n, s[1] / n, s[2] / n)
        }
        return out
    }

    private fun canal(v: Float): Int = round(v).coerceIn(0.0f, 255.0f).toInt()
    private fun hex(c: FloatArray) = "#%02x%02x%02x".format(canal(c[0]), canal(c[1]), canal(c[2]))
    private fun d2(a: FloatArray, b: FloatArray): Float { val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return x * x + y * y + z * z }

    // ------------------------------------------------------------------------- manchas

    private fun kMedias(img: Array<FloatArray>, k: Int): Pair<List<FloatArray>, ByteArray> {
        val paso = max(img.size / 20_000, 1)
        val muestra = img.indices.step(paso).map { img[it] }
        val cubos = LinkedHashMap<Int, DoubleArray>()
        for (c in muestra) {
            val clave = ((c[0] / 16.0f).toInt() shl 16) or ((c[1] / 16.0f).toInt() shl 8) or (c[2] / 16.0f).toInt()
            val e = cubos.getOrPut(clave) { DoubleArray(4) }
            for (j in 0 until 3) e[j] += c[j].toDouble()
            e[3] += 1.0
        }
        val minimo = max(muestra.size / 400.0, 3.0)
        val candidatos = cubos.values.map { s -> floatArrayOf((s[0] / s[3]).toFloat(), (s[1] / s[3]).toFloat(), (s[2] / s[3]).toFloat()) to s[3] }
            .sortedWith { a, b -> val c = b.second.compareTo(a.second); if (c != 0) c else a.first[0].compareTo(b.first[0]) }
        val centros = mutableListOf(candidatos.firstOrNull()?.first ?: floatArrayOf(255f, 255f, 255f))
        val fuertes = candidatos.filter { it.second >= minimo }.map { it.first }
        while (centros.size < k) {
            var mejor: FloatArray? = null
            var dist = -1f
            for (c in fuertes) {
                val d = centros.fold(Float.MAX_VALUE) { m, z -> min(m, d2(c, z)) }
                // `max_by` de Rust: a igual distancia, el último.
                if (mejor == null || d >= dist) { mejor = c; dist = d }
            }
            if (mejor == null || dist < 400.0f) break
            centros += mejor
        }
        repeat(8) {
            val suma = Array(centros.size) { DoubleArray(4) }
            for (c in muestra) {
                val i = cercano(centros, c)
                for (j in 0 until 3) suma[i][j] += c[j].toDouble()
                suma[i][3] += 1.0
            }
            for (i in centros.indices) {
                val s = suma[i]
                if (s[3] > 0.0) centros[i] = floatArrayOf((s[0] / s[3]).toFloat(), (s[1] / s[3]).toFloat(), (s[2] / s[3]).toFloat())
            }
        }
        val et = ByteArray(img.size) { cercano(centros, img[it]).toByte() }
        return centros to et
    }

    private fun cercano(centros: List<FloatArray>, c: FloatArray): Int {
        var mejor = 0
        var md = Float.MAX_VALUE
        for (i in centros.indices) { val d = d2(c, centros[i]); if (d < md) { md = d; mejor = i } }
        return mejor
    }

    private fun et(a: ByteArray, i: Int) = a[i].toInt() and 255

    private fun mayoria(e: ByteArray, w: Int, h: Int, k: Int): ByteArray {
        val out = e.copyOf()
        val cuenta = IntArray(k)
        for (y in 0 until h) for (x in 0 until w) {
            cuenta.fill(0)
            for (yy in max(y - 1, 0) until min(y + 2, h)) for (xx in max(x - 1, 0) until min(x + 2, w)) cuenta[et(e, yy * w + xx)]++
            val propio = et(e, y * w + x)
            // `max_by_key` de Rust: a igual cuenta, el último.
            var mejor = 0
            for (i in cuenta.indices) if (cuenta[i] >= cuenta[mejor]) mejor = i
            if (cuenta[mejor] >= 6 && mejor != propio) out[y * w + x] = mejor.toByte()
        }
        return out
    }

    private fun fusionarMezclas(centros: List<FloatArray>, e: ByteArray, w: Int, h: Int) {
        val k = centros.size
        repeat(k) {
            val cuenta = IntArray(k)
            val deBorde = IntArray(k)
            for (y in 0 until h) for (x in 0 until w) {
                val v = e[y * w + x]
                cuenta[et(e, y * w + x)]++
                val distinto = (x > 0 && e[y * w + x - 1] != v) || (x + 1 < w && e[y * w + x + 1] != v) ||
                    (y > 0 && e[(y - 1) * w + x] != v) || (y + 1 < h && e[(y + 1) * w + x] != v)
                if (distinto) deBorde[et(e, y * w + x)]++
            }
            var cambio = false
            for (c in 0 until k) {
                if (cuenta[c] == 0 || deBorde[c].toDouble() < 0.5 * cuenta[c]) continue
                var mejor: Triple<Int, Int, Float>? = null
                for (a in 0 until k) for (b in a + 1 until k) {
                    if (a == c || b == c || cuenta[a] == 0 || cuenta[b] == 0) continue
                    val pa = centros[a]; val pb = centros[b]; val pc = centros[c]
                    val ab = floatArrayOf(pb[0] - pa[0], pb[1] - pa[1], pb[2] - pa[2])
                    val l2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2]
                    if (l2 <= 0f) continue
                    val t = ((pc[0] - pa[0]) * ab[0] + (pc[1] - pa[1]) * ab[1] + (pc[2] - pa[2]) * ab[2]) / l2
                    if (t < 0.05f || t > 0.95f) continue
                    val q = floatArrayOf(pa[0] + t * ab[0], pa[1] + t * ab[1], pa[2] + t * ab[2])
                    val d = d2(pc, q)
                    if (d < 45.0f * 45.0f && (mejor == null || d < mejor.third)) mejor = Triple(a, b, d)
                }
                if (mejor != null) {
                    val (a, b, _) = mejor
                    val copia = e.copyOf()
                    for (y in 0 until h) for (x in 0 until w) {
                        val i = y * w + x
                        if (et(copia, i) != c) continue
                        var na = 0; var nb = 0
                        for (yy in max(y - 1, 0) until min(y + 2, h)) for (xx in max(x - 1, 0) until min(x + 2, w)) {
                            val v = et(copia, yy * w + xx)
                            if (v == a) na++
                            if (v == b) nb++
                        }
                        e[i] = (if (na > nb) a else if (nb > na) b else if (d2(centros[c], centros[a]) <= d2(centros[c], centros[b])) a else b).toByte()
                    }
                    cambio = true
                    break
                }
            }
            if (!cambio) return
        }
    }

    private class Componente(val pixeles: IntArray, val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    private fun componentes(e: ByteArray, w: Int, h: Int, l: Int): List<Componente> {
        val visto = BooleanArray(w * h)
        val salida = ArrayList<Componente>()
        for (inicio in 0 until w * h) {
            if (visto[inicio] || et(e, inicio) != l) continue
            val pila = ArrayList<Int>().also { it += inicio }
            visto[inicio] = true
            val pixeles = ArrayList<Int>()
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            while (pila.isNotEmpty()) {
                val i = pila.removeAt(pila.size - 1)
                pixeles += i
                val x = i % w; val y = i / w
                x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
                val vecinos = intArrayOf(if (x > 0) i - 1 else -1, if (x + 1 < w) i + 1 else -1, if (y > 0) i - w else -1, if (y + 1 < h) i + w else -1)
                for (j in vecinos) if (j >= 0 && !visto[j] && et(e, j) == l) { visto[j] = true; pila += j }
            }
            salida += Componente(pixeles.toIntArray(), x0, y0, x1, y1)
        }
        return salida
    }

    private fun manchas(img: Array<FloatArray>, w: Int, h: Int, p: Parametros, plano: Double): MutableList<Trazo> {
        val k = if (p.colores) p.detalle.colores else 2
        val (centros, et0) = kMedias(img, k)
        val e = mayoria(et0, w, h, centros.size)
        fusionarMezclas(centros, e, w, h)
        val borde = IntArray(centros.size)
        for (x in 0 until w) { borde[et(e, x)]++; borde[et(e, (h - 1) * w + x)]++ }
        for (y in 0 until h) { borde[et(e, y * w)]++; borde[et(e, y * w + w - 1)]++ }
        var fondo = 0
        for (i in borde.indices) if (borde[i] >= borde[fondo]) fondo = i
        val trazos = ArrayList<Trazo>()
        val blanco = centros[fondo].all { it > 225.0f }
        if (p.colores && !blanco) trazos += Trazo.Fondo(DoubleArray(4), hex(centros[fondo]))
        val areaMin = max(p.detalle.areaMinima * (w * h), 4.0)
        val tol = p.detalle.tolerancia
        val lista = ArrayList<Pair<Int, Componente>>()
        for (l in centros.indices) if (l != fondo) componentes(e, w, h, l).forEach { lista += l to it }
        lista.sortWith { a, b -> b.second.pixeles.size.compareTo(a.second.pixeles.size) }
        val turno = IntArray(w * h) { Int.MAX_VALUE }
        for ((kk, par) in lista.withIndex()) for (i in par.second.pixeles) turno[i] = kk
        val todas = ArrayList<Trazo>()
        for ((kk, par) in lista.withIndex()) {
            val (l, comp) = par
            if (comp.pixeles.size.toDouble() < areaMin) break
            val color = if (p.colores) hex(centros[l]) else "#1e1e1e"
            val x0 = max(comp.x0 - SOLAPE, 0); val y0 = max(comp.y0 - SOLAPE, 0)
            val x1 = min(comp.x1 + SOLAPE, w - 1); val y1 = min(comp.y1 + SOLAPE, h - 1)
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            val mascara = BooleanArray(bw * bh)
            for (i in comp.pixeles) mascara[(i / w - y0) * bw + (i % w - x0)] = true
            fun encima(i: Int) = et(e, i) != fondo && turno[i] > kk
            val debajo = solape(mascara, bw, bh) { x, y ->
                val gx = x + x0; val gy = y + y0
                encima(gy * w + gx) && (max(gy - SOLAPE, 0) until min(gy + SOLAPE + 1, h)).all { yy ->
                    (max(gx - SOLAPE, 0) until min(gx + SOLAPE + 1, w)).all { xx -> val j = yy * w + xx; turno[j] == kk || encima(j) }
                }
            }
            fun mover(v: List<P>) = v.map { P(it.x + x0, it.y + y0) }
            val (campo, gw, gh) = campoDeColor(img, w, e, centros, l, x0, y0, bw, bh, mascara, debajo)
            val entera = BooleanArray(mascara.size) { mascara[it] || debajo[it] }
            for ((contorno0, huecos0) in regionesDeCampo(campo, gw, gh, areaMin)) {
                val contorno = curva(mover(contorno0), true, tol, plano)
                if (contorno.size < 3) continue
                val huecos = huecos0.filter { hq -> huecoDeFondo(hq, entera, bw, bh) { x, y -> et(e, (y + y0) * w + (x + x0)) == fondo } }
                    .map { curva(mover(it), true, tol, plano) }.filter { it.size >= 3 }
                todas += Trazo.Mancha(contorno, huecos, color)
            }
        }
        trazos += todas
        return trazos
    }

    private fun solape(mascara: BooleanArray, bw: Int, bh: Int, encima: (Int, Int) -> Boolean): BooleanArray {
        val fila = BooleanArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            val a = max(x - SOLAPE, 0); val b = min(x + SOLAPE, bw - 1)
            fila[y * bw + x] = (a..b).any { mascara[y * bw + it] }
        }
        val salida = BooleanArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = y * bw + x
            if (mascara[i]) continue
            val a = max(y - SOLAPE, 0); val b = min(y + SOLAPE, bh - 1)
            salida[i] = (a..b).any { fila[it * bw + x] } && encima(x, y)
        }
        return salida
    }

    /** Los contornos **sub-pixel** de una máscara: (contorno, agujeros) de las manchas de al menos [areaMin]. */
    fun regiones(mascara: BooleanArray, w: Int, h: Int, areaMin: Double): List<Pair<List<P>, List<List<P>>>> {
        if (w == 0 || h == 0 || mascara.size < w * h) return emptyList()
        val (campo, gw, gh) = Trazado.campoSuave(mascara, w, h, BORDE)
        return regionesDeCampo(campo, gw, gh, areaMin)
    }

    private fun regionesDeCampo(campo: FloatArray, gw: Int, gh: Int, areaMin: Double): List<Pair<List<P>, List<List<P>>>> {
        val aPixel = BORDE - 0.5
        val exteriores = ArrayList<List<P>>()
        val agujeros = ArrayList<List<P>>()
        for (anillo0 in Trazado.isolineas(campo, gw, gh, 0.5f)) {
            val anillo = anillo0.map { P(it.x - aPixel, it.y - aPixel) }
            val a = Trazado.area(anillo)
            if (abs(a) < areaMin) continue
            if (a < 0.0) exteriores += anillo else agujeros += anillo
        }
        val salida = exteriores.map { it to ArrayList<List<P>>() }
        for (a in agujeros) {
            val p = a[0]
            salida.filter { dentro(it.first, p) }.minByOrNull { abs(Trazado.area(it.first)) }?.second?.add(a)
        }
        return salida
    }

    private fun campoDeColor(
        img: Array<FloatArray>, w: Int, e: ByteArray, centros: List<FloatArray>, l: Int,
        x0: Int, y0: Int, bw: Int, bh: Int, mascara: BooleanArray, debajo: BooleanArray
    ): Triple<FloatArray, Int, Int> {
        val h = e.size / w
        val gw = bw + 2 * BORDE; val gh = bh + 2 * BORDE
        val campo = FloatArray(gw * gh)
        fun proyeccion(c: FloatArray, fuera: FloatArray): Float {
            val d = floatArrayOf(centros[l][0] - fuera[0], centros[l][1] - fuera[1], centros[l][2] - fuera[2])
            val n = d[0] * d[0] + d[1] * d[1] + d[2] * d[2]
            if (n < 1e-3f) return 0.5f
            return (((c[0] - fuera[0]) * d[0] + (c[1] - fuera[1]) * d[1] + (c[2] - fuera[2]) * d[2]) / n).coerceIn(0.0f, 1.0f)
        }
        fun dentroDe(m: BooleanArray, x: Int, y: Int) = x >= 0 && y >= 0 && x < bw && y < bh && m[y * bw + x]
        val lados = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val casi = 1e-3f
        for (y in 0 until bh + 2) for (x in 0 until bw + 2) {
            val bx = x - 1; val by = y - 1
            val ix = x0 + bx; val iy = y0 + by
            if (ix < 0 || iy < 0 || ix >= w || iy >= h) continue
            val i = iy * w + ix
            val v = if (dentroDe(debajo, bx, by)) 1.0f
            else if (dentroDe(mascara, bx, by)) {
                val fuera = lados.firstNotNullOfOrNull { (dx, dy) ->
                    val nx = ix + dx; val ny = iy + dy
                    val esFuera = !dentroDe(mascara, bx + dx, by + dy) && !dentroDe(debajo, bx + dx, by + dy)
                    if (esFuera && nx >= 0 && ny >= 0 && nx < w && ny < h) et(e, ny * w + nx) else null
                }
                if (fuera != null && fuera != l) max(proyeccion(img[i], centros[fuera]), 0.5f + casi) else 1.0f
            } else {
                val k = et(e, i)
                val juntoAlSolape = lados.any { (dx, dy) -> dentroDe(debajo, bx + dx, by + dy) }
                if (k == l || juntoAlSolape) 0.0f else min(proyeccion(img[i], centros[k]), 0.5f - casi)
            }
            campo[(y + BORDE - 1) * gw + x + BORDE - 1] = v
        }
        val tmp = campo.copyOf()
        for (y in 0 until gh) for (x in 1 until gw - 1) { val i = y * gw + x; tmp[i] = 0.25f * campo[i - 1] + 0.5f * campo[i] + 0.25f * campo[i + 1] }
        val suave = FloatArray(gw * gh)
        for (y in 1 until gh - 1) for (x in 1 until gw - 1) { val i = y * gw + x; suave[i] = 0.25f * tmp[i - gw] + 0.5f * tmp[i] + 0.25f * tmp[i + gw] }
        return Triple(suave, gw, gh)
    }

    private fun huecoDeFondo(hueco: List<P>, mascara: BooleanArray, bw: Int, bh: Int, esFondo: (Int, Int) -> Boolean): Boolean {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (q in hueco) { x0 = min(x0, q.x); y0 = min(y0, q.y); x1 = max(x1, q.x); y1 = max(y1, q.y) }
        var fondo = 0; var total = 0
        for (y in max(y0, 0.0).toInt() until min(ceil(y1).toInt(), bh)) for (x in max(x0, 0.0).toInt() until min(ceil(x1).toInt(), bw)) {
            if (mascara[y * bw + x] || !dentro(hueco, P(x + 0.5, y + 0.5))) continue
            total++
            if (esFondo(x, y)) fondo++
        }
        return total > 0 && fondo * 2 >= total
    }

    private fun dentro(pol: List<P>, p: P): Boolean {
        var c = false
        val n = pol.size
        var j = n - 1
        for (i in 0 until n) {
            val (xi, yi) = pol[i]; val (xj, yj) = pol[j]
            if ((yi > p.y) != (yj > p.y) && p.x < (xj - xi) * (p.y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** **El contorno hecho curva**: Bézier que no se apartan más de [tol], aplanadas con [plano]. */
    fun curva(p: List<P>, cerrado: Boolean, tol: Double, plano: Double): List<P> =
        Trazado.aplanar(Trazado.ajustar(p, cerrado, tol), cerrado, plano)

    // ---------------------------------------------------------------------- solo contornos

    private fun contornos(img: Array<FloatArray>, w: Int, h: Int, p: Parametros, plano: Double): MutableList<Trazo> {
        if (w < 3 || h < 3) return ArrayList()
        val gris = FloatArray(img.size) { 0.299f * img[it][0] + 0.587f * img[it][1] + 0.114f * img[it][2] }
        val suave = gauss(gris, w, h)
        val mag = FloatArray(w * h)
        val dir = ByteArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            fun g(dx: Int, dy: Int) = suave[(y + dy) * w + x + dx]
            val gx = g(1, -1) + 2.0f * g(1, 0) + g(1, 1) - g(-1, -1) - 2.0f * g(-1, 0) - g(-1, 1)
            val gy = g(-1, 1) + 2.0f * g(0, 1) + g(1, 1) - g(-1, -1) - 2.0f * g(0, -1) - g(1, -1)
            mag[y * w + x] = sqrt(gx * gx + gy * gy)
            val a0 = Math.toDegrees(atan2(gy, gx).toDouble()).toFloat()
            val a = ((a0 % 180.0f) + 180.0f) % 180.0f
            dir[y * w + x] = (if (a < 22.5f || a >= 157.5f) 0 else if (a < 67.5f) 1 else if (a < 112.5f) 2 else 3).toByte()
        }
        val nms = FloatArray(w * h)
        val finoX = FloatArray(w * h); val finoY = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val (ab, paso) = when (dir[i].toInt()) {
                0 -> (i - 1 to i + 1) to (1.0f to 0.0f)
                1 -> (i - w - 1 to i + w + 1) to (1.0f to 1.0f)
                2 -> (i - w to i + w) to (0.0f to 1.0f)
                else -> (i - w + 1 to i + w - 1) to (-1.0f to 1.0f)
            }
            val (a, b) = ab
            if (mag[i] >= mag[a] && mag[i] >= mag[b]) {
                nms[i] = mag[i]
                val den = mag[a] - 2.0f * mag[i] + mag[b]
                val eta = if (den < -1e-6f) ((mag[a] - mag[b]) / (2.0f * den)).coerceIn(-0.5f, 0.5f) else 0.0f
                finoX[i] = eta * paso.first; finoY[i] = eta * paso.second
            }
        }
        val maximo = nms.fold(0.0f) { m, v -> max(m, v) }
        if (maximo < 8.0f) return ArrayList()
        val vivos = nms.filter { it > maximo * 0.02f }.sorted()
        val percentil = when (p.detalle) { Detalle.BAJO -> 0.85f; Detalle.MEDIO -> 0.7f; Detalle.ALTO -> 0.55f }
        val alto = max(max(min(vivos[((vivos.size - 1) * percentil).toInt()], maximo * 0.45f), maximo * 0.1f), 20.0f)
        val bajo = alto * 0.4f
        val borde = BooleanArray(w * h)
        val pila = ArrayList<Int>()
        for (i in 0 until w * h) if (nms[i] >= alto) { pila += i; borde[i] = true }
        while (pila.isNotEmpty()) {
            val i = pila.removeAt(pila.size - 1)
            val x = i % w; val y = i / w
            for (yy in max(y - 1, 0) until min(y + 2, h)) for (xx in max(x - 1, 0) until min(x + 2, w)) {
                val j = yy * w + xx
                if (!borde[j] && nms[j] >= bajo) { borde[j] = true; pila += j }
            }
        }
        val fondo = run {
            val s = FloatArray(3); var n = 0.0f
            for (x in 0 until w) for (c in listOf(img[x], img[(h - 1) * w + x])) { for (k in 0 until 3) s[k] += c[k]; n += 1.0f }
            floatArrayOf(s[0] / n, s[1] / n, s[2] / n)
        }
        val tol = p.detalle.tolerancia
        val salida = ArrayList<Trazo>()
        for (cadena in cadenas(borde, w, h)) {
            if (cadena.size < p.detalle.cadenaMinima) continue
            val cerrada = cadena.size > 4 && run {
                val a = cadena[0]; val b = cadena[cadena.size - 1]
                abs(a.first - b.first) <= 1 && abs(a.second - b.second) <= 1
            }
            val color = if (p.colores) {
                val s = FloatArray(3); var n = 0.0f
                for (idx in cadena.indices step 2) {
                    val (x, y) = cadena[idx]
                    var mejor = img[y * w + x]
                    var md = d2(mejor, fondo)
                    for ((dx, dy) in listOf(-2 to 0, 2 to 0, 0 to -2, 0 to 2)) {
                        val xx = x + dx; val yy = y + dy
                        if (xx >= 0 && yy >= 0 && xx < w && yy < h) {
                            val c = img[yy * w + xx]
                            if (d2(c, fondo) > md) { md = d2(c, fondo); mejor = c }
                        }
                    }
                    for (k in 0 until 3) s[k] += mejor[k]
                    n += 1.0f
                }
                hex(floatArrayOf(s[0] / n, s[1] / n, s[2] / n))
            } else "#1e1e1e"
            val puntos0 = cadena.map { (x, y) -> P(x + 0.5 + finoX[y * w + x].toDouble(), y + 0.5 + finoY[y * w + x].toDouble()) }
            val puntos = curva(puntos0, cerrada, tol, plano).toMutableList()
            if (cerrada && puntos.size >= 3) puntos += puntos[0]
            if (puntos.size >= 2) salida += Trazo.Linea(puntos, cerrada, color)
        }
        return salida
    }

    private fun gauss(g: FloatArray, w: Int, h: Int): FloatArray {
        val k = floatArrayOf(1.0f / 16.0f, 4.0f / 16.0f, 6.0f / 16.0f, 4.0f / 16.0f, 1.0f / 16.0f)
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0f
            for (i in k.indices) s += k[i] * g[y * w + (x + i - 2).coerceIn(0, w - 1)]
            tmp[y * w + x] = s
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0f
            for (i in k.indices) s += k[i] * tmp[(y + i - 2).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = s
        }
        return out
    }

    private fun cadenas(borde: BooleanArray, w: Int, h: Int): List<List<Pair<Int, Int>>> {
        val dirs = arrayOf(1 to 0, 0 to 1, -1 to 0, 0 to -1, 1 to 1, -1 to 1, -1 to -1, 1 to -1)
        fun vecinos(x: Int, y: Int): List<Pair<Int, Int>> = dirs.mapNotNull { (dx, dy) ->
            val xx = x + dx; val yy = y + dy
            if (xx >= 0 && yy >= 0 && xx < w && yy < h && borde[yy * w + xx]) xx to yy else null
        }
        val salida = ArrayList<List<Pair<Int, Int>>>()
        for (pasada in 0 until 2) for (y in 0 until h) for (x in 0 until w) {
            if (!borde[y * w + x]) continue
            if (pasada == 0 && vecinos(x, y).size != 1) continue
            val cadena = arrayListOf(x to y)
            borde[y * w + x] = false
            var cx = x; var cy = y
            while (true) {
                val (nx, ny) = vecinos(cx, cy).firstOrNull() ?: break
                borde[ny * w + nx] = false
                cadena += nx to ny
                cx = nx; cy = ny
            }
            salida += cadena
        }
        return salida
    }
}
