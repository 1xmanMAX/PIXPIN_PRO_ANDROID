package com.forge.pixpin.motormd

/**
 * **Lo que el PC mete en una nota** (30-sep y 1-oct-2026) y cómo se reconoce aquí. Todo usa lo que
 * [Markdown] ya sabía leer; esto solo le pone nombre:
 *
 * - **Ancho de una foto**: `![Planta|320](…)`, el ancho en píxeles a 96 ppp tras la última barra
 *   (forma de Obsidian), sobre una columna de 720. El pie enseña solo «Planta».
 * - **Página viva**: una foto `vivo-<código de la hoja>.png` que se repinta cuando cambia la hoja.
 * - **Enlace a una hoja**: `[Planta baja](pixpin:hoja=<proyecto>/<hoja>)`, con códigos únicos.
 * - **Mensaje del chat**: `[Pedir la grúa](pixpin:mensaje=<proyecto>/<mensaje>)`.
 *
 * Puro: sin Android, para probarlo en la JVM.
 */
object Incrustados {
    /** La columna del PC: a ese ancho o más, no se escribe ancho. */
    const val COLUMNA = 720

    private val ANCHO = Regex("""^(.*?)\s*\|(\d{1,5})$""")
    private val VIVA = Regex("""(?:^|/)vivo-([A-Za-z0-9]{6,40})\.png$""")

    /** El pie y el ancho (px a 96 ppp) del texto alternativo de una foto. */
    fun partirAlt(alt: String): Pair<String, Int?> {
        val m = ANCHO.find(alt) ?: return alt to null
        val n = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return alt to null
        return m.groupValues[1] to n
    }

    /** El texto alternativo con un ancho nuevo (o sin él, si llega a la columna). */
    fun conAncho(alt: String, ancho: Int?): String {
        val texto = partirAlt(alt).first
        return if (ancho == null || ancho >= COLUMNA) texto else "$texto|$ancho"
    }

    /** El código de la hoja de una página viva, o null si la ruta no es una. */
    fun paginaViva(ruta: String): String? = VIVA.find(ruta)?.groupValues?.get(1)

    sealed interface Enlace {
        data class Hoja(val proyecto: String, val hoja: String) : Enlace
        data class Mensaje(val proyecto: String, val mensaje: String) : Enlace
    }

    /** `pixpin:hoja=p/h` o `pixpin:mensaje=p/m`; null si es otra cosa. */
    fun enlace(url: String?): Enlace? {
        val u = url?.trim() ?: return null
        fun partes(p: String) = p.split('/', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }
        return when {
            u.startsWith("pixpin:hoja=") -> partes(u.removePrefix("pixpin:hoja="))?.let { Enlace.Hoja(it[0], it[1]) }
            u.startsWith("pixpin:mensaje=") -> partes(u.removePrefix("pixpin:mensaje="))?.let { Enlace.Mensaje(it[0], it[1]) }
            else -> null
        }
    }

    /**
     * Si un renglón es **solo** un enlace `pixpin:` (lo que el PC pinta como una burbuja), su
     * enlace y su texto; si no, null.
     */
    fun soloEnlace(t: InlineText): Pair<Enlace, String>? {
        val s = t.spans.singleOrNull { it.kind == SpanKind.LINK } ?: return null
        if (s.start > 0 || s.end < t.text.length) {
            if (t.text.substring(0, s.start).isNotBlank() || t.text.substring(s.end).isNotBlank()) return null
        }
        val e = enlace(s.url) ?: return null
        return e to t.text.substring(s.start, s.end)
    }

    fun escribirEnlaceAHoja(nombre: String, proyecto: String, hoja: String) =
        "[${nombre.replace("[", "(").replace("]", ")")}](pixpin:hoja=$proyecto/$hoja)"

    fun escribirEnlaceAMensaje(texto: String, proyecto: String, mensaje: String) =
        "[${texto.replace("[", "(").replace("]", ")").replace('\n', ' ').take(80)}](pixpin:mensaje=$proyecto/$mensaje)"
}
