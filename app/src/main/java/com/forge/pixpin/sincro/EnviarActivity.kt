package com.forge.pixpin.sincro

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.tamanoLegible
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Enviar por Wi-Fi, una sola vez.** Ver [Envio].
 *
 * Llega de tres sitios: «Compartir» en cualquier aplicación (uno o varios archivos), el menú de un
 * proyecto (el proyecto entero) y el botón «Enviar» de Sincronizar (elegir archivos). Enseña un
 * código y su QR, espera a que el otro lo use, manda, y al acabar **cierra y olvida el código**.
 */
class EnviarActivity : ComponentActivity() {

    private sealed interface Estado {
        data object Preparando : Estado
        data class Esperando(val codigo: String, val qr: Bitmap, val intentosMalos: Int = 0) : Estado
        data class Conectado(val quien: String) : Estado
        data class Enviando(val quien: String, val hechos: Long, val total: Long) : Estado
        data class Hecho(val quien: String) : Estado
        data class Rechazado(val quien: String) : Estado
        data class Fallo(val texto: String) : Estado
    }

    private var estado by mutableStateOf<Estado>(Estado.Preparando)
    private var cosas by mutableStateOf<List<Pair<Envio.Elemento, File>>>(emptyList())
    private var red: Red? = null
    private val carpeta by lazy { File(cacheDir, "envio/${System.currentTimeMillis()}").apply { mkdirs() } }

