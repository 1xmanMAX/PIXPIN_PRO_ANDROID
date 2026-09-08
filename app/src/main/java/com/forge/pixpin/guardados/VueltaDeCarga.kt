package com.forge.pixpin.guardados

/**
 * **La vuelta que da el trazo de carga alrededor del botón de «pásamelo a texto».**
 *
 * Mientras el reconocedor trabaja, Telegram no pone una ruedecita al lado: recorre el
 * **borde del propio botón** con un trazo de 1,5 dp que crece, se estira y se encoge. Se
 * ve dónde has pulsado y se ve que está trabajando en el mismo sitio, sin robarle espacio
 * a la burbuja. Leído en `TranscribeButton.java:449-461` (`getSegments`) y `:355-372`
 * (cómo se reparte por el contorno), Telegram 12.9.2, GPL-2. Aquí está **reescrito**.
 *
 * ## Qué es esta cuenta, en realidad
 *
 * Es la animación indeterminada de Material de toda la vida —1.520° de giro en 5.400 ms,
 * en cuatro tramos que aceleran y frenan con `FastOutSlowIn`— pero **repartida por el
 * contorno de un rectángulo redondeado** en vez de por una circunferencia. De ahí salen
 * dos ángulos, principio y fin del trazo, que quien pinta convierte en distancia sobre el
 * perímetro. Que los dos avancen a distinto ritmo es lo que hace que el trazo se estire y
 * se encoja en vez de ser un guion que da vueltas.
 *
 * Sin Android a propósito: aquí solo hay números, y es justo donde se cuela un error que
 * en pantalla se vería como un parpadeo cada cinco segundos y medio.
 */
object VueltaDeCarga {

    /** Lo que dura la vuelta entera, en milisegundos. `TranscribeButton.java:453`. */
    const val CICLO_MS = 5_400L

    /** Los grados que avanza el conjunto en un ciclo. `TranscribeButton.java:454-455`. */
    const val GIRO_POR_CICLO = 1_520f

    /** Cuánto mide el trazo al empezar, antes de que ningún tramo lo estire. */
    const val TRAZO_INICIAL = 20f

    /** Cuánto estira cada uno de los cuatro tramos. `TranscribeButton.java:457-458`. */
    const val ESTIRON = 250f

    /** Cada cuánto arranca un tramo. */
    const val ENTRE_TRAMOS_MS = 1_350f

    /** Lo que tarda un tramo en estirar del todo. */
    const val TRAMO_MS = 667f

    /** Y cuánto va retrasada la cola respecto a la cabeza: medio tramo. */
    const val RETRASO_DE_LA_COLA_MS = 667f

    /** Nunca menos que esto, o el trazo desaparecería en el cambio de ciclo. */
    const val TRAZO_MINIMO = 40f

    /**
     * Los grados donde empieza y acaba el trazo en el instante [ms].
     *
     * Devuelve `principio a fin` **sin normalizar a 0-360 y con `fin >= principio`**: quien
     * pinta necesita saber si la cola ha dado más vueltas que la cabeza para partir el
     * trazo en dos cuando cruza el punto de arranque. Normalizarlo aquí perdería justo eso.
     */
    fun trazo(ms: Long): Pair<Float, Float> {
        val t = (ms % CICLO_MS).toFloat()
        val base = GIRO_POR_CICLO * t / CICLO_MS
        var fin = base
        var principio = base - TRAZO_INICIAL
        for (i in 0 until 4) {
            fin += suave((t - i * ENTRE_TRAMOS_MS) / TRAMO_MS) * ESTIRON
            principio += suave((t - (RETRASO_DE_LA_COLA_MS + i * ENTRE_TRAMOS_MS)) / TRAMO_MS) * ESTIRON
        }
        // El mínimo evita que la cabeza alcance a la cola y el trazo se esfume un fotograma.
        val largo = maxOf(TRAZO_MINIMO, fin - principio)
        return principio to (principio + largo)
    }

    /**
     * La curva `FastOutSlowIn` de Android, que es la bézier cúbica (0,4 · 0 · 0,2 · 1).
     *
     * Android la resuelve con una tabla de 201 valores; aquí se resuelve la ecuación, que
     * sale más exacta y no guarda nada. Fuera del tramo 0-1 se recorta, porque a esta
     * función le llegan tiempos negativos —tramos que aún no han empezado— y mayores que
     * uno —tramos ya terminados—, y las dos respuestas son las correctas: 0 y 1.
     */
    fun suave(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // Newton sobre la componente horizontal: cuatro pasos bastan de sobra para una
        // bézier tan mansa, y no hay que buscar a ciegas.
        var t = x
        repeat(4) {
            val error = bezier(t, P1X, P2X) - x
            val pendiente = derivada(t, P1X, P2X)
            if (pendiente > 1e-6f) t -= error / pendiente
            t = t.coerceIn(0f, 1f)
        }
        return bezier(t, P1Y, P2Y).coerceIn(0f, 1f)
    }

    private const val P1X = 0.4f
    private const val P1Y = 0f
    private const val P2X = 0.2f
    private const val P2Y = 1f

    /** Bézier cúbica con los extremos clavados en 0 y 1. */
    private fun bezier(t: Float, p1: Float, p2: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
    }

    private fun derivada(t: Float, p1: Float, p2: Float): Float {
        val u = 1f - t
        return 3f * u * u * p1 + 6f * u * t * (p2 - p1) + 3f * t * t * (1f - p2)
    }
}
