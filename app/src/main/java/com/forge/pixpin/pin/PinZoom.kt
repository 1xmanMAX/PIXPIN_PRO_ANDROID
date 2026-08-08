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
     * Cuánto se deja pasar del borde a los pines de imagen. Su ventana tiene
     * tamaño explícito, así que puede ser mayor que la pantalla: acercarse a
     * leer letra pequeña es justo para lo que sirve.
     */
    const val IMAGE_OVERZOOM = 3f

    /**
     * Tope de escala del contenido que ahora mismo mide [realW]×[realH] px con
     * escala [currentScale]. Se deduce su tamaño natural y se mira cuánto cabe.
     *
     * @param overzoom 1 = parar justo al llenar la pantalla; más, dejar crecer
     * por encima (solo tiene sentido si la ventana puede medir más que ella).
     */
    fun maxScaleFor(
        realW: Int,
        realH: Int,
        currentScale: Float,
        screenW: Int,
        screenH: Int,
        overzoom: Float = 1f
    ): Float {
        if (realW <= 0 || realH <= 0 || currentScale <= 0f) return ABSOLUTE_MAX_SCALE
        val naturalW = realW / currentScale
        val naturalH = realH / currentScale
        val fits = minOf(screenW / naturalW, screenH / naturalH)
        return (fits * overzoom).coerceIn(MIN_SCALE, ABSOLUTE_MAX_SCALE)
    }

    /**
     * Escala que toca, ya con sus topes. Está aparte de [step] porque quien
     * dimensiona la ventana necesita saberla ANTES de llamar a step: la posición
     * se calcula con el tamaño al que la ventana va a quedar, no con el que
     * tiene todavía.
     */
    fun scaleFor(scaleAtStart: Float, factor: Float, maxScale: Float): Float =
        (scaleAtStart * factor).coerceIn(MIN_SCALE, maxScale.coerceAtLeast(MIN_SCALE))

    /**
     * Un paso del pellizco: escala nueva y esquina donde recolocar la VENTANA.
     *
     * **El punto que se ancla es del contenido, pero lo que se coloca es la
     * ventana**, y las dos cosas no coinciden: la ventana mide de más para que
     * quepan la sombra y la pegatina, así que el contenido empieza [insetLeft]
     * px a la derecha y [insetTop] px por debajo de su esquina. Sin restar ese
     * hueco, el pin se ancla a un punto desplazado —y el desvío se nota más
     * cuanto más lejos del centro se pellizque, porque ahí el error del hueco se
     * suma al de medir la proporción sobre la ventana en vez de sobre la foto.
     *
     * @param scaleAtStart escala al empezar el gesto
     * @param factor separación actual entre los dedos ÷ separación inicial
     * @param maxScale tope calculado con [maxScaleFor] al empezar el gesto
     * @param realW/realH tamaño al que va a quedar el CONTENIDO, sin el hueco
     * @param focusX/focusY punto medio entre los dedos, en pantalla
     * @param relX/relY posición del foco dentro del CONTENIDO (0..1) al empezar
     * @param insetLeft/insetTop hueco entre la esquina de la ventana y la del
     *   contenido, en px. Cero cuando la ventana **es** el contenido.
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
        relY: Float,
        insetLeft: Int = 0,
        insetTop: Int = 0
    ): ZoomStep {
        val scale = scaleFor(scaleAtStart, factor, maxScale)
        return ZoomStep(
            scale = scale,
            x = (focusX - relX * realW).toInt() - insetLeft,
            y = (focusY - relY * realH).toInt() - insetTop
        )
    }
}
