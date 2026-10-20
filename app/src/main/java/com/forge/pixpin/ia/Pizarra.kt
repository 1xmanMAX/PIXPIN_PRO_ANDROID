package com.forge.pixpin.ia

import com.forge.pixpin.motor.Arrowhead
import com.forge.pixpin.motor.Ecuacion
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.FillStyle
import com.forge.pixpin.motor.Graficas
import com.forge.pixpin.motor.ItemStyle
import com.forge.pixpin.motor.MedidaDeTexto
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.StrokeStyle
import com.forge.pixpin.motor.randomId
import com.forge.pixpin.motor.randomSeed
import com.forge.pixpin.motormd.Formulas
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * **La pizarra** (PC `pixpin-ia/src/pizarra.rs`): las piezas sueltas con las que se dibujan las
 * gráficas de funciones, los diagramas de física y los problemas en limpio. No es otro dibujo: son
 * elementos del lienzo —líneas, flechas, regiones, puntos, textos y ecuaciones compuestas con las cajas
 * de [Ecuacion]—, lisos (rugosidad 0), que se mueven, se recolorean y salen en el PDF como cualquier
 * trazo. Los [Ejes] con dos escalas son para lo que el plano (una sola unidad) no puede.
 *
 * [medir] mide los textos en la letra de la pizarra (Fraunces): en el teléfono, la de verdad; en las
 * pruebas, la tabla del PC ([AnchosDeLetra]).
 */
class Pizarra(val medir: MedidaDeTexto) {
    val el = mutableListOf<Element>()
    private val pendientes = mutableListOf<Pendiente>()

    private class Pendiente(val ancla: Pair<Double, Double>, val preferida: Pair<Double, Double>, val texto: String, val tam: Double, val color: String)

    enum class Alinear { IZQUIERDA, CENTRO, DERECHA }

    companion object {
        const val LETRA = ItemStyle.FONT_FRAUNCES
        // Los colores del PC (`ColorRgba` redondeado a 8 bits, como lo escribe su Excalidraw).
        const val TINTA = "#1f1f1f"
        const val GRIS = "#73737a"
        const val ROJO = "#e03131"
        const val AZUL = "#1971c2"
        const val VERDE = "#2f9e44"
        const val NARANJA = "#f08c00"
        const val MORADO = "#9c36b5"
        const val TURQUESA = "#0c8599"
        val SERIES = listOf(ROJO, AZUL, VERDE, NARANJA, MORADO, TURQUESA)
        fun colorDeSerie(k: Int) = SERIES[k % SERIES.size]

        /** Un número escrito para una persona: como mucho tres decimales, sin ceros colgando ni «-0». */
        fun numero(v: Double): String {
            if (!v.isFinite()) return "?"
            var r = Cuentas.redondear(v * 1000.0) / 1000.0
            if (r == 0.0) r = 0.0
            var s = String.format(java.util.Locale.ROOT, "%.3f", r)
            while (s.contains('.') && (s.endsWith('0') || s.endsWith('.'))) s = s.dropLast(1)
            return s
        }

        private fun palabraSuelta(c: String, i: Int, w: String): Boolean =
            c.startsWith(w, i) && (i == 0 || !c[i - 1].isLetter()) && (i + w.length >= c.length || !c[i + w.length].isLetter())

        private val GRIEGAS = listOf("pi" to "π", "theta" to "θ", "sqrt" to "√", "alpha" to "α", "phi" to "φ", "mu" to "μ",
            "omega" to "ω", "lambda" to "λ", "Delta" to "Δ", "infinito" to "∞", "inf" to "∞")

        /** **Un título escrito por el modelo, como en la pizarra**: `x^3` → x³, `pi` → π, `<=` → ≤, `*` → ·. */
        fun aPizarra(s: String, polar: Boolean): String {
            val arriba = "⁰¹²³⁴⁵⁶⁷⁸⁹"
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (s[i] == '^') {
                    val llaves = s.getOrNull(i + 1) == '{'
                    var j = if (llaves) i + 2 else i + 1
                    val sub = StringBuilder()
                    var ok = true
                    while (j < s.length && ((llaves && s[j] != '}') || (!llaves && (s[j].isDigit() && s[j] in '0'..'9' || (s[j] == '-' && sub.isEmpty()))))) {
                        when (val d = s[j]) {
                            in '0'..'9' -> sub.append(arriba[d - '0'])
                            '-' -> sub.append('⁻')
                            'n' -> sub.append('ⁿ')
                            else -> ok = false
                        }
                        j++
                    }
                    if (!llaves && sub.isEmpty() && s.getOrNull(i + 1) == 'n') { sub.append('ⁿ'); j = i + 2 }
                    if (ok && sub.isNotEmpty()) { out.append(sub); i = if (llaves) j + 1 else j; continue }
                }
                var cambio: Pair<String, Int>? = null
                for ((w, por) in GRIEGAS) {
                    val conBarra = s[i] == '\\' && palabraSuelta(s, i + 1, w)
                    if (palabraSuelta(s, i, w) || conBarra) { cambio = por to (w.length + if (conBarra) 1 else 0); break }
                }
                if (cambio != null) { out.append(cambio.first); i += cambio.second; continue }
                if (polar && palabraSuelta(s, i, "t")) { out.append('θ'); i++; continue }
                when {
                    s[i] == '<' && s.getOrNull(i + 1) == '=' -> { out.append('≤'); i += 2; continue }
                    s[i] == '>' && s.getOrNull(i + 1) == '=' -> { out.append('≥'); i += 2; continue }
                    s[i] == '*' -> out.append('·')
                    else -> out.append(s[i])
                }
                i++
            }
            return out.toString()
        }

