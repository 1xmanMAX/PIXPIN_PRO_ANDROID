package com.forge.pixpin.motor

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * El perímetro de **cualquier** figura, reducido a tramos rectos.
 *
 * Existe porque dos cosas del motor necesitan exactamente lo mismo y antes cada
 * una se lo hacía por su cuenta a medias:
 *
 * - **Las intersecciones**, para engancharse donde se cruzan dos figuras.
 * - **El relleno de un espacio cerrado**, que necesita saber por dónde pasan las
 *   paredes que lo encierran.
 *
 * El enganche a intersecciones solo sabía cruzar rectángulos, rombos y líneas.
 * Con una elipse, un arco, un rectángulo redondeado, una imagen o un número de
 * serie de por medio no había intersección que valiera: justo las figuras cuyo
 * cruce es **más** imposible de acertar a pulso, porque no tienen ni un vértice
 * cerca del punto donde se cortan. Aquí se resuelve de una vez y para todos los
 * tipos, y quien añada un tipo nuevo lo tiene que declarar en un `when`
 * exhaustivo — el compilador no deja olvidarse.
 *
 * **Las curvas se aproximan por tramos rectos**, con el paso acotado por arriba
 * y por abajo. No es una pérdida: el error de muestreo queda muy por debajo del
 * radio de captura del dedo, y a cambio cruzar «lo que sea contra lo que sea» es
 * un solo algoritmo de dos segmentos en vez de un caso por pareja de tipos —que
 * con doce tipos serían setenta y ocho parejas.
 *
 * Sin Android y sin estado, como el resto del núcleo.
 */

/** Un trozo de perímetro ya muestreado. [cerrado] une el último con el primero. */
data class Contorno(val puntos: List<Pt>, val cerrado: Boolean)

/**
 * Paso de muestreo por defecto, en píxeles de escena.
 *
 * Seis píxeles es la distancia a la que una curva deja de leerse como polígono
 * a tamaño normal, y sigue siendo menos de la mitad del radio con el que el
 * dedo se engancha, así que el error de aproximar no llega a notarse nunca.
 */
const val PASO_PERIMETRO = 6.0

/** Tope de muestras por contorno: una elipse enorme no necesita mil tramos. */
private const val MAX_MUESTRAS = 240

/** Mínimo de muestras de una curva cerrada, para que no salga un polígono. */
private const val MIN_MUESTRAS = 24

/**
 * Los contornos de [e] en coordenadas de escena y **ya girados**.
 *
 * Una figura con caja da un contorno cerrado; una raya, uno abierto; y el lápiz
 * o la línea que se cierran sobre sí mismos, uno cerrado. Los tipos que no
 * dibujan ninguna línea —el foco, que es una sombra— no dan ninguno.
 *
 * Las que se pintan por su caja (la imagen, el texto, el mosaico, la hoja) dan
 * **su caja**: es lo que se ve de ellas y por tanto lo que uno espera cruzar o
 * usar de pared.
 */
