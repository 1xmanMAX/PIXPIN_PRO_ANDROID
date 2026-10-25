package com.forge.pixpin.motor

/**
 * **El lector de fórmulas del texto rico del lienzo** (25-oct-2026): el `formulas.rs` del PC
 * (`crates/pixpin-motor2d/src/formulas.rs`), portado tal cual.
 *
 * ## Por qué no se reutiliza `motormd/Formulas.kt`
 *
 * El PC empezó copiando el `Formulas` de las notas, pero luego le añadió lo de las láminas de
 * geometría del 15-oct: **marcas que crecen** (`\overline{AB}`, el arco de `\overset{\frown}{AC}`,
 * `\vec`), **colores** (`\color{red}{X}`), negrita, recuadro, `\overset`, `\underbrace`,
 * matrices y sistemas (`\begin{bmatrix}`, `cases`), `90^\circ`, y decenas de símbolos más. Y lo
 * que importa de verdad: **la regla de coherencia** (`docs/texto-rico-del-lienzo.md`). El PC
 * descarta la fuente rica de un texto si `plano(textoRico) != text`; para que el teléfono decida
 * igual, su `plano` tiene que dar **exactamente** lo mismo que el del PC, carácter a carácter.
 * Tocar el de las notas para eso cambiaría lo que se ve en las notas (sus pruebas lo fijan), así
 * que el lienzo lleva el suyo y las notas siguen con el de siempre.
 *
 * Lo que no se entiende **se enseña tal cual**: antes texto de más que texto perdido. Nunca
 * falla ni se cuelga.
 */
object FormulaRica {

    /** Un trozo de fórmula ya entendido, listo para componer ([CajasRicas.deFormula]). */
    sealed interface Nodo {
        /** Letras, números y símbolos sueltos. */
        data class Texto(val texto: String, val cursiva: Boolean = false) : Nodo
        /** Varios seguidos, en la misma línea. */
        data class Fila(val partes: List<Nodo>) : Nodo
        /** Uno encima de otro, con su raya. */
        data class Fraccion(val arriba: Nodo, val abajo: Nodo) : Nodo
        /** Lo de arriba y lo de abajo pegados a una base: `x^2_i`. */
        data class ConIndices(val base: Nodo, val arriba: Nodo?, val abajo: Nodo?) : Nodo
        /** Una raíz, con su índice si lo lleva. */
        data class Raiz(val dentro: Nodo, val indice: Nodo?) : Nodo
        /** Entre paréntesis que crecen con lo de dentro. */
        data class Agrupado(val dentro: Nodo, val abre: String, val cierra: String) : Nodo
        /** Una matriz o un sistema: filas de celdas y lo que la abre y la cierra. */
        data class Tabla(
            val filas: List<List<Nodo>>,
            val abre: String,
            val cierra: String,
            val izquierda: Boolean
        ) : Nodo
        /** Una marca que crece con lo que cubre: la raya de AB, el arco de AC... */
        data class Adornado(val dentro: Nodo, val adorno: Adorno) : Nodo
        /** De otro color; el color va como se escribió (`red`, `rojo`, `#e53935`). */
        data class Color(val color: String, val dentro: Nodo) : Nodo
        /** En negrita (`\mathbf`, `\textbf`, `\boldsymbol`, `\bm`). */
        data class Negrita(val dentro: Nodo) : Nodo
        /** Dentro de un recuadro (`\boxed{}`, `\fbox{}`). */
        data class Recuadro(val dentro: Nodo) : Nodo
        /** Algo pequeño encima de otra cosa (`\overset{a}{=}`). */
        data class Encima(val base: Nodo, val arriba: Nodo) : Nodo
        /** Lo pequeño centrado debajo (`\underset{n}{x}`, el rótulo de `\underbrace`). */
        data class Debajo(val base: Nodo, val abajo: Nodo) : Nodo
        /** Con su tamaño de display (`\dfrac`, `\displaystyle`): no encoge en un renglón. */
        data class Grande(val dentro: Nodo) : Nodo
    }

