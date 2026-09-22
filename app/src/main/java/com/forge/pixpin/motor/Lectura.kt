package com.forge.pixpin.motor

/**
 * **Leer un Word o un libro a gusto** (19-sep-2026): la letra —tamaño, grosor y tipo— y los
 * **marcadores con emoticono**. Aquí van las cuentas, sin Android, para poder comprobarlas; la
 * pantalla es [com.forge.pixpin.ui.VisorHtmlActivity].
 *
 * Un marcador guarda **en qué fracción del documento** está lo que había arriba de la pantalla al
 * ponerlo, no un número de píxeles: así sigue cayendo (casi) en el mismo párrafo aunque luego se
 * agrande la letra y todo el documento se alargue.
 */
object Lectura {
    data class Marcador(val id: Long, val fraccion: Float, val emoji: String)

    const val TAMANO_MIN = 70
    const val TAMANO_MAX = 250
    const val PASO_DEL_TAMANO = 10

    /** Los tamaños de la barra de puntos, en tanto por ciento: cada punto, uno. */
    val TAMANOS = listOf(80, 90, 100, 115, 130, 150, 175, 200, 230)

    /** Los grosores, como los entiende CSS, y cómo se llaman en la pantalla. */
    val GROSORES = listOf(300 to "Fina", 400 to "Normal", 600 to "Gruesa", 800 to "Negra")

    /** Las letras que hay en cualquier Android sin bajar nada. */
    val LETRAS = listOf(
        "serif" to "Serif",
        "sans-serif" to "Sans",
        "monospace" to "Mono",
        "cursive" to "Cursiva"
    )

    val EMOJIS = listOf("🔖", "⭐", "❤️", "❗", "❓", "💡", "📌", "✅", "🔥", "👀", "✏️", "🏁")

    fun tamanoValido(t: Int) = t.coerceIn(TAMANO_MIN, TAMANO_MAX)

    /**
     * La hoja de estilo que se le pone encima a la página. Con `!important` porque la del
     * documento ya dice qué letra quiere; los títulos conservan su negrita propia salvo que el
     * grosor pedido sea aún mayor.
     */
    /**
     * Cuánto margen se abre a cada lado de la columna de texto para anotar: **dos tercios de su
     * ancho**, que es lo que pidió el usuario (20-sep-2026).
     */
    fun margenDe(columna: Int): Int = columna * 2 / 3

    /**
     * **El margen de un PDF, para anotar** (21-sep-2026): tres cuartos del ancho de la hoja a cada
     * lado, como pidió el usuario —«es como que extiende el PDF a esas zonas»—. En un Word el
     * margen son dos tercios de la columna ([margenDe]); una hoja de PDF ya trae sus propios
     * blancos y lo que se escribe al lado suele ser más largo.
     */
    const val MARGEN_DEL_PDF = 0.75f

    /** Lo más que se aleja un PDF: **la hoja y un margen**, el de un lado o el del otro. */
    const val ALEJADO_DEL_PDF = 1f / (1f + MARGEN_DEL_PDF)

    /** Lo que mide el documento entero con sus dos márgenes: la columna y dos tercios a cada lado. */
    fun anchoConMargenes(columna: Int): Int = columna + 2 * margenDe(columna)

    fun estilo(grosor: Int, letra: Int, columna: Int? = null, oscuro: Boolean? = null): String {
        val peso = GROSORES.getOrElse(grosor) { GROSORES[1] }.first
        val familia = LETRAS.getOrElse(letra) { LETRAS[0] }.first
        val titulos = maxOf(peso, 700)
        return "<style id=\"$ID_DEL_ESTILO\">" +
            "body,p,li,td,th,div,span,blockquote{font-family:$familia !important;font-weight:$peso !important}" +
            "h1,h2,h3,h4,h5,h6,b,strong{font-family:$familia !important;font-weight:$titulos !important}" +
            // **Para anotar**: la columna de texto se queda **del ancho que tenía** —en píxeles, no
            // «el ancho de la pantalla», para que girar el aparato no recoloque el texto bajo lo
            // anotado— y a cada lado se abre un margen en blanco donde escribir.
            (columna?.let {
                "html{width:${anchoConMargenes(it)}px !important;overflow-x:auto !important}" +
                    "body{box-sizing:border-box !important;width:${it}px !important;max-width:none !important;" +
                    "margin-left:${margenDe(it)}px !important;margin-right:${margenDe(it)}px !important}" +
                    // **Las tablas anchas, enteras** (22-sep-2026, pedido por el usuario). Leyendo,
                    // una tabla más ancha que la columna va en una caja con su propio scroll de
                    // lado (`.tabla{overflow-x:auto}` en [DocxAHtml]): se ve un trozo y se corre
                    // con el dedo. Anotando eso es una trampa: lo anotado se ata a la página, no a
                    // ese scroll de dentro, y al correr la tabla después la anotación se queda en
                    // el aire. Con la columna fijada, la caja deja de recortar y la tabla se
                    // extiende hacia el margen de la derecha —hasta la columna más ese margen,
                    // que es lo que hay de papel—; más ancha, envuelve el texto de sus celdas.
                    ".tabla{overflow:visible !important}" +
                    "table{max-width:${it + margenDe(it)}px !important}"
            } ?: "") +
            // **El papel, decidido aquí y no por la página**: claro u oscuro según el aparato, sin
            // dejarlo a lo que el visor entienda por «modo oscuro». La tinta de lo anotado se elige
            // con el mismo dato, así que **siempre contrasta con el papel que hay de verdad**.
            (when (oscuro) {
                true -> "html,body{background:#15171c !important;color:#e6e6ea !important}a{color:#8ab4f8 !important}"
                false -> "html,body{background:#ffffff !important;color:#1b1b1f !important}"
                null -> ""
            }) +
            "</style>"
    }

