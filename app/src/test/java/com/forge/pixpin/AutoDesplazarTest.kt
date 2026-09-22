package com.forge.pixpin

import com.forge.pixpin.motor.AutoDesplazar
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDesplazarTest {
    @Test
    fun `la velocidad en palabras por minuto se vuelve pixeles por segundo`() {
        // 6000 palabras en 60000 px: 10 px por palabra; a 240 palabras/min, 4 por segundo → 40 px/s.
        assertEquals(40f, AutoDesplazar.pixelesPorSegundo(240, 6000, 60000f), 0.001f)
        // Con la letra al doble el documento mide el doble y va el doble de rápido: se lee igual.
        assertEquals(80f, AutoDesplazar.pixelesPorSegundo(240, 6000, 120000f), 0.001f)
        assertEquals(0f, AutoDesplazar.pixelesPorSegundo(240, 0, 60000f))
    }

    @Test
    fun `lo que queda baja con lo leido y con la velocidad`() {
        assertEquals(25f, AutoDesplazar.minutosQueQuedan(6000, 0f, 240), 0.001f)
        assertEquals(12.5f, AutoDesplazar.minutosQueQuedan(6000, 0.5f, 240), 0.001f)
        assertEquals(0f, AutoDesplazar.minutosQueQuedan(6000, 1f, 240), 0.001f)
        assertEquals(10f, AutoDesplazar.minutosQueQuedan(6000, 0.5f, 300), 0.001f)
    }

    @Test
    fun `los rotulos se leen bien`() {
        assertEquals("menos de 1 min", AutoDesplazar.rotulo(0.4f))
        assertEquals("12 min", AutoDesplazar.rotulo(11.2f))
        assertEquals("1 h 05 min", AutoDesplazar.rotulo(65f))
        assertEquals("terminado", AutoDesplazar.rotulo(0f))
        assertEquals("18:40", AutoDesplazar.horaDeTerminar(18 * 60 + 28, 11.2f))
        assertEquals("0:10", AutoDesplazar.horaDeTerminar(23 * 60 + 50, 20f))
    }

    @Test
    fun `los botones van de paso en paso sin salirse`() {
        assertEquals(240, AutoDesplazar.otra(230, 1))
        assertEquals(220, AutoDesplazar.otra(230, -1))
        assertEquals(200, AutoDesplazar.otra(220, -1))
        assertEquals(AutoDesplazar.MINIMO, AutoDesplazar.otra(AutoDesplazar.MINIMO, -1))
        assertEquals(AutoDesplazar.MAXIMO, AutoDesplazar.otra(AutoDesplazar.MAXIMO, 1))
    }
}
