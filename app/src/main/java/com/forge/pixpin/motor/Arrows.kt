package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Puntas de flecha y anclaje a formas. Port de la parte de `bounds.ts` que
 * genera las puntas y del núcleo de `binding.ts`.
 *
 * El anclaje es lo que separa un diagrama de un dibujo: una flecha anclada
 * **recalcula sus extremos cada vez que la forma se mueve**, así que el
 * diagrama sigue siendo correcto después de reorganizarlo. Una flecha suelta se
 * queda flotando y hay que recolocarla a mano.
 */

/** Tamaño de cada punta, en px de escena (`getArrowheadSize`). */
fun arrowheadSize(head: Arrowhead): Double = when (head) {
    Arrowhead.ARROW -> 25.0
    Arrowhead.DIAMOND, Arrowhead.DIAMOND_OUTLINE -> 12.0
    else -> 15.0
}

/** Apertura de la punta en grados (`getArrowheadAngle`). */
fun arrowheadAngle(head: Arrowhead): Double = when (head) {
    Arrowhead.BAR -> 90.0
    Arrowhead.ARROW -> 20.0
    else -> 25.0
}

/**
 * La geometría de una punta, ya resuelta para dibujar.
 *
 * [wings] son los dos puntos de las alas (o los extremos de la barra);
 * [opposite] solo lo usa el rombo, que necesita un cuarto vértice.
 */
data class ArrowheadShape(
    val head: Arrowhead,
    val tip: Pt,
    val wings: Pair<Pt, Pt>,
    val opposite: Pt? = null,
    /** Solo círculo: diámetro. */
    val diameter: Double = 0.0
)

/**
 * Puntos de la punta en [position] (`getArrowheadPoints`).
 *
 * El original saca la dirección de la curva Bézier **ya rugosa**, evaluándola
 * en t=0.3, para que la punta salga torcida igual que el trazo. Aquí se toma
 * del último segmento de la polilínea: el resultado se desvía de forma
 * inapreciable y evita tener que devolver las `Op` del generador hasta aquí,
 * que acoplaría el dibujo de las flechas al orden del generador aleatorio.
 */
fun getArrowheadPoints(
    element: Element, position: ArrowEnd, head: Arrowhead
): ArrowheadShape? {
    val pts = absolutePoints(element)
    if (pts.size < 2) return null

    val tip = if (position == ArrowEnd.END) pts.last() else pts.first()
    val prev = if (position == ArrowEnd.END) pts[pts.size - 2] else pts[1]

    val dist = hypot(tip.x - prev.x, tip.y - prev.y)
    if (dist == 0.0) return null
    val nx = (tip.x - prev.x) / dist
    val ny = (tip.y - prev.y) / dist

    val size = arrowheadSize(head)
    // La punta se encoge en flechas cortas: una punta de 25px en un trazo de
    // 20 es toda la flecha y se ve como un borrón.
    val lengthMultiplier =
        if (head == Arrowhead.DIAMOND || head == Arrowhead.DIAMOND_OUTLINE) 0.25 else 0.5
    val minSize = min(size, dist * lengthMultiplier)

    val tx = tip.x
    val ty = tip.y
    val xs = tx - nx * minSize
    val ys = ty - ny * minSize

    if (head == Arrowhead.CIRCLE || head == Arrowhead.CIRCLE_OUTLINE) {
        val diameter = hypot(ys - ty, xs - tx) + element.strokeWidth - 2
        return ArrowheadShape(head, Pt(tx, ty), Pt(tx, ty) to Pt(tx, ty), diameter = diameter)
    }

    val angle = arrowheadAngle(head)
    val w1 = pointRotateRads(Pt(xs, ys), Pt(tx, ty), -angle * Math.PI / 180)
    val w2 = pointRotateRads(Pt(xs, ys), Pt(tx, ty), angle * Math.PI / 180)

    val opposite = if (head == Arrowhead.DIAMOND || head == Arrowhead.DIAMOND_OUTLINE) {
        val dir = atan2(ty - prev.y, tx - prev.x)
        pointRotateRads(Pt(tx - minSize * 2, ty), Pt(tx, ty), dir)
    } else null

    return ArrowheadShape(head, Pt(tx, ty), w1 to w2, opposite)
}

