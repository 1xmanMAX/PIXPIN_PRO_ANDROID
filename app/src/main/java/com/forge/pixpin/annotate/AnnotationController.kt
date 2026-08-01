package com.forge.pixpin.annotate

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * Estado y operaciones del lienzo de anotaciones. La UI lee las listas
 * observables; el Canvas solo invalida el dibujado (sin recomposición).
 *
 * El trazo a mano alzada se lleva aparte, en un [StrokeBuffer]: es el único que
 * recibe cientos de puntos por segundo y no puede permitirse una lista nueva
 * (ni una recomposición) por muestra.
 */
class AnnotationController {

    val annotations = mutableStateListOf<Annotation>()

    /** Figura en curso (rectángulo, elipse, flecha, mosaico): dos puntos, copiar es barato. */
    val current = mutableStateOf<Annotation?>(null)

    /** Trazo a mano alzada en curso: puntos en [liveStroke], resto de atributos aquí. */
    val liveTemplate = mutableStateOf<Annotation?>(null)
    val liveStroke = StrokeBuffer()

    /** Cambia con cada muestra: el lienzo lo lee al dibujar para invalidarse sin recomponer. */
    val strokeVersion = mutableIntStateOf(0)

    val tool = mutableStateOf(AnnotationType.PENCIL)
    val color = mutableStateOf(0xFFF44336.toInt())
    val strokeWidth = mutableStateOf(8f)
    val filled = mutableStateOf(false)

    val canUndo = mutableStateOf(false)
    val canRedo = mutableStateOf(false)

    private val undoStack = UndoStack()

    private fun refreshUndoState() {
        canUndo.value = undoStack.canUndo
        canRedo.value = undoStack.canRedo
    }

    // ---- Entrada de gestos (coordenadas de imagen) ----

    /**
     * Cambia de herramienta cerrando antes lo que hubiera a medias (una
     * polilínea abierta se queda dibujada, no se pierde).
     */
    fun selectTool(type: AnnotationType) {
        if (tool.value == type) return
        finishPolyline()
        tool.value = type
    }

    fun begin(pt: Pt) {
        when (tool.value) {
            AnnotationType.RECT, AnnotationType.ELLIPSE, AnnotationType.ARROW,
            AnnotationType.MOSAIC, AnnotationType.SPOTLIGHT ->
                current.value = Annotation(tool.value, listOf(pt, pt), color.value,
                    strokeWidth.value, filled = filled.value)
            AnnotationType.PENCIL ->
                beginStroke(AnnotationType.PENCIL, color.value, strokeWidth.value, pt)
            AnnotationType.HIGHLIGHT ->
                beginStroke(AnnotationType.HIGHLIGHT, withAlpha(color.value, 0.35f),
                    strokeWidth.value * 4, pt)
            AnnotationType.ERASER ->
                beginStroke(AnnotationType.ERASER, 0, strokeWidth.value * 3, pt)
            AnnotationType.SERIAL ->
                current.value = Annotation(
                    AnnotationType.SERIAL, listOf(pt), color.value,
                    strokeWidth.value * 3, text = nextSerial().toString()
                )
            AnnotationType.POLYLINE -> {
                val open = current.value?.takeIf { it.type == AnnotationType.POLYLINE }
                current.value = if (open != null) {
                    // El último punto era la previsualización: se fija aquí y se
                    // arranca una nueva.
                    open.copy(points = open.points.dropLast(1) + listOf(pt, pt))
                } else {
                    Annotation(AnnotationType.POLYLINE, listOf(pt, pt), color.value,
                        strokeWidth.value)
                }
            }
            AnnotationType.TEXT -> Unit // el texto se gestiona con diálogo al hacer tap
        }
    }

    /**
     * El número que toca. Se cuenta lo que ya hay en el lienzo en vez de llevar
     * un contador aparte: así deshacer devuelve la numeración sola.
     */
    private fun nextSerial(): Int =
        annotations.count { it.type == AnnotationType.SERIAL } + 1

    /** ¿Hay una polilínea a medio hacer? (la barra enseña el botón de cerrarla). */
    val polylineOpen: Boolean
        get() = current.value?.type == AnnotationType.POLYLINE

    /** Cierra la polilínea abierta y la deja dibujada. */
    fun finishPolyline() {
        val c = current.value ?: return
        if (c.type != AnnotationType.POLYLINE) return
        current.value = null
        val vertices = c.points.dropLast(1) // fuera la previsualización
        if (vertices.size >= 2) {
            undoStack.push(annotations.toList())
            annotations.add(c.copy(points = vertices))
        }
        refreshUndoState()
    }

    private fun beginStroke(type: AnnotationType, argb: Int, width: Float, pt: Pt) {
        liveStroke.clear()
        liveStroke.add(pt.x, pt.y, pt.p)
        liveTemplate.value = Annotation(type, emptyList(), argb, width)
        strokeVersion.intValue++
    }

