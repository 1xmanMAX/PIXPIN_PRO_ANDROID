package com.forge.pixpin.ocr

/**
 * Una palabra leída: su texto, la confianza media de sus letras y, si el lector la da, la de cada
 * letra ([probs], tantas como letras de [texto]).
 */
class PalabraOcr(var texto: String, val confianza: Float, var probs: List<Float>? = null)

/** Un renglón leído. [dudosa]: leída con poca confianza (ver [DudasOcr]); no cuenta para saber si la página es española. */
class LineaOcr(var texto: String, val palabras: MutableList<PalabraOcr>, val confianza: Float, val dudosa: Boolean = false)

/** Una página (o captura) leída, en renglones. */
class PaginaOcr(val lineas: MutableList<LineaOcr>)

/** Un cambio hecho, para el registro. [origen]: `"vocabulario"`, `"tilde"` o `"lexico"`. */
data class CambioOcr(val de: String, val a: String, val origen: String)

/**
 * **El LM de Indra para desempatar**: log P de la palabra (con espacio delante y detrás); NaN si no
 * sabe (una letra fuera de su vocabulario). En el PC es `Desempate { lm, vocab }`; aquí una función,
 * porque el teléfono aún no carga el LM.
 */
fun interface DesempateOcr {
    fun logp(palabra: String): Double
}

/**
 * **Corrección de palabras mal leídas** con el diccionario español y «Mi vocabulario» (25-oct-2026,
 * puerto de `crates/pixpin-indra/src/corrector.rs` del PC = `IndraCorrector.kt` del escáner), SOLO
 * cuando la corrección es clara:
 *
 * - la palabra no está en el diccionario ni en el vocabulario, no lleva cifras (códigos como
 *   U20191A543, importes y fechas no se tocan), tiene 4 letras o más y su confianza es baja;
 * - el candidato está a una edición (diccionario) o a una distancia pequeña ponderada por la
 *   confianza de cada letra (vocabulario: cambiar una letra dudosa cuesta poco, una segura casi
 *   una edición; una tilde 0,1);
 * - es único, o el modelo de lenguaje de Indra lo prefiere con margen;
 * - el diccionario solo se usa si la página es española (40 % de sus palabras están en él).
 *
 * Banco del escáner (54 imágenes): CER 29,6 → 27,2 con todo el afinado.
 *
 * **En el teléfono, hoy** (`docs/ocr.md` del PC, § Android): PixPin Android no lleva motor de OCR;
 * lo único que lee texto de una foto es la visión del modelo del asistente. A eso se le pasa
 * **solo «Mi vocabulario»** ([conVocabulario]) —sin diccionario, que sin confianza por letra
 * adivinaría—; el resto queda probado y listo para cuando haya un lector con confianzas.
 */
class CorrectorOcr(val lexico: Hunspell?, val vocabulario: Vocabulario) {

    private fun enLexico(w: String) = lexico?.contiene(w) == true

    /** (en el diccionario, palabras de 3 letras o más) de la página. */
    fun parteEspanola(p: PaginaOcr): Pair<Int, Int> {
        var n = 0
        var si = 0
        for (l in p.lineas) {
            if (l.dudosa) continue
            for (w in l.palabras) {
                val c = nucleo(w.texto)
                if (c.length < 3 || !c.all { it.isLetter() }) continue
                n++
                if (enLexico(c)) si++
            }
        }
        return si to n
    }

    /** Corrige la página; devuelve los cambios y si era española. */
    fun corregir(p: PaginaOcr, lm: DesempateOcr? = null): Pair<List<CambioOcr>, Boolean> {
        if (lexico == null && vocabulario.vacio) return emptyList<CambioOcr>() to false
        val (si, n) = parteEspanola(p)
        val espanola = lexico != null && n >= PALABRAS_MIN && si.toDouble() >= PARTE_ESPANOLA * n
        val cambios = ArrayList<CambioOcr>()
        for (l in p.lineas) corregirLinea(l, espanola, lm, cambios)
        return cambios to espanola
    }

