package com.forge.pixpin.guardados

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * **Los colores del chat, aparte de los de la aplicación.**
 *
 * ## Por qué aparte
 *
 * Es lo que hace Telegram: sus colores de conversación son un juego propio
 * (`chat_inBubble`, `chat_outBubble`, `chat_outTimeText`…) y no los del resto de la
 * aplicación. Tiene sentido: en una conversación lo único que importa es que **la burbuja se
 * despegue del papel**, y eso no se consigue con los mismos grises que valen para una
 * pantalla de ajustes.
 *
 * ## Qué estaba mal, medido
 *
 * La burbuja era `surfaceVariant` (`#DCE7EB`) sobre un fondo que era `surface` con un velo
 * del propio azul de la aplicación: los dos casi el mismo color. El contraste entre burbuja y
 * papel salía a **1,20:1** — o sea, ninguno—, y por eso el usuario decía que las burbujas «se
 * fusionan con el fondo» (7-sep-2026). Para comparar: en el tema oscuro de Telegram esa misma
 * separación es de **2,63:1**.
 *
 * ## De dónde salen estos números
 *
 * Del propio Telegram, citado y no de memoria:
 *
 * - Claro — `ThemeColors.java:307`: `chat_outBubble = 0xffefffde`; `:315` texto negro;
 *   `:400` `chat_outTimeText = 0xff70b15c`.
 * - Oscuro — `assets/darkblue.attheme` («Dark Blue», el oscuro de fábrica):
 *   `chat_outBubble = #3E618A`, `chat_wallpaper = #151E27`, texto `#FAFAFA`,
 *   `chat_outTimeText = #8FBCDF`.
 *
 * Y dos se apartan a propósito, con la cuenta delante: la hora de Telegram en claro
 * (`#70B15C`) da **2,46:1** sobre su burbuja, que para letra pequeña se queda corto
 * —WCAG pide 4,5—, así que se usa un verde más hondo del mismo tono, **4,96:1**. En oscuro,
 * su `#8FBCDF` da 3,18:1 y aquí se sube a 3,80:1 por lo mismo. El texto sobre la burbuja
 * queda en 16,6:1 (claro) y 6,1:1 (oscuro).
 *
 * El papel es un degradado en el tono de la aplicación y **medido contra la burbuja**: 1,98:1
 * y 2,03:1 en sus dos extremos, frente al 1,20:1 de antes.
 */
object ColoresDelChat {

    /** La burbuja. En claro, la de Telegram; en oscuro, la suya de «Dark Blue». */
    @Composable
    @ReadOnlyComposable
    fun burbuja(): Color = if (esDeNoche()) Color(0xFF3E618A) else Color(0xFFEFFFDE)

    /** Lo escrito dentro de ella. */
    @Composable
    @ReadOnlyComposable
    fun tinta(): Color = if (esDeNoche()) Color(0xFFFAFAFA) else Color(0xFF101B24)

    /** La hora y lo que la acompaña: secundario, pero legible. */
    @Composable
    @ReadOnlyComposable
    fun hora(): Color = if (esDeNoche()) Color(0xFFA8CCE8) else Color(0xFF3F7A30)

    /**
     * **Los dos extremos del papel**, según el fondo elegido. Ver [FondosDelChat] y la nota
     * de arriba para la separación medida: sea cual sea el elegido, la burbuja se despega.
     */
    @Composable
    fun papel(): Pair<Color, Color> {
        val contexto = androidx.compose.ui.platform.LocalContext.current
        val cual = (contexto.applicationContext as? com.forge.pixpin.PixPinApp)?.ajustes?.fondoDelChat
        val fondo = FondosDelChat.porId(cual)
        return if (esDeNoche()) Color(fondo.oscuroA) to Color(fondo.oscuroB)
        else Color(fondo.claroA) to Color(fondo.claroB)
    }

    /** Lo que va encima de la burbuja sin ser texto: bordes, separadores, velos. */
    @Composable
    @ReadOnlyComposable
    fun filete(): Color =
        if (esDeNoche()) Color(0x33FFFFFF) else Color(0x1A101B24)

    @Composable
    @ReadOnlyComposable
    private fun esDeNoche(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}

/**
 * **Los papeles entre los que elegir.**
 *
 * Telegram deja poner un fondo al chat y no es un adorno: el fondo es lo que hace que la
 * burbuja se lea como un objeto suelto y no como una mancha del mismo papel. Aquí son
 * degradados y no fotos por lo de siempre —una foto pesa, se pelea con lo escrito y hay que
 * meterla en el APK—, y **todos están medidos**: ninguno puede acercarse a la burbuja más de
 * lo que se arregló el 7-sep-2026, y hay una prueba que lo comprueba uno a uno
 * (`ColoresDelChatTest`). Un fondo que vuelva a fundir la burbuja no entra aquí.
 */
object FondosDelChat {

    /** Un papel: sus dos extremos de día y sus dos de noche. */
    class Fondo(
        val id: String,
        val nombre: String,
        val claroA: Long,
        val claroB: Long,
        val oscuroA: Long,
        val oscuroB: Long
    )

    /**
     * Los papeles. El primero es el de fábrica —el verde azulado del que sale la burbuja
     * verde de Telegram—; los demás cambian de tono sin cambiar de claridad, que es lo que
     * mantiene la separación medida.
     */
    val TODOS: List<Fondo> = listOf(
        Fondo("mar", "Mar", 0xFF86BFB2, 0xFF8AB8D2, 0xFF151E27, 0xFF10161D),
        Fondo("arena", "Arena", 0xFFCDB893, 0xFFC9A98B, 0xFF241E16, 0xFF1B1711),
        Fondo("brezo", "Brezo", 0xFFB3A7CE, 0xFFA9A2C9, 0xFF1E1A2A, 0xFF171422),
        Fondo("bosque", "Bosque", 0xFF93BE93, 0xFF8FB9A4, 0xFF14201A, 0xFF101913),
        Fondo("ocaso", "Ocaso", 0xFFD3A79B, 0xFFC79FA8, 0xFF251A19, 0xFF1D1413),
        Fondo("pizarra", "Pizarra", 0xFFAAB4BB, 0xFFA3ADB6, 0xFF1A1D20, 0xFF141719)
    )

    val POR_DEFECTO: Fondo = TODOS.first()

    fun porId(id: String?): Fondo = TODOS.firstOrNull { it.id == id } ?: POR_DEFECTO
}
