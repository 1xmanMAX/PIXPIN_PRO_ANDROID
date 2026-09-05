package com.forge.pixpin.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La hora del modo noche automático: de ocho de la tarde a siete de la mañana. */
class AOscurasTest {
    @Test
    fun `de noche por la hora`() {
        assertTrue(esHoraDeNoche(20)); assertTrue(esHoraDeNoche(23)); assertTrue(esHoraDeNoche(0)); assertTrue(esHoraDeNoche(6))
        assertFalse(esHoraDeNoche(7)); assertFalse(esHoraDeNoche(12)); assertFalse(esHoraDeNoche(19))
    }
}
