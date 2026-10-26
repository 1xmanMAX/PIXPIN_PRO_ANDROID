package com.forge.pixpin.ocr.indra

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **Una página en gris de 8 bits**, fila a fila (0 negro, 255 blanco) (26-oct-2026, `Gray` de
 * `IndraImage.kt` del escáner = `Gris` de `crates/pixpin-indra/src/gris.rs` del PC).
 */
class Gris(val ancho: Int, val alto: Int, val px: ByteArray) {
    init {
        require(ancho > 0 && alto > 0 && px.size == ancho * alto) { "gris de ${ancho}x$alto con ${px.size} píxeles" }
    }

    fun en(x: Int, y: Int): Int = px[y * ancho + x].toInt() and 0xFF

    companion object {
        fun lisa(ancho: Int, alto: Int, valor: Int): Gris = Gris(ancho, alto, ByteArray(ancho * alto) { valor.toByte() })

        /** Desde ARGB (lo que da `Bitmap.getPixels`): luminancia BT.601 como OpenCV ([OpsGris.luma]). */
        fun deArgb(ancho: Int, alto: Int, argb: IntArray): Gris =
            Gris(ancho, alto, ByteArray(ancho * alto) { OpsGris.luma(argb[it]).toByte() })
    }
}

/**
 * **Lo poco de OpenCV que necesita Indra, sin OpenCV** (26-oct-2026). El escáner usa OpenCV para
 * Android (`org.opencv`, ~10 MB de `.so` por ABI); PixPin no lo lleva y no lo va a llevar por
 * cuatro operaciones. Es el puerto de `gris.rs` del PC, que ya las reescribió con la misma regla
 * que `cv2` (centros de píxel en +0,5, bordes que replican o reflejan como allí, núcleos gaussianos
 * del mismo tamaño) y probó contra Python con los modelos de verdad: **CER 0,00 % frente al
 * original** en sus cuatro imágenes (`docs/ocr.md` del PC). Las sumas en punto fijo de OpenCV dan,
 * como mucho, un nivel de gris de diferencia: no cambia lo que lee el reconocedor.
 */
object OpsGris {

