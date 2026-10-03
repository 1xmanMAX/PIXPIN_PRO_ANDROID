package com.forge.pixpin.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * **El realce de la voz al grabar** (puerto de `pixpin-audio/src/realce.rs` del PC, 3-oct-2026,
 * mismas constantes y mismas pruebas): una voz baja o lejana sale clara sin tocar nada.
 *
 * Cadena, muestra a muestra y en vivo, antes de comprimir:
 * 1. Paso alto Butterworth de segundo orden a 80 Hz: quita el retumbe y la continua.
 * 2. Ganancia automática: ataque 10 ms, suelta 300 ms, hacia −3 dBFS, entre 0 y +24 dB. Nunca baja.
 * 3. Puerta: por debajo de −45 dBFS la ganancia no sube y vuelve a 0 dB en 1,5 s (el silencio no se
 *    convierte en ruido).
 * 4. Limitador suave (tanh) desde −1,4 dBFS, con techo justo por debajo de 0 dBFS.
 *
 * Puro: sin Android, para probarlo en la JVM.
 */
class Realce(muestreo: Int) {
    companion object {
        const val CORTE_HZ = 80f
        const val ATAQUE_S = 0.010f
        const val SUELTA_S = 0.300f
        const val OBJETIVO = 0.707946f
        const val GANANCIA_MAXIMA = 15.848932f
        const val GANANCIA_MINIMA = 1f
        const val PISO = 0.005623f
        const val UMBRAL_LIMITADOR = 0.85f
        private const val VUELTA_EN_SILENCIO_S = 1.5f

        private fun coeficiente(segundos: Float, muestreo: Int): Float {
            val n = segundos * muestreo.coerceAtLeast(1)
            return if (n <= 0f) 1f else 1f - exp(-1f / n)
        }

        fun limitar(x: Float): Float {
            val a = abs(x)
            if (a <= UMBRAL_LIMITADOR) return x
            val margen = 1f - UMBRAL_LIMITADOR
            val doblado = UMBRAL_LIMITADOR + margen * tanh((a - UMBRAL_LIMITADOR) / margen)
            return Math.copySign(doblado.coerceAtMost(0.9999f), x)
        }
    }

    private val b0: Float; private val b1: Float; private val b2: Float; private val a1: Float; private val a2: Float
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    private val ataque = coeficiente(ATAQUE_S, muestreo)
    private val suelta = coeficiente(SUELTA_S, muestreo)
    private val vuelta = coeficiente(VUELTA_EN_SILENCIO_S, muestreo)
    private var envolvente = 0f
    var ganancia = GANANCIA_MINIMA
        private set

    init {
        val fs = muestreo.coerceAtLeast(1).toFloat()
        val w0 = (2.0 * PI * (CORTE_HZ / fs).coerceAtMost(0.49f)).toFloat()
        val seno = sin(w0); val coseno = cos(w0)
        val alfa = seno / (2f * 0.70710677f)
        val a0 = 1f + alfa
        b0 = (1f + coseno) / 2f / a0
        b1 = -(1f + coseno) / a0
        b2 = (1f + coseno) / 2f / a0
        a1 = -2f * coseno / a0
        a2 = (1f - alfa) / a0
    }

    /** Realza [muestras] (PCM de 16 bits) en su sitio, de [desde] a [hasta]. */
    fun procesar(muestras: ShortArray, desde: Int = 0, hasta: Int = muestras.size) {
        for (i in desde until hasta) {
            val x = muestras[i] / 32768f
            var y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            if (abs(y) < 1.0e-12f) y = 0f
            x2 = x1; x1 = x; y2 = y1; y1 = y
            val a = abs(y)
            envolvente += (a - envolvente) * (if (a > envolvente) ataque else suelta)
            if (envolvente < PISO) {
                ganancia += (GANANCIA_MINIMA - ganancia) * vuelta
            } else {
                val quiere = (OBJETIVO / envolvente).coerceIn(GANANCIA_MINIMA, GANANCIA_MAXIMA)
                ganancia += (quiere - ganancia) * (if (quiere < ganancia) ataque else suelta)
            }
            val fuera = limitar(y * ganancia)
            muestras[i] = Math.round(fuera * 32767f).coerceIn(-32767, 32767).toShort()
        }
    }
}
