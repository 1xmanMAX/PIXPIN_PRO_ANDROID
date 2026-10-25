package com.forge.pixpin.guardados

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.forge.pixpin.R
import com.forge.pixpin.pin.duracionLegible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormatSymbols

/**
 * **La ficha del chat, como el panel de medios de Telegram** (25-oct-2026, la isla del PC de
 * `99f918a` y `2439c5c`). Sale al tocar el nombre del chat, que es lo que hace Telegram, y se abre
 * en **«Proyectos»** (el usuario, en el PC: «quiero que esta zona de proyectos se mueva al menú que
 * se abre al darle click en la parte de arriba del chat»): cada chat en su fila, en el orden de la
 * lista de chats, con su avatar, su nombre, cuántas hojas tiene, su última línea y cuándo se tocó.
 * El de este chat va marcado («Este chat») y tocarlo lleva a sus dibujos; tocar otro cambia de
 * chat aquí mismo, como la lista de chats del menú.
 *
 * Las demás pestañas son las del PC: la rejilla de **Fotos** (con las fotos de las notas ricas,
 * cada una en el sitio de su nota, `8c92ba4`), **Vídeos** (con su triángulo y lo que duran) y
 * **Dibujos**, de borde a borde con 2 dp entre miniaturas y de tres a cinco columnas según el
 * ancho; y las filas de **Archivos** (la chapa de color de su extensión, el nombre cortado con «…»
 * y «peso · fecha»), **Enlaces** (la letra de su sitio, el sitio, la dirección y lo que decía el
 * mensaje), **Voz** (suena sin salir de la ficha) y **Fijados**. Todo va **por meses, lo más nuevo
 * arriba**, y cada pestaña vacía dice lo suyo. La lupa filtra la pestaña que se ve; en «Proyectos»,
 * por nombre, con lo buscado en negrita.
 *
 * Tocar una miniatura la abre en grande sobre fondo oscuro, y se pasa de una a otra deslizando:
 * arriba, volver, el nombre con «3 de 24 · fecha», «Ver en el chat» y «Abrir». La que se ve se
 * apunta **por id** ([SeccionesDelChat.Cosa]) y no por posición: si la sincronización mete o
 * quita mensajes mientras se mira, sigue en la misma o, si ya no está, se vuelve a la rejilla.
 *
 * Del PC no se trae lo que es del teclado y del ratón (las flechas, Intro, la rueda sobre la tira):
 * en el teléfono atrás vuelve un paso —de la previa a la rejilla, de la búsqueda a la pestaña— y al
 * final cierra. Ni «Todo», que aquí es el propio chat, ni el buzón, que tiene su ficha arriba.
 * Lo puro está en [SeccionesDelChat] y se prueba en `SeccionesDelChatTest`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FichaDelChat(
    nombre: String,
    subtitulo: String,
    /** El chat que se ve (null, la conversación general). */
    chatDe: String?,
    /** Los mensajes de este chat. */
    mensajes: List<Mensaje>,
    /** Los chats, en el orden de la lista de chats. Ver [conversaciones]. */
    charlas: List<Conversacion>,
    /** Las hojas de cada chat. Ver [SeccionesDelChat.hojasPorChat]. */
    hojas: Map<String?, Int>,
    /** El id del chat que aún no existe (el «Asistente» sin crear): no sale. */
    sinCrear: String?,
    resumenDe: (Mensaje) -> String,
    fechaDe: (Long) -> String,
    onCerrar: () -> Unit,
    onIrAlChat: (Conversacion) -> Unit,
    onVerEnElChat: (String) -> Unit,
    onAbrir: (Mensaje) -> Unit,
    onAbrirFichero: (File) -> Unit,
    onAbrirEnlace: (String) -> Unit
) {
    var seccion by remember { mutableStateOf(Seccion.PROYECTOS) }
    var consulta by remember { mutableStateOf<String?>(null) }
    var abierta by remember { mutableStateOf<SeccionesDelChat.Cosa?>(null) }
    // **La hoja de la foto del chat**, tocando el avatar de arriba (25-oct-2026, la isla del PC). Ver [HojaDeLaFotoDelChat].
    var cambiandoFoto by remember { mutableStateOf(false) }
    val filesDir = LocalContext.current.filesDir
    val porId = remember(mensajes) { mensajes.associateBy { it.id } }
    // Lo de cada pestaña, con la búsqueda aplicada; lo nuevo primero, como se enseña.
    val cosas = remember(mensajes, seccion, consulta) {
        val filtrados = if (consulta.isNullOrBlank()) mensajes else buscar(mensajes, consulta.orEmpty())
        SeccionesDelChat.cosasDe(filtrados, seccion).asReversed()
    }
    val cuentas = remember(mensajes) {
        SeccionesDelChat.DE_LA_FICHA.associateWith { s ->
            if (s == Seccion.PROYECTOS) SeccionesDelChat.proyectosDeLaFicha(charlas, null, sinCrear).size
            else SeccionesDelChat.cosasDe(mensajes, s).size
        }
    }
    // La previa, por id: si lo abierto ya no está, a la rejilla.
    val enLaPrevia = SeccionesDelChat.reubicar(cosas, abierta)
    LaunchedEffect(enLaPrevia, abierta) { if (abierta != null && enLaPrevia == null) abierta = null }

    val atras = {
        when {
            abierta != null -> abierta = null
            consulta != null -> consulta = null
            else -> onCerrar()
        }
    }
    Dialog(
        onDismissRequest = atras,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Cabecera(nombre, subtitulo, chatDe, consulta, { consulta = it }, onCerrar, onFoto = { cambiandoFoto = true })
                Pestanas(seccion, cuentas) { seccion = it; abierta = null }
                HorizontalDivider()
                Box(Modifier.fillMaxSize()) {
                    when {
                        seccion == Seccion.PROYECTOS -> Proyectos(
                            charlas, hojas, chatDe, sinCrear, consulta, resumenDe, fechaDe,
                            onIrAlChat = onIrAlChat,
                            // El de este chat no sale: lleva a sus hojas, lo que enseñaba su tarjeta.
                            onEste = { seccion = Seccion.DIBUJOS }
                        )
                        cosas.isEmpty() -> Vacia(seccion)
                        SeccionesDelChat.esCuadricula(seccion) -> Rejilla(cosas, porId) { abierta = it }
                        else -> Filas(seccion, cosas, porId, fechaDe, onAbrir, onAbrirEnlace, onVerEnElChat)
                    }
                }
            }
            if (cambiandoFoto && chatDe != null) HojaDeLaFotoDelChat(chatDe, nombre) { cambiandoFoto = false }
            val i = enLaPrevia
            if (i != null) {
                Previa(
                    cosas, i, porId, nombre, fechaDe,
                    onCambiar = { abierta = cosas.getOrNull(it) },
                    onVolver = { abierta = null },
                    onVerEnElChat = onVerEnElChat,
                    onAbrir = { c ->
                        val m = porId[c.id]
                        val k = c.foto
                        if (m != null && k != null) {
                            SeccionesDelChat.fotosDeLaNota(m.texto).getOrNull(k)?.let { f ->
                                NotaRica.archivo(filesDir, f.enlace)?.takeIf { it.isFile }?.let(onAbrirFichero)
                            }
                        } else if (m != null) onAbrir(m)
                    }
                )
            }
        }
    }
}

