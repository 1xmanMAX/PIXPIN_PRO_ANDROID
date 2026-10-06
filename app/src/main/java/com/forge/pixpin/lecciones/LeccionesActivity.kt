package com.forge.pixpin.lecciones

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ui.theme.BarraDeCristal
import com.forge.pixpin.ui.theme.BotonDeBarra
import com.forge.pixpin.ui.theme.BotonRedondo
import com.forge.pixpin.ui.theme.Cristal
import com.forge.pixpin.ui.theme.PixPinTheme
import com.forge.pixpin.ui.theme.cristal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **Las lecciones aprendidas**: tarjetas, buscador y repaso (3-oct-2026).
 *
 * Lo que hay en esta pantalla responde a por qué fracasan las bases de lecciones (ver
 * `docs/lecciones.md`): nadie las consulta porque **no aparecen cuando hacen falta**, y se apunta
 * lo mismo una y otra vez sin que cambie nada. Por eso, además de la lista y del buscador:
 * - **Para repasar hoy**: unas pocas, de una en una, primero intentando recordar qué harías
 *   —recordar fija mucho más que releer— y espaciadas cada vez más si se recuerdan.
 * - **Lista de comprobación**: todo lo de «la próxima vez…» de un área en una lista de casillas,
 *   para repasarla **antes** de empezar algo. Es lo que de verdad evita repetir un error.
 * - Las que **se repiten** se marcan y suben: son la prueba de que no se aprendió.
 * - Con un proyecto ([EXTRA_PROYECTO]) se ven primero las suyas y las que hablan de lo mismo.
 */
class LeccionesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val proyecto = intent.getStringExtra(EXTRA_PROYECTO)
        val consulta = intent.getStringExtra(EXTRA_CONSULTA).orEmpty()
        setContent { PixPinTheme { Pantalla(proyecto, consulta) } }
    }

    override fun onStart() { super.onStart(); aLaVista = true }
    override fun onStop() { super.onStop(); aLaVista = false }

    /** Al salir de la lista, la que esperaba su «Deshacer» se borra ya, como en el PC al cerrar. */
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) LeccionesStore(this).borrarYa((application as PixPinApp).scope)
    }

    private enum class Filtro(val nombre: String) { ERRORES("⚠️ Errores"), REPETIDAS("🔁 Repetidas"), GRAVES("❗ Graves"), ACIERTOS("✅ Aciertos") }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    @Composable
    private fun Pantalla(proyectoInicial: String?, consultaInicial: String) {
        val almacen = remember { LeccionesStore(this) }
        val todas by LeccionesStore.todas.collectAsState()
        // Al día con el chat: lo que llega por la sincronización o se guarda en la hoja.
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { almacen.recargar() }
            MensajesStore.cambios.debounce(400).collect { withContext(Dispatchers.IO) { almacen.recargar() } }
        }
        val app = application as PixPinApp
        val proyectos by app.proyectos.proyectos.collectAsState()

        var consulta by remember { mutableStateOf(consultaInicial) }
        var area by remember { mutableStateOf<String?>(null) }
        var filtro by remember { mutableStateOf<Filtro?>(null) }
        var proyecto by remember { mutableStateOf(proyectoInicial) }
        var enLista by remember { mutableStateOf(false) }

        val dictado = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { consulta = it }
        }
        fun dictarBusqueda() {
            runCatching {
                dictado.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "¿Qué buscas en tus lecciones?")
                )
            }.onFailure { Toast.makeText(this, "No hay dictado en este teléfono", Toast.LENGTH_SHORT).show() }
        }

        // El buscador corre fuera del hilo que pinta, con un respiro tras cada tecla.
        var visibles by remember { mutableStateOf<List<LeccionesStore.Entrada>>(emptyList()) }
        LaunchedEffect(todas, consulta, area, filtro, proyecto) {
            if (consulta.isNotBlank()) delay(120)
            visibles = withContext(Dispatchers.Default) {
                val elProyecto = proyectos.firstOrNull { it.id == proyecto }
                var base = todas.asSequence()
                    .filter { area == null || it.leccion.area == area }
                    .filter {
                        when (filtro) {
                            Filtro.ERRORES -> it.leccion.esError
                            Filtro.REPETIDAS -> it.leccion.repeticiones.isNotEmpty()
                            Filtro.GRAVES -> it.leccion.gravedad >= 3
                            Filtro.ACIERTOS -> it.leccion.tipo == Leccion.TIPO_ACIERTO
                            null -> true
                        }
                    }.toList()
                if (elProyecto != null) {
                    // Las del proyecto y las que hablan de lo mismo que su nombre.
                    val suyas = base.filter { it.proyecto == elProyecto.id }
                    val cerca = Buscador.paraElContexto(base.map { it.indice }, elProyecto.nombre, 10).map { it.id }.toSet()
                    base = (suyas + base.filter { it.leccion.id in cerca }).distinctBy { it.leccion.id }
                }
                if (consulta.isBlank()) base
                else {
                    val porId = base.associateBy { it.leccion.id }
                    Buscador.buscar(base.map { it.indice }, consulta).mapNotNull { porId[it.leccion.id] }
                }
            }
        }
        val hoy = remember(todas) { Repaso.deHoy(todas.map { it.leccion }, System.currentTimeMillis(), 20) }

        // El repaso de hoy, a pantalla entera.
        var repaso by remember { mutableStateOf<ColaDeRepaso?>(null) }

        // **Borrar con Deshacer**: la que se borra (en su hoja) ofrece volver unos segundos.
        val avisos = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            LeccionesStore.borrando.collectLatest { e ->
                if (e == null) { avisos.currentSnackbarData?.dismiss(); return@collectLatest }
                val r = withTimeoutOrNull(LeccionesStore.PARA_DESHACER) {
                    avisos.showSnackbar("Lección borrada", "Deshacer", duration = SnackbarDuration.Indefinite)
                }
                if (r == SnackbarResult.ActionPerformed) almacen.deshacer(app.scope)
            }
        }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                // Cabecera.
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BotonRedondo(Icons.AutoMirrored.Filled.ArrowBack, "Volver", { finish() }, tamano = 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Lecciones", style = MaterialTheme.typography.titleLarge, color = Cristal.tinta, fontWeight = FontWeight.SemiBold)
                        val repetidas = todas.count { it.leccion.repeticiones.isNotEmpty() }
                        Text(
                            "${todas.size} aprendidas" + if (repetidas > 0) " · $repetidas se repitieron" else "",
                            style = MaterialTheme.typography.labelMedium, color = Cristal.tinta.copy(alpha = 0.7f)
                        )
                    }
                    BotonRedondo(Icons.Filled.Checklist, "Lista de comprobación", { enLista = !enLista }, tamano = 44.dp, puesto = enLista)
                }

                // «¿Qué aprendiste?»: apuntar en una línea, lo demás se rellena solo.
                BarraRapida(almacen, todas, proyectos, proyecto)
                Spacer(Modifier.height(8.dp))

                // Buscador.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).cristal().padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, null, tint = Cristal.tinta.copy(alpha = 0.7f))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (consulta.isEmpty()) Text("Buscar: palabra, tema, situación…", color = Cristal.tinta.copy(alpha = 0.5f))
                        BasicTextField(
                            value = consulta, onValueChange = { consulta = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Cristal.tinta),
                            cursorBrush = SolidColor(Cristal.tinta), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (consulta.isNotEmpty()) IconButton(onClick = { consulta = "" }) { Icon(Icons.Filled.Close, "Borrar", tint = Cristal.tinta) }
                    IconButton(onClick = { dictarBusqueda() }) { Icon(Icons.Filled.Mic, "Buscar dictando", tint = Cristal.tinta) }
                }

                // Áreas y filtros, en una fila.
                LazyRow(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    proyectos.firstOrNull { it.id == proyecto }?.let { p ->
                        item { FilterChip(selected = true, onClick = { proyecto = null }, label = { Text("📁 ${p.nombre}") }, trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) }) }
                    }
                    item { FilterChip(selected = area == null && filtro == null, onClick = { area = null; filtro = null }, label = { Text("Todas") }) }
                    val areas = (Leccion.AREAS + todas.map { it.leccion.area }.filter { it.isNotBlank() }).distinct()
                    items(areas) { a ->
                        FilterChip(selected = area == a, onClick = { area = if (area == a) null else a },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(colorDe(a))) },
                            label = { Text(a) })
                    }
                    items(Filtro.entries) { f -> FilterChip(selected = filtro == f, onClick = { filtro = if (filtro == f) null else f }, label = { Text(f.nombre) }) }
                }

                if (todas.isEmpty()) Vacia(Modifier.weight(1f))
                else if (enLista) ListaDeComprobacion(visibles, Modifier.weight(1f))
                else LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(170.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp
                ) {
                    if (hoy.isNotEmpty() && consulta.isBlank() && area == null && filtro == null && proyecto == null) {
                        item(span = StaggeredGridItemSpan.FullLine) { ParaRepasar(hoy) { repaso = ColaDeRepaso(hoy.map { it.id }) } }
                    }
                    if (visibles.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                        Text(
                            if (consulta.isNotBlank()) "Nada con «$consulta». Prueba con otra palabra o apúntala ahora." else "Nada con este filtro.",
                            color = Cristal.tinta.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp)
                        )
                    }
                    items(visibles, key = { it.leccion.id }) { e -> Tarjeta(e) }
                }
            }

            // Apuntar: lo que más se hace, abajo y a mano.
            BarraDeCristal(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)) {
                BotonDeBarra(Icons.Filled.Mic, "Dictar", { LeccionActivity.nueva(this@LeccionesActivity, proyecto = proyecto, dictar = true) })
                BotonDeBarra(Icons.Filled.Add, "Nueva", { LeccionActivity.nueva(this@LeccionesActivity, proyecto = proyecto) }, puesto = true)
            }

            SnackbarHost(avisos, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 90.dp))

            repaso?.let { cola ->
                androidx.activity.compose.BackHandler { repaso = null }
                PantallaDeRepaso(cola, almacen, todas) { repaso = null }
            }
        }
    }

    /**
     * **La barra rápida** (lo trae el PC, v2): una frase —«pasó que…, porque…, la próxima vez…»—
     * y se guarda al pulsar enviar. Mientras se escribe se proponen el área, el proyecto y la
     * gravedad ([Rapida.rellenar]), fuera del hilo que pinta; cada uno se cambia con un toque. Si
     * ya hay una muy parecida, lo que toca es apuntar que **volvió a pasar**.
     */
    @Composable
    private fun BarraRapida(
        almacen: LeccionesStore,
        todas: List<LeccionesStore.Entrada>,
        proyectos: List<com.forge.pixpin.motor.Proyecto>,
        proyectoDeLaLista: String?
    ) {
        val app = application as PixPinApp
        val corrutinas = rememberCoroutineScope()
        var frase by remember { mutableStateOf("") }
        // Lo cambiado a mano: null = lo propuesto; "" = ninguno.
        var area by remember { mutableStateOf<String?>(null) }
        var proyecto by remember { mutableStateOf<String?>(null) }
        var gravedad by remember { mutableStateOf<Int?>(null) }
        // Lo propuesto, con la frase para la que se calculó.
        var relleno by remember { mutableStateOf("" to Rapida.Relleno()) }
        var parecida by remember { mutableStateOf<LeccionesStore.Entrada?>(null) }
        var aprendido by remember { mutableStateOf(Etiquetador.Aprendido.VACIO) }
        LaunchedEffect(todas) { aprendido = withContext(Dispatchers.Default) { Etiquetador.aprender(todas.map { it.leccion }) } }
        val vivos = remember(proyectos) { proyectos.filter { !it.archivado }.map { it.id to it.nombre } }

        fun calcular(f: String): Rapida.Relleno {
            val deQuien = HashMap<String, String>()
            for (e in todas) e.proyecto?.let { deQuien[e.leccion.id] = it }
            return Rapida.rellenar(f, aprendido, todas.map { it.indice }, vivos) { deQuien[it] }
        }
        LaunchedEffect(frase, todas, aprendido) {
            if (frase.isBlank()) { relleno = frase to Rapida.Relleno(); parecida = null; return@LaunchedEffect }
            delay(150)
            val f = frase
            val (r, p) = withContext(Dispatchers.Default) {
                calcular(f) to Buscador.parecidas(todas.map { it.indice }, f).firstOrNull()
                    ?.let { x -> todas.firstOrNull { it.leccion.id == x.leccion.id } }
            }
            relleno = f to r; parecida = p
        }
        val r = relleno.second
        val areaElegida = (area ?: r.propuesta.area).orEmpty()
        val gravedadElegida = (gravedad ?: r.gravedad).coerceIn(1, 3)

        /** El proyecto donde irá: el elegido a mano, el propuesto, el de la lista o el chat general. */
        fun dondeIra(elegido: String?, propuesto: String?): String? = when {
            elegido == "" -> null
            elegido != null -> elegido
            else -> (propuesto ?: proyectoDeLaLista)?.takeIf { p -> vivos.any { it.first == p } }
        }
        val proyectoElegido = dondeIra(proyecto, r.proyecto)

        fun vaciar() { frase = ""; area = null; proyecto = null; gravedad = null; parecida = null }

        fun guardar() {
            val f = frase.trim()
            if (f.isEmpty()) return
            val a = area; val g = gravedad; val p = proyecto
            val yaCalculado = relleno.takeIf { it.first == frase }?.second
            corrutinas.launch {
                val rr = yaCalculado ?: withContext(Dispatchers.Default) { calcular(f) }
                val ahora = System.currentTimeMillis()
                val l = Rapida.leccion(f, LeccionesStore.nuevoId(ahora), ahora, rr.propuesta, a ?: rr.propuesta.area.orEmpty(), g ?: rr.gravedad)
                if (l.titulo.isBlank()) return@launch
                val donde = dondeIra(p, rr.proyecto)
                vaciar()
                app.scope.launch(Dispatchers.IO) { runCatching { almacen.guardar(l, donde) } }
                val nombre = vivos.firstOrNull { it.first == donde }?.second ?: "Sin proyecto (chat general)"
                Toast.makeText(this@LeccionesActivity, "💡 Lección guardada en $nombre", Toast.LENGTH_SHORT).show()
            }
        }

        val dictado = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                frase = if (frase.isBlank()) it else frase.trimEnd() + " " + it
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).cristal(RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💡", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (frase.isEmpty()) Text("¿Qué aprendiste? Escríbelo o díctalo", color = Cristal.tinta.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(
                        value = frase, onValueChange = { frase = it }, maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Cristal.tinta),
                        cursorBrush = SolidColor(Cristal.tinta), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { guardar() })
                    )
                }
                IconButton(onClick = { dictar(dictado, "Cuenta qué aprendiste") }) { Icon(Icons.Filled.Mic, "Dictar", tint = Cristal.tinta) }
                if (frase.isNotBlank()) IconButton(onClick = { guardar() }) { Icon(Icons.AutoMirrored.Filled.Send, "Guardar", tint = Cristal.puesto) }
            }
            if (frase.isNotBlank()) {
                // Lo rellenado solo: un toque lo cambia.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    var eligeArea by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(
                            onClick = { eligeArea = true },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(colorDe(areaElegida))) },
                            label = { Text(if (areaElegida.isBlank()) "Sin área" else "Área: $areaElegida", maxLines = 1) }
                        )
                        DropdownMenu(expanded = eligeArea, onDismissRequest = { eligeArea = false }) {
                            val areas = (Leccion.AREAS + todas.map { it.leccion.area }.filter { it.isNotBlank() }).distinct()
                            areas.forEach { a -> DropdownMenuItem(text = { Text(a) }, onClick = { area = a; eligeArea = false }) }
                            DropdownMenuItem(text = { Text("Sin área") }, onClick = { area = ""; eligeArea = false })
                        }
                    }
                    var eligeProyecto by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(
                            onClick = { eligeProyecto = true },
                            leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(16.dp)) },
                            label = { Text(vivos.firstOrNull { it.first == proyectoElegido }?.second ?: "Sin proyecto", maxLines = 1) }
                        )
                        DropdownMenu(expanded = eligeProyecto, onDismissRequest = { eligeProyecto = false }) {
                            DropdownMenuItem(text = { Text("Sin proyecto (chat general)") }, onClick = { proyecto = ""; eligeProyecto = false })
                            proyectos.filter { !it.archivado }.sortedByDescending { it.tocado }.forEach { p ->
                                DropdownMenuItem(text = { Text(p.nombre) }, onClick = { proyecto = p.id; eligeProyecto = false })
                            }
                        }
                    }
                    // La gravedad da la vuelta con cada toque: Leve → Importante → Grave.
                    AssistChip(
                        onClick = { gravedad = gravedadElegida % 3 + 1 },
                        label = { Text(NOMBRES_DE_GRAVEDAD[gravedadElegida - 1], maxLines = 1) }
                    )
                }
                parecida?.let { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .clickable {
                                app.scope.launch(Dispatchers.IO) { runCatching { almacen.guardar(Repaso.repetida(e.leccion, System.currentTimeMillis()), e.proyecto) } }
                                vaciar()
                                Toast.makeText(this@LeccionesActivity, "🔁 Apuntado: te volvió a pasar", Toast.LENGTH_SHORT).show()
                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Ya la tienes: «${e.leccion.titulo}»", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
                        Text(" · 🔁 Me volvió a pasar", color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    /** Abre el dictado del sistema con [pregunta]; sin él, lo dice. */
    private fun dictar(lanzador: androidx.activity.result.ActivityResultLauncher<Intent>, pregunta: String) {
        runCatching {
            lanzador.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, pregunta)
            )
        }.onFailure { Toast.makeText(this, "No hay dictado en este teléfono", Toast.LENGTH_SHORT).show() }
    }

    @Composable
    private fun Tarjeta(e: LeccionesStore.Entrada) {
        val l = e.leccion
        Column(
            Modifier.fillMaxWidth().cristal(RoundedCornerShape(20.dp)).clickable { LeccionActivity.abrir(this, l.id) }
        ) {
            Box(Modifier.fillMaxWidth().height(5.dp).background(colorDe(l.area)))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(iconoDe(l), fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(l.area.ifBlank { "Sin área" }, style = MaterialTheme.typography.labelSmall, color = Cristal.tinta.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                    if (l.repeticiones.isNotEmpty()) Text("🔁×${l.vecesQuePaso}", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFF8A65), fontWeight = FontWeight.Bold)
                    if (l.adjuntos.isNotEmpty()) Text("📎${l.adjuntos.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(l.titulo, style = MaterialTheme.typography.titleSmall, color = Cristal.tinta, fontWeight = FontWeight.SemiBold, maxLines = 5, overflow = TextOverflow.Ellipsis)
                if (l.proxima.isNotBlank() && l.proxima != l.titulo) {
                    Text("→ ${l.proxima}", style = MaterialTheme.typography.bodySmall, color = Cristal.tinta.copy(alpha = 0.8f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                val etiquetas = l.todasLasEtiquetas.take(4)
                if (etiquetas.isNotEmpty()) {
                    Text(etiquetas.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = Cristal.puesto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    /**
     * **Para repasar hoy**: cuántas tocan y el botón que abre el repaso, de una en una
     * ([PantallaDeRepaso]).
     */
    @Composable
    private fun ParaRepasar(hoy: List<Leccion>, empezar: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().cristal(RoundedCornerShape(22.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("🧠 Para repasar hoy · ${hoy.size}", style = MaterialTheme.typography.labelLarge, color = Cristal.puesto)
                Text("¿Qué harías si te vuelve a pasar?", color = Cristal.tinta, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = empezar) { Text("Repasar") }
        }
    }

    /**
     * **El repaso de hoy, de una en una** (lo trae el PC, v2). Se enseña la situación y se intenta
     * recordar qué harías **antes** de destaparlo —recordar fija mucho más que releer—; lo que se
     * escribe (o dicta) se pone junto a lo apuntado para compararlo y no se guarda. Tres botones,
     * cada uno con cuándo vuelve: «Lo recordaba» sube de caja, «A medias» baja una y «Lo olvidé»
     * vuelve a empezar ([Repaso.calificar]: solo cambian `caja` y `repasar`).
     */
    @Composable
    private fun PantallaDeRepaso(cola: ColaDeRepaso, almacen: LeccionesStore, todas: List<LeccionesStore.Entrada>, salir: () -> Unit) {
        val app = application as PixPinApp
        // La cola cambia por dentro: esta vuelta es lo que Compose ve.
        var vuelta by remember { mutableIntStateOf(0) }
        var respuesta by remember(vuelta) { mutableStateOf("") }
        var mostrada by remember(vuelta) { mutableStateOf(false) }
        val porId = remember(todas) { todas.associateBy { it.leccion.id } }
        val id = remember(vuelta) { cola.actual }
        val e = id?.let { porId[it] }
        // Una que se borró mientras tanto se salta sola.
        LaunchedEffect(id, e) { if (id != null && e == null) { cola.pasar(false); vuelta++ } }

        fun contestar(nota: Nota) {
            val x = e ?: return
            val nueva = Repaso.calificar(x.leccion, nota, System.currentTimeMillis())
            app.scope.launch(Dispatchers.IO) { runCatching { almacen.guardar(nueva, x.proyecto) } }
            cola.pasar(true); vuelta++
        }

        val dictado = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                respuesta = if (respuesta.isBlank()) it else respuesta.trimEnd() + " " + it
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val (va, total) = cola.progreso
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Repaso de hoy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (id != null) Text("$va de $total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = salir) { Text("Salir") }
                }
                val hechoDeLaCola = if (total == 0) 1f else (if (id == null) total else va - 1).toFloat() / total
                LinearProgressIndicator(progress = { hechoDeLaCola }, modifier = Modifier.fillMaxWidth())
                if (id == null) {
                    // Terminado.
                    Spacer(Modifier.height(24.dp))
                    Text("✅ Repaso terminado", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (cola.hechas == 1) "Repasaste 1 lección. Vuelve cuando toque." else "Repasaste ${cola.hechas} lecciones. Vuelven cuando toque.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = salir) { Text("Volver a la lista") }
                    return@Column
                }
                val l = e?.leccion ?: return@Column
                // Los tres pasos: pensar, mirar, contestar.
                val paso = ColaDeRepaso.paso(respuesta, mostrada)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Piensa", "Mira", "¿Lo recordabas?").forEachIndexed { i, t ->
                        val n = i + 1
                        Text(
                            "$n · $t", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (n == paso) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (n < paso) 0.9f else 0.5f),
                            fontWeight = if (n == paso) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                Text("LA SITUACIÓN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ColaDeRepaso.situacion(l), style = MaterialTheme.typography.titleMedium)
                val sub = listOfNotNull(
                    l.area.takeIf { it.isNotBlank() },
                    if (l.repeticiones.isNotEmpty()) "te ha pasado ${l.vecesQuePaso} veces" else null
                )
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = respuesta, onValueChange = { respuesta = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("¿Qué harías?") }, placeholder = { Text("Escribe o dicta") }, minLines = 2, maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    trailingIcon = { IconButton(onClick = { dictar(dictado, "¿Qué harías?") }) { Icon(Icons.Filled.Mic, "Dictar") } },
                    shape = RoundedCornerShape(14.dp)
                )

                if (!mostrada) {
                    Text("Piénsalo antes de mirar: recordar fija mucho más que releer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { mostrada = true }, modifier = Modifier.fillMaxWidth()) { Text("Mostrar la respuesta") }
                } else {
                    // Lo escrito junto a lo apuntado, para compararlo.
                    if (respuesta.isNotBlank()) {
                        Text("TU RESPUESTA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(respuesta.trim(), style = MaterialTheme.typography.bodyLarge)
                    }
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val tinta = MaterialTheme.colorScheme.onSecondaryContainer
                        Text("LO QUE APUNTASTE", style = MaterialTheme.typography.labelMedium, color = tinta)
                        Text(l.proxima.ifBlank { l.titulo }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tinta)
                        if (l.proxima.isNotBlank() && l.titulo != l.proxima) Text(l.titulo, style = MaterialTheme.typography.bodyMedium, color = tinta)
                        if (l.porQue.isNotBlank()) Text("Por qué pasó: ${l.porQue}", style = MaterialTheme.typography.bodySmall, color = tinta)
                    }
                    TextButton(onClick = { LeccionActivity.abrir(this@LeccionesActivity, l.id) }) { Text("Abrir la lección completa") }
                    Text("¿Lo recordabas?", style = MaterialTheme.typography.titleSmall)
                    listOf(
                        Triple(Nota.RECORDABA, "Lo recordaba", Color(0xFF66BB6A)),
                        Triple(Nota.A_MEDIAS, "A medias", Color(0xFFFFB74D)),
                        Triple(Nota.OLVIDE, "Lo olvidé", Color(0xFFEF5350))
                    ).forEachIndexed { i, (nota, nombre, color) ->
                        OutlinedButton(onClick = { contestar(nota) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${i + 1}", color = color, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(10.dp))
                            Text(nombre, Modifier.weight(1f), maxLines = 1)
                            Text(ColaDeRepaso.cuandoVuelve(Repaso.diasHasta(l, nota)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tu respuesta es para comparar: no se guarda.", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { cola.pasar(false); vuelta++ }) { Text("Saltar esta") }
                }
            }
        }
    }

    /**
     * **La lista de comprobación**: lo que haré distinto, como casillas, para mirarla antes de
     * empezar. Las casillas no se guardan: se marcan en cada repaso y se vacían al salir.
     */
    @Composable
    private fun ListaDeComprobacion(visibles: List<LeccionesStore.Entrada>, modifier: Modifier) {
        val filas = visibles.map { it.leccion }.filter { it.enLista && (it.proxima.isNotBlank() || it.esError) }
            .sortedWith(compareByDescending<Leccion> { it.gravedad }.thenByDescending { it.repeticiones.size })
        val marcadas = remember { mutableStateMapOf<String, Boolean>() }
        LazyColumn(modifier, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(
                    "Antes de empezar, repasa: ${marcadas.count { it.value }} de ${filas.size}",
                    color = Cristal.tinta, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp)
                )
            }
            if (filas.isEmpty()) item {
                Text("Aún no hay nada de «la próxima vez…». Se rellena en «Qué pasó, por qué y qué haré distinto».",
                    color = Cristal.tinta.copy(alpha = 0.7f), modifier = Modifier.padding(8.dp))
            }
            items(filas, key = { it.id }) { l ->
                Row(
                    Modifier.fillMaxWidth().cristal(RoundedCornerShape(16.dp)).clickable { marcadas[l.id] = !(marcadas[l.id] ?: false) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = marcadas[l.id] == true, onCheckedChange = { marcadas[l.id] = it })
                    Column(Modifier.weight(1f)) {
                        Text(l.proxima.ifBlank { l.titulo }, color = Cristal.tinta, fontWeight = FontWeight.Medium)
                        Text("${iconoDe(l)} ${l.titulo}", color = Cristal.tinta.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (l.repeticiones.isNotEmpty()) Text("🔁×${l.vecesQuePaso}", color = Color(0xFFFF8A65), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    @Composable
    private fun Vacia(modifier: Modifier) {
        Column(modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("💡", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            Text("Apunta lo que aprendes para no repetir errores", style = MaterialTheme.typography.titleMedium, color = Cristal.tinta)
            Spacer(Modifier.height(8.dp))
            Text(
                "Pulsa Dictar y cuéntalo de corrido: «pasó que…, porque…, la próxima vez…». " +
                    "PixPin lo reparte, le pone etiquetas y te avisa si ya te pasó antes.",
                color = Cristal.tinta.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    private fun iconoDe(l: Leccion) = when (l.tipo) { Leccion.TIPO_ERROR -> "⚠️"; Leccion.TIPO_ACIERTO -> "✅"; else -> "💡" }

    companion object {
        private const val EXTRA_PROYECTO = "lecciones_proyecto"
        private val NOMBRES_DE_GRAVEDAD = listOf("Leve", "Importante", "Grave")

        /** Si la lista está a la vista (detrás de la hoja también): entonces ella ofrece «Deshacer». */
        @Volatile var aLaVista = false
            private set
        private const val EXTRA_CONSULTA = "lecciones_consulta"

        /** El color de cada área: los cuatro de inicio fijos, las demás sacados de su nombre. */
        fun colorDe(area: String): Color = when (area) {
            "Trabajo" -> Color(0xFF42A5F5)
            "Construcción" -> Color(0xFFFF9800)
            "Estudio" -> Color(0xFFAB47BC)
            "Vida diaria" -> Color(0xFF66BB6A)
            "" -> Color(0xFF9E9E9E)
            else -> Color.hsv(((area.hashCode() and 0x7fffffff) % 360).toFloat(), 0.55f, 0.85f)
        }

        fun abrir(context: Context, proyecto: String? = null, consulta: String? = null) =
            context.startActivity(
                Intent(context, LeccionesActivity::class.java)
                    .putExtra(EXTRA_PROYECTO, proyecto).putExtra(EXTRA_CONSULTA, consulta)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
    }
}
