package com.forge.pixpin.motor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R

/**
 * La tabla de coordenadas, para meter puntos tecleando en vez de a pulso.
 *
 * Se edita **entera y se acepta al final**, no punto a punto sobre el dibujo:
 * quien viene con una libreta de campo teclea diez filas seguidas, y validar
 * cada una contra la escena sería interrumpirle diez veces. Al aceptar, la
 * tabla se cierra y los puntos aparecen; volver a abrirla trae lo tecleado tal
 * como estaba, para corregir un número sin rehacer nada.
 *
 * Las filas se guardan **como texto mientras se escriben**. Convertirlas a
 * número en cada pulsación borraría lo que estás tecleando en cuanto quedara a
 * medias: un «-» suelto, o un «4,» antes de los decimales, no son números
 * todavía y desaparecerían bajo el dedo.
 */
@Composable
fun EditorDeTabla(
    tabla: TablaDeCoordenadas,
    escala: Escala?,
    onAceptar: (TablaDeCoordenadas) -> Unit,
    onBorrar: () -> Unit,
    onCancelar: () -> Unit,
    /**
     * Se ha tocado otro color, o sea **otra serie**.
     *
     * El color no es un adorno de la tabla: *es* la tabla. Cambiarlo tenía que
     * significar cambiar de serie, y no repintar la que estás mirando —si no,
     * dos series distintas podrían acabar del mismo color y no habría forma de
     * saber cuál es cuál sobre el dibujo.
     *
     * Llega también lo tecleado hasta ahora, para guardarlo antes de saltar: al
     * volver a esta tabla tiene que estar como se dejó.
     */
    onCambiarDeSerie: (color: String, actual: TablaDeCoordenadas) -> Unit,
    /**
     * Lo que haya ahora mismo en el portapapeles, o null si no hay texto.
     *
     * Llega de fuera y no se lee aquí porque el portapapeles es del sistema y
     * este archivo es del motor: pedirlo aquí ataría el editor de tablas a
     * Android y no se podría probar. Quien lo hospeda ya sabe leerlo.
     */
    textoPegado: () -> String? = { null },
    modifier: Modifier = Modifier
) {
    val color = tabla.color
    var visible by remember(tabla.id) { mutableStateOf(tabla.visible) }
    /**
     * Cuántas filas trajo el último pegado, o null si todavía no se ha pegado.
     *
     * Se enseña porque **pegar puede no traer nada** —el portapapeles tiene una
     * foto, o un texto sin dos números por línea— y sin decirlo el botón parece
     * roto. Con el número, además, se ve de un vistazo si entraron las
     * cincuenta filas o solo cuarenta y nueve.
     */
    var pegadas: Int? by remember(tabla.id) { mutableStateOf(null) }
    val filas = remember(tabla.id) {
        androidx.compose.runtime.mutableStateListOf<Pair<String, String>>().apply {
            tabla.puntos.forEach { add(numero(it.x) to numero(it.y)) }
            // Siempre una fila en blanco al final: si hubiera que pulsar
            // «añadir» antes de escribir nada, la tabla vacía no invitaría a
            // teclear.
            if (isEmpty()) add("" to "")
        }
    }

    fun resultado(): TablaDeCoordenadas = tabla.copy(
        visible = visible,
        // Las filas a medio escribir se caen al aceptar. Es lo correcto: una
        // coordenada incompleta no es un punto, y colocarlo en el cero sería
        // inventarse un dato.
        puntos = filas.mapNotNull { (sx, sy) ->
            val x = leerCoordenada(sx)
            val y = leerCoordenada(sy)
            if (x == null || y == null) null else PuntoDeTabla(x, y)
        }
    )

    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, modifier = modifier) {
        Column(Modifier.widthIn(max = 340.dp).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tabla_titulo),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = stringResource(R.string.tabla_ver)
                    )
                }
                IconButton(onClick = onBorrar) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.cd_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                IconButton(onClick = onCancelar) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close))
                }
            }

            Text(
                stringResource(R.string.tabla_serie),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                stringResource(
                    R.string.tabla_unidades,
                    if (escala != null && escala.valida) escala.unidad
                    else stringResource(R.string.tabla_pixeles)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // El color de la serie: los puntos salen de este color, y por eso se
            // elige aquí y no en el panel de estilos — es de la tabla, no del
            // trazo que se dibuje después.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                COLORES_DE_TABLA.forEach { c ->
                    val puesto = c.equals(color, ignoreCase = true)
                    Box(
                        Modifier
                            .padding(end = 6.dp)
                            .size(if (puesto) 26.dp else 20.dp)
                            .background(Color(parseColor(c, 255)), CircleShape)
                            .border(
                                if (puesto) 3.dp else 1.dp,
                                if (puesto) MaterialTheme.colorScheme.primary else Color.Gray,
                                CircleShape
                            )
                            .clickable { if (!puesto) onCambiarDeSerie(c, resultado()) }
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Cabecera("X")
                Cabecera("Y")
                Box(Modifier.width(40.dp))
            }

            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                filas.forEachIndexed { i, (sx, sy) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Celda(sx) { filas[i] = it to filas[i].second }
                        Celda(sy) { filas[i] = filas[i].first to it }
                        IconButton(
                            onClick = { if (filas.size > 1) filas.removeAt(i) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.tabla_quitar_fila),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { filas.add("" to "") }) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.tabla_fila),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                // **Pegar desde la hoja de cálculo.** Teclear cincuenta puntos
                // en un móvil son cien números en un teclado numérico, y quien
                // los tiene ya los tiene escritos. Ver [leerTablaPegada].
                TextButton(
                    onClick = {
                        val pegados = leerTablaPegada(textoPegado())
                        if (pegados.isNotEmpty()) {
                            // Se **añaden**, no se sustituyen: se pega en dos
                            // tandas desde dos sitios sin perder la primera, y
                            // deshacer un pegado es quitar filas, que se ve.
                            // Antes se tira la fila en blanco del final, que si
                            // no queda un hueco en medio.
                            if (filas.isNotEmpty() &&
                                filas.last().first.isBlank() && filas.last().second.isBlank()
                            ) {
                                filas.removeAt(filas.size - 1)
                            }
                            pegados.forEach { filas.add(numero(it.x) to numero(it.y)) }
                            filas.add("" to "")
                            pegadas = pegados.size
                        } else {
                            pegadas = 0
                        }
                    },
                    modifier = Modifier.padding(start = 2.dp)
                ) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null, Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.tabla_pegar),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                Box(Modifier.weight(1f))
                TextButton(onClick = { onAceptar(resultado()) }) {
                    Text(stringResource(R.string.action_done))
                }
            }

            pegadas?.let { cuantas ->
                Text(
                    if (cuantas > 0) stringResource(R.string.tabla_pegadas, cuantas)
                    else stringResource(R.string.tabla_nada_que_pegar),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (cuantas > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cabecera(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f).padding(start = 10.dp)
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Celda(
    valor: String,
    onCambio: (String) -> Unit
) {
    OutlinedTextField(
        value = valor,
        onValueChange = onCambio,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
        // Con signo: media tabla de coordenadas vive en negativo.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
    )
}

/** Un número escrito para volver a teclearlo: sin ceros de más. */
private fun numero(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
