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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.launch
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
    // El chat «Asistente», fijo detrás de la general; si aún no existe, se crea al tocarlo.
    val delAsistente = remember(mensajes, proyectos) {
        mensajes?.let { com.forge.pixpin.asistente.ChatDelAsistente.buscar(proyectos, it)?.id }
            ?: com.forge.pixpin.asistente.ChatDelAsistente.SIN_CREAR
    }
    val charlas = remember(mensajes, proyectos) {
        mensajes?.let { conversaciones(it, proyectos.filter { p -> !p.archivado }.associate { p -> p.id to p.nombre }, general, delAsistente) }
    }
    val alcance = rememberCoroutineScope()
    // **Los chats compartidos en los que estoy**, para su chapa (25-oct-2026, del PC `e3f1d60`). Se
    // releen cada vez que cambia la lista de compartidos; sin ninguno, ni se mira la identidad.
    val versionDeCompartidos by com.forge.pixpin.sincro.CompartidosEnDisco.version.collectAsState()
    var compartidos by remember { mutableStateOf<Map<String, com.forge.pixpin.sincro.EnElChat>>(emptyMap()) }
    LaunchedEffect(versionDeCompartidos) {
        compartidos = withContext(Dispatchers.IO) {
            runCatching { com.forge.pixpin.guardados.chatsCompartidosDentro(contexto) }.getOrDefault(emptyMap())
        }
    }
    var eligiendoNuevo by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text(
                "Chats", style = MaterialTheme.typography.titleLarge, color = Cristal.tinta,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp)
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
                items(charlas.orEmpty(), key = { it.proyecto ?: "general" }) { c ->
                    Fila(c, esDelAsistente = c.proyecto == delAsistente, compartido = c.proyecto?.let { compartidos[it] }) {
                        if (c.proyecto == com.forge.pixpin.asistente.ChatDelAsistente.SIN_CREAR) alcance.launch {
                            val p = withContext(Dispatchers.IO) { com.forge.pixpin.asistente.Asistente.asegurarChat(contexto) }
                            MensajesActivity.abrirChatDe(contexto, p.id, p.nombre)
                        } else abrir(contexto, c)
                    }
                }
            }
        }
        // El mismo botón, transformado: ahora lleva de vuelta a los proyectos.
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BotonRedondo(Icons.Filled.Folder, "Proyectos", onProyectos, tamano = 56.dp, puesto = true)
            // **El «+» de los chats** (25-oct-2026, del PC `e3f1d60`): primero, normal o compartido.
            // El compartido nace ya en `compartidos.json` conmigo dentro y se abren sus personas para
            // invitar (Añadir, el código y el QR), como «Compartir» de siempre.
            Spacer(Modifier.width(12.dp))
            Box {
                BotonRedondo(Icons.Filled.Add, "Chat nuevo", { eligiendoNuevo = true }, tamano = 48.dp)
                com.forge.pixpin.guardados.MenuDeChatNuevo(eligiendoNuevo, { eligiendoNuevo = false }) { conPersonas ->
                    alcance.launch { chatNuevo(contexto, conPersonas) }
                }
            }
        }
    }
}

/**
 * **Un chat nuevo desde la lista** (25-oct-2026): el proyecto nuevo, como el «+» de Proyectos, y su
 * chat abierto. [conPersonas]: además compartido, y encima sus personas para invitar. Si compartir
 * falla, queda como chat normal y se avisa (`cc-nuevo-error` del PC).
 */
