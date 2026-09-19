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
    fun estilo(grosor: Int, letra: Int): String {
        val peso = GROSORES.getOrElse(grosor) { GROSORES[1] }.first
        val familia = LETRAS.getOrElse(letra) { LETRAS[0] }.first
        val titulos = maxOf(peso, 700)
        return "<style id=\"$ID_DEL_ESTILO\">" +
            "body,p,li,td,th,div,span,blockquote{font-family:$familia !important;font-weight:$peso !important}" +
            "h1,h2,h3,h4,h5,h6,b,strong{font-family:$familia !important;font-weight:$titulos !important}" +
            "</style>"
    }

    /** La página con el estilo puesto (y sin el que tuviera de antes): justo antes de `</head>`. */
    fun conEstilo(pagina: String, grosor: Int, letra: Int): String {
        val limpia = pagina.replace(Regex("<style id=\"$ID_DEL_ESTILO\">.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
        val i = limpia.indexOf("</head>", ignoreCase = true)
        return if (i < 0) estilo(grosor, letra) + limpia else limpia.substring(0, i) + estilo(grosor, letra) + limpia.substring(i)
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

    const val ID_DEL_ESTILO = "pixpin-lector"
    const val MARCADORES = 24
}
