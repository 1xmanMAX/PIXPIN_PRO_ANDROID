package com.forge.pixpin.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motormd.Incrustados
import com.forge.pixpin.sincro.Codigos
import java.io.File

/**
 * **Meter en una nota lo que ya está en PixPin**, como el menú `/` del PC: una hoja de un proyecto
 * como página viva o como enlace, y un mensaje del chat (la nota de voz con su transcripción, lo
 * demás como su burbuja). Aquí se escribe el Markdown; quien lo pinta es [com.forge.pixpin.motormd.MarkdownText].
 */
object ElegirParaLaNota {

    fun claseDe(h: Hoja) = when {
        h.tabla != null -> "Tabla"; h.nota != null -> "Nota"; h.croquis != null -> "Croquis 3D"
        h.pagina != null -> "Página ${h.pagina!! + 1}"; else -> "Lienzo"
    }

    fun nombreDe(h: Hoja) = h.nombre.ifBlank { claseDe(h) }

    /** El enlace a la hoja: `[Nombre](pixpin:hoja=<proyecto>/<hoja>)`. */
    fun enlace(p: Proyecto, h: Hoja) = Incrustados.escribirEnlaceAHoja(nombreDe(h), Codigos.unico(p), Codigos.unico(h))

    /**
     * La página viva: pinta la hoja ya en `notas/vivo-<código>.png` y devuelve su `![Nombre](ruta)`,
     * o null si esa hoja no se puede pintar aquí (tablas, notas y croquis: van como enlace).
     */
    suspend fun paginaViva(context: Context, p: Proyecto, h: Hoja): String? {
        val codigo = Codigos.unico(h)
        val png = File(File(context.filesDir, "notas").apply { mkdirs() }, "vivo-$codigo.png")
        val mapa: Bitmap = PaginasVivas.pintarAhora(context, p, h) ?: return null
        val tmp = File(png.parentFile, png.name + ".tmp")
        tmp.outputStream().use { mapa.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(png)) { tmp.copyTo(png, overwrite = true); tmp.delete() }
        return "![${nombreDe(h).replace("]", ")").replace("[", "(")}](${png.absolutePath})"
    }

    /**
     * Un mensaje del chat en la nota: una nota de voz va **con su audio y su transcripción** debajo
     * (`[m:ss] …`, lo que ya salta en el audio); lo demás, como enlace a su burbuja.
     */
    fun delChat(m: Mensaje, proyecto: Proyecto?): String {
        val p = proyecto?.let { Codigos.unico(it) } ?: "guardados"
        if (m.clase == Clase.VOZ && m.ruta != null) {
            val texto = m.transcripcion?.trim().orEmpty()
            return "![Nota de voz](${m.ruta})" + if (texto.isNotEmpty()) "\n\n$texto" else ""
        }
        val dice = when (m.clase) {
            Clase.NOTA -> m.texto.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            else -> m.nombre.ifBlank { m.texto }
        }.ifBlank { "Mensaje" }
        return Incrustados.escribirEnlaceAMensaje(dice, p, Codigos.unico(m))
    }
}

/** Elegir una hoja: los proyectos de más a menos recientes, cada uno con sus hojas. */
@Composable
fun DialogoDeHojas(titulo: String, onCerrar: () -> Unit, onElegir: (Proyecto, Hoja) -> Unit) {
    val app = LocalContext.current.applicationContext as PixPinApp
    val proyectos by app.proyectos.proyectos.collectAsState()
    var abierto by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(titulo) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                proyectos.filter { !it.archivado && it.hojas.isNotEmpty() }.sortedByDescending { it.tocado }.forEach { p ->
                    item(key = p.id) {
                        Text(
                            (if (abierto == p.id) "▾ " else "▸ ") + p.nombre,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().clickable { abierto = if (abierto == p.id) null else p.id }.padding(vertical = 10.dp)
                        )
                    }
                    if (abierto == p.id) items(p.hojas, key = { p.id + "/" + it.id }) { h ->
                        Row(Modifier.fillMaxWidth().clickable { onElegir(p, h) }.padding(start = 18.dp, top = 8.dp, bottom = 8.dp)) {
                            Text(ElegirParaLaNota.nombreDe(h), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ElegirParaLaNota.claseDe(h), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}

/** Elegir un mensaje del chat de [proyecto] (o del general), del más nuevo al más viejo. */
@Composable
fun DialogoDelChat(proyecto: String?, onCerrar: () -> Unit, onElegir: (Mensaje) -> Unit) {
    val ctx = LocalContext.current
    var mensajes by remember { mutableStateOf<List<Mensaje>?>(null) }
    LaunchedEffect(proyecto) {
        mensajes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.forge.pixpin.guardados.MensajesStore(ctx).leer() }.getOrDefault(emptyList())
                .filter { it.proyecto == proyecto && !it.enBuzon }
                .sortedByDescending { it.cuando }
        }
    }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Del chat") },
        text = {
            val lista = mensajes
            if (lista == null) CircularProgressIndicator()
            else if (lista.isEmpty()) Text("Este chat no tiene mensajes.")
            else LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(lista, key = { it.id }) { m ->
                    val icono = when (m.clase) {
                        Clase.VOZ -> "🎤"; Clase.IMAGEN -> "🖼"; Clase.ARCHIVO -> "📎"; Clase.DIBUJO, Clase.PAGINA -> "✏️"
                        Clase.TABLA -> "📊"; else -> "💬"
                    }
                    val dice = when (m.clase) {
                        Clase.NOTA -> m.texto
                        Clase.VOZ -> m.nombre.ifBlank { "Nota de voz" } + (m.transcripcion?.let { " — $it" } ?: "")
                        else -> m.nombre.ifBlank { m.texto }
                    }
                    Text(
                        "$icono  $dice", maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onElegir(m) }.padding(vertical = 9.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}