enum class ArrowEnd { START, END }

// -------------------------------------------------------------------------
// Anclaje
// -------------------------------------------------------------------------

/** Separación base entre la punta y el borde (`BASE_BINDING_GAP`). */
const val BASE_BINDING_GAP = 5.0

/** Hueco real para una forma concreta (`getBindingGap`). */
fun getBindingGap(target: Element): Double = BASE_BINDING_GAP + target.strokeWidth / 2

/** Distancia máxima a la que una forma «atrae» a la punta. */
fun maxBindingDistance(zoom: Double): Double {
    val base = max(BASE_BINDING_GAP, 15.0)
    val z = if (zoom < 1.0) zoom else 1.0
    return (base / (z * 1.5)).coerceIn(base, base * 2)
}

/**
 * La forma a la que se engancharía la punta si se soltase en [p]
 * (`getHoveredElementForBinding`).
 *
 * **Con la punta dentro también ancla**, que es como se comporta el original y
 * lo que hace usable un esquema: sueltas la flecha encima de la caja, no
 * buscando su borde con el dedo.
 *
 * Hubo un intento anterior de probar **solo el contorno**, y venía de un
 * problema real: una flecha que atravesaba un rectángulo relleno para llegar a
 * otro sitio se quedaba atada al del medio. Pero la causa no era probar el
 * interior, sino probarlo en el sitio equivocado. El original resuelve lo mismo
 * de otra forma, que es la que se copia aquí:
 *
 * 1. Se prueba **donde acaba la flecha**, no por dónde pasa. Una flecha que
 *    cruza un rectángulo y termina más allá tiene la punta fuera y no ancla.
 * 2. Se recorre de delante hacia atrás y **una forma opaca corta la búsqueda**:
 *    lo que queda tapado detrás de ella no es candidato, porque no se ve.
 * 3. Entre los candidatos gana **el más pequeño**. Con una caja dentro de otra,
 *    anclar a la de fuera nunca es lo que se quiere.
 */
fun getHoveredElementForBinding(
    elements: List<Element>, p: Pt, zoom: Double = 1.0
): Element? {
    val threshold = maxBindingDistance(zoom)
    val candidatos = mutableListOf<Element>()

    for (i in elements.indices.reversed()) {
        val e = elements[i]
        if (e.isDeleted || e.locked || !e.isBindable) continue
        if (!bindingBorderTest(p, e, threshold)) continue
        candidatos += e
        // Una forma con fondo opaco tapa lo de detrás: ahí se corta.
        if (e.hasBackground && !isTransparent(e.backgroundColor)) break
    }

    if (candidatos.size <= 1) return candidatos.firstOrNull()
    // El más pequeño por diagonal: es el que el usuario estaba señalando.
    return candidatos.minByOrNull { it.width * it.width + it.height * it.height }
}

/**
 * ¿La punta cae sobre la forma o lo bastante cerca de su borde?
 * (`bindingBorderTest`).
 *
 * El original llama a esto `shouldTestInside`, y lo tiene activado para todo lo
 * que no sea un marco. El descarte previo por caja rotada se conserva porque es
 * lo que lo hace barato: sin él habría que resolver la geometría de cada forma
 * de la escena en cada fotograma mientras se arrastra la flecha.
 */
internal fun bindingBorderTest(p: Pt, element: Element, threshold: Double): Boolean {
    if (!isPointInRotatedBounds(p, element, threshold)) return false
    return isPointInElement(p, element) || isPointOnElementOutline(p, element, threshold)
}

/**
 * Ancla un extremo de la flecha a [target] (`bindLinearElement`).
 *
 * El `focus` guardado no es un punto sino una **proporción**: cuánto se desvía
 * del centro, de -1 a 1. Guardarlo así es lo que permite que el anclaje
 * sobreviva a redimensionar la forma; un punto absoluto se quedaría fuera.
 */
