package com.forge.pixpin.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Cuánto crece cada nivel de título respecto al tamaño base. */
private const val H1 = 1.6f
private const val H2 = 1.35f
private const val H3 = 1.15f

/**
 * Dibuja los bloques ya interpretados por [Markdown].
 *
 * [baseSizeSp] llega **ya multiplicado por el zoom del pin**, y todo lo demás se
 * deriva de él: así el renderizador no sabe nada del pellizco y el pin entero
 * escala en proporción, letra y cuadro a la vez.
 */
@Composable
fun MarkdownText(
    blocks: List<MarkdownBlock>,
    baseSizeSp: Float,
    modifier: Modifier = Modifier
) {
    val gap = (baseSizeSp * 0.35f).dp
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(gap))
            when (block) {
                is MarkdownBlock.Heading -> {
                    val factor = when (block.level) {
                        1 -> H1
                        2 -> H2
                        else -> H3
                    }
                    Body(
                        content = block.content,
                        sizeSp = baseSizeSp * factor,
                        weight = FontWeight.Bold
                    )
                }

                is MarkdownBlock.Paragraph -> Body(block.content, baseSizeSp)

                is MarkdownBlock.Bullet -> Row {
                    Marker("•  ", baseSizeSp)
                    Body(block.content, baseSizeSp)
                }

                is MarkdownBlock.Numbered -> Row {
                    Marker("${block.number}.  ", baseSizeSp)
                    Body(block.content, baseSizeSp)
                }

                is MarkdownBlock.Quote -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height((baseSizeSp * 1.4f).dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Body(
                        content = block.content,
                        sizeSp = baseSizeSp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is MarkdownBlock.Code -> Text(
                    text = block.text,
                    fontSize = (baseSizeSp * 0.92f).sp,
                    lineHeight = (baseSizeSp * 1.3f).sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )

                MarkdownBlock.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

/** La viñeta o el número, que no deben heredar los estilos del contenido. */
@Composable
private fun Marker(text: String, sizeSp: Float) {
    Text(
        text = text,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.4f).sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Body(
    content: InlineText,
    sizeSp: Float,
    weight: FontWeight = FontWeight.Normal,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Text(
        text = content.annotated(MaterialTheme.colorScheme.primary),
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.4f).sp,
        fontWeight = weight,
        color = color
    )
}

/**
 * Traduce los tramos a estilos. Los enlaces se pintan pero **no se tocan**:
 * distinguir un toque sobre un enlace del resto obligaría a meter mano en
 * OverlayTouchHandler, que es el reconocedor que comparten todos los pines y la
 * bola flotante.
 */
private fun InlineText.annotated(linkColor: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    spans.forEach { span ->
        // El parser trabaja sobre el texto ya limpio, pero un tramo corrupto no
        // puede tumbar el pin: buildAnnotatedString lanza si el rango se sale.
        if (span.start < 0 || span.end > text.length || span.start >= span.end) return@forEach
        val style = when (span.kind) {
            SpanKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            SpanKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            SpanKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            SpanKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
            SpanKind.LINK -> SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline
            )
        }
        addStyle(style, span.start, span.end)
    }
}
