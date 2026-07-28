package com.forge.pixpin.capture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/** Qué hace el arrastre sobre la selección de recorte. */
enum class DragMode { NONE, NEW, MOVE, TL, TR, BL, BR }

/** Geometría pura del recorte (sin Android: testable en JVM). */
object SelectionGeometry {

    private const val MIN_SIZE = 24f

    /** Decide, al empezar el arrastre, si se crea, se mueve o se ajusta una esquina. */
    fun classifyDrag(pos: Offset, sel: Rect?, density: Float): DragMode {
        if (sel == null) return DragMode.NEW
        val zone = 36f * density
        val corners = listOf(
            DragMode.TL to Offset(sel.left, sel.top),
            DragMode.TR to Offset(sel.right, sel.top),
            DragMode.BL to Offset(sel.left, sel.bottom),
            DragMode.BR to Offset(sel.right, sel.bottom)
        )
        for ((mode, corner) in corners) {
            if ((pos - corner).getDistance() < zone) return mode
        }
        return if (sel.contains(pos)) DragMode.MOVE else DragMode.NEW
    }

    /**
     * Esquina que se queda quieta durante el arrastre. Calcularla UNA vez al
     * empezar es lo que permite dibujar la selección en cualquier dirección: si
     * se recalcula en cada evento a partir del propio rect, el ancla persigue al
     * dedo y la selección solo puede crecer hacia abajo y a la derecha.
     */
    fun anchorFor(mode: DragMode, sel: Rect?, start: Offset): Offset = when {
        sel == null || mode == DragMode.NEW -> start
        mode == DragMode.TL -> Offset(sel.right, sel.bottom)
        mode == DragMode.TR -> Offset(sel.left, sel.bottom)
        mode == DragMode.BL -> Offset(sel.right, sel.top)
        mode == DragMode.BR -> Offset(sel.left, sel.top)
        else -> start
    }

    fun update(
        mode: DragMode,
        sel: Rect,
        anchor: Offset,
        pos: Offset,
        amount: Offset,
        bounds: Rect
    ): Rect = when (mode) {
        DragMode.MOVE -> {
            val l = (sel.left + amount.x).coerceIn(bounds.left, bounds.right - sel.width)
            val t = (sel.top + amount.y).coerceIn(bounds.top, bounds.bottom - sel.height)
            Rect(l, t, l + sel.width, t + sel.height)
        }
        DragMode.NEW, DragMode.TL, DragMode.TR, DragMode.BL, DragMode.BR -> {
            val moving = Offset(
                pos.x.coerceIn(bounds.left, bounds.right),
                pos.y.coerceIn(bounds.top, bounds.bottom)
            )
            val l = min(anchor.x, moving.x)
            val t = min(anchor.y, moving.y)
            var r = max(anchor.x, moving.x)
            var b = max(anchor.y, moving.y)
            if (r - l < MIN_SIZE) r = l + MIN_SIZE
            if (b - t < MIN_SIZE) b = t + MIN_SIZE
            Rect(l, t, r, b)
        }
        DragMode.NONE -> sel
    }
}
