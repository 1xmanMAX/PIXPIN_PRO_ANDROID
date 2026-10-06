package com.forge.pixpin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.ui.theme.BotonRedondo
import com.forge.pixpin.ui.theme.Cristal
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **La galería de capturas** (como la del PC, 3-oct-2026): todas las capturas de PixPin, la más
 * nueva primero, en una rejilla. En el móvil viven en `Pictures/PixPin` (la galería del sistema
 * también las ve). Cada una: abrir para anotar, sacar a la pantalla, copiar, compartir, al chat
 * y a la papelera.
 */
class GaleriaDeCapturasActivity : ComponentActivity() {

    private class Captura(val uri: Uri, val nombre: String, val cuando: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PixPinTheme { Pantalla() } }
    }

    private fun leer(): List<Captura> {
        val salida = ArrayList<Captura>()
        val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        runCatching {
            contentResolver.query(
                col, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("%Pictures/PixPin%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) salida += Captura(ContentUris.withAppendedId(col, c.getLong(0)), c.getString(1) ?: "captura", c.getLong(2) * 1000)
            }
        }
        return salida
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Pantalla() {
        var capturas by remember { mutableStateOf<List<Captura>?>(null) }
        var elegida by remember { mutableStateOf<Captura?>(null) }
        var version by remember { mutableIntStateOf(0) }
        LaunchedEffect(version) { capturas = withContext(Dispatchers.IO) { leer() } }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BotonRedondo(Icons.AutoMirrored.Filled.ArrowBack, "Volver", { finish() }, tamano = 44.dp)
                Spacer(Modifier.width(10.dp))
                Text(capturas?.let { if (it.isEmpty()) "Capturas" else "Capturas · ${it.size}" } ?: "Capturas",
                    style = MaterialTheme.typography.titleLarge, color = Cristal.tinta)
            }
            val lista = capturas
            when {
                lista == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                lista.isEmpty() -> Text(
                    "Todavía no hay capturas. Las que hagas con la bola o el botón de ajustes rápidos aparecerán aquí.",
                    color = Cristal.tinta.copy(alpha = 0.75f), modifier = Modifier.padding(24.dp)
                )
                else -> LazyVerticalGrid(GridCells.Adaptive(112.dp), contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(lista, key = { it.uri.toString() }) { c -> Miniatura(c) { elegida = c } }
                }
            }
        }
        elegida?.let { c ->
            ModalBottomSheet(onDismissRequest = { elegida = null }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(c.nombre, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "Abrir para anotar" to { abrir(c) },
                        "Pinear" to { pinear(c) },
                        "Copiar" to { copiar(c) },
                        "Compartir" to { compartir(c) },
                        "Al chat" to { alChat(c) },
                        "Papelera" to { papelera(c) { version++ } }
                    ).forEach { (nombre, hacer) ->
                        TextButton(onClick = { elegida = null; hacer() }, modifier = Modifier.fillMaxWidth()) {
                            Text(nombre, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    @Composable
    private fun Miniatura(c: Captura, onClick: () -> Unit) {
        var mapa by remember(c.uri) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(c.uri) {
            mapa = withContext(Dispatchers.IO) {
                runCatching { contentResolver.loadThumbnail(c.uri, Size(256, 256), null).asImageBitmap() }.getOrNull()
            }
        }
        Box(Modifier.aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)).background(Cristal.boton).clickable(onClick = onClick)) {
            mapa?.let { Image(it, c.nombre, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }

    /** Una copia local: el editor, el pin y el chat trabajan con archivos nuestros. */
    private suspend fun local(c: Captura): File? = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(cacheDir, "galeria_${c.nombre}")
            contentResolver.openInputStream(c.uri)?.use { e -> f.outputStream().use { e.copyTo(it) } } ?: return@runCatching null
            f
        }.getOrNull()
    }

    private fun abrir(c: Captura) = lifecycleScope.launch {
        val f = local(c) ?: return@launch aviso("No se pudo abrir")
        val guardada = withContext(Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.let { com.forge.pixpin.pin.ImageStore.saveBitmap(this@GaleriaDeCapturasActivity, it, "galeria_${System.currentTimeMillis()}") }
        } ?: return@launch aviso("No se pudo abrir")
        val id = "dib-${System.currentTimeMillis()}"
        com.forge.pixpin.motor.DrawEditorActivity.abrir(this@GaleriaDeCapturasActivity, id, com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this@GaleriaDeCapturasActivity, id), guardada)
    }

    private fun pinear(c: Captura) = lifecycleScope.launch {
        val f = local(c) ?: return@launch aviso("No se pudo")
        val guardada = withContext(Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.let { com.forge.pixpin.pin.ImageStore.saveBitmap(this@GaleriaDeCapturasActivity, it, "galeria_${System.currentTimeMillis()}") }
        } ?: return@launch aviso("No se pudo")
        (application as PixPinApp).overlayManager.pinImage(guardada)
        aviso("Puesta en la pantalla")
    }

    private fun copiar(c: Captura) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newUri(contentResolver, c.nombre, c.uri))
        aviso("Copiada al portapapeles")
    }

    private fun compartir(c: Captura) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, c.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }

    private fun alChat(c: Captura) = lifecycleScope.launch {
        val bien = withContext(Dispatchers.IO) { com.forge.pixpin.guardados.AlChat.meter(this@GaleriaDeCapturasActivity, c.uri, null, c.nombre) }
        aviso(if (bien) "Añadida a Mensajes guardados" else "No se pudo añadir")
    }

    private fun papelera(c: Captura, luego: () -> Unit) = lifecycleScope.launch {
        val bien = withContext(Dispatchers.IO) { runCatching { contentResolver.delete(c.uri, null, null) > 0 }.getOrDefault(false) }
        aviso(if (bien) "Borrada" else "Android no deja borrarla desde aquí: bórrala desde la galería")
        luego()
    }

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    companion object {
        fun abrir(context: Context) = context.startActivity(Intent(context, GaleriaDeCapturasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
