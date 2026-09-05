package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.pin.duracionLegible
import com.forge.pixpin.ui.theme.PixPinTheme

/**
 * **La biblioteca de audio: toda la música y todas las notas de voz, juntas.**
 *
 * Como la pestaña de audio de Telegram en los archivos compartidos: lo que llegó a
 * cualquier chat —a la conversación general o a la de un proyecto— en una sola lista,
 * primero la música y después las notas de voz, con de qué chat es y cuánto dura. Un
 * toque suena (con la barra de reproducción arriba, que sigue mientras se baja por la
 * lista) y el icono de la letra abre la pantalla para cantarla o leerla. Lo pidió el
 * usuario (5-sep-2026).
 */
class BibliotecaDeAudioActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PixPinTheme { Pantalla() } }
    }

    @Composable
    private fun Pantalla() {
        val almacen = remember { MensajesStore(this) }
        var version by remember { mutableIntStateOf(0) }
        androidx.compose.runtime.LaunchedEffect(Unit) { MensajesStore.cambios.collect { version++ } }
        val audios = remember(version) {
            almacen.leer().filter { it.clase == Clase.VOZ && it.ruta != null && !it.enBuzon }
                .sortedWith(compareByDescending<Mensaje> { it.esMusica }.thenByDescending { it.cuando })
        }
        val proyectos = (application as? PixPinApp)?.proyectos?.proyectos?.collectAsState()?.value.orEmpty()
        val estado by Reproductor.estado.collectAsState()

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.cancel)) }
                Text(getString(R.string.biblioteca_audio), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            BarraDelReproductor(alTocarElTitulo = {
                audios.firstOrNull { it.ruta == estado.ruta }?.let { LetraActivity.abrir(this@BibliotecaDeAudioActivity, it.id) }
            })
            if (audios.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(getString(R.string.biblioteca_vacia), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                var ultimoGrupo: Boolean? = null
                items(audios, key = { it.id }) { m ->
                    val esMusica = m.esMusica
                    if (ultimoGrupo != esMusica) {
                        ultimoGrupo = esMusica
                        Text(
                            getString(if (esMusica) R.string.biblioteca_musica else R.string.biblioteca_notas),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    val activo = estado.ruta == m.ruta
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { Reproductor.alternar(m.ruta!!, tituloDeAudio(this@BibliotecaDeAudioActivity, m)) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (activo && estado.sonando) Icons.Filled.Pause
                                else if (esMusica) Icons.Filled.MusicNote else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(tituloDeAudio(this@BibliotecaDeAudioActivity, m), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            val chat = m.proyecto?.let { id -> proyectos.firstOrNull { it.id == id }?.nombre } ?: getString(R.string.guardados_chat_general)
                            Text(
                                chat + " · " + duracionLegible(m.duracionMs),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { LetraActivity.abrir(this@BibliotecaDeAudioActivity, m.id) }) {
                            Icon(
                                Icons.Filled.Lyrics,
                                contentDescription = getString(R.string.letra_ver),
                                tint = if (m.transcripcion != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun abrir(context: Context) {
            context.startActivity(Intent(context, BibliotecaDeAudioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
