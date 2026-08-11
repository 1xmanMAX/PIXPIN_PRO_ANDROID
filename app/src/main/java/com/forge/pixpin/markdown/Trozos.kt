package com.forge.pixpin.markdown

/** Un bloque del documento, dicho en posiciones del texto original. */
data class Trozo(val desde: Int, val hasta: Int) {
    fun de(fuente: String): String = fuente.substring(desde, hasta)
    operator fun contains(pos: Int): Boolean = pos in desde..hasta
}

/**
 * Parte el documento en bloques **sin interpretarlos**, solo diciendo dónde
 * empieza y acaba cada uno.
 *
 * Es lo que necesita el editor en vivo: para que se pueda escribir en un bloque
 * mientras los demás se ven ya pintados, hace falta saber qué trozo del texto es
 * cada bloque. Interpretar no basta —eso da el resultado, no de dónde salió—, y
 * volver a buscarlo a ojo sobre el texto sería tener dos verdades.
 *
 * Los trozos **cubren el documento entero y no se pisan**: pegados en orden dan
 * exactamente el original. Las líneas en blanco se quedan con el bloque de
 * arriba, que es donde las puso quien escribía.
 *
 * Que esto y el parser digan lo mismo no se vigila leyendo: hay una prueba que
 * interpreta cada trozo por separado y comprueba que sale lo mismo que
 * interpretando el documento de una vez. Si alguien cambia una regla en un sitio
 * y no en el otro, salta.
 */
fun trozosDe(source: String): List<Trozo> {
    val texto = source.replace("\r\n", "\n")
    if (texto.isEmpty()) return emptyList()

    val lines = texto.split('\n')
    // Dónde empieza cada línea dentro del texto.
    val inicios = IntArray(lines.size)
    var acumulado = 0
    lines.forEachIndexed { i, linea ->
        inicios[i] = acumulado
        acumulado += linea.length + 1
    }

    fun finDeLinea(i: Int): Int = inicios[i] + lines[i].length

    val salida = mutableListOf<Trozo>()
    var i = 0
    while (i < lines.size) {
        val desde = inicios[i]
        var ultima = i

        val t = lines[i].trim()
        when {
            t.startsWith("```") -> {
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) i++
                if (i < lines.size) i++ // la valla de cierre
                ultima = i - 1
            }

            abreCaja(t) -> {
                var nivel = 1
                i++
                while (i < lines.size) {
                    val l = lines[i].trim()
                    if (abreCaja(l)) nivel++
                    if (l == ":::") {
                        nivel--
                        if (nivel == 0) break
                    }
                    i++
                }
                if (i < lines.size) i++
                ultima = i - 1
            }

            t == "$$" -> {
                i++
                while (i < lines.size && lines[i].trim() != "$$") i++
                if (i < lines.size) i++
                ultima = i - 1
            }

            hayTabla(lines, i) -> {
                while (i < lines.size && lines[i].trim().startsWith("|")) i++
                ultima = i - 1
            }

            t.startsWith(">") -> {
                while (i < lines.size && lines[i].trim().startsWith(">")) i++
                ultima = i - 1
            }

            t.isEmpty() -> {
                i++
                ultima = i - 1
            }

            // Un párrafo se come las líneas que le siguen mientras no arranquen
            // otra cosa. Es lo mismo que hace el parser al juntarlas.
            !arrancaBloqueSuelto(t) -> {
                i++
                while (i < lines.size &&
                    lines[i].trim().isNotEmpty() &&
                    !arrancaBloqueSuelto(lines[i].trim()) &&
                    !arrancaBloqueLargo(lines, i)
                ) {
                    i++
                }
                ultima = i - 1
            }

            // Lo que ocupa una línea y ya: título, casilla, viñeta, medio…
            else -> {
                i++
                ultima = i - 1
            }
        }

        // Las líneas en blanco de después se quedan con este bloque.
        while (i < lines.size && lines[i].isBlank() && i < lines.size - 1) {
            i++
            ultima = i - 1
        }
        if (i == lines.size - 1 && lines[i].isBlank()) {
            ultima = i
            i++
        }

        val hasta = if (i < lines.size) inicios[i] else finDeLinea(ultima)
        salida += Trozo(desde, hasta.coerceAtMost(texto.length))
    }

    return salida
}

/** El trozo donde cae el cursor, o el último si se pasa. */
fun trozoEn(trozos: List<Trozo>, pos: Int): Int {
    if (trozos.isEmpty()) return -1
    val i = trozos.indexOfFirst { pos in it }
    return if (i >= 0) i else trozos.size - 1
}

private fun abreCaja(linea: String): Boolean =
    linea.startsWith(":::") && linea != ":::"

private fun arrancaBloqueSuelto(t: String): Boolean =
    Regex("""^#{1,6}\s""").containsMatchIn(t) ||
        Regex("""^[-*+]\s+\[[ xX]]""").containsMatchIn(t) ||
        Regex("""^!\[[^]]*]\([^)]+\)$""").matches(t) ||
        Regex("""^\s*([-*_])\1{2,}\s*$""").matches(t) ||
        (t.length > 2 && t[0] in "-*+" && t[1] == ' ') ||
        Regex("""^\d{1,3}[.)]\s""").containsMatchIn(t)

private fun arrancaBloqueLargo(lines: List<String>, i: Int): Boolean {
    val t = lines[i].trim()
    return t.startsWith("```") || abreCaja(t) || t == "$$" ||
        t.startsWith(">") || hayTabla(lines, i)
}

private fun hayTabla(lines: List<String>, i: Int): Boolean {
    if (!lines[i].trim().startsWith("|")) return false
    val siguiente = lines.getOrNull(i + 1)?.trim() ?: return false
    if (!siguiente.startsWith("|")) return false
    val celdas = siguiente.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
    return celdas.isNotEmpty() && celdas.all { Regex("""^:?-{1,}:?$""").matches(it) }
}
