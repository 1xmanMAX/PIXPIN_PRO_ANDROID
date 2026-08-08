package com.forge.pixpin.motor

import android.graphics.Color

/**
 * Modo día y modo noche del lienzo.
 *
 * **No se guarda una paleta oscura aparte, y eso es lo importante.** Un dibujo
 * hecho de noche tiene que verse igual de día y al revés, así que los colores
 * que van al `.excalidraw` son siempre los del modo día; el modo noche es un
 * **filtro que se aplica al pintar**, no un cambio en los datos. Si no, cambiar
 * de modo reescribiría el dibujo entero y exportarlo daría un resultado
 * distinto según cómo lo estuvieras mirando.
 *
 * El filtro es el del original (`applyDarkModeFilter`), que en CSS se escribe
 * `invert(93%) hue-rotate(180deg)`. Las dos piezas hacen falta: invertir solo
 * pondría el negro en blanco pero también giraría los colores a su complementario
 * —el rojo saldría cian—, y el giro de tono los devuelve a su sitio.
 */
object DrawTheme {

    /** Fondo del lienzo en cada modo (`THEME_FILTER` sobre `#ffffff`). */
    const val FONDO_DIA = "#ffffff"
    const val FONDO_NOCHE = "#121212"

    /**
     * El negro de verdad, para pantallas OLED.
     *
     * En un OLED, el negro puro **no enciende el píxel**: no es un gris muy
     * oscuro, es luz apagada. La diferencia se ve —el lienzo desaparece contra
     * el marco del móvil— y se nota en la batería, que en una aplicación que
     * vive encima de otras no es un detalle.
     *
     * No es el valor por defecto porque en una pantalla LCD el negro puro se ve
     * gris lavado y con peor contraste que el `#121212`, así que quien lo quiera
     * lo enciende.
     */
    const val FONDO_OLED = "#000000"

    /** Cuánto invierte el filtro del original. */
    private const val INVERT = 0.93

    /**
     * El color con el que hay que pintar [argb] en modo noche.
     *
     * Con [noche] a false no toca nada: el modo día es el color tal cual.
     */
    fun filtrar(argb: Int, noche: Boolean): Int {
        if (!noche) return argb
        val a = Color.alpha(argb)
        var r = Color.red(argb) / 255.0
        var g = Color.green(argb) / 255.0
        var b = Color.blue(argb) / 255.0

        // invert(0.93): c' = c·(1 − 2a) + a
        val k = 1 - 2 * INVERT
        r = r * k + INVERT
        g = g * k + INVERT
        b = b * k + INVERT

        // hue-rotate(180°). La matriz de la especificación de filtros SVG
        // depende del seno y el coseno del ángulo; a media vuelta valen 0 y −1,
        // así que se reduce a estos nueve números y no hace falta recalcularla.
        val fr = (-0.574 * r + 1.430 * g + 0.144 * b).coerceIn(0.0, 1.0)
        val fg = (0.426 * r + 0.430 * g + 0.144 * b).coerceIn(0.0, 1.0)
        val fb = (0.426 * r + 1.430 * g - 0.856 * b).coerceIn(0.0, 1.0)

        return Color.argb(a, (fr * 255).toInt(), (fg * 255).toInt(), (fb * 255).toInt())
    }

    /** El fondo que le toca a la escena según el modo. */
    fun fondoDe(noche: Boolean, oled: Boolean = false): String = when {
        !noche -> FONDO_DIA
        oled -> FONDO_OLED
        else -> FONDO_NOCHE
    }
}
