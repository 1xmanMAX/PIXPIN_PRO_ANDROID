package com.forge.pixpin.guardados

/**
 * **Vídeos y enlaces, con su pestaña** (25-oct-2026, como el PC: `es_video` y `tiene_enlace` de
 * `crates/pixpin-proyecto/src/cuaderno.rs`). La ficha del chat al estilo de Telegram: «todo,
 * fotos, vídeos, archivos, enlaces…». Un vídeo deja de contar en Fotos y en Archivos.
 *
 * Sin Android: se prueba en `SeccionesDelChatTest`.
 */
object SeccionesDelChat {
    /** Las de la familia «vídeo» de los colores por extensión del PC (`color_de_extension`). */
    val EXTENSIONES_DE_VIDEO = setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "m4v", "wmv", "flv")

    /**
     * Si un mensaje es un vídeo: una foto o un archivo con extensión de vídeo. Por su nombre, y si
     * no lo tiene (lo de antes), por el de su ruta.
     */
    fun esVideo(m: Mensaje): Boolean {
        if (m.clase != Clase.IMAGEN && m.clase != Clase.ARCHIVO) return false
        val nombre = m.nombre.ifBlank { m.ruta?.substringAfterLast('/').orEmpty() }
        val ext = nombre.substringAfterLast('.', "")
        return ext.isNotEmpty() && ext.lowercase() in EXTENSIONES_DE_VIDEO
    }

    private val ABRE = charArrayOf('(', '[', '<', '«', '"', '\'')
    private val CIERRA = charArrayOf('.', ',', ';', ':', ')', ']', '>', '»', '"')

    /**
     * Si el texto lleva una dirección web: una palabra que empieza por `http://`, `https://` o
     * `www.` con un punto en el servidor. Lo justo para la sección «Enlaces».
     */
    fun tieneEnlace(texto: String): Boolean =
        texto.split(Regex("\\s+")).any { palabra ->
            val p = palabra.trimStart(*ABRE).lowercase()
            val resto = listOf("https://", "http://", "www.").firstOrNull { p.startsWith(it) }?.let { p.removePrefix(it) }
                ?: return@any false
            val servidor = resto.split('/', '?', '#').first().trimEnd(*CIERRA)
            '.' in servidor && !servidor.startsWith('.')
        }
}
