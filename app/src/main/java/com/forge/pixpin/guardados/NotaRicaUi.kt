package com.forge.pixpin.guardados

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.motormd.Markdown
import com.forge.pixpin.motormd.MarkdownText
import java.io.File

/**
 * **La burbuja de una nota rica** (25-oct-2026). Los trozos de [NotaRica.trozos]: el Markdown con
 * el pintor de siempre ([MarkdownText]), las fotos seguidas en un álbum de filas de dos o tres y
 * los adjuntos que no son fotos con 📎. La ruta de una foto no se ve nunca: mientras carga, o si
 * su fichero aún no ha llegado a este aparato, un recuadro con el icono de imagen.
 */
@Composable
fun NotaRicaEnBurbuja(texto: String, tamanoSp: Float = 15f, modifier: Modifier = Modifier) {
    val trozos = remember(texto) { NotaRica.trozos(texto) }
    Column(modifier) {
        trozos.forEachIndexed { i, t ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            when (t) {
                is NotaRica.Trozo.Texto -> {
                    val bloques = remember(t.markdown) { Markdown.parse(t.markdown) }
                    MarkdownText(blocks = bloques, baseSizeSp = tamanoSp, modifier = Modifier.fillMaxWidth())
                }
                is NotaRica.Trozo.Album -> AlbumDeLaNota(t.fotos)
                is NotaRica.Trozo.Adjunto -> Text(
                    "📎 " + t.imagen.alt.ifBlank { t.imagen.enlace.substringAfterLast('/') },
                    fontSize = (tamanoSp * 0.9f).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Lo que se carga de una foto: el mapa (reducido) y su proporción, o nada si no está. */
private class FotoCargada(val mapa: ImageBitmap?, val proporcion: Float)

@Composable
private fun AlbumDeLaNota(fotos: List<NotaRica.Imagen>) {
    val filesDir = LocalContext.current.filesDir
    val cargadas by produceState<List<FotoCargada>?>(null, fotos) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fotos.map { f -> cargar(NotaRica.archivo(filesDir, f.enlace), if (fotos.size == 1) 1080 else 640) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val ancho = maxWidth.value
        val proporciones = fotos.indices.map { cargadas?.getOrNull(it)?.proporcion ?: (4f / 3f) }
        val (cajas, alto) = remember(proporciones, ancho) {
            NotaRica.album(proporciones, ancho, altoSolo = 280f, altoFila = 170f, hueco = 3f)
        }
        Box(Modifier.fillMaxWidth().height(alto.dp)) {
            cajas.forEachIndexed { i, c ->
                val mapa = cargadas?.getOrNull(i)?.mapa
                val forma = Modifier
                    .offset(c.x.dp, c.y.dp)
                    .size(c.ancho.dp, c.alto.dp)
                    .clip(RoundedCornerShape(8.dp))
                if (mapa != null) {
                    Image(mapa, contentDescription = fotos[i].alt.ifBlank { null }, modifier = forma, contentScale = ContentScale.Crop)
                } else {
                    Box(forma.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Reducida al decodificar: una foto de 12 megapíxeles en una burbuja es memoria tirada. */
private fun cargar(f: File?, lado: Int): FotoCargada = runCatching {
    if (f == null || !f.isFile) return FotoCargada(null, 4f / 3f)
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) return FotoCargada(null, 4f / 3f)
    var muestra = 1
    while (maxOf(o.outWidth, o.outHeight) / (muestra * 2) >= lado) muestra *= 2
    val mapa = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = muestra })
    FotoCargada(mapa?.asImageBitmap(), o.outWidth.toFloat() / o.outHeight)
}.getOrElse { FotoCargada(null, 4f / 3f) }
