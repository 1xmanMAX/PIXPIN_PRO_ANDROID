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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
/**
 * Dónde cae algo tocable dentro del contenido, en coordenadas SIN escalar.
 *
 * Lleva [url] si es un enlace y [oculto] si es un tapado que hay que destapar.
 * Van juntos porque el toque llega por el mismo sitio —el reconocedor que
 * comparten todos los pines— y quien lo recibe solo necesita saber qué hacer con
 * el rectángulo que pilló.
 */
data class LinkHit(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val url: String? = null,
    val oculto: String? = null
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

@Composable
fun MarkdownText(
    blocks: List<MarkdownBlock>,
    baseSizeSp: Float,
    modifier: Modifier = Modifier,
    ocultosVisibles: Set<String> = emptySet(),
    onLinks: (List<LinkHit>) -> Unit = {}
) {
    // Los rectángulos de cada bloque se juntan aquí para publicarlos de una vez:
    // quien hace la prueba de impacto necesita la lista entera, no trozos.
    val perBlock = remember { mutableStateMapOf<Int, List<LinkHit>>() }
    LaunchedEffect(perBlock.size, blocks) {
        onLinks(perBlock.values.flatten())
    }
    val gap = (baseSizeSp * 0.35f).dp
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(gap))
            // Todos los bloques publican sus rectángulos, no solo el párrafo:
            // un enlace dentro de una viñeta o de una cita también se toca.
            val recoge: (List<LinkHit>) -> Unit = { perBlock[index] = it }
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
                        bloque = index,
                        ocultosVisibles = ocultosVisibles,
                        weight = FontWeight.Bold,
                        onLinks = recoge
                    )
                }

                is MarkdownBlock.Paragraph -> Body(
                    content = block.content,
                    sizeSp = baseSizeSp,
                    bloque = index,
                    ocultosVisibles = ocultosVisibles,
                    onLinks = recoge
                )

                is MarkdownBlock.Bullet -> Row {
                    Marker("•  ", baseSizeSp)
                    Body(block.content, baseSizeSp, index, ocultosVisibles, onLinks = recoge)
                }

                is MarkdownBlock.Numbered -> Row {
                    Marker("${block.number}.  ", baseSizeSp)
                    Body(block.content, baseSizeSp, index, ocultosVisibles, onLinks = recoge)
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
                        bloque = index,
                        ocultosVisibles = ocultosVisibles,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        onLinks = recoge
                    )
                }

                is MarkdownBlock.Code -> Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    // El lenguaje, si se escribió, en pequeño y arriba: es lo
                    // único que dice de qué es el bloque al releer la nota.
                    if (block.lenguaje.isNotEmpty()) {
                        Text(
                            text = block.lenguaje,
                            fontSize = (baseSizeSp * 0.7f).sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height((baseSizeSp * 0.2f).dp))
                    }
                    Text(
                        text = block.text,
                        fontSize = (baseSizeSp * 0.92f).sp,
                        lineHeight = (baseSizeSp * 1.3f).sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

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
    bloque: Int,
    ocultosVisibles: Set<String>,
    weight: FontWeight = FontWeight.Normal,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onLinks: (List<LinkHit>) -> Unit = {}
) {
    val tramos = remember(content) { content.tramos() }
    // Lo tocable: los enlaces, y los tapados que aún no se han destapado.
    val links = tramos.filter {
        (it.tiene(SpanKind.LINK) && it.url != null) ||
            (it.tiene(SpanKind.SPOILER) && claveDeOculto(bloque, it) !in ocultosVisibles)
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Text(
        text = content.annotated(
            tramos = tramos,
            linkColor = MaterialTheme.colorScheme.primary,
            tapado = MaterialTheme.colorScheme.onSurfaceVariant,
            bloque = bloque,
            ocultosVisibles = ocultosVisibles
        ),
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.4f).sp,
        fontWeight = weight,
        color = color,
        modifier = if (links.isEmpty()) Modifier else Modifier.onGloballyPositioned {
            origin = it.positionInRoot()
        },
        onTextLayout = { layout ->
            if (links.isEmpty()) return@Text
            // Un enlace puede partirse en varias líneas, así que se recogen las
            // cajas de cada carácter y se agrupan por línea: una sola caja
            // envolvente daría por tocable medio párrafo.
            onLinks(
                links.flatMap { tramo ->
                    val byLine = (tramo.inicio until tramo.fin)
                        .groupBy { layout.getLineForOffset(it) }
                    byLine.values.mapNotNull { offsets ->
                        val boxes = offsets.map { layout.getBoundingBox(it) }
                        if (boxes.isEmpty()) return@mapNotNull null
                        LinkHit(
                            left = origin.x + boxes.minOf { it.left },
                            top = origin.y + boxes.minOf { it.top },
                            right = origin.x + boxes.maxOf { it.right },
                            bottom = origin.y + boxes.maxOf { it.bottom },
                            // Un tapado se destapa antes de abrir nada: si lleva
                            // enlace debajo, el primer toque lo enseña y el
                            // segundo lo abre.
                            url = if (tramo.tiene(SpanKind.SPOILER)) null else tramo.url,
                            oculto = if (tramo.tiene(SpanKind.SPOILER)) {
                                claveDeOculto(bloque, tramo)
                            } else {
                                null
                            }
                        )
                    }
                }
            )
        }
    )
}

