package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.ui.theme.PixPinTheme

/**
 * **La letra de una canción o el texto de un audio, a pantalla completa, para seguirlo.**
 *
 * Letra grande, que se puede agrandar y achicar, y abajo el reproductor con sus mandos:
 * es la pantalla para cantar una canción o releer una nota de voz mientras suena.
 *
 * - Cada párrafo de una transcripción lleva **su minuto** (`[1:23] …`): tocarlo salta ahí
 *   en el audio, y mientras suena el párrafo por el que va se resalta y se mantiene a la vista.
 * - Las **banderitas** ([Mensaje.marcas]): la bandera de arriba deja una en el punto en
 *   que va el audio; salen como fichas, un toque vuelve a ese punto y mantener pulsado
 *   la quita.
 * - El lápiz abre el editor de notas; lo guardado vuelve al mensaje (o a su hoja).
 *
 * Lo pidió el usuario (5-sep-2026).
 */
class LetraActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EL_MENSAJE).orEmpty()
        aPantallaCompleta()
        setContent { PixPinTheme { Pantalla(id) } }
    }

    /** Un párrafo del texto: en qué milisegundo empieza (o -1) y qué dice. */
    private class Parrafo(val ms: Int, val texto: String)

    @Composable
    private fun Pantalla(id: String) {
        val almacen = remember { MensajesStore(this) }
        var version by remember { mutableIntStateOf(0) }
        // Se vuelve a leer cada vez que el almacén cambia: al volver del editor, la letra nueva.
        LaunchedEffect(Unit) { MensajesStore.cambios.collect { version++ } }
        val m = remember(version) { almacen.leer().firstOrNull { it.id == id } }
        var tamano by remember { mutableIntStateOf(22) }
        val estado by Reproductor.estado.collectAsState()
        val titulo = m?.let { tituloDeAudio(this, it) } ?: ""
        val parrafos = remember(m?.transcripcion) { parrafosDe(m?.transcripcion) }
        val esteAudio = m?.ruta != null && estado.ruta == m.ruta
        // El párrafo por el que va el audio: el último cuyo minuto ya se pasó.
        val actual = if (esteAudio) parrafos.indexOfLast { it.ms in 0..estado.posicionMs } else -1

        // Un `Surface` y no un `background`: el `Surface` es el que pone el color del texto
        // a juego con el fondo. Con solo el fondo, de noche el texto salía negro sobre negro.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.cancel)) }
                    Text(titulo, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = { if (tamano > 14) tamano -= 2 }) { Icon(Icons.Filled.TextDecrease, contentDescription = null) }
                    IconButton(onClick = { if (tamano < 40) tamano += 2 }) { Icon(Icons.Filled.TextIncrease, contentDescription = null) }
                    // La banderita: una marca donde va el audio.
                    IconButton(
                        enabled = m?.ruta != null,
                        onClick = {
                            val ms = if (esteAudio) estado.posicionMs else 0
                            Thread { almacen.actualizar(id) { it.copy(marcas = (it.marcas + ms).distinct().sorted()) } }.start()
                        }
                    ) { Icon(Icons.Filled.Flag, contentDescription = getString(R.string.letra_marcar)) }
                    IconButton(onClick = { m?.let { editar(it) } }) { Icon(Icons.Filled.Edit, contentDescription = getString(R.string.letra_editar)) }
                }
                if (m != null && m.marcas.isNotEmpty()) {
                    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                        itemsIndexed(m.marcas) { _, ms ->
                            Text(
                                "⚑ " + Transcriptor.marcaDeTiempo(ms),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .combinedClickable(
                                        onClick = { saltarA(m, ms) },
                                        onLongClick = { Thread { almacen.actualizar(id) { it.copy(marcas = it.marcas - ms) } }.start() }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (parrafos.isEmpty()) {
                        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(getString(R.string.letra_vacia), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { m?.let { editar(it) } }) { Text(getString(R.string.letra_pegar)) }
                        }
                    } else {
                        val lista = rememberLazyListState()
                        // La vista sigue al audio: el párrafo que suena, a la vista.
                        LaunchedEffect(actual) { if (actual >= 0) runCatching { lista.animateScrollToItem(actual) } }
                        LazyColumn(state = lista, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(parrafos) { i, p ->
                                val activo = i == actual
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (activo) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                                        .combinedClickable(enabled = p.ms >= 0 && m?.ruta != null, onClick = { if (m != null) saltarA(m, p.ms) })
                                        .padding(horizontal = 20.dp, vertical = 8.dp)
                                ) {
                                    if (p.ms >= 0) {
                                        Text(
                                            Transcriptor.marcaDeTiempo(p.ms),
                                            fontSize = (tamano * 0.6f).sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        p.texto,
                                        fontSize = tamano.sp,
                                        lineHeight = (tamano * 1.45f).sp,
                                        fontWeight = if (activo) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
                if (m?.ruta != null) {
                    // Con el audio cargado aunque no suene: los mandos a mano desde el primer momento.
                    LaunchedEffect(m.ruta) {
                        if (Reproductor.estado.value.ruta != m.ruta) Reproductor.cargar(m.ruta, titulo, arrancar = false)
                    }
                    BarraDelReproductor()
                }
            }
        }
    }

    /** A ese punto del audio, cargándolo si hacía falta, y sonando. */
    private fun saltarA(m: Mensaje, ms: Int) {
        val ruta = m.ruta ?: return
        if (Reproductor.estado.value.ruta != ruta) Reproductor.cargar(ruta, tituloDeAudio(this, m), arrancar = false)
        val total = Reproductor.estado.value.duracionMs
        if (total > 0) Reproductor.irA(ms.toFloat() / total)
        if (!Reproductor.estado.value.sonando) Reproductor.seguir()
    }

    /** Del texto guardado a párrafos: los que llevan `[m:ss]` delante saben a dónde saltar. */
    private fun parrafosDe(texto: String?): List<Parrafo> {
        if (texto.isNullOrBlank()) return emptyList()
        return texto.split(Regex("\n\\s*\n")).mapNotNull { bloque ->
            val limpio = sinMarcas(bloque)
            if (limpio.isBlank()) return@mapNotNull null
            val con = Transcriptor.tiempoDe(limpio)
            if (con != null) Parrafo(con.first, con.second.trim()) else Parrafo(-1, limpio)
        }
    }

    private fun editar(m: Mensaje) {
        // Con hoja en un proyecto, se edita la hoja (que es el `.md` de verdad); si no, lo
        // escrito vuelve al mensaje.
        val app = application as? com.forge.pixpin.PixPinApp
        val hoja = m.hojaDelTexto
        val proyecto = m.proyecto?.let { app?.proyectos?.porId(it) }
        val laHoja = if (hoja != null) proyecto?.hojas?.firstOrNull { it.id == hoja } else null
        if (laHoja != null && proyecto != null) {
            com.forge.pixpin.ui.MarkdownEditorActivity.abrir(this, laHoja.id, laHoja.nota ?: "", desdeProyecto = proyecto.id, mensaje = m.id)
        } else {
            com.forge.pixpin.ui.MarkdownEditorActivity.abrir(this, "letra-${m.id}", m.transcripcion ?: "", mensaje = m.id)
        }
    }

    /** Lo escrito sin las marcas de Markdown que no aportan cantando: `#`, `**`, `__`, `![audio]`. */
    private fun sinMarcas(texto: String): String =
        texto.lines()
            .filterNot { it.trimStart().startsWith("![") }
            .joinToString("\n") { linea -> linea.trimStart('#', ' ').replace("**", "").replace("__", "") }
            .trim()

    companion object {
        private const val EL_MENSAJE = "letra_mensaje"

        fun abrir(context: Context, mensaje: String) {
            context.startActivity(
                Intent(context, LetraActivity::class.java)
                    .putExtra(EL_MENSAJE, mensaje)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** Cómo se llama un audio: su nombre de archivo si lo trae, o «Nota de voz» con su fecha. */
fun tituloDeAudio(context: Context, m: Mensaje): String =
    m.nombre.substringBeforeLast('.').ifBlank {
        context.getString(R.string.guardados_voz) + " · " +
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(m.cuando))
    }
