package com.forge.pixpin.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.text.style.TextAlign
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
                    tabla = bloque,
                    baseSizeSp = baseSizeSp,
                    onCambio = { nueva ->
                        val t = trozosDe(texto)[i]
                        val cola = t.de(texto).takeLastWhile { it == '\n' }
                        onTexto(
                            texto.substring(0, t.desde) + Tablas.aTexto(nueva) + cola +
                                texto.substring(t.hasta)
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
                    onRetroceso = {
                        // Primero se va la marca del bloque —la casilla, la
                        // viñeta, la almohadilla—; solo después se junta con el
                        // de arriba. Dos pasos, como en cualquier editor: así
                        // quitar una casilla no se lleva por delante el renglón.
                        if (Vivo.tipo(texto, i) != null) {
                            onTexto(Vivo.quitarTipo(texto, i))
                            onSitio(Sitio(i, TextRange(0)))
                            true
                        } else {
                            Vivo.juntarConElDeArriba(texto, i)?.let { (doc, donde) ->
                                onTexto(doc)
                                onSitio(donde)
                            } != null
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
    tabla: MarkdownBlock.Tabla,
    baseSizeSp: Float,
    onCambio: (MarkdownBlock.Tabla) -> Unit
) {
    var marcado by remember(tabla.filas.size, tabla.columnas) {
        mutableStateOf<Tablas.Marcado?>(null)
    }
    var clase by remember { mutableStateOf(ClaseDeMarcado.CELDA) }
    var menuAbierto by remember { mutableStateOf(false) }

    val rejilla = remember(tabla) { Tablas.rejilla(tabla) }
    val marca = marcado

    /** El ancla que manda: la esquina de arriba a la izquierda de lo marcado. */
    fun anclaDelMarcado(): Tablas.Hueco? =
        marca?.let { rejilla.getOrNull(it.filas.first)?.getOrNull(it.columnas.first) }

    Column(Modifier.fillMaxWidth()) {
        // El título, su `pageBlockTable.title`. Se escribe aquí mismo.
        CeldaEscribible(
            texto = tabla.titulo.text,
            onTexto = { onCambio(Tablas.conTitulo(tabla, InlineText(it))) },
            onFoco = { menuAbierto = false },
            modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp),
            textStyle = TextStyle(
                fontSize = (baseSizeSp * 0.95f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )

        if (menuAbierto && marca != null) {
            MenuDeTabla(
                clase = clase,
                puedeCombinar = !marca.esUnaSola,
                puedeSeparar = anclaDelMarcado()?.celda?.let {
                    it.anchoEnColumnas > 1 || it.altoEnFilas > 1
                } == true,
                onAccion = { accion ->
                    onCambio(aplicarEnLaTabla(tabla, marca, accion))
                    menuAbierto = false
                }
            )
        }

        RejillaDeTabla(
            tabla = tabla,
            marcado = marca,
            conAsas = true,
            // Tocar un asa marca la fila o la columna **entera** y abre el menú:
            // es lo único que se quiere hacer desde ahí.
            onColumna = { c ->
                marcado = Tablas.Marcado.columna(c, tabla.filas.size)
                clase = ClaseDeMarcado.COLUMNA
                menuAbierto = true
            },
            onFila = { f ->
                marcado = Tablas.Marcado.fila(f, tabla.columnas)
                clase = ClaseDeMarcado.FILA
                menuAbierto = true
            },
            onCelda = { f, c ->
                marcado = Tablas.Marcado.celda(f, c)
                clase = ClaseDeMarcado.CELDA
                menuAbierto = false
            },
            // Mantener pulsada **estira lo marcado** hasta aquí y abre el menú.
            // Así el mismo gesto sirve para pedir el menú de una celda y para
            // marcar varias y combinarlas, sin arrastrar —que en una tabla
            // dentro de una nota se pelearía con el desplazamiento.
            // Mantener pulsado empieza el grupo aquí; arrastrar lo estira.
            onCeldaLarga = { f, c ->
                marcado = Tablas.Marcado.celda(f, c)
                clase = ClaseDeMarcado.CELDA
                menuAbierto = true
            },
            onArrastre = { f, c ->
                val desde = marca ?: return@RejillaDeTabla
                marcado = Tablas.Marcado(desde.f1, desde.c1, f, c)
            }
        ) { ancla ->
            CeldaEscribible(
                texto = ancla.celda.contenido.text,
                onTexto = {
                    // La fila y el índice salen del ancla: con una fusión, la
                    // celda que se ve en la columna 2 puede ser la primera de la
                    // lista de su fila. Ver [Ancla.indiceEnLaFila].
                    onCambio(
                        Tablas.conCelda(tabla, ancla.fila, ancla.indiceEnLaFila, InlineText(it))
                    )
                },
                onFoco = {
                    marcado = Tablas.Marcado.celda(ancla.fila, ancla.columna)
                    clase = ClaseDeMarcado.CELDA
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    fontSize = (baseSizeSp * 0.92f).sp,
                    fontWeight = if (ancla.celda.cabecera) FontWeight.Bold else FontWeight.Normal,
                    textAlign = when (ancla.celda.alineacion) {
                        Alineacion.CENTRO -> TextAlign.Center
                        Alineacion.DERECHA -> TextAlign.End
                        else -> TextAlign.Start
                    },
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

/**
 * Una celda que se puede escribir sin que el cursor salte.
 *
 * El texto de la tabla se guarda **en Markdown**, así que en cada tecla se
 * escribe la tabla entera y se vuelve a leer. Pasándole al campo solo la cadena,
 * cada vuelta lo obligaba a rehacer su estado y el cursor se iba al principio;
 * escribir dos letras seguidas era imposible.
 *
 * Con la posición del cursor guardada aquí, el campo manda sobre lo que se está
 * escribiendo y solo se rinde cuando el texto **le llega cambiado desde fuera y
 * no lo tiene delante** —por deshacer, por combinar celdas—, que es justo cuando
 * hay que rendirse.
 */
@Composable
private fun CeldaEscribible(
    texto: String,
    onTexto: (String) -> Unit,
    onFoco: () -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle
) {
    var valor by remember { mutableStateOf(TextFieldValue(texto, TextRange(texto.length))) }
    var conFoco by remember { mutableStateOf(false) }

    if (!conFoco && valor.text != texto) {
        valor = TextFieldValue(texto, TextRange(texto.length))
    }

    BasicTextField(
        value = valor,
        onValueChange = {
            valor = it
            if (it.text != texto) onTexto(it.text)
        },
        modifier = modifier.onFocusChanged {
            conFoco = it.isFocused
            if (it.isFocused) onFoco()
        },
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
    )
}

/**
 * Lo que hace cada acción del menú sobre lo marcado.
 *
 * Todo va **sobre el rectángulo**, no sobre una celda: marcar una columna y
 * centrarla centra sus cinco celdas de una vez, que es lo que se espera al haber
 * tocado el asa de la columna.
 */
private fun aplicarEnLaTabla(
    tabla: MarkdownBlock.Tabla,
    marcado: Tablas.Marcado,
    accion: AccionDeTabla
): MarkdownBlock.Tabla {
    val f = marcado.filas.first
    val c = marcado.columnas.first
    return when (accion) {
        AccionDeTabla.IZQUIERDA ->
            Tablas.enElMarcado(tabla, marcado) { it.copy(alineacion = Alineacion.IZQUIERDA) }
        AccionDeTabla.CENTRO ->
            Tablas.enElMarcado(tabla, marcado) { it.copy(alineacion = Alineacion.CENTRO) }
        AccionDeTabla.DERECHA ->
            Tablas.enElMarcado(tabla, marcado) { it.copy(alineacion = Alineacion.DERECHA) }
        AccionDeTabla.ARRIBA ->
            Tablas.enElMarcado(tabla, marcado) { it.copy(altura = AlturaEnCelda.ARRIBA) }
        AccionDeTabla.MEDIO ->
            Tablas.enElMarcado(tabla, marcado) { it.copy(altura = AlturaEnCelda.MEDIO) }
        AccionDeTabla.ABAJO ->
            Tablas.enElMarcado(tabla, marcado) { it.copy(altura = AlturaEnCelda.ABAJO) }
        // Destacar alterna: si ya lo está todo lo marcado, lo apaga.
        AccionDeTabla.DESTACAR -> {
            val yaLoEstan = Tablas.rejilla(tabla).let { r ->
                marcado.filas.all { ff ->
                    marcado.columnas.all { cc ->
                        r.getOrNull(ff)?.getOrNull(cc)?.celda?.cabecera == true
                    }
                }
            }
            Tablas.enElMarcado(tabla, marcado) { it.copy(cabecera = !yaLoEstan) }
        }
        AccionDeTabla.COMBINAR -> Tablas.fusionar(
            tabla, marcado.filas.first, marcado.columnas.first,
            marcado.filas.last, marcado.columnas.last
        )
        AccionDeTabla.SEPARAR -> Tablas.separar(tabla, f, c)
        AccionDeTabla.FILA_ARRIBA -> Tablas.insertarFila(tabla, f)
        AccionDeTabla.FILA_ABAJO -> Tablas.insertarFila(tabla, marcado.filas.last + 1)
        AccionDeTabla.COLUMNA_IZQUIERDA -> Tablas.insertarColumna(tabla, c)
        AccionDeTabla.COLUMNA_DERECHA ->
            Tablas.insertarColumna(tabla, marcado.columnas.last + 1)
        AccionDeTabla.QUITAR_FILA -> Tablas.quitarFilas(tabla, marcado.filas.toSet())
        AccionDeTabla.QUITAR_COLUMNA -> Tablas.quitarColumnas(tabla, marcado.columnas.toSet())
    }
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
    onRetroceso: () -> Boolean
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
                // Los estilos se recolocan con el cambio: escribir dentro de una
                // palabra en negrita la deja en negrita, borrarla se la lleva.
                val spans = Inline.desplazar(contenido.text, v.text, contenido.spans)
                onCambio(InlineText(v.text, spans), v.selection)
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(foco)
                // El retroceso al principio del bloque hay que cazarlo por
                // tecla: si el bloque ya está vacío, borrar no cambia el texto,
                // así que no llega ningún aviso de cambio y el bloque se queda
                // ahí para siempre. Era el caso de la casilla que no se podía
                // quitar.
                .onKeyEvent { evento ->
                    val esRetroceso = evento.type == KeyEventType.KeyDown &&
                        evento.key == Key.Backspace
                    if (esRetroceso && seleccion.collapsed && seleccion.start == 0) {
                        onRetroceso()
                    } else {
                        false
                    }
                },
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
