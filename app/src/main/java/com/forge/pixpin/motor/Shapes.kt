package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * De qué forma se compone cada figura: el port de `Shape.ts`.
 *
 * **Está aparte del renderizador y sin una sola línea de Android** por el mismo
 * motivo que [DrawController]: aquí es donde un cuadrado sale cuadrado o sale
 * deforme, y eso hay que poder comprobarlo sin dispositivo. Antes vivía dentro
 * de `Renderer`, enredado con `Path` y `Paint`, y no había manera de mirar la
 * geometría sin arrancar la app.
 *
 * El orden de las llamadas al generador **es parte del resultado**: cada una
 * avanza el estado pseudoaleatorio, así que pedir el relleno antes o después del
 * trazo cambia el garabato del contorno. Por eso cada tipo respeta la secuencia
 * del original.
 */

/** Una figura ya generada, en coordenadas de escena. */
data class ShapeGeometry(
    /**
     * Silueta lisa, sin ruido: es la que rellena el sólido y la que recorta el
     * rayado. Va como polígono y no como trazo porque el relleno tiene que
     * quedar **dentro** del borde dibujado, y el borde tiembla.
     */
    val outline: List<Pt>,
    val stroke: List<Op>,
    /** Trazos del rayado, o null si el relleno es liso o no hay relleno. */
    val fill: List<Op>?
)

/**
 * Genera la figura de [e], o null si su tipo no pasa por rough.js (el lápiz, la
 * imagen y el texto no tienen nada que generar).
 */
fun buildShapeGeometry(e: Element): ShapeGeometry? {
    val c = getElementAbsoluteCoords(e)
    val roughFill = needsRoughFill(e)

    return when (e.type) {
        ElementType.RECTANGLE -> {
            if (e.roundness == null) {
                val rough = Rough(roughOptionsFor(e))
                val corners = listOf(
                    Pt(c.x1, c.y1), Pt(c.x2, c.y1), Pt(c.x2, c.y2), Pt(c.x1, c.y2)
                )
                val fill = if (roughFill) rough.fillPolygon(corners) else null
                val stroke = rough.rectangle(c.x1, c.y1, c.x2 - c.x1, c.y2 - c.y1)
                ShapeGeometry(corners, stroke, fill)
            } else {
                roughContinuous(e, roundedRectPoints(c, e), roughFill)
            }
        }

        ElementType.DIAMOND -> {
            if (e.roundness == null) {
                val rough = Rough(roughOptionsFor(e))
                val v = diamondPoints(c)
                val fill = if (roughFill) rough.fillPolygon(v) else null
                val stroke = rough.polygon(v)
                ShapeGeometry(v, stroke, fill)
            } else {
                val outline = roundedDiamondPoints(c, e)
                val g = roughContinuous(e, outline, roughFill)
                ShapeGeometry(outline, g.stroke, g.fill)
            }
        }

        ElementType.ELLIPSE -> {
            // `curveFitting = 1` es lo que Excalidraw fuerza en la elipse: sin
            // ello los radios bailan y el círculo sale abollado.
            val rough = Rough(roughOptionsFor(e).copy(curveFitting = 1.0))
            val (ops, corePoints) = rough.ellipse(c.cx, c.cy, c.x2 - c.x1, c.y2 - c.y1)
            val fill = if (roughFill) rough.fillPolygon(corePoints) else null
            ShapeGeometry(corePoints, ops, fill)
        }

        ElementType.LINE, ElementType.ARROW -> {
            val pts = puntosDeTrazado(e)
            if (pts.size < 2) return null
            val rough = Rough(roughOptionsFor(e))
            // Una flecha de codos nunca se cierra sobre sí misma.
            val loop = e.type == ElementType.LINE && !e.elbowed && isPathALoop(e.points)
            val fill = if (loop && roughFill) rough.fillPolygon(pts) else null
            // **Manda `roundness`, no el número de puntos.** El original elige
            // aquí entre polilínea y curva: sin redondeo, una línea de varios
            // tramos tiene que hacer esquina en cada vértice, y curvarla siempre
            // —que es lo que se hacía— dejaba sin poder dibujar un ángulo recto.
            val stroke = when {
                // Los codos ya vienen redondeados y con los puntos repartidos:
                // pasarles la spline por encima los volvería a curvar y se
                // perderían los 90°, que son justo lo que se pedía.
                e.elbowed -> rough.linearPath(pts, close = false)
                loop && e.roundness == null -> rough.polygon(pts)
                e.roundness == null -> rough.linearPath(pts, close = false)
                else -> rough.curveThrough(pts)
            }
            ShapeGeometry(if (loop) pts else emptyList(), stroke, fill)
        }

        else -> null
    }
}

