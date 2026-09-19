package com.forge.pixpin.tabla

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.motor.Calculadora
import com.forge.pixpin.motor.CalculoLexico
import com.forge.pixpin.motor.ImportarHojas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.forge.pixpin.motor.Celdas
import com.forge.pixpin.motor.EstiloDeCelda
import com.forge.pixpin.motor.ExportarHtml
import com.forge.pixpin.motor.ExportarProyecto
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.PortapapelesDeTabla
import com.forge.pixpin.motor.Proyectos
import com.forge.pixpin.motor.TablaDeCalculo
import com.forge.pixpin.motor.TablaViva
import com.forge.pixpin.motor.TablasEnDisco
import com.forge.pixpin.ui.CompartirEnlaceActivity
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * **Tablas con fórmulas**: la cuarta forma de trabajar de PixPin, al lado del lienzo, el
 * croquis 3D y las notas (lo pidió el usuario el 10-sep-2026).
 *
 * Es solo la carcasa —abrir, guardar, compartir, llevar a un proyecto—. Lo que calcula y lo
 * que entiende lo pegado vive en el motor ([TablaViva], [Calculadora],
 * [PortapapelesDeTabla]) y lo que se toca con el dedo en [RejillaDeTabla].
 *
 * Sin identificador abre la **lista de tablas**; con él, esa tabla. Cada tabla va en su tarea,
 * como las notas y los croquis (ver el manifiesto).
 */
class TablaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val idInicial = intent.getStringExtra(EXTRA_ID)
        val almacen = TablasEnDisco.de(filesDir)
        setContent {
            PixPinTheme {
                var abierta by rememberSaveable { mutableStateOf(idInicial) }
                // Las hojas del libro, si la tabla viene de un Excel: salen como pestañas.
                var hojas by rememberSaveable { mutableStateOf(ArrayList(intent.getStringArrayListExtra(EXTRA_HOJAS).orEmpty())) }
                val id = abierta
                if (id == null) {
                    ListaDeTablas(
                        almacen,
                        onAbrir = { hojas = ArrayList(); abierta = it },
                        onNueva = { hojas = ArrayList(); abierta = "t-${System.currentTimeMillis()}" },
                        onImportadas = { ids -> hojas = ArrayList(ids); abierta = ids.first() },
                        onCerrar = { finish() }
                    )
                } else key(id) {
                    val cargada by produceState<TablaDeCalculo?>(null, id) {
                        value = withContext(Dispatchers.IO) {
                            almacen.cargar(id)
                                ?: TablaDeCalculo(nombre = nombreNuevo(almacen), tocado = System.currentTimeMillis())
                        }
                    }
                    val inicial = cargada
                    if (inicial == null) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                    } else {
                        // **En la multitarea, como una pestaña más** (19-sep-2026).
                        com.forge.pixpin.ui.ConLaRueda(
                            com.forge.pixpin.data.Abiertos.deTabla(
                                id, inicial.nombre.ifBlank { "Tabla" },
                                intent.getStringExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO)
                            )
                        ) {
                        EditorDeTabla(
                            id = id,
                            inicial = inicial,
                            almacen = almacen,
                            enProyecto = intent.getStringExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO) != null ||
                                enAlgunProyecto(id),
                            hojas = hojas,
                            onHoja = { abierta = it },
                            onCerrar = { if (idInicial == null) abierta = null else cerrarYVolver() },
                            onRenombrar = { nombre -> renombrarEnProyectos(id, nombre) },
                            onAProyecto = { nombre -> aUnProyecto(id, nombre) }
                        )
                        }
                    }
                }
            }
        }
    }

    private fun nombreNuevo(almacen: TablasEnDisco): String {
        val usados = almacen.todas().map { it.second.nombre }.toSet()
        var n = usados.size + 1
        while ("Tabla $n" in usados) n++
        return "Tabla $n"
    }

    private fun enAlgunProyecto(id: String): Boolean =
        (application as? PixPinApp)?.proyectos?.proyectos?.value?.any { p -> p.hojas.any { it.tabla == id } } == true

    /** El nombre de la tabla es también el de su hoja en cada proyecto que la tenga. */
    private fun renombrarEnProyectos(id: String, nombre: String) {
        val app = application as? PixPinApp ?: return
        val ahora = System.currentTimeMillis()
        app.proyectos.proyectos.value.filter { p -> p.hojas.any { it.tabla == id } }.forEach { p ->
            app.proyectos.guardar(p.copy(hojas = p.hojas.map { if (it.tabla == id) it.copy(nombre = nombre) else it }, tocado = ahora))
        }
    }

    /** Al proyecto en curso, o a uno nuevo si no hay. Como hace la nota. */
    private fun aUnProyecto(id: String, nombre: String) {
        val app = application as? PixPinApp ?: return
        val ahora = System.currentTimeMillis()
        val proyecto = Proyectos.enCurso(app.proyectos.proyectos.value)
            ?: app.proyectos.nuevo(getString(R.string.proyecto_nuevo_nombre), ahora)
        if (Proyectos.hojaComo(proyecto, Hoja(id = "", tabla = id)) != null) {
            Toast.makeText(this, "Ya está en «${proyecto.nombre}»", Toast.LENGTH_SHORT).show()
            return
        }
        app.proyectos.guardar(Proyectos.conHoja(proyecto, Hoja(id = "h-$ahora", nombre = nombre, tabla = id), ahora))
        Toast.makeText(this, "Añadida a «${proyecto.nombre}»", Toast.LENGTH_SHORT).show()
    }

    private fun cerrarYVolver() {
        val vuelta = intent?.getStringExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO)
        if (vuelta != null) com.forge.pixpin.volverALosProyectos(this, vuelta)
        finish()
    }

    companion object {
        private const val EXTRA_ID = "tabla_id"
        private const val EXTRA_HOJAS = "tabla_hojas"

        /** Sube al guardar una tabla: las miniaturas de los proyectos la miran para rehacerse. */
        private val revisiones = mutableStateMapOf<String, Int>()
        fun revision(id: String): Int = revisiones[id] ?: 0
        internal fun guardada(id: String) { revisiones[id] = revision(id) + 1 }

        /** Abre la tabla [id], o la lista de tablas si es null. */
        fun abrir(
            context: Context, id: String? = null, desdeProyecto: String? = null,
            /** Las tablas de un mismo libro (un Excel con varias hojas): se pasa de una a otra con pestañas. */
            hojas: List<String>? = null
        ) {
            val i = Intent(context, TablaActivity::class.java)
                .setData(android.net.Uri.parse("pixpin://tabla/" + android.net.Uri.encode(id ?: "lista")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (id != null) i.putExtra(EXTRA_ID, id)
            if (hojas != null && hojas.size > 1) i.putStringArrayListExtra(EXTRA_HOJAS, ArrayList(hojas))
            if (desdeProyecto != null) i.putExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO, desdeProyecto)
            runCatching { context.startActivity(i) }
        }
    }
}

