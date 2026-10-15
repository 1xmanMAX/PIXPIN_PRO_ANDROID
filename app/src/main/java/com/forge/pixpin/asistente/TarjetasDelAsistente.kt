package com.forge.pixpin.asistente

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.ia.Modelo
import com.forge.pixpin.ia.Modelos
import kotlinx.coroutines.launch

/**
 * **La tarjeta de la primera vez**, encima de la caja del chat «Asistente» (§4.9 del manual):
 * qué modelo, cuánto pesa, confirmar, la barra mientras baja y cancelar. Se va sola cuando el
 * modelo está bajado. Sin teléfono de 64 bits, lo dice y ya.
 */
@Composable
fun TarjetaDelAsistente(modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val app = contexto.applicationContext as? PixPinApp ?: return
    val ajustes by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    val bajados = Asistente.bajados
    val modelo = remember(ajustes.asistenteModelo) { Asistente.modelo(contexto, ajustes.asistenteModelo) }
    val listo = remember(modelo, bajados) { Asistente.listo(contexto, modelo) }
    val progreso = Asistente.progreso
    if (listo && progreso == null) return
    val alcance = rememberCoroutineScope()
    var confirmando by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!Asistente.hayMotor(contexto)) {
                Text("El asistente local necesita un teléfono de 64 bits.", fontSize = 14.sp)
                return@Column
            }
            Text("🤖 Asistente sin internet", style = MaterialTheme.typography.titleSmall)
            if (progreso != null) {
                Text(
                    (if (progreso.verificando) "Comprobando " else "Bajando ") + "${modelo.nombre}… " +
                        "${Modelos.tamanoLegible(progreso.hecho)} de ${Modelos.tamanoLegible(progreso.total)}",
                    fontSize = 13.sp
                )
                LinearProgressIndicator(progress = { progreso.fraccion }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { Asistente.cancelarDescarga() }) { Text("Cancelar") }
                return@Column
            }
            Text(
                "Contesta, dibuja en el lienzo y lee documentos en el teléfono. Se baja una vez y no sale nada de aquí.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ElegirModelo(modelo) { elegido -> alcance.launch { app.settings.setAsistenteModelo(elegido.id) } }
            Asistente.avisoDeDescarga?.let { Text("⚠️ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            if (!confirmando) {
                Button(onClick = { confirmando = true }) { Text("Bajar (${Modelos.tamanoLegible(modelo.bytes)})") }
            } else {
                Text("Se bajan ${Modelos.tamanoLegible(modelo.bytes)} de Hugging Face. Mejor con Wi-Fi.", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmando = false; Asistente.bajar(contexto, modelo) }) { Text("Bajar") }
                    OutlinedButton(onClick = { confirmando = false }) { Text("Ahora no") }
                }
            }
        }
    }
}

/** Los dos modelos, con su peso y una línea de para qué. */
@Composable
private fun ElegirModelo(actual: Modelo, alElegir: (Modelo) -> Unit) {
    Column {
        for (m in Modelos.TODOS) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(selected = m == actual, onClick = { alElegir(m) })
                Column(Modifier.padding(start = 4.dp)) {
                    Text("${m.nombre} · ${Modelos.tamanoLegible(m.bytes)}", fontSize = 14.sp)
                    Text(
                        if (m == Modelos.QWEN35_2B) "Dibuja y ve mejor. Para teléfonos de 8 GB o más."
                        else "Ligero: conversa bien; dibuja y ve peor.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * **Ajustes → Asistente**: qué modelo usar (o el que toca por la RAM), si está bajado, bajarlo o
 * borrarlo. Es de cada aparato y no viaja (§1.4).
 */
@Composable
fun AsistenteCard() {
    val contexto = LocalContext.current
    val app = contexto.applicationContext as? PixPinApp ?: return
    val ajustes by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    val alcance = rememberCoroutineScope()
    val bajados = Asistente.bajados
    val ram = remember { Asistente.ramTotal(contexto) }
    val porRam = remember(ram) { Modelos.elegido(null, ram) }
    val actual = Modelos.elegido(ajustes.asistenteModelo, ram)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Modelo del asistente", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Corre en el teléfono, sin internet. Con ${"%.1f".format(ram / 1e9)} GB de RAM, de fábrica: ${porRam.nombre}.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ElegirModelo(actual) { m -> alcance.launch { app.settings.setAsistenteModelo(if (m == porRam) "" else m.id) } }
            for (m in Modelos.TODOS) {
                val listo = remember(m, bajados) { Asistente.listo(contexto, m) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${m.nombre}: " + if (listo) "bajado" else "sin bajar",
                        fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                    if (listo) TextButton(onClick = { Asistente.borrar(contexto, m) }) { Text("Borrar") }
                    else if (Asistente.progreso == null && Asistente.hayMotor(contexto)) TextButton(onClick = { Asistente.bajar(contexto, m) }) { Text("Bajar") }
                }
            }
            Asistente.progreso?.let { p ->
                LinearProgressIndicator(progress = { p.fraccion }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { Asistente.cancelarDescarga() }) { Text("Cancelar") }
            }
            Asistente.avisoDeDescarga?.let { Text("⚠️ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.width(1.dp))
        }
    }
}

/**
 * **La pregunta Sí/No dentro de la burbuja** (§4.3 del manual del PC): lo que borra o saca algo
 * del aparato no abre un cuadro del sistema; el turno espera aquí. «No» va relleno (es lo de
 * por omisión) y «Sí» con borde rojo. Caduca a los 5 min o al pulsar parar.
 */
@Composable
fun PreguntaDelAsistente(respuesta: String, modifier: Modifier = Modifier) {
    val p = Asistente.pregunta?.takeIf { it.respuesta == respuesta } ?: return
    Column(modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("❓ ${p.texto}", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Asistente.contestarPregunta(false) }) { Text("No") }
            OutlinedButton(
                onClick = { Asistente.contestarPregunta(true) },
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) { Text("Sí", color = MaterialTheme.colorScheme.error) }
        }
    }
}
