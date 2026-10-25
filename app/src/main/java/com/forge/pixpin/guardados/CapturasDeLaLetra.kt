package com.forge.pixpin.guardados

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **La letra de una clase con capturas** (25-oct-2026, `pintar_capturas_de_la_letra` del PC): la
 * imagen del minuto que suena y, debajo, una ficha por captura con su minuto; un toque lleva el
 * audio (y la letra) ahí. Ver [GrabarClase].
 */

/**
 * Las capturas de la nota [m], `(ms, ruta)` en orden, buscadas entre [todos] los mensajes. Las que
 * ya no están en el chat o en el disco se saltan (como `capturas_de_la_letra` del PC).
 */
fun capturasDeLaLetra(m: Mensaje, todos: List<Mensaje>): List<Pair<Long, String>> {
    val enlaces = GrabarClase.capturasDe(m)
    if (enlaces.isEmpty()) return emptyList()
    val porId = todos.associateBy { it.id }
    return enlaces.mapNotNull { e ->
        val ruta = porId[e.id]?.ruta?.takeIf { File(it).exists() } ?: return@mapNotNull null
        e.ms to ruta
    }
}

/** La ruta del vídeo de la clase ([Mensaje.video]), si sigue en el chat y en el disco. */
fun videoDeLaLetra(m: Mensaje, todos: List<Mensaje>): String? {
    val id = GrabarClase.videoDe(m) ?: return null
    return todos.firstOrNull { it.id == id }?.ruta?.takeIf { File(it).exists() }
}

/** El panel: la imagen que toca en [posicionMs] arriba y las fichas abajo. */
@Composable
fun PanelDeCapturas(
    capturas: List<Pair<Long, String>>,
    posicionMs: Long,
    modifier: Modifier = Modifier,
    /** Si la clase trae vídeo de la pantalla: una ficha «▶ Vídeo» delante que lo abre. */
    alVerVideo: (() -> Unit)? = null,
    alTocar: (Long) -> Unit
) {
    if (capturas.isEmpty() && alVerVideo == null) return
    val actual = GrabarClase.capturaEn(capturas.map { it.first }, posicionMs)
    val ruta = actual?.let { capturas[it].second }
    // La anterior se queda hasta que la nueva está leída: sin parpadeo al cambiar de minuto.
    var imagen by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(ruta) {
        if (ruta != null) leerReducida(ruta)?.let { imagen = it }
    }
    Column(modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (capturas.isNotEmpty()) Box(
            Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        ) {
            imagen?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        val lista = rememberLazyListState()
        val delante = if (alVerVideo != null) 1 else 0
        LaunchedEffect(actual) { if (actual != null) runCatching { lista.animateScrollToItem(actual + delante) } }
        LazyRow(state = lista, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (alVerVideo != null) item {
                Text(
                    "▶ " + androidx.compose.ui.res.stringResource(com.forge.pixpin.R.string.clase_ver_video),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { alVerVideo() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            itemsIndexed(capturas) { i, (ms, _) ->
                val es = i == actual
                Text(
                    "⏱ " + Transcriptor.marcaDeTiempo(ms.toInt()),
                    fontSize = 13.sp,
                    color = if (es) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            if (es) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        )
                        .clickable { alTocar(ms) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/** La imagen reducida a lo que cabe en una pantalla: una captura entera pesa decenas de megas. */
private suspend fun leerReducida(ruta: String, lado: Int = 2048): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ruta, medidas)
        var paso = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / paso > lado) paso *= 2
        BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = paso })?.asImageBitmap()
    }.getOrNull()
}