/**
 * Trazo de un contorno **continuo** ya muestreado a puntos.
 *
 * Es lo que hace rough.js con `generator.path()`: no traza los tramos uno a uno
 * —eso abriría las esquinas, porque cada tramo sortea su propio ruido en los
 * extremos— sino que pasa una curva suave por todos los puntos y activa
 * `preserveVertices` para que el camino siga cerrando. Los rectángulos y los
 * rombos redondeados de Excalidraw se dibujan así.
 */
private fun roughContinuous(
    e: Element, outline: List<Pt>, roughFill: Boolean
): ShapeGeometry {
    val rough = Rough(roughOptionsFor(e).copy(preserveVertices = true))
    // El relleno primero, igual que el original: invertirlo cambia el garabato.
    val fill = if (roughFill) rough.fillPolygon(outline) else null
    val stroke = rough.curveThrough(outline + outline.first())
    return ShapeGeometry(outline, stroke, fill)
}

// -------------------------------------------------------------------------
// Contornos
// -------------------------------------------------------------------------

/**
 * Cuántos puntos aporta cada esquina redondeada. Marca también el paso de
 * muestreo del resto del contorno: ver [OutlineBuilder].
 */
private const val CORNER_STEPS = 6

/**
 * Tope de muestras por tramo recto.
 *
 * Un rectángulo de diez mil píxeles de lado no necesita mil puntos por lado
 * para que la spline se porte; a partir de cierta densidad solo cuestan tiempo.
 */
private const val MAX_EDGE_SAMPLES = 128

/**
 * Cuánto más separadas van las muestras de un tramo recto que las de una curva.
 * El porqué del número, en [OutlineBuilder.lineTo].
 */
private const val EDGE_STEP_FACTOR = 3.0

/** Los cuatro vértices del rombo: arriba, derecha, abajo, izquierda. */
fun diamondPoints(c: AbsoluteCoords): List<Pt> = listOf(
    Pt(c.cx, c.y1), Pt(c.x2, c.cy), Pt(c.cx, c.y2), Pt(c.x1, c.cy)
)

/**
 * Contorno muestreado a puntos **repartidos por igual**.
 *
 * Esta clase existe por un fallo muy concreto, y el comentario es la única
 * forma de que no vuelva. Antes los tramos rectos aportaban solo sus extremos y
 * las esquinas siete puntos cada una. La spline de Catmull-Rom de rough.js
 * calcula la tangente en un punto como `(P[i+1] − P[i−1]) / 6`, o sea **a
 * partir de la distancia a sus vecinos**: en la unión entre una recta de 136 px
 * y un arco muestreado cada 8 px, el vecino de un lado estaba dieciséis veces
 * más lejos que el del otro y salía un tirador de 22 px gobernando un segmento
 * de 8. El trazo se pasaba de largo y volvía, dejando un rizo en **cada esquina
 * de cada rectángulo y cada rombo redondeados**. Eso era lo «deforme».
 *
 * Con el muestreo parejo la fórmula vuelve a estar bien condicionada y las
 * esquinas salen curvas, con el temblor de rough.js pero sin rizos.
 */
private class OutlineBuilder(private val step: Double) {

    private val pts = mutableListOf<Pt>()

    fun moveTo(p: Pt) {
        pts += p
    }

