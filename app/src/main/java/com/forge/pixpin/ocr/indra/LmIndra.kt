package com.forge.pixpin.ocr.indra

import com.forge.pixpin.ocr.CorrectorOcr
import com.forge.pixpin.ocr.DesempateOcr
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * **El modelo de lenguaje n-grama de Indra** (`lm.npz`) y la búsqueda en haz CTC de las líneas
 * dudosas (26-oct-2026, `IndraLm.kt` del escáner, de `indra_ocr/lm.py`). `lm.npz` = ZIP de `.npy`
 * sin pickle: por largo de historia n, claves ordenadas de los pares (historia, ficha) con su
 * log-probabilidad (`k<n>`, `v<n>`) y claves de historias con su peso de retroceso (`hk<n>`,
 * `hb<n>`). Una clave empaqueta fichas de [bits] bits (0 = inicio de línea, t + 1 = ficha t).
 *
 * Banco del escáner: sin el LM el manuscrito inglés (GNHK) baja de 49,7 a 31,4 % de palabras.
 *
 * El sesgo de palabras («Mi vocabulario» y el diccionario) es el [CorrectorOcr.Sesgo] que ya
 * estaba portado del PC: el mismo número en los tres aparatos.
 */
class LmIndra private constructor(
    val orden: Int,
    val tamanoVocab: Int,
    val bits: Int,
    private val k: Array<LongArray>,
    private val v: Array<FloatArray>,
    private val hk: Array<LongArray>,
    private val hb: Array<FloatArray>
) {
    private val cache = HashMap<Long, Double>()

    /** Cota superior de cualquier [logpClave] (poda de la búsqueda en haz). */
    val maxLogp: Double = run {
        var top = -ln(tamanoVocab.toDouble())
        for (arr in v) for (x in arr) if (x > top) top = x.toDouble()
        var bo = 0.0
        for (arr in hb) if (arr.isNotEmpty()) bo += max(0.0, arr.max().toDouble())
        top + bo
    }

    private fun busca(claves: LongArray, clave: Long): Int = java.util.Arrays.binarySearch(claves, clave).let { if (it >= 0) it else -1 }

    /**
     * log P(ficha | historia) de una clave = las últimas orden − 1 fichas de la historia y luego la
     * ficha, empaquetadas (las historias cortas son los bits bajos). Igual que `NgramLM.logp_keys`.
     */
    fun logpClave(entera: Long): Double {
        cache[entera]?.let { return it }
        val b = bits
        val ct = entera and ((1L shl b) - 1)
        val hist = entera ushr b
        val n = orden
        val hOk = BooleanArray(n); val hbv = DoubleArray(n); val pOk = BooleanArray(n); val pv = DoubleArray(n)
        for (i in 0 until n) {
            val mascara = if (i == 0) 0L else (1L shl (b * i)) - 1
            val hq = hist and mascara
            val hi = busca(hk[i], hq)
            hOk[i] = hi >= 0
            hbv[i] = if (hi >= 0) hb[i][hi].toDouble() else 0.0
            val pi = busca(k[i], (hq shl b) or ct)
            pOk[i] = pi >= 0
            pv[i] = if (pi >= 0) v[i][pi].toDouble() else 0.0
        }
        hOk[0] = true
        var m = 0
        for (i in 1 until n) { if (hOk[i]) m++ else break }
        var elegido = -1
        for (i in m downTo 0) if (pOk[i]) { elegido = i; break }
        val res = if (elegido >= 0) {
            var acc = 0.0
            for (i in elegido + 1..m) acc += hbv[i]
            acc + pv[elegido]
        } else {
            var acc = 0.0
            for (i in 0..m) acc += hbv[i]
            acc - ln(tamanoVocab.toDouble())
        }
        if (cache.size < CACHE_MAX) cache[entera] = res
        return res
    }

    /** **El LM para desempatar** en el corrector: log P de la palabra con espacio delante y detrás; NaN si no la sabe escribir. */
    fun desempate(vocab: VocabIndra) = DesempateOcr { palabra ->
        val ids = ArrayList<Int>()
        vocab.espacio?.let { ids += it }
        var sabe = true
        for (ch in palabra) { val c = vocab.indice[ch.toString()]; if (c == null) { sabe = false; break }; ids += c }
        if (!sabe) return@DesempateOcr Double.NaN
        vocab.espacio?.let { ids += it }
        var hist = 0L
        val hmask = (1L shl (bits * (orden - 1))) - 1
        var s = 0.0
        for (id in ids) {
            val clave = (hist shl bits) or (id + 1).toLong()
            s += logpClave(clave)
            hist = clave and hmask
        }
        s
    }

    private class Haz(val b: Double, val nb: Double, val s: Double, val pasos: List<Int>, val hk: Long)

    private fun letraDePalabra(vocab: VocabIndra, id: Int): String? {
        val c = vocab.letras.getOrNull(id - 1) ?: return null
        return if (c.length == 1 && c[0].isLetterOrDigit()) c.lowercase() else null
    }

    /** Palabra en curso al final de [prefijo] (minúsculas). */
    private fun palabraFinal(vocab: VocabIndra, prefijo: List<Int>): String {
        var i = prefijo.size
        while (i > 0 && letraDePalabra(vocab, prefijo[i - 1]) != null) i--
        if (i == prefijo.size) return ""
        val sb = StringBuilder()
        for (q in i until prefijo.size) sb.append(letraDePalabra(vocab, prefijo[q]))
        return sb.toString()
    }

    private fun deltaDeSesgo(sesgo: CorrectorOcr.Sesgo, vocab: VocabIndra, prefijo: List<Int>, c: Int): Double {
        val w = palabraFinal(vocab, prefijo)
        val ch = letraDePalabra(vocab, c)
        return if (ch != null) sesgo.parcial(w + ch) - (if (w.isEmpty()) 0.0 else sesgo.parcial(w))
        else if (w.isEmpty()) 0.0 else sesgo.completa(w) - sesgo.parcial(w)
    }

    /**
     * **Búsqueda en haz CTC por prefijos** (`beam_search` de `lm.py`). [blanco]: log-prob del blanco
     * por paso; [topv]/[topi]: las [kTop] mejores clases por paso (blanco incluido) y su log-prob,
     * fila a fila. Devuelve (clases, paso en que se emitió cada una).
     */
    fun buscarEnHaz(
        blanco: FloatArray, topv: FloatArray, topi: LongArray, kTop: Int, pasos: Int,
        alfa: Double, beta: Double, haz: Int = 8, saltoBlanco: Double = 0.999, sueloCandidato: Double = 1e-3,
        sesgo: CorrectorOcr.Sesgo? = null, vocab: VocabIndra? = null
    ): Pair<IntArray, IntArray> {
        val sg = if (sesgo != null && vocab != null && sesgo.activo) sesgo else null
        val b = bits
        val hmask = (1L shl (b * (orden - 1))) - 1
        var haces = LinkedHashMap<List<Int>, Haz>()
        haces[emptyList()] = Haz(0.0, Double.NEGATIVE_INFINITY, 0.0, emptyList(), 0L)
        val logSalto = ln(saltoBlanco)
        val logSuelo = ln(sueloCandidato)
        val alcance = beta + alfa * maxLogp + (sg?.pasoMax ?: 0.0)
        for (t in 0 until pasos) {
            val b0 = blanco[t].toDouble()
            if (b0 > logSalto) {
                val nh = LinkedHashMap<List<Int>, Haz>()
                for ((p, e) in haces) nh[p] = Haz(lse(e.b, e.nb) + b0, Double.NEGATIVE_INFINITY, e.s, e.pasos, e.hk)
                haces = nh
                continue
            }
            val sig = LinkedHashMap<List<Int>, Haz>()
            fun anadir(prefijo: List<Int>, bb: Double, nbb: Double, s: Double, st: List<Int>, h: Long) {
                val viejo = sig[prefijo]
                sig[prefijo] = if (viejo == null) Haz(bb, nbb, s, st, h) else Haz(lse(viejo.b, bb), lse(viejo.nb, nbb), viejo.s, viejo.pasos, viejo.hk)
            }
            val candC = ArrayList<Int>(kTop)
            val candV = ArrayList<Double>(kTop)
            for (j in 0 until kTop) {
                val c = topi[t * kTop + j].toInt()
                val lv = topv[t * kTop + j].toDouble()
                if (c != 0 && lv >= logSuelo) { candC += c; candV += lv }
            }
            for ((prefijo, e) in haces) {
                anadir(prefijo, lse(e.b, e.nb) + b0, Double.NEGATIVE_INFINITY, e.s, e.pasos, e.hk)
                val ultimo = prefijo.lastOrNull()
                for (j in candC.indices) if (candC[j] == ultimo) anadir(prefijo, Double.NEGATIVE_INFINITY, e.nb + candV[j], e.s, e.pasos, e.hk)
            }
            val suelo = if (haces.size >= haz) sig.values.minOf { lse(it.b, it.nb) + it.s } else Double.NEGATIVE_INFINITY
            class Ext(val prefijo: List<Int>, val c: Int, val nuevo: Double, val s: Double, val st: List<Int>, val h: Long)
            val ext = ArrayList<Ext>()
            for ((prefijo, e) in haces) {
                val tot = lse(e.b, e.nb)
                val ultimo = prefijo.lastOrNull()
                for (j in candC.indices) {
                    val c = candC[j]
                    val nuevo = if (c == ultimo) e.b + candV[j] else tot + candV[j]
                    if (nuevo == Double.NEGATIVE_INFINITY) continue
                    val q = prefijo + c
                    if (q in sig) anadir(q, Double.NEGATIVE_INFINITY, nuevo, e.s, e.pasos, e.hk)
                    else if (nuevo + e.s + alcance >= suelo - 1e-9) ext += Ext(prefijo, c, nuevo, e.s, e.pasos, e.hk)
                }
            }
            for (x in ext) {
                val lmp = logpClave((x.h shl b) or (x.c + 1).toLong())
                val wd = if (sg != null) deltaDeSesgo(sg, vocab!!, x.prefijo, x.c) else 0.0
                anadir(x.prefijo + x.c, Double.NEGATIVE_INFINITY, x.nuevo, x.s + alfa * lmp + beta + wd, x.st + t, ((x.h shl b) or (x.c + 1).toLong()) and hmask)
            }
            val ordenados = sig.entries.sortedByDescending { lse(it.value.b, it.value.nb) + it.value.s }
            haces = LinkedHashMap<List<Int>, Haz>().apply { for (en in ordenados.take(haz)) put(en.key, en.value) }
        }
        var mejor: Map.Entry<List<Int>, Haz>? = null
        var nota = Double.NEGATIVE_INFINITY
        for (en in haces) {
            var sc = lse(en.value.b, en.value.nb) + en.value.s
            if (sg != null) {
                // Fin de línea: la palabra en curso se cierra.
                val w = palabraFinal(vocab!!, en.key)
                if (w.isNotEmpty()) sc += sg.completa(w) - sg.parcial(w)
            }
            if (mejor == null || sc > nota) { mejor = en; nota = sc }
        }
        return mejor!!.key.toIntArray() to mejor.value.pasos.toIntArray()
    }

    companion object {
        private const val TOPE_DE_CLAVE = 61
        /** Tope del caché de log-probabilidades (~15 MB; Python usa 1 M de entradas, mucho para un móvil). */
        private const val CACHE_MAX = 200_000

        fun lse(a: Double, b: Double): Double {
            if (a == Double.NEGATIVE_INFINITY) return b
            if (b == Double.NEGATIVE_INFINITY) return a
            val m = max(a, b)
            return m + ln(exp(a - m) + exp(b - m))
        }

        fun cargar(fichero: File): LmIndra {
            val arrays = HashMap<String, Npy>()
            ZipFile(fichero).use { z ->
                for (e in z.entries()) {
                    if (!e.name.endsWith(".npy")) continue
                    z.getInputStream(e).use { arrays[e.name.removeSuffix(".npy")] = Npy.leer(it) }
                }
            }
            val orden = arrays.getValue("order").entero(0).toInt()
            val vocab = arrays.getValue("vocab_size").entero(0).toInt()
            val bits = arrays["bits"]?.entero(0)?.toInt() ?: 11
            require(orden * bits <= TOPE_DE_CLAVE) { "orden $orden × $bits bits no caben en una clave" }
            return LmIndra(
                orden, vocab, bits,
                Array(orden) { arrays.getValue("k$it").enteros() },
                Array(orden) { arrays.getValue("v$it").reales() },
                Array(orden) { arrays.getValue("hk$it").enteros() },
                Array(orden) { arrays.getValue("hb$it").reales() }
            )
        }
    }
}

