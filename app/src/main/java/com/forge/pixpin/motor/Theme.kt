package com.forge.pixpin.motor

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
 * El filtro **no es el del original**. Excalidraw hace `invert(93%)
 * hue-rotate(180deg)`, y eso tiene dos pegas que se ven a la primera: el negro
 * sale gris claro —invertir al 93 y no al 100 lo deja en 237, y a nadie le
 * cuadra que su rotulador negro se vuelva plomo— y los colores salen de una
 * matriz de giro de tono, que no es una decisión sobre cómo se lee un color
 * sobre fondo oscuro, es una fórmula.
 *
 * Aquí se distinguen los dos casos, que de verdad son dos: **un gris es tinta**
 * y se invierte del todo, y **un color es un color**, que conserva su tono y
 * solo se aclara lo justo para leerse. Ver [filtrar].
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

    /** Por debajo de esta saturación, un color es un gris: tinta, no color. */
    private const val ES_GRIS = 0.14

    /** Lo claro que se pone un color para leerse sobre el fondo oscuro. */
    private const val CLARIDAD_BASE = 0.62
    private const val CLARIDAD_EXTRA = 0.28

    /** Tope de saturación de noche: lo muy saturado vibra sobre oscuro. */
    private const val SATURACION_MAXIMA = 0.85

    /**
     * El color con el que hay que pintar [argb] en modo noche.
     *
     * Con [noche] a false no toca nada: el modo día es el color tal cual.
     */
    fun filtrar(argb: Int, noche: Boolean): Int {
        if (!noche) return argb
        // **Sin `android.graphics.Color`**, a propósito: son cuatro bytes en un
        // entero y desempaquetarlos a mano deja este filtro comprobable en la
        // JVM. Decidir cómo se ve un color de noche es de las cosas que hay que
        // poder comprobar sin un móvil delante.
        val a = (argb ushr 24) and 0xFF
        val (h, s, l) = aHsl(
            ((argb shr 16) and 0xFF) / 255.0,
            ((argb shr 8) and 0xFF) / 255.0,
            (argb and 0xFF) / 255.0
        )

        // **La tinta se da la vuelta; el color solo se aclara.**
        //
        // El filtro de antes era el del original: invertir al 93 % y girar el
        // tono media vuelta. Tenía dos pegas que se veían enseguida. El negro
        // salía **gris claro** —invertir al 93 y no al 100 lo deja en 237— y a
        // nadie le cuadra que su rotulador negro se vuelva plomo. Y los colores
        // salían de la matriz de giro de tono, que no es una decisión sobre cómo
        // se lee un color sobre fondo oscuro: es una fórmula.
        //
        // Aquí se separan los dos casos, que de verdad son dos:
        //
        // - **Un gris es tinta.** Se dibujó pensando en papel blanco, así que se
        //   invierte del todo: el negro es blanco y el blanco es negro.
        // - **Un color es un color.** Se le respeta el tono —el rojo tiene que
        //   seguir siendo rojo, no rosa ni naranja— y se le sube la claridad
        //   hasta donde se lee sobre oscuro, bajándole un punto de saturación
        //   porque un color a tope vibra sobre negro y cansa la vista.
        val gris = s < ES_GRIS
        val claridad = if (gris) 1.0 - l else CLARIDAD_BASE + CLARIDAD_EXTRA * (1.0 - l)
        val saturacion = if (gris) s else minOf(s, SATURACION_MAXIMA)

        val (r, g, b) = deHsl(h, saturacion, claridad)
        return (a shl 24) or
            (redondear(r) shl 16) or
            (redondear(g) shl 8) or
            redondear(b)
    }

    /** De 0..1 a un byte, redondeando: truncar dejaba el blanco en 254. */
    private fun redondear(v: Double): Int = Math.round(v * 255).toInt().coerceIn(0, 255)

    /** De rojo, verde y azul a tono, saturación y claridad. Todo de 0 a 1. */
    private fun aHsl(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val alto = maxOf(r, g, b)
        val bajo = minOf(r, g, b)
        val l = (alto + bajo) / 2
        if (alto == bajo) return Triple(0.0, 0.0, l)
        val d = alto - bajo
        val s = if (l > 0.5) d / (2 - alto - bajo) else d / (alto + bajo)
        val h = when (alto) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } / 6
        return Triple(h, s, l)
    }

    /** Y de vuelta. */
    private fun deHsl(h: Double, s: Double, l: Double): Triple<Double, Double, Double> {
        if (s == 0.0) return Triple(l, l, l)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun canal(t0: Double): Double {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        return Triple(canal(h + 1.0 / 3), canal(h), canal(h - 1.0 / 3))
    }

    /** El fondo que le toca a la escena según el modo. */
    fun fondoDe(noche: Boolean, oled: Boolean = false): String = when {
        !noche -> FONDO_DIA
        oled -> FONDO_OLED
        else -> FONDO_NOCHE
    }
}
