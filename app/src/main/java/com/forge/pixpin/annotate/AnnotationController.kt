package com.forge.pixpin.annotate

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * Estado y operaciones del lienzo de anotaciones. La UI lee las listas
 * observables; el Canvas solo invalida el dibujado (sin recomposición).
 */
class AnnotationController {

    val annotations = mutableStateListOf<Annotation>()
    val current = mutableStateOf<Annotation?>(null)

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

    fun begin(pt: Pt) {
        current.value = when (tool.value) {
            AnnotationType.RECT, AnnotationType.ELLIPSE, AnnotationType.ARROW,
            AnnotationType.MOSAIC ->
                Annotation(tool.value, listOf(pt, pt), color.value, strokeWidth.value,
                    filled = filled.value)
            AnnotationType.PENCIL ->
                Annotation(AnnotationType.PENCIL, listOf(pt), color.value, strokeWidth.value)
            AnnotationType.HIGHLIGHT ->
                Annotation(AnnotationType.HIGHLIGHT, listOf(pt),
                    withAlpha(color.value, 0.35f), strokeWidth.value * 4)
            AnnotationType.ERASER ->
                Annotation(AnnotationType.ERASER, listOf(pt), 0, strokeWidth.value * 3)
            AnnotationType.TEXT -> null // el texto se gestiona con diálogo al hacer tap
        }
    }

    fun drag(pt: Pt) {
        val c = current.value ?: return
        current.value = when (c.type) {
            AnnotationType.RECT, AnnotationType.ELLIPSE, AnnotationType.ARROW,
            AnnotationType.MOSAIC ->
                c.copy(points = listOf(c.points.first(), pt))
            else ->
                c.copy(points = c.points + pt)
        }
    }

    fun end() {
        val c = current.value ?: return
        current.value = null
        when (c.type) {
            AnnotationType.ERASER -> {
                if (c.points.size >= 2) {
                    undoStack.push(annotations.toList())
                    val tolerance = c.strokeWidth
                    val kept = annotations.filterNot {
                        AnnotationGeometry.pathHitsAnnotation(c.points, it, tolerance)
                    }
                    annotations.clear()
                    annotations.addAll(kept)
                }
            }
            AnnotationType.PENCIL, AnnotationType.HIGHLIGHT -> {
                if (c.points.size >= 2) {
                    undoStack.push(annotations.toList())
                    annotations.add(c)
                }
            }
            AnnotationType.RECT, AnnotationType.ELLIPSE, AnnotationType.ARROW,
            AnnotationType.MOSAIC -> {
                val r = AnnotationGeometry.rectFrom(c.points[0], c.points[1])
                if (r[2] - r[0] >= 6 && r[3] - r[1] >= 6) {
                    undoStack.push(annotations.toList())
                    annotations.add(c)
                }
            }
            AnnotationType.TEXT -> Unit
        }
        refreshUndoState()
    }

    fun addText(pt: Pt, text: String) {
        if (text.isBlank()) return
        undoStack.push(annotations.toList())
        annotations.add(
            Annotation(AnnotationType.TEXT, listOf(pt), color.value,
                strokeWidth.value * 5, text = text)
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
