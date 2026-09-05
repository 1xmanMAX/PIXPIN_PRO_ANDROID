package com.forge.pixpin.guardados

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.pin.duracionLegible

/**
 * **La barra de lo que suena**, como la de Telegram encima de la conversación.
 *
 * Título, atrás diez segundos, play/pausa, adelante diez, la velocidad (que va rotando al
 * tocarla) y cerrar; debajo, la barra de progreso que se puede arrastrar y los tiempos.
 * No se pinta nada si no hay nada cargado. Ver [Reproductor].
 *
 * [alTocarElTitulo]: qué hacer al tocar el nombre —abrir la letra o el texto, si lo hay—.
 */
@Composable
fun BarraDelReproductor(modifier: Modifier = Modifier, alTocarElTitulo: (() -> Unit)? = null) {
    val estado by Reproductor.estado.collectAsState()
    if (estado.ruta == null) return
    // Mientras se arrastra, la barra sigue al dedo y no al reloj.
    var arrastrando by remember { mutableStateOf<Float?>(null) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { alTocarElTitulo?.invoke() },
                    enabled = alTocarElTitulo != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        estado.titulo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                IconButton(onClick = { Reproductor.saltar(-Reproductor.SALTO_MS) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Replay10, contentDescription = "Atrás 10 s")
                }
                IconButton(onClick = { if (estado.sonando) Reproductor.pausar() else Reproductor.seguir() }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (estado.sonando) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (estado.sonando) "Pausa" else "Seguir",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { Reproductor.saltar(Reproductor.SALTO_MS) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Forward10, contentDescription = "Adelante 10 s")
                }
                TextButton(onClick = { Reproductor.otraVelocidad() }, modifier = Modifier.size(width = 48.dp, height = 36.dp)) {
                    Text(velocidadLegible(estado.velocidad), fontSize = 12.sp)
                }
                IconButton(onClick = { Reproductor.parar() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar", modifier = Modifier.size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(duracionLegible(estado.posicionMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = arrastrando ?: Reproductor.fraccion(),
                    onValueChange = { arrastrando = it },
                    onValueChangeFinished = {
                        arrastrando?.let { Reproductor.irA(it) }
                        arrastrando = null
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(24.dp)
                )
                Text(duracionLegible(estado.duracionMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** `1×`, `1,5×`, `0,75×`. */
fun velocidadLegible(v: Float): String =
    (if (v == v.toInt().toFloat()) "${v.toInt()}" else v.toString().replace('.', ',').trimEnd('0')) + "×"
