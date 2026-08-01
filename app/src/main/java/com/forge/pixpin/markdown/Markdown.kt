package com.forge.pixpin.markdown

/** Tipo de decoración de un tramo de texto. */
enum class SpanKind { BOLD, ITALIC, STRIKE, CODE, LINK }

/**
 * Tramo con estilo dentro de un texto **ya limpio de marcas**: [start] y [end]
 * apuntan al texto sin los asteriscos, no al original.
 */
data class InlineSpan(
    val start: Int,
    val end: Int,
    val kind: SpanKind,
    val url: String? = null
)

/** Una línea ya interpretada: el texto sin marcas y los tramos que lo decoran. */
data class InlineText(val text: String, val spans: List<InlineSpan> = emptyList())

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val content: InlineText) : MarkdownBlock
    data class Paragraph(val content: InlineText) : MarkdownBlock
    data class Bullet(val content: InlineText) : MarkdownBlock
    data class Numbered(val number: Int, val content: InlineText) : MarkdownBlock
    data class Quote(val content: InlineText) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

/**
 * Un subconjunto de Markdown, suficiente para lo que se pega en un pin desde
 * unas notas o desde un chat.
 *
 * Es propio y no una librería por dos motivos. Uno, aquí **todo tiene que ser
 * función del zoom** —el pin de texto escala en proporción al pellizcar— y una
 * librería no deja gobernar los tamaños. Y dos, sin Compose ni Android dentro se
 * puede probar en la JVM, como el resto de la lógica pura del proyecto.
 *
 * La regla que gobierna todo el parser: **no perder texto nunca**. Una marca sin
 * cerrar se queda tal cual se escribió. El formateo está siempre activo y una
 * nota mal interpretada que se coma caracteres sería mucho peor que ver un
 * asterisco de más.
 */
object Markdown {

    private val NUMBERED = Regex("""^(\d{1,3})[.)]\s+(.*)$""")
    private val RULE = Regex("""^\s*([-*_])\1{2,}\s*$""")

    fun parse(source: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val lines = source.replace("\r\n", "\n").split('\n')
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            blocks += MarkdownBlock.Paragraph(parseInline(paragraph.joinToString(" ")))
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                flushParagraph()
                // Sin valla de cierre se llega hasta el final: tragarse el resto
                // del pin en silencio sería peor que mostrarlo como código.
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    body += lines[i]
                    i++
                }
                i++ // la valla de cierre, si la hay
                blocks += MarkdownBlock.Code(body.joinToString("\n"))
                continue
            }

            when {
                trimmed.isEmpty() -> flushParagraph()

                RULE.matches(trimmed) -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Rule
                }

                trimmed.startsWith("### ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Heading(3, parseInline(trimmed.removePrefix("### ")))
                }

                trimmed.startsWith("## ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Heading(2, parseInline(trimmed.removePrefix("## ")))
                }

                trimmed.startsWith("# ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Heading(1, parseInline(trimmed.removePrefix("# ")))
                }

                trimmed.startsWith("> ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Quote(parseInline(trimmed.removePrefix("> ")))
                }

                isBullet(trimmed) -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Bullet(parseInline(trimmed.substring(2)))
                }

                NUMBERED.matches(trimmed) -> {
                    flushParagraph()
                    val m = NUMBERED.find(trimmed)!!
                    blocks += MarkdownBlock.Numbered(
                        m.groupValues[1].toInt(), parseInline(m.groupValues[2])
                    )
                }

                else -> paragraph += trimmed
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    /**
     * Una viñeta necesita marcador Y espacio: sin el espacio, `*cursiva*` al
     * empezar una línea se convertiría en viñeta y se comería el asterisco.
     */
    private fun isBullet(line: String): Boolean =
        line.length > 2 && line[0] in "-*+" && line[1] == ' '

    /**
     * Recorre la línea de una pasada buscando aperturas de marca. Cuando
     * encuentra una, busca su cierre; si no lo hay, escribe la marca tal cual y
     * sigue — así nunca se pierde nada.
     *
     * El código inline va primero a propósito: dentro de un acento grave no se
     * interpreta nada, que es lo que se espera al pegar una ruta o una fórmula.
     */
    fun parseInline(line: String): InlineText {
        val out = StringBuilder()
        val spans = mutableListOf<InlineSpan>()
        var i = 0

        while (i < line.length) {
            val rest = line.length - i

            if (line[i] == '`') {
                val close = line.indexOf('`', i + 1)
                if (close > i) {
                    val start = out.length
                    out.append(line, i + 1, close)
                    spans += InlineSpan(start, out.length, SpanKind.CODE)
                    i = close + 1
                    continue
                }
            }

            if (rest >= 4 && line.startsWith("**", i)) {
                val close = line.indexOf("**", i + 2)
                if (close > i + 1) {
                    appendStyled(line, i + 2, close, SpanKind.BOLD, out, spans)
                    i = close + 2
                    continue
                }
            }

            if (rest >= 4 && line.startsWith("~~", i)) {
                val close = line.indexOf("~~", i + 2)
                if (close > i + 1) {
                    appendStyled(line, i + 2, close, SpanKind.STRIKE, out, spans)
                    i = close + 2
                    continue
                }
            }

            if (line[i] == '*' || line[i] == '_') {
                val close = line.indexOf(line[i], i + 1)
                // El cierre pegado a la apertura (`**`) no es cursiva vacía: es
                // una negrita rota, y se deja pasar como texto.
                if (close > i + 1) {
                    appendStyled(line, i + 1, close, SpanKind.ITALIC, out, spans)
                    i = close + 1
                    continue
                }
            }

            if (line[i] == '[') {
                val closeText = line.indexOf(']', i + 1)
                if (closeText > i && closeText + 1 < line.length && line[closeText + 1] == '(') {
                    val closeUrl = line.indexOf(')', closeText + 2)
                    if (closeUrl > closeText) {
                        val start = out.length
                        out.append(line, i + 1, closeText)
                        spans += InlineSpan(
                            start, out.length, SpanKind.LINK,
                            url = line.substring(closeText + 2, closeUrl)
                        )
                        i = closeUrl + 1
                        continue
                    }
                }
            }

            out.append(line[i])
            i++
        }
        return InlineText(out.toString(), spans)
    }

    /** Copia el contenido entre marcas y le apunta su tramo. */
    private fun appendStyled(
        line: String,
        from: Int,
        to: Int,
        kind: SpanKind,
        out: StringBuilder,
        spans: MutableList<InlineSpan>
    ) {
        val start = out.length
        out.append(line, from, to)
        spans += InlineSpan(start, out.length, kind)
    }
}
