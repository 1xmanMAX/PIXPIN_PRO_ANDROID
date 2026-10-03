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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val hoy = remember(todas) { Repaso.deHoy(todas.map { it.leccion }, System.currentTimeMillis()) }

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
                        item(span = StaggeredGridItemSpan.FullLine) { ParaRepasar(hoy, almacen, todas) }
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
        }
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
     * **Repasar sin releer**: se enseña lo que pasó (o la etiqueta) y se intenta recordar qué
     * haría uno antes de destaparlo. Recordar mueve la tarjeta a días más lejanos; olvidar la trae.
     */
    @Composable
    private fun ParaRepasar(hoy: List<Leccion>, almacen: LeccionesStore, todas: List<LeccionesStore.Entrada>) {
        val l = hoy.first()
        var destapada by remember(l.id) { mutableStateOf(false) }
        val app = application as PixPinApp
        fun marcar(recordada: Boolean) {
            val e = todas.firstOrNull { it.leccion.id == l.id } ?: return
            val ahora = System.currentTimeMillis()
            val nueva = if (recordada) Repaso.recordada(l, ahora) else Repaso.olvidada(l, ahora)
            app.scope.launch(Dispatchers.IO) { runCatching { almacen.guardar(nueva, e.proyecto) } }
        }
        Column(
            Modifier.fillMaxWidth().cristal(RoundedCornerShape(22.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("🧠 Para repasar hoy · ${hoy.size}", style = MaterialTheme.typography.labelLarge, color = Cristal.puesto)
            val pista = l.quePaso.ifBlank { l.todasLasEtiquetas.joinToString(" ") { "#$it" }.ifBlank { l.area } }
            Text(
                if (pista.isNotBlank()) "Cuando $pista…" else "Recuerda esta lección",
                color = Cristal.tinta, style = MaterialTheme.typography.bodyLarge
            )
            if (!destapada) {
                FilledTonalButton(onClick = { destapada = true }) { Text("¿Qué harías? Ver respuesta") }
            } else {
                Text(l.proxima.ifBlank { l.titulo }, color = Cristal.tinta, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { marcar(true) }) { Text("Lo recordaba") }
                    OutlinedButton(onClick = { marcar(false) }) { Text("Lo olvidé") }
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
