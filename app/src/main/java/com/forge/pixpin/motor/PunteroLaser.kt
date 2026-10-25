package com.forge.pixpin.motor

import kotlin.math.pow

/**
 * **El puntero láser** (25-oct-2026, porte de `puntero_laser.rs` del PC): una estela roja que
 * sigue al dedo y se apaga sola en un segundo, para señalar **sin dejar rastro en el dibujo**.
 *
 * Es la herramienta de Excalidraw (`LaserTrails.ts`) y la que se usa al presentar o al explicar
 * un plano en una llamada. No toca la escena ni el historial ni el fichero: vive en el
 * controlador como el lazo, y la pinta el lienzo encima de todo (`pintarElLaser`, en `PintarElLaser.kt`). Las cuentas
 * son las de Excalidraw, como en el PC: cada punto se apaga en [DURACION_MS] y la estela no pasa
 * de [LARGO] puntos por detrás de la punta, con la suavidad `easeOut` de su `sizeMapping`.
 *
 * Las horas van en milisegundos de quien llama (la del evento): así se prueba sin reloj.
 */
class PunteroLaser {

    /** Cada arrastre es una estela: puntos de escena con su hora. */
    private val estelas = mutableListOf<MutableList<Pair<Pt, Long>>>()

    /** Si el dedo sigue apoyado: mientras, la punta se ve aunque esté quieta. */
    var pulsado: Boolean = false
        private set

    fun pulsar(p: Pt, ms: Long) {
        podar(ms)
        pulsado = true
        estelas += mutableListOf(p to ms)
    }

    /** Un punto más de la estela en curso. Sin el dedo apoyado no hace nada. */
    fun mover(p: Pt, ms: Long): Boolean {
        if (!pulsado) return false
        estelas.lastOrNull()?.add(p to ms)
        return true
    }

    fun soltar(ms: Long) {
        pulsado = false
        podar(ms)
    }

    /** Se olvida todo (al cambiar de herramienta). */
    fun vaciar() {
        estelas.clear()
        pulsado = false
    }

    /** Quita lo que ya se apagó del todo; de la estela en curso se guarda siempre la punta. */
    private fun podar(ms: Long) {
        val n = estelas.size
        val it = estelas.listIterator()
        var i = 0
        while (it.hasNext()) {
            val e = it.next()
            val viva = pulsado && i + 1 == n
            i++
            val apagados = e.takeWhile { ms - it.second >= DURACION_MS }.size
            val quitar = if (viva) minOf(apagados, e.size - 1) else apagados
            repeat(quitar.coerceAtLeast(0)) { e.removeAt(0) }
            while (e.size > LARGO * 2) e.removeAt(0)
            if (e.isEmpty()) it.remove()
        }
    }

    /** Si todavía hay algo que enseñar: mientras sí, el lienzo tiene que seguir repintando. */
    fun vivo(ms: Long): Boolean =
        pulsado || estelas.any { e -> e.any { ms - it.second < DURACION_MS } }

    /** Un tramo de la estela con su grueso, de 0 a 1 del de la punta. */
    data class Tramo(val a: Pt, val b: Pt, val grueso: Double)

    /**
     * **Lo que se pinta**: cada tramo con su grueso, el menor entre lo que se ha apagado con el
     * tiempo y lo lejos que queda de la punta. Los apagados del todo no salen.
     */
    fun tramos(ms: Long): List<Tramo> {
        val salida = mutableListOf<Tramo>()
        for (e in estelas) {
            val total = e.size
            val gruesos = e.mapIndexed { i, (_, t) ->
                val porTiempo = 1.0 - (ms - t).toDouble() / DURACION_MS
                val desdeLaPunta = minOf(total - 1 - i, LARGO).toDouble()
                val porLargo = (LARGO - desdeLaPunta) / LARGO
                minOf(suave(porTiempo), suave(porLargo))
            }
            for (i in 0 until total - 1) {
                val g = (gruesos[i] + gruesos[i + 1]) / 2
                if (g > 0.01) salida += Tramo(e[i].first, e[i + 1].first, g)
            }
        }
        return salida
    }

    /** La punta, mientras el dedo sigue apoyado. */
    fun punta(): Pt? = if (pulsado) estelas.lastOrNull()?.lastOrNull()?.first else null

    companion object {
        /** Lo que tarda en apagarse un punto (`DECAY_TIME`). */
        const val DURACION_MS = 1000L

        /** Cuántos puntos por detrás de la punta llega la estela (`DECAY_LENGTH`). */
        const val LARGO = 50

        /** El grueso de la punta, en dp de pantalla. */
        const val ANCHO_DP = 5f

        /**
         * El reloj de la estela: el mismo para quien apunta (el controlador) y quien pinta (el
         * lienzo). La hora del evento no vale aquí: va en otro reloj que el del fotograma.
         */
        fun ahora(): Long = System.nanoTime() / 1_000_000

        /** `easeOut` de Excalidraw: `1 - (1 - k)^4`. */
        fun suave(k: Double): Double = 1.0 - (1.0 - k.coerceIn(0.0, 1.0)).pow(4)
    }
}
