package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port de rough.js: el trazo «a mano alzada».
 *
 * Esto es lo que hace que un dibujo *parezca* Excalidraw. El algoritmo no
 * dibuja la línea pedida: la dibuja **dos veces**, cada una con los extremos y
 * los puntos de control desplazados por ruido, y con una curva de Bézier en vez
 * de una recta. El resultado imita el temblor de una mano.
 *
 * **El orden de las llamadas al generador es parte del algoritmo.** Cada
 * `rand.next()` avanza el estado, así que una llamada de más o de menos —o
 * hecha en otro orden— cambia el dibujo entero aunque la semilla sea la misma.
 * Por eso este archivo sigue el original línea por línea en vez de reordenar lo
 * que en Kotlin quedaría más natural.
 */

/**
 * Una orden de dibujo. Se traducen a `Path` en [toPath].
 *
 * Son **las mismas cuatro que entienden un camino de SVG y un flujo de PDF**
 * (`M`/`m`, `L`/`l`, `C`/`c`, `Z`/`h`), y no es casualidad: por eso exportar a
 * cualquiera de los dos es traducir, no recalcular. Ver [DibujoVectorial].
 */
sealed interface Op {
    data class Move(val x: Double, val y: Double) : Op
    data class LineTo(val x: Double, val y: Double) : Op
    data class CurveTo(
        val x1: Double, val y1: Double,
        val x2: Double, val y2: Double,
        val x: Double, val y: Double
    ) : Op

    /**
     * Cierra el subcamino: vuelve al punto donde empezó.
     *
     * El generador rugoso no la emite nunca —sus trazos son abiertos a
     * propósito, que es parte de que parezcan hechos a mano— pero los caminos
     * que se construyen a mano sí la necesitan: una silueta, un anillo de un
     * relleno o el perfil de una letra tienen que cerrar, y «volver al primer
     * punto con una recta» no es lo mismo, porque deja la unión en pico.
     */
    data object Cerrar : Op
}

/**
 * Opciones resueltas de rough.js, con los valores por defecto del original
 * (`generator.ts:14`). No se tocan: cambiarlos cambia el aspecto de todo.
 */
data class RoughOptions(
    val maxRandomnessOffset: Double = 2.0,
    val roughness: Double = 1.0,
    val bowing: Double = 1.0,
    val strokeWidth: Double = 1.0,
    val curveTightness: Double = 0.0,
    val curveFitting: Double = 0.95,
    val curveStepCount: Double = 9.0,
    val fillStyle: FillStyle = FillStyle.HACHURE,
    /** Grosor del trazo de relleno. Negativo = derivar de [strokeWidth]. */
    val fillWeight: Double = -1.0,
    val hachureAngle: Double = -41.0,
    /** Separación del rayado. Negativa = derivar de [strokeWidth]. */
    val hachureGap: Double = -1.0,
    val seed: Int = 0,
    val disableMultiStroke: Boolean = false,
    val disableMultiStrokeFill: Boolean = false,
    /** Con true los vértices no se desplazan: los caminos siguen encajando. */
    val preserveVertices: Boolean = false,
    val fillShapeRoughnessGain: Double = 0.8
)

/**
 * Generador de trazo rugoso.
 *
 * Se crea uno **por forma**: lleva dentro el estado del generador aleatorio, y
 * reutilizarlo entre formas las acoplaría (mover una cambiaría el dibujo de la
 * siguiente).
 */
class Rough(private val o: RoughOptions) {

    private val rand = Rand(o.seed)

    private fun random(): Double = rand.next()

    /** `_offset` del original: ruido escalado por la rugosidad. */
    private fun offset(minV: Double, maxV: Double, gain: Double = 1.0): Double =
        o.roughness * gain * ((random() * (maxV - minV)) + minV)

    /** `_offsetOpt`: ruido simétrico alrededor de cero. */
    private fun offsetOpt(x: Double, gain: Double = 1.0): Double = offset(-x, x, gain)

    // ---------------------------------------------------------------------
    // Líneas
    // ---------------------------------------------------------------------

