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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
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
            context.startActivity(
                Intent(context, CarpetaActivity::class.java).putExtra(EXTRA_CARPETA, carpeta)
                    .putExtra(EXTRA_ENTERA, entera)
                    .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }

        /**
         * **Abre una página** con lo que le toca: un lienzo, en el editor; una página de un PDF, en
         * el editor sobre su PDF; lo demás, con su visor del chat ([MensajesActivity.abrirYAbrir]).
         */
        fun abrirPagina(context: Context, m: Mensaje) {
            val ref = m.referencia
            when {
                m.clase == Clase.DIBUJO && ref != null ->
                    com.forge.pixpin.motor.DrawEditorActivity.abrir(context, ref, ExcalidrawStore.rutaDe(context, ref), null)
                m.clase == Clase.PAGINA && ref != null && m.ruta != null && m.pagina != null ->
                    com.forge.pixpin.motor.DrawEditorActivity.abrirPaginaDePdf(context, ref, ExcalidrawStore.rutaDe(context, ref), m.ruta, m.pagina)
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
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                    },
                    title = {
                        Column(Modifier.clickable { renombrandoCarpeta = true }) {
                            Text(nombre, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (grabacion) CarpetasDelChat.detalleDeGrabacion(documento) else cuantas(paginas.size),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        if (grabacion) {
                            // Estudiar (si hay nota), adjuntar y compartir la grabación entera.
                            if (documento.voz.isNotEmpty()) IconButton(onClick = { EstudiarClaseActivity.abrir(this@CarpetaActivity, documento.voz) }) {
                                Icon(Icons.Filled.School, "Estudiar")
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
                            }) { Icon(com.forge.pixpin.ui.IconoDeCompartir, "Compartir la grabación") }
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
                        }) { Icon(Icons.Filled.PictureAsPdf, "Exportar PDF") }
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
                Text("$numero", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
                    m.clase == Clase.VOZ -> Icon(Icons.Filled.Mic, null, tint = Color.Gray)
                    m.id == c.video -> Icon(Icons.Filled.Videocam, null, tint = Color.Gray)
                    else -> MiniaturaDePaginaDeCarpeta(m, Modifier.fillMaxSize())
                }
                if (esquina.isNotEmpty()) Text(
                    esquina, color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 6.dp)
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
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
 * **La miniatura de una página de la carpeta**: de un lienzo, su primera hoja (la de la plantilla,
 * si no el marco, si no todo: lo mismo que saldrá en el PDF); de una página de un PDF, la página con
 * lo anotado; de una foto, la foto; de lo demás, su icono.
 */
@Composable
fun MiniaturaDePaginaDeCarpeta(m: Mensaje, modifier: Modifier = Modifier, lado: Int = 240) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val version = remember(m.referencia, m.ruta) {
        m.referencia?.let { File(ExcalidrawStore.rutaDe(contexto, it)).lastModified() } ?: 0L
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
        Icon(Icons.Filled.Description, contentDescription = null, tint = Color.Gray)
    }
}

private suspend fun miniatura(contexto: Context, m: Mensaje, lado: Int): Bitmap? = when (m.clase) {
    Clase.DIBUJO -> m.referencia?.let { ref ->
        val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, ref)) ?: return null
        val hoja = HojaDePlantilla.hojasDeCuaderno(escena, CarpetasDelChat.catalogo(contexto)).firstOrNull() ?: return null
        val escala = (lado / maxOf(hoja.caja.width, hoja.caja.height, 1.0)).coerceIn(0.02, 2.0)
        DrawExport.aBitmap(escena.copy(elements = hoja.contenido), escala, recorte = hoja.caja) { id ->
            escena.files[id]?.path?.let { ImageStore.load(it, lado) }
        }
    }
    Clase.PAGINA -> {
        val pdf = m.ruta
        val n = m.pagina
        if (pdf == null || n == null) null
        else paginaAnotada(contexto, pdf, n, m.referencia, m.referencia?.let { ExcalidrawStore.rutaDe(contexto, it) }, lado)
    }
    Clase.IMAGEN -> m.ruta?.let { ImageStore.load(it, lado) }
    else -> null
}

/**
 * **La tarjeta de una carpeta en el chat** (`ventana_chat` del PC): el cuaderno, su nombre, cuántas
 * páginas y las primeras en miniatura. Antes, un mensaje así se veía como su JSON en crudo, que es
 * lo que hace el chat con una mini-app que no conoce. Tocarla abre [CarpetaActivity].
 */
@Composable
fun TarjetaDeCarpeta(m: Mensaje) {
    val documento = remember(m.texto) { CarpetaDePaginas.deMensaje(m) }
    // La de una grabación es una sola tarjeta con su foto (PC e1150e8).
    if (documento?.esGrabacion == true) TarjetaDeGrabacion(m, documento) else TarjetaDeCuaderno(m, documento)
}

@Composable
private fun TarjetaDeCuaderno(m: Mensaje, documento: CarpetaDePaginas?) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val cambios by MensajesStore.cambios.collectAsState()
    val paginas by produceState<List<Mensaje>>(emptyList(), m.texto, cambios) {
        val c = documento ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val todos = MensajesStore(contexto).leer()
                c.vivas(todos).map { todos[it.first] }
            }.getOrDefault(emptyList())
        }
    }
    Column(Modifier.widthIn(min = 200.dp, max = 280.dp).padding(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FolderCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Column(Modifier.padding(start = 8.dp)) {
                Text(
                    m.nombre.ifBlank { CarpetasDelChat.NOMBRE_DE_SERIE },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        documento == null -> "Carpeta de páginas"
                        paginas.isEmpty() -> "Vacía"
                        paginas.size == 1 -> "1 página"
                        else -> "${paginas.size} páginas"
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (paginas.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                paginas.take(3).forEach { p ->
                    Box(
                        Modifier.width(60.dp).height(80.dp).clip(RoundedCornerShape(4.dp)).background(Color.White),
                        contentAlignment = Alignment.Center
                    ) { MiniaturaDePaginaDeCarpeta(p, Modifier.fillMaxSize(), lado = 160) }
                }
            }
        }
    }
}

/**
 * **La tarjeta de una clase grabada** (25-oct-2026, `pintar_ojeada_grabacion` del PC, e1150e8): una sola
 * en el chat por toda la grabación. La primera captura de fondo (sin capturas, el icono del vídeo o
 * del micrófono), arriba la chapa «● Grabación», abajo lo que duró y cuántas capturas lleva y, si hay
 * nota, la pastilla «Estudiar» (la tarjeta entera abre la clase: ver [CarpetaActivity]).
 */
@Composable
private fun TarjetaDeGrabacion(m: Mensaje, c: CarpetaDePaginas) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val cambios by MensajesStore.cambios.collectAsState()
    val primera by produceState<Mensaje?>(null, m.texto, cambios) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val todos = MensajesStore(contexto).leer()
                c.capturas().firstNotNullOfOrNull { p -> todos.firstOrNull { it.id == p.id && it.clase == Clase.IMAGEN } }
            }.getOrNull()
        }
    }
    Column(Modifier.widthIn(min = 220.dp, max = 280.dp).padding(2.dp)) {
        Text(m.nombre.ifBlank { "Grabación" }, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            val foto = primera
            if (foto != null) MiniaturaDePaginaDeCarpeta(foto, Modifier.fillMaxSize(), lado = 480)
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
            if (c.voz.isNotEmpty()) Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.School, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text("Estudiar", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
