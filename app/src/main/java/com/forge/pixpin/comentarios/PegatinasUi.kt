package com.forge.pixpin.comentarios

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * **Las pegatinas de un documento, en pantalla** (4-nov-2026, `docs/comentarios.md` §7 del PC, hecho para el
 * dedo): el botón de comentar abajo a la derecha con las abiertas en rojo; su tira con los cuatro tipos y la
 * lista; elegido un tipo, **un toque en el documento la pega** y abre su minichat. Un toque en una pegatina
 * abre el suyo; **mantenerla y arrastrar la mueve**. El minichat y la lista son tarjetas que suben desde abajo.
 *
 * [archivo] es el `.comentarios.json` del documento (el de su mensaje: `anot-<uid>.comentarios.json`, que
 * viaja) y [base] el principio del nombre de lo adjuntado (`anot-<uid>`): `pins/draw/<base>.a<huella>.adjunto`.
 */
class EstadoDePegatinas(private val filesDir: File, val archivo: File, private val base: String, val documento: String) {
    var pegatinas by mutableStateOf(Pegatinas())
        private set
    /** El tipo elegido en la tira, hasta que se pega. */
    var plantando by mutableStateOf<Tipo?>(null)
    var abierta by mutableStateOf<String?>(null)
    var tira by mutableStateOf(false)
    var viendoLista by mutableStateOf(false)
    private var sello = ""

    val quien: Quien by lazy {
        val id = runCatching { com.forge.pixpin.sincro.IdentidadEnDisco(filesDir).leer() }.getOrNull()
        Quien(id?.yo?.nombre ?: android.os.Build.MODEL ?: "Android", com.forge.pixpin.sincro.Codigos.deEsteAparato(filesDir) ?: "and")
    }

    /** Relee el fichero si cambió (lo que llega al sincronizar con el documento abierto). En un hilo. */
    fun releer() {
        val s = if (archivo.isFile) "${archivo.length()}:${archivo.lastModified()}" else "-"
        if (s == sello) return
        sello = s
        Fichero.leer(if (archivo.isFile) archivo.readText() else null)?.let { pegatinas = it }
    }

    /**
     * Aplica [cambio] y guarda, **juntando antes con lo del disco** (quien guarda también junta: lo que llegó
     * mientras tanto no se pierde). Un JSON roto en el disco no se pisa.
     */
    fun cambiar(cambio: (Pegatinas) -> Pegatinas) {
        val nuevas = cambio(pegatinas)
        if (nuevas == pegatinas) return
        pegatinas = nuevas
        runCatching {
            val antes = if (archivo.isFile) archivo.readText() else null
            val enDisco = Fichero.leer(antes) ?: return
            val juntas = Fusion.juntar(enDisco, nuevas)
            pegatinas = juntas
            archivo.parentFile?.mkdirs()
            val tmp = File(archivo.parentFile, archivo.name + ".tmp")
            tmp.writeText(Fichero.escribir(antes, juntas))
            if (!tmp.renameTo(archivo)) { archivo.writeText(tmp.readText()); tmp.delete() }
            sello = "${archivo.length()}:${archivo.lastModified()}"
        }
    }

    fun ahora(): Long = System.currentTimeMillis()

    fun poner(tipo: Tipo, ancla: Ancla): String? {
        var id: String? = null
        cambiar { p -> p.poner(tipo, ancla, quien, ahora()).also { id = it.second }.first }
        return id
    }

    fun escribir(id: String, texto: String, adjuntos: List<Adjunto> = emptyList()) =
        cambiar { it.escribir(id, quien, ahora(), texto, adjuntos).first }

