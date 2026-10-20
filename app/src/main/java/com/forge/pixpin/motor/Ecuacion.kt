package com.forge.pixpin.motor

import com.forge.pixpin.motormd.Adorno
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import com.forge.pixpin.motormd.Pieza as F

/**
 * **La ecuación tipografiada dentro del dibujo**: raíces con su signo y su raya,
 * fracciones con su barra, exponentes en alto, paréntesis, valor absoluto y las partes con
 * su llave. Nada de `sqrt(2x)` escrito a máquina: lo que sale en la gráfica es la ecuación
 * como se escribe en la pizarra.
 *
 * Todo son **textos y líneas del lienzo** —vectoriales— y no una imagen: se mueven con la
 * gráfica, se les cambia el color, y salen igual en el PDF, en el SVG y en la página web.
 * La raya de la fracción, el signo de la raíz y la llave se dibujan con líneas, que es lo
 * que permite que crezcan con lo que envuelven; una letra no crece.
 *
 * ## Cómo se compone
 *
 * Cada trozo del árbol se convierte en una **caja** —ancho, alto y dónde está su eje, que es
 * la línea por la que se alinean el `+` y el `=`— y las cajas se ponen en fila o en columna.
 * Una fracción es una columna con el eje en la barra; un exponente es una fila con la segunda
 * caja más pequeña y subida; una raíz es su argumento con el signo delante y la raya encima.
 *
 * **Un solo compositor para dos árboles** (18-oct-2026, como el PC `ecuacion.rs`): el de la
 * gráfica ([Formula], que evalúa) y el de las notas ([com.forge.pixpin.motormd.Formulas], el
 * LaTeX de bolsillo, vía [deFormula]) que usa el texto rico del lienzo ([TextoRico]). Por eso
 * cada caja guarda sus [Pieza]s ya colocadas respecto de su esquina —y no un cierre que pinta—:
 * quien las quiera como elementos llama a [Caja.elementos]; quien las pinta sin hacerlas
 * elementos (el renderizador, el SVG, el PDF) las recorre.
 *
 * Sin Android: las letras se miden con [MedidaDeTexto].
 */
object Ecuacion {

    /**
     * Una pieza ya colocada respecto de la esquina de su caja. `color` null es «el de quien la
     * pinta»; un tramo de color (`\color{red}{X}`) lo lleva puesto, y el de dentro gana.
     */
    sealed class Pieza {
        data class Texto(
            val x: Double,
            val y: Double,
            val ancho: Double,
            val alto: Double,
            val texto: String,
            val tam: Double,
            val cursiva: Boolean,
            val negrita: Boolean = false,
            val color: String? = null
        ) : Pieza()

        data class Raya(val puntos: List<Pt>, val grosor: Double, val color: String? = null) : Pieza()

        fun corrida(dx: Double, dy: Double): Pieza = if (dx == 0.0 && dy == 0.0) this else when (this) {
            is Texto -> copy(x = x + dx, y = y + dy)
            is Raya -> copy(puntos = puntos.map { Pt(it.x + dx, it.y + dy) })
        }
    }

    /** Una caja compuesta: lo que mide, dónde está su eje y sus piezas. */
    class Caja(
        val ancho: Double,
        val alto: Double,
        /** A qué altura desde arriba pasa el eje: donde va el «=» y la barra de una fracción. */
        val eje: Double,
        val piezas: List<Pieza>
    ) {
        fun piezasEn(dx: Double, dy: Double): List<Pieza> = piezas.map { it.corrida(dx, dy) }

        /** La caja con su esquina en (x, y), como elementos del lienzo con el pincel de [estilo]. */
        fun elementos(x: Double, y: Double, estilo: ItemStyle): List<Element> =
            piezasEn(x, y).map { aElemento(it, estilo) }

        /** **De un color**, salvo lo que ya tenía el suyo: en `\color{red}{a + \color{blue}{b}}` la b sigue azul. */
        fun tenida(c: String): Caja = Caja(ancho, alto, eje, piezas.map {
            when (it) {
                is Pieza.Texto -> if (it.color == null) it.copy(color = c) else it
                is Pieza.Raya -> if (it.color == null) it.copy(color = c) else it
            }
        })

        /** Con las letras en negrita y las rayas un poco más gordas. */
        fun enNegrita(): Caja = Caja(ancho, alto, eje, piezas.map {
            when (it) {
                is Pieza.Texto -> it.copy(negrita = true)
                is Pieza.Raya -> it.copy(grosor = it.grosor * 1.4)
            }
        })
    }

