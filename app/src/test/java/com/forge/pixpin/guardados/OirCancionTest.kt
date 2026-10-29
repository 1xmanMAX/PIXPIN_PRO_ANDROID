package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Test

/** **Una canción oída sin anotaciones** (29-oct-2026, 147492df del PC): «[Música]», «♪» y compañía fuera; la letra, tal cual. */
class OirCancionTest {

    @Test
    fun las_anotaciones_y_las_notas_no_son_letra() {
        assertEquals("", OirCancion.sinAnotaciones("[Música]"))
        assertEquals("", OirCancion.sinAnotaciones(" ♪♪ "))
        assertEquals("", OirCancion.sinAnotaciones("(aplausos) [Music] ♫"))
        assertEquals("bésame mucho", OirCancion.sinAnotaciones("♪ bésame mucho ♪"))
        assertEquals("como si fuera esta noche", OirCancion.sinAnotaciones("[Música] como si fuera (coro) esta noche"))
        assertEquals("", OirCancion.sinAnotaciones("- * #"))
    }

    @Test
    fun las_palabras_se_quedan_como_estan() {
        assertEquals("I can't stop, e-mail.", OirCancion.sinAnotaciones("I can't stop, e-mail."))
        assertEquals("¿Qué tal? 1, 2, 3", OirCancion.sinAnotaciones("¿Qué tal? 1, 2, 3"))
    }
}
