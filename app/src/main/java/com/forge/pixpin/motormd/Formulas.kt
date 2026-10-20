package com.forge.pixpin.motormd

/**
 * Un trozo de fórmula ya entendido, listo para componer.
 *
 * Es un árbol, no una lista, porque una fórmula lo es: el numerador de una
 * fracción puede llevar dentro otra fracción con un exponente que a su vez lleva
 * una raíz. Pintar eso desde una cadena de texto es imposible; desde un árbol es
 * bajar por él.
 *
 * Es el mismo árbol que el del PC (`crates/pixpin-motor2d/src/formulas.rs`, que se portó de
 * aquí el 13-oct y creció el 15-oct con matrices, adornos, colores y recuadros); el 18-oct se
 * trajo de vuelta para el texto rico del lienzo ([com.forge.pixpin.motor.TextoRico]).
 */
sealed interface Pieza {
    /** Letras, números y símbolos sueltos. */
    data class Texto(val texto: String, val cursiva: Boolean = false) : Pieza

    /** Varios seguidos, en la misma línea. */
    data class Fila(val partes: List<Pieza>) : Pieza

    /** Una fracción: uno encima de otro, con su raya. */
    data class Fraccion(val arriba: Pieza, val abajo: Pieza) : Pieza

    /** Lo de arriba y lo de abajo pegados a una base: `x^2_i`. */
    data class ConIndices(
        val base: Pieza,
        val arriba: Pieza? = null,
        val abajo: Pieza? = null
    ) : Pieza

    /** Una raíz, con su índice si lo lleva: `\sqrt[3]{x}`. */
    data class Raiz(val dentro: Pieza, val indice: Pieza? = null) : Pieza

    /** Entre paréntesis que crecen con lo que hay dentro. */
    data class Agrupado(val dentro: Pieza, val abre: String, val cierra: String) : Pieza

    /**
     * **Una matriz o un sistema** (`\begin{bmatrix}`, `pmatrix`, `vmatrix`, `cases`,
     * `aligned`...): filas de celdas y lo que la abre y la cierra. [izquierda]: celdas pegadas a
     * la izquierda (sistemas y alineados), si no centradas.
     */
    data class Tabla(
        val filas: List<List<Pieza>>,
        val abre: String,
        val cierra: String,
        val izquierda: Boolean
    ) : Pieza

    /**
     * **Una marca que crece con lo que cubre** (PC, 15-oct-2026, láminas de geometría): la raya
     * de un segmento (`\overline{AB}`), el arco (`\overset{\frown}{AC}`), la flecha de un
     * vector, el gorro, la tilde y el subrayado.
     */
    data class Adornada(val dentro: Pieza, val adorno: Adorno) : Pieza

    /** **De otro color** (`\color{red}{X}`): el color como se escribió; lo traduce quien compone. */
    data class Color(val color: String, val dentro: Pieza) : Pieza

    /** En negrita (`\mathbf`, `\textbf`, `\boldsymbol`). */
    data class Negrita(val dentro: Pieza) : Pieza

    /** Dentro de un recuadro (`\boxed{}`): el resultado final. */
    data class Recuadro(val dentro: Pieza) : Pieza

    /** Algo pequeño encima de otra cosa (`\overset{a}{=}`). */
    data class Encima(val base: Pieza, val arriba: Pieza) : Pieza

    /** Lo pequeño centrado debajo: `\underset{n}{x}`, el rótulo de `\underbrace{SS...S}_{n veces}` (PC 18-oct). */
    data class Debajo(val base: Pieza, val abajo: Pieza) : Pieza

    /** **Con su tamaño de display** (`\dfrac`): una fracción así no encoge en un renglón. */
    data class Grande(val dentro: Pieza) : Pieza
}

/** Las marcas de [Pieza.Adornada]. */
enum class Adorno {
    RAYA_ENCIMA, ARCO_ENCIMA, FLECHA, GORRO, TILDE, RAYA_DEBAJO,
    /** `\underbrace`: la llave de «"n" veces» (PC 18-oct). */
    LLAVE_DEBAJO
}

