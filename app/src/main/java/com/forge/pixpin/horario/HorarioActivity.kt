package com.forge.pixpin.horario

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.pin.Voz
import com.forge.pixpin.ui.theme.BotonRedondo
import com.forge.pixpin.ui.theme.Cristal
import com.forge.pixpin.ui.theme.PixPinTheme
import com.forge.pixpin.ui.theme.cristal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * **El horario de la semana** (25-oct-2026, la ventana semanal del PC, `horario/ventana.rs`; el
 * usuario: «una interfaz estilo calendario semanal en la que pueda ingresar mi horario: … mis
 * asignaturas, y jalarlas sobre el calendario y soltarlas; que cada asignatura sea un grupo de
 * tareas»).
 *
 * En el teléfono, con el dedo y no con el ratón: **se elige una asignatura arriba y se toca la
 * semana** donde va (arrastrar desde una fila lateral no cabe en una pantalla de móvil). Un bloque
 * se **mueve dejándolo pulsado y arrastrando**, y **se toca para cambiarlo** (horas, aula, lo que
 * lee la voz, el aviso). Cada asignatura es una lista de tareas ([Asignaturas]).
 *
 * Todo se escribe en el MISMO `horario.md` ([Semana.reescribir]: solo las líneas de clases y de
 * asignaturas), así que «Como texto» deja escribirlo a mano o **pegar una tabla** de Excel o de la
 * web, como en el PC. Al guardar se rehacen las llamadas ([AvisosDelHorario]).
 */
class HorarioActivity : ComponentActivity() {

    /** La asignatura a la que va la nota que se pidió grabar mientras se pedía el micrófono. */
    private var paraGrabar: String? = null
    private var grabandoPara by mutableStateOf<String?>(null)
    private var grabador: com.forge.pixpin.audio.Grabador? = null
    private var temporal: File? = null

    private val pedirMicro = registerForActivityResult(ActivityResultContracts.RequestPermission()) { si ->
        val n = paraGrabar
        paraGrabar = null
        if (si && n != null) empezarAGrabar(n)
        else if (!si) Toast.makeText(this, "Sin micrófono no se puede grabar la nota", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PixPinTheme { Pantalla() } }
    }

    // ------------------------------------------------------------- la nota de voz

