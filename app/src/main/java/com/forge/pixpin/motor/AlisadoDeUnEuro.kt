package com.forge.pixpin.motor

import kotlin.math.PI
import kotlin.math.abs

/**
 * El alisado del puntero: el filtro 1€ de Casiez, Roussel y Vogel (CHI 2012),
 * calcado del `OneEuroFilter.java` canónico del propio Casiez.
 *
 * ## Por qué un 1€ y no una media móvil
 *
 * Todo alisado es un trato: quitar temblor cuesta retardo. Un paso bajo de
 * corte fijo firma ese trato una vez para siempre, y sale mal por los dos
 * lados: con el corte bajo el trazo va limpio pero **la tinta persigue al
 * dedo** en cuanto corre; con el corte alto la tinta va pegada pero se cuela el
 * temblor del pulso. El 1€ renegocia el trato en cada muestra: corte bajo
 * cuando el puntero va despacio —ahí el temblor se ve y el retardo no— y corte
 * alto cuando va deprisa —ahí el retardo se ve y el temblor no—.
 *
 * ## Por qué vive en el núcleo del motor
 *
 * Es aritmética pura: **trabajo constante por muestra y sin reservar memoria**,
 * que es lo que puede permitirse algo que corre en cada evento del dedo. Y al
 * no saber nada del sistema, el trato temblor/retardo se comprueba en la JVM
 * con señales sintéticas, sin mover un dedo de verdad.
 */

/**
 * Un filtro 1€ para **una** señal escalar.
 *
 * Los parámetros y su porqué:
 *
 * - [frecuenciaInicial]: solo manda hasta que llega la segunda muestra; desde
 *   ahí la frecuencia se **mide** con los sellos de tiempo reales, muestra a
 *   muestra. 120 Hz porque es lo que entregan las pantallas actuales, y como
 *   solo pinta en la primera alfa, afinarlo no merece más.
 * - [corteMinimo] (en hercios): el corte con el puntero quieto, o sea **cuánto
 *   temblor se quita en reposo**. Bajarlo limpia más y retrasa más; es `var`
 *   porque es el mando que se afina en vivo.
 * - [beta]: cuánto se abre el corte con la velocidad. **Está en unidades⁻¹**
 *   —multiplica una derivada en unidades/segundo y tiene que dar hercios— así
 *   que su escala depende de en qué venga la señal: **los valores por defecto
 *   de esta clase y de [AlisadoDelPuntero] son para PÍXELES**; para milímetros,
 *   fracciones de 0 a 1 o cualquier otra unidad hay que reafinarlo.
 * - [corteDeLaDeriva] (en hercios): el paso bajo de la propia derivada, para
 *   que el corte adaptativo no persiga al mismo ruido que intenta quitar. El
 *   canónico usa 1 Hz y casi nunca se toca.
 *
 * ## Afinado, en dos pasos y en este orden
 *
 * 1. **Primero el reposo**: con [beta] a 0 y el puntero quieto o muy lento,
 *    bajar [corteMinimo] hasta que el temblor desaparezca (y no más: cada
 *    vuelta de menos es retardo de más).
 * 2. **Después la velocidad**: con trazos rápidos, subir [beta] desde 0 hasta
 *    que la tinta deje de quedarse atrás.
 */
class UnEuro(
    private val frecuenciaInicial: Double = 120.0,
    var corteMinimo: Double = 1.0,
    var beta: Double = 0.0,
    val corteDeLaDeriva: Double = 1.0
) {

    /**
     * El paso bajo exponencial de dentro (el `LowPassFilter` del canónico).
     *
     * **La primera muestra sale tal cual**: sin esto el filtro arrancaría desde
     * cero y el principio de cada trazo sería un transitorio visible, una cola
     * que viaja desde el origen hasta donde cayó el dedo.
     */
    private class PasoBajo {
        var alisado = 0.0
        var arrancado = false

        fun filtrar(valor: Double, alfa: Double): Double {
            val resultado = if (arrancado) alfa * valor + (1.0 - alfa) * alisado else valor
            arrancado = true
            alisado = resultado
            return resultado
        }

        fun reiniciar() {
            arrancado = false
            alisado = 0.0
        }
    }

    private val filtroDelValor = PasoBajo()
    private val filtroDeLaDeriva = PasoBajo()
    private var frecuencia = frecuenciaInicial

    /** NaN = aún no ha llegado ninguna muestra (el `UndefinedTime` del canónico). */
    private var ultimoInstante = Double.NaN

    /**
     * La alfa de un paso bajo para un [corte] en hercios: `1 / (1 + tau/te)`
     * con `tau = 1 / (2π·corte)` y `te` el periodo real de muestreo. Es la
     * discretización del paso bajo de primer orden, tal cual el canónico.
     */
    private fun alfa(corte: Double): Double {
        val periodo = 1.0 / frecuencia
        val tau = 1.0 / (2.0 * PI * corte)
        return 1.0 / (1.0 + tau / periodo)
    }

    /**
     * Alisa [valor], llegado en el instante [cuandoSeg] (en segundos).
     *
     * Tres decisiones heredadas del canónico, cada una con su porqué:
     *
     * - La frecuencia se mide con el **dt real** de cada muestra (solo si el
     *   tiempo avanza). Los eventos del dedo no llegan a ritmo fijo, y una alfa
     *   calculada para un dt que no fue alisaría de más o de menos.
     * - La derivada se mide contra el valor **filtrado** anterior, no el crudo:
     *   restar dos muestras crudas restaría dos ruidos, y la derivada es ya la
     *   señal más ruidosa del filtro (un dt pequeño la multiplica).
     * - En la primera muestra la derivada es 0: aún no hay pasado del que
     *   derivar, y **la salida es el propio valor**, sin transitorio.
     */
    fun filtrar(valor: Double, cuandoSeg: Double): Double {
        if (!ultimoInstante.isNaN() && cuandoSeg > ultimoInstante) {
            frecuencia = 1.0 / (cuandoSeg - ultimoInstante)
        }
        ultimoInstante = cuandoSeg

        val derivada = if (filtroDelValor.arrancado) {
            (valor - filtroDelValor.alisado) * frecuencia
        } else {
            0.0
        }
        val derivadaAlisada = filtroDeLaDeriva.filtrar(derivada, alfa(corteDeLaDeriva))

        // El trato del 1€: el corte sube con la velocidad (alisada), así que
        // despacio manda [corteMinimo] y deprisa manda la mano.
        val corte = corteMinimo + beta * abs(derivadaAlisada)
        return filtroDelValor.filtrar(valor, alfa(corte))
    }

    /**
     * Olvida todo: valores, derivada, frecuencia medida e instante. Después de
     * esto la siguiente muestra es de nuevo la primera, **salga tal cual**.
     */
    fun reiniciar() {
        frecuencia = frecuenciaInicial
        ultimoInstante = Double.NaN
        filtroDelValor.reiniciar()
        filtroDeLaDeriva.reiniciar()
    }
}

