package com.forge.pixpin.motor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R

/**
 * La tabla que se acaba de copiar de una hoja de cálculo, **antes de meterla**.
 *
 * El paso intermedio no sobra, y es lo que separa esto de un pegado a ciegas.
 * Lo que llega del portapapeles casi nunca es exactamente lo que se quiere
 * dibujar: sobra una columna de la selección, hay una celda con un número de
 * quince decimales, falta el título de la última. Corregirlo aquí son cuatro
 * toques; corregirlo después, con la tabla ya convertida en veinte rayas y
 * doce textos sueltos sobre el lienzo, es rehacerla.
 *
 * Y **se lee el portapapeles cuando se pide**, no al abrir y ya: se abre el
 * diálogo, uno se da cuenta de que no había copiado nada, se va a Excel, copia y
 * vuelve. El botón de pegar tiene que traer lo nuevo sin cerrar nada.
 *
 * Quien hospeda esto es quien sabe leer el portapapeles y quien sabe medir
 * letras; aquí solo se teclea. Ver [elementosDeTabla], que es lo que dibuja lo
 * que salga de aquí.
 */
@Composable
fun EditorDeTablaPegada(
    /** Lo que haya en el portapapeles, ya partido en filas y columnas. */
    leerPortapapeles: () -> List<List<String>>,
    onInsertar: (filas: List<List<String>>, conCabecera: Boolean) -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier
) {
    var rejilla by remember { mutableStateOf(rejillaRegular(leerPortapapeles())) }
    var conCabecera by remember { mutableStateOf(true) }
    /** Si el último intento de pegar no trajo nada, para decirlo. */
    var vacio by remember { mutableStateOf(false) }

    fun cambiar(fila: Int, columna: Int, valor: String) {
        rejilla = rejilla.mapIndexed { i, f ->
            if (i != fila) f else f.mapIndexed { j, c -> if (j == columna) valor else c }
        }
    }

    val columnas = rejilla.firstOrNull()?.size ?: 0

    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, modifier = modifier) {
        Column(Modifier.widthIn(max = 360.dp).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tabla_pegada_titulo),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCancelar) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close))
                }
            }
            Text(
                stringResource(R.string.tabla_pegada_ayuda),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (rejilla.isEmpty()) {
                Text(
                    stringResource(R.string.tabla_pegada_vacia),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (vacio) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 6.dp)
                ) {
                    rejilla.forEachIndexed { fila, celdas ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            celdas.forEachIndexed { columna, valor ->
                                OutlinedTextField(
                                    value = valor,
                                    onValueChange = { cambiar(fila, columna, it) },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 14.sp),
                                    modifier = Modifier.width(110.dp).padding(2.dp)
                                )
                            }
                            // Quitar la fila, salvo la última que queda: una
                            // tabla sin filas no se puede volver a llenar.
                            IconButton(
                                onClick = {
                                    if (rejilla.size > 1) {
                                        rejilla = rejilla.filterIndexed { i, _ -> i != fila }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.tabla_quitar_fila),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = conCabecera, onCheckedChange = { conCabecera = it })
                    Text(
                        stringResource(R.string.tabla_pegada_cabecera),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TextButton(onClick = {
                    val pegada = rejillaRegular(leerPortapapeles())
                    vacio = pegada.isEmpty()
                    if (pegada.isNotEmpty()) rejilla = pegada
                }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null, Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.tabla_pegar),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                if (rejilla.isNotEmpty()) {
                    TextButton(onClick = {
                        rejilla = rejilla + listOf(List(columnas) { "" })
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.tabla_fila),
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                    TextButton(onClick = { rejilla = rejilla.map { it + "" } }) {
                        Text(stringResource(R.string.tabla_pegada_columna))
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f))
                TextButton(
                    onClick = { onInsertar(rejilla, conCabecera) },
                    enabled = rejilla.isNotEmpty()
                ) {
                    Text(stringResource(R.string.tabla_pegada_insertar))
                }
            }
        }
    }
}
