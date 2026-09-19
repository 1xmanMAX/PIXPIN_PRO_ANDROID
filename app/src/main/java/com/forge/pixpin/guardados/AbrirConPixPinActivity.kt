package com.forge.pixpin.guardados

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * **«Abrir con» PixPin** (19-sep-2026): una página web, un Word, un libro, un PDF, un PowerPoint,
 * una hoja de cálculo o una foto tocados en el gestor de archivos —o en cualquier otra
 * aplicación— salen con PixPin en la lista, y se abren aquí con el visor que les toca.
 *
 * **Y se quedan guardados**: al abrirse se hace **una copia en Guardados** (la conversación
 * general), porque la URI que entrega la otra aplicación caduca en cuanto esta pantalla muere y
 * porque lo que se abre una vez casi siempre se quiere volver a abrir. Lo pidió así el usuario.
 * Abrir dos veces el mismo archivo **no lo guarda dos veces**: se reconoce por nombre y peso.
 *
 * Pregunta qué hacer —abrir, enviar por Wi-Fi o poner como pin— y, al abrir, copia, da de alta
 * el mensaje y le pasa el relevo al chat, que es quien sabe
 * abrir cada cosa ([MensajesActivity.abrir]). Así atrás, desde el visor, deja en el chat con el
 * archivo recién guardado a la vista.
 */
class AbrirConPixPinActivity : ComponentActivity() {

