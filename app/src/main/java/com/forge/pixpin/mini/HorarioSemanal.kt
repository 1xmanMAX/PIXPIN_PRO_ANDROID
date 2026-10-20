package com.forge.pixpin.mini

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.ui.theme.Cristal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **El horario en una semana** (PC `horario/ventana.rs`, 18-oct-2026): «en el lado lateral mis
 * asignaturas, y jalarlas sobre el calendario y soltarlas; que cada asignatura sea un grupo de tareas».
 *
 * En el teléfono las asignaturas van arriba, en una fila: **tocar una la elige** y tocar la rejilla la
 * pone (una hora desde esa franja); también se puede **arrastrar** una asignatura (mantener y llevar)
 * hasta la rejilla. Un bloque se **mueve** manteniéndolo y llevándolo, se **alarga** tirando del borde
 * de abajo y un toque abre su ficha (aula, lo que lee la voz, avisar X min antes, borrar). Mantener una
 * asignatura: su color, su aula, sus tareas pendientes o quitarla. Todo se escribe en el MISMO
 * `horario.md` ([Semana.reescribir]): lo escrito a mano se queda.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HorarioSemanal(texto: String, alCambiar: (String) -> Unit, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val ahora = remember { com.forge.pixpin.ia.Momento.ahora() }
    val estado = remember(texto) { Semana.deHorario(Horario.leer(texto, ahora)) }
    val textoYa by rememberUpdatedState(texto)
    fun cambiar(nuevo: Semana.Estado) = alCambiar(Semana.reescribir(textoYa, nuevo))
    var elegida by remember { mutableIntStateOf(-1) }
    var conDomingo by remember { mutableStateOf(estado.bloques.any { it.dia == 6 }) }
    var nueva by remember { mutableStateOf(false) }
    var deAsignatura by remember { mutableIntStateOf(-1) }
    var ficha by remember { mutableIntStateOf(-1) }
    val densidad = LocalDensity.current
    val altoFranja = 14.dp
    val anchoHoras = 40.dp
    // El arrastre de una asignatura desde la fila (en coordenadas de la pantalla).
    var arrastrando by remember { mutableStateOf<Pair<Int, androidx.compose.ui.geometry.Offset>?>(null) }
    var origenDeLaRejilla by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var inicioDelArrastre by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val chipsEnVentana = remember { HashMap<Int, androidx.compose.ui.geometry.Offset>() }
    var anchoDia by remember { mutableStateOf(0f) }
    val desplazado = rememberScrollState()
    val dias = if (conDomingo) 7 else 6

    /** El día y el minuto bajo un punto de la rejilla (local), o null fuera. */
    fun enLaRejilla(x: Float, y: Float): Pair<Int, Int>? {
        val franja = with(densidad) { altoFranja.toPx() }
        if (x < 0 || y < 0 || anchoDia <= 0f) return null
        val d = (x / anchoDia).toInt()
        val m = Semana.DESDE + (y / franja * Semana.PASO).toInt()
        if (d !in 0 until dias || m >= Semana.HASTA) return null
        return d to m
    }

    Column(modifier) {
        // ---- Las asignaturas ----
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            estado.asignaturas.forEachIndexed { i, a ->
                val color = Color(0xFF000000.toInt() or estado.colorDe(a.nombre))
                val puesta = i == elegida
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(if (puesta) color else Cristal.barra)
                        .border(1.dp, color, RoundedCornerShape(50))
                        .onGloballyPositioned { chipsEnVentana[i] = it.positionInWindow() }
                        .pointerInput(i, estado, dias) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { p -> arrastrando = i to p; inicioDelArrastre = p },
                                onDrag = { cambio, d -> cambio.consume(); arrastrando = arrastrando?.let { (k, p) -> k to p + d } },
                                onDragEnd = {
                                    // Soltada sobre la rejilla: una hora desde esa franja.
                                    val (k, p) = arrastrando ?: return@detectDragGesturesAfterLongPress
                                    arrastrando = null
                                    // Mantener sin llevarla a ningún sitio: su ficha.
                                    if ((p - inicioDelArrastre).getDistance() < 24f) { deAsignatura = k; return@detectDragGesturesAfterLongPress }
                                    val enVentana = (chipsEnVentana[k] ?: return@detectDragGesturesAfterLongPress) + p
                                    val local = enVentana - origenDeLaRejilla
                                    val sitio = enLaRejilla(local.x, local.y)
                                    if (sitio != null) cambiar(estado.soltar(k, sitio.first, sitio.second))
                                },
                                onDragCancel = { arrastrando = null }
                            )
                        }
                        .clickable { elegida = if (puesta) -1 else i }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (puesta) Color.White else color))
                    Spacer(Modifier.width(6.dp))
                    Text(a.nombre, color = if (puesta) Color.White else Cristal.tinta, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
            Text(
                "+ Asignatura", color = Cristal.puesto, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { nueva = true }.padding(horizontal = 12.dp, vertical = 7.dp)
            )
            Text(
                if (conDomingo) "Sin domingo" else "+ Domingo", color = Cristal.tinta.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { conDomingo = !conDomingo }.padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
        Text(
            when {
                estado.asignaturas.isEmpty() -> "Crea tus asignaturas y ponlas en la semana."
                elegida >= 0 -> "Toca la hora en la que tienes «${estado.asignaturas[elegida].nombre}»."
                else -> "Toca una asignatura y luego su hora (o mantenla y llévala). Mantén un bloque para moverlo."
            },
            color = Cristal.tinta.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp)
        )
        // ---- La cabecera de los días ----
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Spacer(Modifier.width(anchoHoras))
            val hoy = com.forge.pixpin.ia.Fechas.diaDeLaSemana(ahora.dia).toInt()
            for (d in 0 until dias) Text(
                Semana.DIAS[d], modifier = Modifier.weight(1f), color = if (d == hoy) Cristal.puesto else Cristal.tinta,
                fontWeight = if (d == hoy) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        // ---- La rejilla ----
        Box(Modifier.weight(1f).verticalScroll(desplazado)) {
            Row {
                Column(Modifier.width(anchoHoras)) {
                    for (h in Semana.DESDE / 60 until Semana.HASTA / 60) Box(Modifier.height(altoFranja * 4)) {
                        Text("$h:00", color = Cristal.tinta.copy(alpha = 0.55f), fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                BoxWithConstraints(
                    Modifier.weight(1f).height(altoFranja * Semana.FRANJAS)
                        .onGloballyPositioned { origenDeLaRejilla = it.positionInWindow() }
                        .pointerInput(elegida, estado, dias) {
                            detectTapGestures { p ->
                                val sitio = enLaRejilla(p.x, p.y) ?: return@detectTapGestures
                                if (elegida in estado.asignaturas.indices) cambiar(estado.soltar(elegida, sitio.first, sitio.second))
                                else Toast.makeText(contexto, "Elige una asignatura arriba", Toast.LENGTH_SHORT).show()
                            }
                        }
                ) {
                    val anchoPx = with(densidad) { maxWidth.toPx() }
                    anchoDia = anchoPx / dias
                    val franjaPx = with(densidad) { altoFranja.toPx() }
                    // Las rayas de las horas y la de ahora.
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        for (k in 0..Semana.FRANJAS step 4) drawLine(Color(0x22888888), androidx.compose.ui.geometry.Offset(0f, k * franjaPx), androidx.compose.ui.geometry.Offset(size.width, k * franjaPx))
                        for (d in 1 until dias) drawLine(Color(0x22888888), androidx.compose.ui.geometry.Offset(d * anchoDia, 0f), androidx.compose.ui.geometry.Offset(d * anchoDia, size.height))
                        val ahoraM = ahora.minuto
                        if (ahoraM in Semana.DESDE until Semana.HASTA) {
                            val y = (ahoraM - Semana.DESDE) / Semana.PASO.toFloat() * franjaPx
                            drawLine(Color(0xFFFF453A), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 2f)
                        }
                    }
                    estado.bloques.forEachIndexed { i, b ->
                        if (b.dia >= dias) return@forEachIndexed
                        val color = Color(0xFF000000.toInt() or estado.colorDe(b.nombre))
                        var arrastre by remember(i, b) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                        var estirado by remember(i, b) { mutableStateOf(0f) }
                        val x = b.dia * anchoDia
                        val y = (b.inicio - Semana.DESDE) / Semana.PASO.toFloat() * franjaPx
                        val alto = (b.fin - b.inicio) / Semana.PASO.toFloat() * franjaPx + estirado
                        Box(
                            Modifier.offset { IntOffset((x + arrastre.x).toInt(), (y + arrastre.y).toInt()) }
                                .size(with(densidad) { (anchoDia - 2).coerceAtLeast(1f).toDp() }, with(densidad) { alto.coerceAtLeast(franjaPx).toDp() })
                                .padding(1.dp).clip(RoundedCornerShape(6.dp)).background(color)
                                .pointerInput(i, b) {
                                    detectDragGesturesAfterLongPress(
                                        onDrag = { c, d -> c.consume(); arrastre += d },
                                        onDragEnd = {
                                            val dia = ((x + arrastre.x + anchoDia / 2) / anchoDia).toInt().coerceIn(0, dias - 1)
                                            val inicio = b.inicio + ((arrastre.y / franjaPx) * Semana.PASO).toInt()
                                            arrastre = androidx.compose.ui.geometry.Offset.Zero
                                            cambiar(estado.mover(i, dia, inicio))
                                        },
                                        onDragCancel = { arrastre = androidx.compose.ui.geometry.Offset.Zero }
                                    )
                                }
                                .clickable { ficha = i }
                        ) {
                            Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                                Text(b.nombre, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("%d:%02d".format(b.inicio / 60, b.inicio % 60) + (b.aula?.let { " · $it" } ?: ""), color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, maxLines = 1)
                            }
                            // El borde de abajo: tirar de él alarga o acorta.
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(12.dp)
                                    .pointerInput(i, b) {
                                        detectDragGestures(
                                            onDrag = { c, d -> c.consume(); estirado += d.y },
                                            onDragEnd = {
                                                val fin = b.fin + ((estirado / franjaPx) * Semana.PASO).toInt()
                                                estirado = 0f
                                                cambiar(estado.alargar(i, fin))
                                            },
                                            onDragCancel = { estirado = 0f }
                                        )
                                    }
                            ) { Box(Modifier.align(Alignment.Center).size(18.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.7f))) }
                        }
                    }
                }
            }
        }
    }
    if (nueva) {
        var escrito by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { nueva = false },
            title = { Text("Nueva asignatura") },
            text = {
                Column {
                    OutlinedTextField(escrito, { escrito = it.take(60) }, singleLine = true, placeholder = { Text("Geometría @A-12") })
                    Text("Con @ delante, el aula.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    nueva = false
                    val n = estado.conAsignatura(escrito)
                    if (n == null) Toast.makeText(contexto, "Ya está o no tiene nombre", Toast.LENGTH_SHORT).show()
                    else {
                        cambiar(n); elegida = n.asignaturas.size - 1
                        // Su grupo de tareas, en el chat «Asignaturas».
                        val nombre = n.asignaturas.last().nombre
                        Thread { runCatching { Asignaturas.asegurarLista(contexto.applicationContext, nombre) } }.start()
                    }
                }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { nueva = false }) { Text("Cancelar") } }
        )
    }
    estado.asignaturas.getOrNull(deAsignatura)?.let { a ->
        var aula by remember(a) { mutableStateOf(a.aula.orEmpty()) }
        var pendientes by remember(a) { mutableStateOf<List<TodasLasTareas.Fila>?>(null) }
        var tarea by remember(a) { mutableStateOf("") }
        LaunchedEffect(a) { pendientes = withContext(Dispatchers.IO) { Asignaturas.pendientes(contexto, a.nombre) } }
        AlertDialog(
            onDismissRequest = { deAsignatura = -1 },
            title = { Text(a.nombre) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(aula, { aula = it.take(40) }, singleLine = true, label = { Text("Aula") })
                    TextButton(onClick = { cambiar(estado.conOtroColor(deAsignatura)) }) { Text("Otro color") }
                    Text("Tareas pendientes", style = MaterialTheme.typography.titleSmall)
                    val lista = pendientes
                    if (lista == null) Text("…") else if (lista.isEmpty()) Text("Nada pendiente", style = MaterialTheme.typography.bodySmall)
                    else lista.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(false, onCheckedChange = {
                                Thread { runCatching { Asignaturas.marcar(contexto.applicationContext, a.nombre, f) } }.start()
                                pendientes = lista - f
                            })
                            Text(Frase.marcas(f.crudo).titulo, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedTextField(tarea, { tarea = it.take(200) }, singleLine = true, label = { Text("Nueva tarea de ${a.nombre}") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val i = deAsignatura
                    deAsignatura = -1
                    val limpia = aula.trim().ifEmpty { null }
                    if (limpia != a.aula) cambiar(estado.conAula(i, limpia))
                    if (tarea.isNotBlank()) {
                        val t = tarea
                        Thread {
                            val ok = runCatching { Asignaturas.apuntar(contexto.applicationContext, a.nombre, t) }.getOrNull() != null
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(contexto, if (ok) "Tarea apuntada en «${a.nombre}»" else "No se pudo apuntar", Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    }
                }) { Text("Listo") }
            },
            dismissButton = {
                TextButton(onClick = { val i = deAsignatura; deAsignatura = -1; if (elegida == i) elegida = -1; cambiar(estado.sinAsignatura(i)) }) {
                    Text("Quitar del horario", color = Color(0xFFE5534B))
                }
            }
        )
    }
    estado.bloques.getOrNull(ficha)?.let { b ->
        var aula by remember(b) { mutableStateOf(b.aula.orEmpty()) }
        var nota by remember(b) { mutableStateOf(b.notas.joinToString("\n")) }
        var avisar by remember(b) { mutableStateOf(b.antelacion?.toString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { ficha = -1 },
            title = { Text("${b.nombre} · ${Semana.DIAS[b.dia]} %d:%02d–%d:%02d".format(b.inicio / 60, b.inicio % 60, b.fin / 60, b.fin % 60)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(aula, { aula = it.take(40) }, singleLine = true, label = { Text("Aula") })
                    OutlinedTextField(nota, { nota = it.take(400) }, label = { Text("Lo que te dirá la voz al llamar") })
                    OutlinedTextField(avisar, { avisar = it.filter { c -> c.isDigit() }.take(4) }, singleLine = true, label = { Text("Avisar minutos antes (vacío: los del horario)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val i = ficha
                    ficha = -1
                    cambiar(estado.conBloque(i, b.copy(
                        aula = aula.trim().ifEmpty { null },
                        notas = nota.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        antelacion = avisar.toIntOrNull()?.coerceAtMost(24 * 60)
                    )))
                }) { Text("Listo") }
            },
            dismissButton = { TextButton(onClick = { val i = ficha; ficha = -1; cambiar(estado.sinBloque(i)) }) { Text("Borrar", color = Color(0xFFE5534B)) } }
        )
    }
}
