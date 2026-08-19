package com.forge.pixpin.motor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * La ventana de configuración: **lo que se toca una vez, junto**.
 *
 * La barra de arriba tiene que quedarse con lo que se usa dibujando. Todo lo
 * demás —cómo se ve el lienzo, cómo engancha el dedo, sacar el dibujo de aquí—
 * se toca al empezar o al terminar, y cada cosa suelta en la barra le robaba
 * sitio a las que sí se usan a cada rato.
 *
 * Van en pestañas y no en una lista larga porque son grupos que no se mezclan:
 * quien viene a exportar no está mirando el imán. Y **exportar es una pestaña
 * más**, no un menú aparte: era la única de estas que tenía botón propio, y
 * tenerlo en dos sitios es justo lo que hay que evitar.
 *
 * Flota y se arrastra, como la paleta: se abre, se deja a un lado y se sigue
 * dibujando con ella puesta si hace falta.
 */

/** Cada pestaña de la ventana. */
enum class PestanaDeAjustes(val titulo: String) {
    LIENZO("Lienzo"),
    DIBUJO("Dibujo"),
    EXPORTAR("Exportar")
}

/** Una opción de exportar, para no repetir la fila cuatro veces. */
data class FormatoDeSalida(val icono: ImageVector, val nombre: String, val al: () -> Unit)

