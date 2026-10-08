package com.forge.pixpin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.capture.BarrenderoDeCapturas
import com.forge.pixpin.capture.BarrenderoDeCapturas.Captura
import com.forge.pixpin.capture.CaducidadDeCapturas
import com.forge.pixpin.capture.GaleriaLogica
import com.forge.pixpin.capture.GaleriaLogica.Filtro
import com.forge.pixpin.capture.GaleriaLogica.Tono
import com.forge.pixpin.ui.theme.BotonRedondo
import com.forge.pixpin.ui.theme.Cristal
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **La galería de capturas** (como la del PC, 3-oct-2026): todas las capturas de PixPin, la más
 * nueva primero. En el móvil viven en `Pictures/PixPin` (la galería del sistema también las ve).
 *
 * **La v2** (como `galeria_capturas.rs` del PC, 4-oct): filtros con su número (Todas, Hoy, Esta
 * semana, Conservadas, Se borran pronto, y GIF/Vídeos si hay alguno) y lo que ocupan; buscador por
 * nombre y fecha; agrupadas por día con «Elegir todo el día»; la caducidad por colores (gris,
 * naranja a 2-3 días, rojo hoy o mañana, verde conservada); un modo de elegir varias (pulsación
 * larga) con Conservar, Pinear, Copiar y Borrar —con «Deshacer» en vez de preguntar—; y la hoja de
 * detalle con «Conservar» y «Dar 7 días más».
 *
 * **Sin texto de dentro**: el PC busca también lo que pone en la captura con el OCR de Windows;
 * PixPin para Android no lleva ningún OCR, así que aquí se busca por nombre y fecha y no sale el
 * filtro «Con texto». Las reglas viven en [GaleriaLogica] y [CaducidadDeCapturas], sin Android.
 */
class GaleriaDeCapturasActivity : ComponentActivity() {

    /** Una captura llevada a la papelera, con lo que hace falta para devolverla tal cual estaba. */
    private class Borrada(val uri: Uri, val nombre: String, val conservada: Boolean, val prorroga: Long?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pedida = intent.getStringExtra(EXTRA_CAPTURA)
        setContent { PixPinTheme { Pantalla() } }
    }

    private val raiz: File get() = BarrenderoDeCapturas.raiz(this)

