package com.forge.pixpin.mini

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * **Ajustes → Tareas: las palabras de cada urgencia** (PC 18-oct-2026): las que, escritas en una tarea,
 * la hacen urgente (🔼) o muy urgente (⏫). Separadas por comas, sin repetir; vacío, ninguna; «De
 * fábrica», las de siempre. Las lee el motor de frases ([Frase.actuales]) en todas las cajas.
 */
@Composable
fun PalabrasDeUrgenciaCard() {
    val contexto = LocalContext.current
    val app = contexto.applicationContext as? com.forge.pixpin.PixPinApp ?: return
    val alcance = rememberCoroutineScope()
    val ajustes by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Palabras de urgencia", style = MaterialTheme.typography.titleSmall)
            Text("Escritas en una tarea, la hacen urgente o muy urgente. Separadas por comas.", style = MaterialTheme.typography.bodySmall)
            for ((cual, titulo, guardado, fabrica) in listOf(
                Cuatro(0, "Urgente 🔼", ajustes.tareasUrgente, Frase.URGENTE),
                Cuatro(1, "Muy urgente ⏫", ajustes.tareasMuyUrgente, Frase.MUY_URGENTE)
            )) {
                var escrito by remember(guardado) { mutableStateOf((guardado?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: fabrica).joinToString(", ")) }
                OutlinedTextField(escrito, { escrito = it }, label = { Text(titulo) }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ninguna") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { alcance.launch { app.settings.setPalabrasDeUrgencia(cual, sinRepetir(escrito)) } }) { Text("Cambiar") }
                    if (guardado != null) TextButton(onClick = { alcance.launch { app.settings.setPalabrasDeUrgencia(cual, null) } }) { Text("De fábrica") }
                }
            }
        }
    }
}

private data class Cuatro(val cual: Int, val titulo: String, val guardado: String?, val fabrica: List<String>)

/** Lo escrito partido por comas y renglones, sin vacías ni repetidas (sin mirar mayúsculas), como el PC. */
fun sinRepetir(escrito: String): String {
    val v = ArrayList<String>()
    for (p in escrito.split(',', '\n').map { it.trim() }) if (p.isNotEmpty() && v.none { it.equals(p, ignoreCase = true) }) v += p
    return v.joinToString(", ")
}
