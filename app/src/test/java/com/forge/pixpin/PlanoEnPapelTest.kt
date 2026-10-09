package com.forge.pixpin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Imprimir partes de un plano ([com.forge.pixpin.planos.ImprimirPlano]): los marcos y los colores en papel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanoEnPapelTest {
    @Test
    fun el_marco_se_ordena_y_en_papel_el_blanco_no_desaparece() {
        val m = com.forge.pixpin.planos.ImprimirPlano.Marco.entre(10.0, 5.0, 2.0, 8.0)
        assertEquals(2.0, m.x0, 0.0); assertEquals(5.0, m.y0, 0.0); assertEquals(8.0, m.ancho, 0.0); assertEquals(3.0, m.alto, 0.0)
        val papel = com.forge.pixpin.planos.ImprimirPlano::colorEnPapel
        // El color 7 (alfa 0) sale negro en papel blanco.
        assertEquals(android.graphics.Color.BLACK, papel(0x00ffffff))
        // Un amarillo puro se oscurece hasta leerse; un rojo se queda como está.
        val amarillo = papel(0xff00ffff.toInt())
        assertTrue(android.graphics.Color.red(amarillo) < 200 && android.graphics.Color.green(amarillo) < 200)
        assertEquals(android.graphics.Color.rgb(255, 0, 0), papel(0xff0000ff.toInt()))
    }
}