    private fun aElemento(p: Pieza, estilo: ItemStyle): Element = when (p) {
        is Pieza.Texto -> {
            val conLetra = estilo.copy(fontSize = p.tam, strokeColor = p.color ?: estilo.strokeColor)
            newElement(ElementType.TEXT, p.x, p.y, conLetra, width = p.ancho, height = p.alto)
                .copy(text = p.texto, cursiva = p.cursiva, negrita = p.negrita)
        }
        is Pieza.Raya -> {
            val x0 = p.puntos.first().x
            val y0 = p.puntos.first().y
            val rel = p.puntos.map { Pt(it.x - x0, it.y - y0) }
            newElement(
                ElementType.LINE, x0, y0,
                estilo.copy(roughness = 0, strokeWidth = p.grosor, strokeColor = p.color ?: estilo.strokeColor),
                width = rel.maxOf { it.x } - rel.minOf { it.x }, height = rel.maxOf { it.y } - rel.minOf { it.y }
            ).copy(points = rel, roundness = null)
        }
    }

    /** Cuánto encoge un exponente. */
    private const val ENCOGE = 0.7

    /** Lo menos que se deja encoger una letra, o la tercera planta no se lee. */
    private const val MINIMO = 7.0

    /**
     * La ecuación `[nombre] = fórmula`, con su esquina en [donde], del tamaño de letra de
     * [estilo]. Con varias partes, una llave las abarca y cada una lleva su condición.
     */
    fun elementos(
        nombre: String,
        formula: Formula.Compilada,
        donde: Pt,
        estilo: ItemStyle,
        medir: MedidaDeTexto
    ): List<Element> = cajaDeLaEcuacion(nombre, formula, estilo, medir).elementos(donde.x, donde.y, estilo)

    /** La caja entera, para saber cuánto ocupa antes de colocarla. */
    fun cajaDeLaEcuacion(
        nombre: String,
        formula: Formula.Compilada,
        estilo: ItemStyle,
        medir: MedidaDeTexto
    ): Caja {
        val t = estilo.fontSize
        val cabeza = fila(listOf(letra("$nombre =", t, false, medir)))
        if (formula.partes.size == 1 && formula.partes[0].condicion == null) {
            return fila(listOf(cabeza, hueco(t * 0.3), componer(formula.partes[0].expresion, t, medir)))
        }
        // Por partes: una columna de «expresión  si  condición» y una llave delante.
        val filas = formula.partes.map { p ->
            val cond = p.condicion
            if (cond == null) componer(p.expresion, t, medir)
            else fila(
                listOf(
                    componer(p.expresion, t, medir),
                    hueco(t * 0.6),
                    letra("si", t * 0.85, false, medir),
                    hueco(t * 0.4),
                    componer(cond, t * 0.85, medir)
                )
            )
        }
        val col = columna(filas, t * 0.35)
        return fila(listOf(cabeza, hueco(t * 0.3), llave(col.alto, t), hueco(t * 0.2), col))
    }

    // ---------------------------------------------------------------------
    // Del árbol de la gráfica a cajas
    // ---------------------------------------------------------------------

    private fun componer(n: Formula.Nodo, t: Double, medir: MedidaDeTexto): Caja = when (n) {
        is Formula.Nodo.Numero -> letra(n.texto, t, false, medir)
        is Formula.Nodo.Variable -> letra(n.nombre, t, true, medir)
        is Formula.Nodo.Constante -> letra(if (n.nombre == "pi") "π" else n.nombre, t, n.nombre == "e", medir)
        is Formula.Nodo.Grupo -> entreParentesis(componer(n.a, t, medir), t, medir)
        is Formula.Nodo.Negado -> fila(listOf(letra("−", t, false, medir), conParentesisSiSuma(n.a, t, medir)))
        is Formula.Nodo.Binaria -> binaria(n, t, medir)
        is Formula.Nodo.Funcion -> funcion(n, t, medir)
    }

