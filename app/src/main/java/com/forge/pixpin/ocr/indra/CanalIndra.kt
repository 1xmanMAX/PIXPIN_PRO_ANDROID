package com.forge.pixpin.ocr.indra

import com.forge.pixpin.ocr.CorrectorOcr
import com.forge.pixpin.ocr.DudasOcr
import com.forge.pixpin.ocr.Hunspell
import com.forge.pixpin.ocr.LineaOcr
import com.forge.pixpin.ocr.PaginaOcr
import com.forge.pixpin.ocr.PalabraOcr
import com.forge.pixpin.ocr.Vocabulario
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * **Quien corre los cuatro modelos de Indra** (`Modelos` de `canal.rs` del PC). En el teléfono,
 * [ModelosPorJna] con el ONNX Runtime que ya trae el APK; en las pruebas, modelos de mentira. Así
 * todo [CanalIndra] se prueba en la JVM sin pesos.
 */
interface ModelosIndra {
    /** `det.onnx` sobre `[1, 1, alto, ancho]`: los planos de salida y su alto y ancho. */
    fun detectar(x: FloatArray, alto: Int, ancho: Int): Triple<FloatArray, Int, Int>

    /** `rec.onnx` sobre `[lote, 1, alto, ancho]` con el ancho útil de cada línea; [conLm] pide también lo de la búsqueda en haz. */
    fun reconocer(x: FloatArray, lote: Int, alto: Int, ancho: Int, anchos: LongArray, conLm: Boolean): SalidaRec

    /** `orient.onnx` sobre `[1, 1, lado, lado]`: las 5 probabilidades (0, 90, 180, 270, vertical), o null sin el modelo. */
    fun orientar(x: FloatArray, lado: Int): FloatArray?

    /** `order.onnx` sobre `[1, n, 16]`, o null sin el modelo. */
    fun ordenar(rasgos: FloatArray, n: Int): SalidaOrden?
}

/** Lo que da `rec.onnx` para un lote: la clase y su probabilidad por paso, los pasos válidos y, para el LM, el blanco y las k mejores. */
class SalidaRec(
    val ids: LongArray,
    val conf: FloatArray,
    val longitudes: LongArray,
    val pasos: Int,
    val blanco: FloatArray? = null,
    val topv: FloatArray? = null,
    val topi: LongArray? = null,
    val k: Int = 0
)

/** Lo que da `order.onnx`: logits por pares `[n · n2]` y, si los tiene, los de clase de bloque y «mismo bloque». */
class SalidaOrden(val a: FloatArray, val n2: Int, val cls: FloatArray? = null, val mismo: FloatArray? = null)

/**
 * **La página entera de Indra** (26-oct-2026, puerto de `IndraOcr.kt` + `IndraEngine.recognizeGray`
 * del escáner; misma forma que `canal.rs` del PC): preparar, enderezar, detectar los renglones,
 * leerlos (con el LM en las líneas dudosas), cortar donde el detector juntó dos columnas, ordenar,
 * agrupar en bloques y corregir con el diccionario y «Mi vocabulario» ([CorrectorOcr]).
 *
 * Solo el reconocedor latino (`rec.onnx`, que lee también manuscrito latino, cirílico y griego):
 * sin CJK, árabe ni índicos, sin matemáticas y sin markdown, como el escáner y el PC.
 *
 * Un reconocimiento a la vez (el caché del LM no es de varios hilos): quien lo tiene ([MotorIndra])
 * lo usa con su cerrojo.
 */
class CanalIndra(val meta: MetaIndra, val lm: LmIndra?) {

    /** Palabra con su caja y su confianza (media de sus letras); [probs] si hay una por carácter. */
    class Palabra(var texto: String, var caja: FloatArray, val confianza: Float, val probs: FloatArray? = null)

    /** Línea en orden de lectura. [dudosa]: confianza entre [SUELO_DUDA] y [CONFIANZA_MIN]; no va al texto plano. */
    class Linea(var caja: FloatArray, var texto: String, val confianza: Float, val palabras: List<Palabra>, val dudosa: Boolean = false)

