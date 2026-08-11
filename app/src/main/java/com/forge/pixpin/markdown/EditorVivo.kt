package com.forge.pixpin.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * El editor: **lo que se ve es el resultado, siempre**.
 *
 * No hay vista previa porque no hay nada que previsualizar. Un título se ve
 * grande mientras lo escribes, la negrita se ve negrita, la tabla se ve con sus
 * líneas y la casilla con su casilla. **Nunca se ve una almohadilla, ni un
 * asterisco, ni una barra**, ni siquiera en el bloque donde está el cursor.
 *
 * ## Cómo se consigue
 *
 * Igual que Telegram: el texto y el formato van por separado. Cada bloque se lee
 * del Markdown y se parte en dos —el texto limpio y sus estilos—, se edita eso,
 * y al escribir se vuelve a juntar. Ver [Vivo] y [Inline].
 *
 * En pantalla los estilos se aplican con una `VisualTransformation` cuya
 * correspondencia de posiciones es **la identidad**: como el texto que se edita
 * ya está limpio, no hay ningún carácter que esconder, así que no hay forma de
 * que el cursor acabe en un sitio distinto del que se ve. Esa es justo la parte
 * que se rompe en los editores que van tapando las marcas.
 *
 * El adorno del bloque —la viñeta, la casilla, la barra de la cita— se pinta
 * **fuera** del campo de texto, no dentro. Así no se puede borrar sin querer y no
 * ocupa sitio en lo escrito.
 */
@Composable
fun EditorVivo(
    texto: String,
    sitio: Sitio?,
    onTexto: (String) -> Unit,
    onSitio: (Sitio?) -> Unit,
    baseSizeSp: Float = 16f,
    modifier: Modifier = Modifier,
    ocultosVisibles: Set<String> = emptySet()
) {
    val trozos = remember(texto) { trozosDe(texto) }

    Column(modifier) {
        trozos.forEachIndexed { i, trozo ->
            val fuente = trozo.de(texto).trimEnd('\n')
            val bloque = remember(fuente) { Markdown.parse(fuente).firstOrNull() }
            val activo = sitio?.bloque == i

            if (activo && bloque is MarkdownBlock.Tabla) {
                TablaEditable(
                    fuente = fuente,
                    tabla = bloque,
                    celda = sitio.celda,
                    baseSizeSp = baseSizeSp,
                    onCelda = { c -> onSitio(Sitio(i, TextRange.Zero, c)) },
                    onCambio = { nueva ->
                        val trozos2 = trozosDe(texto)
                        val t = trozos2[i]
                        val cola = t.de(texto).takeLastWhile { it == '\n' }
                        onTexto(
                            texto.substring(0, t.desde) + nueva + cola + texto.substring(t.hasta)
                        )
                    }
                )
            } else if (activo && sePuedeEscribir(bloque)) {
                BloqueEditable(
                    bloque = bloque,
                    contenido = remember(fuente) {
                        Vivo.contenidoDelTrozo(fuente) ?: InlineText("")
                    },
                    seleccion = sitio.seleccion,
                    baseSizeSp = baseSizeSp,
                    onCambio = { nuevo, sel ->
                        onTexto(Vivo.conContenido(texto, i, nuevo))
                        onSitio(Sitio(i, sel))
                    },
                    onIntro = { contenido, pos ->
                        val (doc, donde) = Vivo.partir(texto, i, contenido, pos)
                        onTexto(doc)
                        onSitio(donde)
                    },
                    onJuntar = {
                        Vivo.juntarConElDeArriba(texto, i)?.let { (doc, donde) ->
                            onTexto(doc)
                            onSitio(donde)
                        }
                    }
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val largo = Vivo.contenidoDelTrozo(fuente)?.text?.length ?: 0
                            onSitio(Sitio(i, TextRange(largo)))
                        }
                ) {
                    if (bloque == null) {
                        // Un renglón vacío tiene que seguir ocupando su sitio o
                        // no habría dónde tocar para escribir ahí.
                        Spacer(Modifier.fillMaxWidth().height((baseSizeSp * 1.5f).dp))
                    } else {
                        MarkdownText(
                            blocks = listOf(bloque),
                            baseSizeSp = baseSizeSp,
                            ocultosVisibles = ocultosVisibles
                        )
                    }
                }
            }
        }
    }
}

/**
 * La tabla, **con un campo por celda**.
 *
 * Es su `RichTableCell`: la rejilla se dibuja y dentro de cada hueco hay un campo
 * normal. Nunca aparece una barra ni una fila de guiones; lo que se ve es la
 * tabla y lo que se escribe es su contenido.
 */