/**
 * Copia el archivo elegido, lo lee y guarda una tabla por hoja. Devuelve sus identificadores.
 * Trabajo de disco. Si no se deja leer, lanza [ImportarHojas.NoSeLee].
 */
/** La tabla como página web (con sus fórmulas) o como CSV, en la carpeta que publica el proveedor. */
private fun archivoDeLaTabla(contexto: Context, foto: TablaDeCalculo, web: Boolean, opciones: ExportarHtml.Opciones = ExportarHtml.Opciones()): File? {
    val titulo = foto.nombre.ifBlank { "Tabla" }
    val base = ExportarProyecto.nombreDeArchivo(titulo)
    val carpeta = File(contexto.cacheDir, "share").apply { mkdirs() }
    if (web) {
        val html = ExportarHtml.paginas(listOf(ExportarHtml.HojaWeb.Tabla(titulo, foto)), titulo = titulo, nombre = base, opciones = opciones)
        return File(carpeta, "$base.html").also { it.writeText(html) }
    }
    val v = TablaViva(foto)
    val k = v.calc.caja() ?: intArrayOf(0, 0, 0, 0)
    val csv = (k[0]..k[2]).joinToString("\r\n") { f ->
        (k[1]..k[3]).joinToString(",") { c ->
            val t = v.calc.texto(f, c)
            if (t.any { it == ',' || it == '"' || it == '\n' || it == ';' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
        }
    }
    return File(carpeta, "$base.csv").also { it.writeText("\uFEFF" + csv) }
}

private fun importarDe(contexto: Context, almacen: TablasEnDisco, uri: android.net.Uri): List<String> {
    val nombre = contexto.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "tabla.xlsx"
    val ahora = System.currentTimeMillis()
    val temporal = File(contexto.cacheDir, "importar-$ahora")
    contexto.contentResolver.openInputStream(uri)?.use { i -> temporal.outputStream().use { i.copyTo(it) } } ?: return emptyList()
    val hojas = try { ImportarHojas.leer(temporal, nombre, ahora) } finally { temporal.delete() }
    return hojas.mapIndexedNotNull { i, h -> "imp-$ahora-$i".takeIf { almacen.guardar(it, h.tabla) } }
}

@Composable
private fun ListaDeTablas(
    almacen: TablasEnDisco,
    onAbrir: (String) -> Unit,
    onNueva: () -> Unit,
    onImportadas: (List<String>) -> Unit,
    onCerrar: () -> Unit
) {
    val contexto = LocalContext.current
    val alcanceDeImportar = rememberCoroutineScope()
    // **Traer un Excel, un ODS o un CSV** desde el teléfono o Drive: una tabla por hoja.
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        alcanceDeImportar.launch {
            val leido = withContext(Dispatchers.IO) { runCatching { importarDe(contexto, almacen, uri) } }
            leido.onSuccess { ids ->
                if (ids.isEmpty()) Toast.makeText(contexto, "Ese archivo no tiene datos", Toast.LENGTH_SHORT).show()
                else onImportadas(ids)
            }.onFailure { e ->
                Toast.makeText(contexto, (e as? ImportarHojas.NoSeLee)?.message ?: "No se pudo leer ese archivo", Toast.LENGTH_LONG).show()
            }
        }
    }
    BackHandler { onCerrar() }
    var recarga by remember { mutableIntStateOf(0) }
    val tablas by produceState<List<Pair<String, TablaDeCalculo>>?>(null, recarga) {
        value = withContext(Dispatchers.IO) { almacen.todas() }
    }
    var borrando by remember { mutableStateOf<Pair<String, TablaDeCalculo>?>(null) }
    val alcance = rememberCoroutineScope()
    borrando?.let { (id, t) ->
        AlertDialog(
            onDismissRequest = { borrando = null },
            title = { Text("¿Borrar «${t.nombre.ifBlank { "Tabla" }}»?") },
            text = { Text("Si está en algún proyecto, su hoja se quedará vacía.") },
            confirmButton = {
                TextButton(onClick = {
                    borrando = null
                    alcance.launch { withContext(Dispatchers.IO) { almacen.borrar(id) }; recarga++ }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { borrando = null }) { Text("Cancelar") } }
        )
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCerrar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                    Text("Tablas", style = MaterialTheme.typography.titleLarge)
                }
                val lista = tablas
                if (lista != null && lista.isEmpty()) {
                    Text(
                        "Todavía no hay tablas.\n\nCrea una y escribe números y fórmulas como en Excel: =SUMA(B2:B9), =SI(A1>0;\"sí\";\"no\"). También puedes pegar lo que copies de Excel o de Google Sheets, importar un .xlsx, .ods o .csv, o compartirlo al chat de PixPin.",
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                    items(lista.orEmpty(), key = { it.first }) { (id, t) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { onAbrir(id) }, onLongClick = { borrando = id to t })
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.TableChart, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                Text(t.nombre.ifBlank { "Tabla" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${t.celdas.size} celdas · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t.tocado)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Column(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        importar.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel.sheet.macroEnabled.12",
                                "application/vnd.oasis.opendocument.spreadsheet",
                                "application/vnd.ms-excel", "text/csv", "text/comma-separated-values",
                                "text/tab-separated-values"
                            )
                        )
                    },
                    icon = { Icon(Icons.Filled.UploadFile, null) },
                    text = { Text("Importar Excel o CSV") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
                ExtendedFloatingActionButton(
                    onClick = onNueva,
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Nueva tabla") }
                )
            }
        }
    }
}

/** Lo último copiado dentro de la tabla: con esto, pegar mueve las fórmulas como en Excel. */
private class CopiaInterna(val tsv: String, val crudos: List<List<String>>, val f: Int, val c: Int)

/** Dónde se acaba de escribir una dirección tocando una celda, para cambiarla al tocar otra. */
private class RefPuesta(val desde: Int, var hasta: Int, val f: Int, val c: Int, var valor: String)

private val COLORES_DE_FONDO = listOf(
    "#fff3bf", "#d3f9d8", "#d0ebff", "#ffe3e3", "#f3d9fa", "#ffe8cc", "#e9ecef", "#c5f6fa"
)

@Composable
private fun EditorDeTabla(
    id: String,
    inicial: TablaDeCalculo,
    almacen: TablasEnDisco,
    enProyecto: Boolean,
    onCerrar: () -> Unit,
    onRenombrar: (String) -> Unit,
    onAProyecto: (String) -> Unit,
    /** Las tablas del mismo libro, para las pestañas de abajo. Ver [LibroDelChat]. */
    hojas: List<String> = emptyList(),
    onHoja: (String) -> Unit = {}
) {
    val contexto = LocalContext.current
    val tabla = remember(id) { TablaViva(inicial) }
    var version by remember { mutableIntStateOf(0) }
    val estado = remember { EstadoDeRejilla() }
    var texto by remember { mutableStateOf(TextFieldValue(tabla.calc.crudo(0, 0))) }
    var enfocado by remember { mutableStateOf(false) }
    var refPuesta by remember { mutableStateOf<RefPuesta?>(null) }
    var copia by remember { mutableStateOf<CopiaInterna?>(null) }
    var nombre by remember { mutableStateOf(inicial.nombre.ifBlank { "Tabla" }) }
    var menu by remember { mutableStateOf(false) }
    var colores by remember { mutableStateOf(false) }
    var renombrando by remember { mutableStateOf(false) }
    var catalogo by remember { mutableStateOf(false) }
    // **Antes de compartir la página se pregunta qué lleva**, como en el lienzo y el proyecto.
    var pidiendoWeb by remember { mutableStateOf(false) }
    var hojaDeCompartir by remember { mutableStateOf(false) }
    val app = contexto.applicationContext as? PixPinApp
    val ajustesWeb by (app?.settings?.settings ?: kotlinx.coroutines.flow.flowOf(null)).collectAsState(initial = null)
    val foco = remember { FocusRequester() }
    val focos = LocalFocusManager.current
    val alcance = rememberCoroutineScope()

    fun cambio() { version = tabla.version }

    fun guardarYa() {
        val foto = tabla.aTabla()
        Thread { if (almacen.guardar(id, foto)) TablaActivity.guardada(id) }.start()
    }

    // Guardar solo, un momento después del último cambio. La foto se saca en el hilo de la
    // pantalla —es quien toca la tabla— y se escribe fuera.
    LaunchedEffect(version) {
        if (version == 0) return@LaunchedEffect
        delay(700)
        val foto = tabla.aTabla()
        if (withContext(Dispatchers.IO) { almacen.guardar(id, foto) }) TablaActivity.guardada(id)
    }
    // Al pasar a otra hoja del libro este editor se va: lo pendiente se guarda antes.
    DisposableEffect(Unit) { onDispose { if (tabla.version > 0) guardarYa() } }
    val ciclo = LocalLifecycleOwner.current
    DisposableEffect(ciclo) {
        val observador = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && tabla.version > 0) guardarYa() }
        ciclo.lifecycle.addObserver(observador)
        onDispose { ciclo.lifecycle.removeObserver(observador) }
    }

    fun cargarBarra() {
        val raw = tabla.calc.crudo(estado.f, estado.c)
        texto = TextFieldValue(raw, TextRange(raw.length))
        refPuesta = null
    }

    fun confirmar() {
        val t = texto.text
        if (t != tabla.calc.crudo(estado.f, estado.c)) {
            tabla.escribir(estado.f, estado.c, t)
            cambio()
        }
        refPuesta = null
    }

    fun elegir(f: Int, c: Int, extender: Boolean = false) {
        estado.elegir(f, c, extender)
        estado.verActiva()
        cargarBarra()
    }

    fun puedePonerRef(): Boolean {
        if (!enfocado || !texto.text.startsWith("=")) return false
        refPuesta?.let { if (it.valor == texto.text) return true }
        val antes = texto.text.substring(0, texto.selection.start.coerceIn(0, texto.text.length)).trimEnd()
        return antes.isNotEmpty() && antes.last() in "=+-*/^&(;,:<>"
    }

    /** Con una fórmula a medias, tocar una celda **escribe su dirección**, como en Excel. */
    fun ponerRef(f: Int, c: Int, extender: Boolean) {
        var v = texto.text
        val r = refPuesta
        val puesta: RefPuesta
        if (extender && r != null && r.valor == v) {
            val t = Celdas.nombre(r.f, r.c) + ":" + Celdas.nombre(f, c)
            v = v.substring(0, r.desde) + t + v.substring(r.hasta)
            r.hasta = r.desde + t.length
            puesta = r
        } else {
            var desde = texto.selection.min.coerceIn(0, v.length)
            var hasta = texto.selection.max.coerceIn(0, v.length)
            if (r != null && r.valor == v) { desde = r.desde; hasta = r.hasta }
            val t = Celdas.nombre(f, c)
            v = v.substring(0, desde) + t + v.substring(hasta)
            puesta = RefPuesta(desde, desde + t.length, f, c, v)
        }
        puesta.valor = v
        refPuesta = puesta
        texto = TextFieldValue(v, TextRange(puesta.hasta))
    }

    fun editar(inicio: String? = null) {
        val raw = inicio ?: tabla.calc.crudo(estado.f, estado.c)
        texto = TextFieldValue(raw, TextRange(raw.length))
        runCatching { foco.requestFocus() }
    }

    fun portapapeles() = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun copiar(cortar: Boolean) {
        val f1 = estado.f1; val c1 = estado.c1; val f2 = estado.f2; val c2 = estado.c2
        if ((f2 - f1 + 1).toLong() * (c2 - c1 + 1) > 200_000) {
            Toast.makeText(contexto, "Demasiadas celdas para copiar", Toast.LENGTH_SHORT).show(); return
        }
        val valores = tabla.valores(f1, c1, f2, c2)
        val crudos = tabla.crudos(f1, c1, f2, c2)
        val tsv = PortapapelesDeTabla.aTsv(valores)
        val html = PortapapelesDeTabla.aHtml(valores, crudos, f1, c1) { f, c -> tabla.estilo(f, c)?.n == true }
        portapapeles().setPrimaryClip(ClipData.newHtmlText("PixPin", tsv, html))
        copia = CopiaInterna(tsv, crudos, f1, c1)
        if (cortar) { tabla.borrar(f1, c1, f2, c2); cambio(); cargarBarra() }
        Toast.makeText(contexto, if (cortar) "Cortado" else "Copiado: ${f2 - f1 + 1} × ${c2 - c1 + 1}", Toast.LENGTH_SHORT).show()
    }

    fun pegar() {
        val clip = portapapeles().primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        if (item == null) { Toast.makeText(contexto, "No hay nada que pegar", Toast.LENGTH_SHORT).show(); return }
        val html = item.htmlText
        val txt = item.coerceToText(contexto)?.toString()
        val interna = copia?.takeIf { txt != null && txt.trimEnd('\n', '\r') == it.tsv }
        val bloque = interna?.crudos?.map { f -> f.map { PortapapelesDeTabla.Pegada(it) } }
            ?: PortapapelesDeTabla.leer(html, txt)
        if (bloque.isNullOrEmpty()) { Toast.makeText(contexto, "No hay nada que pegar", Toast.LENGTH_SHORT).show(); return }
        if (enfocado) { focos.clearFocus() }
        val f1 = estado.f1; val c1 = estado.c1
        val fin = tabla.pegar(f1, c1, bloque, interna?.let { intArrayOf(it.f, it.c) })
        estado.af = f1; estado.ac = c1; estado.f = fin[0]; estado.c = fin[1]
        cambio(); cargarBarra()
        Toast.makeText(contexto, "Pegado: ${fin[0] - f1 + 1} × ${fin[1] - c1 + 1}", Toast.LENGTH_SHORT).show()
    }

    @Suppress("unused")
    fun compartir(web: Boolean, opciones: ExportarHtml.Opciones = ExportarHtml.Opciones()) {
        val foto = tabla.aTabla()
        alcance.launch {
            val archivo = withContext(Dispatchers.IO) {
                runCatching {
                    val titulo = foto.nombre.ifBlank { "Tabla" }
                    val base = ExportarProyecto.nombreDeArchivo(titulo)
                    val carpeta = File(contexto.cacheDir, "share").apply { mkdirs() }
                    if (web) {
                        val html = ExportarHtml.paginas(listOf(ExportarHtml.HojaWeb.Tabla(titulo, foto)), titulo = titulo, nombre = base, opciones = opciones)
                        File(carpeta, "$base.html").also { it.writeText(html) }
                    } else {
                        val v = TablaViva(foto)
                        // Solo lo escrito: el rectángulo que ocupa, no la rejilla infinita.
                        val k = v.calc.caja() ?: intArrayOf(0, 0, 0, 0)
                        val csv = (k[0]..k[2]).joinToString("\r\n") { f ->
                            (k[1]..k[3]).joinToString(",") { c ->
                                val t = v.calc.texto(f, c)
                                if (t.any { it == ',' || it == '"' || it == '\n' || it == ';' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
                            }
                        }
                        File(carpeta, "$base.csv").also { it.writeText("\uFEFF" + csv) }
                    }
                }.getOrNull()
            }
            if (archivo == null) Toast.makeText(contexto, "No se pudo preparar el archivo", Toast.LENGTH_SHORT).show()
            else CompartirEnlaceActivity.abrir(contexto, archivo, archivo.name.substringBeforeLast('.'), if (web) ExportarHtml.MIME_TYPE else "text/csv")
        }
    }

    BackHandler {
        when {
            enfocado -> { cargarBarra(); focos.clearFocus() }
            else -> { if (tabla.version > 0) guardarYa(); onCerrar() }
        }
    }

    if (hojaDeCompartir) {
        val F = com.forge.pixpin.ui.Compartible
        val foto = remember { tabla.aTabla() }
        val c = remember {
            com.forge.pixpin.ui.Compartible(
                titulo = foto.nombre.ifBlank { "Tabla" },
                formatos = listOf(
                    com.forge.pixpin.ui.Compartible.Formato("web", Icons.Filled.Language, "Página web", F.NINGUNA, generar = {
                        val marcadas = app?.ajustes?.funcionesWeb ?: ExportarHtml.Opciones.NOMBRES.toSet()
                        archivoDeLaTabla(contexto, foto, true, ExportarHtml.Opciones.de(marcadas))?.let { com.forge.pixpin.ui.Compartible.Salida(it, ExportarHtml.MIME_TYPE, "Con sus fórmulas, que siguen calculando") }
                    }, ajustes = { cerrar -> pidiendoWeb = true; cerrar() }),
                    com.forge.pixpin.ui.Compartible.Formato("csv", Icons.Filled.TableChart, "CSV (Excel)", F.NINGUNA, generar = {
                        archivoDeLaTabla(contexto, foto, false)?.let { com.forge.pixpin.ui.Compartible.Salida(it, "text/csv", "Los valores, para Excel o Sheets") }
                    })
                )
            )
        }
        com.forge.pixpin.ui.HojaDeCompartir(c) { hojaDeCompartir = false }
    }
    if (pidiendoWeb) {
        val marcadas = ajustesWeb?.funcionesWeb ?: ExportarHtml.Opciones.NOMBRES.toSet()
        com.forge.pixpin.motor.DialogoDeFuncionesWeb(
            marcadas = marcadas,
            onCambio = { clave, puesta ->
                val ahora = com.forge.pixpin.motor.ExportarHtml.conGrupo(marcadas, clave, puesta)
                alcance.launch { app?.settings?.setFuncionesWeb(ahora) }
            },
            onCompartir = { pidiendoWeb = false; if (!hojaDeCompartir) compartir(true, ExportarHtml.Opciones.de(marcadas)) },
            onCerrar = { pidiendoWeb = false },
            hayAudio = false,
            hayLienzo = false,
            hayTabla = true,
            tablasEditables = ExportarHtml.tablasEditables(marcadas),
            onTablasEditables = { e -> alcance.launch { app?.settings?.setFuncionesWeb(ExportarHtml.conTablasEditables(marcadas, e)) } }
        )
    }
    if (renombrando) {
        var propuesto by remember { mutableStateOf(nombre) }
        AlertDialog(
            onDismissRequest = { renombrando = false },
            title = { Text("Nombre de la tabla") },
            text = { OutlinedTextField(propuesto, { propuesto = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val limpio = propuesto.trim()
                    if (limpio.isNotEmpty()) {
                        nombre = limpio; tabla.nombre = limpio
                        guardarYa(); onRenombrar(limpio)
                    }
                    renombrando = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { renombrando = false }) { Text("Cancelar") } }
        )
    }
    if (catalogo) {
        AlertDialog(
            onDismissRequest = { catalogo = false },
            title = { Text("Funciones") },
            text = {
                LazyColumn(Modifier.height(420.dp)) {
                    items(Calculadora.CATALOGO) { (nombreFn, uso) ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                catalogo = false
                                val actual = if (enfocado) texto.text else tabla.calc.crudo(estado.f, estado.c)
                                val base = if (actual.startsWith("=")) actual else "="
                                val insertado = "$nombreFn("
                                val donde = if (enfocado && actual.startsWith("=")) texto.selection.start.coerceIn(0, base.length) else base.length
                                val nuevo = base.substring(0, donde) + insertado + base.substring(donde)
                                texto = TextFieldValue(nuevo, TextRange(donde + insertado.length))
                                runCatching { foco.requestFocus() }
                            }.padding(vertical = 8.dp)
                        ) {
                            Text(nombreFn, fontWeight = FontWeight.SemiBold)
                            Text("=$uso", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { catalogo = false }) { Text("Cerrar") } }
        )
    }

    // **Las celdas que cita la fórmula que se escribe**, cada una de su color en la rejilla y
    // en la barra, como Excel (lo pidió el usuario el 11-sep-2026).
    val marcas = remember(texto.text, enfocado) {
        if (enfocado && texto.text.startsWith("=")) conColores(CalculoLexico.referencias(texto.text)) else emptyList()
    }
    val coloreado = remember(marcas) {
        if (marcas.isEmpty()) VisualTransformation.None
        else VisualTransformation { t ->
            TransformedText(
                buildAnnotatedString {
                    append(t.text)
                    for ((r, color) in marcas) {
                        if (r.hasta <= t.length) addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), r.desde, r.hasta)
                    }
                },
                OffsetMapping.Identity
            )
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // ---- Cabecera ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (tabla.version > 0) guardarYa(); onCerrar() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Guardar y volver")
                }
                Text(
                    nombre,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { renombrando = true }.padding(vertical = 8.dp)
                )
                IconButton(onClick = { if (tabla.deshacer()) { cambio(); cargarBarra() } }, enabled = version >= 0 && tabla.puedeDeshacer) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Deshacer")
                }
                IconButton(onClick = { if (tabla.rehacer()) { cambio(); cargarBarra() } }, enabled = version >= 0 && tabla.puedeRehacer) {
                    Icon(Icons.AutoMirrored.Filled.Redo, "Rehacer")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        // La hoja de compartir de toda la aplicación: página web o CSV, con su peso.
                        DropdownMenuItem(
                            text = { Text("Compartir") },
                            leadingIcon = { Icon(com.forge.pixpin.ui.IconoDeCompartir, null) },
                            onClick = { menu = false; hojaDeCompartir = true }
                        )
                        DropdownMenuItem(text = { Text("Cambiar nombre") }, onClick = { menu = false; renombrando = true })
                        DropdownMenuItem(
                            text = { Text("Proteger la tabla al compartir") },
                            leadingIcon = { Icon(if (tabla.protegida) Icons.Filled.Lock else Icons.Filled.LockOpen, null) },
                            trailingIcon = { androidx.compose.material3.Switch(checked = version >= 0 && tabla.protegida, onCheckedChange = null) },
                            onClick = { menu = false; tabla.proteger(!tabla.protegida); cambio() }
                        )
                        if (!enProyecto) {
                            DropdownMenuItem(text = { Text("Llevar al proyecto en curso") }, onClick = { menu = false; guardarYa(); onAProyecto(nombre) })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Insertar filas encima") }, onClick = {
                            menu = false; tabla.insertar(false, estado.f1, estado.f2 - estado.f1 + 1); cambio(); cargarBarra()
                        })
                        DropdownMenuItem(text = { Text("Eliminar filas") }, onClick = {
                            menu = false; tabla.insertar(false, estado.f1, -(estado.f2 - estado.f1 + 1)); estado.elegir(estado.f1, estado.c1, false); cambio(); cargarBarra()
                        })
                        DropdownMenuItem(text = { Text("Insertar columnas a la izquierda") }, onClick = {
                            menu = false; tabla.insertar(true, estado.c1, estado.c2 - estado.c1 + 1); cambio(); cargarBarra()
                        })
                        DropdownMenuItem(text = { Text("Eliminar columnas") }, onClick = {
                            menu = false; tabla.insertar(true, estado.c1, -(estado.c2 - estado.c1 + 1)); estado.elegir(estado.f1, estado.c1, false); cambio(); cargarBarra()
                        })
                        DropdownMenuItem(text = { Text("Rellenar a la derecha") }, onClick = {
                            menu = false; tabla.rellenar(estado.f1, estado.c1, estado.f2, estado.c2, haciaAbajo = false); cambio(); cargarBarra()
                        })
                    }
                }
            }
            // ---- Barra de fórmula ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (estado.esUna) Celdas.nombre(estado.f, estado.c)
                    else Celdas.nombre(estado.f1, estado.c1) + ":" + Celdas.nombre(estado.f2, estado.c2),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.width(78.dp)
                )
                TextButton(onClick = { catalogo = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                    Text("fx", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontWeight = FontWeight.Bold)
                }
                BasicTextField(
                    value = texto,
                    onValueChange = { texto = it },
                    visualTransformation = coloreado,
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next, autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None
                    ),
                    keyboardActions = KeyboardActions(onNext = { confirmar(); elegir(estado.f + 1, estado.c) }),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.dp, if (enfocado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp, vertical = 9.dp)
                        .focusRequester(foco)
                        .onFocusChanged { enfocado = it.isFocused }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.Enter, Key.NumPadEnter -> { confirmar(); elegir(estado.f + 1, estado.c); true }
                                Key.Tab -> { confirmar(); elegir(estado.f, estado.c + 1); true }
                                Key.Escape -> { cargarBarra(); focos.clearFocus(); true }
                                else -> false
                            }
                        }
                )
                if (enfocado) {
                    IconButton(onClick = { cargarBarra(); focos.clearFocus() }) { Icon(Icons.Filled.Close, "Cancelar") }
                    IconButton(onClick = { confirmar(); focos.clearFocus() }) { Icon(Icons.Filled.Check, "Aceptar") }
                }
            }
            if (version >= 0 && tabla.protegida) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFFFFC107).copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(16.dp))
                    Text(
                        "Protegida: al compartir, solo las celdas ámbar se podrán cambiar",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            // ---- La rejilla ----
            RejillaDeTabla(
                tabla = tabla,
                version = version,
                estado = estado,
                marcas = marcas,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onToque = { f, c ->
                    if (puedePonerRef()) ponerRef(f, c, false)
                    else {
                        if (enfocado) confirmar()
                        elegir(f, c)
                    }
                },
                onDobleToque = { f, c ->
                    if (!puedePonerRef()) { elegir(f, c); editar() }
                },
                onLargo = { f, c ->
                    if (puedePonerRef()) ponerRef(f, c, false)
                    else { if (enfocado) confirmar(); elegir(f, c) }
                },
                onArrastre = { f, c ->
                    if (refPuesta != null && enfocado) ponerRef(f, c, true)
                    else { estado.elegir(f, c, true); cargarBarra() }
                },
                onColumna = { c ->
                    if (enfocado) confirmar()
                    estado.af = 0; estado.ac = c
                    estado.f = maxOf(tabla.calc.filas - 1, 0); estado.c = c
                    cargarBarra()
                },
                onFila = { f ->
                    if (enfocado) confirmar()
                    estado.af = f; estado.ac = 0
                    estado.f = f; estado.c = maxOf(tabla.calc.cols - 1, 0)
                    cargarBarra()
                },
                onAncho = { c, dp -> tabla.ponerAncho(c, dp); cambio() }
            )
            // ---- Las hojas del libro, como las pestañas de abajo de Excel ----
            if (hojas.size > 1) {
                val nombres by produceState(hojas.map { "" }, hojas) {
                    value = withContext(Dispatchers.IO) { hojas.map { almacen.cargar(it)?.nombre.orEmpty() } }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    hojas.forEachIndexed { i, otra ->
                        val activa = otra == id
                        Text(
                            nombres.getOrNull(i).orEmpty().substringAfterLast(" · ").ifBlank { "Hoja ${i + 1}" },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (activa) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (activa) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable(enabled = !activa) { if (tabla.version > 0) guardarYa(); onHoja(otra) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            // ---- Herramientas ----
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { copiar(false) }) { Icon(Icons.Filled.ContentCopy, "Copiar") }
                IconButton(onClick = { copiar(true) }) { Icon(Icons.Filled.ContentCut, "Cortar") }
                IconButton(onClick = { pegar() }) { Icon(Icons.Filled.ContentPaste, "Pegar (también desde Excel)") }
                IconButton(onClick = { tabla.borrar(estado.f1, estado.c1, estado.f2, estado.c2); cambio(); cargarBarra() }) {
                    Icon(Icons.Filled.Delete, "Borrar el contenido")
                }
                IconButton(onClick = {
                    val propuesta = tabla.autosuma(estado.f, estado.c)
                    texto = TextFieldValue(propuesta, TextRange(if (propuesta.endsWith("()")) propuesta.length - 1 else propuesta.length))
                    runCatching { foco.requestFocus() }
                }) { Icon(Icons.Filled.Functions, "Autosuma") }
                IconButton(onClick = {
                    val todas = (estado.f1..minOf(estado.f2, estado.f1 + 2000)).all { f -> (estado.c1..estado.c2).all { c -> tabla.estilo(f, c)?.n == true } }
                    tabla.cambiarEstilo(estado.f1, estado.c1, estado.f2, estado.c2) { (it ?: EstiloDeCelda()).copy(n = !todas) }
                    cambio()
                }) { Icon(Icons.Filled.FormatBold, "Negrita") }
                // **Las celdas que se podrán cambiar** en la página de una tabla protegida.
                IconButton(onClick = {
                    tabla.alternarEditables(estado.f1, estado.c1, estado.f2, estado.c2)
                    if (!tabla.protegida) {
                        Toast.makeText(contexto, "Marcadas como editables. Activa «Proteger la tabla» en ⋮ para que solo estas se puedan cambiar al compartir", Toast.LENGTH_LONG).show()
                    }
                    cambio()
                }) {
                    Icon(
                        Icons.Filled.LockOpen, "Celdas editables",
                        tint = if (tabla.estilo(estado.f, estado.c)?.e == true) Color(0xFFE0A000) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val alineada = tabla.estilo(estado.f, estado.c)?.a
                IconButton(onClick = {
                    val siguiente = when (alineada) { null -> "i"; "i" -> "c"; "c" -> "d"; else -> null }
                    tabla.cambiarEstilo(estado.f1, estado.c1, estado.f2, estado.c2) { (it ?: EstiloDeCelda()).copy(a = siguiente) }
                    cambio()
                }) {
                    Icon(
                        when (alineada) { "c" -> Icons.Filled.FormatAlignCenter; "d" -> Icons.Filled.FormatAlignRight; else -> Icons.Filled.FormatAlignLeft },
                        "Alinear", tint = if (alineada == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                    )
                }
                Box {
                    IconButton(onClick = { colores = true }) { Icon(Icons.Filled.FormatColorFill, "Color de fondo") }
                    DropdownMenu(expanded = colores, onDismissRequest = { colores = false }) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            COLORES_DE_FONDO.forEach { hex ->
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape)
                                        .background(Color(android.graphics.Color.parseColor(hex)))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                        .clickable {
                                            colores = false
                                            tabla.cambiarEstilo(estado.f1, estado.c1, estado.f2, estado.c2) { (it ?: EstiloDeCelda()).copy(f = hex) }
                                            cambio()
                                        }
                                )
                            }
                        }
                        DropdownMenuItem(text = { Text("Sin color") }, onClick = {
                            colores = false
                            tabla.cambiarEstilo(estado.f1, estado.c1, estado.f2, estado.c2) { it?.copy(f = null) }
                            cambio()
                        })
                    }
                }
                IconButton(onClick = { tabla.rellenar(estado.f1, estado.c1, estado.f2, estado.c2, haciaAbajo = true); cambio(); cargarBarra() }) {
                    Icon(Icons.Filled.VerticalAlignBottom, "Rellenar hacia abajo")
                }
                TextButton(onClick = { tabla.insertar(false, estado.f1, 1); cambio(); cargarBarra() }) { Text("+ fila") }
                TextButton(onClick = { tabla.insertar(true, estado.c1, 1); cambio(); cargarBarra() }) { Text("+ col") }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}
