package com.forge.pixpin.annotate

/** Deshacer/rehacer con snapshots (pocas decenas de anotaciones: es barato). */
class UndoStack {

    private val undoStack = ArrayDeque<List<Annotation>>()
    private val redoStack = ArrayDeque<List<Annotation>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Guarda el estado ANTES de una mutación. */
    fun push(stateBefore: List<Annotation>) {
        undoStack.addLast(stateBefore.toList())
        redoStack.clear()
    }

    fun undo(current: List<Annotation>): List<Annotation>? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current.toList())
        return prev
    }

    fun redo(current: List<Annotation>): List<Annotation>? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current.toList())
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