    /** `(r·4899 + g·9617 + b·1868 + 8192) >> 14`: BT.601 con los pesos de OpenCV (`IndraEngine.luma`). */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 4899 + g * 9617 + b * 1868 + 8192) shr 14
    }

    /** `round()` de Python (a la par): los tamaños tienen que salir como allí. */
    fun redondeoPy(x: Double): Int = Math.rint(x).toInt()

    /** Satura a 8 bits como `saturate_cast<uchar>` (la mitad, hacia arriba). */
    fun aByte(v: Float): Byte = (v.coerceIn(0f, 255f) + 0.5f).toInt().toByte()

    /** Los dos modos de `cv2.resize` que usa Indra. */
    enum class Interpolacion {
        /** `INTER_AREA`: media de lo que cae dentro (para reducir). */
        AREA,
        /** `INTER_LINEAR`: bilineal con centros en +0,5 (para ampliar). */
        LINEAL
    }

    /** Pesos de un eje: para cada destino, sus orígenes y pesos (que suman 1). */
    private class Pesos(val origen: Array<IntArray>, val peso: Array<FloatArray>)

    private fun pesosEje(origen: Int, destino: Int, modo: Interpolacion): Pesos {
        val escala = origen.toDouble() / destino
        val os = arrayOfNulls<IntArray>(destino)
        val ps = arrayOfNulls<FloatArray>(destino)
        if (modo == Interpolacion.AREA && escala > 1.0) {
            // Cada destino cubre [d·escala, (d+1)·escala) del origen.
            for (d in 0 until destino) {
                val a = d * escala
                val b = min((d + 1) * escala, origen.toDouble())
                val o = ArrayList<Int>(4)
                val p = ArrayList<Float>(4)
                var s = floor(a).toInt()
                while (s < b && s < origen) {
                    val ini = max(a, s.toDouble())
                    val fin = min(b, (s + 1).toDouble())
                    if (fin > ini) { o += s; p += ((fin - ini) / (b - a)).toFloat() }
                    s++
                }
                os[d] = o.toIntArray(); ps[d] = p.toFloatArray()
            }
        } else {
            for (d in 0 until destino) {
                val x = max((d + 0.5) * escala - 0.5, 0.0)
                val x0 = min(floor(x).toInt(), origen - 1)
                val f = (x - x0).toFloat()
                val x1 = min(x0 + 1, origen - 1)
                if (x1 == x0 || f <= 0f) { os[d] = intArrayOf(x0); ps[d] = floatArrayOf(1f) }
                else { os[d] = intArrayOf(x0, x1); ps[d] = floatArrayOf(1f - f, f) }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return Pesos(os as Array<IntArray>, ps as Array<FloatArray>)
    }

    /** `cv2.resize` a [ancho] × [alto] (separable, en coma flotante). */
    fun redimensionar(g: Gris, ancho: Int, alto: Int, modo: Interpolacion): Gris {
        val w = max(1, ancho)
        val h = max(1, alto)
        if (w == g.ancho && h == g.alto) return g
        val px = pesosEje(g.ancho, w, modo)
        val py = pesosEje(g.alto, h, modo)
        // Primero en horizontal (fila a fila), luego en vertical.
        val medio = FloatArray(w * g.alto)
        for (y in 0 until g.alto) {
            val o = y * g.ancho
            val d = y * w
            for (x in 0 until w) {
                val so = px.origen[x]; val sp = px.peso[x]
                var s = 0f
                for (k in so.indices) s += (g.px[o + so[k]].toInt() and 0xFF) * sp[k]
                medio[d + x] = s
            }
        }
        val sal = ByteArray(w * h)
        for (y in 0 until h) {
            val so = py.origen[y]; val sp = py.peso[y]
            val d = y * w
            for (x in 0 until w) {
                var s = 0f
                for (k in so.indices) s += medio[so[k] * w + x] * sp[k]
                sal[d + x] = aByte(s)
            }
        }
        return Gris(w, h, sal)
    }

    /** `np.rot90(g, k)`: k > 0 antihorario, k < 0 horario. */
    fun rot90(g: Gris, k: Int): Gris {
        val kk = ((k % 4) + 4) % 4
        val w = g.ancho
        val h = g.alto
        return when (kk) {
            0 -> g
            1 -> {
                val o = ByteArray(w * h)
                for (yy in 0 until w) for (xx in 0 until h) o[yy * h + xx] = g.px[xx * w + (w - 1 - yy)]
                Gris(h, w, o)
            }
            2 -> Gris(w, h, ByteArray(w * h) { g.px[w * h - 1 - it] })
            else -> {
                val o = ByteArray(w * h)
                for (yy in 0 until w) for (xx in 0 until h) o[yy * h + xx] = g.px[(h - 1 - xx) * w + yy]
                Gris(h, w, o)
            }
        }
    }

    /** `cv2.getRotationMatrix2D(centro, grados, 1)`: `[a, b, tx, -b, a, ty]`. */
    fun matrizDeGiro(cx: Double, cy: Double, grados: Double): DoubleArray {
        val r = Math.toRadians(grados)
        val a = cos(r)
        val b = sin(r)
        return doubleArrayOf(a, b, (1 - a) * cx - b * cy, -b, a, b * cx + (1 - a) * cy)
    }

    /** Lo que hay fuera de la página al girarla: un valor fijo (`BORDER_CONSTANT`) o el borde (`BORDER_REPLICATE`, null). */
    class Borde private constructor(val fijo: Int?) {
        companion object {
            fun fijo(v: Int) = Borde(v)
            val REPLICAR = Borde(null)
        }
    }

    /** `cv2.warpAffine(g, m, mismo tamaño)` con interpolación bilineal. */
    fun girarAfin(g: Gris, m: DoubleArray, borde: Borde): Gris {
        // OpenCV invierte la matriz y recorre el destino.
        var det = m[0] * m[4] - m[1] * m[3]
        if (abs(det) < 1e-12) det = 1.0
        val a = m[4] / det; val b = -m[1] / det; val c = -m[3] / det; val d = m[0] / det
        val tx = -(a * m[2] + b * m[5])
        val ty = -(c * m[2] + d * m[5])
        val w = g.ancho
        val h = g.alto
        val src = g.px
        fun lee(x: Int, y: Int): Float =
            if (x in 0 until w && y in 0 until h) (src[y * w + x].toInt() and 0xFF).toFloat()
            else borde.fijo?.toFloat() ?: (src[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)].toInt() and 0xFF).toFloat()
        val sal = ByteArray(w * h)
        for (y in 0 until h) {
            // A lo largo de la fila el origen avanza (a, c) por píxel.
            var sx = b * y + tx
            var sy = d * y + ty
            val fila = y * w
            for (x in 0 until w) {
                val x0f = floor(sx)
                val y0f = floor(sy)
                val fx = (sx - x0f).toFloat()
                val fy = (sy - y0f).toFloat()
                val x0 = x0f.toInt()
                val y0 = y0f.toInt()
                val r = if (x0 >= 0 && y0 >= 0 && x0 + 1 < w && y0 + 1 < h) {
                    // Camino rápido: los cuatro vecinos dentro.
                    val i = y0 * w + x0
                    val p00 = (src[i].toInt() and 0xFF).toFloat(); val p01 = (src[i + 1].toInt() and 0xFF).toFloat()
                    val p10 = (src[i + w].toInt() and 0xFF).toFloat(); val p11 = (src[i + w + 1].toInt() and 0xFF).toFloat()
                    (p00 + (p01 - p00) * fx) * (1 - fy) + (p10 + (p11 - p10) * fx) * fy
                } else if (borde.fijo != null && (x0 < -1 || y0 < -1 || x0 >= w || y0 >= h)) {
                    borde.fijo.toFloat()
                } else {
                    (lee(x0, y0) * (1 - fx) + lee(x0 + 1, y0) * fx) * (1 - fy) +
                        (lee(x0, y0 + 1) * (1 - fx) + lee(x0 + 1, y0 + 1) * fx) * fy
                }
                sal[fila + x] = aByte(r)
                sx += a
                sy += c
            }
        }
        return Gris(w, h, sal)
    }

    /** Núcleo de `cv2.getGaussianKernel` para [sigma] (tamaño de 8 bits: `round(sigma·6 + 1) | 1`). */
    fun nucleoGaussiano(sigma: Double): FloatArray {
        val n = max(1, Math.rint(sigma * 6 + 1).toInt() or 1)
        val c = (n - 1) / 2.0
        val v = DoubleArray(n) { exp(-((it - c) * (it - c)) / (2 * sigma * sigma)) }
        val s = v.sum()
        return FloatArray(n) { (v[it] / s).toFloat() }
    }

    /** `BORDER_REFLECT_101` (`gfedcb|abcdefgh|gfedcba`), el de los filtros. */
    private fun refleja(i0: Int, n: Int): Int {
        if (n == 1) return 0
        var i = i0
        while (true) {
            i = when {
                i < 0 -> -i
                i >= n -> 2 * n - 2 - i
                else -> return i
            }
        }
    }

    /** `cv2.GaussianBlur(g, (0, 0), sigma)`, separable, en coma flotante. */
    fun desenfocar(g: Gris, sigma: Double): FloatArray {
        val k = nucleoGaussiano(sigma)
        val r = k.size / 2
        val w = g.ancho
        val h = g.alto
        val medio = FloatArray(w * h)
        val fila = FloatArray(w + 2 * r)
        for (y in 0 until h) {
            for (i in fila.indices) fila[i] = (g.px[y * w + refleja(i - r, w)].toInt() and 0xFF).toFloat()
            for (x in 0 until w) {
                var s = 0f
                for (j in k.indices) s += fila[x + j] * k[j]
                medio[y * w + x] = s
            }
        }
        val sal = FloatArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            for (j in k.indices) {
                val yy = refleja(y + j - r, h) * w
                val kv = k[j]
                for (x in 0 until w) sal[o + x] += medio[yy + x] * kv
            }
        }
        return sal
    }

    /** `cv2.medianBlur(g, 5)` (borde replicado). */
    fun mediana5(g: Gris): Gris {
        val w = g.ancho
        val h = g.alto
        val sal = ByteArray(w * h)
        val ventana = IntArray(25)
        for (y in 0 until h) for (x in 0 until w) {
            var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                ventana[n++] = g.en((x + dx).coerceIn(0, w - 1), (y + dy).coerceIn(0, h - 1))
            }
            ventana.sort()
            sal[y * w + x] = ventana[12].toByte()
        }
        return Gris(w, h, sal)
    }

    /** Elipse de `cv2.getStructuringElement(MORPH_ELLIPSE, (k, k))`: por fila, las columnas `[desde, hasta)` que cuentan. */
    fun elipse(k: Int): List<Pair<Int, Int>> {
        val r = (k / 2).toDouble()
        val c = k / 2
        val inv = if (r > 0) 1.0 / (r * r) else 0.0
        return (0 until k).map { i ->
            val dy = i - r
            if (abs(dy) <= r) {
                val dx = Math.rint(c * sqrt((r * r - dy * dy) * inv)).toInt()
                max(c - dx, 0) to min(c + dx + 1, k)
            } else 0 to 0
        }
    }

    /** Dilata (máximo) o erosiona (mínimo); fuera de la imagen no cuenta (el borde de la morfología de OpenCV). */
    private fun morfologia(g: Gris, el: List<Pair<Int, Int>>, dilatar: Boolean): Gris {
        val k = el.size
        val c = k / 2
        val w = g.ancho
        val h = g.alto
        val sal = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = if (dilatar) 0 else 255
            for (i in 0 until k) {
                val (a, b) = el[i]
                val yy = y + i - c
                if (yy < 0 || yy >= h || a >= b) continue
                val x0 = max(x + a - c, 0)
                val x1 = min(x + b - c, w)
                val o = yy * w
                for (xx in x0 until x1) {
                    val p = g.px[o + xx].toInt() and 0xFF
                    v = if (dilatar) max(v, p) else min(v, p)
                }
            }
            sal[y * w + x] = v.toByte()
        }
        return Gris(w, h, sal)
    }

    /** `cv2.morphologyEx(g, MORPH_CLOSE, elipse k)`: dilatar y luego erosionar. */
    fun cerrar(g: Gris, k: Int): Gris {
        val el = elipse(k)
        return morfologia(morfologia(g, el, true), el, false)
    }

    /** Una mancha de [componentes], con lo que da `connectedComponentsWithStats`. */
    data class Mancha(val izquierda: Int, val arriba: Int, val ancho: Int, val alto: Int, val area: Int)

    /**
     * Componentes conexas (vecindad 4) de [mascara] (≠ 0): la etiqueta de cada píxel (0 fondo,
     * 1… manchas, por orden de aparición fila a fila, como OpenCV) y las manchas (`manchas[e - 1]`
     * es la de la etiqueta e).
     */
    fun componentes(mascara: ByteArray, ancho: Int, alto: Int): Pair<IntArray, List<Mancha>> {
        val etiqueta = IntArray(mascara.size)
        val manchas = ArrayList<Mancha>()
        var pila = IntArray(64)
        for (inicio in mascara.indices) {
            if (mascara[inicio].toInt() == 0 || etiqueta[inicio] != 0) continue
            val e = manchas.size + 1
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = 0; var y1 = 0; var area = 0
            etiqueta[inicio] = e
            var n = 0
            pila[n++] = inicio
            while (n > 0) {
                val i = pila[--n]
                val x = i % ancho
                val y = i / ancho
                x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
                area++
                for (v in 0 until 4) {
                    val j = when (v) {
                        0 -> if (x > 0) i - 1 else -1
                        1 -> if (x + 1 < ancho) i + 1 else -1
                        2 -> if (y > 0) i - ancho else -1
                        else -> if (y + 1 < alto) i + ancho else -1
                    }
                    if (j < 0 || mascara[j].toInt() == 0 || etiqueta[j] != 0) continue
                    etiqueta[j] = e
                    if (n == pila.size) pila = pila.copyOf(n * 2)
                    pila[n++] = j
                }
            }
            manchas += Mancha(x0, y0, x1 - x0 + 1, y1 - y0 + 1, area)
        }
        return etiqueta to manchas
    }
}