@Composable
private fun Cabecera(
    nombre: String,
    subtitulo: String,
    chatDe: String?,
    consulta: String?,
    onConsulta: (String?) -> Unit,
    onCerrar: () -> Unit,
    /** Tocar el avatar: cambiar la foto del chat (no en la conversación general, que no tiene). */
    onFoto: () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCerrar) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
        if (consulta != null) {
            TextField(
                value = consulta,
                onValueChange = { onConsulta(it) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.ficha_buscar)) },
                keyboardOptions = KeyboardOptions.Default,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f)
            )
        } else {
            // El avatar redondo de 40, como la cabecera del PC: su foto o su icono. Tocarlo cambia la
            // foto del chat (en el PC, pulsar el avatar de la isla).
            CirculoDelChat(
                chatDe, nombre, 40.dp, MaterialTheme.colorScheme.primary,
                if (chatDe != null) Modifier.clip(CircleShape).clickable(onClick = onFoto) else Modifier
            ) {
                Icon(
                    if (chatDe == null) Icons.Filled.BookmarkBorder else Icons.Filled.Folder, null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp)
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(nombre, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitulo.isNotBlank()) Text(
                    subtitulo, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = { onConsulta(if (consulta == null) "" else null) }) {
            Icon(Icons.Filled.Search, stringResource(R.string.ficha_buscar))
        }
    }
}