/**
 * Cómo se reconoce un tapado entre un toque y el siguiente.
 *
 * El bloque y dónde empieza bastan y no hacen falta identificadores guardados: si
 * el texto cambia, el tapado es otro y volver a esconderlo es lo correcto.
 */
private fun claveDeOculto(bloque: Int, tramo: Tramo): String = "$bloque:${tramo.inicio}"

/**
 * Traduce cada tramo a **un** estilo con todo lo que lleva dentro.
 *
 * Antes se recorrían las marcas sueltas y cada una ponía su estilo por su cuenta.
 * Ahora se pintan los tramos ya aplanados —ver [tramosDe]—, que es lo que hace
 * que negrita y cursiva a la vez sean una sola decisión y no dos que hay que
 * confiar en que se sumen bien.
 */
private fun InlineText.annotated(
    tramos: List<Tramo>,
    linkColor: Color,
    tapado: Color,
    bloque: Int,
    ocultosVisibles: Set<String>
): AnnotatedString = buildAnnotatedString {
    append(text)
    tramos.forEach { tramo ->
        // El parser trabaja sobre el texto ya limpio, pero un tramo corrupto no
        // puede tumbar el pin: buildAnnotatedString lanza si el rango se sale.
        if (tramo.inicio < 0 || tramo.fin > text.length || tramo.inicio >= tramo.fin) {
            return@forEach
        }

        var style = SpanStyle()
        if (tramo.tiene(SpanKind.BOLD)) style = style.copy(fontWeight = FontWeight.Bold)
        if (tramo.tiene(SpanKind.ITALIC)) style = style.copy(fontStyle = FontStyle.Italic)
        if (tramo.tiene(SpanKind.CODE)) style = style.copy(fontFamily = FontFamily.Monospace)
        if (tramo.tiene(SpanKind.LINK)) {
            style = style.copy(color = linkColor, textDecoration = TextDecoration.Underline)
        }
        // El tachado gana al subrayado del enlace: un enlace tachado es un enlace
        // que ya no vale, y esa es la información que importa.
        if (tramo.tiene(SpanKind.STRIKE)) {
            style = style.copy(textDecoration = TextDecoration.LineThrough)
        }
        // Tapado: la letra y el fondo del mismo color, que deja una barra maciza
        // sin cambiar cuánto ocupa el texto. Al destaparlo se cae solo en su
        // sitio porque nunca dejó de estar ahí.
        if (tramo.tiene(SpanKind.SPOILER) &&
            claveDeOculto(bloque, tramo) !in ocultosVisibles
        ) {
            style = style.copy(color = tapado, background = tapado)
        }

        addStyle(style, tramo.inicio, tramo.fin)
    }
}
