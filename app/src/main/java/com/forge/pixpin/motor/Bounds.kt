package com.forge.pixpin.motor

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Cajas y transformaciones. Port de `bounds.ts` y de la parte de `math` que
 * usa el resto del módulo.
 *
 * Convenio de Excalidraw, respetado aquí: una caja es la tupla
 * `[x1, y1, x2, y2]`, y las «coordenadas absolutas» de un elemento añaden el
 * centro: `[x1, y1, x2, y2, cx, cy]`. El centro va aparte y no se recalcula
 * porque para los elementos lineales **no** es el centro de la caja.
 */

/** Caja alineada con los ejes. */
data class Bounds(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val width: Double get() = x2 - x1
    val height: Double get() = y2 - y1
    val midX: Double get() = (x1 + x2) / 2
    val midY: Double get() = (y1 + y2) / 2
}

/** Coordenadas absolutas con centro de rotación. */
data class AbsoluteCoords(
    val x1: Double, val y1: Double, val x2: Double, val y2: Double,
    val cx: Double, val cy: Double
) {
    fun toBounds() = Bounds(x1, y1, x2, y2)
}

/**
 * Gira [p] alrededor de [center] un ángulo en radianes (`pointRotateRads`).
 *
 * Es la operación más repetida del módulo: los tiradores, el picado y el
 * dibujado de cualquier elemento rotado pasan por aquí.
 */
fun pointRotateRads(p: Pt, center: Pt, angle: Double): Pt {
    if (angle == 0.0) return p
    val c = cos(angle)
    val s = sin(angle)
    return Pt(
        (p.x - center.x) * c - (p.y - center.y) * s + center.x,
        (p.x - center.x) * s + (p.y - center.y) * c + center.y
    )
}

/**
 * Coordenadas absolutas **sin rotar** (`getElementAbsoluteCoords`).
 *
 * Para los lineales y el lápiz la caja sale de los puntos y no de
 * `width`/`height`, porque esos dos campos guardan la extensión del trazo
 * original y dejan de valer en cuanto se edita un punto suelto.
 */
fun getElementAbsoluteCoords(element: Element): AbsoluteCoords {
    if (element.isFreeDraw || element.isLinear) {
        val pts = element.points
        if (!pts.isNullOrEmpty()) {
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (p in pts) {
                minX = min(minX, p.x); minY = min(minY, p.y)
                maxX = max(maxX, p.x); maxY = max(maxY, p.y)
            }
            val x1 = element.x + minX
            val y1 = element.y + minY
            val x2 = element.x + maxX
            val y2 = element.y + maxY
            return AbsoluteCoords(x1, y1, x2, y2, (x1 + x2) / 2, (y1 + y2) / 2)
        }
    }
    return AbsoluteCoords(
        element.x,
        element.y,
        element.x + element.width,
        element.y + element.height,
        element.x + element.width / 2,
        element.y + element.height / 2
    )
}

/**
 * Caja alineada con los ejes que **envuelve el elemento ya rotado**
 * (`getElementBounds`).
 *
 * Con `angle` a cero coincide con [getElementAbsoluteCoords]; con el elemento
 * girado es mayor, porque un rectángulo inclinado ocupa más caja de la que
 * mide. Es la caja que usan el picado rápido y la selección por área.
 */
fun getElementBounds(element: Element): Bounds {
    val c = getElementAbsoluteCoords(element)
    if (element.angle == 0.0) return c.toBounds()

    val center = Pt(c.cx, c.cy)
    val corners = listOf(
        pointRotateRads(Pt(c.x1, c.y1), center, element.angle),
        pointRotateRads(Pt(c.x2, c.y1), center, element.angle),
        pointRotateRads(Pt(c.x2, c.y2), center, element.angle),
        pointRotateRads(Pt(c.x1, c.y2), center, element.angle)
    )
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (p in corners) {
        minX = min(minX, p.x); minY = min(minY, p.y)
        maxX = max(maxX, p.x); maxY = max(maxY, p.y)
    }
    return Bounds(minX, minY, maxX, maxY)
}

