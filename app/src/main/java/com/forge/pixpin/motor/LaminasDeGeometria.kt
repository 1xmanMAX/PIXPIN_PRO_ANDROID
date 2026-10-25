package com.forge.pixpin.motor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * **Las láminas de geometría del PC, aquí** (25-oct-2026; `geometria_exacta.rs` y
 * `docs/manual-geometria-android.md` del PC): el sector y el segmento ([CierreArco]), la marca de
 * ángulo con sus arquitos, su cuadradito y su letra ([MarcaAngulo]), el relleno degradado
 * ([Degradado]) y el trazo raya-punto.
 *
 * **Ninguna figura nueva**: un sector, un cuadrante y una marca de ángulo son `pixpin-arc` con
 * claves de más, y el degradado y el raya-punto son claves sobre lo que ya hay. Hasta hoy el
 * teléfono las conservaba sin pintarlas (se veía la reserva: el arco abierto, el relleno liso, el
 * discontinuo); ahora las lee, las pinta y las escribe **con las mismas claves y los mismos
 * valores** que el PC. Lo que no entiende (un `"cierreArco": "espiral"` de una versión futura) lo
 * deja tal cual, la misma regla que el PC.
 *
 * Todo lo de aquí es puro —ni lienzo ni pincel— y se comprueba en `LaminasDeGeometriaTest`.
 * Quien pinta es [PintorDeLaminas].
 */
object Laminas {

    // ---------------------------------------------------------------------
    // Las claves del fichero
    // ---------------------------------------------------------------------

    /** Cómo se cierra un arco: por los dos radios (el «trozo de pizza») o por la cuerda. */
    enum class CierreArco(val palabra: String) {
        SECTOR("sector"),
        SEGMENTO("segmento");

        companion object {
            /** `"segment"` también vale: lo acepta el PC. Lo demás, null (y se deja intacto). */
            fun desdePalabra(s: String?): CierreArco? = when (s) {
                "sector" -> SECTOR
                "segmento", "segment" -> SEGMENTO
                else -> null
            }
        }
    }

    /**
     * **El arco es la marca de un ángulo**: su centro es el vértice y su tramo va de un lado al
     * otro. [arcos] arquitos concéntricos (1 a 3: dos ángulos con los mismos son iguales), o el
     * cuadradito si es [recto], y [valor] escrito fuera, sobre la bisectriz.
     */
    data class MarcaAngulo(val arcos: Int = 1, val recto: Boolean = false, val valor: String? = null)

    enum class TipoDegradado(val palabra: String) {
        LINEAL("lineal"),
        RADIAL("radial");

        companion object {
            fun desdePalabra(s: String?): TipoDegradado? = when (s) {
                "lineal", "linear" -> LINEAL
                "radial" -> RADIAL
                else -> null
            }
        }
    }

    /**
     * **Un relleno que va de un color a otro.** [origen] y [destino] en **fracciones de la caja**
     * (0 a 1): así se mueve y se estira con la figura sin tocarlo. Lineal: de [desde] en el origen a
     * [hasta] en el destino. Radial: [desde] en el centro (origen) y [hasta] a la distancia del
     * destino.
     */
    data class Degradado(
        val tipo: TipoDegradado,
        val desde: String,
        val hasta: String,
        val origen: Pair<Double, Double>,
        val destino: Pair<Double, Double>
    )

    fun cierreDe(e: Element): CierreArco? = CierreArco.desdePalabra(e.cierreArco)

