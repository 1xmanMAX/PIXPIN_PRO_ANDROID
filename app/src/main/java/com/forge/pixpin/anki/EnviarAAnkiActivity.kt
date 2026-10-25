package com.forge.pixpin.anki

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.tituloDeAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **«Enviar a Anki»** (25-oct-2026, `ventana_chat/anki.rs` del PC): una pantalla sin pantalla que
 * abre el menú del mensaje. Existe para poder **pedir el permiso de AnkiDroid** ahí mismo (un
 * permiso peligroso necesita una actividad que lo pida) sin tocar el chat más que con una línea.
 *
 * - Con **AnkiDroid** (y su permiso): las tarjetas entran directas en el mazo `PixPin::<chat>`, con
 *   sus imágenes ([AnkiDroid]).
 * - **Sin él** (o si dice que no, o con su API apagada): un `.txt` para «Archivo → Importar» de
 *   Anki ([Tarjetas.fichero]) por la hoja de compartir, con las imágenes al lado. En el PC es el
 *   fichero en `<raíz>/anki/`; en el teléfono no hay una carpeta de Anki que valga, así que se da
 *   a quien el usuario elija (Drive, correo, el PC por Wi-Fi…).
 */
class EnviarAAnkiActivity : ComponentActivity() {

    private val pedir = registerForActivityResult(ActivityResultContracts.RequestPermission()) { enviar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (AnkiDroid.instalado(this) && !AnkiDroid.tienePermiso(this)) pedir.launch(AnkiDroid.PERMISO)
        else enviar()
    }

    private fun enviar() {
        val id = intent.getStringExtra(EXTRA_MENSAJE) ?: return finish()
        val app = application as? PixPinApp
        lifecycleScope.launch {
            val (aviso, compartir) = withContext(Dispatchers.IO) { preparar(id, app) }
            Toast.makeText(this@EnviarAAnkiActivity, aviso, Toast.LENGTH_LONG).show()
            if (compartir != null) runCatching { startActivity(Intent.createChooser(compartir, "Importar en Anki")) }
            finish()
        }
    }

    /** Lo que se dice al acabar y, si hay que compartir el fichero, su intención. */
    private fun preparar(id: String, app: PixPinApp?): Pair<String, Intent?> {
        val todos = runCatching { MensajesStore(this).leer() }.getOrDefault(emptyList())
        val m = todos.firstOrNull { it.id == id } ?: return "El mensaje ya no está" to null
        val chat = m.proyecto?.let { app?.proyectos?.porId(it)?.nombre } ?: "Mensajes guardados"
        val tarjetas = AnkiDelMensaje.tarjetasDe(m, todos, tituloDeAudio(this, m), chat)
        if (tarjetas.isEmpty()) return NADA to null
        val mazo = AnkiDelMensaje.mazoDe(chat)
        if (AnkiDroid.instalado(this) && AnkiDroid.tienePermiso(this)) {
            val hecho = runCatching { AnkiDroid.anadir(this, mazo, tarjetas) }
            hecho.onSuccess { return "$it tarjetas en Anki, mazo «$mazo»" to null }
            android.util.Log.w("PixPin", "Anki no admitió las tarjetas", hecho.exceptionOrNull())
        }
        return runCatching { aFichero(mazo, tarjetas) }.fold(
            { "AnkiDroid no está (o no dio permiso). ${tarjetas.size} tarjetas en un fichero: en Anki, Archivo → Importar." to it },
            { "No se pudo: ${it.message}" to null }
        )
    }

    /** El `.txt` de importar y las imágenes, listos para la hoja de compartir. */
    private fun aFichero(mazo: String, tarjetas: List<Tarjetas.Tarjeta>): Intent {
        val carpeta = AnkiDroid.carpeta(this)
        val nombre = mazo.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        val txt = File(carpeta, "$nombre-${System.currentTimeMillis()}.txt").apply { writeText(Tarjetas.fichero(mazo, tarjetas)) }
        val imagenes = tarjetas.flatMap { it.imagenes }.distinctBy { it.nombre }.mapNotNull { AnkiDroid.copiaCompartible(this, it) }
        val autoridad = "$packageName.fileprovider"
        val uris = (listOf(txt) + imagenes).map { FileProvider.getUriForFile(this, autoridad, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        return intent.putExtra(Intent.EXTRA_SUBJECT, mazo).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        private const val EXTRA_MENSAJE = "mensaje"

        /** `anki-nada` del PC. */
        const val NADA = "Aquí no hay tarjetas. Escribe una por línea como «pregunta :: respuesta», o «P: …» y «R: …», o con huecos {{c1::así}}."

        fun abrir(context: Context, mensaje: String) = context.startActivity(
            Intent(context, EnviarAAnkiActivity::class.java).putExtra(EXTRA_MENSAJE, mensaje)
        )
    }
}
