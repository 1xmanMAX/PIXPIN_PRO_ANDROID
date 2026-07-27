package com.forge.pixpin.annotate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {

    private fun state(n: Int) = (1..n).map {
        Annotation(AnnotationType.RECT, listOf(Pt(0f, 0f), Pt(it.toFloat(), 1f)), 0, 1f)
    }

    @Test
    fun `undo y redo restauran estados`() {
        val stack = UndoStack()
        val s0 = state(0)
        val s1 = state(1)
        val s2 = state(2)

        stack.push(s0) // antes de añadir 1
        stack.push(s1) // antes de añadir 2

        assertTrue(stack.canUndo)
        assertEquals(s1, stack.undo(s2))
        assertEquals(s0, stack.undo(s1))
        assertNull(stack.undo(s0))
        assertFalse(stack.canUndo)

        assertTrue(stack.canRedo)
        assertEquals(s1, stack.redo(s0))
        assertEquals(s2, stack.redo(s1))
        assertFalse(stack.canRedo)
    }

    @Test
    fun `push limpia el redo`() {
        val stack = UndoStack()
        stack.push(state(0))
        stack.undo(state(1))
        assertTrue(stack.canRedo)
        stack.push(state(2))
        assertFalse(stack.canRedo)
    }
}
