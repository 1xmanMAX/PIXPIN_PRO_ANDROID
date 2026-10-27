package com.forge.pixpin.guardados

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Lo que llega de otra aplicación: «Abrir con» PixPin y «Compartir» a PixPin.**
 *
 * **27-oct-2026, el modo de paso** ([ModoDePaso]). El usuario: «quiero abrir cualquier archivo de
 * los formatos que aceptamos como una app para abrir archivos: lo abro, lo veo, salgo y se cierra,
 * sin guardarse en ningún lado». Hasta hoy (desde el 19-sep) «Abrir con» hacía una copia en
 * Mensajes guardados y la abría desde el chat; ahora **no se guarda nada**: se copia a la caché de
 * la sesión, se abre en su visor con todo y, al salir, se borra. Para quedárselo, el botón
 * «Guardar» del visor ([com.forge.pixpin.ui.BarraDePaso]).
 *
 * **Y compartir a PixPin, sin menú para lo conocido.** Compartir a PixPin enseñaba cuatro destinos
 * en la hoja del sistema (el pin, Mensajes guardados, un proyecto nuevo, Wi-Fi), y el usuario: «quita
 * ese menú para los formatos conocidos». Ahora hay **una sola entrada, «PixPin»**, y esta pantalla
 * decide ([ModoDePaso.queHacerConLoCompartido]): un archivo que PixPin sabe ver se abre de paso
 * como el «Abrir con»; un `.pixpin` se importa; y lo demás —un formato desconocido, varios
 * archivos, un texto o un enlace— sale en un **menú corto** con lo que tiene sentido: guardarlo
 * (en Mensajes guardados o en un chat), mandarlo por Wi-Fi a mis aparatos, ponerlo como pin o
 * abrirlo con otra aplicación. Cada opción la cumple la pantalla de siempre.
 *
 * Su propia afinidad y sin historia: se quita de en medio en cuanto abre el visor, que vive en su
 * propia tarea; atrás desde él vuelve a la aplicación que lo mandó.
 */
