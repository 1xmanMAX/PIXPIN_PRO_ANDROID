package com.forge.pixpin.guardados

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import kotlinx.coroutines.delay

/*
 * **El karaoke de una canción en «Estudiar la clase»** (29-oct-2026, como el PC de 058ae2b7, a
 * petición del usuario): **sin la tira de arriba** (la flecha y los botones de cristal sobre el cartel),
 * con la portada, el título y el artista de las etiquetas (sin etiquetas, nada: nunca el nombre del
 * fichero) y a su lado **A−, A+, el reloj** (sincronizar a toques), **los subtítulos** («Sincronizar
 * sola», [SincronizarSola]), **el lápiz**, el ⋮ (poner los tiempos con la propuesta, copiar en LRC y el
 * tema) y una **✕** para volver. Sin botón de copiar a la vista (el PC lo quitó; en ⋮ sigue).
 *
 * La letra va **entera y se mueve** ([LetraQueSigue]): la línea que suena grande y en color a un tercio
 * de la altura, las cantadas apagadas; mientras suena la vista la sigue y moverla a mano lo para 6 s
 * ([SeguirLaLetra]). Lo que se arma aquí lo usa [EstudiarClaseActivity] solo con una canción.
 */

/**
 * **La cabecera del karaoke**: la portada con el título y el artista de sus etiquetas, y los botones.
 * En un teléfono de pie, los botones van en una fila debajo; a lo ancho, al lado del título.
 */
@Composable
internal fun CabeceraDelKaraoke(
    portada: Bitmap?,
    titulo: String?,
    debajo: String?,
    conAudio: Boolean,
    sincronizandoSola: Boolean,
    sincronizandoAToques: Boolean,
    alMenos: () -> Unit,
    alMas: () -> Unit,
    alReloj: () -> Unit,
    alSola: () -> Unit,
    alLapiz: () -> Unit,
    alVolver: () -> Unit,
    menu: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val contexto = LocalContext.current
    @Composable
    fun Botones() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotonDeTexto("A", 12, contexto.getString(R.string.letra_mas_pequena), alMenos)
            BotonDeTexto("A", 18, contexto.getString(R.string.letra_mas_grande), alMas)
            IconButton(onClick = alReloj, enabled = conAudio, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Timer, contexto.getString(R.string.letra_sincronizar),
                    tint = if (sincronizandoAToques) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current
                )
            }
            // Encendido mientras se sincroniza sola: pulsarlo otra vez la para.
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (sincronizandoSola) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable(enabled = conAudio, onClickLabel = contexto.getString(R.string.letra_sola), onClick = alSola),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Subtitles, contexto.getString(R.string.letra_sola),
                    tint = when {
                        sincronizandoSola -> MaterialTheme.colorScheme.primary
                        conAudio -> androidx.compose.material3.LocalContentColor.current
                        else -> androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.38f)
                    }
                )
            }
            IconButton(onClick = alLapiz, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Edit, contexto.getString(R.string.letra_editar_titulo)) }
            menu()
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val ancho = maxWidth.value >= 600f
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(if (ancho) 64.dp else 56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (portada != null) Image(portada.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                // Solo lo de las etiquetas: sin ellas, nada (nunca el nombre del fichero).
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 6.dp)) {
                    if (!titulo.isNullOrBlank()) Text(titulo, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!debajo.isNullOrBlank()) Text(
                        debajo, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (ancho) Botones()
                IconButton(onClick = alVolver, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Close, contexto.getString(R.string.letra_volver)) }
            }
            if (!ancho) Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End) { Botones() }
        }
    }
}

/** Una «A» pequeña o grande: la letra más pequeña o más grande. */
@Composable
private fun BotonDeTexto(letra: String, tam: Int, nombre: String, al: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = nombre, onClick = al),
        contentAlignment = Alignment.Center
    ) { Text(letra, fontSize = tam.sp, fontWeight = FontWeight.SemiBold) }
}

/**
 * **El ⋮ del karaoke**: lo que no tiene botón a la vista. «Poner los tiempos a la letra» (con la
 * propuesta para revisar antes de guardar), «Copiar como LRC» (el PC solo quitó el botón visible) y
 * claro u oscuro (iba en la tira de arriba).
 */
@Composable
internal fun MenuDelKaraoke(
    conAudio: Boolean,
    poniendoTiempos: Boolean,
    conTiempos: Boolean,
    oscuro: Boolean,
    alPonerTiempos: () -> Unit,
    alCopiarLrc: () -> Unit,
    alCambiarTema: () -> Unit
) {
    val contexto = LocalContext.current
    var abierto by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { abierto = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.MoreVert, contexto.getString(R.string.estudiar_mas)) }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            DropdownMenuItem(
                text = { Text(contexto.getString(R.string.letra_poner_tiempos)) },
                leadingIcon = { Icon(Icons.Filled.Hearing, null) },
                enabled = conAudio && !poniendoTiempos,
                onClick = { abierto = false; alPonerTiempos() }
            )
            if (conTiempos) DropdownMenuItem(
                text = { Text(contexto.getString(R.string.letra_copiar_lrc)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { abierto = false; alCopiarLrc() }
            )
            DropdownMenuItem(
                text = { Text(contexto.getString(if (oscuro) R.string.letra_tema_claro else R.string.letra_tema_oscuro)) },
                leadingIcon = { Icon(if (oscuro) Icons.Filled.LightMode else Icons.Filled.DarkMode, null) },
                onClick = { abierto = false; alCambiarTema() }
            )
        }
    }
}