    /**
     * Una pasada de línea rugosa (`_line`).
     *
     * [overlay] marca la segunda pasada, que usa la mitad de ruido para que las
     * dos rayas se crucen en vez de ir paralelas.
     */
    private fun lineOps(
        x1: Double, y1: Double, x2: Double, y2: Double,
        move: Boolean, overlay: Boolean
    ): List<Op> {
        val lengthSq = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
        val length = sqrt(lengthSq)

        // Las líneas largas se dibujan más rectas: el temblor constante en un
        // trazo de 800px se ve como un error, no como un dibujo a mano.
        val roughnessGain = when {
            length < 200 -> 1.0
            length > 500 -> 0.4
            else -> -0.0016668 * length + 1.233334
        }

        var off = o.maxRandomnessOffset
        if ((off * off * 100) > lengthSq) off = length / 10
        val halfOffset = off / 2
        val divergePoint = 0.2 + random() * 0.2

        var midDispX = o.bowing * o.maxRandomnessOffset * (y2 - y1) / 200
        var midDispY = o.bowing * o.maxRandomnessOffset * (x1 - x2) / 200
        midDispX = offsetOpt(midDispX, roughnessGain)
        midDispY = offsetOpt(midDispY, roughnessGain)

        val ops = mutableListOf<Op>()
        fun randomHalf() = offsetOpt(halfOffset, roughnessGain)
        fun randomFull() = offsetOpt(off, roughnessGain)
        val keep = o.preserveVertices

        if (move) {
            ops += if (overlay) {
                Op.Move(
                    x1 + (if (keep) 0.0 else randomHalf()),
                    y1 + (if (keep) 0.0 else randomHalf())
                )
            } else {
                Op.Move(
                    x1 + (if (keep) 0.0 else randomFull()),
                    y1 + (if (keep) 0.0 else randomFull())
                )
            }
        }

        ops += if (overlay) {
            Op.CurveTo(
                midDispX + x1 + (x2 - x1) * divergePoint + randomHalf(),
                midDispY + y1 + (y2 - y1) * divergePoint + randomHalf(),
                midDispX + x1 + 2 * (x2 - x1) * divergePoint + randomHalf(),
                midDispY + y1 + 2 * (y2 - y1) * divergePoint + randomHalf(),
                x2 + (if (keep) 0.0 else randomHalf()),
                y2 + (if (keep) 0.0 else randomHalf())
            )
        } else {
            Op.CurveTo(
                midDispX + x1 + (x2 - x1) * divergePoint + randomFull(),
                midDispY + y1 + (y2 - y1) * divergePoint + randomFull(),
                midDispX + x1 + 2 * (x2 - x1) * divergePoint + randomFull(),
                midDispY + y1 + 2 * (y2 - y1) * divergePoint + randomFull(),
                x2 + (if (keep) 0.0 else randomFull()),
                y2 + (if (keep) 0.0 else randomFull())
            )
        }
        return ops
    }

    /** `_doubleLine`: las dos pasadas que dan el aspecto de boceto. */
    fun doubleLine(
        x1: Double, y1: Double, x2: Double, y2: Double, filling: Boolean = false
    ): List<Op> {
        val single = if (filling) o.disableMultiStrokeFill else o.disableMultiStroke
        val first = lineOps(x1, y1, x2, y2, move = true, overlay = false)
        if (single) return first
        return first + lineOps(x1, y1, x2, y2, move = true, overlay = true)
    }

