package com.forge.pixpin.ia

import com.forge.pixpin.motor.Ecuacion
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.Formula
import com.forge.pixpin.motor.Graficas
import com.forge.pixpin.motor.MedidaDeTexto
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.getElementBounds
import com.forge.pixpin.motor.randomId
import com.forge.pixpin.motor.randomSeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * **Graficar funciones** (`graficar_funcion`, PC `pixpin-ia/src/funciones.rs`): `y = f(x)` (también a
 * trozos), paramétricas `x(t), y(t)`, polares `r(θ)` y puntos de una tabla, varias a la vez con su
 * leyenda, sobre el **plano cartesiano** del lienzo (o unos ejes con una escala por eje si el plano no
 * vale), con lo que se pide en clase: marcar raíces, extremos e intersecciones, sombrear el área, la
 * tangente y las asíntotas.
 *
 * **Las cuentas las hace el teléfono, no el modelo** ([Cuentas]): el modelo solo escribe la fórmula y lo
 * que quiere ver; el intérprete del lienzo ([Formula], el mismo del PC) la evalúa, y lo calculado vuelve
 * al modelo como aviso para que lo diga sin inventarlo.
 */
object Funciones {
    const val NOMBRE = "graficar_funcion"
    const val MAX_FUNCIONES = 6
    const val LIMITE = 1e6
    const val MUESTRAS = 600
    const val MUESTRAS_T = 1200
    const val MAX_PUNTOS = 200
    const val MAX_MARCAS = 24
    private const val PROPORCION = 4.0

    private fun mal(m: String) = ErrorHerramienta.Parametro(m)

    sealed class Curva {
        data class Explicita(val texto: String, val f: Formula.Compilada) : Curva()
        data class Parametrica(val tx: String, val ty: String, val x: Formula.Compilada, val y: Formula.Compilada) : Curva()
        data class Polar(val texto: String, val r: Formula.Compilada) : Curva()
        data class Puntos(val nombre: String, val puntos: List<Pair<Double, Double>>, val unir: Boolean) : Curva()

        /** El rótulo de la leyenda, como se escribiría en la pizarra. */
        fun rotulo(): String = when (this) {
            is Explicita -> "y = ${sinIgual(texto)}"
            is Parametrica -> "x = ${sinIgual(tx)}, y = ${sinIgual(ty)}"
            is Polar -> "r = ${sinIgual(texto).replace("theta", "θ")}"
            is Puntos -> nombre
        }
    }

    enum class TipoMarca { RAIZ, MAXIMO, MINIMO, INTERSECCION, CORTE_Y }
    data class Marca(val x: Double, val y: Double, val tipo: TipoMarca)
    data class Sombra(val curva: Int, val otra: Int?, val desde: Double, val hasta: Double, val area: Double)
    data class Tangente(val curva: Int, val x: Double, val y: Double, val m: Double)
    sealed class Asintota { data class Vertical(val x: Double) : Asintota(); data class Recta(val m: Double, val b: Double) : Asintota() }

    class Pedido(
        val curvas: List<Curva>, val xDesde: Double, val xHasta: Double, val yDesde: Double, val yHasta: Double,
        val tDesde: Double, val tHasta: Double, val titulo: String,
        val marcas: MutableList<Marca> = mutableListOf(), var sombra: Sombra? = null, var tangente: Tangente? = null,
        val asintotas: MutableList<Asintota> = mutableListOf()
    ) {
        fun formulas() = curvas.filterIsInstance<Curva.Explicita>().map { it.texto }
    }

    private fun numeroEn(v: JsonElement?, k: String): Double? = when {
        v == null || v is JsonNull -> null
        v is JsonPrimitive && !v.isString -> v.doubleOrNull?.takeIf { it.isFinite() } ?: throw mal("«$k» no es un numero")
        v is JsonPrimitive -> {
            val t = v.content.trim().replace(',', '.')
            // «2pi», «π/2»: con el intérprete de fórmulas.
            (t.toDoubleOrNull() ?: Formula.compilar(preparar(t))?.takeIf { it.variables.isEmpty() }?.en(0.0))
                ?.takeIf { it.isFinite() } ?: throw mal("«$k» = «${v.content}» no es un numero")
        }
        else -> throw mal("«$k» tiene que ser un numero")
    }

    /** La fórmula lista para el intérprete: el ángulo θ es `t`, y los signos de la pizarra los de siempre. */
    private fun preparar(f: String) = f.replace('θ', 't').replace('ϑ', 't').replace('φ', 't').replace("theta", "t")
        .replace("\\cdot", "*").replace("\\", "").trim()

    /** `y = x^2` → `x^2`, para el rótulo. */
    fun sinIgual(f: String): String {
        val t = f.trim()
        if (t.isNotEmpty() && t[0].isLetter()) {
            var resto = t.substring(t.offsetByCodePoints(0, 1)).trimStart()
            if (resto.startsWith("(")) { val i = resto.indexOf(')'); if (i >= 0) resto = resto.substring(i + 1); resto = resto.trimStart() }
            if (resto.startsWith("=") && !resto.startsWith("==")) return resto.substring(1).trim()
        }
        return t
    }

    private fun compilarEn(f: String, variable: Char): Formula.Compilada {
        if (f.codePointCount(0, f.length) > 160) throw mal("la formula «${f.take(30)}...» es demasiado larga")
        val c = Formula.compilar(preparar(f)) ?: throw mal("la formula «$f» no se entiende; escribela como x^2 + 3x - 1 o sin(x)/x")
        c.variables.sorted().firstOrNull { it != variable.toString() }?.let { v ->
            throw mal(if (variable == 'x' && v == "t") "«$f» usa t: para una parametrica pon {\"x\":\"...\",\"y\":\"...\"} y para una polar {\"r\":\"...\"}"
            else "«$f» usa la variable «$v»; aqui solo vale $variable")
        }
        return c
    }

