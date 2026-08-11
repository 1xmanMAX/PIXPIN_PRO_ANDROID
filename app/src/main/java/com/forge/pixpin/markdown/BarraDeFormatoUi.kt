package com.forge.pixpin.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun iconoDeBloque(tipo: TipoDeBloque): ImageVector = when (tipo) {
    TipoDeBloque.TITULO_1, TipoDeBloque.TITULO_2, TipoDeBloque.TITULO_3,
    TipoDeBloque.TITULO_4, TipoDeBloque.TITULO_5, TipoDeBloque.TITULO_6 -> Icons.Filled.Title
    TipoDeBloque.CITA -> Icons.Filled.FormatQuote
    TipoDeBloque.DESTACADO -> Icons.Filled.Campaign
    TipoDeBloque.CODIGO -> Icons.Filled.Code
    TipoDeBloque.PIE -> Icons.Filled.Notes
    TipoDeBloque.LISTA -> Icons.Filled.FormatListBulleted
    TipoDeBloque.NUMERADA -> Icons.Filled.FormatListNumbered
    TipoDeBloque.TAREAS -> Icons.Filled.CheckBox
    TipoDeBloque.PLEGABLE -> Icons.Filled.UnfoldMore
    TipoDeBloque.TABLA -> Icons.Filled.TableChart
    TipoDeBloque.FORMULA -> Icons.Filled.Functions
    TipoDeBloque.SEPARADOR -> Icons.Filled.HorizontalRule
    TipoDeBloque.IMAGEN -> Icons.Filled.Image
    TipoDeBloque.VIDEO -> Icons.Filled.Movie
    TipoDeBloque.AUDIO -> Icons.Filled.AudioFile
    TipoDeBloque.ARCHIVO -> Icons.Filled.AttachFile
    TipoDeBloque.CENTRAR -> Icons.Filled.FormatAlignCenter
    TipoDeBloque.DERECHA -> Icons.Filled.FormatAlignRight
}

/**
 * La barra de formato, la misma en la nota flotante y en el editor avanzado.
 *
 * Es una sola porque la organización **es** lo que se está copiando de Telegram:
 * tener dos barras con los mismos botones en distinto orden sería quedarse con
 * el trabajo de portarlo y ninguna de sus ventajas. Ver [BarraDeFormato] para el
 * reparto y [Formato] para el orden.
 *
 * Lo que se toma de su `FloatingToolbarPopup`:
 *
 * - **Panel principal y desbordamiento.** Lo de todos los días a la vista, el
 *   resto tras un botón. Ni una fila kilométrica ni un menú donde el primer
 *   toque no hace nada.
 * - **Botones encendidos.** Cada uno se ilumina cuando su formato ya cubre la
 *   selección, que es lo que dice si el siguiente toque va a poner o a quitar.
 *   Sin eso, alternar es adivinar.
 *
 * [onPedirUrl] es la única parte que cambia entre los dos sitios: en el editor
 * avanzado abre el diálogo con su botón de pegar; en la nota flotante va en
 * null, porque un diálogo necesita una actividad y allí solo hay una ventana del
 * servicio. Sin él el enlace se escribe con los paréntesis vacíos y el cursor
 * dentro, que para pegar una dirección es el mismo gesto.
 */
