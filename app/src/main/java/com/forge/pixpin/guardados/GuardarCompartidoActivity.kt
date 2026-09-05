package com.forge.pixpin.guardados

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Guardar aquí lo que se comparta desde otra aplicación.**
 *
 * ## Por qué es una entrada aparte
 *
 * PixPin ya salía en el menú de compartir, pero lo que llegaba se convertía en **pin**: se
 * quedaba flotando en la pantalla. Eso está bien para lo que se va a usar ahora mismo y
 * fatal para lo que se manda uno a sí mismo para luego — que es la mitad de lo que se
 * comparte desde WhatsApp o Telegram.
 *
 * Es exactamente lo que hace Telegram con «Mensajes guardados»: aparece como **destino
 * propio** en el menú de compartir del sistema, al lado de los contactos. Por eso son dos
 * entradas y no una con un diálogo en medio: elegir entre «pin» y «guardar» a base de
 * preguntar añade un toque a las dos, y compartir algo tiene que costar un toque.
 *
 * ## Y el texto, que antes se perdía
 *
 * El receptor de pines solo miraba el archivo adjunto, así que compartir **un enlace o un
 * trozo de texto** desde otra aplicación no hacía absolutamente nada: ni pin, ni aviso. Es
 * lo más compartido que hay y era lo único que no funcionaba.
 *
 * ## Sin pantalla
 *
 * No abre nada: guarda, avisa con una tostada y se quita de en medio. Quien comparte desde
 * WhatsApp quiere volver a WhatsApp, no aterrizar en otra aplicación.
 */
class GuardarCompartidoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        val almacen = MensajesStore(this)
        val texto = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val asunto = intent?.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            else -> emptyList()
        }

        if (uris.isEmpty() && texto.isBlank()) {
            avisar(false)
            finish()
            return
        }

        // La copia va **antes** de terminar: el permiso sobre lo compartido se retira en
        // cuanto esta pantalla muere, y con él la posibilidad de leer el archivo. Es el
        // mismo motivo por el que el receptor de pines lo hace así.
        lifecycleScope.launch {
            val guardados = withContext(Dispatchers.IO) {
                var cuantos = 0
                uris.forEach { uri -> if (guardarArchivo(almacen, uri, asunto)) cuantos++ }
                // El texto se guarda **además** del archivo, no en vez de él: al
                // compartir una foto con comentario llegan los dos, y quedarse solo con
                // la foto pierde justo lo que explicaba por qué se guardó.
                if (texto.isNotBlank()) {
                    almacen.anadir(
                        Mensaje(
                            id = UUID.randomUUID().toString(),
                            cuando = System.currentTimeMillis(),
                            clase = Clase.NOTA,
                            texto = texto.trim()
                        )
                    )
                    cuantos++
                }
                cuantos
            }
            avisar(guardados > 0)
            finishAndRemoveTask()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** Copia el archivo compartido al almacén y lo da de alta. */
    private fun guardarArchivo(almacen: MensajesStore, uri: Uri, asunto: String): Boolean =
        runCatching {
            val nombre = nombreDe(uri).ifBlank { asunto }.ifBlank { "compartido" }
            val temporal = File(cacheDir, "comp_${System.currentTimeMillis()}")
            contentResolver.openInputStream(uri)?.use { entrada ->
                temporal.outputStream().use { entrada.copyTo(it) }
            } ?: return false
            val tipo = contentResolver.getType(uri)
            val ruta = almacen.copiarAdjunto(temporal, nombre, extensionDe(tipo)) ?: return false
            val bytes = temporal.length()
            temporal.delete()
            val esImagen = tipo?.startsWith("image/") == true
            // **Un audio de otra aplicación es una nota de voz**: se escucha aquí y se
            // pasa a texto como las nuestras, venga en el formato que venga (ver
            // [Transcriptor]). Antes entraba como archivo a secas.
            val esAudio = tipo?.startsWith("audio/") == true ||
                ruta.substringAfterLast('.', "").lowercase() in setOf("m4a", "mp3", "ogg", "oga", "opus", "wav", "flac", "aac", "amr", "3gp")
            almacen.anadir(
                Mensaje(
                    id = UUID.randomUUID().toString(),
                    cuando = System.currentTimeMillis(),
                    clase = if (esImagen) Clase.IMAGEN else if (esAudio) Clase.VOZ else Clase.ARCHIVO,
                    ruta = ruta,
                    nombre = nombre,
                    bytes = bytes,
                    duracionMs = if (esAudio) com.forge.pixpin.pin.Voz.duracion(ruta) else 0
                )
            )
            true
        }.getOrDefault(false)

    /** La extensión que le toca a un tipo, si Android la conoce. */
    private fun extensionDe(mime: String?): String? =
        mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }

    /** El nombre con el que lo llama quien lo comparte, si es que lo dice. */
    private fun nombreDe(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { fila ->
            val columna = fila.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (columna >= 0 && fila.moveToFirst()) fila.getString(columna) else null
        }
    }.getOrNull().orEmpty()

    private fun avisar(bien: Boolean) {
        Toast.makeText(
            this,
            if (bien) com.forge.pixpin.R.string.guardados_compartido_ok
            else com.forge.pixpin.R.string.guardados_compartido_no,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    private fun Intent.streamUris(): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }
}
