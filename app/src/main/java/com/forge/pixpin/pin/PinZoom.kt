package com.forge.pixpin.pin

/** Escala y posición que deja un paso del pellizco. */
data class ZoomStep(
    val scale: Float,
    val x: Int,
    val y: Int
)

/**
 * Matemática del pellizco de un pin.
 *
 * El punto entre los dedos tiene que quedarse clavado sobre el mismo punto del
 * contenido, así que la ventana se recoloca a la vez que crece. Se calcula con
 * el tamaño REAL medido y no con el teórico: durante un pellizco rápido el
 * dibujado va un fotograma por detrás, y con el tamaño teórico el pin se
 * escapaba en diagonal hacia la esquina superior izquierda.
 *
 * **El tope.** Antes había un límite fijo de 5× más una detección de atasco: se
 * miraba si la ventana había dejado de crecer y se congelaba la escala ahí. Eso
 * paraba el pin en un tamaño que dependía de lo rápido que fuese el gesto —el
 * «hasta aquí y no más» arbitrario que se notaba al usarlo—. Ahora el tope se
 * calcula de una vez a partir del tamaño natural del contenido y de la
 * pantalla: el pin crece hasta que **un eje toca el borde** y ahí para, con lo
 * que siempre se ve entero.
 */
object PinZoom {

    const val MIN_SCALE = 0.25f

    /** Salvaguarda para contenidos diminutos (un icono no debe llenar la pantalla 200 veces). */
    const val ABSOLUTE_MAX_SCALE = 24f

    /**
     * Tope de escala del contenido que ahora mismo mide [realW]×[realH] px con
     * escala [currentScale]. Se deduce su tamaño natural y se mira cuánto cabe.
     */
    fun maxScaleFor(
        realW: Int,
        realH: Int,
        currentScale: Float,
        screenW: Int,
        screenH: Int
    ): Float {
        if (realW <= 0 || realH <= 0 || currentScale <= 0f) return ABSOLUTE_MAX_SCALE
        val naturalW = realW / currentScale
        val naturalH = realH / currentScale
        val fits = minOf(screenW / naturalW, screenH / naturalH)
        return fits.coerceIn(MIN_SCALE, ABSOLUTE_MAX_SCALE)
    }

    /**
     * @param scaleAtStart escala al empezar el gesto
     * @param factor separación actual entre los dedos ÷ separación inicial
     * @param maxScale tope calculado con [maxScaleFor] al empezar el gesto
     * @param realW/realH tamaño que la ventana tiene de verdad
     * @param focusX/focusY punto medio entre los dedos, en pantalla
     * @param relX/relY posición del foco dentro del pin (0..1) al empezar
     */
    fun step(
        scaleAtStart: Float,
        factor: Float,
        maxScale: Float,
        realW: Int,
        realH: Int,
        focusX: Float,
        focusY: Float,
        relX: Float,
        relY: Float
    ): ZoomStep {
        val scale = (scaleAtStart * factor).coerceIn(MIN_SCALE, maxScale.coerceAtLeast(MIN_SCALE))
        return ZoomStep(
            scale = scale,
            x = (focusX - relX * realW).toInt(),
            y = (focusY - relY * realH).toInt()
        )
    }
}