    private fun binaria(n: Formula.Nodo.Binaria, t: Double, medir: MedidaDeTexto): Caja {
        fun signo(s: String) = fila(listOf(hueco(t * 0.22), letra(s, t, false, medir), hueco(t * 0.22)))
        fun entre(s: String) = fila(listOf(componer(n.a, t, medir), signo(s), componer(n.b, t, medir)))
        return when (n.op) {
            '+' -> entre("+")
            '-' -> fila(listOf(componer(n.a, t, medir), signo("−"), conParentesisSiSuma(n.b, t, medir)))
            '*' -> if (n.implicita) {
                fila(listOf(componer(n.a, t, medir), hueco(t * 0.08), componer(n.b, t, medir)))
            } else entre("·")
            '/' -> fraccion(componer(n.a, t, medir), componer(n.b, t, medir), t)
            '%' -> entre("mod")
            '^' -> potencia(conParentesisSiCompuesto(n.a, t, medir), componer(n.b, menor(t), medir), t)
            '<' -> entre("<")
            '>' -> entre(">")
            'l' -> entre("≤")
            'g' -> entre("≥")
            '=' -> entre("=")
            else -> entre("≠")
        }
    }

    private fun funcion(n: Formula.Nodo.Funcion, t: Double, medir: MedidaDeTexto): Caja = when (n.nombre) {
        "sqrt" -> raiz(componer(n.args[0], t, medir), null, t)
        "cbrt" -> raiz(componer(n.args[0], t, medir), letra("3", menor(t), false, medir), t)
        "abs" -> entreBarras(componer(n.args[0], t, medir), t)
        "exp" -> potencia(letra("e", t, true, medir), componer(n.args[0], menor(t), medir), t)
        "pow" -> potencia(conParentesisSiCompuesto(n.args[0], t, medir), componer(n.args[1], menor(t), medir), t)
        "si" -> fila(
            listOf(
                componer(n.args[1], t, medir), hueco(t * 0.5),
                letra("si", t * 0.85, false, medir), hueco(t * 0.3),
                componer(n.args[0], t * 0.85, medir), hueco(t * 0.5),
                letra("si no", t * 0.85, false, medir), hueco(t * 0.3),
                componer(n.args[2], t, medir)
            )
        )
        "log2" -> fila(
            listOf(
                subindice(letra("log", t, false, medir), letra("2", menor(t), false, medir), t),
                entreParentesis(componer(n.args[0], t, medir), t, medir)
            )
        )
        else -> {
            val nombre = when (n.nombre) { "asin" -> "arcsin"; "acos" -> "arccos"; "atan" -> "arctan"; else -> n.nombre }
            val dentro = if (n.args.size == 1) componer(n.args[0], t, medir)
            else fila(
                n.args.map { componer(it, t, medir) }.flatMapIndexed { i, c ->
                    if (i == 0) listOf(c) else listOf(letra(",", t, false, medir), hueco(t * 0.2), c)
                }
            )
            fila(listOf(letra(nombre, t, false, medir), hueco(t * 0.05), entreParentesis(dentro, t, medir)))
        }
    }

    private fun conParentesisSiSuma(n: Formula.Nodo, t: Double, medir: MedidaDeTexto): Caja {
        val c = componer(n, t, medir)
        return if (n is Formula.Nodo.Binaria && (n.op == '+' || n.op == '-')) entreParentesis(c, t, medir) else c
    }

    private fun conParentesisSiCompuesto(n: Formula.Nodo, t: Double, medir: MedidaDeTexto): Caja {
        val c = componer(n, t, medir)
        return if (n is Formula.Nodo.Binaria || n is Formula.Nodo.Negado) entreParentesis(c, t, medir) else c
    }

    // ---------------------------------------------------------------------
    // Las cajas
    // ---------------------------------------------------------------------

    fun menor(t: Double) = max(t * ENCOGE, MINIMO)

    /** Un texto suelto. Su eje está donde una letra minúscula tiene la mitad de su cuerpo. */
    fun letra(texto: String, t: Double, cursiva: Boolean, medir: MedidaDeTexto): Caja {
        val (ancho, alto) = medir(texto, t)
        return Caja(ancho, alto, alto * 0.55, listOf(Pieza.Texto(0.0, 0.0, ancho, alto, texto, t, cursiva)))
    }

    fun hueco(ancho: Double) = Caja(ancho, 0.0, 0.0, emptyList())

