package com.forge.pixpin.horario

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.mini.VistaDeTareas
import com.forge.pixpin.pin.Voz
import com.forge.pixpin.ui.theme.BotonRedondo
import com.forge.pixpin.ui.theme.Cristal
import com.forge.pixpin.ui.theme.cristal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Una tarea con hora que quien aloja el panel quiere en la semana (`TareaConHora` del PC). */
data class TareaConHora(
    /** Lo que se lee en la chapita (sin las marcas). */
    val texto: String,
    /** Su hora local, en ms desde 1970. */
    val cuando: Long,
    /** El color de su lista, `0xRRGGBB`. */
    val color: Int
)

/** El alto de la chapita de una tarea con hora, y a menos de cuántos minutos van una debajo de otra. */
private val ALTO_CHIP = 18.dp
private const val PASO_CHIP = 30

/**
 * **«Nota de voz para la próxima clase»** (`grabar_para_la_proxima` del PC): se graba en el fichero
 * de la asignatura y suena al contestar la llamada de su próxima clase. Se graba en un temporal y se
 * pone en su sitio al acabar: una grabación fallida no pisa la que había.
 *
 * **Vive en la pantalla que aloja el panel** (25-oct-2026) porque pide el micrófono, y eso se
 * registra al crearla: se construye en un campo de la actividad y se le avisa en `onStop` ([alParar]).
 */
class NotaDeVozDeClase(private val actividad: ComponentActivity) {
    /** La asignatura a la que va la nota que se pidió grabar mientras se pedía el micrófono. */
    private var paraGrabar: String? = null
    var grabandoPara by mutableStateOf<String?>(null)
        private set
    private var grabador: com.forge.pixpin.audio.Grabador? = null
    private var temporal: File? = null

    private val pedirMicro = actividad.registerForActivityResult(ActivityResultContracts.RequestPermission()) { si ->
        val n = paraGrabar
        paraGrabar = null
        if (si && n != null) empezarAGrabar(n)
        else if (!si) Toast.makeText(actividad, "Sin micrófono no se puede grabar la nota", Toast.LENGTH_SHORT).show()
    }

