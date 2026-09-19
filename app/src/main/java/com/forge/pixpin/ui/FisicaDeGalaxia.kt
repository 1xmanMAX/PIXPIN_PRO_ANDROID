package com.forge.pixpin.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * **La física del sistema solar de proyectos** (17-sep-2026). Ver [PantallaDeGalaxia].
 *
 * Verlet con restricciones (lo de los juegos de cuerdas): cada cuerpo guarda dónde está y dónde
 * estaba, la velocidad es la diferencia, y después de moverlos se corrigen las distancias. Es
 * estable con cualquier paso y no necesita fuerzas ni masas explícitas.
 *
 * - **Cuerdas** (sol ↔ sol): solo tiran cuando se estiran. Arrastrar un sol lleva detrás a los
 *   que están conectados, con retraso, sin moverse en bloque; acercarlo no empuja a nadie. Si se
 *   tira un rato la cuerda **cede** un poco ([CEDER]), así que alejar dos soles conectados se
 *   puede, cuesta. Al dormirse, cada cuerda toma su largo de ese momento.
 * - **Varillas** (sol → exoplaneta): mantienen la órbita. Solo mueven al exoplaneta: una nota
 *   no arrastra un sol.
 * - **Choques** entre soles: no se pisan; arrastrar uno contra otro lo aparta.
 *
 * Todo en arrays planos y sin reservar memoria al pasar: corre solo mientras algo se mueve y
 * la pantalla se duerme en cuanto se para. Ver [pixpin-rendimiento-primero].
 *
 * Unidades: dp del mundo; el tiempo, en pasos de 1/60 s.
 */
class FisicaDeGalaxia(val n: Int, val soles: Int) {
    val x = FloatArray(n)
    val y = FloatArray(n)
    private val px = FloatArray(n)
    private val py = FloatArray(n)
    /** Radio para los choques; solo cuenta en los soles. */
    val radio = FloatArray(n)
    private val sujeto = BooleanArray(n)

    private var cuerdas = IntArray(0)
    private var largoCuerda = FloatArray(0)
    private var varillas = IntArray(0)
    private var largoVarilla = FloatArray(0)

    fun poner(i: Int, nx: Float, ny: Float) {
        x[i] = nx; y[i] = ny; px[i] = nx; py[i] = ny
    }

    /** Las cuerdas, como pares seguidos de índices; el largo es el que tengan ahora. */
    fun ponerCuerdas(pares: IntArray) {
        cuerdas = pares
        largoCuerda = FloatArray(pares.size / 2) { distancia(pares[2 * it], pares[2 * it + 1]) }
    }

    /** Las varillas, como pares (sol, exoplaneta); el largo es el que tengan ahora. */
    fun ponerVarillas(pares: IntArray) {
        varillas = pares
        largoVarilla = FloatArray(pares.size / 2) { max(distancia(pares[2 * it], pares[2 * it + 1]), 1f) }
    }

    fun largoDeCuerda(k: Int) = largoCuerda[k]
    val cuantasCuerdas get() = largoCuerda.size
    fun cuerda(k: Int, extremo: Int) = cuerdas[2 * k + extremo]
    val cuantasVarillas get() = largoVarilla.size
    fun varilla(k: Int, extremo: Int) = varillas[2 * k + extremo]
    fun largoDeVarilla(k: Int) = largoVarilla[k]

    /** Coger un cuerpo con el dedo: deja de moverse solo y manda el dedo. */
    fun sujetar(i: Int) {
        sujeto[i] = true
        px[i] = x[i]; py[i] = y[i]
    }

    /** Soltarlo, **con la inercia del último movimiento**: se puede lanzar. */
    fun soltar(i: Int) {
        sujeto[i] = false
    }

    fun estaSujeto(i: Int) = sujeto[i]

    /** El dedo lleva el cuerpo a (nx, ny). */
    fun llevar(i: Int, nx: Float, ny: Float) {
        px[i] = x[i]; py[i] = y[i]
        x[i] = nx; y[i] = ny
    }

    /** Un sol ha crecido o menguado [delta]: sus órbitas se abren o se cierran lo mismo. */
    fun crecer(sol: Int, delta: Float) {
        radio[sol] = max(radio[sol] + delta, 1f)
        for (k in largoVarilla.indices) if (varillas[2 * k] == sol) largoVarilla[k] = max(largoVarilla[k] + delta, 1f)
    }

    /** La varilla del cuerpo [planeta] (la que lo ata a su sol), o -1 si no tiene. */
    fun varillaDe(planeta: Int): Int {
        for (k in largoVarilla.indices) if (varillas[2 * k + 1] == planeta) return k
        return -1
    }

