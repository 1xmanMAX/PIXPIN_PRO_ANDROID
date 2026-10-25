package com.forge.pixpin.motor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * **El compositor de cajas del texto rico** (25-oct-2026): el `ecuacion.rs` del PC, que a su vez
 * venía de nuestro [Ecuacion]. Aquí cada caja guarda **sus piezas ya colocadas** respecto de su
 * esquina —como en el PC— en vez del cierre que pinta de [Ecuacion]: el texto rico necesita
 * teñir lo de dentro (`\color{red}{a + \color{blue}{b}}`), engordarlo (`\mathbf`) y medir dónde
 * está la tinta para poner encima la raya de `AB` o el arco de `AC`, y eso solo se puede con las
 * piezas a la vista.
 *
 * Por qué no se amplía [Ecuacion]: esa compone el árbol de [Formula] (la gráfica, que evalúa) y
 * pinta elementos del lienzo; este compone el de [FormulaRica] (que solo sabe escribir) y lo
 * pinta el [Renderer] directamente. Mismas cuentas, entradas y salidas distintas.
 *
 * Sin Android: las letras se miden con [Medir].
 */
object CajasRicas {

    /** Lo que ocupa un texto de un tamaño: ancho y alto (el alto, el de un renglón). */
    fun interface Medir {
        fun medir(texto: String, tam: Double): Pair<Double, Double>
    }

    /**
     * Una pieza ya colocada respecto de la esquina de su caja. `color == null` es «el del
     * elemento»; un tramo de color lo lleva puesto y el de dentro gana al de fuera ([Caja.tenida]).
     * Los colores van en ARGB opaco.
     */
    sealed interface Pieza {
        fun corrida(dx: Double, dy: Double): Pieza

        data class Texto(
            val x: Double, val y: Double, val ancho: Double, val alto: Double,
            val texto: String, val tam: Double,
            val cursiva: Boolean, val negrita: Boolean, val color: Int?
        ) : Pieza {
            override fun corrida(dx: Double, dy: Double) = copy(x = x + dx, y = y + dy)
        }

        data class Raya(val puntos: List<Pt>, val grosor: Double, val color: Int?) : Pieza {
            override fun corrida(dx: Double, dy: Double) =
                copy(puntos = puntos.map { Pt(it.x + dx, it.y + dy) })
        }
    }

    /** Una caja compuesta: lo que mide, a qué altura pasa su eje y sus piezas. */
    data class Caja(val ancho: Double, val alto: Double, val eje: Double, val piezas: List<Pieza>) {
        fun piezasEn(dx: Double, dy: Double): List<Pieza> =
            if (dx == 0.0 && dy == 0.0) piezas else piezas.map { it.corrida(dx, dy) }

        /** **De un color**, salvo lo que ya tenía el suyo. */
        fun tenida(c: Int): Caja = copy(
            piezas = piezas.map { p ->
                when (p) {
                    is Pieza.Texto -> if (p.color == null) p.copy(color = c) else p
                    is Pieza.Raya -> if (p.color == null) p.copy(color = c) else p
                }
            }
        )

        /** Con las letras en negrita y las rayas un poco más gordas. */
        fun enNegrita(): Caja = copy(
            piezas = piezas.map { p ->
                when (p) {
                    is Pieza.Texto -> p.copy(negrita = true)
                    is Pieza.Raya -> p.copy(grosor = p.grosor * 1.4)
                }
            }
        )
    }

    /** Cuánto encoge un exponente. */
    private const val ENCOGE = 0.7

    /** Lo menos que se deja encoger una letra. */
    const val MINIMO = 7.0

    /** Los signos de ángulo que se dibujan con rayas en vez de con la letra. */
    val ANGULOS = charArrayOf('∢', '∡', '∠')

    fun menor(t: Double) = max(t * ENCOGE, MINIMO)

    /** El grosor de las rayas: proporcional a la letra. */
    fun trazo(t: Double) = (t / 14.0).coerceIn(0.8, 3.0)

    private fun raya(puntos: List<Pair<Double, Double>>, t: Double): Pieza =
        Pieza.Raya(puntos.map { Pt(it.first, it.second) }, trazo(t), null)

    /** Un texto suelto. Su eje está donde una minúscula tiene la mitad de su cuerpo. */
    fun letra(texto: String, t: Double, cursiva: Boolean, medir: Medir): Caja {
        val (ancho, alto) = medir.medir(texto, t)
        return Caja(
            ancho, alto, alto * 0.55,
            listOf(Pieza.Texto(0.0, 0.0, ancho, alto, texto, t, cursiva, false, null))
        )
    }

    fun hueco(ancho: Double) = Caja(ancho, 0.0, 0.0, emptyList())

