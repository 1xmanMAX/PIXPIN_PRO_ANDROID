package com.forge.pixpin.ia

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * **Ilustraciones por SVG** (`dibujar_svg`; `svg.rs` del PC, portado tal cual): el modelo escribe
 * un SVG y aquí se convierte en figuras normales del lienzo —manchas rellenas con sus agujeros,
 * líneas, rectángulos, elipses y textos—, editables y sincronizables como cualquier trazo.
 *
 * Entiende `path` (M L H V C S Q T A Z, absolutos y relativos), `rect` (redondeado), `circle`,
 * `ellipse`, `line`, `polyline`, `polygon`, `text`/`tspan`, `g` y `svg` anidados con `transform`,
 * `use` de un `#id` del mismo SVG, `<style>` por clase, tipo e id, y los degradados como su color
 * medio.
 *
 * **Lo que llega es hostil**: sin DTD ni entidades (se rechaza un `<!DOCTYPE`/`<!ENTITY` antes de
 * leer), con topes de bytes, nodos, figuras, puntos y anidamiento; `script`, `foreignObject`,
 * `image` y lo que apunte fuera se ignoran (nunca se ejecuta ni se baja nada).
 */
object Svg {
    const val NOMBRE = "dibujar_svg"
    const val MAX_BYTES = 200_000
    const val MAX_FIGURAS = 400
    const val MAX_PUNTOS = 60_000
    const val MAX_PROFUNDIDAD = 32
    private const val MAX_NODOS = 20_000
    private const val XLINK = "http://www.w3.org/1999/xlink"
    private const val TAU = 2 * Math.PI

    private fun mal(m: String) = ErrorHerramienta.Parametro(m)

    sealed class Modo {
        object Liso : Modo()
        object AMano : Modo()
        data class Lapiz(val material: String, val dureza: String?) : Modo()
    }

    data class Pintura(val relleno: String?, val trazo: String?, var grosor: Double, val opacidad: Double, val discontinuo: Boolean)

    sealed class Pieza {
        data class Caja(val tipo: String, val caja: DoubleArray, val redondo: Boolean, val pinta: Pintura) : Pieza()
        data class Forma(val anillos: List<Pair<List<Pair<Double, Double>>, Boolean>>, val pinta: Pintura) : Pieza()
        data class Texto(val x: Double, val y: Double, val texto: String, val tam: Double, val alinear: String, val color: String, val opacidad: Double) : Pieza()
    }

    data class Dibujo(val piezas: List<Pieza>, val modo: Modo)

    // ------------------------------------------------------------------------------ validar

