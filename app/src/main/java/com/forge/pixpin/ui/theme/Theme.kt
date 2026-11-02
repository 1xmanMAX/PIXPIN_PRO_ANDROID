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
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
 *
 * (2-nov-2026) El primario de día ya no es este cian claro sino [CianHondo], que se lee
 * como texto, con blanco encima; [CianMuyOscuro] sigue en los contenedores. Ver [LightColors].
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

internal val DarkColors = darkColorScheme(
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

/**
 * **El modo día, escrito entero** (2-nov-2026).
 *
 * Lo reportó el usuario: «el modo noche se ve bien; el de día se ve muy mal: los iconos
 * cambian más o menos, pero el resto está oscuro, negro con letras negras». Dos cosas:
 *
 * - **Lo negro no era del tema, era de la ventana.** `values/themes.xml` pinta la ventana de
 *   negro y, desde el aspecto Cosmos (17-sep-2026), muchas pantallas —la lista de chats, las
 *   tareas— ya no se pintan su fondo: dejan ver el cielo, o el negro de la ventana de noche.
 *   De día, encima de ese negro, el texto `onSurface` del esquema claro, que es casi negro.
 *   Lo arregla [PixPinTheme] pintando la ventana con el `background` del esquema claro. Ver
 *   `docs/modo-dia-y-noche.md`.
 * - **El esquema claro solo tenía los acentos**: los fondos, contenedores y bordes eran los de
 *   fábrica de Material, gris **lila** (`#FFFBFE`, `#E7E0EC`…), y el primario cian claro
 *   (`#29B8DB`) daba **2,3 a 1** como texto sobre blanco: los «Conceder» y demás botones de
 *   texto casi no se leían. Ahora es la misma escalera que la de noche, en el tono de la
 *   marca, y los acentos van en su tono 40 (un cian hondo), con todo par texto/fondo por
 *   encima de 4,5 a 1 (`EsquemasDeColorTest` lo comprueba uno a uno).
 */
private val DiaBase = Color(0xFFF5FAFB)
private val DiaBajo = Color(0xFFEFF5F6)
private val DiaMedio = Color(0xFFE9EFF1)
private val DiaAlto = Color(0xFFE3EAEC)
private val DiaMasAlto = Color(0xFFDDE4E6)
private val DiaTexto = Color(0xFF161D1F)
private val DiaTextoSuave = Color(0xFF40484C)

/** El cian de la marca, oscurecido hasta leerse como texto sobre el papel claro (6,1 a 1). */
private val CianHondo = Color(0xFF00687D)

internal val LightColors = lightColorScheme(
    primary = CianHondo,
    onPrimary = Color.White,
    primaryContainer = CianClaro,
    onPrimaryContainer = CianMuyOscuro,
    inversePrimary = Color(0xFF5CD3F0),
    secondary = Color(0xFF00639B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8EF),
    onSecondaryContainer = Color(0xFF06282F),
    tertiary = Color(0xFF8B5000),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF2A1700),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = DiaBase,
    onBackground = DiaTexto,
    surface = DiaBase,
    onSurface = DiaTexto,
    surfaceDim = Color(0xFFD5DBDD),
    surfaceBright = DiaBase,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = DiaBajo,
    surfaceContainer = DiaMedio,
    surfaceContainerHigh = DiaAlto,
    surfaceContainerHighest = DiaMasAlto,
    surfaceVariant = Color(0xFFDCE7EB),
    onSurfaceVariant = DiaTextoSuave,
    surfaceTint = PinBlue,
    inverseSurface = Color(0xFF2B3133),
    inverseOnSurface = Color(0xFFECF2F3),
    outline = Color(0xFF6F797C),
    outlineVariant = Color(0xFFBFC8CB),
    scrim = Color.Black
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
internal val OledColors = DarkColors.copy(
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
    /**
     * Si se pone el cielo del tema Cosmos detrás. Las pantallas que se tapan enteras con su
     * propio lienzo (el editor, el croquis) dicen que no: pintarlo debajo sería trabajo tirado
     * en cada fotograma.
     */
    cielo: Boolean = true,
    content: @Composable () -> Unit
) {
    val contexto = LocalContext.current
    val app = contexto.applicationContext as? PixPinApp
    val ajustes by (app?.settings?.settings ?: kotlinx.coroutines.flow.flowOf(null))
        .collectAsState(initial = app?.ajustes)
    val modo = ajustes?.modoNoche ?: ModoNoche.COSMOS
    // **El cosmos**: el aspecto del sistema solar para toda la aplicación. Ver [esquemaCosmos].
    val cosmos = darkTheme == null && modo == ModoNoche.COSMOS
    val oscuro = darkTheme ?: deNocheSegun(modo)
    val negro = oled ?: (ajustes?.oledNegro ?: false)
    // El cielo solo detrás de una pantalla opaca: ni en las ventanas sobre otras apps ni en las
    // pantallas transparentes (captura, grabadora), que dejan ver lo de debajo a propósito.
    val conCielo = cosmos && cielo && remember(contexto) { ventanaOpaca(contexto) }
    val esquema = when {
        cosmos -> remember(conCielo) { esquemaCosmos(conCielo) }
        oscuro && negro -> OledColors
        oscuro -> DarkColors
        else -> LightColors
    }
    // **La ventana, del color del tema** (2-nov-2026). Ver [VentanaDelTema].
    if (darkTheme == null) VentanaDelTema(contexto, claro = !cosmos && !oscuro, fondo = esquema.background)
    CompositionLocalProvider(LocalDeNoche provides oscuro, LocalCosmos provides cosmos) {
        MaterialTheme(colorScheme = esquema) {
            if (conCielo) {
                Box(Modifier.fillMaxSize()) {
                    FondoCosmico(Modifier.matchParentSize())
                    content()
                }
            } else {
                content()
            }
        }
    }
}

/**
 * **La ventana, del color del tema** (2-nov-2026).
 *
 * `values/themes.xml` pinta la ventana de negro, y está bien de noche: con el cielo de Cosmos
 * delante, o con el fondo oscuro, no se ve. Pero muchas pantallas no se pintan su fondo —la lista
 * de chats, las tareas: cuentan con el cielo detrás— y **de día dejaban ver ese negro con el texto
 * casi negro del esquema claro encima**: «negro con letras negras», lo que reportó el usuario.
 * De día la ventana se pinta con el `background` del esquema claro; de noche se deja como estaba
 * (negro), para que la noche no cambie ni un píxel.
 *
 * Y las **barras del sistema**: iconos oscuros de día, claros de noche. `enableEdgeToEdge()` los
 * elige por el modo del **teléfono**, no por el de la aplicación: con el teléfono de noche y
 * PinDesk de día, la hora y la batería salían blancas sobre blanco.
 *
 * Solo en una pantalla con la ventana opaca y con el tema por el ajuste (sin `darkTheme`
 * forzado): las que fuerzan claro u oscuro (la reunión, la cámara, la clase) llevan sus barras.
 */
@Composable
private fun VentanaDelTema(contexto: android.content.Context, claro: Boolean, fondo: Color) {
    val actividad = remember(contexto) { actividadDe(contexto)?.takeIf { ventanaOpaca(it) } }
    androidx.compose.runtime.SideEffect {
        actividad?.window?.let { ventana -> pintarLaVentana(ventana, claro, fondo) }
    }
}

internal fun pintarLaVentana(ventana: android.view.Window, claro: Boolean, fondo: Color) {
    val color = if (claro) fondo.toArgbSeguro() else android.graphics.Color.BLACK
    val actual = (ventana.decorView.background as? android.graphics.drawable.ColorDrawable)?.color
    if (actual != color) ventana.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color))
    val barras = androidx.core.view.WindowCompat.getInsetsController(ventana, ventana.decorView)
    if (barras.isAppearanceLightStatusBars != claro) barras.isAppearanceLightStatusBars = claro
    if (barras.isAppearanceLightNavigationBars != claro) barras.isAppearanceLightNavigationBars = claro
}