        /** **Una fórmula lista para componer** en la leyenda: sin el `*` entre un número y lo que multiplica; en una polar, θ por `t`. */
        fun paraVer(f: String, polar: Boolean): String {
            val out = StringBuilder()
            for ((i, ch) in f.withIndex()) {
                if (ch == '*') {
                    val antes = f.substring(0, i).lastOrNull { !it.isWhitespace() }
                    val despues = f.substring(i + 1).firstOrNull { !it.isWhitespace() }
                    if (antes != null && (antes in '0'..'9' || antes == ')') && despues != null && (despues.isLetter() || despues == '(')) continue
                }
                if (polar && ch == 't' && (i == 0 || !f[i - 1].isLetter()) && (i + 1 >= f.length || !f[i + 1].isLetter())) { out.append('θ'); continue }
                out.append(ch)
            }
            return out.toString()
        }

        /** Las piezas de una ecuación (LaTeX o escrita normal) en (x, y) y lo que mide (PC `caja_de_ecuacion`). */
        fun cajaDeEcuacion(texto: String, x: Double, y: Double, tam: Double, color: String, medir: MedidaDeTexto): Pair<List<Element>, Pair<Double, Double>> {
            val cajas = EscribirEcuacion.partirLineas(texto).map { Ecuacion.deFormula(Formulas.leer(it), tam, medir, false) }
            val col = Ecuacion.columna(cajas, tam * 0.5)
            return col.elementos(x, y, estilo(color, tam)).map(::liso) to (col.ancho to col.alto)
        }

        fun estilo(color: String, tam: Double) = ItemStyle(strokeColor = color, fontSize = tam, fontFamily = LETRA, roughness = 0)

        /** Liso; los textos de una ecuación con grosor 1, como los del PC (`elemento_texto`). */
        internal fun liso(e: Element) = if (e.type == ElementType.TEXT) e.copy(roughness = 0, strokeWidth = 1.0) else e.copy(roughness = 0)

