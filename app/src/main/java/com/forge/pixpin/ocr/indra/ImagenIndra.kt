package com.forge.pixpin.ocr.indra

import com.forge.pixpin.ocr.indra.OpsGris.Interpolacion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * **Pre y posproceso de imagen de Indra** (26-oct-2026, puerto de `IndraImage.kt` del escáner, que
 * lo es de `pipeline.py`, `det_post.py` y `rec_preproc.py`): mismos tamaños (redondeo de Python),
 * mismas reglas de reducir con media y ampliar con bilineal, mismos giros. Donde el escáner llama
 * a OpenCV va [OpsGris] (el `gris.rs` del PC). Cajas: `FloatArray(4)` = x0, y0, x1, y1.
 */
object ImagenIndra {
    const val LADO_PREVIA = 1600
    const val LADO_ORIENTACION = 768
    const val INCLINACION_MAX = 45.0
    /** Inclinaciones menores no se enderezan (el paso de la búsqueda es 0,1). */
    const val INCLINACION_MIN = 0.3
    const val MULTIPLO_ENTRADA = 32
    const val MARGEN_Y = 0.2
    const val MARGEN_X = 0.3
    const val MULTIPLO_ANCHO = 8

    private fun reducirA(g: Gris, lado: Int, modo: Interpolacion): Gris {
        val s = lado.toDouble() / max(g.ancho, g.alto)
        return OpsGris.redimensionar(g, max(1, OpsGris.redondeoPy(g.ancho * s)), max(1, OpsGris.redondeoPy(g.alto * s)), modo)
    }

    /** La página reducida una vez a como mucho [lado] px: la leen la búsqueda de la inclinación y la orientación. */
    fun previa(g: Gris, lado: Int = LADO_PREVIA): Gris =
        if (lado.toDouble() / max(g.ancho, g.alto) >= 1.0) g else reducirA(g, lado, Interpolacion.AREA)

    /** Mapa de tinta (255 = tinta) reducido a [lado]. */
    private fun miniaturaDeTinta(g: Gris, lado: Int): Gris {
        val m = reducirA(g, lado, Interpolacion.AREA)
        for (i in m.px.indices) m.px[i] = (255 - (m.px[i].toInt() and 0xFF)).toByte()
        return m
    }

    /** Lo bruscamente que cambia el perfil horizontal de tinta de fila a fila con la página girada [a] grados. */
    private fun nitidezDeFilas(tinta: Gris, a: Double): Double {
        val m = OpsGris.matrizDeGiro(tinta.ancho / 2.0, tinta.alto / 2.0, a)
        val r = OpsGris.girarAfin(tinta, m, OpsGris.Borde.fijo(0))
        var previo = -1L
        var acc = 0.0
        for (y in 0 until r.alto) {
            var s = 0L
            val o = y * r.ancho
            for (x in 0 until r.ancho) s += r.px[o + x].toInt() and 0xFF
            if (previo >= 0) { val d = (s - previo).toDouble(); acc += d * d }
            previo = s
        }
        return acc
    }

    /** Elemento [i] de `np.arange(inicio, …, paso)`: numpy suma `i · ((inicio + paso) − inicio)`. */
    private fun npArange(inicio: Double, paso: Double, i: Int): Double = inicio + i * ((inicio + paso) - inicio)

