package com.forge.pixpin.floating

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresión: la bola desaparecía y no volvía ni pulsando «Comenzar».
 *
 * La causa era que el código solo distinguía dos estados —hay ventana / no hay
 * ventana— y el que fallaba era un tercero: la ventana puesta pero con el
 * contenido oculto, que es como queda si una captura se va al traste sin
 * restaurar los overlays.
 */
class BallStateTest {

    @Test
    fun `sin ventana hay que crearla`() {
        assertEquals(
            BallRecovery.CREAR,
            BallState.recoveryFor(windowExists = false, contentVisible = false)
        )
    }

    @Test
    fun `con la ventana visible no hay nada que hacer`() {
        assertEquals(
            BallRecovery.NADA,
            BallState.recoveryFor(windowExists = true, contentVisible = true)
        )
    }

    /** El caso del fallo: existe pero está en GONE. Antes se daba por buena. */
    @Test
    fun `con la ventana puesta pero oculta hay que volver a ensenarla`() {
        assertEquals(
            BallRecovery.VOLVER_A_ENSENAR,
            BallState.recoveryFor(windowExists = true, contentVisible = false)
        )
    }

    /**
     * Sin ventana da igual lo que diga la visibilidad del contenido: no hay
     * contenido del que hablar.
     */
    @Test
    fun `sin ventana la visibilidad del contenido es irrelevante`() {
        assertEquals(
            BallRecovery.CREAR,
            BallState.recoveryFor(windowExists = false, contentVisible = true)
        )
    }
}