/**
 * La caja de **lo que se ve**, que no siempre es la del elemento.
 *
 * Casi todas las figuras ocupan exactamente su caja, y para esas esto es
 * [getElementBounds] y ya está. Dos no:
 *
 * - **El sólido** dibuja fuera de su huella —la proyección se sale por arriba y por un
 *   lado—, así que su caja se queda corta. Y lo que ocupa depende de desde dónde se
 *   mire, así que hay que decírselo: sin [vista] se devuelve la unión de las cuatro, que
 *   sobra bastante. Ver [envolturaDeSolido].
 * - **El arco** guarda el óvalo entero aunque solo pinte una uña de él, así que su caja
 *   se pasa, y a veces muchísimo: es lo que hacía que recortar un círculo dejara un trozo
 *   pequeño con un recuadro de selección enorme alrededor, imposible de encerrar con el
 *   dedo. Ver [cajaDelArco].
 *
 * Es la caja que hay que usar en todo lo que sea *dónde está esto para el que mira*:
 * el recuadro de la selección, encerrar con el dedo, el lazo y el descarte del picado.
 * Lo que es *geometría del elemento* —de dónde sale el óvalo, cómo se estira— sigue
 * yendo por [getElementAbsoluteCoords], que es donde vive esa verdad.
 */
fun envolturaVisible(element: Element, vista: Vista? = null): Bounds = when {
    // Sin vista, la unión de las cuatro: se pasa, pero es lo único honrado cuando no se
    // sabe desde dónde se mira. Ver [envolturaDeSolido].
    element.isSolido ->
        if (vista == null) envolturaDeSolidoSinVista(element)
        else envolturaDeSolido(element, vista)
    // Una imagen tumbada en el suelo ocupa su paralelogramo proyectado, no su rectángulo:
    // con la caja del elemento, el recuadro de la selección caía en otro sitio. Ver
    // [imagenEnElSuelo].
    element.type == ElementType.IMAGE && element.enElSuelo ->
        boundsOfPoints(imagenEnElSuelo(element, vista ?: Vista.CERO))

    element.type == ElementType.ARC -> {
        val caja = cajaDelArco(element)
        if (element.angle == 0.0) caja else {
            // **Se gira alrededor del centro del óvalo**, no del centro de la caja del
            // trozo: es el punto sobre el que gira el elemento, y el que deshacen
            // `toLocal` y el muestreo del arco.
            val c = getElementAbsoluteCoords(element)
            val centro = Pt(c.cx, c.cy)
            boundsOfPoints(
                listOf(
                    pointRotateRads(Pt(caja.x1, caja.y1), centro, element.angle),
                    pointRotateRads(Pt(caja.x2, caja.y1), centro, element.angle),
                    pointRotateRads(Pt(caja.x2, caja.y2), centro, element.angle),
                    pointRotateRads(Pt(caja.x1, caja.y2), centro, element.angle)
                )
            )
        }
    }
    else -> getElementBounds(element)
}

/** Caja que envuelve **lo que se ve** de todos. Ver [envolturaVisible]. */
fun envolturaComun(elements: List<Element>, vista: Vista? = null): Bounds {
    if (elements.isEmpty()) return Bounds(0.0, 0.0, 0.0, 0.0)
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (e in elements) {
        val b = envolturaVisible(e, vista)
        minX = min(minX, b.x1); minY = min(minY, b.y1)
        maxX = max(maxX, b.x2); maxY = max(maxY, b.y2)
    }
    return Bounds(minX, minY, maxX, maxY)
}

