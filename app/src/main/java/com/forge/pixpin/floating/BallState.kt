package com.forge.pixpin.floating

/** Qué hace falta para que la bola vuelva a verse. */
enum class BallRecovery { NADA, CREAR, VOLVER_A_ENSENAR }

/**
 * El estado de la bola tiene TRES casos, no dos.
 *
 * El tercero —ventana puesta pero con el contenido en GONE— es como queda si una
 * captura se va al traste sin restaurar los overlays: el servicio de captura
 * muere a media captura, cancela la corrutina que iba a volver a enseñarlos y
 * nadie deshace el ocultado.
 *
 * Mientras el código solo miró «¿existe la ventana?», ese caso se daba por
 * bueno: la bola había desaparecido de la pantalla y ni «Comenzar» ni reiniciar
 * el servicio la traían de vuelta, porque ambos preguntaban por la existencia de
 * la ventana y no por si se veía.
 */
object BallState {

    fun recoveryFor(windowExists: Boolean, contentVisible: Boolean): BallRecovery = when {
        !windowExists -> BallRecovery.CREAR
        !contentVisible -> BallRecovery.VOLVER_A_ENSENAR
        else -> BallRecovery.NADA
    }
}
