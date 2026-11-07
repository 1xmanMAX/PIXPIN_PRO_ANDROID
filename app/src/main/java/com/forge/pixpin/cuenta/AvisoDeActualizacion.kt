package com.forge.pixpin.cuenta

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * **Hay una versión nueva** (7-nov-2026): una tarjeta en la pantalla de inicio con sus novedades y «Actualizar», que
 * la baja aquí mismo con su avance ([Actualizar]). Si es obligatoria (versión mínima), un aviso que no se cierra.
 */
@Composable
fun AvisoDeActualizacion(modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val nueva by Actualizar.nueva.collectAsState()
    val bajando by Actualizar.bajando.collectAsState()
    val alcance = rememberCoroutineScope()
    var cerrada by remember { mutableStateOf<Int?>(null) }
    val v = nueva ?: return
    fun actualizar() {
        if (!Actualizar.puedeInstalar(contexto)) {
            Toast.makeText(contexto, "Permite a PinDesk instalar actualizaciones y vuelve a tocar «Actualizar»", Toast.LENGTH_LONG).show()
            Actualizar.pedirPermiso(contexto); return
        }
        alcance.launch {
            when (val r = Actualizar.bajarEInstalar(contexto, v)) {
                Actualizar.Resultado.Instalando -> Unit
                Actualizar.Resultado.HuellaMala -> Toast.makeText(contexto, "La descarga no es la auténtica: no se instala. Vuelve a probar.", Toast.LENGTH_LONG).show()
                is Actualizar.Resultado.Fallo -> Toast.makeText(contexto, r.motivo, Toast.LENGTH_LONG).show()
            }
        }
    }
    if (v.obligatoria) {
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Filled.SystemUpdate, null) },
            title = { Text("Actualiza PinDesk") },
            text = {
                Column {
                    Text("Esta versión ya no funciona: hace falta la ${v.versionName}.")
                    if (v.notas.isNotBlank()) Text(v.notas, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    bajando?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }
                }
            },
            confirmButton = { Button(onClick = { actualizar() }, enabled = bajando == null) { Text("Actualizar") } }
        )
        return
    }
    if (cerrada == v.versionCode) return
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Nueva versión ${v.versionName}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (v.notas.isNotBlank()) Text(v.notas, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            val b = bajando
            if (b != null) {
                LinearProgressIndicator(progress = { b }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                Text("Bajando… ${(b * 100).toInt()} %", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            } else Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = { cerrada = v.versionCode }) { Text("Luego") }
                Spacer(Modifier.height(0.dp).width(4.dp))
                Button(onClick = { actualizar() }) { Text("Actualizar") }
            }
        }
    }
}