/** El color en ARGB, opaco: una ventana translúcida por error dejaría ver el escritorio. */
private fun Color.toArgbSeguro(): Int = copy(alpha = 1f).toArgb()

/** La actividad que hay detrás de [contexto], si la hay. */
private fun actividadDe(contexto: android.content.Context): android.app.Activity? {
    var c: android.content.Context? = contexto
    while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
    return c as? android.app.Activity
}

/** Si [contexto] es una pantalla con la ventana opaca. Una ventana sobre otras apps no lo es. */
private fun ventanaOpaca(contexto: android.content.Context): Boolean {
    val actividad = actividadDe(contexto) ?: return false
    val a = actividad.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
    return try { !a.getBoolean(0, false) } finally { a.recycle() }
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
    ModoNoche.COSMOS -> true
}

/**
 * **Si la aplicación está de noche, fuera de Compose** (2-nov-2026): lo que decide el ajuste
 * «Aspecto» y no el modo del teléfono. Para lo que no pasa por [PixPinTheme] —el papel de los
 * visores, la página web que se exporta—, que antes miraba el teléfono: con el teléfono de noche
 * y PinDesk en «Claro», el visor salía negro dentro de una aplicación blanca.
 *
 * Con «Automático» cuenta solo la hora: el sensor de luz lo escucha [rememberAOscuras], dentro de
 * una pantalla.
 */
fun deNocheEnLaApp(contexto: android.content.Context): Boolean =
    when ((contexto.applicationContext as? PixPinApp)?.ajustes?.modoNoche ?: ModoNoche.COSMOS) {
        ModoNoche.SISTEMA -> telefonoDeNoche(contexto)
        ModoNoche.CLARO -> false
        ModoNoche.OSCURO, ModoNoche.COSMOS -> true
        ModoNoche.AUTO -> esHoraDeNoche()
    }

/** Si el teléfono está en modo oscuro. Solo para el ajuste «Sistema»: ver [deNocheEnLaApp]. */
fun telefonoDeNoche(contexto: android.content.Context): Boolean =
    (contexto.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

/**
 * **Lo que es oscuro de día y de noche** (2-nov-2026): las hojas y barras que se pintan oscuras a
 * propósito encima de un documento o de unas fotos (la hoja de la letra del lector, la barra de las
 * capturas elegidas). Su texto va en blanco a mano, pero los botones de texto, los interruptores y
 * los deslizadores de dentro toman el primario del tema, y el de día es un cian hondo hecho para
 * leerse sobre papel claro: sobre ese fondo oscuro se perdía. Dentro, el esquema de noche.
 *
 * De noche (o con Cosmos) no cambia nada: se queda el esquema que ya hay.
 */
@Composable
fun SiempreOscuro(contenido: @Composable () -> Unit) {
    if (deNoche()) {
        contenido()
    } else {
        CompositionLocalProvider(LocalDeNoche provides true) {
            MaterialTheme(
                colorScheme = DarkColors,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes,
                content = contenido
            )
        }
    }
}
