package com.forge.pixpin.comunidad

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.cuenta.Cuenta
import com.forge.pixpin.cuenta.CuentaActivity
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.motor.BibliotecaStore
import com.forge.pixpin.motor.ExcalidrawJson
import com.forge.pixpin.motor.FiguraGuardada
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * **La galería de la comunidad** ([Comunidad]): explorar lo que sube la gente (por tipo, buscando, por populares o
 * nuevas), darle «me gusta», descargarlo y usarlo al momento (una miniapp se abre en su visor; una figura entra en
 * la biblioteca del lienzo), y **contribuir**: un archivo del chat (lo que hizo el asistente), una figura propia o
 * cualquier archivo del teléfono, con su título, su descripción y su portada. En «Mis aportes», el estado de cada uno
 * (en revisión, publicado, rechazado y por qué) y subir una versión nueva.
 */
class ComunidadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { com.forge.pixpin.ui.theme.PixPinTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Pantalla() } } }
    }

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    /** Lo que se va a publicar, ya elegido. */
    private data class Borrador(val nombre: String, val bytes: ByteArray, val tipo: Comunidad.Tipo, val vista: ByteArray?, val titulo: String, val reemplaza: Comunidad.Item? = null)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Pantalla() {
        val alcance = rememberCoroutineScope()
        val cuenta by Cuenta.datos.collectAsState()
        var pestana by remember { mutableStateOf(0) }
        var tipo by remember { mutableStateOf<Comunidad.Tipo?>(null) }
        var orden by remember { mutableStateOf(Comunidad.Orden.POPULARES) }
        var busqueda by remember { mutableStateOf("") }
        val items = remember { mutableStateListOf<Comunidad.Item>() }
        var total by remember { mutableStateOf(0) }
        var cargando by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var abierto by remember { mutableStateOf<Comunidad.Item?>(null) }
        var contribuir by remember { mutableStateOf(false) }
        var borrador by remember { mutableStateOf<Borrador?>(null) }
        var recarga by remember { mutableStateOf(0) }

        fun cargar(mas: Boolean = false) {
            if (!Comunidad.hay) return
            cargando = true; error = null
            alcance.launch {
                runCatching {
                    if (pestana == 1) { val m = Comunidad.mias(this@ComunidadActivity); items.clear(); items.addAll(m); total = m.size }
                    else {
                        val (t, l) = Comunidad.lista(this@ComunidadActivity, tipo, busqueda, orden, if (mas) items.size else 0)
                        if (!mas) items.clear(); items.addAll(l); total = t
                    }
                }.onFailure { error = it.message ?: "Sin conexión" }
                cargando = false
            }
        }
        LaunchedEffect(pestana, tipo, orden, recarga, cuenta) { cargar() }

        // Elegir de dónde sale lo que se publica.
        val delTelefono = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) alcance.launch {
                val b = withContext(Dispatchers.IO) { leerUri(uri) }
                if (b == null) aviso("No se pudo leer ese archivo") else borrador = borradorDe(b.first, b.second)
            }
        }

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                Column(Modifier.weight(1f)) {
                    Text("Comunidad", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Lo que crea la gente con PinDesk", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!Comunidad.hay) {
                Mensajito("La galería necesita el servidor de PinDesk, y esta versión aún no lo tiene.")
                return@Column
            }
            TabRow(selectedTabIndex = pestana) {
                Tab(pestana == 0, { pestana = 0 }, text = { Text("Explorar") })
                Tab(pestana == 1, { pestana = 1 }, text = { Text("Mis aportes") })
            }
            if (pestana == 0) {
                OutlinedTextField(
                    busqueda, { busqueda = it }, singleLine = true, placeholder = { Text("Buscar miniapps, figuras, plantillas…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { cargar() }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(tipo == null, { tipo = null }, label = { Text("Todo") })
                    Comunidad.Tipo.entries.forEach { t -> FilterChip(tipo == t, { tipo = if (tipo == t) null else t }, label = { Text("${t.emoji} ${t.nombre}") }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Comunidad.Orden.entries.forEach { o -> FilterChip(orden == o, { orden = o }, label = { Text(o.nombre, fontSize = 12.sp) }) }
                }
            } else if (cuenta == null) {
                Mensajito("Entra con tu cuenta de Google para ver y publicar tus aportes.")
                Button(onClick = { CuentaActivity.abrir(this@ComunidadActivity) }, modifier = Modifier.padding(16.dp).fillMaxWidth()) { Text("Entrar") }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    error != null && items.isEmpty() -> Mensajito(error!!)
                    !cargando && items.isEmpty() && (pestana == 0 || cuenta != null) ->
                        Mensajito(if (pestana == 1) "Aún no has subido nada. Toca «Contribuir» y comparte lo que hiciste." else "Nada por aquí todavía. ¡Sé el primero en contribuir!")
                    else -> LazyVerticalGrid(
                        GridCells.Adaptive(160.dp), Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(items, key = { it.id }) { it -> Tarjeta(it) { abierto = it } }
                        if (pestana == 0 && items.size < total) item { TextButton(onClick = { cargar(mas = true) }) { Text("Ver más") } }
                    }
                }
                if (cargando) CircularProgressIndicator(Modifier.align(Alignment.Center))
                ExtendedFloatingActionButton(
                    onClick = { if (cuenta == null) CuentaActivity.abrir(this@ComunidadActivity) else contribuir = true },
                    icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Contribuir") },
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)
                )
            }
        }

        abierto?.let { it ->
            ModalBottomSheet(onDismissRequest = { abierto = null }) {
                Detalle(it,
                    alCambiar = { n -> abierto = n; val i = items.indexOfFirst { x -> x.id == n.id }; if (i >= 0) items[i] = n },
                    alBorrar = { abierto = null; recarga++ },
                    nuevaVersion = { abierto = null; contribuir = true; borrador = null; versionDe = it })
            }
        }
        if (contribuir) {
            ModalBottomSheet(onDismissRequest = { contribuir = false; versionDe = null }) {
                Origenes(
                    deFigura = { f -> contribuir = false; borrador = borradorDeFigura(f) },
                    deChat = { m -> contribuir = false; alcance.launch {
                        val b = withContext(Dispatchers.IO) { runCatching { File(m.ruta!!).readBytes() }.getOrNull() }
                        if (b == null) aviso("No se pudo leer «${m.nombre}»") else borrador = borradorDe(m.nombre, b)
                    } },
                    delTelefono = { contribuir = false; delTelefono.launch(arrayOf("*/*")) },
                )
            }
        }
        borrador?.let { b ->
            val conVersion = versionDe
            ModalBottomSheet(onDismissRequest = { borrador = null; versionDe = null }) {
                Publicar(b.copy(reemplaza = conVersion, titulo = conVersion?.titulo ?: b.titulo)) { hecho ->
                    borrador = null; versionDe = null
                    if (hecho != null) {
                        aviso(if (hecho.estado == "pendiente") "¡Gracias! Se publicará cuando se revise." else "¡Publicado!")
                        pestana = 1; recarga++
                    }
                }
            }
        }
    }

    /** Para «Nueva versión»: el aporte que se reemplaza. */
    private var versionDe by mutableStateOf<Comunidad.Item?>(null)

    @Composable
    private fun Mensajito(t: String) {
        Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().padding(28.dp))
    }

    // ------------------------------------------------------------------------------------ las tarjetas

    private val vistas = HashMap<String, Bitmap?>()

    @Composable
    private fun Portada(it: Comunidad.Item, modifier: Modifier) {
        val bmp by produceState<Bitmap?>(vistas[it.id], it.id, it.version) {
            if (it.conVista && !vistas.containsKey("${it.id}:${it.version}")) {
                value = Comunidad.vista(it.id)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) }
                vistas["${it.id}:${it.version}"] = value; vistas[it.id] = value
            } else value = vistas["${it.id}:${it.version}"] ?: vistas[it.id]
        }
        Box(modifier.background(colorDe(it.tipo).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(it.tipo.emoji, fontSize = 40.sp)
        }
    }

    private fun colorDe(t: Comunidad.Tipo) = when (t) {
        Comunidad.Tipo.MINIAPP -> Color(0xFF6E56CF); Comunidad.Tipo.FIGURA -> Color(0xFF3E63DD); Comunidad.Tipo.PLANTILLA -> Color(0xFF12A594)
        Comunidad.Tipo.LIENZO -> Color(0xFF8E4EC6); Comunidad.Tipo.PLANO -> Color(0xFF14A3B8); Comunidad.Tipo.DOCUMENTO -> Color(0xFFE5484D)
        Comunidad.Tipo.OTRO -> Color(0xFF8B8D98)
    }

    @Composable
    private fun Tarjeta(it: Comunidad.Item, alTocar: () -> Unit) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.clickable(onClick = alTocar)) {
            Column {
                Portada(it, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)))
                Column(Modifier.padding(10.dp)) {
                    Text(it.titulo, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${it.tipo.emoji} ${it.autor}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(if (it.meGusta) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, Modifier.size(14.dp), tint = Color(0xFFE5484D))
                        Text(" ${it.gustos}   ", fontSize = 12.sp)
                        Icon(Icons.Filled.Download, null, Modifier.size(14.dp))
                        Text(" ${it.descargas}", fontSize = 12.sp)
                    }
                    if (it.mia && it.estado != null && it.estado != "publicada") Text(
                        when (it.estado) { "pendiente" -> "⏳ En revisión"; "rechazada" -> "✖️ Rechazada" + (it.motivo?.let { m -> ": $m" } ?: ""); else -> "🙈 Oculta" },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------ el detalle

    @Composable
    private fun Detalle(it: Comunidad.Item, alCambiar: (Comunidad.Item) -> Unit, alBorrar: () -> Unit, nuevaVersion: () -> Unit) {
        val alcance = rememberCoroutineScope()
        var trabajando by remember { mutableStateOf(false) }
        var denunciando by remember { mutableStateOf(false) }
        val fecha = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Portada(it, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)))
            Spacer(Modifier.height(12.dp))
            Text(it.titulo, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${it.tipo.emoji} ${it.tipo.nombre.dropLast(1)} · de ${it.autor} · v${it.version} · ${fecha.format(java.util.Date(it.actualizada))}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (it.descripcion.isNotBlank()) Text(it.descripcion, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            if (it.etiquetas.isNotEmpty()) Text(it.etiquetas.joinToString("  ") { e -> "#$e" }, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            Text("${it.nombreArchivo} · ${(it.bytes / 1024).coerceAtLeast(1)} KB · ♥ ${it.gustos} · ↓ ${it.descargas}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (it.tipo == Comunidad.Tipo.MINIAPP) Text("Es código de otra persona: se abre en el visor de PinDesk, sin acceso a tus archivos.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { trabajando = true; alcance.launch { usar(it); trabajando = false } }, enabled = !trabajando,
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)
            ) { if (trabajando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Descargar y usar", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = {
                    if (Cuenta.datos.value == null) { CuentaActivity.abrir(this@ComunidadActivity); return@OutlinedButton }
                    alcance.launch { runCatching { alCambiar(Comunidad.gusta(this@ComunidadActivity, it.id, !it.meGusta)) }.onFailure { e -> aviso(e.message ?: "No se pudo") } }
                }, modifier = Modifier.weight(1f)) {
                    Icon(if (it.meGusta) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, tint = Color(0xFFE5484D))
                    Text(if (it.meGusta) "  Te gusta" else "  Me gusta")
                }
                if (!it.mia) OutlinedButton(onClick = { denunciando = true }) { Text("Denunciar") }
            }
            if (it.mia) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = nuevaVersion, modifier = Modifier.weight(1f)) { Text("Subir versión nueva") }
                OutlinedButton(onClick = {
                    alcance.launch { runCatching { Comunidad.borrar(this@ComunidadActivity, it.id) }.onSuccess { aviso("Borrado de la galería"); alBorrar() }.onFailure { e -> aviso(e.message ?: "No se pudo") } }
                }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(20.dp))
        }
        if (denunciando) {
            var motivo by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { denunciando = false },
                title = { Text("Denunciar") },
                text = { OutlinedTextField(motivo, { motivo = it.take(300) }, placeholder = { Text("¿Qué pasa? (spam, ofensivo, no funciona, copia…)") }) },
                confirmButton = { TextButton(onClick = {
                    denunciando = false
                    if (Cuenta.datos.value == null) { CuentaActivity.abrir(this@ComunidadActivity); return@TextButton }
                    alcance.launch { runCatching { Comunidad.denunciar(this@ComunidadActivity, it.id, motivo) }.onSuccess { aviso("Gracias: lo revisaremos") }.onFailure { e -> aviso(e.message ?: "No se pudo") } }
                }) { Text("Enviar") } },
                dismissButton = { TextButton(onClick = { denunciando = false }) { Text("Cancelar") } }
            )
        }
    }

    /**
     * **Usarlo**: una figura entra en la biblioteca del lienzo; lo demás, al chat «Guardados» como un archivo más
     * (y se abre en su visor: la miniapp en el web, el plano en el de planos…).
     */
    private suspend fun usar(it: Comunidad.Item) {
        val almacen = MensajesStore(this)
        val ext = it.nombreArchivo.substringAfterLast('.', "bin").lowercase().take(12)
        val destino = File(almacen.carpetaDeAdjuntos(), "comunidad-${System.currentTimeMillis()}.$ext")
        val r = runCatching { Comunidad.bajar(this, it, destino) }
        r.exceptionOrNull()?.let { e -> aviso(e.message ?: "No se pudo bajar"); return }
        if (it.nombreArchivo.endsWith(Comunidad.FIGURA)) {
            val f = withContext(Dispatchers.IO) { runCatching { ExcalidrawJson.decodeFromString(FiguraGuardada.serializer(), destino.readText()) }.getOrNull() }
            destino.delete()
            if (f == null) { aviso("Esa figura no se pudo leer"); return }
            withContext(Dispatchers.IO) { BibliotecaStore.anadir(this@ComunidadActivity, f.copy(id = UUID.randomUUID().toString(), propia = true, nombre = it.titulo)) }
            aviso("«${it.titulo}» ya está en tus figuras del lienzo")
            return
        }
        val id = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            almacen.anadir(Mensaje(id = id, cuando = System.currentTimeMillis(), clase = Clase.ARCHIVO, ruta = destino.path, nombre = it.nombreArchivo,
                bytes = destino.length(), texto = "🌐 De la comunidad: ${it.titulo} (de ${it.autor})", proyecto = null))
        }
        com.forge.pixpin.guardados.MensajesActivity.abrirYAbrir(this, id)
    }

    // ------------------------------------------------------------------------------------ contribuir

    @Composable
    private fun Origenes(deFigura: (FiguraGuardada) -> Unit, deChat: (Mensaje) -> Unit, delTelefono: () -> Unit) {
        val archivos by produceState(emptyList<Mensaje>()) {
            value = withContext(Dispatchers.IO) {
                MensajesStore(this@ComunidadActivity).leer().filter { it.clase == Clase.ARCHIVO && it.ruta != null && !it.id.startsWith("auto-") && File(it.ruta).isFile && File(it.ruta).length() <= 3_000_000 }
                    .sortedWith(compareByDescending<Mensaje> { it.aparato == com.forge.pixpin.asistente.ChatDelAsistente.APARATO }.thenByDescending { it.cuando }).take(40)
            }
        }
        val figuras by produceState(emptyList<FiguraGuardada>()) { value = withContext(Dispatchers.IO) { BibliotecaStore.cargar(this@ComunidadActivity).filter { it.propia }.take(40) } }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            item {
                Text("¿Qué quieres compartir?", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                Button(onClick = delTelefono, modifier = Modifier.fillMaxWidth()) { Text("Un archivo del teléfono") }
            }
            if (archivos.isNotEmpty()) item { Text("De tus chats", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            items(archivos, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth().clickable { deChat(m) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Comunidad.tipoDe(m.nombre).emoji, fontSize = 22.sp, modifier = Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.nombre, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (m.aparato == com.forge.pixpin.asistente.ChatDelAsistente.APARATO) "Hecho con el asistente · " else "") + "${(m.bytes / 1024).coerceAtLeast(1)} KB",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (figuras.isNotEmpty()) item { Text("Tus figuras del lienzo", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            items(figuras, key = { "f" + it.id }) { f ->
                Row(Modifier.fillMaxWidth().clickable { deFigura(f) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔷", fontSize = 22.sp, modifier = Modifier.width(36.dp))
                    Text(f.nombre.ifBlank { "Figura" }, maxLines = 1)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    @Composable
    private fun Publicar(b: Borrador, alAcabar: (Comunidad.Item?) -> Unit) {
        val alcance = rememberCoroutineScope()
        var titulo by remember { mutableStateOf(b.titulo) }
        var descripcion by remember { mutableStateOf("") }
        var etiquetas by remember { mutableStateOf("") }
        var tipo by remember { mutableStateOf(b.tipo) }
        var vista by remember { mutableStateOf(b.vista) }
        var mio by remember { mutableStateOf(false) }
        var enviando by remember { mutableStateOf(false) }
        val portada = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) alcance.launch { vista = withContext(Dispatchers.IO) { leerUri(uri)?.second?.let { reducir(it) } } }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(if (b.reemplaza != null) "Versión nueva de «${b.reemplaza.titulo}»" else "Publicar en la comunidad", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${b.nombre} · ${(b.bytes.size / 1024).coerceAtLeast(1)} KB", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(colorDe(tipo).copy(alpha = 0.16f)).clickable { portada.launch("image/*") }, contentAlignment = Alignment.Center) {
                val bmp = remember(vista) { vista?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(tipo.emoji, fontSize = 36.sp); Text("Toca para poner una portada", fontSize = 13.sp) }
            }
            OutlinedTextField(titulo, { titulo = it.take(80) }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            OutlinedTextField(descripcion, { descripcion = it.take(1000) }, label = { Text("Qué es y cómo se usa") }, minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            OutlinedTextField(etiquetas, { etiquetas = it.take(200) }, label = { Text("Etiquetas (separadas por comas)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Comunidad.Tipo.entries.forEach { t -> FilterChip(tipo == t, { tipo = t }, label = { Text("${t.emoji} ${t.nombre}") }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).clickable { mio = !mio }) {
                Checkbox(mio, { mio = it })
                Text("Lo hice yo (o puedo compartirlo) y no lleva datos privados", fontSize = 14.sp)
            }
            Button(
                onClick = {
                    enviando = true
                    alcance.launch {
                        val r = runCatching {
                            Comunidad.publicar(this@ComunidadActivity, tipo, titulo, descripcion, etiquetas.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                b.nombre, b.bytes, vista, b.reemplaza?.id)
                        }
                        enviando = false
                        r.onSuccess { alAcabar(it) }.onFailure { aviso(it.message ?: "No se pudo publicar") }
                    }
                },
                enabled = !enviando && mio && titulo.trim().length >= 3,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp), shape = RoundedCornerShape(14.dp)
            ) { if (enviando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Publicar", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Text("Lo revisamos antes de que lo vean los demás. Luego puedes subir versiones nuevas desde «Mis aportes».", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
        }
    }

    // ------------------------------------------------------------------------------------ ayudantes

    private fun leerUri(uri: Uri): Pair<String, ByteArray>? = runCatching {
        val nombre = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "archivo"
        val b = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        nombre to b
    }.getOrNull()

    private fun titulo(nombre: String) = nombre.substringBeforeLast('.').replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    private fun borradorDe(nombre: String, b: ByteArray): Borrador? {
        if (b.size > 3_000_000) { aviso("Es demasiado grande (3 MB como mucho)"); return null }
        val tipo = Comunidad.tipoDe(nombre)
        // Una imagen es su propia portada.
        val vista = if (nombre.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp")) reducir(b) else null
        return Borrador(nombre, b, tipo, vista, titulo(nombre))
    }

    private fun borradorDeFigura(f: FiguraGuardada): Borrador {
        val json = ExcalidrawJson.encodeToString(FiguraGuardada.serializer(), f).toByteArray()
        val vista = runCatching {
            com.forge.pixpin.motor.DrawExport.aBitmap(com.forge.pixpin.motor.Scene(elements = f.elementos), 2.0)?.let { bmp -> png(bmp) }
        }.getOrNull()
        val nombre = (f.nombre.ifBlank { "figura" }.replace(Regex("""[^\p{L}\p{N} _-]"""), "_").take(60)) + Comunidad.FIGURA
        return Borrador(nombre, json, Comunidad.Tipo.FIGURA, vista, f.nombre.ifBlank { "Figura" })
    }

    /** Una portada de 800 px como mucho, en JPEG (o PNG si tiene transparencia), por debajo de 400 KB. */
    private fun reducir(b: ByteArray): ByteArray? = runCatching {
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return null
        val e = 800f / maxOf(bmp.width, bmp.height)
        val chica = if (e < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * e).toInt().coerceAtLeast(1), (bmp.height * e).toInt().coerceAtLeast(1), true) else bmp
        val o = ByteArrayOutputStream()
        if (chica.hasAlpha()) chica.compress(Bitmap.CompressFormat.PNG, 100, o) else chica.compress(Bitmap.CompressFormat.JPEG, 82, o)
        o.toByteArray().takeIf { it.size <= 400_000 }
    }.getOrNull()

    private fun png(bmp: Bitmap): ByteArray? {
        val o = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
        return o.toByteArray().let { if (it.size > 400_000) reducir(it) else it }
    }

    companion object {
        fun abrir(c: Context) = c.startActivity(Intent(c, ComunidadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
