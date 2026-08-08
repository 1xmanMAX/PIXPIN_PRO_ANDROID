package com.forge.pixpin.annotate

import android.view.MotionEvent
import android.view.View

/** Qué está tocando la pantalla. */
enum class ToolKind { FINGER, STYLUS, OTHER }

/**
 * Rechazo de palma.
 *
 * Al escribir con lápiz, la mano se apoya en la pantalla y Android entrega esos
 * toques como dedos normales: aparecen manchones donde descansa la muñeca. La
 * regla es simple y sin falsos positivos molestos: mientras el lápiz esté en
 * uso —o lo haya estado hace muy poco— los toques de dedo se ignoran. Sin lápiz
 * a la vista, el dedo dibuja como siempre.
 */
class PalmGuard(private val windowMs: Long = 1500L) {

    private var lastStylusMs = Long.MIN_VALUE / 4

    fun noteStylus(nowMs: Long) {
        lastStylusMs = nowMs
    }

    fun accepts(kind: ToolKind, nowMs: Long): Boolean =
        kind != ToolKind.FINGER || nowMs - lastStylusMs > windowMs
}

/**
 * Lee trazos de `MotionEvent` sin perder muestras.
 *
 * Se trabaja con MotionEvent y no con los gestos de Compose por dos razones:
 *
 * 1. `detectDragGestures` no emite nada hasta superar el *touch slop*, así que
 *    el arranque de cada trazo se perdía y los trazos cortos —un punto, una
 *    tilde, la barra de una «t»— no llegaban a existir. Aquí el trazo empieza
 *    en el primer contacto.
 * 2. Un digitalizador de lápiz muestrea a cientos de hercios, pero solo llega un
 *    evento por fotograma; el resto de muestras viajan dentro como *históricos*.
 *    Ignorarlas, como se hacía, tiraba la mayoría de los puntos del trazo.
 *
 * Además es lo único que funciona dentro de un pin: sus ventanas overlay
 * interceptan los toques antes de que lleguen a Compose.
 */
class StrokeTouchReader(
    private val guard: PalmGuard = PalmGuard(),
    private val onBegin: (x: Float, y: Float, pressure: Float) -> Unit,
    private val onExtend: (x: Float, y: Float, pressure: Float) -> Unit,
    private val onFinish: () -> Unit,
    private val onCancel: () -> Unit,
    /**
     * **El segundo dedo, que hace de Shift.**
     *
     * Con el dedo que dibuja apoyado, posar otro en cualquier parte de la
     * pantalla pide *forma perfecta*: el círculo sale redondo, el cuadrado
     * cuadrado y el rombo simétrico. Al levantarlo, se vuelve a mano alzada.
     *
     * En un teclado esto es la tecla Shift, y en una pantalla no hay teclas: lo
     * que hay es otra mano. Es el gesto que ya hace todo el mundo para «sujetar»
     * algo mientras lo ajusta con la otra.
     */
    private val onModifier: (activo: Boolean) -> Unit = {}
) : View.OnTouchListener {

    private var activeId = INVALID_POINTER
    private var activeKind = ToolKind.OTHER

    /** ¿Hay un dedo de más apoyado ahora mismo? */
    private var modificador = false

    override fun onTouch(view: View, event: MotionEvent): Boolean = onTouchEvent(event)

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startFrom(event, 0)

            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                // Si aparece el lápiz mientras dibujaba un dedo, manda el lápiz:
                // ese dedo era la palma apoyándose justo antes de escribir.
                if (kindOf(event, index) == ToolKind.STYLUS && activeKind != ToolKind.STYLUS) {
                    if (activeId != INVALID_POINTER) onCancel()
                    startFrom(event, index)
                } else if (activeId != INVALID_POINTER && activeKind == ToolKind.FINGER) {
                    // Solo cuando se dibuja **con el dedo**. Dibujando con
                    // lápiz, el segundo contacto es la palma apoyándose, y
                    // tomarla por un modificador convertiría todos los trazos
                    // de la mano apoyada en formas perfectas.
                    setModificador(true)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val index = activeIndex(event)
                if (index >= 0) {
                    // Los históricos van primero y en orden: son las muestras
                    // intermedias que ocurrieron desde el fotograma anterior.
                    for (h in 0 until event.historySize) {
                        onExtend(
                            event.getHistoricalX(index, h),
                            event.getHistoricalY(index, h),
                            pressureOf(event.getHistoricalPressure(index, h))
                        )
                    }
                    onExtend(
                        event.getX(index),
                        event.getY(index),
                        pressureOf(event.getPressure(index))
                    )
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activeId) {
                    finish()
                } else if (event.pointerCount <= 2) {
                    // El que se va todavía se cuenta aquí: si quedan dos o
                    // menos, al soltarlo solo queda el que dibuja.
                    setModificador(false)
                }
            }

            MotionEvent.ACTION_UP -> finish()

            MotionEvent.ACTION_CANCEL -> {
                if (activeId != INVALID_POINTER) onCancel()
                activeId = INVALID_POINTER
                setModificador(false)
            }
        }
        return true
    }

    private fun startFrom(event: MotionEvent, index: Int) {
        val kind = kindOf(event, index)
        if (!guard.accepts(kind, event.eventTime)) {
            activeId = INVALID_POINTER
            return
        }
        if (kind == ToolKind.STYLUS) guard.noteStylus(event.eventTime)
        activeId = event.getPointerId(index)
        activeKind = kind
        onBegin(event.getX(index), event.getY(index), pressureOf(event.getPressure(index)))
    }

    private fun finish() {
        if (activeId == INVALID_POINTER) return
        activeId = INVALID_POINTER
        // Primero se cierra el trazo y después se suelta el modificador: al
        // revés, la forma se recalcularía sin él justo antes de darla por buena
        // y el círculo perfecto se quedaría ovalado al levantar los dedos.
        onFinish()
        setModificador(false)
    }

    /** Avisa solo cuando cambia: esto se llama en cada evento de un gesto. */
    private fun setModificador(activo: Boolean) {
        if (modificador == activo) return
        modificador = activo
        onModifier(activo)
    }

    private fun activeIndex(event: MotionEvent): Int =
        if (activeId == INVALID_POINTER) -1 else event.findPointerIndex(activeId)

    /** La presión solo es información real viniendo de un lápiz. */
    private fun pressureOf(raw: Float): Float =
        if (activeKind == ToolKind.STYLUS) raw.coerceIn(0f, 1f) else 1f

    private fun kindOf(event: MotionEvent, index: Int): ToolKind =
        when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> ToolKind.STYLUS
            MotionEvent.TOOL_TYPE_FINGER -> ToolKind.FINGER
            else -> ToolKind.OTHER
        }

    private companion object {
        const val INVALID_POINTER = -1
    }
}
