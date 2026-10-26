package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.motor.DrawExport
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.HojaDePlantilla
import com.forge.pixpin.pin.ImageStore
import com.forge.pixpin.ui.CompartirNativo
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **La pantalla de una carpeta de páginas** (25-oct-2026, portado del PC: `ventana_chat::carpetas`):
 * sus páginas en orden —miniatura, número y título—; tocar una la abre; subirla o bajarla de
 * puesto, cambiarle el título o quitarla de la carpeta (el lienzo sigue en el chat); «Página nueva»
 * (la plantilla de la última, vacía, con el título siguiente) y «Exportar PDF».
 *
 * Lo que se cambia aquí es el mensaje de la carpeta ([CarpetasDelChat.cambiar]), que es lo que viaja.
 * En el PC las páginas se arrastran para reordenarlas; aquí, con las flechas de «Antes» y
 * «Después», que con el dedo no se confunden con desplazar la lista.
 *
 * **La carpeta de una grabación** (25-oct-2026, PC e1150e8) se ve igual, adaptada: la nota y el vídeo
 * con su icono y lo que duran, las capturas con su foto y su minuto, en el orden de la grabación (no
 * se mueven: el orden es el del tiempo). Detrás, **lo adjuntado a la clase**, en ámbar y con la chapa
 * «Adjunto · no es de la grabación». Arriba, «Estudiar», «Adjuntar a la clase» y compartir la
 * grabación ([ExportarGrabacion]). Tocar una pieza de lo grabado abre la clase en su minuto. Y como
 * en el PC, **tocar la tarjeta del chat estudia la clase**: sin [EXTRA_ENTERA] (el «Abrir la carpeta»
 * del menú), esta pantalla abre «Estudiar la clase» y se va.
 */
class CarpetaActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_CARPETA = "carpeta"
        private const val EXTRA_ENTERA = "carpeta_entera"

        /**
         * Abre la carpeta [carpeta]. La de una grabación, sin [entera], estudia su clase (lo que hace
         * tocar su tarjeta en el chat); con [entera], enseña lo grabado y lo adjuntado.
         */
        fun abrir(context: Context, carpeta: String, entera: Boolean = false) {
            // Desde fuera y sin la aplicación abierta, con la lista de chats debajo. Ver [com.forge.pixpin.Volver.abrirEnLaApp].
            com.forge.pixpin.Volver.abrirEnLaApp(
                context, Intent(context, CarpetaActivity::class.java).putExtra(EXTRA_CARPETA, carpeta).putExtra(EXTRA_ENTERA, entera)
            )
        }

        /**
         * **Abre una página** con lo que le toca: un lienzo, en el editor; una página de un PDF, en
         * el editor sobre su PDF; lo demás, con su visor del chat ([MensajesActivity.abrirYAbrir]).
         *
         * **Lo que se ve al momento, sin salir de la carpeta** (26-oct-2026, el usuario: «un icono
         * para abrir las carpetas y todo eso, para visualizar también»): el vídeo, en
         * [VisorDeVideoActivity]; la nota de voz suena con la barra del reproductor de esta pantalla;
         * la foto, en el editor sobre ella (como en el chat); un PDF, en su lector. Antes todo eso
         * pasaba por la conversación general, que se ponía delante de la carpeta y atrás ya no
         * volvía a ella. Si el fichero aún no ha llegado (la sincronización lo trae detrás), se dice.
         */
        fun abrirPagina(context: Context, m: Mensaje) {
            val ref = m.referencia
            val ruta = m.ruta
            val hay = ruta != null && File(ruta).isFile
            when {
                m.clase == Clase.DIBUJO && ref != null ->
                    com.forge.pixpin.motor.DrawEditorActivity.abrir(context, ref, ExcalidrawStore.rutaDe(context, ref), null)
                m.clase == Clase.PAGINA && ref != null && m.ruta != null && m.pagina != null ->
                    com.forge.pixpin.motor.DrawEditorActivity.abrirPaginaDePdf(context, ref, ExcalidrawStore.rutaDe(context, ref), m.ruta, m.pagina)
                ruta != null && !hay && m.clase != Clase.NOTA ->
                    Toast.makeText(context, "«${m.nombre.ifBlank { "Esto" }}» aún no ha llegado a este aparato: sincroniza con el otro", Toast.LENGTH_LONG).show()
                ruta != null && CarpetaDePaginas.piezaDe(m) == CarpetaDePaginas.Pieza.VIDEO && VisorDeVideoActivity.seVeAqui(ruta) ->
                    VisorDeVideoActivity.abrir(context, ruta, m.nombre.ifBlank { File(ruta).name })
                ruta != null && m.clase == Clase.VOZ -> Reproductor.alternar(ruta, tituloDeAudio(context, m))
                ruta != null && m.clase == Clase.IMAGEN -> {
                    // Como el chat: la foto se abre en el editor y se le apunta su dibujo la primera vez.
                    val dibujo = m.dibujoDeLaFoto
                    if (m.referencia == null) Thread {
                        runCatching { MensajesStore(context).cambiar { l -> l.map { if (it.id == m.id) it.copy(referencia = dibujo) else it } } }
                    }.start()
                    com.forge.pixpin.motor.DrawEditorActivity.abrir(context, dibujo, ExcalidrawStore.rutaDe(context, dibujo), if (m.referencia == null) ruta else null)
                }
                ruta != null && ruta.substringAfterLast('.', "").equals("pdf", ignoreCase = true) ->
                    com.forge.pixpin.pdf.LectorPdfActivity.abrir(context, ruta, m.nombre.ifBlank { File(ruta).name }, mensaje = m.id)
                else -> MensajesActivity.abrirYAbrir(context, m.id)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_CARPETA)
        if (id == null) {
            finish()
            return
        }
        val entera = intent.getBooleanExtra(EXTRA_ENTERA, false)
        if (!entera) {
            // La de una grabación con su nota: su vista natural es «Estudiar la clase».
            val voz = runCatching {
                MensajesStore(this).leer().firstOrNull { it.id == id }?.let(CarpetaDePaginas::deMensaje)
                    ?.takeIf { it.esGrabacion }?.voz?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            if (voz != null) {
                EstudiarClaseActivity.abrir(this, voz)
                finish()
                return
            }
        }
        setContent { PixPinTheme { Pantalla(id) } }
    }

    /** La carpeta a la que va el fichero elegido para «Adjuntar a la clase». */
    private var adjuntandoA: String? = null
    private val elegirAdjunto = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val carpeta = adjuntandoA
        if (uri != null && carpeta != null) Thread {
            val puesto = runCatching { CarpetasDelChat.adjuntarALaClase(this, carpeta, uri) }.getOrNull()
            runOnUiThread {
                Toast.makeText(this, if (puesto != null) "Adjuntado a la clase: $puesto" else "No se pudo adjuntar", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Pantalla(id: String) {
        val cambios by MensajesStore.cambios.collectAsState()
        val alcance = rememberCoroutineScope()
        // Todo el chat, releído cada vez que alguien lo escribe (aquí, el editor, la sincronización).
        val todos by produceState<List<Mensaje>?>(null, cambios) {
            value = withContext(Dispatchers.IO) { runCatching { MensajesStore(this@CarpetaActivity).leer() }.getOrNull() }
        }
        val lista = todos ?: return
        val carpeta = lista.firstOrNull { it.id == id }
        val documento = carpeta?.let { CarpetaDePaginas.deMensaje(it) }
        LaunchedEffect(carpeta == null || documento == null) {
            if (carpeta == null || documento == null) {
                Toast.makeText(this@CarpetaActivity, "Esa carpeta ya no está en el chat", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        if (carpeta == null || documento == null) return
        // En una grabación, lo grabado delante y lo adjuntado detrás (`sort_by_key(adjunto)` del PC).
        val grabacion = documento.esGrabacion
        val paginas = documento.vivas(lista).map { (i, p) -> lista[i] to p }
            .let { l -> if (grabacion) l.sortedBy { it.second.adjunto } else l }
        val nombre = carpeta.nombre.ifBlank { CarpetasDelChat.NOMBRE_DE_SERIE }
        var compartiendo by remember { mutableStateOf<com.forge.pixpin.ui.Compartible?>(null) }

        var renombrandoCarpeta by remember { mutableStateOf(false) }
        var renombrandoPagina by remember { mutableStateOf<CarpetaDePaginas.Pagina?>(null) }
        var trabajando by remember { mutableStateOf(false) }

        fun cambiar(cambio: (CarpetaDePaginas) -> CarpetaDePaginas?) {
            alcance.launch(Dispatchers.IO) { CarpetasDelChat.cambiar(this@CarpetaActivity, id, cambio) }
        }

        Scaffold(
            // La nota de voz que se toca aquí suena con su barra, sin salir de la carpeta.
            bottomBar = { BarraDelReproductor() },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                    },
                    title = {
                        // El icono de la barra del PC: el micrófono en una grabación, el libro en un cuaderno.
                        Row(Modifier.clickable { renombrandoCarpeta = true }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (grabacion) Icons.Filled.Mic else Icons.AutoMirrored.Filled.MenuBook, null,
                                modifier = Modifier.padding(end = 10.dp).size(24.dp)
                            )
                            Column {
                                Text(nombre, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (grabacion) CarpetasDelChat.detalleDeGrabacion(documento) else cuantas(paginas.size),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        if (grabacion) {
                            // Estudiar (si hay nota), adjuntar y compartir la grabación entera.
                            if (documento.voz.isNotEmpty()) IconButton(onClick = { EstudiarClaseActivity.abrir(this@CarpetaActivity, documento.voz) }) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, "Estudiar")
                            }
                            IconButton(onClick = { adjuntandoA = id; elegirAdjunto.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Filled.AttachFile, "Adjuntar a la clase")
                            }
                            IconButton(onClick = {
                                alcance.launch {
                                    compartiendo = withContext(Dispatchers.IO) {
                                        runCatching {
                                            ExportarGrabacion.notaDe(carpeta, lista)?.let { ExportarGrabacion.compartible(this@CarpetaActivity, it, lista) }
                                        }.getOrNull()
                                    }
                                }
                            }) { Icon(Icons.Filled.IosShare, "Compartir la grabación") }
                        } else IconButton(enabled = !trabajando && paginas.isNotEmpty(), onClick = {
                            trabajando = true
                            Toast.makeText(this@CarpetaActivity, "Exportando el PDF…", Toast.LENGTH_SHORT).show()
                            alcance.launch {
                                val pdf = withContext(Dispatchers.IO) {
                                    runCatching { CarpetaEnPdf.escribir(this@CarpetaActivity, nombre, paginas.map { it.first }) }
                                        .onFailure { android.util.Log.e("PixPinCarpeta", "pdf", it) }.getOrNull()
                                }
                                trabajando = false
                                if (pdf == null) Toast.makeText(this@CarpetaActivity, "No se pudo exportar: no hay ninguna página con algo dentro", Toast.LENGTH_LONG).show()
                                else CompartirNativo.archivo(this@CarpetaActivity, pdf, "application/pdf", pdf.name)
                            }
                        }) { Icon(Icons.Filled.IosShare, "Exportar PDF") }
                    }
                )
            },
            floatingActionButton = {
                if (!grabacion) ExtendedFloatingActionButton(
                    text = { Text("Página nueva") },
                    icon = { Icon(Icons.Filled.Add, null) },
                    onClick = {
                        if (trabajando) return@ExtendedFloatingActionButton
                        trabajando = true
                        alcance.launch {
                            val m = withContext(Dispatchers.IO) {
                                runCatching { CarpetasDelChat.paginaSiguiente(this@CarpetaActivity, id) }
                                    .onFailure { android.util.Log.e("PixPinCarpeta", "página nueva", it) }.getOrNull()
                            }
                            trabajando = false
                            if (m != null) abrirPagina(this@CarpetaActivity, m)
                            else Toast.makeText(this@CarpetaActivity, "No se pudo crear la página", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        ) { relleno ->
            if (paginas.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(relleno).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Esta carpeta está vacía. Pulsa «Página nueva», o en el chat elige «Añadir a carpeta…» en el menú de un lienzo o de una imagen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Scaffold
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(relleno),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(paginas, key = { _, (m, _) -> m.id }) { i, (m, p) ->
                    if (grabacion) FilaDeGrabacion(m, p, documento, onQuitar = { cambiar { c -> c.quitar(m.id) } })
                    else FilaDePagina(
                        numero = i + 1,
                        m = m,
                        titulo = p.titulo.ifBlank { m.nombre.ifBlank { CarpetasDelChat.PAGINA } },
                        primera = i == 0,
                        ultima = i == paginas.lastIndex,
                        onAbrir = { abrirPagina(this@CarpetaActivity, m) },
                        onSubir = { cambiar { c -> c.subir(c.posicion(m.id)) } },
                        onBajar = { cambiar { c -> c.posicion(m.id).takeIf { it >= 0 }?.let(c::bajar) } },
                        onRenombrar = { renombrandoPagina = p },
                        onQuitar = { cambiar { c -> c.quitar(m.id) } }
                    )
                }
            }
        }

        if (renombrandoCarpeta) {
            Renombrar(nombre, "Nombre de la carpeta", onCerrar = { renombrandoCarpeta = false }) { nuevo ->
                val limpio = CarpetaDePaginas.limpio(nuevo)
                if (limpio.isNotEmpty()) alcance.launch(Dispatchers.IO) {
                    MensajesStore(this@CarpetaActivity).cambiar { l -> l.map { if (it.id == id) it.copy(nombre = limpio) else it } }
                }
            }
        }
        compartiendo?.let { c -> com.forge.pixpin.ui.HojaDeCompartir(c) { compartiendo = null } }
        renombrandoPagina?.let { p ->
            Renombrar(p.titulo, "Título de la página", onCerrar = { renombrandoPagina = null }) { nuevo ->
                cambiar { c -> c.renombrar(c.posicion(p.id), nuevo) }
            }
        }
    }

    private fun cuantas(n: Int) = when (n) {
        0 -> "Vacía"
        1 -> "1 página"
        else -> "$n páginas"
    }

    @Composable
    private fun FilaDePagina(
        numero: Int,
        m: Mensaje,
        titulo: String,
        primera: Boolean,
        ultima: Boolean,
        onAbrir: () -> Unit,
        onSubir: () -> Unit,
        onBajar: () -> Unit,
        onRenombrar: () -> Unit,
        onQuitar: () -> Unit
    ) {
        var menu by remember { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onAbrir)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(72.dp, 96.dp).clip(RoundedCornerShape(4.dp)).background(Color.White),
                contentAlignment = Alignment.Center
            ) { MiniaturaDePaginaDeCarpeta(m, Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$numero", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Icon(
                        iconoDePieza(CarpetaDePaginas.piezaDe(m)), null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp).size(14.dp)
                    )
                }
                Text(titulo, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (m.nombre.isNotBlank() && m.nombre != titulo) {
                    Text(m.nombre, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column {
                IconButton(enabled = !primera, onClick = onSubir, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Antes") }
                IconButton(enabled = !ultima, onClick = onBajar, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Después") }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Cambiar título") }, onClick = { menu = false; onRenombrar() })
                    DropdownMenuItem(text = { Text("Quitar de la carpeta") }, onClick = { menu = false; onQuitar() })
                }
            }
        }
    }

    /**
     * **Una pieza de una grabación**: la nota y el vídeo con su icono y lo que duran, una captura con
     * su foto y su minuto; lo adjuntado, en ámbar y con su chapa. Lo grabado se toca para estudiarlo
     * en su minuto; lo adjuntado se abre como en el chat.
     */
    @Composable
    private fun FilaDeGrabacion(m: Mensaje, p: CarpetaDePaginas.Pagina, c: CarpetaDePaginas, onQuitar: () -> Unit) {
        var menu by remember { mutableStateOf(false) }
        val adjunto = p.adjunto
        val esquina = when {
            adjunto -> ""
            m.clase == Clase.VOZ || m.id == c.video -> GrabarClase.minuto(m.duracionMs.toLong())
            else -> "⏱ " + GrabarClase.minuto(p.ms ?: 0)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (adjunto) COLOR_ADJUNTO.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    // Lo grabado se estudia en su minuto, con el texto al lado.
                    if (!adjunto && c.voz.isNotEmpty()) EstudiarClaseActivity.abrir(this@CarpetaActivity, c.voz, p.ms ?: 0)
                    else abrirPagina(this@CarpetaActivity, m)
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(96.dp, 72.dp).clip(RoundedCornerShape(4.dp)).background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                when {
                    m.clase == Clase.VOZ -> Icon(Icons.Filled.Mic, null, tint = Color.Gray, modifier = Modifier.size(32.dp))
                    else -> MiniaturaDePaginaDeCarpeta(m, Modifier.fillMaxSize())
                }
                // El vídeo, con su fotograma y el triángulo encima: se ve que se reproduce.
                if (CarpetaDePaginas.piezaDe(m) == CarpetaDePaginas.Pieza.VIDEO) Icon(
                    Icons.Filled.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(32.dp)
                )
                if (esquina.isNotEmpty()) Text(
                    esquina, color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 6.dp)
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        iconoDePieza(CarpetaDePaginas.piezaDe(m)), null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp).size(16.dp)
                    )
                    Text(nombreDePieza(CarpetaDePaginas.piezaDe(m)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(p.titulo.ifBlank { m.nombre.ifBlank { CarpetasDelChat.PAGINA } }, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (adjunto) Text(
                    "Adjunto · no es de la grabación", color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp).background(COLOR_ADJUNTO, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Abrir") }, onClick = { menu = false; abrirPagina(this@CarpetaActivity, m) })
                    DropdownMenuItem(text = { Text("Quitar de la carpeta") }, onClick = { menu = false; onQuitar() })
                }
            }
        }
    }

    @Composable
    private fun Renombrar(actual: String, rotulo: String, onCerrar: () -> Unit, onGuardar: (String) -> Unit) {
        var texto by remember { mutableStateOf(actual) }
        AlertDialog(
            onDismissRequest = onCerrar,
            title = { Text(rotulo) },
            text = { OutlinedTextField(texto, { texto = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onGuardar(texto); onCerrar() }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
        )
    }
}

/**
 * El color de lo adjuntado a una clase (no es de lo grabado): el ámbar del PC (`COLOR_ADJUNTO`,
 * 0.80/0.45/0.05), que se lee igual en claro y en oscuro.
 */
val COLOR_ADJUNTO = Color(0xFFCC730D)

/**
 * **El icono de cada pieza**, el mismo glifo que el PC ([CarpetaDePaginas.Pieza]; `icono_de` de
 * `carpetas.rs`, Material Icons allí y aquí).
 */
fun iconoDePieza(p: CarpetaDePaginas.Pieza): androidx.compose.ui.graphics.vector.ImageVector = when (p) {
    CarpetaDePaginas.Pieza.VIDEO -> Icons.Filled.Videocam
    CarpetaDePaginas.Pieza.LIENZO -> Icons.Filled.Draw
    CarpetaDePaginas.Pieza.PAGINA -> Icons.AutoMirrored.Filled.MenuBook
    CarpetaDePaginas.Pieza.IMAGEN -> Icons.Filled.Image
    CarpetaDePaginas.Pieza.VOZ -> Icons.Filled.Mic
    CarpetaDePaginas.Pieza.NOTA -> Icons.Filled.EditNote
    CarpetaDePaginas.Pieza.ARCHIVO -> Icons.Filled.Description
}

/** Cómo se llama cada pieza en su fila de la carpeta. */
fun nombreDePieza(p: CarpetaDePaginas.Pieza): String = when (p) {
    CarpetaDePaginas.Pieza.VIDEO -> "Vídeo"
    CarpetaDePaginas.Pieza.LIENZO -> "Lienzo"
    CarpetaDePaginas.Pieza.PAGINA -> "Página de PDF"
    CarpetaDePaginas.Pieza.IMAGEN -> "Imagen"
    CarpetaDePaginas.Pieza.VOZ -> "Nota de voz"
    CarpetaDePaginas.Pieza.NOTA -> "Nota"
    CarpetaDePaginas.Pieza.ARCHIVO -> "Archivo"
}

/**
 * **La miniatura de una página de la carpeta**: de un lienzo, su primera hoja (la de la plantilla,
 * si no el marco, si no todo: lo mismo que saldrá en el PDF); de una página de un PDF, la página con
 * lo anotado; de una foto, la foto; de un vídeo, su primer fotograma (26-oct-2026); de lo demás, el
 * icono de lo que es ([iconoDePieza]), como el PC cuando no hay dibujo que enseñar.
 */
@Composable
fun MiniaturaDePaginaDeCarpeta(m: Mensaje, modifier: Modifier = Modifier, lado: Int = 240) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val version = remember(m.referencia, m.ruta) {
        m.referencia?.let { File(ExcalidrawStore.rutaDe(contexto, it)).lastModified() }
            ?: m.ruta?.let { File(it).lastModified() } ?: 0L
    }
    val mapa by produceState<Bitmap?>(null, m.id, m.referencia, version) {
        value = withContext(Dispatchers.IO) { runCatching { miniatura(contexto, m, lado) }.getOrNull() }
    }
    val actual = mapa
    if (actual != null) {
        Image(actual.asImageBitmap(), contentDescription = m.nombre, contentScale = ContentScale.Fit, modifier = modifier)
    } else if (m.clase == Clase.NOTA && m.texto.isNotBlank()) {
        Text(m.texto, fontSize = 7.sp, lineHeight = 8.sp, color = Color.DarkGray, overflow = TextOverflow.Clip, modifier = modifier.padding(4.dp))
    } else {
        Icon(iconoDePieza(CarpetaDePaginas.piezaDe(m)), contentDescription = null, tint = Color.Gray)
    }
}

private suspend fun miniatura(contexto: Context, m: Mensaje, lado: Int): Bitmap? = when {
    CarpetaDePaginas.piezaDe(m) == CarpetaDePaginas.Pieza.VIDEO -> m.ruta?.takeIf { File(it).isFile }?.let { fotogramaDe(it, lado) }
    m.clase == Clase.DIBUJO -> m.referencia?.let { ref ->
        val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, ref)) ?: return null
        val hoja = HojaDePlantilla.hojasDeCuaderno(escena, CarpetasDelChat.catalogo(contexto)).firstOrNull() ?: return null
        val escala = (lado / maxOf(hoja.caja.width, hoja.caja.height, 1.0)).coerceIn(0.02, 2.0)
        DrawExport.aBitmap(escena.copy(elements = hoja.contenido), escala, recorte = hoja.caja) { id ->
            escena.files[id]?.path?.let { ImageStore.load(it, lado) }
        }
    }
    m.clase == Clase.PAGINA -> {
        val pdf = m.ruta
        val n = m.pagina
        if (pdf == null || n == null) null
        else paginaAnotada(contexto, pdf, n, m.referencia, m.referencia?.let { ExcalidrawStore.rutaDe(contexto, it) }, lado)
    }
    m.clase == Clase.IMAGEN -> m.ruta?.let { ImageStore.load(it, lado) }
    else -> null
}

/** El primer fotograma de un vídeo, a [lado] como mucho. */
private fun fotogramaDe(ruta: String, lado: Int): Bitmap? {
    val r = android.media.MediaMetadataRetriever()
    return try {
        r.setDataSource(ruta)
        val f = r.getFrameAtTime(0) ?: return null
        val escala = lado.toFloat() / maxOf(f.width, f.height, 1)
        if (escala >= 1f) f
        else Bitmap.createScaledBitmap(f, (f.width * escala).toInt().coerceAtLeast(1), (f.height * escala).toInt().coerceAtLeast(1), true)
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { r.release() }
    }
}

/**
 * **Lo que la tarjeta de una carpeta sabe de sus piezas**: las que siguen en el chat, en su orden, y
 * cuántas nombran un fichero que aún no está en este aparato (la sincronización trae los mensajes
 * primero y los ficheros detrás: un vídeo de una hora tarda).
 */
private data class PiezasDeLaTarjeta(val vivas: List<Mensaje> = emptyList(), val sinLlegar: Int = 0)

@Composable
private fun piezasDe(m: Mensaje, documento: CarpetaDePaginas?): PiezasDeLaTarjeta {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val cambios by MensajesStore.cambios.collectAsState()
    val piezas by produceState(PiezasDeLaTarjeta(), m.texto, cambios) {
        val c = documento ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val todos = MensajesStore(contexto).leer()
                val vivas = c.vivas(todos).map { todos[it.first] }
                PiezasDeLaTarjeta(vivas, vivas.count { p -> p.ruta?.let { !File(it).isFile } == true })
            }.getOrDefault(PiezasDeLaTarjeta())
        }
    }
    return piezas
}

/**
 * **La tarjeta de una carpeta en el chat** (`ventana_chat` del PC): el cuaderno, su nombre, cuántas
 * páginas y las primeras en miniatura. Antes, un mensaje así se veía como su JSON en crudo, que es
 * lo que hace el chat con una mini-app que no conoce. Tocarla abre [CarpetaActivity].
 *
 * **Con su botón de abrir, como en Windows** (26-oct-2026, el usuario: «tienen que aparecer las
 * carpetas, grabaciones, todo con iconos como en Windows, un icono para abrir las carpetas»): arriba
 * el icono de lo que es (el libro de un cuaderno, el micrófono de una grabación: los glifos del PC),
 * el nombre, lo que lleva y el botón de la carpeta abierta (lo que en el PC es «Abrir la carpeta» de
 * su menú). Si algo de dentro aún no ha llegado a este aparato, lo dice: así no parece que la
 * grabación esté rota mientras el vídeo viaja.
 */
@Composable
fun TarjetaDeCarpeta(m: Mensaje) {
    val documento = remember(m.texto) { CarpetaDePaginas.deMensaje(m) }
    val piezas = piezasDe(m, documento)
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val grabacion = documento?.esGrabacion == true
    val rojo = Color(0xFFE53935)
    Column(Modifier.widthIn(min = 220.dp, max = 290.dp).padding(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (grabacion) rojo.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (grabacion) Icons.Filled.Mic else Icons.AutoMirrored.Filled.MenuBook, contentDescription = null,
                    tint = if (grabacion) rojo else MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)
                )
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    m.nombre.ifBlank { if (grabacion) "Grabación" else CarpetasDelChat.NOMBRE_DE_SERIE },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        documento == null -> "Carpeta de páginas"
                        grabacion -> CarpetasDelChat.detalleDeGrabacion(documento)
                        piezas.vivas.isEmpty() -> "Vacía"
                        piezas.vivas.size == 1 -> "1 página"
                        else -> "${piezas.vivas.size} páginas"
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
            // «Abrir la carpeta»: lo que lleva, cada cosa con su icono y su visor.
            IconButton(onClick = { CarpetaActivity.abrir(contexto, m.id, entera = true) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "Abrir la carpeta", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (documento != null && grabacion) CuerpoDeGrabacion(m, documento, piezas) else CuerpoDeCuaderno(piezas)
        if (piezas.sinLlegar > 0) Text(
            if (piezas.sinLlegar == 1) "1 archivo aún no ha llegado a este aparato"
            else "${piezas.sinLlegar} archivos aún no han llegado a este aparato",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Cuántas páginas se ven en la tarjeta de un cuaderno, una al lado de otra (`PAGINAS_EN_LA_TARJETA` del PC). */
private const val PAGINAS_EN_LA_TARJETA = 3

/**
 * **El cuaderno** (`pintar_ojeada_carpeta` del PC): sus primeras páginas en fila, con su número en la
 * esquina, y cuántas más lleva («+4»). Vacío, el libro.
 */
@Composable
private fun CuerpoDeCuaderno(piezas: PiezasDeLaTarjeta) {
    if (piezas.vivas.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp)) }
    } else Box(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            piezas.vivas.take(PAGINAS_EN_LA_TARJETA).forEachIndexed { k, p ->
                Box(
                    Modifier.width(72.dp).height(96.dp).clip(RoundedCornerShape(4.dp)).background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    MiniaturaDePaginaDeCarpeta(p, Modifier.fillMaxSize(), lado = 200)
                    Text(
                        "${k + 1}", color = Color.White, fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(3.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 5.dp)
                    )
                }
            }
        }
        if (piezas.vivas.size > PAGINAS_EN_LA_TARJETA) Text(
            "+${piezas.vivas.size - PAGINAS_EN_LA_TARJETA}", color = Color.White, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * **La clase grabada** (25-oct-2026, `pintar_ojeada_grabacion` del PC, e1150e8): una sola tarjeta en
 * el chat por toda la grabación. La primera captura de fondo (sin capturas, el fotograma del vídeo o
 * el icono del micrófono), arriba la chapa «● Grabación», abajo lo que duró y cuántas capturas lleva.
 * Debajo, **la tira de lo que lleva** (26-oct-2026): la nota, el vídeo y las capturas en pequeño, cada
 * una con su icono, y los dos botones: «Estudiar» (el libro, como la pastilla del PC) y «Abrir» (la
 * carpeta). Tocar la tarjeta fuera de los botones estudia la clase, como en el PC.
 */
@Composable
private fun CuerpoDeGrabacion(m: Mensaje, c: CarpetaDePaginas, piezas: PiezasDeLaTarjeta) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val porId = remember(piezas) { piezas.vivas.associateBy { it.id } }
    val primera = remember(piezas, c) {
        c.capturas().firstNotNullOfOrNull { p -> porId[p.id]?.takeIf { it.clase == Clase.IMAGEN } } ?: porId[c.video]
    }
    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        if (primera != null) MiniaturaDePaginaDeCarpeta(primera, Modifier.fillMaxSize(), lado = 480)
        else Icon(
            if (c.video.isEmpty()) Icons.Filled.Mic else Icons.Filled.Videocam, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp)
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE53935)))
            Text("Grabación", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(start = 5.dp))
        }
        Text(
            CarpetasDelChat.detalleDeGrabacion(c), color = Color.White, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp)
        )
        if (c.video.isNotEmpty() && porId[c.video] != null) Icon(
            Icons.Filled.PlayCircle, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(44.dp)
        )
    }
    // La tira: lo grabado en pequeño, en su orden, con el icono de cada cosa.
    val tira = remember(piezas, c) { c.deLaGrabacion().mapNotNull { porId[it.id] } }
    if (tira.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tira.take(TIRA).forEach { p ->
            Box(
                Modifier.size(48.dp, 36.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (p.clase == Clase.IMAGEN) MiniaturaDePaginaDeCarpeta(p, Modifier.fillMaxSize(), lado = 120)
                else Icon(iconoDePieza(CarpetaDePaginas.piezaDe(p)), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        if (tira.size > TIRA) Box(Modifier.size(48.dp, 36.dp), contentAlignment = Alignment.Center) {
            Text("+${tira.size - TIRA}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (c.voz.isNotEmpty()) Pastilla(Icons.AutoMirrored.Filled.MenuBook, "Estudiar", MaterialTheme.colorScheme.primary, Color.White) {
            EstudiarClaseActivity.abrir(contexto, c.voz)
        }
        Pastilla(Icons.Filled.FolderOpen, "Abrir", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface) {
            CarpetaActivity.abrir(contexto, m.id, entera = true)
        }
    }
}

/** Cuántas piezas caben en la tira de la tarjeta de una grabación. */
private const val TIRA = 5

/** Un botón redondeado con su icono y su rótulo, como las pastillas del PC. */
@Composable
private fun Pastilla(icono: androidx.compose.ui.graphics.vector.ImageVector, rotulo: String, fondo: Color, tinta: Color, alTocar: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(fondo).clickable(onClick = alTocar).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icono, null, tint = tinta, modifier = Modifier.size(16.dp))
        Text(rotulo, color = tinta, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    }
}
