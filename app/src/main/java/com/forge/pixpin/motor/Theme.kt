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
     * El azul del tema Cosmos (17-sep-2026). En pantalla, con ese tema puesto, este papel y la
     * pizarra se ven con el cielo difuminado detrás en vez de liso; al guardar y exportar sale
     * este color liso. Ver [seVeComoCielo].
     */
    const val FONDO_COSMOS = "#0b0f24"

    /** Si con el tema Cosmos este papel se enseña como cielo. */
    fun seVeComoCielo(fondo: String): Boolean =
        fondo.trim().lowercase().let { it == FONDO_COSMOS || it == FONDO_NOCHE }

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

    /**
     * **La tinta se adapta al papel, no a un «modo noche».**
     *
     * Hasta el 13-sep-2026 había un filtro de noche que aclaraba todos los colores al pintarlos
     * sobre papel oscuro. Tenía un fallo de fondo que el usuario vio dos veces: lo que se elegía de
     * noche se guardaba con otra claridad, y al volver al día salía casi negro. Pidió quitarlo y
     * hacer algo mejor: que cada tinta se vea bien **sobre el papel que haya**, cambiando solo la
     * que haga falta.
     *
     * La regla es la del contraste de WCAG 2.2 (1.4.11, gráficos y trazos: 3:1):
     *
     * - **Si la tinta ya se lee sobre ese papel, no se toca.** Un rojo vivo, un morado uva o un azul
     *   medio se leen sobre blanco y sobre negro; cambiarlos sería estropearlos.
     * - **Si no, se le cambia la claridad y se le deja el tono**, lo justo para llegar a 4,5:1 (el
     *   de texto normal): el amarillo sobre blanco pasa a un ocre; el azul marino sobre negro, a un
     *   azul claro; el morado sobre papel morado, a uno mucho más claro u oscuro.
     * - **Un gris es tinta, y se da la vuelta**: el blanco sobre papel blanco sale negro, y al revés.
     *
     * Es pintura, no dibujo: el color guardado no cambia nunca, así que cambiar de papel y volver
     * deja todo exactamente como estaba.
     */
    fun adaptar(argb: Int, papel: Int): Int {
        val lp = luminancia(papel)
        if (contraste(luminancia(argb), lp) >= CONTRASTE_QUE_VALE) return argb
        val a = (argb ushr 24) and 0xFF
        val (h, s, l) = aHsl(((argb shr 16) and 0xFF) / 255.0, ((argb shr 8) and 0xFF) / 255.0, (argb and 0xFF) / 255.0)
        val gris = s < ES_GRIS
        // Hacia donde hay más contraste: oscurecer sobre papel claro, aclarar sobre oscuro.
        val oscurecer = contraste(0.0, lp) >= contraste(1.0, lp)
        fun con(claridad: Double): Int {
            val (r, g, b) = deHsl(h, if (gris) 0.0 else s, claridad.coerceIn(0.0, 1.0))
            return (a shl 24) or (redondear(r) shl 16) or (redondear(g) shl 8) or redondear(b)
        }
        // Un gris empieza en su espejo (blanco → negro); un color, en su propia claridad.
        var claridad = if (gris) 1.0 - l else l
        val paso = if (oscurecer) -0.02 else 0.02
        var mejor = con(claridad)
        while (claridad in 0.0..1.0) {
            val c = con(claridad)
            mejor = c
            if (contraste(luminancia(c), lp) >= CONTRASTE_BUSCADO) return c
            claridad += paso
        }
        // Un papel de gris medio no deja llegar: el extremo que más se lea.
        return mejor
    }

    /** Lo mismo con el papel escrito en hexadecimal. */
    fun adaptar(argb: Int, papel: String): Int = adaptar(argb, colorDe(papel))

    /** El papel en entero, sin Android. */
    fun colorDe(hex: String): Int {
        val limpio = hex.trim().removePrefix("#")
        val seis = when (limpio.length) {
            3 -> limpio.map { "$it$it" }.joinToString("")
            6, 8 -> limpio.takeLast(6)
            else -> return 0xFFFFFFFF.toInt()
        }
        return (0xFF shl 24) or (seis.toLongOrNull(16)?.toInt() ?: 0xFFFFFF)
    }

    /** Luminancia relativa de WCAG. */
    fun luminancia(argb: Int): Double {
        fun lineal(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lineal((argb shr 16) and 0xFF) + 0.7152 * lineal((argb shr 8) and 0xFF) + 0.0722 * lineal(argb and 0xFF)
    }

    fun contraste(l1: Double, l2: Double): Double = (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)

    /** Por debajo de esto, la tinta se adapta. WCAG 1.4.11: trazos y gráficos. */
    const val CONTRASTE_QUE_VALE = 3.0

    /** Adonde se lleva la que se adapta: WCAG 1.4.3, texto normal. */
    const val CONTRASTE_BUSCADO = 4.5

    /**
     * **Compatibilidad**: quien aún pregunta por «noche» pinta contra el papel de noche o el de día
     * de fábrica. Lo nuevo pasa el papel de verdad ([adaptar]).
     */
    fun filtrar(argb: Int, noche: Boolean): Int = adaptar(argb, if (noche) PAPEL_NOCHE else PAPEL_DIA)

    private val PAPEL_DIA = 0xFFFFFFFF.toInt()
    private val PAPEL_NOCHE = 0xFF121212.toInt()

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

    /**
     * **Si un papel de este color es papel de noche.**
     *
     * De aquí sale todo lo demás: con el papel oscuro, la tinta se pinta con el filtro de
     * noche y la cuadrícula sale clara; con el papel claro, al revés. Es una sola decisión y
     * no dos interruptores que se pueden contradecir — que era lo que pasaba: se podía tener
     * el papel a oscuras y el modo día puesto, y entonces el dibujo salía negro sobre negro.
     *
     * El corte va alto a propósito, en el gris medio: lo que decide no es el gusto sino si
     * encima de ese papel se lee mejor una tinta oscura o una clara.
     */
    fun esDeNoche(fondo: String): Boolean {
        // **Se lee el hexadecimal a mano y no con `android.graphics.Color`.** De esta cuenta
        // depende cómo se pinta todo el dibujo, y una decisión así tiene que poder
        // comprobarse sin un móvil delante — que es la regla de esta carpeta.
        val limpio = fondo.trim().removePrefix("#")
        val seis = when (limpio.length) {
            3 -> limpio.map { "$it$it" }.joinToString("")
            6, 8 -> limpio.takeLast(6)
            else -> return false
        }
        val n = seis.toLongOrNull(16) ?: return false
        val r = ((n shr 16) and 0xFF) / 255.0
        val g = ((n shr 8) and 0xFF) / 255.0
        val b = (n and 0xFF) / 255.0
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) < 0.5
    }

    /**
     * Los papeles que se ofrecen, del más blanco al negro.
     *
     * Cinco y no una rueda de color: un papel no es una tinta. Lo que se elige aquí es sobre
     * qué se dibuja, y de eso hay **dos decisiones de verdad** —claro u oscuro— con un par de
     * matices dentro de cada una. Una rueda entera invitaría a poner el papel verde lima, que
     * es exactamente lo que nadie quiere y lo que dejaría el dibujo ilegible.
     */
    /*
     * **Ampliados el 13-sep-2026 con lo que dicen los estudios de papel para leer y escribir.** El
     * blanco puro refleja más luz y cansa en sesiones largas; el crema/marfil lo prefiere la
     * mayoría (dos de cada tres lectores en las encuestas de editoriales) y los tonos pastel
     * suaves —amarillo pálido, menta, azul claro— bajan el deslumbramiento **sin perder contraste**
     * con la tinta, que es la condición (Perkins School for the Blind; guías para dislexia: crema,
     * verde y azul claros). Nada saturado: un amarillo canario deslumbra más que el blanco. De
     * noche, además de la pizarra y el negro, un azul noche y el verde de pizarra de aula, que
     * con tinta clara se leen igual de bien y cansan menos que el negro puro en pantallas LCD.
     * Los valores que ya existían no cambian: un lienzo guardado sigue encontrando su papel.
     */
    val PAPELES: List<Pair<String, String>> = listOf(
        "Blanco" to FONDO_DIA,
        "Crema" to "#fdf6e3",
        "Hueso" to "#f5f1e8",
        "Sepia" to "#f1e7d0",
        "Amarillo" to "#fbf6d9",
        "Menta" to "#e9f5ec",
        "Azul claro" to "#e8f1fb",
        "Gris" to "#d8dade",
        "Cosmos" to FONDO_COSMOS,
        "Pizarra" to FONDO_NOCHE,
        "Azul noche" to "#14213d",
        "Verde pizarra" to "#1f3b33",
        "Negro" to FONDO_OLED
    )
}