    fun drag(pt: Pt) {
        if (liveTemplate.value != null) {
            liveStroke.add(pt.x, pt.y, pt.p)
            strokeVersion.intValue++
            return
        }
        val c = current.value ?: return
        current.value = when (c.type) {
            // Solo se mueve la previsualización; los vértices ya puestos no.
            AnnotationType.POLYLINE -> c.copy(points = c.points.dropLast(1) + pt)
            AnnotationType.SERIAL -> c.copy(points = listOf(pt))
            else -> c.copy(points = listOf(c.points.first(), pt))
        }
    }

    fun end() {
        liveTemplate.value?.let { template ->
            val finished = template.copy(points = liveStroke.toPoints())
            liveTemplate.value = null
            liveStroke.clear()
            strokeVersion.intValue++
            commitStroke(finished)
            refreshUndoState()
            return
        }

        val c = current.value ?: return
        // La polilínea sigue abierta hasta que se cierre a propósito. Si el dedo
        // se movió, ese punto queda fijado como vértice (arrastrar dibuja un
        // tramo); si fue un toque seco, solo espera al siguiente vértice.
        if (c.type == AnnotationType.POLYLINE) {
            val pts = c.points
            if (pts.size >= 2 && pts[pts.size - 1] != pts[pts.size - 2]) {
                current.value = c.copy(points = pts + pts.last())
            }
            return
        }

        current.value = null
        if (c.type == AnnotationType.SERIAL) {
            undoStack.push(annotations.toList())
            annotations.add(c)
            refreshUndoState()
            return
        }
        val r = AnnotationGeometry.rectFrom(c.points[0], c.points[1])
        if (r[2] - r[0] >= 6 && r[3] - r[1] >= 6) {
            undoStack.push(annotations.toList())
            annotations.add(c)
        }
        refreshUndoState()
    }

    /** Descarta el trazo en curso sin guardarlo (llega un lápiz, se cancela el gesto). */
    fun cancel() {
        liveTemplate.value = null
        liveStroke.clear()
        current.value = null
        strokeVersion.intValue++
    }

    private fun commitStroke(stroke: Annotation) {
        // Un solo punto es un trazo válido: un punto de un bolígrafo, la tilde de
        // una «í». Antes se descartaba todo lo que no llegase a dos muestras.
        if (stroke.points.isEmpty()) return
        if (stroke.type == AnnotationType.ERASER) {
            val kept = annotations.filterNot {
                AnnotationGeometry.pathHitsAnnotation(stroke.points, it, stroke.strokeWidth)
            }
            if (kept.size == annotations.size) return // nada borrado: no ensuciar el historial
            undoStack.push(annotations.toList())
            annotations.clear()
            annotations.addAll(kept)
            return
        }
        undoStack.push(annotations.toList())
        annotations.add(stroke)
    }

    /**
     * Tamaño y ancho del último texto puesto. Poner tres seguidos del mismo
     * tamaño no debería obligar a ajustarlo tres veces.
     */
    val lastTextSize = mutableStateOf(40f)
    val lastTextBoxWidth = mutableStateOf<Float?>(240f)

    fun addText(pt: Pt, text: String, fontSize: Float, boxWidth: Float?) {
        if (text.isBlank()) return
        lastTextSize.value = fontSize
        lastTextBoxWidth.value = boxWidth
        undoStack.push(annotations.toList())
        annotations.add(
            Annotation(
                AnnotationType.TEXT, listOf(pt), color.value,
                fontSize, text = text, boxWidth = boxWidth
            )
        )
        refreshUndoState()
    }

    /**
     * Índice del texto que hay bajo [pt], o −1 si no hay ninguno. Se busca el
     * último: si dos se solapan, se edita el de encima, que es el que se ve.
     */
    fun textAt(pt: Pt): Int = annotations.indexOfLast {
        it.type == AnnotationType.TEXT && AnnotationGeometry.boundingBox(it).let { b ->
            pt.x >= b[0] && pt.x <= b[2] && pt.y >= b[1] && pt.y <= b[3]
        }
    }

    /** Sustituye un texto ya puesto, conservando su punto de anclaje. */
    fun replaceText(index: Int, text: String, fontSize: Float, boxWidth: Float?) {
        val old = annotations.getOrNull(index) ?: return
        if (text.isBlank()) return
        lastTextSize.value = fontSize
        lastTextBoxWidth.value = boxWidth
        undoStack.push(annotations.toList())
        annotations[index] = old.copy(
            text = text, strokeWidth = fontSize, boxWidth = boxWidth
        )
        refreshUndoState()
    }

    fun undo() {
        undoStack.undo(annotations.toList())?.let {
            annotations.clear()
            annotations.addAll(it)
        }
        refreshUndoState()
    }

    fun redo() {
        undoStack.redo(annotations.toList())?.let {
            annotations.clear()
            annotations.addAll(it)
        }
        refreshUndoState()
    }

    fun clearAll() {
        current.value = null
        if (annotations.isEmpty()) return
        undoStack.push(annotations.toList())
        annotations.clear()
        refreshUndoState()
    }

    private fun withAlpha(argb: Int, alpha: Float): Int {
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (a shl 24)
    }
}