@Composable
private fun TablaEditable(
    fuente: String,
    tabla: MarkdownBlock.Tabla,
    celda: Int,
    baseSizeSp: Float,
    onCelda: (Int) -> Unit,
    onCambio: (String) -> Unit
) {
    val borde = MaterialTheme.colorScheme.outlineVariant
    val columnas = tabla.filas.maxOfOrNull { it.size } ?: 0

    Column(Modifier.fillMaxWidth()) {
        // La fila de asas de columna, como su `colHandleAtGrid`: se toca una y
        // se actúa sobre esa columna entera. Sin ellas, «quitar columna» tenía
        // que adivinar cuál, y adivinar sobre algo que borra no vale.
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(ASA))
            (0 until columnas).forEach { c ->
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 1.dp)
                        .height(ASA)
                        .background(
                            if (celda >= 0 && celda % columnas == c) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                borde
                            },
                            RoundedCornerShape(2.dp)
                        )
                        .clickable { onCelda(c) }
                )
            }
        }
        Spacer(Modifier.height(2.dp))

        tabla.filas.forEachIndexed { f, fila ->
            Row(Modifier.fillMaxWidth()) {
                // El asa de la fila.
                Box(
                    Modifier
                        .width(ASA)
                        .height((baseSizeSp * 2.4f).dp)
                        .padding(vertical = 1.dp)
                        .background(
                            if (celda >= 0 && celda / columnas == f) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                borde
                            },
                            RoundedCornerShape(2.dp)
                        )
                        .clickable { onCelda(f * columnas) }
                )
                (0 until columnas).forEach { c ->
                    val esCabecera = tabla.cabecera && f == 0
                    val indice = f * columnas + c
                    Box(
                        Modifier
                            .weight(1f)
                            .background(
                                if (esCabecera) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    Color.Transparent
                                }
                            )
                            .padding(1.dp)
                            .clickable { onCelda(indice) }
                    ) {
                        val contenido = fila.getOrNull(c)?.text.orEmpty()
                        BasicTextField(
                            value = contenido,
                            onValueChange = { onCambio(Tablas.conCelda(fuente, f, c, it)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            textStyle = TextStyle(
                                fontSize = (baseSizeSp * 0.92f).sp,
                                fontWeight = if (esCabecera) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                        )
                    }
                    if (c < columnas - 1) {
                        Box(Modifier.width(1.dp).height((baseSizeSp * 2.4f).dp).background(borde))
                    }
                }
            }
            if (f < tabla.filas.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(borde))
            }
        }
    }
    // `celda` se guarda para que la barra de tabla sepa sobre qué columna actuar.
    @Suppress("UNUSED_EXPRESSION") celda
}

/** El grosor de las asas de fila y columna. */
private val ASA = 6.dp

/** Los bloques cuyo contenido se escribe a mano. Una imagen o una raya, no. */
private fun sePuedeEscribir(bloque: MarkdownBlock?): Boolean = when (bloque) {
    null,
    is MarkdownBlock.Paragraph,
    is MarkdownBlock.Heading,
    is MarkdownBlock.Quote,
    is MarkdownBlock.Bullet,
    is MarkdownBlock.Numbered,
    is MarkdownBlock.Tarea,
    is MarkdownBlock.Code,
    is MarkdownBlock.Formula -> true
    else -> false
}

/**
 * El bloque que se está escribiendo: su adorno fuera y su texto dentro, con los
 * estilos ya puestos.
 */
