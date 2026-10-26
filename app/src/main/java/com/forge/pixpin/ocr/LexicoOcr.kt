package com.forge.pixpin.ocr

/**
 * **El diccionario español y «Mi vocabulario» de la corrección del OCR** (25-oct-2026, puerto de
 * `crates/pixpin-indra/src/lexico.rs` del PC, que lo es a su vez de `Hunspell.kt` y
 * `UserVocabulary.kt` del escáner). Mismas reglas y mismos números, para que el teléfono y el PC
 * corrijan igual el mismo texto (ver `docs/ocr.md` del PC, § Android).
 *
 * El diccionario es el hunspell `es_ES` de RLA-ES tal como lo trae LibreOffice (se usa bajo la
 * MPL). Solo se pregunta si una palabra existe: raíces + reglas de afijos, sin expandir el millón
 * de formas (~2 MB de memoria en vez de ~80). Admite lo que usa `es_ES`: `FLAG UTF-8`, `PFX`/`SFX`
 * con condición, producto cruzado y un nivel de sufijo de continuación («-ción/S» → «-ciones»).
 *
 * **Se baja con Indra** (26-oct-2026, [CatalogoIndra]) y lo usa su canal, que da confianza por
 * letra. Sin Indra no se usa: corregir con el diccionario sin esa confianza adivinaría.
 *
 * Kotlin cuenta en `Char` (UTF-16) y el PC en caracteres Unicode: para el español —todo en el
 * plano básico— es lo mismo.
 */
