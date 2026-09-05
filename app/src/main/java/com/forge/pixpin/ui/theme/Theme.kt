package com.forge.pixpin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.data.ModoNoche

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

/**
 * **El modo noche, hecho a partir de lo que se sabe de mirar colores a oscuras.**
 *
 * El de antes dejaba a Material rellenar los fondos, y Material los rellena con su gris
 * lila (`#141218`, `#E6E0E9`): una aplicación cian con la sombra de otro color debajo, y
 * los acentos de día puestos tal cual sobre negro. Se veía **pálido**, y no es casualidad,
 * es percepción:
 *
 * - *Contraste simultáneo*: el mismo cian que se ve vivo sobre blanco se ve apagado sobre
 *   casi negro. Para que se perciba igual hay que **subirle la saturación un 15–25 %** y
 *   aclararlo un poco (ColorArchive, «Why dark mode colors need more saturation», 2026;
 *   es también lo que hace Apple con sus colores de sistema: el azul `#007AFF` de día es
 *   `#0A84FF` de noche). Por eso el primario de noche es `#5CD3F0` y no `#29B8DB`.
 * - *Efecto Bartleson–Breneman*: con el entorno oscuro el rango de valores se comprime y
 *   los tonos oscuros parecen más claros de lo que son. De ahí que los fondos vayan en
 *   **escalones de luminosidad claros y separados** —base, contenedor bajo, contenedor,
 *   alto, más alto— en vez de en el gris único de antes: es lo que hace que un panel se
 *   despegue de lo que tiene detrás.
 * - *Helmholtz–Kohlrausch*: un color saturado parece más luminoso que un gris de la misma
 *   luminancia, y más aún a oscuras. Por eso los **contenedores grandes** (fondos de
 *   burbuja, fichas) van con la saturación **bajada** —un fondo entero en cian vivo
 *   vibra— y los acentos pequeños (iconos, texto, el botón principal) con la saturación
 *   **subida**. El estudio de PLOS One sobre logotipos en modo oscuro (2025) mide lo
 *   mismo: los colores claros se bajan unos 8 puntos de L*, los oscuros se suben unos 12,
 *   y cuanto más cromático el color más croma se le quita.
 * - Los fondos **no son grises neutros sino del tono de la marca** con un croma
 *   mínimo (un azul-verde muy apagado): así toda la pantalla es de una sola familia, que
 *   es lo que Material 3 llama «surface tint» y lo que se hace a mano aquí.
 * - El texto **no es blanco puro**: `#E2E9EB` sobre `#0E1416` da 15 a 1, de sobra, sin el
 *   deslumbramiento del blanco sobre negro que cansa a oscuras.
 */
private val NocheBase = Color(0xFF0E1416)
private val NocheBajo = Color(0xFF141B1D)
private val NocheMedio = Color(0xFF192123)
private val NocheAlto = Color(0xFF212B2E)
private val NocheMasAlto = Color(0xFF2B3639)
private val NocheTexto = Color(0xFFE2E9EB)
private val NocheTextoSuave = Color(0xFFB7C3C7)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CD3F0),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF0C4E5C),
    onPrimaryContainer = Color(0xFFC6F1FB),
    inversePrimary = Color(0xFF0F7F98),
    secondary = Color(0xFF7ACDF7),
    onSecondary = Color(0xFF00344A),
    secondaryContainer = Color(0xFF184A5A),
    onSecondaryContainer = Color(0xFFD2ECF5),
    tertiary = Color(0xFFFFC46B),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF6A4400),
    onTertiaryContainer = Color(0xFFFFE0B5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = NocheBase,
    onBackground = NocheTexto,
    surface = NocheBase,
    onSurface = NocheTexto,
    surfaceDim = Color(0xFF0B1113),
    surfaceBright = Color(0xFF33403F),
    surfaceContainerLowest = Color(0xFF090E10),
    surfaceContainerLow = NocheBajo,
    surfaceContainer = NocheMedio,
    surfaceContainerHigh = NocheAlto,
    surfaceContainerHighest = NocheMasAlto,
    surfaceVariant = NocheAlto,
    onSurfaceVariant = NocheTextoSuave,
    inverseSurface = NocheTexto,
    inverseOnSurface = Color(0xFF1C2527),
    outline = Color(0xFF7F8E93),
    outlineVariant = Color(0xFF344145),
    scrim = Color.Black
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
 * acaba. Los acentos son los del modo noche, por lo mismo que allí.
 */
private val OledColors = DarkColors.copy(
    background = Color.Black,
    onBackground = Color(0xFFE6E6E6),
    surface = Color.Black,
    onSurface = Color(0xFFE6E6E6),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF060606),
    surfaceContainer = Color(0xFF0C0C0C),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFF2A2A2A)
)

/** Si la interfaz está en modo noche. Lo pone [PixPinTheme]; fuera de él, lo que diga el sistema. */
val LocalDeNoche = compositionLocalOf { false }

/** Si lo que se ve ahora está en modo noche: la decisión del tema, no la del sistema. */
@Composable
fun deNoche(): Boolean = LocalDeNoche.current

@Composable
fun PixPinTheme(
    /**
     * Forzar claro u oscuro. Sin decirlo, decide el ajuste «Modo noche» del usuario:
     * el sistema, siempre claro, siempre oscuro, o automático por la hora y la luz de
     * la habitación. Ver [ModoNoche] y [deNocheSegun].
     */
    darkTheme: Boolean? = null,
    /**
     * Negro de verdad en vez del gris oscuro de Material.
     *
     * Solo hace algo con [darkTheme] puesto: en claro no hay nada que apagar. Sin
     * decirlo, lo que tenga el ajuste.
     */
    oled: Boolean? = null,
    content: @Composable () -> Unit
) {
    val app = LocalContext.current.applicationContext as? PixPinApp
    val ajustes by (app?.settings?.settings ?: kotlinx.coroutines.flow.flowOf(null))
        .collectAsState(initial = app?.ajustes)
    val oscuro = darkTheme ?: deNocheSegun(ajustes?.modoNoche ?: ModoNoche.SISTEMA)
    val negro = oled ?: (ajustes?.oledNegro ?: false)
    CompositionLocalProvider(LocalDeNoche provides oscuro) {
        MaterialTheme(
            colorScheme = when {
                oscuro && negro -> OledColors
                oscuro -> DarkColors
                else -> LightColors
            },
            content = content
        )
    }
}

/**
 * Qué toca según el ajuste. Con [ModoNoche.AUTO] es de noche por la hora —de ocho de la
 * tarde a siete de la mañana— **o** porque la habitación está a oscuras, que lo dice el
 * sensor de luz del aparato. Ver [rememberAOscuras].
 */
@Composable
private fun deNocheSegun(modo: ModoNoche): Boolean = when (modo) {
    ModoNoche.SISTEMA -> isSystemInDarkTheme()
    ModoNoche.CLARO -> false
    ModoNoche.OSCURO -> true
    ModoNoche.AUTO -> rememberAOscuras()
}