    /** Grados (antihorario) que enderezan los renglones (`estimate_skew`). */
    fun estimarInclinacion(g: Gris, lado: Int = 800): Double {
        val fina = miniaturaDeTinta(g, lado)
        var suma = 0L
        for (b in fina.px) suma += b.toInt() and 0xFF
        if (suma.toDouble() / fina.px.size < 0.003 * 255) return 0.0 // una página en blanco no tiene renglones
        val gruesa = miniaturaDeTinta(g, lado / 2)
        val rejilla = ArrayList<Double>()
        var a = -INCLINACION_MAX
        while (a <= INCLINACION_MAX + 1e-6) { rejilla += a; a += 2.0 }
        val notas = rejilla.map { nitidezDeFilas(gruesa, it) }
        // np.argsort(notas)[-2:] (estable: en empate, el índice mayor al final).
        val idx = notas.indices.sortedWith(compareBy<Int> { notas[it] }.thenBy { it })
        val salidas = LinkedHashSet<Double>().apply { add(0.0); add(rejilla[idx[idx.size - 2]]); add(rejilla[idx[idx.size - 1]]) }
        val cerca = ArrayList<Double>()
        for (a0 in salidas) for (k in 0..8) cerca += npArange(a0 - 2.0, 0.5, k)
        val vistos = LinkedHashMap<Double, Double>()
        for (x in cerca) if (x !in vistos) vistos[x] = nitidezDeFilas(fina, x)
        var a1 = cerca[0]
        var b1 = vistos.getValue(a1)
        for (x in cerca) { val v = vistos.getValue(x); if (v > b1) { b1 = v; a1 = x } }
        var mejor = a1 - 0.5
        var bv = Double.NEGATIVE_INFINITY
        for (k in 0..10) {
            val x = npArange(a1 - 0.5, 0.1, k)
            val v = nitidezDeFilas(fina, x)
            if (v > bv) { bv = v; mejor = x }
        }
        return mejor
    }

    /** La página girada [grados] (antihorario) sobre su centro, mismo tamaño, borde replicado (`rotate_page`). */
    fun girarPagina(g: Gris, grados: Double): Gris =
        OpsGris.girarAfin(g, OpsGris.matrizDeGiro(g.ancho / 2.0, g.alto / 2.0, grados), OpsGris.Borde.REPLICAR)