/**
 * Entiende fórmulas escritas en LaTeX, del subconjunto que se usa en unas notas.
 *
 * ## Por qué propio y no una librería
 *
 * Componer LaTeX entero es un trabajo enorme: es un lenguaje con macros, y los
 * motores que lo hacen bien —KaTeX, MathJax— son megas de JavaScript que hay que
 * meter en un navegador dentro de la app. En una nota que flota sobre otra
 * aplicación, arrastrar un navegador entero para poner un exponente sale caro en
 * memoria, en arranque y en cosas que se pueden torcer.
 *
 * Lo que se escribe en una nota, en cambio, es un puñado de construcciones:
 * fracciones, exponentes, subíndices, raíces, letras griegas y los símbolos de
 * siempre. Eso cabe aquí, se compone bien, y no depende de nada.
 *
 * Lo que no entienda **se enseña tal cual**, que es la misma promesa que el resto
 * del parser: antes texto de más que texto perdido.
 */
object Formulas {

    /** **Lee una fórmula** a su árbol. Nunca falla: lo raro sale tal cual. */
    fun leer(formula: String): Pieza {
        val l = Lector(formula)
        val partes = mutableListOf(l.expresion(null))
        // Lo que corta la expresión (una coma, un `)` suelto) se escribe y se sigue: antes se
        // perdía todo lo de detrás («x ∈ ℝ, rang(A) = 3» se quedaba en «x ∈ ℝ»).
        while (l.i < formula.length) {
            val c = formula[l.i]
            l.i++
            partes += texto(if (c == ',' || c == ';') "$c " else c.toString())
            partes += l.expresion(null)
        }
        return if (partes.size == 1) partes[0] else Pieza.Fila(partes)
    }

    private fun texto(t: String) = Pieza.Texto(t)

