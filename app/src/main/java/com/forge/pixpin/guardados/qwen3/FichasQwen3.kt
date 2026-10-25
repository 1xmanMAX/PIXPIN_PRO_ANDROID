package com.forge.pixpin.guardados.qwen3

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * **Los tokens de Qwen**: de texto a números (solo para el guion) y de números a texto (lo que
 * escribe el modelo). 25-oct-2026, puerto de `crates/pixpin-voz/src/qwen3/fichas.rs` del PC.
 *
 * Qwen usa el BPE a nivel de byte de GPT-2: cada byte se escribe como una letra imprimible
 * (`bytes_to_unicode`: el espacio es `Ġ`, el salto de línea `Ċ`) y `vocab.json` dice el número
 * de cada cadena de esas letras. Para **leer** basta con deshacer eso: números → letras → bytes →
 * UTF-8 (una «ñ» puede venir partida en dos tokens, así que se juntan los bytes de toda la frase
 * y se convierte al final).
 *
 * Para **escribir** solo hacen falta unas pocas frases fijas del guion, pero se hace bien: tokens
 * especiales enteros, el pretroceado de Qwen2 (la expresión de `Qwen2TokenizerFast`, escrita a
 * mano) y las fusiones de `merges.txt` por orden. Las fusiones solo se cargan para eso y se
 * sueltan después (son 150 000 pares).
 */
object FichasQwen3 {

    /** `bytes_to_unicode` de GPT-2: la letra (un carácter BMP) de cada byte. */
    private val LETRAS: CharArray = run {
        val t = CharArray(256)
        var extra = 0
        for (b in 0..255) {
            val imprimible = b in '!'.code..'~'.code || b in 0xA1..0xAC || b in 0xAE..0xFF
            t[b] = if (imprimible) b.toChar() else (256 + extra++).toChar()
        }
        t
    }

    /** La vuelta: el byte de cada letra. */
    internal val BYTE_DE_LETRA: Map<Char, Byte> = LETRAS.withIndex().associate { (b, c) -> c to b.toByte() }

    fun letraDeByte(b: Int): Char = LETRAS[b and 0xFF]

