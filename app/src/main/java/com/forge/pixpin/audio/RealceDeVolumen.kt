package com.forge.pixpin.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * **Lo grabado bajito, más alto al oírlo** (puerto de `pixpin-audio/src/volumen.rs` del PC,
 * commit cd0cc30 del 19-oct-2026; aquí el 24-oct-2026, mismas constantes y mismas pruebas). El
 * usuario: «se graban con un volumen muy bajo, casi no se escucha: auméntalos con software a la
 * hora de reproducirlos».
 *
 * Se mide **el pico de verdad** del audio —el 99,9 % de las muestras, para que un chasquido
 * suelto no mande— y de ahí sale **la ganancia justa** para dejarlo en −1 dBFS, con tope de ×16
 * (+24 dB, lo mismo que el realce al grabar, ver [Realce]). En el PC se escribe una copia con esa
 * ganancia y un limitador suave (tanh); en Android la sube `LoudnessEnhancer` sobre la sesión del
 * `MediaPlayer` (ver [RealceAlOir]), que trae su propio limitador, así que [aplicar] queda para
 * quien tenga las muestras en la mano (y para las pruebas).
 *
 * Puro: sin Android, para probarlo en la JVM.
 */
object RealceDeVolumen {
    /** A dónde se sube el pico: −1 dBFS. */
    const val OBJETIVO = 0.89f

    /** Lo máximo que se sube: ×16 (+24 dB), lo mismo que el realce al grabar. */
    const val GANANCIA_MAXIMA = 16f

    /** Menos que esto no merece realce: ya se oye bien. */
    const val GANANCIA_MINIMA = 1.3f

    /** Desde aquí el limitador redondea en vez de dejar saturar. */
    const val UMBRAL = 0.85f

    /** Por debajo de este pico es silencio (o casi): nada que subir. */
    private const val SILENCIO = 0.002f

    private const val CUBOS = 1024

    /**
     * **El medidor del pico, a trozos**: un histograma de 1024 cubos en vez de guardar y ordenar
     * las muestras —un audio largo tiene millones y en el teléfono no caben de golpe—. Se le van
     * dando los trozos según salen del descodificador y al final [ganancia] dice cuánto subir.
     */
    class Medidor {
        private val cubos = LongArray(CUBOS)
        var muestras = 0L
            private set

        /** Cuenta [trozo] (muestras de −1 a 1, entrelazadas por canal da igual). */
        fun anadir(trozo: FloatArray, desde: Int = 0, hasta: Int = trozo.size) {
            for (i in desde until hasta) contar(trozo[i])
        }

        /** Cuenta [trozo] en PCM de 16 bits, como lo da `MediaCodec` por defecto. */
        fun anadir(trozo: ShortArray, desde: Int = 0, hasta: Int = trozo.size) {
            for (i in desde until hasta) contar(trozo[i] / 32768f)
        }

        fun contar(m: Float) {
            val i = (abs(m).coerceAtMost(1f) * (CUBOS - 1)).toInt()
            cubos[i]++
            muestras++
        }

        /**
         * **El pico de verdad**: hasta dónde llega el 99,9 % de las muestras (el borde de arriba
         * del cubo donde se cruza ese corte).
         */
        fun pico(): Float {
            if (muestras == 0L) return 0f
            val corte = (muestras.toDouble() * 0.999).toLong()
            var acumulado = 0L
            for (i in 0 until CUBOS) {
                acumulado += cubos[i]
                if (acumulado >= corte) return (i + 1f) / CUBOS
            }
            return 1f
        }

        /** La ganancia para que ese pico quede en [OBJETIVO], con tope; 1 si es silencio o nada. */
        fun ganancia(): Float {
            if (muestras == 0L) return 1f
            val p = pico()
            if (p < SILENCIO) return 1f
            return (OBJETIVO / p).coerceIn(1f, GANANCIA_MAXIMA)
        }
    }

    /**
     * **La ganancia para que el pico de verdad quede en el objetivo**: el pico es el 99,9 % de las
     * muestras (un golpe suelto no frena el realce), con tope; 1 si es silencio.
     */
    fun gananciaPara(muestras: FloatArray): Float = Medidor().apply { anadir(muestras) }.ganancia()

    /** Si [g] merece realce: por debajo de [GANANCIA_MINIMA] ya se oye bien y no se toca. */
    fun merece(g: Float): Boolean = g >= GANANCIA_MINIMA

    /**
     * Sube cada muestra [g] veces; lo que pasa del [UMBRAL] se redondea (tanh) en vez de
     * recortarse: no se oye roto y nunca llega a 1.
     */
    fun aplicar(muestras: FloatArray, g: Float) {
        for (i in muestras.indices) muestras[i] = limitar(muestras[i] * g)
    }

    /** El limitador suave de una muestra ya subida. */
    fun limitar(y: Float): Float {
        val a = abs(y)
        if (a <= UMBRAL) return y
        val margen = 1f - UMBRAL
        return Math.copySign(UMBRAL + margen * tanh((a - UMBRAL) / margen), y)
    }

    /**
     * [g] en milibelios, lo que pide `LoudnessEnhancer.setTargetGain`: 20·log10(g)·100. ×16 son
     * unos 2408 mB; 1 o menos, 0.
     */
    fun milibelios(g: Float): Int = if (g <= 1f) 0 else (2000.0 * log10(g.toDouble())).roundToInt()
}