fun contornosDe(e: Element, paso: Double = PASO_PERIMETRO): List<Contorno> {
    if (e.isDeleted) return emptyList()
    val c = getElementAbsoluteCoords(e)
    val centro = Pt(c.cx, c.cy)

    fun girar(pts: List<Pt>): List<Pt> =
        if (e.angle == 0.0) pts else pts.map { pointRotateRads(it, centro, e.angle) }

    fun cerrado(pts: List<Pt>) =
        if (pts.size < 2) emptyList() else listOf(Contorno(girar(pts), cerrado = true))

    return when (e.type) {
        // **Un punto no tiene contorno: es un sitio.** No corta a nadie ni nadie
        // lo corta, así que no aporta intersecciones — pero sí es de lo primero
        // a lo que se engancha el dedo, y de eso se encarga el imán aparte.
        ElementType.PUNTO -> emptyList()

        // El redondeo cambia el contorno de verdad: una esquina redondeada no
        // corta donde cortaría el pico. Se reusa el mismo muestreo que dibuja.
        ElementType.RECTANGLE ->
            cerrado(if (e.roundness == null) esquinasDe(c) else roundedRectPoints(c, e))

        ElementType.DIAMOND ->
            cerrado(if (e.roundness == null) diamondPoints(c) else roundedDiamondPoints(c, e))

        // El número de serie es un círculo dentro de su caja, no la caja.
        ElementType.ELLIPSE, ElementType.SERIAL ->
            cerrado(puntosDeElipse(c, paso))

        // El arco ya viene girado de fábrica: sus puntos salen de la caja del
        // óvalo aplicándole la rotación del elemento. Girarlo otra vez lo
        // mandaría al doble de ángulo.
        ElementType.ARC -> {
            val pts = puntosDelArco(e).map { Pt(e.x + it.x, e.y + it.y) }
            if (pts.size < 2) emptyList() else listOf(Contorno(pts, cerrado = false))
        }

        // La raya: se cierra sola si el trazo vuelve a su punto de partida, que
        // es lo mismo que decide si se puede rellenar.
        ElementType.LINE, ElementType.FREEDRAW -> {
            val pts = puntosDeTrazado(e)
            if (pts.size < 2) emptyList()
            else listOf(Contorno(girar(pts), cerrado = isPathALoop(e.points)))
        }

        ElementType.ARROW, ElementType.MEASURE -> {
            val pts = puntosDeTrazado(e)
            if (pts.size < 2) emptyList()
            else listOf(Contorno(girar(pts), cerrado = false))
        }

        // El relleno de una región **también** es pared: rellenar el hueco que
        // deja otro relleno tiene que respetar su borde.
        ElementType.REGION -> anillosDeRegion(e).mapNotNull {
            if (it.size < 3) null else Contorno(girar(it), cerrado = true)
        }

        ElementType.IMAGE, ElementType.TEXT, ElementType.MOSAIC, ElementType.FRAME,
        ElementType.ESCALA_GRAFICA -> cerrado(esquinasDe(c))

        // El foco no tiene borde: es una sombra sobre todo lo demás. Ni se cruza
        // ni encierra nada.
        ElementType.SPOTLIGHT -> emptyList()
    }
}

/**
 * Los tramos rectos de [e], listos para cruzarlos con los de otro.
 *
 * Es la forma en que lo consumen tanto el enganche como el relleno: a los dos
 * les da igual de qué figura venía cada tramo.
 */
fun segmentosDe(e: Element, paso: Double = PASO_PERIMETRO): List<Pair<Pt, Pt>> =
    contornosDe(e, paso).flatMap { contorno ->
        val pts = if (contorno.cerrado && contorno.puntos.size > 2) {
            contorno.puntos + contorno.puntos.first()
        } else contorno.puntos
        pts.zipWithNext()
    }

/** Las cuatro esquinas de una caja, sin girar. */
private fun esquinasDe(c: AbsoluteCoords): List<Pt> =
    listOf(Pt(c.x1, c.y1), Pt(c.x2, c.y1), Pt(c.x2, c.y2), Pt(c.x1, c.y2))

/**
 * La elipse muestreada, con **el paso adaptado a su tamaño**.
 *
 * Un número fijo de muestras se equivoca por los dos lados: en un círculo de
 * diez píxeles sobran setenta tramos, y en uno de dos mil se ve el polígono.
 * Aquí las muestras salen del perímetro y se acotan por los dos extremos.
 */
private fun puntosDeElipse(c: AbsoluteCoords, paso: Double): List<Pt> {
    val rx = (c.x2 - c.x1) / 2
    val ry = (c.y2 - c.y1) / 2
    if (rx <= 0.0 || ry <= 0.0) return emptyList()
    // Aproximación de Ramanujan al perímetro: sobra de sobra para contar tramos.
    val h = ((rx - ry) * (rx - ry)) / ((rx + ry) * (rx + ry))
    val perimetro = Math.PI * (rx + ry) * (1 + (3 * h) / (10 + kotlin.math.sqrt(4 - 3 * h)))
    val n = (perimetro / paso.coerceAtLeast(0.5)).toInt()
        .coerceIn(MIN_MUESTRAS, MAX_MUESTRAS)
    return (0 until n).map {
        val a = 2 * Math.PI * it / n
        Pt(c.cx + rx * cos(a), c.cy + ry * sin(a))
    }
}