/** **Lector mínimo de `.npy`** (v1/v2/v3, little-endian, u8/i8/u4/i4/f4/f8, orden C). */
internal class Npy(val descr: String, val cuantos: Int, val datos: ByteBuffer) {
    fun entero(i: Int): Long = when (descr.substring(1)) {
        "u8", "i8" -> datos.getLong(i * 8)
        "i4", "u4" -> datos.getInt(i * 4).toLong()
        else -> throw IllegalArgumentException("npy $descr no es entero")
    }

    fun enteros(): LongArray = LongArray(cuantos).also { datos.asLongBuffer().get(it) }

    fun reales(): FloatArray = when (descr.substring(1)) {
        "f4" -> FloatArray(cuantos).also { datos.asFloatBuffer().get(it) }
        "f8" -> FloatArray(cuantos) { datos.getDouble(it * 8).toFloat() }
        else -> throw IllegalArgumentException("npy $descr no es real")
    }

    companion object {
        fun leer(entrada: InputStream): Npy {
            val din = DataInputStream(entrada.buffered())
            val magia = ByteArray(6).also { din.readFully(it) }
            require(magia[0] == 0x93.toByte() && String(magia, 1, 5, Charsets.US_ASCII) == "NUMPY") { "no es .npy" }
            val mayor = din.readUnsignedByte()
            din.readUnsignedByte()
            val largo = if (mayor == 1) {
                val a = din.readUnsignedByte(); val b = din.readUnsignedByte(); a or (b shl 8)
            } else {
                ByteBuffer.wrap(ByteArray(4).also { din.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN).int
            }
            val cabecera = String(ByteArray(largo).also { din.readFully(it) }, Charsets.ISO_8859_1)
            val descr = Regex("'descr':\\s*'([^']+)'").find(cabecera)?.groupValues?.get(1) ?: error("npy sin descr")
            require(descr[0] == '<' || descr[0] == '|') { "npy big-endian no soportado" }
            require(!Regex("'fortran_order':\\s*True").containsMatchIn(cabecera)) { "npy en orden Fortran" }
            val forma = Regex("'shape':\\s*\\(([^)]*)\\)").find(cabecera)?.groupValues?.get(1) ?: error("npy sin shape")
            val dims = forma.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toLong() }
            val cuantos = dims.fold(1L) { a, d -> a * d }.toInt()
            val tam = descr.substring(2).toInt()
            val bytes = ByteArray(cuantos * tam).also { din.readFully(it) }
            return Npy(descr, cuantos, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        }
    }
}
