package com.forge.pixpin.annotate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF

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
                AnnotationType.PENCIL, AnnotationType.HIGHLIGHT -> {
                    if (a.points.size < 2) continue
                    val path = Path()
                    path.moveTo(a.points[0].x - ox, a.points[0].y - oy)
                    for (i in 1 until a.points.size) {
                        path.lineTo(a.points[i].x - ox, a.points[i].y - oy)
                    }
                    canvas.drawPath(path, paint)
                }
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
                    canvas.drawText(
                        a.text.orEmpty(),
                        a.points[0].x - ox,
                        a.points[0].y - oy + a.strokeWidth * 0.9f,
                        paint
                    )
                }
                AnnotationType.ERASER -> Unit
            }
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
}
