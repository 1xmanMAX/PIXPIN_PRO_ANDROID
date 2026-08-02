package com.forge.pixpin.markdown

/** Texto y selección tras aplicar un formato. */
data class EditResult(val text: String, val selStart: Int, val selEnd: Int)

/**
 * Aplica marcas de Markdown a la selección de un editor.
 *
 * Es matemática de índices y nada más, así que vive aquí y no en el composable:
 * se comprueba en la JVM y no hace falta un teclado delante para saber si el
 * cursor acaba donde debe.
 */
object MarkdownEdit {

    /**
     * Envuelve la selección entre [marker] (negrita, cursiva, código…). Si ya
     * estaba envuelta, la desenvuelve: el mismo botón pone y quita.
     *
     * Sin selección, inserta las dos marcas y deja el cursor en medio, listo
     * para escribir.
     */
    fun wrap(text: String, selStart: Int, selEnd: Int, marker: String): EditResult {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)

        val before = text.substring(0, start)
        val selected = text.substring(start, end)
        val after = text.substring(end)

        // Ya envuelta por dentro de la selección: se quitan las marcas.
        if (selected.length >= marker.length * 2 &&
            selected.startsWith(marker) && selected.endsWith(marker)
        ) {
            val naked = selected.substring(marker.length, selected.length - marker.length)
            return EditResult(before + naked + after, start, start + naked.length)
        }
        // Envuelta por fuera: las marcas están justo alrededor de la selección.
        if (before.endsWith(marker) && after.startsWith(marker)) {
            val trimmedBefore = before.dropLast(marker.length)
            val trimmedAfter = after.drop(marker.length)
            return EditResult(
                trimmedBefore + selected + trimmedAfter,
                start - marker.length,
                end - marker.length
            )
        }
        val out = before + marker + selected + marker + after
        return EditResult(out, start + marker.length, end + marker.length)
    }

    /**
     * Pone [prefix] al principio de la línea donde está el cursor (títulos,
     * viñetas, cita). Si ya lo tenía, lo quita.
     */
    fun togglePrefix(text: String, cursor: Int, prefix: String): EditResult {
        val pos = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }

        val rest = text.substring(lineStart)
        return if (rest.startsWith(prefix)) {
            EditResult(
                text.removeRange(lineStart, lineStart + prefix.length),
                (pos - prefix.length).coerceAtLeast(lineStart),
                (pos - prefix.length).coerceAtLeast(lineStart)
            )
        } else {
            EditResult(
                text.substring(0, lineStart) + prefix + rest,
                pos + prefix.length,
                pos + prefix.length
            )
        }
    }
}