    /** Cajas en fila, alineadas por su eje. */
    fun fila(cajas: List<Caja>): Caja {
        val arriba = cajas.maxOfOrNull { it.eje } ?: 0.0
        val abajo = cajas.maxOfOrNull { it.alto - it.eje } ?: 0.0
        val piezas = ArrayList<Pieza>()
        var cursor = 0.0
        for (c in cajas) {
            piezas += c.piezasEn(cursor, arriba - c.eje)
            cursor += c.ancho
        }
        return Caja(cursor, arriba + abajo, arriba, piezas)
    }

    /** Cajas en columna, alineadas a la izquierda; el eje en medio. */
    fun columna(cajas: List<Caja>, separacion: Double): Caja {
        val ancho = cajas.maxOfOrNull { it.ancho } ?: 0.0
        val alto = cajas.sumOf { it.alto } + separacion * max(0, cajas.size - 1)
        val piezas = ArrayList<Pieza>()
        var cursor = 0.0
        for (c in cajas) {
            piezas += c.piezasEn(0.0, cursor)
            cursor += c.alto + separacion
        }
        return Caja(ancho, alto, alto / 2, piezas)
    }

    /** El grosor de las rayas de la ecuación: proporcional a la letra. */
    private fun trazo(t: Double) = (t / 14.0).coerceIn(0.8, 3.0)

    private fun raya(puntos: List<Pair<Double, Double>>, t: Double): Pieza =
        Pieza.Raya(puntos.map { Pt(it.first, it.second) }, trazo(t))

    /** Numerador sobre denominador, con la barra en el eje. */
    fun fraccion(arriba: Caja, abajo: Caja, t: Double): Caja {
        val margen = t * 0.15
        val ancho = max(arriba.ancho, abajo.ancho) + margen * 2
        val hueco = t * 0.18
        val alto = arriba.alto + hueco * 2 + abajo.alto
        val eje = arriba.alto + hueco
        val piezas = ArrayList<Pieza>()
        piezas += arriba.piezasEn((ancho - arriba.ancho) / 2, 0.0)
        piezas += raya(listOf(0.0 to eje, ancho to eje), t)
        piezas += abajo.piezasEn((ancho - abajo.ancho) / 2, eje + hueco)
        return Caja(ancho, alto, eje, piezas)
    }

    /** La base con el exponente pequeño y subido: su pie queda a media altura de la base. */
    fun potencia(base: Caja, exponente: Caja, t: Double): Caja {
        val pie = base.alto * 0.45
        val sobresale = max(0.0, exponente.alto - pie)
        val hueco = t * 0.05
        return Caja(
            base.ancho + hueco + exponente.ancho, base.alto + sobresale, base.eje + sobresale,
            base.piezasEn(0.0, sobresale) + exponente.piezasEn(base.ancho + hueco, sobresale + pie - exponente.alto)
        )
    }

    /** La base con un índice pequeño y bajado. */
    fun subindice(base: Caja, indice: Caja, t: Double): Caja {
        // Su centro algo por encima del borde de abajo de la base (PC 18-oct): con la mitad por debajo
        // caía tan bajo que en un renglón de texto pisaba el de abajo («P₍ₓ₎=x⁴+6 es un polinomio…»).
        val bajada = indice.alto * 0.72
        val alto = max(base.alto, base.alto - base.eje + indice.alto - bajada + base.eje)
        return Caja(
            base.ancho + indice.ancho + t * 0.05, alto, base.eje,
            base.piezas + indice.piezasEn(base.ancho + t * 0.05, base.alto - bajada)
        )
    }

    /** El signo de la raíz dibujado con líneas —crece con lo que envuelve— y la raya encima. */
    fun raiz(dentro: Caja, indice: Caja?, t: Double): Caja {
        val margen = t * 0.15
        val altoDentro = dentro.alto + margen
        val anchoDelSigno = t * 0.6
        val anchoIndice = indice?.let { it.ancho * 0.6 } ?: 0.0
        val ancho = anchoIndice + anchoDelSigno + dentro.ancho + margen
        val alto = altoDentro + margen
        val x0 = anchoIndice
        val piezas = ArrayList<Pieza>()
        piezas += raya(
            listOf(
                x0 to alto * 0.6,
                x0 + anchoDelSigno * 0.4 to alto,
                x0 + anchoDelSigno to 0.0,
                x0 + anchoDelSigno + dentro.ancho + margen to 0.0
            ),
            t
        )
        piezas += dentro.piezasEn(x0 + anchoDelSigno + margen / 2, margen * 2)
        if (indice != null) piezas += indice.piezasEn(0.0, alto * 0.25 - indice.alto / 2)
        return Caja(ancho, alto, dentro.eje + margen * 2, piezas)
    }