    /**
     * **Un fichero adjunto**, tal cual en `pins/draw/<base>.a<huella>.adjunto` (FNV-1a de 64 bits de su
     * contenido: el mismo fichero desde dos aparatos es uno). Devuelve el adjunto con su ruta portátil.
     */
    fun guardarAdjunto(context: Context, uri: Uri): Adjunto? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val nombre = nombreDe(context, uri) ?: "archivo"
        val rel = "${com.forge.pixpin.sincro.AnotacionesDelAdjunto.CARPETA}/$base.a${huella(bytes)}.adjunto"
        val f = File(filesDir, rel)
        if (!f.isFile) { f.parentFile?.mkdirs(); f.writeBytes(bytes) }
        Adjunto.archivo(rel, nombre)
    }.getOrNull()

    fun fichero(a: Adjunto): File? = a.ruta?.let { r -> File(filesDir, r).takeIf { it.isFile } }

    /**
     * **Un lienzo propio en el minichat** (`tipo: lienzo`): una entrada nueva con su Excalidraw en
     * `pins/draw/<base>.c<entrada>.excalidraw.gz`. Devuelve el id del dibujo para abrirlo en el editor.
     */
    fun lienzoNuevo(id: String): String? {
        val cuando = ahora()
        val eid = pegatinas.idNuevo(quien.aparato, cuando)
        val dibujo = "$base.c$eid"
        var hecho = false
        cambiar { p ->
            p.escribir(id, quien, cuando, "", listOf(Adjunto(Adjunto.LIENZO, tinta = "${com.forge.pixpin.sincro.AnotacionesDelAdjunto.CARPETA}/$dibujo.excalidraw.gz")), conId = eid)
                .also { hecho = it.second != null }.first
        }
        return dibujo.takeIf { hecho }
    }

    /**
     * **Anotar una imagen del minichat**: su lienzo, `pins/draw/<base>.c<entrada>-<k>.excalidraw.gz`, con la
     * imagen de fondo; la primera vez se apunta en `tinta`. Devuelve (id del dibujo, imagen si aún no hay lienzo).
     */
    fun anotarImagen(c: Comentario, e: Entrada, k: Int): Pair<String, String?>? {
        val a = e.adjuntos.getOrNull(k) ?: return null
        val f = fichero(a) ?: return null
        val rel = a.tinta ?: "${com.forge.pixpin.sincro.AnotacionesDelAdjunto.CARPETA}/$base.c${e.id}-$k.excalidraw.gz"
        val dibujo = rel.substringAfterLast('/').removeSuffix(".excalidraw.gz")
        if (a.tinta == null) cambiar { it.ponerTinta(c.id, e.id, k, rel, ahora()) }
        val yaHay = File(filesDir, rel).isFile
        return dibujo to (if (yaHay) null else f.absolutePath)
    }

    companion object {
        /** FNV-1a de 64 bits en 16 cifras hexadecimales. */
        fun huella(bytes: ByteArray): String {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (b in bytes) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
            return "%016x".format(h)
        }

        private fun nombreDe(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}

private fun color(v: Long) = Color(v.toInt())

@Composable
fun rotuloDe(t: Tipo): String = stringResource(
    when (t) {
        Tipo.URGENTE -> R.string.cm_tipo_urgente
        Tipo.ALTA -> R.string.cm_tipo_alta
        Tipo.MEDIA -> R.string.cm_tipo_media
        Tipo.NOTA -> R.string.cm_tipo_nota
    }
)

/** El redondel de un tipo con su signo (o el bocadillo, la nota). */
@Composable
fun Redondel(tipo: Tipo, tamano: Int = 30, modifier: Modifier = Modifier) {
    Box(
        modifier.size(tamano.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(color(tipo.color))
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (tipo == Tipo.NOTA) Icon(Icons.Filled.ChatBubble, null, tint = Color.White, modifier = Modifier.size((tamano * 0.5f).dp))
        else Text(tipo.signo, color = color(tipo.colorDelSigno), fontWeight = FontWeight.Black, fontSize = (tamano * 0.42f).sp, maxLines = 1)
    }
}

/**
 * **La capa de las pegatinas** encima de un visor, a pantalla entera. [aPantalla] da dónde cae un ancla en
 * esta capa (en píxeles; null si no es de este visor o no se ve), [dePantalla] el ancla de un punto, e [irA]
 * lleva la vista a una. [tick] cambia cada vez que la vista se mueve: las pegatinas la siguen.
 */
@Composable
fun CapaDePegatinas(
    estado: EstadoDePegatinas,
    aPantalla: (Ancla) -> Offset?,
    dePantalla: (Offset) -> Ancla?,
    irA: (Ancla) -> Unit,
    tick: Int,
    modifier: Modifier = Modifier,
    /** Si el botón de comentar se ve (con las barras del visor); las pegatinas se ven siempre. */
    botonVisible: Boolean = true,
    /** Cuánto subir el botón sobre el borde de abajo (lo que ocupe la barra del visor). */
    margenAbajo: Int = 16
) {
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    val densidad = LocalDensity.current
    LaunchedEffect(estado.archivo) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { estado.releer() } }
            kotlinx.coroutines.delay(2_000)
        }
    }
    fun enHilo(f: () -> Unit) { alcance.launch(Dispatchers.IO) { runCatching { f() } } }
    val aPantallaAhora by rememberUpdatedState(aPantalla)
    val dePantallaAhora by rememberUpdatedState(dePantalla)

    Box(modifier.fillMaxSize()) {
        // --------------------------------------------------- pegando: un toque en el documento
        val plantando = estado.plantando
        if (plantando != null) {
            Box(Modifier.fillMaxSize().pointerInput(plantando) {
                detectTapGestures { p ->
                    val a = dePantallaAhora(p)
                    estado.plantando = null
                    estado.tira = false
                    if (a != null) enHilo { estado.poner(plantando, a)?.let { id -> estado.abierta = id } }
                }
            })
            Surface(
                shape = RoundedCornerShape(50), color = Color(0xE014182B), shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Redondel(plantando, 20)
                    Text(stringResource(R.string.cm_plantando_toque), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                    Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.padding(start = 8.dp).size(18.dp).clickable { estado.plantando = null })
                }
            }
        }

        // --------------------------------------------------- las pegatinas
        @Suppress("UNUSED_VARIABLE") val vuelta = tick
        for (c in estado.pegatinas.vivas()) androidx.compose.runtime.key(c.id) {
            val p = if (c.ancla.conPunto) aPantallaAhora(c.ancla) else null
            if (p != null) {
            var arrastre by remember(c.id) { mutableStateOf(Offset.Zero) }
            val lado = with(densidad) { 30.dp.toPx() }
            val pico = with(densidad) { 8.dp.toPx() }
            // El redondel encima del punto, con su piquito bajando hasta él.
            Box(
                Modifier.offset { IntOffset((p.x + arrastre.x - lado / 2).roundToInt(), (p.y + arrastre.y - lado - pico).roundToInt()) }
                    .alpha(if (c.resuelto) 0.5f else 1f)
                    .pointerInput(c.id) {
                        detectTapGestures { estado.abierta = c.id }
                    }
                    .pointerInput(c.id) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { cambio, d -> cambio.consume(); arrastre += d },
                            onDragEnd = {
                                val fin = Offset(p.x + arrastre.x, p.y + arrastre.y)
                                arrastre = Offset.Zero
                                val a = dePantallaAhora(fin)
                                if (a != null) enHilo { estado.cambiar { it.mover(c.id, c.ancla.copy(pagina = a.pagina ?: c.ancla.pagina).movida(a.x ?: 0.0, a.y ?: 0.0), estado.ahora()) } }
                            },
                            onDragCancel = { arrastre = Offset.Zero }
                        )
                    }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Redondel(c.tipo, 30)
                    Canvas(Modifier.size(width = 12.dp, height = 8.dp)) {
                        val camino = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }
                        drawPath(camino, color(c.tipo.color))
                    }
                }
            }
            }
        }

        // --------------------------------------------------- el botón y su tira
        if (botonVisible || estado.tira) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = margenAbajo.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (estado.tira) Surface(shape = RoundedCornerShape(50), color = Color.White, shadowElevation = 6.dp, modifier = Modifier.padding(end = 8.dp)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (t in Tipo.entries) Redondel(
                        t, 34,
                        Modifier.clickable { estado.plantando = if (estado.plantando == t) null else t }
                            .then(if (estado.plantando == t) Modifier.border(3.dp, Color(0xFF0060DF), CircleShape) else Modifier)
                    )
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFEFEFF4)).clickable { estado.viendoLista = true; estado.plantando = null }, contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.cm_lista_titulo), tint = Color(0xFF1C1C1E))
                    }
                }
            }
            Box {
                Surface(
                    onClick = { estado.tira = !estado.tira; if (!estado.tira) estado.plantando = null },
                    shape = CircleShape, color = Color.White, shadowElevation = 6.dp, modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (estado.tira) Icons.Filled.Close else Icons.Filled.ChatBubble, stringResource(R.string.cm_comentar), tint = Color(0xFF5C6BC0))
                    }
                }
                val n = estado.pegatinas.abiertas()
                if (n > 0) Text(
                    "$n", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).clip(RoundedCornerShape(50))
                        .background(Color(0xFFE53935)).padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }

    estado.abierta?.let { id ->
        val c = estado.pegatinas.get(id)
        if (c == null) estado.abierta = null else Minichat(estado, c, alCerrar = { estado.abierta = null }, enHilo = ::enHilo, contexto = contexto)
    }
    if (estado.viendoLista) ListaDePegatinas(estado, alCerrar = { estado.viendoLista = false }) { c ->
        estado.viendoLista = false
        estado.tira = false
        irA(c.ancla)
        estado.abierta = c.id
    }
}