class Hunspell private constructor(
    private val raices: Map<String, List<Char>>,
    private val sufijos: Map<String, List<Afijo>>,
    private val prefijos: Map<String, List<Afijo>>,
    private val maxSuf: Int,
    private val maxPre: Int,
    private val continuaciones: Set<Char>
) {
    /** Condición de una regla («[^aeiou]r», «.», «ecer»), al final (sufijo) o al principio (prefijo). */
    private class Condicion(spec: String) {
        /** null = cualquiera; si no, (negada, conjunto). */
        val partes: List<Pair<Boolean, Set<Char>>?>

        init {
            val c = spec
            val p = ArrayList<Pair<Boolean, Set<Char>>?>()
            var i = 0
            while (i < c.length) {
                if (c[i] == '[') {
                    val j = c.indexOf(']', i).let { if (it < 0) c.length else it }
                    val neg = c.getOrNull(i + 1) == '^'
                    val desde = if (neg) i + 2 else i + 1
                    p.add(neg to c.substring(minOf(desde, j), j).toSet())
                    i = j + 1
                } else {
                    p.add(if (c[i] == '.') null else (false to setOf(c[i])))
                    i++
                }
            }
            partes = p
        }

        private fun vale(p: Pair<Boolean, Set<Char>>?, c: Char) = p == null || (c in p.second) != p.first

        fun alFinal(w: String): Boolean {
            if (partes.all { it == null }) return true
            if (w.length < partes.size) return false
            val o = w.length - partes.size
            return partes.indices.all { k -> vale(partes[k], w[o + k]) }
        }

        fun alPrincipio(w: String): Boolean {
            if (partes.all { it == null }) return true
            if (w.length < partes.size) return false
            return partes.indices.all { k -> vale(partes[k], w[k]) }
        }
    }

    private class Afijo(
        val bandera: Char,
        val quita: String,
        val cond: Condicion,
        val cruzado: Boolean,
        val continua: List<Char>
    )

    val tamano: Int get() = raices.size

    /** ¿Está la palabra (tal cual, en minúscula, o capitalizada si viene en MAYÚSCULAS: «MADRID» → «Madrid»)? */
    fun contiene(w: String): Boolean {
        if (w.isEmpty()) return false
        if (mira(w)) return true
        val minus = w.lowercase()
        if (minus != w && mira(minus)) return true
        if (w.length > 1 && w.all { !it.isLetter() || it.isUpperCase() }) {
            val cap = minus.replaceFirstChar { it.uppercase() }
            if (mira(cap)) return true
        }
        return false
    }

    private fun tiene(raiz: String, bandera: Char) = raices[raiz]?.contains(bandera) == true

    private fun mira(w: String): Boolean {
        if (w in raices) return true
        if (conSufijo(w, null, false)) return true
        for (l in 0..minOf(maxPre, w.length)) {
            val reglas = prefijos[w.substring(0, l)] ?: continue
            val resto = w.substring(l)
            for (r in reglas) {
                val raiz = r.quita + resto
                if (raiz.isEmpty() || !r.cond.alPrincipio(raiz)) continue
                if (tiene(raiz, r.bandera)) return true
                if (r.cruzado && conSufijo(raiz, r.bandera, false)) return true
            }
        }
        return false
    }

    /** ¿`w` = raíz + sufijo (o + sufijo de continuación)? [necesita]: la raíz debe llevar también esa bandera (prefijo cruzado). */
    private fun conSufijo(w: String, necesita: Char?, segundo: Boolean): Boolean {
        for (l in 0..minOf(maxSuf, w.length)) {
            val reglas = sufijos[w.substring(w.length - l)] ?: continue
            val base = w.substring(0, w.length - l)
            for (r in reglas) {
                if (necesita != null && !r.cruzado) continue
                val raiz = base + r.quita
                if (raiz.isEmpty() || !r.cond.alFinal(raiz)) continue
                if (tiene(raiz, r.bandera) && (necesita == null || tiene(raiz, necesita))) return true
                if (!segundo && r.bandera in continuaciones && conContinuacion(raiz, r.bandera, necesita)) return true
            }
        }
        return false
    }

    private fun conContinuacion(w: String, bandera2: Char, necesita: Char?): Boolean {
        for (l in 0..minOf(maxSuf, w.length)) {
            val reglas = sufijos[w.substring(w.length - l)] ?: continue
            val base = w.substring(0, w.length - l)
            for (r in reglas) {
                if (bandera2 !in r.continua || (necesita != null && !r.cruzado)) continue
                val raiz = base + r.quita
                if (raiz.isEmpty() || !r.cond.alFinal(raiz)) continue
                if (tiene(raiz, r.bandera) && (necesita == null || tiene(raiz, necesita))) return true
            }
        }
        return false
    }

    companion object {
        private val BLANCOS = Regex("\\s+")

        /** Desde el texto de `.aff` y `.dic` (UTF-8). */
        fun cargar(aff: String, dic: String): Hunspell {
            val sufijos = HashMap<String, MutableList<Afijo>>()
            val prefijos = HashMap<String, MutableList<Afijo>>()
            val cruzado = HashMap<String, Boolean>()
            var maxSuf = 0
            var maxPre = 0
            for (linea in aff.lineSequence()) {
                val f = linea.trim().split(BLANCOS).filter { it.isNotEmpty() }
                if (f.size < 4 || (f[0] != "SFX" && f[0] != "PFX")) continue
                val clave = f[0] + f[1]
                if (f.size == 4 && (f[2] == "Y" || f[2] == "N")) {
                    cruzado[clave] = f[2] == "Y"
                    continue
                }
                if (f.size < 5) continue
                val quita = if (f[2] == "0") "" else f[2]
                val anade0 = f[3].substringBefore('/')
                val cont = if ('/' in f[3]) f[3].substringAfter('/') else ""
                val anade = if (anade0 == "0") "" else anade0
                val a = Afijo(f[1].firstOrNull() ?: ' ', quita, Condicion(f[4]), cruzado[clave] ?: false, cont.toList())
                if (f[0] == "SFX") {
                    sufijos.getOrPut(anade) { ArrayList() }.add(a)
                    maxSuf = maxOf(maxSuf, anade.length)
                } else {
                    prefijos.getOrPut(anade) { ArrayList() }.add(a)
                    maxPre = maxOf(maxPre, anade.length)
                }
            }
            val raices = HashMap<String, MutableList<Char>>(80_000)
            for ((i, linea) in dic.lineSequence().withIndex()) {
                val l = linea.trim()
                if (i == 0 && l.isNotEmpty() && l.all { it in '0'..'9' }) continue
                if (l.isEmpty() || l.startsWith('#')) continue
                val entrada = l.split(' ', '\t').first()
                val w = entrada.substringBefore('/')
                val fl = if ('/' in entrada) entrada.substringAfter('/') else ""
                raices.getOrPut(w) { ArrayList() }.addAll(fl.toList())
            }
            val continuaciones = sufijos.values.flatten().flatMapTo(HashSet()) { it.continua }
            return Hunspell(raices, sufijos, prefijos, maxSuf, maxPre, continuaciones)
        }
    }
}