    /** Entre paréntesis del alto de lo de dentro: de letra si cabe, y si no dibujados. */
    fun entreParentesis(dentro: Caja, t: Double, medir: MedidaDeTexto): Caja {
        val abre = letra("(", t, false, medir)
        // Un exponente dentro (`(x^2 + 1)`) apenas lo sube: con la letra se lee mejor.
        if (dentro.alto <= abre.alto * 1.6) {
            return fila(listOf(abre, dentro, letra(")", t, false, medir)))
        }
        // Altos: dos arcos suaves (con tres puntos salían picos, como ⟨ ⟩).
        val ap = t * 0.35
        val ancho = dentro.ancho + ap * 2 + t * 0.2
        val h = dentro.alto
        val xd = ancho - ap
        fun arco(x0: Double, hacia: Double) = (0..10).map { k ->
            val s = k / 10.0
            (x0 + hacia * sin(PI * s)) to h * s
        }
        val piezas = ArrayList<Pieza>()
        piezas += raya(arco(ap, -ap * 0.7), t)
        piezas += dentro.piezasEn(ap + t * 0.1, 0.0)
        piezas += raya(arco(xd, ap * 0.7), t)
        return Caja(ancho, dentro.alto, dentro.eje, piezas)
    }

    /** Entre barras verticales: el valor absoluto. */
    fun entreBarras(dentro: Caja, t: Double): Caja {
        val margen = t * 0.2
        val ancho = dentro.ancho + margen * 4
        val h = dentro.alto
        val piezas = ArrayList<Pieza>()
        piezas += raya(listOf(margen to 0.0, margen to h), t)
        piezas += dentro.piezasEn(margen * 2, 0.0)
        piezas += raya(listOf(ancho - margen to 0.0, ancho - margen to h), t)
        return Caja(ancho, dentro.alto, dentro.eje, piezas)
    }

    /** Entre corchetes del alto de lo de dentro, dibujados (una matriz). */
    fun entreCorchetes(dentro: Caja, t: Double): Caja {
        val m = t * 0.12
        val pata = t * 0.22
        val ancho = dentro.ancho + (m * 2 + pata) * 2
        val h = dentro.alto
        val xd = ancho - m
        val piezas = ArrayList<Pieza>()
        piezas += raya(listOf(m + pata to 0.0, m to 0.0, m to h, m + pata to h), t)
        piezas += dentro.piezasEn(m * 2 + pata, 0.0)
        piezas += raya(listOf(xd - pata to 0.0, xd to 0.0, xd to h, xd - pata to h), t)
        return Caja(ancho, h, dentro.eje, piezas)
    }

    /** **Una matriz o un sistema**: celdas en filas y columnas, sin delimitadores. */
    fun matriz(filas: List<List<Caja>>, t: Double, aLaIzquierda: Boolean): Caja {
        val columnas = filas.maxOfOrNull { it.size } ?: 0
        val anchos = DoubleArray(columnas)
        for (f in filas) f.forEachIndexed { j, c -> anchos[j] = max(anchos[j], c.ancho) }
        val entreColumnas = t * 0.9
        val entreFilas = t * 0.3
        val piezas = ArrayList<Pieza>()
        var y = 0.0
        filas.forEachIndexed { i, f ->
            if (i > 0) y += entreFilas
            val arriba = f.maxOfOrNull { it.eje } ?: 0.0
            val abajo = f.maxOfOrNull { it.alto - it.eje } ?: 0.0
            var x = 0.0
            f.forEachIndexed { j, c ->
                val dx = if (aLaIzquierda) 0.0 else (anchos[j] - c.ancho) / 2
                piezas += c.piezasEn(x + dx, y + arriba - c.eje)
                x += anchos[j] + entreColumnas
            }
            y += arriba + abajo
        }
        val ancho = anchos.sum() + entreColumnas * max(0, columnas - 1)
        return Caja(ancho, y, y / 2, piezas)
    }

