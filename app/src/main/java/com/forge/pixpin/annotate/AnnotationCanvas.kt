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
                    drawFreehand(
                        pts.size,
                        { vx(pts[it].x) }, { vy(pts[it].y) }, { pts[it].p },
                        Color(a.color), a.strokeWidth * scale
                    )
                }
                AnnotationType.MOSAIC -> drawMosaic(a)
                AnnotationType.TEXT -> {
                    val pos = a.points[0].toView()
                    drawText(
                        textMeasurer = textMeasurer,
                        text = a.text.orEmpty(),
                        topLeft = pos,
                        style = TextStyle(
                            color = Color(a.color),
                            fontSize = (a.strokeWidth * scale).toSp()
                        )
                    )
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
        controller.liveTemplate.value?.let { live ->
            val buf = controller.liveStroke
            @Suppress("UNUSED_EXPRESSION") version
            drawFreehand(
                buf.size,
                { vx(buf.x(it)) }, { vy(buf.y(it)) }, { buf.pressure(it) },
                // El borrador no deja marca, pero mientras se arrastra hay que ver
                // por dónde va o se borra a ciegas.
                if (live.type == AnnotationType.ERASER) Color(0x66FFFFFF) else Color(live.color),
                live.strokeWidth * scale
            )
        }
    }
}
