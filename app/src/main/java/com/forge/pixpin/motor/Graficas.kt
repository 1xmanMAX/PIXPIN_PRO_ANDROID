package com.forge.pixpin.motor

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow

/**
 * **La gráfica de una función, escrita con su fórmula.**
 *
 * Se teclea `sin(x)/x`, se dan los límites —de dónde a dónde va la `x` y qué ventana de
 * `y` se enseña— y sale dibujada: la curva, los dos ejes con sus marcas y su rótulo, todo
 * como elementos normales del lienzo, agrupados. Es dibujo, no un cuadro incrustado: se
 * mueve, se escala, se le cambia el color y sale en el PDF, en el SVG y en la página web
 * como cualquier otra cosa.
 *
 * **Varias curvas en la misma gráfica**: cada fórmula de la lista es una curva con su
 * color, sobre los mismos ejes. Y **funciones por partes**: dentro de una fórmula, las
 * partes van separadas por punto y coma y cada una lleva su condición —`x^2 si x < 0;
 * 2x si x >= 0`—; donde ninguna se cumple no se dibuja nada. Ver [Formula].
 *
 * ## La curva
 *
 * Se muestrea la `x` a paso fijo y se **corta la polilínea** donde la función se sale de
 * la ventana o no existe: así `tan(x)` sale como sus ramas y no como una raya vertical que
 * las une, y `1/x` deja el hueco en el cero. Cada rama es una línea del lienzo con la
 * rugosidad a cero: una gráfica se quiere limpia, no a mano alzada.
 *
 * Sin Android: medir las letras llega por [MedidaDeTexto].
 */
object Graficas {

    /** Lo que se pide para una gráfica. Los límites son de la ventana que se enseña. */
    data class Peticion(
        /** Una fórmula por curva, en función de `x`. */
        val formulas: List<String>,
        val xDesde: Double = -5.0,
        val xHasta: Double = 5.0,
        val yDesde: Double = -5.0,
        val yHasta: Double = 5.0,
        /** Cuántos píxeles de escena mide una unidad. */
        val escala: Double = 40.0
    ) {
        constructor(
            formula: String,
            xDesde: Double = -5.0,
            xHasta: Double = 5.0,
            yDesde: Double = -5.0,
            yHasta: Double = 5.0,
            escala: Double = 40.0
        ) : this(listOf(formula), xDesde, xHasta, yDesde, yHasta, escala)

        val formula: String get() = formulas.firstOrNull().orEmpty()
    }

    /** Cuántas muestras de `x` entre los dos límites. */
    const val MUESTRAS = 400

    /**
     * Los colores de las curvas a partir de la segunda: la primera va con el pincel que
     * haya puesto, y las demás con estos, vivos y distintos entre sí.
     */
    val COLORES_DE_CURVAS = listOf("#e03131", "#1971c2", "#2f9e44", "#f08c00", "#9c36b5", "#0c8599")