/** Una muestra del puntero ya alisada, lista para el trazo. */
data class MuestraAlisada(val x: Double, val y: Double, val presion: Double)

/**
 * El alisado completo de un puntero: posición y presión.
 *
 * Lleva **tres [UnEuro], uno por eje**, como hace Chromium con el tacto: cada
 * eje es una señal de una dimensión y se filtra por su cuenta, así el filtro
 * sigue siendo escalar y barato, y un trazo recto sigue recto porque los dos
 * ejes cargan el mismo retardo.
 *
 * La presión va con sus propios mandos —ver [CORTE_DE_LA_PRESION] y
 * [BETA_DE_LA_PRESION]— porque **la presión no necesita seguir a la
 * velocidad**: que la mano corra no significa que apriete distinto, y abrirle
 * el corte con la velocidad dejaría pasar el ruido de presión justo en los
 * trazos rápidos, donde se vería como un gusano de grosor cambiante.
 *
 * **Hay que [reiniciar] entre trazo y trazo**: el filtro es la memoria de un
 * gesto, y sin reiniciar la primera muestra del trazo nuevo se alisaría contra
 * donde acabó el anterior —la tinta arrancaría torcida hacia el trazo viejo— y
 * la pausa entre trazos entraría en la cuenta de la frecuencia como si hubiera
 * sido una muestra más.
 *
 * Los valores por defecto (1.4 y 0.003) son un punto de partida afinado **en
 * píxeles** y a ritmo de pantalla; el procedimiento para reafinarlos está en
 * el KDoc de [UnEuro].
 */
class AlisadoDelPuntero(corteMinimo: Double = 1.4, beta: Double = 0.003) {

    private val ejeX = UnEuro(corteMinimo = corteMinimo, beta = beta)
    private val ejeY = UnEuro(corteMinimo = corteMinimo, beta = beta)
    private val ejeDePresion = UnEuro(corteMinimo = CORTE_DE_LA_PRESION, beta = BETA_DE_LA_PRESION)

    /**
     * Alisa una muestra del puntero. [cuandoMs] va en milisegundos porque así
     * llegan los sellos de tiempo de los eventos del sistema; la conversión a
     * segundos se hace aquí, una vez, y no en cada sitio que llama.
     */
    fun filtrar(x: Double, y: Double, presion: Double, cuandoMs: Long): MuestraAlisada {
        val cuandoSeg = cuandoMs / MILISEGUNDOS_POR_SEGUNDO
        return MuestraAlisada(
            x = ejeX.filtrar(x, cuandoSeg),
            y = ejeY.filtrar(y, cuandoSeg),
            presion = ejeDePresion.filtrar(presion, cuandoSeg)
        )
    }

    /** Olvida el trazo: los tres ejes vuelven a «la siguiente muestra es la primera». */
    fun reiniciar() {
        ejeX.reiniciar()
        ejeY.reiniciar()
        ejeDePresion.reiniciar()
    }

    private companion object {
        /** Los cortes del filtro están en hercios y los sellos llegan en milisegundos. */
        const val MILISEGUNDOS_POR_SEGUNDO = 1000.0

        /**
         * El corte de la presión en reposo: 1 Hz, más agresivo que el de la
         * posición porque el ruido de presión no se ve como retardo, se ve como
         * **grosor que tiembla** a lo largo del trazo.
         */
        const val CORTE_DE_LA_PRESION = 1.0

        /** Beta 0: el corte de la presión **no** se abre con la velocidad. Ver arriba. */
        const val BETA_DE_LA_PRESION = 0.0
    }
}
