package com.forge.pixpin.asistente.web

/**
 * **Partir HTML en piezas** (28-oct-2026, `html.rs` de `crates/pixpin-paginas` del PC): texto,
 * etiqueta que abre (con sus atributos) y etiqueta que cierra. No es un parser de HTML5 entero: lo
 * justo para leer páginas reales (etiquetas sin cerrar, atributos sin comillas, comentarios,
 * `<script>` con `<` dentro) sin perderse ni colgarse. Nunca falla: lo que no entiende es texto.
 */
object Html {
    sealed class Pieza {
        data class Texto(val t: String) : Pieza()
        /** [nombre] en minúsculas; los nombres de atributo también, y sus valores con las entidades ya leídas. */
        data class Abre(val nombre: String, val atributos: List<Pair<String, String>>) : Pieza()
        data class Cierra(val nombre: String) : Pieza()

        /** El valor de un atributo de una etiqueta que abre. */
        fun atributo(n: String): String? = (this as? Abre)?.atributos?.firstOrNull { it.first == n }?.second
    }

    /** Las etiquetas cuyo contenido es texto crudo hasta su cierre (puede llevar `<` y `>`). */
    private val CRUDAS = setOf("script", "style", "textarea", "xmp", "noscript")

    /** Las etiquetas que nunca tienen contenido. */
    val VACIAS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    private fun letra(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun alfanumerico(c: Char) = letra(c) || c in '0'..'9'
    private fun blanco(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    /** **Parte el HTML.** */
    fun piezas(html: String): List<Pieza> {
        val n = html.length
        val v = ArrayList<Pieza>()
        var i = 0
        var textoDesde = 0
        fun texto(hasta: Int) { if (textoDesde < hasta) v += Pieza.Texto(html.substring(textoDesde, hasta)) }
        while (i < n) {
            if (html[i] != '<') { i++; continue }
            val siguiente = if (i + 1 < n) html[i + 1] else '\u0000'
            val fin: Int? = when {
                // Comentario.
                html.startsWith("<!--", i) -> html.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                // <!DOCTYPE>, <![CDATA[…]]>, <?xml?>.
                siguiente == '!' || siguiente == '?' -> html.indexOf('>', i).let { if (it < 0) n else it + 1 }
                siguiente == '/' && i + 2 < n && letra(html[i + 2]) -> {
                    val (nombre, f0) = nombreEn(html, i + 2)
                    val f = html.indexOf('>', f0).let { if (it < 0) n else it + 1 }
                    texto(i)
                    v += Pieza.Cierra(nombre)
                    textoDesde = f
                    i = f
                    continue
                }
                letra(siguiente) -> {
                    val (nombre, f0) = nombreEn(html, i + 1)
                    val (atributos, f) = atributosEn(html, f0)
                    texto(i)
                    v += Pieza.Abre(nombre, atributos)
                    i = f
                    textoDesde = f
                    if (nombre in CRUDAS) {
                        // Hasta `</nombre`, sin mirar lo de dentro.
                        val donde = buscarSinMayusculas(html, "</$nombre", f) ?: n
                        if (donde > f) v += Pieza.Texto(html.substring(f, donde))
                        val tras = html.indexOf('>', donde).let { if (it < 0 || donde >= n) n else it + 1 }
                        v += Pieza.Cierra(nombre)
                        i = tras
                        textoDesde = tras
                    }
                    continue
                }
                else -> null
            }
            if (fin != null) {
                texto(i)
                i = fin
                textoDesde = fin
            } else i++
        }
        texto(n)
        return v
    }

    private fun buscarSinMayusculas(texto: String, aguja: String, desde: Int): Int? {
        var i = desde
        while (i + aguja.length <= texto.length) {
            if (texto.regionMatches(i, aguja, 0, aguja.length, ignoreCase = true)) return i
            i++
        }
        return null
    }

    /** El nombre de una etiqueta desde [i], y dónde acaba. */
    private fun nombreEn(html: String, i: Int): Pair<String, Int> {
        var f = i
        while (f < html.length && (alfanumerico(html[f]) || html[f] == '-' || html[f] == ':' || html[f] == '_')) f++
        return html.substring(i, f).lowercase() to f
    }

    /** Los atributos desde [desde] hasta el `>`, y dónde acaba la etiqueta. */
    private fun atributosEn(html: String, desde: Int): Pair<List<Pair<String, String>>, Int> {
        val n = html.length
        val v = ArrayList<Pair<String, String>>()
        var i = desde
        while (true) {
            while (i < n && (blanco(html[i]) || html[i] == '/')) i++
            if (i >= n) return v to n
            if (html[i] == '>') return v to i + 1
            val ini = i
            while (i < n && !blanco(html[i]) && html[i] != '=' && html[i] != '>' && html[i] != '/') i++
            val nombre = html.substring(ini, i).lowercase()
            while (i < n && blanco(html[i])) i++
            var valor = ""
            if (i < n && html[i] == '=') {
                i++
                while (i < n && blanco(html[i])) i++
                if (i < n && (html[i] == '"' || html[i] == '\'')) {
                    val q = html[i]
                    val ini2 = i + 1
                    val fin = html.indexOf(q, ini2).let { if (it < 0) n else it }
                    valor = entidades(html.substring(ini2, fin))
                    i = minOf(fin + 1, n)
                } else {
                    val ini2 = i
                    while (i < n && !blanco(html[i]) && html[i] != '>') i++
                    valor = entidades(html.substring(ini2, i))
                }
            }
            if (nombre.isNotEmpty()) v += nombre to valor
        }
    }

    /** Las entidades con nombre que salen en páginas en español e inglés. */
    private val NOMBRADAS = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó", "Uacute" to "Ú",
        "ntilde" to "ñ", "Ntilde" to "Ñ", "uuml" to "ü", "Uuml" to "Ü", "ccedil" to "ç",
        "agrave" to "à", "egrave" to "è", "ograve" to "ò", "iexcl" to "¡", "iquest" to "¿",
        "laquo" to "«", "raquo" to "»", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "middot" to "·", "bull" to "•",
        "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€", "pound" to "£", "deg" to "°",
        "ordm" to "º", "ordf" to "ª", "times" to "×", "divide" to "÷", "sect" to "§", "para" to "¶",
        "shy" to "", "zwnj" to "", "zwj" to ""
    )

    /** Windows-1252 de `0x80` a `0x9F` (de `0xA0` en adelante es Latin-1). */
    private const val CP1252 = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ"

    internal fun cp1252(b: Int): Char = if (b in 0x80..0x9F) CP1252[b - 0x80] else b.toChar()

    /** **Lee las entidades** (`&amp;`, `&#225;`, `&#xE1;`, `&aacute;`). Las que no conoce quedan tal cual. */
    fun entidades(s: String): String {
        if ('&' !in s) return s
        val o = StringBuilder(s.length)
        var resto = s
        while (true) {
            val i = resto.indexOf('&')
            if (i < 0) break
            o.append(resto, 0, i)
            val tras = resto.substring(i + 1)
            // Hasta 12 letras: el `;` o lo primero que no es letra, número ni `#`.
            var fin: Pair<Int, Boolean>? = null
            for (j in 0 until minOf(12, tras.length)) {
                val c = tras[j]
                if (c == ';' || !(alfanumerico(c) || c == '#')) { fin = j to (c == ';'); break }
            }
            if (fin == null && tras.length <= 12) fin = tras.length to false
            val leida = fin?.let { (j, conPunto) ->
                val nombre = tras.substring(0, j)
                val valor = if (nombre.startsWith('#')) {
                    val num = nombre.substring(1)
                    val cp = if (num.startsWith('x') || num.startsWith('X')) num.substring(1).takeIf { it.isNotEmpty() }?.toLongOrNull(16)
                    else num.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
                    cp?.takeIf { it <= 0xFFFFFFFFL }?.let { x ->
                        when {
                            x == 0L -> "�"
                            // Lo que las páginas viejas mandan como Windows-1252.
                            x in 0x80L..0x9FL -> cp1252(x.toInt()).toString()
                            x > 0x10FFFFL || x in 0xD800L..0xDFFFL -> "�"
                            else -> String(Character.toChars(x.toInt()))
                        }
                    }
                } else NOMBRADAS[nombre]
                valor?.let { it to (j + if (conPunto) 1 else 0) }
            }
            if (leida != null) {
                o.append(leida.first)
                resto = tras.substring(leida.second)
            } else {
                o.append('&')
                resto = tras
            }
        }
        o.append(resto)
        return o.toString()
    }
}