fun bindArrow(
    arrow: Element, target: Element, end: ArrowEnd
): Element {
    val pts = absolutePoints(arrow)
    if (pts.size < 2) return arrow
    val tip = if (end == ArrowEnd.END) pts.last() else pts.first()
    val other = if (end == ArrowEnd.END) pts.first() else pts.last()

    val binding = Binding(
        elementId = target.id,
        focus = determineFocus(target, tip, other),
        gap = getBindingGap(target),
        fixedPoint = proporcionEn(target, tip),
        mode = modoPara(target, tip)
    )
    return when (end) {
        ArrowEnd.END -> arrow.copy(endBinding = binding)
        ArrowEnd.START -> arrow.copy(startBinding = binding)
    }.touched()
}

/**
 * El punto [p] como proporción de la caja de [target]: `[0,0]`…`[1,1]`.
 *
 * Se mide **en el sistema sin girar** de la forma, deshaciendo su rotación
 * primero. Así el punto agarrado sigue siendo el mismo trozo de la forma
 * aunque luego la gires.
 */
private fun proporcionEn(target: Element, p: Pt): List<Double> {
    val c = getElementAbsoluteCoords(target)
    val local = pointRotateRads(p, Pt(c.cx, c.cy), -target.angle)
    val w = (c.x2 - c.x1).takeIf { it > 0 } ?: 1.0
    val h = (c.y2 - c.y1).takeIf { it > 0 } ?: 1.0
    return listOf(
        ((local.x - c.x1) / w).coerceIn(0.0, 1.0),
        ((local.y - c.y1) / h).coerceIn(0.0, 1.0)
    )
}

/**
 * ¿La punta se queda dentro o se posa en el contorno?
 *
 * Dentro si se soltó **bien adentro**; en el contorno si se soltó en el borde o
 * cerca de él por fuera. El umbral es el mismo hueco de anclaje, así que la
 * franja que cuenta como «el borde» es la que ya se usa para el imán y no hay
 * que inventar otra medida.
 */
private fun modoPara(target: Element, p: Pt): BindMode {
    val enElBorde = isPointOnElementOutline(p, target, getBindingGap(target) * 2)
    return if (!enElBorde && isPointInElement(p, target)) BindMode.INSIDE else BindMode.ORBIT
}

/** Suelta un extremo (`unbindBindingElement`). */
fun unbindArrow(arrow: Element, end: ArrowEnd): Element = when (end) {
    ArrowEnd.END -> arrow.copy(endBinding = null)
    ArrowEnd.START -> arrow.copy(startBinding = null)
}.touched()

/**
 * Cuánto se desvía del centro el punto de contacto, en -1..1
 * (`determineFocusDistance`).
 *
 * Se mide perpendicularmente a la línea de la flecha y se normaliza contra el
 * semitamaño de la forma, para que el mismo `focus` señale el mismo sitio
 * relativo aunque la forma cambie de tamaño.
 */
private fun determineFocus(target: Element, tip: Pt, other: Pt): Double {
    val c = getElementAbsoluteCoords(target)
    val center = Pt(c.cx, c.cy)
    val dx = tip.x - other.x
    val dy = tip.y - other.y
    val len = hypot(dx, dy)
    if (len == 0.0) return 0.0

    // Distancia con signo del centro a la recta que forma la flecha.
    val signed = ((center.x - other.x) * dy - (center.y - other.y) * dx) / len
    val halfSize = max((c.x2 - c.x1) / 2, (c.y2 - c.y1) / 2)
    if (halfSize == 0.0) return 0.0
    return (-signed / halfSize).coerceIn(-1.0, 1.0)
}

/**
 * Recoloca los extremos anclados de la flecha (`updateBoundPoint`).
 *
 * Es lo que hay que llamar cada vez que se mueve o redimensiona una forma que
 * tenga flechas colgando.
 */