    private fun esLetra(cp: Int) = Character.isLetter(cp)
    private fun esNumero(cp: Int) = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }
    private fun esBlanco(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
    private fun esSalto(cp: Int) = cp == '\r'.code || cp == '\n'.code

    /**
     * **El pretroceado de Qwen2**, la expresión de `Qwen2TokenizerFast`:
     *
     * ```
     * (?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}
     * | ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+
     * ```
     *
     * Se prueba alternativa a alternativa en cada posición, como el motor de expresiones (gana la
     * primera que casa, no la más larga). Va por puntos de código, no por `Char`: un emoji es uno.
     */
    fun pretrocear(texto: String): List<String> {
        val cps = texto.codePoints().toArray()
        // Dónde empieza cada punto de código en el String (UTF-16), y el final.
        val inicio = IntArray(cps.size + 1)
        run { var o = 0; for (i in cps.indices) { inicio[i] = o; o += Character.charCount(cps[i]) }; inicio[cps.size] = o }
        val v = ArrayList<String>()
        var i = 0
        while (i < cps.size) {
            val ch = cps[i]
            val largo = casar(texto, cps, inicio, i, ch)
            v += texto.substring(inicio[i], inicio[i + largo])
            i += largo
        }
        return v
    }

    private fun casar(texto: String, c: IntArray, inicio: IntArray, i: Int, ch: Int): Int {
        // 1. Contracciones.
        if (ch == '\''.code) {
            val resto = texto.substring(inicio[i] + 1)
            for (suf in arrayOf("s", "t", "re", "ve", "m", "ll", "d")) {
                if (resto.length >= suf.length && resto.regionMatches(0, suf, 0, suf.length, ignoreCase = true)) return 1 + suf.length
            }
        }
        // 2. Un signo opcional (ni salto, ni letra, ni número) y letras.
        run {
            var j = i
            if (!esSalto(ch) && !esLetra(ch) && !esNumero(ch)) j++
            var k = 0
            while (j + k < c.size && esLetra(c[j + k])) k++
            if (k > 0) return j + k - i
        }
        // 3. Una cifra suelta.
        if (esNumero(ch)) return 1
        // 4. ` ?[^\s\p{L}\p{N}]+[\r\n]*`.
        run {
            var j = i
            if (ch == ' '.code) j++
            var k = 0
            while (j + k < c.size && c[j + k].let { !esBlanco(it) && !esLetra(it) && !esNumero(it) }) k++
            if (k > 0) {
                var s = 0
                while (j + k + s < c.size && esSalto(c[j + k + s])) s++
                return j + k + s - i
            }
        }
        // 5. `\s*[\r\n]+`: blancos que acaban en saltos (el último salto del tramo manda).
        var tramo = 0
        while (i + tramo < c.size && esBlanco(c[i + tramo])) tramo++
        for (k in i + tramo - 1 downTo i) if (esSalto(c[k])) return k + 1 - i
        // 6. `\s+(?!\S)`: todos los blancos menos el último si detrás viene algo que no es blanco.
        if (tramo > 0) {
            val detras = i + tramo < c.size
            if (!detras) return tramo
            if (tramo > 1) return tramo - 1
            // 7. `\s+`.
            return tramo
        }
        return 1
    }

    /** **La tabla de tokens**: número a bytes, los especiales por nombre y la cadena de cada token normal. */
    class Fichas internal constructor(
        private val bytes: Array<ByteArray>,
        private val especiales: Map<String, Long>,
        private val numero: Map<String, Long>
    ) {
        val vacia: Boolean get() = bytes.isEmpty()

        /** El número de un token especial (`<asr_text>`…). */
        fun especial(nombre: String): Long? = especiales[nombre]

        /**
         * **De números a texto**: los bytes de cada token normal, juntos, a UTF-8. Los especiales
         * no se escriben; lo que no sea UTF-8 válido (un token partido al final) se tira.
         */
        fun texto(ids: List<Long>): String {
            val out = java.io.ByteArrayOutputStream()
            for (i in ids) if (i >= 0 && i < bytes.size) out.write(bytes[i.toInt()])
            return String(out.toByteArray(), Charsets.UTF_8).replace("�", "")
        }

        /** **De texto a números**, con las fusiones de `merges.txt`. Los especiales que aparezcan enteros van como un token. */
        fun codificar(texto: String, fusiones: Fusiones): List<Long> {
            val v = ArrayList<Long>()
            var resto = texto
            while (resto.isNotEmpty()) {
                // El especial que empiece antes (y el más largo si empatan).
                var hasta = resto.length
                var especial: Pair<Int, Long>? = null
                for ((n, id) in especiales) {
                    val p = resto.indexOf(n)
                    if (p < 0) continue
                    if (p < hasta || (p == hasta && especial != null && n.length > especial.first)) { hasta = p; especial = n.length to id }
                }
                for (pieza in pretrocear(resto.substring(0, hasta))) bpe(pieza, fusiones, v)
                if (especial == null) break
                v += especial.second
                resto = resto.substring(hasta + especial.first)
            }
            return v
        }

        private fun bpe(pieza: String, fusiones: Fusiones, salida: MutableList<Long>) {
            val partes = pieza.toByteArray(Charsets.UTF_8).map { letraDeByte(it.toInt()).toString() }.toMutableList()
            while (true) {
                var mejor = Int.MAX_VALUE; var donde = -1
                for (i in 0 until partes.size - 1) {
                    val r = fusiones.rango(partes[i], partes[i + 1]) ?: continue
                    if (r < mejor) { mejor = r; donde = i }
                }
                if (donde < 0) break
                partes[donde] = partes[donde] + partes[donde + 1]
                partes.removeAt(donde + 1)
            }
            for (p in partes) {
                val id = numero[p]
                // No debería pasar con el vocabulario de Qwen (cada byte suelto es un token); si pasa, byte a byte.
                if (id != null) salida += id else p.forEach { c -> numero[c.toString()]?.let { salida += it } }
            }
        }

        companion object {
            /**
             * Lee `vocab.json` (`{"!": 0, ...}`) y los especiales de `tokenizer_config.json`
             * (`added_tokens_decoder`). Null si alguno no se entiende.
             */
            fun leer(vocab: String, config: String): Fichas? = runCatching {
                val v = Json.parseToJsonElement(vocab).jsonObject
                val numero = HashMap<String, Long>(v.size * 2)
                for ((k, x) in v) numero[k] = x.jsonPrimitive.longOrNull ?: return null
                val decoder = Json.parseToJsonElement(config).jsonObject["added_tokens_decoder"] as? JsonObject ?: return null
                val especiales = HashMap<String, Long>()
                for ((id, x) in decoder) {
                    especiales[x.jsonObject["content"]?.jsonPrimitive?.content ?: return null] = id.toLongOrNull() ?: return null
                }
                val masAlto = (numero.values.maxOrNull() ?: return null).coerceAtLeast(0)
                val bytes = Array(masAlto.toInt() + 1) { ByteArray(0) }
                for ((cadena, id) in numero) {
                    if (id < 0) continue
                    bytes[id.toInt()] = cadena.mapNotNull { BYTE_DE_LETRA[it] }.toByteArray()
                }
                Fichas(bytes, especiales, numero)
            }.getOrNull()
        }
    }

    /** Las fusiones de `merges.txt`, con su orden (antes = se fusiona antes). */
    class Fusiones private constructor(private val rangos: Map<String, Int>) {
        internal fun rango(a: String, b: String): Int? = rangos["$a $b"]

        companion object {
            val NINGUNA = Fusiones(emptyMap())

            /** Una fusión por línea, `a b`; la cabecera (`#version: ...`) no cuenta. Ninguna letra de token es un espacio (el espacio es `Ġ`). */
            fun leer(texto: String): Fusiones {
                val m = HashMap<String, Int>()
                var i = 0
                for (l in texto.lineSequence()) {
                    if (l.isEmpty() || l.startsWith("#version")) continue
                    if (l.indexOf(' ') < 0) continue
                    m[l] = i
                    i++
                }
                return Fusiones(m)
            }
        }
    }
}