    /**
     * Los elementos de la gráfica, con su esquina en el (0, 0), o null si ninguna fórmula
     * se entiende o los límites no tienen sentido.
     */
    fun elementos(p: Peticion, estilo: ItemStyle, medir: MedidaDeTexto): List<Element>? {
        val funciones = p.formulas.map { it.trim() }.filter { it.isNotEmpty() }
            .map { Formula.compilar(it) ?: return null }
        if (funciones.isEmpty()) return null
        if (funciones.any { !it.variables.all { v -> v == "x" } }) return null
        if (!(p.xHasta > p.xDesde) || !(p.yHasta > p.yDesde) || !(p.escala > 0.0)) return null
        val ancho = (p.xHasta - p.xDesde) * p.escala
        val alto = (p.yHasta - p.yDesde) * p.escala
        if (!ancho.isFinite() || !alto.isFinite() || ancho > 100_000 || alto > 100_000) return null

        fun px(x: Double) = (x - p.xDesde) * p.escala
        fun py(y: Double) = (p.yHasta - y) * p.escala

        val salida = ArrayList<Element>()
        // Los ejes, finos y sin rugosidad: son referencia, no dibujo.
        val deEje = estilo.copy(roughness = 0, strokeWidth = 1.0)
        val deLetra = estilo.copy(fontSize = (estilo.fontSize * 0.7).coerceAtLeast(8.0))

        val hayEjeX = p.yDesde <= 0.0 && 0.0 <= p.yHasta
        val hayEjeY = p.xDesde <= 0.0 && 0.0 <= p.xHasta
        val yDelEjeX = if (hayEjeX) py(0.0) else py(p.yDesde)
        val xDelEjeY = if (hayEjeY) px(0.0) else px(p.xDesde)

        salida += flecha(Pt(0.0, yDelEjeX), Pt(ancho, yDelEjeX), deEje)
        salida += flecha(Pt(xDelEjeY, alto), Pt(xDelEjeY, 0.0), deEje)
        // El nombre de cada eje, en cursiva como las variables de la ecuación,
        // pegado a la punta: la x a la derecha de la suya, la y al lado de la
        // suya, donde no chocan con los números de las marcas (que en el eje y
        // van a la izquierda y en el eje x cuelgan por debajo).
        val deVariable = deLetra.copy(fontSize = deLetra.fontSize * 1.15, cursiva = true)
        salida += rotulo("x", Pt(ancho + 4.0, yDelEjeX), deVariable, medir, aLaDerecha = true)
        salida += rotulo("y", Pt(xDelEjeY + 5.0, 0.0), deVariable, medir, arriba = true, aLaDerecha = true)

        // Las marcas, a un paso «bonito» para que no salgan ni dos ni doscientas.
        val pasoX = pasoBonito(p.xHasta - p.xDesde)
        val pasoY = pasoBonito(p.yHasta - p.yDesde)
        val marca = 4.0
        var x = ceil(p.xDesde / pasoX) * pasoX
        while (x <= p.xHasta + 1e-9) {
            if (abs(x) > 1e-9 || !hayEjeY) {
                salida += raya(Pt(px(x), yDelEjeX - marca), Pt(px(x), yDelEjeX + marca), deEje)
                salida += rotulo(numero(x), Pt(px(x), yDelEjeX + marca + 2), deLetra, medir, arriba = true)
            }
            x += pasoX
        }
        var y = ceil(p.yDesde / pasoY) * pasoY
        while (y <= p.yHasta + 1e-9) {
            if (abs(y) > 1e-9 || !hayEjeX) {
                salida += raya(Pt(xDelEjeY - marca, py(y)), Pt(xDelEjeY + marca, py(y)), deEje)
                salida += rotulo(numero(y), Pt(xDelEjeY - marca - 2, py(y)), deLetra, medir, aLaIzquierda = true)
            }
            y += pasoY
        }

        // Las curvas, por ramas, cada una con su color.
        var alturaDelRotulo = 6.0
        for ((k, f) in funciones.withIndex()) {
            val color = if (k == 0) estilo.strokeColor
            else COLORES_DE_CURVAS[(k - 1) % COLORES_DE_CURVAS.size]
            val deCurva = estilo.copy(roughness = 0, strokeColor = color)
            var rama = ArrayList<Pt>()
            fun cerrarRama() {
                if (rama.size >= 2) salida += polilinea(rama, deCurva)
                rama = ArrayList()
            }
            for (i in 0..MUESTRAS) {
                val xv = p.xDesde + (p.xHasta - p.xDesde) * i / MUESTRAS
                val yv = f.en(xv)
                if (!yv.isFinite() || yv < p.yDesde || yv > p.yHasta) {
                    cerrarRama()
                    continue
                }
                rama += Pt(px(xv), py(yv))
            }
            cerrarRama()

            // El rótulo de cada curva, **como ecuación** —raíz, fracción, exponente—, del
            // color de la curva, apilados arriba a la izquierda. Ver [Ecuacion].
            val letra = estilo.copy(fontSize = deLetra.fontSize * 1.15, strokeColor = color)
            val caja = Ecuacion.cajaDeLaEcuacion("y", f, letra, medir)
            salida += caja.pintar(6.0, alturaDelRotulo)
            alturaDelRotulo += caja.alto + 4.0
        }
        return salida
    }

