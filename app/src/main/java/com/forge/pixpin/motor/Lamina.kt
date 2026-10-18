package com.forge.pixpin.motor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * **Láminas de geometría** (port del PC, 15/17-oct-2026: `geometria_exacta.rs`): semicircunferencias,
 * cuadrantes y sectores rellenos (también con degradado), marcas de ángulo con sus arquitos, el
 * cuadradito del recto, la letra griega del ángulo y el trazo raya-punto.
 *
 * ## Ninguna figura nueva
 *
 * Un sector, un cuadrante, una semicircunferencia y una marca de ángulo **son `pixpin-arc`**. Lo
 * nuevo son claves opcionales del elemento, con reserva: quien no las entienda ve el arco de
 * siempre en el mismo sitio. Ver `docs/manual-geometria-android.md` del PC.
 *
 * | Clave | Valor |
 * |---|---|
 * | `cierreArco` | `"sector"` / `"segmento"` |
 * | `marcaAngulo` | `{"arcos": 1-3, "recto": bool, "valor": "45°"}` |
 * | `degradado` | `{"tipo": "lineal"/"radial", "desde", "hasta", "origen": [fx, fy], "destino": [fx, fy]}` |
 * | `trazoRayaPunto` | `true` (con `strokeStyle: "dashed"`) |
 *
 * ## Lo que no se entiende, vuelve intacto
 *
 * En [Element] van **tal cual llegaron** (`JsonElement`), y se interpretan al leerlas con las
 * extensiones de aquí. Así un valor de una versión futura (`"cierreArco": "espiral"`) se guarda
 * sin tocar —la misma regla del PC: una clave solo se quita si se entendía— y uno conocido vuelve
 * byte a byte como vino, subclaves raras incluidas. Al cambiarlas desde aquí se escriben con
 * [cierreHacia], [marcaHacia] y [degradadoHacia].
 *
 * Todo esto es puro (sin Android): lo comparten el lienzo, el SVG/HTML, el PDF y las pruebas.
 * Ángulos como el resto del motor: radianes de pantalla (la y baja).
 */

/** Cómo se cierra un arco: por los dos radios (sector, cuadrante) o por la cuerda (segmento). */
@kotlinx.serialization.Serializable
enum class CierreArco(val palabra: String) {
    SECTOR("sector"), SEGMENTO("segmento");

    companion object {
        fun desdePalabra(s: String): CierreArco? = when (s) {
            "sector" -> SECTOR
            "segmento", "segment" -> SEGMENTO
            else -> null
        }
    }
}

/** El arco es la marca de un ángulo: su centro es el vértice. */
data class MarcaAngulo(val arcos: Int = 1, val recto: Boolean = false, val valor: String? = null)

@kotlinx.serialization.Serializable
enum class TipoDegradado(val palabra: String) {
    LINEAL("lineal"), RADIAL("radial");

    companion object {
        fun desdePalabra(s: String): TipoDegradado? = when (s) {
            "lineal", "linear" -> LINEAL
            "radial" -> RADIAL
            else -> null
        }
    }
}

/**
 * **Un relleno que va de un color a otro.** `origen` y `destino` en **fracciones de la caja** de
 * la figura (0–1): se mueve y se estira con ella. Lineal: de origen a destino. Radial: centro en
 * origen, radio = distancia origen→destino. Los colores, en el `#rrggbb[aa]` del archivo.
 */
data class Degradado(
    val tipo: TipoDegradado,
    val desde: String,
    val hasta: String,
    val origen: Pt,
    val destino: Pt
) {
    companion object {
        fun radial(desde: String, hasta: String) =
            Degradado(TipoDegradado.RADIAL, desde, hasta, Pt(0.5, 0.5), Pt(1.0, 1.0))

        fun lineal(desde: String, hasta: String) =
            Degradado(TipoDegradado.LINEAL, desde, hasta, Pt(0.0, 0.5), Pt(1.0, 0.5))

        /** El del panel: del color de relleno a uno 80 % más claro (`desde_relleno` del PC). */
        fun desdeRelleno(tipo: TipoDegradado, color: String): Degradado {
            val c = rgbaDeHex(color) ?: intArrayOf(0x1e, 0x1e, 0x1e, 255)
            val claro = hexDeRgba(
                intArrayOf(
                    mezcla(c[0], 255, 0.8), mezcla(c[1], 255, 0.8), mezcla(c[2], 255, 0.8), c[3]
                )
            )
            val desde = hexDeRgba(c)
            return if (tipo == TipoDegradado.RADIAL) radial(desde, claro) else lineal(desde, claro)
        }
    }
}

