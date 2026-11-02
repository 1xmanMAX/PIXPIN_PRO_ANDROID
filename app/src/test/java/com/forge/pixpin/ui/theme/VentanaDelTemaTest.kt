package com.forge.pixpin.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **La ventana, del color del tema** (2-nov-2026). Ver [pintarLaVentana].
 *
 * La causa de «negro con letras negras» del modo día: la ventana es negra (`values/themes.xml`)
 * y las pantallas que no se pintan su fondo la dejaban ver con la tinta oscura del esquema
 * claro encima. De día la ventana tiene que quedar del papel claro y con los iconos de las
 * barras oscuros; de noche, negra como siempre y con los iconos claros.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VentanaDelTemaTest {

    private fun fondoDe(a: Activity): Int? = (a.window.decorView.background as? ColorDrawable)?.color

    @Test
    fun deDiaLaVentanaEsDelPapelClaro() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        pintarLaVentana(a.window, claro = true, fondo = LightColors.background)
        assertEquals(LightColors.background.toArgb(), fondoDe(a))
        val barras = WindowCompat.getInsetsController(a.window, a.window.decorView)
        assertTrue(barras.isAppearanceLightStatusBars)
        assertTrue(barras.isAppearanceLightNavigationBars)
    }

    @Test
    fun deNocheVuelveAlNegro() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        pintarLaVentana(a.window, claro = true, fondo = LightColors.background)
        pintarLaVentana(a.window, claro = false, fondo = DarkColors.background)
        assertEquals(android.graphics.Color.BLACK, fondoDe(a))
        val barras = WindowCompat.getInsetsController(a.window, a.window.decorView)
        assertFalse(barras.isAppearanceLightStatusBars)
        assertFalse(barras.isAppearanceLightNavigationBars)
    }

    @Test
    fun unFondoTranslucidoSalePintadoOpaco() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        pintarLaVentana(a.window, claro = true, fondo = LightColors.background.copy(alpha = 0.3f))
        assertEquals(0xFF, (fondoDe(a) ?: 0) ushr 24)
    }
}
