package com.forge.pixpin.asistente

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.ia.ConexionesNativas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * **Ajustes → Asistente → Conexiones** (PC 17/18-oct-2026): Google Drive y Google Calendar con el mismo
 * ID de cliente (se pega una vez: el ID solo, el ID y el secreto, o el JSON que da Google). Conectar
 * abre el navegador y espera la vuelta; lo guardado va cifrado en este teléfono y no viaja.
 */
@Composable
fun ConexionesDelAsistente() {
    val contexto = LocalContext.current
    if (!ConexionesNativas.disponible) return
    val alcance = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    Text("Conexiones", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Text(
        "Para que el asistente busque, lea, baje y suba en tu Google Drive (subir pregunta antes) y vea tu Google Calendar " +
            "(sus eventos salen en el horario y avisan con la llamada). Crea un ID de cliente de «aplicación de escritorio» en Google Cloud y pégalo; vale el mismo del PC.",
        fontSize = 13.sp
    )
    for (servicio in listOf(ConexionesNativas.DRIVE, ConexionesNativas.CALENDAR)) {
        var estado by remember(servicio, version) { mutableStateOf<JsonObject?>(null) }
        var aviso by remember(servicio) { mutableStateOf<String?>(null) }
        var pegado by remember(servicio) { mutableStateOf("") }
        LaunchedEffect(servicio, version) {
            estado = withContext(Dispatchers.IO) { ConexionesDelTelefono(contexto.applicationContext).estado(servicio).getOrNull() as? JsonObject }
        }
        val e = estado
        val nombre = (e?.get("nombre") as? JsonPrimitive)?.content ?: if (servicio == ConexionesNativas.DRIVE) "Google Drive" else "Google Calendar"
        val cliente = (e?.get("cliente") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val conectado = (e?.get("conectado") as? JsonPrimitive)?.booleanOrNull == true
        val cuenta = (e?.get("cuenta") as? JsonPrimitive)?.takeIf { it.isString }?.content
        Text(
            "$nombre: " + when {
                conectado -> "conectado" + (cuenta?.let { " ($it)" } ?: "")
                cliente != null -> "sin conectar"
                else -> "falta el ID de cliente"
            },
            fontSize = 13.sp
        )
        if (cliente == null) {
            OutlinedTextField(value = pegado, onValueChange = { pegado = it }, label = { Text("Pega el ID de cliente") }, singleLine = false, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (cliente == null) TextButton(onClick = {
                val t = pegado
                alcance.launch {
                    aviso = withContext(Dispatchers.IO) { ConexionesDelTelefono(contexto.applicationContext).guardarCliente(servicio, t) }.fold({ "Guardado." }, { "⚠️ " + it.message })
                    pegado = ""; version++
                }
            }) { Text("Guardar") }
            if (cliente != null && !conectado) TextButton(onClick = {
                aviso = "Esperando al navegador… da el permiso y vuelve."
                ConexionesDelTelefono.esperando.set(true)
                alcance.launch {
                    val parar = java.util.concurrent.atomic.AtomicBoolean(false)
                    val r = withContext(Dispatchers.IO) { ConexionesDelTelefono(contexto.applicationContext, parar = parar).conectar(servicio) }
                    aviso = r.fold({ "Conectado" + ((it as? JsonPrimitive)?.content?.takeIf { c -> c.isNotBlank() }?.let { c -> " ($c)" } ?: "") + "." }, { "⚠️ " + it.message })
                    if (r.isSuccess && servicio == ConexionesNativas.CALENDAR) withContext(Dispatchers.IO) { CalendarioGoogle.leer(contexto.applicationContext) }
                    version++
                }
            }) { Text("Conectar") }
            if (conectado) TextButton(onClick = {
                alcance.launch {
                    aviso = withContext(Dispatchers.IO) {
                        ConexionesDelTelefono(contexto.applicationContext).desconectar(servicio).also {
                            if (servicio == ConexionesNativas.CALENDAR) CalendarioGoogle.olvidar(contexto.applicationContext)
                        }
                    }.fold({ "Desconectado." }, { "⚠️ " + it.message })
                    version++
                }
            }) { Text("Desconectar") }
        }
        aviso?.let { Text(it, fontSize = 13.sp) }
    }
}
