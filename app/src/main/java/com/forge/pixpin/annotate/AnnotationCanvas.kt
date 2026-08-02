package com.forge.pixpin.annotate

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp

/** Tamaño del bloque de pixelado, en px de imagen. */
private const val MOSAIC_BLOCK = 14

/** Adaptador del suavizado al Path de Compose. */
private class ComposePathSink(private val path: Path) : PathSink {
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun quadTo(controlX: Float, controlY: Float, x: Float, y: Float) =
        path.quadraticTo(controlX, controlY, x, y)
}

/**
 * Dibuja un trazo a mano alzada ya en coordenadas de vista.
 *
 * Tres casos, por orden de frecuencia: un solo punto (un toque seco) se dibuja
 * como punto redondo; con presión variable hay que ir tramo a tramo, porque un
 * `Path` solo admite un grosor; y el caso normal es un único `Path` suavizado.
 */
private fun DrawScope.drawFreehand(
    count: Int,
    xAt: (Int) -> Float,
    yAt: (Int) -> Float,
    pressureAt: (Int) -> Float,
    color: Color,
    baseWidth: Float
) {
    if (count <= 0) return
    if (count == 1) {
        drawCircle(
            color,
            radius = StrokeSmoothing.widthFor(baseWidth, pressureAt(0)) / 2f,
            center = Offset(xAt(0), yAt(0))
        )
        return
    }
    if (StrokeSmoothing.hasVariablePressure(count, pressureAt)) {
        for (i in 0 until count - 1) {
            drawLine(
                color,
                Offset(xAt(i), yAt(i)),
                Offset(xAt(i + 1), yAt(i + 1)),
                strokeWidth = StrokeSmoothing.widthFor(
                    baseWidth, (pressureAt(i) + pressureAt(i + 1)) / 2f
                ),
                cap = StrokeCap.Round
            )
        }
        return
    }
    val path = Path()
    StrokeSmoothing.feed(count, xAt, yAt, ComposePathSink(path))
    drawPath(
        path, color,
        style = Stroke(width = baseWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/**
 * Trazo vivo dibujado **sin rehacerlo entero en cada muestra**.
 *
 * Reconstruir el `Path` desde el principio costaba O(n) por muestra, y como se
 * redibuja en cada muestra, el trazo entero salía O(n²): empezaba fluido y se
 * iba atascando cuanto más largo. Con un lápiz que muestrea a cientos de Hz eso
 * son miles de puntos en unos segundos.
 *
 * El suavizado por puntos medios permite acumular: cada muestra nueva añade
 * exactamente un `quadTo` y no toca lo ya dibujado. Lo único provisional es el
 * tramo final hasta el último punto, que se pinta aparte en cada fotograma.
 */
/**
 * Caché del `Path` de los trazos YA TERMINADOS.
 *
 * Un trazo cerrado no vuelve a cambiar nunca, pero se reconstruía entero en cada
 * redibujado. Con quince trazos sobre la imagen, cada muestra del lápiz rehacía
 * los quince: por eso el lag no aparecía al empezar a dibujar sino a medida que
 * se acumulaban trazos.
 *
 * Se indexa por IDENTIDAD y no por valor: `Annotation` es un data class y su
 * hashCode recorre la lista de puntos entera, con lo que buscar en el mapa
 * costaría casi tanto como el problema que viene a resolver.
 */
private class StrokePathCache {
    private val cache = java.util.IdentityHashMap<Annotation, Path>()
    private var builtAtScale = Float.NaN

    fun pathFor(a: Annotation, scale: Float, build: (Path) -> Unit): Path {
        // Los caminos están en coordenadas de vista: al cambiar el zoom ya no valen.
        if (builtAtScale != scale) {
            cache.clear()
            builtAtScale = scale
        }
        // Deshacer y rehacer van dejando entradas huérfanas; con el mapa grande
        // sale más barato tirarlo entero que llevar la cuenta de cuáles sobran.
        if (cache.size > MAX_ENTRIES) cache.clear()
        return cache.getOrPut(a) { Path().also(build) }
    }

    private companion object {
        const val MAX_ENTRIES = 128
    }
}

private class LiveStrokePath {
    val path = Path()
    /** Cuántas muestras están ya dentro de [path]. */
    var built = 0

    fun reset() {
        path.reset()
        built = 0
    }

    /** Añade al camino los tramos estables que aún no estaban. */
    fun extendTo(count: Int, xAt: (Int) -> Float, yAt: (Int) -> Float) {
        if (count <= 0) return
        if (built == 0) {
            path.moveTo(xAt(0), yAt(0))
            built = 1
        }
        // El último punto siempre es provisional: su tramo se dibuja aparte.
        for (i in built until count - 1) {
            path.quadraticTo(
                xAt(i), yAt(i),
                (xAt(i) + xAt(i + 1)) / 2f,
                (yAt(i) + yAt(i + 1)) / 2f
            )
        }
        if (count >= 2) built = count - 1
    }

    /** Dónde acaba el camino acumulado: de ahí arranca el tramo provisional. */
    fun tailStart(count: Int, xAt: (Int) -> Float, yAt: (Int) -> Float): Offset =
        if (count <= 2) {
            Offset(xAt(0), yAt(0))
        } else {
            Offset(
                (xAt(count - 2) + xAt(count - 1)) / 2f,
                (yAt(count - 2) + yAt(count - 1)) / 2f
            )
        }
}

/** Cuánto se oscurece lo que queda fuera del foco. */
private const val SPOTLIGHT_DIM = 0.6f

/**
 * Oscurece la imagen dejando limpios los rectángulos marcados.
 *
 * Se dibuja **una sola capa oscura para todos los focos** y se le recortan los
 * huecos. Antes cada foco pintaba sus propias bandas alrededor: con dos focos,
 * la banda de uno caía sobre la del otro y la pantalla se iba oscureciendo más
 * cuanto más señalabas, que es justo lo contrario de lo que se busca.
 */
private fun DrawScope.drawSpotlights(holes: List<Rect>) {
    val canvas = drawContext.canvas
    val area = Rect(0f, 0f, size.width, size.height)
    canvas.saveLayer(area, Paint())
    drawRect(Color.Black.copy(alpha = SPOTLIGHT_DIM), size = size)
    for (hole in holes) {
        drawRect(
            color = Color.Transparent,
            topLeft = Offset(hole.left, hole.top),
            size = Size(hole.width.coerceAtLeast(0f), hole.height.coerceAtLeast(0f)),
            blendMode = BlendMode.Clear
        )
    }
    canvas.restore()
}

/**
 * Dibuja las anotaciones sobre el fotograma. Todo el estado observable se lee
 * DENTRO del bloque de dibujo del Canvas: añadir un punto al trazo no recompone,
 * solo redibuja (clave para 60+ fps con un lápiz que muestrea a cientos de Hz).
 */
@Composable
fun AnnotationCanvas(
    controller: AnnotationController,
    sourceBitmap: Bitmap,
    imageRectInView: Rect,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val scale = imageRectInView.width / sourceBitmap.width

    // Una única versión pixelada de TODA la imagen, calculada una vez. Antes se
    // recortaba y reescalaba un bitmap por fotograma mientras se arrastraba el
    // mosaico: eso son decenas de allocations por segundo en el hilo de UI.
    val pixelated = remember(sourceBitmap) {
        runCatching {
            Bitmap.createScaledBitmap(
                sourceBitmap,
                (sourceBitmap.width / MOSAIC_BLOCK).coerceAtLeast(1),
                (sourceBitmap.height / MOSAIC_BLOCK).coerceAtLeast(1),
                false
            ).asImageBitmap()
        }.getOrNull()
    }

    // Sobrevive a los redibujados: es lo que permite no rehacer el trazo entero
    // en cada muestra.
    val livePath = remember { LiveStrokePath() }

    // Los trazos ya cerrados no cambian: se construyen una vez y se reutilizan.
    val pathCache = remember { StrokePathCache() }

    Canvas(modifier = modifier.fillMaxSize()) {
        // Suscribe el DIBUJADO (no la composición) a cada muestra del trazo vivo.
        val version = controller.strokeVersion.intValue

        fun vx(x: Float) = imageRectInView.left + x * scale
        fun vy(y: Float) = imageRectInView.top + y * scale
        fun Pt.toView(): Offset = Offset(vx(x), vy(y))

        fun strokeOf(a: Annotation) = Stroke(
            width = a.strokeWidth * scale,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        fun drawMosaic(a: Annotation) {
            val small = pixelated ?: return
            val r = AnnotationGeometry.rectFrom(a.points[0], a.points[1])
            val l = r[0].coerceIn(0f, sourceBitmap.width - 1f).toInt()
            val t = r[1].coerceIn(0f, sourceBitmap.height - 1f).toInt()
            val rr = r[2].coerceIn(0f, sourceBitmap.width.toFloat()).toInt()
            val bb = r[3].coerceIn(0f, sourceBitmap.height.toFloat()).toInt()
            val w = rr - l
            val h = bb - t
            if (w < 4 || h < 4) return
            // Recorte sobre la imagen pixelada ya cacheada: cero allocations.
            val sx = (l / MOSAIC_BLOCK).coerceIn(0, small.width - 1)
            val sy = (t / MOSAIC_BLOCK).coerceIn(0, small.height - 1)
            val sw = (w / MOSAIC_BLOCK).coerceIn(1, small.width - sx)
            val sh = (h / MOSAIC_BLOCK).coerceIn(1, small.height - sy)
            drawImage(
                image = small,
                srcOffset = IntOffset(sx, sy),
                srcSize = IntSize(sw, sh),
                dstOffset = IntOffset(vx(l.toFloat()).toInt(), vy(t.toFloat()).toInt()),
                dstSize = IntSize((w * scale).toInt(), (h * scale).toInt()),
                filterQuality = if (a.variant == 1) FilterQuality.Low else FilterQuality.None
            )
        }

        fun drawAnnotation(a: Annotation) {
            when (a.type) {
                AnnotationType.RECT, AnnotationType.ELLIPSE -> {
                    val r = AnnotationGeometry.rectFrom(a.points[0], a.points[1])
                    val tl = Pt(r[0], r[1]).toView()
                    val size = Size((r[2] - r[0]) * scale, (r[3] - r[1]) * scale)
                    val style = if (a.filled) Fill else strokeOf(a)
                    if (a.type == AnnotationType.RECT) {
                        drawRect(Color(a.color), topLeft = tl, size = size, style = style)
                    } else {
                        drawOval(Color(a.color), topLeft = tl, size = size, style = style)
                    }
                }
                AnnotationType.LINE -> drawLine(
                    Color(a.color),
                    a.points[0].toView(),
                    a.points[1].toView(),
                    strokeWidth = a.strokeWidth * scale,
                    cap = StrokeCap.Round
                )
                AnnotationType.ARROW -> {
                    val start = a.points[0].toView()
                    val end = a.points[1].toView()
                    val color = Color(a.color)
                    val stroke = a.strokeWidth * scale
                    drawLine(color, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
                    val (h1, h2) = AnnotationGeometry.arrowHead(
                        a.points[0], a.points[1], a.strokeWidth * 4
                    )
                    drawLine(color, end, h1.toView(), strokeWidth = stroke, cap = StrokeCap.Round)
                    drawLine(color, end, h2.toView(), strokeWidth = stroke, cap = StrokeCap.Round)
                }
                AnnotationType.PENCIL, AnnotationType.HIGHLIGHT -> {
                    val pts = a.points
                    // Con grosor variable el trazo va tramo a tramo y no cabe en
                    // un Path único; ese caso no se puede cachear, pero solo se
                    // da con lápiz de presión.
                    if (pts.size < 3 || StrokeSmoothing.hasVariablePressure(pts)) {
                        drawFreehand(
                            pts.size,
                            { vx(pts[it].x) }, { vy(pts[it].y) }, { pts[it].p },
                            Color(a.color), a.strokeWidth * scale
                        )
                    } else {
                        val cached = pathCache.pathFor(a, scale) { path ->
                            StrokeSmoothing.feed(
                                pts.size, { vx(pts[it].x) }, { vy(pts[it].y) },
                                ComposePathSink(path)
                            )
                        }
                        drawPath(
                            cached, Color(a.color),
                            style = Stroke(
                                width = a.strokeWidth * scale,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
                AnnotationType.MOSAIC -> drawMosaic(a)
                AnnotationType.TEXT -> {
                    val pos = a.points[0].toView()
                    val style = TextStyle(
                        color = Color(a.color),
                        fontSize = (a.strokeWidth * scale).toSp()
                    )
                    val boxW = a.boxWidth
                    if (boxW == null) {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = a.text.orEmpty(),
                            topLeft = pos,
                            style = style
                        )
                    } else {
                        val pad = AnnotationGeometry.TEXT_BOX_PAD * scale
                        val outer = boxW * scale
                        val layout = textMeasurer.measure(
                            text = a.text.orEmpty(),
                            style = style,
                            constraints = Constraints(
                                maxWidth = (outer - pad * 2).toInt().coerceAtLeast(1)
                            )
                        )
                        drawRect(
                            color = Color(a.color),
                            topLeft = pos,
                            size = Size(outer, layout.size.height + pad * 2),
                            style = Stroke(width = 1.5f * scale)
                        )
                        drawText(layout, topLeft = Offset(pos.x + pad, pos.y + pad))
                    }
                }
                AnnotationType.POLYLINE -> {
                    val pts = a.points
                    val color = Color(a.color)
                    val width = a.strokeWidth * scale
                    for (i in 0 until pts.size - 1) {
                        drawLine(
                            color, pts[i].toView(), pts[i + 1].toView(),
                            strokeWidth = width, cap = StrokeCap.Round
                        )
                    }
                }
                AnnotationType.SERIAL -> {
                    val center = a.points[0].toView()
                    val radius = a.strokeWidth * scale
                    drawCircle(Color(a.color), radius, center)
                    val layout = textMeasurer.measure(
                        a.text.orEmpty(),
                        TextStyle(
                            color = Color(AnnotationGeometry.contrastingTextColor(a.color)),
                            fontSize = (radius * 1.1f).toSp()
                        )
                    )
                    drawText(
                        layout,
                        topLeft = Offset(
                            center.x - layout.size.width / 2f,
                            center.y - layout.size.height / 2f
                        )
                    )
                }
                // El foco no se dibuja aquí: va todo junto al final, en una sola
                // capa. Ver drawSpotlights.
                AnnotationType.SPOTLIGHT -> Unit
                AnnotationType.ERASER -> Unit
            }
        }

        // El foco va siempre el último: oscurece lo que hay debajo, incluidas
        // las demás anotaciones.
        controller.annotations.forEach {
            if (it.type != AnnotationType.SPOTLIGHT) drawAnnotation(it)
        }
        controller.current.value?.let { if (it.type != AnnotationType.SPOTLIGHT) drawAnnotation(it) }

        val spotlights = controller.annotations.filter { it.type == AnnotationType.SPOTLIGHT } +
            listOfNotNull(controller.current.value?.takeIf { it.type == AnnotationType.SPOTLIGHT })
        if (spotlights.isNotEmpty()) {
            drawSpotlights(spotlights.map { a ->
                val r = AnnotationGeometry.rectFrom(a.points[0], a.points[1])
                Rect(vx(r[0]), vy(r[1]), vx(r[2]), vy(r[3]))
            })
        }

        // Trazo vivo: los puntos salen del búfer plano, no de una lista.
        val live = controller.liveTemplate.value
        if (live == null) {
            livePath.reset()
        } else {
            val buf = controller.liveStroke
            @Suppress("UNUSED_EXPRESSION") version
            // El borrador no deja marca, pero mientras se arrastra hay que ver
            // por dónde va o se borra a ciegas.
            val color =
                if (live.type == AnnotationType.ERASER) Color(0x66FFFFFF) else Color(live.color)
            val width = live.strokeWidth * scale
            val n = buf.size
            val xAt = { i: Int -> vx(buf.x(i)) }
            val yAt = { i: Int -> vy(buf.y(i)) }

            when {
                n <= 0 -> Unit

                n == 1 -> drawCircle(
                    color,
                    radius = StrokeSmoothing.widthFor(width, buf.pressure(0)) / 2f,
                    center = Offset(xAt(0), yAt(0))
                )

                // Con presión variable el trazo va tramo a tramo con grosores
                // distintos y no cabe en un Path único: ahí no hay nada que
                // acumular, pero tampoco es el caso frecuente (solo con lápiz).
                StrokeSmoothing.hasVariablePressure(n) { buf.pressure(it) } -> {
                    livePath.reset()
                    drawFreehand(n, xAt, yAt, { buf.pressure(it) }, color, width)
                }

                else -> {
                    // Si el trazo se reinició (undo, cancelar) el acumulado ya no vale.
                    if (livePath.built > n) livePath.reset()
                    livePath.extendTo(n, xAt, yAt)
                    val stroke = Stroke(
                        width = width, cap = StrokeCap.Round, join = StrokeJoin.Round
                    )
                    drawPath(livePath.path, color, style = stroke)
                    // El tramo hasta el último punto, que aún puede moverse.
                    drawLine(
                        color,
                        livePath.tailStart(n, xAt, yAt),
                        Offset(xAt(n - 1), yAt(n - 1)),
                        strokeWidth = width,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
