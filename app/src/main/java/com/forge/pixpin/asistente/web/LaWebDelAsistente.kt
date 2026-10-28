package com.forge.pixpin.asistente.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.asistente.ClavesDeLaNube
import com.forge.pixpin.ia.Nube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dónde se saca una clave de Brave Search (tiene un plan gratis). */
private const val PAGINA_BRAVE = "https://api-dashboard.search.brave.com/app/keys"

/**
 * **Ajustes → Asistente → La web** (28-oct-2026): si el asistente puede buscar en internet y leer
 * páginas (encendido de serie, como el PC) y la clave opcional de Brave Search para buscar con
 * DeepSeek, cifrada con el Keystore como las de la nube ([ClavesDeLaNube.guardarBrave]) y enseñada
 * solo enmascarada. Es de cada aparato y no viaja.
 */
@Composable
fun LaWebDelAsistenteCard() {
    val contexto = LocalContext.current
    val app = contexto.applicationContext as? PixPinApp
    if (app != null) {
        val ajustes by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
        val alcance = rememberCoroutineScope()
        var version by remember { mutableStateOf(0) }
        var guardada by remember(version) { mutableStateOf<String?>(null) }
        LaunchedEffect(version) {
            guardada = withContext(Dispatchers.IO) { ClavesDeLaNube.leerBrave(contexto)?.let(Nube::enmascarar) }
        }
        var nueva by remember { mutableStateOf("") }
        var aviso by remember { mutableStateOf<String?>(null) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("La web", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Buscar en internet y leer páginas", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = ajustes.asistenteWeb, onCheckedChange = { si -> alcance.launch { app.settings.setAsistenteWeb(si) } })
                }
                Text(
                    "Para «mira el horario en la web de la universidad y apúntalo». Solo abre páginas públicas (nada de tu red de casa), " +
                        "sin tus cookies ni contraseñas, y lo que diga una página no son órdenes: si pide hacer algo, te pregunta. " +
                        "Con Claude busca su propia API; con DeepSeek, DuckDuckGo o, si pegas su clave, Brave Search.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ajustes.asistenteWeb) {
                    Text("Brave Search (opcional): " + (guardada?.let { "clave $it, cifrada en este teléfono" } ?: "sin clave, se busca en DuckDuckGo"), fontSize = 13.sp)
                    OutlinedTextField(
                        value = nueva, onValueChange = { nueva = it.trim() }, singleLine = true,
                        label = { Text("Pega la clave de Brave Search") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            val clave = nueva
                            if (Nube.pareceClave(clave)) {
                                alcance.launch {
                                    aviso = withContext(Dispatchers.IO) {
                                        runCatching { ClavesDeLaNube.guardarBrave(contexto, clave) }.fold({ "Guardada." }, { "No se pudo guardar." })
                                    }
                                    nueva = ""; version++
                                }
                            } else aviso = "Eso no parece una clave."
                        }) { Text("Guardar") }
                        if (guardada != null) TextButton(onClick = {
                            alcance.launch { withContext(Dispatchers.IO) { ClavesDeLaNube.borrarBrave(contexto) }; version++; aviso = "Clave borrada." }
                        }) { Text("Borrar") }
                        TextButton(onClick = {
                            runCatching {
                                contexto.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PAGINA_BRAVE))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("Sacar una") }
                    }
                    aviso?.let { Text(it, fontSize = 13.sp) }
                }
            }
        }
    }
}
