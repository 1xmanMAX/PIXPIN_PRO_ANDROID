package com.forge.pixpin.motor

/**
 * **La decisión de la tasa de refresco, sin Android delante.**
 *
 * Vive aparte de [PantallaFluida] —que es la fontanería: ventanas, `LayoutParams` y
 * `FrameMetrics`— porque esta mitad es la que hay que poder probar. `MotorSeparadoTest`
 * vigila que este archivo no importe nada de Android, y así seguirá.
 *
 * Los números salen de Telegram (12.10.1, GPL-2), de
 * `TMessagesProj/src/main/java/org/telegram/messenger/utils/RefreshRateController.java`;
 * cada uno se cita con su línea donde se declara. La lógica está reescrita, no copiada.
 */

/** Las dos tasas entre las que se elige. No hay más: o la que da la pantalla, o 60. */
enum class TasaDePantalla { MAXIMA, SESENTA }

/**
 * Los números de la política, juntos y con nombre.
 *
 * Son los de Telegram, que están rodados en millones de teléfonos, y se citan uno a uno
 * en `RefreshRateController.java`. Van en un objeto y no como constantes sueltas para
 * poder apretarlos en una prueba sin esperar segundos de reloj.
 */
data class NumerosDeFluidez(
    /** Cuánto tiene que aguantar la media antes de mover nada. `RefreshRateController.java:48`. */
    val ventanaEstableMs: Long = 1_800,
    /** Y cuánto hay que esperar entre dos cambios. `RefreshRateController.java:51`. */
    val minimoEntreCambiosMs: Long = 3_000,
    /** Se baja a 60 con la media en esto o por debajo. `RefreshRateController.java:58`. */
    val fpsParaBajar: Float = 55f,
    /**
     * Y se sube con la media en esto o por encima. `RefreshRateController.java:59`.
     *
     * **El hueco entre los dos números es la histéresis**: con la media entre 55 y 58,5 no
     * se toca nada. Sin ese hueco, un solo umbral haría que la tasa oscilara sin parar
     * justo en el punto donde la pantalla va regular, que es cuando más se nota.
     */
    val fpsParaSubir: Float = 58.5f,
    /** Fotogramas mínimos antes de opinar. `RefreshRateController.java:162`. */
    val fotogramasDeCalentamiento: Int = 30,
    /** Cuántos fotogramas se recuerdan: unos 2-3 segundos. `RefreshRateController.java:62`. */
    val memoriaDeFotogramas: Int = 240
)

/**
 * **La decisión, sin Android delante.**
 *
 * Se le van dando duraciones de fotograma y el instante en que se pregunta; contesta si
 * toca subir, bajar o quedarse. No sabe qué es una ventana ni cómo se cambia una tasa:
 * eso es cosa de [ElVigilanteDeFotogramas]. Separado así se puede probar entero en la
 * JVM, que es donde de verdad se comprueba que no oscila.
 *
 * ## Qué es «fps» aquí
 *
 * Lo que se mide es `FrameMetrics.TOTAL_DURATION` (`RefreshRateController.java:121`): lo
 * que **cuesta producir** un fotograma, de recoger el toque a entregar el búfer. No es el
 * ritmo al que la pantalla los enseña. Así que «55 fps» significa «cada fotograma cuesta
 * 18 ms de trabajo», y eso no cambia porque la pantalla vaya a 60 o a 120: es la carga de
 * la aplicación, que es justo lo que hay que mirar para decidir si cabe en 8,3 ms.
 *
 * Guarda las duraciones en un anillo de tamaño fijo con la suma al día: cero reservas de
 * memoria por fotograma, que es la norma de la casa —el vigilante mide, no dibuja.
 *
 * Los instantes que se le pasan tienen que ser no negativos y no ir hacia atrás
 * (`SystemClock.uptimeMillis` cumple las dos cosas).
 */
class PoliticaDeFluidez(val numeros: NumerosDeFluidez = NumerosDeFluidez()) {

    private val duraciones = LongArray(numeros.memoriaDeFotogramas)
    private var cuantos = 0
    private var siguiente = 0
    private var sumaNs = 0L

    /** Desde cuándo la media está baja (o alta), o [SIN_MARCA] si la racha se rompió. */
    private var bajoDesdeMs = SIN_MARCA
    private var altoDesdeMs = SIN_MARCA

