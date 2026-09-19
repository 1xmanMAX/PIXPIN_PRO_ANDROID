package com.forge.pixpin.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.forge.pixpin.R
import com.forge.pixpin.guardados.aPantallaCompleta
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.ByteArrayInputStream
import java.io.File
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Ver una página web guardada, sin salir de PixPin.**
 *
 * Un `.html` de la conversación salía con «abrir con» a un navegador, y la mitad de los
 * navegadores de Android ni se ofrecen para un archivo local. Y justo los `.html` que más
 * circulan por aquí son **los nuestros**: lo que saca el exportador «Página web»
 * ([com.forge.pixpin.motor.ExportarHtml]) es un solo archivo, interactivo, con su guion dentro.
 * Que la aplicación que los hace no supiera enseñarlos era raro (usuario, 19-sep-2026).
 *
 * **Sin ninguna librería**: el `WebView` es del sistema, como el `PdfRenderer` del lector de
 * PDF. Ver [com.forge.pixpin.pdf.LectorPdfActivity], del que esto es el hermano pequeño.
 *
 * ## Lo que se le deja hacer a la página, y lo que no
 *
 * - **JavaScript y almacenamiento DOM, sí**: las páginas exportadas son un visor entero
 *   —capas, medir, pasar hojas— y sin guion son una hoja en blanco.
 * - **Archivos, solo su carpeta.** La página no se abre donde está guardada —al lado de
 *   todos los demás adjuntos del chat— sino **una copia en una carpeta propia** de la caché,
 *   y todo `file://` que apunte fuera de esa carpeta se corta (ver [Cliente]). Una página
 *   que llegó de otro no tiene por qué poder asomarse a los archivos de la aplicación.
 * - **Los enlaces de la red salen al navegador**: esto es un visor de un documento, no un
 *   navegador; lo que la página cargue de la red para pintarse (una fuente, una imagen) sí
 *   se deja, como en cualquier sitio.
 */
class VisorHtmlActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_RUTA = "ruta"
        private const val EXTRA_NOMBRE = "nombre"
        private const val EXTRA_COMPARTE = "comparte"
        private const val EXTRA_SIN_GUION = "sinGuion"

        /** La carpeta de la caché donde se deja la copia que se enseña. */
        private const val CARPETA = "visor-html"

        /** Si por el nombre (o la ruta) es una página web. */
        fun esHtml(nombre: String?): Boolean =
            when (nombre.orEmpty().substringAfterLast('.', "").lowercase()) {
                "html", "htm", "xhtml" -> true
                else -> false
            }

        /**
         * [nombre] es lo que se lee arriba. Los otros dos son para cuando la página **no es el
         * documento, sino lo que se ha sacado de él** —un Word pasado a HTML por
         * [com.forge.pixpin.motor.DocxAHtml]—: [comparte] es el archivo que sale al compartir
         * (el `.docx` de verdad, no la página fabricada), y [sinGuion] apaga JavaScript, que
         * una página así no lo lleva y lo que llega de otro no tiene por qué ejecutar nada.
         * Sin ellos, todo sigue como siempre: se comparte la propia página y el guion va encendido.
         */
        fun abrir(
            context: Context, ruta: String, nombre: String,
            comparte: String? = null, sinGuion: Boolean = false
        ) {
            val intent = Intent(context, VisorHtmlActivity::class.java)
                .putExtra(EXTRA_RUTA, ruta)
                .putExtra(EXTRA_NOMBRE, nombre)
                .putExtra(EXTRA_SIN_GUION, sinGuion)
            if (comparte != null) intent.putExtra(EXTRA_COMPARTE, comparte)
            context.startActivity(intent)
        }
    }

    /** El de ahora, para que «atrás» pueda volver dentro de la página y para soltarlo al salir. */
    private var web: WebView? = null

    /** El archivo que sale al compartir cuando no es la propia página; y si la página va sin guion. Ver [abrir]. */
    private var comparte: File? = null
    private var sinGuion = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aPantallaCompleta()
        val ruta = intent?.getStringExtra(EXTRA_RUTA)
        if (ruta == null) { finish(); return }
        val nombre = intent?.getStringExtra(EXTRA_NOMBRE).orEmpty().ifBlank { File(ruta).name }
        comparte = intent?.getStringExtra(EXTRA_COMPARTE)?.let { File(it) }
        sinGuion = intent?.getBooleanExtra(EXTRA_SIN_GUION, false) == true
        setContent { PixPinTheme { Pantalla(File(ruta), nombre) } }
    }

    override fun onDestroy() {
        // Un `WebView` que no se destruye se queda con su proceso de pintar y su guion vivos.
        web?.let { runCatching { it.stopLoading(); it.destroy() } }
        web = null
        super.onDestroy()
    }

    @Composable
    private fun Pantalla(original: File, nombre: String) {
        // La copia que se enseña; nula mientras se hace. Copiar es disco: fuera del hilo que pinta.
        var copia by remember(original) { mutableStateOf<File?>(null) }
        var fallo by remember(original) { mutableStateOf(false) }
        LaunchedEffect(original) {
            val hecha = withContext(Dispatchers.IO) { runCatching { copiaParaVer(original) }.getOrNull() }
            if (hecha == null) fallo = true else copia = hecha
        }
        // **Atrás vuelve primero dentro de la página** —un índice con sus hojas es ir y volver—
        // y solo cuando no queda a dónde volver, sale.
        BackHandler { if (web?.canGoBack() == true) web?.goBack() else finish() }

        // Un `Surface` y no un `background`: es el que pone el color del texto a juego con el
        // fondo. Ver `LetraActivity`.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.cancel))
                    }
                    Text(
                        nombre, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { compartir(comparte ?: original, nombre) }) {
                        Icon(Icons.Filled.Share, contentDescription = getString(R.string.guardados_compartir))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val pagina = copia
                    when {
                        fallo -> Text(
                            getString(R.string.visor_html_no),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )
                        pagina != null -> AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { contexto -> nuevoWeb(contexto, pagina) }
                        )
                    }
                }
            }
        }
    }

    /**
     * El `WebView` ya cargado con [pagina]. Los permisos, uno a uno y a la vista: lo que no
     * está aquí encendido está apagado. Ver el porqué en la documentación de la clase.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun nuevoWeb(contexto: Context, pagina: File): WebView = WebView(contexto).apply {
        settings.javaScriptEnabled = !sinGuion
        settings.domStorageEnabled = !sinGuion
        // Desde Android 11 viene apagado, y sin él un `file://` no carga ni la propia página.
        settings.allowFileAccess = true
        settings.allowContentAccess = false
        // Que el guion de la página no pueda leer otros archivos por su cuenta.
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        // Un plano exportado se mira acercándose: el pellizco, sin los botones de + y −.
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        webViewClient = Cliente(pagina.parentFile ?: pagina)
        loadUrl(Uri.fromFile(pagina).toString())
        web = this
    }

    /**
     * **El portero**: de `file://` solo pasa lo que está en la carpeta de la copia, y los
     * enlaces a la red se abren fuera.
     */
    private inner class Cliente(carpeta: File) : WebViewClient() {
        private val raiz: String = runCatching { carpeta.canonicalPath }.getOrDefault(carpeta.absolutePath) + File.separator

        private fun dentro(uri: Uri): Boolean {
            val camino = uri.path ?: return false
            return runCatching { File(camino).canonicalPath.startsWith(raiz) }.getOrDefault(false)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                // `true` es «no lo cargues»: un `file://` de fuera de la carpeta se queda en nada.
                "file" -> !dentro(uri)
                "http", "https", "mailto", "tel" -> {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    true
                }
                // `data:`, `blob:`, `about:`: lo que la propia página se fabrica.
                else -> false
            }
        }

        /** Y lo mismo para lo que la página pide sin navegar: una imagen, un guion, un marco. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            if (uri.scheme?.lowercase() == "file" && !dentro(uri)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }
    }

    /**
     * La copia que se enseña, **sola en su carpeta**: así «solo su carpeta» es de verdad solo
     * ella, y no los demás adjuntos del chat. La carpeta se vacía antes: es caché de usar y
     * tirar, y de otro modo cada página abierta se quedaría ahí para siempre.
     */
    private fun copiaParaVer(original: File): File? {
        if (!original.exists()) return null
        val carpeta = File(File(cacheDir, CARPETA), original.absolutePath.hashCode().toString())
        File(cacheDir, CARPETA).listFiles()?.forEach { if (it != carpeta) it.deleteRecursively() }
        carpeta.mkdirs()
        return original.copyTo(File(carpeta, "pagina.html"), overwrite = true)
    }

    /**
     * Manda **el archivo original**, con su nombre. Pasa por `cache/share`, que es lo que el
     * `FileProvider` sabe servir venga el archivo de donde venga. Ver `res/xml` y
     * `MensajesActivity.compartibleDe`.
     */
    private fun compartir(original: File, nombre: String) {
        // Copiar es disco —una página exportada con sus fotos dentro pesa megas—: fuera del hilo que pinta.
        // La extensión **del archivo que se manda**: un Word convertido se comparte como el
        // `.docx` que es, no como la página que se fabricó para verlo.
        val suya = original.extension.lowercase().ifBlank { nombre.substringAfterLast('.', "").lowercase() }
        val esPagina = suya.isBlank() || esHtml("x.$suya")
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val conExtension = when {
                        esPagina -> if (esHtml(nombre)) nombre else "$nombre.html"
                        nombre.substringAfterLast('.', "").lowercase() == suya -> nombre
                        else -> "$nombre.$suya"
                    }
                    val limpio = conExtension.replace(Regex("""[^\p{L}\p{N} ._-]"""), "_").takeLast(80)
                    val destino = File(File(cacheDir, "share").apply { mkdirs() }, limpio)
                    original.copyTo(destino, overwrite = true)
                    FileProvider.getUriForFile(this@VisorHtmlActivity, "$packageName.fileprovider", destino)
                }.getOrNull()
            }
            val salio = uri != null && runCatching {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = if (esPagina) "text/html"
                            else android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(suya) ?: "*/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        null
                    )
                )
            }.isSuccess
            if (!salio) Toast.makeText(this@VisorHtmlActivity, getString(R.string.visor_html_no_compartir), Toast.LENGTH_SHORT).show()
        }
    }
}