    /** Camino de segmentos rectos, opcionalmente cerrado (`linearPath`). */
    fun linearPath(points: List<Pt>, close: Boolean): List<Op> {
        val pts = if (close && points.size > 2) points + points.first() else points
        if (pts.size < 2) return emptyList()
        val ops = mutableListOf<Op>()
        for (i in 0 until pts.size - 1) {
            ops += doubleLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y)
        }
        return ops
    }

    fun polygon(points: List<Pt>): List<Op> = linearPath(points, close = true)

    fun rectangle(x: Double, y: Double, w: Double, h: Double): List<Op> =
        polygon(
            listOf(Pt(x, y), Pt(x + w, y), Pt(x + w, y + h), Pt(x, y + h))
        )

    // ---------------------------------------------------------------------
    // Curvas
    // ---------------------------------------------------------------------

    /**
     * Spline de Catmull-Rom convertida a Béziers (`_curve`).
     *
     * Los dos puntos duplicados en los extremos que mete [curveWithOffset] no
     * son un descuido del original: la spline necesita un punto antes del
     * primero y otro después del último para calcular sus tangentes.
     */
    private fun curve(points: List<Pt>, closePoint: Pt?): List<Op> {
        val len = points.size
        val ops = mutableListOf<Op>()
        if (len > 3) {
            val s = 1 - o.curveTightness
            ops += Op.Move(points[1].x, points[1].y)
            var i = 1
            while (i + 2 < len) {
                val cur = points[i]
                val b1x = cur.x + (s * points[i + 1].x - s * points[i - 1].x) / 6
                val b1y = cur.y + (s * points[i + 1].y - s * points[i - 1].y) / 6
                val b2x = points[i + 1].x + (s * points[i].x - s * points[i + 2].x) / 6
                val b2y = points[i + 1].y + (s * points[i].y - s * points[i + 2].y) / 6
                ops += Op.CurveTo(b1x, b1y, b2x, b2y, points[i + 1].x, points[i + 1].y)
                i++
            }
            if (closePoint != null) {
                val ro = o.maxRandomnessOffset
                ops += Op.LineTo(closePoint.x + offsetOpt(ro), closePoint.y + offsetOpt(ro))
            }
        } else if (len == 3) {
            ops += Op.Move(points[1].x, points[1].y)
            ops += Op.CurveTo(
                points[1].x, points[1].y,
                points[2].x, points[2].y,
                points[2].x, points[2].y
            )
        } else if (len == 2) {
            ops += lineOps(points[0].x, points[0].y, points[1].x, points[1].y, true, true)
        }
        return ops
    }

    /** `_curveWithOffset`: la spline con cada punto sacudido por el ruido. */
    private fun curveWithOffset(points: List<Pt>, off: Double): List<Op> {
        if (points.isEmpty()) return emptyList()
        val ps = mutableListOf<Pt>()
        ps += Pt(points[0].x + offsetOpt(off), points[0].y + offsetOpt(off))
        ps += Pt(points[0].x + offsetOpt(off), points[0].y + offsetOpt(off))
        for (i in 1 until points.size) {
            ps += Pt(points[i].x + offsetOpt(off), points[i].y + offsetOpt(off))
            if (i == points.size - 1) {
                ps += Pt(points[i].x + offsetOpt(off), points[i].y + offsetOpt(off))
            }
        }
        return curve(ps, null)
    }

    /** Curva suave por una lista de puntos: el lápiz y las líneas curvas. */
    fun curveThrough(points: List<Pt>): List<Op> {
        if (points.size < 2) return emptyList()
        val o1 = curveWithOffset(points, 1.0 * (1 + o.roughness * 0.2))
        if (o.disableMultiStroke) return o1
        val o2 = curveWithOffset(points, 1.5 * (1 + o.roughness * 0.22))
        return o1 + o2
    }

    // ---------------------------------------------------------------------
    // Elipse
    // ---------------------------------------------------------------------

    private data class EllipseParams(val increment: Double, val rx: Double, val ry: Double)

    private fun ellipseParams(width: Double, height: Double): EllipseParams {
        val psq = sqrt(
            Math.PI * 2 * sqrt(
                ((width / 2) * (width / 2) + (height / 2) * (height / 2)) / 2
            )
        )
        val stepCount = ceil(max(o.curveStepCount, (o.curveStepCount / sqrt(200.0)) * psq))
        val increment = (Math.PI * 2) / stepCount
        var rx = abs(width / 2)
        var ry = abs(height / 2)
        val curveFitRandomness = 1 - o.curveFitting
        rx += offsetOpt(rx * curveFitRandomness)
        ry += offsetOpt(ry * curveFitRandomness)
        return EllipseParams(increment, rx, ry)
    }

    /**
     * Puntos de la elipse (`_computeEllipsePoints`).
     *
     * Devuelve dos listas: la del trazo completo —con el arranque y el cierre
     * solapados para que la vuelta no deje costura— y la de los puntos «del
     * núcleo», que son los que usa el relleno.
     */
    private fun computeEllipsePoints(
        increment: Double, cx: Double, cy: Double, rx: Double, ry: Double,
        offsetAmount: Double, overlap: Double
    ): Pair<List<Pt>, List<Pt>> {
        val coreOnly = o.roughness == 0.0
        val corePoints = mutableListOf<Pt>()
        val allPoints = mutableListOf<Pt>()

        if (coreOnly) {
            val inc = increment / 4
            allPoints += Pt(cx + rx * cos(-inc), cy + ry * sin(-inc))
            var angle = 0.0
            while (angle <= Math.PI * 2) {
                val p = Pt(cx + rx * cos(angle), cy + ry * sin(angle))
                corePoints += p
                allPoints += p
                angle += inc
            }
            allPoints += Pt(cx + rx * cos(0.0), cy + ry * sin(0.0))
            allPoints += Pt(cx + rx * cos(inc), cy + ry * sin(inc))
        } else {
            val radOffset = offsetOpt(0.5) - (Math.PI / 2)
            allPoints += Pt(
                offsetOpt(offsetAmount) + cx + 0.9 * rx * cos(radOffset - increment),
                offsetOpt(offsetAmount) + cy + 0.9 * ry * sin(radOffset - increment)
            )
            val endAngle = Math.PI * 2 + radOffset - 0.01
            var angle = radOffset
            while (angle < endAngle) {
                val p = Pt(
                    offsetOpt(offsetAmount) + cx + rx * cos(angle),
                    offsetOpt(offsetAmount) + cy + ry * sin(angle)
                )
                corePoints += p
                allPoints += p
                angle += increment
            }
            allPoints += Pt(
                offsetOpt(offsetAmount) + cx + rx * cos(radOffset + Math.PI * 2 + overlap * 0.5),
                offsetOpt(offsetAmount) + cy + ry * sin(radOffset + Math.PI * 2 + overlap * 0.5)
            )
            allPoints += Pt(
                offsetOpt(offsetAmount) + cx + 0.98 * rx * cos(radOffset + overlap),
                offsetOpt(offsetAmount) + cy + 0.98 * ry * sin(radOffset + overlap)
            )
            allPoints += Pt(
                offsetOpt(offsetAmount) + cx + 0.9 * rx * cos(radOffset + overlap * 0.5),
                offsetOpt(offsetAmount) + cy + 0.9 * ry * sin(radOffset + overlap * 0.5)
            )
        }
        return allPoints to corePoints
    }

    /** Elipse rugosa. Devuelve el trazo y el polígono que usa el relleno. */
    fun ellipse(
        cx: Double, cy: Double, width: Double, height: Double
    ): Pair<List<Op>, List<Pt>> {
        val params = ellipseParams(width, height)
        val (all1, core1) = computeEllipsePoints(
            params.increment, cx, cy, params.rx, params.ry,
            1.0, params.increment * offset(0.1, offset(0.4, 1.0))
        )
        var ops = curve(all1, null)
        if (o.roughness != 0.0 && !o.disableMultiStroke) {
            val (all2, _) = computeEllipsePoints(
                params.increment, cx, cy, params.rx, params.ry, 1.5, 0.0
            )
            ops = ops + curve(all2, null)
        }
        return ops to core1
    }

    // ---------------------------------------------------------------------
    // Rellenos
    // ---------------------------------------------------------------------

    /**
     * Trazos del relleno para un polígono (rayado, cruzado o zigzag).
     *
     * El relleno sólido no pasa por aquí: se pinta como un `Path` cerrado
     * corriente, sin ruido, porque un sólido con temblor deja huecos blancos en
     * los bordes.
     */
    fun fillPolygon(polygon: List<Pt>): List<Op> = fillPolygons(listOf(polygon))

    /**
     * Lo mismo para **un contorno con agujeros**.
     *
     * El barrido de rayado ya casa las cruces de dos en dos —es el par/impar de
     * toda la vida—, así que darle todos los anillos a la vez rellena el de
     * fuera y salta los de dentro sin una sola línea de código extra. Es lo que
     * permite rellenar el hueco entre dos figuras cuando una está dentro de la
     * otra. Ver [Regiones].
     */
    fun fillPolygons(polygons: List<List<Pt>>): List<Op> {
        var gap = o.hachureGap
        if (gap < 0) gap = o.strokeWidth * 4
        gap = max(gap, 0.1)

        return when (o.fillStyle) {
            FillStyle.SOLID -> emptyList()
            FillStyle.HACHURE -> renderHachure(polygons, gap, o.hachureAngle)
            FillStyle.CROSS_HATCH ->
                renderHachure(polygons, gap, o.hachureAngle) +
                    renderHachure(polygons, gap, o.hachureAngle + 90)
            FillStyle.ZIGZAG -> renderZigzag(polygons, gap)
            // A tiralíneas: las mismas rayas del barrido, pero **sin pasar por
            // el generador de ruido**. Que no consuma números del azar da igual
            // aquí: el relleno se pide siempre antes que el trazo, así que lo
            // único que cambia es que el garabato del contorno sale como si la
            // forma no tuviera relleno. Ninguna forma sin relleno se ve afectada.
            FillStyle.LINEAS -> anillosHachureLines(polygons, gap, o.hachureAngle, 1.0)
                .flatMap { (a, b) -> listOf(Op.Move(a.x, a.y), Op.LineTo(b.x, b.y)) }
        }
    }

    private fun renderHachure(
        polygons: List<List<Pt>>, gap: Double, angle: Double
    ): List<Op> {
        val lines = anillosHachureLines(polygons, gap, angle, hachureSkipOffset(gap))
        val ops = mutableListOf<Op>()
        for ((a, b) in lines) ops += doubleLine(a.x, a.y, b.x, b.y, filling = true)
        return ops
    }

    private fun renderZigzag(polygons: List<List<Pt>>, gap: Double): List<Op> {
        val lines = anillosHachureLines(polygons, gap, o.hachureAngle, hachureSkipOffset(gap))
        val zigAngle = (Math.PI / 180) * o.hachureAngle
        val dgx = gap * 0.5 * cos(zigAngle)
        val dgy = gap * 0.5 * sin(zigAngle)
        val ops = mutableListOf<Op>()
        for ((p1, p2) in lines) {
            if (hypot(p2.x - p1.x, p2.y - p1.y) == 0.0) continue
            ops += doubleLine(p1.x - dgx, p1.y + dgy, p2.x, p2.y, filling = true)
            ops += doubleLine(p1.x + dgx, p1.y - dgy, p2.x, p2.y, filling = true)
        }
        return ops
    }

    /**
     * Con rugosidad alta, rough.js salta rayas al azar para que el rayado no
     * quede peinado. Consume un número del generador, así que va aquí y no
     * dentro del algoritmo de barrido, que es geometría pura.
     */
    private fun hachureSkipOffset(gap: Double): Double {
        if (o.roughness >= 1 && random() > 0.7) return gap
        return 1.0
    }
}

