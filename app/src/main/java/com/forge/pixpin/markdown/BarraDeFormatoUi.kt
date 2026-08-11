package com.forge.pixpin.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
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

/**
 * La barra de bloques, la que sale **cuando no hay nada seleccionado**.
 *
 * Telegram cambia la barra entera según haya selección o no: sin selección
 * enseña tipos de bloque (`BLOCK_TEXT`, `BLOCK_LIST`, `BLOCK_TABLE`,
 * `BLOCK_MATH`) más un botón de adjuntar a la derecha; con selección enseña los
 * estilos. Tiene sentido: sin texto marcado, poner negrita no significa nada, y
 * lo que sí quieres es empezar algo nuevo.
 *
 * Aquí van los cuatro suyos y el catálogo entero detrás del `+`, porque nuestro
 * catálogo tiene veintitrés bloques y en su editor cada bloque es una fila de
 * una lista, no una línea de texto.
 */
@Composable
fun BarraDeBloquesUi(
    onBloque: (TipoDeBloque) -> Unit,
    onCatalogo: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Surface(shape = RoundedCornerShape(22.dp), shadowElevation = 8.dp, modifier = modifier) {
        Row(
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 3.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BLOQUES_A_MANO.forEach { tipo ->
                IconButton(
                    onClick = { onBloque(tipo) },
                    modifier = Modifier.widthIn(min = 40.dp)
                ) {
                    Icon(
                        imageVector = iconoDeBloque(tipo),
                        contentDescription = Bloques.de(tipo).nombre,
                        modifier = Modifier.size(21.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            IconButton(onClick = onCatalogo) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Todos los bloques",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            trailing()
        }
    }
}

/** Los cuatro de su panel de bloques, más el separador, que no cuesta nada. */
private val BLOQUES_A_MANO = listOf(
    TipoDeBloque.TITULO_2,
    TipoDeBloque.LISTA,
    TipoDeBloque.TAREAS,
    TipoDeBloque.TABLA,
    TipoDeBloque.FORMULA
)

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
    trailing: @Composable RowScope.() -> Unit = {}
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

    Surface(shape = RoundedCornerShape(22.dp), shadowElevation = 8.dp, modifier = modifier) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarraDeFormato.principal.forEach { f ->
                    BotonDeFormato(f, f in activos) { pulsar(f) }
                }
                IconButton(onClick = { desplegada = !desplegada }) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription = descripcionDe(Formato.QUITAR),
                        tint = if (desplegada) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                trailing()
            }

            if (desplegada) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BarraDeFormato.desbordamiento.forEach { f ->
                        BotonDeFormato(f, f in activos) { pulsar(f) }
                    }
                }
            }
        }
    }
}

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