/** **El minichat de una pegatina**, en una tarjeta que sube desde abajo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Minichat(estado: EstadoDePegatinas, c: Comentario, alCerrar: () -> Unit, enHilo: (() -> Unit) -> Unit, contexto: Context) {
    val hoja = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var texto by remember(c.id) { mutableStateOf("") }
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) enHilo {
            val adjuntos = uris.mapNotNull { estado.guardarAdjunto(contexto, it) }
            if (adjuntos.isNotEmpty()) estado.escribir(c.id, "", adjuntos)
        }
    }
    // **El clip, como el del chat pero recortado** (5-nov-2026, el usuario): la cámara, fotos y vídeos,
    // archivo, lienzo y lista de tareas.
    val adjuntar: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) enHilo {
            val adjuntos = uris.mapNotNull { estado.guardarAdjunto(contexto, it) }
            if (adjuntos.isNotEmpty()) estado.escribir(c.id, "", adjuntos)
        }
    }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { adjuntar(it) }
    // Con el permiso de la cámara pedido antes (sin él Android no la abre). Ver [com.forge.pixpin.ui.rememberCamaraDelTelefono].
    val hacerFotoConCamara = com.forge.pixpin.ui.rememberCamaraDelTelefono { u -> adjuntar(listOf(u)) }
    fun hacerFoto() = hacerFotoConCamara()
    var clip by remember { mutableStateOf(false) }
    val tareaCreada = stringResource(R.string.cm_tarea_creada)
    if (clip) ClipDelMinichat(
        alCerrar = { clip = false },
        alCamara = { clip = false; hacerFoto() },
        alGaleria = { clip = false; galeria.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        alArchivo = { clip = false; elegir.launch(arrayOf("*/*")) },
        alLienzo = {
            clip = false
            enHilo {
                estado.lienzoNuevo(c.id)?.let { dibujo ->
                    val ruta = com.forge.pixpin.motor.ExcalidrawStore.rutaDe(contexto, dibujo)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { com.forge.pixpin.motor.DrawEditorActivity.abrir(contexto, dibujo, ruta, null) }
                }
            }
        },
        alTareas = { clip = false; texto = (if (texto.isBlank()) "" else texto.trimEnd() + "\n") + "- [ ] " }
    )
    ModalBottomSheet(onDismissRequest = alCerrar, sheetState = hoja, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            // La cabecera: el redondel (un toque, el tipo siguiente), el tipo y el documento, y lo de la pegatina.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Redondel(c.tipo, 36, Modifier.clickable { enHilo { estado.cambiar { it.cambiarTipo(c.id, c.tipo.siguiente(), estado.ahora()) } } })
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(rotuloDe(c.tipo) + if (c.resuelto) " · " + stringResource(R.string.cm_resuelto) else "", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(estado.documento, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (c.tipo.esUrgencia && !c.resuelto && c.tarea == null) IconButton(onClick = {
                    enHilo {
                        val t = "${c.tipo.emoji} ${c.resumen().ifBlank { rotuloDeTexto(contexto, c.tipo) }} — ${estado.documento}"
                        com.forge.pixpin.mini.TareaRapidaActivity.alInbox(contexto, t)
                        val inbox = com.forge.pixpin.mini.TodasLasTareas.inboxEn(com.forge.pixpin.guardados.MensajesStore(contexto).leer())
                        estado.cambiar { it.vincularTarea(c.id, inbox?.let { m -> com.forge.pixpin.sincro.Codigos.unico(m) } ?: "inbox", estado.ahora()) }
                        android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(contexto, tareaCreada, android.widget.Toast.LENGTH_SHORT).show() }
                    }
                }) { Icon(Icons.Filled.TaskAlt, stringResource(R.string.cm_crear_tarea)) }
                IconButton(onClick = { enHilo { estado.cambiar { it.resolver(c.id, !c.resuelto, estado.ahora()) } } }) {
                    Icon(if (c.resuelto) Icons.Filled.Replay else Icons.Filled.Check, stringResource(if (c.resuelto) R.string.cm_reabrir else R.string.cm_resolver))
                }
                IconButton(onClick = { enHilo { estado.cambiar { it.quitar(c.id, estado.ahora()) } }; alCerrar() }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.cm_quitar), tint = Color(0xFFE53935))
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            // El hilo: lo último abajo.
            val entradas = c.entradas()
            val lista = rememberLazyListState()
            LaunchedEffect(entradas.size) { if (entradas.isNotEmpty()) lista.scrollToItem(entradas.size - 1) }
            if (entradas.isEmpty()) Text(
                stringResource(R.string.cm_sin_entradas), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)
            ) else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), state = lista) {
                items(entradas, key = { it.id }) { e -> FilaDeEntrada(estado, c, e, contexto, enHilo) }
            }
            // La caja de escribir.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { clip = true }) { Icon(Icons.Filled.AttachFile, stringResource(R.string.ip_adjuntar)) }
                OutlinedTextField(
                    texto, { texto = it }, placeholder = { Text(stringResource(R.string.cm_escribir_toque)) },
                    modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(20.dp)
                )
                IconButton(onClick = {
                    val t = texto
                    texto = ""
                    if (t.isNotBlank()) enHilo { estado.escribir(c.id, t) }
                }) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.cm_enviar), tint = Color(0xFF0060DF)) }
            }
        }
    }
}