    /** Pone la varilla [k] a [largo] y lleva su planeta a esa distancia, por su mismo ángulo. */
    fun ponerLargoDeVarilla(k: Int, largo: Float) {
        largoVarilla[k] = max(largo, 1f)
        val s = varillas[2 * k]; val p = varillas[2 * k + 1]
        val dx = x[p] - x[s]; val dy = y[p] - y[s]
        val d = sqrt(dx * dx + dy * dy)
        if (d < 0.001f) return
        x[p] = x[s] + dx / d * largoVarilla[k]
        y[p] = y[s] + dy / d * largoVarilla[k]
        px[p] = x[p]; py[p] = y[p]
    }

    /** Un exoplaneta soltado en otro sitio de su misma órbita: la órbita pasa a ser esa. */
    fun reajustarVarillasDe(planeta: Int) {
        for (k in largoVarilla.indices) {
            if (varillas[2 * k + 1] == planeta) largoVarilla[k] = max(distancia(varillas[2 * k], planeta), 1f)
        }
    }

    /**
     * Un paso. Devuelve lo máximo que se ha movido un cuerpo libre, para saber cuándo dormirse.
     */
    fun paso(): Float {
        for (i in 0 until n) {
            if (sujeto[i]) continue
            val vx = (x[i] - px[i]) * AMORTIGUAR
            val vy = (y[i] - py[i]) * AMORTIGUAR
            px[i] = x[i]; py[i] = y[i]
            x[i] += vx; y[i] += vy
        }
        // Las cuerdas ceden si alguien tira de un extremo.
        for (k in largoCuerda.indices) {
            val a = cuerdas[2 * k]; val b = cuerdas[2 * k + 1]
            if (sujeto[a] || sujeto[b]) {
                val d = distancia(a, b)
                if (d > largoCuerda[k]) largoCuerda[k] += (d - largoCuerda[k]) * CEDER
            }
        }
        repeat(VUELTAS) {
            for (k in largoCuerda.indices) {
                val a = cuerdas[2 * k]; val b = cuerdas[2 * k + 1]
                acercar(a, b, largoCuerda[k], RIGIDEZ_CUERDA, soloTirar = true, soloB = false)
            }
            for (k in largoVarilla.indices) {
                val s = varillas[2 * k]; val p = varillas[2 * k + 1]
                acercar(s, p, largoVarilla[k], RIGIDEZ_VARILLA, soloTirar = false, soloB = true)
            }
            if (soles <= MAX_SOLES_CON_CHOQUES) chocar()
        }
        var maximo = 0f
        for (i in 0 until n) {
            if (sujeto[i]) continue
            maximo = max(maximo, abs(x[i] - px[i]) + abs(y[i] - py[i]))
        }
        return maximo
    }

    /** Al dormirse: cada cuerda se queda con el largo que tiene, que es donde el usuario la dejó. */
    fun asentar() {
        for (k in largoCuerda.indices) largoCuerda[k] = distancia(cuerdas[2 * k], cuerdas[2 * k + 1])
    }

    private fun acercar(a: Int, b: Int, largo: Float, rigidez: Float, soloTirar: Boolean, soloB: Boolean) {
        val dx = x[b] - x[a]
        val dy = y[b] - y[a]
        val d = sqrt(dx * dx + dy * dy)
        if (d < 0.0001f) return
        val sobra = d - largo
        if (soloTirar && sobra <= 0f) return
        val wa = if (sujeto[a] || soloB) 0f else 1f
        val wb = if (sujeto[b]) 0f else 1f
        val w = wa + wb
        if (w == 0f) return
        val f = sobra / d * rigidez / w
        x[a] += dx * f * wa; y[a] += dy * f * wa
        x[b] -= dx * f * wb; y[b] -= dy * f * wb
    }

    private fun chocar() {
        for (a in 0 until soles) for (b in a + 1 until soles) {
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val minimo = radio[a] + radio[b] + HOLGURA
            val d2 = dx * dx + dy * dy
            if (d2 >= minimo * minimo) continue
            val d = sqrt(d2).coerceAtLeast(0.001f)
            val wa = if (sujeto[a]) 0f else 1f
            val wb = if (sujeto[b]) 0f else 1f
            val w = wa + wb
            if (w == 0f) continue
            val f = (d - minimo) / d * 0.5f / w * 2f
            x[a] += dx * f * wa; y[a] += dy * f * wa
            x[b] -= dx * f * wb; y[b] -= dy * f * wb
        }
    }

    private fun distancia(a: Int, b: Int): Float {
        val dx = x[b] - x[a]
        val dy = y[b] - y[a]
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        const val AMORTIGUAR = 0.86f
        const val VUELTAS = 4
        const val RIGIDEZ_CUERDA = 0.18f
        const val RIGIDEZ_VARILLA = 0.35f
        const val CEDER = 0.006f
        /** Lo que queda entre dos soles que se tocan, en dp. */
        const val HOLGURA = 24f
        const val MAX_SOLES_CON_CHOQUES = 300
        /** Por debajo de esto (dp por paso) se da por quieto. */
        const val QUIETO = 0.04f
    }
}