/** Caja que envuelve a todos (`getCommonBounds`). */
fun getCommonBounds(elements: List<Element>): Bounds {
    if (elements.isEmpty()) return Bounds(0.0, 0.0, 0.0, 0.0)
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (e in elements) {
        val b = getElementBounds(e)
        minX = min(minX, b.x1); minY = min(minY, b.y1)
        maxX = max(maxX, b.x2); maxY = max(maxY, b.y2)
    }
    return Bounds(minX, minY, maxX, maxY)
}

/**
 * ¿Está [p] dentro de la caja del elemento, deshaciendo antes la rotación?
 *
 * Girar el punto en sentido contrario es mucho más barato que girar la caja y
 * hacer un test contra un cuadrilátero arbitrario, y da el mismo resultado.
 * Es el descarte rápido de `hitElementItself`, que se salta el 99% de los
 * elementos antes del test caro.
 */
fun isPointInRotatedBounds(
    p: Pt, element: Element, threshold: Double
): Boolean {
    val c = getElementAbsoluteCoords(element)
    val local = pointRotateRads(p, Pt(c.cx, c.cy), -element.angle)
    return local.x >= c.x1 - threshold && local.x <= c.x2 + threshold &&
        local.y >= c.y1 - threshold && local.y <= c.y2 + threshold
}

/** ¿La caja [a] está entera dentro de [b]? Selección por área en modo «contener». */
fun boundsContains(b: Bounds, a: Bounds): Boolean =
    a.x1 >= b.x1 && a.y1 >= b.y1 && a.x2 <= b.x2 && a.y2 <= b.y2

/** ¿Se tocan las dos cajas? Selección por área en modo «solapar». */
fun boundsOverlap(a: Bounds, b: Bounds): Boolean =
    a.x1 <= b.x2 && a.x2 >= b.x1 && a.y1 <= b.y2 && a.y2 >= b.y1

/** Distancia de un punto al segmento `ab`. La usa el picado de trazos. */
fun distanceToSegment(p: Pt, a: Pt, b: Pt): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lenSq = dx * dx + dy * dy
    if (lenSq == 0.0) return kotlin.math.hypot(p.x - a.x, p.y - a.y)
    // Proyección acotada al segmento: fuera de [0,1] el punto más cercano es
    // un extremo, no la proyección sobre la recta infinita.
    var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
    t = t.coerceIn(0.0, 1.0)
    return kotlin.math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/**
 * Radio de las esquinas redondeadas (`getCornerRadius`).
 *
 * Dos algoritmos porque uno solo no sirve: el proporcional se ve bien en formas
 * pequeñas pero en un rectángulo enorme deja unas curvas desmedidas, así que a
 * partir de cierto tamaño se pasa a un radio fijo.
 */
fun getCornerRadius(shorterSide: Double, element: Element): Double {
    val roundness = element.roundness ?: return 0.0
    return when (roundness.type) {
        Roundness.PROPORTIONAL_RADIUS, Roundness.LEGACY ->
            shorterSide * DEFAULT_PROPORTIONAL_RADIUS
        Roundness.ADAPTIVE_RADIUS -> {
            val cutoff = DEFAULT_ADAPTIVE_RADIUS / DEFAULT_PROPORTIONAL_RADIUS
            if (shorterSide <= cutoff) shorterSide * DEFAULT_PROPORTIONAL_RADIUS
            else DEFAULT_ADAPTIVE_RADIUS
        }
        else -> 0.0
    }
}

const val DEFAULT_PROPORTIONAL_RADIUS = 0.25
const val DEFAULT_ADAPTIVE_RADIUS = 32.0

/** Normaliza un ángulo a [0, 2π). Evita que las rotaciones acumulen vueltas. */
fun normalizeAngle(angle: Double): Double {
    val twoPi = Math.PI * 2
    val a = angle % twoPi
    return if (a < 0) a + twoPi else a
}


// -------------------------------------------------------------------------
// Encuadre en una página
// -------------------------------------------------------------------------

