package com.forge.pixpin.privacidad

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** La palabra que hay que escribir para borrar todo: un toque sin querer no basta. */
const val PALABRA_PARA_BORRAR = "BORRAR"

/**
 * **«Privacidad y datos»** en Ajustes (30-oct-2026, para Google Play): encima de la versión, en la
 * portada de los ajustes. Cuatro cosas:
 *
 * - **«Política de privacidad»**: abre [Privacidad.URL_POLITICA] en el navegador.
 * - **Qué sale del teléfono y adónde**: el resumen de [Privacidad.DESTINOS], plegado.
 * - **«Reportar contenido de la IA»**: el mismo diálogo que la bandera de cada respuesta, para lo
 *   que no la lleva (la prioridad de una tarea, las tarjetas de Anki que eligió Jev…).
 * - **«Borrar todos mis datos»**: con una confirmación fuerte (escribir [PALABRA_PARA_BORRAR]) y
 *   luego `ActivityManager.clearApplicationUserData()`, que borra todo lo de la app —ajustes,
 *   chats, capturas propias, claves cifradas— y la cierra. Lo guardado fuera (la galería,
 *   Descargas) no es de la app y se queda.
 */
@Composable
fun PrivacidadYDatos() {
    val context = LocalContext.current
    var verResumen by rememberSaveable { mutableStateOf(false) }
    var reportar by rememberSaveable { mutableStateOf(false) }
    var borrar by rememberSaveable { mutableStateOf(false) }
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Privacidad y datos", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Sin cuenta, sin anuncios y sin analíticas. Casi todo se queda en el teléfono; lo que sale, sale cuando tú lo pides.",
                style = MaterialTheme.typography.bodySmall, color = tenue, modifier = Modifier.padding(top = 2.dp)
            )
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Privacidad.URL_POLITICA)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, Privacidad.URL_POLITICA, Toast.LENGTH_LONG).show()
                }
            }) {
                Icon(Icons.Outlined.Policy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Política de privacidad", modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = { verResumen = !verResumen }) {
                Icon(if (verResumen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Qué sale del teléfono y adónde", modifier = Modifier.padding(start = 8.dp))
            }
            if (verResumen) Column(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                for (d in Privacidad.DESTINOS) {
                    Text("${d.que} → ${d.adonde}", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    Text(d.cuando, fontSize = 12.sp, color = tenue)
                }
                Text(Privacidad.LO_QUE_NO, fontSize = 12.sp, color = tenue, modifier = Modifier.padding(top = 8.dp))
            }
            TextButton(onClick = { reportar = true }) {
                Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Reportar contenido de la IA", modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = { borrar = true }) {
                Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text("Borrar todos mis datos", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    if (reportar) DialogoDeReporte(ReportesDeIa.Funcion.OTRA, "") { reportar = false }
    if (borrar) DialogoDeBorrarTodo { borrar = false }
}

/** **La confirmación de borrar todo**: qué se va, qué se queda, y escribir la palabra. */
@Composable
private fun DialogoDeBorrarTodo(alCerrar: () -> Unit) {
    val context = LocalContext.current
    var escrito by rememberSaveable { mutableStateOf("") }
    val vale = escrito.trim().equals(PALABRA_PARA_BORRAR, ignoreCase = true)
    AlertDialog(
        onDismissRequest = alCerrar,
        icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("¿Borrar todos tus datos?") },
        text = {
            Column {
                Text(
                    "Se borra para siempre todo lo que PixPin guarda en este teléfono: chats, notas, pines, lienzos, " +
                        "grabaciones y su texto, tareas, ajustes, modelos bajados y tus claves de IA. No se puede deshacer, " +
                        "y la app se cerrará.",
                    fontSize = 14.sp
                )
                Text(
                    "Lo que guardaste fuera de la app (la galería, Descargas) y lo que ya está en tu PC se queda.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = escrito, onValueChange = { escrito = it.take(20) }, singleLine = true,
                    label = { Text("Escribe $PALABRA_PARA_BORRAR para confirmar") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = vale, onClick = {
                val am = context.getSystemService(ActivityManager::class.java)
                val ok = runCatching { am?.clearApplicationUserData() == true }.getOrDefault(false)
                // Si sale bien, el sistema cierra la app aquí mismo; si no, se dice.
                if (!ok) Toast.makeText(context, "No se pudo borrar. Prueba desde Ajustes del teléfono › Apps › PixPin › Almacenamiento.", Toast.LENGTH_LONG).show()
                alCerrar()
            }) { Text("Borrar todo", color = if (vale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        dismissButton = { TextButton(onClick = alCerrar) { Text("Cancelar") } }
    )
}
