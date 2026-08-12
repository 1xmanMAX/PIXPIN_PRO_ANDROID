package com.forge.pixpin.markdown

/**
 * Un trozo de fórmula ya entendido, listo para componer.
 *
 * Es un árbol, no una lista, porque una fórmula lo es: el numerador de una
 * fracción puede llevar dentro otra fracción con un exponente que a su vez lleva
 * una raíz. Pintar eso desde una cadena de texto es imposible; desde un árbol es
 * bajar por él.
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

    fun leer(latex: String): Pieza = Lector(latex).fila(null)

    /**
     * Los símbolos que se escriben con barra invertida.
     *
     * Solo los que aparecen de verdad en unas notas: las letras griegas, los
     * operadores y las flechas corrientes. Añadir más es una línea; adivinar
     * cuáles hacen falta antes de que alguien los use, no.
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
        "ldots" to "…", "cdots" to "⋯", "perp" to "⊥", "parallel" to "∥"
    )

    /** Las palabras que van derechas y no en cursiva: `\sin`, `\log`… */
    private val FUNCIONES = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "log", "ln", "exp", "lim", "max", "min", "det", "gcd", "mod"
    )

    private class Lector(private val s: String) {
        private var i = 0

        /**
         * Lee piezas seguidas hasta el final o hasta [fin], el carácter que
         * cierra lo que se estaba leyendo.
         *
         * El cierre tiene que ser un parámetro y no una llave fija: la raíz
         * acaba en corchete y el paréntesis en paréntesis. Leyendo siempre hasta
         * el final, `\sqrt[3]{x}` se tragaba la raíz entera dentro del índice y
         * `(a+b)^2` se comía el exponente dentro del paréntesis.
         *
         * Los índices —lo de arriba y lo de abajo— se pegan **al trozo anterior**
         * según se leen, porque en `x^2` el 2 no es un trozo más: es parte de la
         * x. Sin eso quedaría suelto y se pintaría a su lado, del mismo tamaño.
         */
        fun fila(fin: Char?): Pieza {
            val partes = mutableListOf<Pieza>()
            while (i < s.length) {
                val c = s[i]
                if (fin != null && c == fin) break

                when {
                    c == '^' || c == '_' -> {
                        i++
                        val indice = grupo()
                        val base = partes.removeLastOrNull() ?: Pieza.Texto("")
                        partes += conIndice(base, indice, arriba = c == '^')
                    }
                    c == '\\' -> partes += mandato()
                    c == '{' -> { i++; partes += fila('}'); if (i < s.length) i++ }
                    c == '(' || c == '[' -> partes += agrupado(c)
                    c.isWhitespace() -> { i++; if (partes.isNotEmpty()) partes += Pieza.Texto(" ") }
                    else -> partes += sueltos()
                }
            }
            return if (partes.size == 1) partes[0] else Pieza.Fila(partes)
        }

        /** Junta índices sobre la misma base: `x^2_i` es una sola cosa. */
        private fun conIndice(base: Pieza, indice: Pieza, arriba: Boolean): Pieza {
            val previo = base as? Pieza.ConIndices
            return if (previo != null) {
                if (arriba) previo.copy(arriba = indice) else previo.copy(abajo = indice)
            } else {
                if (arriba) {
                    Pieza.ConIndices(base, arriba = indice)
                } else {
                    Pieza.ConIndices(base, abajo = indice)
                }
            }
        }

        /** Un grupo entre llaves, o el siguiente carácter suelto si no las hay. */
        private fun grupo(): Pieza {
            if (i >= s.length) return Pieza.Texto("")
            if (s[i] == '{') {
                i++
                val dentro = fila('}')
                if (i < s.length) i++
                return dentro
            }
            if (s[i] == '\\') return mandato()
            val c = s[i]
            i++
            return Pieza.Texto(c.toString(), cursiva = c.isLetter())
        }

        private fun mandato(): Pieza {
            i++ // la barra
            val nombre = StringBuilder()
            while (i < s.length && s[i].isLetter()) { nombre.append(s[i]); i++ }
            val palabra = nombre.toString()

            return when {
                palabra == "frac" || palabra == "dfrac" || palabra == "tfrac" ->
                    Pieza.Fraccion(grupo(), grupo())

                palabra == "sqrt" -> {
                    var indice: Pieza? = null
                    if (i < s.length && s[i] == '[') {
                        i++
                        indice = fila(']')
                        if (i < s.length) i++
                    }
                    Pieza.Raiz(grupo(), indice)
                }

                palabra == "left" || palabra == "right" -> Pieza.Texto("")
                palabra in FUNCIONES -> Pieza.Texto(palabra)
                SIMBOLOS.containsKey(palabra) -> Pieza.Texto(SIMBOLOS.getValue(palabra))

                // Ni idea de qué es: se enseña tal cual, con su barra. Antes un
                // `\loquesea` a la vista que una fórmula a la que le falta algo.
                palabra.isEmpty() -> {
                    val c = if (i < s.length) s[i].toString() else ""
                    i++
                    Pieza.Texto(c)
                }
                else -> Pieza.Texto("\\$palabra")
            }
        }

        private fun agrupado(abre: Char): Pieza {
            val cierra = if (abre == '(') ')' else ']'
            val desde = i
            i++
            val dentro = fila(cierra)
            // Sin cierre no hay grupo: se devuelve tal cual para no perder nada.
            return if (i < s.length && s[i] == cierra) {
                i++
                Pieza.Agrupado(dentro, abre.toString(), cierra.toString())
            } else {
                i = desde + 1
                Pieza.Texto(abre.toString())
            }
        }

        /**
         * Letras y números corrientes.
         *
         * Las letras salen en cursiva y los números derechos, que es como se
         * escriben las matemáticas de toda la vida: la `x` de una variable se
         * distingue de la equis de multiplicar.
         */
        private fun sueltos(): Pieza {
            val c = s[i]
            if (c.isDigit()) {
                val n = StringBuilder()
                while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) {
                    n.append(s[i]); i++
                }
                return Pieza.Texto(n.toString())
            }
            i++
            return Pieza.Texto(c.toString(), cursiva = c.isLetter())
        }
    }
}