    /** Parte por comas o punto y coma de primer nivel (fuera de paréntesis). */
    private fun partirArriba(s: String): List<String> {
        val v = mutableListOf<String>()
        var nivel = 0
        val actual = StringBuilder()
        for (ch in s) {
            when (ch) {
                '(', '[', '{' -> nivel++
                ')', ']', '}' -> nivel--
                ',', ';' -> if (nivel == 0) { v += actual.toString(); actual.clear(); continue }
            }
            actual.append(ch)
        }
        v += actual.toString()
        return v.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Lo que va antes del igual de `x = ...` (una palabra), y lo de detrás. */
    private fun ladoIzquierdo(s: String): Pair<String, String>? {
        val i = s.indexOf('=')
        val (izq0, der) = if (i >= 0) s.substring(0, i) to s.substring(i + 1)
        else s.indexOf(':').takeIf { it >= 0 && s.substring(0, it).trim().codePointCount(0, s.substring(0, it).trim().length) <= 2 }
            ?.let { s.substring(0, it) to s.substring(it + 1) } ?: return null
        val izq = izq0.trim().lowercase()
        val nombre = izq.takeWhile { it.isLetter() }
        return if (nombre.isNotEmpty() && !der.startsWith("=")) nombre to der.trim() else null
    }

    private fun curvaDeTexto(s: String): Curva {
        val t = s.trim()
        if (t.startsWith("(") && t.endsWith(")") && t.length >= 2) {
            val dentro = partirArriba(t.substring(1, t.length - 1))
            if (dentro.size == 2 && t.contains('t')) return parametrica(dentro[0], dentro[1])
        }
        // Las partes de una por partes van con «si»: no son una paramétrica.
        if (!t.contains(" si ")) {
            val trozos = partirArriba(t)
            if (trozos.size == 2) {
                val a = ladoIzquierdo(trozos[0]); val b = ladoIzquierdo(trozos[1])
                if (a != null && b != null) {
                    if (a.first == "x" && b.first == "y") return parametrica(a.second, b.second)
                    if (a.first == "y" && b.first == "x") return parametrica(b.second, a.second)
                }
            }
        }
        ladoIzquierdo(t)?.let { (n, der) -> if (n == "r" || n == "ρ") return polar(der) }
        val usaAngulo = listOf('θ', 'ϑ', 'φ').any { t.contains(it) } || t.contains("theta")
        if (usaAngulo && !t.contains('x')) return polar(sinIgual(t))
        return Curva.Explicita(t, compilarEn(t, 'x'))
    }

    private fun parametrica(fx: String, fy: String) = Curva.Parametrica(fx, fy, compilarEn(fx, 't'), compilarEn(fy, 't'))
    private fun polar(f: String) = Curva.Polar(f, compilarEn(f, 't'))

    private fun numeroRust(v: Double) = if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun tabla(v: JsonElement, que: String): List<Pair<Double, Double>> {
        val l = v as? JsonArray ?: throw mal("«$que» tiene que ser una lista [[x, y], ...]")
        if (l.size > MAX_PUNTOS) throw mal("como mucho $MAX_PUNTOS puntos")
        val salida = mutableListOf<Pair<Double, Double>>()
        for (p in l) {
            fun n(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            val par = when {
                p is JsonArray && p.size == 2 -> n(p[0])?.let { x -> n(p[1])?.let { y -> x to y } }
                p is JsonObject -> n(p["x"])?.let { x -> n(p["y"])?.let { y -> x to y } }
                else -> null
            } ?: throw mal("cada punto de «$que» es [x, y]")
            val (x, y) = par
            if (!(x.isFinite() && y.isFinite()) || abs(x) > LIMITE || abs(y) > LIMITE) throw mal("el punto (${numeroRust(x)}, ${numeroRust(y)}) es enorme o no es un numero")
            salida += par
        }
        if (salida.isEmpty()) throw mal("«$que» esta vacia")
        return salida
    }

    private fun texto(v: JsonElement?) = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun curvaDeObjeto(o: JsonObject): Curva {
        o["puntos"]?.let { p ->
            val nombre = (texto(o["nombre"]) ?: "datos").take(40)
            val unir = (o["unir"] as? JsonPrimitive)?.booleanOrNull ?: false
            return Curva.Puntos(nombre, tabla(p, "puntos"), unir)
        }
        texto(o["r"])?.let { return polar(it) }
        val x = texto(o["x"]); val y = texto(o["y"])
        return when {
            x != null && y != null -> parametrica(x, y)
            y != null -> curvaDeTexto(y)
            x != null -> throw mal("falta «y» para la parametrica de x = $x")
            else -> (texto(o["formula"]) ?: texto(o["funcion"]) ?: texto(o["f"]))?.let(::curvaDeTexto)
                ?: throw mal("cada curva es una formula, {\"x\":..,\"y\":..}, {\"r\":..} o {\"puntos\":[[x,y],...]}")
        }
    }

    /** Las muestras de una curva no explícita, con un hueco (null) donde no existe. */
    private fun muestrasT(c: Curva, t0: Double, t1: Double): List<Pair<Double, Double>?> {
        fun punto(t: Double): Pair<Double, Double>? {
            val (x, y) = when (c) {
                is Curva.Parametrica -> c.x.evaluar(0.0, 0.0, t) to c.y.evaluar(0.0, 0.0, t)
                is Curva.Polar -> { val r = c.r.evaluar(0.0, 0.0, t); r * cos(t) to r * sin(t) }
                else -> return null
            }
            return if (x.isFinite() && y.isFinite() && abs(x) <= LIMITE && abs(y) <= LIMITE) x to y else null
        }
        return when (c) {
            is Curva.Puntos -> c.puntos
            is Curva.Explicita -> emptyList()
            else -> (0..MUESTRAS_T).map { punto(t0 + (t1 - t0) * it / MUESTRAS_T) }
        }
    }

    private fun indices(v: JsonElement?): List<Int> = when (v) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.map { max(it, 0.0).toInt() }
        is JsonPrimitive -> v.doubleOrNull?.let { listOf(max(it, 0.0).toInt()) } ?: emptyList()
        else -> emptyList()
    }

    /** Un dominio que enseñe lo interesante de las `y = f(x)`: raíces, extremos y polos, con el origen dentro. */
    private fun dominioAutomatico(fs: List<Formula.Compilada>): Pair<Double, Double> {
        val puntos = mutableListOf<Double>()
        for (f in fs) {
            val g = { x: Double -> f.en(x) }
            val (r, p) = Cuentas.ceros(g, -50.0, 50.0)
            puntos += r; puntos += p
            puntos += Cuentas.extremos(g, -50.0, 50.0).map { it.first }
        }
        if (puntos.size > 12 * max(fs.size, 1)) return -2 * PI to 2 * PI
        if (puntos.isEmpty()) return -5.0 to 5.0
        val lo = puntos.fold(Double.MAX_VALUE, ::min); val hi = puntos.fold(-Double.MAX_VALUE, ::max)
        val margen = max((hi - lo) * 0.25, 1.0)
        var a = floor(min(lo - margen, 0.0)); var b = kotlin.math.ceil(max(hi + margen, 0.0))
        if (b - a < 4.0) { val falta = (4.0 - (b - a)) / 2.0; a -= falta; b += falta }
        return a to b
    }

    /** Lo que mandan los modelos pequeños para una paramétrica, arreglado (PC `juntar_parametricas`). */
    private fun juntarParametricas(lista: List<JsonElement>): List<JsonElement> {
        fun textoDe(v: JsonElement): String? = when {
            v is JsonPrimitive && v.isString -> v.content.trim()
            v is JsonObject && v.size <= 2 -> v.entries.firstOrNull { (k, _) -> Acciones.normalizar(k) in setOf("funcion", "formula", "f", "expresion", "ecuacion") }
                ?.value?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            else -> null
        }
        val salida = mutableListOf<JsonElement>()
        var i = 0
        while (i < lista.size) {
            val a = textoDe(lista[i]); val b = lista.getOrNull(i + 1)?.let(::textoDe)
            if (a != null && b != null) {
                val la = ladoIzquierdo(a); val lb = ladoIzquierdo(b)
                if (la != null && lb != null && la.first == "x" && lb.first == "y" && (la.second.contains('t') || lb.second.contains('t'))) {
                    salida += buildJsonObject { put("x", la.second); put("y", lb.second) }
                    i += 2; continue
                }
            }
            salida += if (a != null && !(lista[i] is JsonPrimitive && (lista[i] as JsonPrimitive).isString)) JsonPrimitive(a) else lista[i]
            i++
        }
        return salida
    }

    fun validar(args: JsonObject, avisos: MutableList<String>): Pedido {
        val crudo = args["funciones"] ?: args["funcion"] ?: args["formula"] ?: args["curvas"]
        var lista: List<JsonElement> = when {
            crudo is JsonPrimitive && crudo.isString -> crudo.content.split('\n', '|').map { JsonPrimitive(it) }
            crudo is JsonArray -> crudo.toList()
            crudo is JsonObject -> listOf(crudo)
            crudo == null && args["puntos"] != null -> emptyList()
            else -> throw mal("falta «funciones» (una lista de formulas en x, como \"x^2\")")
        }
        // Un objeto escrito como texto (del 2B): el objeto.
        lista = lista.map { v ->
            (v as? JsonPrimitive)?.takeIf { it.isString && it.content.trimStart().startsWith("{") }
                ?.let { runCatching { Json.parseToJsonElement(it.content.trim()) as? JsonObject }.getOrNull() } ?: v
        }
        lista = juntarParametricas(lista)
        val curvas = mutableListOf<Curva>()
        for (v in lista) {
            when {
                v is JsonPrimitive && v.isString && v.content.isBlank() -> {}
                v is JsonPrimitive && v.isString -> curvas += curvaDeTexto(v.content)
                v is JsonObject -> curvas += curvaDeObjeto(v)
                else -> throw mal("cada curva de «funciones» es un texto o un objeto")
            }
        }
        args["puntos"]?.let { p ->
            val nombre = ((args["nombre_puntos"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "datos").take(40)
            val unir = (args["unir"] as? JsonPrimitive)?.booleanOrNull ?: false
            curvas += Curva.Puntos(nombre, tabla(p, "puntos"), unir)
        }
        if (curvas.isEmpty()) throw mal("«funciones» esta vacia")
        if (curvas.size > MAX_FUNCIONES) throw mal("como mucho $MAX_FUNCIONES funciones por grafica")
        val explicitas = curvas.filterIsInstance<Curva.Explicita>().map { it.f }
        val xd = numeroEn(args["x_desde"], "x_desde"); val xh = numeroEn(args["x_hasta"], "x_hasta")
        var (xDesde, xHasta) = when {
            xd == null && xh == null && explicitas.isNotEmpty() -> dominioAutomatico(explicitas)
            xd == null && xh == null -> Double.NaN to Double.NaN
            xd != null && xh != null -> xd to xh
            else -> throw mal("pon los dos limites del dominio: «x_desde» y «x_hasta»")
        }
        val xDado = xDesde.isFinite()
        if (xDado) {
            if (!(xDesde < xHasta)) throw mal("el dominio esta al reves o vacio: x_desde = ${numeroRust(xDesde)} tiene que ser menor que x_hasta = ${numeroRust(xHasta)}")
            if (abs(xDesde) > LIMITE || abs(xHasta) > LIMITE || xHasta - xDesde < 1e-9) throw mal("el dominio tiene que estar entre -${numeroRust(LIMITE)} y ${numeroRust(LIMITE)} y no ser un punto")
        }
        val td = numeroEn(args["t_desde"], "t_desde"); val th = numeroEn(args["t_hasta"], "t_hasta")
        val (tDesde, tHasta) = when {
            td == null && th == null -> 0.0 to 2 * PI
            th == null -> td!! to td + 2 * PI
            td == null -> min(0.0, th - 1e-3) to th
            else -> td to th
        }
        if (!(tDesde < tHasta) || abs(tDesde) > 1e4 || abs(tHasta) > 1e4) throw mal("el intervalo de t esta al reves o es enorme: t_desde = ${numeroRust(tDesde)}, t_hasta = ${numeroRust(tHasta)}")
        // Las muestras, para ver si hay curva y ajustar la ventana.
        val ys = mutableListOf<Double>()
        val xsOtras = mutableListOf<Double>(); val ysOtras = mutableListOf<Double>()
        for (c in curvas) {
            if (c is Curva.Explicita) {
                val antes = ys.size
                for (i in 0..MUESTRAS) {
                    val x = xDesde + (xHasta - xDesde) * i / MUESTRAS
                    val y = c.f.en(x)
                    if (y.isFinite() && abs(y) <= LIMITE) ys += y
                }
                if (ys.size - antes < 2) throw mal("«${c.texto}» no tiene valores reales entre x = ${numeroRust(xDesde)} y x = ${numeroRust(xHasta)}: cambia el dominio")
            } else {
                val m = muestrasT(c, tDesde, tHasta).filterNotNull()
                if (m.isEmpty() || (c !is Curva.Puntos && m.size < 2)) throw mal("«${c.rotulo()}» no tiene valores reales entre t = ${numeroRust(tDesde)} y t = ${numeroRust(tHasta)}")
                xsOtras += m.map { it.first }; ysOtras += m.map { it.second }
            }
        }
        fun ventanaDe(v: List<Double>): Pair<Double, Double> {
            val lo = v.fold(Double.MAX_VALUE, ::min); val hi = v.fold(-Double.MAX_VALUE, ::max)
            val m = max((hi - lo) * 0.1, 0.5)
            return lo - m to hi + m
        }
        if (!xDado) { val (a, b) = ventanaDe(xsOtras); xDesde = min(a, -1.0); xHasta = max(b, 1.0) }
        var (yAutoLo, yAutoHi) = if (ys.isEmpty()) Double.MAX_VALUE to -Double.MAX_VALUE else ventana(ys)
        if (ysOtras.isNotEmpty()) { val (a, b) = ventanaDe(ysOtras); yAutoLo = min(yAutoLo, a); yAutoHi = max(yAutoHi, b) }
        val yd = numeroEn(args["y_desde"], "y_desde"); val yh = numeroEn(args["y_hasta"], "y_hasta")
        var (yDesde, yHasta) = when {
            yd != null && yh != null && yd < yh && abs(yd) <= LIMITE && abs(yh) <= LIMITE -> yd to yh
            yd != null && yh != null -> throw mal("la ventana de y esta al reves o fuera de rango: y_desde = ${numeroRust(yd)}, y_hasta = ${numeroRust(yh)}")
            yd == null && yh == null -> yAutoLo to yAutoHi
            yh == null -> yd!! to max(yAutoHi, yd + 1.0)
            else -> min(yAutoLo, yh - 1.0) to yh
        }
        // Una sola unidad para los dos ejes: si la y se dispara, la curva se corta arriba y abajo.
        val ax = max(abs(xDesde), abs(xHasta)); val ay = max(abs(yDesde), abs(yHasta))
        if (ay > ax * PROPORCION && explicitas.isNotEmpty()) {
            avisos += "la curva sube mucho mas de lo que mide el dominio: se ensena y entre ${com.forge.pixpin.motor.escrito(max(yDesde, -ax * PROPORCION))} y ${com.forge.pixpin.motor.escrito(min(yHasta, ax * PROPORCION))} (pide y_desde/y_hasta para otra ventana)"
            yDesde = max(yDesde, -ax * PROPORCION); yHasta = min(yHasta, ax * PROPORCION)
        }
        val titulo = (texto(args["titulo"]) ?: "").let { s -> s.codePoints().limit(120).toArray().let { String(it, 0, it.size) } }
        val p = Pedido(curvas, xDesde, xHasta, yDesde, yHasta, tDesde, tHasta, titulo)
        analizar(p, args, avisos)
        return p
    }

    private fun explicita(p: Pedido, k: Int, para: String): Formula.Compilada = when (val c = p.curvas.getOrNull(k)) {
        is Curva.Explicita -> c.f
        null -> throw mal("$para: no hay curva $k (se cuentan desde 0; hay ${p.curvas.size})")
        else -> throw mal("$para solo se hace con curvas y = f(x); la curva $k no lo es")
    }

    private fun numero(v: Double) = Pizarra.numero(v)

    /** **Las cuentas**: marcas, área, tangente y asíntotas, y lo calculado dicho al modelo (en avisos). */
    private fun analizar(p: Pedido, args: JsonObject, avisos: MutableList<String>) {
        val a = p.xDesde; val b = p.xHasta
        val explicitas = p.curvas.withIndex().mapNotNull { (k, c) -> (c as? Curva.Explicita)?.let { k to it.f } }
        val pedidas = when (val m = args["marcar"]) {
            is JsonPrimitive -> if (m.isString) m.content.split(',', ' ').map(Acciones::normalizar) else if (m.booleanOrNull == true) listOf("todo") else emptyList()
            is JsonArray -> m.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.map(Acciones::normalizar)
            else -> emptyList()
        }.filter { it.isNotEmpty() }
        fun quiere(raiz: List<String>) = pedidas.any { q -> q == "todo" || raiz.any { q.startsWith(it) } }
        if (pedidas.isNotEmpty() && explicitas.isEmpty()) throw mal("marcar raices, extremos o intersecciones solo se hace con curvas y = f(x)")
        val dichos = mutableListOf<String>()
        for ((k, f) in explicitas) {
            val g = { x: Double -> f.en(x) }
            val nombre = p.curvas[k].rotulo()
            if (quiere(listOf("raic", "raiz", "cero"))) {
                val (r, _) = Cuentas.ceros(g, a, b)
                dichos += if (r.isEmpty()) "$nombre no corta el eje x entre ${numero(a)} y ${numero(b)}"
                else "raices de $nombre: ${r.joinToString(", ") { "x = ${numero(it)}" }}"
                p.marcas += r.map { Marca(it, 0.0, TipoMarca.RAIZ) }
            }
            if (quiere(listOf("extrem", "maxim", "minim", "vertic"))) {
                val e = Cuentas.extremos(g, a, b)
                if (e.isEmpty()) dichos += "$nombre no tiene maximos ni minimos locales entre ${numero(a)} y ${numero(b)}"
                for ((x, esMax) in e) {
                    val y = Cuentas.limpio(g(x))
                    dichos += "${if (esMax) "maximo" else "minimo"} de $nombre en (${numero(x)}, ${numero(y)})"
                    p.marcas += Marca(x, y, if (esMax) TipoMarca.MAXIMO else TipoMarca.MINIMO)
                }
            }
            if (quiere(listOf("corte_y", "corte y", "ordenada")) && a <= 0.0 && 0.0 <= b && g(0.0).isFinite()) {
                val y = Cuentas.limpio(g(0.0))
                dichos += "$nombre corta el eje y en (0, ${numero(y)})"
                p.marcas += Marca(0.0, y, TipoMarca.CORTE_Y)
            }
        }
        if (quiere(listOf("inters", "cortes")) && explicitas.size >= 2) {
            for (i in explicitas.indices) for (j in i + 1 until explicitas.size) {
                val fi = explicitas[i].second; val fj = explicitas[j].second
                val (r, _) = Cuentas.ceros({ x -> fi.en(x) - fj.en(x) }, a, b)
                for (x in r) {
                    val y = Cuentas.limpio(fi.en(x))
                    if (y.isFinite()) { dichos += "se cortan en (${numero(x)}, ${numero(y)})"; p.marcas += Marca(x, y, TipoMarca.INTERSECCION) }
                }
            }
        }
        while (p.marcas.size > MAX_MARCAS) p.marcas.removeAt(p.marcas.size - 1)
        // El área.
        (args["sombrear"] ?: args["area"])?.let { s ->
            var (entre, desde, hasta) = when {
                s is JsonObject -> Triple((s["entre"] ?: s["curvas"] ?: s["curva"])?.let(::indices) ?: listOf(0), numeroEn(s["desde"], "desde"), numeroEn(s["hasta"], "hasta"))
                s is JsonPrimitive && s.booleanOrNull == true -> Triple(listOf(0, 1), null, null)
                else -> Triple(indices(s), null, null)
            }
            if (entre.isEmpty()) entre = listOf(0)
            val f = explicita(p, entre[0], "sombrear un area")
            val g: Formula.Compilada? = entre.getOrNull(1)?.takeIf { it != entre[0] }?.let { explicita(p, it, "sombrear un area") }
            val dif = { x: Double -> f.en(x) - (g?.en(x) ?: 0.0) }
            val (d, h) = if (desde != null && hasta != null) desde to hasta else {
                val (r, _) = Cuentas.ceros(dif, a, b)
                if (r.size >= 2) r.first() to r.last() else (desde ?: a) to (hasta ?: b)
            }
            if (!(d < h)) throw mal("el area va de «desde» = ${numeroRust(d)} a «hasta» = ${numeroRust(h)}: al reves o vacia")
            val area = Cuentas.areaEntre({ x -> f.en(x) }, { x -> g?.en(x) ?: 0.0 }, d, h)
                ?: throw mal("el area entre x = ${numero(d)} y x = ${numero(h)} no es finita (hay un polo o un hueco en medio)")
            dichos += "area sombreada entre x = ${numero(d)} y x = ${numero(h)}: ${numero(area)}"
            p.sombra = Sombra(entre[0], g?.let { entre[1] }, d, h, area)
        }
        // La tangente.
        args["tangente"]?.let { t ->
            val (k, x0) = when {
                t is JsonObject -> max((t["curva"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, 0.0).toInt() to
                    (numeroEn(t["x"] ?: t["en"], "x") ?: throw mal("la tangente necesita «x»: {\"curva\":0,\"x\":1}"))
                t is JsonPrimitive && !t.isString -> 0 to (t.doubleOrNull ?: 0.0)
                else -> throw mal("«tangente» es {\"curva\":0,\"x\":1}")
            }
            val f = explicita(p, k, "la tangente")
            val g = { x: Double -> f.en(x) }
            val y00 = g(x0)
            if (!y00.isFinite()) throw mal("la curva $k no existe en x = ${numero(x0)}: no hay tangente")
            if (!Cuentas.derivable(g, x0)) throw mal("la curva $k no es derivable en x = ${numero(x0)} (tiene un pico): no hay tangente")
            val m = Cuentas.limpio(Cuentas.redondear(Cuentas.derivada(g, x0) * 1e6) / 1e6)
            val y0 = Cuentas.limpio(y00)
            dichos += "tangente en x = ${numero(x0)}: pendiente ${numero(m)}, recta y = ${recta(m, Cuentas.limpio(y0 - m * x0))}"
            p.tangente = Tangente(k, x0, y0, m)
        }
        // Las asíntotas: las verticales si hay polos; las demás si se piden o si la curva tiene polos.
        val pide = (args["asintotas"] as? JsonPrimitive)?.booleanOrNull
        if (pide != false) {
            for ((k, f) in explicitas) {
                val g = { x: Double -> f.en(x) }
                val polos = Cuentas.polos(g, a, b)
                for (x in polos) { dichos += "asintota vertical x = ${numero(x)} (${p.curvas[k].rotulo()})"; p.asintotas += Asintota.Vertical(x) }
                if (pide == true || polos.isNotEmpty()) {
                    val vistas = mutableListOf<Pair<Double, Double>>()
                    for (lado in listOf(1.0, -1.0)) {
                        val mq = Cuentas.asintotaAlInfinito(g, lado) ?: continue
                        if (Cuentas.esLaRecta(g, mq, a, b) || mq in vistas) continue
                        vistas += mq
                        dichos += "asintota ${if (mq.first == 0.0) "horizontal" else "oblicua"} y = ${recta(mq.first, mq.second)} (${p.curvas[k].rotulo()})"
                        p.asintotas += Asintota.Recta(mq.first, mq.second)
                    }
                }
            }
            if (pide == true && p.asintotas.isEmpty()) dichos += "no hay asintotas en lo que se ve"
        }
        if (dichos.isNotEmpty()) avisos += "calculado por la app: ${dichos.joinToString("; ")}"
    }

    /** `y = m x + b` escrito bonito. */
    fun recta(m: Double, b: Double): String {
        val mTxt = when (m) { 0.0 -> ""; 1.0 -> "x"; -1.0 -> "-x"; else -> "${numero(m)}x" }
        return when {
            mTxt.isEmpty() -> numero(b)
            b == 0.0 -> mTxt
            b < 0.0 -> "$mTxt - ${numero(-b)}"
            else -> "$mTxt + ${numero(b)}"
        }
    }

    /** La ventana de la y que deja ver la curva: entre los percentiles 2 y 98, con un margen y el cero si cae cerca. */
    private fun ventana(ys0: List<Double>): Pair<Double, Double> {
        val ys = ys0.sorted()
        val n = ys.size
        var a = ys[n * 2 / 100]; var b = ys[min(n * 98 / 100, n - 1)]
        if (b - a < 1e-9) { a -= 1.0; b += 1.0 }
        val margen = (b - a) * 0.08
        a -= margen; b += margen
        if (a > 0.0 && a < (b - a) * 0.5) a = 0.0
        if (b < 0.0 && -b < (b - a) * 0.5) b = 0.0
        return a to b
    }

    private fun colorDeCurva(k: Int) = Graficas.COLORES_DE_CURVAS[k % Graficas.COLORES_DE_CURVAS.size]

    private fun medidaDe(els: List<Element>): Pair<Double, Double> {
        var x1 = 0.0; var y1 = 0.0
        for (e in els) { val b = getElementBounds(e); x1 = max(x1, b.x2); y1 = max(y1, b.y2) }
        return x1 to y1
    }

    private fun pasoUtil(paso: Double, unidad: Double, minimo: Double): Double {
        if (paso <= 0.0 || unidad <= 0.0) return paso
        var p = paso; var vueltas = 0
        while (p * unidad < minimo && vueltas < 16) { p *= 2.0; vueltas++ }
        return p
    }

    /** **Plano o ejes** (PC `marco_de`): el plano enseña de -a a a con la misma unidad; si la ventana no es casi simétrica, ejes. */
    private fun conPlano(p: Pedido): Boolean {
        val ax = max(abs(p.xDesde), abs(p.xHasta)); val ay = max(abs(p.yDesde), abs(p.yHasta))
        val simetricaX = p.xDesde < 0.0 && p.xHasta > 0.0 && (p.xHasta - p.xDesde) >= 1.5 * ax
        val simetricaY = (p.yHasta - p.yDesde) >= 1.0 * ay
        val llena = ay / ax <= 3.5 && ax / ay <= 3.5
        return simetricaX && simetricaY && llena
    }

    /** **Las piezas** dentro de la caja `(x, y, ancho, alto)` del lienzo de 800×600 (PC `piezas`). */
    fun piezas(p: Pedido, medir: MedidaDeTexto, cx: Double = 60.0, cy: Double = 40.0, cw: Double = 680.0, ch: Double = 520.0): List<Element> {
        val negro = Pizarra.TINTA
        val estilo = Pizarra.estilo(negro, 18.0)
        // La leyenda, para saber cuánto sitio deja.
        val leyenda: List<List<Element>> = p.curvas.withIndex().map { (k, c) ->
            val color = colorDeCurva(k)
            when (c) {
                is Curva.Explicita -> Ecuacion.cajaDeLaEcuacion("y", c.f, estilo.copy(strokeColor = color), medir).elementos(0.0, 0.0, estilo.copy(strokeColor = color)).map(Pizarra::liso)
                is Curva.Puntos -> Pizarra(medir).apply {
                    punto(4.0, 10.0, "", color)
                    texto(c.nombre, 16.0, 0.0, estilo.fontSize * 0.9, color, Pizarra.Alinear.IZQUIERDA)
                }.el
                else -> Pizarra.cajaDeEcuacion(Pizarra.paraVer(c.rotulo().replace(", y = ", "\ny = "), c is Curva.Polar), 0.0, 0.0, estilo.fontSize * 0.9, color, medir).first
            }
        }
        val extraLeyenda = mutableListOf<String>()
        p.sombra?.let { extraLeyenda += "Área ≈ ${numero(it.area)}" }
        p.tangente?.let { extraLeyenda += "tangente: y = ${recta(it.m, Cuentas.limpio(it.y - it.m * it.x))}" }
        val anchoLeyenda = min((leyenda.map { medidaDe(it).first } + extraLeyenda.map { medir(it, 15.0).first }).fold(0.0, ::max), cw * 0.4)
        val altoTitulo = if (p.titulo.isEmpty()) 0.0 else 40.0
        val w = cw - anchoLeyenda - 24.0; val h = ch - altoTitulo
        val ax = max(abs(p.xDesde), abs(p.xHasta)); val ay = max(abs(p.yDesde), abs(p.yHasta))
        val u = min(w / (2.0 * ax), h / (2.0 * ay)).coerceIn(0.05, 400.0)
        val ladoX = 2.0 * ax * u; val ladoY = 2.0 * ay * u
        val plano: Element?
        val ejes: Pizarra.Ejes?
        if (conPlano(p)) {
            val paso = max(Graficas.pasoBonito(2.0 * max(ax, ay)), min(pasoUtil(1.0, u, 34.0), 1e9))
            plano = Element(
                id = randomId(), type = ElementType.PLANO, x = cx + (w - ladoX) / 2.0, y = cy + altoTitulo + (h - ladoY) / 2.0,
                width = ladoX, height = ladoY, seed = randomSeed(), strokeColor = negro, strokeWidth = 1.5, roughness = 0,
                unidad = u, pasoDeNumeros = paso, pasoDeCuadros = paso / if (paso >= 2.0) 2.0 else 1.0
            )
            ejes = null
        } else {
            plano = null
            ejes = Pizarra.Ejes(doubleArrayOf(cx + 34.0, cy + altoTitulo + 14.0, max(w - 54.0, 80.0), max(h - 40.0, 80.0)), p.xDesde, p.xHasta, p.yDesde, p.yHasta)
        }
        fun en(x: Double, y: Double): Pair<Double, Double> = if (plano != null)
            (plano.x + plano.width / 2 + x * u) to (plano.y + plano.height / 2 - y * u) else ejes!!.aLienzo(x, y)
        fun pt(x: Double, y: Double) = en(x, y).let { Pt(it.first, it.second) }
        val xa = if (plano != null) -ax else p.xDesde
        val xb = if (plano != null) ax else p.xHasta
        val cajaMarco = if (plano != null) doubleArrayOf(plano.x, plano.y, plano.width, plano.height) else ejes!!.caja
        fun dentro(x: Double, y: Double) = x >= xa - 1e-9 && x <= xb + 1e-9 && y >= p.yDesde - 1e-9 && y <= p.yHasta + 1e-9
        val salida = mutableListOf<Element>()
        if (plano != null) salida += plano else { val pz = Pizarra(medir); ejes!!.dibujar(pz, "x", "y"); salida += pz.el }
        val pz = Pizarra(medir)
        // El área, debajo de todo.
        p.sombra?.let { s ->
            val f = (p.curvas.getOrNull(s.curva) as? Curva.Explicita)?.f ?: return@let
            val otra = s.otra?.let { p.curvas.getOrNull(it) as? Curva.Explicita }
            val g = { x: Double -> otra?.f?.en(x) ?: 0.0 }
            val n = 200
            fun recorte(y: Double) = y.coerceIn(p.yDesde, p.yHasta)
            fun xk(k: Int) = s.desde + (s.hasta - s.desde) * k / n
            val contorno = (0..n).map { en(xk(it), recorte(f.en(xk(it)))) } + (n downTo 0).map { en(xk(it), recorte(g(xk(it)))) }
            pz.region(contorno, colorDeCurva(s.curva), 0.25)
        }
        // Las asíntotas, a trazos y grises.
        for (a in p.asintotas) {
            when (a) {
                is Asintota.Vertical -> if (a.x in xa..xb) pz.discontinua(listOf(en(a.x, p.yDesde), en(a.x, p.yHasta)), Pizarra.GRIS, 1.5)
                is Asintota.Recta -> {
                    val tramo = (0..100).map { xa + (xb - xa) * it / 100.0 }.filter { dentro(it, a.m * it + a.b) }.map { en(it, a.m * it + a.b) }
                    if (tramo.isNotEmpty()) pz.discontinua(listOf(tramo.first(), tramo.last()), Pizarra.GRIS, 1.5)
                }
            }
        }
        salida += pz.el
        for ((k, c) in p.curvas.withIndex()) {
            val color = colorDeCurva(k)
            val rama = mutableListOf<Pt>()
            fun cerrar() { if (rama.size >= 2) salida += Pizarra.elementoLinea(rama.toList(), 2.5, color); rama.clear() }
            when (c) {
                is Curva.Explicita -> {
                    var anterior: Double? = null
                    for (s in 0..MUESTRAS) {
                        val x = p.xDesde + (p.xHasta - p.xDesde) * s / MUESTRAS
                        val y = c.f.en(x)
                        // Un salto enorme entre dos muestras es un polo: se corta.
                        val salto = anterior?.let { abs(y - it) > (p.yHasta - p.yDesde) * 0.9 } ?: false
                        if (!y.isFinite() || y < p.yDesde || y > p.yHasta || salto) {
                            cerrar()
                            anterior = if (y.isFinite()) y else null
                            if (!y.isFinite() || y < p.yDesde || y > p.yHasta) continue
                        }
                        anterior = y
                        rama += pt(x, y)
                    }
                    cerrar()
                }
                is Curva.Puntos -> {
                    val pp = Pizarra(medir)
                    val dentroP = c.puntos.filter { dentro(it.first, it.second) }
                    if (c.unir) pp.linea(dentroP.map { en(it.first, it.second) }, color, 2.0)
                    for ((x, y) in dentroP) { val (px, py) = en(x, y); pp.punto(px, py, "", color) }
                    salida += pp.el
                }
                else -> {
                    var anterior: Pair<Double, Double>? = null
                    val sx = xb - xa; val sy = p.yHasta - p.yDesde
                    for (m in muestrasT(c, p.tDesde, p.tHasta)) {
                        if (m != null && dentro(m.first, m.second)) {
                            val salto = anterior?.let { abs(m.first - it.first) > sx * 0.5 || abs(m.second - it.second) > sy * 0.5 } ?: false
                            if (salto) cerrar()
                            anterior = m
                            rama += pt(m.first, m.second)
                        } else { cerrar(); anterior = null }
                    }
                    cerrar()
                }
            }
        }
        // La tangente y las marcas, encima.
        val pzT = Pizarra(medir)
        p.tangente?.let { t ->
            val tramo = (0..100).map { xa + (xb - xa) * it / 100.0 }.filter { dentro(it, t.y + t.m * (it - t.x)) }.map { en(it, t.y + t.m * (it - t.x)) }
            pzT.linea(tramo, Pizarra.MORADO, 2.0)
        }
        // Los puntos marcados con sus coordenadas, que no se pisen entre sí ni con las curvas y los ejes.
        val marcados = mutableListOf<Triple<Pair<Double, Double>, String, String>>()
        p.tangente?.let { marcados += Triple(en(it.x, it.y), "(${numero(it.x)}, ${numero(it.y)})", Pizarra.MORADO) }
        for (m in p.marcas.filter { dentro(it.x, it.y) }) {
            val rotulo = "(${numero(m.x)}, ${numero(m.y)})"
            if (marcados.none { it.second == rotulo }) marcados += Triple(en(m.x, m.y), rotulo, negro)
        }
        if (marcados.isNotEmpty()) {
            val tam = 13.0
            val ocupado = mutableListOf<DoubleArray>()
            for (e in salida + pzT.el) {
                when (e.type) {
                    ElementType.LINE, ElementType.ARROW -> e.points?.let { pts -> ocupado += Rotulos.cajasDeLinea(pts.map { (it.x + e.x) to (it.y + e.y) }, 5.0) }
                    ElementType.TEXT -> getElementBounds(e).let { b -> ocupado += doubleArrayOf(b.x1, b.y1, b.width, b.height) }
                    else -> {}
                }
            }
            if (plano != null) {
                ocupado += Rotulos.cajasDeLinea(listOf(en(xa, 0.0), en(xb, 0.0)), 4.0)
                ocupado += Rotulos.cajasDeLinea(listOf(en(0.0, p.yDesde), en(0.0, p.yHasta)), 4.0)
            }
            val pedidos = marcados.map { (a, t, _) -> val (mw, mh) = medir(t, tam); Rotulos.Rotulo(a, mw, mh) }
            val sitios = Rotulos.colocar(pedidos, ocupado, cajaMarco)
            for ((m, xy) in marcados.zip(sitios)) {
                pzT.punto(m.first.first, m.first.second, "", m.third)
                pzT.texto(m.second, xy.first, xy.second, tam, m.third, Pizarra.Alinear.IZQUIERDA)
            }
        }
        salida += pzT.el
        // La leyenda, a la derecha, del color de cada curva.
        var yLey = cajaMarco[1] + 8.0
        val xLey = cajaMarco[0] + cajaMarco[2] + 24.0
        for (l in leyenda) {
            val (_, alto) = medidaDe(l)
            for (e in l) salida += e.copy(x = e.x + xLey, y = e.y + yLey)
            yLey += alto + 12.0
        }
        val pzL = Pizarra(medir)
        for (t in extraLeyenda) { val (_, alto) = pzL.texto(t, xLey, yLey, 15.0, Pizarra.GRIS, Pizarra.Alinear.IZQUIERDA); yLey += alto + 8.0 }
        salida += pzL.el
        if (p.titulo.isNotEmpty()) {
            val tam = 26.0
            // Con x³, π y θ, no «x^3», «pi» ni la t de las polares.
            val titulo = Pizarra.aPizarra(p.titulo, p.curvas.any { it is Curva.Polar })
            val pzTi = Pizarra(medir)
            pzTi.texto(titulo, cx + cw / 2.0, cy, tam, negro, Pizarra.Alinear.CENTRO)
            salida += pzTi.el
        }
        val grupo = randomId()
        return salida.map { it.copy(groupIds = listOf(grupo)) }
    }
}
