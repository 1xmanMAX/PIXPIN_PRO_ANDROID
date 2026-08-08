package com.forge.pixpin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PinBlue = Color(0xFF29B8DB)

private val DarkColors = darkColorScheme(
    primary = PinBlue,
    secondary = Color(0xFF4FC3F7),
    tertiary = Color(0xFFFFB74D)
)

private val LightColors = lightColorScheme(
    primary = PinBlue,
    secondary = Color(0xFF0288D1),
    tertiary = Color(0xFFF57C00)
)

/**
 * El esquema oscuro **con el negro apagado**.
 *
 * No basta con pintar el lienzo de negro: alrededor están la barra de
 * herramientas, el panel de estilos y las islas de botones, y todo eso son
 * `Surface` de Material, que en oscuro son grises. Con el lienzo negro y el
 * cromo gris, lo que se ve es un rectángulo negro rodeado de grises — que no es
 * ni lo uno ni lo otro.
 *
 * Aquí se bajan a negro los fondos y se deja un gris muy oscuro para lo que
 * tiene que despegarse del fondo (un panel flotante sobre el lienzo): a negro
 * puro contra negro puro, un panel no se ve y no habría forma de saber dónde
 * acaba.
 */
private val OledColors = darkColorScheme(
    primary = PinBlue,
    secondary = Color(0xFF4FC3F7),
    tertiary = Color(0xFFFFB74D),
    background = Color.Black,
    onBackground = Color(0xFFE6E6E6),
    surface = Color.Black,
    onSurface = Color(0xFFE6E6E6),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF121212),
    surfaceContainerHighest = Color(0xFF161616),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFFBDBDBD)
)

@Composable
fun PixPinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Negro de verdad en vez del gris oscuro de Material.
     *
     * Solo hace algo con [darkTheme] puesto: en claro no hay nada que apagar.
     */
    oled: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = when {
            darkTheme && oled -> OledColors
            darkTheme -> DarkColors
            else -> LightColors
        },
        content = content
    )
}
