package com.forge.pixpin.guardados

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.forge.pixpin.audio.DecisionDeSalida
import com.forge.pixpin.audio.SalidaDeAudio
import com.forge.pixpin.pin.duracionLegible
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **Cantar una canción guardada: el karaoke** (27-oct-2026, lo pidió el usuario: «quiero guardar
 * música, con su letra adjunta, para poder cantar cuando quiera»).
 *
 * Portada, título y artista arriba; la letra grande en medio y, si lleva tiempos, **la línea que
 * suena resaltada y siempre a la vista**, con las cantadas apagadas y las que vienen a medio tono,
 * como las aplicaciones de karaoke; tocar una línea salta ahí. Sin tiempos, la letra entera para
 * leerla mientras suena. Abajo, play/pausa, ±10 s y la barra para ir a cualquier punto, y el botón
 * de por dónde suena (altavoz o auricular de llamadas, [com.forge.pixpin.audio.BotonDeSalida]).
 *
 * - **El lápiz** escribe o pega la letra: texto llano o LRC, del portapapeles o de un `.lrc`/`.txt`
 *   ([EditorDeLetra]).
 * - **El reloj** («Sincronizar la letra»): la canción suena desde el principio y un toque al empezar
 *   cada línea le pone su tiempo ([SincronizadorDeLetra]). Lo que sale es una letra con tiempos.
 *
 * Suena con el reproductor de toda la app ([Reproductor]): la barra de arriba del chat sigue
 * mandando al salir. La letra se guarda donde ya la guardaban el teléfono y el PC (ver
 * [LetraDeCancion] y `docs/musica-con-letra.md`), así que lo hecho aquí se ve igual en Windows.
 */
class MusicaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EL_MENSAJE).orEmpty()
        aPantallaCompleta()
        setContent { PixPinTheme { Pantalla(id) } }
    }

    @Composable
    private fun Pantalla(id: String) {
        val almacen = remember { MensajesStore(this) }
        var version by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { MensajesStore.cambios.collect { version++ } }
        val m = remember(version) { almacen.leer().firstOrNull { it.id == id } }
        val ruta = m?.ruta
        val datos by produceState<DatosDeCancion.Datos?>(null, ruta) {
            value = ruta?.let { withContext(Dispatchers.IO) { DatosDeCancion.de(it, 512) } }
        }
        val titulo = m?.let { DatosDeCancion.titulo(this, it, datos) } ?: ""
        val estado by Reproductor.estado.collectAsState()
        val esteAudio = ruta != null && estado.ruta == ruta
        val letra = remember(m?.transcripcion) { LetraDeCancion.leer(m?.transcripcion.orEmpty()) }
        var tamano by remember { mutableIntStateOf(24) }
        var editando by remember { mutableStateOf(false) }
        var sincronizando by remember { mutableStateOf<SincronizadorDeLetra?>(null) }
        // El sincronizador no es de Compose: esto lo vuelve a pintar tras cada toque.
        var toques by remember { mutableIntStateOf(0) }

        // Con el audio cargado aunque no suene: los mandos a mano desde el primer momento.
        LaunchedEffect(ruta) { if (ruta != null && Reproductor.estado.value.ruta != ruta) Reproductor.cargar(ruta, titulo, arrancar = false) }
        // **Los botones del volumen, los del sitio por donde suena**: por el auricular el volumen
        // es el de llamada, y subir el de medios no haría nada.
        val salida by SalidaDeAudio.ruta.collectAsState()
        LaunchedEffect(salida) {
            volumeControlStream = if (salida == DecisionDeSalida.Ruta.OIDO) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                    Text("Música", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = { if (tamano > 14) tamano -= 2 }) { Icon(Icons.Filled.TextDecrease, contentDescription = "Letra más pequeña") }
                    IconButton(onClick = { if (tamano < 44) tamano += 2 }) { Icon(Icons.Filled.TextIncrease, contentDescription = "Letra más grande") }
                    com.forge.pixpin.audio.BotonDeSalida(tamano = 48.dp)
                    IconButton(enabled = sincronizando == null, onClick = { editando = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Escribir o pegar la letra")
                    }
                    IconButton(
                        enabled = m != null && ruta != null && sincronizando == null,
                        onClick = {
                            val s = SincronizadorDeLetra.de(letra)
                            if (s.textos.isEmpty()) {
                                Toast.makeText(this@MusicaActivity, "Primero escribe o pega la letra", Toast.LENGTH_SHORT).show()
                                editando = true
                            } else if (ruta != null) {
                                // Desde el principio y sonando: se marca a la vez que se canta.
                                if (Reproductor.estado.value.ruta != ruta) Reproductor.cargar(ruta, titulo, arrancar = false)
                                Reproductor.irAMs(0)
                                if (!Reproductor.estado.value.sonando) Reproductor.seguir()
                                sincronizando = s
                                Toast.makeText(this@MusicaActivity, "Toca «Marcar» justo cuando empiece cada línea", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) { Icon(Icons.Filled.Timer, contentDescription = "Sincronizar la letra") }
                    if (letra.conTiempos) IconButton(onClick = {
                        copiar(LetraDeCancion.aLrc(letra, datos?.titulo ?: titulo, datos?.artista))
                    }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar como LRC") }
                }
                Cabecera(titulo, datos)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val s = sincronizando
                    when {
                        m == null -> Text("No se encuentra la canción", Modifier.align(Alignment.Center))
                        s != null -> ListaParaSincronizar(s, toques, tamano)
                        letra.lineas.isEmpty() -> Column(
                            Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Esta canción aún no tiene letra", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { editando = true }) { Text("Escribir o pegar la letra") }
                        }
                        else -> Karaoke(letra, if (esteAudio) estado.posicionMs else -1, tamano) { ms ->
                            if (ruta != null) {
                                if (Reproductor.estado.value.ruta != ruta) Reproductor.cargar(ruta, titulo, arrancar = false)
                                Reproductor.irAMs(ms)
                                if (!Reproductor.estado.value.sonando) Reproductor.seguir()
                            }
                        }
                    }
                }
                Mandos(ruta, titulo)
                sincronizando?.let { s ->
                    PanelDeSincronizar(
                        s, toques,
                        alMarcar = {
                            s.marcar(Reproductor.posicionAhora())
                            toques++
                        },
                        alDeshacer = {
                            s.deshacer()?.let { Reproductor.irAMs(it) }
                            toques++
                        },
                        alGuardar = {
                            guardar(id, LetraDeCancion.aGuardar(s.letra()))
                            sincronizando = null
                            Toast.makeText(this@MusicaActivity, "Letra sincronizada", Toast.LENGTH_SHORT).show()
                        },
                        alCancelar = { sincronizando = null }
                    )
                }
            }
        }
        if (editando) {
            Dialog(onDismissRequest = { editando = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
                        Text("La letra de «$titulo»", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
                        var texto by remember { mutableStateOf(m?.transcripcion.orEmpty()) }
                        EditorDeLetra(texto, { texto = it })
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { editando = false }) { Text("Cancelar") }
                            Button(onClick = {
                                guardar(id, LetraDeCancion.aGuardar(LetraDeCancion.leer(texto)))
                                editando = false
                            }) { Text("Guardar") }
                        }
                    }
                }
            }
        }
    }

    /** La letra al mensaje: queda como música aunque no lo fuera (el lápiz de una nota de voz cualquiera). */
    private fun guardar(id: String, texto: String) {
        val almacen = MensajesStore(this)
        Thread {
            almacen.actualizar(id) { it.copy(transcripcion = texto.ifBlank { null }, estadoDelTexto = TEXTO_LETRA) }
        }.start()
    }

    private fun copiar(texto: String) {
        runCatching {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LRC", texto))
            Toast.makeText(this, "Letra copiada en LRC", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun Cabecera(titulo: String, datos: DatosDeCancion.Datos?) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                val portada = datos?.portada
                if (portada != null) Image(portada.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(titulo, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2)
                val debajo = listOfNotNull(datos?.artista, datos?.album).joinToString(" · ")
                if (debajo.isNotEmpty()) Text(debajo, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }

    /**
     * **La letra que se canta.** Con tiempos: la línea que suena grande y en color, siempre a un
     * tercio de la pantalla; las cantadas apagadas. Sin tiempos: la letra tal cual, para leerla.
     * [posicionMs] -1: esta canción no está cargada.
     */
    @Composable
    private fun Karaoke(letra: LetraDeCancion.Letra, posicionMs: Int, tamano: Int, alTocar: (Int) -> Unit) {
        val lista = rememberLazyListState()
        val actual = if (letra.conTiempos && posicionMs >= 0) LetraDeCancion.lineaActual(letra.lineas, posicionMs) else -1
        LaunchedEffect(actual) {
            if (actual >= 0) runCatching {
                val alto = lista.layoutInfo.viewportSize.height
                lista.animateScrollToItem(actual, -alto / 3)
            }
        }
        val tinta = MaterialTheme.colorScheme.onSurface
        LazyColumn(state = lista, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 120.dp)) {
            itemsIndexed(letra.lineas) { i, l ->
                val activa = i == actual
                val color = when {
                    !letra.conTiempos || actual < 0 -> tinta
                    activa -> MaterialTheme.colorScheme.primary
                    i < actual -> tinta.copy(alpha = 0.40f)
                    else -> tinta.copy(alpha = 0.75f)
                }
                val texto = l.texto.ifEmpty { if (l.ms != null) "♪" else "" }
                Text(
                    texto,
                    fontSize = (if (activa) tamano * 1.15f else tamano.toFloat()).sp,
                    lineHeight = (tamano * 1.5f).sp,
                    fontWeight = if (activa) FontWeight.Bold else FontWeight.Normal,
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (l.ms != null) Modifier.clickable { alTocar(l.ms) } else Modifier)
                        .padding(horizontal = 20.dp, vertical = if (letra.conTiempos) 8.dp else 2.dp)
                )
            }
        }
    }

    /** Las líneas mientras se sincroniza: la que toca marcar, resaltada; las marcadas, con su minuto. */
    @Composable
    private fun ListaParaSincronizar(s: SincronizadorDeLetra, toques: Int, tamano: Int) {
        val lista = rememberLazyListState()
        LaunchedEffect(toques) {
            runCatching { lista.animateScrollToItem(s.siguiente.coerceAtMost((s.textos.size - 1).coerceAtLeast(0)), -lista.layoutInfo.viewportSize.height / 3) }
        }
        LazyColumn(state = lista, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(s.textos) { i, t ->
                val toca = i == s.siguiente
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (toca) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        s.tiempo(i)?.let { LetraDeCancion.marca(it) } ?: "",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp).size(width = 44.dp, height = 18.dp)
                    )
                    Text(
                        t, fontSize = (tamano * 0.8f).sp,
                        fontWeight = if (toca) FontWeight.Bold else FontWeight.Normal,
                        color = if (s.tiempo(i) != null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    @Composable
    private fun PanelDeSincronizar(
        s: SincronizadorDeLetra, toques: Int,
        alMarcar: () -> Unit, alDeshacer: () -> Unit, alGuardar: () -> Unit, alCancelar: () -> Unit
    ) {
        // [toques] solo está para que esto se vuelva a pintar con cada toque.
        val siguiente = if (toques >= 0) s.textos.getOrNull(s.siguiente) else null
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (siguiente != null) "Siguiente: $siguiente" else "Todas marcadas. Guarda la letra.",
                    fontSize = 14.sp, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(enabled = s.siguiente > 0, onClick = alDeshacer) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Deshacer la última marca")
                    }
                    Button(enabled = !s.terminado, onClick = alMarcar, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("Marcar  ${s.siguiente + (if (s.terminado) 0 else 1)}/${s.textos.size}", fontSize = 18.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = alCancelar) { Text("Cancelar") }
                    OutlinedButton(enabled = s.siguiente > 0, onClick = alGuardar) { Text("Guardar") }
                }
            }
        }
    }

    /** Play/pausa, ±10 s y la barra, del [Reproductor] de toda la app. */
    @Composable
    private fun Mandos(ruta: String?, titulo: String) {
        val estado by Reproductor.estado.collectAsState()
        val esteAudio = ruta != null && estado.ruta == ruta
        var arrastrando by remember { mutableStateOf<Float?>(null) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Slider(
                value = arrastrando ?: if (esteAudio) Reproductor.fraccion() else 0f,
                onValueChange = { arrastrando = it },
                onValueChangeFinished = {
                    val f = arrastrando
                    arrastrando = null
                    if (f != null && ruta != null) {
                        if (Reproductor.estado.value.ruta != ruta) Reproductor.cargar(ruta, titulo, arrancar = false)
                        Reproductor.irA(f)
                    }
                }
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(duracionLegible(if (esteAudio) estado.posicionMs else 0), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(enabled = esteAudio, onClick = { Reproductor.saltar(-Reproductor.SALTO_MS) }) { Icon(Icons.Filled.Replay10, "Atrás 10 s") }
                FilledIconButton(
                    enabled = ruta != null,
                    onClick = { ruta?.let { Reproductor.alternar(it, titulo) } },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        if (esteAudio && estado.sonando) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (esteAudio && estado.sonando) "Pausa" else "Escuchar",
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(enabled = esteAudio, onClick = { Reproductor.saltar(Reproductor.SALTO_MS) }) { Icon(Icons.Filled.Forward10, "Adelante 10 s") }
                Spacer(Modifier.weight(1f))
                Text(duracionLegible(if (esteAudio) estado.duracionMs else 0), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    companion object {
        private const val EL_MENSAJE = "musica_mensaje"

        fun abrir(context: Context, mensaje: String) {
            context.startActivity(
                Intent(context, MusicaActivity::class.java)
                    .putExtra(EL_MENSAJE, mensaje)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * **Escribir o pegar la letra** (27-oct-2026): la caja de texto y, encima, «Pegar del
 * portapapeles» y «Abrir .lrc o .txt». Vale texto llano o LRC; al guardar se pasa al formato del
 * chat con [LetraDeCancion.aGuardar]. Lo usan el karaoke ([MusicaActivity]) y la canción nueva
 * ([CancionNuevaActivity]).
 */
@Composable
fun ColumnScope.EditorDeLetra(texto: String, alCambiar: (String) -> Unit) {
    val contexto = LocalContext.current
    val abrir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val leido = runCatching { leerTexto(contexto, uri) }.getOrNull()
        if (leido.isNullOrBlank()) Toast.makeText(contexto, "No se pudo leer ese archivo", Toast.LENGTH_SHORT).show()
        else alCambiar(leido)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = {
                val copiado = portapapeles(contexto)
                if (copiado.isNullOrBlank()) Toast.makeText(contexto, "No hay texto copiado", Toast.LENGTH_SHORT).show()
                else alCambiar(copiado)
            },
            label = { Text("Pegar del portapapeles") },
            leadingIcon = { Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)) }
        )
        AssistChip(
            onClick = { runCatching { abrir.launch(arrayOf("text/*", "application/octet-stream", "application/x-subrip")) } },
            label = { Text("Abrir .lrc o .txt") },
            leadingIcon = { Icon(Icons.Filled.FileOpen, null, Modifier.size(18.dp)) }
        )
    }
    OutlinedTextField(
        value = texto,
        onValueChange = alCambiar,
        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
        placeholder = { Text("Pega aquí la letra.\n\nVale texto normal o LRC:\n[00:12.30] Primera línea\n[00:15.80] Segunda línea") }
    )
    val leida = remember(texto) { LetraDeCancion.leer(texto) }
    Text(
        if (leida.conTiempos) "Con tiempos: se resaltará la línea que suena."
        else "Sin tiempos. Para cantarla al ritmo, guarda y toca el reloj («Sincronizar»).",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
    )
}

/** El texto del portapapeles, o null. */
fun portapapeles(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

/** Un `.lrc` o `.txt` elegido, en UTF-8 y sin la marca de orden de bytes. */
private fun leerTexto(context: Context, uri: Uri): String? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > 2_000_000) return null
    return String(bytes, Charsets.UTF_8).removePrefix("﻿")
}
