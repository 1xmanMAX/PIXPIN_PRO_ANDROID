package com.forge.pixpin.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **Un pin parado no debe despertar la CPU.**
 *
 * El bucle que refresca la hora era un `while (true)` con espera fija que no
 * miraba si algo estaba corriendo: un temporizador parado en una esquina de la
 * pantalla latía cuatro veces por segundo para siempre, y el reloj —que enseña
 * `HH:MM`— se refrescaba doscientas cuarenta veces por cada minuto que cambia.
 *
 * Esto es lo que se puede comprobar de eso sin un móvil delante.
 */
class RelojDelPinTest {

    @Test
    fun `el cronometro parado no se refresca`() {
        val w = WidgetState(stopwatch = true, runningSince = null, accumulatedMs = 5_000)
        assertNull(esperaDelReloj(w, 1_000_000L))
    }

    @Test
    fun `el cronometro en marcha va con decimas`() {
        val w = WidgetState(stopwatch = true, runningSince = 1_000L)
        assertEquals(60L, esperaDelReloj(w, 2_000L))
    }

    @Test
    fun `la cuenta atras se refresca mientras queda tiempo`() {
        val w = WidgetState(timerEndsAt = 10_000L)
        assertEquals(250L, esperaDelReloj(w, 5_000L))
    }

    /** Y **para**: llegada a cero, no hay nada más que enseñar. */
    @Test
    fun `la cuenta atras terminada deja de refrescarse`() {
        val w = WidgetState(timerEndsAt = 10_000L)
        assertNull(esperaDelReloj(w, 10_000L))
        assertNull(esperaDelReloj(w, 12_000L))
    }

    /**
     * El reloj duerme **justo hasta el minuto siguiente**.
     *
     * Ni antes —sería despertar para no cambiar nada— ni después, que se vería
     * la hora atrasada.
     */
    @Test
    fun `el reloj duerme lo que falta para el minuto`() {
        // 90 s: faltan 30 para el siguiente minuto.
        assertEquals(30_000L, esperaDelReloj(WidgetState(), 90_000L))
        // Justo en el minuto: un minuto entero por delante.
        assertEquals(60_000L, esperaDelReloj(WidgetState(), 120_000L))
        // Un milisegundo antes: se despierta para ese único milisegundo.
        assertEquals(1L, esperaDelReloj(WidgetState(), 119_999L))
    }

    /** Nunca una espera de cero: sería un bucle cerrado comiéndose un núcleo. */
    @Test
    fun `ninguna espera es cero`() {
        for (t in 0L until 60_000L step 997L) {
            val e = esperaDelReloj(WidgetState(), t)!!
            assert(e in 1L..60_000L) { "espera fuera de rango en $t: $e" }
        }
    }
}