/** Cómo traza la herramienta Arco. */
@kotlinx.serialization.Serializable
enum class ModoArco { GUIA, CENTRO, TRES_PUNTOS }

/** Qué pone la herramienta Ángulo: la letra griega libre, la marca sola o el cuadradito. */
@kotlinx.serialization.Serializable
enum class ModoAngulo { GRIEGA, SIMPLE, RECTO }

private fun mezcla(a: Int, b: Int, t: Double): Int = Math.round(a + (b - a) * t).toInt().coerceIn(0, 255)

/**
 * `#rgb`, `#rrggbb` o `#rrggbbaa` (el orden de la web y del PC, **alfa al final**) a `[r, g, b, a]`.
 * Null si no es un color de esos (entonces la clave que lo lleva no se entiende).
 */
fun rgbaDeHex(texto: String?): IntArray? {
    val t = texto?.trim() ?: return null
    if (t.isEmpty() || t.equals("transparent", ignoreCase = true)) return null
    val h = t.removePrefix("#")
    fun canal(i: Int) = h.substring(i, i + 2).toIntOrNull(16)
    return when (h.length) {
        3 -> {
            val r = h.substring(0, 1).toIntOrNull(16) ?: return null
            val g = h.substring(1, 2).toIntOrNull(16) ?: return null
            val b = h.substring(2, 3).toIntOrNull(16) ?: return null
            intArrayOf(r * 17, g * 17, b * 17, 255)
        }
        6 -> intArrayOf(canal(0) ?: return null, canal(2) ?: return null, canal(4) ?: return null, 255)
        8 -> intArrayOf(
            canal(0) ?: return null, canal(2) ?: return null, canal(4) ?: return null, canal(6) ?: return null
        )
        else -> null
    }
}

/** Y al revés, sin alfa si es opaco (lo que escribe el PC: `color_hacia`). */
fun hexDeRgba(c: IntArray): String =
    if (c[3] >= 255) "#%02x%02x%02x".format(c[0], c[1], c[2])
    else "#%02x%02x%02x%02x".format(c[0], c[1], c[2], c[3])

