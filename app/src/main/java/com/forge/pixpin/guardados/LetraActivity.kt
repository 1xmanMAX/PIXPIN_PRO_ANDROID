package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.ui.theme.PixPinTheme

/**
 * **La letra de una canción o el texto de un audio, a pantalla completa, para seguirlo.**
 *
 * Letra grande, que se puede agrandar y achicar, y abajo el reproductor con sus mandos:
 * es la pantalla para cantar una canción o releer una nota de voz mientras suena. La
 * letra la pega uno mismo (el lápiz abre el editor de notas y lo guardado vuelve al
 * mensaje); el texto de un audio lo puso la transcripción. Lo pidió el usuario
 * (5-sep-2026).
 */
class LetraActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EL_MENSAJE).orEmpty()
        setContent { PixPinTheme { Pantalla(id) } }
    }

    @Composable
    private fun Pantalla(id: String) {
        val almacen = remember { MensajesStore(this) }
        var version by remember { mutableIntStateOf(0) }
        // Se vuelve a leer cada vez que el almacén cambia: al volver del editor, la letra nueva.
        androidx.compose.runtime.LaunchedEffect(Unit) { MensajesStore.cambios.collect { version++ } }
        val m = remember(version) { almacen.leer().firstOrNull { it.id == id } }
        var tamano by remember { mutableIntStateOf(22) }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.cancel)) }
                Text(
                    m?.let { tituloDeAudio(this@LetraActivity, it) } ?: "",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                IconButton(onClick = { if (tamano > 14) tamano -= 2 }) { Icon(Icons.Filled.TextDecrease, contentDescription = null) }
                IconButton(onClick = { if (tamano < 40) tamano += 2 }) { Icon(Icons.Filled.TextIncrease, contentDescription = null) }
                IconButton(onClick = { m?.let { editar(it) } }) { Icon(Icons.Filled.Edit, contentDescription = getString(R.string.letra_editar)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val texto = m?.transcripcion
                if (texto.isNullOrBlank()) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(getString(R.string.letra_vacia), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { m?.let { editar(it) } }) { Text(getString(R.string.letra_pegar)) }
                    }
                } else {
                    Text(
                        sinMarcas(texto),
                        fontSize = tamano.sp,
                        lineHeight = (tamano * 1.45f).sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
            if (m?.ruta != null) {
                // Con el audio cargado aunque no suene: los mandos a mano desde el primer momento.
                androidx.compose.runtime.LaunchedEffect(m.ruta) {
                    if (Reproductor.estado.value.ruta != m.ruta) Reproductor.cargar(m.ruta, tituloDeAudio(this@LetraActivity, m), arrancar = false)
                }
                BarraDelReproductor()
            }
        }
    }

    private fun editar(m: Mensaje) {
        // Con hoja en un proyecto, se edita la hoja (que es el `.md` de verdad); si no, lo
        // escrito vuelve al mensaje.
        val app = application as? com.forge.pixpin.PixPinApp
        val hoja = m.hojaDelTexto
        val proyecto = m.proyecto?.let { app?.proyectos?.porId(it) }
        val laHoja = if (hoja != null) proyecto?.hojas?.firstOrNull { it.id == hoja } else null
        if (laHoja != null && proyecto != null) {
            com.forge.pixpin.ui.MarkdownEditorActivity.abrir(this, laHoja.id, laHoja.nota ?: "", desdeProyecto = proyecto.id, mensaje = m.id)
        } else {
            com.forge.pixpin.ui.MarkdownEditorActivity.abrir(this, "letra-${m.id}", m.transcripcion ?: "", mensaje = m.id)
        }
    }

    /** Lo escrito sin las marcas de Markdown que no aportan cantando: `#`, `**`, `_`. */
    private fun sinMarcas(texto: String): String =
        texto.lines().joinToString("\n") { linea ->
            linea.trimStart('#', ' ').replace("**", "").replace("__", "")
        }.trim()

    companion object {
        private const val EL_MENSAJE = "letra_mensaje"

        fun abrir(context: Context, mensaje: String) {
            context.startActivity(
                Intent(context, LetraActivity::class.java)
                    .putExtra(EL_MENSAJE, mensaje)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** Cómo se llama un audio: su nombre de archivo si lo trae, o «Nota de voz» con su fecha. */
fun tituloDeAudio(context: Context, m: Mensaje): String =
    m.nombre.substringBeforeLast('.').ifBlank {
        context.getString(R.string.guardados_voz) + " · " +
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(m.cuando))
    }
