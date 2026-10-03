package com.forge.pixpin.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.motormd.Comentarios
import com.forge.pixpin.motormd.Vivo
import com.forge.pixpin.motormd.trozoEn
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import com.forge.pixpin.sincro.Codigos
import java.io.File

/**
 * **Los comentarios de la nota que se está editando**: dónde están y cómo se guardan sin pisar
 * los que lleguen del PC mientras tanto. El formato y el anclaje son los de [Comentarios].
 *
 * Qué nota es se saca de su id: una hoja nota de un proyecto (código de la hoja), un mensaje
 * NOTA del chat o el `.md` de un mensaje (código del mensaje). Sin código —una nota suelta de un
 * pin—, la nota no se puede comentar: el archivo no tendría con qué viajar.
 */
class ComentariosDeNota private constructor(private val archivo: File, private val quien: Comentarios.Quien) {

    /** Lo que había en disco al abrir o tras el último guardado: la base para juntar. */
    private var base: Comentarios.Fichero = Comentarios.Fichero()
    /** Si el archivo existe pero no se entiende: se enseña y no se escribe encima. */
    var roto = false
        private set

    fun leer(): Comentarios.Fichero {
        val t = runCatching { if (archivo.isFile) archivo.readText() else "" }.getOrDefault("")
        val f = Comentarios.leer(t)
        roto = f == null
        base = f ?: Comentarios.Fichero()
        return base
    }

    /**
     * Guarda [mio] juntándolo con lo que haya ahora en disco y poniendo las anclas al día contra
     * [texto]. Devuelve lo que quedó, o null si no se pudo (archivo roto).
     */
    fun guardar(mio: Comentarios.Fichero, texto: String): Comentarios.Fichero? {
        if (roto) return null
        val ahora = runCatching { if (archivo.isFile) Comentarios.leer(archivo.readText()) else Comentarios.Fichero() }.getOrNull()
            ?: run { roto = true; return null }
        val junto = Comentarios.reanclar(Comentarios.fusionar(base, mio, ahora), texto).first
        // Sin comentarios y sin archivo, nada que escribir. Con archivo se escribe la lista vacía:
        // la sincronización no lleva borrados de archivos y el otro aparato lo devolvería.
        if (junto.comentarios.isEmpty() && !archivo.isFile) { base = junto; return junto }
        archivo.parentFile?.mkdirs()
        val tmp = File(archivo.parentFile, archivo.name + ".tmp")
        tmp.writeText(Comentarios.escribir(junto))
        if (!tmp.renameTo(archivo)) { tmp.copyTo(archivo, overwrite = true); tmp.delete() }
        base = junto
        return junto
    }

    fun quien() = quien

    companion object {
        /** Los comentarios de la nota [id], o null si esa nota no tiene código con el que viajar. */
        fun de(context: Context, id: String): ComentariosDeNota? {
            if (id.isBlank()) return null
            val app = context.applicationContext as? com.forge.pixpin.PixPinApp ?: return null
            val uid = app.proyectos.proyectos.value.asSequence().flatMap { it.hojas.asSequence() }
                .firstOrNull { it.id == id && it.nota != null }?.let { Codigos.unico(it) }
                ?: run {
                    val mensajes = com.forge.pixpin.guardados.MensajesStore(app).leer()
                    val m = mensajes.firstOrNull { it.id == id } ?: mensajes.firstOrNull { "md-${it.id}" == id }
                    m?.let { Codigos.unico(it) }
                }
                ?: return null
            val identidad = runCatching { com.forge.pixpin.sincro.IdentidadEnDisco(app.filesDir).leer().yo }.getOrNull()
            val quien = Comentarios.Quien(
                identidad?.nombre?.ifBlank { null } ?: android.os.Build.MODEL ?: "Teléfono",
                Codigos.deEsteAparato(app.filesDir).orEmpty()
            )
            return ComentariosDeNota(AnotacionesDelAdjunto.comentarios(app.filesDir, uid), quien)
        }

        /** Bloque del editor → cuántos comentarios abiertos caen en él. */
        fun porBloque(f: Comentarios.Fichero, texto: String): Map<Int, Int> {
            val trozos = Vivo.trozos(texto)
            val salida = HashMap<Int, Int>()
            for (h in f.comentarios) {
                if (h.resuelto) continue
                val r = Comentarios.ubicar(texto, h.ancla) ?: continue
                val b = trozoEn(trozos, r.first)
                salida[b] = (salida[b] ?: 0) + 1
            }
            return salida
        }
    }
}

