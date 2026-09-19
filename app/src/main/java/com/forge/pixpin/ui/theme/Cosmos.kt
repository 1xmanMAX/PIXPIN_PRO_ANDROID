package com.forge.pixpin.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.random.Random

/**
 * **El tema Cosmos** (17-sep-2026): el aspecto del sistema solar de proyectos, llevado a toda la
 * aplicación. Lo pidió el usuario: «esa misma interfaz, esos colores, para toda la app; sin el
 * fondo gris, con el fondo difuminado».
 *
 * - **Fondo**: azul noche con dos nebulosas y estrellas quietas ([FondoCosmico]), detrás de las
 *   pantallas opacas. Por eso aquí `background` es **transparente**: los `Scaffold` y las
 *   `Surface(color = background)` dejan ver el cielo sin tocar cada pantalla.
 * - **Tarjetas** (`surfaceContainerHighest`, lo que usa `Card`): vidrio azul translúcido. Sin
 *   desenfoque a propósito, por lo mismo que en el chat ([pixpin-cabecera-telegram]).
 * - **Diálogos, menús y barras**: azul opaco, que encima de un dibujo o de otra app un panel
 *   transparente no se lee.
 * - **Acento dorado** (`#FFD27A`, el del modo elegido en la galaxia) con texto casi negro
 *   encima (13:1); cian y rosa de los soles como secundario y terciario.
 */
val CosmosBase = Color(0xFF0B0F24)
private val CosmosBajo = Color(0xFF10152E)
private val CosmosMedio = Color(0xFF151B3A)
private val CosmosAlto = Color(0xFF1C2347)
private val CosmosMasAlto = Color(0xFF242C55)
private val CosmosTexto = Color(0xFFE8EAF6)
private val CosmosTextoSuave = Color(0xFFB4B9D6)
val CosmosDorado = Color(0xFFFFD27A)

/** El esquema, con el fondo transparente si detrás está el cielo y opaco si no. */
internal fun esquemaCosmos(conCielo: Boolean) = darkColorScheme(
    primary = CosmosDorado,
    onPrimary = Color(0xFF231A00),
    primaryContainer = Color(0xFF4A3A12),
    onPrimaryContainer = Color(0xFFFFE8B8),
    inversePrimary = Color(0xFF8A6A1E),
    secondary = Color(0xFF6FD3FF),
    onSecondary = Color(0xFF00334A),
    secondaryContainer = Color(0xFF1E3A5C),
    onSecondaryContainer = Color(0xFFD4EEFF),
    tertiary = Color(0xFFFF8FB1),
    onTertiary = Color(0xFF4A0F24),
    tertiaryContainer = Color(0xFF5A2440),
    onTertiaryContainer = Color(0xFFFFD9E4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = if (conCielo) Color.Transparent else CosmosBase,
    onBackground = CosmosTexto,
    surface = CosmosBase,
    onSurface = CosmosTexto,
    surfaceDim = Color(0xFF080B1A),
    surfaceBright = Color(0xFF2E3766),
    surfaceContainerLowest = Color(0xFF070A18),
    surfaceContainerLow = CosmosBajo,
    surfaceContainer = CosmosMedio,
    surfaceContainerHigh = CosmosAlto,
    // El de las tarjetas: vidrio, si hay cielo que ver detrás.
    surfaceContainerHighest = if (conCielo) Color(0xC21C2350) else CosmosMasAlto,
    surfaceVariant = CosmosMasAlto,
    onSurfaceVariant = CosmosTextoSuave,
    surfaceTint = CosmosDorado,
    inverseSurface = CosmosTexto,
    inverseOnSurface = Color(0xFF1A1F3A),
    outline = Color(0xFF7D84AE),
    outlineVariant = Color(0xFF343C6A),
    scrim = Color.Black
)

/** Si la interfaz lleva el tema Cosmos. */
val LocalCosmos = compositionLocalOf { false }

/**
 * **El cielo de fondo**, quieto: degradado, dos nebulosas y estrellas.
 *
 * Va en su propia capa (`graphicsLayer`), así que se graba una vez y se reutiliza mientras no
 * cambie de tamaño: desplazar una lista encima no lo vuelve a pintar.
 */
@Composable
fun FondoCosmico(modifier: Modifier = Modifier) {
    Canvas(modifier.graphicsLayer()) {
        val w = size.width
        val h = size.height
        drawRect(Brush.radialGradient(listOf(Color(0xFF151B3F), Color(0xFF05060F)), Offset(w * 0.5f, h * 0.35f), maxOf(w, h) * 0.95f))
        drawCircle(
            Brush.radialGradient(listOf(Color(0x335B3FD9), Color.Transparent), Offset(w * 0.2f, h * 0.22f), w * 0.8f),
            radius = w * 0.8f, center = Offset(w * 0.2f, h * 0.22f)
        )
        drawCircle(
            Brush.radialGradient(listOf(Color(0x2A1FA2C9), Color.Transparent), Offset(w * 0.9f, h * 0.78f), w * 0.7f),
            radius = w * 0.7f, center = Offset(w * 0.9f, h * 0.78f)
        )
        val d = density
        for (k in ESTRELLAS.indices) {
            drawPoints(
                ESTRELLAS[k].map { Offset(it.x * w, it.y * h) }, PointMode.Points, BRILLOS[k],
                strokeWidth = (k + 1) * 1.1f * d, cap = StrokeCap.Round
            )
        }
    }
}

private val BRILLOS = listOf(Color(0x55FFFFFF), Color(0x88DCE6FF), Color(0xCCFFF4D6))

/** Posiciones de 0 a 1: tres tamaños, de muchas y tenues a pocas y brillantes. */
private val ESTRELLAS: List<List<Offset>> = Random(11).let { r ->
    listOf(160, 45, 12).map { n -> List(n) { Offset(r.nextFloat(), r.nextFloat()) } }
}
