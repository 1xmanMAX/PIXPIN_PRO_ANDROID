package com.forge.pixpin.audio

/**
 * **Lo grabado bajito, más alto al oírlo** (20-oct-2026, puerto de `pixpin-audio/src/volumen.rs` del
 * PC; el usuario: «se graban con un volumen muy bajo, casi no se escucha: auméntalos con software a
 * la hora de reproducirlos»).
 *
 * Se mide el pico de verdad del audio —el 99,9 % de las muestras, para que un chasquido suelto no
 * mande— y la ganancia es la que lo lleva a −1 dBFS, hasta ×16 (+24 dB). Menos de ×1,3 no merece la
 * pena: ya se oye bien. Cómo se aplica es cosa de [AudioAlto] (Android); aquí solo las cuentas, sin
 * Android, con las mismas constantes que el PC.
 */
class Volumen {
    companion object {
        /** A dónde se sube el pico: −1 dBFS. */
        const val OBJETIVO = 0.89f
        /** Lo máximo que se sube: ×16 (+24 dB), lo mismo que el realce al grabar. */
        const val GANANCIA_MAXIMA = 16f
        /** Menos que esto no se toca: ya se oye bien. */
        const val GANANCIA_MINIMA = 1.3f
        /** Desde aquí el limitador redondea en vez de dejar saturar. */
        const val UMBRAL = 0.85f

        /** La ganancia para unas muestras ya en −1..1. */
        fun gananciaPara(muestras: FloatArray): Float = Volumen().apply { muestras.forEach { anadir(it) } }.ganancia()

        /** Sube [m] [g] veces; lo que pasa del umbral se redondea (tanh), no se recorta. */
        fun aplicar(m: Float, g: Float): Float {
            val y = m * g
            val a = kotlin.math.abs(y)
            return if (a <= UMBRAL) y else Math.copySign(UMBRAL + (1 - UMBRAL) * kotlin.math.tanh((a - UMBRAL) / (1 - UMBRAL)), y)
        }

        /** En milibelios, como los pide `LoudnessEnhancer`. */
        fun aMilibelios(g: Float): Int = (2000.0 * kotlin.math.log10(g.coerceAtLeast(1f).toDouble())).toInt()
    }

    // Un histograma en vez de ordenar: un audio largo tiene millones de muestras.
    private val cubos = LongArray(1024)
    private var cuantas = 0L

    fun anadir(m: Float) {
        cubos[(kotlin.math.abs(m).coerceAtMost(1f) * 1023f).toInt()]++
        cuantas++
    }

    fun anadir16(m: Short) = anadir(m / 32768f)

    /** La ganancia que lleva el pico al objetivo; 1 si es silencio o no hay nada. */
    fun ganancia(): Float {
        if (cuantas == 0L) return 1f
        val corte = (cuantas * 0.999).toLong()
        var acumulado = 0L
        var pico = 1f
        for (i in cubos.indices) {
            acumulado += cubos[i]
            if (acumulado >= corte) { pico = (i + 1f) / 1024f; break }
        }
        if (pico < 0.002f) return 1f
        return (OBJETIVO / pico).coerceIn(1f, GANANCIA_MAXIMA)
    }
}
