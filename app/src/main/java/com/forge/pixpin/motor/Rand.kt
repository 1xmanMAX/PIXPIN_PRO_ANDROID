package com.forge.pixpin.motor

import kotlin.random.Random as KRandom

/**
 * El generador pseudoaleatorio de rough.js, portado bit a bit.
 *
 * Es un Lehmer (Park–Miller) con multiplicador 48271 y módulo 2³¹−1. **La
 * fidelidad aquí no es capricho**: es lo que hace que un elemento con la misma
 * `seed` produzca en Android exactamente el mismo garabato que en
 * excalidraw.com. Si el generador difiere, un dibujo exportado y reabierto en
 * el navegador se ve distinto aunque el JSON sea idéntico.
 *
 * El original en JS es:
 * ```js
 * ((2 ** 31 - 1) & (this.seed = Math.imul(48271, this.seed))) / 2 ** 31
 * ```
 * `Math.imul` es una multiplicación con truncamiento a 32 bits con signo, que
 * es justo lo que hace `Int * Int` en Kotlin al desbordar. Por eso la línea se
 * traduce sola y no hace falta aritmética de 64 bits.
 */
class Rand(seed: Int) {

    private var state: Int = seed

    /** Siguiente valor en [0, 1). Con semilla 0 el original cae a `Math.random`. */
    fun next(): Double {
        if (state == 0) return KRandom.nextDouble()
        state = 48271 * state
        return (state and 0x7FFFFFFF).toDouble() / 2147483648.0
    }
}

/** Semilla nueva para un elemento. Mismo rango que `randomSeed()` del original. */
fun randomSeed(): Int = KRandom.nextInt(0, Int.MAX_VALUE)

/**
 * Id de elemento.
 *
 * Excalidraw usa nanoid de 21 caracteres. Se replica el alfabeto y la longitud
 * para que los ids generados aquí no desentonen ni colisionen al mezclar
 * escenas hechas en los dos sitios.
 */
fun randomId(): String {
    val alphabet = "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict"
    return buildString(21) {
        repeat(21) { append(alphabet[KRandom.nextInt(alphabet.length)]) }
    }
}

/**
 * Nonce de versión.
 *
 * En Excalidraw sirve para resolver conflictos de edición concurrente. Aquí no
 * hay colaboración, pero se mantiene porque va en el JSON y porque el borrado
 * de caché de render lo usa para saber si un elemento cambió de verdad.
 */
fun randomVersionNonce(): Int = KRandom.nextInt()