    /**
     * La página leída. Cajas en px de la página GIRADA [giro] grados (antihorario), ya sin la
     * inclinación pequeña; [ancho] × [alto] = la página girada. [aLaImagen] las devuelve a la imagen.
     */
    class Pagina(
        val lineas: List<Linea>, var bloques: List<OrdenIndra.Bloque>, var giro: Int, var inclinacion: Double,
        val ancho: Int, val alto: Int, val ms: MutableMap<String, Long> = LinkedHashMap()
    ) {
        /** Las líneas seguras, una por renglón. */
        val texto: String get() = lineas.filter { !it.dudosa && it.texto.isNotEmpty() }.joinToString("\n") { it.texto }

        /**
         * **Las cajas de vuelta a la imagen que entró** (`IndraResult.kt`): se deshace el giro de
         * cuartos de vuelta y se escala por [sx], [sy] (px de la imagen por px de lo que leyó
         * Indra). Cambia en el sitio; devuelve el ángulo de las líneas (grados, horario positivo).
         */
        fun aLaImagen(anchoImg: Int, altoImg: Int, sx: Float, sy: Float): Float {
            val k = ((giro / 90) % 4 + 4) % 4
            val w0 = (if (k % 2 == 1) alto else ancho).toFloat()
            val h0 = (if (k % 2 == 1) ancho else alto).toFloat()
            val aw = anchoImg.toFloat()
            val ah = altoImg.toFloat()
            fun mapa(b: FloatArray): FloatArray {
                val r = when (k) {
                    1 -> floatArrayOf(b[1], h0 - b[2], b[3], h0 - b[0])
                    2 -> floatArrayOf(w0 - b[2], h0 - b[3], w0 - b[0], h0 - b[1])
                    3 -> floatArrayOf(w0 - b[3], b[0], w0 - b[1], b[2])
                    else -> b
                }
                return floatArrayOf((r[0] * sx).coerceIn(0f, aw), (r[1] * sy).coerceIn(0f, ah), (r[2] * sx).coerceIn(0f, aw), (r[3] * sy).coerceIn(0f, ah))
            }
            for (l in lineas) {
                l.caja = mapa(l.caja)
                for (w in l.palabras) w.caja = mapa(w.caja)
            }
            bloques = bloques.map { OrdenIndra.Bloque(it.etiqueta, mapa(it.caja), it.lineas) }
            var angulo = (inclinacion - 90.0 * k).toFloat()
            while (angulo <= -180f) angulo += 360f
            while (angulo > 180f) angulo -= 360f
            return angulo
        }
    }

    /** El diccionario español (null: sin corregir con él). */
    var lexico: Hunspell? = null
    /** «Mi vocabulario». */
    var vocabulario: Vocabulario = Vocabulario.deTexto("")
    var orientacionMin: Float = ORIENTACION_MIN
    var sueloDuda: Float = SUELO_DUDA
    /** Preparar la página antes de leer (fondo a blanco y enfoque), salvo si es oscura. */
    var preparar: Boolean = true

    /** **Lee una página**: la prepara, la lee y la corrige. Cajas en px de [g] girada (ver [Pagina]). */
    fun leer(m: ModelosIndra, g: Gris): Pagina {
        val t0 = System.nanoTime()
        val preparada = if (preparar && PrepIndra.conviene(g)) PrepIndra.preparar(g) else g
        val msPrep = ms(t0)
        val p = ejecutar(m, preparada)
        p.ms["prep_ms"] = msPrep
        val t = System.nanoTime()
        corregir(p)
        p.ms["correccion_ms"] = ms(t)
        return p
    }