private fun rotuloDeTexto(c: Context, t: Tipo) = c.getString(
    when (t) {
        Tipo.URGENTE -> R.string.cm_tipo_urgente
        Tipo.ALTA -> R.string.cm_tipo_alta
        Tipo.MEDIA -> R.string.cm_tipo_media
        Tipo.NOTA -> R.string.cm_tipo_nota
    }
)

@Composable
private fun FilaDeEntrada(estado: EstadoDePegatinas, c: Comentario, e: Entrada, contexto: Context, enHilo: (() -> Unit) -> Unit) {
    val mia = e.aparato == estado.quien.aparato
    var menu by remember(e.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.autor.ifBlank { "?" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                android.text.format.DateUtils.getRelativeTimeSpanString(e.cuando).toString(),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
            )
            if (mia) Box {
                Icon(Icons.Filled.Close, stringResource(R.string.cm_quitar), modifier = Modifier.size(16.dp).clickable { menu = true }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.cm_quitar)) }, onClick = {
                        menu = false
                        enHilo { estado.cambiar { it.quitarEntrada(c.id, e.id, estado.ahora()) } }
                    })
                }
            }
        }
        if (e.texto.isNotEmpty()) TextoConCasillas(e.texto) { nuevo -> enHilo { estado.cambiar { it.editar(c.id, e.id, nuevo, estado.ahora()) } } }
        // Las fotos, en miniatura (un toque: anotarla en el lienzo); lo demás, compacto con el icono de su
        // extensión y su nombre.
        val fotos = e.adjuntos.withIndex().filter { it.value.tipo == Adjunto.IMAGEN }
        if (fotos.isNotEmpty()) androidx.compose.foundation.lazy.LazyRow(
            Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(fotos.size) { i ->
                val (k, a) = fotos[i]
                Miniatura(estado.fichero(a), Modifier.size(width = 120.dp, height = 90.dp).clip(RoundedCornerShape(12.dp)).clickable {
                    enHilo {
                        estado.anotarImagen(c, e, k)?.let { (dibujo, imagen) ->
                            val ruta = com.forge.pixpin.motor.ExcalidrawStore.rutaDe(contexto, dibujo)
                            android.os.Handler(android.os.Looper.getMainLooper()).post { com.forge.pixpin.motor.DrawEditorActivity.abrir(contexto, dibujo, ruta, imagen) }
                        }
                    }
                })
            }
        }
        for (a in e.adjuntos) {
            if (a.tipo == Adjunto.IMAGEN) continue
            val esLienzo = a.tipo == Adjunto.LIENZO
            val nombre = if (esLienzo) stringResource(R.string.cm_lienzo_propio) else a.nombre ?: a.rotulo ?: a.tipo
            Row(
                Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        if (esLienzo) {
                            val dibujo = a.tinta?.substringAfterLast('/')?.removeSuffix(".excalidraw.gz")
                            if (dibujo != null) com.forge.pixpin.motor.DrawEditorActivity.abrir(contexto, dibujo, com.forge.pixpin.motor.ExcalidrawStore.rutaDe(contexto, dibujo), null)
                        } else {
                            val f = estado.fichero(a)
                            if (f != null) {
                                val ext = nombre.substringAfterLast('.', "").lowercase()
                                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                                runCatching { com.forge.pixpin.ui.AbrirCon.abrir(contexto, f, mime) }
                            }
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (esLienzo) Icon(Icons.Filled.Draw, null, modifier = Modifier.size(24.dp), tint = Color(0xFFC77700))
                else com.forge.pixpin.ui.IconoDeFormato(nombre, lado = 26.dp)
                Text(nombre, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 220.dp))
            }
        }
    }
}