    fun grabar(asignatura: String) {
        if (ContextCompat.checkSelfPermission(actividad, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            empezarAGrabar(asignatura)
        } else {
            paraGrabar = asignatura
            pedirMicro.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun empezarAGrabar(asignatura: String) {
        val carpeta = AvisosDelHorario.carpetaDeVoz(actividad).apply { mkdirs() }
        val t = File(carpeta, "grabando.m4a")
        val g = Voz.empezar(actividad, t)
        if (g == null) { Toast.makeText(actividad, "El micrófono no está libre", Toast.LENGTH_SHORT).show(); return }
        temporal = t; grabador = g; grabandoPara = asignatura
    }

    fun parar(guardar: Boolean) {
        val n = grabandoPara ?: return
        val bien = Voz.parar(grabador)
        grabador = null; grabandoPara = null
        val t = temporal ?: return
        temporal = null
        if (!guardar) { t.delete(); return }
        if (!bien || Voz.duracion(t.absolutePath) < Voz.MINIMO_MS) {
            t.delete()
            Toast.makeText(actividad, "Muy corta: no se guardó", Toast.LENGTH_SHORT).show()
            return
        }
        val destino = File(t.parentFile, Horario.ficheroDeVoz(n))
        destino.delete()
        if (t.renameTo(destino)) Toast.makeText(actividad, "Se oirá en la próxima clase de $n", Toast.LENGTH_LONG).show()
    }

    /** Si la pantalla se va grabando, no se deja el micrófono cogido. */
    fun alParar() {
        if (grabador != null) parar(guardar = true)
    }
}

private fun copia(s: Semana) = Semana(s.asignaturas.toMutableList(), s.bloques.toMutableList())

/** El lunes de la semana de [msLocales] (días desde 1970), para colocar las tareas con hora. */
internal fun lunesDe(msLocales: Long): Long {
    val dia = Math.floorDiv(msLocales, 86_400_000L)
    return dia - (java.time.LocalDate.ofEpochDay(dia).dayOfWeek.value - 1)
}

/**
 * **El horario de la semana, como panel** (25-oct-2026): la vista **Horario** de la pantalla de
 * Tareas (Tarjetas · Kanban · Horario), y ya no una pantalla aparte. Es lo que hizo el PC el mismo
 * día (`5139e5d2`: `horario/ventana.rs` pasa a `horario/panel.rs`, alojado en la ventana de
 * Tareas); el usuario: «ahora quiero que la función de Horario aparezca en la ventana de Tareas».
 * Lo aloja [com.forge.pixpin.mini.TodasLasTareasActivity]; el mosaico Horario de Funciones abre
 * Tareas en esta vista.
 *
 * El calendario es el de antes (la ventana semanal del PC; el usuario: «una interfaz estilo
 * calendario semanal en la que pueda ingresar mi horario: … mis asignaturas, y jalarlas sobre el
 * calendario y soltarlas; que cada asignatura sea un grupo de tareas»). En el teléfono, con el
 * dedo: **se elige una asignatura arriba y se toca la semana** donde va; un bloque se **mueve
 * dejándolo pulsado y arrastrando** y **se toca para cambiarlo**. En una pantalla ancha (tableta,
 * teléfono girado) las asignaturas van en una columna a la izquierda, como en el PC
 * ([VistaDeTareas.disponerHorario]).
 *
 * **Las tareas con hora (`⏰`) de esta semana** ([tareas]) salen como chapitas en su día y su hora,
 * pegadas a la derecha del día ([VistaDeTareas.chipsDeLaSemana]); tocar una ([onTarea]) la enseña
 * en las tarjetas. [onAbrirEnTareas]: ver las tareas de una asignatura en las tarjetas.
 *
 * Todo se escribe en el MISMO `horario.md` ([Semana.reescribir]: solo las líneas de clases y de
 * asignaturas), así que «Como texto» deja escribirlo a mano o **pegar una tabla**, como en el PC.
 * Al guardar se rehacen las llamadas ([AvisosDelHorario]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PanelDelHorario(
    voz: NotaDeVozDeClase,
    tareas: List<TareaConHora>,
    onTarea: (Int) -> Unit,
    onAbrirEnTareas: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val contexto = LocalContext.current
    val app = contexto.applicationContext as PixPinApp
    val alcance = rememberCoroutineScope()
    var original by remember { mutableStateOf<String?>(null) }
    var leido by remember { mutableStateOf(Horario.Leido()) }
    var semana by remember { mutableStateOf(Semana()) }
    var elegida by remember { mutableStateOf<String?>(null) }
    var conDomingo by remember { mutableStateOf(false) }
    var editando by remember { mutableStateOf<Int?>(null) }
    var deAsignatura by remember { mutableStateOf<Int?>(null) }
    var nueva by remember { mutableStateOf(false) }
    var comoTexto by remember { mutableStateOf(false) }
    var evaluacion by remember { mutableStateOf(false) }

    fun ahora() = Momento.deMsLocales(AvisosDelHorario.ahoraLocal())

    fun ponerTexto(texto: String) {
        original = texto
        leido = Horario.leer(texto, ahora())
        val s = Semana.deHorario(leido)
        semana = s
        if (s.bloques.any { it.dia == 6 }) conDomingo = true
    }

    /** Guarda un texto entero (el escrito a mano, o el de la rejilla) y rehace las llamadas. */
    fun guardarTexto(texto: String) {
        ponerTexto(texto)
        alcance.launch(Dispatchers.IO) {
            val hecho = runCatching { AvisosDelHorario.guardar(contexto, texto) }.isSuccess
            // Las llamadas de las clases son alarmas: sin permiso de exactas, se ofrece (versión de Play).
            if (hecho) com.forge.pixpin.pin.AlarmaExacta.ofrecerSiFalta(contexto)
            if (!hecho) withContext(Dispatchers.Main) { Toast.makeText(contexto, "No se pudo guardar el horario", Toast.LENGTH_SHORT).show() }
        }
    }

    /** Guarda lo de la rejilla (cambia solo las líneas de clases y de asignaturas). */
    fun guardar(s: Semana) = guardarTexto(Semana.reescribir(original ?: Horario.PLANTILLA, s))

    LaunchedEffect(Unit) { ponerTexto(withContext(Dispatchers.IO) { AvisosDelHorario.texto(contexto) }) }
    val lunes = remember { lunesDe(AvisosDelHorario.ahoraLocal()) }

    /** Las asignaturas: se elige una y se toca la semana. En fila encima, o en su columna al lado. */
    @Composable
    fun LasAsignaturas(enColumna: Boolean) {
        val chapas: @Composable () -> Unit = {
            semana.asignaturas.forEachIndexed { i, a ->
                androidx.compose.runtime.key(a.nombre) {
                    val puesta = elegida?.let { Horario.norm(it) == Horario.norm(a.nombre) } == true
                    Chapa(a.nombre, Color(0xFF000000.toInt() or semana.colorDe(a.nombre)), puesta,
                        onToque = { elegida = if (puesta) null else a.nombre },
                        onLargo = { deAsignatura = i })
                }
            }
            Box(Modifier.clip(RoundedCornerShape(18.dp)).cristal(RoundedCornerShape(18.dp)).clickable { nueva = true }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("+ Asignatura", color = Cristal.tinta)
            }
        }
        if (enColumna) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { chapas() }
        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chapas() }
        Text(
            when {
                elegida != null -> "Toca la semana para poner «$elegida». Mantén pulsada una clase para moverla."
                semana.asignaturas.isEmpty() -> "Añade una asignatura y ponla en la semana (o pega una tabla en «Como texto»)."
                else -> "Elige una asignatura y toca la semana. Mantén pulsada una asignatura para sus tareas."
            },
            style = MaterialTheme.typography.labelMedium, color = Cristal.tinta.copy(alpha = 0.65f),
            modifier = Modifier.padding(horizontal = if (enColumna) 4.dp else 16.dp, vertical = 6.dp)
        )
    }

    @Composable
    fun LaSemanaYSuPie() {
        LaSemana(semana, if (conDomingo) 7 else 6, tareas, lunes, onTarea,
            onSoltar = { dia, minuto ->
                val n = elegida
                val i = n?.let { semana.asignatura(it) }
                if (i == null) Toast.makeText(contexto, "Elige antes una asignatura arriba", Toast.LENGTH_SHORT).show()
                else guardar(copia(semana).also { it.soltar(i, dia, minuto) })
            },
            onTocar = { editando = it },
            onMover = { i, dia, inicio -> guardar(copia(semana).also { it.mover(i, dia, inicio) }) }
        )
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = conDomingo, onClick = { conDomingo = !conDomingo }, label = { Text("Domingo") })
            Spacer(Modifier.width(8.dp))
            Text("Avisa ${leido.antelacion} min antes de cada clase", style = MaterialTheme.typography.labelMedium, color = Cristal.tinta.copy(alpha = 0.65f))
        }
    }

    /** Las evaluaciones: llaman 3 días y 1 día antes. */
    @Composable
    fun LasEvaluaciones() {
        Column(Modifier.fillMaxWidth().padding(12.dp).cristal(RoundedCornerShape(18.dp)).padding(14.dp)) {
            Text("Evaluaciones", fontWeight = FontWeight.SemiBold, color = Cristal.tinta)
            Text("Llaman ${leido.diasAntes.joinToString(" y ")} días antes", style = MaterialTheme.typography.labelMedium, color = Cristal.tinta.copy(alpha = 0.65f))
            val hoy = ahora()
            for (e in leido.evaluaciones.sortedBy { it.cuando }) {
                Text("📝 ${e.que} · ${e.cuando.legible(hoy)}", color = Cristal.tinta, modifier = Modifier.padding(top = 6.dp))
            }
            TextButton(onClick = { evaluacion = true }) { Text("Añadir evaluación") }
        }
    }

    Column(modifier.fillMaxSize()) {
        // Lo que se cuenta, la nota de voz y el texto (lo que iba en la cabecera de la pantalla de antes).
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${semana.asignaturas.size} asignaturas · ${leido.clases.sumOf { it.dias.size }} clases",
                style = MaterialTheme.typography.labelLarge, color = Cristal.tinta.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f)
            )
            BotonRedondo(Icons.Filled.Mic, "Nota de voz para la próxima clase", {
                val i = Horario.claseParaGrabar(leido, AvisosDelHorario.ahoraLocal())
                if (i == null) Toast.makeText(contexto, "Aún no hay clases en el horario", Toast.LENGTH_SHORT).show()
                else voz.grabar(leido.clases[i].nombre)
            }, tamano = 40.dp)
            Spacer(Modifier.width(6.dp))
            BotonRedondo(Icons.Filled.EditNote, "Como texto", { comoTexto = true }, tamano = 40.dp)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Ancha (tableta, teléfono girado): las asignaturas en su columna al lado, como en el PC.
            if (maxWidth >= 600.dp) {
                val d = VistaDeTareas.disponerHorario(VistaDeTareas.Caja(0f, 0f, maxWidth.value, maxHeight.value), 1f)
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(start = d.lateral.x.dp).width(d.lateral.ancho.dp).fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                    ) {
                        LasAsignaturas(enColumna = true)
                        LasEvaluaciones()
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 8.dp)) {
                        LaSemanaYSuPie()
                        Spacer(Modifier.height(24.dp))
                    }
                }
            } else Column(Modifier.fillMaxSize()) {
                LasAsignaturas(enColumna = false)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    LaSemanaYSuPie()
                    LasEvaluaciones()
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (nueva) {
        var escrito by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { nueva = false },
            title = { Text("Nueva asignatura") },
            text = { OutlinedTextField(escrito, { escrito = it }, label = { Text("Nombre (@aula opcional)") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val s = copia(semana)
                    when (val r = s.anadirAsignatura(escrito)) {
                        is Semana.Resultado.Repetida -> Toast.makeText(contexto, "«${s.asignaturas[r.i].nombre}» ya está en la lista", Toast.LENGTH_SHORT).show()
                        is Semana.Resultado.Nueva -> r.i?.let { i ->
                            val nombre = s.asignaturas[i].nombre
                            guardar(s)
                            elegida = nombre
                            // Su lista de tareas, en el chat «Asignaturas».
                            alcance.launch(Dispatchers.IO) { runCatching { Asignaturas.asegurarLista(app, nombre) } }
                        }
                    }
                    nueva = false
                }) { Text("Añadir") }
            },
            dismissButton = { TextButton(onClick = { nueva = false }) { Text("Cancelar") } }
        )
    }

    editando?.let { i ->
        val b = semana.bloques.getOrNull(i)
        if (b == null) editando = null
        else EditarClase(b,
            onGuardar = { nuevo -> guardar(copia(semana).also { it.bloques[i] = nuevo }); editando = null },
            onBorrar = { guardar(copia(semana).also { it.borrar(i) }); editando = null; Toast.makeText(contexto, "Clase borrada", Toast.LENGTH_SHORT).show() },
            onGrabar = { voz.grabar(b.nombre) },
            onCerrar = { editando = null })
    }

    deAsignatura?.let { i ->
        val a = semana.asignaturas.getOrNull(i)
        if (a == null) deAsignatura = null
        else DeLaAsignatura(app, a, Color(0xFF000000.toInt() or semana.colorDe(a.nombre)),
            onColor = { guardar(copia(semana).also { it.cambiarColor(i) }) },
            onAula = { aula -> guardar(copia(semana).also { it.ponerAulaDeAsignatura(i, aula) }) },
            onQuitar = {
                guardar(copia(semana).also { it.borrarAsignatura(i) })
                if (elegida?.let { Horario.norm(it) == Horario.norm(a.nombre) } == true) elegida = null
                deAsignatura = null
                Toast.makeText(contexto, "«${a.nombre}» quitada del horario (sus tareas siguen en Tareas)", Toast.LENGTH_LONG).show()
            },
            onGrabar = { voz.grabar(a.nombre) },
            onAbrirEnTareas = { deAsignatura = null; onAbrirEnTareas(a.nombre) },
            onCerrar = { deAsignatura = null })
    }

    if (comoTexto) {
        var t by remember { mutableStateOf(original ?: Horario.PLANTILLA) }
        AlertDialog(
            onDismissRequest = { comoTexto = false },
            title = { Text("Horario como texto") },
            text = {
                Column {
                    Text(
                        "Una clase por línea: «Lun Mie 8:00-10:00 Geometría @A-12». Debajo, lo que empieza por > lo lee la voz. También vale pegar una tabla de Excel o de la web.",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(t, { t = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp), textStyle = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        val pegado = (contexto.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
                            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(contexto)?.toString()
                        if (pegado.isNullOrBlank()) Toast.makeText(contexto, "No hay nada copiado", Toast.LENGTH_SHORT).show()
                        else t = t.trimEnd() + "\n\n" + pegado.trim() + "\n"
                    }) { Text("Pegar una tabla") }
                }
            },
            confirmButton = { TextButton(onClick = { guardarTexto(if (t.endsWith("\n")) t else t + "\n"); comoTexto = false }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { comoTexto = false }) { Text("Cancelar") } }
        )
    }

    if (evaluacion) {
        var que by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { evaluacion = false },
            title = { Text("Nueva evaluación") },
            text = {
                Column {
                    Text("Qué y cuándo, como en las tareas: «Examen Geometría 23/10», «Entrega de Física el jueves».", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(que, { que = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val linea = que.trim().let { if (Horario.leer(it, ahora()).evaluaciones.isEmpty()) "Evaluación $it" else it }
                    val h = Horario.leer(linea, ahora())
                    if (h.evaluaciones.isEmpty()) Toast.makeText(contexto, "No entiendo la fecha (o ya pasó)", Toast.LENGTH_SHORT).show()
                    else { guardarTexto((original ?: Horario.PLANTILLA).trimEnd() + "\n" + linea + "\n"); evaluacion = false }
                }) { Text("Añadir") }
            },
            dismissButton = { TextButton(onClick = { evaluacion = false }) { Text("Cancelar") } }
        )
    }

    voz.grabandoPara?.let { n ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Grabando…") },
            text = { Text("La nota para la próxima clase de «$n». Se oirá al contestar su llamada.") },
            confirmButton = { TextButton(onClick = { voz.parar(guardar = true) }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { voz.parar(guardar = false) }) { Text("Tirar") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chapa(nombre: String, color: Color, puesta: Boolean, onToque: () -> Unit, onLargo: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (puesta) color.copy(alpha = 0.3f) else Cristal.boton)
            .then(if (puesta) Modifier.border(2.dp, color, RoundedCornerShape(18.dp)) else Modifier)
            .combinedClickable(onClick = onToque, onLongClick = onLargo)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(nombre, color = Cristal.tinta, maxLines = 1)
    }
}

/**
 * **La rejilla**: la columna de las horas, una columna por día y los bloques encima. Las cuentas
 * (qué día y qué minuto hay bajo el dedo, dónde cae cada bloque) son las de [Rejilla], en píxeles.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LaSemana(
    semana: Semana, dias: Int, tareas: List<TareaConHora>, lunes: Long, onTarea: (Int) -> Unit,
    onSoltar: (Int, Int) -> Unit, onTocar: (Int) -> Unit, onMover: (Int, Int, Long) -> Unit
) {
    val densidad = LocalDensity.current
    val horas = 40.dp
    val franja = 12.dp
    val tinta = Cristal.tinta
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val anchoDia = (maxWidth - horas) / dias
        val r = with(densidad) { com.forge.pixpin.horario.Rejilla(horas.toPx(), 0f, anchoDia.toPx(), franja.toPx(), dias) }
        Column {
            Row {
                Spacer(Modifier.width(horas))
                for (d in 0 until dias) Text(Semana.DIAS[d], Modifier.width(anchoDia).padding(vertical = 4.dp), color = tinta, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Box(
                Modifier.fillMaxWidth().height(with(densidad) { r.alto().toDp() })
                    .pointerInput(dias, r) { detectTapGestures { p -> r.en(p.x, p.y)?.let { (d, m) -> onSoltar(d, m) } } }
            ) {
                // Las horas y sus rayas.
                for (h in Semana.DESDE / 60 until Semana.HASTA / 60) {
                    val y = with(densidad) { r.yDe(h * 60).toDp() }
                    Box(Modifier.offset(y = y).fillMaxWidth().height(1.dp).background(tinta.copy(alpha = 0.08f)))
                    Text("$h:00", Modifier.offset(y = y).width(horas), fontSize = 10.sp, color = tinta.copy(alpha = 0.55f))
                }
                // Sin `return@key`: salir antes de un `key {}` deja un método que R8 no sabe pasar a dex.
                for ((i, b) in semana.bloques.withIndex()) if (b.dia < dias) androidx.compose.runtime.key(i, b) {
                    val caja = r.caja(b.dia, b.inicio, b.fin)
                    var arrastre by remember(b) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                    val color = Color(0xFF000000.toInt() or semana.colorDe(b.nombre))
                    Column(
                        Modifier
                            .offset { IntOffset((caja.x + arrastre.x).roundToInt(), (caja.y + arrastre.y).roundToInt()) }
                            .size(with(densidad) { caja.ancho.toDp() }, with(densidad) { caja.alto.toDp() })
                            .padding(1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(color.copy(alpha = if (arrastre == androidx.compose.ui.geometry.Offset.Zero) 0.92f else 0.6f))
                            .pointerInput(b, i, r) {
                                detectDragGesturesAfterLongPress(
                                    onDrag = { cambio, d -> cambio.consume(); arrastre += d },
                                    onDragEnd = {
                                        val dia = r.diaCercano(caja.x + arrastre.x + caja.ancho / 2)
                                        val inicio = r.minutoEn(caja.y + arrastre.y)
                                        arrastre = androidx.compose.ui.geometry.Offset.Zero
                                        onMover(i, dia, inicio)
                                    },
                                    onDragCancel = { arrastre = androidx.compose.ui.geometry.Offset.Zero }
                                )
                            }
                            .clickable { onTocar(i) }
                            .padding(3.dp)
                    ) {
                        Text(b.nombre, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp)
                        if (caja.alto > with(densidad) { 40.dp.toPx() }) {
                            Text("%d:%02d".format(b.inicio / 60, b.inicio % 60) + (b.aula?.let { " · $it" } ?: ""), color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                ChapitasDeTareas(r, dias, tareas, lunes, onTarea)
            }
        }
    }
}

/** «8:00». */
private fun hora(m: Int) = "%d:%02d".format(m / 60, m % 60)

/**
 * **Las tareas con hora de la semana** (25-oct-2026, `pintar_chips` del PC), como chapitas en su
 * día y su hora, encima de las clases y pegadas a la derecha del día: lo que hay que hacer, a la
 * vista junto a donde se está. Tocar una la enseña en las tarjetas ([onTarea]). Dónde va cada una
 * lo dicen [VistaDeTareas.chipsDeLaSemana] y [VistaDeTareas.cajaDeChip], en píxeles.
 */
@Composable
private fun ChapitasDeTareas(r: Rejilla, dias: Int, tareas: List<TareaConHora>, lunes: Long, onTarea: (Int) -> Unit) {
    val densidad = LocalDensity.current
    val chips = remember(tareas, lunes, dias) {
        VistaDeTareas.chipsDeLaSemana(tareas.map { it.cuando }, lunes, dias, Semana.DESDE, Semana.HASTA, PASO_CHIP)
    }
    val s = densidad.density
    val alto = with(densidad) { ALTO_CHIP.toPx() }
    // Sin `return@key` (ver los bloques): lo que no esté se salta con un `if`.
    for (c in chips) androidx.compose.runtime.key("chip", c.indice) {
        val t = tareas.getOrNull(c.indice)
        if (t != null) {
            val k = VistaDeTareas.cajaDeChip(r.x + c.dia * r.anchoDia, r.anchoDia, r.yDe(c.minuto), c.carril, alto, s)
            val color = Color(0xFF000000.toInt() or t.color)
            val forma = RoundedCornerShape(50)
            Row(
                Modifier.offset { IntOffset(k.x.roundToInt(), k.y.roundToInt()) }
                    .size(with(densidad) { k.ancho.toDp() }, with(densidad) { k.alto.toDp() })
                    .clip(forma).background(Color.Black.copy(alpha = 0.55f), forma).background(color.copy(alpha = 0.35f), forma)
                    .clickable { onTarea(c.indice) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(3.dp))
                Text(
                    if (c.mas > 0) "${t.texto} +${c.mas}" else t.texto, color = Color.White, fontSize = 9.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 10.sp
                )
            }
        }
    }
}

/** **Cambiar una clase**: el día, las horas, el aula, lo que lee la voz y el aviso. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditarClase(b: Semana.Bloque, onGuardar: (Semana.Bloque) -> Unit, onBorrar: () -> Unit, onGrabar: () -> Unit, onCerrar: () -> Unit) {
    var dia by remember { mutableStateOf(b.dia) }
    var inicio by remember { mutableStateOf(hora(b.inicio)) }
    var fin by remember { mutableStateOf(hora(b.fin)) }
    var aula by remember { mutableStateOf(b.aula.orEmpty()) }
    var notas by remember { mutableStateOf(b.notas.joinToString("\n")) }
    var avisar by remember { mutableStateOf(b.antelacion?.toString().orEmpty()) }
    val contexto = LocalContext.current
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(b.nombre) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Semana.DIAS.forEachIndexed { d, n -> FilterChip(selected = dia == d, onClick = { dia = d }, label = { Text(n) }) }
                }
                Row {
                    OutlinedTextField(inicio, { inicio = it }, label = { Text("Empieza") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(fin, { fin = it }, label = { Text("Acaba") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(aula, { aula = it }, label = { Text("Aula") }, singleLine = true)
                OutlinedTextField(notas, { notas = it }, label = { Text("Nota para la voz (lo que lee la llamada)") })
                OutlinedTextField(
                    avisar, { avisar = it.filter(Char::isDigit) }, label = { Text("Avisar (minutos antes)") },
                    placeholder = { Text("el de todo el horario") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row {
                    TextButton(onClick = onGrabar) { Text("Grabar nota de voz") }
                    TextButton(onClick = onBorrar) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = Horario.horaDe(inicio)
                val z = Horario.horaDe(fin)
                if (a == null || z == null || z <= a) {
                    Toast.makeText(contexto, "Las horas no cuadran (p. ej. 8:00 y 10:00)", Toast.LENGTH_SHORT).show()
                    return@TextButton
                }
                onGuardar(b.copy(
                    dia = dia, inicio = a, fin = z, aula = aula.trim().ifEmpty { null },
                    notas = notas.lines().map { it.trim() }.filter { it.isNotEmpty() },
                    antelacion = avisar.toIntOrNull()?.coerceAtMost(24 * 60)
                ))
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}

/** **Una asignatura**: su color, su aula, su nota de voz y sus tareas (su lista en «Asignaturas»). */
@Composable
private fun DeLaAsignatura(
    app: PixPinApp, a: Horario.Asignatura, color: Color,
    onColor: () -> Unit, onAula: (String?) -> Unit, onQuitar: () -> Unit, onGrabar: () -> Unit,
    onAbrirEnTareas: () -> Unit, onCerrar: () -> Unit
) {
    var aula by remember(a) { mutableStateOf(a.aula.orEmpty()) }
    var tareas by remember { mutableStateOf<List<String>?>(null) }
    var nuevaTarea by remember { mutableStateOf("") }
    val alcance = rememberCoroutineScope()
    suspend fun releer() { tareas = withContext(Dispatchers.IO) { runCatching { Asignaturas.pendientes(app, a.nombre) }.getOrDefault(emptyList()) } }
    LaunchedEffect(a.nombre) { releer() }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(color).clickable(onClick = onColor))
                Spacer(Modifier.width(10.dp))
                Text(a.nombre)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Toca el círculo para cambiar el color.", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(aula, { aula = it }, label = { Text("Aula") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                Text("Tareas de ${a.nombre}", fontWeight = FontWeight.SemiBold)
                val t = tareas
                when {
                    t == null -> Text("…")
                    t.isEmpty() -> Text("Sin tareas pendientes.", style = MaterialTheme.typography.bodySmall)
                    else -> t.forEach { Text("☐ $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(nuevaTarea, { nuevaTarea = it }, label = { Text("Nueva tarea") }, singleLine = true, modifier = Modifier.weight(1f))
                    BotonRedondo(Icons.Filled.Add, "Apuntar", {
                        val texto = nuevaTarea
                        nuevaTarea = ""
                        alcance.launch {
                            withContext(Dispatchers.IO) { runCatching { Asignaturas.apuntar(app, a.nombre, texto) } }
                            releer()
                        }
                    }, tamano = 40.dp)
                }
                TextButton(onClick = onAbrirEnTareas) { Text("Ver sus tareas") }
                TextButton(onClick = onGrabar) { Text("Nota de voz para su próxima clase") }
                TextButton(onClick = onQuitar) { Text("Quitar del horario", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val nueva = aula.trim().ifEmpty { null }
                if (nueva != a.aula) onAula(nueva)
                onCerrar()
            }) { Text("Listo") }
        }
    )
}
