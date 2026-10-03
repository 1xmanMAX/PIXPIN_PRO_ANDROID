package com.forge.pixpin.lecciones

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.pin.Voz
import com.forge.pixpin.pin.duracionLegible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * **Fotos y audios de una lección** (4-oct-2026): la foto del error en la obra, la nota de voz
 * con lo que dijo el profesor. Tres botones —cámara, galería, grabar— y la tira de lo puesto.
 *
 * Lo nuevo es [nuevos]: mensajes ya con su archivo en `guardados/` pero aún sin chat; se meten en
 * él al guardar la lección ([LeccionesStore.guardar]) y, si se descarta, quien llama borra sus
 * archivos. Lo que ya estaba es [viejos]; quitarlo lo apunta en [quitados] y se borra al guardar.
 */
@Composable
fun AdjuntosDeLaLeccion(
    viejos: List<Mensaje>,
    nuevos: SnapshotStateList<Mensaje>,
    quitados: SnapshotStateList<String>
) {
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    val carpeta = remember { MensajesStore(contexto).carpetaDeAdjuntos() }

    // La cámara escribe directamente en el archivo que le damos.
    var fotoEnCurso by remember { mutableStateOf<File?>(null) }
    val camara = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { bien ->
        val f = fotoEnCurso; fotoEnCurso = null
        if (bien && f != null && f.length() > 0) nuevos += mensaje(f, Clase.IMAGEN, "Foto de la lección")
        else f?.delete()
    }
    fun hacerFoto() {
        val f = File(carpeta, "leccion_${System.currentTimeMillis()}.jpg")
        fotoEnCurso = f
        runCatching { camara.launch(FileProvider.getUriForFile(contexto, "${contexto.packageName}.fileprovider", f)) }
            .onFailure { fotoEnCurso = null; Toast.makeText(contexto, "No hay cámara disponible", Toast.LENGTH_SHORT).show() }
    }

    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        alcance.launch {
            val copiadas = withContext(Dispatchers.IO) { uris.mapNotNull { copiar(contexto, it, carpeta) } }
            copiadas.forEach { nuevos += mensaje(it, Clase.IMAGEN, "Foto de la lección") }
        }
    }

    // Grabar aquí mismo, sin salir de la hoja.
    var grabador by remember { mutableStateOf<com.forge.pixpin.audio.Grabador?>(null) }
    var audioEnCurso by remember { mutableStateOf<File?>(null) }
    var desde by remember { mutableLongStateOf(0L) }
    fun empezarAGrabar() {
        val f = File(carpeta, "voz_${System.currentTimeMillis()}.m4a")
        val g = Voz.empezar(contexto, f)
        if (g == null) { Toast.makeText(contexto, "No se pudo usar el micrófono", Toast.LENGTH_SHORT).show(); return }
        audioEnCurso = f; grabador = g; desde = System.currentTimeMillis()
    }
    fun pararDeGrabar() {
        val g = grabador ?: return
        val f = audioEnCurso
        grabador = null; audioEnCurso = null
        val bien = Voz.parar(g)
        val ms = if (f != null) Voz.duracion(f.absolutePath) else 0
        if (bien && f != null && f.exists() && ms >= Voz.MINIMO_MS) nuevos += mensaje(f, Clase.VOZ, "Audio de la lección", ms)
        else { f?.delete(); Toast.makeText(contexto, "Muy corto: no se guardó", Toast.LENGTH_SHORT).show() }
    }
    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { si ->
        if (si) empezarAGrabar() else Toast.makeText(contexto, "Sin permiso para el micrófono", Toast.LENGTH_SHORT).show()
    }
    // Si la hoja se va grabando (pantalla apagada, otra app), lo grabado se queda.
    val ciclo = LocalLifecycleOwner.current
    DisposableEffect(ciclo) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) pararDeGrabar() }
        ciclo.lifecycle.addObserver(o)
        onDispose { ciclo.lifecycle.removeObserver(o); pararDeGrabar() }
    }

    // Un solo reproductor para toda la tira.
    var sonando by remember { mutableStateOf<String?>(null) }
    val reproductor = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(Unit) { onDispose { runCatching { reproductor[0]?.release() } } }
    fun tocar(m: Mensaje) {
        runCatching { reproductor[0]?.release() }; reproductor[0] = null
        if (sonando == m.id) { sonando = null; return }
        val p = runCatching { MediaPlayer().apply { setDataSource(m.ruta); prepare(); start() } }.getOrNull() ?: return
        p.setOnCompletionListener { sonando = null }
        reproductor[0] = p; sonando = m.id
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(onClick = { hacerFoto() }, label = { Text("Foto") }, leadingIcon = { Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp)) })
        AssistChip(
            onClick = { galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            label = { Text("Galería") }, leadingIcon = { Icon(Icons.Filled.Image, null, Modifier.size(18.dp)) }
        )
        if (grabador == null) AssistChip(
            onClick = {
                if (ContextCompat.checkSelfPermission(contexto, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) empezarAGrabar()
                else permiso.launch(Manifest.permission.RECORD_AUDIO)
            },
            label = { Text("Audio") }, leadingIcon = { Icon(Icons.Filled.Mic, null, Modifier.size(18.dp)) }
        ) else {
            var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { ahora = System.currentTimeMillis(); delay(250) } }
            FilledTonalButton(
                onClick = { pararDeGrabar() },
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0x33E5534B))
            ) {
                Icon(Icons.Filled.Stop, null, tint = Color(0xFFE5534B), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Parar " + duracionLegible((ahora - desde).toInt()))
            }
        }
    }

    val visibles = viejos.filter { it.id !in quitados } + nuevos
    if (visibles.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        visibles.forEach { m ->
            val esNuevo = nuevos.any { it.id == m.id }
            Box {
                if (m.clase == Clase.VOZ) Surface(
                    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.height(72.dp).clip(RoundedCornerShape(16.dp)).clickable { tocar(m) }
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (sonando == m.id) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Escuchar")
                        Spacer(Modifier.width(6.dp))
                        Text(duracionLegible(m.duracionMs))
                    }
                } else Miniatura(m.ruta) {
                    // Las que ya están en el chat se abren con su visor; las nuevas, aún no.
                    if (!esNuevo) com.forge.pixpin.guardados.MensajesActivity.abrirYAbrir(contexto, m.id)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp).background(Color(0xAA000000), CircleShape)
                        .clickable {
                            if (sonando == m.id) tocar(m)
                            if (esNuevo) { nuevos.removeAll { it.id == m.id }; m.ruta?.let { File(it).delete() } }
                            else quitados += m.id
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Close, "Quitar", tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}

@Composable
private fun Miniatura(ruta: String?, alTocar: () -> Unit) {
    val imagen by produceState<ImageBitmap?>(null, ruta) {
        value = withContext(Dispatchers.IO) { ruta?.let { reducida(it, 220) } }
    }
    val forma = RoundedCornerShape(14.dp)
    Box(Modifier.size(72.dp).clip(forma).background(MaterialTheme.colorScheme.surfaceVariant).clickable { alTocar() }) {
        imagen?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** La foto a unos [lado] píxeles: una miniatura no necesita la foto entera en memoria. */
private fun reducida(ruta: String, lado: Int): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(ruta, o)
    var muestra = 1
    while (o.outWidth / (muestra * 2) >= lado && o.outHeight / (muestra * 2) >= lado) muestra *= 2
    BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = muestra })?.asImageBitmap()
}.getOrNull()

private fun copiar(contexto: Context, uri: Uri, carpeta: File): File? = runCatching {
    val tipo = contexto.contentResolver.getType(uri).orEmpty()
    val ext = when { "png" in tipo -> "png"; "webp" in tipo -> "webp"; "gif" in tipo -> "gif"; else -> "jpg" }
    val f = File(carpeta, "leccion_${System.currentTimeMillis()}_${(0..999).random()}.$ext")
    contexto.contentResolver.openInputStream(uri)!!.use { entrada -> f.outputStream().use { entrada.copyTo(it) } }
    f
}.getOrNull()

private fun mensaje(f: File, clase: Clase, nombre: String, duracionMs: Int = 0) = Mensaje(
    id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = clase,
    ruta = f.absolutePath, nombre = nombre, bytes = f.length(), duracionMs = duracionMs
)