    /** Un paso de marca redondo (1, 2, 5, 10…) que deje entre cinco y diez marcas. */
    internal fun pasoBonito(rango: Double): Double {
        if (rango <= 0.0 || !rango.isFinite()) return 1.0
        val crudo = rango / 8.0
        val potencia = 10.0.pow(floor(log10(crudo)))
        val mantisa = crudo / potencia
        val redonda = when {
            mantisa < 1.5 -> 1.0
            mantisa < 3.5 -> 2.0
            mantisa < 7.5 -> 5.0
            else -> 10.0
        }
        return redonda * potencia
    }

    private fun numero(v: Double): String {
        val r = if (abs(v) < 1e-9) 0.0 else v
        return if (r == floor(r) && abs(r) < 1e9) r.toLong().toString()
        else String.format(Locale.ROOT, "%.3f", r).trimEnd('0').trimEnd('.')
    }

    private fun polilinea(puntos: List<Pt>, estilo: ItemStyle): Element {
        val x0 = puntos.first().x
        val y0 = puntos.first().y
        val rel = puntos.map { Pt(it.x - x0, it.y - y0) }
        val w = rel.maxOf { it.x } - rel.minOf { it.x }
        val h = rel.maxOf { it.y } - rel.minOf { it.y }
        return newElement(ElementType.LINE, x0, y0, estilo, width = w, height = h)
            .copy(points = rel, roundness = null)
    }

    private fun raya(a: Pt, b: Pt, estilo: ItemStyle): Element = polilinea(listOf(a, b), estilo)

    /** Largo de la punta de los ejes, en px de escena. */
    const val PUNTA = 8.0

    /** Apertura de cada ala de la punta, en grados. */
    private const val ANGULO_DE_LA_PUNTA = 22.0

    /**
     * Un eje: la raya de [a] a [b] y una punta pequeña en [b]. No es un
     * elemento ARROW porque su punta mide 25 px fijos —lo que pide una flecha
     * de diagrama— y en un eje de trazo fino se comía las marcas y los
     * números; aquí la punta es una uve de [PUNTA] px, dibujada aparte.
     */
    private fun flecha(a: Pt, b: Pt, estilo: ItemStyle): List<Element> {
        val largo = hypot(b.x - a.x, b.y - a.y)
        if (largo == 0.0) return emptyList()
        val nx = (b.x - a.x) / largo
        val ny = (b.y - a.y) / largo
        val base = Pt(b.x - nx * PUNTA, b.y - ny * PUNTA)
        val rad = ANGULO_DE_LA_PUNTA * PI / 180
        val ala1 = pointRotateRads(base, b, -rad)
        val ala2 = pointRotateRads(base, b, rad)
        return listOf(raya(a, b, estilo), polilinea(listOf(ala1, b, ala2), estilo))
    }

    /** Una letra colocada respecto de un punto: encima o debajo, a un lado u otro. */
    private fun rotulo(
        texto: String,
        donde: Pt,
        estilo: ItemStyle,
        medir: MedidaDeTexto,
        arriba: Boolean = false,
        aLaIzquierda: Boolean = false,
        aLaDerecha: Boolean = false
    ): Element {
        val (ancho, alto) = medir(texto, estilo.fontSize)
        val x = when {
            aLaIzquierda -> donde.x - ancho
            aLaDerecha -> donde.x
            else -> donde.x - ancho / 2
        }
        val y = if (arriba) donde.y else donde.y - alto / 2
        return newElement(ElementType.TEXT, x, y, estilo, width = ancho, height = alto)
            .copy(text = texto, cursiva = estilo.cursiva)
    }
}