    /**
     * **«Nota de voz para la próxima clase»** (`grabar_para_la_proxima` del PC): se graba en el
     * fichero de la asignatura y suena al contestar la llamada de su próxima clase. Se graba en un
     * temporal y se pone en su sitio al acabar: una grabación fallida no pisa la que había.
     */
    private fun grabar(asignatura: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            empezarAGrabar(asignatura)
        } else {
            paraGrabar = asignatura
            pedirMicro.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun empezarAGrabar(asignatura: String) {
        val carpeta = AvisosDelHorario.carpetaDeVoz(this).apply { mkdirs() }
        val t = File(carpeta, "grabando.m4a")
        val g = Voz.empezar(this, t)
        if (g == null) { Toast.makeText(this, "El micrófono no está libre", Toast.LENGTH_SHORT).show(); return }
        temporal = t; grabador = g; grabandoPara = asignatura
    }

    private fun pararDeGrabar(guardar: Boolean) {
        val n = grabandoPara ?: return
        val bien = Voz.parar(grabador)
        grabador = null; grabandoPara = null
        val t = temporal ?: return
        temporal = null
        if (!guardar) { t.delete(); return }
        if (!bien || Voz.duracion(t.absolutePath) < Voz.MINIMO_MS) {
            t.delete()
            Toast.makeText(this, "Muy corta: no se guardó", Toast.LENGTH_SHORT).show()
            return
        }
        val destino = File(t.parentFile, Horario.ficheroDeVoz(n))
        destino.delete()
        if (t.renameTo(destino)) Toast.makeText(this, "Se oirá en la próxima clase de $n", Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        super.onStop()
        // Si la pantalla se va grabando, no se deja el micrófono cogido.
        if (grabador != null) pararDeGrabar(guardar = true)
    }

    // ------------------------------------------------------------- la pantalla

    private fun copia(s: Semana) = Semana(s.asignaturas.toMutableList(), s.bloques.toMutableList())

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Pantalla() {
        val app = application as PixPinApp
        val contexto = this
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
                if (!hecho) withContext(Dispatchers.Main) { Toast.makeText(contexto, "No se pudo guardar el horario", Toast.LENGTH_SHORT).show() }
            }
        }

        /** Guarda lo de la rejilla (cambia solo las líneas de clases y de asignaturas). */
        fun guardar(s: Semana) = guardarTexto(Semana.reescribir(original ?: Horario.PLANTILLA, s))

        LaunchedEffect(Unit) { ponerTexto(withContext(Dispatchers.IO) { AvisosDelHorario.texto(contexto) }) }

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding()) {
            // Cabecera.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BotonRedondo(Icons.AutoMirrored.Filled.ArrowBack, "Volver", { finish() }, tamano = 44.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Horario", style = MaterialTheme.typography.titleLarge, color = Cristal.tinta, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${semana.asignaturas.size} asignaturas · ${leido.clases.sumOf { it.dias.size }} clases",
                        style = MaterialTheme.typography.labelMedium, color = Cristal.tinta.copy(alpha = 0.7f)
                    )
                }
                BotonRedondo(Icons.Filled.Mic, "Nota de voz para la próxima clase", {
                    val i = Horario.claseParaGrabar(leido, AvisosDelHorario.ahoraLocal())
                    if (i == null) Toast.makeText(contexto, "Aún no hay clases en el horario", Toast.LENGTH_SHORT).show()
                    else grabar(leido.clases[i].nombre)
                }, tamano = 44.dp)
                Spacer(Modifier.width(6.dp))
                BotonRedondo(Icons.Filled.EditNote, "Como texto", { comoTexto = true }, tamano = 44.dp)
            }

            // Las asignaturas: se elige una y se toca la semana.
            LazyRow(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(semana.asignaturas.withIndex().toList(), key = { it.value.nombre }) { (i, a) ->
                    val puesta = elegida?.let { Horario.norm(it) == Horario.norm(a.nombre) } == true
                    Chapa(a.nombre, Color(0xFF000000.toInt() or semana.colorDe(a.nombre)), puesta,
                        onToque = { elegida = if (puesta) null else a.nombre },
                        onLargo = { deAsignatura = i })
                }
                item {
                    Box(Modifier.clip(RoundedCornerShape(18.dp)).cristal(RoundedCornerShape(18.dp)).clickable { nueva = true }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("+ Asignatura", color = Cristal.tinta)
                    }
                }
            }
            Text(
                when {
                    elegida != null -> "Toca la semana para poner «$elegida». Mantén pulsada una clase para moverla."
                    semana.asignaturas.isEmpty() -> "Añade una asignatura y ponla en la semana (o pega una tabla en «Como texto»)."
                    else -> "Elige una asignatura y toca la semana. Mantén pulsada una asignatura para sus tareas."
                },
                style = MaterialTheme.typography.labelMedium, color = Cristal.tinta.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                LaSemana(semana, if (conDomingo) 7 else 6,
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
                // Las evaluaciones: llaman 3 días y 1 día antes.
                Column(Modifier.fillMaxWidth().padding(12.dp).cristal(RoundedCornerShape(18.dp)).padding(14.dp)) {
                    Text("Evaluaciones", fontWeight = FontWeight.SemiBold, color = Cristal.tinta)
                    Text("Llaman ${leido.diasAntes.joinToString(" y ")} días antes", style = MaterialTheme.typography.labelMedium, color = Cristal.tinta.copy(alpha = 0.65f))
                    val hoy = ahora()
                    for (e in leido.evaluaciones.sortedBy { it.cuando }) {
                        Text("📝 ${e.que} · ${e.cuando.legible(hoy)}", color = Cristal.tinta, modifier = Modifier.padding(top = 6.dp))
                    }
                    TextButton(onClick = { evaluacion = true }) { Text("Añadir evaluación") }
                }
                Spacer(Modifier.height(24.dp))
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
                onGrabar = { grabar(b.nombre) },
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
                onGrabar = { grabar(a.nombre) },
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
                            val pegado = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
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

        grabandoPara?.let { n ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Grabando…") },
                text = { Text("La nota para la próxima clase de «$n». Se oirá al contestar su llamada.") },
                confirmButton = { TextButton(onClick = { pararDeGrabar(guardar = true) }) { Text("Guardar") } },
                dismissButton = { TextButton(onClick = { pararDeGrabar(guardar = false) }) { Text("Tirar") } }
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
    private fun LaSemana(semana: Semana, dias: Int, onSoltar: (Int, Int) -> Unit, onTocar: (Int) -> Unit, onMover: (Int, Int, Long) -> Unit) {
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
                    for ((i, b) in semana.bloques.withIndex()) androidx.compose.runtime.key(i, b) {
                        if (b.dia >= dias) return@key
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
                }
            }
        }
    }

    /** «8:00». */
    private fun hora(m: Int) = "%d:%02d".format(m / 60, m % 60)

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
        val contexto = this
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
        onColor: () -> Unit, onAula: (String?) -> Unit, onQuitar: () -> Unit, onGrabar: () -> Unit, onCerrar: () -> Unit
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
                    TextButton(onClick = { com.forge.pixpin.mini.TodasLasTareasActivity.abrir(this@HorarioActivity) }) { Text("Abrir en Tareas") }
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

    companion object {
        fun abrir(context: Context) = context.startActivity(
            Intent(context, HorarioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