@Composable
private fun BloqueEditable(
    bloque: MarkdownBlock?,
    contenido: InlineText,
    seleccion: TextRange,
    baseSizeSp: Float,
    onCambio: (InlineText, TextRange) -> Unit,
    onIntro: (InlineText, Int) -> Unit,
    onJuntar: () -> Unit
) {
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }

    val campo = @Composable { estilo: TextStyle, color: Color ->
        BasicTextField(
            value = TextFieldValue(contenido.text, seleccion),
            onValueChange = { v ->
                // El intro no mete un salto de línea: **parte el bloque**. Es lo
                // que distingue un editor por bloques de un cuadro de texto, y
                // sin esto un título de dos renglones dejaba el segundo suelto.
                val salto = v.text.indexOf('\n')
                if (salto >= 0) {
                    val sinSalto = v.text.removeRange(salto, salto + 1)
                    val spans = Inline.desplazar(contenido.text, sinSalto, contenido.spans)
                    onIntro(InlineText(sinSalto, spans), salto)
                    return@BasicTextField
                }
                // Borrar hacia atrás con el bloque ya vacío lo junta con el de
                // arriba, en vez de no hacer nada, que es lo que parece roto.
                if (v.text.isEmpty() && contenido.text.isEmpty()) {
                    onJuntar()
                    return@BasicTextField
                }
                // Los estilos se recolocan con el cambio: escribir dentro de una
                // palabra en negrita la deja en negrita, borrarla se la lleva.
                val spans = Inline.desplazar(contenido.text, v.text, contenido.spans)
                onCambio(InlineText(v.text, spans), v.selection)
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(foco),
            textStyle = estilo.copy(color = color),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = EstilosEnVivo(
                contenido.spans,
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }

    val normal = TextStyle(
        fontSize = baseSizeSp.sp,
        lineHeight = (baseSizeSp * 1.5f).sp
    )
    val alFrente = MaterialTheme.colorScheme.onSurface
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant

    when (bloque) {
        is MarkdownBlock.Heading -> campo(
            normal.copy(
                fontSize = (baseSizeSp * factorDelTitulo(bloque.level)).sp,
                lineHeight = (baseSizeSp * factorDelTitulo(bloque.level) * 1.35f).sp,
                fontWeight = FontWeight.Bold
            ),
            alFrente
        )

        is MarkdownBlock.Bullet -> Row {
            Adorno("•  ", baseSizeSp)
            campo(normal, alFrente)
        }

        is MarkdownBlock.Numbered -> Row {
            Adorno("${bloque.number}.  ", baseSizeSp)
            campo(normal, alFrente)
        }

        is MarkdownBlock.Tarea -> Row(verticalAlignment = Alignment.CenterVertically) {
            Adorno(if (bloque.hecha) "☑  " else "☐  ", baseSizeSp)
            campo(normal, if (bloque.hecha) apagado else alFrente)
        }

        is MarkdownBlock.Quote -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height((baseSizeSp * 1.5f).dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(8.dp))
            campo(normal, apagado)
        }

        is MarkdownBlock.Code, is MarkdownBlock.Formula -> Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            campo(normal.copy(fontFamily = FontFamily.Monospace), apagado)
        }

        else -> campo(normal, alFrente)
    }
}

@Composable
private fun Adorno(texto: String, baseSizeSp: Float) {
    androidx.compose.material3.Text(
        text = texto,
        fontSize = baseSizeSp.sp,
        lineHeight = (baseSizeSp * 1.5f).sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun factorDelTitulo(nivel: Int): Float = when (nivel) {
    1 -> 1.6f
    2 -> 1.35f
    3 -> 1.15f
    4 -> 1.05f
    5 -> 1f
    else -> 0.95f
}

/**
 * Pinta los estilos sobre el texto que se está escribiendo.
 *
 * La correspondencia de posiciones es **la identidad**, y eso no es un atajo: es
 * lo que hace que esto sea seguro. El texto que edita el campo ya viene limpio,
 * así que no hay ningún carácter escondido y la posición 7 de lo que se ve es la
 * posición 7 de lo que hay. Los editores que van tapando asteriscos tienen que
 * traducir posiciones en los dos sentidos, y ahí es donde el cursor acaba
 * saltando o borrando lo que no es.
 */
private class EstilosEnVivo(
    private val spans: List<InlineSpan>,
    private val colorEnlace: Color,
    private val colorTapado: Color
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val tramos = tramosDe(spans, text.length)
        val pintado = buildAnnotatedString {
            append(text.text)
            tramos.forEach { tramo ->
                if (tramo.inicio < 0 || tramo.fin > text.length) return@forEach
                var estilo = SpanStyle()
                if (tramo.tiene(SpanKind.BOLD)) estilo = estilo.copy(fontWeight = FontWeight.Bold)
                if (tramo.tiene(SpanKind.ITALIC)) {
                    estilo = estilo.copy(fontStyle = FontStyle.Italic)
                }
                if (tramo.tiene(SpanKind.CODE)) {
                    estilo = estilo.copy(fontFamily = FontFamily.Monospace)
                }
                if (tramo.tiene(SpanKind.LINK)) {
                    estilo = estilo.copy(
                        color = colorEnlace,
                        textDecoration = TextDecoration.Underline
                    )
                }
                if (tramo.tiene(SpanKind.STRIKE)) {
                    estilo = estilo.copy(textDecoration = TextDecoration.LineThrough)
                }
                // El tapado se ve mientras se escribe: taparlo al autor de la
                // nota no protege a nadie y le impediría corregirlo.
                if (tramo.tiene(SpanKind.SPOILER)) {
                    estilo = estilo.copy(background = colorTapado.copy(alpha = 0.25f))
                }
                addStyle(estilo, tramo.inicio, tramo.fin)
            }
        }
        return TransformedText(pintado, OffsetMapping.Identity)
    }
}
