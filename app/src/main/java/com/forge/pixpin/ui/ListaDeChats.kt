package com.forge.pixpin.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Conversacion
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesActivity
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.conversaciones
import com.forge.pixpin.lecciones.LeccionesStore
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.ui.theme.BotonRedondo
import com.forge.pixpin.ui.theme.Cristal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * **Los chats, como una app de mensajería** (4-oct-2026, pedido del usuario): el botón de chat de
 * Proyectos ya no entra directo en el chat del proyecto, sino que **convierte la pantalla en la
 * lista de chats** —Mensajes guardados arriba y cada proyecto por su último mensaje, como
 * WhatsApp o Telegram—. El mismo sitio pasa a ser «Proyectos» y devuelve a las tarjetas. El
 * chat de un proyecto concreto sigue a un deslizamiento de su tarjeta.
 *
 * Las lecciones no cuentan como último mensaje: viven en su sección ([LeccionesStore.sinLecciones]).
 */
@Composable
internal fun ListaDeChats(proyectos: List<Proyecto>, onProyectos: () -> Unit) {
    val contexto = LocalContext.current
    BackHandler(onBack = onProyectos)
    val cambios by MensajesStore.cambios.collectAsState()
    var mensajes by remember { mutableStateOf<List<Mensaje>?>(null) }
    LaunchedEffect(cambios) {
        mensajes = withContext(Dispatchers.IO) {
            runCatching { LeccionesStore.sinLecciones(MensajesStore(contexto).leer()) }.getOrDefault(emptyList())
        }
    }
    val general = contexto.getString(R.string.guardados_titulo)
    val charlas = remember(mensajes, proyectos) {
        mensajes?.let { conversaciones(it, proyectos.filter { p -> !p.archivado }.associate { p -> p.id to p.nombre }, general) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text(
                "Chats", style = MaterialTheme.typography.titleLarge, color = Cristal.tinta,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp)
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
                items(charlas.orEmpty(), key = { it.proyecto ?: "general" }) { c -> Fila(c) { abrir(contexto, c) } }
            }
        }
        // El mismo botón, transformado: ahora lleva de vuelta a los proyectos.
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BotonRedondo(Icons.Filled.Folder, "Proyectos", onProyectos, tamano = 56.dp, puesto = true)
        }
    }
}

private fun abrir(contexto: android.content.Context, c: Conversacion) {
    if (c.proyecto != null) MensajesActivity.abrirChatDe(contexto, c.proyecto, c.nombre)
    else contexto.startActivity(Intent(contexto, MensajesActivity::class.java))
}

@Composable
private fun Fila(c: Conversacion, alTocar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = alTocar).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(50.dp).background(Cristal.boton, CircleShape), contentAlignment = Alignment.Center) {
            if (c.proyecto == null) Icon(Icons.Filled.BookmarkBorder, null, tint = Cristal.tinta)
            else Text(c.nombre.trim().take(1).uppercase().ifBlank { "·" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Cristal.tinta)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.nombre, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Cristal.tinta,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                c.ultimo?.let { Text(hora(it.cuando), fontSize = 12.sp, color = Cristal.tinta.copy(alpha = 0.6f)) }
            }
            Text(
                c.ultimo?.let { vistazo(it) } ?: "Sin mensajes",
                fontSize = 14.sp, color = Cristal.tinta.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** La línea de debajo, como en cualquier app de mensajes: el texto, o qué es el archivo. */
internal fun vistazo(m: Mensaje): String = when (m.clase) {
    Clase.VOZ -> "🎙 " + m.nombre.removeSuffix(".m4a").ifBlank { "Nota de voz" }
    Clase.IMAGEN -> "🖼 " + m.texto.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { "Foto" }
    Clase.DIBUJO, Clase.PAGINA -> "✏️ " + m.nombre.ifBlank { "Lienzo" }
    Clase.TABLA -> "📊 " + m.nombre.ifBlank { "Tabla" }
    Clase.CROQUIS -> "🧊 " + m.nombre.ifBlank { "Croquis 3D" }
    Clase.PROYECTO -> "📁 " + m.nombre.ifBlank { "Proyecto" }
    Clase.MINIAPP -> "☑️ " + m.texto.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim('#', ' ')
    Clase.ARCHIVO -> "📄 " + m.nombre.ifBlank { "Archivo" }
    Clase.NOTA -> m.texto.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
}

/** La hora si es de hoy, «ayer», o el día. */
private fun hora(ms: Long): String {
    val hoy = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = ms }
    val mismoAno = hoy.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    val dias = hoy.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
    return when {
        mismoAno && dias == 0 -> String.format(java.util.Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        mismoAno && dias == 1 -> "ayer"
        else -> String.format(java.util.Locale.ROOT, "%d/%d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
    }
}