/**
 * **El intérprete de fórmulas**: de un texto como `2x^2 - 3sin(x)` a un árbol que se evalúa
 * y que **se escribe bonito** (en el LaTeX de bolsillo de las notas) para enseñarlo como
 * una ecuación de verdad mientras se teclea.
 *
 * Descenso recursivo de manual, con la precedencia de siempre (potencia, luego producto,
 * luego suma, luego comparación) y la potencia asociando a la derecha. La multiplicación
 * implícita se decide al leer: un número seguido de una letra o de un paréntesis, un
 * paréntesis cerrado seguido de otro abierto, una `x` seguida de una función… multiplican.
 *
 * Variables: `x`, `y` y `t`. Una gráfica plana solo admite `x`; una superficie, `x` e `y`;
 * una curva en el espacio, `t`. Quien compila mira [Compilada.variables] y decide.
 *
 * Condiciones: `<`, `<=`, `>`, `>=`, `=`, `!=` dan uno o cero, y `si(cond, a, b)` elige.
 * **Por partes**: `x^2 si x < 0; 2x si x >= 0` — las partes van separadas por punto y
 * coma, cada una con su condición detrás de un `si`; donde ninguna se cumple, no hay valor.
 *
 * La coma decimal se admite (`0,5`) cuando va entre dígitos; el separador de argumentos de
 * `max(a; b)` es el punto y coma dentro de paréntesis o la coma.
 */
object Formula {

    /** Las variables de una evaluación. Se reutiliza: se evalúa miles de veces por gráfica. */
    class Vars(var x: Double = 0.0, var y: Double = 0.0, var t: Double = 0.0)

    /** El árbol de la fórmula. */
    sealed class Nodo {
        abstract fun en(v: Vars): Double
        abstract fun latex(): String
        abstract val variables: Set<String>

        class Numero(val valor: Double, val texto: String) : Nodo() {
            override fun en(v: Vars) = valor
            override fun latex() = texto
            override val variables: Set<String> = emptySet()
        }

        class Variable(val nombre: String) : Nodo() {
            override fun en(v: Vars) = when (nombre) { "x" -> v.x; "y" -> v.y; else -> v.t }
            override fun latex() = nombre
            override val variables: Set<String> = setOf(nombre)
        }

        class Constante(val nombre: String, val valor: Double) : Nodo() {
            override fun en(v: Vars) = valor
            override fun latex() = if (nombre == "pi") "\\pi" else nombre
            override val variables: Set<String> = emptySet()
        }

        class Binaria(val op: Char, val a: Nodo, val b: Nodo, val implicita: Boolean = false) : Nodo() {
            override fun en(v: Vars): Double {
                val x = a.en(v)
                val y = b.en(v)
                return when (op) {
                    '+' -> x + y
                    '-' -> x - y
                    '*' -> x * y
                    '/' -> x / y
                    '%' -> x % y
                    '^' -> x.pow(y)
                    '<' -> if (x < y) 1.0 else 0.0
                    '>' -> if (x > y) 1.0 else 0.0
                    'l' -> if (x <= y) 1.0 else 0.0
                    'g' -> if (x >= y) 1.0 else 0.0
                    '=' -> if (abs(x - y) <= 1e-9 * maxOf(1.0, abs(x), abs(y))) 1.0 else 0.0
                    'n' -> if (abs(x - y) > 1e-9 * maxOf(1.0, abs(x), abs(y))) 1.0 else 0.0
                    else -> Double.NaN
                }
            }