/**
 * Cómo cae un dibujo dentro de una página de papel.
 *
 * Es la cuenta del PDF, y vive aquí —fuera de todo lo que huela a Android—
 * porque es justo lo que se puede equivocar en silencio: un dibujo recortado
 * por el borde, o pegado a una esquina con el folio medio vacío, no revienta
 * nada y no se ve hasta que alguien lo imprime.
 *
 * [margen] va en las mismas unidades que la página. El resultado encaja el
 * dibujo **entero** y lo **centra**, y nunca amplía por encima de 1:1: estirar
 * un garabato pequeño hasta llenar el folio solo enseña sus imperfecciones.
 *
 * Devuelve null si no hay nada que encuadrar o la página no da ni para el
 * margen — pintar entonces daría un zoom negativo y el dibujo saldría del revés.
 */
fun encuadreEnPagina(
    caja: Bounds,
    anchoPagina: Double,
    altoPagina: Double,
    margen: Double
): Viewport? {
    if (caja.width <= 0 || caja.height <= 0) return null
    val util = anchoPagina - margen * 2
    val utilAlto = altoPagina - margen * 2
    if (util <= 0 || utilAlto <= 0) return null

    val zoom = minOf(util / caja.width, utilAlto / caja.height).coerceAtMost(1.0)
    if (zoom <= 0.0 || !zoom.isFinite()) return null

    val sobraX = (util - caja.width * zoom) / 2
    val sobraY = (utilAlto - caja.height * zoom) / 2
    return Viewport(
        // El desplazamiento va en coordenadas de escena —el renderizador
        // multiplica por el zoom después—, así que lo que se mide en puntos de
        // la página hay que traerlo dividido.
        scrollX = -caja.x1 + (margen + sobraX) / zoom,
        scrollY = -caja.y1 + (margen + sobraY) / zoom,
        zoom = zoom
    )
}

/**
 * ¿La página va tumbada?
 *
 * Un esquema ancho metido en un A4 vertical sale reducido a la mitad para que
 * quepa de ancho, con media página en blanco debajo.
 */
fun paginaApaisada(caja: Bounds): Boolean = caja.width > caja.height

/**
 * El trozo del segmento `a`→`b` que cae dentro del rectángulo, o null si no
 * pasa por él. Liang–Barsky de toda la vida.
 *
 * Es la pieza del pintado recortado: al rasterizador no se le pueden dar
 * coordenadas de dispositivo enormes —tiene un tope, y pasado el tope lo
 * relleno desaparece o revienta, según el aparato—, así que a mucho aumento lo
 * que se le da es **solo lo que se ve**, ya cortado. Devuelve las cuatro
 * coordenadas en un arreglo para no fabricar objetos: esto corre por cada
 * tramo de cada cadena visible, en cada fotograma.
 */
fun recortarSegmento(
    ax: Double, ay: Double, bx: Double, by: Double,
    x1: Double, y1: Double, x2: Double, y2: Double,
    fuera: DoubleArray
): Boolean {
    var t0 = 0.0
    var t1 = 1.0
    val dx = bx - ax
    val dy = by - ay

    // Cada borde recorta el intervalo [t0, t1] del parámetro del segmento.
    for (lado in 0 until 4) {
        val p: Double
        val q: Double
        when (lado) {
            0 -> { p = -dx; q = ax - x1 }
            1 -> { p = dx; q = x2 - ax }
            2 -> { p = -dy; q = ay - y1 }
            else -> { p = dy; q = y2 - ay }
        }
        if (p == 0.0) {
            // Paralelo a este borde: o está del lado bueno o no pasa.
            if (q < 0.0) return false
        } else {
            val r = q / p
            if (p < 0.0) {
                if (r > t1) return false
                if (r > t0) t0 = r
            } else {
                if (r < t0) return false
                if (r < t1) t1 = r
            }
        }
    }
    fuera[0] = ax + t0 * dx
    fuera[1] = ay + t0 * dy
    fuera[2] = ax + t1 * dx
    fuera[3] = ay + t1 * dy
    return true
}
