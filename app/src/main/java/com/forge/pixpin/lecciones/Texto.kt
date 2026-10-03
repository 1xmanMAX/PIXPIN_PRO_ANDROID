package com.forge.pixpin.lecciones

import java.text.Normalizer

/**
 * **Las palabras, tal como las compara el buscador**: sin mayúsculas, sin acentos, sin plurales
 * y con las terminaciones más comunes del español recortadas. Así «Estructuras», «estructura» y
 * «estructural» caen juntas, y un dictado que escribe «hormigon» encuentra «hormigón».
 */
object Texto {
    private val DIACRITICOS = Regex("\\p{Mn}+")
    private val NO_LETRA = Regex("[^a-z0-9ñ#]+")

    /** Palabras que no dicen nada: no cuentan ni para buscar ni para parecerse. */
    val VACIAS = setOf(
        "a", "al", "algo", "ante", "antes", "aqui", "asi", "bien", "cada", "como", "con", "cual",
        "cuando", "de", "del", "desde", "donde", "dos", "el", "ella", "en", "entre", "era", "es",
        "esa", "ese", "eso", "esta", "este", "esto", "fue", "ha", "hay", "he", "la", "las", "le",
        "les", "lo", "los", "mas", "me", "mi", "mis", "muy", "nada", "ni", "no", "nos", "o", "otra",
        "otro", "para", "pero", "poco", "por", "porque", "que", "se", "sea", "si", "sin", "sobre",
        "son", "su", "sus", "tambien", "te", "tener", "tengo", "todo", "tu", "un", "una", "uno",
        "unos", "y", "ya", "yo", "vez", "hacer", "hice", "debo", "debe", "siempre", "nunca", "luego"
    )

    fun normal(s: String): String =
        DIACRITICOS.replace(Normalizer.normalize(s.lowercase().replace('ñ', '\u0001'), Normalizer.Form.NFD), "")
            .replace('\u0001', 'ñ')

    /** Las palabras de [s], normalizadas, sin las vacías. */
    fun palabras(s: String): List<String> =
        NO_LETRA.split(normal(s)).filter { it.length > 1 && it !in VACIAS }

    /**
     * La raíz de una palabra, a lo bruto: lo justo para que el singular y el plural, el verbo y
     * su participio, se encuentren. No es un lematizador; no hace falta.
     */
    fun raiz(p: String): String {
        if (p.length <= 4) return p.trimEnd('s')
        for (fin in TERMINACIONES) if (p.length - fin.length >= 4 && p.endsWith(fin)) return p.dropLast(fin.length)
        return p
    }

    private val TERMINACIONES = listOf(
        "aciones", "iciones", "amientos", "imientos", "amiento", "imiento", "mente",
        "ciones", "acion", "icion", "cion", "sion", "ando", "iendo", "adas", "idas", "ados", "idos",
        "ada", "ida", "ado", "ido", "ales", "eles", "ar", "er", "ir", "es", "as", "os", "al", "a", "o", "e", "s"
    )

    fun raices(s: String): List<String> = palabras(s).map(::raiz)

    /**
     * Distancia de edición, cortando en cuanto pasa de [tope]: con dictado hay erratas, y una
     * letra de más o de menos no puede dejar sin resultados.
     */
    fun distancia(a: String, b: String, tope: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > tope) return tope + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var minFila = cur[0]
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
                if (cur[j] < minFila) minFila = cur[j]
            }
            if (minFila > tope) return tope + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Cuántas erratas se perdonan según lo larga que es la palabra buscada. */
    fun erratas(p: String): Int = when {
        p.length >= 8 -> 2
        p.length >= 4 -> 1
        else -> 0
    }
}