    private val elegir = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) { finish(); return@registerForActivityResult }
        lifecycleScope.launch { preparar { deUris(uris) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { PixPinTheme { Pantalla() } }
        if (savedInstanceState != null) { finish(); return }
        val proyecto = intent.getStringExtra(EXTRA_PROYECTO)
        val hoja = intent.getStringExtra(EXTRA_HOJA)
        val ruta = intent.getStringExtra(EXTRA_RUTA)
        when {
            proyecto != null && hoja != null -> lifecycleScope.launch { preparar { deLienzo(proyecto, hoja) } }
            proyecto != null -> lifecycleScope.launch { preparar { deProyecto(proyecto) } }
            ruta != null -> lifecycleScope.launch { preparar { deRuta(File(ruta), intent.getStringExtra(EXTRA_NOMBRE)) } }
            intent.getBooleanExtra(EXTRA_ELEGIR, false) -> elegir.launch(arrayOf("*/*"))
            else -> {
                // **Se copia ya**: el permiso sobre lo compartido se va en cuanto esta pantalla muere.
                val uris = when (intent.action) {
                    Intent.ACTION_SEND -> listOfNotNull(uriDe(intent))
                    Intent.ACTION_SEND_MULTIPLE -> urisDe(intent)
                    else -> emptyList()
                }
                if (uris.isEmpty()) { estado = Estado.Fallo("No llegó ningún archivo que enviar."); return }
                lifecycleScope.launch { preparar { deUris(uris) } }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        red?.cerrar()
        red = null
        if (isFinishing) Thread { runCatching { carpeta.deleteRecursively() } }.start()
    }

    // --------------------------------------------------------------- qué

    private fun yo(): Aparato = IdentidadEnDisco(filesDir).leer(android.os.Build.MODEL.orEmpty().ifBlank { "Este aparato" }).yo

    private fun deUris(uris: List<Uri>): List<Pair<Envio.Elemento, File>> {
        val quien = yo()
        return uris.mapNotNull { uri ->
            runCatching {
                val nombre = Envio.nombreSano(nombreDe(uri))
                val destino = File(carpeta, "${System.nanoTime()}-$nombre")
                contentResolver.openInputStream(uri)?.use { entrada -> destino.outputStream().use { entrada.copyTo(it) } } ?: return@runCatching null
                Envio.Elemento(Envio.ARCHIVO, nombre, destino.length(), contentResolver.getType(uri), "archivo:${quien.id}:$nombre") to destino
            }.getOrNull()
        }
    }

    /** Un archivo que ya es de PixPin (del chat): se copia para mandarlo tal cual. */
    private fun deRuta(origen: File, nombre: String?): List<Pair<Envio.Elemento, File>> {
        if (!origen.exists()) return emptyList()
        val quien = yo()
        val bonito = Envio.nombreSano(nombre?.ifBlank { null } ?: origen.name)
        val destino = File(carpeta, "${System.nanoTime()}-$bonito")
        origen.copyTo(destino, overwrite = true)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(origen.extension.lowercase())
        return listOf(Envio.Elemento(Envio.ARCHIVO, bonito, destino.length(), mime, "archivo:${quien.id}:$bonito") to destino)
    }

    /**
     * **Un solo lienzo de un proyecto**: un `.pixpin` con esa hoja, y con qué proyecto es para que
     * al otro lado caiga en el mismo. Ver [Recepcion].
     */
    private fun deLienzo(proyectoId: String, hojaId: String): List<Pair<Envio.Elemento, File>> {
        val app = application as PixPinApp
        val p = app.proyectos.porId(proyectoId) ?: return emptyList()
        val h = p.hojas.firstOrNull { it.id == hojaId } ?: return emptyList()
        val conPdf = h.pagina != null
        val solo = p.copy(hojas = listOf(h), croquis = emptyList(), pdfOrigen = p.pdfOrigen.takeIf { conPdf }, pdfLimpio = p.pdfLimpio.takeIf { conPdf })
        val nombre = h.nombre.ifBlank { if (h.pagina != null) "Página ${h.pagina + 1}" else "Lienzo" }
        val destino = File(carpeta, Envio.nombreSano("${p.nombre} - $nombre") + "." + com.forge.pixpin.motor.PaquetePixpin.EXTENSION)
        com.forge.pixpin.motor.PaquetePixpin.escribir(this, solo, destino) ?: return emptyList()
        val creado = com.forge.pixpin.guardados.MensajesStore(this).leer()
            .firstOrNull { it.proyecto == p.id && ((h.dibujo != null && it.referencia == h.dibujo) || it.id == h.deMensaje) }?.cuando ?: p.tocado
        val identidadDelProyecto = Recepcion.identidadDe(p)
        return listOf(
            Envio.Elemento(
                Envio.LIENZO, nombre, destino.length(), com.forge.pixpin.motor.PaquetePixpin.MIME_TYPE,
                identidad = "lienzo:${identidadDelProyecto.removePrefix("proyecto:")}:${h.origen ?: h.id}",
                proyecto = identidadDelProyecto, proyectoNombre = p.nombre, creado = creado
            ) to destino
        )
    }

    private fun deProyecto(id: String): List<Pair<Envio.Elemento, File>> {
        val app = application as PixPinApp
        val p = app.proyectos.porId(id) ?: return emptyList()
        val destino = File(carpeta, Envio.nombreSano(p.nombre) + "." + com.forge.pixpin.motor.PaquetePixpin.EXTENSION)
        com.forge.pixpin.motor.PaquetePixpin.escribir(
            this, p, destino,
            croquisDe = { c -> com.forge.pixpin.croquis3d.Croquis3DAlmacen.jsonDe(this, c) }
        ) ?: return emptyList()
        return listOf(Envio.Elemento(Envio.PROYECTO, p.nombre, destino.length(), com.forge.pixpin.motor.PaquetePixpin.MIME_TYPE, Recepcion.identidadDe(p)) to destino)
    }

    private suspend fun preparar(que: () -> List<Pair<Envio.Elemento, File>>) {
        estado = Estado.Preparando
        val lista = withContext(Dispatchers.IO) { runCatching(que).getOrDefault(emptyList()) }
        if (lista.isEmpty()) { estado = Estado.Fallo("No se pudo preparar lo que querías enviar."); return }
        cosas = lista
        abrirLaPuerta(lista)
    }

    // ------------------------------------------------------------- la red

    private suspend fun abrirLaPuerta(lista: List<Pair<Envio.Elemento, File>>) {
        val codigo = Envio.nuevoCodigo()
        val quien = yo()
        val etiqueta = withContext(Dispatchers.Default) { Envio.etiqueta(codigo) }
        val r = Red(this).also { red = it }
        var malos = 0
        r.escuchar(0) { socket, entrada ->
            if (red !== r) return@escuchar
            try {
                val final = Envio.Emisor(quien, lista).atender(
                    entrada, socket.getOutputStream(), codigo,
                    alConocer = { nombre -> estado = Estado.Conectado(nombre.ifBlank { "El otro aparato" }) },
                    avance = { hechos, total ->
                        val q = (estado as? Estado.Conectado)?.quien ?: (estado as? Estado.Enviando)?.quien ?: "el otro aparato"
                        estado = Estado.Enviando(q, hechos, total)
                    }
                )
                val q = (estado as? Estado.Enviando)?.quien ?: (estado as? Estado.Conectado)?.quien ?: "El otro aparato"
                estado = if (final == Envio.Final.ENVIADO) Estado.Hecho(q) else Estado.Rechazado(q)
                // **Una sola vez**: hecho o rechazado, el código deja de existir.
                runOnUiThread { r.cerrar() }
            } catch (e: Canal.CodigoDistinto) {
                malos++
                if (malos >= Envio.INTENTOS) {
                    estado = Estado.Fallo("Alguien probó varias veces con un código equivocado, así que este código ya no vale. Vuelve a enviar para sacar otro.")
                    runOnUiThread { r.cerrar() }
                } else (estado as? Estado.Esperando)?.let { estado = it.copy(intentosMalos = malos) }
            } catch (e: Exception) {
                estado = Estado.Fallo("Se cortó mientras se enviaba: ${e.message ?: e.javaClass.simpleName}")
                runOnUiThread { r.cerrar() }
            }
        }
        r.anunciar(Envio.TIPO, "PixPin ${quien.nombre}", mapOf("g" to etiqueta, "n" to quien.nombre.take(40)))
        val qr = withContext(Dispatchers.Default) { Qr.imagen(Envio.textoDelQr(codigo, Red.miDireccion(this@EnviarActivity), r.puerto)) }
        if (estado is Estado.Preparando) estado = Estado.Esperando(codigo, qr)
    }

    // ------------------------------------------------------------ pantalla

    @Composable
    private fun Pantalla() {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, contentDescription = "Cerrar") }
                    Text("Enviar por Wi-Fi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                LoQueSeEnvia()
                Spacer(Modifier.height(16.dp))
                when (val e = estado) {
                    Estado.Preparando -> Cargando("Preparando…")
                    is Estado.Esperando -> Esperando(e)
                    is Estado.Conectado -> Cargando("${e.quien} se ha conectado.\nEsperando a que acepte…")
                    is Estado.Enviando -> {
                        Text("Enviando a ${e.quien}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { if (e.total > 0) e.hechos.toFloat() / e.total else 0f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text("${tamanoLegible(e.hechos).ifBlank { "0 B" }} de ${tamanoLegible(e.total)}", style = MaterialTheme.typography.bodySmall)
                    }
                    is Estado.Hecho -> Final("Enviado a ${e.quien}", "El código ya no sirve: para enviar otra cosa, empieza otro envío.", bien = true)
                    is Estado.Rechazado -> Final("${e.quien} no lo aceptó", "No se envió nada y el código ya no sirve.", bien = false)
                    is Estado.Fallo -> Final("No se pudo", e.texto, bien = false)
                }
            }
        }
    }

    @Composable
    private fun LoQueSeEnvia() {
        if (cosas.isEmpty()) return
        Caja {
            Text(if (cosas.size == 1) "Vas a enviar" else "Vas a enviar ${cosas.size} cosas", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((e, _) in cosas.take(6)) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (e.tipo) { Envio.PROYECTO -> "Proyecto «${e.nombre}»"; Envio.LIENZO -> "Lienzo «${e.nombre}»"; else -> e.nombre },
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium
                    )
                    Text(tamanoLegible(e.bytes), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (cosas.size > 6) Text("y ${cosas.size - 6} más", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun Esperando(e: Estado.Esperando) {
        Text("En el otro teléfono: PixPin → Sincronizar → Recibir", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp)
        ) {
            Image(e.qr.asImageBitmap(), contentDescription = "Código QR del envío", modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(14.dp))
        Text("o escribe este código", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Envio.legible(e.codigo), fontFamily = FontFamily.Monospace, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Caja {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Los dos en la misma Wi-Fi", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Si no hay Wi-Fi, uno puede activar su zona Wi-Fi y el otro conectarse a ella. No se usa internet.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (e.intentosMalos > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Alguien probó con un código equivocado (${e.intentosMalos} de ${Envio.INTENTOS}).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Esperando… El código solo sirve para este envío.", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun Cargando(texto: String) {
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(14.dp))
        Text(texto, textAlign = TextAlign.Center)
    }

    @Composable
    private fun Final(titulo: String, texto: String, bien: Boolean) {
        Spacer(Modifier.height(20.dp))
        if (bien) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = VERDE, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(10.dp))
        Text(titulo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(texto, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Cerrar") }
    }

    // -------------------------------------------------------------- útiles

    @Suppress("DEPRECATION")
    private fun uriDe(i: Intent): Uri? = i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun urisDe(i: Intent): List<Uri> = i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

    private fun nombreDe(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { fila ->
            val i = fila.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && fila.moveToFirst()) fila.getString(i) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"

    companion object {
        private const val EXTRA_PROYECTO = "enviar_proyecto"
        private const val EXTRA_ELEGIR = "enviar_elegir"
        private const val EXTRA_HOJA = "enviar_hoja"
        private const val EXTRA_RUTA = "enviar_ruta"
        private const val EXTRA_NOMBRE = "enviar_nombre"

        /** Un solo lienzo (una hoja) de un proyecto. */
        fun enviarLienzo(context: Context, proyecto: String, hoja: String) {
            context.startActivity(Intent(context, EnviarActivity::class.java).putExtra(EXTRA_PROYECTO, proyecto).putExtra(EXTRA_HOJA, hoja))
        }

        /** Un archivo de PixPin, tal cual. */
        fun enviarArchivo(context: Context, ruta: String, nombre: String?) {
            context.startActivity(Intent(context, EnviarActivity::class.java).putExtra(EXTRA_RUTA, ruta).putExtra(EXTRA_NOMBRE, nombre))
        }

        fun enviarProyecto(context: Context, id: String) {
            context.startActivity(Intent(context, EnviarActivity::class.java).putExtra(EXTRA_PROYECTO, id))
        }

        fun elegirArchivos(context: Context) {
            context.startActivity(Intent(context, EnviarActivity::class.java).putExtra(EXTRA_ELEGIR, true))
        }
    }
}