/**
 * **Las pestañas**: el texto con la rayita de color bajo la activa (en negrita) y la cuenta
 * apagada detrás, en una sola fila que se desliza si no caben; nunca se parte un rótulo.
 */
@Composable
private fun Pestanas(actual: Seccion, cuentas: Map<Seccion, Int>, onElegir: (Seccion) -> Unit) {
    val lista = SeccionesDelChat.DE_LA_FICHA
    val i = lista.indexOf(actual).coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = i,
        edgePadding = 8.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        indicator = { posiciones ->
            if (i < posiciones.size) TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(posiciones[i]), color = MaterialTheme.colorScheme.primary
            )
        },
        divider = {}
    ) {
        lista.forEachIndexed { k, s ->
            Tab(selected = k == i, onClick = { onElegir(s) }, text = {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = if (k == i) FontWeight.Bold else FontWeight.Normal)) {
                            append(stringResource(rotuloDe(s)))
                        }
                        val n = cuentas[s] ?: 0
                        if (n > 0) withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))) {
                            append("  $n")
                        }
                    },
                    maxLines = 1, softWrap = false, fontSize = 14.sp
                )
            })
        }
    }
}

/** El nombre de cada pestaña. */
fun rotuloDe(s: Seccion): Int = when (s) {
    Seccion.TODO -> R.string.guardados_todo
    Seccion.FOTOS -> R.string.guardados_fotos
    Seccion.VIDEOS -> R.string.guardados_videos
    Seccion.ARCHIVOS -> R.string.guardados_archivos
    Seccion.ENLACES -> R.string.guardados_enlaces
    Seccion.VOZ -> R.string.guardados_voz
    Seccion.DIBUJOS -> R.string.guardados_dibujos
    Seccion.FIJADOS -> R.string.guardados_fijados
    Seccion.BUZON -> R.string.guardados_buzon
    Seccion.PROYECTOS -> R.string.guardados_proyectos
}

private fun vaciaDe(s: Seccion): Pair<Int, ImageVector> = when (s) {
    Seccion.FOTOS -> R.string.ficha_vacia_fotos to Icons.Filled.Image
    Seccion.VIDEOS -> R.string.ficha_vacia_videos to Icons.Filled.Videocam
    Seccion.ARCHIVOS -> R.string.ficha_vacia_archivos to Icons.Filled.Description
    Seccion.ENLACES -> R.string.ficha_vacia_enlaces to Icons.Filled.Link
    Seccion.VOZ -> R.string.ficha_vacia_voz to Icons.Filled.Mic
    Seccion.DIBUJOS -> R.string.ficha_vacia_dibujos to Icons.Filled.Brush
    Seccion.FIJADOS -> R.string.ficha_vacia_fijados to Icons.Filled.PushPin
    Seccion.PROYECTOS -> R.string.ficha_vacia_proyectos to Icons.Filled.Folder
    else -> R.string.guardados_seccion_vacia to Icons.Filled.BookmarkBorder
}

@Composable
private fun Vacia(s: Seccion) {
    val (texto, icono) = vaciaDe(s)
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(icono, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) }
        Text(
            stringResource(texto), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp)
        )
    }
}

