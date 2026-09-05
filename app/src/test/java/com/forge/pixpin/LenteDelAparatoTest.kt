package com.forge.pixpin.croquis3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Lo que abarca la cámara del aparato en la pantalla.** Ver [LenteDelAparato].
 *
 * Los números son los de un teléfono corriente: sensor de 6,4 × 4,8 mm y focal de
 * 4,3 mm, que abre unos 73 grados a lo largo y 58 a lo ancho.
 */
class LenteDelAparatoTest {

    private val sensor = 6.4 to 4.8
    private val pixeles = 4000 to 3000
    private val focal = 4.3

    private fun grados(radianes: Double) = Math.toDegrees(radianes)

    /** En vertical, la vista previa de 4:3 cabe entera de alto: se ve el lado largo del sensor. */
    @Test
    fun `en vertical se ve el lado largo del sensor`() {
        val campo = LenteDelAparato.campoVertical(
            sensor, pixeles, pixeles, focal, 1600 to 1200, 90, 1080 to 2400
        )!!
        assertEquals(73.3, grados(campo), 0.2)
    }

    /** En apaisado la pantalla es más alargada que la previa: se recorta arriba y abajo. */
    @Test
    fun `en apaisado se recorta arriba y abajo`() {
        val campo = LenteDelAparato.campoVertical(
            sensor, pixeles, pixeles, focal, 1600 to 1200, 0, 2400 to 1080
        )!!
        // El alto visto es el ancho de la previa entre la proporción de la pantalla:
        // 6,4 / 2,22 = 2,88 mm.
        assertEquals(37.0, grados(campo), 0.3)
    }

    /** Un flujo de 16:9 sobre un sensor de 4:3 recorta el sensor por arriba y abajo. */
    @Test
    fun `un flujo panoramico recorta el sensor`() {
        val vertical = LenteDelAparato.campoVertical(
            sensor, pixeles, pixeles, focal, 1920 to 1080, 90, 1080 to 2400
        )!!
        // En vertical sigue viéndose el lado largo entero: lo recortado es el corto.
        assertEquals(73.3, grados(vertical), 0.2)
        val apaisado = LenteDelAparato.campoVertical(
            sensor, pixeles, pixeles, focal, 1920 to 1080, 0, 1920 to 1080
        )!!
        // Pantalla y flujo de la misma proporción: se ve el alto recortado del sensor,
        // 6,4 · 9/16 = 3,6 mm.
        assertEquals(45.4, grados(apaisado), 0.3)
    }

    /** Solo cuenta la parte activa del sensor. */
    @Test
    fun `la parte inactiva del sensor no abarca`() {
        val entero = LenteDelAparato.campoVertical(
            sensor, pixeles, pixeles, focal, 1600 to 1200, 90, 1080 to 2400
        )!!
        val recortado = LenteDelAparato.campoVertical(
            sensor, pixeles, 2000 to 1500, focal, 1600 to 1200, 90, 1080 to 2400
        )!!
        assertTrue(recortado < entero)
        assertEquals(41.0, grados(recortado), 0.3)
    }

    /** Con cualquier dato roto no se inventa nada. */
    @Test
    fun `sin datos no hay campo`() {
        assertNull(LenteDelAparato.campoVertical(0.0 to 4.8, pixeles, pixeles, focal, 1600 to 1200, 90, 1080 to 2400))
        assertNull(LenteDelAparato.campoVertical(sensor, pixeles, pixeles, 0.0, 1600 to 1200, 90, 1080 to 2400))
        assertNull(LenteDelAparato.campoVertical(sensor, pixeles, pixeles, focal, 0 to 0, 90, 1080 to 2400))
        assertNull(LenteDelAparato.campoVertical(sensor, pixeles, pixeles, focal, 1600 to 1200, 90, 0 to 0))
    }
}