class AbrirConPixPinActivity : LanzadorDePaso() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        val i = intent
        when (i?.action) {
            Intent.ACTION_VIEW -> {
                val uri = i.data ?: run { finish(); return }
                lifecycleScope.launch { deUno(uri, i.type) }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> lifecycleScope.launch { compartido(i) }
            else -> finish()
        }
    }

    /** Un archivo: de paso en su visor; si PixPin no lo ve, al menú corto. */
    private suspend fun deUno(uri: Uri, tipo: String?) {
        // La copia va antes que nada: el permiso de lectura se va con esta pantalla.
        val e = withContext(Dispatchers.IO) { AbrirDePaso.traer(this@AbrirConPixPinActivity, uri, mimeDado = tipo?.takeIf { it != "*/*" }) }
        if (e == null) {
            Toast.makeText(this, "No se pudo abrir el archivo", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val abierto = runCatching { AbrirDePaso.abrirEnSuVisor(this, e) }.getOrDefault(false)
        if (abierto) finish() else menu(listOf(e), "", "")
    }

    private suspend fun compartido(i: Intent) {
        val uris = if (i.action == Intent.ACTION_SEND) listOfNotNull(i.streamUri()) else i.streamUris()
        val texto = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val asunto = i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        if (uris.isEmpty() && texto.isBlank()) { finish(); return }
        val primero = uris.singleOrNull()
        val nombre = primero?.let { withContext(Dispatchers.IO) { AbrirDePaso.nombreDe(this@AbrirConPixPinActivity, it) } }
        val tipo = primero?.let { runCatching { contentResolver.getType(it) }.getOrNull() } ?: i.type?.takeIf { it != "*/*" }
        when (ModoDePaso.queHacerConLoCompartido(uris.size, nombre, tipo)) {
            ModoDePaso.Recibido.DE_PASO -> deUno(primero!!, tipo)
            // Un `.pixpin` es un proyecto: lo importa quien siempre lo importó.
            ModoDePaso.Recibido.IMPORTAR -> { pasarA(NuevoProyectoCompartidoActivity::class.java, Intent(i)); finish() }
            ModoDePaso.Recibido.MENU -> {
                val copias = withContext(Dispatchers.IO) { uris.mapNotNull { AbrirDePaso.traer(this@AbrirConPixPinActivity, it, if (uris.size == 1) nombre else null) } }
                if (uris.isNotEmpty() && copias.isEmpty() && texto.isBlank()) {
                    Toast.makeText(this, "No se pudo leer lo compartido", Toast.LENGTH_SHORT).show()
                    finish()
                } else menu(copias, texto, asunto)
            }
        }
    }

    /**
     * **El menú corto**, para lo que no se abre de paso. Lo copiado se le pasa a cada pantalla con
     * nuestras propias URI (la del otro caduca con esta pantalla). Las copias quedan en la caché de
     * paso y se van en la limpieza al arrancar.
     */
    private fun menu(copias: List<AbrirDePaso.Estado>, texto: String, asunto: String) {
        val hayChats = AbrirDePaso.chats(this).isNotEmpty()
        val titulo = copias.singleOrNull()?.nombre ?: if (copias.size > 1) "${copias.size} archivos" else texto.take(60)
        fun reenvio(): Intent {
            val uris = ArrayList(copias.map { com.forge.pixpin.ui.AbrirCon.uriDe(this, File(it.ruta)) })
            val r = if (uris.size > 1) Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            else Intent(Intent.ACTION_SEND).also { s -> uris.firstOrNull()?.let { s.putExtra(Intent.EXTRA_STREAM, it) } }
            r.type = copias.singleOrNull()?.mime ?: if (copias.isEmpty()) "text/plain" else "*/*"
            if (texto.isNotBlank()) r.putExtra(Intent.EXTRA_TEXT, texto)
            if (asunto.isNotBlank()) r.putExtra(Intent.EXTRA_SUBJECT, asunto)
            return r
        }
        val opciones = buildList {
            add(Triple(Icons.Filled.Bookmark, "Guardar en Mensajes guardados") {
                pasarA(GuardarCompartidoActivity::class.java, reenvio().putExtra(GuardarCompartidoActivity.EXTRA_A_LA_GENERAL, true))
            })
            if (hayChats) add(Triple(Icons.Filled.Forum, "Guardar en un chat…") { pasarA(GuardarCompartidoActivity::class.java, reenvio()) })
            if (copias.isNotEmpty()) add(Triple(Icons.Filled.Wifi, "Enviar por Wi-Fi a mis aparatos") {
                pasarA(com.forge.pixpin.sincro.EnviarActivity::class.java, reenvio())
            })
            copias.singleOrNull()?.let { e -> add(Triple(Icons.Filled.OpenInNew, "Abrir con otra aplicación") { com.forge.pixpin.ui.AbrirCon.abrir(this@AbrirConPixPinActivity, File(e.ruta), e.mime) }) }
            add(Triple(Icons.Filled.PushPin, "Poner como pin") { pasarA(com.forge.pixpin.clipboard.ShareReceiverActivity::class.java, reenvio()) })
        }
        setContent {
            com.forge.pixpin.ui.theme.PixPinTheme {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { finish() },
                    title = { androidx.compose.material3.Text(titulo, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            for ((icono, nombre, hacer) in opciones) Opcion(icono, nombre) { hacer(); finish() }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { finish() }) {
                            androidx.compose.material3.Text(getString(com.forge.pixpin.R.string.cancel))
                        }
                    }
                )
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

    /**
     * Le pasa [i] a la pantalla [destino] de siempre, con permiso para leer lo que lleva. En su propia
     * tarea (todas tienen afinidad propia): si fuera en la de quien compartió, su `finishAndRemoveTask`
     * se llevaría por delante la aplicación de origen.
     */
    private fun pasarA(destino: Class<*>, i: Intent) {
        runCatching {
            startActivity(
                i.setComponent(ComponentName(this, destino))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Toast.makeText(this, "No se pudo", Toast.LENGTH_SHORT).show() }
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
