package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.pin.duracionLegible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Lo que dice de sí misma una canción**: título, artista, álbum y la portada que lleve dentro
 * (27-oct-2026). Sale de las etiquetas del archivo (ID3 de un mp3, las de un m4a, ogg o flac) con
 * `MediaMetadataRetriever`, que ya decidía si un audio es música ([MensajesStore.anadir]). Se
 * guarda en memoria por ruta: leerlo es abrir el archivo, y la tarjeta se pinta muchas veces.
 */
object DatosDeCancion {
    data class Datos(val titulo: String?, val artista: String?, val album: String?, val portada: Bitmap?)

    private val cache = object : LruCache<String, Datos>(48) {}

    /** Lo que se sepa de [ruta]; trabajo de disco. Nunca falla: sin etiquetas, todo nulo. */
    fun de(ruta: String, ladoDeLaPortada: Int = 256): Datos {
        val f = File(ruta)
        val clave = "$ruta|${f.length()}|${f.lastModified()}|$ladoDeLaPortada"
        cache.get(clave)?.let { return it }
        val d = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ruta)
                Datos(
                    titulo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null },
                    artista = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.trim()?.ifBlank { null },
                    album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.ifBlank { null },
                    portada = r.embeddedPicture?.let { reducir(it, ladoDeLaPortada) }
                )
            } finally { runCatching { r.release() } }
        }.getOrNull() ?: Datos(null, null, null, null)
        cache.put(clave, d)
        return d
    }

    /** La portada a un tamaño de tarjeta: una de 3000 px por canción se comería la memoria. */
    private fun reducir(bytes: ByteArray, lado: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var paso = 1
        while (o.outWidth / (paso * 2) >= lado && o.outHeight / (paso * 2) >= lado) paso *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = paso })
    }

    /** El título que se enseña: el de las etiquetas, o el del archivo. */
    fun titulo(context: Context, m: Mensaje, d: Datos?): String = d?.titulo ?: tituloDeAudio(context, m)
}

/**
 * **Una canción en el chat**, como la tarjeta de música de Telegram: portada (o una nota musical),
 * título y artista de sus etiquetas, duración y, si lleva letra, el botón para cantarla
 * (27-oct-2026). El botón redondo suena y pausa con el reproductor de la app ([Reproductor]); tocar
 * el resto abre la pantalla de karaoke ([MusicaActivity]); mantener abre el menú del mensaje.
 */
@Composable
fun TarjetaDeMusica(m: Mensaje, alMantener: () -> Unit) {
    val contexto = LocalContext.current
    val ruta = m.ruta
    val datos by produceState<DatosDeCancion.Datos?>(null, ruta) {
        value = ruta?.let { withContext(Dispatchers.IO) { DatosDeCancion.de(it, 160) } }
    }
    val estado by Reproductor.estado.collectAsState()
    val activo = ruta != null && estado.ruta == ruta
    val titulo = DatosDeCancion.titulo(contexto, m, datos)
    val conLetra = !m.transcripcion.isNullOrBlank()
    Column(
        Modifier
            .widthIn(min = 220.dp, max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { MusicaActivity.abrir(contexto, m.id) }, onLongClick = alMantener)
            .padding(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                val portada = datos?.portada
                if (portada != null) {
                    Image(portada.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                // El play encima de la portada, como Telegram: se ve qué suena y se toca sin abrir nada.
                Box(
                    Modifier
                        .size(34.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .clickable { ruta?.let { Reproductor.alternar(it, titulo) } },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (activo && estado.sonando) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (activo && estado.sonando) "Pausa" else "Escuchar",
                        tint = Color.White
                    )
                }
            }
            Column(Modifier.padding(start = 10.dp).weight(1f, fill = false)) {
                Text(titulo, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val debajo = listOfNotNull(datos?.artista, duracionLegible(if (activo && estado.duracionMs > 0) estado.duracionMs else m.duracionMs))
                Text(
                    debajo.joinToString(" · "), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (conLetra) "Con letra · tócala para cantar" else "Sin letra · tócala para añadirla",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { MusicaActivity.abrir(contexto, m.id) }) {
                Icon(
                    Icons.Filled.Lyrics, contentDescription = "Letra",
                    tint = if (conLetra) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (activo && estado.duracionMs > 0) {
            LinearProgressIndicator(
                progress = { Reproductor.fraccion() },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp)
            )
        }
    }
}