// -------------------------------------------------------------------------
// Barrido de rayado (paquete `hachure-fill`)
//
// Geometría pura y determinista: no toca el generador aleatorio. Gira el
// polígono para que las rayas queden horizontales, hace un barrido de línea
// clásico con tabla de aristas activas, y desgira el resultado.
// -------------------------------------------------------------------------

private class HachureEdge(
    val ymin: Double,
    val ymax: Double,
    var x: Double,
    val islope: Double
)

private fun rotatePoints(points: MutableList<Pt>, degrees: Double) {
    if (degrees == 0.0) return
    val angle = (Math.PI / 180) * degrees
    val c = cos(angle)
    val s = sin(angle)
    for (i in points.indices) {
        val p = points[i]
        points[i] = Pt(p.x * c - p.y * s, p.x * s + p.y * c)
    }
}

/** Rayas que rellenan [polygon], con separación [gap] e inclinación [angle]. */
internal fun polygonHachureLines(
    polygon: List<Pt>, gap: Double, angle: Double, skipOffset: Double
): List<Pair<Pt, Pt>> = anillosHachureLines(listOf(polygon), gap, angle, skipOffset)

/**
 * Lo mismo con varios anillos: el de fuera se raya y los de dentro se saltan.
 *
 * Se llama distinto y no es una sobrecarga porque en la JVM `List<Pt>` y
 * `List<List<Pt>>` son la misma firma: el compilador las rechaza.
 */
