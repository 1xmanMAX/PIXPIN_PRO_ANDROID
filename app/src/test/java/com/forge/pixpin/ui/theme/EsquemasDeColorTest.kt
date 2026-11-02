package com.forge.pixpin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Los esquemas de color, con la cuenta delante** (2-nov-2026).
 *
 * El usuario reportó el modo día «negro con letras negras». Parte de la culpa era la ventana
 * (ver [VentanaDelTemaTest]) y parte el esquema claro, que solo tenía los acentos: los fondos
 * eran los lila de fábrica de Material y el primario cian claro daba 2,3 a 1 como texto sobre
 * blanco. Esto fija que **cada tinta se lea sobre su fondo** —4,5 a 1, lo que pide WCAG AA
 * para texto normal— de día, de noche, en negro OLED y en Cosmos, y que el de día sea de verdad
 * claro y el de noche de verdad oscuro.
 */
class EsquemasDeColorTest {

    private fun luminancia(c: Color): Double {
        fun canal(v: Float): Double =
            if (v <= 0.04045f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * canal(c.red) + 0.7152 * canal(c.green) + 0.0722 * canal(c.blue)
    }

    /** El contraste de WCAG 2.x entre dos colores opacos: de 1 (iguales) a 21 (negro y blanco). */
    private fun contraste(a: Color, b: Color): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun laCuentaDeWcag() {
        assertEquals(21.0, contraste(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contraste(Color(0xFF29B8DB), Color(0xFF29B8DB)), 0.0001)
        // El primario de día de antes sobre blanco: lo que no se leía.
        assertTrue(contraste(Color(0xFF29B8DB), Color.White) < 2.5)
    }

    /** Los pares tinta/fondo que tienen que leerse, con su nombre para el mensaje de error. */
    private fun pares(e: ColorScheme, conFondo: Boolean = true): List<Triple<String, Color, Color>> {
        val fondos = listOf(
            "surface" to e.surface,
            "surfaceContainerLowest" to e.surfaceContainerLowest,
            "surfaceContainerLow" to e.surfaceContainerLow,
            "surfaceContainer" to e.surfaceContainer,
            "surfaceContainerHigh" to e.surfaceContainerHigh,
            "surfaceContainerHighest" to e.surfaceContainerHighest,
            "surfaceVariant" to e.surfaceVariant
        )
        val lista = mutableListOf<Triple<String, Color, Color>>()
        if (conFondo) lista += Triple("onBackground/background", e.onBackground, e.background)
        for ((nombre, fondo) in fondos) {
            lista += Triple("onSurface/$nombre", e.onSurface, fondo)
            lista += Triple("onSurfaceVariant/$nombre", e.onSurfaceVariant, fondo)
        }
        // Los botones de texto y los enlaces: el primario como letra sobre el papel.
        lista += Triple("primary/surface", e.primary, e.surface)
        lista += Triple("onPrimary/primary", e.onPrimary, e.primary)
        lista += Triple("onPrimaryContainer/primaryContainer", e.onPrimaryContainer, e.primaryContainer)
        lista += Triple("onSecondary/secondary", e.onSecondary, e.secondary)
        lista += Triple("onSecondaryContainer/secondaryContainer", e.onSecondaryContainer, e.secondaryContainer)
        lista += Triple("onTertiary/tertiary", e.onTertiary, e.tertiary)
        lista += Triple("onTertiaryContainer/tertiaryContainer", e.onTertiaryContainer, e.tertiaryContainer)
        lista += Triple("onError/error", e.onError, e.error)
        lista += Triple("onErrorContainer/errorContainer", e.onErrorContainer, e.errorContainer)
        lista += Triple("error/surface", e.error, e.surface)
        lista += Triple("inverseOnSurface/inverseSurface", e.inverseOnSurface, e.inverseSurface)
        return lista
    }

    private fun comprobar(nombre: String, e: ColorScheme, conFondo: Boolean = true) {
        val malos = pares(e, conFondo).mapNotNull { (par, tinta, fondo) ->
            val c = contraste(tinta, fondo)
            if (c < 4.5) "$par = ${"%.2f".format(c)}" else null
        }
        assertTrue("$nombre: pares por debajo de 4,5 a 1: $malos", malos.isEmpty())
    }

    @Test fun elDeDiaSeLee() = comprobar("día", LightColors)
    @Test fun elDeNocheSeLee() = comprobar("noche", DarkColors)
    @Test fun elNegroSeLee() = comprobar("OLED", OledColors)
    @Test fun elCosmosSeLee() = comprobar("Cosmos", esquemaCosmos(conCielo = false))

    @Test
    fun elDeDiaEsClaroYElDeNocheOscuro() {
        // Lo que se rompió: un fondo oscuro con la tinta del esquema claro. De día, papel claro y
        // tinta oscura en todo el esquema; de noche, al revés.
        for (fondo in listOf(LightColors.background, LightColors.surface, LightColors.surfaceContainerHighest)) {
            assertTrue("de día un fondo es oscuro: $fondo", luminancia(fondo) > 0.6)
        }
        assertTrue(luminancia(LightColors.onSurface) < 0.05)
        for (fondo in listOf(DarkColors.background, DarkColors.surface, OledColors.background)) {
            assertTrue("de noche un fondo es claro: $fondo", luminancia(fondo) < 0.05)
        }
        assertTrue(luminancia(DarkColors.onSurface) > 0.6)
    }

    @Test
    fun elDeDiaNoTieneLila() {
        // Los fondos de fábrica de Material 3 son gris lila; los de PinDesk, del tono de la marca:
        // ni un fondo claro con más rojo que verde.
        for (fondo in listOf(
            LightColors.background, LightColors.surfaceContainerLow, LightColors.surfaceContainer,
            LightColors.surfaceContainerHigh, LightColors.surfaceContainerHighest, LightColors.surfaceVariant
        )) {
            assertTrue("fondo lila: $fondo", fondo.red <= fondo.green)
        }
    }
}