    /** Una llave de la altura de la columna de partes, dibujada con líneas. */
    fun llave(alto: Double, t: Double): Caja {
        val ancho = t * 0.45
        val pico = t * 0.25
        return Caja(
            ancho, alto, alto / 2,
            listOf(
                raya(
                    listOf(
                        ancho to 0.0, ancho * 0.5 to pico, ancho * 0.5 to alto / 2 - pico,
                        0.0 to alto / 2, ancho * 0.5 to alto / 2 + pico,
                        ancho * 0.5 to alto - pico, ancho to alto
                    ),
                    t
                )
            )
        )
    }

    // ---------------------------------------------------------------------
    // Adornos, recuadros y lo pequeño encima (PC, 15-oct-2026)
    // ---------------------------------------------------------------------

    /**
     * Dónde empieza y dónde acaba **la tinta** de una caja en vertical, no su caja: la caja de
     * una letra lleva el interlineado, y una raya encima de `AB` puesta sobre la caja quedaba
     * a media letra de distancia.
     */
    private fun tintaVertical(c: Caja): Pair<Double, Double> {
        var arriba = Double.MAX_VALUE
        var abajo = -Double.MAX_VALUE
        for (p in c.piezas) when (p) {
            is Pieza.Texto -> {
                val base = p.y + p.alto * 0.55 + p.tam * 0.25
                arriba = min(arriba, base - p.tam * 0.72)
                abajo = max(abajo, base)
            }
            is Pieza.Raya -> for (q in p.puntos) {
                arriba = min(arriba, q.y)
                abajo = max(abajo, q.y)
            }
        }
        return if (arriba > abajo) 0.0 to c.alto else arriba to abajo
    }

    /**
     * **Una marca encima (o debajo) del ancho de lo que cubre**: la raya de `AB`, el arco de
     * `AC`, la flecha de un vector, el gorro, la tilde, el subrayado. Pegada a la tinta; si no
     * cabe, la caja crece.
     */
    fun adornada(dentro: Caja, adorno: Adorno, t: Double): Caja {
        val (arriba, abajo) = tintaVertical(dentro)
        val w = dentro.ancho
        val m = t * 0.06
        val x0 = m
        val x1 = max(w - m, m + 1.0)
        val separa = t * 0.1
        val h = when (adorno) {
            Adorno.ARCO_ENCIMA -> min(t * 0.16 + w * 0.04, t * 0.32)
            Adorno.GORRO, Adorno.TILDE -> t * 0.16
            Adorno.FLECHA -> t * 0.12
            Adorno.RAYA_ENCIMA, Adorno.RAYA_DEBAJO, Adorno.LLAVE_DEBAJO -> 0.0
        }
        // La llave de `\underbrace`: dos tramos que bajan, van rectos y se juntan en un pico en medio.
        if (adorno == Adorno.LLAVE_DEBAJO) {
            val y0 = abajo + t * 0.1
            val altoLlave = t * 0.22
            val xm = (x0 + x1) / 2; val y1 = y0 + altoLlave
            val medio = y0 + altoLlave * 0.55
            fun lado(de: Double, a: Double) = (0..10).map { k ->
                val s = k / 10.0
                val y = if (s < 0.15) y0 + (medio - y0) * (s / 0.15) else if (s < 0.85) medio else medio + (y1 - medio) * ((s - 0.85) / 0.15)
                (de + (a - de) * s) to y
            }
            return Caja(w, max(dentro.alto, y1 + trazo(t)), dentro.eje, dentro.piezas + raya(lado(x0, xm), t) + raya(lado(x1, xm), t))
        }
        if (adorno == Adorno.RAYA_DEBAJO) {
            val y = abajo + t * 0.12
            return Caja(w, max(dentro.alto, y + trazo(t)), dentro.eje, dentro.piezas + raya(listOf(x0 to y, x1 to y), t))
        }
        // La marca acaba `separa` por encima de la tinta; si se sale de la caja, todo baja.
        var fondo = arriba - separa
        val baja = max(0.0, h + trazo(t) - fondo)
        fondo += baja
        val tope = fondo - h
        val piezas = ArrayList<Pieza>(dentro.piezasEn(0.0, baja))
        val marca: List<Pair<Double, Double>> = when (adorno) {
            Adorno.RAYA_ENCIMA -> listOf(x0 to fondo, x1 to fondo)
            Adorno.ARCO_ENCIMA -> (0..20).map { k ->
                val s = k / 20.0
                // Un trozo de círculo achatado: sube rápido y se aplana arriba.
                (x0 + (x1 - x0) * s) to fondo - h * max(0.0, sin(PI * s)).pow(0.8)
            }
            Adorno.GORRO -> listOf(x0 to fondo, (x0 + x1) / 2 to tope, x1 to fondo)
            Adorno.TILDE -> (0..16).map { k ->
                val s = k / 16.0
                (x0 + (x1 - x0) * s) to tope + h / 2 - h / 2 * sin(2 * PI * s)
            }
            Adorno.FLECHA -> {
                val y = fondo - h / 2
                piezas += raya(listOf(x1 - t * 0.18 to y - t * 0.1, x1 to y, x1 - t * 0.18 to y + t * 0.1), t)
                listOf(x0 to y, x1 to y)
            }
            Adorno.RAYA_DEBAJO, Adorno.LLAVE_DEBAJO -> emptyList()
        }
        piezas += raya(marca, t)
        return Caja(w, dentro.alto + baja, dentro.eje + baja, piezas)
    }