    /**
     * Cuándo se cambió por última vez.
     *
     * Empieza muy atrás a propósito: el primer cambio no tiene por qué esperar tres
     * segundos a nada, ya ha esperado la ventana estable.
     */
    private var ultimoCambioMs = Long.MIN_VALUE / 2

    /** La tasa que se está pidiendo ahora mismo. */
    var tasa: TasaDePantalla = TasaDePantalla.MAXIMA
        private set

    /** Cuántos fotogramas caben aún en la memoria antes de dar una media. */
    val calentando: Boolean get() = cuantos < numeros.fotogramasDeCalentamiento

    /** La media de fotogramas por segundo del historial, o 0 si no hay nada. */
    val fpsMedios: Float
        get() {
            if (cuantos == 0) return 0f
            val medioNs = sumaNs.toDouble() / cuantos
            if (medioNs <= 0.0) return 0f
            return (1_000_000_000.0 / medioNs).toFloat()
        }

    /** Anota lo que costó un fotograma. Las duraciones absurdas se tiran sin más. */
    fun anota(duracionNs: Long) {
        if (duracionNs <= 0L) return
        if (cuantos < duraciones.size) cuantos++ else sumaNs -= duraciones[siguiente]
        duraciones[siguiente] = duracionNs
        sumaNs += duracionNs
        siguiente++
        if (siguiente == duraciones.size) siguiente = 0
    }

    /**
     * ¿Toca mover la tasa? Devuelve la nueva, o `null` para quedarse como estaba.
     *
     * Solo devuelve algo cuando **de verdad** cambia, así quien llama puede tocar la
     * ventana únicamente en ese caso: en un minuto de dibujo eso son cero o una llamadas,
     * no una por fotograma.
     */
    fun decide(ahoraMs: Long): TasaDePantalla? {
        if (calentando) return null
        val fps = fpsMedios
        val puedeCambiar = ahoraMs - ultimoCambioMs >= numeros.minimoEntreCambiosMs

        if (tasa == TasaDePantalla.MAXIMA) {
            // Vamos a tope: solo se baja si la media se queda floja un buen rato.
            if (fps <= numeros.fpsParaBajar) {
                if (bajoDesdeMs == SIN_MARCA) bajoDesdeMs = ahoraMs
                if (ahoraMs - bajoDesdeMs >= numeros.ventanaEstableMs && puedeCambiar) {
                    return cambiaA(TasaDePantalla.SESENTA, ahoraMs)
                }
            } else {
                // Un respiro rompe la racha: no se acumulan bajones sueltos.
                bajoDesdeMs = SIN_MARCA
            }
            return null
        }

        // Vamos a 60: se vuelve arriba solo si sobra margen, no en cuanto se pase de 55.
        if (fps >= numeros.fpsParaSubir) {
            if (altoDesdeMs == SIN_MARCA) altoDesdeMs = ahoraMs
            if (ahoraMs - altoDesdeMs >= numeros.ventanaEstableMs && puedeCambiar) {
                return cambiaA(TasaDePantalla.MAXIMA, ahoraMs)
            }
        } else {
            altoDesdeMs = SIN_MARCA
        }
        return null
    }

    private fun cambiaA(nueva: TasaDePantalla, ahoraMs: Long): TasaDePantalla {
        tasa = nueva
        ultimoCambioMs = ahoraMs
        bajoDesdeMs = SIN_MARCA
        altoDesdeMs = SIN_MARCA
        return nueva
    }

    /**
     * Borra el historial y las rachas, dejando la tasa donde está.
     *
     * Se llama al dejar de mirar una pantalla: lo que costaba dibujar hace media hora no
     * dice nada de lo que cuesta ahora, y arrastrarlo haría que la primera decisión al
     * volver fuera con datos de otra sesión.
     */
    fun olvida() {
        cuantos = 0
        siguiente = 0
        sumaNs = 0L
        bajoDesdeMs = SIN_MARCA
        altoDesdeMs = SIN_MARCA
    }

    companion object {
        /** «Aquí no hay racha empezada». Los instantes reales nunca son negativos. */
        const val SIN_MARCA = -1L
    }
}
