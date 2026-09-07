package com.forge.pixpin.guardados

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.forge.pixpin.R
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.PdfMiniaturas
import com.forge.pixpin.pin.Voz
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * **Pronunciar: hablar, oírse y repetir, con una guía delante.**
 *
 * Para practicar un idioma —o la propia dicción— lo que hace falta es un bucle corto:
 * se mantiene pulsado el botón, se dice la frase, se suelta y **se oye al momento**. Si
 * salió mal, se vuelve a pulsar; cada toma pisa la anterior, que era un ensayo. La que
 * convenza se guarda en el chat con «Guardar», y ahí se pasa a texto como cualquier nota
 * de voz —en el idioma que se practica, no en el del teléfono— para ver qué entendió el
 * reconocedor. Lo pidió el usuario (6-sep-2026).
 *
 * **La guía** es lo que se está leyendo o mirando: un texto escrito aquí mismo, una
 * nota `.md` o `.txt`, una imagen (la foto de un libro) o una página de un PDF, del
 * teléfono o de lo que hay en el chat. Ocupa la pantalla; el botón va abajo, bajo el
 * pulgar.
 */
class PronunciarActivity : ComponentActivity() {

    private var grabador: android.media.MediaRecorder? = null
    private var grabandoEn: File? = null
    private var reproductor: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val proyecto = intent.getStringExtra(EL_PROYECTO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        aPantallaCompleta()
        setContent { PixPinTheme { Pantalla(proyecto) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        Voz.parar(grabador); grabador = null
        soltarReproductor()
        carpeta().deleteRecursively()
    }

    private fun carpeta(): File = File(cacheDir, "pronunciar").apply { mkdirs() }

    private fun soltarReproductor() { runCatching { reproductor?.stop() }; runCatching { reproductor?.release() }; reproductor = null }

    /** Lo que se tiene delante mientras se habla. */
    private sealed class Guia {
        object Ninguna : Guia()
        class Texto(val texto: String) : Guia()
        class Imagen(val archivo: File) : Guia()
        class Pdf(val archivo: File, val paginas: Int) : Guia()
    }

    /** Una guía que se puede traer del chat: cómo se llama y cómo se carga. */
    private class Opcion(val titulo: String, val cargar: () -> Guia)

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun Pantalla(proyecto: String?) {
        val almacen = remember { MensajesStore(this) }
        val alcance = rememberCoroutineScope()
        var guia by remember { mutableStateOf<Guia>(Guia.Ninguna) }
        var pagina by remember { mutableIntStateOf(0) }
        var eligiendo by remember { mutableStateOf(false) }
        var escribiendo by remember { mutableStateOf(false) }
        var tamano by rememberSaveable { mutableIntStateOf(24) }
        var idioma by rememberSaveable { mutableStateOf("") }
        var grabando by remember { mutableStateOf(false) }
        // **El reloj mientras se habla.** Manteniendo pulsado no hay forma de saber si el
        // micrófono está cogiendo algo; un número que corre lo dice sin explicar nada.
        var hablandoDesde by remember { mutableLongStateOf(0L) }
        var hablando by remember { mutableIntStateOf(0) }
        // La última toma: el archivo, lo que dura y si ya se guardó (para no guardarla dos veces).
        var toma by remember { mutableStateOf<File?>(null) }
        var tomaMs by remember { mutableIntStateOf(0) }
        var guardada by remember { mutableStateOf(false) }

        // Traer algo del teléfono: se copia a la caché y se enseña.
        val abrirDocumento = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            alcance.launch {
                val g = withContext(Dispatchers.IO) { guiaDe(uri) }
                if (g == null) Toast.makeText(this@PronunciarActivity, R.string.pronunciar_no_se_pudo, Toast.LENGTH_SHORT).show()
                else { guia = g; pagina = 0 }
            }
        }

        fun oir(f: File) {
            soltarReproductor()
            reproductor = runCatching {
                MediaPlayer().apply {
                    setDataSource(f.absolutePath); prepare(); start()
                    setOnCompletionListener { soltarReproductor() }
                }
            }.getOrNull()
        }
        fun empezar() {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
            }
            soltarReproductor()
            val destino = File(carpeta(), "toma-${System.currentTimeMillis()}.m4a")
            grabador = Voz.empezar(this, destino) ?: return
            grabandoEn = destino
            hablandoDesde = System.currentTimeMillis()
            hablando = 0
            grabando = true
        }
        fun soltar() {
            if (!grabando) return
            grabando = false
            val bien = Voz.parar(grabador); grabador = null
            val f = grabandoEn; grabandoEn = null
            if (!bien || f == null || !f.exists()) { f?.delete(); return }
            val ms = Voz.duracion(f.absolutePath)
            if (ms < Voz.MINIMO_MS) { f.delete(); Toast.makeText(this, R.string.voz_muy_corta, Toast.LENGTH_SHORT).show(); return }
            // La toma anterior era un ensayo: fuera, salvo que se hubiera guardado (ya está copiada).
            toma?.delete()
            toma = f; tomaMs = ms; guardada = false
            oir(f)
        }
        fun guardar() {
            val f = toma ?: return
            if (guardada) return
            guardada = true
            alcance.launch {
                withContext(Dispatchers.IO) {
                    val ahora = System.currentTimeMillis()
                    val ruta = almacen.copiarAdjunto(f, "pronunciar-$ahora.m4a") ?: return@withContext
                    almacen.anadir(
                        Mensaje(id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.VOZ, ruta = ruta, duracionMs = tomaMs, proyecto = proyecto),
                        transcribir = true,
                        idioma = idioma.ifBlank { null }
                    )
                }
                Toast.makeText(this@PronunciarActivity, R.string.pronunciar_guardado, Toast.LENGTH_LONG).show()
            }
        }

        LaunchedEffect(grabando) {
            while (grabando) {
                hablando = (System.currentTimeMillis() - hablandoDesde).toInt()
                kotlinx.coroutines.delay(100)
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.cancel)) }
                    Text(getString(R.string.pronunciar_titulo), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    if (guia is Guia.Texto) {
                        IconButton(onClick = { if (tamano > 14) tamano -= 2 }) { Icon(Icons.Filled.TextDecrease, contentDescription = null) }
                        IconButton(onClick = { if (tamano < 44) tamano += 2 }) { Icon(Icons.Filled.TextIncrease, contentDescription = null) }
                    }
                    IconButton(onClick = { eligiendo = true }) { Icon(Icons.Filled.Description, contentDescription = getString(R.string.pronunciar_guia)) }
                }
                // **El idioma que se practica**: manda en la transcripción de lo que se guarde.
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    Text(
                        getString(R.string.pronunciar_idioma),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for ((codigo, nombre) in IDIOMAS) {
                            FilterChip(
                                selected = idioma == codigo,
                                onClick = { idioma = codigo },
                                label = { Text(nombre, fontSize = 12.sp) }
                            )
                        }
                    }
                }
                // **La guía**, con lo que quede de pantalla.
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                    when (val g = guia) {
                        Guia.Ninguna -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(getString(R.string.pronunciar_sin_guia), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            TextButton(onClick = { eligiendo = true }) { Text(getString(R.string.pronunciar_guia)) }
                        }
                        is Guia.Texto -> Text(
                            g.texto, fontSize = tamano.sp, lineHeight = (tamano * 1.45f).sp,
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                        is Guia.Imagen -> Lamina(g.archivo.absolutePath, null, 0)
                        is Guia.Pdf -> Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) { Lamina(g.archivo.absolutePath, g.paginas, pagina) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                IconButton(enabled = pagina > 0, onClick = { pagina-- }) { Icon(Icons.Filled.ChevronLeft, contentDescription = null) }
                                Text(getString(R.string.pronunciar_pagina, pagina + 1, g.paginas), style = MaterialTheme.typography.labelMedium)
                                IconButton(enabled = pagina < g.paginas - 1, onClick = { pagina++ }) { Icon(Icons.Filled.ChevronRight, contentDescription = null) }
                            }
                        }
                    }
                }
                // **La toma y el botón, con los tres estados dichos claros.**
                //
                // El bucle que pidió el usuario es: mantener, hablar, soltar, oírse y repetir.
                // Así que el pie dice en cada momento **cuál de los tres** es: mientras se
                // habla, un reloj y «suelta para oírte»; con una toma hecha, oírla otra vez o
                // guardarla; y en reposo, cómo se empieza.
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val t = toma
                    if (t != null && !grabando) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = { oir(t) }, modifier = Modifier.weight(1f).height(48.dp)) {
                                Icon(Icons.Filled.Replay, contentDescription = null, Modifier.size(18.dp))
                                Text(getString(R.string.pronunciar_repetir), modifier = Modifier.padding(start = 6.dp))
                            }
                            OutlinedButton(
                                onClick = { guardar() },
                                enabled = !guardada,
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(
                                    if (guardada) Icons.Filled.Check else Icons.Filled.Save,
                                    contentDescription = null, Modifier.size(18.dp)
                                )
                                Text(
                                    getString(if (guardada) R.string.pronunciar_ya_guardada else R.string.pronunciar_guardar),
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                        }
                        Text(
                            getString(R.string.pronunciar_toma, com.forge.pixpin.pin.duracionLegible(tomaMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Text(
                        when {
                            grabando -> getString(R.string.pronunciar_suelta)
                            t != null -> getString(R.string.pronunciar_otra_vez)
                            else -> getString(R.string.pronunciar_manten)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (grabando) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    // El reloj de lo que se lleva hablado: dice que el micrófono está cogiendo.
                    Text(
                        if (grabando) com.forge.pixpin.pin.duracionLegible(hablando) else " ",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Box(
                        Modifier.size(if (grabando) 108.dp else 92.dp)
                            .clip(CircleShape)
                            .background(if (grabando) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            .pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    empezar()
                                    try { tryAwaitRelease() } finally { soltar() }
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = getString(R.string.pronunciar_manten),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(if (grabando) 46.dp else 40.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        if (eligiendo) {
            val delChat = remember { opcionesDelChat(almacen, proyecto) }
            ModalBottomSheet(onDismissRequest = { eligiendo = false }) {
                Column(Modifier.fillMaxWidth().padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
                    Text(getString(R.string.pronunciar_guia), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    val fijas = listOf(
                        R.string.pronunciar_guia_texto to { escribiendo = true },
                        R.string.pronunciar_guia_imagen to { abrirDocumento.launch(arrayOf("image/*")) },
                        R.string.pronunciar_guia_pdf to { abrirDocumento.launch(arrayOf("application/pdf")) },
                        R.string.pronunciar_guia_nota to { abrirDocumento.launch(arrayOf("text/*", "text/markdown")) }
                    )
                    for ((texto, hacer) in fijas) {
                        Text(getString(texto), fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { eligiendo = false; hacer() }.padding(horizontal = 24.dp, vertical = 14.dp))
                    }
                    if (delChat.isNotEmpty()) {
                        Text(getString(R.string.pronunciar_guia_del_chat), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                        for (o in delChat) {
                            Text(
                                o.titulo, fontSize = 16.sp, maxLines = 1,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    eligiendo = false
                                    alcance.launch { guia = withContext(Dispatchers.IO) { runCatching { o.cargar() }.getOrDefault(Guia.Ninguna) }; pagina = 0 }
                                }.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
        if (escribiendo) {
            var borrador by remember { mutableStateOf((guia as? Guia.Texto)?.texto.orEmpty()) }
            AlertDialog(
                onDismissRequest = { escribiendo = false },
                title = { Text(getString(R.string.pronunciar_guia_texto)) },
                text = { OutlinedTextField(value = borrador, onValueChange = { borrador = it }, modifier = Modifier.fillMaxWidth().height(220.dp)) },
                confirmButton = { TextButton(onClick = { guia = Guia.Texto(borrador); escribiendo = false }) { Text(getString(R.string.telepronter_listo)) } },
                dismissButton = { TextButton(onClick = { escribiendo = false }) { Text(getString(R.string.cancel)) } }
            )
        }
    }

    /** Una imagen, o una página de un PDF, a lo ancho. Se dibuja fuera del hilo de la interfaz. */
    @Composable
    private fun Lamina(ruta: String, paginas: Int?, pagina: Int) {
        var mapa by remember(ruta, pagina) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(ruta, pagina) {
            mapa = withContext(Dispatchers.IO) {
                if (paginas == null) imagen(File(ruta)) else PdfMiniaturas.de(this@PronunciarActivity, ruta, pagina, ANCHO_DE_LAMINA)
            }
        }
        val m = mapa
        if (m != null) {
            Image(m.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
        }
    }

    /** La imagen a un tamaño de pantalla, no la foto de doce megapíxeles entera. */
    private fun imagen(f: File): Bitmap? = runCatching {
        val medida = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, medida)
        var escala = 1
        while (medida.outWidth / escala > ANCHO_DE_LAMINA * 2) escala *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = escala })
    }.getOrNull()

    /** Lo que se trajo del teléfono, copiado a la caché, como guía; null si no se pudo leer. */
    private fun guiaDe(uri: Uri): Guia? {
        val nombre = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        val tipo = contentResolver.getType(uri).orEmpty()
        val destino = File(carpeta(), "guia-${System.currentTimeMillis()}-" + nombre.replace(Regex("[\\\\/:*?\"<>|]"), "-"))
        runCatching { contentResolver.openInputStream(uri)?.use { it.copyTo(destino.outputStream()) } }.getOrNull() ?: return null
        return guiaDeArchivo(destino, nombre, tipo)
    }

    private fun guiaDeArchivo(f: File, nombre: String, tipo: String): Guia? = when {
        tipo == "application/pdf" || nombre.endsWith(".pdf", true) -> PdfDoc.pageCount(f.absolutePath).takeIf { it > 0 }?.let { Guia.Pdf(f, it) }
        tipo.startsWith("image/") || nombre.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic") -> Guia.Imagen(f)
        else -> runCatching { Guia.Texto(f.readText()) }.getOrNull()
    }

    /** Lo del chat que sirve de guía: notas, imágenes, PDF y `.md`, lo último primero. */
    private fun opcionesDelChat(almacen: MensajesStore, proyecto: String?): List<Opcion> =
        almacen.leer().filter { it.proyecto == proyecto }.mapNotNull { m ->
            when {
                m.clase == Clase.NOTA && m.texto.isNotBlank() -> Opcion("✎ " + m.texto.lineSequence().first().take(60)) { Guia.Texto(m.texto) }
                m.clase == Clase.IMAGEN && m.ruta != null -> Opcion("🖼 " + m.nombre.ifBlank { fechaCorta(m.cuando) }) { Guia.Imagen(File(m.ruta)) }
                m.clase == Clase.ARCHIVO && m.ruta != null -> Opcion("📄 " + m.nombre) { guiaDeArchivo(File(m.ruta), m.nombre, "") ?: Guia.Ninguna }
                else -> null
            }
        }.asReversed()

    private fun fechaCorta(cuando: Long): String =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(cuando))

    companion object {
        private const val EL_PROYECTO = "pronunciar_proyecto"
        private const val ANCHO_DE_LAMINA = 1400
        /** Vacío: el idioma de Ajustes. Los demás, los que Whisper y Vosk entienden aquí. */
        private val IDIOMAS = listOf("" to "Ajustes", "es" to "Español", "en" to "Inglés", "pt" to "Portugués", "fr" to "Francés", "de" to "Alemán", "it" to "Italiano", "ca" to "Catalán")

        fun abrir(context: Context, proyecto: String?) {
            context.startActivity(
                Intent(context, PronunciarActivity::class.java)
                    .putExtra(EL_PROYECTO, proyecto)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
