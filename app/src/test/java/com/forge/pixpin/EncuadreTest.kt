package com.forge.pixpin

import com.forge.pixpin.ui.Encuadre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Acercar como un lienzo** (25-oct-2026): las pruebas de `pixpin_ui::encuadre` del PC (8d7fe93),
 * con la misma caja y las mismas imágenes. La rueda del PC (`delta` de 120 en 120) es aquí
 * [Encuadre.acercar] con su factor, `1.25^(delta/120)`.
 */
class EncuadreTest {

    private val caja = Encuadre.Caja(100f, 50f, 800f, 450f)
    private fun cerca(a: Float, b: Float) = Math.abs(a - b) < 0.01f
    private fun rueda(e: Encuadre, w: Float, h: Float, delta: Int, x: Float, y: Float) =
        e.acercar(caja, w, h, Math.pow(Encuadre.PASO.toDouble(), delta / 120.0).toFloat(), x, y)

    @Test fun al_abrir_cabe_entera_y_centrada() {
        val e = Encuadre()
        assertEquals(Encuadre.Caja(275f, 50f, 450f, 450f), e.destino(caja, 1000f, 1000f))
        assertTrue(e.encajado)
        assertEquals(45, e.porCiento(caja, 1000f, 1000f))
        // Caso negativo: una imagen vacía se queda con la caja.
        assertEquals(caja, Encuadre.encajada(caja, 0f, 10f))
    }

    @Test fun el_pellizco_acerca_hacia_los_dedos() {
        var e = Encuadre()
        val antes = e.aLaImagen(caja, 1600f, 900f, 300f, 120f)!!
        e = rueda(e, 1600f, 900f, 240, 300f, 120f)
        assertTrue("${e.aumento}", e.aumento > 1.5f && e.aumento < 1.6f)
        val despues = e.aLaImagen(caja, 1600f, 900f, 300f, 120f)!!
        assertTrue(cerca(antes.first, despues.first) && cerca(antes.second, despues.second))
        // Y alejar del todo vuelve a encajarla.
        e = rueda(e, 1600f, 900f, -2400, 300f, 120f)
        assertTrue("$e", e.encajado)
    }

    @Test fun arrastrar_la_mueve_sin_dejar_hueco() {
        var e = Encuadre().acercar(caja, 1600f, 900f, 2f, 500f, 275f)
        e = e.mover(caja, 1600f, 900f, 5_000f, 0f)
        var d = e.destino(caja, 1600f, 900f)
        assertTrue("$d", cerca(d.x, caja.x))
        e = e.mover(caja, 1600f, 900f, -10_000f, 0f)
        d = e.destino(caja, 1600f, 900f)
        assertTrue("$d", cerca(d.x + d.ancho, caja.x + caja.ancho))
        // Caso negativo: encajada no se mueve (no hay nada fuera que ver).
        val quieta = Encuadre().mover(caja, 1600f, 900f, 300f, 200f)
        assertTrue(quieta.encajado)
    }

    @Test fun no_pasa_del_maximo_ni_baja_de_encajada() {
        var e = Encuadre()
        repeat(100) { e = rueda(e, 640f, 360f, 120, 500f, 275f) }
        assertTrue(cerca(e.aumento, Encuadre.AUMENTO_MAXIMO))
        e = e.acercar(caja, 640f, 360f, 0.0001f, 500f, 275f)
        assertTrue(cerca(e.aumento, 1f))
        // Fuera de la imagen no hay punto de la imagen.
        assertNull(e.aLaImagen(caja, 640f, 360f, 0f, 0f))
        // Los botones: un paso acerca un 25 % hacia el centro, y vuelta.
        val un = Encuadre().paso(caja, 640f, 360f, 1)
        assertTrue(cerca(un.aumento, 1.25f))
        assertTrue(un.paso(caja, 640f, 360f, -1).encajado)
    }
}