    /** Lo tocado, ya copiado a un temporal nuestro con su nombre: la URI del otro caduca. */
    private class Traido(val archivo: File, val nombre: String, val tipo: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        val uri = intent?.data
        if (intent?.action != Intent.ACTION_VIEW || uri == null) { finish(); return }
        lifecycleScope.launch {
            // La copia va antes que nada: el permiso de lectura se va con esta pantalla.
            val traido = withContext(Dispatchers.IO) { runCatching { traer(uri) }.getOrNull() }
            if (traido == null) {
                Toast.makeText(this@AbrirConPixPinActivity, "No se pudo abrir el archivo", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            // **Tres cosas que hacer con él, a elegir** (19-sep-2026): abrirlo, mandarlo por
            // Wi-Fi o dejarlo como pin. Antes, para las dos últimas había que ir a «compartir» y
            // buscar PixPin otra vez; ahora «Abrir con» las ofrece todas, para cualquier archivo.
            setContent {
                com.forge.pixpin.ui.theme.PixPinTheme {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { traido.archivo.delete(); finishAndRemoveTask() },
                        title = { androidx.compose.material3.Text(traido.nombre, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        text = {
                            androidx.compose.foundation.layout.Column {
                                Opcion(androidx.compose.material.icons.Icons.Filled.OpenInNew, "Abrir") { abrir(traido) }
                                Opcion(androidx.compose.material.icons.Icons.Filled.Wifi, "Enviar por Wi-Fi") { porWifi(traido) }
                                Opcion(androidx.compose.material.icons.Icons.Filled.PushPin, "Poner como pin") { comoPin(traido) }
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { traido.archivo.delete(); finishAndRemoveTask() }) {
                                androidx.compose.material3.Text(getString(com.forge.pixpin.R.string.cancel))
                            }
                        }
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Opcion(icono: androidx.compose.ui.graphics.vector.ImageVector, texto: String, alElegir: () -> Unit) {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier
                .fillMaxWidth()
                .clickable(onClick = alElegir)
                .padding(vertical = 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(icono, contentDescription = null, modifier = androidx.compose.ui.Modifier.size(22.dp))
            androidx.compose.material3.Text(texto, modifier = androidx.compose.ui.Modifier.padding(start = 14.dp))
        }
    }

    /** **Abrir**: una copia a Guardados y el relevo al chat, que sabe con qué se abre cada cosa. */
    private fun abrir(t: Traido) {
        lifecycleScope.launch {
            val almacen = MensajesStore(this@AbrirConPixPinActivity)
            val esMarkdown = t.nombre.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
            // Una nota en Markdown se abre en su editor: se lee antes de que el temporal se vaya.
            val texto = if (esMarkdown) withContext(Dispatchers.IO) { runCatching { t.archivo.readText() }.getOrNull() } else null
            val id = withContext(Dispatchers.IO) { runCatching { guardar(almacen, t) }.getOrNull() }
            when {
                id == null -> Toast.makeText(this@AbrirConPixPinActivity, "No se pudo abrir el archivo", Toast.LENGTH_SHORT).show()
                texto != null -> com.forge.pixpin.ui.MarkdownEditorActivity.abrir(this@AbrirConPixPinActivity, "md-$id", texto)
                else -> MensajesActivity.abrirYAbrir(this@AbrirConPixPinActivity, id)
            }
            finish()
        }
    }

    /** El temporal, con su nombre de verdad, donde el proveedor de archivos sabe servirlo. */
    private fun conSuNombre(t: Traido): File? = runCatching {
        val limpio = t.nombre.replace(Regex("""[^\p{L}\p{N} ._-]"""), "_").takeLast(80).ifBlank { "archivo" }
        File(File(cacheDir, "share").apply { mkdirs() }, limpio).also { t.archivo.copyTo(it, overwrite = true); t.archivo.delete() }
    }.getOrNull()

    /** **Enviar por Wi-Fi**: el envío de una vez, con su código y su QR. Ver [com.forge.pixpin.sincro.EnviarActivity]. */
    private fun porWifi(t: Traido) {
        lifecycleScope.launch {
            val archivo = withContext(Dispatchers.IO) { conSuNombre(t) }
            if (archivo == null) Toast.makeText(this@AbrirConPixPinActivity, "No se pudo preparar el envío", Toast.LENGTH_SHORT).show()
            else com.forge.pixpin.sincro.EnviarActivity.enviarArchivo(this@AbrirConPixPinActivity, archivo.absolutePath, t.nombre)
            finish()
        }
    }

    /** **Poner como pin**: se le pasa a quien ya convierte en pin lo compartido. Ver [com.forge.pixpin.clipboard.ShareReceiverActivity]. */
    private fun comoPin(t: Traido) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                conSuNombre(t)?.let { runCatching { androidx.core.content.FileProvider.getUriForFile(this@AbrirConPixPinActivity, "$packageName.fileprovider", it) }.getOrNull() }
            }
            if (uri == null) Toast.makeText(this@AbrirConPixPinActivity, "No se pudo hacer el pin", Toast.LENGTH_SHORT).show()
            else runCatching {
                startActivity(
                    Intent(this@AbrirConPixPinActivity, com.forge.pixpin.clipboard.ShareReceiverActivity::class.java)
                        .setAction(Intent.ACTION_SEND)
                        .setType(t.tipo ?: "*/*")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
            finish()
        }
    }

    private fun traer(uri: Uri): Traido? {
        val tipo = contentResolver.getType(uri)
        val nombre = nombreDe(uri).ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }.ifBlank { "archivo" }
        val temporal = File(cacheDir, "abrir_${System.currentTimeMillis()}_${nombre.hashCode()}")
        contentResolver.openInputStream(uri)?.use { entra -> temporal.outputStream().use { entra.copyTo(it) } } ?: return null
        if (temporal.length() == 0L) { temporal.delete(); return null }
        return Traido(temporal, nombre, tipo)
    }

    /** Copia lo que señala [uri] a Guardados —o encuentra la copia de antes— y devuelve su mensaje. */
    private fun guardar(almacen: MensajesStore, t: Traido): String? {
        val tipo = t.tipo
        val nombre = t.nombre
        val temporal = t.archivo
        val peso = temporal.length()

        // ¿Ya está? Mismo nombre y mismo peso en la general. Un PDF se guarda aligerado, así que
        // su peso no coincide con el de fuera: a ese se le reconoce solo por el nombre.
        val esPdf = nombre.endsWith(".pdf", ignoreCase = true)
        almacen.leer().lastOrNull { m ->
            m.proyecto == null && m.nombre == nombre && m.ruta?.let { File(it).exists() } == true &&
                (m.clase == Clase.ARCHIVO || m.clase == Clase.IMAGEN) && (esPdf || m.bytes == peso)
        }?.let { temporal.delete(); return it.id }

        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(tipo)
        val ruta = almacen.copiarAdjunto(temporal, nombre, extension) ?: run { temporal.delete(); return null }
        temporal.delete()
        val esImagen = tipo?.startsWith("image/") == true
        val id = UUID.randomUUID().toString()
        almacen.anadir(
            Mensaje(
                id = id,
                cuando = System.currentTimeMillis(),
                clase = if (esImagen) Clase.IMAGEN else Clase.ARCHIVO,
                ruta = ruta,
                nombre = nombre,
                bytes = File(ruta).length(),
                proyecto = null
            )
        )
        return id
    }

    private fun nombreDe(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { fila ->
            val columna = fila.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (columna >= 0 && fila.moveToFirst()) fila.getString(columna).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")
}
