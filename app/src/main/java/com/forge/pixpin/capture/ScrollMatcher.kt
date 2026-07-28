package com.forge.pixpin.capture

import kotlin.math.abs

/**
 * Matemática del cosido de la captura con scroll: pura, sin Android, para poder
 * probarla sin dispositivo.
 *
 * Android no tiene API de captura con scroll (la del sistema solo funciona
 * dentro de apps que la implementan), así que hay que deducir cuánto se ha
 * desplazado la pantalla comparando fotogramas consecutivos. Comparar imágenes
 * enteras sería carísimo; aquí cada fila se resume en una **firma** —la suma de
 * las luminancias de unos cuantos píxeles— y el desplazamiento se busca sobre
 * ese vector de firmas.
 *
 * Lo importante no es acertar siempre, sino **no acertar por casualidad**: un
 * fotograma mal cosido estropea la imagen final sin que se note hasta el final.
 * Por eso se rechaza lo dudoso (bandas lisas, coincidencias ambiguas) y se
 * espera al siguiente fotograma, que llega en milisegundos.
 */
object ScrollMatcher {

    const val NO_MATCH = -1

    /** Distancia mínima entre dos candidatos para considerarlos alternativas reales. */
    private const val MIN_SEPARATION = 4

    /** Cuánto peor tiene que ser la segunda opción para fiarse de la primera. */
    private const val AMBIGUITY_FACTOR = 2

    /**
     * Firma de la fila que empieza en [offset] dentro de [pixels] (ARGB), de
     * [width] píxeles, muestreando uno de cada [step].
     */
    fun rowSignature(pixels: IntArray, offset: Int, width: Int, step: Int): Int {
        var sum = 0
        var x = 0
        while (x < width) {
            val p = pixels[offset + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Luminancia entera: 0,299 / 0,587 / 0,114 en escala de 256.
            sum += (r * 77 + g * 151 + b * 28) shr 8
            x += step
        }
        return sum
    }

    /** Firmas de todas las filas de una imagen ya volcada a [pixels]. */
    fun signatures(pixels: IntArray, width: Int, height: Int, step: Int): IntArray =
        IntArray(height) { rowSignature(pixels, it * width, width, step) }

    /**
     * Dónde vuelven a aparecer, dentro de [frame], las filas de [tail] (el final
     * de lo ya acumulado). El resultado es cuánto ha subido el contenido:
     * 0 = la pantalla no se ha movido.
     *
     * Devuelve [NO_MATCH] si no encaja con confianza o si encaja en varios
     * sitios parecidos.
     */
    fun findOffset(tail: IntArray, frame: IntArray, tolerance: Int): Int {
        if (tail.isEmpty() || frame.size < tail.size) return NO_MATCH

        var best = NO_MATCH
        var bestScore = Long.MAX_VALUE
        val scores = LongArray(frame.size - tail.size + 1)

        for (d in scores.indices) {
            var score = 0L
            for (k in tail.indices) score += abs(tail[k] - frame[d + k]).toLong()
            scores[d] = score
            if (score < bestScore) {
                bestScore = score
                best = d
            }
        }
        if (best == NO_MATCH) return NO_MATCH

        val bestMean = bestScore / tail.size
        if (bestMean > tolerance) return NO_MATCH

        // Segunda mejor opción lo bastante lejos como para ser otra alternativa.
        var secondScore = Long.MAX_VALUE
        for (d in scores.indices) {
            if (abs(d - best) < MIN_SEPARATION) continue
            if (scores[d] < secondScore) secondScore = scores[d]
        }
        if (secondScore == Long.MAX_VALUE) return best // no había alternativa

        // El margen aditivo es lo que salva de los patrones que se repiten (una
        // cabecera cada N filas encaja PERFECTO en varios sitios a la vez: sin
        // él, «el doble de malo» se cumpliría con dos ceros).
        val secondMean = secondScore / tail.size
        return if (secondMean >= bestMean * AMBIGUITY_FACTOR + tolerance / 2) best else NO_MATCH
    }

    /**
     * Una banda sin textura (un fondo liso, un degradado suave) encaja en
     * cualquier sitio: no sirve como referencia y hay que esperar.
     */
    fun isFlat(tail: IntArray, minVariation: Int): Boolean {
        if (tail.size < 2) return true
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (v in tail) {
            if (v < min) min = v
            if (v > max) max = v
        }
        return max - min < minVariation
    }
}