@Composable
fun BarraDeFormatoUi(
    valor: TextFieldValue,
    onValor: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    onPedirUrl: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    var desplegada by remember { mutableStateOf(false) }
    val activos = remember(valor.text, valor.selection) {
        MarkdownEdit.estiloDeLaSeleccion(
            valor.text,
            valor.selection.start,
            valor.selection.end
        )
    }

    fun pulsar(f: Formato) {
        if (f == Formato.ENLACE && onPedirUrl != null) {
            onPedirUrl()
            return
        }
        onValor(aplicarA(valor, f))
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // El desbordamiento sale por encima, en su propia isla: así el resto no
        // se mueve de sitio al abrirlo.
        if (desplegada) {
            Isla(Modifier.padding(bottom = 6.dp)) {
                BarraDeFormato.desbordamiento.forEach { f ->
                    BotonDeFormato(f, f in activos) { pulsar(f) }
                }
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarraDeFormato.pildoras.forEachIndexed { i, familia ->
                if (i > 0) Spacer(Modifier.width(SEPARACION))
                Isla {
                    familia.forEach { f ->
                        BotonDeFormato(f, f in activos) { pulsar(f) }
                    }
                }
            }

            Spacer(Modifier.width(SEPARACION))
            Isla {
                IconButton(onClick = { desplegada = !desplegada }) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription = "Más formatos",
                        tint = if (desplegada) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            if (trailing != null) {
                Spacer(Modifier.width(SEPARACION))
                Isla(contenido = trailing)
            }
        }
    }
}

/**
 * Una píldora de la barra: fondo redondo, sombra y lo que le metan dentro.
 *
 * Es su forma —44 dp de alto, esquinas de 22, sombra— y el envoltorio que
 * convierte una fila de iconos en un grupo con sentido.
 */
@Composable
private fun Isla(modifier: Modifier = Modifier, contenido: @Composable RowScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), shadowElevation = 8.dp, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = contenido
        )
    }
}

/** Los 8 dp que su barra deja entre píldora y píldora. */
private val SEPARACION = 8.dp

/**
 * Aplica el formato al valor del campo, dejando la selección donde toca.
 *
 * Está fuera del composable para que sirva igual desde un botón, desde un atajo
 * de teclado o desde una prueba.
 */
fun aplicarA(valor: TextFieldValue, formato: Formato): TextFieldValue {
    val dato = when (formato) {
        Formato.FECHA -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date())
        else -> null
    }
    val r = MarkdownEdit.aplicar(
        formato, valor.text, valor.selection.start, valor.selection.end, dato
    )
    return TextFieldValue(r.text, TextRange(r.selStart, r.selEnd))
}

/** Pone el enlace con la url ya sabida. */
fun conEnlace(valor: TextFieldValue, url: String): TextFieldValue {
    val r = MarkdownEdit.enlace(valor.text, valor.selection.start, valor.selection.end, url)
    return TextFieldValue(r.text, TextRange(r.selStart, r.selEnd))
}

