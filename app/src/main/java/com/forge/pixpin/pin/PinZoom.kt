package com.forge.pixpin.pin

/**
 * Memoria del pellizco en curso: hace falta para saber si el pin ha dejado de
 * crecer de verdad o si simplemente el dibujado va un fotograma por detrás.
 */
data class ZoomState(
    val maxScale: Float = PinZoom.MAX_SCALE,
    val requestedW: Int = 0,
    val lastRealW: Int = 0,
    val stall: Int = 0
)

/** Escala y posición que deja un paso del pellizco. */
data class ZoomStep(
    val scale: Float,
    val x: Int,
    val y: Int,
    val state: ZoomState
)

/**
 * Matemática del pellizco de un pin.
 *
 * El punto entre los dedos tiene que quedarse clavado sobre el mismo punto del
 * contenido, así que la ventana se recoloca a la vez que crece. La trampa está
 * en con qué tamaño se calcula: una ventana WRAP_CONTENT no puede medir más que
 * la pantalla, así que a partir de cierto punto el contenido deja de crecer
 * aunque la escala suba. Calculando con el tamaño *teórico*, el pin se escapaba
 * en diagonal hacia la esquina superior izquierda; con el tamaño REAL se queda
 * quieto en cuanto topa.
 *
 * Además se detecta ese tope para congelar la escala — si no, seguiría subiendo
 * sin efecto visible y luego habría que "gastarla" para volver a encoger. El
 * tope solo se da por bueno tras varios fotogramas seguidos sin crecer, porque
 * durante un pellizco rápido el tamaño real siempre va algo por detrás.
 */
object PinZoom {

    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 5f

    /** Fotogramas seguidos sin crecer que confirman que se ha topado. */
    private const val STALL_FRAMES = 3

    /**
     * @param scaleAtStart escala al empezar el gesto
     * @param factor separación actual entre los dedos ÷ separación inicial
     * @param currentScale escala aplicada ahora mismo
     * @param realW/realH tamaño que la ventana tiene de verdad
     * @param focusX/focusY punto medio entre los dedos, en pantalla
     * @param relX/relY posición del foco dentro del pin (0..1) al empezar
     */
    fun step(
        scaleAtStart: Float,
        factor: Float,
        currentScale: Float,
        realW: Int,
        realH: Int,
        focusX: Float,
        focusY: Float,
        relX: Float,
        relY: Float,
        state: ZoomState
    ): ZoomStep {
        // Pedimos más ancho del que la ventana da y encima no ha crecido nada
        // desde el fotograma anterior: sospecha de tope.
        val quieto = state.requestedW > realW + 4 && realW <= state.lastRealW
        val stall = if (quieto) state.stall + 1 else 0
        val maxScale = if (stall >= STALL_FRAMES) {
            minOf(state.maxScale, currentScale)
        } else {
            state.maxScale
        }

        val scale = (scaleAtStart * factor).coerceIn(MIN_SCALE, maxScale)
        return ZoomStep(
            scale = scale,
            x = (focusX - relX * realW).toInt(),
            y = (focusY - relY * realH).toInt(),
            state = ZoomState(
                maxScale = maxScale,
                requestedW = (relW(realW, scale, currentScale)),
                lastRealW = realW,
                stall = stall
            )
        )
    }

    /** Ancho que cabe esperar en el próximo fotograma con la nueva escala. */
    private fun relW(realW: Int, newScale: Float, currentScale: Float): Int =
        if (currentScale <= 0f) realW else (realW * (newScale / currentScale)).toInt()
}