    private fun corregirLinea(l: LineaOcr, espanola: Boolean, lm: DesempateOcr?, cambios: MutableList<CambioOcr>) {
        if (l.palabras.isEmpty()) return
        val partes = l.texto.split(' ')
        if (partes.count { it.isNotEmpty() } != l.palabras.size) return
        var cambio = false
        for (w in l.palabras) {
            val (nueva, origen) = corregirPalabra(w, espanola, lm) ?: continue
            if (nueva == w.texto) continue
            cambios.add(CambioOcr(w.texto, nueva, origen))
            w.texto = nueva
            w.probs = null
            cambio = true
        }
        if (cambio) {
            var k = 0
            l.texto = partes.joinToString(" ") { if (it.isEmpty()) "" else l.palabras[k++].texto }
        }
    }

    /** (palabra corregida con su puntuación, origen) o null. */
    fun corregirPalabra(w: PalabraOcr, espanola: Boolean, lm: DesempateOcr? = null): Pair<String, String>? {
        val t = w.texto
        val ini = t.indexOfFirst { esLetraOCifra(it) }.takeIf { it >= 0 } ?: return null
        val fin = t.indexOfLast { esLetraOCifra(it) } + 1
        val c = t.substring(ini, fin)
        val pre = t.substring(0, ini)
        val post = t.substring(fin)
        val probs = w.probs?.takeIf { it.size == t.length }?.subList(ini, fin)
        val largo = c.length
        if (c.any { esCifra(it) }) {
            // Solo términos del vocabulario con cifras, por confusiones letra/cifra.
            if (largo < 3) return null
            val alias = aliasDeCifras(c)
            for ((_, v) in vocabulario.palabras) {
                if (v.any { esCifra(it) } && v.length == largo && v != c && aliasDeCifras(v) == alias) {
                    return "$pre$v$post" to "vocabulario"
                }
            }
            return null
        }
        if (largo < LARGO_MIN || !c.all { it.isLetter() }) return null
        vocabulario.forma(c)?.let { v ->
            // Ya es del vocabulario: solo se le ponen sus tildes.
            val f = igualarMayusculas(c, v)
            return if (f != c && f.lowercase() != c.lowercase()) "$pre$f$post" to "vocabulario" else null
        }
        if (w.confianza >= CONFIANZA_MAX) return null
        val enLex = enLexico(c)
        // 1) Vocabulario del usuario: distancia ponderada pequeña.
        var mejor: String? = null
        var costeMejor = Double.MAX_VALUE
        var segundo = Double.MAX_VALUE
        for ((k, v) in vocabulario.palabras) {
            if (v.any { esCifra(it) } || v.length < LARGO_MIN) continue
            if (enLex && k != clave(c)) continue
            if (distancia(clave(c), k) > edicionesVocabulario(largo)) continue
            val coste = distanciaPonderada(c, v, probs)
            if (coste < costeMejor) {
                segundo = costeMejor
                costeMejor = coste
                mejor = v
            } else if (coste < segundo) {
                segundo = coste
            }
        }
        if (mejor != null && costeMejor <= costeMaxVocabulario(largo) && segundo - costeMejor >= MARGEN) {
            return "$pre${igualarMayusculas(c, mejor)}$post" to "vocabulario"
        }
        // 2) Diccionario: una edición, candidato único. Tildes: basta con que sea único. Otras
        //    ediciones: 6 letras y el LM de Indra debe preferir claramente el candidato (aún más
        //    con Mayúscula).
        if (!espanola || enLex || lexico == null) return null
        if (largo in 2..4 && c.all { it.isUpperCase() }) return null // siglas
        val minus = c.lowercase()
        val cands = ArrayList<Pair<String, Double>>()
        for (cand in ediciones1(minus)) {
            if (enLexico(cand)) cands.add(cand to distanciaPonderada(c, cand, probs))
        }
        if (cands.isEmpty()) return null
        cands.sortWith(compareBy<Pair<String, Double>> { it.second }.thenBy { it.first })
        var top = cands[0]
        if (top.second > COSTE_MAX_LEXICO) return null
        if (cands.size > 1 && cands[1].second - top.second < MARGEN) {
            val empates = cands.filter { it.second - top.second < MARGEN }
                .map { it.first to puntuar(lm, it.first) }
                .sortedByDescending { it.second }
            if (empates[0].second.isNaN() || empates[0].second - empates[1].second < MARGEN_LM) return null
            val ganador = empates[0].first
            top = cands.firstOrNull { it.first == ganador } ?: return null
        }
        val soloTilde = clave(top.first) == clave(minus)
        if (!soloTilde) {
            val g = puntuar(lm, top.first) - puntuar(lm, minus)
            if (largo < LARGO_MIN_EDICION || g.isNaN()) return null
            val mayus = c.first().isUpperCase()
            if (g < if (mayus) GANANCIA_LM_MAYUSCULA else GANANCIA_LM) return null
        }
        return "$pre${igualarMayusculas(c, top.first)}$post" to (if (soloTilde) "tilde" else "lexico")
    }

