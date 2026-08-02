package com.forge.pixpin.annotate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Hornea las anotaciones sobre el recorte final del bitmap
 * (para guardar / copiar / pinear el resultado).
 */
object AnnotationRenderer {

    fun bake(
        source: Bitmap,
        region: Rect,
        annotations: List<Annotation>,
        cornerRadiusPx: Float = 0f
    ): Bitmap {
        val w = region.width()
        val h = region.height()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(
            source, region,
            RectF(0f, 0f, w.toFloat(), h.toFloat()), null
        )

        val ox = region.left.toFloat()
        val oy = region.top.toFloat()

        for (a in annotations) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = a.color
                strokeWidth = a.strokeWidth
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                style = if (a.filled) Paint.Style.FILL else Paint.Style.STROKE
            }
            when (a.type) {
                AnnotationType.RECT, AnnotationType.ELLIPSE -> {
                    val r = AnnotationGeometry.rectFrom(a.points[0], a.points[1])
                    val rect = RectF(r[0] - ox, r[1] - oy, r[2] - ox, r[3] - oy)
                    if (a.type == AnnotationType.RECT) canvas.drawRect(rect, paint)
                    else canvas.drawOval(rect, paint)
                }
                AnnotationType.ARROW -> {
                    val s = a.points[0]
                    val e = a.points[1]
                    canvas.drawLine(s.x - ox, s.y - oy, e.x - ox, e.y - oy, paint)
                    val (h1, h2) = AnnotationGeometry.arrowHead(s, e, a.strokeWidth * 4)
                    canvas.drawLine(e.x - ox, e.y - oy, h1.x - ox, h1.y - oy, paint)
                    canvas.drawLine(e.x - ox, e.y - oy, h2.x - ox, h2.y - oy, paint)
                }
                AnnotationType.PENCIL, AnnotationType.HIGHLIGHT ->
                    drawFreehand(canvas, a, ox, oy, paint)
                AnnotationType.MOSAIC -> {
                    val r = AnnotationGeometry.rectFrom(a.points[0], a.points[1])
                    val l = r[0].toInt().coerceIn(0, source.width - 1)
                    val t = r[1].toInt().coerceIn(0, source.height - 1)
                    val rr = r[2].toInt().coerceIn(0, source.width)
                    val bb = r[3].toInt().coerceIn(0, source.height)
                    if (rr - l < 4 || bb - t < 4) continue
                    val crop = Bitmap.createBitmap(source, l, t, rr - l, bb - t)
                    val small = Bitmap.createScaledBitmap(
                        crop, ((rr - l) / 14).coerceAtLeast(1), ((bb - t) / 14).coerceAtLeast(1), false
                    )
                    val mosaicPaint = Paint(
                        if (a.variant == 1) Paint.FILTER_BITMAP_FLAG else 0
                    )
                    canvas.drawBitmap(
                        small, null,
                        RectF(l - ox, t - oy, rr - ox, bb - oy),
                        mosaicPaint
                    )
                }
                AnnotationType.TEXT -> {
                    paint.style = Paint.Style.FILL
                    paint.textSize = a.strokeWidth
                    val boxW = a.boxWidth
                    val left = a.points[0].x - ox
                    val top = a.points[0].y - oy
                    if (boxW == null) {
                        canvas.drawText(
                            a.text.orEmpty(), left, top + a.strokeWidth * 0.9f, paint
                        )
                    } else {
                        // Mismo cálculo que en AnnotationCanvas: si los dos
                        // renderizadores no coinciden, lo exportado no es lo
                        // que se vio en pantalla.
                        val pad = AnnotationGeometry.TEXT_BOX_PAD
                        val body = a.text.orEmpty()
                        val inner = (boxW - pad * 2).toInt().coerceAtLeast(1)
                        val layout = StaticLayout.Builder
                            .obtain(body, 0, body.length, TextPaint(paint), inner)
                            .build()
                        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = a.color
                            style = Paint.Style.STROKE
                            strokeWidth = 1.5f
                        }
                        canvas.drawRect(
                            left, top, left + boxW, top + layout.height + pad * 2, border
                        )
                        canvas.save()
                        canvas.translate(left + pad, top + pad)
                        layout.draw(canvas)
                        canvas.restore()
                    }
                }
                AnnotationType.LINE -> canvas.drawLine(
                    a.points[0].x - ox, a.points[0].y - oy,
                    a.points[1].x - ox, a.points[1].y - oy, paint
                )
                AnnotationType.POLYLINE -> {
                    val pts = a.points
                    for (i in 0 until pts.size - 1) {
                        canvas.drawLine(
                            pts[i].x - ox, pts[i].y - oy,
                            pts[i + 1].x - ox, pts[i + 1].y - oy, paint
                        )
                    }
                }
                AnnotationType.SERIAL -> {
                    val cx = a.points[0].x - ox
                    val cy = a.points[0].y - oy
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy, a.strokeWidth, paint)
                    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = AnnotationGeometry.contrastingTextColor(a.color)
                        textSize = a.strokeWidth * 1.1f
                        textAlign = Paint.Align.CENTER
                    }
                    // El centro óptico del texto no es su baseline.
                    val baseline = cy - (label.descent() + label.ascent()) / 2f
                    canvas.drawText(a.text.orEmpty(), cx, baseline, label)
                }
                // Va aparte, todos juntos en una sola capa (ver más abajo).
                AnnotationType.SPOTLIGHT -> Unit
                AnnotationType.ERASER -> Unit
            }
        }

        // El foco, el último y todos en la misma capa: una sola veladura con
        // tantos huecos como focos haya. Pintando cada uno por su cuenta, dos
        // focos oscurecían el doble.
        val holes = annotations.filter { it.type == AnnotationType.SPOTLIGHT }
        if (holes.isNotEmpty()) {
            val layer = canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
            canvas.drawColor((0xFF * 0.6f).toInt() shl 24)
            val cut = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            for (a in holes) {
                val r = AnnotationGeometry.rectFrom(a.points[0], a.points[1])
                canvas.drawRect(r[0] - ox, r[1] - oy, r[2] - ox, r[3] - oy, cut)
            }
            canvas.restoreToCount(layer)
        }

        if (cornerRadiusPx > 0f) {
            val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            canvas.drawRoundRect(
                RectF(0f, 0f, w.toFloat(), h.toFloat()),
                cornerRadiusPx, cornerRadiusPx, mask
            )
        }
        return out
    }

    /**
     * Trazo a mano alzada, con el mismo suavizado y la misma presión que en
     * pantalla: lo guardado tiene que ser idéntico a lo que se vio al dibujar.
     */
    private fun drawFreehand(canvas: Canvas, a: Annotation, ox: Float, oy: Float, paint: Paint) {
        val pts = a.points
        if (pts.isEmpty()) return
        if (pts.size == 1) {
            val filled = Paint(paint).apply { style = Paint.Style.FILL }
            canvas.drawCircle(
                pts[0].x - ox, pts[0].y - oy,
                StrokeSmoothing.widthFor(a.strokeWidth, pts[0].p) / 2f, filled
            )
            return
        }
        if (StrokeSmoothing.hasVariablePressure(pts)) {
            for (i in 0 until pts.size - 1) {
                paint.strokeWidth =
                    StrokeSmoothing.widthFor(a.strokeWidth, (pts[i].p + pts[i + 1].p) / 2f)
                canvas.drawLine(
                    pts[i].x - ox, pts[i].y - oy,
                    pts[i + 1].x - ox, pts[i + 1].y - oy, paint
                )
            }
            return
        }
        val path = Path()
        StrokeSmoothing.feed(
            pts.size, { pts[it].x - ox }, { pts[it].y - oy }, AndroidPathSink(path)
        )
        canvas.drawPath(path, paint)
    }

    /** Adaptador del suavizado al Path de android.graphics. */
    private class AndroidPathSink(private val path: Path) : PathSink {
        override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
        override fun quadTo(controlX: Float, controlY: Float, x: Float, y: Float) =
            path.quadTo(controlX, controlY, x, y)
    }
}
