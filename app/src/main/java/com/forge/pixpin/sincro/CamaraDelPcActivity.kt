package com.forge.pixpin.sincro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * **La cámara para la reunión que graba el PC** (28-oct-2026, §7 de `docs/movil-como-camara.md`
 * del PC): a pantalla completa, la vista previa, un disparador grande abajo, a su lado el de clip
 * (solo si el PC recibe clips: `codecs` en el anuncio) y arriba «● 12:34 grabando en el PC» con el
 * reloj de la grabación que lleva este teléfono ([RelojDelMovil]). En pausa el disparador sigue:
 * la foto entra en el minuto en que se pausó.
 *
 * Cada disparo se apunta en la [ColaDeLaCamara] con el minuto **del momento del disparo** y se sube
 * en segundo plano ([SubidaDeLaCamaraService]); abajo, una miniatura de cada una con ✓ cuando el
 * PC dice «listo». Al acabar la grabación, la pantalla lo dice y lo pendiente sigue subiendo;
 * cerrarla no tira nada.
 *
 * - **Foto**: `CAPTURE_MODE_MAXIMIZE_QUALITY` y la resolución más alta, a fichero: JPEG con su
 *   EXIF ([FotoEnJpeg] por si el aparato diera HEIC).
 * - **Clip**: 1080p (o lo más cercano), unos 5 Mbit/s, audio AAC, MP4; se corta solo en
 *   `clipMaximoMs`. En H.264: el `Recorder` de CameraX 1.5 no deja elegir HEVC (solo lo usa para
 *   vídeo HDR), y H.264 lo ve cualquier PC (ver `docs/movil-como-camara-android.md`).
 */
class CamaraDelPcActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { PixPinTheme(darkTheme = true) { Pantalla(cerrar = { finish() }) } }
    }

    override fun onStart() {
        super.onStart()
        // Para que el PC pueda avisar a este teléfono: escuchar mientras se ve.
        Presencia.encender()
        CamaraDelPc.pantallaDeCamara(true)
    }

    override fun onStop() {
        CamaraDelPc.pantallaDeCamara(false)
        super.onStop()
    }

    companion object {
        fun abrir(context: Context) {
            context.startActivity(Intent(context, CamaraDelPcActivity::class.java).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}

private fun tiene(c: Context, permiso: String) = ContextCompat.checkSelfPermission(c, permiso) == PackageManager.PERMISSION_GRANTED

private fun nombreDeToma(prefijo: String, cuando: Long, ext: String) =
    prefijo + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(cuando)) + "." + ext

/** **Apunta la toma** en la cola y que suba. */
private fun apuntar(c: Context, cola: ColaDeLaCamara, v: CamaraDelPc.Visto, f: File, nombre: String, mime: String, ms: Long, tomadaEn: Long, duracionMs: Long = 0) {
    cola.poner(
        ColaDeLaCamara.Toma(
            clave = "toma-" + UUID.randomUUID(), fichero = f.name, nombre = nombre, mime = mime,
            grabacion = v.anuncio.grabacion, pc = v.pc, pcNombre = v.nombreDelPc, titulo = v.anuncio.titulo,
            ms = ms, tomadaEn = tomadaEn, reloj = v.adelanto, duracionMs = duracionMs, creada = System.currentTimeMillis()
        )
    )
    SubidaDeLaCamaraService.empujar(c)
}

/**
 * **Una foto sin grabación a la vista** (28-oct-2026, §12.4 de la guía del PC: «que se puedan tomar
 * fotos normalmente aunque NO se esté conectado»): entra ya en la Conversación general como una foto
 * normal, con su hora (`tomadaEn`), y se apunta en la cola **por su hora** (sin `grabacion`, a
 * cualquier PC del grupo, con su uid de `clave`). Cuando un PC la oiga, la pone en la grabación donde
 * caiga su hora; si no cae en ninguna, se queda como está. Devuelve si se guardó.
 */
private fun apuntarPorSuHora(c: Context, cola: ColaDeLaCamara, f: File, nombre: String, cuando: Long): Boolean {
    val almacen = com.forge.pixpin.guardados.MensajesStore(c)
    val ruta = almacen.copiarAdjunto(f, nombre, "jpg", aligerar = false) ?: return false
    val m = com.forge.pixpin.guardados.Mensaje(
        id = UUID.randomUUID().toString(), cuando = cuando, clase = com.forge.pixpin.guardados.Clase.IMAGEN,
        ruta = ruta, nombre = nombre, bytes = File(ruta).length(), tomadaEn = cuando
    )
    almacen.anadir(m)
    cola.poner(
        ColaDeLaCamara.Toma(
            clave = Codigos.unico(m), fichero = f.name, nombre = nombre, mime = "image/jpeg",
            grabacion = "", pc = "", ms = 0, tomadaEn = cuando, creada = System.currentTimeMillis()
        )
    )
    SubidaDeLaCamaraService.empujar(c)
    return true
}

@SuppressLint("MissingPermission")
@Composable
private fun Pantalla(cerrar: () -> Unit) {
    val contexto = LocalContext.current
    val dueno = LocalLifecycleOwner.current
    val cola = remember { ColaDeLaCamara.de(contexto.filesDir) }
    val visto by CamaraDelPc.visto.collectAsState()
    val preguntado by CamaraDelPc.preguntado.collectAsState()
    var conCamara by remember { mutableStateOf(tiene(contexto, Manifest.permission.CAMERA)) }
    var conMicro by remember { mutableStateOf(tiene(contexto, Manifest.permission.RECORD_AUDIO)) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        conCamara = r[Manifest.permission.CAMERA] ?: conCamara
        conMicro = r[Manifest.permission.RECORD_AUDIO] ?: conMicro
    }
    LaunchedEffect(Unit) {
        if (!conCamara || !conMicro) pedir.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    // El reloj de la pantalla: cuatro veces por segundo.
    var ahora by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) { ahora = SystemClock.elapsedRealtime(); delay(250) }
    }
    val foto = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
            .build()
    }
    val grabadora = remember {
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
            .setTargetVideoEncodingBitRate(5_000_000)
            .build()
    }
    val video = remember { VideoCapture.withOutput(grabadora) }
    var conVideo by remember { mutableStateOf(false) }
    var clip by remember { mutableStateOf<Recording?>(null) }
    var clipDesde by remember { mutableLongStateOf(0L) }
    // La foto sale derecha aunque el teléfono se gire: la rotación de los casos de uso sigue a la mano.
    DisposableEffect(Unit) {
        val oyente = object : OrientationEventListener(contexto) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                foto.targetRotation = r
                video.targetRotation = r
            }
        }
        oyente.enable()
        onDispose {
            oyente.disable()
            runCatching { ProcessCameraProvider.getInstance(contexto).get().unbindAll() }
        }
    }
    val cambios by ColaDeLaCamara.cambios.collectAsState()
    val grabacion = visto?.anuncio?.grabacion
    val tomas by produceState(emptyList<ColaDeLaCamara.Toma>(), cambios, grabacion) {
        value = withContext(Dispatchers.IO) { cola.todas().filter { it.grabacion == (grabacion ?: "") }.sortedByDescending { it.creada } }
    }

    val disparar = {
        val v = visto
        if (v == null || !v.grabando) {
            // Sin grabación a la vista: por su hora (§12.4).
            val cuando = System.currentTimeMillis()
            val f = cola.ficheroNuevo("jpg")
            foto.takePicture(
                ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(contexto),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                        Thread {
                            val bien = FotoEnJpeg.asegurar(f) && apuntarPorSuHora(contexto, cola, f, nombreDeToma("IMG", cuando, "jpg"), cuando)
                            if (!bien) f.delete()
                        }.start()
                    }
                    override fun onError(e: ImageCaptureException) {
                        f.delete()
                        Toast.makeText(contexto, "No se pudo hacer la foto: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            // El minuto y la hora, en el disparo: no cuando acabe de guardarse ni cuando se mande.
            val ms = v.reloj.ms(SystemClock.elapsedRealtime())
            val cuando = System.currentTimeMillis()
            val f = cola.ficheroNuevo("jpg")
            foto.takePicture(
                ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(contexto),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                        Thread {
                            if (FotoEnJpeg.asegurar(f)) apuntar(contexto, cola, v, f, nombreDeToma("IMG", cuando, "jpg"), "image/jpeg", ms, cuando)
                            else f.delete()
                        }.start()
                    }
                    override fun onError(e: ImageCaptureException) {
                        f.delete()
                        Toast.makeText(contexto, "No se pudo hacer la foto: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
        Unit
    }
    val grabarClip = {
        val v = visto
        val enMarcha = clip
        if (enMarcha != null) {
            enMarcha.stop()
        } else if (v == null || !v.grabando) {
            Toast.makeText(contexto, "Ningún PC está grabando ahora", Toast.LENGTH_SHORT).show()
        } else {
            val ms = v.reloj.ms(SystemClock.elapsedRealtime())
            val cuando = System.currentTimeMillis()
            val f = cola.ficheroNuevo("mp4")
            val opciones = FileOutputOptions.Builder(f).setDurationLimitMillis(v.anuncio.clipMaximo).build()
            var pendiente = grabadora.prepareRecording(contexto, opciones)
            if (tiene(contexto, Manifest.permission.RECORD_AUDIO)) pendiente = pendiente.withAudioEnabled()
            clipDesde = SystemClock.elapsedRealtime()
            clip = pendiente.start(ContextCompat.getMainExecutor(contexto)) { e ->
                if (e is VideoRecordEvent.Finalize) {
                    clip = null
                    val dura = e.recordingStats.recordedDurationNanos / 1_000_000
                    val vale = !e.hasError() || e.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                    if (vale && f.isFile && f.length() > 0) apuntar(contexto, cola, v, f, nombreDeToma("VID", cuando, "mp4"), "video/mp4", ms, cuando, dura)
                    else { f.delete(); Toast.makeText(contexto, "No se pudo grabar el clip", Toast.LENGTH_SHORT).show() }
                }
            }
        }
        Unit
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (conCamara) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val vista = PreviewView(ctx)
                    vista.scaleType = PreviewView.ScaleType.FILL_CENTER
                    val futuro = ProcessCameraProvider.getInstance(ctx)
                    futuro.addListener({
                        val proveedor = runCatching { futuro.get() }.getOrNull()
                        if (proveedor != null) {
                            val mirilla = Preview.Builder().build().also { it.surfaceProvider = vista.surfaceProvider }
                            proveedor.unbindAll()
                            // Con vídeo si el aparato deja los tres a la vez; si no, solo fotos.
                            val conTodo = runCatching {
                                proveedor.bindToLifecycle(dueno, CameraSelector.DEFAULT_BACK_CAMERA, mirilla, foto, video)
                            }.isSuccess
                            if (!conTodo) runCatching {
                                proveedor.unbindAll()
                                proveedor.bindToLifecycle(dueno, CameraSelector.DEFAULT_BACK_CAMERA, mirilla, foto)
                            }
                            conVideo = conTodo
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    vista
                }
            )
        } else {
            Text(
                "PixPin necesita la cámara para hacer las fotos de la reunión.",
                color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )
        }

        // Arriba: volver, y cómo va la grabación del PC.
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = cerrar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Color.White) }
                val v = visto
                val texto = when {
                    v != null -> RelojDelMovil.rotulo(v.reloj.ms(ahora), v.reloj.enPausa, !v.grabando)
                    preguntado -> "Fotos por su hora"
                    else -> "Buscando la grabación del PC…"
                }
                val color = when {
                    v == null -> Color(0x99000000)
                    !v.grabando -> Color(0xCC555555)
                    v.reloj.enPausa -> Color(0xCCB07800)
                    else -> Color(0xCCC62828)
                }
                Text(
                    texto, color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(color).padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            val v = visto
            val detalle = when {
                v == null && preguntado -> "Sin conexión con una grabación: las fotos se guardan en la Conversación general con su hora y, al conectar, entran en la grabación del PC donde caiga su hora."
                v != null && v.grabando -> "«${v.titulo}» · ${v.nombreDelPc}"
                v != null -> "«${v.titulo}» terminó. Lo pendiente sigue subiendo y entra en su minuto."
                else -> null
            }
            if (detalle != null) Text(detalle, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
        }

        // Abajo: las miniaturas y los disparadores.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            if (tomas.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(tomas, key = { it.clave }) { t -> Miniatura(t, cola.fichero(t)) }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                val v = visto
                val conClips = v != null && v.anuncio.conClips && conVideo
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    if (conClips) {
                        val grabando = clip != null
                        Box(
                            Modifier.size(56.dp).clip(CircleShape)
                                .background(if (grabando) Color(0xFFC62828) else Color(0x66000000))
                                .border(2.dp, Color.White, CircleShape)
                                .clickable { grabarClip() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (grabando) Text(CamaraRemota.minuto(ahora - clipDesde), color = Color.White, fontSize = 12.sp)
                            else Icon(Icons.Filled.Videocam, "Grabar un clip", tint = Color.White)
                        }
                    }
                }
                // El disparador grande.
                Box(
                    Modifier.size(84.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(8.dp)
                        .clip(CircleShape).background(Color.White)
                        .clickable { disparar() }
                )
                Spacer(Modifier.size(64.dp))
            }
            if (conClips(visto) && !conMicro) {
                TextButton(onClick = { pedir.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Los clips sin sonido: permitir el micrófono", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun conClips(v: CamaraDelPc.Visto?) = v != null && v.anuncio.conClips

/** Una toma: su miniatura y si ya está en el PC (✓), subiendo (…) o negada (!). */
@Composable
private fun Miniatura(t: ColaDeLaCamara.Toma, f: File) {
    val imagen by produceState<ImageBitmap?>(null, t.clave) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val b = if (t.esClip) ThumbnailUtils.createVideoThumbnail(f, Size(160, 160), null)
                else ThumbnailUtils.createImageThumbnail(f, Size(160, 160), null)
                b.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF333333))) {
        val i = imagen
        if (i != null) Image(i, t.nombre, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        val (marca, fondo) = when (t.estado) {
            ColaDeLaCamara.LISTA -> "✓" to Color(0xFF2E9E4F)
            // Por su hora y fuera de toda grabación: se quedó como una foto normal, no es un fallo.
            ColaDeLaCamara.NEGADA -> if (t.porSuHora && CamaraRemota.esNinguna(t.error.orEmpty())) "·" to Color(0x99000000) else "!" to Color(0xFFE0453A)
            else -> "…" to Color(0x99000000)
        }
        Text(
            marca, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).clip(CircleShape).background(fondo).padding(horizontal = 5.dp)
        )
        Text(
            if (t.porSuHora && t.msListo == null) "🕒" else (if (t.esClip) "▶ " else "") + CamaraRemota.minuto(t.msListo ?: t.ms), color = Color.White, fontSize = 9.sp,
            modifier = Modifier.align(Alignment.TopStart).background(Color(0x66000000)).padding(horizontal = 2.dp)
        )
    }
}
