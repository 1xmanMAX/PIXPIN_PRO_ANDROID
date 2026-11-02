package com.forge.pixpin.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

/** **Elegir el texto de una hoja** (2-nov-2026): las cajas de cada renglón y su paso a la tinta. */
class ElegirTextoDeLaHojaTest {
    @Test
    fun `las cajas del mismo renglon se juntan y las de otro no`() {
        val j = ElegirTextoDeLaHoja.juntarPorRenglon(
            listOf(floatArrayOf(10f, 100f, 40f, 112f), floatArrayOf(45f, 101f, 90f, 113f), floatArrayOf(10f, 120f, 60f, 132f))
        )
        assertEquals(2, j.size)
        assertEquals(listOf(10f, 100f, 90f, 113f), j[0].toList())
        assertEquals(listOf(10f, 120f, 60f, 132f), j[1].toList())
        // Caso negativo: cajas vacías no cuentan.
        assertEquals(0, ElegirTextoDeLaHoja.juntarPorRenglon(listOf(floatArrayOf(5f, 5f, 5f, 9f))).size)
    }

    @Test
    fun `a la tinta de la hoja de 1400 de ancho`() {
        val c = ElegirTextoDeLaHoja.aTinta(listOf(floatArrayOf(0f, 10f, 595f, 20f)), 595.0).single()
        assertEquals(1400.0, c.x1, 1e-6)
        assertEquals(10.0 * 1400 / 595, c.y0, 1e-4)
    }
}