private suspend fun chatNuevo(contexto: android.content.Context, conPersonas: Boolean) {
    val app = contexto.applicationContext as com.forge.pixpin.PixPinApp
    val p = app.proyectos.nuevo(contexto.getString(R.string.proyecto_nuevo_nombre), System.currentTimeMillis())
    val compartido = conPersonas && withContext(Dispatchers.IO) {
        runCatching { com.forge.pixpin.guardados.compartirChatNuevo(contexto, p.id, p.nombre) }.isSuccess
    }
    MensajesActivity.abrirChatDe(contexto, p.id, p.nombre)
    if (compartido) com.forge.pixpin.sincro.ChatCompartidoActivity.abrir(contexto, p.id, p.nombre)
    else if (conPersonas) android.widget.Toast.makeText(contexto, "No se pudo compartir el chat nuevo: queda como chat normal", android.widget.Toast.LENGTH_LONG).show()
}

private fun abrir(contexto: android.content.Context, c: Conversacion) {
    if (c.proyecto != null) MensajesActivity.abrirChatDe(contexto, c.proyecto, c.nombre)
    else contexto.startActivity(Intent(contexto, MensajesActivity::class.java))
}

@Composable
private fun Fila(c: Conversacion, esDelAsistente: Boolean = false, compartido: com.forge.pixpin.sincro.EnElChat? = null, alTocar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = alTocar).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // El logo del proyecto si se le puso uno; si no, su inicial. Ver [com.forge.pixpin.guardados.LogoDelProyecto].
        val logo = com.forge.pixpin.guardados.logoDe(c.proyecto)
        Box(Modifier.size(50.dp)) {
            Box(Modifier.size(50.dp).clip(CircleShape).background(Cristal.boton, CircleShape), contentAlignment = Alignment.Center) {
                if (c.proyecto == null) Icon(Icons.Filled.BookmarkBorder, null, tint = Cristal.tinta)
                else if (esDelAsistente) Text("🤖", fontSize = 22.sp)
                else if (logo != null) androidx.compose.foundation.Image(
                    logo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                else Text(c.nombre.trim().take(1).uppercase().ifBlank { "·" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Cristal.tinta)
            }
            // **La chapa de compartido** (25-oct-2026): abajo a la derecha del avatar, con un aro del
            // color del fondo. Un chat normal no la lleva, ni uno del que salí.
            if (compartido != null) com.forge.pixpin.guardados.ChapaDeCompartido(
                MaterialTheme.colorScheme.background, Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp)
            )
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Delante del nombre, las dos personas y cuántas son si son más de una, como el
                // icono de grupo de Telegram delante del nombre del grupo.
                if (compartido != null) {
                    val verde = androidx.compose.ui.graphics.Color(com.forge.pixpin.sincro.EntrePersonas.VERDE_COMPARTIDO)
                    Icon(Icons.Filled.Groups, contentDescription = null, tint = verde, modifier = Modifier.size(16.dp))
                    if (compartido.personas > 1) Text("${compartido.personas}", fontSize = 12.sp, color = verde, modifier = Modifier.padding(start = 2.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(c.nombre, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Cristal.tinta,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                c.ultimo?.let { Text(hora(it.cuando), fontSize = 12.sp, color = Cristal.tinta.copy(alpha = 0.6f)) }
            }
            Text(
                c.ultimo?.let { vistazo(it) } ?: if (esDelAsistente) "Pregunta, pide un dibujo o una gráfica" else "Sin mensajes",
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
    // Una carpeta de páginas: su nombre, no su JSON (25-oct-2026). Ver [com.forge.pixpin.guardados.CarpetaDePaginas].
    Clase.MINIAPP -> if (com.forge.pixpin.guardados.CarpetaDePaginas.esCarpeta(m)) "📒 " + m.nombre.ifBlank { "Carpeta de páginas" }
    else "☑️ " + m.texto.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim('#', ' ')
    Clase.ARCHIVO -> "📄 " + m.nombre.ifBlank { "Archivo" }
    // Las fotos de una nota rica, como «🖼 alt» y no su ruta (25-oct-2026). Ver [com.forge.pixpin.guardados.NotaRica.sinRutasDeFotos].
    Clase.NOTA -> com.forge.pixpin.guardados.NotaRica.sinRutasDeFotos(m.texto).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
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