    /** El sesgo para la búsqueda en haz del LM. */
    fun sesgo() = Sesgo(this, SESGO_LEXICO, SESGO_VOCABULARIO, FIN_VOCABULARIO)

    /** Sesgo de palabras: prefijos y palabras del vocabulario, y (suave) las del diccionario. */
    class Sesgo(private val c: CorrectorOcr, val lexico: Double, val vocabulario: Double, val fin: Double) {
        val activo: Boolean get() = !c.vocabulario.vacio || (c.lexico != null && lexico > 0.0)
        val pasoMax: Double get() = vocabulario + fin + lexico

        fun parcial(w: String): Double =
            if (!c.vocabulario.vacio && clave(w) in c.vocabulario.prefijos) vocabulario * w.length else 0.0

        fun completa(w: String): Double {
            if (!c.vocabulario.vacio && c.vocabulario.contiene(w)) return vocabulario * w.length + fin
            if (lexico > 0.0 && w.length >= 3 && w.all { it.isLetter() } && c.enLexico(w)) return lexico
            return 0.0
        }
    }

    companion object {
        const val LARGO_MIN = 4
        /** Ediciones que no son tildes: palabras de 6 letras o más. */
        const val LARGO_MIN_EDICION = 6
        /** Ventaja mínima (nats) del candidato según el LM: minúscula / Mayúscula. */
        const val GANANCIA_LM = 5.0
        const val GANANCIA_LM_MAYUSCULA = 10.0
        const val PALABRAS_MIN = 5
        const val PARTE_ESPANOLA = 0.4
        /** Desde esta confianza (media de sus letras) no se corrige. */
        const val CONFIANZA_MAX = 0.9f
        const val MARGEN = 0.15
        const val MARGEN_LM = 1.0
        const val COSTE_MAX_LEXICO = 0.75
        /** Bonificación del LM por palabra completa del diccionario (banco: manuscrito español AK137 20,5 → 23,2 % de palabras). */
        const val SESGO_LEXICO = 1.0
        const val SESGO_VOCABULARIO = 0.5
        const val FIN_VOCABULARIO = 1.0
        private const val COSTE_INSERTAR = 0.6
        private const val COSTE_TILDE = 0.1
        private const val LETRAS = "abcdefghijklmnopqrstuvwxyzñáéíóúü"

        /**
         * **La confianza que se le da a lo que no la trae** (la transcripción de la visión del
         * modelo): por debajo de [CONFIANZA_MAX], para que el vocabulario pueda corregir, y sin
         * confianza por letra, así que cada cambio cuesta 0,84 (casi una edición entera).
         */
        const val CONFIANZA_SIN_MEDIDA = 0.5f

        /** log P de la palabra según el LM; NaN sin él. */
        private fun puntuar(lm: DesempateOcr?, w: String): Double = lm?.logp(w) ?: Double.NaN

        fun costeMaxVocabulario(largo: Int): Double = minOf(0.3 + 0.12 * largo, 2.0)

        /** Ediciones (sin tildes) hacia un término del vocabulario: 1 hasta 9 letras, 2 desde 10, 3 desde 15. */
        fun edicionesVocabulario(largo: Int): Int = maxOf(largo / 5, 1)

        fun distancia(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1) { i }
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] != b[j - 1]) 1 else 0)
                }
                prev = cur
            }
            return prev[b.length]
        }

        private fun aliasDeCifras(s: String): String = buildString {
            for (c in s.uppercase()) append(
                when (c) {
                    'O', 'Q', 'D' -> '0'
                    'I', 'L', '|' -> '1'
                    'S' -> '5'
                    'B' -> '8'
                    'Z' -> '2'
                    'G' -> '6'
                    else -> c
                }
            )
        }

        /** Copia las mayúsculas del original: TODO, Capitalizada o tal cual. */
        fun igualarMayusculas(orig: String, cand: String): String {
            val todo = orig.length > 1 && orig.all { !it.isLetter() || it.isUpperCase() }
            if (todo) return cand.uppercase()
            if (orig.firstOrNull()?.isUpperCase() == true) return cand.replaceFirstChar { it.uppercase() }
            return cand
        }

        /** Formas a una edición (borrar, cambiar, insertar, trasponer), en minúsculas. */
        fun ediciones1(w: String): Set<String> {
            val o = HashSet<String>()
            for (i in w.indices) {
                o.add(w.removeRange(i, i + 1))
                for (l in LETRAS) if (l != w[i]) o.add(w.substring(0, i) + l + w.substring(i + 1))
                if (i + 1 < w.length) o.add(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
            }
            for (i in 0..w.length) for (l in LETRAS) o.add(w.substring(0, i) + l + w.substring(i))
            o.remove(w)
            return o
        }

        /**
         * Distancia ponderada de lo leído [o] (probabilidad de cada letra [p]) a [c]: cambiar o
         * borrar cuesta 0,2 + 0,8·p, una tilde 0,1, insertar 0,6. Sin [p], cada letra vale 0,8.
         */
        fun distanciaPonderada(o: String, c: String, p: List<Float>?): Double {
            val a = o.lowercase()
            val b = c.lowercase()
            fun pc(i: Int) = 0.2 + 0.8 * (p?.getOrNull(i)?.toDouble() ?: 0.8)
            var prev = DoubleArray(b.length + 1) { it * COSTE_INSERTAR }
            for (i in 1..a.length) {
                val cur = DoubleArray(b.length + 1)
                cur[0] = prev[0] + pc(i - 1)
                for (j in 1..b.length) {
                    val x = a[i - 1]
                    val y = b[j - 1]
                    val sub = when {
                        x == y -> 0.0
                        clave(x.toString()) == clave(y.toString()) -> COSTE_TILDE
                        else -> pc(i - 1)
                    }
                    cur[j] = minOf(prev[j - 1] + sub, prev[j] + pc(i - 1), cur[j - 1] + COSTE_INSERTAR)
                }
                prev = cur
            }
            return prev[b.length]
        }

        /**
         * **«Mi vocabulario» sobre un texto que no trae confianzas** (25-oct-2026): la transcripción
         * de una foto por la visión del modelo del asistente, que es el OCR que tiene hoy el teléfono.
         * Cada renglón es una línea; cada palabra lleva [CONFIANZA_SIN_MEDIDA]. Sin diccionario: solo
         * se cambia lo que se acerca claramente a un término del usuario («Sismorresistenta» →
         * «Sismorresistente», «E03O» → «E030») o sus tildes. Vocabulario vacío: el texto tal cual.
         */
        fun conVocabulario(texto: String, vocabulario: Vocabulario): String {
            if (vocabulario.vacio || texto.isBlank()) return texto
            val renglones = texto.split('\n')
            val pagina = PaginaOcr(
                renglones.mapTo(ArrayList()) { r ->
                    val palabras = r.split(' ').filter { it.isNotEmpty() }
                        .mapTo(ArrayList()) { PalabraOcr(it, CONFIANZA_SIN_MEDIDA) }
                    LineaOcr(r, palabras, CONFIANZA_SIN_MEDIDA)
                }
            )
            CorrectorOcr(null, vocabulario).corregir(pagina)
            return pagina.lineas.joinToString("\n") { it.texto }
        }
    }
}