    /** La página con el estilo puesto (y sin el que tuviera de antes): justo antes de `</head>`. */
    fun conEstilo(pagina: String, grosor: Int, letra: Int, columna: Int? = null, oscuro: Boolean? = null): String {
        val limpia = pagina.replace(Regex("<style id=\"$ID_DEL_ESTILO\">.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
        val i = limpia.indexOf("</head>", ignoreCase = true)
        val hoja = estilo(grosor, letra, columna, oscuro)
        return if (i < 0) hoja + limpia else limpia.substring(0, i) + hoja + limpia.substring(i)
    }

    /** Con uno más, **en el orden del documento**, que es el de los puntos del lateral. */
    fun conMarcador(lista: List<Marcador>, fraccion: Float, emoji: String, ahora: Long): List<Marcador> =
        (lista + Marcador(ahora, fraccion.coerceIn(0f, 1f), emoji)).sortedBy { it.fraccion }.takeLast(MARCADORES)

    /** Qué punto del lateral cae bajo el dedo: [y] desde el primer punto, [paso] entre puntos. */
    fun puntoBajoElDedo(y: Float, paso: Float, cuantos: Int): Int =
        if (cuantos <= 0 || paso <= 0f) -1 else (y / paso).toInt().coerceIn(0, cuantos - 1)

    /** Guardados en una línea: `id:fraccion:emoji` separados por `|`. Sin JSON que arrastrar. */
    fun aTexto(lista: List<Marcador>): String = lista.joinToString("|") { "${it.id}:${it.fraccion}:${it.emoji}" }

    fun deTexto(texto: String?): List<Marcador> = texto.orEmpty().split('|').mapNotNull { trozo ->
        val p = trozo.split(':', limit = 3)
        val id = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val f = p.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
        Marcador(id, f.coerceIn(0f, 1f), p.getOrNull(2).orEmpty().ifBlank { EMOJIS[0] })
    }.sortedBy { it.fraccion }

    /**
     * **Dónde puede estar la vista** de un documento de [ancho]×[alto] en una pantalla de
     * [vistaAncho]×[vistaAlto]: dentro, sin salirse por ningún lado. Todo en las mismas unidades.
     */
    fun dentroDelDocumento(x: Double, y: Double, ancho: Double, alto: Double, vistaAncho: Double, vistaAlto: Double): Pair<Double, Double> =
        x.coerceIn(0.0, (ancho - vistaAncho).coerceAtLeast(0.0)) to y.coerceIn(0.0, (alto - vistaAlto).coerceAtLeast(0.0))

    /**
     * **El imán del centro.** Con márgenes a los lados, lo que hay que ver siempre es el texto: se
     * puede ir a un margen y quedarse en él, pero **en cuanto se empuja de vuelta hacia el centro,
     * la vista se va al centro** y encaja. [antes] y [ahora] son dónde estaba la vista al posar el
     * dedo y al soltarlo; [centro], dónde queda el texto de borde a borde. Devuelve a dónde ir, o
     * null para quedarse. Lo pidió así el usuario (20-sep-2026).
     */
    fun imanDelCentro(antes: Double, ahora: Double, centro: Double, margen: Double): Double? {
        val lejos = kotlin.math.abs(ahora - centro)
        if (lejos < 0.5) return null
        // Muy cerca del centro se encaja siempre; si no, solo si se venía hacia él.
        val casi = lejos <= margen * CERCA_DEL_CENTRO
        val hacia = lejos < kotlin.math.abs(antes - centro) - 1.0
        return if (casi || hacia) centro else null
    }

    private const val CERCA_DEL_CENTRO = 0.12

    /**
     * **Por dónde va la lectura**, de 0 a 1, para la flecha del riel del lateral (22-sep-2026,
     * pedido por el usuario): [corrido] es lo desplazado, [alto] lo que mide el documento y [vista]
     * lo que se ve de él, todo en las mismas unidades. Arriba del todo es 0 y **al final, 1**: la
     * flecha llega abajo cuando ya se ve la última línea, no cuando esta sube hasta arriba.
     */
    fun progreso(corrido: Float, alto: Float, vista: Float): Float {
        val recorrido = alto - vista
        return if (recorrido <= 0f) 0f else (corrido / recorrido).coerceIn(0f, 1f)
    }

    const val ID_DEL_ESTILO = "pixpin-lector"
    const val MARCADORES = 24
}