    /**
     * Los símbolos que se escriben con barra invertida. Los mismos del PC (`formulas.rs`), con
     * los que añadió al pasar láminas de geometría y hojas el 15-oct.
     */
    private val SIMBOLOS = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ",
        "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ",
        "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ",
        "Psi" to "Ψ", "Omega" to "Ω",
        "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "cdot" to "·", "ast" to "∗", "star" to "⋆",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡",
        "sim" to "∼", "propto" to "∝",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
        "sqrt" to "√", "angle" to "∠", "degree" to "°",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "supset" to "⊃",
        "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "forall" to "∀",
        "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨",
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←",
        "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
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
        "leqslant" to "≤", "geqslant" to "≥", "lceil" to "⌈", "rceil" to "⌉",
        "lfloor" to "⌊", "rfloor" to "⌋", "aleph" to "ℵ", "Re" to "ℜ", "Im" to "ℑ",
        // Geometría (láminas del 15-oct): «m∢AOB», arcos, perpendiculares.
        "measuredangle" to "∡", "sphericalangle" to "∢", "angulo" to "∢", "frown" to "⌢", "smile" to "⌣",
        "gets" to "←", "nparallel" to "∦", "ncong" to "≇", "nsim" to "≁", "doteq" to "≐", "odot" to "⊙",
        "oplus" to "⊕", "otimes" to "⊗", "blacksquare" to "■", "lozenge" to "◊", "diamond" to "⋄",
        "textdegree" to "°", "cdotp" to "·", "backsim" to "∽"
    )

    /** Los mandatos que no se ven (tamaños, estilo): se quitan. */
    private val MUDOS = setOf(
        "left", "right", "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "big", "Big",
        "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "middle", "hline", "noindent",
        "centering", "nonumber", "notag"
    )

    /** Las letras de pizarra (`\mathbb{R}`). */
    private val PIZARRA = mapOf('R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'P' to "ℙ", 'E' to "𝔼")

    /** Las palabras que van derechas y no en cursiva: `\sin`, `\log`… */
    private val FUNCIONES = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "log", "ln", "exp", "lim", "max", "min", "det", "gcd", "mod", "sen", "tg", "ctg", "cosec",
        "arcsen", "arctg", "lg", "arg", "sup", "Pr", "dim", "ker", "coth", "arcsinh", "arccosh", "arctanh"
    )

    /** Lo que se escribe de forma corriente y significa un símbolo. */
    private val PALABRAS = mapOf(
        "inf" to "∞", "infinito" to "∞", "infinity" to "∞",
        "grados" to "°", "deg" to "°",
        "sum" to "∑", "suma" to "∑", "prod" to "∏", "int" to "∫",
        "raiz" to "√"
    )

    /** Parejas de signos que se escriben con dos teclas y significan una. */
    private val PAREJAS = listOf(
        "<=" to "≤", ">=" to "≥", "!=" to "≠", "/=" to "≠",
        "+-" to "±", "-+" to "∓", "==" to "≡", "~=" to "≈",
        "->" to "→", "=>" to "⇒", "<-" to "←", "<->" to "↔"
    )

    private class Lector(private val s: String) {
        var i = 0

        private fun en(k: Int): Char? = s.getOrNull(k)
        private fun esFin(fin: Char?) = i >= s.length || (fin != null && s[i] == fin)

        /**
         * Una expresión entera: sumas, restas y comparaciones.
         *
         * El nivel más suelto de todos, por eso va el primero: en `1/2 + x`, lo
         * que se suma es la fracción entera y la equis, no el 2 y la equis.
         */
        fun expresion(fin: Char?): Pieza {
            val partes = mutableListOf(termino(fin))
            while (true) {
                // Los espacios se saltan **antes** de mirar el signo. Sin esto,
                // `1/2 + x` se paraba en el espacio y devolvía solo la fracción.
                saltarEspacios()
                if (esFin(fin)) break
                val signo = signoSuelto()
                if (signo == null) {
                    // **Dentro de un grupo** (`{(x; y)}`, PC 18-oct): una coma, un punto y coma o un «°» no lo
                    // cierra. Antes se cortaba ahí y la llave salía suelta («F (x y)}»): se escribe y se sigue.
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
            return if (partes.size == 1) partes[0] else Pieza.Fila(partes)
        }

        private fun saltarEspacios() {
            while (i < s.length && s[i] == ' ') i++
        }

        /** Los signos que separan términos, ya convertidos a su símbolo. */
        private fun signoSuelto(): String? {
            PAREJAS.forEach { (escrito, simbolo) ->
                if (s.startsWith(escrito, i)) { i += escrito.length; return simbolo }
            }
            val c = en(i) ?: return null
            if (c in "+-=<>≤≥≠≈") { i++; return c.toString() }
            return null
        }

        /**
         * Un término: lo que se multiplica y se divide.
         *
         * **La barra hace una fracción de verdad.** `1/2` se escribe en dos teclas y sale con
         * su raya, sin `\frac{}{}` ni llaves que cuadrar en una pantalla táctil.
         */
        private fun termino(fin: Char?): Pieza {
            var izquierda = factor(fin)
            while (true) {
                val antes = i
                saltarEspacios()
                if (esFin(fin)) { i = antes; break }
                val c = s[i]
                when {
                    c == '/' -> { i++; izquierda = Pieza.Fraccion(izquierda, factor(fin)) }
                    c == '*' -> {
                        i++
                        val d = factor(fin)
                        izquierda = Pieza.Fila(listOf(izquierda, texto("·"), d))
                    }
                    // Pegado significa multiplicado: `2x`, `ab`, sin signo, como en un libro.
                    esDeFactor(c) -> {
                        val d = factor(fin)
                        izquierda = Pieza.Fila(listOf(izquierda, d))
                    }
                    else -> { i = antes; break }
                }
            }
            return izquierda
        }

        private fun esDeFactor(c: Char): Boolean =
            c.isLetterOrDigit() || c == '(' || c == '{' || c == '\\' || c == '.'

        /** Un factor con sus exponentes y subíndices pegados. */
        private fun factor(fin: Char?): Pieza {
            var base = atomo(fin)
            while (i < s.length && (s[i] == '^' || s[i] == '_')) {
                val arriba = s[i] == '^'
                i++
                val indice = atomo(fin)
                // `90^\circ` y `90^{\circ}` son grados: el signo de grado, no un circulito subido.
                if (arriba && plano(indice).trim().let { it == "∘" || it == "°" }) {
                    base = Pieza.Fila(listOf(base, texto("°")))
                    continue
                }
                base = conIndice(base, indice, arriba)
            }
            return base
        }

        /** Lo más pequeño: un número, una letra, un paréntesis, un mandato. */
        private fun atomo(fin: Char?): Pieza {
            saltarEspacios()
            if (esFin(fin)) return texto("")
            val c = s[i]
            return when {
                c == '\\' -> mandato()
                c == '(' || c == '[' -> agrupado(c)
                c == '{' -> { i++; val d = expresion('}'); if (i < s.length) i++; d }
                c in '0'..'9' || c == '.' -> numero()
                c.isLetter() -> palabra()
                else -> { i++; texto(c.toString()) }
            }
        }

        private fun numero(): Pieza {
            val n = StringBuilder()
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == ',')) {
                n.append(s[i]); i++
            }
            return texto(n.toString())
        }

        /**
         * Una palabra: puede ser un símbolo, una función o letras sueltas.
         *
         * `pi` sale como π sin barra invertida, `sqrt(x)` y `raiz(x)` hacen una
         * raíz, `sin(x)` sale derecho. Y `xy`, que no es nada de eso, sale como
         * dos letras en cursiva multiplicándose, que es lo que significa.
         */
        private fun palabra(): Pieza {
            val desde = i
            val w = StringBuilder()
            while (i < s.length && s[i].isLetter()) { w.append(s[i]); i++ }
            val palabra = w.toString()
            val minus = palabra.lowercase()
            if (palabra == "sqrt" || minus == "raiz" || minus == "raíz") {
                return Pieza.Raiz(argumento())
            }
            if (minus in FUNCIONES) {
                // `sin(3t)` conserva sus paréntesis (sin ellos salía «sin3·t»); `sin x` igual.
                saltarEspacios()
                if (en(i) == '(') return Pieza.Fila(listOf(texto(minus), agrupado('(')))
                val a = argumento()
                return Pieza.Fila(listOf(texto(minus), texto(" "), a))
            }
            SIMBOLOS[palabra]?.let { return texto(it) }
            PALABRAS[minus]?.let { return texto(it) }
            // Letras sueltas: cada una es una variable. Se vuelve a la primera y se devuelve
            // solo esa, para que `2ab` sean dos factores y no una palabra rara.
            i = desde + 1
            return Pieza.Texto(palabra.first().toString(), cursiva = true)
        }

        /** Lo que va detrás de una función o de una raíz. */
        private fun argumento(): Pieza {
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

        /** Un grupo entre llaves, o el siguiente carácter suelto si no las hay. */
        private fun grupo(): Pieza {
            val c = en(i) ?: return texto("")
            if (c == '{') {
                i++
                val dentro = expresion('}')
                if (i < s.length) i++
                return dentro
            }
            if (c == '\\') return mandato()
            i++
            return Pieza.Texto(c.toString(), cursiva = c.isLetter())
        }

        private fun mandato(): Pieza {
            i++ // la barra
            val nombre = StringBuilder()
            while (i < s.length && s[i].isLetter()) { nombre.append(s[i]); i++ }
            val palabra = nombre.toString()
            return when (palabra) {
                "frac", "tfrac" -> {
                    val a = grupo()
                    Pieza.Fraccion(a, grupo())
                }
                // La alta: del tamaño de la letra aunque vaya dentro de un renglón de texto.
                "dfrac", "cfrac" -> {
                    val a = grupo()
                    Pieza.Grande(Pieza.Fraccion(a, grupo()))
                }
                // **Colores** (`\color{red}{X}`; sin llaves detrás, hasta el final del grupo,
                // como en LaTeX: la llave que lo cierra la consume quien lo abrió).
                "color", "textcolor", "colorbox" -> {
                    val color = grupoCrudo().trim()
                    saltarEspacios()
                    val dentro = if (en(i) == '{' || palabra != "color") grupo() else expresion('}')
                    Pieza.Color(color, dentro)
                }
                "boxed", "fbox", "framebox" -> {
                    val crudo = grupoCrudo()
                    Pieza.Recuadro(if (palabra == "boxed") leer(crudo) else texto(crudo))
                }
                // **Arcos, rayas y flechas encima**, del ancho de lo que cubren.
                "overline", "bar", "widebar", "overarc", "wideparen", "arc", "overparen", "vec",
                "overrightarrow", "hat", "widehat", "tilde", "widetilde", "underline" -> {
                    val base = grupo()
                    val adorno = when (palabra) {
                        "overline", "bar", "widebar" -> Adorno.RAYA_ENCIMA
                        "vec", "overrightarrow" -> Adorno.FLECHA
                        "hat", "widehat" -> Adorno.GORRO
                        "tilde", "widetilde" -> Adorno.TILDE
                        "underline" -> Adorno.RAYA_DEBAJO
                        else -> Adorno.ARCO_ENCIMA
                    }
                    Pieza.Adornada(base, adorno)
                }
                // `\underbrace{SS...S}_{"n" veces}`: la llave debajo y su rótulo debajo de ella (PC 18-oct).
                "underbrace" -> {
                    val base = Pieza.Adornada(grupo(), Adorno.LLAVE_DEBAJO)
                    saltarEspacios()
                    if (en(i) == '_') { i++; Pieza.Debajo(base, grupo()) } else base
                }
                // `\underset{n}{x}`: lo pequeño debajo.
                "underset" -> {
                    val abajo = grupo()
                    Pieza.Debajo(grupo(), abajo)
                }
                // `\phantom{xx}`: su hueco, sin tinta (salía como código).
                "phantom", "hphantom", "vphantom" -> {
                    val g = plano(grupo())
                    texto(" ".repeat(maxOf(g.codePointCount(0, g.length), 1)))
                }
                // `\overbrace{...}^{...}`: el arco encima y lo de arriba, encima.
                "overbrace" -> {
                    val base = Pieza.Adornada(grupo(), Adorno.ARCO_ENCIMA)
                    saltarEspacios()
                    if (en(i) == '^') { i++; Pieza.Encima(base, grupo()) } else base
                }
                // `\overset{\frown}{AC}` es el arco; `\overset{?}{=}`, lo pequeño encima.
                "overset", "stackrel" -> {
                    val arriba = grupo()
                    val base = grupo()
                    when (plano(arriba).trim()) {
                        "⌢" -> Pieza.Adornada(base, Adorno.ARCO_ENCIMA)
                        "‾", "-", "—" -> Pieza.Adornada(base, Adorno.RAYA_ENCIMA)
                        "→", "⟶" -> Pieza.Adornada(base, Adorno.FLECHA)
                        else -> Pieza.Encima(base, arriba)
                    }
                }
                "displaystyle" -> Pieza.Grande(expresion('}'))
                "sqrt" -> {
                    var indice: Pieza? = null
                    if (en(i) == '[') {
                        i++
                        indice = expresion(']')
                        if (i < s.length) i++
                    }
                    Pieza.Raiz(grupo(), indice)
                }
                in MUDOS -> texto("")
                // `\ln x` con su hueco (salía «lnx»); `\sin(x)`, `\sin^2` pegados.
                in FUNCIONES -> {
                    var k = i
                    while (en(k) == ' ') k++
                    when (en(k)) {
                        '(', '^', '_', null -> texto(palabra)
                        else -> Pieza.Fila(listOf(texto(palabra), texto(" ")))
                    }
                }
                in SIMBOLOS -> texto(SIMBOLOS.getValue(palabra))
                // Texto derecho, con sus espacios: `\text{si } x > 0`.
                "text", "textrm", "textup", "mbox", "textnormal", "mathrm", "operatorname", "mathsf",
                "texttt", "mathtt" -> texto(grupoCrudo())
                // Negrita: lo de un `\mathbf{x^2}` es fórmula; lo de un `\textbf{}`, texto tal cual.
                "textbf", "mathbf", "boldsymbol", "bm" -> {
                    val crudo = grupoCrudo()
                    val dentro = when {
                        palabra != "textbf" && crudo.any { it in "^_\\{" } -> leer(crudo)
                        palabra == "boldsymbol" || palabra == "bm" -> Pieza.Texto(crudo, cursiva = true)
                        else -> texto(crudo)
                    }
                    Pieza.Negrita(dentro)
                }
                "textit", "mathit", "emph" -> Pieza.Texto(grupoCrudo(), cursiva = true)
                "mathbb", "mathcal", "mathscr", "mathfrak" ->
                    texto(grupoCrudo().map { PIZARRA[it] ?: it.toString() }.joinToString(""))
                // Acentos de vector, media...: la marca encima.
                "dot", "ddot" -> {
                    val base = grupo()
                    Pieza.ConIndices(base, arriba = texto(if (palabra == "dot") "˙" else "¨"))
                }
                "quad" -> texto("  ")
                "qquad" -> texto("    ")
                "begin" -> entorno()
                "end" -> { grupoCrudo(); texto("") }
                "" -> {
                    // `\{`, `\%`...: el carácter tal cual; los espacios de LaTeX (`\,` `\;` `\:`
                    // `\ `), un espacio; `\!` nada; un `\\` suelto, un espacio.
                    val c = en(i) ?: ' '
                    i++
                    when (c) {
                        ',', ';', ':', ' ' -> texto(" ")
                        '!' -> texto("")
                        '\\' -> texto(" ")
                        else -> texto(c.toString())
                    }
                }
                // Ni idea de qué es: se enseña tal cual, con su barra.
                else -> texto("\\$palabra")
            }
        }

        private fun agrupado(abre: Char): Pieza {
            val cierra = if (abre == '(') ')' else ']'
            val desde = i
            i++
            val dentro = expresion(cierra)
            // Sin cierre no hay grupo: se devuelve tal cual para no perder nada.
            return if (en(i) == cierra) {
                i++
                Pieza.Agrupado(dentro, abre.toString(), cierra.toString())
            } else {
                i = desde + 1
                texto(abre.toString())
            }
        }

        /** Lo de dentro de unas llaves, sin leer (`\text{...}`), o el siguiente carácter. */
        private fun grupoCrudo(): String {
            saltarEspacios()
            val c = en(i) ?: return ""
            if (c != '{') { i++; return c.toString() }
            var prof = 0
            val desde = i + 1
            while (i < s.length) {
                val d = s[i]
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

        /**
         * **Un entorno** (`\begin{pmatrix} 1 & 2 \\ 3 & 4 \end{pmatrix}`) a una [Pieza.Tabla].
         * Uno que no se conoce se lee como matriz sin paréntesis.
         */
        private fun entorno(): Pieza {
            val nombre = grupoCrudo()
            val base = nombre.trimEnd('*')
            if (base == "array" || base == "tabular") grupoCrudo()
            val abre = "\\begin{$nombre}"
            val cierra = "\\end{$nombre}"
            val desde = i
            var prof = 1
            var fin = s.length
            var k = i
            while (k < s.length) {
                if (s.startsWith(abre, k)) {
                    prof++
                    k += abre.length
                } else if (s.startsWith(cierra, k)) {
                    prof--
                    if (prof == 0) { fin = k; break }
                    k += cierra.length
                } else k++
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
                if (alineado) listOf(leer(celdas.joinToString(" "))) else celdas.map { leer(it) }
            }
            return Pieza.Tabla(filas, a, c, izquierda)
        }
    }

    /** El cuerpo de un entorno en filas (`\\`) y celdas (`&`), sin mirar dentro de las llaves. */
    private fun partirTabla(cuerpo: String): List<List<String>> {
        val filas = mutableListOf<List<String>>()
        var fila = mutableListOf<String>()
        val celda = StringBuilder()
        var prof = 0
        var i = 0
        while (i < cuerpo.length) {
            val c = cuerpo[i]
            when {
                c == '\\' && prof == 0 && cuerpo.getOrNull(i + 1) == '\\' -> {
                    fila += celda.toString(); celda.clear()
                    filas += fila; fila = mutableListOf()
                    i += 2
                    // `\\[2pt]`: el hueco extra, que no se ve.
                    if (cuerpo.getOrNull(i) == '[') {
                        while (i < cuerpo.length && cuerpo[i] != ']') i++
                        i++
                    }
                    continue
                }
                c == '\\' -> {
                    celda.append('\\')
                    cuerpo.getOrNull(i + 1)?.let { celda.append(it) }
                    i += 2
                    continue
                }
                c == '{' -> prof++
                c == '}' -> prof--
                c == '&' && prof == 0 -> {
                    fila += celda.toString(); celda.clear()
                    i++
                    continue
                }
            }
            celda.append(c)
            i++
        }
        fila += celda.toString()
        filas += fila
        return filas
            .map { f -> f.map { it.replace("\\hline", "").trim() } }
            .filter { f -> f.any { it.isNotEmpty() } }
    }

    /** Junta índices sobre la misma base: `x^2_i` es una sola cosa. */
    private fun conIndice(base: Pieza, indice: Pieza, arriba: Boolean): Pieza {
        val previo = base as? Pieza.ConIndices
        return if (previo != null) {
            if (arriba) previo.copy(arriba = indice) else previo.copy(abajo = indice)
        } else {
            if (arriba) Pieza.ConIndices(base, arriba = indice) else Pieza.ConIndices(base, abajo = indice)
        }
    }

    /** El árbol en texto plano (el de las pruebas): `(a/b)`, `x^2_i`, `sqrt(x)`. */
    fun plano(p: Pieza): String = when (p) {
        is Pieza.Texto -> p.texto
        is Pieza.Fila -> p.partes.joinToString("") { plano(it) }
        is Pieza.Fraccion -> "(${plano(p.arriba)}/${plano(p.abajo)})"
        is Pieza.ConIndices ->
            plano(p.base) + (p.arriba?.let { "^${plano(it)}" } ?: "") + (p.abajo?.let { "_${plano(it)}" } ?: "")
        is Pieza.Raiz -> "sqrt(${plano(p.dentro)})"
        is Pieza.Agrupado -> p.abre + plano(p.dentro) + p.cierra
        is Pieza.Tabla -> p.abre + p.filas.joinToString("; ") { f -> f.joinToString(", ") { plano(it) } } + p.cierra
        is Pieza.Adornada -> plano(p.dentro) + "^" + when (p.adorno) {
            Adorno.RAYA_ENCIMA -> "‾"
            Adorno.ARCO_ENCIMA -> "⌒"
            Adorno.FLECHA -> "→"
            Adorno.GORRO -> "^"
            Adorno.TILDE -> "~"
            Adorno.RAYA_DEBAJO -> "_"
            Adorno.LLAVE_DEBAJO -> ""
        }
        is Pieza.Color -> plano(p.dentro)
        is Pieza.Negrita -> plano(p.dentro)
        is Pieza.Grande -> plano(p.dentro)
        is Pieza.Recuadro -> "[${plano(p.dentro)}]"
        is Pieza.Encima -> plano(p.base) + "^" + plano(p.arriba)
        is Pieza.Debajo -> plano(p.base) + " (" + plano(p.abajo) + ")"
    }

    /** **Los mandatos que el lector no entendió** (salen con su barra). */
    fun desconocidos(p: Pieza): List<String> = when (p) {
        is Pieza.Texto -> if (p.texto.startsWith('\\') && p.texto.length > 1) listOf(p.texto) else emptyList()
        is Pieza.Fila -> p.partes.flatMap { desconocidos(it) }
        is Pieza.Fraccion -> desconocidos(p.arriba) + desconocidos(p.abajo)
        is Pieza.Encima -> desconocidos(p.base) + desconocidos(p.arriba)
        is Pieza.Debajo -> desconocidos(p.base) + desconocidos(p.abajo)
        is Pieza.ConIndices -> desconocidos(p.base) + (p.arriba?.let { desconocidos(it) } ?: emptyList()) +
            (p.abajo?.let { desconocidos(it) } ?: emptyList())
        is Pieza.Raiz -> desconocidos(p.dentro) + (p.indice?.let { desconocidos(it) } ?: emptyList())
        is Pieza.Agrupado -> desconocidos(p.dentro)
        is Pieza.Adornada -> desconocidos(p.dentro)
        is Pieza.Color -> desconocidos(p.dentro)
        is Pieza.Negrita -> desconocidos(p.dentro)
        is Pieza.Recuadro -> desconocidos(p.dentro)
        is Pieza.Grande -> desconocidos(p.dentro)
        is Pieza.Tabla -> p.filas.flatten().flatMap { desconocidos(it) }
    }

    private val ARRIBA = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷',
        '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', 'n' to 'ⁿ', 'i' to 'ⁱ', '(' to '⁽', ')' to '⁾'
    )
    private val ABAJO = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇',
        '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', 'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ',
        'i' to 'ᵢ', 'n' to 'ₙ'
    )

    /** En alto o en bajo con los caracteres de Unicode, o null si alguno no tiene versión. */
    private fun alzado(s: String, arriba: Boolean): String? {
        val tabla = if (arriba) ARRIBA else ABAJO
        val out = StringBuilder()
        for (c in s) out.append(tabla[c] ?: return null)
        return out.toString()
    }

    private fun marcaDe(a: Adorno): String = when (a) {
        Adorno.RAYA_ENCIMA -> "̅"
        Adorno.ARCO_ENCIMA -> "⌒"
        Adorno.FLECHA -> "⃗"
        Adorno.GORRO -> "̂"
        Adorno.TILDE -> "̃"
        Adorno.RAYA_DEBAJO -> "̲"
        Adorno.LLAVE_DEBAJO -> ""
    }

    private fun compuesto(p: Pieza): Boolean {
        val s = legible(p)
        return s.codePointCount(0, s.length) > 1 && s.any { it in "+-−=·×÷ /" }
    }

    private fun entre(p: Pieza): String = if (compuesto(p)) "(${legible(p)})" else legible(p)

    /**
     * **La fórmula escrita para quien no sabe componerla**: el texto de reserva de un texto rico
     * del lienzo ([com.forge.pixpin.motor.TextoRico.plano]). Se lee como en un cuaderno: `x²`,
     * `a₁`, `(2a + 2b)/2`, `√(x)`, `A̅B̅`, `AC⌒`, `45°`. **Tiene que salir idéntico al del PC**
     * (`formulas::legible`): el PC descarta la fuente rica si su plano no coincide con el `text`.
     */
    fun legible(p: Pieza): String = when (p) {
        is Pieza.Texto -> p.texto
        is Pieza.Fila -> p.partes.joinToString("") { legible(it) }
        is Pieza.Fraccion -> "${entre(p.arriba)}/${entre(p.abajo)}"
        is Pieza.ConIndices -> {
            val s = StringBuilder(legible(p.base))
            for ((k, sube) in listOf(p.abajo to false, p.arriba to true)) {
                if (k == null) continue
                val t = legible(k)
                val u = alzado(t, sube)
                if (u != null) s.append(u) else s.append(if (sube) '^' else '_').append('(').append(t).append(')')
            }
            s.toString()
        }
        is Pieza.Raiz -> {
            val ind = p.indice?.let { alzado(legible(it), true) ?: "" } ?: ""
            "$ind√${entre(p.dentro)}"
        }
        is Pieza.Agrupado -> p.abre + legible(p.dentro) + p.cierra
        is Pieza.Tabla -> p.abre + p.filas.joinToString("; ") { f -> f.joinToString(", ") { legible(it) } } + p.cierra
        is Pieza.Adornada -> if (p.adorno == Adorno.ARCO_ENCIMA) "${legible(p.dentro)}⌒" else {
            // Una marca combinante detrás de cada letra: A̅B̅.
            val m = marcaDe(p.adorno)
            val d = legible(p.dentro)
            val out = StringBuilder()
            var k = 0
            while (k < d.length) {
                val cp = d.codePointAt(k)
                out.appendCodePoint(cp).append(m)
                k += Character.charCount(cp)
            }
            out.toString()
        }
        is Pieza.Color -> legible(p.dentro)
        is Pieza.Negrita -> legible(p.dentro)
        is Pieza.Grande -> legible(p.dentro)
        is Pieza.Recuadro -> legible(p.dentro)
        is Pieza.Encima -> legible(p.base) + (alzado(legible(p.arriba), true) ?: "")
        is Pieza.Debajo -> legible(p.base) + " (" + legible(p.abajo) + ")"
    }

    /**
     * **Un color por su nombre** (español o inglés, en masculino, femenino o plural) o en
     * `#rrggbb`/`#rgb`, devuelto como `#rrggbb`. La misma paleta que el PC
     * (`texto_rico::color_de_nombre`).
     */
    fun colorDeNombre(nombre: String): String? {
        val t = nombre.trim().lowercase()
        fun hex(h: String): String? {
            if (h.any { it !in "0123456789abcdef" }) return null
            return when (h.length) {
                3 -> "#" + h.map { "$it$it" }.joinToString("")
                6 -> "#$h"
                8 -> "#" + h.substring(0, 6)
                else -> null
            }
        }
        if (t.startsWith('#')) return hex(t.substring(1))
        val candidatos = mutableListOf(t)
        for (sufijo in listOf("es", "s")) if (t.endsWith(sufijo)) candidatos += t.dropLast(sufijo.length)
        for (c in candidatos.toList()) if (c.endsWith('a')) candidatos += c.dropLast(1) + "o"
        for (c in candidatos) {
            val h = when (c) {
                "negro", "black" -> "1e1e1e"
                "blanco", "white" -> "ffffff"
                "rojo", "red" -> "e03131"
                "verde", "green" -> "2f9e44"
                "azul", "blue" -> "1971c2"
                "amarillo", "yellow" -> "f08c00"
                "naranja", "orange" -> "e8590c"
                "morado", "violeta", "purple", "violet" -> "6741d9"
                "rosa", "pink", "magenta" -> "c2255c"
                "gris", "gray", "grey" -> "868e96"
                "marron", "marrón", "cafe", "café", "brown" -> "8b5a2b"
                "celeste", "cyan", "cian", "teal" -> "0c8599"
                else -> null
            }
            if (h != null) return "#$h"
        }
        return null
    }
}