fun updateBoundPoints(arrow: Element, elements: List<Element>): Element {
    if (arrow.startBinding == null && arrow.endBinding == null) return arrow
    val byId = elements.associateBy { it.id }
    val pts = absolutePoints(arrow).toMutableList()
    if (pts.size < 2) return arrow

    arrow.startBinding?.let { b ->
        byId[b.elementId]?.let { target ->
            pts[0] = bindingPointOn(target, pts.last(), b)
        }
    }
    arrow.endBinding?.let { b ->
        byId[b.elementId]?.let { target ->
            pts[pts.size - 1] = bindingPointOn(target, pts.first(), b)
        }
    }

    // Los puntos se guardan relativos al primero, que pasa a ser el origen.
    val originX = pts.first().x
    val originY = pts.first().y
    val relative = pts.map { Pt(it.x - originX, it.y - originY) }
    return arrow.copy(x = originX, y = originY, points = relative).touched()
}

/**
 * Dónde debe posarse la punta sobre el borde de [target].
 *
 * Se traza un rayo desde [from] hacia el punto de enfoque y se corta con la
 * silueta de la forma; luego se retrocede el hueco. El resultado es que la
 * punta toca el borde sin invadirlo, venga de donde venga.
 */
private fun bindingPointOn(target: Element, from: Pt, binding: Binding): Pt {
    // **Modo «dentro»: manda el punto agarrado, no el contorno.** Es lo que
    // hace que la flecha se quede señalando el sitio exacto donde la soltaste
    // y lo siga al mover o redimensionar la forma, en vez de saltar al borde
    // más cercano. La proporción se convierte de vuelta a coordenadas de
    // escena y se le aplica el giro actual de la forma.
    val fijo = binding.fixedPoint
    if (binding.mode == BindMode.INSIDE && fijo != null && fijo.size == 2) {
        val cc = getElementAbsoluteCoords(target)
        val sinGirar = Pt(
            cc.x1 + fijo[0] * (cc.x2 - cc.x1),
            cc.y1 + fijo[1] * (cc.y2 - cc.y1)
        )
        return pointRotateRads(sinGirar, Pt(cc.cx, cc.cy), target.angle)
    }

    val c = getElementAbsoluteCoords(target)
    val center = Pt(c.cx, c.cy)

    val dx = center.x - from.x
    val dy = center.y - from.y
    val len = hypot(dx, dy)
    if (len == 0.0) return center

    // El enfoque desplaza el objetivo perpendicular a la línea.
    val halfSize = max((c.x2 - c.x1) / 2, (c.y2 - c.y1) / 2)
    val offset = binding.focus * halfSize
    val focusTarget = Pt(
        center.x + (-dy / len) * offset,
        center.y + (dx / len) * offset
    )

    val hit = intersectOutline(target, from, focusTarget) ?: return focusTarget
    // Retroceder el hueco por la misma dirección de llegada.
    val bdx = hit.x - from.x
    val bdy = hit.y - from.y
    val blen = hypot(bdx, bdy)
    if (blen == 0.0) return hit
    return Pt(hit.x - (bdx / blen) * binding.gap, hit.y - (bdy / blen) * binding.gap)
}

/**
 * Corte del segmento `from → to` con la silueta de [element].
 *
 * Cada forma tiene su fórmula: el rectángulo y el rombo se resuelven contra sus
 * lados, la elipse contra su ecuación. Se trabaja en el sistema sin rotar y se
 * devuelve el punto ya girado de vuelta.
 */