/** «Octubre de 2026», con el nombre del mes del idioma del teléfono. */
@Composable
private fun rotuloDelMes(anio: Int, mes: Int): String {
    val nombre = remember(mes) {
        DateFormatSymbols.getInstance().months.getOrNull(mes - 1).orEmpty().replaceFirstChar { it.titlecase() }
    }
    return stringResource(R.string.ficha_mes, nombre, anio)
}

@Composable
private fun RotuloDelMes(anio: Int, mes: Int) {
    Text(
        rotuloDelMes(anio, mes), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

// ---- La pestaña «Proyectos» ----

@Composable
private fun Proyectos(
    charlas: List<Conversacion>,
    hojas: Map<String?, Int>,
    chatDe: String?,
    sinCrear: String?,
    consulta: String?,
    resumenDe: (Mensaje) -> String,
    fechaDe: (Long) -> String,
    onIrAlChat: (Conversacion) -> Unit,
    onEste: () -> Unit
) {
    val filas = remember(charlas, consulta, sinCrear) { SeccionesDelChat.proyectosDeLaFicha(charlas, consulta, sinCrear) }
    if (filas.isEmpty()) Vacia(Seccion.PROYECTOS) else LazyColumn(Modifier.fillMaxSize()) {
        items(filas, key = { it.proyecto ?: "general" }) { c ->
            val esEste = c.proyecto == chatDe
            Row(
                Modifier.fillMaxWidth()
                    .background(if (esEste) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else Color.Transparent)
                    .clickable { if (esEste) onEste() else onIrAlChat(c) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarDelChat(c)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            conLoBuscado(c.nombre, consulta), fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        c.ultimo?.let {
                            Text(fechaDe(it.cuando), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    // Debajo: «Este chat · 12 hojas · lo último que se dijo».
                    val partes = buildList {
                        if (esEste) add(stringResource(R.string.ficha_este_chat))
                        val n = hojas[c.proyecto] ?: 0
                        if (n > 0) add(pluralStringResource(R.plurals.ficha_hojas, n, n))
                        c.ultimo?.let { resumenDe(it).lineSequence().firstOrNull { l -> l.isNotBlank() }?.trim() }
                            ?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                    if (partes.isNotEmpty()) Text(
                        partes.joinToString(" · "), fontSize = 13.sp,
                        color = if (esEste) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarDelChat(c: Conversacion) {
    CirculoDelChat(c.proyecto, c.nombre, 46.dp, MaterialTheme.colorScheme.primaryContainer) {
        if (c.proyecto == null) Icon(Icons.Filled.BookmarkBorder, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        // Sin foto, las iniciales, como el PC.
        else Text(
            iniciales(c.nombre), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** Las dos primeras iniciales de un nombre («Reforma del baño» → «RD»). */
internal fun iniciales(nombre: String): String =
    nombre.split(Regex("\\s+")).filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { nombre.trim().take(1).uppercase().ifEmpty { "·" } }

/** El nombre con lo buscado en negrita. Ver [SeccionesDelChat.tramoBuscado]. */
private fun conLoBuscado(nombre: String, consulta: String?): AnnotatedString {
    val t = SeccionesDelChat.tramoBuscado(nombre, consulta) ?: return AnnotatedString(nombre)
    return buildAnnotatedString {
        append(nombre.substring(0, t.first))
        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(nombre.substring(t.first, t.last + 1)) }
        append(nombre.substring(t.last + 1))
    }
}

// ---- La rejilla: Fotos, Vídeos y Dibujos ----

@Composable
private fun Rejilla(
    cosas: List<SeccionesDelChat.Cosa>,
    porId: Map<String, Mensaje>,
    onAbrir: (SeccionesDelChat.Cosa) -> Unit
) {
    val meses = remember(cosas, porId) { SeccionesDelChat.porMeses(cosas) { porId[it.id]?.cuando ?: 0L } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // De tres a cinco columnas según el ancho, como el PC: las medidas son fijas.
        val columnas = (maxWidth.value / 120f).toInt().coerceIn(3, 5)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnas),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            for (mes in meses) {
                item(key = "mes-${mes.anio}-${mes.mes}", span = { GridItemSpan(maxLineSpan) }) { RotuloDelMes(mes.anio, mes.mes) }
                items(mes.cosas, key = { "${it.id}#${it.foto ?: -1}" }) { c ->
                    val m = porId[c.id]
                    Box(Modifier.aspectRatio(1f).clickable { onAbrir(c) }) {
                        if (m != null) Celda(m, c.foto)
                    }
                }
            }
        }
    }
}

/** Lo que se lee de una miniatura: la imagen y, en un vídeo, lo que dura. */
private class Leida(val mapa: ImageBitmap?, val duraMs: Long?)

@Composable
private fun Celda(m: Mensaje, foto: Int?) {
    val contexto = LocalContext.current
    var leida by remember(m.id, foto) { mutableStateOf<Leida?>(null) }
    LaunchedEffect(m.id, foto, m.ruta) {
        leida = withContext(Dispatchers.IO) { leerMiniatura(contexto.filesDir, contexto, m, foto, 360) }
    }
    val mapa = leida?.mapa
    if (mapa != null) {
        Image(mapa, m.nombre.ifBlank { null }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        // Lo que carga: un recuadro neutro con su icono.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(
                when {
                    SeccionesDelChat.esVideo(m) -> Icons.Filled.Videocam
                    m.clase == Clase.DIBUJO -> Icons.Filled.Brush
                    else -> Icons.Filled.Image
                },
                null, tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    // Los vídeos llevan abajo a la izquierda el triángulo y lo que duran.
    if (SeccionesDelChat.esVideo(m)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Row(
                Modifier.padding(4.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(13.dp))
                leida?.duraMs?.let { Text(duracionLegible(it.toInt()), color = Color.White, fontSize = 11.sp) }
            }
        }
    }
}

/**
 * **La miniatura de una cosa de la ficha**, fuera del hilo de la pantalla: la foto reducida, el
 * fotograma de un vídeo (y lo que dura), la foto [foto] de una nota rica o el lienzo pintado.
 */
private fun leerMiniatura(filesDir: File, contexto: android.content.Context, m: Mensaje, foto: Int?, lado: Int): Leida = runCatching {
    when {
        foto != null -> {
            val f = SeccionesDelChat.fotosDeLaNota(m.texto).getOrNull(foto)?.let { NotaRica.archivo(filesDir, it.enlace) }
            Leida(f?.takeIf { it.isFile }?.let { com.forge.pixpin.pin.ImageStore.cargarNitida(it.path, lado, lado.toLong() * lado * 2) }?.asImageBitmap(), null)
        }
        SeccionesDelChat.esVideo(m) -> {
            val ruta = m.ruta ?: return@runCatching Leida(null, null)
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ruta)
                val dura = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val cuadro = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, lado, lado)
                Leida(cuadro?.asImageBitmap(), dura)
            } finally {
                runCatching { r.release() }
            }
        }
        m.clase == Clase.DIBUJO -> {
            val dibujo = m.referencia ?: return@runCatching Leida(null, null)
            val escena = com.forge.pixpin.motor.ExcalidrawStore.cargar(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(contexto, dibujo))
                ?: return@runCatching Leida(null, null)
            val caja = escena.contenidoVisible.takeIf { it.isNotEmpty() }?.let { com.forge.pixpin.motor.getCommonBounds(it) }
            val escala = caja?.let { escalaParaLado(it.width, it.height, lado) } ?: 0.5
            val mapa: Bitmap? = com.forge.pixpin.motor.DrawExport.aBitmap(escena, escala) { id ->
                escena.files[id]?.path?.let { com.forge.pixpin.pin.ImageStore.cargarNitida(it, lado, lado.toLong() * lado * 2) }
            }
            Leida(mapa?.asImageBitmap(), null)
        }
        else -> Leida(m.ruta?.let { com.forge.pixpin.pin.ImageStore.cargarNitida(it, lado, lado.toLong() * lado * 2) }?.asImageBitmap(), null)
    }
}.getOrElse { Leida(null, null) }

// ---- La previa ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Previa(
    cosas: List<SeccionesDelChat.Cosa>,
    actual: Int,
    porId: Map<String, Mensaje>,
    nombre: String,
    fechaDe: (Long) -> String,
    onCambiar: (Int) -> Unit,
    onVolver: () -> Unit,
    onVerEnElChat: (String) -> Unit,
    onAbrir: (SeccionesDelChat.Cosa) -> Unit
) {
    BackHandler(onBack = onVolver)
    val pagina = rememberPagerState(initialPage = actual) { cosas.size }
    // La lista cambió por debajo (sincronización, borrado): el pager sigue a la misma cosa.
    LaunchedEffect(actual) { if (pagina.currentPage != actual) pagina.scrollToPage(actual) }
    LaunchedEffect(pagina.currentPage) { if (pagina.currentPage != actual) onCambiar(pagina.currentPage) }
    val contexto = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pagina, Modifier.fillMaxSize(), key = { k -> cosas.getOrNull(k)?.let { "${it.id}#${it.foto ?: -1}" } ?: "fin-$k" }) { k ->
            val c = cosas.getOrNull(k)
            val m = c?.let { porId[it.id] }
            var leida by remember(c) { mutableStateOf<Leida?>(null) }
            LaunchedEffect(c) {
                if (m != null) leida = withContext(Dispatchers.IO) { leerMiniatura(contexto.filesDir, contexto, m, c.foto, 2048) }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val mapa = leida?.mapa
                // La foto entera sobre el fondo oscuro, con sus bandas.
                if (mapa != null) Image(mapa, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.Image, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
                if (m != null && SeccionesDelChat.esVideo(m)) {
                    Box(
                        Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).clickable { onAbrir(c) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.ficha_abrir), tint = Color.White, modifier = Modifier.size(40.dp)) }
                }
            }
        }
        val c = cosas.getOrNull(actual)
        val m = c?.let { porId[it.id] }
        // Arriba: volver, el nombre con «3 de 24 · fecha», «Ver en el chat» y «Abrir».
        Row(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onVolver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(
                    m?.nombre?.ifBlank { null } ?: nombre, color = Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.ficha_posicion, actual + 1, cosas.size) + (m?.let { " · " + fechaDe(it.cuando) } ?: ""),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1
                )
            }
            if (c != null) {
                TextButton(onClick = { onVerEnElChat(c.id) }) {
                    Text(stringResource(R.string.ficha_ver_en_el_chat), color = Color.White, fontSize = 13.sp)
                }
                TextButton(onClick = { onAbrir(c) }) {
                    Text(stringResource(R.string.ficha_abrir), color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

// ---- Las filas: Archivos, Enlaces, Voz y Fijados ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Filas(
    seccion: Seccion,
    cosas: List<SeccionesDelChat.Cosa>,
    porId: Map<String, Mensaje>,
    fechaDe: (Long) -> String,
    onAbrir: (Mensaje) -> Unit,
    onAbrirEnlace: (String) -> Unit,
    onVerEnElChat: (String) -> Unit
) {
    val meses = remember(cosas, porId) { SeccionesDelChat.porMeses(cosas.mapNotNull { porId[it.id] }) { it.cuando } }
    val suena by Reproductor.estado.collectAsState()
    val contexto = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        for (mes in meses) {
            item(key = "mes-${mes.anio}-${mes.mes}") { RotuloDelMes(mes.anio, mes.mes) }
            items(mes.cosas, key = { it.id }) { m ->
                val enlace = remember(m.texto) { if (seccion == Seccion.ENLACES) primerEnlace(m.texto) else null }
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                when {
                                    enlace != null -> onAbrirEnlace(enlace.url)
                                    seccion == Seccion.VOZ -> m.ruta?.let { Reproductor.alternar(it, tituloDeAudio(contexto, m)) }
                                    seccion == Seccion.FIJADOS -> onVerEnElChat(m.id)
                                    else -> onAbrir(m)
                                }
                            },
                            // Mantener pulsado lleva al mensaje, como «Ver en el chat».
                            onLongClick = { onVerEnElChat(m.id) }
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (seccion) {
                        Seccion.ARCHIVOS -> FilaDeArchivo(m, fechaDe)
                        Seccion.ENLACES -> FilaDeEnlace(m, enlace)
                        Seccion.VOZ -> FilaDeVoz(m, fechaDe, sonando = suena.sonando && suena.ruta == m.ruta && m.ruta != null)
                        else -> FilaFijada(m, fechaDe)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * **El color de la chapa de una extensión**, por familias, como `color_de_extension` del PC: los
 * PDF rojos, los textos azules, las hojas de cálculo verdes, las presentaciones naranjas, los
 * vídeos morados, el audio rosa, los comprimidos grises y los planos turquesa.
 */
internal fun colorDeExtension(ext: String): Long = when (ext.lowercase()) {
    "pdf" -> 0xFFE5534B
    "doc", "docx", "odt", "rtf", "txt", "md", "epub" -> 0xFF3B82F6
    "xls", "xlsx", "ods", "csv" -> 0xFF22A06B
    "ppt", "pptx", "odp", "key" -> 0xFFF08C2E
    in SeccionesDelChat.EXTENSIONES_DE_VIDEO -> 0xFF8B5CF6
    "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus" -> 0xFFE0569B
    "zip", "rar", "7z", "tar", "gz" -> 0xFF7A8594
    "dwg", "dxf", "ifc", "rvt", "obj", "stl", "skp" -> 0xFF14A3B8
    else -> 0xFF64748B
}

@Composable
private fun Chapa(texto: String, color: Color, letra: Int = 12) {
    Box(Modifier.size(46.dp).background(color, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Text(texto, color = Color.White, fontSize = letra.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun FilaDeArchivo(m: Mensaje, fechaDe: (Long) -> String) {
    val nombre = m.nombre.ifBlank { m.ruta?.substringAfterLast('/').orEmpty() }
    val ext = nombre.substringAfterLast('.', "").take(4)
    Chapa(ext.uppercase().ifEmpty { "·" }, Color(colorDeExtension(ext)))
    Column(Modifier.padding(start = 12.dp)) {
        Text(nombre, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val peso = tamanoLegible(m.bytes.takeIf { it > 0 } ?: m.ruta?.let { File(it).length() } ?: 0L)
        Text(
            listOf(peso, fechaDe(m.cuando)).filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
        )
    }
}

@Composable
private fun FilaDeEnlace(m: Mensaje, enlace: Enlace?) {
    val sitio = enlace?.donde.orEmpty()
    Chapa(sitio.take(1).uppercase().ifEmpty { "·" }, MaterialTheme.colorScheme.primary, letra = 18)
    Column(Modifier.padding(start = 12.dp)) {
        Text(sitio, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        enlace?.let {
            Text(it.url, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Lo que decía el mensaje, sin la dirección.
        val dicho = enlace?.let { m.texto.replace(it.url, "") }?.trim().orEmpty()
        if (dicho.isNotEmpty()) Text(
            NotaRica.sinRutasDeFotos(dicho), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FilaDeVoz(m: Mensaje, fechaDe: (Long) -> String, sonando: Boolean) {
    Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(
            if (sonando) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(R.string.ficha_reproducir), tint = MaterialTheme.colorScheme.onPrimary
        )
    }
    Column(Modifier.padding(start = 12.dp)) {
        Text(
            m.nombre.removeSuffix(".m4a").ifBlank { stringResource(R.string.guardados_voz) },
            fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            listOfNotNull(m.duracionMs.takeIf { it > 0 }?.let { duracionLegible(it) }, fechaDe(m.cuando)).joinToString(" · "),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
        )
    }
}

@Composable
private fun FilaFijada(m: Mensaje, fechaDe: (Long) -> String) {
    Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.PushPin, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Column(Modifier.padding(start = 12.dp)) {
        Text(
            com.forge.pixpin.ui.vistazo(m).ifBlank { m.nombre }, fontSize = 15.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Text(fechaDe(m.cuando), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