    /** Una caja de la página que [girarPagina] hizo, devuelta a la de antes (`unrotate_box`). */
    fun desgirarCaja(b: FloatArray, grados: Double, ancho: Int, alto: Int): FloatArray {
        val m = OpsGris.matrizDeGiro(ancho / 2.0, alto / 2.0, -grados)
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for ((px, py) in listOf(b[0] to b[1], b[2] to b[1], b[0] to b[3], b[2] to b[3])) {
            val x = m[0] * px + m[1] * py + m[2]
            val y = m[3] * px + m[4] * py + m[5]
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        fun r1(v: Double, tope: Int) = (Math.round(v.coerceIn(0.0, tope.toDouble()) * 10) / 10.0).toFloat()
        return floatArrayOf(r1(x0, ancho), r1(y0, alto), r1(x1, ancho), r1(y1, alto))
    }

    /** Entrada de `orient.onnx`: `[lado, lado]` de tinta (1 = tinta), el lado largo a [lado] y relleno (`orient_input`). */
    fun entradaOrientacion(g: Gris, lado: Int = LADO_ORIENTACION): FloatArray {
        val p = reducirA(g, lado, Interpolacion.AREA)
        val v = FloatArray(lado * lado)
        for (y in 0 until min(lado, p.alto)) for (x in 0 until min(lado, p.ancho)) v[y * lado + x] = 1f - p.en(x, y) / 255f
        return v
    }

    /** Entrada del detector (`page_to_input`): el lado largo a `ladoLargo`, relleno hasta múltiplos de 32. */
    class EntradaDetector(val datos: FloatArray, val ancho: Int, val alto: Int, val escala: Double)

    fun entradaDetector(g: Gris, ladoLargo: Int): EntradaDetector {
        val escala = ladoLargo.toDouble() / max(g.ancho, g.alto)
        val nh = max(1, OpsGris.redondeoPy(g.alto * escala))
        val nw = max(1, OpsGris.redondeoPy(g.ancho * escala))
        val r = OpsGris.redimensionar(g, nw, nh, if (escala < 1) Interpolacion.AREA else Interpolacion.LINEAL)
        val ph = (nh + MULTIPLO_ENTRADA - 1) / MULTIPLO_ENTRADA * MULTIPLO_ENTRADA
        val pw = (nw + MULTIPLO_ENTRADA - 1) / MULTIPLO_ENTRADA * MULTIPLO_ENTRADA
        val datos = FloatArray(ph * pw)
        for (y in 0 until nh) for (x in 0 until nw) datos[y * pw + x] = 1f - r.en(x, y) / 255f
        return EntradaDetector(datos, pw, ph, escala)
    }

    // ---- De la salida de det.onnx a renglones (det_post.decode, `deteccion.rs` del PC) ----

    /** La salida del detector va a la mitad de la entrada. */
    const val PASO_SALIDA = 2.0
    /** El núcleo es más corto que el renglón esta fracción de su alto por lado. */
    const val ENCOGE_NUCLEO = 0.15

    /**
     * Cajas de renglón en px de la ENTRADA del detector. [salida]: los tres primeros planos
     * `[3, alto, ancho]` (el logit del núcleo y las distancias al borde de arriba y de abajo).
     */
    fun decodificar(salida: FloatArray, ancho: Int, alto: Int, umbral: Float = 0.5f, areaMin: Int = 2): List<FloatArray> {
        val plano = ancho * alto
        if (plano == 0 || salida.size < 3 * plano) return emptyList()
        val mascara = ByteArray(plano) { if (1f / (1f + exp(-salida[it])) > umbral) 1 else 0 }
        val (etiquetas, manchas) = OpsGris.componentes(mascara, ancho, alto)
        if (manchas.isEmpty()) return emptyList()
        val n = manchas.size + 1
        val cuenta = DoubleArray(n); val arriba = DoubleArray(n); val abajo = DoubleArray(n)
        for (i in 0 until plano) {
            val e = etiquetas[i]
            if (e == 0) continue
            val ys = (i / ancho + 0.5) * PASO_SALIDA
            cuenta[e] += 1.0
            arriba[e] += ys - salida[plano + i]
            abajo[e] += ys + salida[2 * plano + i]
        }
        val cajas = ArrayList<FloatArray>()
        for ((k, m) in manchas.withIndex()) {
            val e = k + 1
            if (m.area < areaMin) continue
            val c = max(cuenta[e], 1.0)
            val y0 = arriba[e] / c
            val y1 = abajo[e] / c
            val lh = max(1.0, y1 - y0)
            val ex = min(ENCOGE_NUCLEO * lh, 0.5 * lh)
            cajas += floatArrayOf((m.izquierda * PASO_SALIDA - ex).toFloat(), y0.toFloat(), ((m.izquierda + m.ancho) * PASO_SALIDA + ex).toFloat(), y1.toFloat())
        }
        return cajas
    }

    // ---- Recortes de renglón (rec_preproc.py) ----

    /** `np.percentile(b, (2, 50, 98))` de un recorte, desde su histograma. */
    private fun percentiles(g: Gris, x0: Int, y0: Int, x1: Int, y1: Int): DoubleArray {
        val hist = LongArray(256)
        var n = 0L
        for (y in y0 until y1) for (x in x0 until x1) { hist[g.en(x, y)]++; n++ }
        val cum = LongArray(256)
        var c = 0L
        for (i in 0 until 256) { c += hist[i]; cum[i] = c }
        fun derecha(v: Long): Double { // np.searchsorted(cum, v, side="right")
            var lo = 0; var hi = 256
            while (lo < hi) { val m = (lo + hi) ushr 1; if (cum[m] <= v) lo = m + 1 else hi = m }
            return lo.toDouble()
        }
        return doubleArrayOf(0.02, 0.5, 0.98).map { q ->
            val x = q * max(n - 1, 0)
            val i = floor(x).toLong()
            val f = x - i
            val lo = derecha(i)
            val hi = derecha(min(i + 1, max(n - 1, 0)))
            val d = hi - lo
            if (f >= 0.5) hi - d * (1 - f) else lo + d * f
        }.toDoubleArray()
    }

    /** Texto claro sobre fondo oscuro (`is_dark_line`). */
    fun lineaOscura(g: Gris, b: FloatArray): Boolean {
        val x0 = max(0, b[0].toInt()); val y0 = max(0, b[1].toInt())
        val x1 = max(0, min(g.ancho, b[2].toInt() + 1)); val y1 = max(0, min(g.alto, b[3].toInt() + 1))
        if (x1 <= x0 || y1 <= y0) return false
        val p = percentiles(g, x0, y0, x1, y1)
        return p[2] - p[1] > 2 * (p[1] - p[0]) + 8
    }

    /** Un renglón listo para el reconocedor: mapa de tinta [alto] × [ancho]. */
    class Recorte(val datos: FloatArray, val ancho: Int, val alto: Int)

    /** Recorte de un renglón a `alto · ventana` px (`crop_line`), con margen y escalado sin deformar. */
    fun recortarLinea(g: Gris, b: FloatArray, alto: Int, ventana: Int = 1, oscura: Boolean? = null, anchoMax: Int = 4096): Recorte {
        val margenY = (ventana * (1 + 2 * MARGEN_Y) - 1) / 2
        val hh = alto * ventana
        val x0 = b[0].toDouble(); val y0 = b[1].toDouble(); val x1 = b[2].toDouble(); val y1 = b[3].toDouble()
        val h = max(1.0, y1 - y0)
        val ix0 = floor(x0 - MARGEN_X * h).toInt(); val iy0 = floor(y0 - margenY * h).toInt()
        val ix1 = ceil(x1 + MARGEN_X * h).toInt(); val iy1 = ceil(y1 + margenY * h).toInt()
        val cw = max(1, ix1 - ix0); val ch = max(1, iy1 - iy0)
        val sx0 = max(0, ix0); val sy0 = max(0, iy0); val sx1 = min(g.ancho, ix1); val sy1 = min(g.alto, iy1)
        val dk = oscura ?: lineaOscura(g, b)
        val c = ByteArray(cw * ch) { if (dk) 0 else 255.toByte() }
        if (sx1 > sx0 && sy1 > sy0) {
            for (y in sy0 until sy1) System.arraycopy(g.px, y * g.ancho + sx0, c, (y - iy0) * cw + (sx0 - ix0), sx1 - sx0)
        }
        val ancho = OpsGris.redondeoPy(cw * hh.toDouble() / ch).coerceIn(MULTIPLO_ANCHO, max(anchoMax, MULTIPLO_ANCHO))
        val r = OpsGris.redimensionar(Gris(cw, ch, c), ancho, hh, if (ch > hh) Interpolacion.AREA else Interpolacion.LINEAL)
        val datos = FloatArray(ancho * hh) {
            val v = (r.px[it].toInt() and 0xFF) / 255f
            if (dk) v else 1f - v
        }
        return Recorte(datos, ancho, hh)
    }

    /**
     * Lotes de anchos parecidos (`width_batches`): del más estrecho al más ancho; un lote se cierra
     * a las [maxLote] líneas, cuando la siguiente es 1,3 veces más ancha que la primera o cuando
     * líneas × ancho pasarían de 64 × 640 columnas.
     */
    fun lotesPorAncho(anchos: IntArray, maxLote: Int = 64): List<List<Int>> {
        val orden = anchos.indices.sortedWith(compareBy<Int> { anchos[it] }.thenBy { it })
        val lotes = ArrayList<MutableList<Int>>()
        for (i in orden) {
            val w = max(anchos[i], 64)
            val ultimo = lotes.lastOrNull()
            if (ultimo != null && ultimo.size < maxLote && w <= 1.3 * max(anchos[ultimo[0]], 64) && (ultimo.size + 1) * w <= 64 * 640) ultimo += i
            else lotes += mutableListOf(i)
        }
        return lotes
    }

    /** ¿Inclinación que merece enderezarse? */
    fun seEndereza(a: Double) = abs(a) >= INCLINACION_MIN
}