            override fun latex(): String = when (op) {
                '+' -> "${a.latex()} + ${b.latex()}"
                '-' -> "${a.latex()} - ${entreParentesisSiSuma(b)}"
                '*' -> if (implicita) {
                    val izq = a.latex()
                    val der = b.latex()
                    // «\pi» seguido de «x» se pegaría en «\pix», que ya es otro mandato.
                    if (izq.last().isLetter() && der.first().isLetter()) "$izq $der" else "$izq$der"
                } else "${a.latex()} \\cdot ${b.latex()}"
                '/' -> "\\frac{${a.latex()}}{${b.latex()}}"
                '%' -> "${a.latex()} \\bmod ${b.latex()}"
                '^' -> "${entreParentesisSiCompuesto(a)}^{${b.latex()}}"
                '<' -> "${a.latex()} < ${b.latex()}"
                '>' -> "${a.latex()} > ${b.latex()}"
                'l' -> "${a.latex()} \\le ${b.latex()}"
                'g' -> "${a.latex()} \\ge ${b.latex()}"
                '=' -> "${a.latex()} = ${b.latex()}"
                else -> "${a.latex()} \\ne ${b.latex()}"
            }

            override val variables: Set<String> = a.variables + b.variables
        }

        class Negado(val a: Nodo) : Nodo() {
            override fun en(v: Vars) = -a.en(v)
            override fun latex() = "-${entreParentesisSiSuma(a)}"
            override val variables: Set<String> = a.variables
        }

        class Grupo(val a: Nodo) : Nodo() {
            override fun en(v: Vars) = a.en(v)
            override fun latex() = "(${a.latex()})"
            override val variables: Set<String> = a.variables
        }

        class Funcion(val nombre: String, val args: List<Nodo>, val f: (List<Double>) -> Double) : Nodo() {
            override fun en(v: Vars) = f(args.map { it.en(v) })
            override fun latex(): String = when (nombre) {
                "sqrt" -> "\\sqrt{${args[0].latex()}}"
                "cbrt" -> "\\sqrt[3]{${args[0].latex()}}"
                "abs" -> "|${args[0].latex()}|"
                "exp" -> "e^{${args[0].latex()}}"
                "sin", "cos", "tan", "ln", "log", "sinh", "cosh", "tanh", "max", "min" ->
                    "\\$nombre(${args.joinToString(", ") { it.latex() }})"
                "asin", "acos", "atan" -> "\\arc${nombre.drop(1)}(${args[0].latex()})"
                "log2" -> "\\log_{2}(${args[0].latex()})"
                "pow" -> "${entreParentesisSiCompuesto(args[0])}^{${args[1].latex()}}"
                "si" -> "\\{${args[1].latex()}\\ si\\ ${args[0].latex()};\\ ${args[2].latex()}\\}"
                else -> "$nombre(${args.joinToString(", ") { it.latex() }})"
            }

            override val variables: Set<String> = args.flatMap { it.variables }.toSet()
        }

        protected fun entreParentesisSiSuma(n: Nodo): String =
            if (n is Binaria && (n.op == '+' || n.op == '-')) "(${n.latex()})" else n.latex()

        protected fun entreParentesisSiCompuesto(n: Nodo): String =
            if (n is Binaria || n is Negado) "(${n.latex()})" else n.latex()
    }

    /** Una parte de una función por partes: la expresión y, si la tiene, su condición. */
    class Parte(val expresion: Nodo, val condicion: Nodo?)

    /** La fórmula compilada. [texto] es como se escribió, limpio, para el rótulo. */
    class Compilada internal constructor(val texto: String, val partes: List<Parte>) {

        /** Qué variables usa: `x`, `y`, `t`. */
        val variables: Set<String> =
            partes.flatMap { it.expresion.variables + (it.condicion?.variables ?: emptySet()) }.toSet()

        /** Cada parte, en el LaTeX de bolsillo de las notas: la expresión y su condición. */
        val latex: List<Pair<String, String?>> =
            partes.map { it.expresion.latex() to it.condicion?.latex() }

        private val vars = Vars()