    /**
     * Recta hasta [to], con los puntos más separados que en las curvas.
     *
     * Un tramo recto **no** quiere el mismo muestreo que una esquina: cada punto
     * recibe su sacudida de ruido, así que muestrear fino una recta la convierte
     * en una sierra. Lo que hay que respetar es el desequilibrio entre vecinos
     * que estropea la tangente, y ahí hay margen: el tirador vale
     * `(E + A) / 6` frente a un segmento de `A`, con lo que un vecino hasta
     * cinco veces más lejos todavía no se pasa de largo. Con tres se está
     * cómodamente dentro y el lado sale liso.
     */
    fun lineTo(to: Pt) {
        val from = pts.lastOrNull() ?: run { pts += to; return }
        val n = samplesFor(hypot(to.x - from.x, to.y - from.y) / EDGE_STEP_FACTOR)
        for (i in 1..n) {
            val t = i.toDouble() / n
            pts += Pt(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
        }
    }

    /** Arco de circunferencia, de [from] a [to] en radianes. */
    fun arcTo(cx: Double, cy: Double, r: Double, from: Double, to: Double) {
        val n = samplesFor(abs(to - from) * r)
        for (i in 1..n) {
            val a = from + (to - from) * i / n
            pts += Pt(cx + r * cos(a), cy + r * sin(a))
        }
    }

    /**
     * Bézier cúbica. Es la que usa el original para las puntas del rombo, con
     * **los dos tiradores sobre el vértice**: no es lo mismo que una cuadrática
     * que pase por ahí —la cúbica se queda más pegada al pico— y esa diferencia
     * es justo la forma de la punta.
     */
    fun cubicTo(c1: Pt, c2: Pt, to: Pt) {
        val from = pts.lastOrNull() ?: return
        val aprox = hypot(c1.x - from.x, c1.y - from.y) +
            hypot(c2.x - c1.x, c2.y - c1.y) + hypot(to.x - c2.x, to.y - c2.y)
        val cuerda = hypot(to.x - from.x, to.y - from.y)
        val n = samplesFor((cuerda + aprox) / 2)
        for (i in 1..n) {
            val t = i.toDouble() / n
            val u = 1 - t
            pts += Pt(
                u * u * u * from.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * to.x,
                u * u * u * from.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * to.y
            )
        }
    }

    /** Bézier cuadrática con el control en [ctrl], que es donde estaba el pico. */
    fun quadTo(ctrl: Pt, to: Pt) {
        val from = pts.lastOrNull() ?: return
        // La longitud real cae entre la cuerda —que la acota por abajo— y el
        // polígono de control, que la acota por arriba; la media de las dos es
        // la estimación barata de siempre. Usar solo el polígono la sobrestima
        // y saca más muestras de la cuenta, y entonces la punta queda muestreada
        // mucho más fina que la arista que llega hasta ella: exactamente el
        // desequilibrio que [OutlineBuilder] existe para evitar.
        val cuerda = hypot(to.x - from.x, to.y - from.y)
        val control = hypot(ctrl.x - from.x, ctrl.y - from.y) +
            hypot(to.x - ctrl.x, to.y - ctrl.y)
        val n = samplesFor((cuerda + control) / 2)
        for (i in 1..n) {
            val t = i.toDouble() / n
            val u = 1 - t
            pts += Pt(
                u * u * from.x + 2 * u * t * ctrl.x + t * t * to.x,
                u * u * from.y + 2 * u * t * ctrl.y + t * t * to.y
            )
        }
    }

    /**
     * El contorno cerrado. **Sin repetir el punto de partida**: quien traza se
     * encarga de cerrar, y un punto duplicado le daría a la spline dos vecinos
     * a distancia cero — justo lo contrario de lo que busca esta clase.
     */
    fun build(): List<Pt> {
        val first = pts.firstOrNull() ?: return emptyList()
        while (pts.size > 1 && hypot(pts.last().x - first.x, pts.last().y - first.y) < 1e-6) {
            pts.removeAt(pts.size - 1)
        }
        return pts.toList()
    }

    private fun samplesFor(length: Double): Int =
        Math.ceil(length / step).toInt().coerceIn(1, MAX_EDGE_SAMPLES)
}

/** Paso de muestreo que deja [CORNER_STEPS] puntos en un cuarto de arco de [r]. */
private fun stepFor(r: Double): Double =
    (r * (Math.PI / 2) / CORNER_STEPS).coerceAtLeast(2.0)

/**
 * Contorno de un rectángulo redondeado.
 *
 * **Empieza en el arranque del lado de arriba**, y no en mitad de una esquina
 * como antes. El trazo rugoso sortea ruido independiente para el primer punto y
 * para el último, así que los dos extremos nunca casan del todo: donde se juntan
 * queda una costura. Sobre un tramo recto no se ve; en mitad de una curva sí, y
 * es lo mismo que hace el original por lo mismo.
 */
fun roundedRectPoints(c: AbsoluteCoords, e: Element): List<Pt> {
    val w = c.x2 - c.x1
    val h = c.y2 - c.y1
    val r = getCornerRadius(min(w, h), e).coerceAtMost(min(w, h) / 2)
    if (r <= 0.0) {
        return listOf(Pt(c.x1, c.y1), Pt(c.x2, c.y1), Pt(c.x2, c.y2), Pt(c.x1, c.y2))
    }

    val b = OutlineBuilder(stepFor(r))
    b.moveTo(Pt(c.x1 + r, c.y1))
    b.lineTo(Pt(c.x2 - r, c.y1))
    b.arcTo(c.x2 - r, c.y1 + r, r, -Math.PI / 2, 0.0)
    b.lineTo(Pt(c.x2, c.y2 - r))
    b.arcTo(c.x2 - r, c.y2 - r, r, 0.0, Math.PI / 2)
    b.lineTo(Pt(c.x1 + r, c.y2))
    b.arcTo(c.x1 + r, c.y2 - r, r, Math.PI / 2, Math.PI)
    b.lineTo(Pt(c.x1, c.y1 + r))
    b.arcTo(c.x1 + r, c.y1 + r, r, Math.PI, Math.PI * 1.5)
    return b.build()
}

/**
 * Contorno de un rombo con las puntas redondeadas.
 *
 * **Faltaba entero.** El estilo por defecto trae `roundness`, así que el rombo
 * pedía puntas redondeadas desde el primer día y se dibujaba en pico igual que
 * los demás; en un rombo la diferencia canta, porque sus cuatro vértices son
 * ángulos agudos y el ruido de rough.js los remata en punta de flecha.
 *
 * Reproduce el camino del original carácter por carácter. La clave está en
 * **cómo recorta**: no avanza por la arista una distancia, sino que se desplaza
 * `vr` en horizontal y `hr` en vertical, con **un radio distinto por eje** —el
 * de la media anchura y el de la media altura—. Por eso un rombo aplastado no
 * se redondea igual arriba que a los lados, que es como tiene que ser: con un
 * radio único las puntas laterales quedarían romas y las de arriba en pico.
 *
 * Y la costura queda donde el original la pone, justo después de la punta de
 * arriba y sobre el tramo recto que baja a la derecha.
 */
fun roundedDiamondPoints(c: AbsoluteCoords, e: Element): List<Pt> {
    val v = diamondPoints(c)
    val (arriba, derecha, abajo, izquierda) = v
    val halfW = (c.x2 - c.x1) / 2
    val halfH = (c.y2 - c.y1) / 2
    if (halfW <= 0.0 || halfH <= 0.0) return v

    val vr = getCornerRadius(halfW, e)
    val hr = getCornerRadius(halfH, e)
    if (vr <= 0.0 && hr <= 0.0) return v

    // El paso de muestreo lo marca el radio menor: es el que decide cuántos
    // puntos necesita la punta más cerrada para no verse poligonal.
    val b = OutlineBuilder(stepFor(max(min(vr, hr), 1.0)))

    b.moveTo(Pt(arriba.x + vr, arriba.y + hr))
    b.lineTo(Pt(derecha.x - vr, derecha.y - hr))
    b.cubicTo(derecha, derecha, Pt(derecha.x - vr, derecha.y + hr))
    b.lineTo(Pt(abajo.x + vr, abajo.y - hr))
    b.cubicTo(abajo, abajo, Pt(abajo.x - vr, abajo.y - hr))
    b.lineTo(Pt(izquierda.x + vr, izquierda.y + hr))
    b.cubicTo(izquierda, izquierda, Pt(izquierda.x + vr, izquierda.y - hr))
    b.lineTo(Pt(arriba.x - vr, arriba.y + hr))
    b.cubicTo(arriba, arriba, Pt(arriba.x + vr, arriba.y + hr))
    return b.build()
}

// -------------------------------------------------------------------------
// Opciones de rough.js
// -------------------------------------------------------------------------

/** ¿El relleno lleva trazos rugosos, o es liso (o no hay)? */
fun needsRoughFill(e: Element): Boolean =
    e.hasBackground && !isTransparent(e.backgroundColor) && e.fillStyle != FillStyle.SOLID

/**
 * ¿Este lápiz encierra algo **y** hay con qué rellenarlo?
 *
 * Un garabato abierto no se rellena aunque tenga fondo elegido: no hay dentro
 * que pintar, y el relleno saldría por donde el trazo no ha llegado a cerrar.
 * El umbral de «cerrado» es el mismo que decide si se puede agarrar por dentro
 * ([isPathALoop]), así que lo que se ve relleno se coge por el relleno.
 */
fun rellenaSuLazo(e: Element): Boolean =
    e.isFreeDraw && e.hasBackground && !isTransparent(e.backgroundColor) &&
        isPathALoop(e.points)

/**
 * Cuánto se puede simplificar el camino del lápiz antes de rellenarlo.
 *
 * Es el `0.75` de Excalidraw. No es una optimización: el lápiz entrega cientos
 * de puntos casi pegados, y pasarle eso al barrido del rayado —que es cuadrático
 * en el número de aristas activas— dejaba el fotograma a medias. Tres cuartos de
 * píxel no los distingue nadie, y menos debajo del propio trazo.
 */
const val FREEDRAW_FILL_TOLERANCE = 0.75

/**
 * Opciones de rough.js para un elemento (`generateRoughOptions`).
 *
 * `fillWeight` y `hachureGap` se fijan a mano —y no se dejan derivar del grosor,
 * como haría rough.js por defecto— porque al engordar el trazo de las líneas
 * discontinuas el relleno cambiaría también sin que nadie lo haya pedido.
 */
fun roughOptionsFor(e: Element): RoughOptions = RoughOptions(
    seed = e.seed,
    strokeWidth = if (e.strokeStyle != StrokeStyle.SOLID) e.strokeWidth + 0.5 else e.strokeWidth,
    disableMultiStroke = e.strokeStyle != StrokeStyle.SOLID,
    fillWeight = e.strokeWidth / 2,
    hachureGap = e.strokeWidth * 4,
    roughness = adjustRoughness(e),
    fillStyle = e.fillStyle,
    preserveVertices = e.roughness < Element.ROUGHNESS_CARTOONIST
)

/**
 * Baja la rugosidad en las formas pequeñas (`adjustRoughness`).
 *
 * Un temblor de 2px en un cuadrado de 8 no parece dibujado a mano: parece roto.
 * El original define a partir de qué tamaños deja de tocarlo.
 */
fun adjustRoughness(e: Element): Double {
    val roughness = e.roughness.toDouble()
    val maxSize = max(e.width, e.height)
    val minSize = min(e.width, e.height)

    val bigEnough = (minSize >= 20 && maxSize >= 50) ||
        (minSize >= 15 && e.roundness != null && e.canChangeRoundness) ||
        (e.isLinear && maxSize >= 50)
    if (bigEnough) return roughness

    return min(roughness / (if (maxSize < 10) 3 else 2), 2.5)
}