/**
 * Dónde se cortan dos tramos, o null si no se cortan **dentro de los dos**.
 *
 * Prolongar las rectas hasta que se toquen daría puntos en mitad de la nada,
 * lejos de cualquier trazo, y el imán tiraría del dedo hacia sitios que el
 * usuario no ve.
 */
internal fun interseccion(a1: Pt, a2: Pt, b1: Pt, b2: Pt): Pt? {
    val d1x = a2.x - a1.x
    val d1y = a2.y - a1.y
    val d2x = b2.x - b1.x
    val d2y = b2.y - b1.y
    val den = d1x * d2y - d1y * d2x
    if (kotlin.math.abs(den) < 1e-9) return null // paralelos

    val t = ((b1.x - a1.x) * d2y - (b1.y - a1.y) * d2x) / den
    val u = ((b1.x - a1.x) * d1y - (b1.y - a1.y) * d1x) / den
    if (t < 0.0 || t > 1.0 || u < 0.0 || u > 1.0) return null
    return Pt(a1.x + d1x * t, a1.y + d1y * t)
}

/**
 * Todos los cruces que hay a menos de [radio] de [p].
 *
 * **Los tramos se filtran antes de cruzarlos.** Cruzar todo contra todo es
 * cuadrático en el número de tramos, y ahora que las curvas aportan hasta
 * doscientos cada una eso se notaría en cada fotograma: dos elipses grandes
 * serían cuarenta mil parejas. Como el resultado solo interesa si cae cerca del
 * dedo, se tira antes todo tramo que ni siquiera pase por el entorno del dedo —
 * y de una elipse entera suelen quedar dos o tres.
 *
 * [consigo] busca además los cruces de una figura **consigo misma**: un garabato
 * que se cruza al volver, o una línea en forma de lazo. Son puntos igual de
 * difíciles de acertar que los de dos figuras distintas.
 */
fun interseccionesCerca(
    elementos: List<Element>,
    p: Pt,
    radio: Double,
    excluir: String? = null,
    consigo: Boolean = true
): List<Anclaje> {
    val candidatos = elementos.filter { e ->
        e.id != excluir && !e.isDeleted && !e.isFrame &&
            getElementBounds(e).let {
                p.x >= it.x1 - radio && p.x <= it.x2 + radio &&
                    p.y >= it.y1 - radio && p.y <= it.y2 + radio
            }
    }
    if (candidatos.isEmpty()) return emptyList()

    // Cada figura, reducida a los tramos que rozan el entorno del dedo.
    val cerca = candidatos
        .map { e -> e.id to tramosIndexados(e).filter { it.cerca(p, radio) } }
        .filter { it.second.isNotEmpty() }

    val out = mutableListOf<Anclaje>()
    for (i in cerca.indices) {
        val (id, tramos) = cerca[i]
        if (consigo) {
            for (a in tramos.indices) {
                for (b in a + 1 until tramos.size) {
                    // Dos tramos **seguidos** comparten un extremo, y ese
                    // «cruce» es el propio vértice: ya engancha por su cuenta.
                    if (tramos[a].seguidoDe(tramos[b])) continue
                    interseccion(tramos[a].a, tramos[a].b, tramos[b].a, tramos[b].b)
                        ?.let { out += Anclaje(it, TipoAnclaje.INTERSECCION, id) }
                }
            }
        }
        for (j in i + 1 until cerca.size) {
            for (x in tramos) {
                for (y in cerca[j].second) {
                    interseccion(x.a, x.b, y.a, y.b)?.let {
                        out += Anclaje(it, TipoAnclaje.INTERSECCION, id)
                    }
                }
            }
        }
    }
    return out
}