        /** El valor con estas variables, o NaN si ninguna parte se cumple. No es reentrante. */
        fun evaluar(x: Double, y: Double = 0.0, t: Double = 0.0): Double {
            vars.x = x; vars.y = y; vars.t = t
            for (p in partes) {
                val c = p.condicion
                if (c == null || c.en(vars) != 0.0) {
                    return runCatching { p.expresion.en(vars) }.getOrDefault(Double.NaN)
                }
            }
            return Double.NaN
        }

        fun en(x: Double): Double = evaluar(x)
        fun en(x: Double, y: Double): Double = evaluar(x, y)
        fun enT(t: Double): Double = evaluar(0.0, 0.0, t)
    }

    /** Compila, o null si no se entiende. */
    fun compilar(fuente: String): Compilada? {
        val texto = limpiar(fuente)
        if (texto.isEmpty()) return null
        return runCatching {
            val partes = partesDe(texto).map { trozo ->
                val (expr, cond) = separarCondicion(trozo)
                val lector = Lector(fichas(expr))
                val e = lector.expresion()
                if (!lector.seAcabo()) return null
                val c = cond?.let {
                    val l = Lector(fichas(it))
                    val n = l.expresion()
                    if (!l.seAcabo()) return null
                    n
                }
                Parte(e, c)
            }
            if (partes.isEmpty()) return null
            Compilada(texto, partes)
        }.getOrNull()
    }

    private fun limpiar(fuente: String): String {
        var t = fuente.trim()
        // «y =», «z =», «f(x) =», «f(x, y) =»… delante: es decoración.
        t = t.replace(Regex("""^\s*[a-zA-Z]\s*(\([^)]*\))?\s*=\s*"""), "")
        // La coma decimal entre dígitos.
        t = t.replace(Regex("""(?<=\d),(?=\d)"""), ".")
        return t.trim()
    }