    fun validar(args: JsonObject, avisos: MutableList<String>): Dibujo {
        val texto = (args["svg"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw mal("falta «svg» (el dibujo en SVG, de <svg> a </svg>)")
        val caja = when (val c = args["caja"]) {
            null, JsonNull -> null
            is JsonArray -> {
                val n = c.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull }.filter { it.isFinite() }
                if (n.size == 4 && n[2] >= 10.0 && n[3] >= 10.0 && abs(n[0]) < 2000.0 && abs(n[1]) < 2000.0 && n[2] < 4000.0 && n[3] < 4000.0) n.toDoubleArray()
                else throw mal("«caja» tiene que ser [x, y, ancho, alto] con ancho y alto de 10 o mas")
            }
            else -> throw mal("«caja» tiene que ser [x, y, ancho, alto]")
        }
        val dureza = (args["dureza"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { Herramientas.dureza(it) }
        val modo = when (val t = (args["trazo"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(Acciones::normalizar)) {
            null, "liso", "vectorial", "limpio" -> Modo.Liso
            "a_mano", "a mano", "mano", "boceto" -> Modo.AMano
            "lapiz", "lapiz2b" -> Modo.Lapiz(if (dureza != null) "cuadritos" else "lapiz2b", dureza)
            "grafito", "cuadritos" -> Modo.Lapiz("cuadritos", dureza)
            else -> throw mal("«trazo» = «$t» no existe; usa liso, a_mano, lapiz o grafito")
        }
        return Dibujo(leer(texto, caja ?: doubleArrayOf(60.0, 40.0, Herramientas.ANCHO_LIENZO - 120.0, Herramientas.ALTO_LIENZO - 80.0), avisos), modo)
    }

    /** **Lee un SVG** y lo encaja en [destino] (del lienzo de 800x600), centrado y sin deformarlo. */
    fun leer(texto: String, destino: DoubleArray, avisos: MutableList<String>): List<Pieza> {
        val bytes = texto.toByteArray().size
        if (bytes > MAX_BYTES) throw mal("el SVG ocupa ${bytes / 1024} KB; el maximo es ${MAX_BYTES / 1024} KB: simplificalo (menos puntos, menos figuras)")
        val desde = texto.indexOf("<svg").takeIf { it >= 0 } ?: throw mal("no hay ningun <svg> en «svg»")
        val hasta = texto.lastIndexOf("</svg>").let { if (it >= 0) it + 6 else texto.length }
        if (hasta <= desde) throw mal("el SVG no se cierra con </svg>")
        val trozo = texto.substring(desde, hasta)
        val doc = parsear(trozo)
        val raiz = doc.documentElement
        if (nombre(raiz) != "svg") throw mal("el elemento de fuera tiene que ser <svg>")
        val todos = descendientes(raiz)
        if (todos.size > MAX_NODOS) throw mal("el SVG no es XML valido (demasiados nodos); sin DOCTYPE ni entidades")
        val estilos = Hojas.de(todos)
        val degradados = degradados(todos)
        val ids = HashMap<String, Element>()
        for (n in todos) n.getAttribute("id").takeIf { it.isNotEmpty() }?.let { ids[it] = n }
        val vista: DoubleArray? = attr(raiz, "viewBox")?.let { v ->
            val n = numerosSueltos(v)
            if (n.size == 4 && n[2] > 0.0 && n[3] > 0.0) n.toDoubleArray() else null
        } ?: run {
            val w = attr(raiz, "width")?.let(::longitud)
            val h = attr(raiz, "height")?.let(::longitud)
            if (w != null && h != null && w > 0.0 && h > 0.0) doubleArrayOf(0.0, 0.0, w, h) else null
        }
        val lector = Recorrido(estilos, degradados, ids)
        fun escalaDe(v: DoubleArray) = min(destino[2] / v[2], destino[3] / v[3])
        val v = vista ?: run {
            lector.recorrer(raiz, Matriz.IDENTIDAD, Estilo.raiz(), 0)
            val c = cajaDe(lector.piezas) ?: throw mal("el SVG no dibuja nada visible")
            lector.piezas.clear(); lector.puntos = 0
            doubleArrayOf(c[0], c[1], max(c[2], 1.0), max(c[3], 1.0))
        }
        val escala = escalaDe(v)
        if (!escala.isFinite() || escala <= 0.0) throw mal("el viewBox del SVG no tiene medida")
        lector.detalle = escala / 4.0
        lector.recorrer(raiz, Matriz.IDENTIDAD, Estilo.raiz(), 0)
        if (lector.ignorados.isNotEmpty()) avisos += "del SVG se ignoro: ${lector.ignorados.toSortedSet().joinToString(", ")}"
        if (lector.piezas.isEmpty()) throw mal("el SVG no dibuja nada visible (¿todo con fill=\"none\" y sin stroke?)")
        val ox = destino[0] + (destino[2] - v[2] * escala) / 2.0 - v[0] * escala
        val oy = destino[1] + (destino[3] - v[3] * escala) / 2.0 - v[1] * escala
        fun a(p: Pair<Double, Double>) = (ox + p.first * escala) to (oy + p.second * escala)
        return lector.piezas.map { p ->
            when (p) {
                is Pieza.Caja -> {
                    val (x, y) = a(p.caja[0] to p.caja[1])
                    Pieza.Caja(p.tipo, doubleArrayOf(x, y, p.caja[2] * escala, p.caja[3] * escala), p.redondo, p.pinta.copy(grosor = (p.pinta.grosor * escala).coerceIn(0.5, 40.0)))
                }
                is Pieza.Forma -> Pieza.Forma(
                    p.anillos.map { (pts, cerrado) -> simplificar(pts.map(::a), 0.35) to cerrado }.filter { it.first.size >= 2 },
                    p.pinta.copy(grosor = (p.pinta.grosor * escala).coerceIn(0.5, 40.0))
                )
                is Pieza.Texto -> { val (x, y) = a(p.x to p.y); p.copy(x = x, y = y, tam = (p.tam * escala).coerceIn(4.0, 400.0)) }
            }
        }.filter { !(it is Pieza.Forma && it.anillos.isEmpty()) }
    }

    private fun parsear(trozo: String): org.w3c.dom.Document {
        // Sin DTD ni entidades: lo que llega es texto de un modelo (nada de bombas de XML).
        if (trozo.contains("<!DOCTYPE", ignoreCase = true) || trozo.contains("<!ENTITY", ignoreCase = true))
            throw mal("el SVG no es XML valido (lleva DOCTYPE o entidades); sin DOCTYPE ni entidades")
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isExpandEntityReferences = false
        for ((k, v) in listOf("http://apache.org/xml/features/disallow-doctype-decl" to true, "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false)) runCatching { f.setFeature(k, v) }
        return try {
            val b = f.newDocumentBuilder()
            b.setErrorHandler(null)
            b.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
            b.parse(org.xml.sax.InputSource(java.io.StringReader(trozo)))
        } catch (e: Exception) {
            throw mal("el SVG no es XML valido (${e.message?.take(120) ?: "no se lee"}); sin DOCTYPE ni entidades")
        }
    }

    private fun nombre(n: Node) = (n.localName ?: n.nodeName).substringAfter(':')
    private fun attr(n: Element, k: String): String? = n.getAttributeNode(k)?.value
    private fun hijos(n: Node): List<Element> = (0 until n.childNodes.length).map { n.childNodes.item(it) }.filterIsInstance<Element>()
    private fun descendientes(n: Element): List<Element> {
        val out = ArrayList<Element>()
        fun ir(x: Element) { out += x; hijos(x).forEach(::ir) }
        ir(n)
        return out
    }

    private fun cajaDe(piezas: List<Pieza>): DoubleArray? {
        var c: DoubleArray? = null
        fun meter(x: Double, y: Double) {
            c = c?.let { doubleArrayOf(min(it[0], x), min(it[1], y), max(it[2], x), max(it[3], y)) } ?: doubleArrayOf(x, y, x, y)
        }
        for (p in piezas) when (p) {
            is Pieza.Caja -> { meter(p.caja[0], p.caja[1]); meter(p.caja[0] + p.caja[2], p.caja[1] + p.caja[3]) }
            is Pieza.Forma -> p.anillos.flatMap { it.first }.forEach { meter(it.first, it.second) }
            is Pieza.Texto -> { meter(p.x, p.y); meter(p.x + p.tam * 0.6 * p.texto.codePointCount(0, p.texto.length), p.y + p.tam) }
        }
        return c?.let { doubleArrayOf(it[0], it[1], it[2] - it[0], it[3] - it[1]) }
    }

    // ------------------------------------------------------------------ matrices y números

    private class Matriz(val m: DoubleArray) {
        fun por(o: Matriz): Matriz {
            val (a, b, c, d, e) = m; val f = m[5]
            val (g, h, i, j, k) = o.m; val l = o.m[5]
            return Matriz(doubleArrayOf(a * g + c * h, b * g + d * h, a * i + c * j, b * i + d * j, a * k + c * l + e, b * k + d * l + f))
        }
        fun aplicar(p: Pair<Double, Double>) = (m[0] * p.first + m[2] * p.second + m[4]) to (m[1] * p.first + m[3] * p.second + m[5])
        fun escala() = sqrt(abs(m[0] * m[3] - m[1] * m[2]))
        fun derecha() = abs(m[1]) < 1e-9 && abs(m[2]) < 1e-9 && m[0] > 0.0 && m[3] > 0.0

        companion object {
            val IDENTIDAD = Matriz(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
            fun trasladar(x: Double, y: Double) = Matriz(doubleArrayOf(1.0, 0.0, 0.0, 1.0, x, y))
        }
    }

    private fun transformacion(t: String): Matriz {
        var m = Matriz.IDENTIDAD
        var resto = t
        while (true) {
            val abre = resto.indexOf('(').takeIf { it >= 0 } ?: break
            val nombre = resto.substring(0, abre).trim { it.isWhitespace() || it == ',' }.lowercase()
            val cierra = resto.indexOf(')', abre).takeIf { it >= 0 } ?: break
            val n = numerosSueltos(resto.substring(abre + 1, cierra))
            fun r(g: Double) = Math.toRadians(g)
            val otra = when {
                nombre == "matrix" && n.size == 6 -> Matriz(n.toDoubleArray())
                nombre == "translate" && n.size == 1 -> Matriz.trasladar(n[0], 0.0)
                nombre == "translate" && n.size == 2 -> Matriz.trasladar(n[0], n[1])
                nombre == "scale" && n.size == 1 -> Matriz(doubleArrayOf(n[0], 0.0, 0.0, n[0], 0.0, 0.0))
                nombre == "scale" && n.size == 2 -> Matriz(doubleArrayOf(n[0], 0.0, 0.0, n[1], 0.0, 0.0))
                nombre == "rotate" && n.size == 1 -> Matriz(doubleArrayOf(cos(r(n[0])), sin(r(n[0])), -sin(r(n[0])), cos(r(n[0])), 0.0, 0.0))
                nombre == "rotate" && n.size == 3 -> Matriz.trasladar(n[1], n[2]).por(Matriz(doubleArrayOf(cos(r(n[0])), sin(r(n[0])), -sin(r(n[0])), cos(r(n[0])), 0.0, 0.0))).por(Matriz.trasladar(-n[1], -n[2]))
                nombre == "skewx" && n.size == 1 -> Matriz(doubleArrayOf(1.0, 0.0, tan(r(n[0])), 1.0, 0.0, 0.0))
                nombre == "skewy" && n.size == 1 -> Matriz(doubleArrayOf(1.0, tan(r(n[0])), 0.0, 1.0, 0.0, 0.0))
                else -> Matriz.IDENTIDAD
            }
            if (otra.m.all { it.isFinite() }) m = m.por(otra)
            resto = resto.substring(cierra + 1)
        }
        return m
    }

    fun numerosSueltos(s: String): List<Double> {
        val l = Lector(s)
        val v = ArrayList<Double>()
        while (true) v += l.numero() ?: break
        return v
    }

    private fun longitud(s: String): Double? {
        val t = s.trim()
        val (num, k) = when {
            t.endsWith("px") -> t.dropLast(2) to 1.0
            t.endsWith("pt") -> t.dropLast(2) to 4.0 / 3.0
            t.endsWith("em") -> t.dropLast(2) to 16.0
            t.endsWith("%") -> return null
            else -> t to 1.0
        }
        return num.trim().toDoubleOrNull()?.times(k)?.takeIf { it.isFinite() }
    }

    private class Lector(s: String) {
        val b = s.toByteArray()
        var i = 0
        fun saltar() { while (i < b.size && (b[i].toInt().toChar().isWhitespace() || b[i] == ','.code.toByte())) i++ }
        private fun digito(k: Int) = k < b.size && b[k] in '0'.code.toByte()..'9'.code.toByte()
        fun numero(): Double? {
            saltar()
            val ini = i
            var j = i
            if (j < b.size && (b[j] == '+'.code.toByte() || b[j] == '-'.code.toByte())) j++
            var digitos = 0
            while (digito(j)) { j++; digitos++ }
            if (j < b.size && b[j] == '.'.code.toByte()) { j++; while (digito(j)) { j++; digitos++ } }
            if (digitos == 0) return null
            if (j < b.size && (b[j] == 'e'.code.toByte() || b[j] == 'E'.code.toByte())) {
                var k = j + 1
                if (k < b.size && (b[k] == '+'.code.toByte() || b[k] == '-'.code.toByte())) k++
                if (digito(k)) { while (digito(k)) k++; j = k }
            }
            val v = String(b, ini, j - ini).toDoubleOrNull() ?: return null
            i = j
            return v.takeIf { it.isFinite() }
        }
        fun bandera(): Boolean? {
            saltar()
            return when (b.getOrNull(i)) { '0'.code.toByte() -> { i++; false }; '1'.code.toByte() -> { i++; true }; else -> null }
        }
        fun comando(): Char? {
            saltar()
            val c = b.getOrNull(i)?.toInt()?.toChar() ?: return null
            return if (c.isLetter() && c.code < 128 && c != 'e' && c != 'E') { i++; c } else null
        }
        fun fin(): Boolean { saltar(); return i >= b.size }
    }

    private val NOMBRES = mapOf(
        "black" to "#000000", "white" to "#ffffff", "red" to "#ff0000", "green" to "#008000", "blue" to "#0000ff", "yellow" to "#ffff00",
        "orange" to "#ffa500", "purple" to "#800080", "pink" to "#ffc0cb", "gray" to "#808080", "grey" to "#808080", "brown" to "#a52a2a",
        "cyan" to "#00ffff", "aqua" to "#00ffff", "magenta" to "#ff00ff", "fuchsia" to "#ff00ff", "lime" to "#00ff00", "navy" to "#000080",
        "teal" to "#008080", "olive" to "#808000", "maroon" to "#800000", "silver" to "#c0c0c0", "gold" to "#ffd700", "beige" to "#f5f5dc",
        "tan" to "#d2b48c", "khaki" to "#f0e68c", "coral" to "#ff7f50", "salmon" to "#fa8072", "tomato" to "#ff6347", "crimson" to "#dc143c",
        "indigo" to "#4b0082", "violet" to "#ee82ee", "orchid" to "#da70d6", "plum" to "#dda0dd", "lavender" to "#e6e6fa", "turquoise" to "#40e0d0",
        "skyblue" to "#87ceeb", "lightblue" to "#add8e6", "steelblue" to "#4682b4", "royalblue" to "#4169e1", "darkblue" to "#00008b",
        "darkgreen" to "#006400", "forestgreen" to "#228b22", "seagreen" to "#2e8b57", "mediumseagreen" to "#3cb371", "lightgreen" to "#90ee90",
        "palegreen" to "#98fb98", "yellowgreen" to "#9acd32", "olivedrab" to "#6b8e23", "darkolivegreen" to "#556b2f", "springgreen" to "#00ff7f",
        "lightgray" to "#d3d3d3", "lightgrey" to "#d3d3d3", "darkgray" to "#a9a9a9", "darkgrey" to "#a9a9a9", "dimgray" to "#696969", "dimgrey" to "#696969",
        "slategray" to "#708090", "slategrey" to "#708090", "gainsboro" to "#dcdcdc", "whitesmoke" to "#f5f5f5", "snow" to "#fffafa", "ivory" to "#fffff0",
        "wheat" to "#f5deb3", "saddlebrown" to "#8b4513", "sienna" to "#a0522d", "chocolate" to "#d2691e", "peru" to "#cd853f", "goldenrod" to "#daa520",
        "darkorange" to "#ff8c00", "darkred" to "#8b0000", "firebrick" to "#b22222", "hotpink" to "#ff69b4", "deeppink" to "#ff1493",
        "lightpink" to "#ffb6c1", "lightyellow" to "#ffffe0"
    )

    /** Un color de SVG a `#rrggbb`; `Pair(true, null)` es «none»; null, que no se entiende. */
    private fun colorSvg(t0: String, actual: String, degradados: Map<String, String>): Pair<Boolean, String?>? {
        val t = t0.trim()
        val l = t.lowercase()
        if (l == "none" || l == "transparent") return true to null
        if (l == "currentcolor") return true to actual
        if (l.startsWith("url(")) {
            val id = l.removePrefix("url(").trimEnd(')').trim().trim('\'', '"').trimStart('#')
            degradados[id]?.let { return true to it }
            val reserva = t.split(')').getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            return reserva?.let { colorSvg(it, actual, degradados) }
        }
        val dentro = l.removePrefix("rgba(").takeIf { l.startsWith("rgba(") } ?: l.removePrefix("rgb(").takeIf { l.startsWith("rgb(") }
        if (dentro != null) {
            val partes = dentro.trimEnd(')').split(',', ' ', '/').filter { it.isNotEmpty() }
            if (partes.size < 3) return null
            fun canal(s: String): Int? = if (s.endsWith('%')) s.dropLast(1).trim().toDoubleOrNull()?.let { round(it.coerceIn(0.0, 100.0) * 2.55).toInt() }
            else s.trim().toDoubleOrNull()?.let { round(it.coerceIn(0.0, 255.0)).toInt() }
            val r = canal(partes[0]) ?: return null; val g = canal(partes[1]) ?: return null; val b = canal(partes[2]) ?: return null
            return true to "#%02x%02x%02x".format(r, g, b)
        }
        if (l.startsWith("#")) return Herramientas.color(l)?.let { true to it }
        return NOMBRES[l]?.let { true to it } ?: Herramientas.color(l)?.let { true to it }
    }

    private fun degradados(todos: List<Element>): Map<String, String> {
        val paradas = LinkedHashMap<String, List<Triple<Double, Double, Double>>>()
        val herencia = ArrayList<Pair<String, String>>()
        for (n in todos.filter { nombre(it) == "linearGradient" || nombre(it) == "radialGradient" }) {
            val id = attr(n, "id") ?: continue
            val lista = ArrayList<Triple<Double, Double, Double>>()
            for (s in hijos(n).filter { nombre(it) == "stop" }) {
                var c = attr(s, "stop-color")
                attr(s, "style")?.let { st -> for ((k, v) in declaraciones(st)) if (k == "stop-color") c = v }
                val hex = c?.let { colorSvg(it, "#000000", emptyMap())?.second } ?: continue
                val h = hex.trimStart('#')
                fun v(i: Int) = (h.substring(i, i + 2).toIntOrNull(16) ?: 0).toDouble()
                lista += Triple(v(0), v(2), v(4))
            }
            if (lista.isEmpty()) href(n)?.let { herencia += id to it }
            paradas[id] = lista
        }
        for ((id, de) in herencia) paradas[de]?.let { paradas[id] = it }
        return paradas.filter { it.value.isNotEmpty() }.mapValues { (_, l) ->
            val n = l.size.toDouble()
            "#%02x%02x%02x".format((l.sumOf { it.first } / n).toInt(), (l.sumOf { it.second } / n).toInt(), (l.sumOf { it.third } / n).toInt())
        }
    }

    private fun href(n: Element): String? =
        (attr(n, "href") ?: n.getAttributeNodeNS(XLINK, "href")?.value ?: attr(n, "xlink:href"))?.trim()?.takeIf { it.startsWith("#") }?.substring(1)

    private fun declaraciones(s: String): List<Pair<String, String>> = s.split(';').mapNotNull { d ->
        val i = d.indexOf(':').takeIf { it >= 0 } ?: return@mapNotNull null
        d.substring(0, i).trim().lowercase() to d.substring(i + 1).trim().removeSuffix("!important").trim()
    }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }

    // ---------------------------------------------------------------------------- <style>

    private class Selector(val tipo: String?, val clases: List<String>, val id: String?)

    private class Hojas(val reglas: List<Triple<Int, Selector, List<Pair<String, String>>>>) {
        fun para(n: Element): List<Pair<String, String>> {
            val tipo = nombre(n)
            val clases = (attr(n, "class") ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }
            val id = attr(n, "id")
            return reglas.filter { (_, s, _) -> (s.tipo == null || s.tipo == tipo) && s.clases.all { it in clases } && (s.id == null || s.id == id) }.flatMap { it.third }
        }

        companion object {
            fun de(todos: List<Element>): Hojas {
                val reglas = ArrayList<Triple<Int, Selector, List<Pair<String, String>>>>()
                for (st in todos.filter { nombre(it) == "style" }) {
                    val css = st.textContent.orEmpty()
                    val limpio = StringBuilder()
                    var resto = css
                    while (true) {
                        val i = resto.indexOf("/*").takeIf { it >= 0 } ?: break
                        limpio.append(resto, 0, i)
                        val j = resto.indexOf("*/", i)
                        resto = if (j < 0) "" else resto.substring(j + 2)
                    }
                    limpio.append(resto)
                    for (bloque in limpio.split('}')) {
                        val i = bloque.indexOf('{').takeIf { it >= 0 } ?: continue
                        val decl = declaraciones(bloque.substring(i + 1))
                        for (s0 in bloque.substring(0, i).split(',')) {
                            val s = s0.trim()
                            if (s.isEmpty() || s.any { it in " >+~:[" } || s.startsWith("@")) continue
                            var tipo: String? = null; val clases = ArrayList<String>(); var id: String? = null
                            var peso = 0
                            val actual = StringBuilder()
                            var claseDe = ' '
                            for (c in s + '\u0000') {
                                if (c == '.' || c == '#' || c == '\u0000') {
                                    if (actual.isNotEmpty()) when (claseDe) {
                                        '.' -> { clases += actual.toString(); peso += 10 }
                                        '#' -> { id = actual.toString(); peso += 100 }
                                        else -> if (actual.toString() != "*") { tipo = actual.toString(); peso += 1 }
                                    }
                                    actual.setLength(0); claseDe = c
                                } else actual.append(c)
                            }
                            reglas += Triple(peso, Selector(tipo, clases, id), decl)
                        }
                    }
                }
                return Hojas(reglas.sortedBy { it.first })
            }
        }
    }

    // ---------------------------------------------------------------------------- recorrido

    private data class Estilo(
        val relleno: String?, val trazo: String?, val grosor: Double, val opacidad: Double, val opacidadRelleno: Double,
        val opacidadTrazo: Double, val color: String, val tamLetra: Double, val ancla: String, val discontinuo: Boolean, val oculto: Boolean
    ) {
        fun poner(k: String, v: String, degradados: Map<String, String>): Estilo = when (k) {
            "fill" -> colorSvg(v, color, degradados)?.let { copy(relleno = it.second) } ?: this
            "stroke" -> colorSvg(v, color, degradados)?.let { copy(trazo = it.second) } ?: this
            "color" -> colorSvg(v, color, degradados)?.second?.let { copy(color = it) } ?: this
            "stroke-width" -> longitud(v)?.takeIf { it >= 0.0 }?.let { copy(grosor = it) } ?: this
            "opacity" -> v.trim().toDoubleOrNull()?.let { copy(opacidad = opacidad * it.coerceIn(0.0, 1.0)) } ?: this
            "fill-opacity" -> v.trim().toDoubleOrNull()?.let { copy(opacidadRelleno = it.coerceIn(0.0, 1.0)) } ?: this
            "stroke-opacity" -> v.trim().toDoubleOrNull()?.let { copy(opacidadTrazo = it.coerceIn(0.0, 1.0)) } ?: this
            "font-size" -> longitud(v)?.takeIf { it > 0.0 }?.let { copy(tamLetra = it) } ?: this
            "text-anchor" -> copy(ancla = when (v.trim()) { "middle" -> "center"; "end" -> "right"; else -> "left" })
            "stroke-dasharray" -> copy(discontinuo = v.trim() !in setOf("none", "0", ""))
            "display" -> copy(oculto = oculto || v.trim() == "none")
            "visibility" -> copy(oculto = oculto || v.trim() in setOf("hidden", "collapse"))
            else -> this
        }

        fun pintura(escala: Double): Pintura {
            val o = opacidad * if (relleno != null) opacidadRelleno else opacidadTrazo
            return Pintura(relleno, trazo, max(grosor * escala, 0.1), round(o * 100.0).coerceIn(5.0, 100.0), discontinuo)
        }

        companion object {
            fun raiz() = Estilo("#000000", null, 1.0, 1.0, 1.0, 1.0, "#000000", 16.0, "left", false, false)
        }
    }

    private val PROPIEDADES = listOf(
        "fill", "stroke", "color", "stroke-width", "opacity", "fill-opacity", "stroke-opacity", "font-size", "text-anchor",
        "stroke-dasharray", "display", "visibility", "font-family"
    )

    private class Recorrido(val estilos: Hojas, val degradados: Map<String, String>, val ids: Map<String, Element>) {
        val piezas = ArrayList<Pieza>()
        var puntos = 0
        val ignorados = ArrayList<String>()
        var detalle = 1.0 / 4.0

        fun recorrer(n: Element, m0: Matriz, padre: Estilo, hondo: Int) {
            if (hondo > MAX_PROFUNDIDAD) throw mal("el SVG anida mas de $MAX_PROFUNDIDAD niveles de grupos")
            val tipo = nombre(n)
            when (tipo) {
                "script", "foreignObject", "image", "iframe", "video", "audio", "animate", "animateTransform", "set", "filter", "mask", "pattern" -> { ignorados += tipo; return }
                "defs", "style", "title", "desc", "metadata", "linearGradient", "radialGradient", "clipPath", "symbol", "marker" -> return
            }
            var e = padre.copy(opacidad = 1.0)
            for (k in PROPIEDADES) attr(n, k)?.let { e = e.poner(k, it, degradados) }
            for ((k, v) in estilos.para(n)) e = e.poner(k, v, degradados)
            attr(n, "style")?.let { st -> for ((k, v) in declaraciones(st)) e = e.poner(k, v, degradados) }
            e = e.copy(opacidad = e.opacidad * padre.opacidad)
            if (e.oculto) return
            val m = attr(n, "transform")?.let { m0.por(transformacion(it)) } ?: m0
            fun num(k: String) = attr(n, k)?.let(::longitud) ?: 0.0
            val escala = m.escala()
            when (tipo) {
                "svg" -> {
                    val mm = if (hondo > 0) m.por(Matriz.trasladar(num("x"), num("y"))) else m
                    hijos(n).forEach { recorrer(it, mm, e, hondo + 1) }
                }
                "g", "a", "switch" -> hijos(n).forEach { recorrer(it, m, e, hondo + 1) }
                "use" -> {
                    val objetivo = href(n)?.let { ids[it] }
                    if (objetivo != null && objetivo !== n) {
                        val mm = m.por(Matriz.trasladar(num("x"), num("y")))
                        if (nombre(objetivo) == "symbol") hijos(objetivo).forEach { recorrer(it, mm, e, hondo + 2) }
                        else recorrer(objetivo, mm, e, hondo + 2)
                    } else ignorados += "use externo"
                }
                "rect" -> {
                    val x = num("x"); val y = num("y"); val w = num("width"); val h = num("height")
                    if (w <= 0.0 || h <= 0.0) return
                    val rx = (attr(n, "rx") ?: attr(n, "ry"))?.let(::longitud) ?: 0.0
                    if (m.derecha()) {
                        val (x0, y0) = m.aplicar(x to y); val (x1, y1) = m.aplicar((x + w) to (y + h))
                        caja("rectangle", doubleArrayOf(x0, y0, x1 - x0, y1 - y0), rx > 0.0, e.pintura(escala))
                    } else forma(listOf(listOf(x to y, (x + w) to y, (x + w) to (y + h), x to (y + h)).map(m::aplicar) to true), e.pintura(escala))
                }
                "circle", "ellipse" -> {
                    val cx = num("cx"); val cy = num("cy")
                    val rx = if (tipo == "circle") num("r") else num("rx")
                    val ry = if (tipo == "circle") num("r") else num("ry")
                    if (rx <= 0.0 || ry <= 0.0) return
                    if (m.derecha()) {
                        val (x0, y0) = m.aplicar((cx - rx) to (cy - ry)); val (x1, y1) = m.aplicar((cx + rx) to (cy + ry))
                        caja("ellipse", doubleArrayOf(x0, y0, x1 - x0, y1 - y0), false, e.pintura(escala))
                    } else {
                        val pasos = ceil(max(rx, ry) * escala * detalle * TAU).toInt().coerceIn(12, 72)
                        forma(listOf((0 until pasos).map { k -> val a = k.toDouble() / pasos * TAU; m.aplicar((cx + rx * cos(a)) to (cy + ry * sin(a))) } to true), e.pintura(escala))
                    }
                }
                "line" -> forma(listOf(listOf(m.aplicar(num("x1") to num("y1")), m.aplicar(num("x2") to num("y2"))) to false), e.pintura(escala).copy(relleno = null))
                "polyline", "polygon" -> {
                    val v = numerosSueltos(attr(n, "points") ?: "")
                    val pts = v.chunked(2).filter { it.size == 2 }.map { m.aplicar(it[0] to it[1]) }
                    if (pts.size >= 2) forma(listOf(pts to (tipo == "polygon")), e.pintura(escala))
                }
                "path" -> {
                    val anillos = camino(attr(n, "d") ?: "", m, detalle)
                    if (anillos.isNotEmpty()) forma(anillos, e.pintura(escala))
                }
                "text" -> {
                    val texto = n.textContent.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                    if (texto.isEmpty()) return
                    var et = e
                    hijos(n).firstOrNull { nombre(it) == "tspan" }?.let { t -> for (k in listOf("font-size", "text-anchor", "fill")) attr(t, k)?.let { et = et.poner(k, it, degradados) } }
                    fun primero(k: String) = attr(n, k)?.let { numerosSueltos(it).firstOrNull() } ?: 0.0
                    val (x, y) = m.aplicar(primero("x") to primero("y"))
                    val tam = et.tamLetra * escala
                    val color = et.relleno ?: et.trazo ?: "#000000"
                    contar(1)
                    piezas += Pieza.Texto(x, y - tam * 0.8, texto.take(Herramientas.MAX_TEXTO), tam, et.ancla, color,
                        round(et.opacidad * et.opacidadRelleno * 100.0).coerceIn(5.0, 100.0))
                }
                else -> ignorados += tipo
            }
        }

        fun contar(n: Int) {
            puntos += n
            if (piezas.size >= MAX_FIGURAS) throw mal("el SVG pasa de $MAX_FIGURAS figuras: junta formas en un solo path o quita detalle")
            if (puntos > MAX_PUNTOS) throw mal("el SVG pasa de $MAX_PUNTOS puntos: simplifica las curvas")
        }

        fun caja(tipo: String, caja: DoubleArray, redondo: Boolean, pinta: Pintura) {
            if ((pinta.relleno == null && pinta.trazo == null) || caja.any { !it.isFinite() }) return
            contar(4)
            piezas += Pieza.Caja(tipo, caja, redondo, pinta)
        }

        fun forma(anillos0: List<Pair<List<Pair<Double, Double>>, Boolean>>, pinta: Pintura) {
            if (pinta.relleno == null && pinta.trazo == null) return
            val anillos = anillos0.map { (a, c) -> a.filter { it.first.isFinite() && it.second.isFinite() } to c }.filter { it.first.size >= 2 }
            if (anillos.isEmpty()) return
            contar(anillos.sumOf { it.first.size })
            piezas += Pieza.Forma(anillos, pinta)
        }
    }

    // ---------------------------------------------------------------------------- un path

    private fun camino(d: String, m: Matriz, detalle: Double): List<Pair<List<Pair<Double, Double>>, Boolean>> {
        val l = Lector(d)
        val anillos = ArrayList<Pair<List<Pair<Double, Double>>, Boolean>>()
        var actual = ArrayList<Pair<Double, Double>>()
        var x = 0.0; var y = 0.0; var sx = 0.0; var sy = 0.0
        var control: Pair<Double, Double>? = null
        var ultimo = ' '
        fun pasos(largo: Double) = ceil(largo * detalle).toInt().coerceIn(2, 48)
        fun largoEn(pts: List<Pair<Double, Double>>) = pts.zipWithNext { a0, b0 -> val a = m.aplicar(a0); val b = m.aplicar(b0); hypot(a.first - b.first, a.second - b.second) }.sum()
        fun cerrar(cerrado: Boolean) { if (actual.size >= 2) { anillos += actual to cerrado; actual = ArrayList() } else actual.clear() }
        while (true) {
            if (l.fin()) break
            val cmd = l.comando() ?: when (ultimo) { 'M' -> 'L'; 'm' -> 'l'; 'Z', 'z', ' ' -> break; else -> ultimo }
            val rel = cmd.isLowerCase()
            val bx = if (rel) x else 0.0; val by = if (rel) y else 0.0
            var ok = true
            when (cmd.uppercaseChar()) {
                'M' -> { val a = l.numero(); val b = l.numero()
                    if (a != null && b != null) { cerrar(false); x = bx + a; y = by + b; sx = x; sy = y; actual += m.aplicar(x to y); control = null } else ok = false }
                'L' -> { val a = l.numero(); val b = l.numero()
                    if (a != null && b != null) { x = bx + a; y = by + b; actual += m.aplicar(x to y); control = null } else ok = false }
                'H' -> { val a = l.numero(); if (a != null) { x = if (rel) x + a else a; actual += m.aplicar(x to y); control = null } else ok = false }
                'V' -> { val b = l.numero(); if (b != null) { y = if (rel) y + b else b; actual += m.aplicar(x to y); control = null } else ok = false }
                'C', 'S' -> {
                    val c1 = if (cmd.uppercaseChar() == 'C') { val a = l.numero(); val b = l.numero(); if (a != null && b != null) (bx + a) to (by + b) else null }
                    else control?.takeIf { ultimo.uppercaseChar() in setOf('C', 'S') }?.let { (2.0 * x - it.first) to (2.0 * y - it.second) } ?: (x to y)
                    val a = l.numero(); val b = l.numero(); val c = l.numero(); val dd = l.numero()
                    if (c1 != null && a != null && b != null && c != null && dd != null) {
                        val c2 = (bx + a) to (by + b); val fin = (bx + c) to (by + dd); val p0 = x to y
                        val n = pasos(largoEn(listOf(p0, c1, c2, fin)))
                        for (k in 1..n) {
                            val t = k.toDouble() / n; val u = 1.0 - t
                            actual += m.aplicar((u * u * u * p0.first + 3.0 * u * u * t * c1.first + 3.0 * u * t * t * c2.first + t * t * t * fin.first) to
                                (u * u * u * p0.second + 3.0 * u * u * t * c1.second + 3.0 * u * t * t * c2.second + t * t * t * fin.second))
                        }
                        control = c2; x = fin.first; y = fin.second
                    } else ok = false
                }
                'Q', 'T' -> {
                    val c1 = if (cmd.uppercaseChar() == 'Q') { val a = l.numero(); val b = l.numero(); if (a != null && b != null) (bx + a) to (by + b) else null }
                    else control?.takeIf { ultimo.uppercaseChar() in setOf('Q', 'T') }?.let { (2.0 * x - it.first) to (2.0 * y - it.second) } ?: (x to y)
                    val a = l.numero(); val b = l.numero()
                    if (c1 != null && a != null && b != null) {
                        val fin = (bx + a) to (by + b); val p0 = x to y
                        val n = pasos(largoEn(listOf(p0, c1, fin)))
                        for (k in 1..n) {
                            val t = k.toDouble() / n; val u = 1.0 - t
                            actual += m.aplicar((u * u * p0.first + 2.0 * u * t * c1.first + t * t * fin.first) to (u * u * p0.second + 2.0 * u * t * c1.second + t * t * fin.second))
                        }
                        control = c1; x = fin.first; y = fin.second
                    } else ok = false
                }
                'A' -> {
                    val rx = l.numero(); val ry = l.numero(); val giro = l.numero(); val grande = l.bandera(); val barre = l.bandera(); val a = l.numero(); val b = l.numero()
                    if (rx != null && ry != null && giro != null && grande != null && barre != null && a != null && b != null) {
                        val fin = (bx + a) to (by + b)
                        for (p in arco(x to y, rx, ry, giro, grande, barre, fin, ::pasos)) actual += m.aplicar(p)
                        x = fin.first; y = fin.second; control = null
                    } else ok = false
                }
                'Z' -> { cerrar(true); x = sx; y = sy; actual += m.aplicar(x to y); control = null }
                else -> ok = false
            }
            if (!ok) break
            ultimo = cmd
        }
        cerrar(false)
        return anillos
    }

    private fun arco(desde: Pair<Double, Double>, rx0: Double, ry0: Double, giro: Double, grande: Boolean, barre: Boolean, hasta: Pair<Double, Double>, pasos: (Double) -> Int): List<Pair<Double, Double>> {
        var rx = abs(rx0); var ry = abs(ry0)
        if (rx < 1e-9 || ry < 1e-9 || hypot(desde.first - hasta.first, desde.second - hasta.second) < 1e-12) return listOf(hasta)
        val fi = Math.toRadians(giro)
        val c = cos(fi); val s = sin(fi)
        val dx = (desde.first - hasta.first) / 2.0; val dy = (desde.second - hasta.second) / 2.0
        val x1 = c * dx + s * dy; val y1 = -s * dx + c * dy
        val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (lambda > 1.0) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val num = max(rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1, 0.0)
        val den = rx * rx * y1 * y1 + ry * ry * x1 * x1
        var k = if (den > 0.0) sqrt(num / den) else 0.0
        if (grande == barre) k = -k
        val cx1 = k * rx * y1 / ry; val cy1 = -k * ry * x1 / rx
        val cx = c * cx1 - s * cy1 + (desde.first + hasta.first) / 2.0
        val cy = s * cx1 + c * cy1 + (desde.second + hasta.second) / 2.0
        fun angulo(ux: Double, uy: Double, vx: Double, vy: Double) = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        val t1 = angulo(1.0, 0.0, (x1 - cx1) / rx, (y1 - cy1) / ry)
        var dt = angulo((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
        if (!barre && dt > 0.0) dt -= TAU else if (barre && dt < 0.0) dt += TAU
        val n = pasos(abs(dt) * max(rx, ry))
        return (1..n).map { i ->
            if (i == n) hasta else { val t = t1 + dt * i / n; val px = rx * cos(t); val py = ry * sin(t); (c * px - s * py + cx) to (s * px + c * py + cy) }
        }
    }

    /** Ramer-Douglas-Peucker: quita lo que no cambia la forma más de [tol] píxeles. */
    fun simplificar(pts: List<Pair<Double, Double>>, tol: Double): List<Pair<Double, Double>> {
        val v = ArrayList<Pair<Double, Double>>(pts.size)
        for (p in pts) if (v.isEmpty() || hypot(v.last().first - p.first, v.last().second - p.second) > 1e-6) v += p
        if (v.size <= 3) return v
        val guardar = BooleanArray(v.size).also { it[0] = true; it[v.size - 1] = true }
        val pila = ArrayList<Pair<Int, Int>>().also { it += 0 to v.size - 1 }
        while (pila.isNotEmpty()) {
            val (a, b) = pila.removeAt(pila.size - 1)
            if (b <= a + 1) continue
            val pa = v[a]; val pb = v[b]
            val dx = pb.first - pa.first; val dy = pb.second - pa.second
            val largo = hypot(dx, dy)
            var mayor = 0.0 to a
            for (i in a + 1 until b) {
                val p = v[i]
                val d = if (largo < 1e-9) hypot(p.first - pa.first, p.second - pa.second) else abs((p.first - pa.first) * dy - (p.second - pa.second) * dx) / largo
                if (d > mayor.first) mayor = d to i
            }
            if (mayor.first > tol) { guardar[mayor.second] = true; pila += a to mayor.second; pila += mayor.second to b }
        }
        return v.filterIndexed { i, _ -> guardar[i] }
    }

    private fun dentro(p: Pair<Double, Double>, poligono: List<Pair<Double, Double>>): Boolean {
        var si = false
        val n = poligono.size
        var j = n - 1
        for (i in 0 until n) {
            val a = poligono[i]; val b = poligono[j]
            if ((a.second > p.second) != (b.second > p.second) && p.first < (b.first - a.first) * (p.second - a.second) / (b.second - a.second + 1e-300) + a.first) si = !si
            j = i
        }
        return si
    }

    // ----------------------------------------------------------------------------- al lienzo

    private fun oscurecer(hex: String): String {
        val h = hex.trimStart('#')
        fun c(i: Int) = ((h.getOrNull(i)?.let { h.substring(i, minOf(i + 2, h.length)).toIntOrNull(16) } ?: 0) * 0.55).toInt()
        return "#%02x%02x%02x".format(c(0), c(2), c(4))
    }

    private fun n2(v: Double): JsonPrimitive = JsonPrimitive(round(v * 100.0) / 100.0)

    private fun pintar(e: MutableMap<String, JsonElement>, pinta: Pintura, rugosidad: Int) {
        e["strokeWidth"] = n2(pinta.grosor)
        e["opacity"] = JsonPrimitive(pinta.opacidad)
        e["roughness"] = JsonPrimitive(rugosidad)
        if (pinta.discontinuo) e["strokeStyle"] = JsonPrimitive("dashed")
        if (pinta.trazo == null) e["strokeWidth"] = JsonPrimitive(1)
    }

    /** **Las piezas como elementos del lienzo**, todas en un grupo (`svg::elementos` del PC). */
    internal fun elementos(f: Lienzo.Fabrica, s: Dibujo) {
        val antes = f.grupo
        f.grupo = f.nombreDeGrupo("svg")
        val rugosidad = if (s.modo is Modo.AMano) 1 else 0
        val familia = if (s.modo is Modo.Liso) 2 else 1
        val lapiz = s.modo as? Modo.Lapiz
        for (p in s.piezas) {
            val desde = f.salida.size
            when (p) {
                is Pieza.Caja -> {
                    val trazo = p.pinta.trazo ?: p.pinta.relleno ?: "#1e1e1e"
                    f.caja(p.tipo, p.caja, trazo, p.pinta.relleno)
                    f.salida.lastOrNull()?.let { e ->
                        if (p.redondo) e["roundness"] = buildJsonObject { put("type", 3) }
                        pintar(e, p.pinta, rugosidad)
                        if (lapiz != null) {
                            e["material"] = JsonPrimitive(lapiz.material)
                            lapiz.dureza?.takeIf { it != "HB" }?.let { e["dureza"] = JsonPrimitive(it) }
                            e["strokeWidth"] = JsonPrimitive(max(p.pinta.grosor, 1.5))
                            if (p.pinta.trazo == null) e["strokeColor"] = JsonPrimitive(oscurecer(p.pinta.relleno ?: "#1e1e1e"))
                        }
                    }
                }
                is Pieza.Forma -> forma(f, p.anillos, p.pinta, rugosidad, lapiz)
                is Pieza.Texto -> {
                    f.texto(p.x, p.y, p.texto, p.tam, p.color, p.alinear)
                    f.salida.lastOrNull()?.let { e -> e["fontFamily"] = JsonPrimitive(familia); e["opacity"] = JsonPrimitive(p.opacidad) }
                }
            }
            if (lapiz != null) for (e in f.salida.drop(desde)) {
                val t = (e["type"] as? JsonPrimitive)?.content
                val fondo = (e["backgroundColor"] as? JsonPrimitive)?.content
                if (t == "pixpin-region" || (fondo != "transparent" && t != "freedraw")) {
                    val o = (e["opacity"] as? JsonPrimitive)?.doubleOrNull ?: 100.0
                    e["opacity"] = JsonPrimitive(max(round(o * 0.45), 5.0))
                }
            }
        }
        f.grupo = antes
    }

    private fun forma(f: Lienzo.Fabrica, anillos: List<Pair<List<Pair<Double, Double>>, Boolean>>, pinta: Pintura, rugosidad: Int, lapiz: Modo.Lapiz?) {
        val relleno = pinta.relleno
        if (relleno != null) {
            val cerrados = anillos.map { it.first }.filter { it.size >= 3 }
            val nivel = cerrados.mapIndexed { i, a -> cerrados.indices.count { j -> j != i && dentro(a[0], cerrados[j]) } }
            for (i in cerrados.indices) {
                if (nivel[i] % 2 != 0) continue
                val a = cerrados[i]
                val huecos = cerrados.indices.filter { j -> nivel[j] == nivel[i] + 1 && dentro(cerrados[j][0], a) }.map { cerrados[it] }
                val trazo = pinta.trazo?.takeIf { lapiz == null } ?: relleno
                f.region(a, trazo, relleno, huecos)
                f.salida.lastOrNull()?.let { e ->
                    pintar(e, pinta, rugosidad)
                    if (lapiz != null || pinta.trazo == null) e["strokeWidth"] = JsonPrimitive(1)
                }
            }
        }
        val contornoDeLapiz = if (pinta.trazo == null && relleno != null && lapiz != null) oscurecer(relleno) else null
        val trazo = pinta.trazo ?: contornoDeLapiz ?: return
        for ((a, cerrado) in anillos) {
            if (relleno != null && lapiz == null && a.size >= 3) continue
            val pts = if (cerrado && a.size >= 3) a + a[0] else a
            if (lapiz != null) {
                f.lapiz(pts, trazo, lapiz.material, lapiz.dureza)
                f.salida.lastOrNull()?.let { e -> e["strokeWidth"] = n2(pinta.grosor); e["opacity"] = JsonPrimitive(pinta.opacidad) }
            } else {
                f.trazo("line", pts, trazo, null)
                f.salida.lastOrNull()?.let { e -> pintar(e, pinta, rugosidad); e["strokeWidth"] = n2(pinta.grosor) }
            }
        }
    }
}
