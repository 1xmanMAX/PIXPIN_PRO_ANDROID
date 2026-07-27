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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/**
 * Dibuja las anotaciones sobre el fotograma. Las listas observables se leen
 * dentro del bloque de dibujo del Canvas: invalidar el trazo NO recompone,
 * solo redibuja (clave para 60+ fps).
 */
@Composable
fun AnnotationCanvas(
    controller: AnnotationController,
    sourceBitmap: Bitmap,
    imageRectInView: Rect,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val mosaicCache = remember { mutableMapOf<Int, ImageBitmap>() }
    val annotations = controller.annotations
    val current = controller.current.value
    val scale = imageRectInView.width / sourceBitmap.width

    Canvas(modifier = modifier.fillMaxSize()) {
        fun Pt.toView(): Offset =
            Offset(imageRectInView.left + x * scale, imageRectInView.top + y * scale)

        fun strokeOf(a: Annotation) = Stroke(
            width = a.strokeWidth * scale,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        fun drawMosaic(a: Annotation) {
            val r = AnnotationGeometry.rectFrom(a.points[0], a.points[1])
            val l = r[0].coerceIn(0f, sourceBitmap.width - 1f).toInt()
            val t = r[1].coerceIn(0f, sourceBitmap.height - 1f).toInt()
            val rr = r[2].coerceIn(0f, sourceBitmap.width.toFloat()).toInt()
            val bb = r[3].coerceIn(0f, sourceBitmap.height.toFloat()).toInt()
            val w = rr - l
            val h = bb - t
            if (w < 4 || h < 4) return
            val key = a.hashCode()
            val small = mosaicCache.getOrPut(key) {
                val crop = Bitmap.createBitmap(sourceBitmap, l, t, w, h)
                Bitmap.createScaledBitmap(crop, (w / 14).coerceAtLeast(1), (h / 14).coerceAtLeast(1), false)
                    .asImageBitmap()
            }
            drawImage(
                image = small,
                dstOffset = Offset(
                    imageRectInView.left + l * scale,
                    imageRectInView.top + t * scale
                ).let { androidx.compose.ui.unit.IntOffset(it.x.toInt(), it.y.toInt()) },
                dstSize = androidx.compose.ui.unit.IntSize((w * scale).toInt(), (h * scale).toInt()),
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
                    if (a.points.size < 2) return
                    val path = Path()
                    val first = a.points.first().toView()
                    path.moveTo(first.x, first.y)
                    for (i in 1 until a.points.size) {
                        val p = a.points[i].toView()
                        path.lineTo(p.x, p.y)
                    }
                    drawPath(path, Color(a.color), style = strokeOf(a))
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
                AnnotationType.ERASER -> Unit
            }
        }

        if (mosaicCache.size > annotations.size + 1) mosaicCache.clear()
        annotations.forEach { drawAnnotation(it) }
        current?.let { drawAnnotation(it) }
    }
}