    /** Cajas en fila, alineadas por su eje. */
    fun fila(cajas: List<Caja>): Caja {
        val arriba = cajas.fold(0.0) { m, c -> max(m, c.eje) }
        val abajo = cajas.fold(0.0) { m, c -> max(m, c.alto - c.eje) }
        val piezas = ArrayList<Pieza>()
        var cursor = 0.0
        for (c in cajas) {
            piezas += c.piezasEn(cursor, arriba - c.eje)
            cursor += c.ancho
        }
        return Caja(cursor, arriba + abajo, arriba, piezas)
    }

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

    /** La base con el exponente pequeño y subido. */
    fun potencia(base: Caja, exponente: Caja, t: Double): Caja {
        val pie = base.alto * 0.45
        val sobresale = max(exponente.alto - pie, 0.0)
        val hueco = t * 0.05
        val piezas = ArrayList<Pieza>()
        piezas += base.piezasEn(0.0, sobresale)
        piezas += exponente.piezasEn(base.ancho + hueco, sobresale + pie - exponente.alto)
        return Caja(base.ancho + hueco + exponente.ancho, base.alto + sobresale, base.eje + sobresale, piezas)
    }

    /** La base con un índice pequeño y bajado (su centro algo por encima del borde de abajo). */
    fun subindice(base: Caja, indice: Caja, t: Double): Caja {
        val bajada = indice.alto * 0.72
        val alto = max(base.alto, base.alto - base.eje + indice.alto - bajada + base.eje)
        val piezas = ArrayList<Pieza>()
        piezas += base.piezas
        piezas += indice.piezasEn(base.ancho + t * 0.05, base.alto - bajada)
        return Caja(base.ancho + indice.ancho + t * 0.05, alto, base.eje, piezas)
    }

    /** El signo de la raíz dibujado con líneas y la raya encima. */
    fun raiz(dentro: Caja, indice: Caja?, t: Double): Caja {
        val margen = t * 0.15
        val altoDentro = dentro.alto + margen
        val anchoSigno = t * 0.6
        val anchoIndice = indice?.let { it.ancho * 0.6 } ?: 0.0
        val ancho = anchoIndice + anchoSigno + dentro.ancho + margen
        val alto = altoDentro + margen
        val x0 = anchoIndice
        val piezas = ArrayList<Pieza>()
        piezas += raya(
            listOf(
                x0 to alto * 0.6, x0 + anchoSigno * 0.4 to alto, x0 + anchoSigno to 0.0,
                x0 + anchoSigno + dentro.ancho + margen to 0.0
            ),
            t
        )
        piezas += dentro.piezasEn(x0 + anchoSigno + margen / 2, margen * 2)
        if (indice != null) piezas += indice.piezasEn(0.0, alto * 0.25 - indice.alto / 2)
        return Caja(ancho, alto, dentro.eje + margen * 2, piezas)
    }