    /**
     * **La corrección del PC sobre la página de Indra**: se le pasa a [CorrectorOcr] (el puerto de
     * `corrector.rs`, ya en el teléfono) una copia en su forma, y lo que cambie vuelve a las
     * líneas y palabras de aquí. Devuelve los cambios (para el registro).
     */
    fun corregir(p: Pagina): List<com.forge.pixpin.ocr.CambioOcr> {
        val c = CorrectorOcr(lexico, vocabulario)
        val espejo = PaginaOcr(p.lineas.mapTo(ArrayList()) { l ->
            LineaOcr(l.texto, l.palabras.mapTo(ArrayList()) { w -> PalabraOcr(w.texto, w.confianza, w.probs?.toList()) }, l.confianza, l.dudosa)
        })
        val (cambios, _) = c.corregir(espejo, lm?.desempate(meta.vocab))
        if (cambios.isEmpty()) return cambios
        for ((i, l) in p.lineas.withIndex()) {
            val e = espejo.lineas[i]
            l.texto = e.texto
            for ((j, w) in l.palabras.withIndex()) w.texto = e.palabras[j].texto
        }
        return cambios
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    /** La página sin preparar ni corregir (`run` del escáner). */
    fun ejecutar(m: ModelosIndra, g: Gris): Pagina {
        val tiempos = LinkedHashMap<String, Long>()
        val t0 = System.nanoTime()
        val previa = ImagenIndra.previa(g)
        var a = ImagenIndra.estimarInclinacion(previa)
        val k = orientacion(m, if (ImagenIndra.seEndereza(a)) ImagenIndra.girarPagina(previa, a) else previa)
        var pag = if (k != 0) OpsGris.rot90(g, -k) else g
        // Medio giro conserva los renglones; tras un cuarto de vuelta se mide otra vez el ángulo.
        if (k == 1 || k == 3) a = ImagenIndra.estimarInclinacion(OpsGris.rot90(previa, -k))
        val incl = if (ImagenIndra.seEndereza(a)) a else 0.0
        if (incl != 0.0) pag = ImagenIndra.girarPagina(pag, incl)
        tiempos["orientacion_ms"] = ms(t0)
        var t = System.nanoTime()
        val cajas = detectar(m, pag)
        tiempos["deteccion_ms"] = ms(t)
        t = System.nanoTime()
        val lecturas = reconocer(m, pag, cajas, 64)
        tiempos["reconocimiento_ms"] = ms(t)
        t = System.nanoTime()
        val p = ensamblar(m, pag.ancho, pag.alto, cajas, lecturas)
        tiempos["orden_ms"] = ms(t)
        if (incl != 0.0) {
            val w = pag.ancho
            val h = pag.alto
            for (l in p.lineas) {
                l.caja = ImagenIndra.desgirarCaja(l.caja, incl, w, h)
                for (wd in l.palabras) wd.caja = ImagenIndra.desgirarCaja(wd.caja, incl, w, h)
            }
            p.bloques = p.bloques.map { OrdenIndra.Bloque(it.etiqueta, ImagenIndra.desgirarCaja(it.caja, incl, w, h), it.lineas) }
        }
        p.giro = 90 * k
        p.inclinacion = incl
        tiempos["total_ms"] = ms(t0)
        p.ms.putAll(tiempos)
        return p
    }

    /** Cuartos de vuelta (antihorario) del contenido; 0 si ninguno supera el umbral o va en columnas verticales. */
    private fun orientacion(m: ModelosIndra, g: Gris): Int {
        val p = m.orientar(ImagenIndra.entradaOrientacion(g), ImagenIndra.LADO_ORIENTACION) ?: return 0
        if (p.isEmpty()) return 0
        var k = 0
        for (i in p.indices) if (p[i] > p[k]) k = i
        return if (p[k] > orientacionMin && k < 4) k else 0
    }

    private fun detectarA(m: ModelosIndra, g: Gris, lado: Int): Pair<List<FloatArray>, Double> {
        val e = ImagenIndra.entradaDetector(g, lado)
        val (s, ho, wo) = m.detectar(e.datos, e.alto, e.ancho)
        return ImagenIndra.decodificar(s, wo, ho) to e.escala
    }

    /** Renglones en px de la página, con una segunda pasada a más resolución si la letra es diminuta. */
    private fun detectar(m: ModelosIndra, g: Gris): List<FloatArray> {
        val lado = meta.ladoLargo
        var (cajas, s) = detectarA(m, g, lado)
        if (cajas.size >= 8) {
            val med = OrdenIndra.mediana(FloatArray(cajas.size) { cajas[it][3] - cajas[it][1] })
            if (med < ALTO_MIN_ADAPTATIVO) {
                val ls = min(LADO_MAX_ADAPTATIVO.toDouble(), lado * ALTO_OBJETIVO_ADAPTATIVO / max(med.toDouble(), 1.0)).toInt() / 32 * 32
                if (ls > lado) { val r = detectarA(m, g, ls); cajas = r.first; s = r.second }
            }
        }
        val w = g.ancho.toFloat()
        val h = g.alto.toFloat()
        return cajas.map { b ->
            floatArrayOf((b[0] / s).toFloat().coerceIn(0f, w), (b[1] / s).toFloat().coerceIn(0f, h), (b[2] / s).toFloat().coerceIn(0f, w), (b[3] / s).toFloat().coerceIn(0f, h))
        }
    }

    /** Una salida CTC: clase, x en la página y probabilidad de la clase elegida en su paso. */
    private class Ficha(val clase: Int, val x: Float, val p: Float = 1f)

    /** La lectura de un renglón. Bajo [CONFIANZA_MIN] las fichas quedan vacías y la lectura va en las dudosas. */
    private class Lectura(val conf: Float, val fichas: List<Ficha> = emptyList(), val textoDudoso: String = "", val fichasDudosas: List<Ficha> = emptyList())

    private fun reconocer(m: ModelosIndra, g: Gris, cajas: List<FloatArray>, lote: Int): List<Lectura> {
        val n = cajas.size
        val out = MutableList(n) { Lectura(0f) }
        if (n == 0) return out
        val alto = meta.altoRec
        val ventana = meta.ventana
        val oscuras = if (ventana > 1) BooleanArray(n) { ImagenIndra.lineaOscura(g, cajas[it]) } else null
        val recortes = List(n) { ImagenIndra.recortarLinea(g, cajas[it], alto, 1, oscuras?.get(it)) }
        val altos = if (ventana > 1) List(n) { ImagenIndra.recortarLinea(g, cajas[it], alto, ventana, oscuras?.get(it)) } else recortes
        val conLm = lm != null
        val suelo = min(CONFIANZA_MIN, sueloDuda)
        val sesgo = if (lm != null) CorrectorOcr(lexico, vocabulario).sesgo() else null
        for (idx in ImagenIndra.lotesPorAncho(IntArray(n) { recortes[it].ancho }, lote)) {
            val b = idx.size
            val hh = altos[idx[0]].alto
            val wm = (ceil(idx.maxOf { altos[it].ancho } / ImagenIndra.MULTIPLO_ANCHO.toDouble()) * ImagenIndra.MULTIPLO_ANCHO).toInt()
            val arr = FloatArray(b * hh * wm)
            val anchos = LongArray(b)
            for ((k, j) in idx.withIndex()) {
                val c = altos[j]
                for (y in 0 until hh) System.arraycopy(c.datos, y * c.ancho, arr, (k * hh + y) * wm, c.ancho)
                anchos[k] = c.ancho.toLong()
            }
            val r = m.reconocer(arr, b, hh, wm, anchos, conLm)
            val tPasos = r.pasos
            val pasoX = wm.toDouble() / max(tPasos, 1)
            for ((k, j) in idx.withIndex()) {
                val largo = min(r.longitudes.getOrElse(k) { 0L }.coerceAtLeast(0L).toInt(), tPasos)
                val guardar = ArrayList<Int>()
                var suma = 0.0
                var previo = -1L
                for (s in 0 until largo) {
                    val v = r.ids[k * tPasos + s]
                    if (v != 0L && (s == 0 || v != previo)) { guardar += s; suma += r.conf[k * tPasos + s] }
                    previo = v
                }
                val conf = if (guardar.isEmpty()) 0f else (suma / guardar.size).toFloat()
                var clases = IntArray(guardar.size) { r.ids[k * tPasos + guardar[it]].toInt() }
                var pasos = guardar.toIntArray()
                var probs = FloatArray(guardar.size) { r.conf[k * tPasos + guardar[it]] }
                val kt = r.k
                if (lm != null && r.blanco != null && r.topv != null && r.topi != null && kt > 0 && conf >= suelo && conf < meta.puerta) {
                    val base = k * tPasos
                    val bl = FloatArray(largo) { r.blanco[base + it] }
                    val tv = FloatArray(largo * kt) { r.topv[base * kt + it] }
                    val ti = LongArray(largo * kt) { r.topi[base * kt + it] }
                    val (bi, bs) = lm.buscarEnHaz(bl, tv, ti, kt, largo, meta.alfa, meta.beta, HAZ_LM, sueloCandidato = SUELO_CANDIDATO_LM, sesgo = sesgo, vocab = meta.vocab)
                    clases = bi; pasos = bs
                    // La probabilidad de la clase elegida en su paso (entre las kt mejores; si no está, casi nula).
                    probs = FloatArray(bi.size) { q ->
                        val s0 = bs[q]
                        var pr = 0.01f
                        for (jj in 0 until kt) if (ti[s0 * kt + jj].toInt() == bi[q]) { pr = exp(tv[s0 * kt + jj]); break }
                        pr
                    }
                }
                val bx = cajas[j]
                val h = max(1.0, (bx[3] - bx[1]).toDouble())
                val ix0 = floor(bx[0] - ImagenIndra.MARGEN_X * h)
                val cw = ceil(bx[2] + ImagenIndra.MARGEN_X * h) - ix0
                val f = cw / recortes[j].ancho
                val fichas = List(clases.size) { Ficha(clases[it], (ix0 + (pasos[it] + 0.5) * pasoX * f).toFloat(), probs[it]) }
                out[j] = when {
                    conf >= CONFIANZA_MIN -> Lectura(conf, fichas)
                    conf >= suelo -> Lectura(conf, textoDudoso = meta.vocab.decodificar(clases), fichasDudosas = fichas)
                    else -> Lectura(conf)
                }
            }
        }
        return out
    }

    // ---- Ensamblado (assemble) ----

    private fun esEspacio(f: Ficha) = f.clase == meta.vocab.espacio

    private fun textoDe(f: List<Ficha>): String = meta.vocab.decodificar(IntArray(f.size) { f[it].clase })

    /** Cortes de calle: dos letras muy separadas con bordes de otras líneas en el hueco (columnas que el detector juntó). */
    private fun cortesDeCalle(cajas: List<FloatArray>, lect: List<Lectura>): List<List<Float>> = cajas.indices.map { i ->
        val b = cajas[i]
        val h = max(1f, b[3] - b[1])
        val xs = lect[i].fichas.filterNot { esEspacio(it) }.map { it.x }
        val mios = ArrayList<Float>()
        for (q in 0 until xs.size - 1) {
            val a = xs[q]
            val c = xs[q + 1]
            if (c - a <= HUECO_CALLE * h) continue
            val gl = a + 0.35f * h
            val gr = c - 0.35f * h
            var cerca = 0
            for (o in cajas.indices) if (o != i && (abs(cajas[o][2] - gl) < 0.5f * h || abs(cajas[o][0] - gr) < 0.5f * h)) cerca++
            if (cerca >= BORDES_CALLE) mios += (a + c) / 2
        }
        mios
    }

    private class Trozo(val caja: FloatArray, val texto: String, val fichas: List<Ficha>)

    private fun partir(caja: FloatArray, fichas: List<Ficha>, hueco: Float, en: List<Float>): List<Trozo> {
        val h = max(1f, caja[3] - caja[1])
        val trozos = arrayListOf(ArrayList<Ficha>())
        var previo: Float? = null
        val cortes = en.sorted()
        for (f in fichas) {
            if (!esEspacio(f)) {
                val p = previo
                if (p != null && (f.x - p > hueco * h || cortes.any { p < it && it < f.x })) trozos.add(ArrayList())
                previo = f.x
            }
            trozos.last() += f
        }
        if (trozos.size == 1) return listOf(Trozo(caja.copyOf(), textoDe(fichas), fichas))
        val out = ArrayList<Trozo>()
        for (t in trozos) {
            val texto = textoDe(t).trim()
            val xs = t.filterNot { esEspacio(it) }.map { it.x }
            if (texto.isNotEmpty() && xs.isNotEmpty()) {
                out += Trozo(floatArrayOf(max(caja[0], xs.min() - 0.35f * h), caja[1], min(caja[2], xs.max() + 0.35f * h), caja[3]), texto, t)
            }
        }
        return out
    }

    private class Renglon(var caja: FloatArray, var texto: String, val conf: Float, var fichas: List<Ficha>, val origen: Int)

    /** Fuera líneas de puro relleno (puntos, rayas); viñetas y números sueltos unidos a su elemento (`tidy_lines`). */
    private fun ordenarLineas(l: List<Renglon>): List<Renglon> {
        val queda = BooleanArray(l.size) { true }
        for ((i, r) in l.withIndex()) {
            val nucleo = r.texto.replace(" ", "")
            if (nucleo.isNotEmpty() && nucleo.all { it in RELLENO }) {
                val w = r.caja[2] - r.caja[0]
                val h = r.caja[3] - r.caja[1]
                if (nucleo.length >= 3 || w >= 3 * h || nucleo.none { it in GUIONES }) queda[i] = false
            }
        }
        // Alturas de antes de unir (como tidy_lines: se calculan una vez).
        val hs = FloatArray(l.size) { l[it].caja[3] - l[it].caja[1] }
        for (i in l.indices) {
            val t = l[i].texto.trim()
            if (!queda[i] || !((t.length == 1 && t[0] in VINETAS) || esEnumerador(t))) continue
            val bi = l[i].caja
            val hi = hs[i]
            var mejor = -1
            var huecoMejor = 0f
            for (j in l.indices) {
                if (j == i || !queda[j] || l[j].texto.isBlank()) continue
                val bj = l[j].caja
                val hj = hs[j]
                val solape = min(bi[3], bj[3]) - max(bi[1], bj[1])
                if (solape < 0.5f * min(hi, hj)) continue
                val hueco = bj[0] - bi[2]
                if (hueco >= -0.3f * hi && hueco <= HUECO_MARCADOR * max(hi, hj) && (mejor < 0 || hueco < huecoMejor)) { mejor = j; huecoMejor = hueco }
            }
            if (mejor < 0) continue
            val lj = l[mejor]
            val sp = meta.vocab.espacio
            if (l[i].fichas.isNotEmpty() && lj.fichas.isNotEmpty() && sp != null) {
                lj.fichas = l[i].fichas + Ficha(sp, (bi[2] + lj.caja[0]) / 2) + lj.fichas
            }
            lj.texto = "$t ${lj.texto.trim()}"
            lj.caja = floatArrayOf(min(bi[0], lj.caja[0]), min(bi[1], lj.caja[1]), max(bi[2], lj.caja[2]), max(bi[3], lj.caja[3]))
            queda[i] = false
        }
        return l.filterIndexed { i, _ -> queda[i] }
    }

    /** Palabras de una línea: tramos entre espacios, con la caja desde las posiciones de los picos CTC. */
    private fun palabras(caja: FloatArray, fichas: List<Ficha>): List<Palabra> {
        val h = max(1f, caja[3] - caja[1])
        val out = ArrayList<Palabra>()
        var cur = ArrayList<Ficha>()
        fun vaciar() {
            if (cur.isEmpty()) return
            val texto = textoDe(cur).trim()
            if (texto.isNotEmpty()) {
                val x0 = max(caja[0], cur.minOf { it.x } - 0.35f * h)
                val x1 = min(caja[2], cur.maxOf { it.x } + 0.35f * h)
                var ps = 0f
                for (f in cur) ps += f.p
                val probs = if (texto.length == cur.size) FloatArray(cur.size) { cur[it].p } else null
                out += Palabra(texto, floatArrayOf(x0, caja[1], max(x0 + 1f, x1), caja[3]), ps / cur.size, probs)
            }
            cur = ArrayList()
        }
        for (f in fichas) if (esEspacio(f)) vaciar() else cur += f
        vaciar()
        return out
    }

    private fun cuadrada(v: FloatArray, n: Int, m2: Int) = Array(n) { i -> FloatArray(n) { j -> v[i * m2 + j] } }

    private fun ensamblar(m: ModelosIndra, w: Int, h: Int, cajas: List<FloatArray>, lect: List<Lectura>): Pagina {
        val cortes = cortesDeCalle(cajas, lect)
        val partidas = ArrayList<Renglon>()
        for (i in cajas.indices) for (t in partir(cajas[i], lect[i].fichas, HUECO_CORTE, cortes[i])) partidas += Renglon(t.caja, t.texto, lect[i].conf, t.fichas, i)
        val lineas = ordenarLineas(partidas)
        val bx = lineas.map { it.caja }
        val n = lineas.size
        var cls: Array<FloatArray>? = null
        var mismo: Array<FloatArray>? = null
        val salida = if (n >= 2) m.ordenar(OrdenIndra.rasgos(bx, w.toFloat(), h.toFloat(), lineas.map { it.texto }), n) else null
        val ord: IntArray = if (salida == null) OrdenIndra.ordenXy(bx).toIntArray() else {
            val m2 = if (salida.n2 == 0) n else salida.n2
            val a = cuadrada(salida.a, n, m2)
            if (salida.cls != null && salida.mismo != null) {
                val nc = salida.cls.size / n
                val c = Array(n) { i -> FloatArray(nc) { salida.cls[i * nc + it] } }
                val sm = cuadrada(salida.mismo, n, m2)
                cls = c; mismo = sm
                OrdenIndra.decodificarOrdenPorBloques(a, sm, bx, c)
            } else OrdenIndra.decodificarOrden(Array(n) { i -> DoubleArray(n) { j -> a[i][j].toDouble() } })
        }
        val pos = IntArray(n).also { p -> ord.forEachIndexed { k, i -> p[i] = k } }
        val med = if (n == 0) 1f else OrdenIndra.mediana(FloatArray(n) { lineas[it].caja[3] - lineas[it].caja[1] })
        fun r1(c: FloatArray) = FloatArray(4) { (Math.round(c[it] * 10.0) / 10.0).toFloat() }
        val salidaLineas = ord.map { i ->
            val l = lineas[i]
            val r = lect[l.origen]
            if (l.texto.isBlank() && r.fichasDudosas.isNotEmpty()) {
                // Línea dudosa: su lectura, salvo basura evidente (rayas, casillas, sueltas de un carácter).
                val t = r.textoDudoso.trim()
                val bh = l.caja[3] - l.caja[1]
                if (DudasOcr.conservar(t, l.conf, bh / max(1f, med), sueloDuda)) Linea(r1(l.caja), t, l.conf, palabras(l.caja, r.fichasDudosas), dudosa = true)
                else Linea(r1(l.caja), "", l.conf, emptyList())
            } else Linea(r1(l.caja), l.texto.trim(), l.conf, palabras(l.caja, l.fichas))
        }
        val c = cls
        val sm = mismo
        val bloques: List<OrdenIndra.Bloque> = if (c != null && sm != null) {
            OrdenIndra.bloquesDePagina(bx, ord, c, sm).map { b -> OrdenIndra.Bloque(b.etiqueta, b.caja, b.lineas.map { pos[it] }) }
        } else if (n > 0) {
            // Sin cabezas de maquetación: bloques por XY-cut.
            OrdenIndra.bloquesXy(bx).map { blk -> OrdenIndra.Bloque("Text", OrdenIndra.cajaDe(bx, blk), blk.map { pos[it] }.sorted()) }
        } else emptyList()
        return Pagina(salidaLineas, bloques, 0, 0.0, w, h)
    }

    companion object {
        /** Lo que necesita de la descarga (latín): el resto del paquete de Indra no se usa. */
        val MODELOS = listOf("det.onnx", "rec.onnx", "order.onnx", "orient.onnx", "meta.json", "lm.npz")

        const val ALTO_MIN_ADAPTATIVO = 9.0f
        const val ALTO_OBJETIVO_ADAPTATIVO = 12.0
        const val LADO_MAX_ADAPTATIVO = 2048
        /** Cortar un renglón donde dos letras vecinas están a más de tantas alturas. */
        const val HUECO_CORTE = 3.0f
        const val HUECO_CALLE = 1.6f
        const val BORDES_CALLE = 3
        /** El escáner gira con 0,9 (Python 0,95): en su banco de 132 páginas, a 0,9 giran 4 y todas bien. */
        const val ORIENTACION_MIN = 0.9f
        const val CONFIANZA_MIN = DudasOcr.CONFIANZA_MIN
        const val SUELO_DUDA = DudasOcr.SUELO_DUDA
        const val PUERTA_LM = 0.9
        const val ALFA_LM = 0.5
        const val BETA_LM = 1.5
        const val HAZ_LM = 8
        const val SUELO_CANDIDATO_LM = 3e-3
        const val HUECO_MARCADOR = 2.5f
        /** Lado largo máximo que recibe Indra: el detector trabaja a 1024 y los recortes no ganan más allá. */
        const val LADO_MAX = 4000
        private const val RELLENO = ".·…‥_-–—="
        private const val GUIONES = "-–—"
        private const val VINETAS = "•◦▪▫●○■□‣⁃∙*"
        private val ENUMERADOR = Regex("(\\d{1,3}|[A-Za-z])[.)]|\\((\\d{1,3}|[A-Za-z]|[ivxlcIVXLC]{1,5})\\)")

        /** «1.», «a)», «(iv)»…: un número de lista suelto. */
        fun esEnumerador(t: String) = ENUMERADOR.matches(t)
    }
}