    /** **Dentro de un recuadro** (`\boxed`): un margen alrededor de la tinta y el marco. */
    fun recuadro(dentro: Caja, t: Double): Caja {
        val (arriba, abajo) = tintaVertical(dentro)
        val m = t * 0.25
        val dy = max(0.0, m - arriba)
        val ancho = dentro.ancho + m * 2
        val y0 = arriba + dy - m
        val y1 = abajo + dy + m
        val piezas = ArrayList<Pieza>(dentro.piezasEn(m, dy))
        piezas += raya(listOf(0.0 to y0, ancho to y0, ancho to y1, 0.0 to y1, 0.0 to y0), t)
        return Caja(ancho, max(dentro.alto + dy, y1 + trazo(t)), dentro.eje + dy, piezas)
    }

    /** Lo pequeño centrado debajo de la base (el rótulo de `\underbrace`, PC 18-oct). */
    fun debajo(base: Caja, abajo: Caja, t: Double): Caja {
        val ancho = max(base.ancho, abajo.ancho)
        val dy = base.alto + t * 0.02
        return Caja(ancho, dy + abajo.alto, base.eje, base.piezasEn((ancho - base.ancho) / 2, 0.0) + abajo.piezasEn((ancho - abajo.ancho) / 2, dy))
    }

    /** Lo pequeño centrado encima de la base (`\overset{?}{=}`). */
    fun encima(base: Caja, arriba: Caja, t: Double): Caja {
        val ancho = max(base.ancho, arriba.ancho)
        val dy = arriba.alto * 0.7 + t * 0.02
        return Caja(
            ancho, base.alto + dy, base.eje + dy,
            arriba.piezasEn((ancho - arriba.ancho) / 2, 0.0) + base.piezasEn((ancho - base.ancho) / 2, dy)
        )
    }

    /** Los signos de ángulo que se dibujan con rayas en vez de con la letra. */
    val ANGULOS = charArrayOf('∢', '∡', '∠')

    /**
     * **Un signo de ángulo dibujado** (`∢` de «m∢AOB», `∡`, `∠`). Con rayas y no con la letra:
     * casi ninguna fuente de texto los trae. La caja es la de una «m» del mismo tamaño.
     */
    fun angulo(signo: Char, t: Double, medir: MedidaDeTexto): Caja {
        val (_, alto) = medir("m", t)
        val eje = alto * 0.55
        val base = eje + t * 0.25
        val w = t * 0.62
        val x0 = w * 0.1
        val x1 = w * 0.9
        fun arco(cx: Double, cy: Double, r: Double, a0: Double, a1: Double) = (0..10).map { k ->
            val a = a0 + (a1 - a0) * k / 10.0
            (cx + r * cos(a)) to (cy - r * sin(a))
        }
        val piezas = ArrayList<Pieza>()
        if (signo == '∢') {
            // Esférico: un «<» con un arco que corta los dos lados.
            val v = x0 to base - t * 0.3
            val sube = x1 to base - t * 0.66
            val baja = x1 to base + t * 0.04
            piezas += raya(listOf(sube, v, baja), t)
            val a = abs(atan((sube.second - v.second) / (sube.first - v.first)))
            piezas += raya(arco(v.first, v.second, w * 0.48, -a * 1.25, a * 1.25), t)
        } else {
            // Medido y plano: la base horizontal y el lado subiendo.
            val v = x0 to base
            val sube = x1 * 0.85 to base - t * 0.68
            piezas += raya(listOf(sube, v, x1 to base), t)
            if (signo == '∡') {
                val a = atan((v.second - sube.second) / (sube.first - v.first))
                piezas += raya(arco(v.first, v.second, w * 0.5, 0.0, a), t)
            }
        }
        return Caja(w, alto, eje, piezas)
    }

