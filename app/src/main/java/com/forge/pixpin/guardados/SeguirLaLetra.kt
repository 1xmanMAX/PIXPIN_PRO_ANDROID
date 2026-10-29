package com.forge.pixpin.guardados

/**
 * **Si la vista de la letra sigue a la canción** (29-oct-2026, `sigue_a_la_cancion` y
 * `LETRA_A_MANO` de `ventana_chat.rs` del PC). Puro, con sus pruebas en `SeguirLaLetraTest`.
 *
 * Mientras suena, la vista sigue a la línea que suena; **moverla a mano** (arrastrar o la rueda) lo
 * para [A_MANO_MS] o hasta tocar una línea, y luego vuelve sola. Parada, lo movido a mano se queda
 * donde se dejó (no hay nada que seguir).
 *
 * *El fallo que había* (el del PC era que, con tiempos, la vista se calculaba en cada fotograma desde
 * la línea que suena y lo que movía la rueda no se leía nunca): aquí la lista de la letra volvía a la
 * línea que suena **en cuanto cambiaba de línea**, aunque se acabara de mover a mano, y sin «Seguir»
 * no la seguía nunca. Ahora mover a mano pausa el seguir y nada lo pisa mientras dura.
 */
object SeguirLaLetra {

    /** Lo que se para «seguir a la canción» tras mover la letra a mano. */
    const val A_MANO_MS = 6_000L

    /**
     * **Si sigue a la canción** en [ahoraMs]: siempre si no se movió a mano ([aManoMs] null); si se
     * movió, solo sonando y pasados [A_MANO_MS].
     */
    fun sigue(aManoMs: Long?, ahoraMs: Long, sonando: Boolean): Boolean =
        aManoMs == null || (sonando && ahoraMs - aManoMs >= A_MANO_MS)

    /**
     * **Cuánto falta** para volver a seguir (ms), o null si no va a volver solo (parada, sin mover
     * a mano no hay nada que esperar: devuelve 0).
     */
    fun falta(aManoMs: Long?, ahoraMs: Long, sonando: Boolean): Long? = when {
        aManoMs == null -> 0L
        !sonando -> null
        else -> (A_MANO_MS - (ahoraMs - aManoMs)).coerceAtLeast(0L)
    }
}