@Composable
private fun BotonDeFormato(formato: Formato, activo: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.widthIn(min = 40.dp)) {
        Icon(
            imageVector = iconoDe(formato),
            contentDescription = descripcionDe(formato),
            modifier = Modifier.size(21.dp),
            // Encendido = ya lo tiene, así que el siguiente toque lo quita.
            tint = if (activo) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

private fun iconoDe(formato: Formato): ImageVector = when (formato) {
    Formato.NEGRITA -> Icons.Filled.FormatBold
    Formato.CURSIVA -> Icons.Filled.FormatItalic
    Formato.TACHADO -> Icons.Filled.FormatStrikethrough
    Formato.CODIGO -> Icons.Filled.Code
    Formato.ENLACE -> Icons.Filled.Link
    Formato.TAPADO -> Icons.Filled.VisibilityOff
    Formato.CITA -> Icons.Filled.FormatQuote
    Formato.TITULO -> Icons.Filled.Title
    Formato.LISTA -> Icons.Filled.FormatListBulleted
    Formato.NUMERADA -> Icons.Filled.FormatListNumbered
    Formato.BLOQUE -> Icons.Filled.DataObject
    Formato.FECHA -> Icons.Filled.Today
    Formato.QUITAR -> Icons.Filled.FormatClear
}

/**
 * El nombre de cada botón, que es lo único que oye quien usa el lector de
 * pantalla. Telegram los tiene en su lista de acciones de accesibilidad; aquí
 * van igual, porque una barra de iconos sin nombres es una barra muda.
 */
private fun descripcionDe(formato: Formato): String = when (formato) {
    Formato.NEGRITA -> "Negrita"
    Formato.CURSIVA -> "Cursiva"
    Formato.TACHADO -> "Tachado"
    Formato.CODIGO -> "Código"
    Formato.ENLACE -> "Enlace"
    Formato.TAPADO -> "Tapado"
    Formato.CITA -> "Cita"
    Formato.TITULO -> "Título"
    Formato.LISTA -> "Lista"
    Formato.NUMERADA -> "Lista numerada"
    Formato.BLOQUE -> "Bloque de código"
    Formato.FECHA -> "Fecha de hoy"
    Formato.QUITAR -> "Quitar formato"
}

/**
 * Los seis botones de la barra, cada uno con su menú dentro.
 *
 * Es la forma de su `RichEditorToolbar` + `showTextTypeMenu` / `showListMenu`:
 * **cuatro o seis botones a la vista y todo lo demás dentro de ellos**. Un menú
 * de veintitrés bloques no se lee; seis botones se recuerdan.
 *
 * Y lo que importa de sus menús: **sale marcado lo que el bloque ya es**. No es
 * un menú de insertar, es uno de «esto qué es». Le das a cita sobre un párrafo y
 * el párrafo se vuelve cita, conservando lo escrito; vuelves a abrir y ves que
 * ahora la marcada es cita. Ver [Menus].
 */
@Composable
fun BarraDeFamiliasUi(
    tipoActual: TipoDeBloque?,
    onConvertir: (TipoDeBloque?) -> Unit,
    onInsertar: (TipoDeBloque) -> Unit,
    onCatalogo: () -> Unit,
    onBorrarBloque: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    var abierta by remember { mutableStateOf<Familia?>(null) }
    var submenu by remember { mutableStateOf<List<Entrada>?>(null) }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        abierta?.let { familia ->
            MenuDeFamilia(
                entradas = submenu ?: Menus.de(familia),
                tipoActual = tipoActual,
                atras = if (submenu != null) ({ submenu = null }) else null,
                onEntrada = { entrada ->
                    when {
                        entrada.abre -> submenu = entrada.submenu
                        // Lo que cambia el bloque de tipo pasa por convertir;
                        // lo que mete algo nuevo, por insertar.
                        familia == Familia.TEXTO || familia == Familia.LISTAS ->
                            { onConvertir(entrada.tipo); abierta = null; submenu = null }
                        else -> {
                            entrada.tipo?.let(onInsertar); abierta = null; submenu = null
                        }
                    }
                }
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Isla {
                Menus.familias.forEach { familia ->
                    IconButton(
                        onClick = {
                            submenu = null
                            abierta = if (abierta == familia) null else familia
                        },
                        modifier = Modifier.widthIn(min = 40.dp)
                    ) {
                        Icon(
                            imageVector = iconoDeFamilia(familia),
                            contentDescription = Menus.nombres[familia],
                            modifier = Modifier.size(21.dp),
                            tint = if (abierta == familia) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(SEPARACION))
            Isla {
                IconButton(onClick = onCatalogo) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription = "Todos los bloques",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Borrar el bloque entero. Es la única forma de quitar una
                // imagen, una raya o una tabla: en esos no se puede poner el
                // cursor, así que el retroceso no llega nunca.
                if (onBorrarBloque != null) {
                    IconButton(onClick = onBorrarBloque) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "Borrar este bloque",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(SEPARACION))
                Isla(contenido = trailing)
            }
        }
    }
}

/**
 * El menú de un botón. Los encabezados se pintan **a su tamaño real y en
 * negrita**, como en su `addHeadingItem`: la opción se enseña a sí misma y no
 * hace falta leer «Título 3» para saber cuánto abulta.
 */
@Composable
private fun MenuDeFamilia(
    entradas: List<Entrada>,
    tipoActual: TipoDeBloque?,
    atras: (() -> Unit)?,
    onEntrada: (Entrada) -> Unit
) {
    Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 8.dp) {
        Column(Modifier.widthIn(min = 200.dp)) {
            if (atras != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { atras() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Atrás",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Atrás", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }

            entradas.forEach { entrada ->
                val marcado = entrada.tipo == tipoActual && !entrada.abre
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEntrada(entrada) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = entrada.tipo?.let { iconoDeBloque(it) }
                            ?: Icons.Filled.Notes,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = entrada.nombre,
                        fontSize = tamañoDeEntrada(entrada.tipo),
                        fontWeight = if (esTitulo(entrada.tipo)) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        },
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    if (entrada.abre) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (marcado) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Puesto",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private fun esTitulo(tipo: TipoDeBloque?): Boolean = tipo in setOf(
    TipoDeBloque.TITULO_1, TipoDeBloque.TITULO_2, TipoDeBloque.TITULO_3,
    TipoDeBloque.TITULO_4, TipoDeBloque.TITULO_5, TipoDeBloque.TITULO_6
)

private fun tamañoDeEntrada(tipo: TipoDeBloque?): TextUnit = when (tipo) {
    TipoDeBloque.TITULO_1 -> 22.sp
    TipoDeBloque.TITULO_2 -> 20.sp
    TipoDeBloque.TITULO_3 -> 18.sp
    TipoDeBloque.TITULO_4 -> 17.sp
    TipoDeBloque.TITULO_5 -> 16.sp
    TipoDeBloque.TITULO_6 -> 15.sp
    else -> 16.sp
}

private fun iconoDeFamilia(familia: Familia): ImageVector = when (familia) {
    Familia.TEXTO -> Icons.Filled.Title
    Familia.LISTAS -> Icons.Filled.FormatListBulleted
    Familia.TABLA -> Icons.Filled.TableChart
    Familia.FORMULA -> Icons.Filled.Functions
    Familia.INSERTAR -> Icons.Filled.Add
    Familia.ADJUNTAR -> Icons.Filled.AttachFile
}


/**
 * La barra de estilos del editor en vivo.
 *
 * Aquí no se escriben asteriscos: se ponen y se quitan estilos sobre el trozo
 * seleccionado, como en Telegram. Por eso habla de [SpanKind] y no de [Formato],
 * que es el de la nota flotante, donde lo que se edita sí es Markdown a la vista.
 *
 * Sigue encendiendo los botones que ya cubren la selección —su `getCurrentStyle`—
 * porque la regla de alternar por cobertura es la misma.
 */
@Composable
fun BarraDeEstilosUi(
    activos: Set<SpanKind>,
    onEstilo: (SpanKind) -> Unit,
    onEnlace: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Isla {
            ESTILOS_A_MANO.forEach { kind ->
                IconButton(
                    onClick = { onEstilo(kind) },
                    modifier = Modifier.widthIn(min = 40.dp)
                ) {
                    Icon(
                        imageVector = iconoDeEstilo(kind),
                        contentDescription = nombreDeEstilo(kind),
                        modifier = Modifier.size(21.dp),
                        tint = if (kind in activos) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
        Spacer(Modifier.width(SEPARACION))
        Isla {
            IconButton(onClick = onEnlace) {
                Icon(
                    Icons.Filled.Link,
                    contentDescription = "Enlace",
                    modifier = Modifier.size(21.dp),
                    tint = if (SpanKind.LINK in activos) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(SEPARACION))
            Isla(contenido = trailing)
        }
    }
}

/** Los de su panel de formato, menos el subrayado, que Markdown no tiene. */
private val ESTILOS_A_MANO = listOf(
    SpanKind.BOLD, SpanKind.ITALIC, SpanKind.STRIKE, SpanKind.CODE, SpanKind.SPOILER
)

private fun iconoDeEstilo(kind: SpanKind): ImageVector = when (kind) {
    SpanKind.BOLD -> Icons.Filled.FormatBold
    SpanKind.ITALIC -> Icons.Filled.FormatItalic
    SpanKind.STRIKE -> Icons.Filled.FormatStrikethrough
    SpanKind.CODE -> Icons.Filled.Code
    SpanKind.SPOILER -> Icons.Filled.VisibilityOff
    SpanKind.LINK -> Icons.Filled.Link
}

private fun nombreDeEstilo(kind: SpanKind): String = when (kind) {
    SpanKind.BOLD -> "Negrita"
    SpanKind.ITALIC -> "Cursiva"
    SpanKind.STRIKE -> "Tachado"
    SpanKind.CODE -> "Código"
    SpanKind.SPOILER -> "Tapado"
    SpanKind.LINK -> "Enlace"
}