/** Una foto reducida (en su hilo), o un recuadro gris mientras llega. */
@Composable
private fun Miniatura(f: File?, modifier: Modifier) {
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (f == null) return@runCatching null
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, o)
                var paso = 1
                while (o.outWidth / (paso * 2) >= 320 && o.outHeight / (paso * 2) >= 240) paso *= 2
                android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = paso })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0x22888888)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) androidx.compose.foundation.Image(b, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.Image, null, tint = Color(0x88888888))
    }
}

/**
 * **El texto de una entrada, con sus casillas**: los renglones «- [ ] …» y «- [x] …» son una lista de tareas
 * (la del clip); un toque en la casilla la tacha o la destacha, y eso se guarda en la entrada (viaja como texto).
 */
@Composable
private fun TextoConCasillas(texto: String, alCambiar: (String) -> Unit) {
    val renglones = texto.lines()
    val hayCasillas = renglones.any { it.trimStart().startsWith("- [ ]") || it.trimStart().startsWith("- [x]", ignoreCase = true) }
    if (!hayCasillas) { Text(texto, fontSize = 15.sp); return }
    Column {
        renglones.forEachIndexed { i, r ->
            val t = r.trimStart()
            val hecha = t.startsWith("- [x]", ignoreCase = true)
            if (hecha || t.startsWith("- [ ]")) Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = hecha, onCheckedChange = { si ->
                    val nuevo = renglones.toMutableList().also { it[i] = (if (si) "- [x] " else "- [ ] ") + t.drop(5).trimStart() }
                    alCambiar(nuevo.joinToString("\n"))
                })
                Text(
                    t.drop(5).trimStart(), fontSize = 15.sp,
                    textDecoration = if (hecha) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    color = if (hecha) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            } else if (r.isNotBlank()) Text(r, fontSize = 15.sp)
        }
    }
}

