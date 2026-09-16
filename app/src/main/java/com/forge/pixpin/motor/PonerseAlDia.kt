package com.forge.pixpin.motor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import com.forge.pixpin.sincro.AlDia
import com.forge.pixpin.sincro.CopiasActivity
import com.forge.pixpin.sincro.haceCuanto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **«Ponerme al día con este lienzo»**, pedido por el usuario el 16-sep-2026.
 *
 * Su razón, en sus palabras: las escrituras se ven superpuestas y arreglarlo después es difícil;
 * mejor «traer la última versión de forma rápida y así editar sobre esa». Así que esto no
 * sincroniza el proyecto entero —eso sigue en la pantalla de Sincronizar—, sino **este archivo**.
 *
 * Y en dos tiempos, como pidió: **primero se ve** qué tiene cada aparato de este lienzo y cuál lo
 * tocó más tarde —con la lista delante se decide—, y solo entonces se trae. Dentro va también lo
 * de revertir: antes de juntar nada se hace copia de seguridad, y el botón lleva a ella.
 *
 * Ver [AlDia], [com.forge.pixpin.sincro.Copias] y [CopiasActivity].
 */
@Composable
fun PonerseAlDia(chat: String, rel: String, onCerrar: () -> Unit, alTraer: () -> Unit) {
    val contexto = LocalContext.current
    var estados by remember { mutableStateOf<List<AlDia.Estado>>(emptyList()) }
    var mirando by remember { mutableStateOf(true) }
    var trayendo by remember { mutableStateOf<String?>(null) }
    var resultado by remember { mutableStateOf<AlDia.Resultado?>(null) }
    var miResumen by remember { mutableStateOf<String?>(null) }
    val alcance = rememberCoroutineScope()

    LaunchedEffect(chat, rel) {
        miResumen = withContext(Dispatchers.IO) { AlDia.resumenDeEste(contexto, chat, rel) }
        estados = withContext(Dispatchers.IO) { AlDia.mirar(contexto, chat, rel) }
        mirando = false
    }

    // El más reciente de los que lo tienen y no son igual que el de aquí: es el que interesa.
    val masNuevo = estados.filter { it.loTiene && it.resumen != miResumen }.maxByOrNull { it.tocado }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Ponerme al día") },
        text = {
            Column {
                when {
                    mirando -> Fila("Preguntando a tus aparatos…", cargando = true)
                    estados.isEmpty() -> Text(
                        "No hay otros aparatos en el grupo, o no se ha hablado con ellos todavía. " +
                            "Añádelos en Sincronizar.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> for (e in estados) {
                        val dice = when {
                            e.error != null -> e.error
                            !e.loTiene -> "No tiene este lienzo"
                            e.resumen == miResumen -> "Igual que el tuyo"
                            e === masNuevo -> "La más reciente · ${haceCuanto(e.tocado)}"
                            else -> "Distinta · ${haceCuanto(e.tocado)}"
                        }
                        Fila(
                            texto = e.nombre,
                            detalle = dice,
                            destacada = e === masNuevo,
                            cargando = trayendo == e.nombre
                        )
                    }
                }
                resultado?.let { r ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            r.fallaron.isNotEmpty() && r.deQuien.isEmpty() -> r.fallaron.values.first()
                            r.deQuien.isEmpty() -> "Ya estabas al día."
                            else -> "Traído de ${r.deQuien.joinToString(" · ")}. Ya estás editando la última versión."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (!mirando && estados.any { it.loTiene }) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Antes de juntar nada se guarda cómo estaba: si sale algo raro, «Revertir» te devuelve a la versión de antes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            val hayQueTraer = !mirando && estados.any { it.loTiene && it.resumen != miResumen }
            TextButton(
                enabled = hayQueTraer && trayendo == null,
                onClick = {
                    val destinos = estados.filter { it.loTiene }
                    trayendo = destinos.firstOrNull()?.nombre
                    alcance.launch {
                        val r = withContext(Dispatchers.IO) {
                            AlDia.traer(contexto, chat, rel, destinos) { quien -> trayendo = quien }
                        }
                        trayendo = null
                        resultado = r
                        estados = withContext(Dispatchers.IO) { AlDia.mirar(contexto, chat, rel) }
                        if (r.deQuien.isNotEmpty()) alTraer()
                    }
                }
            ) { Text("Traer de todos") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { CopiasActivity.abrir(contexto, chat); onCerrar() }) { Text("Revertir") }
                TextButton(onClick = onCerrar) { Text("Cerrar") }
            }
        }
    )
}

@Composable
private fun Fila(texto: String, detalle: String? = null, destacada: Boolean = false, cargando: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (cargando) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (destacada) {
            Icon(
                Icons.Filled.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(
                texto,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (destacada) FontWeight.SemiBold else FontWeight.Normal
            )
            detalle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (destacada) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
