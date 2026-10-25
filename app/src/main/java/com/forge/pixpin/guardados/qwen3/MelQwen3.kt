package com.forge.pixpin.guardados.qwen3

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * **El log-mel de Qwen3-ASR**: el de Whisper con 128 bandas y sin rellenar a 30 s (25-oct-2026).
 *
 * Puerto de `crates/pixpin-voz/src/qwen3/mel.rs` del PC. Qwen3-ASR usa el
 * `WhisperFeatureExtractor` de `transformers` (`feature_size = 128`, `n_fft = 400`,
 * `hop_length = 160`) pero **no rellena hasta 30 s**: el frente convolucional acepta cualquier
 * número de marcos. Aquí no hay mel de Whisper que reaprovechar (en el teléfono lo calcula
 * sherpa-onnx por dentro), así que va entero:
 *
 * 1. STFT con Hann periódica de 400 muestras, salto de 160, centrada con relleno reflejado
 *    (`center=True`), y se tira el último marco: salen `muestras / 160` marcos.
 * 2. Potencia de los 201 cubos y 128 filtros mel de Slaney normalizados por área.
 * 3. `log10(max(x, 1e-10))`, recorte a `max - 8` sobre todo el trozo y `(x + 4) / 4`.
 *
 * Sale **marco a marco** (`[marcos, 128]`), la forma de `input_features` del frente. Aritmética
 * pura: se prueba entera sin modelo contra los mismos valores de numpy que el PC (`MelQwen3Test`).
 */
object MelQwen3 {
    const val HERCIOS = 16_000
    const val N_FFT = 400
    const val SALTO = 160
    const val BANDAS = 128
    /** Cubos de frecuencia de una FFT real de 400: 0..=200. */
    const val CUBOS = N_FFT / 2 + 1

    /** Hercios a mel de Slaney: lineal hasta 1 kHz y logarítmica después. */
    private fun hzAMel(f: Double): Double {
        val fSp = 200.0 / 3.0
        return if (f >= 1000.0) 1000.0 / fSp + ln(f / 1000.0) / (ln(6.4) / 27.0) else f / fSp
    }

    private fun melAHz(m: Double): Double {
        val fSp = 200.0 / 3.0
        val minLogMel = 1000.0 / fSp
        return if (m >= minLogMel) 1000.0 * exp((ln(6.4) / 27.0) * (m - minLogMel)) else fSp * m
    }

    /**
     * **Los 128 filtros**, `BANDAS x CUBOS` fila a fila: 130 puntos equiespaciados en mel de 0 a
     * 8 kHz, un triángulo por banda y cada uno escalado por `2 / ancho` (misma área).
     */
    val filtros: FloatArray by lazy {
        val tope = hzAMel(HERCIOS / 2.0)
        val puntos = DoubleArray(BANDAS + 2) { melAHz(tope * it / (BANDAS + 1)) }
        val w = FloatArray(BANDAS * CUBOS)
        for (b in 0 until BANDAS) {
            val izq = puntos[b]; val centro = puntos[b + 1]; val der = puntos[b + 2]
            val norma = 2.0 / (der - izq)
            for (k in 0 until CUBOS) {
                val f = k * (HERCIOS / 2.0) / (CUBOS - 1)
                val sube = (f - izq) / (centro - izq)
                val baja = (der - f) / (der - centro)
                w[b * CUBOS + k] = (max(min(sube, baja), 0.0) * norma).toFloat()
            }
        }
        w
    }

    /** Coseno y seno de cada cubo por cada muestra de la ventana, con la Hann ya aplicada: la DFT a mano (400 no es potencia de dos). */
    private val tablas: Pair<FloatArray, FloatArray> by lazy {
        val c = FloatArray(CUBOS * N_FFT)
        val s = FloatArray(CUBOS * N_FFT)
        for (n in 0 until N_FFT) {
            val hann = 0.5 - 0.5 * cos(2.0 * PI * n / N_FFT)
            for (k in 0 until CUBOS) {
                val a = 2.0 * PI * ((k * n) % N_FFT) / N_FFT
                c[k * N_FFT + n] = (hann * cos(a)).toFloat()
                s[k * N_FFT + n] = (hann * sin(a)).toFloat()
            }
        }
        c to s
    }

    /** Cuántos marcos salen de [muestras] (lo que espera el frente). */
    fun marcos(muestras: Int): Int = muestras / SALTO

    /**
     * **El log-mel de un trozo**, `[marcos, 128]` marco a marco. [audio] va de -1 a 1 a 16 kHz.
     * Un trozo de menos de 10 ms no da ningún marco.
     */
    fun logMel(audio: FloatArray): FloatArray {
        val nMarcos = marcos(audio.size)
        if (nMarcos == 0) return FloatArray(0)
        // `center=True`: 200 muestras de reflejo a cada lado (`x[-k] = x[k]`). Con menos de 201
        // muestras no hay reflejo posible; un trozo así no da marcos (ver arriba).
        val mitad = N_FFT / 2
        val n = audio.size
        val senal = FloatArray(n + 2 * mitad)
        System.arraycopy(audio, 0, senal, mitad, n)
        for (i in 0 until mitad) {
            senal[mitad - 1 - i] = audio[min(i + 1, n - 1)]
            senal[mitad + n + i] = audio[max(n - 2 - i, 0)]
        }
        val (cos, sin) = tablas
        val w = filtros
        val mel = FloatArray(nMarcos * BANDAS)
        val potencia = FloatArray(CUBOS)
        for (m in 0 until nMarcos) {
            val base = m * SALTO
            var todoCero = true
            for (j in 0 until N_FFT) if (senal[base + j] != 0f) { todoCero = false; break }
            if (todoCero) {
                potencia.fill(0f)
            } else {
                for (k in 0 until CUBOS) {
                    val o = k * N_FFT
                    var re = 0f; var im = 0f
                    for (j in 0 until N_FFT) {
                        val t = senal[base + j]
                        re += t * cos[o + j]
                        im += t * sin[o + j]
                    }
                    potencia[k] = re * re + im * im
                }
            }
            for (b in 0 until BANDAS) {
                val o = b * CUBOS
                var e = 0f
                for (k in 0 until CUBOS) e += w[o + k] * potencia[k]
                mel[m * BANDAS + b] = log10(max(e, 1e-10f))
            }
        }
        var tope = -Float.MAX_VALUE
        for (x in mel) if (x > tope) tope = x
        for (i in mel.indices) mel[i] = (max(mel[i], tope - 8f) + 4f) / 4f
        return mel
    }
}
