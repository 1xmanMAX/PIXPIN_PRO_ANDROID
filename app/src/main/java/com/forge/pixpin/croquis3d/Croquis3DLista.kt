package com.forge.pixpin.croquis3d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.motor.parseColor

/**
 * **La lista de grupos**: qué hay dibujado, en piezas y no en trazos.
 *
 * A los diez minutos de croquizar, lo que hay en la pantalla ya no son trazos sueltos: es
 * *la pata*, *el respaldo*, *el hueco de la escalera*. La lista es esa lectura del croquis
 * —lo que uno tiene en la cabeza— puesta donde se pueda tocar: se elige una pieza entera de
 * un toque, se esconde para ver lo que tapa y se vuelve a enseñar.
 *
 * Es corta a propósito y no un árbol con niveles: un croquis no es un proyecto, y en cuanto
 * una lista pide desplegar y plegar deja de leerse de un vistazo, que es lo único que hace
 * falta aquí.
 *
 * Va en una lista perezosa: con veinte piezas da igual, pero una lista normal compone las
 * veinte filas aunque se vean cuatro, y esta aplicación se maneja con el dedo encima del
 * dibujo.
 */
@Composable
fun Croquis3DLista(controlador: Croquis3DControlador, modifier: Modifier = Modifier) {
    val capas = controlador.croquis.grupos
    var pintandoElFondo by remember { mutableStateOf(false) }

    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.croquis_capas),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.croquis_capa_nueva),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { controlador.capaNueva() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // **Las capas, de arriba abajo, y el fondo al final.** Es el orden de un montón
            // de papeles: lo último que se puso, arriba; el papel sobre el que está todo,
            // debajo del todo. Y el fondo es una capa más —la primera y la que no se puede
            // quitar—, así que cambiarle el color se hace donde se hace todo lo demás.
            LazyColumn(
                Modifier.heightIn(max = ALTO_MAXIMO.dp).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(capas.reversed(), key = { it.id }) { capa ->
                    val suyos = controlador.idsDelGrupo(capa.id)
                    Fila(
                        nombre = stringResource(R.string.croquis_grupo_n, capa.nombre),
                        cuantos = suyos.size,
                        elegido = controlador.capaActiva == capa.id,
                        escondido = controlador.grupoEscondido(capa.id),
                        alElegir = { controlador.dibujarEnLaCapa(capa.id) },
                        alEsconder = {
                            controlador.ocultarElGrupo(capa.id, !controlador.grupoEscondido(capa.id))
                        },
                        alDesagrupar = { controlador.desagrupar(capa.id) }
                    )
                }
            }

            // El fondo: la capa de debajo de todo. Se toca y se le cambia el color.
            val fondo = controlador.croquis.colorDelFondo
            Row(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { pintandoElFondo = !pintandoElFondo }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            fondo?.let { Color(parseColor(it, 255)) }
                                ?: MaterialTheme.colorScheme.surface
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp))
                )
                Text(
                    stringResource(R.string.croquis_capa_fondo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (pintandoElFondo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.croquis_fondo_del_tema),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (fondo == null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                controlador.empezarAPintarElFondo()
                                controlador.pintarElFondo(null)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                RuedaDeColor(
                    fondo ?: "#f5f5f5",
                    Modifier.padding(top = 4.dp),
                    controlador.croquis.favoritos,
                    alGuardar = controlador::recordarElColor
                ) {
                    controlador.empezarAPintarElFondo()
                    controlador.pintarElFondo(it)
                }
            }

            if (capas.isEmpty()) {
                Text(
                    stringResource(R.string.croquis_sin_grupos),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/**
 * Una fila: el nombre y lo que tiene dentro, y a la derecha los dos botones.
 *
 * El nombre entero es el que elige el grupo —un blanco grande para el dedo—, y los dos
 * botones van pegados al canto, que es donde no se tocan por error al elegir.
 */
@Composable
private fun Fila(
    nombre: String,
    cuantos: Int,
    elegido: Boolean,
    escondido: Boolean,
    alElegir: () -> Unit,
    alEsconder: () -> Unit,
    alDesagrupar: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (elegido) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else androidx.compose.ui.graphics.Color.Transparent
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$nombre · $cuantos",
            style = MaterialTheme.typography.bodyMedium,
            color = if (escondido) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = alElegir)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
        Glifo(if (escondido) "◌" else "◍", alEsconder)
        Glifo("⊗", alDesagrupar)
    }
}

@Composable
private fun Glifo(glifo: String, alTocar: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = alTocar),
        contentAlignment = Alignment.Center
    ) {
        Text(glifo, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Hasta dónde crece la lista antes de empezar a desplazarse, en dp. */
private const val ALTO_MAXIMO = 200
