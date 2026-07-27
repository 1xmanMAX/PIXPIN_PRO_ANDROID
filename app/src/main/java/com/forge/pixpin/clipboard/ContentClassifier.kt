package com.forge.pixpin.clipboard

/** Contenido clasificado del portapapeles, listo para convertirse en pin. */
sealed interface PinContent {
    data class ColorPin(val argb: Int, val source: String) : PinContent
    data class TextPin(val text: String) : PinContent
    data class ImageUri(val uriString: String) : PinContent
    data class FileUri(val uriString: String) : PinContent
    data object Empty : PinContent
}

/**
 * Clasificador puro (sin Android): detecta colores estilo CSS en el texto
 * copiado (#hex, rgb(), "r, g, b", nombres CSS) igual que PixPin desktop.
 */
object ContentClassifier {

    private val hexRegex = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    private val rgbFuncRegex = Regex(
        "^rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,\\s*(\\d?\\.?\\d+)\\s*)?\\)$"
    )
    private val tripleRegex = Regex("^(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})$")

    fun classify(text: String?): PinContent {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return PinContent.Empty
        parseColor(t)?.let { return PinContent.ColorPin(it, t) }
        return PinContent.TextPin(t)
    }

    /** Devuelve el color como ARGB Int, o null si el texto no es un color. */
    fun parseColor(input: String): Int? {
        hexRegex.matchEntire(input)?.let { m ->
            val hex = m.groupValues[1]
            return when (hex.length) {
                3 -> {
                    val r = hex[0].toString().repeat(2).toInt(16)
                    val g = hex[1].toString().repeat(2).toInt(16)
                    val b = hex[2].toString().repeat(2).toInt(16)
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                6 -> (0xFF shl 24) or hex.toInt(16)
                8 -> hex.toLong(16).toInt()
                else -> null
            }
        }
        rgbFuncRegex.matchEntire(input)?.let { m ->
            val r = m.groupValues[1].toIntOrNull() ?: return null
            val g = m.groupValues[2].toIntOrNull() ?: return null
            val b = m.groupValues[3].toIntOrNull() ?: return null
            if (r > 255 || g > 255 || b > 255) return null
            val aStr = m.groupValues[4]
            val a = when {
                aStr.isEmpty() -> 255
                aStr.contains('.') -> (((aStr.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)) * 255).toInt()
                else -> (aStr.toIntOrNull() ?: 255).coerceIn(0, 255)
            }
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        tripleRegex.matchEntire(input)?.let { m ->
            val r = m.groupValues[1].toInt()
            val g = m.groupValues[2].toInt()
            val b = m.groupValues[3].toInt()
            if (r > 255 || g > 255 || b > 255) return null
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return cssColorNames[input.lowercase()]
    }

    fun toHex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

    fun toRgb(argb: Int): String =
        "rgb(${(argb shr 16) and 0xFF}, ${(argb shr 8) and 0xFF}, ${argb and 0xFF})"

    private val cssColorNames: Map<String, Int> = mapOf(
        "white" to 0xFFFFFFFF.toInt(), "black" to 0xFF000000.toInt(),
        "red" to 0xFFF44336.toInt(), "orange" to 0xFFFF9800.toInt(),
        "yellow" to 0xFFFFEB3B.toInt(), "green" to 0xFF4CAF50.toInt(),
        "teal" to 0xFF009688.toInt(), "cyan" to 0xFF00BCD4.toInt(),
        "blue" to 0xFF2196F3.toInt(), "indigo" to 0xFF3F51B5.toInt(),
        "purple" to 0xFF9C27B0.toInt(), "pink" to 0xFFE91E63.toInt(),
        "brown" to 0xFF795548.toInt(), "gray" to 0xFF9E9E9E.toInt(),
        "grey" to 0xFF9E9E9E.toInt(), "magenta" to 0xFFFF00FF.toInt(),
        "lime" to 0xFFCDDC39.toInt(), "olive" to 0xFF808000.toInt(),
        "navy" to 0xFF000080.toInt(), "maroon" to 0xFF800000.toInt(),
        "aqua" to 0xFF00FFFF.toInt(), "silver" to 0xFFC0C0C0.toInt(),
        "gold" to 0xFFFFD700.toInt(), "coral" to 0xFFFF7F50.toInt(),
        "salmon" to 0xFFFA8072.toInt(), "khaki" to 0xFFF0E68C.toInt(),
        "violet" to 0xFFEE82EE.toInt(), "turquoise" to 0xFF40E0D0.toInt(),
        "crimson" to 0xFFDC143C.toInt(), "tomato" to 0xFFFF6347.toInt()
    )
}