/** ARGB empaquetado (el de Android) de `#rrggbb[aa]`, multiplicando el alfa por [alpha]/255. */
fun argbDeHex(texto: String, alpha: Int = 255): Int {
    val c = rgbaDeHex(texto) ?: return 0
    val a = c[3] * alpha / 255
    return (a shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
}

// ---------------------------------------------------------------------------
// Lectura: de lo que guarda el elemento a algo con lo que pintar

/** El cierre, si se entiende. */
val Element.cierre: CierreArco?
    get() = (cierreArco as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(CierreArco::desdePalabra)

/** La marca de ángulo, si se entiende (un objeto). */
val Element.marca: MarcaAngulo?
    get() {
        val m = marcaAngulo as? JsonObject ?: return null
        return MarcaAngulo(
            arcos = ((m["arcos"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 1).coerceIn(1, 3),
            recto = (m["recto"] as? JsonPrimitive)?.booleanOrNull ?: false,
            valor = (m["valor"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        )
    }

private fun parDe(v: JsonElement?): Pt? {
    val a = v as? JsonArray ?: return null
    val x = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
    val y = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
    return Pt(x, y)
}

/** El degradado, si se entiende: tipo conocido y los dos colores válidos. */
val Element.degradadoDe: Degradado?
    get() {
        val d = degradado as? JsonObject ?: return null
        val tipo = (d["tipo"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let(TipoDegradado::desdePalabra) ?: return null
        val desde = (d["desde"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val hasta = (d["hasta"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (rgbaDeHex(desde) == null || rgbaDeHex(hasta) == null) return null
        val base = if (tipo == TipoDegradado.RADIAL) Degradado.radial(desde, hasta) else Degradado.lineal(desde, hasta)
        return base.copy(origen = parDe(d["origen"]) ?: base.origen, destino = parDe(d["destino"]) ?: base.destino)
    }

/** El trazo raya-punto: `dashed` + `trazoRayaPunto: true`. Sin él, discontinuo (la reserva). */
val Element.esRayaPunto: Boolean
    get() = strokeStyle == StrokeStyle.DASHED && (trazoRayaPunto as? JsonPrimitive)?.booleanOrNull == true

/** Si es un arco de lámina (cerrado o marca de ángulo): se pinta con [figuraDeLamina]. */
val Element.esArcoDeLamina: Boolean
    get() = type == ElementType.ARC && arcSweep != null &&
        ((cierreArco != null && cierre != null) || marcaAngulo is JsonObject)

/** Las figuras que admiten relleno degradado (rectángulo, rombo, óvalo, arco cerrado, región). */
val Element.admiteDegradado: Boolean
    get() = type == ElementType.RECTANGLE || type == ElementType.DIAMOND ||
        type == ElementType.ELLIPSE || type == ElementType.REGION ||
        (type == ElementType.ARC && (cierre != null || marcaAngulo is JsonObject))

// ---------------------------------------------------------------------------
// Escritura

fun cierreHacia(c: CierreArco?): JsonElement? = c?.let { JsonPrimitive(it.palabra) }

fun marcaHacia(m: MarcaAngulo?): JsonElement? = m?.let {
    val mapa = LinkedHashMap<String, JsonElement>()
    mapa["arcos"] = JsonPrimitive(it.arcos.coerceIn(1, 3))
    mapa["recto"] = JsonPrimitive(it.recto)
    it.valor?.takeIf { v -> v.isNotEmpty() }?.let { v -> mapa["valor"] = JsonPrimitive(v) }
    JsonObject(mapa)
}

fun degradadoHacia(d: Degradado?): JsonElement? = d?.let {
    JsonObject(
        linkedMapOf(
            "tipo" to JsonPrimitive(it.tipo.palabra),
            "desde" to JsonPrimitive(it.desde),
            "hasta" to JsonPrimitive(it.hasta),
            "origen" to JsonArray(listOf(JsonPrimitive(it.origen.x), JsonPrimitive(it.origen.y))),
            "destino" to JsonArray(listOf(JsonPrimitive(it.destino.x), JsonPrimitive(it.destino.y)))
        )
    )
}

/**
 * [e] con el cierre [c]. Quitarlo (null) solo quita lo que se entendía: un cierre de una versión
 * futura se queda, igual que en el PC.
 */
fun conCierre(e: Element, c: CierreArco?): Element = when {
    c != null -> e.copy(cierreArco = cierreHacia(c))
    e.cierreArco != null && e.cierre == null -> e
    else -> e.copy(cierreArco = null)
}

/** [e] con la marca [m] (null la quita, si se entendía). */
fun conMarca(e: Element, m: MarcaAngulo?): Element = when {
    m != null -> e.copy(marcaAngulo = marcaHacia(m))
    e.marcaAngulo != null && e.marca == null -> e
    else -> e.copy(marcaAngulo = null)
}

/**
 * [e] con el degradado [d]. Con uno puesto y **sin fondo**, el fondo pasa a ser `desde` y liso:
 * es la reserva de quien no entienda degradados (lo mismo que escribe el PC).
 */
fun conDegradado(e: Element, d: Degradado?): Element = when {
    d != null -> {
        val conClave = e.copy(degradado = degradadoHacia(d))
        if (isTransparent(conClave.backgroundColor)) {
            conClave.copy(backgroundColor = d.desde, fillStyle = FillStyle.SOLID)
        } else conClave
    }
    e.degradado != null && e.degradadoDe == null -> e
    else -> e.copy(degradado = null)
}

/** [e] con o sin raya-punto (el `strokeStyle` pasa a `dashed`, la reserva). */
fun conRayaPunto(e: Element, si: Boolean): Element =
    if (si) e.copy(strokeStyle = StrokeStyle.DASHED, trazoRayaPunto = JsonPrimitive(true))
    else if (e.trazoRayaPunto != null) e.copy(trazoRayaPunto = null) else e

/** Lo de lámina que pierde un arco al recortarlo: el cierre, la marca y el degradado (si se entendían). */
fun sinLamina(e: Element): Element = conDegradado(conMarca(conCierre(e, null), null), null)

// ---------------------------------------------------------------------------
// Trazo raya-punto

/**
 * **Los intervalos del raya-punto** para un pincel de **punta redonda** (lienzo, SVG y PDF lo son):
 * se ven raya `5w+4`, hueco `2w+2`, punto `w`, hueco `2w+2` (el patrón del PC). Como la punta
 * redonda alarga cada guion `w/2` por cada lado, aquí se le resta a la raya y al punto lo que la
 * punta añade y se le suma a los huecos; el periodo es el mismo.
 */
fun intervalosRayaPunto(grosor: Double): DoubleArray {
    val w = max(1.0, grosor)
    return doubleArrayOf(4 * w + 4, 3 * w + 2, 0.01, 3 * w + 2)
}

// ---------------------------------------------------------------------------
// Ángulos

fun anguloDe(c: Pt, p: Pt): Double = atan2(p.y - c.y, p.x - c.x)

fun aUnaVuelta(a: Double): Double {
    val r = ((a % (2 * PI)) + 2 * PI) % (2 * PI)
    return if (r >= 2 * PI) 0.0 else r
}

fun enLaCircunferencia(c: Pt, r: Double, a: Double): Pt = Pt(c.x + r * cos(a), c.y + r * sin(a))

/** El ángulo menor entre los rayos `v→p1` y `v→p2`: `(inicio, barrido)` con |barrido| ≤ π. */
fun anguloMenor(v: Pt, p1: Pt, p2: Pt): Pair<Double, Double> {
    val a1 = anguloDe(v, p1)
    var d = anguloDe(v, p2) - a1
    while (d > PI) d -= 2 * PI
    while (d <= -PI) d += 2 * PI
    return a1 to d
}

/** Si [a] cae en el tramo que empieza en [inicio] y barre [barrido]. */
fun dentroDelBarrido(a: Double, inicio: Double, barrido: Double): Boolean {
    if (abs(barrido) >= 2 * PI - 1e-5) return true
    val d = if (barrido >= 0) aUnaVuelta(a - inicio) else aUnaVuelta(inicio - a)
    return d <= abs(barrido) + 1e-5
}

// ---------------------------------------------------------------------------
// Constructoras (las de la herramienta y las pruebas)

/** Un arco exacto de la circunferencia `(c, r)`, con el estilo [s]. */
fun arcoExacto(c: Pt, r: Double, inicio: Double, barrido: Double, s: ItemStyle): Element {
    val rr = abs(r)
    return newElement(ElementType.ARC, c.x - rr, c.y - rr, s, width = 2 * rr, height = 2 * rr)
        .copy(arcStart = inicio, arcSweep = barrido.coerceIn(-2 * PI, 2 * PI))
}

/** El centro de la circunferencia por tres puntos (null si están en línea). */
fun circuncentro(a: Pt, b: Pt, c: Pt): Pt? {
    val d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
    val escala = max(max(hypot(a.x - b.x, a.y - b.y), hypot(b.x - c.x, b.y - c.y)), 1.0)
    if (abs(d) < 1e-4 * escala * escala) return null
    val a2 = a.x * a.x + a.y * a.y
    val b2 = b.x * b.x + b.y * b.y
    val c2 = c.x * c.x + c.y * c.y
    return Pt(
        (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d,
        (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d
    )
}

/** `(centro, radio, inicio, barrido)` del arco que empieza en [a], pasa por [m] y acaba en [b]. */
fun arcoPorTresPuntos(a: Pt, m: Pt, b: Pt): DoubleArray? {
    val c = circuncentro(a, m, b) ?: return null
    val inicio = anguloDe(c, a)
    val hastaB = aUnaVuelta(anguloDe(c, b) - inicio)
    val hastaM = aUnaVuelta(anguloDe(c, m) - inicio)
    val barrido = if (hastaM <= hastaB) hastaB else hastaB - 2 * PI
    return doubleArrayOf(c.x, c.y, hypot(a.x - c.x, a.y - c.y), inicio, barrido)
}

/** Radio de fábrica de una marca de ángulo. */
const val RADIO_MARCA = 22.0

/** A cuántos px de pantalla del vértice se busca el ángulo. */
const val ALCANCE_ANGULO_PX = 60.0

/**
 * **La marca del ángulo** de vértice [v] entre los rayos a [p1] y [p2] (el menor), radio [r]:
 * un arco cerrado como sector con su `marcaAngulo`. Liso y de 1,5 de grosor, como en el PC.
 */
fun marcaDeAngulo(v: Pt, p1: Pt, p2: Pt, r: Double, marca: MarcaAngulo, s: ItemStyle): Element {
    val (inicio, barrido) = anguloMenor(v, p1, p2)
    val base = arcoExacto(v, r, inicio, barrido, s)
    return base.copy(
        strokeWidth = 1.5,
        roughness = Element.ROUGHNESS_ARCHITECT,
        cierreArco = cierreHacia(CierreArco.SECTOR),
        marcaAngulo = marcaHacia(marca.copy(arcos = marca.arcos.coerceIn(1, 3)))
    )
}

// ---------------------------------------------------------------------------
// Las letras griegas de los ángulos (17-oct-2026)

/** α, β, θ, γ y ω primero (las que pidió el usuario, en su orden) y luego las de siempre. */
val LETRAS_DE_ANGULO = listOf(
    "α", "β", "θ", "γ", "ω", "δ", "φ", "ε", "λ", "μ", "ρ", "σ", "τ", "ψ", "η", "κ"
)

/** La letra número [i]: pasada la última, α1, β1… como los puntos siguen con A1 tras la Z. */
fun letraDeAngulo(i: Int): String {
    val n = LETRAS_DE_ANGULO.size
    val letra = LETRAS_DE_ANGULO[i % n]
    val vuelta = i / n
    return if (vuelta == 0) letra else "$letra$vuelta"
}

/** Las letras que ya llevan las marcas del dibujo (sin [excepto]). */
fun letrasDeAnguloUsadas(elementos: List<Element>, excepto: String? = null): List<String> =
    elementos.asSequence()
        .filter { !it.isDeleted && it.id != excepto && it.marcaAngulo != null }
        .mapNotNull { it.marca?.valor?.trim() }
        .filter { it.isNotEmpty() }
        .toList()

/** La siguiente libre, mirando el dibujo y no un contador: borrar la β la deja libre. */
fun siguienteLetraDeAngulo(usadas: List<String>): String {
    val set = usadas.toHashSet()
    for (i in 0 until 10_000) {
        val l = letraDeAngulo(i)
        if (l !in set) return l
    }
    return LETRAS_DE_ANGULO[0]
}

/** La de al lado (paso > 0 la siguiente, < 0 la anterior), saltando las usadas. */
fun letraVecina(actual: String?, paso: Int, usadas: List<String>): String {
    val i = LETRAS_DE_ANGULO.indexOf(actual)
    if (actual == null || i < 0) return siguienteLetraDeAngulo(usadas)
    val n = LETRAS_DE_ANGULO.size
    val d = if (paso >= 0) 1 else -1
    var j = i
    repeat(n) {
        j = ((j + d) % n + n) % n
        val l = LETRAS_DE_ANGULO[j]
        if (l !in usadas) return l
    }
    return LETRAS_DE_ANGULO[i]
}

// ---------------------------------------------------------------------------
// La herramienta Ángulo: dónde se juntan dos rayas cerca del toque

/** Los tramos rectos de las rayas (línea, flecha, cota) y los lados de rectángulos y rombos. */
private fun tramosRectos(elementos: List<Element>): List<Pair<Pt, Pt>> {
    val v = ArrayList<Pair<Pt, Pt>>()
    for (e in elementos) {
        if (e.isDeleted) continue
        when (e.type) {
            ElementType.LINE, ElementType.ARROW, ElementType.MEASURE,
            ElementType.RECTANGLE, ElementType.DIAMOND ->
                for ((a, b) in segmentosDe(e)) if (hypot(a.x - b.x, a.y - b.y) > 0.5) v += a to b
            else -> Unit
        }
    }
    return v
}

/**
 * **El ángulo que se quiere marcar con un toque en [p]**: el vértice más cercano donde se juntan
 * dos rayas (a menos de [alcance]) y los dos lados que encierran el toque. Se miran **todas** las
 * rayas que pasan por el vértice: en uno de donde salen cuatro, el toque cae entre las vecinas.
 * Devuelve `(vértice, punto del lado 1, punto del lado 2)`.
 */
fun anguloEn(elementos: List<Element>, p: Pt, alcance: Double): Triple<Pt, Pt, Pt>? {
    val tramos = tramosRectos(elementos)
    var mejorD = Double.MAX_VALUE
    var v: Pt? = null
    for (i in tramos.indices) for (j in i + 1 until tramos.size) {
        val x = interseccion(tramos[i].first, tramos[i].second, tramos[j].first, tramos[j].second) ?: continue
        val d = hypot(x.x - p.x, x.y - p.y)
        if (d <= alcance && d < mejorD) { mejorD = d; v = x }
    }
    val vertice = v ?: return null
    val rayos = ArrayList<Pt>()
    for ((a, b) in tramos) {
        if (distanceToSegment(vertice, a, b) > 0.75) continue
        for (extremo in arrayOf(a, b)) {
            if (hypot(extremo.x - vertice.x, extremo.y - vertice.y) > 1.0) rayos += extremo
        }
    }
    if (rayos.size < 2) return null
    val ac = anguloDe(vertice, p)
    var antes: Pt? = null
    var dAntes = Double.MAX_VALUE
    var despues: Pt? = null
    var dDespues = Double.MAX_VALUE
    for (r in rayos) {
        val d = aUnaVuelta(anguloDe(vertice, r) - ac)
        if (d < dDespues) { dDespues = d; despues = r }
        val d2 = aUnaVuelta(ac - anguloDe(vertice, r))
        if (d2 < dAntes) { dAntes = d2; antes = r }
    }
    val p1 = antes ?: return null
    val p2 = despues ?: return null
    if (abs(anguloDe(vertice, p1) - anguloDe(vertice, p2)) < 1e-9) return null
    return Triple(vertice, p1, p2)
}

/**
 * **La marca que pone la herramienta Ángulo en [modo]**: con letra, la siguiente griega libre;
 * el recto (a medio grado, o siempre en [ModoAngulo.RECTO]) con su cuadradito y sin letra.
 */
fun marcaConModo(
    modo: ModoAngulo, v: Pt, p1: Pt, p2: Pt, r: Double, arcos: Int, usadas: List<String>, s: ItemStyle
): Element {
    val (_, barrido) = anguloMenor(v, p1, p2)
    val recto = modo == ModoAngulo.RECTO || abs(abs(barrido) - PI / 2) < Math.toRadians(0.5)
    val marca = when {
        recto -> MarcaAngulo(arcos = 1, recto = true)
        modo == ModoAngulo.GRIEGA -> MarcaAngulo(arcos = arcos, valor = siguienteLetraDeAngulo(usadas))
        else -> MarcaAngulo(arcos = arcos)
    }
    return marcaDeAngulo(v, p1, p2, if (recto) r * 0.6 else r, marca, s)
}

// ---------------------------------------------------------------------------
// Lo que se pinta

/** El centro y el radio de un arco de lámina (el de la caja: `min(ancho, alto)/2`). */
private fun centroDe(e: Element): Pt = Pt(e.x + e.width / 2, e.y + e.height / 2)

/** Muestras por vuelta del arco de una lámina. */
private const val PASOS_LAMINA = 96

/**
 * El contorno cerrado de un arco con cierre (sector o segmento) sin girar y en el mundo: el arco
 * muestreado y, si es sector y no la vuelta entera, el centro. Null si va abierto.
 */
fun contornoCerrado(e: Element): List<Pt>? {
    if (e.type != ElementType.ARC) return null
    val barrido = e.arcSweep ?: return null
    val cierre = e.cierre ?: (if (e.marcaAngulo is JsonObject) CierreArco.SECTOR else return null)
    val rel = puntosDelArco(e, PASOS_LAMINA)
    if (rel.size < 2) return null
    val out = ArrayList<Pt>(rel.size + 1)
    for (q in rel) out += Pt(e.x + q.x, e.y + q.y)
    if (cierre == CierreArco.SECTOR && abs(barrido) < 2 * PI - 1e-4) out += centroDe(e)
    return out
}

/**
 * Lo que se pinta de un arco de lámina, en el mundo y sin girar: el polígono del relleno (null si
 * no lleva), las polilíneas del trazo (la primera cerrada si [primeroCerrado]) y, si la marca tiene
 * valor, dónde va (sobre la bisectriz, por fuera) para que quien sepa medir texto lo coloque.
 */
class FiguraDeLamina(
    val relleno: List<Pt>?,
    val trazos: List<List<Pt>>,
    val primeroCerrado: Boolean,
    val valor: String?,
    val centro: Pt,
    val radio: Double,
    val bisectriz: Double,
    val tamLetra: Double
) {
    /** El centro del texto del valor, con su medida (ancho × alto). */
    fun sitioDelValor(ancho: Double, alto: Double): Pt {
        val lejos = radio + 4 + 0.5 * (ancho * abs(cos(bisectriz)) + alto * abs(sin(bisectriz)))
        return enLaCircunferencia(centro, lejos, bisectriz)
    }
}

/** Tamaño de fábrica del valor de una marca de ángulo. */
const val TAM_VALOR_MARCA = 14.0

/**
 * **Las piezas de un arco de lámina** (`ordenes_de_arco` del PC). [conRelleno] dice si se rellena
 * (fondo puesto o degradado). Null si es un arco de los de siempre.
 */
fun figuraDeLamina(e: Element, conRelleno: Boolean): FiguraDeLamina? {
    if (e.type != ElementType.ARC) return null
    val barrido = e.arcSweep ?: return null
    val inicio = e.arcStart ?: 0.0
    val c = centroDe(e)
    val r = min(e.width, e.height) / 2
    val m = e.marca
    val tam = e.fontSize ?: TAM_VALOR_MARCA
    if (m != null) {
        val bis = inicio + barrido / 2
        if (m.recto) {
            val u1x = cos(inicio); val u1y = sin(inicio)
            val u2x = cos(inicio + barrido); val u2y = sin(inicio + barrido)
            val a = Pt(c.x + u1x * r, c.y + u1y * r)
            val b = Pt(a.x + u2x * r, a.y + u2y * r)
            val d = Pt(c.x + u2x * r, c.y + u2y * r)
            return FiguraDeLamina(
                if (conRelleno) listOf(c, a, b, d) else null, listOf(listOf(a, b, d)), false,
                m.valor, c, r, bis, tam
            )
        }
        val relleno = if (conRelleno) contornoCerrado(e) else null
        val sep = max(3.0, e.strokeWidth * 2.2)
        val trazos = ArrayList<List<Pt>>(3)
        for (k in 0 until m.arcos.coerceIn(1, 3)) {
            val rk = r - k * sep
            if (rk <= 1.0) break
            val pasos = max(8, (PASOS_LAMINA * abs(barrido) / (2 * PI)).toInt())
            trazos += (0..pasos).map { i -> enLaCircunferencia(c, rk, inicio + barrido * i / pasos) }
        }
        return FiguraDeLamina(relleno, trazos, false, m.valor, c, r, bis, tam)
    }
    if (e.cierre == null) return null
    val poligono = contornoCerrado(e) ?: return null
    return FiguraDeLamina(
        if (conRelleno) poligono else null, listOf(poligono), true, null, c, r, inicio + barrido / 2, tam
    )
}

/**
 * **La caja con la que se mide un degradado** (sus fracciones): la del trozo de arco que se ve
 * (hasta el centro si es sector o marca), la del contorno de una región, la de la figura en lo
 * demás. Sin girar, como todo: el giro lo pone quien pinta.
 */
fun cajaDelDegradado(e: Element): Bounds = when (e.type) {
    ElementType.ARC -> cajaDelArco(e)
    ElementType.REGION -> {
        val pts = e.points
        if (pts.isNullOrEmpty()) Bounds(e.x, e.y, e.x + e.width, e.y + e.height) else {
            var x1 = Double.MAX_VALUE; var y1 = Double.MAX_VALUE
            var x2 = -Double.MAX_VALUE; var y2 = -Double.MAX_VALUE
            for (q in pts) {
                x1 = min(x1, e.x + q.x); y1 = min(y1, e.y + q.y)
                x2 = max(x2, e.x + q.x); y2 = max(y2, e.y + q.y)
            }
            Bounds(x1, y1, x2, y2)
        }
    }
    else -> Bounds(e.x, e.y, e.x + e.width, e.y + e.height)
}

/** Los dos puntos del degradado en el mundo: origen y destino (radial: centro y borde). */
fun puntosDelDegradado(e: Element, d: Degradado): Pair<Pt, Pt> {
    val b = cajaDelDegradado(e)
    fun en(f: Pt) = Pt(b.x1 + f.x * (b.x2 - b.x1), b.y1 + f.y * (b.y2 - b.y1))
    return en(d.origen) to en(d.destino)
}

/** Cuántas bandas tiene un degradado escrito a bandas (el PDF). */
const val BANDAS_DEGRADADO = 48

/** El color del degradado a la altura [t] (0 en `desde`, 1 en `hasta`), como `[r, g, b, a]`. */
fun colorDelDegradado(d: Degradado, t: Double): IntArray {
    val a = rgbaDeHex(d.desde) ?: intArrayOf(0, 0, 0, 255)
    val b = rgbaDeHex(d.hasta) ?: a
    val tt = t.coerceIn(0.0, 1.0)
    return IntArray(4) { mezcla(a[it], b[it], tt) }
}

/**
 * **Extiende un arco por su circunferencia** desde la punta más cercana a [p] hasta lo primero
 * que toca de [otros] (sin pasar de la vuelta entera). Null si no topa con nada o no es un arco
 * de circunferencia (óvalo achatado).
 */
fun extenderArco(e: Element, otros: List<Element>, p: Pt): Element? {
    if (e.type != ElementType.ARC) return null
    val barrido = e.arcSweep ?: return null
    val inicio = e.arcStart ?: 0.0
    val rx = e.width / 2
    val ry = e.height / 2
    if (rx <= 0 || abs(rx - ry) > 0.02 * max(rx, ry) + 0.5) return null
    val c = centroDe(e)
    val r = (rx + ry) / 2
    val giro = e.angle
    fun mundo(t: Double) = enLaCircunferencia(c, r, t + giro)
    val fin = inicio + barrido
    val porInicio = hypot(p.x - mundo(inicio).x, p.y - mundo(inicio).y) <
        hypot(p.x - mundo(fin).x, p.y - mundo(fin).y)
    val s = if (barrido >= 0) 1.0 else -1.0
    val desde = if (porInicio) inicio else fin
    val sentido = if (porInicio) -s else s
    val resto = 2 * PI - abs(barrido)
    if (resto < 1e-3) return null
    val paredes = otros.filter { !it.isDeleted && it.id != e.id }.flatMap { segmentosDe(it) }
    if (paredes.isEmpty()) return null
    val paso = Math.toRadians(0.5)
    val pasos = kotlin.math.ceil(resto / paso).toInt()
    var anterior = mundo(desde)
    for (k in 1..pasos) {
        val t = desde + sentido * min(k * paso, resto)
        val q = mundo(t)
        var mejor: Pt? = null
        var mejorD = Double.MAX_VALUE
        for ((a, b) in paredes) {
            val x = interseccion(anterior, q, a, b) ?: continue
            val d = hypot(x.x - anterior.x, x.y - anterior.y)
            if ((k > 1 || d > 0.5) && d < mejorD) { mejorD = d; mejor = x }
        }
        if (mejor != null) {
            val ang = anguloDe(c, mejor) - giro
            val extra = min(if (sentido > 0) aUnaVuelta(ang - desde) else aUnaVuelta(desde - ang), resto)
            return if (porInicio) e.copy(arcStart = inicio - s * extra, arcSweep = barrido + s * extra).touched()
            else e.copy(arcSweep = barrido + s * extra).touched()
        }
        anterior = q
    }
    return null
}

/** La marca de ángulo (no recta) que hay bajo [p]: dentro de su sector, hasta un poco por fuera. */
fun marcaBajo(elementos: List<Element>, p: Pt, margen: Double): Element? =
    elementos.lastOrNull { e ->
        if (e.isDeleted || e.type != ElementType.ARC || e.marcaAngulo == null) return@lastOrNull false
        val m = e.marca ?: return@lastOrNull false
        if (m.recto) return@lastOrNull false
        val c = centroDe(e)
        val r = min(e.width, e.height) / 2
        val d = hypot(p.x - c.x, p.y - c.y)
        d <= r + margen && dentroDelBarrido(anguloDe(c, p) - e.angle, e.arcStart ?: 0.0, e.arcSweep ?: 0.0)
    }

/** Tangentes desde [p] a la circunferencia `(c, r)` (null si [p] está dentro). */
fun tangentesDesde(p: Pt, c: Pt, r: Double): Pair<Pt, Pt>? {
    val d = hypot(p.x - c.x, p.y - c.y)
    if (d <= r + 1e-3 || r <= 0) return null
    val base = anguloDe(c, p)
    val abre = acos(r / d)
    return enLaCircunferencia(c, r, base + abre) to enLaCircunferencia(c, r, base - abre)
}