    /** `marcaAngulo`: `{"arcos": 2, "recto": false, "valor": "45°"}`, como `marca_desde`. */
    fun marcaDe(e: Element): MarcaAngulo? {
        val m = e.marcaAngulo ?: return null
        return MarcaAngulo(
            arcos = (m["arcos"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 3) ?: 1,
            recto = (m["recto"] as? JsonPrimitive)?.booleanOrNull ?: false,
            valor = (m["valor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        )
    }

    /** Y de vuelta, con las claves en el orden del PC (`laminas_hacia`). */
    fun marcaJson(m: MarcaAngulo): JsonObject {
        val campos = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "arcos" to JsonPrimitive(m.arcos.coerceIn(1, 3)),
            "recto" to JsonPrimitive(m.recto)
        )
        m.valor?.takeIf { it.isNotEmpty() }?.let { campos["valor"] = JsonPrimitive(it) }
        return JsonObject(campos)
    }

    private fun par(v: kotlinx.serialization.json.JsonElement?): Pair<Double, Double>? {
        val a = v as? JsonArray ?: return null
        val x = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val y = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        return x to y
    }

    /** Los de fábrica de cada tipo: el radial del centro a las esquinas, el lineal de izquierda a derecha. */
    private fun extremosDeFabrica(t: TipoDegradado) = when (t) {
        TipoDegradado.RADIAL -> (0.5 to 0.5) to (1.0 to 1.0)
        TipoDegradado.LINEAL -> (0.0 to 0.5) to (1.0 to 0.5)
    }

    /** `degradado`: `{"tipo", "desde", "hasta", "origen": [fx, fy], "destino": [fx, fy]}`. */
    fun degradadoDe(e: Element): Degradado? {
        val d = e.degradado ?: return null
        val tipo = TipoDegradado.desdePalabra((d["tipo"] as? JsonPrimitive)?.contentOrNull) ?: return null
        val desde = (d["desde"] as? JsonPrimitive)?.contentOrNull?.takeIf { rgbaDe(it) != null } ?: return null
        val hasta = (d["hasta"] as? JsonPrimitive)?.contentOrNull?.takeIf { rgbaDe(it) != null } ?: return null
        val (o, q) = extremosDeFabrica(tipo)
        return Degradado(tipo, desde, hasta, par(d["origen"]) ?: o, par(d["destino"]) ?: q)
    }

    fun degradadoJson(d: Degradado): JsonObject = JsonObject(
        linkedMapOf(
            "tipo" to JsonPrimitive(d.tipo.palabra),
            "desde" to JsonPrimitive(d.desde),
            "hasta" to JsonPrimitive(d.hasta),
            "origen" to JsonArray(listOf(JsonPrimitive(d.origen.first), JsonPrimitive(d.origen.second))),
            "destino" to JsonArray(listOf(JsonPrimitive(d.destino.first), JsonPrimitive(d.destino.second)))
        )
    )

    /**
     * El que se pone desde el panel con un color de relleno: **de ese color a uno un 80 % más
     * claro** (el «cuadrante coloreado» de las láminas), como `desde_relleno` del PC.
     */
    fun degradadoDesdeRelleno(tipo: TipoDegradado, color: String): Degradado? {
        val c = rgbaDe(color) ?: return null
        val claro = doubleArrayOf(
            c[0] + (1 - c[0]) * 0.8, c[1] + (1 - c[1]) * 0.8, c[2] + (1 - c[2]) * 0.8, c[3]
        )
        val (o, q) = extremosDeFabrica(tipo)
        return Degradado(tipo, hexDe(c), hexDe(claro), o, q)
    }

    /**
     * Un color del fichero (`#rgb`, `#rrggbb` o `#rrggbbaa`, **el alfa al final**, como lo escribe
     * el PC y la web) en r, g, b, a de 0 a 1. Ojo: `Color.parseColor` de Android lee el de ocho
     * cifras al revés (`#aarrggbb`), y por eso aquí no se usa.
     */
    fun rgbaDe(s: String): DoubleArray? {
        if (!s.startsWith("#")) return null
        val h = s.substring(1)
        fun b(i: Int) = h.substring(i, i + 2).toIntOrNull(16)?.div(255.0)
        return when (h.length) {
            3 -> {
                val v = h.map { "$it$it".toIntOrNull(16)?.div(255.0) ?: return null }
                doubleArrayOf(v[0], v[1], v[2], 1.0)
            }
            6 -> doubleArrayOf(b(0) ?: return null, b(2) ?: return null, b(4) ?: return null, 1.0)
            8 -> doubleArrayOf(b(0) ?: return null, b(2) ?: return null, b(4) ?: return null, b(6) ?: return null)
            else -> null
        }
    }

    /** Y al revés: `#rrggbb`, o `#rrggbbaa` si no es opaco (`color_hacia` del PC). */
    fun hexDe(c: DoubleArray): String {
        fun b(v: Double) = "%02x".format(Math.round(v.coerceIn(0.0, 1.0) * 255.0).toInt())
        val rgb = "#" + b(c[0]) + b(c[1]) + b(c[2])
        return if (c[3] >= 1.0) rgb else rgb + b(c[3])
    }

    /** ARGB de Android de un color del fichero, con [alfa] (0-255) encima del suyo. */
    fun argbDe(s: String, alfa: Int = 255): Int? {
        val c = rgbaDe(s) ?: return null
        val a = (c[3] * alfa).toInt().coerceIn(0, 255)
        return (a shl 24) or ((c[0] * 255).toInt() shl 16) or ((c[1] * 255).toInt() shl 8) or (c[2] * 255).toInt()
    }

    /**
     * **El patrón del raya-punto** (ejes, líneas de centro): raya `5·w+4`, hueco `2·w+2`, punto
     * `w`, hueco `2·w+2`, el de `partir_raya_punto` del PC.
     */
    fun patronRayaPunto(grosor: Double): DoubleArray {
        val g = grosor.coerceAtLeast(1.0)
        return doubleArrayOf(5 * g + 4, 2 * g + 2, g, 2 * g + 2)
    }

    /** Si [e] va en raya-punto: `dashed` más la clave, como lo escribe el PC. */
    fun esRayaPunto(e: Element): Boolean = e.trazoRayaPunto == true && e.strokeStyle == StrokeStyle.DASHED

    // ---------------------------------------------------------------------
    // Ángulos
    // ---------------------------------------------------------------------

    fun anguloDe(c: Pt, p: Pt): Double = atan2(p.y - c.y, p.x - c.x)

    /** Un ángulo llevado a `[0, 2π)`. */
    fun aUnaVuelta(a: Double): Double {
        val r = a.mod(2 * PI)
        return if (r >= 2 * PI) 0.0 else r
    }

    /**
     * **El ángulo menor entre dos rayos** que salen de [v] hacia [p1] y [p2]: `(inicio, barrido)`
     * con el barrido con signo y de módulo como mucho media vuelta. Es lo que mide una marca.
     */
    fun anguloMenor(v: Pt, p1: Pt, p2: Pt): Pair<Double, Double> {
        val a1 = anguloDe(v, p1)
        var d = anguloDe(v, p2) - a1
        while (d > PI) d -= 2 * PI
        while (d <= -PI) d += 2 * PI
        return a1 to d
    }

    // ---------------------------------------------------------------------
    // Lo que se pinta de un arco de lámina (sin girar: el giro lo pone quien pinta)
    // ---------------------------------------------------------------------

    /** El centro y el radio que manda en la marca: el del óvalo, que en una marca es un círculo. */
    private fun centroYRadio(e: Element): Pair<Pt, Double> =
        Pt(e.x + e.width / 2, e.y + e.height / 2) to minOf(abs(e.width), abs(e.height)) / 2

    /** El arco muestreado sobre su óvalo, en escena. */
    fun arcoMuestreado(e: Element, inicio: Double, barrido: Double): List<Pt> {
        val rx = e.width / 2
        val ry = e.height / 2
        if (rx <= 0 || ry <= 0) return emptyList()
        val cx = e.x + rx
        val cy = e.y + ry
        val pasos = (ARCO_PASOS * 2 * abs(barrido) / (2 * PI)).toInt().coerceAtLeast(8)
        return (0..pasos).map { i ->
            val t = inicio + barrido * i / pasos
            Pt(cx + rx * cos(t), cy + ry * sin(t))
        }
    }

    /**
     * **El contorno cerrado de un arco con cierre** (sector o segmento; una marca de ángulo cuenta
     * como sector), en escena y sin girar. Null si el arco va abierto. Es `contorno_cerrado`.
     */
    fun contornoCerrado(e: Element): List<Pt>? {
        if (e.type != ElementType.ARC) return null
        val cierre = cierreDe(e) ?: if (e.marcaAngulo != null) CierreArco.SECTOR else return null
        val inicio = e.arcStart ?: 0.0
        val barrido = e.arcSweep ?: (2 * PI)
        val puntos = arcoMuestreado(e, inicio, barrido).toMutableList()
        if (puntos.size < 2) return null
        if (cierre == CierreArco.SECTOR && abs(barrido) < 2 * PI - 1e-4) {
            puntos += Pt(e.x + e.width / 2, e.y + e.height / 2)
        }
        return puntos
    }

    /** Lo que hay que pintar de una marca de ángulo. Todo en escena y sin girar. */
    data class DibujoDeMarca(
        /** Lo que se rellena (el sector, o el cuadradito con el vértice). */
        val relleno: List<Pt>,
        /** Las rayas: los arquitos, o los dos lados del cuadradito. */
        val rayas: List<List<Pt>>,
        /** Hacia dónde cae la bisectriz y a qué radio acaba la marca, para colocar el valor. */
        val bisectriz: Double,
        val radio: Double,
        val vertice: Pt
    )

    /**
     * **Las piezas de una marca de ángulo**, como `ordenes_de_arco` del PC: con [MarcaAngulo.recto]
     * el cuadradito de lado = radio; si no, [MarcaAngulo.arcos] arquitos concéntricos separados
     * `max(3, 2,2·grosor)` hacia dentro, y el sector debajo para el relleno.
     */
    fun dibujoDeMarca(e: Element, m: MarcaAngulo): DibujoDeMarca? {
        if (e.type != ElementType.ARC) return null
        val inicio = e.arcStart ?: return null
        val barrido = e.arcSweep ?: return null
        val (c, r) = centroYRadio(e)
        if (r <= 0) return null
        val bis = inicio + barrido / 2
        if (m.recto) {
            val u1 = Pt(cos(inicio), sin(inicio))
            val u2 = Pt(cos(inicio + barrido), sin(inicio + barrido))
            val a = Pt(c.x + u1.x * r, c.y + u1.y * r)
            val b = Pt(c.x + (u1.x + u2.x) * r, c.y + (u1.y + u2.y) * r)
            val d = Pt(c.x + u2.x * r, c.y + u2.y * r)
            return DibujoDeMarca(listOf(c, a, b, d), listOf(listOf(a, b, d)), bis, r * 1.42, c)
        }
        val sep = (e.strokeWidth * 2.2).coerceAtLeast(3.0)
        val rayas = mutableListOf<List<Pt>>()
        for (k in 0 until m.arcos.coerceIn(1, 3)) {
            val rk = r - k * sep
            if (rk <= 1.0) break
            val pasos = (ARCO_PASOS * abs(barrido) / (2 * PI)).toInt().coerceAtLeast(8)
            rayas += (0..pasos).map { i ->
                val t = inicio + barrido * i / pasos
                Pt(c.x + rk * cos(t), c.y + rk * sin(t))
            }
        }
        return DibujoDeMarca(contornoCerrado(e) ?: emptyList(), rayas, bis, r, c)
    }

    /**
     * **Dónde va el centro del valor** de una marca: por fuera, sobre su bisectriz, apartado lo
     * que pida el tamaño del texto ([ancho] × [alto]) para no tocar el arco. Es donde lo pone uno
     * a mano y donde no tapa los lados.
     */
    fun sitioDelValor(d: DibujoDeMarca, ancho: Double, alto: Double): Pt {
        val lejos = d.radio + 4 + 0.5 * (ancho * abs(cos(d.bisectriz)) + alto * abs(sin(d.bisectriz)))
        return Pt(d.vertice.x + lejos * cos(d.bisectriz), d.vertice.y + lejos * sin(d.bisectriz))
    }

    // ---------------------------------------------------------------------
    // La herramienta Ángulo
    // ---------------------------------------------------------------------

    /** A cuántos píxeles de pantalla del vértice se busca el ángulo (`ALCANCE_ANGULO_PX`). */
    const val ALCANCE_PX = 60.0

    /** Radio de fábrica de una marca, en píxeles del documento (`RADIO_MARCA`). */
    const val RADIO_MARCA = 22.0

    /** **Qué pone la herramienta Ángulo** (`ModoAngulo`). */
    enum class ModoAngulo(val palabra: String) {
        /** La marca con la siguiente letra griega libre (α, β, θ…). El recto, sin letra. */
        GRIEGA("griega"),
        /** La marca sola, sin letra. */
        SIMPLE("simple"),
        /** Siempre el cuadradito, aunque lo dibujado a mano no mida 90 justos. */
        RECTO("recto");

        companion object {
            fun desdePalabra(s: String?) = entries.firstOrNull { it.palabra == s } ?: GRIEGA
        }
    }

    /**
     * **Las letras de los ángulos, en el orden de los libros de colegio**: α, β, θ, γ y ω primero
     * (las que pidió el usuario, en su orden), y luego las que se usan también para ángulos.
     */
    val LETRAS_DE_ANGULO = listOf(
        "α", "β", "θ", "γ", "ω", "δ", "φ", "ε", "λ", "μ", "ρ", "σ", "τ", "ψ", "η", "κ"
    )

    /** La letra número [i]: pasada la última sigue con α1, β1… como los puntos con A1. */
    fun letraDeAngulo(i: Int): String {
        val n = LETRAS_DE_ANGULO.size
        val letra = LETRAS_DE_ANGULO[i % n]
        return if (i / n == 0) letra else "$letra${i / n}"
    }

    /** Las letras que ya llevan las marcas del dibujo (sin [excepto]). */
    fun letrasUsadas(elementos: List<Element>, excepto: String? = null): List<String> =
        elementos.filter { !it.isDeleted && it.id != excepto }
            .mapNotNull { marcaDe(it)?.valor?.trim() }
            .filter { it.isNotEmpty() }

    /** **La siguiente letra libre**, mirando el dibujo: borrar la β la deja libre otra vez. */
    fun siguienteLetra(usadas: List<String>): String =
        (0 until 10_000).asSequence().map(::letraDeAngulo).firstOrNull { it !in usadas }
            ?: LETRAS_DE_ANGULO[0]

    /**
     * **La letra de al lado** ([paso] > 0 la siguiente, < 0 la anterior) de la serie, saltando
     * las que ya usan otras marcas. Sin letra, o con una que no es de la serie, la primera libre.
     */
    fun letraVecina(actual: String?, paso: Int, usadas: List<String>): String {
        val i = LETRAS_DE_ANGULO.indexOf(actual)
        if (i < 0) return siguienteLetra(usadas)
        val n = LETRAS_DE_ANGULO.size
        var j = i
        repeat(n) {
            j = (j + if (paso >= 0) 1 else -1).mod(n)
            val l = LETRAS_DE_ANGULO[j]
            if (l !in usadas) return l
        }
        return LETRAS_DE_ANGULO[i]
    }

    /** Donde se cortan los segmentos `a1b1` y `a2b2`, o null si no se tocan. */
    fun cruceDeSegmentos(a1: Pt, b1: Pt, a2: Pt, b2: Pt): Pt? {
        val d1x = b1.x - a1.x
        val d1y = b1.y - a1.y
        val d2x = b2.x - a2.x
        val d2y = b2.y - a2.y
        val den = d1x * d2y - d1y * d2x
        if (abs(den) < 1e-6) return null
        val wx = a2.x - a1.x
        val wy = a2.y - a1.y
        val t = (wx * d2y - wy * d2x) / den
        val u = (wx * d1y - wy * d1x) / den
        val tol = 1e-3
        if (t < -tol || t > 1 + tol || u < -tol || u > 1 + tol) return null
        return Pt(a1.x + d1x * t, a1.y + d1y * t)
    }

    /**
     * Los tramos rectos de las figuras de rayas (línea, flecha, cota y los lados de rectángulos y
     * rombos), en escena y ya girados. Las marcas de ángulo y las nubes no cuentan.
     */
    private fun tramosRectos(elementos: List<Element>): List<Pair<Pt, Pt>> {
        val v = mutableListOf<Pair<Pt, Pt>>()
        for (e in elementos) {
            if (e.isDeleted) continue
            val vale = e.isLinear ||
                ((e.type == ElementType.RECTANGLE || e.type == ElementType.DIAMOND) && e.roundness == null && !e.esNube)
            if (!vale) continue
            for (c in contornosDe(e)) {
                val pts = if (c.cerrado && c.puntos.size > 2) c.puntos + c.puntos.first() else c.puntos
                for (i in 0 until pts.lastIndex) {
                    if (hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y) > 0.5) v += pts[i] to pts[i + 1]
                }
            }
        }
        return v
    }

    /**
     * **El ángulo que se quiere marcar con un toque en [p]** (`angulo_en`): el vértice más cercano
     * donde se juntan dos rayas, a menos de [alcance], y los dos lados que encierran el toque.
     * Devuelve `(vértice, punto del lado 1, punto del lado 2)`.
     *
     * Se miran **todas** las rayas que pasan por el vértice, no solo las dos que dieron el cruce:
     * donde salen cuatro rectas, el toque cae entre las dos vecinas.
     */
    fun anguloEn(elementos: List<Element>, p: Pt, alcance: Double): Triple<Pt, Pt, Pt>? {
        val tramos = tramosRectos(elementos)
        var mejor: Pair<Double, Pt>? = null
        for (i in tramos.indices) for (j in i + 1 until tramos.size) {
            val v = cruceDeSegmentos(tramos[i].first, tramos[i].second, tramos[j].first, tramos[j].second)
                ?: continue
            val d = hypot(v.x - p.x, v.y - p.y)
            if (d <= alcance && (mejor == null || d < mejor.first)) mejor = d to v
        }
        val v = mejor?.second ?: return null
        val rayos = mutableListOf<Pt>()
        for ((a, b) in tramos) {
            if (distanceToSegment(v, a, b) > 0.75) continue
            for (extremo in listOf(a, b)) if (hypot(extremo.x - v.x, extremo.y - v.y) > 1.0) rayos += extremo
        }
        if (rayos.size < 2) return null
        val ac = anguloDe(v, p)
        var antes: Pair<Double, Pt>? = null
        var despues: Pair<Double, Pt>? = null
        for (r in rayos) {
            val d = aUnaVuelta(anguloDe(v, r) - ac)
            if (despues == null || d < despues.first) despues = d to r
            val d2 = aUnaVuelta(ac - anguloDe(v, r))
            if (antes == null || d2 < antes.first) antes = d2 to r
        }
        val p1 = antes?.second ?: return null
        val p2 = despues?.second ?: return null
        if (anguloDe(v, p1) == anguloDe(v, p2)) return null
        return Triple(v, p1, p2)
    }

    /**
     * **La marca que pone la herramienta Ángulo** en [v] entre [p1] y [p2] (`marca_con_modo`): el
     * cuadradito si es recto (a medio grado, o siempre con [ModoAngulo.RECTO]; de lado 0,6 del
     * radio) y si no los arquitos pedidos, con la siguiente letra griega libre de [usadas] en
     * [ModoAngulo.GRIEGA]. Hecha sobre [base], que trae id, semilla y estilo.
     *
     * Del estilo, lo del PC (`marca_con_estilo`): el color y la opacidad del pincel, grosor 1,5,
     * lisa, y de relleno el del pincel o, sin él, **el color del trazo al 30 %**.
     */
    fun marcaNueva(
        base: Element,
        modo: ModoAngulo,
        v: Pt,
        p1: Pt,
        p2: Pt,
        r: Double,
        arcos: Int,
        usadas: List<String>
    ): Element {
        val (inicio, barrido) = anguloMenor(v, p1, p2)
        val recto = modo == ModoAngulo.RECTO || abs(abs(barrido) - PI / 2) < Math.toRadians(0.5)
        val radio = if (recto) r * 0.6 else r
        val marca = MarcaAngulo(
            arcos = if (recto) 1 else arcos.coerceIn(1, 3),
            recto = recto,
            valor = if (modo == ModoAngulo.GRIEGA && !recto) siguienteLetra(usadas) else null
        )
        val relleno = if (!isTransparent(base.backgroundColor)) base.backgroundColor
        else rgbaDe(base.strokeColor)?.let { hexDe(doubleArrayOf(it[0], it[1], it[2], 0.3)) } ?: "#1e1e1e4d"
        return base.copy(
            type = ElementType.ARC,
            x = v.x - radio, y = v.y - radio, width = 2 * radio, height = 2 * radio,
            angle = 0.0,
            arcStart = inicio,
            arcSweep = barrido,
            strokeWidth = 1.5,
            roughness = Element.ROUGHNESS_ARCHITECT,
            backgroundColor = relleno,
            fillStyle = FillStyle.SOLID,
            roundness = null,
            points = null,
            cierreArco = null,
            degradado = null,
            marcaAngulo = marcaJson(marca)
        )
    }
}
