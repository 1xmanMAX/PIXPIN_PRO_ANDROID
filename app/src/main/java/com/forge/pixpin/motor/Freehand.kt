package com.forge.pixpin.motor

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Port de `perfect-freehand`: el trazo del lápiz.
 *
 * **Un trazo a mano no es una línea gruesa.** Es una mancha: se ensancha donde
 * la mano va despacio y se afila donde acelera, y sus extremos son casquetes
 * redondos, no cortes. Por eso esta librería no devuelve un camino que recorrer
 * con un pincel, sino **el contorno cerrado de la mancha**, que luego se
 * rellena. Antes aquí se pintaban segmentos rectos variando el grosor de la
 * brocha, y por muy afinado que estuviera nunca iba a parecerse: los extremos
 * salían romos, los giros cerrados hacían codo y el ancho saltaba de un segmento
 * al siguiente en vez de fluir.
 *
 * Es lo que usa Excalidraw y por eso está aquí, con sus constantes y su orden.
 * El algoritmo tiene dos pasadas:
 *
 * 1. [getStrokePoints] alisa la entrada. No promedia: **interpola** cada punto
 *    nuevo hacia el anterior (`streamline`), que es lo que quita el temblor del
 *    dedo sin retrasar el trazo.
 * 2. [getStrokeOutlinePoints] recorre esos puntos proyectando a izquierda y
 *    derecha a una distancia que depende de la presión —real o simulada a
 *    partir de la velocidad— y cierra el contorno con los casquetes.
 *
 * Sin Android: se puede comprobar sin dispositivo, igual que [Shapes].
 */

// -------------------------------------------------------------------------
// Constantes del original (`constants.ts`). No se tocan.
// -------------------------------------------------------------------------

/** Cuánto cambia la presión simulada con la velocidad. */
private const val RATE_OF_PRESSURE_CHANGE = 0.275

/**
 * π con un pelo de más.
 *
 * En el original es para esquivar artefactos de redondeo de algunos
 * navegadores. Se conserva porque el valor **entra en las cuentas** de los
 * casquetes: cambiarlo movería los puntos.
 */
private const val FIXED_PI = Math.PI + 0.0001

private const val START_CAP_SEGMENTS = 13
private const val END_CAP_SEGMENTS = 29
private const val CORNER_CAP_SEGMENTS = 13

/** Píxeles del final del trazo que se descartan por ruido. */
private const val END_NOISE_THRESHOLD = 3.0

private const val MIN_STREAMLINE_T = 0.15
private const val STREAMLINE_T_RANGE = 0.85

/** Radio mínimo: por debajo el trazo desaparecería. */
private const val MIN_RADIUS = 0.01

/**
 * Presión del primer punto cuando no viene dada. Más baja que la de los demás
 * a propósito: un trazo casi siempre empieza despacio, y sin esto arranca gordo.
 */
private const val DEFAULT_FIRST_PRESSURE = 0.25

private const val DEFAULT_PRESSURE = 0.5

// -------------------------------------------------------------------------
// Constantes de Excalidraw (`shape.ts`)
// -------------------------------------------------------------------------

/**
 * Ajustes del lápiz de Excalidraw. Están medidos a ojo contra el resultado, no
 * deducidos: son números mágicos respaldados por cómo queda.
 */
object FreedrawTuning {
    /** Tamaño del trazo respecto al grosor elegido. */
    const val SIZE_FACTOR = 4.25
    const val THINNING = 0.6
    const val SMOOTHING = 0.5

    /**
     * Cuánto se corrige el pulso.
     *
     * **Es el mando que decide si el trazo es tuyo o del programa.** El
     * alisado interpola cada punto nuevo hacia el anterior: cuanto más alto
     * este número, más se queda el trazo con la trayectoria «limpia» y menos
     * con la que hizo tu mano. A 0,5 —lo que trae el original por defecto— una
     * letra sale enderezada y deja de parecer tu escritura.
     *
     * Se usa 0,2, que es el valor que el propio Excalidraw llama **preciso**
     * (`DEFAULT_STROKE_STREAMLINE_PRECISE`) y reserva para cuando se dibuja con
     * intención de detalle. No se baja a cero porque a cero entra **todo**,
     * incluido el temblor del dedo y el ruido del digitalizador, y el trazo sale
     * dentado: eso no es menos corrección, es otro defecto.
     *
     * Si aun así se quiere más crudo, este es el único número que hay que tocar.
     */
    const val STREAMLINE = 0.2
}

/** Opciones resueltas del trazo. */
data class StrokeOptions(
    val size: Double = 16.0,
    val thinning: Double = 0.5,
    val smoothing: Double = 0.5,
    val streamline: Double = 0.5,
    val simulatePressure: Boolean = true,
    /** `easeOutSine`, que es la que pasa Excalidraw. */
    val easing: (Double) -> Double = { sin(it * Math.PI / 2) },
    /** El trazo está terminado: el último punto se respeta tal cual. */
    val last: Boolean = false,
    val capStart: Boolean = true,
    val capEnd: Boolean = true
)

