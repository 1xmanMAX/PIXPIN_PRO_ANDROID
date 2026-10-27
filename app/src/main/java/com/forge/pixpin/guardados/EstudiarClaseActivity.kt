package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.forge.pixpin.R
import com.forge.pixpin.ui.EditorAvanzadoDeVisor
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Estudiar la clase** (25-oct-2026, la ventana del PC de `ce6c058`, el usuario: «una ventana
 * especial … donde se vea la grabación del vídeo con su transcripción al lado; al otro lado las
 * capturas, los marcadores … y que se pueda hacer scroll de las capturas y del texto, que una siga a
 * la otra»).
 *
 * En el teléfono, de arriba abajo: el vídeo de la clase (si lo trae) o **la captura del minuto** en
 * grande; **la barra del tiempo**, con una raya por captura y una banderita por marcador (un toque
 * cerca de una se queda en ella, [EstudiarClase.pegarASenal]); los mandos (±10 s, reproducir, la
 * velocidad y «Marcador»); la **tira de capturas** y el **texto** con su minuto y los marcadores
 * metidos en el suyo ([EstudiarClase.mezclar]). Con «Seguir», el texto y la tira siguen a lo que
 * suena, y mover una con el dedo lleva la otra al mismo minuto.
 *
 * El reloj es siempre **la nota de voz** ([Reproductor]): con vídeo, el vídeo va callado y la sigue
 * (su sonido es el mismo: la nota sale de su pista). Así suena igual con vídeo que sin él y la
 * barra del reproductor del chat sigue sabiendo qué suena.
 *
 * **Los marcadores** ([Mensaje.marcadores]) llevan su nota y se guardan en la nota de voz, donde los
 * pone también el PC; las banderitas de la letra ([Mensaje.marcas]) se enseñan con ellos y cada una
 * se quita de su campo (mantener pulsado). Del PC no se trae el teclado (espacio, J/L, M…).
 *
 * **Como los visores** (25-oct-2026, PC fe0c9c4 y 19c060f): claro u oscuro con la luna/el sol de
 * arriba; lo que se ve (el vídeo o la captura) **se acerca como un lienzo** —pellizcar hacia los dedos,
 * arrastrar, la pastilla de − % +— con [com.forge.pixpin.ui.Encuadre]; un toque reproduce o para y
 * **el doble toque solo encaja** (no reproduce: el toque suelto espera a ver si viene otro). Al lado,
 * el riel de todos los visores ([com.forge.pixpin.ui.RielDelVisor]): el marcador y **el lápiz, que
 * anota la captura que se ve** en su lienzo `foto-<id>` (el mismo que al tocarla en el chat, el que
 * viaja con ella), con el editor avanzado de los visores ([com.forge.pixpin.ui.EditorAvanzadoDeVisor]);
 * lo anotado se ve aquí encima de la captura. Con el vídeo, el lápiz lo para y guarda antes el
 * fotograma como **una captura más de la clase, en su minuto** (`guardar_fotograma` del PC: una
 * imagen con `enGrabacion` y su sitio en `capturas` de la nota; aquí además entra en la carpeta de la
 * grabación, para que no salga suelta en el chat). Al arrastrar la barra, el tirador va con el dedo
 * ([EstudiarClase.posicionALaVista]). Del PC no se traen Escape, las teclas de acercar ni el clic
 * contado desde la esquina de la ventana (`ms_del_raton`, a75847c): son cosas de una ventana de
 * escritorio.
 */
class EstudiarClaseActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(LA_NOTA).orEmpty()
        val inicio = intent.getLongExtra(EL_MINUTO, 0L)
        aPantallaCompleta()
        setContent {
            // Claro u oscuro como los visores: null es lo de los Ajustes.
            var oscuro by remember { mutableStateOf<Boolean?>(null) }
            PixPinTheme(darkTheme = oscuro) { Pantalla(id, inicio) { oscuro = it } }
        }
    }

    /** Una señal de la columna del texto: un marcador con nota o una banderita de la letra. */
    private class Senal(val ms: Long, val texto: String, val banderita: Boolean)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Pantalla(id: String, inicio: Long, alCambiarTema: (Boolean) -> Unit) {
        val almacen = remember { MensajesStore(this) }
        var version by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { MensajesStore.cambios.collect { version++ } }
        val todos = remember(version) { almacen.leer() }
        val m = remember(todos) { todos.firstOrNull { it.id == id } }
        val capturas = remember(todos, m) { m?.let { capturasDeLaLetra(it, todos) }.orEmpty() }
        // El mensaje de cada captura (el mismo orden): para su lienzo anotado.
        val fotos = remember(todos, m) {
            val porId = todos.associateBy { it.id }
            m?.let { n -> GrabarClase.capturasDe(n).mapNotNull { e -> porId[e.id]?.takeIf { it.ruta?.let { r -> File(r).exists() } == true } } }.orEmpty()
        }
        val video = remember(todos, m) { m?.let { videoDeLaLetra(it, todos) } }
        val lineas = remember(m?.transcripcion) { EstudiarClase.lineasDe(m?.transcripcion) }
        // Los marcadores y las banderitas, juntos y en orden: la ventana del PC enseña los dos.
        val senales = remember(m?.marcadores, m?.marcas) {
            (EstudiarClase.marcadoresDe(m?.marcadores).map { Senal(it.ms, it.texto, false) } +
                m?.marcas.orEmpty().map { Senal(it.toLong(), "", true) }).sortedBy { it.ms }
        }
        val tiemposDeLineas = remember(lineas) { lineas.map { it.ms } }
        val filas = remember(lineas, senales) { EstudiarClase.mezclar(tiemposDeLineas, senales.map { it.ms }) }
        val tiemposDeFilas = remember(filas) { EstudiarClase.tiemposDeFilas(filas, tiemposDeLineas, senales.map { it.ms }) }
        val tiemposDeCapturas = remember(capturas) { capturas.map { it.first } }

        val estado by Reproductor.estado.collectAsState()
        val titulo = m?.let { tituloDeAudio(this, it) }.orEmpty()
        val esta = m?.ruta != null && estado.ruta == m.ruta
        val posMs = if (esta) estado.posicionMs.toLong() else 0L
        val durMs = (if (esta && estado.duracionMs > 0) estado.duracionMs else m?.duracionMs ?: 0).toLong()
        LaunchedEffect(m?.ruta) {
            val r = m?.ruta
            if (r != null && File(r).exists() && Reproductor.estado.value.ruta != r) Reproductor.cargar(r, titulo, arrancar = false)
            // Abierta desde una pieza de su carpeta: en su minuto (cuando ya se sabe lo que dura).
            if (r != null && inicio > 0) {
                repeat(20) {
                    if (Reproductor.estado.value.duracionMs > 0) { m?.let { irA(it, inicio) }; return@LaunchedEffect }
                    kotlinx.coroutines.delay(100)
                }
            }
        }
        var arrastrando by remember { mutableStateOf<Long?>(null) }
        var encuadre by remember { mutableStateOf(com.forge.pixpin.ui.Encuadre()) }
        var verVideo by remember { mutableStateOf(true) }
        var anotando by remember { mutableStateOf<Mensaje?>(null) }
        val oscuroAhora = MaterialTheme.colorScheme.background.luminance() < 0.5f
        var seguir by remember { mutableStateOf(true) }
        var poniendo by remember { mutableStateOf<Long?>(null) }
        // **Compartir la clase** (27-oct-2026): la misma hoja que en el chat y en su carpeta. Ver [ExportarGrabacion].
        var compartiendo by remember { mutableStateOf<com.forge.pixpin.ui.Compartible?>(null) }
        val alcance = androidx.compose.runtime.rememberCoroutineScope()
        val irA: (Long) -> Unit = { ms -> m?.let { irA(it, ms) } }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, getString(R.string.cancel)) }
                    Column(Modifier.weight(1f)) {
                        Text(getString(R.string.estudiar_titulo), fontWeight = FontWeight.Bold, maxLines = 1)
                        if (titulo.isNotBlank()) Text(titulo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(enabled = m != null, onClick = {
                        val nota = m ?: return@IconButton
                        alcance.launch {
                            compartiendo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { val todos = almacen.leer(); ExportarGrabacion.compartible(this@EstudiarClaseActivity, ExportarGrabacion.notaDe(nota, todos) ?: nota, todos) }
                                    .onFailure { android.util.Log.e("PixPinCompartir", "estudiar", it) }.getOrNull()
                            }
                        }
                    }) { Icon(com.forge.pixpin.ui.IconoDeCompartir, getString(R.string.guardados_compartir)) }
                    compartiendo?.let { c -> com.forge.pixpin.ui.HojaDeCompartir(c) { compartiendo = null } }
                    IconButton(onClick = { alCambiarTema(!oscuroAhora) }) {
                        Icon(if (oscuroAhora) Icons.Filled.LightMode else Icons.Filled.DarkMode, if (oscuroAhora) "Claro" else "Oscuro")
                    }
                    FilterChip(selected = seguir, onClick = { seguir = !seguir }, label = { Text(getString(R.string.estudiar_seguir)) })
                }
                if (m == null || m.ruta == null || !File(m.ruta).exists()) {
                    Text(
                        getString(R.string.estudiar_sin_sonido), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                // Lo que se ve: el vídeo callado siguiendo a la nota, o la captura del minuto (con lo
                // anotado encima). Se acerca como un lienzo; ver [com.forge.pixpin.ui.Encuadre].
                val conVideo = video != null && (verVideo || capturas.isEmpty())
                val iVista = EstudiarClase.ultimoAntes(tiemposDeCapturas, posMs)
                val fotoVista = if (conVideo) null else iVista?.let { fotos.getOrNull(it) }
                var imagen by remember { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(fotoVista?.id, fotoVista?.referencia, anotando, version) {
                    val f = fotoVista
                    imagen = if (f == null) null else withContext(Dispatchers.IO) { capturaAnotada(f, 2048) }
                }
                // Al cambiar de captura o del vídeo a una captura, vuelve a encajada.
                LaunchedEffect(fotoVista?.id, conVideo) { encuadre = com.forge.pixpin.ui.Encuadre() }
                val actual = imagen
                val (anchoImg, altoImg) = when {
                    conVideo -> 0f to 0f
                    actual != null -> actual.width.toFloat() to actual.height.toFloat()
                    else -> 0f to 0f
                }
                Box(
                    Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(0.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    var caja by remember { mutableStateOf(com.forge.pixpin.ui.Encuadre.Caja(0f, 0f, 1f, 1f)) }
                    // Sin medidas de la imagen (el vídeo se encaja solo), la caja entera hace de imagen.
                    val w = if (anchoImg > 0f) anchoImg else caja.ancho
                    val h = if (altoImg > 0f) altoImg else caja.alto
                    Box(
                        Modifier.fillMaxSize()
                            .onSizeChanged { caja = com.forge.pixpin.ui.Encuadre.Caja(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
                            .graphicsLayer {
                                scaleX = encuadre.aumento; scaleY = encuadre.aumento
                                translationX = encuadre.dx; translationY = encuadre.dy
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (conVideo) VideoQueSigue(video!!, posMs, esta && estado.sonando)
                        else if (actual != null) Image(actual, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Filled.Image, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
                    }
                    // Los gestos, encima (el vídeo es una vista de Android y se quedaría los toques).
                    Box(
                        Modifier.fillMaxSize()
                            .pointerInput(m?.ruta) {
                                // Un toque reproduce o para; el doble toque **solo** encaja (19c060f).
                                detectTapGestures(
                                    onTap = { m?.ruta?.let { Reproductor.alternar(it, titulo) } },
                                    onDoubleTap = { encuadre = com.forge.pixpin.ui.Encuadre() }
                                )
                            }
                            .pointerInput(w, h, caja) {
                                detectTransformGestures { centro, mover, zoom, _ ->
                                    var e = encuadre
                                    if (zoom != 1f) e = e.acercar(caja, w, h, zoom, centro.x, centro.y)
                                    encuadre = e.mover(caja, w, h, mover.x, mover.y)
                                }
                            }
                    )
                    // Abajo a la izquierda: − % + y encajar, como la pastilla del PC.
                    if (!conVideo || !encuadre.encajado) Row(
                        Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(50))
                            .background(com.forge.pixpin.ui.CRISTAL_DE_VISOR),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { encuadre = encuadre.paso(caja, w, h, -1) }) { Text("−", color = Color.White) }
                        Text("${encuadre.porCiento(caja, w, h)} %", color = Color.White, fontSize = 12.sp)
                        TextButton(onClick = { encuadre = encuadre.paso(caja, w, h, 1) }) { Text("+", color = Color.White) }
                        if (!encuadre.encajado) IconButton(onClick = { encuadre = com.forge.pixpin.ui.Encuadre() }) {
                            Icon(Icons.Filled.FitScreen, "Encajar", tint = Color.White)
                        }
                    }
                    // Arriba a la izquierda, el vídeo o las capturas, si hay las dos cosas.
                    if (video != null && capturas.isNotEmpty()) TextButton(
                        onClick = { verVideo = !conVideo },
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(50)).background(com.forge.pixpin.ui.CRISTAL_DE_VISOR)
                    ) { Text(if (conVideo) "Capturas" else "Vídeo", color = Color.White, fontSize = 12.sp) }
                    // El riel de todos los visores: el marcador y el lápiz (anotar lo que se ve).
                    com.forge.pixpin.ui.RielDelVisor(
                        poniendoMarca = poniendo != null, anotando = anotando != null, marcando = false,
                        onMarcador = { if (m != null) poniendo = posMs },
                        onLapiz = {
                            val f = fotoVista
                            when {
                                f != null -> anotando = f
                                conVideo && m != null -> {
                                    // Se para: se anota lo que se está viendo, no lo que viene.
                                    if (estado.sonando && esta) Reproductor.alternar(m.ruta!!, titulo)
                                    val ms = posMs
                                    Toast.makeText(this@EstudiarClaseActivity, "Guardando el fotograma…", Toast.LENGTH_SHORT).show()
                                    Thread {
                                        val nueva = runCatching { guardarFotograma(m, video!!, ms) }
                                            .onFailure { android.util.Log.w("PixPinEstudiar", "fotograma", it) }.getOrNull()
                                        runOnUiThread {
                                            if (nueva == null) Toast.makeText(this@EstudiarClaseActivity, R.string.estudiar_no_se_guardo, Toast.LENGTH_SHORT).show()
                                            else { verVideo = false; anotando = nueva }
                                        }
                                    }.start()
                                }
                                else -> Toast.makeText(this@EstudiarClaseActivity, "Aquí no hay nada que anotar", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onMarco = null,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        rotuloDelLapiz = "Anotar"
                    )
                }
                BarraDelTiempo(EstudiarClase.posicionALaVista(posMs, arrastrando), durMs, tiemposDeCapturas, senales, irA) { arrastrando = it }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Transcriptor.marcaDeTiempo(posMs.toInt()) + " / " + Transcriptor.marcaDeTiempo(durMs.toInt()),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp)
                    )
                    IconButton(onClick = { irA(EstudiarClase.saltar(posMs, -10_000, durMs)) }) { Icon(Icons.Filled.Replay10, null) }
                    IconButton(onClick = { m?.ruta?.let { Reproductor.alternar(it, titulo) } }) {
                        Icon(if (esta && estado.sonando) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = { irA(EstudiarClase.saltar(posMs, 10_000, durMs)) }) { Icon(Icons.Filled.Forward10, null) }
                    TextButton(onClick = { Reproductor.otraVelocidad() }) {
                        Text(estado.velocidad.toString().removeSuffix(".0") + "×")
                    }
                    Box(Modifier.weight(1f))
                    IconButton(enabled = m != null, onClick = { poniendo = posMs }) {
                        Icon(Icons.Filled.BookmarkAdd, getString(R.string.estudiar_marcador))
                    }
                }
                // La tira de capturas.
                val tira = rememberLazyListState()
                val capturaActual = EstudiarClase.ultimoAntes(tiemposDeCapturas, posMs)
                if (capturas.isNotEmpty()) {
                    LazyRow(state = tira, modifier = Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(capturas) { i, (ms, ruta) ->
                            Miniatura(ruta, ms, i == capturaActual) { irA(ms) }
                        }
                    }
                }
                // El texto, con los marcadores en su minuto.
                val texto = rememberLazyListState()
                val lineaActual = if (esta) EstudiarClase.lineaEn(tiemposDeLineas, posMs) else null
                val filaActual = lineaActual?.let { l -> filas.indexOf(EstudiarClase.Fila.Linea(l)) }?.takeIf { it >= 0 }
                // **Con «Seguir», el texto y la tira siguen a lo que suena.**
                LaunchedEffect(filaActual, seguir) {
                    if (seguir && filaActual != null && !texto.isScrollInProgress) runCatching { texto.animateScrollToItem(filaActual) }
                }
                LaunchedEffect(capturaActual, seguir) {
                    if (seguir && capturaActual != null && !tira.isScrollInProgress) runCatching { tira.animateScrollToItem(capturaActual) }
                }
                // **Y mover una con el dedo lleva la otra al mismo minuto** (`seguir` del PC: el
                // milisegundo de la fila que se lee, y la captura que toca en él).
                LaunchedEffect(seguir, filas, capturas) {
                    if (!seguir) return@LaunchedEffect
                    snapshotFlow { texto.isScrollInProgress to texto.firstVisibleItemIndex }.collect { (moviendo, i) ->
                        if (moviendo && !tira.isScrollInProgress) {
                            val ms = tiemposDeFilas.getOrNull(i) ?: return@collect
                            EstudiarClase.ultimoAntes(tiemposDeCapturas, ms)?.let { runCatching { tira.scrollToItem(it) } }
                        }
                    }
                }
                LaunchedEffect(seguir, filas, capturas) {
                    if (!seguir) return@LaunchedEffect
                    snapshotFlow { tira.isScrollInProgress to tira.firstVisibleItemIndex }.collect { (moviendo, i) ->
                        if (moviendo && !texto.isScrollInProgress) {
                            val ms = tiemposDeCapturas.getOrNull(i) ?: return@collect
                            EstudiarClase.ultimoAntes(tiemposDeFilas, ms)?.let { runCatching { texto.scrollToItem(it) } }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (filas.isEmpty()) {
                        Text(
                            getString(if (capturas.isEmpty()) R.string.estudiar_sin_capturas else R.string.estudiar_sin_texto),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )
                    } else LazyColumn(state = texto, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(filas) { k, f ->
                            when (f) {
                                is EstudiarClase.Fila.Linea -> {
                                    val l = lineas[f.i]
                                    val activa = k == filaActual
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .background(if (activa) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                                            .clickable(enabled = l.ms != null) { l.ms?.let(irA) }
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                    ) {
                                        l.ms?.let {
                                            Text(Transcriptor.marcaDeTiempo(it.toInt()), fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        }
                                        Text(l.texto, fontSize = 17.sp, lineHeight = 24.sp,
                                            fontWeight = if (activa) FontWeight.SemiBold else FontWeight.Normal)
                                    }
                                }
                                is EstudiarClase.Fila.DeMarcador -> {
                                    val s = senales[f.j]
                                    Row(
                                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                                            .combinedClickable(
                                                onClick = { irA(s.ms) },
                                                onLongClick = { quitar(id, s) }
                                            )
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(if (s.banderita) Icons.Filled.Flag else Icons.Filled.BookmarkAdd, null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(16.dp))
                                        Text(
                                            " " + Transcriptor.marcaDeTiempo(s.ms.toInt()) +
                                                (if (s.texto.isNotBlank()) " · " + s.texto else ""),
                                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (senales.isNotEmpty()) Text(
                    getString(R.string.estudiar_quitar_marcador), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        poniendo?.let { ms -> DialogoDelMarcador(ms, onCancelar = { poniendo = null }) { nota -> poniendo = null; poner(id, ms, nota) } }
        anotando?.let { f -> AnotarCaptura(f, oscuroAhora) { anotando = null } }
    }

    /**
     * **Anotar una captura en su lienzo** `foto-<id>` ([dibujoDeLaFoto]), el mismo que al tocarla en el
     * chat: si aún no lo tiene, nace con la foto al fondo y clavada, como en [com.forge.pixpin.motor.DrawEditorActivity].
     * A pantalla entera, con el editor avanzado de los visores; «Listo» lo guarda y apunta el lienzo
     * en el mensaje (su `referencia`), que es lo que viaja.
     */
    @Composable
    private fun AnotarCaptura(f: Mensaje, noche: Boolean, alAcabar: () -> Unit) {
        val ajustes by (application as com.forge.pixpin.PixPinApp).settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
        val dibujo = f.dibujoDeLaFoto
        var capa by remember(f.id) { mutableStateOf<com.forge.pixpin.motor.DrawController?>(null) }
        val fotos = remember(f.id) { HashMap<String, android.graphics.Bitmap?>() }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(f.id) {
            capa = withContext(Dispatchers.IO) { lienzoDeLaCaptura(f, dibujo) }
        }
        val c = capa
        Box(Modifier.fillMaxSize().background(if (noche) Color(0xFF121212) else Color.White)) {
            if (c != null) {
            @Suppress("UNUSED_EXPRESSION") tick
            com.forge.pixpin.motor.DrawCanvas(
                controller = c,
                modifier = Modifier.fillMaxSize(),
                imageProvider = { idf -> fotos.getOrPut(idf) { c.scene.files[idf]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } } },
                dark = noche,
                onChange = { trazando -> if (!trazando) tick++ }
            )
            val sitio = com.forge.pixpin.motor.HerramientasPorSitio.Sitio.PIN
            EditorAvanzadoDeVisor(
                capa = c, ajustes = ajustes,
                permitidas = ajustes.formatoToolSet(sitio) - com.forge.pixpin.motor.LECTOR_TOOLS_FUERA,
                grupos = ajustes.formatoGroupList(sitio).map { g -> g.filterNot { it in com.forge.pixpin.motor.LECTOR_TOOLS_FUERA } }.filter { it.isNotEmpty() },
                noche = noche, tick = tick,
                alListo = {
                    val escena = c.scene
                    Thread {
                        runCatching {
                            com.forge.pixpin.motor.ExcalidrawStore.guardar(this@EstudiarClaseActivity, dibujo, escena)
                            if (f.referencia == null) MensajesStore(this@EstudiarClaseActivity).actualizar(f.id) { it.copy(referencia = dibujo) }
                        }
                    }.start()
                    alAcabar()
                },
                cambiado = { tick++ }
            )
            }
        }
    }

    /** El lienzo de la captura: el suyo si ya lo tiene; si no, uno con la foto al fondo, clavada. */
    private fun lienzoDeLaCaptura(f: Mensaje, dibujo: String): com.forge.pixpin.motor.DrawController? {
        val ruta = com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, dibujo)
        com.forge.pixpin.motor.ExcalidrawStore.cargar(ruta)?.let { escena ->
            return com.forge.pixpin.motor.DrawController(escena).also { it.selectTool(com.forge.pixpin.motor.Tool.FREEDRAW) }
        }
        val foto = f.ruta ?: return null
        val mapa = com.forge.pixpin.pin.ImageStore.load(foto) ?: return null
        val fichero = com.forge.pixpin.motor.ExcalidrawStore.guardarImagen(this, File(foto), "image/png") ?: return null
        val c = com.forge.pixpin.motor.DrawController(com.forge.pixpin.motor.Scene())
        c.placeImage(fichero, at = com.forge.pixpin.motor.Pt(mapa.width / 2.0, mapa.height / 2.0), width = mapa.width.toDouble(), height = mapa.height.toDouble(), alFondo = true)
        c.selectTool(com.forge.pixpin.motor.Tool.FREEDRAW)
        return c
    }

    /** **La captura con lo anotado encima**, si tiene lienzo; si no, la foto tal cual. */
    private fun capturaAnotada(f: Mensaje, lado: Int): ImageBitmap? = runCatching {
        val dibujo = f.referencia
        val escena = dibujo?.let { com.forge.pixpin.motor.ExcalidrawStore.cargar(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, it)) }
        if (escena != null && escena.elements.any { !it.isDeleted }) {
            val ancho = escena.elements.filter { !it.isDeleted }.maxOf { it.x + it.width } - escena.elements.filter { !it.isDeleted }.minOf { it.x }
            val escala = (lado / ancho.coerceAtLeast(1.0)).coerceIn(0.05, 2.0)
            com.forge.pixpin.motor.DrawExport.aBitmap(escena, escala) { idf ->
                escena.files[idf]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it, lado) }
            }?.asImageBitmap()
        } else null
    }.getOrNull() ?: f.ruta?.let { leerYa(it, lado) }

    /**
     * **Guarda el fotograma que se ve** como una captura más de la clase (`guardar_fotograma` del PC):
     * una imagen en el chat con su pie y `enGrabacion`, su sitio en las `capturas` de la nota
     * ([GrabarClase.ponerCaptura]) y, como pieza de lo grabado, en la carpeta de la grabación (así no
     * sale suelta en el chat). Devuelve el mensaje de la imagen.
     */
    private fun guardarFotograma(voz: Mensaje, video: String, ms: Long): Mensaje? {
        val r = android.media.MediaMetadataRetriever()
        val mapa = try {
            r.setDataSource(video)
            r.getFrameAtTime(ms * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
        } finally { runCatching { r.release() } } ?: return null
        val ahora = System.currentTimeMillis()
        val temporal = File(cacheDir, "clase-$ahora-$ms.png")
        temporal.outputStream().use { mapa.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val almacen = MensajesStore(this)
        val fichero = temporal.name
        val ruta = almacen.copiarAdjunto(temporal, fichero, "png", aligerar = false, enderezar = false)
        temporal.delete()
        ruta ?: return null
        val nombre = voz.nombre.removeSuffix(".m4a")
        val m = Mensaje(
            id = java.util.UUID.randomUUID().toString(),
            cuando = ahora,
            clase = Clase.IMAGEN,
            texto = GrabarClase.pie(ms, nombre),
            ruta = ruta,
            nombre = fichero,
            bytes = File(ruta).length(),
            proyecto = voz.proyecto,
            enGrabacion = GrabarClase.enGrabacion(voz.id, ms)
        )
        almacen.anadir(m)
        almacen.actualizar(voz.id) { n -> GrabarClase.ponerCaptura(n.capturas, ms, m.id)?.let { n.copy(capturas = it) } ?: n }
        almacen.leer().firstOrNull { CarpetaDePaginas.deMensaje(it)?.let { c -> c.esGrabacion && c.voz == voz.id } == true }?.let { carpeta ->
            CarpetasDelChat.cambiar(this, carpeta.id) { c -> c.anadirAlMinuto(m.id, "⏱ ${GrabarClase.minuto(ms)}", ms) }
        }
        return m
    }

    /** **La barra del tiempo**: lo sonado, una raya por captura y una banderita por marcador. */
    @Composable
    private fun BarraDelTiempo(
        posMs: Long, durMs: Long, capturas: List<Long>, senales: List<Senal>, irA: (Long) -> Unit,
        /** Dónde va el dedo arrastrando (null al soltar): el tirador va con él. */
        alArrastrar: (Long?) -> Unit = {}
    ) {
        val color = MaterialTheme.colorScheme.primary
        val fondo = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        val raya = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        val bandera = MaterialTheme.colorScheme.tertiary
        val todas = remember(capturas, senales) { (capturas + senales.map { it.ms }).distinct().sorted() }
        val tolerancia = 14f * resources.displayMetrics.density
        fun pegar(x: Float, ancho: Float) = irA(EstudiarClase.pegarASenal(x, ancho, durMs, todas, tolerancia))
        Canvas(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp)
                .pointerInput(durMs, todas) { detectTapGestures { pegar(it.x, size.width.toFloat()) } }
                .pointerInput(durMs) {
                    detectHorizontalDragGestures(
                        onDragEnd = { alArrastrar(null) },
                        onDragCancel = { alArrastrar(null) }
                    ) { cambio, _ ->
                        val ms = EstudiarClase.msDeLaBarra(cambio.position.x, size.width.toFloat(), durMs)
                        alArrastrar(ms)
                        irA(ms)
                    }
                }
        ) {
            val y = size.height * 0.6f
            drawLine(fondo, Offset(0f, y), Offset(size.width, y), strokeWidth = 4.dp.toPx())
            val x = EstudiarClase.xEnLaBarra(posMs, size.width, durMs)
            drawLine(color, Offset(0f, y), Offset(x, y), strokeWidth = 4.dp.toPx())
            for (c in capturas) {
                val cx = EstudiarClase.xEnLaBarra(c, size.width, durMs)
                drawLine(raya, Offset(cx, y - 7.dp.toPx()), Offset(cx, y + 7.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
            for (s in senales) {
                val sx = EstudiarClase.xEnLaBarra(s.ms, size.width, durMs)
                val p = Path().apply {
                    moveTo(sx, y - 4.dp.toPx()); lineTo(sx, y - 16.dp.toPx())
                    lineTo(sx + 8.dp.toPx(), y - 13.dp.toPx()); lineTo(sx, y - 10.dp.toPx()); close()
                }
                drawPath(p, bandera)
            }
            drawCircle(color, radius = 7.dp.toPx(), center = Offset(x, y))
        }
    }

    @Composable
    private fun Miniatura(ruta: String, ms: Long, actual: Boolean, alTocar: () -> Unit) {
        var imagen by remember(ruta) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(ruta) { imagen = leer(ruta, 320) }
        Box(
            Modifier.fillMaxHeight().width(120.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(if (actual) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                .clickable(onClick = alTocar),
            contentAlignment = Alignment.BottomStart
        ) {
            imagen?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Text(
                Transcriptor.marcaDeTiempo(ms.toInt()), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.padding(4.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp)
            )
        }
    }

    /**
     * **El vídeo, callado, siguiendo a la nota**: arranca y para con ella, y si se separa más de
     * medio segundo (un salto, la velocidad) se recoloca.
     */
    @Composable
    private fun VideoQueSigue(ruta: String, posMs: Long, sonando: Boolean) {
        var vista by remember { mutableStateOf<VideoView?>(null) }
        var listo by remember { mutableStateOf(false) }
        AndroidView(
            factory = { c ->
                VideoView(c).apply {
                    setOnPreparedListener { mp: MediaPlayer ->
                        runCatching { mp.setVolume(0f, 0f) }
                        listo = true
                    }
                    setVideoPath(ruta)
                    vista = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        LaunchedEffect(listo, sonando) {
            val v = vista ?: return@LaunchedEffect
            if (!listo) return@LaunchedEffect
            if (sonando) runCatching { v.start() } else runCatching { v.pause() }
        }
        LaunchedEffect(listo, posMs / 500) {
            val v = vista ?: return@LaunchedEffect
            if (!listo) return@LaunchedEffect
            val en = runCatching { v.currentPosition.toLong() }.getOrDefault(0L)
            if (Math.abs(en - posMs) > 500) runCatching { v.seekTo(posMs.toInt()) }
        }
    }

    @Composable
    private fun DialogoDelMarcador(ms: Long, onCancelar: () -> Unit, onGuardar: (String) -> Unit) {
        var nota by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onCancelar,
            title = { Text(getString(R.string.estudiar_marcador) + " · " + Transcriptor.marcaDeTiempo(ms.toInt())) },
            text = {
                OutlinedTextField(
                    value = nota,
                    onValueChange = { nota = it.replace('\n', ' ').take(EstudiarClase.NOTA_MAXIMA) },
                    placeholder = { Text(getString(R.string.estudiar_nota_del_marcador)) },
                    singleLine = true
                )
            },
            confirmButton = { TextButton(onClick = { onGuardar(nota) }) { Text(getString(R.string.estudiar_guardar)) } },
            dismissButton = { TextButton(onClick = onCancelar) { Text(getString(R.string.cancel)) } }
        )
    }

    /** A ese punto de la nota, cargándola si hacía falta. */
    private fun irA(m: Mensaje, ms: Long) {
        val ruta = m.ruta ?: return
        if (Reproductor.estado.value.ruta != ruta) Reproductor.cargar(ruta, tituloDeAudio(this, m), arrancar = false)
        val total = Reproductor.estado.value.duracionMs
        if (total > 0) Reproductor.irA(ms.toFloat() / total)
    }

    private fun poner(id: String, ms: Long, nota: String) {
        Thread {
            val bien = runCatching {
                MensajesStore(this).actualizar(id) { it.copy(marcadores = EstudiarClase.ponerMarcador(it.marcadores, ms, nota)) }
            }.isSuccess
            runOnUiThread {
                Toast.makeText(this, if (bien) R.string.estudiar_marcador_guardado else R.string.estudiar_no_se_guardo, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Quita la señal de su campo: el marcador de `marcadores`, la banderita de `marcas`. */
    private fun quitar(id: String, s: Senal) {
        Thread {
            runCatching {
                MensajesStore(this).actualizar(id) {
                    if (s.banderita) it.copy(marcas = it.marcas - s.ms.toInt())
                    else it.copy(marcadores = EstudiarClase.quitarMarcador(it.marcadores, s.ms).first)
                }
            }
        }.start()
    }

    companion object {
        private const val LA_NOTA = "estudiar_nota"
        private const val EL_MINUTO = "estudiar_minuto"

        fun intento(context: Context, nota: String, ms: Long = 0): Intent =
            Intent(context, EstudiarClaseActivity::class.java).putExtra(LA_NOTA, nota).putExtra(EL_MINUTO, ms)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Abre la clase de la nota de voz [nota], en [ms] si se da (una pieza de su carpeta). */
        fun abrir(context: Context, nota: String, ms: Long = 0) {
            // Desde fuera y sin la aplicación abierta, con la lista de chats debajo. Ver [com.forge.pixpin.Volver.abrirEnLaApp].
            com.forge.pixpin.Volver.abrirEnLaApp(context, intento(context, nota, ms))
        }
    }
}

/** La imagen reducida a [lado], fuera del hilo de la pantalla. */
private suspend fun leer(ruta: String, lado: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ruta, medidas)
        var paso = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / paso > lado) paso *= 2
        BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = paso })?.asImageBitmap()
    }.getOrNull()
}

/** Como [leer], ya en el hilo en que se esté (para quien ya va fuera del de la pantalla). */
private fun leerYa(ruta: String, lado: Int): ImageBitmap? = runCatching {
    val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(ruta, medidas)
    var paso = 1
    while (maxOf(medidas.outWidth, medidas.outHeight) / paso > lado) paso *= 2
    BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = paso })?.asImageBitmap()
}.getOrNull()
