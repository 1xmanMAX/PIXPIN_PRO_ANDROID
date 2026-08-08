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

        /**
         * Arrastre sobre la pastilla de desplazamiento. Como el handle, se
         * decide en el ACTION_DOWN: es una zona pequeña y esperar al slop la
         * haría inagarrable.
         */
        fun onScrollStart() {}
        fun onScrollDrag(dyFromDown: Float) {}
        fun onScrollEnd() {}

        /** x/y en coordenadas LOCALES de la ventana: sirven para saber qué se tocó. */
        fun onTap(x: Float, y: Float) {}
        fun onDoubleTap() {}
        fun onLongPress() {}

        /**
         * Dos dedos a la vez, dos veces seguidas y sin mover nada.
         *
         * Hacía falta un gesto libre para entrar a dibujar en el pin, y de los
         * que quedaban este es el único que no le quita el sitio a ninguno: con
         * un dedo el toque copia y el doble toque minimiza, y con dos dedos ya
         * se escala y se gradúa la opacidad, pero **solo al arrastrar**. Posar
         * dos dedos y levantarlos no significaba nada.
         */
        fun onTwoFingerDoubleTap() {}
    }

    private enum class Mode { NONE, DRAG, SCALE, OPACITY, RESIZE, SCROLL }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /**
     * Esquina que redimensiona en vez de arrastrar, en coordenadas LOCALES de la
     * vista (no raw): el dueño la calcula midiendo su propio contenido. null =
     * la ventana no se redimensiona.
     */
    var handleRect: android.graphics.Rect? = null

    /** Zona de la pastilla de desplazamiento, también en coordenadas locales. */
    var scrollRect: android.graphics.Rect? = null

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

    // ---- Doble toque con dos dedos ----
    //
    // Se reconoce a mano y no con el `GestureDetector`, que solo entiende de un
    // dedo: en cuanto baja el segundo cancela lo que llevara. Lo que se mira es
    // que en el gesto hayan llegado a estar dos dedos, que **no haya pasado
    // nada más** —ni arrastre, ni pellizco, ni opacidad— y que haya durado poco.

    /** ¿Han llegado a estar dos dedos en este gesto? */
    private var dosDedos = false

    /** ¿El gesto pasó a hacer algo? Entonces no fue un toque. */
    private var huboGesto = false

    /** Cuándo bajó el primer dedo de este gesto. */
    private var gestoInicio = 0L

    /** Cuándo acabó el último toque de dos dedos, para emparejarlos. */
    private var ultimoDosDedos = 0L

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (idle()) listener.onTap(e.x, e.y)
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
                dosDedos = false
                huboGesto = false
                gestoInicio = event.eventTime
                val px = event.x.toInt()
                val py = event.y.toInt()
                // El handle gana si se solapan: es el de la esquina y el más
                // difícil de acertar de los dos.
                if (handleRect?.contains(px, py) == true) {
                    mode = Mode.RESIZE
                    listener.onResizeStart()
                    huboGesto = true
                } else if (scrollRect?.contains(px, py) == true) {
                    mode = Mode.SCROLL
                    listener.onScrollStart()
                    huboGesto = true
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    if (mode == Mode.DRAG) {
                        listener.onDragEnd()
                        mode = Mode.NONE
                    }
                    multiTouch = true
                    dosDedos = true
                    startSpan = span(event)
                    startMidY = midRawY(event)
                    twoFingerDecided = false
                }
            }

            MotionEvent.ACTION_MOVE -> handleMove(event)

            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) finishGesture()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishGesture()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    comprobarDosDedos(event.eventTime)
                } else {
                    ultimoDosDedos = 0L
                }
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
        if (mode == Mode.SCROLL) {
            listener.onScrollDrag(event.rawY - downY)
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
                    huboGesto = true
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
            huboGesto = true
            listener.onDragStart()
        }
        if (mode == Mode.DRAG) listener.onDrag(dx, dy)
    }

    /**
     * Al soltar: ¿ha sido un toque de dos dedos, y hace poco hubo otro?
     *
     * Los dos topes son lo que separa el gesto de posar la mano encima del pin:
     * un toque tiene que ser corto, y dos toques tienen que ir seguidos.
     */
    private fun comprobarDosDedos(cuando: Long) {
        val fueToque = dosDedos && !huboGesto && (cuando - gestoInicio) <= TOQUE_MAX_MS
        if (!fueToque) {
            // Cualquier otra cosa rompe la pareja: si no, un toque de hace un
            // rato se emparejaría con otro después de haber movido el pin.
            ultimoDosDedos = 0L
            return
        }
        if (ultimoDosDedos != 0L && cuando - ultimoDosDedos <= HUECO_MAX_MS) {
            ultimoDosDedos = 0L
            listener.onTwoFingerDoubleTap()
        } else {
            ultimoDosDedos = cuando
        }
    }

    private fun finishGesture() {
        when (mode) {
            Mode.DRAG -> listener.onDragEnd()
            Mode.SCALE -> listener.onScaleEnd()
            Mode.OPACITY -> listener.onOpacityEnd()
            Mode.RESIZE -> listener.onResizeEnd()
            Mode.SCROLL -> listener.onScrollEnd()
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

    companion object {
        /**
         * Lo que puede durar un toque de dos dedos.
         *
         * Más largo que el toque de un dedo del sistema (100 ms) a propósito:
         * poner dos dedos a la vez y levantarlos cuesta más que dar un golpecito
         * con uno, y con el tope corto el gesto no salía casi nunca.
         */
        const val TOQUE_MAX_MS = 400L

        /** Cuánto puede pasar entre los dos toques para que cuenten como uno doble. */
        const val HUECO_MAX_MS = 500L
    }
}