    /** Entre paréntesis del alto de lo de dentro: de letra si cabe, y si no dibujados. */
    fun entreParentesis(dentro: Caja, t: Double, medir: Medir): Caja {
        val abre = letra("(", t, false, medir)
        if (dentro.alto <= abre.alto * 1.6) {
            return fila(listOf(abre, dentro, letra(")", t, false, medir)))
        }
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

    /** Una matriz o un sistema: celdas en filas y columnas, sin delimitadores. */
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
            val arriba = f.fold(0.0) { m, c -> max(m, c.eje) }
            val abajo = f.fold(0.0) { m, c -> max(m, c.alto - c.eje) }
            var x = 0.0
            f.forEachIndexed { j, c ->
                val dx = if (aLaIzquierda) 0.0 else (anchos[j] - c.ancho) / 2
                piezas += c.piezasEn(x + dx, y + arriba - c.eje)
                x += anchos[j] + entreColumnas
            }
            y += arriba + abajo
        }
        val ancho = anchos.sum() + entreColumnas * max(columnas - 1, 0)
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
                        0.0 to alto / 2, ancho * 0.5 to alto / 2 + pico, ancho * 0.5 to alto - pico,
                        ancho to alto
                    ),
                    t
                )
            )
        )
    }

    /**
     * Dónde empieza y acaba **la tinta** de una caja en vertical, no su caja: la caja de una letra
     * lleva el interlineado, y una raya encima de `AB` puesta sobre la caja quedaba lejos.
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

    /** **Una marca encima (o debajo) del ancho de lo que cubre**, pegada a la tinta. */
    fun adornada(dentro: Caja, adorno: FormulaRica.Adorno, t: Double): Caja {
        val (arriba, abajo) = tintaVertical(dentro)
        val w = dentro.ancho
        val m = t * 0.06
        val x0 = m
        val x1 = max(w - m, m + 1.0)
        val separa = t * 0.1
        val h = when (adorno) {
            FormulaRica.Adorno.ARCO_ENCIMA -> min(t * 0.16 + w * 0.04, t * 0.32)
            FormulaRica.Adorno.GORRO, FormulaRica.Adorno.TILDE -> t * 0.16
            FormulaRica.Adorno.FLECHA -> t * 0.12
            else -> 0.0
        }
        if (adorno == FormulaRica.Adorno.LLAVE_DEBAJO) {
            val y0 = abajo + t * 0.1
            val altoLlave = t * 0.22
            val xm = (x0 + x1) / 2
            val y1 = y0 + altoLlave
            val medio = y0 + altoLlave * 0.55
            fun lado(de: Double, a: Double) = (0..10).map { k ->
                val s = k / 10.0
                val y = when {
                    s < 0.15 -> y0 + (medio - y0) * (s / 0.15)
                    s < 0.85 -> medio
                    else -> medio + (y1 - medio) * ((s - 0.85) / 0.15)
                }
                (de + (a - de) * s) to y
            }
            val piezas = ArrayList(dentro.piezas)
            piezas += raya(lado(x0, xm), t)
            piezas += raya(lado(x1, xm), t)
            return Caja(w, max(dentro.alto, y1 + trazo(t)), dentro.eje, piezas)
        }
        if (adorno == FormulaRica.Adorno.RAYA_DEBAJO) {
            val y = abajo + t * 0.12
            val piezas = ArrayList(dentro.piezas)
            piezas += raya(listOf(x0 to y, x1 to y), t)
            return Caja(w, max(dentro.alto, y + trazo(t)), dentro.eje, piezas)
        }
        // La marca acaba `separa` por encima de la tinta; si se sale de la caja, todo baja.
        var fondo = arriba - separa
        val baja = max(h + trazo(t) - fondo, 0.0)
        fondo += baja
        val tope = fondo - h
        val piezas = ArrayList(dentro.piezasEn(0.0, baja))
        val marca: List<Pair<Double, Double>> = when (adorno) {
            FormulaRica.Adorno.RAYA_ENCIMA -> listOf(x0 to fondo, x1 to fondo)
            FormulaRica.Adorno.ARCO_ENCIMA -> (0..20).map { k ->
                val s = k / 20.0
                // `max(0)`: el seno de pi sale un pelo negativo y su potencia sería NaN.
                (x0 + (x1 - x0) * s) to fondo - h * max(sin(PI * s), 0.0).pow(0.8)
            }
            FormulaRica.Adorno.GORRO -> listOf(x0 to fondo, (x0 + x1) / 2 to tope, x1 to fondo)
            FormulaRica.Adorno.TILDE -> (0..16).map { k ->
                val s = k / 16.0
                (x0 + (x1 - x0) * s) to tope + h / 2 - h / 2 * sin(2 * PI * s)
            }
            FormulaRica.Adorno.FLECHA -> {
                val y = fondo - h / 2
                piezas += raya(listOf(x1 - t * 0.18 to y - t * 0.1, x1 to y, x1 - t * 0.18 to y + t * 0.1), t)
                listOf(x0 to y, x1 to y)
            }
            else -> emptyList()
        }
        piezas += raya(marca, t)
        return Caja(w, dentro.alto + baja, dentro.eje + baja, piezas)
    }

    /** **Dentro de un recuadro** (`\boxed`): un margen alrededor de la tinta y el marco. */
    fun recuadro(dentro: Caja, t: Double): Caja {
        val (arriba, abajo) = tintaVertical(dentro)
        val m = t * 0.25
        val dy = max(m - arriba, 0.0)
        val ancho = dentro.ancho + m * 2
        val y0 = arriba + dy - m
        val y1 = abajo + dy + m
        val piezas = ArrayList(dentro.piezasEn(m, dy))
        piezas += raya(listOf(0.0 to y0, ancho to y0, ancho to y1, 0.0 to y1, 0.0 to y0), t)
        return Caja(ancho, max(dentro.alto + dy, y1 + trazo(t)), dentro.eje + dy, piezas)
    }

    /** Lo pequeño centrado encima de la base (`\overset{?}{=}`). */
    fun encima(base: Caja, arriba: Caja, t: Double): Caja {
        val ancho = max(base.ancho, arriba.ancho)
        val hueco = t * 0.02
        val piezas = ArrayList(arriba.piezasEn((ancho - arriba.ancho) / 2, 0.0))
        val dy = arriba.alto * 0.7 + hueco
        piezas += base.piezasEn((ancho - base.ancho) / 2, dy)
        return Caja(ancho, base.alto + dy, base.eje + dy, piezas)
    }

    /** Lo pequeño centrado debajo de la base (el rótulo de `\underbrace`). */
    fun debajo(base: Caja, abajo: Caja, t: Double): Caja {
        val ancho = max(base.ancho, abajo.ancho)
        val piezas = ArrayList(base.piezasEn((ancho - base.ancho) / 2, 0.0))
        val dy = base.alto + t * 0.02
        piezas += abajo.piezasEn((ancho - abajo.ancho) / 2, dy)
        return Caja(ancho, dy + abajo.alto, base.eje, piezas)
    }

    /**
     * **Un signo de ángulo dibujado** (`∢` de «m∢AOB», `∡`, `∠`): casi ninguna fuente los trae,
     * así que salen con rayas, igual en pantalla, en el PNG y en el PDF. La caja es la de una letra.
     */
    fun angulo(signo: Char, t: Double, medir: Medir): Caja {
        val (_, alto) = medir.medir("m", t)
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
            val v = x0 to base - t * 0.3
            val arriba = x1 to base - t * 0.66
            val abajo = x1 to base + t * 0.04
            piezas += raya(listOf(arriba, v, abajo), t)
            val a = abs(atan((arriba.second - v.second) / (arriba.first - v.first)))
            piezas += raya(arco(v.first, v.second, w * 0.48, -a * 1.25, a * 1.25), t)
        } else {
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
     * **De un árbol de [FormulaRica] a la caja compuesta** (`de_formula` del PC). `enLinea`: la
     * fórmula va dentro de un renglón de texto (`$...$`) y sus fracciones encogen para no abrirlo.
     */
    fun deFormula(p: FormulaRica.Nodo, t: Double, medir: Medir, enLinea: Boolean): Caja {
        fun sigue(q: FormulaRica.Nodo, tt: Double) = deFormula(q, tt, medir, enLinea)
        return when (p) {
            is FormulaRica.Nodo.Texto -> when {
                p.texto.isEmpty() -> hueco(0.0)
                p.texto.any { it in ANGULOS } -> {
                    val cajas = ArrayList<Caja>()
                    val trozo = StringBuilder()
                    for (c in p.texto) {
                        if (c in ANGULOS) {
                            if (trozo.isNotEmpty()) {
                                cajas += letra(trozo.toString(), t, p.cursiva, medir)
                                trozo.clear()
                            }
                            cajas += angulo(c, t, medir)
                        } else trozo.append(c)
                    }
                    if (trozo.isNotEmpty()) cajas += letra(trozo.toString(), t, p.cursiva, medir)
                    fila(cajas)
                }
                else -> letra(p.texto, t, p.cursiva, medir)
            }
            is FormulaRica.Nodo.Fila -> fila(p.partes.map { sigue(it, t) })
            is FormulaRica.Nodo.Fraccion -> {
                val m = if (enLinea) max(t * 0.78, MINIMO) else max(menor(t), t * 0.85)
                fraccion(sigue(p.arriba, m), sigue(p.abajo, m), t)
            }
            is FormulaRica.Nodo.Grande -> deFormula(p.dentro, t, medir, false)
            is FormulaRica.Nodo.ConIndices -> {
                var c = sigue(p.base, t)
                p.abajo?.let { c = subindice(c, sigue(it, menor(t)), t) }
                p.arriba?.let { c = potencia(c, sigue(it, menor(t)), t) }
                c
            }
            is FormulaRica.Nodo.Raiz ->
                raiz(sigue(p.dentro, t), p.indice?.let { sigue(it, menor(menor(t))) }, t)
            is FormulaRica.Nodo.Agrupado -> {
                val d = sigue(p.dentro, t)
                when (p.abre) {
                    "(" -> entreParentesis(d, t, medir)
                    "|" -> entreBarras(d, t)
                    else -> fila(listOf(letra(p.abre, t, false, medir), d, letra(p.cierra, t, false, medir)))
                }
            }
            is FormulaRica.Nodo.Tabla -> {
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
            is FormulaRica.Nodo.Adornado -> adornada(sigue(p.dentro, t), p.adorno, t)
            is FormulaRica.Nodo.Color -> {
                val c = sigue(p.dentro, t)
                TextoRico.colorDeNombre(p.color)?.let { c.tenida(it) } ?: c
            }
            is FormulaRica.Nodo.Negrita -> sigue(p.dentro, t).enNegrita()
            is FormulaRica.Nodo.Recuadro -> recuadro(sigue(p.dentro, t), t)
            is FormulaRica.Nodo.Encima -> encima(sigue(p.base, t), sigue(p.arriba, menor(t)), t)
            is FormulaRica.Nodo.Debajo -> debajo(sigue(p.base, t), sigue(p.abajo, menor(t)), t)
        }
    }
}