    /** Las partes: separadas por punto y coma **fuera de paréntesis**. */
    private fun partesDe(texto: String): List<String> {
        val salida = ArrayList<String>()
        var nivel = 0
        var desde = 0
        for ((i, c) in texto.withIndex()) {
            when (c) {
                '(', '[', '{' -> nivel++
                ')', ']', '}' -> nivel--
                ';' -> if (nivel == 0) { salida += texto.substring(desde, i); desde = i + 1 }
            }
        }
        salida += texto.substring(desde)
        return salida.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** `expresión si condición`, o `expresión` a secas. También «para» y «cuando». */
    private fun separarCondicion(trozo: String): Pair<String, String?> {
        val m = Regex("""^(.*?)\s+(si|para|cuando|if)\s+(.+)$""").find(trozo) ?: return trozo to null
        return m.groupValues[1].trim() to m.groupValues[3].trim()
    }

    private sealed class Ficha {
        data class Numero(val v: Double, val texto: String) : Ficha()
        data class Nombre(val n: String) : Ficha()
        data class Signo(val c: Char) : Ficha()
    }

    private fun fichas(texto: String): List<Ficha> {
        val salida = ArrayList<Ficha>()
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < texto.length && texto[i + 1].isDigit()) -> {
                    val inicio = i
                    while (i < texto.length && (texto[i].isDigit() || texto[i] == '.')) i++
                    val t = texto.substring(inicio, i)
                    salida += Ficha.Numero(t.toDouble(), t)
                }
                c.isLetter() || c == 'π' -> {
                    val inicio = i
                    while (i < texto.length && (texto[i].isLetterOrDigit() || texto[i] == '_' || texto[i] == 'π')) i++
                    salida += Ficha.Nombre(texto.substring(inicio, i).lowercase(Locale.ROOT))
                }
                c == '²' -> { salida += Ficha.Signo('^'); salida += Ficha.Numero(2.0, "2"); i++ }
                c == '³' -> { salida += Ficha.Signo('^'); salida += Ficha.Numero(3.0, "3"); i++ }
                c == '√' -> { salida += Ficha.Nombre("sqrt"); i++ }
                c == '×' || c == '·' -> { salida += Ficha.Signo('*'); i++ }
                c == '÷' -> { salida += Ficha.Signo('/'); i++ }
                c == '−' || c == '–' -> { salida += Ficha.Signo('-'); i++ }
                c == ';' -> { salida += Ficha.Signo(','); i++ }
                c == '≤' -> { salida += Ficha.Signo('l'); i++ }
                c == '≥' -> { salida += Ficha.Signo('g'); i++ }
                c == '≠' -> { salida += Ficha.Signo('n'); i++ }
                c == '<' -> if (texto.getOrNull(i + 1) == '=') { salida += Ficha.Signo('l'); i += 2 } else { salida += Ficha.Signo('<'); i++ }
                c == '>' -> if (texto.getOrNull(i + 1) == '=') { salida += Ficha.Signo('g'); i += 2 } else { salida += Ficha.Signo('>'); i++ }
                c == '=' -> { salida += Ficha.Signo('='); i += if (texto.getOrNull(i + 1) == '=') 2 else 1 }
                c == '!' && texto.getOrNull(i + 1) == '=' -> { salida += Ficha.Signo('n'); i += 2 }
                c in "+-*/^(),%|" -> { salida += Ficha.Signo(c); i++ }
                else -> throw IllegalArgumentException("carácter raro: $c")
            }
        }
        return salida
    }

    private class Lector(private val fichas: List<Ficha>) {
        private var pos = 0
        private fun mira(): Ficha? = fichas.getOrNull(pos)
        private fun toma(): Ficha = fichas[pos++]
        private fun esSigno(c: Char) = (mira() as? Ficha.Signo)?.c == c
        fun seAcabo() = pos >= fichas.size

        /** Comparaciones: el nivel más suelto. */
        fun expresion(): Nodo {
            var izq = suma()
            while (true) {
                val f = mira() as? Ficha.Signo ?: return izq
                if (f.c !in "<>lg=n") return izq
                toma()
                izq = Nodo.Binaria(f.c, izq, suma())
            }
        }

        private fun suma(): Nodo {
            var izq = termino()
            while (esSigno('+') || esSigno('-')) {
                val c = (toma() as Ficha.Signo).c
                izq = Nodo.Binaria(c, izq, termino())
            }
            return izq
        }

        private fun termino(): Nodo {
            var izq = unario()
            while (true) {
                val f = mira() ?: return izq
                val explicito = f is Ficha.Signo && (f.c == '*' || f.c == '/' || f.c == '%')
                // Multiplicación implícita: lo que puede empezar un factor, sin signo en
                // medio. El «|» no: cerraría un valor absoluto abierto en vez de abrir otro.
                val implicito = f is Ficha.Numero || f is Ficha.Nombre ||
                    (f is Ficha.Signo && f.c == '(')
                if (!explicito && !implicito) return izq
                val c = if (explicito) (toma() as Ficha.Signo).c else '*'
                val der = if (!explicito) potencia() else unario()
                izq = Nodo.Binaria(c, izq, der, implicita = !explicito)
            }
        }

        private fun unario(): Nodo {
            if (esSigno('-')) { toma(); return Nodo.Negado(unario()) }
            if (esSigno('+')) { toma(); return unario() }
            return potencia()
        }

        private fun potencia(): Nodo {
            val base = atomo()
            if (esSigno('^')) {
                toma()
                return Nodo.Binaria('^', base, unario())
            }
            return base
        }

        private fun atomo(): Nodo {
            val f = toma()
            return when (f) {
                is Ficha.Numero -> Nodo.Numero(f.v, f.texto)
                is Ficha.Signo -> when (f.c) {
                    '(' -> { val e = expresion(); espera(')'); Nodo.Grupo(e) }
                    '|' -> { val e = expresion(); espera('|'); Nodo.Funcion("abs", listOf(e)) { abs(it[0]) } }
                    else -> throw IllegalArgumentException("signo suelto: ${f.c}")
                }
                is Ficha.Nombre -> nombre(f.n)
            }
        }

        private fun espera(c: Char) {
            if (!esSigno(c)) throw IllegalArgumentException("falta $c")
            toma()
        }

        private fun argumentos(): List<Nodo> {
            espera('(')
            val lista = ArrayList<Nodo>()
            if (!esSigno(')')) {
                lista += expresion()
                while (esSigno(',')) { toma(); lista += expresion() }
            }
            espera(')')
            return lista
        }

        private fun nombre(n: String): Nodo {
            when (n) {
                "x", "y", "t" -> return Nodo.Variable(n)
                "pi", "π" -> return Nodo.Constante("pi", Math.PI)
                "e" -> return Nodo.Constante("e", Math.E)
            }
            val fn1: ((Double) -> Double)? = when (n) {
                "sin", "sen" -> Math::sin
                "cos" -> Math::cos
                "tan", "tg" -> Math::tan
                "asin", "arcsin", "asen" -> Math::asin
                "acos", "arccos" -> Math::acos
                "atan", "arctan", "atg" -> Math::atan
                "sinh", "senh" -> Math::sinh
                "cosh" -> Math::cosh
                "tanh" -> Math::tanh
                "sqrt", "raiz", "raíz" -> Math::sqrt
                "cbrt" -> Math::cbrt
                "abs" -> { v: Double -> abs(v) }
                "exp" -> Math::exp
                "ln" -> Math::log
                "log", "log10" -> Math::log10
                "log2" -> { v: Double -> Math.log(v) / Math.log(2.0) }
                "floor", "suelo" -> Math::floor
                "ceil", "techo" -> Math::ceil
                "round", "redondea" -> { v: Double -> Math.rint(v) }
                "sign", "signo" -> { v: Double -> Math.signum(v) }
                else -> null
            }
            if (fn1 != null) {
                val args = argumentosOImplicito()
                if (args.size != 1) throw IllegalArgumentException("$n pide un argumento")
                val canonico = when (n) {
                    "sen" -> "sin"; "tg" -> "tan"; "arcsin", "asen" -> "asin"; "arccos" -> "acos"
                    "arctan", "atg" -> "atan"; "senh" -> "sinh"; "raiz", "raíz" -> "sqrt"
                    "log10" -> "log"; "suelo" -> "floor"; "techo" -> "ceil"; "redondea" -> "round"
                    "signo" -> "sign"; else -> n
                }
                return Nodo.Funcion(canonico, args) { fn1(it[0]) }
            }
            val fn2: ((Double, Double) -> Double)? = when (n) {
                "max" -> { a: Double, b: Double -> Math.max(a, b) }
                "min" -> { a: Double, b: Double -> Math.min(a, b) }
                "pow" -> Math::pow
                "atan2" -> Math::atan2
                "mod" -> { a: Double, b: Double -> a % b }
                else -> null
            }
            if (fn2 != null) {
                val args = argumentos()
                if (args.size != 2) throw IllegalArgumentException("$n pide dos argumentos")
                return Nodo.Funcion(n, args) { fn2(it[0], it[1]) }
            }
            if (n == "si" || n == "if") {
                val args = argumentos()
                if (args.size != 3) throw IllegalArgumentException("si pide tres argumentos")
                return Nodo.Funcion("si", args) { if (it[0] != 0.0) it[1] else it[2] }
            }
            throw IllegalArgumentException("no sé qué es $n")
        }

        /** `sin(x)` de siempre, o `sin x` / `sin 2x` sin paréntesis, como en la pizarra. */
        private fun argumentosOImplicito(): List<Nodo> {
            if (esSigno('(')) return argumentos()
            return listOf(unario())
        }
    }
}