private fun intersectOutline(element: Element, from: Pt, to: Pt): Pt? {
    val c = getElementAbsoluteCoords(element)
    val center = Pt(c.cx, c.cy)
    val a = pointRotateRads(from, center, -element.angle)
    val b = pointRotateRads(to, center, -element.angle)

    val local = when (element.type) {
        ElementType.ELLIPSE -> intersectEllipse(c, a, b)
        ElementType.DIAMOND -> intersectPolygon(
            listOf(Pt(c.cx, c.y1), Pt(c.x2, c.cy), Pt(c.cx, c.y2), Pt(c.x1, c.cy)), a, b
        )
        else -> intersectPolygon(
            listOf(Pt(c.x1, c.y1), Pt(c.x2, c.y1), Pt(c.x2, c.y2), Pt(c.x1, c.y2)), a, b
        )
    } ?: return null

    return pointRotateRads(local, center, element.angle)
}

private fun intersectEllipse(c: AbsoluteCoords, from: Pt, to: Pt): Pt? {
    val rx = (c.x2 - c.x1) / 2
    val ry = (c.y2 - c.y1) / 2
    if (rx <= 0 || ry <= 0) return null
    // Se escala el espacio para volver la elipse un círculo de radio 1; ahí la
    // intersección es una ecuación de segundo grado corriente.
    val px = (from.x - c.cx) / rx
    val py = (from.y - c.cy) / ry
    val dx = (to.x - from.x) / rx
    val dy = (to.y - from.y) / ry

    val aa = dx * dx + dy * dy
    if (aa == 0.0) return null
    val bb = 2 * (px * dx + py * dy)
    val cc = px * px + py * py - 1
    val disc = bb * bb - 4 * aa * cc
    if (disc < 0) return null
    val sq = kotlin.math.sqrt(disc)
    val t1 = (-bb - sq) / (2 * aa)
    val t2 = (-bb + sq) / (2 * aa)
    // El primer corte por delante del origen del rayo.
    val t = listOf(t1, t2).filter { it >= 0 }.minOrNull() ?: return null
    return Pt(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
}

private fun intersectPolygon(polygon: List<Pt>, from: Pt, to: Pt): Pt? {
    var best: Pt? = null
    var bestT = Double.MAX_VALUE
    for (i in polygon.indices) {
        val p1 = polygon[i]
        val p2 = polygon[(i + 1) % polygon.size]
        val t = segmentIntersectionT(from, to, p1, p2) ?: continue
        if (t < bestT) { bestT = t; best = Pt(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t) }
    }
    return best
}

/** Parámetro `t` del corte sobre `a1→a2`, o null si no se cortan. */
private fun segmentIntersectionT(a1: Pt, a2: Pt, b1: Pt, b2: Pt): Double? {
    val d1x = a2.x - a1.x
    val d1y = a2.y - a1.y
    val d2x = b2.x - b1.x
    val d2y = b2.y - b1.y
    val denom = d1x * d2y - d1y * d2x
    if (abs(denom) < 1e-9) return null
    val t = ((b1.x - a1.x) * d2y - (b1.y - a1.y) * d2x) / denom
    val u = ((b1.x - a1.x) * d1y - (b1.y - a1.y) * d1x) / denom
    // `u` debe caer dentro del lado; `t` puede pasarse de 1 porque el rayo
    // apunta al centro y el borde queda antes.
    if (u < 0.0 || u > 1.0 || t < 0.0) return null
    return t
}

/**
 * Recalcula todas las flechas ancladas a los elementos que acaban de cambiar
 * (`updateBoundElements`). Es el enganche que hay que llamar tras mover,
 * redimensionar o rotar cualquier cosa.
 */
fun updateBoundElements(elements: List<Element>, changedIds: Set<String>): List<Element> {
    if (changedIds.isEmpty()) return elements
    return elements.map { e ->
        if (e.type != ElementType.ARROW) return@map e
        val touchesChanged = e.startBinding?.elementId in changedIds ||
            e.endBinding?.elementId in changedIds
        if (touchesChanged) updateBoundPoints(e, elements) else e
    }
}

/** Vector unitario de `a` a `b`, o null si coinciden. */
internal fun direction(a: Pt, b: Pt): Pt? {
    val d = hypot(b.x - a.x, b.y - a.y)
    if (d == 0.0) return null
    return Pt((b.x - a.x) / d, (b.y - a.y) / d)
}