/** **El clip del minichat**: lo del clip del chat que vale aquí, en una tarjeta que sube desde abajo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipDelMinichat(
    alCerrar: () -> Unit, alCamara: () -> Unit, alGaleria: () -> Unit, alArchivo: () -> Unit, alLienzo: () -> Unit, alTareas: () -> Unit
) {
    val hoja = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = alCerrar, sheetState = hoja, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.ip_adjuntar), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp))
            // Fotos y vídeos, con la cámara dentro: la cámara grande, la galería a su lado.
            Text(stringResource(R.string.adjuntar_foto), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tecla(Icons.Filled.PhotoCamera, stringResource(R.string.cm_camara), Color(0xFF0A6CD6), Modifier.weight(1f), alCamara)
                Tecla(Icons.Filled.PhotoLibrary, stringResource(R.string.cm_galeria), Color(0xFF0A6CD6), Modifier.weight(1f), alGaleria)
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tecla(Icons.Filled.Description, stringResource(R.string.adjuntar_archivo), Color(0xFF636366), Modifier.weight(1f), alArchivo)
                Tecla(Icons.Filled.Draw, stringResource(R.string.adjuntar_lienzo), Color(0xFFC77700), Modifier.weight(1f), alLienzo)
                Tecla(Icons.Filled.Checklist, stringResource(R.string.adjuntar_tareas), Color(0xFF1E7A4A), Modifier.weight(1f), alTareas)
            }
        }
    }
}

@Composable
private fun Tecla(icono: androidx.compose.ui.graphics.vector.ImageVector, rotulo: String, color: Color, modifier: Modifier, al: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = al).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).background(color), contentAlignment = Alignment.Center) {
            Icon(icono, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Text(rotulo, fontSize = 13.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

/** **La lista «Comentarios (n)»**: un toque lleva la vista a su pegatina y abre su minichat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListaDePegatinas(estado: EstadoDePegatinas, alCerrar: () -> Unit, alElegir: (Comentario) -> Unit) {
    val hoja = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val todas = estado.pegatinas.ordenadas()
    ModalBottomSheet(onDismissRequest = alCerrar, sheetState = hoja, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                stringResource(R.string.cm_lista_titulo) + " (${todas.size})", fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            if (todas.isEmpty()) Text(
                stringResource(R.string.cm_lista_vacia_toque), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            ) else BoxWithConstraints {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(todas, key = { it.id }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { alElegir(c) }.padding(horizontal = 16.dp, vertical = 10.dp).alpha(if (c.resuelto) 0.55f else 1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Redondel(c.tipo, 28)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(c.resumen().ifBlank { rotuloDe(c.tipo) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(c.autor.takeIf { it.isNotBlank() }, android.text.format.DateUtils.getRelativeTimeSpanString(c.ultimo()).toString(),
                                        if (c.resuelto) stringResource(R.string.cm_resuelto) else null).joinToString(" · "),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val n = c.entradas().size
                            if (n > 0) Text("$n", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