/** Las opciones con las que Excalidraw dibuja un elemento de lápiz. */
fun strokeOptionsFor(e: Element): StrokeOptions = StrokeOptions(
    size = e.strokeWidth * FreedrawTuning.SIZE_FACTOR,
    thinning = FreedrawTuning.THINNING,
    smoothing = FreedrawTuning.SMOOTHING,
    streamline = FreedrawTuning.STREAMLINE,
    simulatePressure = e.simulatePressure,
    last = true
)

/** Un punto ya procesado, con su vector, distancia y recorrido acumulado. */
data class StrokePoint(
    val point: Pt,
    val pressure: Double,
    val vector: Pt,
    val distance: Double,
    val runningLength: Double
)

/** El contorno cerrado de la mancha que dejan [points]. */
fun getStroke(
    points: List<Pt>, pressures: List<Double>?, o: StrokeOptions
): List<Pt> = getStrokeOutlinePoints(getStrokePoints(points, pressures, o), o)

// -------------------------------------------------------------------------
// Pasada 1: alisado
// -------------------------------------------------------------------------

/**
 * Alisa la entrada y calcula vector, distancia y recorrido de cada punto.
 *
 * El alisado es una interpolación hacia el punto anterior, no una media móvil:
 * la media retrasaría el trazo respecto al dedo, que es lo que se nota como
 * «va detrás».
 */
fun getStrokePoints(
    points: List<Pt>, pressures: List<Double>?, o: StrokeOptions
): List<StrokePoint> {
    if (points.isEmpty()) return emptyList()

    val t = MIN_STREAMLINE_T + (1 - o.streamline) * STREAMLINE_T_RANGE

    // Cada entrada es punto + presión, que puede faltar.
    var pts: List<Pair<Pt, Double?>> =
        points.mapIndexed { i, p -> p to pressures?.getOrNull(i) }

    // Con solo dos puntos se meten intermedios: si no, un trazo corto sale a
    // rayas cuando los extremos se afilan.
    if (pts.size == 2) {
        val primero = pts[0]
        val ultimo = pts[1].first
        // Los intermedios pierden la presión, igual que en el original: `lrp`
        // interpola vectores de dos componentes y la tercera se queda fuera.
        pts = listOf(primero) + (1..4).map { i ->
            lerp(primero.first, ultimo, i / 4.0) to null
        }
    }

    // Con uno solo se inventa otro a un punto de distancia.
    if (pts.size == 1) {
        pts = pts + (Pt(pts[0].first.x + 1, pts[0].first.y + 1) to pts[0].second)
    }

    val out = mutableListOf(
        StrokePoint(
            point = pts[0].first,
            pressure = pts[0].second.takeIf { it != null && it >= 0 } ?: DEFAULT_FIRST_PRESSURE,
            vector = Pt(1.0, 1.0),
            distance = 0.0,
            runningLength = 0.0
        )
    )

    var alcanzoMinimo = false
    var recorrido = 0.0
    var prev = out[0]
    val max = pts.size - 1

    for (i in 1 until pts.size) {
        val punto = if (o.last && i == max) {
            pts[i].first
        } else {
            lerp(prev.point, pts[i].first, t)
        }

        if (punto.x == prev.point.x && punto.y == prev.point.y) continue

        val distancia = hypot(punto.x - prev.point.x, punto.y - prev.point.y)
        recorrido += distancia

        // Al principio se espera a alejarse del origen: los primeros píxeles de
        // un trazo son temblor del apoyo, no intención.
        if (i < max && !alcanzoMinimo) {
            if (recorrido < o.size) continue
            alcanzoMinimo = true
        }

        prev = StrokePoint(
            point = punto,
            pressure = pts[i].second.takeIf { it != null && it >= 0 } ?: DEFAULT_PRESSURE,
            // El vector apunta hacia ATRÁS, del punto actual al anterior.
            vector = unit(Pt(prev.point.x - punto.x, prev.point.y - punto.y)),
            distance = distancia,
            runningLength = recorrido
        )
        out += prev
    }

    // El primero hereda el vector del segundo: solo no tiene dirección.
    out[0] = out[0].copy(vector = out.getOrNull(1)?.vector ?: Pt(0.0, 0.0))
    return out
}

// -------------------------------------------------------------------------
// Pasada 2: el contorno
// -------------------------------------------------------------------------