    /** La captura que pidió el widget de la galería ([EXTRA_CAPTURA]), hasta que se abre. */
    private var pedida: String? = null

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Pantalla() {
        val app = application as PixPinApp
        val ajustes by app.settings.settings.collectAsState(initial = app.ajustes)
        val dias = ajustes.diasCaducidad
        var capturas by remember { mutableStateOf<List<Captura>?>(null) }
        var registro by remember { mutableStateOf(CaducidadDeCapturas.Registro(desde = 0L)) }
        var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var version by remember { mutableIntStateOf(0) }
        var filtro by remember { mutableStateOf(Filtro.TODAS) }
        var consulta by remember { mutableStateOf("") }
        var eligiendo by remember { mutableStateOf(false) }
        var elegidas by remember { mutableStateOf<Set<Uri>>(emptySet()) }
        var detalle by remember { mutableStateOf<Captura?>(null) }
        val avisos = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        // Lo último borrado, para «Deshacer».
        var deshacer by remember { mutableStateOf<List<Borrada>>(emptyList()) }

        LaunchedEffect(version) {
            val (l, r) = withContext(Dispatchers.IO) {
                val t = System.currentTimeMillis()
                // Al abrir, también se barre: lo que caducó con la app cerrada no se enseña.
                BarrenderoDeCapturas.barrer(this@GaleriaDeCapturasActivity, t, app.settings.settings.first().diasCaducidad)
                (BarrenderoDeCapturas.listar(this@GaleriaDeCapturasActivity) ?: emptyList()) to CaducidadDeCapturas.leer(raiz, t)
            }
            ahora = System.currentTimeMillis()
            registro = r
            capturas = l
            val presentes = l.mapTo(HashSet()) { it.uri }
            elegidas = elegidas.filterTo(HashSet()) { it in presentes }
            // La captura que se tocó en el widget, abierta una vez (si sigue ahí).
            pedida?.let { u -> pedida = null; l.firstOrNull { it.uri.toString() == u }?.let { detalle = it } }
        }

        fun avisar(texto: String, conDeshacer: Boolean = false): kotlinx.coroutines.Job = scope.launch {
            avisos.currentSnackbarData?.dismiss()
            val r = avisos.showSnackbar(texto, actionLabel = if (conDeshacer) "Deshacer" else null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed && conDeshacer) {
                val lista = deshacer
                deshacer = emptyList()
                devolver(lista) { n -> version++; avisar(if (n == 1) "Recuperada" else "$n recuperadas") }
            }
        }

        // Lo que Android pide confirmar (capturas que no escribió PixPin): la papelera o sacarlas.
        var pendienteDeBorrar by remember { mutableStateOf<List<Borrada>>(emptyList()) }
        val pedirPapelera = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val estas = pendienteDeBorrar
            pendienteDeBorrar = emptyList()
            if (res.resultCode == RESULT_OK && estas.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) { olvidar(estas) }
                deshacer = deshacer + estas
                version++
                avisar(if (deshacer.size == 1) "Llevada a la papelera" else "${deshacer.size} llevadas a la papelera", conDeshacer = true)
            }
        }

        fun borrar(lista: List<Captura>) {
            if (lista.isEmpty()) return
            lifecycleScope.launch {
                val r0 = withContext(Dispatchers.IO) { CaducidadDeCapturas.leer(raiz, System.currentTimeMillis()) }
                val todas = lista.map { Borrada(it.uri, it.nombre, it.nombre in r0.conservadas, r0.prorrogadas[it.nombre]) }
                val faltan = withContext(Dispatchers.IO) { BarrenderoDeCapturas.aLaPapelera(this@GaleriaDeCapturasActivity, todas.map { it.uri }) }.toSet()
                val hechas = todas.filter { it.uri !in faltan }
                withContext(Dispatchers.IO) { olvidar(hechas) }
                elegidas = elegidas - lista.map { it.uri }.toSet()
                val quitadas = lista.mapTo(HashSet()) { it.uri }
                if (detalle?.let { it.uri in quitadas } == true) detalle = null
                deshacer = if (BarrenderoDeCapturas.hayPapelera) hechas else emptyList()
                version++
                if (hechas.isNotEmpty()) {
                    val n = hechas.size
                    if (BarrenderoDeCapturas.hayPapelera) avisar(if (n == 1) "Llevada a la papelera" else "$n llevadas a la papelera", conDeshacer = true)
                    else avisar(if (n == 1) "Borrada" else "$n borradas")
                }
                // Las ajenas: Android pregunta (Android 11+); en Android 10 no se puede desde aquí.
                val ajenas = todas.filter { it.uri in faltan }
                if (ajenas.isNotEmpty()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        pendienteDeBorrar = ajenas
                        runCatching {
                            val pi = MediaStore.createTrashRequest(contentResolver, ajenas.map { it.uri }, true)
                            pedirPapelera.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }.onFailure { avisar("No se pudo llevar a la papelera") }
                    } else avisar("Android no deja borrarla desde aquí: bórrala desde la galería")
                }
            }
        }

        // ---------------------------------------------------------------- la vista
        val lista = capturas
        val zona = remember { java.util.TimeZone.getDefault() }
        val fichas = remember(lista, registro, dias) {
            lista?.map { c -> GaleriaLogica.Ficha(c.nombre, c.extension, c.cuando, CaducidadDeCapturas.seVaEl(registro, c.nombre, c.cuando, dias)) } ?: emptyList()
        }
        val carril = remember(fichas, ahora) { GaleriaLogica.carril(fichas, ahora, hayOcr = false, zona = zona) }
        // Si el filtro elegido deja de salir (no queda ningún GIF), vuelve a «Todas».
        LaunchedEffect(carril) { if (carril.none { it.first == filtro }) filtro = Filtro.TODAS }
        val vista = remember(fichas, filtro, consulta, ahora) {
            fichas.indices.filter { GaleriaLogica.pasa(filtro, fichas[it], ahora, zona) && GaleriaLogica.coincide(fichas[it], consulta, zona) }
        }
        val grupos = remember(vista, fichas) { GaleriaLogica.agrupar(vista.map { GaleriaLogica.diaLocal(fichas[it].cuando, zona) }) }
        val hoy = GaleriaLogica.diaLocal(ahora, zona)

        Scaffold(
            snackbarHost = { SnackbarHost(avisos) },
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (eligiendo && lista != null) {
                    BarraDeElegidas(
                        n = elegidas.size,
                        onTodas = { elegidas = GaleriaLogica.alternarVarias(elegidas, vista.map { lista[it].uri }) },
                        onConservar = { conservar(lista.filter { it.uri in elegidas }, registro, dias) { r, t -> registro = r; avisar(t) } },
                        onPinear = { pinear(lista.filter { it.uri in elegidas }) { avisar(it) } },
                        onCopiar = { copiar(lista.filter { it.uri in elegidas }) { avisar(it) } },
                        onBorrar = { borrar(lista.filter { it.uri in elegidas }) },
                        onSalir = { eligiendo = false; elegidas = emptySet() }
                    )
                }
            }
        ) { relleno ->
            Column(Modifier.fillMaxSize().padding(relleno).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BotonRedondo(Icons.AutoMirrored.Filled.ArrowBack, "Volver", { finish() }, tamano = 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Capturas", style = MaterialTheme.typography.titleLarge, color = Cristal.tinta, maxLines = 1)
                        if (!lista.isNullOrEmpty()) {
                            val ocupan = remember(lista) { GaleriaLogica.tamanoLegible(lista.sumOf { it.bytes }) }
                            Text("${GaleriaLogica.subtitulo(lista.size, dias)} · Ocupan $ocupan",
                                style = MaterialTheme.typography.bodySmall, color = Cristal.tinta.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!lista.isNullOrEmpty()) {
                        TextButton(onClick = { eligiendo = !eligiendo; if (!eligiendo) elegidas = emptySet() }) {
                            Text(if (eligiendo) "Listo" else "Elegir", maxLines = 1)
                        }
                    }
                }
                when {
                    lista == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    lista.isEmpty() -> Text(
                        "Todavía no hay capturas. Las que hagas con la bola o el botón de ajustes rápidos aparecerán aquí.",
                        color = Cristal.tinta.copy(alpha = 0.75f), modifier = Modifier.padding(24.dp)
                    )
                    else -> {
                        Buscador(consulta) { consulta = it }
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(carril, key = { it.first }) { (f, n) ->
                                FilterChip(
                                    selected = filtro == f, onClick = { filtro = f },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(f.rotulo, maxLines = 1)
                                            Spacer(Modifier.width(6.dp))
                                            Text("$n", color = if (f == Filtro.PRONTO && n > 0) NARANJA else Cristal.tinta.copy(alpha = 0.6f), maxLines = 1)
                                        }
                                    }
                                )
                            }
                        }
                        if (vista.isEmpty()) {
                            Text(
                                if (consulta.isNotBlank()) "Ninguna captura con «${consulta.trim()}»." else "Ninguna captura en este filtro.",
                                color = Cristal.tinta.copy(alpha = 0.75f), modifier = Modifier.padding(24.dp)
                            )
                        } else LazyVerticalGrid(
                            GridCells.Adaptive(112.dp), contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            grupos.forEach { g ->
                                item(key = "dia-${g.dia}", span = { GridItemSpan(maxLineSpan) }, contentType = "dia") {
                                    val (titulo, sub) = GaleriaLogica.tituloDelDia(g.dia, hoy, zona)
                                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(titulo, fontWeight = FontWeight.Bold, color = Cristal.tinta, maxLines = 1)
                                        Spacer(Modifier.width(8.dp))
                                        Text(sub, style = MaterialTheme.typography.bodySmall, color = Cristal.tinta.copy(alpha = 0.6f), maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        TextButton(onClick = {
                                            eligiendo = true
                                            elegidas = GaleriaLogica.alternarVarias(elegidas, (g.desde until g.hasta).map { lista[vista[it]].uri })
                                        }) { Text("Elegir todo el día", maxLines = 1) }
                                    }
                                }
                                items(vista.subList(g.desde, g.hasta), key = { lista[it].uri.toString() }, contentType = { "captura" }) { i ->
                                    val c = lista[i]
                                    Miniatura(
                                        c, fichas[i].seVa, ahora, zona,
                                        elegida = if (eligiendo) c.uri in elegidas else null,
                                        onClick = {
                                            if (eligiendo) elegidas = GaleriaLogica.alternarUna(elegidas, c.uri) else detalle = c
                                        },
                                        onLargo = { eligiendo = true; elegidas = GaleriaLogica.alternarUna(elegidas, c.uri) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        detalle?.let { c ->
            val seVa = CaducidadDeCapturas.seVaEl(registro, c.nombre, c.cuando, dias)
            ModalBottomSheet(onDismissRequest = { detalle = null }) {
                Detalle(
                    c, seVa, ahora, zona,
                    onConservar = { conservar(listOf(c), registro, dias) { r, t -> registro = r; avisar(t) } },
                    onProrrogar = {
                        lifecycleScope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    val t = System.currentTimeMillis()
                                    CaducidadDeCapturas.cambiar(raiz, t) { CaducidadDeCapturas.prorrogada(it, c.nombre, c.cuando, t, dias) }
                                }.getOrNull()
                            }
                            if (r != null) { registro = r; avisar("Siete días más: se borrará más tarde") } else avisar("No se pudo")
                        }
                    },
                    onAccion = { hacer -> detalle = null; hacer() },
                    abrir = { abrir(c) }, pinear = { pinear(listOf(c)) { avisar(it) } }, copiar = { copiar(listOf(c)) { avisar(it) } },
                    compartir = { compartir(c) }, borrar = { borrar(listOf(c)) }
                )
            }
        }
    }

    @Composable
    private fun Buscador(consulta: String, onCambio: (String) -> Unit) {
        OutlinedTextField(
            value = consulta, onValueChange = onCambio, singleLine = true,
            placeholder = { Text("Buscar por nombre o fecha", maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (consulta.isNotEmpty()) IconButton(onClick = { onCambio("") }) { Icon(Icons.Default.Close, "Borrar la búsqueda") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }

    @Composable
    private fun BarraDeElegidas(
        n: Int, onTodas: () -> Unit, onConservar: () -> Unit, onPinear: () -> Unit, onCopiar: () -> Unit,
        onBorrar: () -> Unit, onSalir: () -> Unit
    ) {
        Surface(color = Color(0xFF2C2C2E), shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSalir) { Icon(Icons.Default.Close, "Dejar de elegir", tint = Color.White) }
                    Text(GaleriaLogica.elegidas(n), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = onTodas) { Text("Todas", maxLines = 1) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val activo = n > 0
                    TextButton(onClick = onConservar, enabled = activo) { Text("Conservar", color = if (activo) Color(0xFF30D158) else Color.Gray, maxLines = 1) }
                    TextButton(onClick = onPinear, enabled = activo) { Text("Pinear", maxLines = 1) }
                    TextButton(onClick = onCopiar, enabled = activo) { Text("Copiar", maxLines = 1) }
                    TextButton(onClick = onBorrar, enabled = activo) { Text("Borrar", color = if (activo) ROJO_TEXTO else Color.Gray, maxLines = 1) }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Miniatura(
        c: Captura, seVa: Long?, ahora: Long, zona: java.util.TimeZone, elegida: Boolean?,
        onClick: () -> Unit, onLargo: () -> Unit
    ) {
        var mapa by remember(c.uri) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(c.uri) {
            mapa = withContext(Dispatchers.IO) {
                runCatching { contentResolver.loadThumbnail(c.uri, Size(256, 256), null).asImageBitmap() }.getOrNull()
            }
        }
        val plazo = GaleriaLogica.plazo(seVa, ahora, zona)
        val (fondo, tinta) = colores(GaleriaLogica.tono(plazo))
        Box(
            Modifier.aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)).background(Cristal.boton)
                .then(if (elegida == true) Modifier.border(3.dp, AZUL, RoundedCornerShape(10.dp)) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = onLargo)
        ) {
            mapa?.let { Image(it, c.nombre, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (c.esVideo) Chapa("Vídeo", Modifier.align(Alignment.TopStart).padding(5.dp))
            Text(
                GaleriaLogica.textoDelPlazo(plazo, zona), color = tinta, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(8.dp)).background(fondo).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (elegida != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                        .background(if (elegida) AZUL else Color.Black.copy(alpha = 0.35f)).border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) { if (elegida) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
    }

    @Composable
    private fun Chapa(t: String, modifier: Modifier) {
        Text(t, color = Color.White, fontSize = 10.sp, maxLines = 1,
            modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 5.dp, vertical = 1.dp))
    }

    @Composable
    private fun Detalle(
        c: Captura, seVa: Long?, ahora: Long, zona: java.util.TimeZone,
        onConservar: () -> Unit, onProrrogar: () -> Unit, onAccion: (() -> Unit) -> Unit,
        abrir: () -> Unit, pinear: () -> Unit, copiar: () -> Unit, compartir: () -> Unit, borrar: () -> Unit
    ) {
        var grande by remember(c.uri) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(c.uri) {
            grande = withContext(Dispatchers.IO) {
                runCatching { contentResolver.loadThumbnail(c.uri, Size(720, 720), null).asImageBitmap() }.getOrNull()
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            grande?.let {
                Image(it, c.nombre, Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                Spacer(Modifier.height(10.dp))
            }
            Text(c.nombre, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val tipo = when {
                c.esVideo -> "Vídeo"
                c.extension == "gif" -> "GIF animado"
                else -> "Captura de pantalla"
            }
            val medidas = if (c.ancho > 0 && c.alto > 0) " · ${c.ancho} × ${c.alto}" else ""
            Text("$tipo$medidas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Fila("Fecha", GaleriaLogica.fechaYHora(c.cuando, ahora, zona))
            Fila("Tamaño", GaleriaLogica.tamanoLegible(c.bytes))
            Spacer(Modifier.height(10.dp))

            // La caducidad, con su color, y lo que se puede hacer con ella.
            val plazo = GaleriaLogica.plazo(seVa, ahora, zona)
            val color = when (GaleriaLogica.tono(plazo)) {
                Tono.LEJOS -> GRIS
                Tono.PRONTO -> NARANJA
                Tono.URGENTE -> ROJO_TEXTO
                Tono.CONSERVADA -> Color(0xFF30D158)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.10f))
                    .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(8.dp))
                    Text(GaleriaLogica.textoDelPlazo(plazo, zona), fontWeight = FontWeight.Bold, maxLines = 1)
                    if (seVa != null) {
                        Text(" · ${GaleriaLogica.enDias(GaleriaLogica.diasQueFaltan(seVa, ahora, zona).coerceAtLeast(0))}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                if (seVa == null) {
                    Text("Está en Mensajes guardados y no se borra.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConservar, colors = ButtonDefaults.buttonColors(containerColor = VERDE), modifier = Modifier.weight(1f)) {
                            Text("Conservar", color = Color.White, maxLines = 1)
                        }
                        OutlinedButton(onClick = onProrrogar, modifier = Modifier.weight(1f)) { Text("Dar 7 días más", maxLines = 1) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            buildList {
                if (!c.esVideo) add("Abrir para anotar" to abrir)
                if (!c.esVideo) add("Pinear" to pinear)
                add("Copiar" to copiar)
                add("Compartir" to compartir)
                add("Borrar" to borrar)
            }.forEach { (nombre, hacer) ->
                TextButton(onClick = { onAccion(hacer) }, modifier = Modifier.fillMaxWidth()) {
                    Text(nombre, modifier = Modifier.fillMaxWidth(), maxLines = 1, color = if (nombre == "Borrar") ROJO_TEXTO else Color.Unspecified)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun Fila(clave: String, valor: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(clave, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(90.dp))
            Text(valor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    // ------------------------------------------------------------------- acciones

    /** Una copia local: el editor, el pin y el chat trabajan con archivos nuestros. */
    private suspend fun local(c: Captura): File? = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(cacheDir, "galeria_${c.nombre}")
            contentResolver.openInputStream(c.uri)?.use { e -> f.outputStream().use { e.copyTo(it) } } ?: return@runCatching null
            f
        }.getOrNull()
    }

    private suspend fun guardadaParaPin(c: Captura): String? {
        val f = local(c) ?: return null
        return withContext(Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.let {
                com.forge.pixpin.pin.ImageStore.saveBitmap(this@GaleriaDeCapturasActivity, it, "galeria_${System.nanoTime()}")
            }
        }
    }

    private fun abrir(c: Captura) = lifecycleScope.launch {
        val guardada = guardadaParaPin(c) ?: return@launch aviso("No se pudo abrir")
        val id = "dib-${System.currentTimeMillis()}"
        com.forge.pixpin.motor.DrawEditorActivity.abrir(this@GaleriaDeCapturasActivity, id, com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this@GaleriaDeCapturasActivity, id), guardada)
    }

    /** Sacarlas a la pantalla, cada una en su pin. Los vídeos no se pinean. */
    private fun pinear(lista: List<Captura>, avisar: (String) -> Unit) = lifecycleScope.launch {
        val imagenes = lista.filterNot { it.esVideo }
        if (imagenes.isEmpty()) return@launch
        var n = 0
        for (c in imagenes) {
            val g = guardadaParaPin(c) ?: continue
            (application as PixPinApp).overlayManager.pinImage(g)
            n++
        }
        avisar(when (n) { 0 -> "No se pudo"; 1 -> "Puesta en la pantalla"; else -> "$n pineadas en la pantalla" })
    }

    /** Al portapapeles, todas en el mismo recorte. */
    private fun copiar(lista: List<Captura>, avisar: (String) -> Unit) {
        if (lista.isEmpty()) return
        val clip = ClipData.newUri(contentResolver, lista.first().nombre, lista.first().uri)
        lista.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        avisar(if (lista.size == 1) "Copiada al portapapeles" else "${lista.size} copiadas")
    }

    private fun compartir(c: Captura) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(c.mime).putExtra(Intent.EXTRA_STREAM, c.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }

    /**
     * **Conservar**: entra en «Mensajes guardados» como una foto más y se apunta para que no
     * caduque. Solo las que aún se iban.
     */
    private fun conservar(lista: List<Captura>, r: CaducidadDeCapturas.Registro, dias: Int, luego: (CaducidadDeCapturas.Registro, String) -> Unit) = lifecycleScope.launch {
        val pendientes = lista.filter { CaducidadDeCapturas.seVaEl(r, it.nombre, it.cuando, dias) != null }
        if (pendientes.isEmpty()) return@launch
        val (hechas, nuevo) = withContext(Dispatchers.IO) {
            val bien = pendientes.filter { com.forge.pixpin.guardados.AlChat.meter(this@GaleriaDeCapturasActivity, it.uri, null, it.nombre) }
            val reg = if (bien.isEmpty()) null else runCatching {
                CaducidadDeCapturas.cambiar(raiz, System.currentTimeMillis()) { x -> x.copy(conservadas = x.conservadas + bien.map { it.nombre }) }
            }.getOrNull()
            bien.size to reg
        }
        val texto = when {
            hechas == 0 || nuevo == null -> "No se pudo añadir"
            hechas == 1 -> "Conservada: está en Mensajes guardados y ya no se borra"
            else -> "$hechas conservadas: están en Mensajes guardados"
        }
        luego(nuevo ?: r, texto)
    }

    /** Lo borrado se olvida del registro (su nombre queda libre). Disco. */
    private fun olvidar(borradas: List<Borrada>) {
        if (borradas.none { it.conservada || it.prorroga != null }) return
        runCatching { CaducidadDeCapturas.cambiar(raiz, System.currentTimeMillis()) { CaducidadDeCapturas.sinEstas(it, borradas.map { b -> b.nombre }) } }
    }

    /** **Deshace un borrado**: cada una sale de la papelera con lo que tenía apuntado. */
    private fun devolver(lista: List<Borrada>, luego: (Int) -> Unit) = lifecycleScope.launch {
        if (lista.isEmpty()) return@launch
        val vueltas = withContext(Dispatchers.IO) {
            val faltan = BarrenderoDeCapturas.devolver(this@GaleriaDeCapturasActivity, lista.map { it.uri }).toSet()
            val vueltas = lista.filter { it.uri !in faltan }
            if (vueltas.any { it.conservada || it.prorroga != null }) runCatching {
                CaducidadDeCapturas.cambiar(raiz, System.currentTimeMillis()) { r ->
                    r.copy(
                        conservadas = r.conservadas + vueltas.filter { it.conservada }.map { it.nombre },
                        prorrogadas = r.prorrogadas + vueltas.mapNotNull { b -> b.prorroga?.let { b.nombre to it } }
                    )
                }
            }
            // Las ajenas no se pueden sacar sin preguntar: Android lo pide.
            val ajenas = lista.filter { it.uri in faltan }
            if (ajenas.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
                val pi = MediaStore.createTrashRequest(contentResolver, ajenas.map { it.uri }, false)
                withContext(Dispatchers.Main) { startIntentSenderForResult(pi.intentSender, 0, null, 0, 0, 0) }
            }
            vueltas.size
        }
        luego(vueltas)
    }

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    companion object {
        // Los colores del PC (`galeria_capturas.rs`).
        private val NARANJA = Color(0xFFFF9F0A)
        private val ROJO = Color(0xFFC9342B)
        private val ROJO_TEXTO = Color(0xFFFF6961)
        private val VERDE = Color(0xFF248A3D)
        private val GRIS = Color(0xFF98989D)
        private val AZUL = Color(0xFF0A84FF)

        /** Fondo y tinta de la pastilla de caducidad. */
        private fun colores(t: Tono): Pair<Color, Color> = when (t) {
            Tono.LEJOS -> Color(0xFF141416).copy(alpha = 0.85f) to Color(0xFFE5E5EA)
            Tono.PRONTO -> NARANJA to Color(0xFF1C1C1E)
            Tono.URGENTE -> ROJO to Color.White
            Tono.CONSERVADA -> VERDE to Color.White
        }

        /** El `content://` de una captura que abrir en su detalle nada más entrar (lo pone el widget de la galería). */
        const val EXTRA_CAPTURA = "captura"

        fun abrir(context: Context) = context.startActivity(Intent(context, GaleriaDeCapturasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