/** Minúsculas sin tildes ni diéresis (la eñe se queda). */
fun clave(w: String): String = buildString(w.length) {
    for (c in w.lowercase()) append(
        when (c) {
            'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a'
            'é', 'è', 'ê', 'ë' -> 'e'
            'í', 'ì', 'î', 'ï' -> 'i'
            'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
            'ú', 'ù', 'û', 'ü' -> 'u'
            'ç' -> 'c'
            'ý', 'ÿ' -> 'y'
            else -> c
        }
    )
}

/** Letra o cifra, como `char::is_alphanumeric` de Rust (que cuenta también ½, Ⅻ…). */
internal fun esLetraOCifra(c: Char) = c.isLetter() || esCifra(c)

/** Cifra, como `char::is_numeric` de Rust: decimales, romanas (Ⅻ) y otras (½, ²). */
internal fun esCifra(c: Char) = c.isDigit() || Character.getType(c).let {
    it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt()
}

/** Lo de dentro de una palabra, sin la puntuación de los lados. */
internal fun nucleo(t: String) = t.trim { !esLetraOCifra(it) }

/**
 * **«Mi vocabulario»** (25-oct-2026, `[ocr] vocabulario` del PC): términos del usuario (nombres,
 * cursos, siglas como «E030») que mandan en la corrección. Se escribe uno por línea o separados
 * por comas; lo que se compara es su [clave] (sin tildes ni mayúsculas) y lo que se pone es como
 * lo escribió.
 */
class Vocabulario private constructor(
    val terminos: List<String>,
    /** Las palabras de los términos (como las escribió), por su [clave], en el orden en que salen. */
    val palabras: List<Pair<String, String>>,
    val prefijos: Set<String>
) {
    val vacio: Boolean get() = palabras.isEmpty()

    /** La forma del usuario de una palabra, por su clave. */
    fun forma(w: String): String? {
        val k = clave(w)
        return palabras.firstOrNull { it.first == k }?.second
    }

    fun contiene(w: String) = forma(w) != null

    companion object {
        /** Palabras más cortas no cuentan (demasiado fáciles de confundir). */
        const val LARGO_MIN_VOCABULARIO = 3

        val VACIO = nuevo(emptyList())

        fun nuevo(terminos: List<String>): Vocabulario {
            val t = ArrayList<String>()
            for (x in terminos) {
                val y = x.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                if (y.isNotEmpty() && y !in t) t.add(y)
            }
            val palabras = ArrayList<Pair<String, String>>()
            for (termino in t) for (w in termino.split(' ')) {
                val n = nucleo(w)
                if (n.length >= LARGO_MIN_VOCABULARIO) {
                    val k = clave(n)
                    if (palabras.none { it.first == k }) palabras.add(k to n)
                }
            }
            val prefijos = HashSet<String>()
            for ((k, _) in palabras) for (i in 1..k.length) prefijos.add(k.substring(0, i))
            return Vocabulario(t, palabras, prefijos)
        }

        /** Del texto del ajuste: un término por línea o separados por comas (o punto y coma). */
        fun deTexto(texto: String): Vocabulario =
            nuevo(texto.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() })
    }
}
