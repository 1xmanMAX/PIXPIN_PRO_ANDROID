package com.forge.pixpin.annotate

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Punto en coordenadas de imagen (px del bitmap fuente). Propio para ser serializable. */
@Serializable
data class Pt(val x: Float, val y: Float)

enum class AnnotationType { RECT, ELLIPSE, ARROW, PENCIL, HIGHLIGHT, MOSAIC, TEXT, ERASER }

/**
 * Anotación vectorial: serializable, re-editable, en coordenadas de imagen.
 * strokeWidth en px de imagen; en TEXT hace de tamaño de fuente.
 */
@Serializable
data class Annotation(
    val type: AnnotationType,
    val points: List<Pt>,
    val color: Int,
    val strokeWidth: Float,
    val text: String? = null,
    val filled: Boolean = false,
    val variant: Int = 0 // MOSAIC: 0 = pixelado, 1 = desenfoque
)

/** Geometría pura de las anotaciones (sin Android: testable en JVM). */
object AnnotationGeometry {

    /** Dos puntos que forman la punta de la flecha (líneas desde end). */
    fun arrowHead(start: Pt, end: Pt, headLength: Float): Pair<Pt, Pt> {
        val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
        val spread = Math.toRadians(28.0)
        val a1 = angle + Math.PI - spread
        val a2 = angle + Math.PI + spread
        return Pt(
            (end.x + headLength * cos(a1)).toFloat(),
            (end.y + headLength * sin(a1)).toFloat()
        ) to Pt(
            (end.x + headLength * cos(a2)).toFloat(),
            (end.y + headLength * sin(a2)).toFloat()
        )
    }

    /** Rectángulo normalizado (min/max) desde dos puntos. */
    fun rectFrom(a: Pt, b: Pt): FloatArray {
        return floatArrayOf(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }

    /** Bounding box [left, top, right, bottom] de una anotación. */
    fun boundingBox(annotation: Annotation): FloatArray {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in annotation.points) {
            l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
        }
        val pad = annotation.strokeWidth / 2
        return floatArrayOf(l - pad, t - pad, r + pad, b + pad)
    }

    /** ¿Intersectan dos bounding boxes [l,t,r,b]? */
    fun boxesIntersect(a: FloatArray, b: FloatArray): Boolean {
        return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1]
    }

    /** Distancia punto-trayectoria simplificada: distancia mínima a los puntos muestreados. */
    fun pathHitsAnnotation(path: List<Pt>, annotation: Annotation, tolerance: Float): Boolean {
        if (!boxesIntersect(boundingBox(annotation), bboxOfPoints(path, tolerance))) return false
        val step = max(1, path.size / 24)
        for (i in path.indices step step) {
            val p = path[i]
            val bb = boundingBox(annotation)
            if (p.x >= bb[0] && p.x <= bb[2] && p.y >= bb[1] && p.y <= bb[3]) return true
        }
        return false
    }

    private fun bboxOfPoints(points: List<Pt>, pad: Float): FloatArray {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in points) {
            l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
        }
        return floatArrayOf(l - pad, t - pad, r + pad, b + pad)
    }

    fun distance(a: Pt, b: Pt): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
