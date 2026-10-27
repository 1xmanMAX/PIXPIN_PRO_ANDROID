package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Adjuntar → Música: una canción con su letra** (27-oct-2026, lo pidió el usuario).
 *
 * Primero se elige el audio (mp3, m4a, ogg, flac, wav…) y luego se escribe, se pega o se abre la
 * letra ([EditorDeLetra]). Si lo último copiado parece una letra, se ofrece de entrada («Pegar la
 * letra copiada»): es lo normal, copiarla de una web y venir aquí. Al guardar entra en el chat como
 * cualquier audio ([AlChat]) y queda marcado como música con su letra ([TEXTO_LETRA] y
 * [Mensaje.transcripcion], ver [LetraDeCancion]), que es lo que el PC ya entiende. En el chat sale
 * como tarjeta de música ([TarjetaDeMusica]) y se canta en [MusicaActivity].
 */
class CancionNuevaActivity : ComponentActivity() {

    private var audio by mutableStateOf<Uri?>(null)
    private var nombre by mutableStateOf("")
    private var titulo by mutableStateOf<String?>(null)
    private var artista by mutableStateOf<String?>(null)
    private var portada by mutableStateOf<Bitmap?>(null)
    private var texto by mutableStateOf("")
    private var guardando by mutableStateOf(false)

    private val elegir = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { if (audio == null) finish(); return@registerForActivityResult }
        audio = uri
        lifecycleScope.launch {
            val (n, t, a, p) = withContext(Dispatchers.IO) { leerDatos(uri) }
            nombre = n; titulo = t; artista = a; portada = p
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chat = intent.getStringExtra(EL_CHAT)
        aPantallaCompleta()
        if (savedInstanceState == null) elegirAudio()
        setContent { PixPinTheme { Pantalla(chat) } }
    }

    private fun elegirAudio() {
        runCatching { elegir.launch(arrayOf("audio/*", "application/ogg")) }
            .onFailure { Toast.makeText(this, "No hay dónde elegir el audio", Toast.LENGTH_SHORT).show(); finish() }
    }

    private data class Leido(val nombre: String, val titulo: String?, val artista: String?, val portada: Bitmap?)

    private fun leerDatos(uri: Uri): Leido {
        val n = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "canción"
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            Leido(
                n,
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null },
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.ifBlank { null },
                r.embeddedPicture?.let { b ->
                    android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
                }
            )
        } catch (_: Throwable) {
            Leido(n, null, null, null)
        } finally { runCatching { r.release() } }
    }

    @Composable
    private fun Pantalla(chat: String?) {
        // Lo copiado, mirado una vez al abrir: si parece una letra, se ofrece.
        val copiada = remember { portapapeles(this).takeIf { LetraDeCancion.pareceLetra(it) } }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                    Text("Canción nueva", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        val p = portada
                        if (p != null) Image(p.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            titulo ?: nombre.substringBeforeLast('.').ifBlank { if (audio == null) "Elige una canción" else "Canción" },
                            fontWeight = FontWeight.SemiBold, maxLines = 2
                        )
                        val debajo = listOfNotNull(artista, nombre.takeIf { it.isNotBlank() }).joinToString(" · ")
                        if (debajo.isNotEmpty()) Text(debajo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    TextButton(onClick = { elegirAudio() }) { Text(if (audio == null) "Elegir" else "Cambiar") }
                }
                if (copiada != null && texto.isBlank()) {
                    Button(onClick = { texto = copiada }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                        Text(
                            "  Pegar la letra copiada (${copiada.lines().count { it.isNotBlank() }} líneas)"
                        )
                    }
                }
                EditorDeLetra(texto, { texto = it })
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { finish() }) { Text("Cancelar") }
                    Button(enabled = audio != null && !guardando, onClick = { guardar(chat) }) {
                        Text(if (texto.isBlank()) "Guardar sin letra" else "Guardar en el chat")
                    }
                }
            }
        }
    }

    private fun guardar(chat: String?) {
        val uri = audio ?: return
        guardando = true
        val letra = LetraDeCancion.aGuardar(LetraDeCancion.leer(texto))
        lifecycleScope.launch {
            val m = withContext(Dispatchers.IO) {
                runCatching {
                    val m = AlChat.meterYDevolver(this@CancionNuevaActivity, uri, chat, "cancion") ?: return@runCatching null
                    // Como música aunque no traiga etiquetas: quien la mete por aquí sabe lo que es.
                    if (m.clase == Clase.VOZ) MensajesStore(this@CancionNuevaActivity).actualizar(m.id) {
                        it.copy(estadoDelTexto = TEXTO_LETRA, transcripcion = letra.ifBlank { null })
                    }
                    m
                }.getOrNull()
            }
            guardando = false
            if (m == null) {
                Toast.makeText(this@CancionNuevaActivity, "No se pudo guardar la canción", Toast.LENGTH_SHORT).show()
            } else if (m.clase != Clase.VOZ) {
                Toast.makeText(this@CancionNuevaActivity, "No parece un audio: se guardó como archivo", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this@CancionNuevaActivity, "Canción guardada", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val EL_CHAT = "cancion_chat"

        /** [chat]: el proyecto de la conversación, o null para la general. */
        fun abrir(context: Context, chat: String?) {
            context.startActivity(Intent(context, CancionNuevaActivity::class.java).putExtra(EL_CHAT, chat))
        }
    }
}