/** El contorno cerrado, en orden: izquierda, casquete final, derecha, inicial. */
fun getStrokeOutlinePoints(points: List<StrokePoint>, o: StrokeOptions): List<Pt> {
    if (points.isEmpty() || o.size <= 0) return emptyList()

    val total = points.last().runningLength

    // Distancia mínima entre puntos guardados, al cuadrado: por debajo de esto
    // el punto no aporta forma y solo engorda el camino.
    val minDistancia = (o.size * o.smoothing).pow(2)

    val izquierda = mutableListOf<Pt>()
    val derecha = mutableListOf<Pt>()

    var presionPrevia = presionInicial(points, o)
    var radio = strokeRadius(o.size, o.thinning, points.last().pressure, o.easing)
    var primerRadio: Double? = null

    var vectorPrevio = points[0].vector
    var izquierdaPrevia = points[0].point
    var derechaPrevia = izquierdaPrevia
    var izquierdaTmp = izquierdaPrevia
    var derechaTmp = derechaPrevia
    var previoFueEsquina = false

    for (i in points.indices) {
        val sp = points[i]
        var presion = sp.pressure
        val esUltimo = i == points.size - 1

        // El final del trazo lleva el ruido de levantar el dedo.
        if (!esUltimo && total - sp.runningLength < END_NOISE_THRESHOLD) continue

        if (o.thinning != 0.0) {
            if (o.simulatePressure) {
                presion = simulatePressure(presionPrevia, sp.distance, o.size)
            }
            radio = strokeRadius(o.size, o.thinning, presion, o.easing)
        } else {
            radio = o.size / 2
        }
        if (primerRadio == null) primerRadio = radio
        radio = max(MIN_RADIUS, radio)

        val siguienteVector = (if (!esUltimo) points[i + 1] else sp).vector
        val dprSiguiente = if (!esUltimo) dot(sp.vector, siguienteVector) else 1.0
        val dprPrevio = dot(sp.vector, vectorPrevio)

        val esEsquina = dprPrevio < 0 && !previoFueEsquina
        val siguienteEsEsquina = dprSiguiente < 0

        if (esEsquina || siguienteEsEsquina) {
            // Giro de más de 90°: en vez de proyectar a los lados —que cruzaría
            // el trazo consigo mismo— se dibuja un casquete redondo entero.
            val off = mul(perp(vectorPrevio), radio)
            val paso = 1.0 / CORNER_CAP_SEGMENTS
            var t = 0.0
            while (t <= 1.0) {
                izquierdaTmp = rotAround(
                    Pt(sp.point.x - off.x, sp.point.y - off.y), sp.point, FIXED_PI * t
                )
                izquierda += izquierdaTmp
                derechaTmp = rotAround(
                    Pt(sp.point.x + off.x, sp.point.y + off.y), sp.point, FIXED_PI * -t
                )
                derecha += derechaTmp
                t += paso
            }
            izquierdaPrevia = izquierdaTmp
            derechaPrevia = derechaTmp
            if (siguienteEsEsquina) previoFueEsquina = true
            continue
        }
        previoFueEsquina = false

        if (esUltimo) {
            val off = mul(perp(sp.vector), radio)
            izquierda += Pt(sp.point.x - off.x, sp.point.y - off.y)
            derecha += Pt(sp.point.x + off.x, sp.point.y + off.y)
            continue
        }

        // La dirección de proyección se interpola entre el vector de este punto
        // y el del siguiente: así el ancho gira suave en las curvas.
        val off = mul(perp(lerp(siguienteVector, sp.vector, dprSiguiente)), radio)

        izquierdaTmp = Pt(sp.point.x - off.x, sp.point.y - off.y)
        if (i <= 1 || dist2(izquierdaPrevia, izquierdaTmp) > minDistancia) {
            izquierda += izquierdaTmp
            izquierdaPrevia = izquierdaTmp
        }

        derechaTmp = Pt(sp.point.x + off.x, sp.point.y + off.y)
        if (i <= 1 || dist2(derechaPrevia, derechaTmp) > minDistancia) {
            derecha += derechaTmp
            derechaPrevia = derechaTmp
        }

        presionPrevia = presion
        vectorPrevio = sp.vector
    }

    val primerPunto = points[0].point
    val ultimoPunto = if (points.size > 1) points.last().point
    else Pt(points[0].point.x + 1, points[0].point.y + 1)

    // Un toque seco es un punto: un círculo y ya.
    if (points.size == 1) return dibujarPunto(primerPunto, primerRadio ?: radio)

    val casqueteInicial = mutableListOf<Pt>()
    val casqueteFinal = mutableListOf<Pt>()

    if (derecha.isNotEmpty()) {
        casqueteInicial += if (o.capStart) {
            roundStartCap(primerPunto, derecha[0])
        } else {
            flatStartCap(primerPunto, izquierda.firstOrNull() ?: primerPunto, derecha[0])
        }
    }

    val direccion = perp(Pt(-points.last().vector.x, -points.last().vector.y))
    casqueteFinal += if (o.capEnd) {
        roundEndCap(ultimoPunto, direccion, radio)
    } else {
        flatEndCap(ultimoPunto, direccion, radio)
    }

    return izquierda + casqueteFinal + derecha.reversed() + casqueteInicial
}

