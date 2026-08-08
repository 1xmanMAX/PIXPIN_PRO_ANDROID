package com.forge.pixpin.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.forge.pixpin.R
import com.forge.pixpin.motor.FilaDeGrupo
import com.forge.pixpin.motor.Tool
import com.forge.pixpin.motor.destinoDeArrastre
import com.forge.pixpin.motor.iconFor
import com.forge.pixpin.motor.labelFor
import com.forge.pixpin.motor.moverHerramienta

/**
 * El editor de la barra: qué herramientas salen y **cómo se agrupan**.
 *
 * Se arrastra manteniendo pulsada una herramienta y soltándola donde se quiera:
 * en otro grupo para juntarla con él, en el hueco entre dos para meterla en
 * medio, o abajo del todo para sacarla a un grupo propio. La última fila es
 * «fuera de la barra», y es la misma mecánica: lo que se suelta ahí deja de
 * salir, sin más botón que aprender.
 *
 * **La decisión de dónde cae no está aquí**, está en `destinoDeArrastre` y
 * `moverHerramienta`, que son funciones puras con sus pruebas. Aquí solo queda
 * seguir al dedo y pintar la sombra: si eso falla se ve enseguida, mientras que
 * una herramienta que se pierde al soltarla no se ve hasta que la buscas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorDeBarra(
    grupos: List<List<Tool>>,
    fuera: List<Tool>,
    onCambio: (grupos: List<List<Tool>>, fuera: List<Tool>) -> Unit,
    modifier: Modifier = Modifier
) {
    // La última fila es «fuera de la barra», y así el arrastre no distingue
    // entre mover y quitar: es el mismo gesto contra una fila más.
    val filas = grupos + listOf(fuera)

    // Dónde está cada cosa en la pantalla, para saber dónde se suelta.
    val bordes = remember { mutableStateMapOf<Int, ClosedFloatingPointRange<Float>>() }
    val centros = remember { mutableStateMapOf<Pair<Int, Int>, Float>() }

    var arrastrada by remember { mutableStateOf<Tool?>(null) }
    var dedo by remember { mutableStateOf(Offset.Zero) }

    fun geometria(): List<FilaDeGrupo> = filas.indices.map { i ->
        val rango = bordes[i] ?: 0f..0f
        FilaDeGrupo(
            arriba = rango.start,
            abajo = rango.endInclusive,
            centros = filas[i].indices.mapNotNull { j -> centros[i to j] }
        )
    }

    val destino = arrastrada?.let { destinoDeArrastre(dedo.x, dedo.y, geometria()) }

    Column(modifier) {
        filas.forEachIndexed { i, fila ->
            val esFuera = i == filas.lastIndex
            val resaltada = destino?.first == i

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = when {
                    resaltada -> MaterialTheme.colorScheme.primaryContainer
                    esFuera -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surface
                },
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (resaltada) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .onGloballyPositioned { coords ->
                        val y = coords.positionInRoot().y
                        bordes[i] = y..(y + coords.size.height)
                    }
            ) {
                Column(Modifier.padding(6.dp)) {
                    if (esFuera) {
                        Text(
                            stringResource(R.string.barra_fuera),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                        )
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().heightIn(min = 34.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        fila.forEachIndexed { j, tool ->
                            Ficha(
                                tool = tool,
                                atenuada = esFuera,
                                levantada = arrastrada == tool,
                                onPosicion = { centro -> centros[i to j] = centro },
                                onArrastreInicio = { pos -> arrastrada = tool; dedo = pos },
                                onArrastre = { pos -> dedo = pos },
                                onArrastreFin = {
                                    val d = destinoDeArrastre(dedo.x, dedo.y, geometria())
                                    arrastrada = null
                                    aplicar(grupos, fuera, tool, d, onCambio)
                                },
                                onCancelado = { arrastrada = null }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reparte el resultado del arrastre entre «lo que sale en la barra» y «lo que
 * no».
 *
 * Se hace aquí y no dentro del movimiento porque el movimiento no tiene por qué
 * saber que la última fila significa algo distinto: para él son grupos, y esa
 * es justo la simplificación que hace que soltar y quitar sean el mismo gesto.
 */
private fun aplicar(
    grupos: List<List<Tool>>,
    fuera: List<Tool>,
    tool: Tool,
    destino: Pair<Int, Int>,
    onCambio: (List<List<Tool>>, List<Tool>) -> Unit
) {
    // **Las dos ramas van explícitas.** Lo tentador era tratar «fuera» como una
    // fila más y dejar que el movimiento lo resolviera solo, pero al vaciarse
    // una fila desaparece, y entonces cuál de las que quedan era la de fuera
    // deja de poder deducirse de su posición. Distinguir aquí cuesta seis
    // líneas; deducirlo mal pierde herramientas.
    val sacar = destino.first >= grupos.size
    if (sacar) {
        val restantes = (fuera - tool).toMutableList()
        restantes.add(destino.second.coerceIn(0, restantes.size), tool)
        onCambio(grupos.map { it - tool }.filter { it.isNotEmpty() }, restantes)
    } else {
        // Devolver a la barra algo que estaba fuera es un alta en el grupo de
        // destino: `moverHerramienta` la trata igual que un movimiento.
        onCambio(
            moverHerramienta(grupos, tool, destino.first, destino.second),
            fuera - tool
        )
    }
}

/** Una herramienta en el editor: icono, nombre y agarradero. */
@Composable
private fun Ficha(
    tool: Tool,
    atenuada: Boolean,
    levantada: Boolean,
    onPosicion: (Float) -> Unit,
    onArrastreInicio: (Offset) -> Unit,
    onArrastre: (Offset) -> Unit,
    onArrastreFin: () -> Unit,
    onCancelado: () -> Unit
) {
    val contexto = LocalContext.current
    // La posición absoluta de la ficha: los desplazamientos del arrastre llegan
    // relativos a ella, y lo que hay que comparar contra las filas es dónde
    // está el dedo en la pantalla.
    var origen by remember { mutableStateOf(Offset.Zero) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (levantada) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (levantada) 6.dp else 0.dp,
        modifier = Modifier
            .onGloballyPositioned { coords ->
                origen = coords.positionInRoot()
                onPosicion(origen.x + coords.size.width / 2f)
            }
            .pointerInput(tool) {
                detectDragGesturesAfterLongPress(
                    // Las posiciones llegan relativas a la ficha y lo que hay
                    // que comparar con las filas es dónde está el dedo en la
                    // pantalla. La ficha no se mueve mientras se arrastra, así
                    // que su origen sirve de referencia todo el rato.
                    onDragStart = { pos -> onArrastreInicio(origen + pos) },
                    onDrag = { cambio, _ ->
                        cambio.consume()
                        onArrastre(origen + cambio.position)
                    },
                    onDragEnd = { onArrastreFin() },
                    onDragCancel = { onCancelado() }
                )
            }
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                iconFor(tool),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (levantada) MaterialTheme.colorScheme.onPrimary
                else if (atenuada) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                contexto.getString(labelFor(tool)),
                style = MaterialTheme.typography.labelMedium,
                color = if (levantada) MaterialTheme.colorScheme.onPrimary
                else if (atenuada) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 5.dp)
            )
        }
    }
}