@Composable
fun VentanaDeAjustes(
    cuadricula: Cuadricula,
    onCuadricula: (Cuadricula) -> Unit,
    zoomBloqueado: Boolean,
    onZoomBloqueado: (Boolean) -> Unit,
    /** Enseñar u ocultar las líneas de referencia; null si no hay ninguna. */
    referenciasVisibles: Boolean?,
    onReferencias: () -> Unit,
    /** Solo el lápiz dibuja; el dedo mueve el papel. */
    modoLapiz: Boolean,
    onModoLapiz: (Boolean) -> Unit,
    /** Ensancha todas las zonas de toque. Ver [DrawController.modoDedo]. */
    modoDedo: Boolean,
    onModoDedo: (Boolean) -> Unit,
    /** El trazo a mano sale firme. Ver [Element.presionFirme]. */
    presionFirme: Boolean,
    onPresionFirme: (Boolean) -> Unit,
    /** Traer una imagen para copiar de ella. Ver [VentanaDeReferencia]. */
    onImagenDeReferencia: () -> Unit,
    /** Poner el zoom a la escala de una hoja. Ver [Papel]. */
    onPapel: (Papel) -> Unit,
    /** Guardar lo que hay en el lienzo como una hoja del proyecto en curso. */
    onAProyecto: () -> Unit,
    /** Soltar lo que esté clavado, o null si no hay nada clavado. */
    onSoltarClavados: (() -> Unit)?,
    formatos: List<FormatoDeSalida>,
    exportando: Boolean,
    onCerrar: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pestana by remember { mutableStateOf(PestanaDeAjustes.LIENZO) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 10.dp,
        modifier = modifier
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .widthIn(max = 320.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            BarraDeVentana(
                onArrastrar = { ax, ay -> dx += ax; dy += ay },
                onAmpliar = null,
                onCerrar = onCerrar
            )

            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PestanaDeAjustes.entries.forEach { cual ->
                    Pestana(cual.titulo, cual == pestana, Modifier.weight(1f)) { pestana = cual }
                }
            }

            Column(
                Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (pestana) {
                    PestanaDeAjustes.LIENZO -> {
                        Titulo("Fondo")
                        // Las tres formas del papel, como tres opciones y no como
                        // un interruptor: son tres, no dos.
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Opcion("Liso", cuadricula == Cuadricula.NINGUNA, Modifier.weight(1f)) {
                                onCuadricula(Cuadricula.NINGUNA)
                            }
                            Opcion("Cuadros", cuadricula == Cuadricula.LINEAS, Modifier.weight(1f)) {
                                onCuadricula(Cuadricula.LINEAS)
                            }
                            Opcion("Puntos", cuadricula == Cuadricula.PUNTOS, Modifier.weight(1f)) {
                                onCuadricula(Cuadricula.PUNTOS)
                            }
                        }
                        // **A la escala de una hoja.**
                        //
                        // El lienzo es infinito y eso está bien para trabajar,
                        // pero lo que se entrega es una hoja. Ponerse «a A3» es
                        // ver el dibujo del tamaño al que va a salir impreso,
                        // que es la única forma de saber si la línea fina se va
                        // a perder o si el texto se va a leer.
                        Titulo("Ver como hoja")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Papel.entries.forEach { papel ->
                                Opcion(papel.etiqueta, false, Modifier.weight(1f)) {
                                    onPapel(papel)
                                }
                            }
                        }

                        // Va con el modo lápiz porque son la misma pregunta —
                        // con qué se está tocando— y se cambian juntos.
                        // **Llevarlo al proyecto**, que es donde acaba lo que se
                        // dibuja: en un PDF con las demás hojas. Sin esto, lo
                        // editado se quedaba dentro del pin y no había forma de
                        // sacarlo sin exportarlo a mano una por una.
                        Fila("Guardar en un proyecto", onAProyecto) {
                            Text(
                                "Añadir",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Interruptor("Modo dedo", modoDedo, onModoDedo)
                        // Escribir y dibujar piden cosas contrarias, y por eso
                        // es un interruptor y no una decisión de la aplicación.
                        Interruptor("Trazo firme (sin presión)", presionFirme, onPresionFirme)
                        Interruptor("Clavar el zoom", zoomBloqueado, onZoomBloqueado)
                        // Solo cuando hay algo clavado: es un botón de rescate,
                        // y ofrecerlo siempre haría pensar que hace falta.
                        if (onSoltarClavados != null) {
                            Fila("Soltar lo clavado", onSoltarClavados) {
                                Text(
                                    "Soltar",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (referenciasVisibles != null) {
                            Fila("Líneas de referencia", onReferencias) {
                                Text(
                                    if (referenciasVisibles) "A la vista" else "Escondidas",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    PestanaDeAjustes.DIBUJO -> {
                        // **El interruptor del lápiz.** Se enciende solo al usar
                        // el stylus; está aquí para poder **apagarlo**, que es lo
                        // que faltaba: sin lápiz a mano, el dedo tiene que volver
                        // a dibujar.
                        Interruptor("Solo el lápiz dibuja", modoLapiz, onModoLapiz)
                        Text(
                            "Con esto puesto, el dedo mueve el lienzo y solo el " +
                                "lápiz traza. Se enciende solo al detectar uno.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Fila("Imagen de referencia", onImagenDeReferencia) {
                            Text(
                                "Abrir",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    PestanaDeAjustes.EXPORTAR -> {
                        formatos.forEach { formato ->
                            Fila(formato.nombre, if (exportando) null else formato.al) {
                                Icon(
                                    formato.icono,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (exportando) {
                            Text(
                                "Escribiendo el archivo…",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(
        texto,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun Pestana(
    texto: String,
    puesta: Boolean,
    modifier: Modifier = Modifier,
    onTocar: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (puesta) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable { onTocar() }
    ) {
        Text(
            texto,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = if (puesta) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)
        )
    }
}

@Composable
private fun Opcion(
    texto: String,
    puesta: Boolean,
    modifier: Modifier = Modifier,
    onTocar: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (puesta) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.clickable { onTocar() }
    ) {
        Text(
            texto,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun Interruptor(texto: String, puesto: Boolean, onCambio: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(texto, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = puesto, onCheckedChange = onCambio)
    }
}

/** Una fila que se toca. Con [al] a null sale apagada, no escondida. */
@Composable
private fun Fila(texto: String, al: (() -> Unit)?, derecha: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(10.dp)
            )
            .let { if (al == null) it else it.clickable { al() } }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            texto,
            fontSize = 13.sp,
            color = if (al == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        derecha()
    }
}