internal fun anillosHachureLines(
    polygons: List<List<Pt>>, gap: Double, angle: Double, skipOffset: Double
): List<Pair<Pt, Pt>> {
    // El original suma 90º: con `hachureAngle` a 0 las rayas salen verticales.
    val rotAngle = angle + 90
    val work = polygons.map { anillo ->
        anillo.toMutableList().also { rotatePoints(it, rotAngle) }
    }
    val lines = straightHachureLines(work, max(gap, 0.1), skipOffset)
    val out = mutableListOf<Pair<Pt, Pt>>()
    for ((a, b) in lines) {
        val back = mutableListOf(a, b)
        rotatePoints(back, -rotAngle)
        out += back[0] to back[1]
    }
    return out
}

private fun straightHachureLines(
    polygons: List<List<Pt>>, gap: Double, skipOffset: Double
): List<Pair<Pt, Pt>> {
    val edges = mutableListOf<HachureEdge>()
    for (anillo in polygons) {
        val vertices = anillo.toMutableList()
        if (vertices.size < 3) continue
        if (vertices.first() != vertices.last()) vertices += vertices.first()

        for (i in 0 until vertices.size - 1) {
            val p1 = vertices[i]
            val p2 = vertices[i + 1]
            // Las aristas horizontales no cortan a una raya horizontal: se tiran.
            if (p1.y == p2.y) continue
            val ymin = min(p1.y, p2.y)
            edges += HachureEdge(
                ymin = ymin,
                ymax = max(p1.y, p2.y),
                x = if (ymin == p1.y) p1.x else p2.x,
                islope = (p2.x - p1.x) / (p2.y - p1.y)
            )
        }
    }
    edges.sortWith(compareBy({ it.ymin }, { it.x }, { it.ymax }))
    if (edges.isEmpty()) return emptyList()

    val lines = mutableListOf<Pair<Pt, Pt>>()
    var active = mutableListOf<HachureEdge>()
    var y = edges.first().ymin
    var iteration = 0

    while (active.isNotEmpty() || edges.isNotEmpty()) {
        if (edges.isNotEmpty()) {
            var ix = -1
            for (i in edges.indices) {
                if (edges[i].ymin > y) break
                ix = i
            }
            repeat(ix + 1) { active += edges.removeAt(0) }
        }
        active = active.filter { it.ymax > y }.toMutableList()
        active.sortBy { it.x }

        // Con `skipOffset` a 1 solo se emite cada `gap` pasos; si no, en todos.
        if (skipOffset != 1.0 || iteration % gap.toInt().coerceAtLeast(1) == 0) {
            var i = 0
            while (i + 1 < active.size) {
                lines += Pt(active[i].x.roundToInt().toDouble(), y) to
                    Pt(active[i + 1].x.roundToInt().toDouble(), y)
                i += 2
            }
        }

        y += skipOffset
        for (e in active) e.x += skipOffset * e.islope
        iteration++

        // Cortafuegos: con un polígono degenerado el barrido no termina solo.
        if (iteration > MAX_HACHURE_ITERATIONS) break
    }
    return lines
}

private const val MAX_HACHURE_ITERATIONS = 20_000
