package com.forge.pixpin.privacidad

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.BuildConfig
import com.forge.pixpin.privacidad.ReportesDeIa.Funcion
import com.forge.pixpin.privacidad.ReportesDeIa.Motivo

/**
 * **La bandera de «Reportar respuesta»** (30-oct-2026, política de contenido generado por IA de
 * Google Play): un icono pequeño al lado de lo que escribió una IA. Al tocarlo, [DialogoDeReporte].
 * [contenido] se pide al tocar, no al pintar: así una lista larga no se arma en cada fotograma.
 */
@Composable
fun BotonReportarIa(
    funcion: Funcion,
    contenido: () -> String,
    modifier: Modifier = Modifier,
    tinte: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    tamano: Dp = 36.dp
) {
    var abierto by remember { mutableStateOf<String?>(null) }
    IconButton(onClick = { abierto = contenido() }, modifier = modifier.size(tamano)) {
        Icon(Icons.Outlined.Flag, contentDescription = "Reportar respuesta", tint = tinte, modifier = Modifier.size(18.dp))
    }
    abierto?.let { DialogoDeReporte(funcion, it) { abierto = null } }
}

/** Lo mismo como botón de texto («⚑ Reportar»), para ir al lado de «🔊 Escuchar» en el chat. */
@Composable
fun TextoReportarIa(funcion: Funcion, contenido: () -> String) {
    var abierto by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { abierto = contenido() }, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text("⚑ Reportar", fontSize = 12.sp)
    }
    abierto?.let { DialogoDeReporte(funcion, it) { abierto = null } }
}

/**
 * **El diálogo de reportar**: el motivo (Ofensivo, Peligroso, Falso, Otro), un comentario opcional y
 * lo reportado a la vista. «Guardar» lo apunta en el teléfono; «Guardar y enviar» además abre el
 * correo con todo escrito. [contenido] vacío (el reporte desde Ajustes): se pide pegarlo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogoDeReporte(funcion: Funcion, contenido: String, alCerrar: () -> Unit) {
    val context = LocalContext.current
    var motivo by remember { mutableStateOf<Motivo?>(null) }
    var detalle by remember { mutableStateOf("") }
    var pegado by remember { mutableStateOf("") }
    val sinContenido = contenido.isBlank()

    fun hecho(enviar: Boolean) {
        val m = motivo ?: Motivo.OTRO
        val r = ReportesDeIa.nuevo(funcion, m, if (sinContenido) pegado else contenido, detalle, version = BuildConfig.VERSION_NAME)
        val guardado = runCatching { ReportesDeIa.guardar(java.io.File(context.filesDir, ReportesDeIa.ARCHIVO), r) }.isSuccess
        if (enviar) enviarPorCorreo(context, r)
        else Toast.makeText(context, if (guardado) "Reporte guardado. Gracias." else "No se pudo guardar el reporte", Toast.LENGTH_SHORT).show()
        alCerrar()
    }

    AlertDialog(
        onDismissRequest = alCerrar,
        icon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
        title = { Text("Reportar respuesta") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "¿Qué tiene de malo? Lo escribió una IA (${funcion.rotulo}) y puede equivocarse u ofender: tu reporte ayuda a corregirlo.",
                    fontSize = 13.sp
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    for (m in Motivo.entries) FilterChip(selected = motivo == m, onClick = { motivo = m }, label = { Text(m.rotulo) })
                }
                OutlinedTextField(
                    value = detalle, onValueChange = { detalle = it.take(ReportesDeIa.LARGO_DETALLE) },
                    label = { Text("Comentario (opcional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 4
                )
                if (sinContenido) OutlinedTextField(
                    value = pegado, onValueChange = { pegado = it.take(ReportesDeIa.LARGO_CONTENIDO) },
                    label = { Text("Lo que escribió la IA (pégalo aquí)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 6
                )
                else Text(
                    contenido.take(600) + if (contenido.length > 600) "…" else "",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp).heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                )
                Text(
                    "Se guarda en este teléfono. Con «Enviar» se abre tu correo para mandarlo a ${Privacidad.CORREO_CONTACTO}.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = motivo != null, onClick = { hecho(enviar = true) }) { Text("Enviar") }
        },
        dismissButton = {
            Column {
                TextButton(enabled = motivo != null, onClick = { hecho(enviar = false) }) { Text("Solo guardar") }
                TextButton(onClick = alCerrar) { Text("Cancelar") }
            }
        }
    )
}

/**
 * **El correo del reporte**: `mailto:` con el asunto y el cuerpo escritos (ACTION_SENDTO, que solo
 * ofrece apps de correo). Sin app de correo, se dice y el reporte se queda guardado.
 */
fun enviarPorCorreo(context: Context, r: ReportesDeIa.Reporte) {
    val asunto = ReportesDeIa.asunto(r)
    val cuerpo = ReportesDeIa.cuerpo(r)
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:${Privacidad.CORREO_CONTACTO}?subject=${Uri.encode(asunto)}&body=${Uri.encode(cuerpo)}")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(Privacidad.CORREO_CONTACTO))
        putExtra(Intent.EXTRA_SUBJECT, asunto)
        putExtra(Intent.EXTRA_TEXT, cuerpo)
    }
    try {
        context.startActivity(if (context is android.app.Activity) intent else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No hay app de correo: el reporte se quedó guardado en el teléfono.", Toast.LENGTH_LONG).show()
    }
}