/**
 * **La pastilla de lo que se está haciendo** bajo la cabecera: «Sincronizando la letra… 40 %», con lo
 * hecho como una barra dentro.
 */
@Composable
internal fun PastillaDelKaraoke(texto: String, avance: Float) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        val color = MaterialTheme.colorScheme.primary
        val hecho = avance.coerceIn(0f, 1f)
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
                // Lo hecho, de fondo: tan ancho como lo que lleva.
                .drawBehind { drawRect(color.copy(alpha = 0.22f), size = Size(size.width * hecho, size.height)) }
        ) {
            Text(texto, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

/**
 * **La letra que se canta**, centrada y entera: la línea que suena ([actual]) grande y en color a un
 * tercio de la altura, las cantadas apagadas al 40 % y las que vienen al 75 %. Se mueve arrastrando
 * (o con la rueda), con tiempos y sin ellos.
 *
 * **Seguir a la canción** ([SeguirLaLetra]): mientras suena, la vista va a la línea que suena; moverla
 * a mano lo para 6 s o hasta tocar una línea, y luego vuelve sola. Parada, se queda donde se dejó.
 * Lo que se mueve a mano se nota en la conexión de desplazamiento anidado (solo los gestos del
 * usuario pasan por ahí; los saltos de seguir a la canción, no), así que nada lo pisa mientras dura.
 */
@Composable
internal fun LetraQueSigue(
    lineas: List<EstudiarClase.Linea>,
    actual: Int?,
    sonando: Boolean,
    tamano: Int,
    alTocar: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val lista = rememberLazyListState()
    var aMano by remember { mutableStateOf<Long?>(null) }
    val conexion = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) aMano = SystemClock.uptimeMillis()
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // La vista sigue moviéndose tras soltar: los 6 s cuentan desde que se para.
                if (aMano != null) aMano = SystemClock.uptimeMillis()
                return Velocity.Zero
            }
        }
    }
    // Pasado el rato (y sonando), vuelve a seguir. Parada no hay nada que esperar.
    LaunchedEffect(aMano, sonando) {
        val t = aMano ?: return@LaunchedEffect
        val falta = SeguirLaLetra.falta(t, SystemClock.uptimeMillis(), sonando) ?: return@LaunchedEffect
        delay(falta)
        if (aMano == t) aMano = null
    }
    // Siguiendo: la línea que suena a un tercio de la altura (el relleno de arriba la deja ahí).
    LaunchedEffect(actual, aMano) {
        val i = actual ?: return@LaunchedEffect
        if (aMano == null && !lista.isScrollInProgress) runCatching { lista.animateScrollToItem(i) }
    }
    BoxWithConstraints(modifier) {
        val alto = maxHeight
        LazyColumn(
            state = lista,
            modifier = Modifier.fillMaxSize().nestedScroll(conexion),
            contentPadding = PaddingValues(top = alto / 3, bottom = alto * 2 / 3),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(lineas) { k, l ->
                val activa = k == actual
                val cantada = actual != null && k < actual
                // Un renglón en blanco de una letra separa estrofas; un silencio del LRC, «♪».
                if (l.texto.isEmpty() && l.ms == null) Spacer(Modifier.height((tamano * 0.9f).dp))
                else Text(
                    l.texto.ifEmpty { "♪" },
                    fontSize = (if (activa) tamano * 1.15f else tamano.toFloat()).sp,
                    lineHeight = ((if (activa) tamano * 1.15f else tamano.toFloat()) * 1.4f).sp,
                    fontWeight = if (activa) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    color = when {
                        activa -> MaterialTheme.colorScheme.primary
                        cantada -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        actual != null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = l.ms != null) {
                            // Tocar una línea la lleva ahí y vuelve a seguir a la canción.
                            aMano = null
                            l.ms?.let(alTocar)
                        }
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** **Sin letra aún**: se dice, y cómo ponerla (escribirla o pegarla; o lo que se oye como letra). */
@Composable
internal fun SinLetraEnElKaraoke(avance: Float?, conAudio: Boolean, alEscribir: () -> Unit, alPonerTiempos: () -> Unit, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (avance != null) {
            Text(contexto.getString(R.string.letra_poniendo_tiempos, (avance * 100).toInt()), color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.LinearProgressIndicator(progress = { avance.coerceIn(0f, 1f) }, modifier = Modifier.padding(top = 10.dp).width(200.dp))
        } else {
            Text(contexto.getString(R.string.letra_sin_letra), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            androidx.compose.material3.TextButton(onClick = alEscribir) { Text(contexto.getString(R.string.letra_editar_titulo)) }
            if (conAudio) androidx.compose.material3.TextButton(onClick = alPonerTiempos) { Text(contexto.getString(R.string.letra_poner_tiempos)) }
        }
    }
}
