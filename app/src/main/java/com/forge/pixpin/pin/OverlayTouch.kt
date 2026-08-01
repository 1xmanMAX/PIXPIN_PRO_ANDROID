package com.forge.pixpin.pin

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Reconocedor de gestos para ventanas overlay.
 *
 * Por qué no se usan los gestos de Compose: al arrastrar movemos la PROPIA
 * ventana, así que la posición del dedo relativa a la ventana apenas cambia y
 * los deltas locales se anulan entre sí (movimiento a tirones). Con
 * MotionEvent.getRawX/getRawY trabajamos en coordenadas absolutas de pantalla,
 * inmunes a que la ventana se mueva bajo el dedo.
 *
 * Gestos: arrastrar (1 dedo), pellizcar (2 dedos = zoom con foco entre los
 * dedos), deslizar vertical con 2 dedos (opacidad), toque, doble toque y
 * pulsación larga.
 */
class OverlayTouchHandler(
    context: Context,
    private val listener: Listener
) : View.OnTouchListener {

    interface Listener {
        fun onDragStart() {}
        fun onDrag(dxFromDown: Float, dyFromDown: Float)
        fun onDragEnd() {}
        /** focusX/focusY: punto medio entre los dedos, en coordenadas de pantalla. */
        fun onScaleStart(focusX: Float, focusY: Float) {}
        fun onScale(factorFromDown: Float, focusX: Float, focusY: Float) {}
        fun onScaleEnd() {}
        fun onOpacityStart() {}
        fun onOpacity(dyFromDown: Float) {}
        fun onOpacityEnd() {}

        /**
         * Arranca el redimensionado: el dedo ha bajado dentro de [handleRect].
         * No espera al touch slop — un handle de 30 dp no da margen para eso.
         */
        fun onResizeStart() {}
        fun onResize(dxFromDown: Float, dyFromDown: Float) {}
        fun onResizeEnd() {}

        fun onTap() {}
        fun onDoubleTap() {}
        fun onLongPress() {}
    }

    private enum class Mode { NONE, DRAG, SCALE, OPACITY, RESIZE }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /**
     * Esquina que redimensiona en vez de arrastrar, en coordenadas LOCALES de la
     * vista (no raw): el dueño la calcula midiendo su propio contenido. null =
     * la ventana no se redimensiona.
     */
    var handleRect: android.graphics.Rect? = null

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startSpan = 0f
    private var startMidY = 0f
    private var twoFingerDecided = false

    /**
     * En este gesto ha habido 2+ dedos. Se mantiene hasta soltarlos TODOS: al
     * levantar solo uno, el que queda arrastraba el pin de golpe desde el punto
     * donde empezó el gesto — un salto que lo mandaba fuera de la pantalla.
     */
    private var multiTouch = false

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (idle()) listener.onTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (idle()) listener.onDoubleTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (idle()) listener.onLongPress()
            }
        }
    )

    private fun idle() = mode == Mode.NONE && !multiTouch

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                mode = Mode.NONE
                twoFingerDecided = false
                multiTouch = false
                if (handleRect?.contains(event.x.toInt(), event.y.toInt()) == true) {
                    mode = Mode.RESIZE
                    listener.onResizeStart()
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    if (mode == Mode.DRAG) {
                        listener.onDragEnd()
                        mode = Mode.NONE
                    }
                    multiTouch = true
                    startSpan = span(event)
                    startMidY = midRawY(event)
                    twoFingerDecided = false
                }
            }

            MotionEvent.ACTION_MOVE -> handleMove(event)

            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) finishGesture()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishGesture()
                multiTouch = false
            }
        }
        return true
    }

    private fun handleMove(event: MotionEvent) {
        // Los deltas van en coordenadas raw: la ventana cambia de tamaño bajo el
        // dedo, así que los locales se falsearían igual que con el arrastre.
        if (mode == Mode.RESIZE) {
            listener.onResize(event.rawX - downX, event.rawY - downY)
            return
        }
        if (event.pointerCount >= 2) {
            val currentSpan = span(event)
            val currentMid = midRawY(event)
            if (!twoFingerDecided) {
                val spanDelta = abs(currentSpan - startSpan)
                val midDelta = abs(currentMid - startMidY)
                if (spanDelta > slop * 1.5f || midDelta > slop * 1.5f) {
                    twoFingerDecided = true
                    if (spanDelta > midDelta) {
                        mode = Mode.SCALE
                        listener.onScaleStart(midRawX(event), currentMid)
                    } else {
                        mode = Mode.OPACITY
                        listener.onOpacityStart()
                    }
                }
            }
            when (mode) {
                Mode.SCALE -> if (startSpan > 0f) {
                    listener.onScale(currentSpan / startSpan, midRawX(event), currentMid)
                }
                Mode.OPACITY -> listener.onOpacity(currentMid - startMidY)
                else -> Unit
            }
            return
        }

        // Un solo dedo, pero venimos de un gesto de dos: no arrastrar.
        if (multiTouch) return

        val dx = event.rawX - downX
        val dy = event.rawY - downY
        if (mode == Mode.NONE && hypot(dx, dy) > slop) {
            mode = Mode.DRAG
            listener.onDragStart()
        }
        if (mode == Mode.DRAG) listener.onDrag(dx, dy)
    }

    private fun finishGesture() {
        when (mode) {
            Mode.DRAG -> listener.onDragEnd()
            Mode.SCALE -> listener.onScaleEnd()
            Mode.OPACITY -> listener.onOpacityEnd()
            Mode.RESIZE -> listener.onResizeEnd()
            Mode.NONE -> Unit
        }
        mode = Mode.NONE
        twoFingerDecided = false
    }

    private fun span(event: MotionEvent): Float =
        hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private fun midRawX(event: MotionEvent): Float =
        (event.getRawX(0) + event.getRawX(1)) / 2f

    private fun midRawY(event: MotionEvent): Float =
        (event.getRawY(0) + event.getRawY(1)) / 2f
}