    /**
     * **De un árbol de fórmula de las notas** (el LaTeX de bolsillo y la escritura corriente)
     * **a la caja compuesta**. Es el compositor del texto rico del lienzo.
     *
     * [enLinea]: la fórmula va dentro de un renglón de texto, como `$...$`: sus fracciones
     * encogen para no abrir el renglón (las de `\dfrac` no).
     */
    fun deFormula(p: F, t: Double, medir: MedidaDeTexto, enLinea: Boolean): Caja {
        fun sigue(q: F, tt: Double) = deFormula(q, tt, medir, enLinea)
        return when (p) {
            is F.Texto -> when {
                p.texto.isEmpty() -> hueco(0.0)
                p.texto.any { it in ANGULOS } -> {
                    // Los ángulos, dibujados; lo de alrededor, en letra.
                    val cajas = ArrayList<Caja>()
                    val trozo = StringBuilder()
                    for (c in p.texto) {
                        if (c in ANGULOS) {
                            if (trozo.isNotEmpty()) { cajas += letra(trozo.toString(), t, p.cursiva, medir); trozo.clear() }
                            cajas += angulo(c, t, medir)
                        } else trozo.append(c)
                    }
                    if (trozo.isNotEmpty()) cajas += letra(trozo.toString(), t, p.cursiva, medir)
                    fila(cajas)
                }
                else -> letra(p.texto, t, p.cursiva, medir)
            }
            is F.Fila -> fila(p.partes.map { sigue(it, t) })
            is F.Fraccion -> {
                // Una fracción dentro de otra, más pequeña; en un renglón de texto, ya de entrada.
                val m = if (enLinea) max(t * 0.78, MINIMO) else max(menor(t), t * 0.85)
                fraccion(sigue(p.arriba, m), sigue(p.abajo, m), t)
            }
            is F.Grande -> deFormula(p.dentro, t, medir, false)
            is F.ConIndices -> {
                var c = sigue(p.base, t)
                p.abajo?.let { c = subindice(c, sigue(it, menor(t)), t) }
                p.arriba?.let { c = potencia(c, sigue(it, menor(t)), t) }
                c
            }
            is F.Raiz -> raiz(sigue(p.dentro, t), p.indice?.let { sigue(it, menor(menor(t))) }, t)
            is F.Agrupado -> {
                val d = sigue(p.dentro, t)
                when (p.abre) {
                    "(" -> entreParentesis(d, t, medir)
                    "|" -> entreBarras(d, t)
                    else -> fila(listOf(letra(p.abre, t, false, medir), d, letra(p.cierra, t, false, medir)))
                }
            }
            // Matrices y sistemas: la rejilla y su delimitador dibujado del alto de la rejilla.
            is F.Tabla -> {
                val m = matriz(p.filas.map { f -> f.map { sigue(it, t) } }, t, p.izquierda)
                when {
                    p.abre == "[" -> entreCorchetes(m, t)
                    p.abre == "(" -> entreParentesis(m, t, medir)
                    p.abre == "|" -> entreBarras(m, t)
                    p.abre == "‖" -> entreBarras(entreBarras(m, t), t)
                    p.abre == "{" && p.cierra.isEmpty() -> fila(listOf(llave(m.alto, t), hueco(t * 0.3), m))
                    p.abre == "{" -> entreCorchetes(m, t)
                    else -> m
                }
            }
            is F.Adornada -> adornada(sigue(p.dentro, t), p.adorno, t)
            is F.Color -> {
                val c = sigue(p.dentro, t)
                com.forge.pixpin.motormd.Formulas.colorDeNombre(p.color)?.let { c.tenida(it) } ?: c
            }
            is F.Negrita -> sigue(p.dentro, t).enNegrita()
            is F.Recuadro -> recuadro(sigue(p.dentro, t), t)
            is F.Encima -> encima(sigue(p.base, t), sigue(p.arriba, menor(t)), t)
            is F.Debajo -> debajo(sigue(p.base, t), sigue(p.abajo, menor(t)), t)
        }
    }
}
