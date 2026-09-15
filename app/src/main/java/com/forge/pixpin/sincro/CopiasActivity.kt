package com.forge.pixpin.sincro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * **Volver atrás: las copias de un proyecto y los lienzos que se quedaron sin proyecto**
 * (15-sep-2026). Ver [Copias].
 *
 * Es la respuesta a la pregunta del usuario tras perder lienzos de «Tesis»: si un envío o una
 * sincronización pisa algo, ¿cómo lo recupero? Dos cosas:
 *
 * - **Lienzos sin proyecto**: los que siguen en el disco y no están en ningún proyecto, con su
 *   miniatura, para devolverlos al proyecto de un toque. Es lo que recupera lo perdido *antes* de
 *   que existieran las copias.
 * - **Copias**: cómo estaba el proyecto antes de cada envío recibido y de cada sincronización.
 *   Volver a una no borra lo hecho después, y se puede deshacer (se guarda otra copia antes).
 */
class CopiasActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val proyecto = intent.getStringExtra(EXTRA_PROYECTO)
        setContent { PixPinTheme { Pantalla(proyecto) } }
    }

    @Composable
    private fun Pantalla(proyectoId: String?) {
        val app = application as PixPinApp
        val disco = remember { Red.disco(this) }
        val copias = remember { Copias(disco) }
        var vuelta by remember { mutableIntStateOf(0) }
        val alcance = rememberCoroutineScope()
        val proyecto = app.proyectos.porId(proyectoId)

        val lista by produceState(emptyList<Copias.Copia>(), vuelta) {
            value = withContext(Dispatchers.IO) { if (proyectoId != null) copias.lista(proyectoId) else copias.todas() }
        }
        val sueltos by produceState(emptyList<Copias.Suelto>(), vuelta) {
            value = withContext(Dispatchers.IO) { copias.lienzosSueltos() }
        }
        var aRestaurar by remember { mutableStateOf<Copias.Copia?>(null) }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, contentDescription = "Cerrar") }
                    Column(Modifier.weight(1f)) {
                        Text("Copias de seguridad", style = MaterialTheme.typography.titleLarge)
                        Text(
                            proyecto?.let { "«${it.nombre}»" } ?: "Todos los proyectos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Titulo(
                            "Lienzos sin proyecto",
                            if (sueltos.isEmpty()) "No hay ninguno: todos los lienzos del teléfono están en algún proyecto."
                            else "Siguen en el teléfono pero no están en ningún proyecto. Tócalo para verlo; «Devolver» lo pone otra vez en " +
                                (proyecto?.let { "«${it.nombre}»" } ?: "su proyecto") + "."
                        )
                    }
                    items(sueltos, key = { "s-" + it.dibujo }) { s ->
                        FilaSuelta(s, destino = proyecto?.nombre ?: s.deProyecto?.let { app.proyectos.porId(it)?.nombre }) {
                            alcance.launch {
                                val donde = proyecto ?: s.deProyecto?.let { app.proyectos.porId(it) }
                                if (donde == null) {
                                    Toast.makeText(this@CopiasActivity, "Abre esta pantalla desde el proyecto al que quieres devolverlo", Toast.LENGTH_LONG).show()
                                    return@launch
                                }
                                withContext(Dispatchers.IO) {
                                    val ahora = System.currentTimeMillis()
                                    app.proyectos.conHoja(donde, Hoja(id = "hoja-$ahora", nombre = s.nombre ?: "Lienzo recuperado", dibujo = s.dibujo), ahora)
                                }
                                Toast.makeText(this@CopiasActivity, "Devuelto a «${donde.nombre}»", Toast.LENGTH_SHORT).show()
                                vuelta++
                            }
                        }
                    }
                    item {
                        Titulo(
                            "Versiones anteriores",
                            if (lista.isEmpty()) "Aún no hay copias. Se hacen solas antes de recibir algo por Wi-Fi y antes de sincronizar."
                            else "Cómo estaba el proyecto antes de cada envío recibido y de cada sincronización. Volver a una no borra lo que hiciste después."
                        )
                    }
                    items(lista, key = { "c-" + it.chat + it.id }) { c ->
                        FilaCopia(c, conProyecto = proyectoId == null) { aRestaurar = c }
                    }
                }
            }
        }

        aRestaurar?.let { c ->
            AlertDialog(
                onDismissRequest = { aRestaurar = null },
                title = { Text("¿Volver a esta copia?") },
                text = {
                    Text(
                        "«${c.nombre}» vuelve a como estaba ${cuando(c.cuando)} (${c.motivo.replaceFirstChar { it.lowercase() }}): " +
                            "${c.hojas} hojas. Los lienzos que añadiste después se quedan. Antes se guarda una copia de cómo está ahora, " +
                            "así que puedes deshacerlo."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        aRestaurar = null
                        alcance.launch {
                            val bien = withContext(Dispatchers.IO) { copias.restaurar(c) }
                            Toast.makeText(
                                this@CopiasActivity,
                                if (bien) "«${c.nombre}» ha vuelto a la copia" else "No se pudo volver a la copia",
                                Toast.LENGTH_SHORT
                            ).show()
                            vuelta++
                        }
                    }) { Text("Volver a esta copia") }
                },
                dismissButton = { TextButton(onClick = { aRestaurar = null }) { Text("Cancelar") } }
            )
        }
        LaunchedEffect(Unit) { app.proyectos.recargar() }
    }

    @Composable
    private fun Titulo(titulo: String, texto: String) {
        Column(Modifier.padding(top = 8.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(texto, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun FilaSuelta(s: Copias.Suelto, destino: String?, alDevolver: () -> Unit) {
        val miniatura by produceState<android.graphics.Bitmap?>(null, s.dibujo, s.tocado) {
            value = withContext(Dispatchers.IO) {
                runCatching { com.forge.pixpin.pdf.SublienzosDelPdf.miniatura(this@CopiasActivity, s.dibujo, 240) }.getOrNull()
            }
        }
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            com.forge.pixpin.motor.DrawEditorActivity.abrir(
                                this@CopiasActivity, s.dibujo, ExcalidrawStore.rutaDe(this@CopiasActivity, s.dibujo), null
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    miniatura?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize()) }
                        ?: Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.nombre ?: "Lienzo sin nombre", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Tocado ${cuando(s.tocado)} · ${s.bytes / 1024} KB" + (destino?.let { "\nEra de «$it»" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = alDevolver) { Text("Devolver") }
            }
        }
    }

    @Composable
    private fun FilaCopia(c: Copias.Copia, conProyecto: Boolean, alRestaurar: () -> Unit) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cuando(c.cuando), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        (if (conProyecto) "«${c.nombre}» · " else "") + c.motivo + "\n${c.hojas} hojas · ${c.mensajes.size} mensajes" +
                            (if (c.sinCopiar.isNotEmpty()) " · sin el PDF (demasiado grande)" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = alRestaurar) { Text("Volver") }
            }
        }
    }

    private fun cuando(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    companion object {
        private const val EXTRA_PROYECTO = "copias_proyecto"

        fun abrir(context: Context, proyecto: String?) {
            context.startActivity(Intent(context, CopiasActivity::class.java).putExtra(EXTRA_PROYECTO, proyecto))
        }
    }
}
