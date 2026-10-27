package com.forge.pixpin.guardados

/**
 * **Los archivos de texto que PixPin lee, y cómo** (27-oct-2026). El usuario: «no puedo abrir
 * archivos Markdown; y, como en Windows, que abra otros formatos parecidos, como txt y otros».
 *
 * **La lista es la del PC.** Su visor de documentos (`crates/pixpin-docs/src/lib.rs`, `formato_de`)
 * abre `md` y `markdown` como Markdown y `txt` y `log` como texto llano (`html::llano`: una línea,
 * un renglón, sin interpretar nada); y su tabla de colores (`crates/pixpin-ui/src/color_de_extension.rs`)
 * pone en la familia «Texto» además `ini`, `cfg`, `srt` y `vtt`. Aquí se abren todos: el `csv`, que
 * el PC también lee como texto, sigue yendo al visor de hojas, que lo enseña mejor.
 *
 * **Markdown y llano no se mezclan.** Un `.md` se ve compuesto (títulos, listas, negritas); un
 * `.txt` o un `.log` se ve **tal cual**: sus saltos de línea donde están, y un `#` o un `*` son un
 * `#` y un `*`, no un título ni una cursiva. Es lo que hace el PC con `html::llano`.
 *
 * Sin Android, para probarlo en la JVM: qué es cada cosa ([como]), si unos bytes son texto
 * ([pareceTexto]) y cómo se leen ([leer], el `texto_de` del PC). Quien abre es [AbrirMensaje] (desde
 * el chat) y [AbrirDePaso] (desde otra aplicación), los dos al editor de notas
 * ([com.forge.pixpin.ui.MarkdownEditorActivity]) con la ruta, no con el texto.
 */
object FormatosDeTexto {

    /** Cómo se enseña un archivo de texto. */
    enum class Como {
        /** Compuesto, con el motor de Markdown. */
        MARKDOWN,
        /** Tal cual, renglón a renglón. */
        LLANO
    }

    /** Las de Markdown (las del PC: `"md" | "markdown"`). */
    val DE_MARKDOWN = setOf("md", "markdown")

    /** Las de texto llano: las del visor del PC (`txt`, `log`) y las de su familia «Texto». */
    val LLANAS = setOf("txt", "log", "ini", "cfg", "srt", "vtt")

    /** Todas, para los filtros y las listas de extensiones conocidas. */
    val TODAS = DE_MARKDOWN + LLANAS

    /** La extensión de [nombre] (o de una ruta), en minúsculas y sin el punto; vacía si no la trae. */
    fun extension(nombre: String?): String {
        val n = nombre.orEmpty().replace('\\', '/').substringAfterLast('/')
        val i = n.lastIndexOf('.')
        return if (i <= 0 || i == n.lastIndex) "" else n.substring(i + 1).lowercase()
    }

    /** Cómo se enseña [nombre] por su extensión, o null si no es de estos. */
    fun como(nombre: String?): Como? = when (extension(nombre)) {
        in DE_MARKDOWN -> Como.MARKDOWN
        in LLANAS -> Como.LLANO
        else -> null
    }

    /**
     * **Cómo se enseña el adjunto de un mensaje**: por su nombre y, si el nombre no lleva
     * extensión (lo de antes, o un asunto), por la de su [ruta]. Null si no es de texto.
     */
    fun delMensaje(nombre: String?, ruta: String?): Como? {
        val n = nombre?.takeIf { it.isNotBlank() }
        if (n != null && extension(n).isNotEmpty()) return como(n)
        return como(ruta)
    }

    /**
     * **Si unos bytes son texto**: los primeros de un archivo que llega sin extensión (un
     * `content://` que no dice su nombre, un `README`). Con marca de UTF-16 o de UTF-8, sí; con un
     * cero o con caracteres de control que no sean de texto, no; y si no, que se lea como UTF-8
     * (un PDF o un ZIP llevan bytes que no lo son). Vacío no es texto: no hay nada que ver.
     */
    fun pareceTexto(cabeza: ByteArray): Boolean {
        if (cabeza.isEmpty()) return false
        if (empieza(cabeza, 0xFE, 0xFF) || empieza(cabeza, 0xFF, 0xFE) || empieza(cabeza, 0xEF, 0xBB, 0xBF)) return true
        if (extensionPorContenido(cabeza) != null) return false
        var raros = 0
        for (b in cabeza) {
            val c = b.toInt() and 0xFF
            if (c == 0) return false
            // Tabulador, saltos, avance de página y escape son de texto; lo demás de debajo del espacio, no.
            if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D && c != 0x0C && c != 0x1B) raros++
        }
        if (raros * 100 > cabeza.size) return false
        return esUtf8(cabeza, cortadoAlFinal = true)
    }

    /**
     * **Unos bytes como texto**, el `texto_de` del PC: la marca de orden si la lleva (UTF-16 en sus
     * dos órdenes, UTF-8), UTF-8 si lo es, y si no Latin-1, que no falla nunca y conserva las letras
     * acentuadas de un archivo de los de antes (Windows-1252). Así un `.txt` del Bloc de notas viejo
     * no sale con rombos.
     */
    fun leer(bytes: ByteArray): String = when {
        empieza(bytes, 0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        empieza(bytes, 0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        empieza(bytes, 0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        esUtf8(bytes, cortadoAlFinal = false) -> String(bytes, Charsets.UTF_8)
        else -> String(bytes, Charsets.ISO_8859_1)
    }

    private fun empieza(b: ByteArray, vararg m: Int) = b.size >= m.size && m.indices.all { (b[it].toInt() and 0xFF) == m[it] }

    /** Si [b] es UTF-8 válido. [cortadoAlFinal]: una letra a medias al final no cuenta (es una cabeza). */
    private fun esUtf8(b: ByteArray, cortadoAlFinal: Boolean): Boolean {
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xFF
            val siguen = when {
                c < 0x80 -> 0
                c in 0xC2..0xDF -> 1
                c in 0xE0..0xEF -> 2
                c in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + siguen >= b.size && siguen > 0) return cortadoAlFinal && (i + 1 until b.size).all { (b[it].toInt() and 0xC0) == 0x80 }
            for (k in 1..siguen) if ((b[i + k].toInt() and 0xC0) != 0x80) return false
            i += siguen + 1
        }
        return true
    }
}