    /** Las marcas de [Nodo.Adornado]. */
    enum class Adorno { RAYA_ENCIMA, ARCO_ENCIMA, FLECHA, GORRO, TILDE, RAYA_DEBAJO, LLAVE_DEBAJO }

    private fun texto(t: String): Nodo = Nodo.Texto(t, false)

    /** Los símbolos que se escriben con barra invertida (los del PC, en su orden). */
    private val SIMBOLOS: Map<String, String> = linkedMapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω", "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ",
        "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ",
        "Omega" to "Ω", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "·",
        "ast" to "∗", "star" to "⋆", "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡", "sim" to "∼",
        "propto" to "∝", "infty" to "∞", "partial" to "∂", "nabla" to "∇", "sum" to "∑",
        "prod" to "∏", "int" to "∫", "oint" to "∮", "sqrt" to "√", "angle" to "∠",
        "degree" to "°", "in" to "∈", "notin" to "∉", "subset" to "⊂", "supset" to "⊃",
        "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "forall" to "∀", "exists" to "∃",
        "neg" to "¬", "land" to "∧", "lor" to "∨", "rightarrow" to "→", "to" to "→",
        "leftarrow" to "←", "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "ldots" to "…", "cdots" to "⋯", "perp" to "⊥", "parallel" to "∥",
        "dots" to "…", "dotsc" to "…", "dotsb" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        "circ" to "∘", "prime" to "′", "iff" to "⇔", "implies" to "⇒", "impliedby" to "⇐",
        "mid" to "|", "vert" to "|", "lvert" to "|", "rvert" to "|", "Vert" to "‖",
        "langle" to "⟨", "rangle" to "⟩", "setminus" to "∖", "ell" to "ℓ", "hbar" to "ℏ",
        "therefore" to "∴", "because" to "∵", "triangle" to "△", "square" to "□",
        "simeq" to "≃", "cong" to "≅", "ll" to "≪", "gg" to "≫", "subseteq" to "⊆",
        "supseteq" to "⊇", "varepsilon" to "ε", "vartheta" to "ϑ", "varphi" to "φ",
        "varrho" to "ϱ", "varsigma" to "ς", "mapsto" to "↦", "longrightarrow" to "⟶",
        "uparrow" to "↑", "downarrow" to "↓", "bullet" to "•", "checkmark" to "✓",
        "leqslant" to "≤", "geqslant" to "≥", "lceil" to "⌈", "rceil" to "⌉", "lfloor" to "⌊",
        "rfloor" to "⌋", "aleph" to "ℵ", "Re" to "ℜ", "Im" to "ℑ",
        "measuredangle" to "∡", "sphericalangle" to "∢", "angulo" to "∢", "frown" to "⌢",
        "smile" to "⌣", "gets" to "←", "nparallel" to "∦", "ncong" to "≇", "nsim" to "≁",
        "doteq" to "≐", "odot" to "⊙", "oplus" to "⊕", "otimes" to "⊗", "blacksquare" to "■",
        "lozenge" to "◊", "diamond" to "⋄", "textdegree" to "°", "cdotp" to "·", "backsim" to "∽"
    )

    /** Los mandatos que no se ven (tamaños, estilo): se quitan. */
    private val MUDOS = setOf(
        "left", "right", "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits",
        "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr",
        "middle", "hline", "noindent", "centering", "nonumber", "notag"
    )

    /** Las letras de pizarra (`\mathbb{R}`). */
    private val PIZARRA = mapOf(
        'R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'P' to "ℙ", 'E' to "𝔼"
    )

    /** Las palabras que van derechas y no en cursiva. */
    private val FUNCIONES = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh",
        "tanh", "log", "ln", "exp", "lim", "max", "min", "det", "gcd", "mod", "sen", "tg", "ctg",
        "cosec", "arcsen", "arctg", "lg", "arg", "sup", "Pr", "dim", "ker", "coth", "arcsinh",
        "arccosh", "arctanh"
    )

    /** Lo que se escribe de forma corriente y significa un símbolo. */
    private val PALABRAS = mapOf(
        "inf" to "∞", "infinito" to "∞", "infinity" to "∞", "grados" to "°", "deg" to "°",
        "sum" to "∑", "suma" to "∑", "prod" to "∏", "int" to "∫", "raiz" to "√"
    )

    /** Parejas de signos que se escriben con dos teclas y significan una (en este orden). */
    private val PAREJAS = listOf(
        "<=" to "≤", ">=" to "≥", "!=" to "≠", "/=" to "≠", "+-" to "±", "-+" to "∓",
        "==" to "≡", "~=" to "≈", "->" to "→", "=>" to "⇒", "<-" to "←", "<->" to "↔"
    )

    /** **Lee una fórmula** a su árbol. Nunca falla: lo raro sale tal cual. */
    fun leer(formula: String): Nodo {
        val l = Lector(formula)
        val partes = mutableListOf(l.expresion(null))
        // Lo que corta la expresión (una coma, un `)` suelto) se escribe y se sigue.
        while (l.i < l.s.length) {
            val c = l.s[l.i]
            l.i++
            partes += texto(if (c == ',' || c == ';') "$c " else c.toString())
            partes += l.expresion(null)
        }
        return if (partes.size == 1) partes[0] else Nodo.Fila(partes)
    }

    private class Lector(val s: String) {
        var i = 0

        fun en(k: Int): Char? = if (k in s.indices) s[k] else null

        fun esFin(fin: Char?): Boolean = i >= s.length || (fin != null && en(i) == fin)

        /** Sumas, restas y comparaciones: el nivel más suelto. */
        fun expresion(fin: Char?): Nodo {
            val partes = mutableListOf(termino(fin))
            while (true) {
                saltarEspacios()
                if (esFin(fin)) break
                val signo = signoSuelto()
                if (signo == null) {
                    // Dentro de un grupo (`{(x; y)}`) una coma no lo cierra: se escribe y se
                    // sigue hasta la `}`.
                    val c = en(i)
                    if (fin != null && c != null && c != fin) {
                        i++
                        partes += texto(if (c == ',' || c == ';') "$c " else c.toString())
                        partes += termino(fin)
                        continue
                    }
                    break
                }
                partes += texto(" $signo ")
                partes += termino(fin)
            }
            return if (partes.size == 1) partes[0] else Nodo.Fila(partes)
        }

        fun saltarEspacios() {
            while (en(i) == ' ') i++
        }

        fun empieza(t: String): Boolean = s.startsWith(t, i)

        fun signoSuelto(): String? {
            for ((escrito, simbolo) in PAREJAS) {
                if (empieza(escrito)) {
                    i += escrito.length
                    return simbolo
                }
            }
            val c = en(i) ?: return null
            if (c in "+-=<>≤≥≠≈") {
                i++
                return c.toString()
            }
            return null
        }

        /** Lo que se multiplica y se divide. **La barra hace una fracción**. */
        fun termino(fin: Char?): Nodo {
            var izquierda = factor(fin)
            while (true) {
                val antes = i
                saltarEspacios()
                if (esFin(fin)) {
                    i = antes
                    break
                }
                val c = s[i]
                if (c == '/') {
                    i++
                    izquierda = Nodo.Fraccion(izquierda, factor(fin))
                } else if (c == '*') {
                    i++
                    val d = factor(fin)
                    izquierda = Nodo.Fila(listOf(izquierda, texto("·"), d))
                } else if (esDeFactor(c)) {
                    val d = factor(fin)
                    izquierda = Nodo.Fila(listOf(izquierda, d))
                } else {
                    i = antes
                    break
                }
            }
            return izquierda
        }

        /** Un factor con sus exponentes y subíndices pegados. */
        fun factor(fin: Char?): Nodo {
            var base = atomo(fin)
            while (true) {
                val c = en(i)
                if (c != '^' && c != '_') break
                i++
                val indice = atomo(fin)
                // `90^\circ` y `90^{\circ}` son grados: el signo de grado.
                if (c == '^' && plano(indice).trim().let { it == "∘" || it == "°" }) {
                    base = Nodo.Fila(listOf(base, texto("°")))
                    continue
                }
                base = conIndice(base, indice, c == '^')
            }
            return base
        }

        /** Lo más pequeño: un número, una letra, un paréntesis, un mandato. */
        fun atomo(fin: Char?): Nodo {
            saltarEspacios()
            if (esFin(fin)) return texto("")
            val c = s[i]
            return when {
                c == '\\' -> mandato()
                c == '(' || c == '[' -> agrupado(c)
                c == '{' -> {
                    i++
                    val d = expresion('}')
                    if (i < s.length) i++
                    d
                }
                c in '0'..'9' || c == '.' -> numero()
                c.isLetter() -> palabra()
                else -> {
                    i++
                    texto(c.toString())
                }
            }
        }

        fun numero(): Nodo {
            val n = StringBuilder()
            while (true) {
                val c = en(i) ?: break
                if (!(c in '0'..'9' || c == '.' || c == ',')) break
                n.append(c)
                i++
            }
            return texto(n.toString())
        }

        /** `pi` sale π sin barra, `sqrt(x)` hace raíz, `sin(x)` sale derecho, `xy` son dos letras. */
        fun palabra(): Nodo {
            val desde = i
            val w = StringBuilder()
            while (true) {
                val c = en(i) ?: break
                if (!c.isLetter()) break
                w.append(c)
                i++
            }
            val palabra = w.toString()
            val minus = palabra.lowercase()
            if (palabra == "sqrt" || minus == "raiz" || minus == "raíz") {
                return Nodo.Raiz(argumento(), null)
            }
            if (minus in FUNCIONES) {
                saltarEspacios()
                if (en(i) == '(') {
                    val a = agrupado('(')
                    return Nodo.Fila(listOf(texto(minus), a))
                }
                val a = argumento()
                return Nodo.Fila(listOf(texto(minus), texto(" "), a))
            }
            SIMBOLOS[palabra]?.let { return texto(it) }
            PALABRAS[minus]?.let { return texto(it) }
            // Letras sueltas: cada una es una variable.
            i = desde + 1
            return Nodo.Texto(palabra.firstOrNull()?.toString() ?: " ", cursiva = true)
        }

        /** Lo que va detrás de una función o de una raíz. */
        fun argumento(): Nodo {
            saltarEspacios()
            val c = en(i)
            if (c == '(' || c == '{') {
                val cierra = if (c == '(') ')' else '}'
                i++
                val dentro = expresion(cierra)
                if (i < s.length) i++
                return dentro
            }
            return atomo(null)
        }

        /** Un grupo entre llaves, o el siguiente carácter suelto. */
        fun grupo(): Nodo {
            val c = en(i) ?: return texto("")
            if (c == '{') {
                i++
                val dentro = expresion('}')
                if (i < s.length) i++
                return dentro
            }
            if (c == '\\') return mandato()
            i++
            return Nodo.Texto(c.toString(), cursiva = c.isLetter())
        }

        fun mandato(): Nodo {
            i++ // la barra
            val nombre = StringBuilder()
            while (true) {
                val c = en(i) ?: break
                if (!c.isLetter()) break
                nombre.append(c)
                i++
            }
            val palabra = nombre.toString()
            when (palabra) {
                "frac", "tfrac" -> {
                    val a = grupo()
                    val b = grupo()
                    return Nodo.Fraccion(a, b)
                }
                "dfrac", "cfrac" -> {
                    val a = grupo()
                    val b = grupo()
                    return Nodo.Grande(Nodo.Fraccion(a, b))
                }
                "color", "textcolor", "colorbox" -> {
                    val color = grupoCrudo().trim()
                    saltarEspacios()
                    val dentro = if (en(i) == '{' || palabra != "color") grupo()
                    else expresion('}')
                    return Nodo.Color(color, dentro)
                }
                "boxed", "fbox", "framebox" -> {
                    val crudo = grupoCrudo()
                    val dentro = if (palabra == "boxed") leer(crudo) else texto(crudo)
                    return Nodo.Recuadro(dentro)
                }
                "overline", "bar", "widebar", "overarc", "wideparen", "arc", "overparen",
                "vec", "overrightarrow", "hat", "widehat", "tilde", "widetilde", "underline" -> {
                    val base = grupo()
                    val adorno = when (palabra) {
                        "overline", "bar", "widebar" -> Adorno.RAYA_ENCIMA
                        "vec", "overrightarrow" -> Adorno.FLECHA
                        "hat", "widehat" -> Adorno.GORRO
                        "tilde", "widetilde" -> Adorno.TILDE
                        "underline" -> Adorno.RAYA_DEBAJO
                        else -> Adorno.ARCO_ENCIMA
                    }
                    return Nodo.Adornado(base, adorno)
                }
                "underbrace" -> {
                    val base = Nodo.Adornado(grupo(), Adorno.LLAVE_DEBAJO)
                    saltarEspacios()
                    return if (en(i) == '_') {
                        i++
                        Nodo.Debajo(base, grupo())
                    } else base
                }
                "underset" -> {
                    val abajo = grupo()
                    val base = grupo()
                    return Nodo.Debajo(base, abajo)
                }
                "overset", "stackrel" -> {
                    val arriba = grupo()
                    val base = grupo()
                    return when (plano(arriba).trim()) {
                        "⌢" -> Nodo.Adornado(base, Adorno.ARCO_ENCIMA)
                        "‾", "-", "—" -> Nodo.Adornado(base, Adorno.RAYA_ENCIMA)
                        "→", "⟶" -> Nodo.Adornado(base, Adorno.FLECHA)
                        else -> Nodo.Encima(base, arriba)
                    }
                }
                "displaystyle" -> return Nodo.Grande(expresion('}'))
                "sqrt" -> {
                    var indice: Nodo? = null
                    if (en(i) == '[') {
                        i++
                        indice = expresion(']')
                        if (i < s.length) i++
                    }
                    return Nodo.Raiz(grupo(), indice)
                }
            }
            if (palabra in MUDOS) return texto("")
            if (palabra in FUNCIONES) {
                var k = i
                while (en(k) == ' ') k++
                return when (en(k)) {
                    '(', '^', '_', null -> texto(palabra)
                    else -> Nodo.Fila(listOf(texto(palabra), texto(" ")))
                }
            }
            SIMBOLOS[palabra]?.let { return texto(it) }
            return when (palabra) {
                "text", "textrm", "textup", "mbox", "textnormal", "mathrm", "operatorname",
                "mathsf", "texttt", "mathtt" -> texto(grupoCrudo())
                "textbf", "mathbf", "boldsymbol", "bm" -> {
                    val crudo = grupoCrudo()
                    val dentro = if (palabra != "textbf" &&
                        crudo.any { it == '^' || it == '_' || it == '\\' || it == '{' }
                    ) leer(crudo)
                    else if (palabra == "boldsymbol" || palabra == "bm") Nodo.Texto(crudo, true)
                    else texto(crudo)
                    Nodo.Negrita(dentro)
                }
                "textit", "mathit", "emph" -> Nodo.Texto(grupoCrudo(), cursiva = true)
                "mathbb", "mathcal", "mathscr", "mathfrak" -> {
                    val crudo = grupoCrudo()
                    texto(crudo.map { c -> PIZARRA[c] ?: c.toString() }.joinToString(""))
                }
                "dot", "ddot" -> {
                    val base = grupo()
                    val marca = if (palabra == "dot") "˙" else "¨"
                    Nodo.ConIndices(base, texto(marca), null)
                }
                "quad" -> texto("  ")
                "phantom", "hphantom", "vphantom" -> {
                    val p = plano(grupo())
                    val n = p.codePointCount(0, p.length).coerceAtLeast(1)
                    texto(" ".repeat(n))
                }
                "overbrace" -> {
                    val base = Nodo.Adornado(grupo(), Adorno.ARCO_ENCIMA)
                    saltarEspacios()
                    if (en(i) == '^') {
                        i++
                        Nodo.Encima(base, grupo())
                    } else base
                }
                "qquad" -> texto("    ")
                "begin" -> entorno()
                "end" -> {
                    grupoCrudo()
                    texto("")
                }
                "" -> {
                    // `\{`, `\%`: el carácter de detrás; los espacios de LaTeX, un espacio.
                    val c = en(i) ?: ' '
                    i++
                    when (c) {
                        ',', ';', ':', ' ' -> texto(" ")
                        '!' -> texto("")
                        '\\' -> texto(" ")
                        else -> texto(c.toString())
                    }
                }
                // Ni idea: se enseña con su barra.
                else -> texto("\\$palabra")
            }
        }

        fun agrupado(abre: Char): Nodo {
            val cierra = if (abre == '(') ')' else ']'
            val desde = i
            i++
            val dentro = expresion(cierra)
            return if (en(i) == cierra) {
                i++
                Nodo.Agrupado(dentro, abre.toString(), cierra.toString())
            } else {
                // Sin cierre no hay grupo: se devuelve tal cual para no perder nada.
                i = desde + 1
                texto(abre.toString())
            }
        }

        /** Lo de dentro de unas llaves, sin leer, o el siguiente carácter si no hay llaves. */
        fun grupoCrudo(): String {
            saltarEspacios()
            val c = en(i) ?: return ""
            if (c != '{') {
                i++
                return c.toString()
            }
            var prof = 0
            val desde = i + 1
            while (true) {
                val d = en(i) ?: break
                i++
                when (d) {
                    '\\' -> i++
                    '{' -> prof++
                    '}' -> {
                        prof--
                        if (prof == 0) return s.substring(desde, i - 1)
                    }
                }
            }
            return s.substring(minOf(desde, s.length))
        }

        /** Un entorno (`\begin{pmatrix} ... \end{pmatrix}`) a una [Nodo.Tabla]. */
        fun entorno(): Nodo {
            val nombre = grupoCrudo()
            val base = nombre.trimEnd('*')
            if (base == "array" || base == "tabular") grupoCrudo()
            val abre = "\\begin{$nombre}"
            val cierra = "\\end{$nombre}"
            val desde = minOf(i, s.length)
            var prof = 1
            var fin = s.length
            var k = desde
            while (k < s.length) {
                if (s.startsWith(abre, k)) {
                    prof++
                    k += abre.length
                } else if (s.startsWith(cierra, k)) {
                    prof--
                    if (prof == 0) {
                        fin = k
                        break
                    }
                    k += cierra.length
                } else {
                    k++
                }
            }
            val cuerpo = s.substring(desde, fin)
            i = minOf(fin + cierra.length, s.length)
            val (a, c, izquierda) = when (base) {
                "pmatrix", "smallmatrix" -> Triple("(", ")", false)
                "bmatrix" -> Triple("[", "]", false)
                "Bmatrix" -> Triple("{", "}", false)
                "vmatrix" -> Triple("|", "|", false)
                "Vmatrix" -> Triple("‖", "‖", false)
                "cases", "dcases", "rcases" -> Triple("{", "", true)
                "matrix", "array", "tabular" -> Triple("", "", false)
                else -> Triple("", "", true)
            }
            val alineado = izquierda && a.isEmpty()
            val filas = partirTabla(cuerpo).map { celdas ->
                if (alineado) listOf(leer(celdas.joinToString(" ")))
                else celdas.map { leer(it) }
            }
            return Nodo.Tabla(filas, a, c, izquierda)
        }
    }

    /** El cuerpo de un entorno en filas (`\\`) y celdas (`&`), sin mirar dentro de las llaves. */
    private fun partirTabla(cuerpo: String): List<List<String>> {
        val c = cuerpo
        val filas = mutableListOf<List<String>>()
        var fila = mutableListOf<String>()
        var celda = StringBuilder()
        var prof = 0
        var i = 0
        while (i < c.length) {
            val ch = c[i]
            if (ch == '\\' && prof == 0 && c.getOrNull(i + 1) == '\\') {
                fila += celda.toString()
                celda = StringBuilder()
                filas += fila
                fila = mutableListOf()
                i += 2
                if (c.getOrNull(i) == '[') {
                    while (i < c.length && c[i] != ']') i++
                    i++
                }
                continue
            }
            if (ch == '\\') {
                celda.append('\\')
                c.getOrNull(i + 1)?.let { celda.append(it) }
                i += 2
                continue
            }
            when (ch) {
                '{' -> prof++
                '}' -> prof--
                '&' -> if (prof == 0) {
                    fila += celda.toString()
                    celda = StringBuilder()
                    i++
                    continue
                }
            }
            celda.append(ch)
            i++
        }
        fila += celda.toString()
        filas += fila
        return filas
            .map { f -> f.map { it.replace("\\hline", "").trim() } }
            .filter { f -> f.any { it.isNotEmpty() } }
    }

    private fun esDeFactor(c: Char): Boolean =
        c.isLetterOrDigit() || c == '(' || c == '{' || c == '\\' || c == '.'

    /** Junta índices sobre la misma base: `x^2_i` es una sola cosa. */
    private fun conIndice(base: Nodo, indice: Nodo, arriba: Boolean): Nodo =
        if (base is Nodo.ConIndices) {
            if (arriba) base.copy(arriba = indice) else base.copy(abajo = indice)
        } else {
            if (arriba) Nodo.ConIndices(base, indice, null) else Nodo.ConIndices(base, null, indice)
        }

    /** El árbol en texto plano de pruebas (`(a/b)`, `x^2_i`, `sqrt(x)`), el `plano` de `formulas.rs`. */
    fun plano(p: Nodo): String = when (p) {
        is Nodo.Texto -> p.texto
        is Nodo.Fila -> p.partes.joinToString("") { plano(it) }
        is Nodo.Fraccion -> "(${plano(p.arriba)}/${plano(p.abajo)})"
        is Nodo.ConIndices -> buildString {
            append(plano(p.base))
            p.arriba?.let { append('^').append(plano(it)) }
            p.abajo?.let { append('_').append(plano(it)) }
        }
        is Nodo.Raiz -> "sqrt(${plano(p.dentro)})"
        is Nodo.Agrupado -> "${p.abre}${plano(p.dentro)}${p.cierra}"
        is Nodo.Tabla -> p.abre +
            p.filas.joinToString("; ") { f -> f.joinToString(", ") { plano(it) } } + p.cierra
        is Nodo.Adornado -> plano(p.dentro) + "^" + when (p.adorno) {
            Adorno.RAYA_ENCIMA -> "‾"
            Adorno.ARCO_ENCIMA -> "⌒"
            Adorno.FLECHA -> "→"
            Adorno.GORRO -> "^"
            Adorno.TILDE -> "~"
            Adorno.RAYA_DEBAJO -> "_"
            Adorno.LLAVE_DEBAJO -> ""
        }
        is Nodo.Color -> plano(p.dentro)
        is Nodo.Negrita -> plano(p.dentro)
        is Nodo.Grande -> plano(p.dentro)
        is Nodo.Recuadro -> "[${plano(p.dentro)}]"
        is Nodo.Encima -> "${plano(p.base)}^${plano(p.arriba)}"
        is Nodo.Debajo -> "${plano(p.base)} (${plano(p.abajo)})"
    }

    /** La marca de un adorno escrita detrás, para el texto legible. */
    private fun marcaDe(a: Adorno): String = when (a) {
        Adorno.RAYA_ENCIMA -> "̅"
        Adorno.ARCO_ENCIMA -> "⌒"
        Adorno.FLECHA -> "⃗"
        Adorno.GORRO -> "̂"
        Adorno.TILDE -> "̃"
        Adorno.RAYA_DEBAJO -> "̲"
        Adorno.LLAVE_DEBAJO -> ""
    }

    private val ARRIBA = mapOf(
        '0'.code to "⁰", '1'.code to "¹", '2'.code to "²", '3'.code to "³", '4'.code to "⁴",
        '5'.code to "⁵", '6'.code to "⁶", '7'.code to "⁷", '8'.code to "⁸", '9'.code to "⁹",
        '+'.code to "⁺", '-'.code to "⁻", 'n'.code to "ⁿ", 'i'.code to "ⁱ", '('.code to "⁽",
        ')'.code to "⁾"
    )
    private val ABAJO = mapOf(
        '0'.code to "₀", '1'.code to "₁", '2'.code to "₂", '3'.code to "₃", '4'.code to "₄",
        '5'.code to "₅", '6'.code to "₆", '7'.code to "₇", '8'.code to "₈", '9'.code to "₉",
        '+'.code to "₊", '-'.code to "₋", 'a'.code to "ₐ", 'e'.code to "ₑ", 'o'.code to "ₒ",
        'x'.code to "ₓ", 'i'.code to "ᵢ", 'n'.code to "ₙ"
    )

    /** Todo en super o subíndice, o `null` si algún carácter no lo tiene. */
    private fun alzado(s: String, arriba: Boolean): String? {
        val tabla = if (arriba) ARRIBA else ABAJO
        val out = StringBuilder()
        for (cp in s.codePoints()) out.append(tabla[cp] ?: return null)
        return out.toString()
    }

    private fun compuesto(p: Nodo): Boolean {
        val s = legible(p)
        return s.codePointCount(0, s.length) > 1 && s.any { it in "+-−=·×÷ /" }
    }

    private fun entre(p: Nodo): String = if (compuesto(p)) "(${legible(p)})" else legible(p)

    /**
     * **La fórmula escrita para quien no sabe componerla**: el texto de reserva de un texto rico
     * (`text`), como en un cuaderno: `x²`, `a₁`, `(2a + 2b)/2`, `√(x)`, `A̅B̅`, `AC⌒`, `45°`. Es
     * el `legible` del PC, y tiene que dar lo mismo que él: de eso depende la regla de coherencia.
     */
    fun legible(p: Nodo): String = when (p) {
        is Nodo.Texto -> p.texto
        is Nodo.Fila -> p.partes.joinToString("") { legible(it) }
        is Nodo.Fraccion -> "${entre(p.arriba)}/${entre(p.abajo)}"
        is Nodo.ConIndices -> buildString {
            append(legible(p.base))
            for ((k, sube) in listOf(p.abajo to false, p.arriba to true)) {
                if (k == null) continue
                val t = legible(k)
                val u = alzado(t, sube)
                if (u != null) append(u) else append(if (sube) '^' else '_').append('(').append(t).append(')')
            }
        }
        is Nodo.Raiz -> {
            val i = p.indice?.let { alzado(legible(it), true) ?: "" } ?: ""
            "$i√${entre(p.dentro)}"
        }
        is Nodo.Agrupado -> "${p.abre}${legible(p.dentro)}${p.cierra}"
        is Nodo.Tabla -> p.abre +
            p.filas.joinToString("; ") { f -> f.joinToString(", ") { legible(it) } } + p.cierra
        is Nodo.Adornado -> if (p.adorno == Adorno.ARCO_ENCIMA) "${legible(p.dentro)}⌒" else {
            // Una marca combinante detrás de cada letra: A̅B̅.
            val m = marcaDe(p.adorno)
            val d = legible(p.dentro)
            buildString { for (cp in d.codePoints()) appendCodePoint(cp).append(m) }
        }
        is Nodo.Color -> legible(p.dentro)
        is Nodo.Negrita -> legible(p.dentro)
        is Nodo.Grande -> legible(p.dentro)
        is Nodo.Recuadro -> legible(p.dentro)
        is Nodo.Encima -> legible(p.base) + (alzado(legible(p.arriba), true) ?: "")
        is Nodo.Debajo -> "${legible(p.base)} (${legible(p.abajo)})"
    }

    // `String.codePoints()` es de Java 9+ y no está en todos los Android: se recorre a mano.
    private fun String.codePoints(): Sequence<Int> = sequence {
        var k = 0
        while (k < length) {
            val cp = codePointAt(k)
            yield(cp)
            k += Character.charCount(cp)
        }
    }
}
