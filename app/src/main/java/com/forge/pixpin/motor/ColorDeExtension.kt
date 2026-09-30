package com.forge.pixpin.motor

/**
 * **Un color por tipo de archivo** (30-sep-2026, pedido por el usuario con una captura de
 * Telegram: «rojo para todo PDF, azul para docx y similares, amarillo para dwg y similares»).
 *
 * Telegram solo tiene cuatro colores (`AndroidUtilities.getThumbForNameOrMime`: azul, verde, rojo,
 * amarillo) y reparte el resto por la primera letra. Aquí cada **familia** tiene el suyo, para que
 * de un vistazo se sepa qué es: los parecidos (docx, doc, odt…) comparten color. Lo que no está en
 * la tabla sale siempre del mismo color, sacado de su extensión.
 *
 * Sin Android: el color va en `0xRRGGBB` y [textoOscuro] dice si la extensión escrita encima va en
 * oscuro (sobre el amarillo, el blanco no se lee).
 */
object ColorDeExtension {

    class Familia(val nombre: String, val color: Int, val textoOscuro: Boolean = false)

    val PDF = Familia("PDF", 0xE5484D)
    val WORD = Familia("Documento", 0x2F7BF5)
    val HOJA = Familia("Hoja de cálculo", 0x2E9E4F)
    val PRESENTACION = Familia("Presentación", 0xF2711C)
    val PLANO = Familia("Plano", 0xF5B800, textoOscuro = true)
    val MODELO = Familia("Modelo 3D", 0x0FA3A3)
    val IMAGEN = Familia("Imagen", 0xD6336C)
    val AUDIO = Familia("Audio", 0x8E44AD)
    val VIDEO = Familia("Vídeo", 0x5B3CC4)
    val COMPRIMIDO = Familia("Comprimido", 0x8D6E63)
    val CODIGO = Familia("Código y web", 0x1098AD)
    val TEXTO = Familia("Texto", 0x607D8B)
    val LIBRO = Familia("Libro", 0x3B5BDB)
    val APLICACION = Familia("Aplicación", 0x6CBF5B)
    val PIXPIN = Familia("PixPin", 0x7048E8)

    private val porExtension: Map<String, Familia> = buildMap {
        fun poner(f: Familia, vararg ext: String) = ext.forEach { put(it, f) }
        poner(PDF, "pdf")
        poner(WORD, "doc", "docx", "docm", "dot", "dotx", "odt", "rtf", "pages", "wps")
        poner(HOJA, "xls", "xlsx", "xlsm", "xlsb", "csv", "tsv", "ods", "numbers")
        poner(PRESENTACION, "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "odp", "key")
        poner(PLANO, "dwg", "dxf", "dwf", "dwfx", "dgn", "dwt", "plt", "rvt", "rfa", "ifc", "nwd", "nwc")
        poner(MODELO, "obj", "stl", "fbx", "3ds", "blend", "skp", "gltf", "glb", "dae", "ply", "step", "stp", "iges", "igs", "3dm", "usdz")
        poner(IMAGEN, "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "tif", "tiff", "svg", "psd", "ai", "raw", "dng", "avif", "ico")
        poner(AUDIO, "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "wma", "mid", "midi")
        poner(VIDEO, "mp4", "mov", "avi", "mkv", "webm", "3gp", "m4v", "wmv", "flv")
        poner(COMPRIMIDO, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst")
        poner(CODIGO, "html", "htm", "css", "js", "ts", "json", "xml", "kt", "kts", "java", "py", "c", "h", "cpp", "hpp", "cs", "rs", "go", "sh", "sql", "yml", "yaml", "tex", "m", "ipynb")
        poner(TEXTO, "txt", "md", "log", "ini", "cfg", "srt", "vtt")
        poner(LIBRO, "epub", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr")
        poner(APLICACION, "apk", "aab", "xapk", "apks", "exe", "msi", "dmg", "deb", "appimage")
        poner(PIXPIN, "pixpin", "excalidraw")
    }

    /** Para lo que no está en la tabla: colores que se distinguen de los de las familias. */
    private val RESTO = intArrayOf(0x5C7CFA, 0x20C997, 0xFF6B6B, 0xFAB005, 0x845EF7, 0x15AABF, 0xE64980, 0x82C91E)

    /** La extensión de [nombre], en minúsculas, o null si no tiene. */
    fun extension(nombre: String?): String? =
        nombre?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() && it.length <= 10 }

    /** La familia de [nombre]: la de su extensión, o una hecha a su medida si no está en la tabla. */
    fun de(nombre: String?): Familia {
        val ext = extension(nombre) ?: return Familia("Archivo", 0x868E96)
        porExtension[ext]?.let { return it }
        // Siempre el mismo para la misma extensión: la suma de sus letras.
        val color = RESTO[ext.sumOf { it.code } % RESTO.size]
        return Familia(ext.uppercase(), color, textoOscuro = color == 0xFAB005)
    }

    /** Lo que se escribe en el icono: la extensión en minúsculas, como Telegram, cortada a 4 letras. */
    fun rotulo(nombre: String?): String = extension(nombre)?.take(4) ?: ""
}