private val MESES = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept", "oct", "nov", "dic")

/** «30 sept, 14:05», con el año si no es este (como el PC). */
fun fechaDeComentario(ms: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val hoy = java.util.Calendar.getInstance()
    val dia = "${c.get(java.util.Calendar.DAY_OF_MONTH)} ${MESES[c.get(java.util.Calendar.MONTH)]}"
    val anio = if (c.get(java.util.Calendar.YEAR) != hoy.get(java.util.Calendar.YEAR)) " ${c.get(java.util.Calendar.YEAR)}" else ""
    return String.format(java.util.Locale.ROOT, "%s%s, %02d:%02d", dia, anio, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

/** El color fijo de cada aparato, para el redondel de su inicial. */
private fun colorDe(aparato: String): Color =
    Color.hsv(((aparato.hashCode() and 0x7fffffff) % 360).toFloat(), 0.55f, 0.75f)

/**
 * **El panel de comentarios**, como hoja que sube desde abajo (en el teléfono no cabe un margen
 * a la derecha como en el PC). Los que ya no encuentran su texto van arriba, aparte, con su cita;
 * los demás en el orden del texto. [enfoque] pone primero los del bloque que se tocó.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelDeComentarios(
    fichero: Comentarios.Fichero,
    texto: String,
    quien: Comentarios.Quien,
    soloLectura: Boolean,
    enfoque: Int?,
    onCambio: (Comentarios.Fichero) -> Unit,
    onIrA: (IntRange) -> Unit,
    onCerrar: () -> Unit
) {
    var verResueltos by remember { mutableStateOf(false) }
    val trozos = remember(texto) { Vivo.trozos(texto) }
    val ordenados = remember(fichero, texto, verResueltos, enfoque) {
        fichero.comentarios.filter { verResueltos || !it.resuelto }
            .map { it to Comentarios.ubicar(texto, it.ancla) }
            .sortedWith(compareBy<Pair<Comentarios.Hilo, IntRange?>> { (_, r) ->
                when {
                    r == null -> -2
                    enfoque != null && trozoEn(trozos, r.first) == enfoque -> -1
                    else -> 0
                }
            }.thenBy { it.second?.first ?: 0 })
    }
    ModalBottomSheet(onDismissRequest = onCerrar) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Comentarios", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("Resueltos", style = MaterialTheme.typography.labelMedium)
                Switch(checked = verResueltos, onCheckedChange = { verResueltos = it }, modifier = Modifier.padding(start = 6.dp))
            }
            if (soloLectura) Text(
                "El archivo de comentarios no se entiende: se enseña sin tocarlo.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
            )
            if (ordenados.isEmpty()) {
                Text(
                    "No hay comentarios. Elige un trozo de texto y pulsa «Comentar».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(ordenados, key = { it.first.id }) { (h, r) ->
                    TarjetaDeHilo(h, r, quien, soloLectura, onCambio = { onCambio(it(fichero)) }, onIrA = { r?.let(onIrA) })
                }
            }
        }
    }
}

@Composable
private fun TarjetaDeHilo(
    h: Comentarios.Hilo,
    sitio: IntRange?,
    quien: Comentarios.Quien,
    soloLectura: Boolean,
    onCambio: ((Comentarios.Fichero) -> Comentarios.Fichero) -> Unit,
    onIrA: () -> Unit
) {
    var respuesta by remember(h.id) { mutableStateOf("") }
    var editando by remember(h.id) { mutableStateOf<String?>(null) }
    var editado by remember(h.id) { mutableStateOf("") }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (h.resuelto) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(enabled = sitio != null) { onIrA() }
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sitio == null) Text("Su texto ya no está en la nota", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Text(
                "«${h.ancla.cita}»", maxLines = 1, overflow = TextOverflow.Ellipsis, fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.background(Color(0x33FFC440), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)
            )
            Mensaje(h.id, h.autor, h.aparato, h.cuando, h.editado, h.texto, editando == h.id, editado, { editado = it },
                onGuardar = { onCambio { f -> Comentarios.editar(f, h.id, editado, System.currentTimeMillis()) ?: f }; editando = null },
                acciones = if (soloLectura) null else ({ que ->
                    when (que) {
                        "editar" -> { editando = h.id; editado = h.texto }
                        "borrar" -> onCambio { f -> Comentarios.borrar(f, h.id) ?: f }
                    }
                }),
                resolver = if (soloLectura) null else ({
                    onCambio { f -> Comentarios.resolver(f, h.id, !h.resuelto, quien, System.currentTimeMillis()) ?: f }
                }),
                resuelto = h.resuelto, resueltoPor = h.resueltoPor
            )
            h.respuestas.forEach { r ->
                Row {
                    Spacer(Modifier.width(18.dp))
                    Mensaje(r.id, r.autor, r.aparato, r.cuando, r.editado, r.texto, editando == r.id, editado, { editado = it },
                        onGuardar = { onCambio { f -> Comentarios.editar(f, r.id, editado, System.currentTimeMillis()) ?: f }; editando = null },
                        acciones = if (soloLectura) null else ({ que ->
                            when (que) {
                                "editar" -> { editando = r.id; editado = r.texto }
                                "borrar" -> onCambio { f -> Comentarios.borrar(f, r.id) ?: f }
                            }
                        })
                    )
                }
            }
            if (!soloLectura) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = respuesta, onValueChange = { respuesta = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Responder…") }, maxLines = 4, shape = RoundedCornerShape(14.dp)
                )
                TextButton(enabled = respuesta.isNotBlank(), onClick = {
                    val t = respuesta
                    onCambio { f -> Comentarios.responder(f, h.id, quien, System.currentTimeMillis(), t)?.first ?: f }
                    respuesta = ""
                }) { Text("Enviar") }
            }
        }
    }
}

@Composable
private fun Mensaje(
    id: String, autor: String, aparato: String, cuando: Long, editadoEn: Long?, texto: String,
    editandoEste: Boolean, borrador: String, onBorrador: (String) -> Unit, onGuardar: () -> Unit,
    acciones: ((String) -> Unit)?, resolver: (() -> Unit)? = null, resuelto: Boolean = false, resueltoPor: String? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(colorDe(aparato)), contentAlignment = Alignment.Center) {
                Text(autor.take(1).uppercase().ifEmpty { "?" }, color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(autor.ifBlank { "Alguien" }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    fechaDeComentario(cuando) + (if (editadoEn != null) " · editado" else "") +
                        (if (resuelto) " · resuelto" + (resueltoPor?.let { " por $it" } ?: "") else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (resolver != null) IconButton(onClick = resolver) {
                Icon(if (resuelto) Icons.Filled.Undo else Icons.Filled.Check, if (resuelto) "Reabrir" else "Resolver")
            }
            if (acciones != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Editar") }, onClick = { menu = false; acciones("editar") })
                        DropdownMenuItem(text = { Text("Borrar") }, onClick = { menu = false; acciones("borrar") })
                    }
                }
            }
        }
        if (editandoEste) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = borrador, onValueChange = onBorrador, modifier = Modifier.weight(1f), maxLines = 6)
                TextButton(enabled = borrador.isNotBlank(), onClick = onGuardar) { Text("Guardar") }
            }
        } else Text(texto, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 34.dp))
    }
}

/** El diálogo de comentar: la cita y el texto. */
@Composable
fun DialogoDeComentar(cita: String, onCerrar: () -> Unit, onComentar: (String) -> Unit) {
    var texto by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Comentar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("«$cita»", maxLines = 3, overflow = TextOverflow.Ellipsis, fontStyle = FontStyle.Italic,
                    modifier = Modifier.background(Color(0x33FFC440), RoundedCornerShape(4.dp)).padding(4.dp))
                OutlinedTextField(value = texto, onValueChange = { texto = it }, placeholder = { Text("Tu comentario") }, maxLines = 6, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = texto.isNotBlank(), onClick = { onComentar(texto) }) { Text("Comentar") } },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}
