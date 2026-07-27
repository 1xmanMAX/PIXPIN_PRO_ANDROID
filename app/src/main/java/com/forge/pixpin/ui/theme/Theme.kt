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

@Composable
fun PixPinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
