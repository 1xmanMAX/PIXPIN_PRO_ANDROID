package com.forge.pixpin.guardados

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
 * No enseña nada: copia, da de alta el mensaje y le pasa el relevo al chat, que es quien sabe
 * abrir cada cosa ([MensajesActivity.abrir]). Así atrás, desde el visor, deja en el chat con el
 * archivo recién guardado a la vista.
 */
class AbrirConPixPinActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        val uri = intent?.data
        if (intent?.action != Intent.ACTION_VIEW || uri == null) { finish(); return }
        val almacen = MensajesStore(this)
        lifecycleScope.launch {
            // La copia va antes que nada: el permiso de lectura se va con esta pantalla.
            val id = withContext(Dispatchers.IO) { runCatching { guardar(almacen, uri) }.getOrNull() }
            if (id == null) {
                Toast.makeText(this@AbrirConPixPinActivity, "No se pudo abrir el archivo", Toast.LENGTH_SHORT).show()
            } else {
                MensajesActivity.abrirYAbrir(this@AbrirConPixPinActivity, id)
            }
            finish()
        }
    }

    /** Copia lo que señala [uri] a Guardados —o encuentra la copia de antes— y devuelve su mensaje. */
    private fun guardar(almacen: MensajesStore, uri: Uri): String? {
        val tipo = contentResolver.getType(uri)
        val nombre = nombreDe(uri).ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }.ifBlank { "archivo" }
        val temporal = File(cacheDir, "abrir_${System.currentTimeMillis()}_${nombre.hashCode()}")
        contentResolver.openInputStream(uri)?.use { entra -> temporal.outputStream().use { entra.copyTo(it) } } ?: return null
        val peso = temporal.length()
        if (peso == 0L) { temporal.delete(); return null }

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
