package com.forge.pixpin.ocr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.data.Settings
import kotlinx.coroutines.launch

/**
 * **«Mi vocabulario»** en Ajustes › Capturar (25-oct-2026, la fila de `[ocr]` del PC). Se edita en
 * un diálogo y se guarda al aceptar —no a cada letra, que el almacén devolvería tarde y movería
 * el cursor—. Lo de debajo dice qué hace en el teléfono, que no es lo mismo que en el PC: allí lo
 * usa Indra al leer; aquí corrige lo que transcribe de una foto la visión del asistente.
 */
@Composable
fun MiVocabularioCard() {
    val app = LocalContext.current.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val ajustes by app.settings.settings.collectAsState(initial = Settings())
    var editando by remember { mutableStateOf(false) }
    val terminos = Vocabulario.deTexto(ajustes.ocrVocabulario).terminos
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mi vocabulario", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { editando = true }) { Text("Editar") }
            }
            Text(
                if (terminos.isEmpty()) "Ninguno. Nombres, siglas o términos que el texto leído debe preferir (E030, Arequipa…)."
                else terminos.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis
            )
            Text(
                "El teléfono no lleva lector de texto (OCR): las fotos las lee la visión del asistente, y lo que " +
                    "se parezca claramente a uno de estos términos se escribe como tú lo pones. Nunca toca cifras " +
                    "salvo las de un término tuyo (E03O → E030). Es el mismo «Mi vocabulario» que el del PC, pero no viaja: " +
                    "escríbelo en cada aparato.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
    if (editando) {
        var texto by remember { mutableStateOf(ajustes.ocrVocabulario) }
        AlertDialog(
            onDismissRequest = { editando = false },
            title = { Text("Mi vocabulario") },
            text = {
                Column {
                    OutlinedTextField(
                        value = texto, onValueChange = { texto = it }, singleLine = false,
                        placeholder = { Text("término, otro término…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Uno por línea o separados por comas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editando = false
                    scope.launch { app.settings.setOcrVocabulario(texto) }
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { editando = false }) { Text("Cancelar") } }
        )
    }
}