        /** Un texto partido en renglones que caben en [ancho] (por palabras; los saltos se respetan). */
        fun renglones(t: String, ancho: Double, tam: Double, medir: MedidaDeTexto): List<String> {
            val v = mutableListOf<String>()
            for (parrafo in t.split('\n')) {
                var actual = ""
                for (palabra in parrafo.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                    val prueba = if (actual.isEmpty()) palabra else "$actual $palabra"
                    if (actual.isNotEmpty() && medir(prueba, tam).first > ancho) { v += actual; actual = palabra } else actual = prueba
                }
                v += actual
            }
            while (v.isNotEmpty() && v.last().isEmpty()) v.removeAt(v.size - 1)
            return v
        }

        /** Una línea del lienzo por esos puntos (PC `ecuacion::elemento_linea`): su esquina en el primero. */
        fun elementoLinea(puntos: List<Pt>, grosor: Double, color: String, opacidad: Int = 100): Element {
            val x0 = puntos.minOf { it.x }; val y0 = puntos.minOf { it.y }
            val x1 = puntos.maxOf { it.x }; val y1 = puntos.maxOf { it.y }
            val p0 = puntos.first()
            return Element(
                id = randomId(), type = ElementType.LINE, x = p0.x, y = p0.y, width = max(x1 - x0, 0.0), height = max(y1 - y0, 0.0),
                seed = randomSeed(), strokeColor = color, strokeWidth = grosor, roughness = 0, opacity = opacidad,
                points = puntos.map { Pt(it.x - p0.x, it.y - p0.y) }
            )
        }
    }

    fun medida(t: String, tam: Double) = medir(t, tam)

    /** Una polilínea recta por esos puntos. */
    fun linea(puntos: List<Pair<Double, Double>>, color: String, grosor: Double) {
        if (puntos.size < 2) return
        el += elementoLinea(puntos.map { Pt(it.first, it.second) }, grosor, color)
    }

    /** Lo mismo, a trazos (asíntotas, componentes, guías). */
    fun discontinua(puntos: List<Pair<Double, Double>>, color: String, grosor: Double) {
        val antes = el.size
        linea(puntos, color, grosor)
        if (el.size > antes) el[el.size - 1] = el.last().copy(strokeStyle = StrokeStyle.DASHED)
    }

    /** Una flecha de [a] a [b] (un vector, una fuerza). */
    fun flecha(a: Pair<Double, Double>, b: Pair<Double, Double>, color: String, grosor: Double) {
        el += Element(
            id = randomId(), type = ElementType.ARROW, x = a.first, y = a.second,
            width = abs(b.first - a.first), height = abs(b.second - a.second), seed = randomSeed(),
            strokeColor = color, strokeWidth = grosor, roughness = 0,
            points = listOf(Pt(0.0, 0.0), Pt(b.first - a.first, b.second - a.second)),
            startArrowhead = null, endArrowhead = Arrowhead.ARROW
        )
    }

    fun flechaDiscontinua(a: Pair<Double, Double>, b: Pair<Double, Double>, color: String, grosor: Double) {
        flecha(a, b, color, grosor)
        el[el.size - 1] = el.last().copy(strokeStyle = StrokeStyle.DASHED)
    }

    /** Un texto de una línea; devuelve lo que mide. */
    fun texto(t: String, x: Double, y: Double, tam: Double, color: String, alinear: Alinear, cursiva: Boolean = false): Pair<Double, Double> {
        if (t.isEmpty()) return 0.0 to 0.0
        val (w, h) = medir(t, tam)
        val xx = when (alinear) { Alinear.IZQUIERDA -> x; Alinear.CENTRO -> x - w / 2; Alinear.DERECHA -> x - w }
        el += Element(
            id = randomId(), type = ElementType.TEXT, x = xx, y = y, width = w, height = h, seed = randomSeed(),
            strokeColor = color, strokeWidth = 1.0, roughness = 0, text = t, fontSize = tam, fontFamily = LETRA, cursiva = cursiva
        )
        return w to h
    }

    /** Un párrafo partido en renglones de [ancho] como mucho; devuelve el alto que ocupa. */
    fun parrafo(t: String, x: Double, y: Double, ancho: Double, tam: Double, color: String): Double {
        var alto = 0.0
        for (r in renglones(t, ancho, tam, medir)) {
            val (_, h) = texto(r, x, y + alto, tam, color, Alinear.IZQUIERDA)
            alto += max(h, tam * 1.2)
        }
        return alto
    }

    private fun caja(tipo: ElementType, c: DoubleArray, color: String, relleno: String?, grosor: Double) {
        el += Element(
            id = randomId(), type = tipo, x = c[0], y = c[1], width = c[2], height = c[3], seed = randomSeed(),
            strokeColor = color, backgroundColor = relleno ?: Element.TRANSPARENT, fillStyle = FillStyle.SOLID,
            strokeWidth = grosor, roughness = 0
        )
    }

    /** Un rectángulo, con relleno liso si se da. */
    fun rectangulo(c: DoubleArray, color: String, relleno: String?, grosor: Double) = caja(ElementType.RECTANGLE, c, color, relleno, grosor)

    fun elipse(c: DoubleArray, color: String, relleno: String?, grosor: Double) = caja(ElementType.ELLIPSE, c, color, relleno, grosor)

    /** Una mancha rellena (el área entre dos curvas), translúcida. */
    fun region(contorno: List<Pair<Double, Double>>, color: String, opacidad: Double) {
        if (contorno.size < 3) return
        val x0 = contorno.minOf { it.first }; val y0 = contorno.minOf { it.second }
        val x1 = contorno.maxOf { it.first }; val y1 = contorno.maxOf { it.second }
        el += Element(
            id = randomId(), type = ElementType.REGION, x = x0, y = y0, width = x1 - x0, height = y1 - y0, seed = randomSeed(),
            strokeColor = color, backgroundColor = color, fillStyle = FillStyle.SOLID, strokeWidth = 0.5, roughness = 0,
            opacity = Math.round(opacidad * 100).toInt(),
            points = contorno.map { Pt(it.first - x0, it.second - y0) }
        )
    }

    /** Un polígono cerrado sin relleno (un bloque girado, un plano). */
    fun poligono(puntos: List<Pair<Double, Double>>, color: String, grosor: Double) {
        linea(if (puntos.isEmpty()) puntos else puntos + puntos.first(), color, grosor)
    }

    /** Un punto marcado con su rótulo al lado (una raíz, un máximo). */
    fun punto(x: Double, y: Double, rotulo: String, color: String) = puntoCon(x, y, rotulo, color, -PI / 4)

    /** Lo mismo con el rótulo hacia [angulo] (radianes de pantalla: 0 a la derecha, -π/2 arriba). */
    fun puntoCon(x: Double, y: Double, rotulo: String, color: String, angulo: Double) {
        el += Element(
            id = randomId(), type = ElementType.PUNTO, x = x, y = y, width = 0.0, height = 0.0, seed = randomSeed(),
            strokeColor = color, backgroundColor = color, strokeWidth = 2.0, roughness = 0, text = rotulo,
            etiquetaAngulo = angulo.toFloat().toDouble(), etiquetaRadio = 12.0
        )
    }

    /** Una ecuación compuesta con su esquina en (x, y); devuelve lo que mide. */
    fun ecuacion(texto: String, x: Double, y: Double, tam: Double, color: String): Pair<Double, Double> {
        val (els, medida) = cajaDeEcuacion(texto, x, y, tam, color, medir)
        el += els
        return medida
    }

    /** Un arco de circunferencia (el de un ángulo), en grados como en clase: desde el eje x, antihorario. */
    fun arco(c: Pair<Double, Double>, radio: Double, desde: Double, hasta: Double, color: String, grosor: Double) {
        val n = 24
        linea((0..n).map { k -> Math.toRadians(desde + (hasta - desde) * k / n) }.map { a -> c.first + radio * cos(a) to c.second - radio * sin(a) }, color, grosor)
    }

    /** Una flecha que sale de [origen] con ese [largo] y [angulo] (grados), con su rótulo en la punta. Devuelve la punta. */
    fun vector(origen: Pair<Double, Double>, largo: Double, angulo: Double, rotulo: String, color: String, discontinua: Boolean): Pair<Double, Double> {
        val a = Math.toRadians(angulo)
        val punta = origen.first + largo * cos(a) to origen.second - largo * sin(a)
        if (discontinua) flechaDiscontinua(origen, punta, color, 2.0) else flecha(origen, punta, color, 2.5)
        rotuloEn(punta, a, rotulo, color)
        return punta
    }

    /** Un rótulo junto a una punta que apunta hacia [a] (radianes): fuera de la flecha. */
    fun rotuloEn(punta: Pair<Double, Double>, a: Double, rotulo: String, color: String) {
        if (rotulo.isEmpty()) return
        val tam = 15.0
        val (w, h) = medir(rotulo, tam)
        val dx = cos(a); val dy = -sin(a)
        val x = punta.first + dx * 10.0 + if (dx < -0.3) -w else if (dx > 0.3) 0.0 else -w / 2
        val y = punta.second + dy * 10.0 + if (dy < -0.3) -h else if (dy > 0.3) 0.0 else -h / 2
        pendientes += Pendiente(punta, x to y, rotulo, tam, color)
    }

    /** **Pone los rótulos pendientes sin que se pisen** ([Rotulos.colocar]) con nada de lo dibujado. */
    fun colocarRotulos() {
        if (pendientes.isEmpty()) return
        val ocupado = mutableListOf<DoubleArray>()
        for (e in el) {
            if ((e.type == ElementType.LINE || e.type == ElementType.ARROW) && e.points != null) {
                ocupado += Rotulos.cajasDeLinea(e.points!!.map { (it.x + e.x) to (it.y + e.y) }, 6.0)
            } else if (listOf(e.x, e.y, e.width, e.height).all { it.isFinite() }) {
                ocupado += doubleArrayOf(e.x, e.y, e.width, e.height)
            }
        }
        val ps = pendientes.toList(); pendientes.clear()
        val pedidos = ps.map { p -> val (w, h) = medir(p.texto, p.tam); Rotulos.Rotulo(p.ancla, w, h, p.preferida) }
        for ((p, xy) in ps.zip(Rotulos.colocar(pedidos, ocupado, null))) texto(p.texto, xy.first, xy.second, p.tam, p.color, Alinear.IZQUIERDA)
    }

    /** Mueve todo lo dibujado desde [desde]. */
    fun moverDesde(desde: Int, dx: Double, dy: Double) {
        for (i in desde until el.size) el[i] = el[i].copy(x = el[i].x + dx, y = el[i].y + dy)
    }

    /**
     * **Unos ejes con una escala por eje** (PC `Ejes`), para gráficas de física (x-t, v-t...): la caja en
     * el lienzo y la ventana de valores, con marcas a un paso bonito.
     */
    data class Ejes(val caja: DoubleArray, val x0: Double, val x1: Double, val y0: Double, val y1: Double) {
        companion object {
            /** Ventana con los dos ceros dentro si caen cerca, y un poco de margen arriba y abajo. */
            fun nuevos(caja: DoubleArray, x: Pair<Double, Double>, y: Pair<Double, Double>): Ejes {
                var y0 = min(y.first, 0.0); var y1 = max(y.second, 0.0)
                if (abs(y1 - y0) < 1e-9) { y0 -= 1.0; y1 += 1.0 }
                val m = (y1 - y0) * 0.08
                if (y0 < 0.0) y0 -= m
                y1 += m
                return Ejes(caja, x.first, if (x.second > x.first) x.second else x.first + 1.0, y0, y1)
            }
        }

        fun aLienzo(x: Double, y: Double): Pair<Double, Double> {
            val cx = caja[0]; val cy = caja[1]; val w = caja[2]; val h = caja[3]
            return (cx + (x - x0) / (x1 - x0) * w) to (cy + h - (y - y0) / (y1 - y0) * h)
        }

        /** Los ejes con flechas, marcas, números y el nombre de cada uno. */
        fun dibujar(pz: Pizarra, nombreX: String, nombreY: String) {
            val cx = caja[0]; val cy = caja[1]; val w = caja[2]; val h = caja[3]
            val yCero = aLienzo(x0, 0.0.coerceIn(y0, y1)).second
            val xCero = aLienzo(0.0.coerceIn(x0, x1), y0).first
            pz.flecha(cx to yCero, cx + w + 10.0 to yCero, TINTA, 1.5)
            pz.flecha(xCero to cy + h, xCero to cy - 10.0, TINTA, 1.5)
            val tam = 12.0
            fun pasoQueCabe(rango: Double, pixeles: Double): Double {
                var paso = Graficas.pasoBonito(rango)
                while (paso / rango * pixeles < 24.0 && paso < rango) paso *= 2.0
                return paso
            }
            val pasoX = pasoQueCabe(x1 - x0, w)
            val pasoY = pasoQueCabe(y1 - y0, h)
            var x = ceil(x0 / pasoX) * pasoX
            while (x <= x1 + 1e-9) {
                if (abs(x) > 1e-9) {
                    val px = aLienzo(x, 0.0).first
                    pz.linea(listOf(px to yCero - 4.0, px to yCero + 4.0), TINTA, 1.0)
                    pz.texto(numero(x), px, yCero + 6.0, tam, GRIS, Alinear.CENTRO)
                }
                x += pasoX
            }
            var y = ceil(y0 / pasoY) * pasoY
            while (y <= y1 + 1e-9) {
                if (abs(y) > 1e-9) {
                    val py = aLienzo(0.0, y).second
                    pz.linea(listOf(xCero - 4.0 to py, xCero + 4.0 to py), TINTA, 1.0)
                    pz.texto(numero(y), xCero - 6.0, py - tam * 0.65, tam, GRIS, Alinear.DERECHA)
                }
                y += pasoY
            }
            pz.texto(nombreX, cx + w + 14.0, yCero - 9.0, 15.0, TINTA, Alinear.IZQUIERDA, cursiva = true)
            pz.texto(nombreY, xCero + 6.0, cy - 24.0, 15.0, TINTA, Alinear.IZQUIERDA, cursiva = true)
        }

        /** Una curva `y(x)` muestreada, cortada donde se sale de la ventana. */
        fun curva(pz: Pizarra, f: (Double) -> Double, color: String, grosor: Double) {
            val n = 200
            val rama = mutableListOf<Pair<Double, Double>>()
            for (i in 0..n) {
                val x = x0 + (x1 - x0) * i / n
                val y = f(x)
                if (!y.isFinite() || y < y0 - 1e-9 || y > y1 + 1e-9) { pz.linea(rama.toList(), color, grosor); rama.clear(); continue }
                rama += aLienzo(x, y)
            }
            pz.linea(rama, color, grosor)
        }

        override fun equals(other: Any?) = other is Ejes && caja.contentEquals(other.caja) && x0 == other.x0 && x1 == other.x1 && y0 == other.y0 && y1 == other.y1
        override fun hashCode() = listOf(caja.toList(), x0, x1, y0, y1).hashCode()
    }
}

