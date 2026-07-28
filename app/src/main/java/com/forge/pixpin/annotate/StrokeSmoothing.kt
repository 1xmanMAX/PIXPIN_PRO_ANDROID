package com.forge.pixpin.annotate

/**
 * Destino de una trayectoria. Existe para que el suavizado sea matemática pura
 * (testable en la JVM) y a la vez sirva tanto al `Path` de Compose (dibujado en
 * vivo) como al de `android.graphics` (horneado al exportar): lo que se ve y lo
 * que se guarda salen del mismo cálculo.
 */
interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun quadTo(controlX: Float, controlY: Float, x: Float, y: Float)
}

/**
 * Suavizado del trazo a mano alzada por puntos medios.
 *
 * Unir las muestras con rectas deja esquinas visibles en cada cambio de
 * dirección. Aquí cada muestra intermedia pasa a ser el punto de control de una
 * curva cuadrática que va del punto medio de un tramo al punto medio del
 * siguiente: la curva resultante es continua, se calcula sobre la marcha (sin
 * latencia ni pasadas posteriores) y no se desvía de lo que dibujó el dedo.
 */
object StrokeSmoothing {

    /** Factor de grosor con presión mínima y máxima del lápiz. */
    const val MIN_PRESSURE_FACTOR = 0.35f
    const val MAX_PRESSURE_FACTOR = 1.6f

    /** Diferencia de presión a partir de la cual merece la pena variar el grosor. */
    private const val PRESSURE_EPSILON = 0.08f

    /**
     * Vuelca la trayectoria suavizada en [sink].
     *
     * Válido para cualquier número de puntos: uno solo produce un `moveTo`
     * (el lienzo lo dibuja como punto redondo) y dos, una recta.
     */
    inline fun feed(
        count: Int,
        xAt: (Int) -> Float,
        yAt: (Int) -> Float,
        sink: PathSink
    ) {
        if (count <= 0) return
        sink.moveTo(xAt(0), yAt(0))
        for (i in 1 until count - 1) {
            sink.quadTo(
                xAt(i), yAt(i),
                (xAt(i) + xAt(i + 1)) / 2f,
                (yAt(i) + yAt(i + 1)) / 2f
            )
        }
        if (count >= 2) sink.lineTo(xAt(count - 1), yAt(count - 1))
    }

    fun feed(points: List<Pt>, sink: PathSink) =
        feed(points.size, { points[it].x }, { points[it].y }, sink)

    /** Grosor de un tramo según la presión del lápiz (1 = grosor nominal). */
    fun widthFor(baseWidth: Float, pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        return baseWidth * (MIN_PRESSURE_FACTOR + (MAX_PRESSURE_FACTOR - MIN_PRESSURE_FACTOR) * p)
    }

    /**
     * ¿Vale la pena dibujar el trazo tramo a tramo con grosor variable? Solo si
     * la presión cambió de verdad; con el dedo llega siempre constante y un
     * `Path` de un solo grosor es mucho más barato de dibujar.
     */
    fun hasVariablePressure(count: Int, pressureAt: (Int) -> Float): Boolean {
        if (count < 2) return false
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until count) {
            val p = pressureAt(i)
            if (p < min) min = p
            if (p > max) max = p
        }
        return max - min > PRESSURE_EPSILON
    }

    fun hasVariablePressure(points: List<Pt>): Boolean =
        hasVariablePressure(points.size) { points[it].p }
}
