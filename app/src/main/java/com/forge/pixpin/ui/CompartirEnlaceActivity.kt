package com.forge.pixpin.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.motor.SubirPagina
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Compartir la página: como archivo o como enlace.**
 *
 * Mandar un `.html` llega como documento —hay que bajarlo y abrirlo a mano, y muchas
 * aplicaciones ni lo enseñan—. Un enlace se toca y se ve. Aquí se elige una cosa o la otra,
 * y con qué servicio y cuánto dura. Lo pidió el usuario (6-sep-2026). Ver [SubirPagina].
 *
 * **Subir es lo único que manda algo fuera del teléfono**, y por eso se dice con todas las
 * letras antes de tocar nada: el archivo va a un servicio público de otra gente y cualquiera
 * con el enlace puede abrirlo mientras dure. Compartir como archivo no sale del aparato.
 */
class CompartirEnlaceActivity : ComponentActivity() {

    private lateinit var archivo: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        archivo = File(intent.getStringExtra(EL_ARCHIVO).orEmpty())
        if (!archivo.exists()) { finish(); return }
        setContent { PixPinTheme { Pantalla(intent.getStringExtra(EL_TITULO).orEmpty()) } }
    }

    private sealed class Paso {
        object Elegir : Paso()
        class Subiendo(val servicio: SubirPagina.Servicio) : Paso()
        class Listo(val url: String, val servicio: SubirPagina.Servicio) : Paso()
        class Mal(val motivo: String) : Paso()
    }

    @Composable
    private fun Pantalla(titulo: String) {
        val alcance = rememberCoroutineScope()
        val app = applicationContext as? com.forge.pixpin.PixPinApp
        var paso by remember { mutableStateOf<Paso>(Paso.Elegir) }
        var servicio by remember { mutableStateOf(SubirPagina.porId(app?.ajustes?.servicioDeEnlace)) }
        var avance by remember { mutableFloatStateOf(0f) }
        val megas = remember { archivo.length() / 1_000_000.0 }

        fun subir() {
            paso = Paso.Subiendo(servicio); avance = 0f
            alcance.launch { app?.settings?.setServicioDeEnlace(servicio.id) }
            alcance.launch {
                val r = withContext(Dispatchers.IO) {
                    SubirPagina.conReserva(
                        servicio, archivo, titulo.ifBlank { archivo.name },
                        avance = { avance = it },
                        alProbar = { s -> paso = Paso.Subiendo(s) }
                    )
                }
                paso = when (r) {
                    is SubirPagina.Resultado.Enlace -> Paso.Listo(r.url, r.servicio)
                    is SubirPagina.Resultado.Fallo -> Paso.Mal(r.motivo)
                }
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.cancel))
                    }
                    Text(
                        getString(R.string.enlace_titulo), fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), maxLines = 1
                    )
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                    Text(
                        titulo.ifBlank { archivo.name },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        getString(R.string.enlace_pesa, String.format(java.util.Locale.getDefault(), "%.1f", megas)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))

                    when (val p = paso) {
                        is Paso.Elegir -> Elegir(servicio, { servicio = it }, archivo.length(), ::compartirArchivo, ::subir)
                        is Paso.Subiendo -> Subiendo(p.servicio, avance)
                        is Paso.Listo -> Listo(p.url, p.servicio)
                        is Paso.Mal -> Mal(p.motivo) { paso = Paso.Elegir }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    @Composable
    private fun Elegir(
        elegido: SubirPagina.Servicio,
        alElegir: (SubirPagina.Servicio) -> Unit,
        bytes: Long,
        comoArchivo: () -> Unit,
        comoEnlace: () -> Unit
    ) {
        // **Como archivo va primero**: no sale del teléfono, así que es lo que no hay que pensar.
        Card(Modifier.fillMaxWidth().clickable(onClick = comoArchivo)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(getString(R.string.enlace_como_archivo), fontWeight = FontWeight.Bold)
                    Text(
                        getString(R.string.enlace_como_archivo_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                getString(R.string.enlace_como_enlace), fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        Text(
            getString(R.string.enlace_como_enlace_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        // **El aviso, antes de elegir nada.** Sube a un sitio público y de otra gente.
        Box(
            Modifier.fillMaxWidth().padding(top = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(12.dp)
        ) {
            Text(
                getString(R.string.enlace_aviso),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        Spacer(Modifier.height(10.dp))
        for (s in SubirPagina.SERVICIOS) {
            val cabe = SubirPagina.cabe(s, bytes)
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .let { if (cabe) it.clickable { alElegir(s) } else it }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = elegido.id == s.id && cabe, onClick = { alElegir(s) }, enabled = cabe)
                Column(Modifier.padding(start = 6.dp)) {
                    Text(s.nombre, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (cabe) s.caduca else getString(R.string.enlace_no_cabe, s.topeMb),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (cabe) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = comoEnlace,
            enabled = SubirPagina.cabe(elegido, bytes),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text(getString(R.string.enlace_subir)) }
    }

    @Composable
    private fun Subiendo(servicio: SubirPagina.Servicio, avance: Float) {
        Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                getString(R.string.enlace_subiendo, servicio.nombre),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            LinearProgressIndicator(progress = { avance }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
            Text(
                "${(avance * 100).toInt()} %",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }

    @Composable
    private fun Listo(url: String, servicio: SubirPagina.Servicio) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                getString(R.string.enlace_listo), fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { copiar(url) }
                .padding(14.dp)
        ) { Text(url, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        Text(
            getString(R.string.enlace_caduca, servicio.caduca),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { compartirTexto(url) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Share, contentDescription = null, Modifier.size(18.dp))
                Text(getString(R.string.enlace_compartir), modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(onClick = { copiar(url) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = getString(R.string.enlace_copiar), Modifier.size(18.dp))
            }
            OutlinedButton(onClick = { abrir(url) }) {
                Icon(Icons.Filled.OpenInNew, contentDescription = getString(R.string.enlace_abrir), Modifier.size(18.dp))
            }
        }
    }

    @Composable
    private fun Mal(motivo: String, otraVez: () -> Unit) {
        Text(getString(R.string.enlace_mal), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        Text(motivo, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        Text(
            getString(R.string.enlace_mal_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = otraVez) { Text(getString(R.string.enlace_otra_vez)) }
            TextButton(onClick = { compartirArchivo() }) { Text(getString(R.string.enlace_como_archivo)) }
        }
    }

    private fun copiar(url: String) {
        val cp = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cp?.setPrimaryClip(android.content.ClipData.newPlainText("enlace", url))
        android.widget.Toast.makeText(this, R.string.enlace_copiado, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun abrir(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    private fun compartirTexto(url: String) {
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null
                )
            )
        }
    }

    private fun compartirArchivo() {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", archivo)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType(com.forge.pixpin.motor.ExportarHtml.MIME_TYPE)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    null
                )
            )
        }
        finish()
    }

    companion object {
        private const val EL_ARCHIVO = "enlace_archivo"
        private const val EL_TITULO = "enlace_titulo"

        /** La pantalla de compartir para una página ya escrita en disco. */
        fun abrir(context: Context, archivo: File, titulo: String) {
            context.startActivity(
                Intent(context, CompartirEnlaceActivity::class.java)
                    .putExtra(EL_ARCHIVO, archivo.absolutePath)
                    .putExtra(EL_TITULO, titulo)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