/**
 * Un tramo que **sabe dónde estaba**.
 *
 * Sin esto no se puede distinguir un cruce de verdad del vértice donde se juntan
 * dos tramos seguidos, y hay dos formas de equivocarse, las dos vividas:
 *
 * - Mirando la posición en la lista **ya filtrada**: al quedarse solo con los
 *   tramos cercanos al dedo, dos que estaban lejísimos en el trazo pasan a ser
 *   vecinos en la lista, y el cruce de verdad entre ellos se descarta por
 *   «seguidos». Es lo que hacía que un garabato cruzado consigo mismo no
 *   enganchara nunca.
 * - Olvidando que en un contorno **cerrado el último tramo va seguido del
 *   primero**. Ahí es donde el anillo cierra, y como el muestreo de una elipse
 *   empieza en el punto de ángulo cero —el de la derecha—, todo círculo
 *   fabricaba una «intersección» consigo mismo justo ahí. Enganchaba, sí, pero
 *   por el motivo equivocado y con el nombre equivocado.
 */
private class TramoIndexado(
    val a: Pt,
    val b: Pt,
    /** A qué contorno del elemento pertenece: dos distintos nunca van seguidos. */
    val contorno: Int,
    val indice: Int,
    val cerrado: Boolean,
    val total: Int
) {
    fun cerca(p: Pt, radio: Double): Boolean = distanceToSegment(p, a, b) <= radio

    fun seguidoDe(otro: TramoIndexado): Boolean {
        if (contorno != otro.contorno) return false
        val d = kotlin.math.abs(indice - otro.indice)
        return d <= 1 || (cerrado && d == total - 1)
    }
}

private fun tramosIndexados(e: Element): List<TramoIndexado> =
    contornosDe(e).flatMapIndexed { c, contorno ->
        val pts = if (contorno.cerrado && contorno.puntos.size > 2) {
            contorno.puntos + contorno.puntos.first()
        } else contorno.puntos
        val total = pts.size - 1
        (0 until total).map { i ->
            TramoIndexado(pts[i], pts[i + 1], c, i, contorno.cerrado, total)
        }
    }

/**
 * El punto del perímetro de [e] más cercano a [p], si cae a menos de [radio].
 *
 * **Es la escuadra.** Los demás enganches llevan a puntos sueltos —un vértice,
 * un centro, un cruce—, y con eso se puede empezar y terminar un trazo sobre una
 * guía, pero no *recorrerla*: en medio de un lado no hay ningún punto notable al
 * que pegarse, así que la raya salía torcida entre esquina y esquina y la curva
 * de un círculo no había manera de repasarla.
 *
 * Pegándose a todo el borde, la guía se comporta como una plantilla de dibujo de
 * las de siempre: se apoya el lápiz en el canto y el trazo sale por donde va el
 * canto, no por donde tiembla la mano.
 */
fun puntoEnElPerimetro(e: Element, p: Pt, radio: Double): Pt? {
    var mejor: Pt? = null
    var mejorDistancia = radio
    for ((a, b) in segmentosDe(e)) {
        val q = proyeccionEnSegmento(p, a, b)
        val d = hypot(q.x - p.x, q.y - p.y)
        if (d < mejorDistancia) {
            mejorDistancia = d
            mejor = q
        }
    }
    return mejor
}

/** El punto del segmento `ab` más cercano a [p]. Con el pie fuera, el extremo. */
private fun proyeccionEnSegmento(p: Pt, a: Pt, b: Pt): Pt {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val largoSq = dx * dx + dy * dy
    if (largoSq == 0.0) return a
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / largoSq).coerceIn(0.0, 1.0)
    return Pt(a.x + t * dx, a.y + t * dy)
}

/** Largo de una polilínea. Lo usan el muestreo y las pruebas. */
internal fun largoDe(puntos: List<Pt>): Double =
    puntos.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
