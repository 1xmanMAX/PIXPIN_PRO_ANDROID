package com.forge.pixpin.motor

import kotlin.math.max

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
 * Igual que un compositor de fórmulas de verdad, pero con lo justo: cada trozo del árbol de
 * [Formula] se convierte en una **caja** —ancho, alto y dónde está su eje, que es la línea
 * por la que se alinean el `+` y el `=`— y las cajas se ponen en fila o en columna. Una
 * fracción es una columna con el eje en la barra; un exponente es una fila con la segunda
 * caja más pequeña y subida; una raíz es su argumento con el signo delante y la raya
 * encima. Cada caja sabe pintarse en un sitio, y pintar la ecuación es pintar la caja de
 * fuera en la esquina que se pida.
 *
 * Sin Android: las letras se miden con [MedidaDeTexto].
 */
object Ecuacion {

    /** Una caja compuesta: lo que mide, dónde está su eje, y cómo se pinta en (x, y). */
    class Caja(
        val ancho: Double,
        val alto: Double,
        /** A qué altura desde arriba pasa el eje: donde va el «=» y la barra de una fracción. */
        val eje: Double,
        val pintar: (x: Double, y: Double) -> List<Element>
    )

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
    ): List<Element> {
        val caja = cajaDeLaEcuacion(nombre, formula, estilo, medir)
        return caja.pintar(donde.x, donde.y)
    }

    /** La caja entera, para saber cuánto ocupa antes de colocarla. */
    fun cajaDeLaEcuacion(
        nombre: String,
        formula: Formula.Compilada,
        estilo: ItemStyle,
        medir: MedidaDeTexto
    ): Caja {
        val t = estilo.fontSize
        val cabeza = fila(listOf(letra("$nombre =", t, estilo, medir)), t)
        if (formula.partes.size == 1 && formula.partes[0].condicion == null) {
            return fila(listOf(cabeza, hueco(t * 0.3), componer(formula.partes[0].expresion, t, estilo, medir)), t)
        }
        // Por partes: una columna de «expresión  si  condición» y una llave delante.
        val filas = formula.partes.map { p ->
            val cond = p.condicion
            if (cond == null) componer(p.expresion, t, estilo, medir)
            else fila(
                listOf(
                    componer(p.expresion, t, estilo, medir),
                    hueco(t * 0.6),
                    letra("si", t * 0.85, estilo.copy(cursiva = false), medir),
                    hueco(t * 0.4),
                    componer(cond, t * 0.85, estilo, medir)
                ),
                t
            )
        }
        val columna = columna(filas, t * 0.35)
        return fila(listOf(cabeza, hueco(t * 0.3), llave(columna.alto, t, estilo), hueco(t * 0.2), columna), t)
    }

    // ---------------------------------------------------------------------
    // Del árbol a cajas
    // ---------------------------------------------------------------------

    private fun componer(n: Formula.Nodo, t: Double, estilo: ItemStyle, medir: MedidaDeTexto): Caja = when (n) {
        is Formula.Nodo.Numero -> letra(n.texto, t, estilo.copy(cursiva = false), medir)
        is Formula.Nodo.Variable -> letra(n.nombre, t, estilo.copy(cursiva = true), medir)
        is Formula.Nodo.Constante -> letra(if (n.nombre == "pi") "π" else n.nombre, t, estilo.copy(cursiva = n.nombre == "e"), medir)
        is Formula.Nodo.Grupo -> entreParentesis(componer(n.a, t, estilo, medir), t, estilo, medir)
        is Formula.Nodo.Negado -> fila(
            listOf(letra("−", t, estilo.copy(cursiva = false), medir), conParentesisSiSuma(n.a, t, estilo, medir)), t
        )
        is Formula.Nodo.Binaria -> binaria(n, t, estilo, medir)
        is Formula.Nodo.Funcion -> funcion(n, t, estilo, medir)
    }

    private fun binaria(n: Formula.Nodo.Binaria, t: Double, estilo: ItemStyle, medir: MedidaDeTexto): Caja {
        val recto = estilo.copy(cursiva = false)
        fun signo(s: String) = fila(listOf(hueco(t * 0.22), letra(s, t, recto, medir), hueco(t * 0.22)), t)
        return when (n.op) {
            '+' -> fila(listOf(componer(n.a, t, estilo, medir), signo("+"), componer(n.b, t, estilo, medir)), t)
            '-' -> fila(listOf(componer(n.a, t, estilo, medir), signo("−"), conParentesisSiSuma(n.b, t, estilo, medir)), t)
            '*' -> if (n.implicita) {
                fila(listOf(componer(n.a, t, estilo, medir), hueco(t * 0.08), componer(n.b, t, estilo, medir)), t)
            } else {
                fila(listOf(componer(n.a, t, estilo, medir), signo("·"), componer(n.b, t, estilo, medir)), t)
            }
            '/' -> fraccion(componer(n.a, t, estilo, medir), componer(n.b, t, estilo, medir), t, estilo)
            '%' -> fila(listOf(componer(n.a, t, estilo, medir), signo("mod"), componer(n.b, t, estilo, medir)), t)
            '^' -> potencia(conParentesisSiCompuesto(n.a, t, estilo, medir), componer(n.b, menor(t), estilo, medir), t)
            '<' -> fila(listOf(componer(n.a, t, estilo, medir), signo("<"), componer(n.b, t, estilo, medir)), t)
            '>' -> fila(listOf(componer(n.a, t, estilo, medir), signo(">"), componer(n.b, t, estilo, medir)), t)
            'l' -> fila(listOf(componer(n.a, t, estilo, medir), signo("≤"), componer(n.b, t, estilo, medir)), t)
            'g' -> fila(listOf(componer(n.a, t, estilo, medir), signo("≥"), componer(n.b, t, estilo, medir)), t)
            '=' -> fila(listOf(componer(n.a, t, estilo, medir), signo("="), componer(n.b, t, estilo, medir)), t)
            else -> fila(listOf(componer(n.a, t, estilo, medir), signo("≠"), componer(n.b, t, estilo, medir)), t)
        }
    }

    private fun funcion(n: Formula.Nodo.Funcion, t: Double, estilo: ItemStyle, medir: MedidaDeTexto): Caja {
        val recto = estilo.copy(cursiva = false)
        return when (n.nombre) {
            "sqrt" -> raiz(componer(n.args[0], t, estilo, medir), null, t, estilo, medir)
            "cbrt" -> raiz(componer(n.args[0], t, estilo, medir), letra("3", menor(t), recto, medir), t, estilo, medir)
            "abs" -> entreBarras(componer(n.args[0], t, estilo, medir), t, estilo)
            "exp" -> potencia(letra("e", t, estilo.copy(cursiva = true), medir), componer(n.args[0], menor(t), estilo, medir), t)
            "pow" -> potencia(conParentesisSiCompuesto(n.args[0], t, estilo, medir), componer(n.args[1], menor(t), estilo, medir), t)
            "si" -> fila(
                listOf(
                    componer(n.args[1], t, estilo, medir), hueco(t * 0.5),
                    letra("si", t * 0.85, recto, medir), hueco(t * 0.3),
                    componer(n.args[0], t * 0.85, estilo, medir), hueco(t * 0.5),
                    letra("si no", t * 0.85, recto, medir), hueco(t * 0.3),
                    componer(n.args[2], t, estilo, medir)
                ),
                t
            )
            "log2" -> fila(
                listOf(
                    subindice(letra("log", t, recto, medir), letra("2", menor(t), recto, medir), t),
                    entreParentesis(componer(n.args[0], t, estilo, medir), t, estilo, medir)
                ),
                t
            )
            else -> {
                val nombre = when (n.nombre) { "asin" -> "arcsin"; "acos" -> "arccos"; "atan" -> "arctan"; else -> n.nombre }
                val dentro = if (n.args.size == 1) componer(n.args[0], t, estilo, medir)
                else fila(
                    n.args.map { componer(it, t, estilo, medir) }.flatMapIndexed { i, c ->
                        if (i == 0) listOf(c) else listOf(letra(",", t, recto, medir), hueco(t * 0.2), c)
                    },
                    t
                )
                fila(listOf(letra(nombre, t, recto, medir), hueco(t * 0.05), entreParentesis(dentro, t, estilo, medir)), t)
            }
        }
    }

    private fun conParentesisSiSuma(n: Formula.Nodo, t: Double, estilo: ItemStyle, medir: MedidaDeTexto): Caja {
        val c = componer(n, t, estilo, medir)
        return if (n is Formula.Nodo.Binaria && (n.op == '+' || n.op == '-')) entreParentesis(c, t, estilo, medir) else c
    }

    private fun conParentesisSiCompuesto(n: Formula.Nodo, t: Double, estilo: ItemStyle, medir: MedidaDeTexto): Caja {
        val c = componer(n, t, estilo, medir)
        return if (n is Formula.Nodo.Binaria || n is Formula.Nodo.Negado) entreParentesis(c, t, estilo, medir) else c
    }

    // ---------------------------------------------------------------------
    // Las cajas
    // ---------------------------------------------------------------------

    private fun menor(t: Double) = max(t * ENCOGE, MINIMO)

    /** Un texto suelto. Su eje está donde una letra minúscula tiene la mitad de su cuerpo. */
    private fun letra(texto: String, t: Double, estilo: ItemStyle, medir: MedidaDeTexto): Caja {
        val (ancho, alto) = medir(texto, t)
        val conLetra = estilo.copy(fontSize = t)
        return Caja(ancho, alto, alto * 0.55) { x, y ->
            listOf(
                newElement(ElementType.TEXT, x, y, conLetra, width = ancho, height = alto)
                    .copy(text = texto, cursiva = conLetra.cursiva)
            )
        }
    }

    private fun hueco(ancho: Double) = Caja(ancho, 0.0, 0.0) { _, _ -> emptyList() }

    /** Cajas en fila, alineadas por su eje. */
    private fun fila(cajas: List<Caja>, t: Double): Caja {
        val arriba = cajas.maxOf { it.eje }
        val abajo = cajas.maxOf { it.alto - it.eje }
        val ancho = cajas.sumOf { it.ancho }
        return Caja(ancho, arriba + abajo, arriba) { x, y ->
            var cursor = x
            cajas.flatMap { c ->
                val e = c.pintar(cursor, y + arriba - c.eje)
                cursor += c.ancho
                e
            }
        }
    }

    /** Cajas en columna, alineadas a la izquierda; el eje en medio. */
    private fun columna(cajas: List<Caja>, separacion: Double): Caja {
        val ancho = cajas.maxOf { it.ancho }
        val alto = cajas.sumOf { it.alto } + separacion * (cajas.size - 1)
        return Caja(ancho, alto, alto / 2) { x, y ->
            var cursor = y
            cajas.flatMap { c ->
                val e = c.pintar(x, cursor)
                cursor += c.alto + separacion
                e
            }
        }
    }

    private fun raya(a: Pt, b: Pt, estilo: ItemStyle, grosor: Double): Element {
        val rel = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
        return newElement(
            ElementType.LINE, a.x, a.y, estilo.copy(roughness = 0, strokeWidth = grosor),
            width = kotlin.math.abs(b.x - a.x), height = kotlin.math.abs(b.y - a.y)
        ).copy(points = rel, roundness = null)
    }

    private fun polilinea(puntos: List<Pt>, estilo: ItemStyle, grosor: Double): Element {
        val x0 = puntos.first().x
        val y0 = puntos.first().y
        val rel = puntos.map { Pt(it.x - x0, it.y - y0) }
        return newElement(
            ElementType.LINE, x0, y0, estilo.copy(roughness = 0, strokeWidth = grosor),
            width = rel.maxOf { it.x } - rel.minOf { it.x }, height = rel.maxOf { it.y } - rel.minOf { it.y }
        ).copy(points = rel, roundness = null)
    }

    /** El grosor de las rayas de la ecuación: proporcional a la letra. */
    private fun trazo(t: Double) = (t / 14.0).coerceIn(0.8, 3.0)

    /** Numerador sobre denominador, con la barra en el eje. */
    private fun fraccion(arriba: Caja, abajo: Caja, t: Double, estilo: ItemStyle): Caja {
        val margen = t * 0.15
        val ancho = max(arriba.ancho, abajo.ancho) + margen * 2
        val hueco = t * 0.18
        val alto = arriba.alto + hueco * 2 + abajo.alto
        val eje = arriba.alto + hueco
        return Caja(ancho, alto, eje) { x, y ->
            arriba.pintar(x + (ancho - arriba.ancho) / 2, y) +
                raya(Pt(x, y + eje), Pt(x + ancho, y + eje), estilo, trazo(t)) +
                abajo.pintar(x + (ancho - abajo.ancho) / 2, y + eje + hueco)
        }
    }

    /** La base con el exponente pequeño y subido: su pie queda a media altura de la base. */
    private fun potencia(base: Caja, exponente: Caja, t: Double): Caja {
        val pie = base.alto * 0.45
        val sobresale = max(0.0, exponente.alto - pie)
        val hueco = t * 0.05
        return Caja(base.ancho + hueco + exponente.ancho, base.alto + sobresale, base.eje + sobresale) { x, y ->
            base.pintar(x, y + sobresale) +
                exponente.pintar(x + base.ancho + hueco, y + sobresale + pie - exponente.alto)
        }
    }

    /** La base con un índice pequeño y bajado. */
    private fun subindice(base: Caja, indice: Caja, t: Double): Caja {
        val bajada = indice.alto * 0.5
        val alto = max(base.alto, base.alto - base.eje + indice.alto - bajada + base.eje)
        return Caja(base.ancho + indice.ancho + t * 0.05, alto, base.eje) { x, y ->
            base.pintar(x, y) + indice.pintar(x + base.ancho + t * 0.05, y + base.alto - bajada)
        }
    }

    /** El signo de la raíz dibujado con líneas —crece con lo que envuelve— y la raya encima. */
    private fun raiz(dentro: Caja, indice: Caja?, t: Double, estilo: ItemStyle, medir: MedidaDeTexto): Caja {
        val margen = t * 0.15
        val altoDentro = dentro.alto + margen
        val anchoDelSigno = t * 0.6
        val anchoIndice = indice?.let { it.ancho * 0.6 } ?: 0.0
        val ancho = anchoIndice + anchoDelSigno + dentro.ancho + margen
        val alto = altoDentro + margen
        return Caja(ancho, alto, dentro.eje + margen * 2) { x, y ->
            val x0 = x + anchoIndice
            val signo = polilinea(
                listOf(
                    Pt(x0, y + alto * 0.6),
                    Pt(x0 + anchoDelSigno * 0.4, y + alto),
                    Pt(x0 + anchoDelSigno, y),
                    Pt(x0 + anchoDelSigno + dentro.ancho + margen, y)
                ),
                estilo, trazo(t)
            )
            listOf(signo) +
                dentro.pintar(x0 + anchoDelSigno + margen / 2, y + margen * 2) +
                (indice?.pintar(x, y + alto * 0.25 - indice.alto / 2) ?: emptyList())
        }
    }

    /** Entre paréntesis del alto de lo de dentro: de letra si cabe, y si no dibujados. */
    private fun entreParentesis(dentro: Caja, t: Double, estilo: ItemStyle, medir: MedidaDeTexto): Caja {
        val abre = letra("(", t, estilo.copy(cursiva = false), medir)
        if (dentro.alto <= abre.alto * 1.25) {
            return fila(listOf(abre, dentro, letra(")", t, estilo.copy(cursiva = false), medir)), t)
        }
        // Altos: se dibujan como dos arcos de tres puntos, que a este tamaño se leen igual.
        val anchoDelParentesis = t * 0.35
        val ancho = dentro.ancho + anchoDelParentesis * 2 + t * 0.2
        return Caja(ancho, dentro.alto, dentro.eje) { x, y ->
            val h = dentro.alto
            val izq = polilinea(
                listOf(Pt(x + anchoDelParentesis, y), Pt(x + anchoDelParentesis * 0.3, y + h * 0.5), Pt(x + anchoDelParentesis, y + h)),
                estilo, trazo(t)
            )
            val xd = x + ancho - anchoDelParentesis
            val der = polilinea(
                listOf(Pt(xd, y), Pt(xd + anchoDelParentesis * 0.7, y + h * 0.5), Pt(xd, y + h)),
                estilo, trazo(t)
            )
            listOf(izq) + dentro.pintar(x + anchoDelParentesis + t * 0.1, y) + der
        }
    }

    /** Entre barras verticales: el valor absoluto. */
    private fun entreBarras(dentro: Caja, t: Double, estilo: ItemStyle): Caja {
        val margen = t * 0.2
        val ancho = dentro.ancho + margen * 4
        return Caja(ancho, dentro.alto, dentro.eje) { x, y ->
            listOf(raya(Pt(x + margen, y), Pt(x + margen, y + dentro.alto), estilo, trazo(t))) +
                dentro.pintar(x + margen * 2, y) +
                raya(Pt(x + ancho - margen, y), Pt(x + ancho - margen, y + dentro.alto), estilo, trazo(t))
        }
    }

    /** Una llave de la altura de la columna de partes, dibujada con líneas. */
    private fun llave(alto: Double, t: Double, estilo: ItemStyle): Caja {
        val ancho = t * 0.45
        return Caja(ancho, alto, alto / 2) { x, y ->
            val pico = t * 0.25
            listOf(
                polilinea(
                    listOf(
                        Pt(x + ancho, y), Pt(x + ancho * 0.5, y + pico), Pt(x + ancho * 0.5, y + alto / 2 - pico),
                        Pt(x, y + alto / 2), Pt(x + ancho * 0.5, y + alto / 2 + pico),
                        Pt(x + ancho * 0.5, y + alto - pico), Pt(x + ancho, y + alto)
                    ),
                    estilo, trazo(t)
                )
            )
        }
    }
}