/**
 * **Colocar rótulos sin que se pisen** (PC `rotulos.rs`): cada rótulo tiene su ancla y su medida; se
 * prueban sitios alrededor, de más cerca a más lejos, y se queda el primero que no pisa nada.
 */
object Rotulos {
    data class Rotulo(val ancla: Pair<Double, Double>, val ancho: Double, val alto: Double, val preferida: Pair<Double, Double>? = null)

    fun solape(a: DoubleArray, b: DoubleArray): Double {
        val w = min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0])
        val h = min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1])
        return if (w > 0 && h > 0) w * h else 0.0
    }

    /** Las cajas pequeñas que tapan una polilínea: una por tramo (troceados los largos). */
    fun cajasDeLinea(puntos: List<Pair<Double, Double>>, grosor: Double): List<DoubleArray> {
        val v = mutableListOf<DoubleArray>()
        for ((a, b) in puntos.zipWithNext()) {
            val largo = hypot(b.first - a.first, b.second - a.second)
            val n = max(ceil(largo / max(grosor * 2.0, 4.0)), 1.0).toInt()
            for (k in 0 until n) {
                val s0 = k.toDouble() / n; val s1 = (k + 1).toDouble() / n
                val p = (a.first + (b.first - a.first) * s0) to (a.second + (b.second - a.second) * s0)
                val q = (a.first + (b.first - a.first) * s1) to (a.second + (b.second - a.second) * s1)
                v += doubleArrayOf(min(p.first, q.first) - grosor / 2, min(p.second, q.second) - grosor / 2, abs(p.first - q.first) + grosor, abs(p.second - q.second) + grosor)
            }
        }
        return v
    }

    private fun candidatos(r: Rotulo): List<DoubleArray> {
        val (ax, ay) = r.ancla
        val w = r.ancho; val h = r.alto
        val v = mutableListOf<DoubleArray>()
        r.preferida?.let { v += doubleArrayOf(it.first, it.second, w, h) }
        for (d in listOf(8.0, 16.0, 28.0, 44.0, 66.0)) {
            val k = d * 0.7
            v += doubleArrayOf(ax + k, ay - k - h, w, h)
            v += doubleArrayOf(ax - k - w, ay - k - h, w, h)
            v += doubleArrayOf(ax + k, ay + k, w, h)
            v += doubleArrayOf(ax - k - w, ay + k, w, h)
            v += doubleArrayOf(ax + d, ay - h / 2, w, h)
            v += doubleArrayOf(ax - d - w, ay - h / 2, w, h)
            v += doubleArrayOf(ax - w / 2, ay - d - h, w, h)
            v += doubleArrayOf(ax - w / 2, ay + d, w, h)
        }
        return v
    }

    /** **La esquina de cada rótulo**, en el orden en que llegan. [dentro]: los sitios fuera de esa caja, al final. */
    fun colocar(rotulos: List<Rotulo>, ocupado: List<DoubleArray>, dentro: DoubleArray?): List<Pair<Double, Double>> {
        val puestos = mutableListOf<DoubleArray>()
        val anclas = rotulos.map { doubleArrayOf(it.ancla.first - 5, it.ancla.second - 5, 10.0, 10.0) }
        val salida = ArrayList<Pair<Double, Double>>(rotulos.size)
        for (r in rotulos) {
            var mejor: Pair<Double, DoubleArray>? = null
            for (c in candidatos(r)) {
                val fuera = dentro != null && (c[0] < dentro[0] || c[1] < dentro[1] || c[0] + c[2] > dentro[0] + dentro[2] || c[1] + c[3] > dentro[1] + dentro[3])
                val pisa = (puestos + anclas + ocupado).sumOf { solape(c, it) } + if (fuera) c[2] * c[3] else 0.0
                if (pisa <= 1e-6) { mejor = 0.0 to c; break }
                if (mejor == null || pisa < mejor.first) mejor = pisa to c
            }
            val c = mejor?.second ?: doubleArrayOf(r.ancla.first, r.ancla.second, r.ancho, r.alto)
            puestos += c
            salida += c[0] to c[1]
        }
        return salida
    }
}