/**
 * Presión de partida: la media de los primeros puntos.
 *
 * Tomar la del primero a secas daría un arranque gordo, porque un trazo empieza
 * despacio y la presión simulada crece con la lentitud.
 */
private fun presionInicial(points: List<StrokePoint>, o: StrokeOptions): Double {
    var acc = points[0].pressure
    for (sp in points.take(10)) {
        val p = if (o.simulatePressure) simulatePressure(acc, sp.distance, o.size) else sp.pressure
        acc = (acc + p) / 2
    }
    return acc
}

/** Presión deducida de la velocidad: despacio aprieta, deprisa afila. */
private fun simulatePressure(previa: Double, distancia: Double, size: Double): Double {
    val sp = min(1.0, distancia / size)
    val rp = min(1.0, 1 - sp)
    return min(1.0, previa + (rp - previa) * (sp * RATE_OF_PRESSURE_CHANGE))
}

private fun strokeRadius(
    size: Double, thinning: Double, pressure: Double, easing: (Double) -> Double
): Double = size * easing(0.5 - thinning * (0.5 - pressure))

// ---- Casquetes ----

/** Un círculo, para el trazo que es solo un toque. */
private fun dibujarPunto(centro: Pt, radio: Double): List<Pt> {
    // El original arranca desde la perpendicular a (−1, −1), que es el vector
    // que separa el centro de su punto auxiliar.
    val inicio = prj(centro, unit(perp(Pt(-1.0, -1.0))), -radio)
    val out = mutableListOf<Pt>()
    val paso = 1.0 / START_CAP_SEGMENTS
    var t = paso
    while (t <= 1.0) {
        out += rotAround(inicio, centro, FIXED_PI * 2 * t)
        t += paso
    }
    return out
}

private fun roundStartCap(centro: Pt, derecha: Pt): List<Pt> {
    val out = mutableListOf<Pt>()
    val paso = 1.0 / START_CAP_SEGMENTS
    var t = paso
    while (t <= 1.0) {
        out += rotAround(derecha, centro, FIXED_PI * t)
        t += paso
    }
    return out
}

private fun flatStartCap(centro: Pt, izq: Pt, der: Pt): List<Pt> {
    val v = Pt(izq.x - der.x, izq.y - der.y)
    val a = mul(v, 0.5)
    val b = mul(v, 0.51)
    return listOf(
        Pt(centro.x - a.x, centro.y - a.y),
        Pt(centro.x - b.x, centro.y - b.y),
        Pt(centro.x + b.x, centro.y + b.y),
        Pt(centro.x + a.x, centro.y + a.y)
    )
}

/** Vuelta y media, que es lo que hace falta para cerrar un final en pico. */
private fun roundEndCap(centro: Pt, direccion: Pt, radio: Double): List<Pt> {
    val out = mutableListOf<Pt>()
    val inicio = prj(centro, direccion, radio)
    val paso = 1.0 / END_CAP_SEGMENTS
    var t = paso
    while (t < 1.0) {
        out += rotAround(inicio, centro, FIXED_PI * 3 * t)
        t += paso
    }
    return out
}

private fun flatEndCap(centro: Pt, direccion: Pt, radio: Double): List<Pt> = listOf(
    prj(centro, direccion, radio),
    prj(centro, direccion, radio * 0.99),
    prj(centro, direccion, -radio * 0.99),
    prj(centro, direccion, -radio)
)

// ---- Vectores (`vec.ts`) ----

private fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private fun unit(v: Pt): Pt {
    val l = hypot(v.x, v.y)
    return if (l == 0.0) Pt(0.0, 0.0) else Pt(v.x / l, v.y / l)
}

/** Perpendicular, con el giro del original: (x, y) → (y, −x). */
private fun perp(v: Pt) = Pt(v.y, -v.x)

private fun mul(v: Pt, k: Double) = Pt(v.x * k, v.y * k)

private fun dot(a: Pt, b: Pt) = a.x * b.x + a.y * b.y

private fun dist2(a: Pt, b: Pt): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
}

/** Avanza desde [a] en la dirección [v] una distancia [k]. */
private fun prj(a: Pt, v: Pt, k: Double) = Pt(a.x + v.x * k, a.y + v.y * k)

private fun rotAround(a: Pt, centro: Pt, radianes: Double): Pt {
    val s = sin(radianes)
    val c = cos(radianes)
    val px = a.x - centro.x
    val py = a.y - centro.y
    return Pt(px * c - py * s + centro.x, px * s + py * c + centro.y)
}
