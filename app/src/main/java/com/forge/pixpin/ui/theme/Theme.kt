package com.forge.pixpin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PinBlue = Color(0xFF29B8DB)

/**
 * **Los contenedores, escritos a mano.**
 *
 * Poniendo solo `primary`, `secondary` y `tertiary`, Material rellena el resto del
 * esquema —`primaryContainer`, `secondaryContainer`, `surfaceVariant` y sus «on»— con su
 * paleta de fábrica, que es **lila**. El resultado era una aplicación de color cian con
 * la mitad de sus piezas en morado: los botones redondos de adjuntar, los círculos de
 * archivo y de nota de voz, la ficha de sección elegida y el fondo de las burbujas. Dos
 * familias de color en la misma pantalla y ninguna decisión detrás de la segunda.
 *
 * `onPrimary` tampoco vale el blanco por omisión: sobre este cian claro da un contraste
 * de 2,4 a 1, por debajo del mínimo de 3 a 1 que se le exige a un icono. Va un cian muy
 * oscuro, que además es el mismo que llevan los contenedores claros.
 */
private val CianMuyOscuro = Color(0xFF00323D)
private val CianClaro = Color(0xFFB8ECF7)
private val CianHondo = Color(0xFF004E60)

private val DarkColors = darkColorScheme(
    primary = PinBlue,
    onPrimary = CianMuyOscuro,
    primaryContainer = CianHondo,
    onPrimaryContainer = CianClaro,
    secondary = Color(0xFF4FC3F7),
    onSecondary = CianMuyOscuro,
    secondaryContainer = Color(0xFF14464F),
    onSecondaryContainer = Color(0xFFCDE8EF),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF614000),
    onTertiaryContainer = Color(0xFFFFDDB0),
    surfaceVariant = Color(0xFF3F484B),
    onSurfaceVariant = Color(0xFFBFC8CC)
)

private val LightColors = lightColorScheme(
    primary = PinBlue,
    onPrimary = CianMuyOscuro,
    primaryContainer = CianClaro,
    onPrimaryContainer = CianMuyOscuro,
    secondary = Color(0xFF0288D1),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8EF),
    onSecondaryContainer = Color(0xFF06282F),
    tertiary = Color(0xFFF57C00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF2A1700),
    surfaceVariant = Color(0xFFDCE7EB),
    onSurfaceVariant = Color(0xFF40484C)
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
