package com.forge.pixpin.annotate

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Punto en coordenadas de imagen (px del bitmap fuente). Propio para ser serializable.
 *
 * [p] es la presión del lápiz, normalizada a 0..1; con el dedo llega siempre 1.
 * Tiene valor por defecto para que los pines ya guardados en disco sigan
 * leyéndose sin migración.
 */
@Serializable
data class Pt(val x: Float, val y: Float, val p: Float = 1f)

enum class AnnotationType {
    RECT, ELLIPSE, ARROW, PENCIL, HIGHLIGHT, MOSAIC, TEXT, ERASER,

    /** Recta simple, sin punta: subrayar, tachar, separar. */
    LINE,

    /** Un toque = un círculo numerado 1, 2, 3… El número va en `text`. */
    SERIAL,

    /** Rectas encadenadas: el último punto es la previsualización hasta el dedo. */
    POLYLINE,

    /** Oscurece todo MENOS el rectángulo marcado. Se dibuja siempre el último. */
    SPOTLIGHT
}

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
    val variant: Int = 0, // MOSAIC: 0 = pixelado, 1 = desenfoque
    /**
     * Solo en TEXT: ancho del cuadro en px de imagen. Con null el texto se
     * dibuja suelto en una línea, que es como se guardaron los de la v0.2.
     */
    val boxWidth: Float? = null
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

    /** Margen interior del cuadro de texto, en px de imagen. */
    const val TEXT_BOX_PAD = 8f

    /**
     * Rectángulo [l, t, r, b] que ocupa una anotación de texto.
     *
     * El alto se estima contando caracteres por línea en vez de medirlo: medirlo
     * de verdad exige StaticLayout, que es de Android, y esto solo lo usan el
     * borrador y el toque para reeditar — ahí una aproximación basta y se puede
     * comprobar sin dispositivo.
     */
    fun textBoxBounds(a: Annotation): FloatArray {
        val p = a.points.first()
        val avgChar = a.strokeWidth * 0.55f
        val width = a.boxWidth
        if (width == null) {
            val w = avgChar * (a.text?.length ?: 0)
            return floatArrayOf(p.x, p.y - a.strokeWidth, p.x + w, p.y)
        }
        val perLine = ((width - TEXT_BOX_PAD * 2) / avgChar).toInt().coerceAtLeast(1)
        val lines = a.text.orEmpty().split('\n').sumOf { line ->
            maxOf(1, (line.length + perLine - 1) / perLine)
        }
        val h = lines * a.strokeWidth * 1.3f + TEXT_BOX_PAD * 2
        return floatArrayOf(p.x, p.y, p.x + width, p.y + h)
    }

    /** Bounding box [left, top, right, bottom] de una anotación. */
    fun boundingBox(annotation: Annotation): FloatArray {
        // El texto no se mide por sus puntos: solo tiene uno, el de anclaje.
        if (annotation.type == AnnotationType.TEXT) return textBoxBounds(annotation)
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

    /**
     * Blanco o negro, el que se lea sobre [argb]. Lo usa el número de serie,
     * que va dentro de un círculo del color elegido.
     */
    fun contrastingTextColor(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return if (luminance > 150f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    fun distance(a: Pt, b: Pt): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
