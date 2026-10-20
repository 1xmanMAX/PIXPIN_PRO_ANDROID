package com.forge.pixpin.mini

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.mini.TodasLasTareas.Fila
import com.forge.pixpin.mini.TodasLasTareas.Lista
import com.forge.pixpin.ui.theme.BotonRedondo
import com.forge.pixpin.ui.theme.Cristal
import com.forge.pixpin.ui.theme.PixPinTheme
import com.forge.pixpin.ui.theme.cristal
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **Tareas**: las listas de tareas de TODOS los chats en una pantalla, como la ventana de
 * Tareas del PC (tareas-v3, 3/4-oct-2026; `apps/pixpin/src/tareas/ventana.rs`).
 *
 * Arriba el buscador (sin tildes ni mayúsculas, todas las palabras, por lista o chat, y por el
 * día: «hoy», «ayer», «anteayer», `2026-10-01`; lo encontrado se resalta); debajo la caja de
 * apuntar, que va al **Inbox** de «Mensajes guardados»; y debajo una tarjeta por tarea,
 * agrupadas por su lista (la lista · su chat · cuántas quedan): lo pendiente primero, la más
 * vieja arriba, y lo hecho plegado en «Hechas (N)», tachado. La casilla tacha; lo recién
 * tachado se queda unos segundos en su sitio. Cada tarjeta lleva sus imágenes, cuántos días
 * lleva y su menú: «Mover a…» otra lista y «Quitar» (con «Deshacer»).
 *
 * **17/18-oct-2026 (PC `bfdd89a`, `18aaf99`)**: lo escrito en la caja se entiende ([Frase]: prioridad,
 * `#etiquetas` y la hora de recordar salen del título y van como marcas); cada tarea puede llevar
 * su **nota** de por qué no se está haciendo y **fijarse en la pantalla** como pin; la vista
 * **Kanban** ([Kanban]) pone cada lista en una columna y arrastrar una tarjeta (mantener pulsada)
 * a otra columna la pasa a esa lista; y arriba se abre el **horario de clases** ([HorarioActivity]).
 *
 * Toda la lógica está en [TodasLasTareas], sin Android y probada. Leer el chat y agrupar va
 * fuera del hilo que pinta; aquí solo se enseña lo ya calculado.
 */
class TodasLasTareasActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PixPinTheme { Pantalla() } }
    }

    /** Lo que se enseña, calculado fuera del hilo que pinta. */
    private data class Vista(val listas: List<Lista>, val grupos: List<TodasLasTareas.Grupo>, val hoy: LocalDate)

    /** Lo que se puede hacer con una tarjeta (lo mismo en la lista y en el tablero). */
    private class AccionesDeTarjeta(
        val marcar: (Lista, Fila, Boolean) -> Unit,
        val moverA: (Lista, Fila) -> Unit,
        val quitar: (Lista, Fila) -> Unit,
        val recordar: (Lista, Fila) -> Unit,
        val olvidarHora: (Lista, Fila) -> Unit,
        val notar: (Lista, Fila) -> Unit,
        val fijar: (Lista, Fila) -> Unit,
        /** Buscar una etiqueta (la chapita pulsada). */
        val buscar: (String) -> Unit = {}
    )

    /** Una tarea recién quitada, mientras su aviso ofrece «Deshacer». */
    private data class Quitada(val codigo: String, val indice: Int, val tarea: Tarea, val texto: String)

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    @Composable
    private fun Pantalla() {
        val app = application as PixPinApp
        val almacen = remember { MensajesStore(this) }
        val proyectos by app.proyectos.proyectos.collectAsState()
        var listas by remember { mutableStateOf<List<Lista>?>(null) }

        // Al día con el chat: lo que se tacha allí, lo que llega del PC o lo que se cambia aquí.
        LaunchedEffect(proyectos) {
            val nombres = proyectos.associate { it.id to it.nombre }
            suspend fun leer() { listas = withContext(Dispatchers.IO) { TodasLasTareas.reunir(almacen.leer(), nombres) } }
            leer()
            MensajesStore.cambios.debounce(250).collect { leer() }
        }

        var consulta by remember { mutableStateOf("") }
        var apuntar by remember { mutableStateOf("") }
        /** La asignatura de la tarea nueva (el libro de la caja, PC 18-oct); null: al Inbox. */
        var asignatura by remember { mutableStateOf<String?>(null) }
        var eligiendoAsignatura by remember { mutableStateOf<List<String>?>(null) }
        // Las recién tachadas (clave de la lista + texto crudo) y cuándo.
        val recien = remember { mutableStateMapOf<String, Long>() }
        val abiertas = remember { mutableStateMapOf<String, Boolean>() }
        var moviendo by remember { mutableStateOf<Pair<Lista, Fila>?>(null) }
        var notando by remember { mutableStateOf<Pair<Lista, Fila>?>(null) }
        // La vista elegida se recuerda (`tareas-vista.txt` del PC).
        val prefs = remember { getSharedPreferences("tareas", MODE_PRIVATE) }
        var vistaElegida by remember { mutableStateOf(Kanban.Vista.deTexto(prefs.getString("vista", null))) }
        val avisos = remember { SnackbarHostState() }
        val alcance = rememberCoroutineScope()

        // Lo recién tachado se va a «Hechas» pasado su rato, cada una a su hora.
        LaunchedEffect(recien.size) {
            while (recien.isNotEmpty()) {
                val primera = recien.values.minOrNull() ?: break
                delay((primera + TodasLasTareas.SE_QUEDA_MS - System.currentTimeMillis()).coerceAtLeast(0))
                val ahora = System.currentTimeMillis()
                recien.keys.filter { ahora - (recien[it] ?: 0) >= TodasLasTareas.SE_QUEDA_MS }.forEach { recien.remove(it) }
            }
        }

        val palabras = remember(consulta) { TodasLasTareas.palabras(consulta) }
        var vista by remember { mutableStateOf<Vista?>(null) }
        val recienes = recien.keys.toSet()
        LaunchedEffect(listas, palabras, recienes) {
            val ls = listas ?: return@LaunchedEffect
            if (palabras.isNotEmpty()) delay(80)
            vista = withContext(Dispatchers.Default) {
                val hoy = LocalDate.now()
                Vista(ls, TodasLasTareas.agrupar(ls, palabras, hoy) { l, f -> clave(l, f) in recienes }, hoy)
            }
        }

        fun avisar(t: String) { alcance.launch { avisos.currentSnackbarData?.dismiss(); avisos.showSnackbar(t) } }

        fun marcar(l: Lista, f: Fila, hecha: Boolean) {
            if (hecha) recien[clave(l, f)] = System.currentTimeMillis() else recien.remove(clave(l, f))
            app.scope.launch(Dispatchers.IO) {
                var cambio = false
                almacen.cambiar { todos ->
                    todos.map { m ->
                        if (m.id != l.codigo) m
                        else TodasLasTareas.marcar(m.texto, f, hecha)?.let { m.copy(texto = it) } ?: m.also { cambio = true }
                    }
                }
                if (cambio) withContext(Dispatchers.Main) { avisar("La lista cambió mientras tanto; ya está al día. Vuelve a marcarla.") }
            }
        }

        fun quitar(l: Lista, f: Fila) {
            app.scope.launch(Dispatchers.IO) {
                var hecha: Quitada? = null
                almacen.cambiar { todos ->
                    todos.map { m ->
                        if (m.id != l.codigo) m
                        else TodasLasTareas.quitar(m.texto, f)?.let { (doc, t) ->
                            hecha = Quitada(l.codigo, f.indice, t, f.texto); m.copy(texto = doc)
                        } ?: m
                    }
                }
                withContext(Dispatchers.Main) {
                    val q = hecha
                    if (q == null) { avisar("La lista cambió mientras tanto; ya está al día. Vuelve a quitarla."); return@withContext }
                    alcance.launch {
                        avisos.currentSnackbarData?.dismiss()
                        val r = withTimeoutOrNull(TodasLasTareas.DURA_DESHACER_MS) {
                            avisos.showSnackbar("«${q.texto.ifBlank { "Tarea" }}» quitada", "Deshacer", duration = SnackbarDuration.Indefinite)
                        }
                        if (r == null) avisos.currentSnackbarData?.dismiss()
                        if (r == SnackbarResult.ActionPerformed) app.scope.launch(Dispatchers.IO) {
                            var esta = false
                            almacen.cambiar { todos ->
                                todos.map { m ->
                                    if (m.id != q.codigo || !TodasLasTareas.esLista(m)) m
                                    else { esta = true; m.copy(texto = TodasLasTareas.reponer(m.texto, q.indice, q.tarea)) }
                                }
                            }
                            withContext(Dispatchers.Main) { avisar(if (esta) "Tarea devuelta a su lista" else "Esa lista ya no está") }
                        }
                    }
                }
            }
        }

        /** Recordarla a [hora], o dejar de recordarla (null). Ver [TodasLasTareas.ponerHora]. */
        fun ponerHora(l: Lista, f: Fila, hora: java.time.LocalDateTime?) {
            app.scope.launch(Dispatchers.IO) {
                var cambio = false
                almacen.cambiar { todos ->
                    todos.map { m ->
                        if (m.id != l.codigo) m
                        else TodasLasTareas.ponerHora(m.texto, f, hora)?.let { m.copy(texto = it) } ?: m.also { cambio = true }
                    }
                }
                withContext(Dispatchers.Main) {
                    avisar(when {
                        cambio -> "La lista cambió mientras tanto; ya está al día. Vuelve a intentarlo."
                        hora == null -> "Ya no se recuerda"
                        else -> "Te la recuerdo ${Tareas.textoDeHora(hora)}"
                    })
                }
            }
        }

        fun recordar(l: Lista, f: Fila) = ElegirHora.pedir(this@TodasLasTareasActivity, Tareas.horaDe(f.crudo)) { ponerHora(l, f, it) }

        fun mover(desde: Lista, f: Fila, hasta: Lista) {
            if (desde.clave == hasta.clave) return
            app.scope.launch(Dispatchers.IO) {
                // Las imágenes, copiadas al chat de destino antes de tocar nada (`tareas::mover`).
                val crudo = if (desde.proyecto == hasta.proyecto) f.crudo
                else TodasLasTareas.conImagenesCopiadas(f.crudo, filesDir, almacen.carpetaDeAdjuntos(), System.currentTimeMillis())
                if (crudo == null) { withContext(Dispatchers.Main) { avisar("No se pudieron copiar sus imágenes") }; return@launch }
                var hecho = false
                almacen.cambiar { todos ->
                    val o = todos.firstOrNull { it.id == desde.codigo }
                    val d = todos.firstOrNull { it.id == hasta.codigo }?.takeIf { TodasLasTareas.esLista(it) }
                    val r = if (o == null || d == null) null else TodasLasTareas.mover(o.texto, f, d.texto, crudo)
                    if (r == null) todos
                    else {
                        hecho = true
                        todos.map {
                            when (it.id) {
                                desde.codigo -> it.copy(texto = r.first)
                                hasta.codigo -> it.copy(texto = r.second)
                                else -> it
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    avisar(if (hecho) "Movida a ${hasta.titulo}" else "La lista cambió mientras tanto; ya está al día. Vuelve a moverla.")
                }
            }
        }

        /** La nota de por qué no se está haciendo (vacía: sin nota). Ver [TodasLasTareas.ponerNota]. */
        fun ponerNota(l: Lista, f: Fila, nota: String) {
            app.scope.launch(Dispatchers.IO) {
                var cambio = false
                almacen.cambiar { todos ->
                    todos.map { m ->
                        if (m.id != l.codigo) m
                        else TodasLasTareas.ponerNota(m.texto, f, nota)?.let { m.copy(texto = it) } ?: m.also { cambio = true }
                    }
                }
                withContext(Dispatchers.Main) {
                    avisar(when {
                        cambio -> "La lista cambió mientras tanto; ya está al día. Vuelve a intentarlo."
                        nota.isBlank() -> "Nota quitada"
                        else -> "Nota guardada"
                    })
                }
            }
        }

        /** **El pin de la tarea**: solo su título, la hora, las etiquetas y la nota, encima de todo. */
        fun fijar(l: Lista, f: Fila) {
            if (!android.provider.Settings.canDrawOverlays(this)) { avisar("Para fijarla, PixPin necesita «Mostrar sobre otras apps»"); return }
            app.overlayManager.pinTexto(Frase.textoDelPin(f.crudo, Momento.ahora()))
        }

        val acciones = AccionesDeTarjeta(
            ::marcar, { l, f -> moviendo = l to f }, ::quitar, ::recordar, { a, b -> ponerHora(a, b, null) },
            { l, f -> notando = l to f }, ::fijar, buscar = { consulta = it }
        )

        fun apuntarAhora() {
            val t = apuntar
            if (Tareas.saneado(t).isEmpty()) return
            apuntar = ""
            val destino = asignatura
            app.scope.launch(Dispatchers.IO) {
                val guardada = (if (destino != null) Asignaturas.apuntar(applicationContext, destino, t) else TareaRapidaActivity.alInbox(applicationContext, t)) ?: return@launch
                val m = Frase.marcas(guardada)
                val hora = m.hora?.let { " · ⏰ " + Momento.deMsLocales(it).legible(Momento.ahora()) }.orEmpty()
                withContext(Dispatchers.Main) { avisar("«${m.titulo.ifBlank { Tareas.saneado(t) }}» añadida a «${destino ?: TodasLasTareas.INBOX}»$hora") }
            }
        }

        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(avisos) }
        ) { relleno ->
            Column(Modifier.fillMaxSize().padding(relleno)) {
                // Cabecera.
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BotonRedondo(Icons.AutoMirrored.Filled.ArrowBack, "Volver", { finish() }, tamano = 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tareas", style = MaterialTheme.typography.titleLarge, color = Cristal.tinta, fontWeight = FontWeight.SemiBold)
                        val v = vista
                        if (v != null) Text(
                            if (palabras.isNotEmpty()) TodasLasTareas.encontradas(v.grupos.sumOf { it.arriba.size + it.hechas.size })
                            else TodasLasTareas.resumen(v.listas.sumOf { it.pendientes }, v.listas.count { it.filas.isNotEmpty() }),
                            style = MaterialTheme.typography.labelMedium, color = Cristal.tinta.copy(alpha = 0.7f)
                        )
                    }
                    // El horario de clases que llama (PC 17-oct) y el cambio de vista (lista / Kanban).
                    IconButton(onClick = { HorarioActivity.abrir(this@TodasLasTareasActivity) }) {
                        Icon(Icons.Filled.School, "Horario de clases", tint = Cristal.tinta)
                    }
                    val enKanban = vistaElegida == Kanban.Vista.KANBAN
                    IconButton(onClick = {
                        vistaElegida = if (enKanban) Kanban.Vista.LISTA else Kanban.Vista.KANBAN
                        prefs.edit().putString("vista", vistaElegida.clave).apply()
                    }) {
                        Icon(
                            if (enKanban) Icons.AutoMirrored.Filled.Notes else Icons.Filled.ViewColumn,
                            if (enKanban) "Ver como lista" else "Ver como tablero Kanban", tint = Cristal.tinta
                        )
                    }
                }

                // Buscador.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).cristal().padding(horizontal = 14.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, null, tint = Cristal.tinta.copy(alpha = 0.7f))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (consulta.isEmpty()) Text(
                            "Buscar… («hoy», «ayer»)", color = Cristal.tinta.copy(alpha = 0.5f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        BasicTextField(
                            value = consulta, onValueChange = { consulta = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Cristal.tinta),
                            cursorBrush = SolidColor(Cristal.tinta), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (consulta.isNotEmpty()) IconButton(onClick = { consulta = "" }) { Icon(Icons.Filled.Close, "Vaciar", tint = Cristal.tinta) }
                }
                Spacer(Modifier.height(8.dp))

                // Apuntar: va al Inbox de «Mensajes guardados».
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).cristal().padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, null, tint = Cristal.tinta.copy(alpha = 0.7f))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (apuntar.isEmpty()) Text(
                            "Apunta… («mañana a las 5 #casa !alta»)", color = Cristal.tinta.copy(alpha = 0.5f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        BasicTextField(
                            value = apuntar, onValueChange = { apuntar = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Cristal.tinta),
                            cursorBrush = SolidColor(Cristal.tinta), modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { apuntarAhora() })
                        )
                    }
                    // **El reloj**: elegir día y hora; lo dicho con palabras se quita y va la marca elegida.
                    IconButton(onClick = {
                        val c = java.util.Calendar.getInstance()
                        android.app.DatePickerDialog(this@TodasLasTareasActivity, { _, y, mo, d ->
                            android.app.TimePickerDialog(this@TodasLasTareasActivity, { _, h, mi ->
                                val m = Momento(com.forge.pixpin.ia.Fechas.diasDe(y.toLong(), mo + 1L, d.toLong()), h * 60 + mi)
                                apuntar = (Frase.sinFechaDicha(apuntar, Momento.ahora()).trim() + " " + m.marca()).trim()
                            }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show()
                        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
                    }) { Icon(Icons.Filled.Schedule, contentDescription = "Día y hora", tint = Cristal.tinta.copy(alpha = 0.8f)) }
                    // **El libro**: a qué asignatura va (cada asignatura es un grupo de tareas).
                    Box {
                        IconButton(onClick = {
                            app.scope.launch(Dispatchers.IO) {
                                val n = Asignaturas.nombres(applicationContext)
                                withContext(Dispatchers.Main) {
                                    if (n.isEmpty()) avisar("Aún no hay asignaturas: créalas en el horario") else eligiendoAsignatura = n
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Book, contentDescription = "Asignatura", tint = if (asignatura != null) Cristal.puesto else Cristal.tinta.copy(alpha = 0.8f))
                        }
                        androidx.compose.material3.DropdownMenu(expanded = eligiendoAsignatura != null, onDismissRequest = { eligiendoAsignatura = null }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Inbox (sin asignatura)") }, onClick = { asignatura = null; eligiendoAsignatura = null })
                            eligiendoAsignatura.orEmpty().forEach { n ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(if (n == asignatura) "✓ $n" else n) }, onClick = { asignatura = n; eligiendoAsignatura = null })
                            }
                        }
                    }
                    TextButton(onClick = { apuntarAhora() }, enabled = apuntar.isNotBlank()) { Text(asignatura?.let { "A $it" } ?: "Apuntar") }
                }

                val v = vista
                when {
                    v == null -> Box(Modifier.weight(1f).fillMaxWidth())
                    v.grupos.isEmpty() && palabras.isNotEmpty() -> SinResultados(consulta, Modifier.weight(1f)) { consulta = "" }
                    v.grupos.isEmpty() -> Vacia(Modifier.weight(1f))
                    vistaElegida == Kanban.Vista.KANBAN -> {
                        val colores = TodasLasTareas.coloresDeListas(v.listas.map { it.clave to it.filas.isNotEmpty() })
                        TableroKanban(
                            v, { li -> Color(0xFF000000.toInt() or TodasLasTareas.COLORES_DE_GRUPO[colores[li]]) }, palabras, abiertas, acciones,
                            { desde, f, hasta -> mover(desde, f, hasta) }, Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                    else -> LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // El color de cada grupo, el mismo que en el PC.
                        val colores = TodasLasTareas.coloresDeListas(v.listas.map { it.clave to it.filas.isNotEmpty() })
                        v.grupos.forEach { g ->
                            val l = v.listas[g.lista]
                            val color = Color(0xFF000000.toInt() or TodasLasTareas.COLORES_DE_GRUPO[colores[g.lista]])
                            item(key = "e:" + l.clave) { Encabezado(l, g.pendientes(l), color) }
                            items(g.arriba, key = { "a:" + l.clave + ":" + l.filas[it].crudo + ":" + it }) { i ->
                                Tarjeta(l, l.filas[i], color, palabras, v.hoy, palabras.isNotEmpty(), acciones)
                            }
                            if (g.hechas.isNotEmpty()) {
                                // Buscando, lo hecho que coincide se ve.
                                val abierta = palabras.isNotEmpty() || abiertas[l.clave] == true
                                item(key = "p:" + l.clave) {
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .clickable { abiertas[l.clave] = !abierta }.padding(horizontal = 8.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(if (abierta) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Cristal.tinta.copy(alpha = 0.7f))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Hechas (${g.hechas.size})", color = Cristal.tinta.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                                if (abierta) items(g.hechas, key = { "h:" + l.clave + ":" + l.filas[it].crudo + ":" + it }) { i ->
                                    Tarjeta(l, l.filas[i], color, palabras, v.hoy, palabras.isNotEmpty(), acciones)
                                }
                            }
                            item(key = "s:" + l.clave) { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }

        moviendo?.let { (desde, f) ->
            val otras = (listas ?: emptyList()).filter { it.clave != desde.clave }
            MoverA(f, otras, { moviendo = null }) { hasta -> moviendo = null; mover(desde, f, hasta) }
        }
        notando?.let { (l, f) ->
            NotaDeTarea(f, { notando = null }) { nota -> notando = null; ponerNota(l, f, nota) }
        }
    }

    private fun clave(l: Lista, f: Fila) = l.clave + "\u0000" + f.crudo

    @Composable
    private fun Encabezado(l: Lista, pendientes: Int, color: Color) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable { MiniActivity.abrir(this, l.codigo) }.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Su color: una marca redonda delante del nombre, como en el PC.
            Box(Modifier.width(6.dp).height(18.dp).clip(RoundedCornerShape(3.dp)).background(lerp(color, Color.White, 0.25f)))
            Spacer(Modifier.width(8.dp))
            Text(
                l.titulo.ifBlank { "Tareas" }, color = Cristal.tinta, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                "  ·  ${l.chat}", color = Cristal.tinta.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(TodasLasTareas.pendientesDeGrupo(pendientes), color = Cristal.puesto, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }

    /** El color de la chapita de una urgencia (los de la franja del pin de tarea del PC). */
    private fun colorDe(u: Urgencia): Color = when (u) {
        Urgencia.MUY_URGENTE -> Color(0xFFE5534B)
        Urgencia.URGENTE -> Color(0xFFE8A33D)
        Urgencia.NORMAL -> Color(0xFF4CAF7A)
    }

    @Composable
    private fun Tarjeta(
        l: Lista, f: Fila, color: Color, palabras: List<String>, hoy: LocalDate, conSuLista: Boolean,
        acc: AccionesDeTarjeta, modifier: Modifier = Modifier
    ) {
        val hora = remember(f.crudo) { Tareas.horaDe(f.crudo) }
        // Lo reconocido al escribirla (prioridad, #etiquetas, nota), aparte del título.
        val marcas = remember(f.crudo) { Frase.marcas(f.crudo) }
        val titulo = marcas.titulo
        val atenuada = if (f.hecha) 0.55f else 1f
        val resalte = Color(0x61B38F00)
        val texto = remember(titulo, palabras) {
            buildAnnotatedString {
                append(titulo)
                TodasLasTareas.resaltes(titulo, palabras).forEach { addStyle(SpanStyle(background = resalte), it.first, it.last + 1) }
            }
        }
        var menu by remember { mutableStateOf(false) }
        Row(
            modifier.fillMaxWidth().cristal(RoundedCornerShape(16.dp))
                // La raya de su grupo, a la izquierda: las tareas de una lista se reconocen de lejos.
                .drawBehind {
                    val m = 10.dp.toPx()
                    drawRoundRect(color.copy(alpha = if (f.hecha) 0.4f else 1f), Offset(3.dp.toPx(), m), Size(4.dp.toPx(), (size.height - 2 * m).coerceAtLeast(0f)), CornerRadius(2.dp.toPx()))
                }
                .padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = f.hecha, onCheckedChange = { acc.marcar(l, f, it) })
            Column(Modifier.weight(1f).padding(vertical = 6.dp).alpha(atenuada), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (titulo.isNotEmpty()) Text(
                    texto, color = Cristal.tinta, style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (f.hecha) TextDecoration.LineThrough else null
                )
                // La urgencia como chapita de color y las etiquetas como chapitas (PC 18-oct: tres a la
                // vista y «+N»; tocar una busca esa etiqueta).
                if (marcas.urgencia != Urgencia.NORMAL || marcas.etiquetas.isNotEmpty()) Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    if (marcas.urgencia != Urgencia.NORMAL) Text(
                        marcas.urgencia.nombre,
                        color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colorDe(marcas.urgencia)).padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                    for (e in marcas.etiquetas.take(3)) Text(
                        "#$e", color = Cristal.puesto, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Cristal.puesto.copy(alpha = 0.14f))
                            .clickable { acc.buscar("#$e") }.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                    if (marcas.etiquetas.size > 3) Text("+${marcas.etiquetas.size - 3}", color = Cristal.puesto, style = MaterialTheme.typography.labelMedium)
                }
                marcas.nota?.let { n ->
                    Text(
                        "${Frase.NOTA} $n", color = Cristal.tinta.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                if (f.imagenes.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    f.imagenes.forEach { Miniatura(it) }
                }
                val edad = TodasLasTareas.edad(f.creada, hoy)
                val pie = listOfNotNull(edad, if (conSuLista) "${l.titulo} · ${l.chat}" else null).joinToString("  ·  ")
                if (hora != null && !f.hecha) {
                    // La hora a la que suena; en rojo si ya pasó sin sonar (el teléfono apagado).
                    val pasada = hora.isBefore(java.time.LocalDateTime.now())
                    Text(
                        "${Tareas.RELOJ} ${Tareas.textoDeHora(hora)}",
                        color = if (pasada) Color(0xFFFF6B6B) else Cristal.puesto,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1
                    )
                }
                if (pie.isNotEmpty()) Text(
                    pie, color = Cristal.tinta.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más", tint = Cristal.tinta.copy(alpha = 0.7f)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Mover a…") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
                        onClick = { menu = false; acc.moverA(l, f) }
                    )
                    if (!f.hecha) DropdownMenuItem(
                        text = { Text(if (hora == null) "Recordar…" else "Otra hora…") }, leadingIcon = { Icon(Icons.Filled.Alarm, null) },
                        onClick = { menu = false; acc.recordar(l, f) }
                    )
                    if (hora != null) DropdownMenuItem(
                        text = { Text("No recordar") }, leadingIcon = { Icon(Icons.Filled.AlarmOff, null) },
                        onClick = { menu = false; acc.olvidarHora(l, f) }
                    )
                    if (!f.hecha) DropdownMenuItem(
                        text = { Text(if (marcas.nota == null) "¿Por qué no ahora?" else "Cambiar la nota") },
                        leadingIcon = { Icon(Icons.Filled.ChatBubbleOutline, null) },
                        onClick = { menu = false; acc.notar(l, f) }
                    )
                    if (!f.hecha) DropdownMenuItem(
                        text = { Text("Fijar en pantalla") }, leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                        onClick = { menu = false; acc.fijar(l, f) }
                    )
                    DropdownMenuItem(
                        text = { Text("Quitar") }, leadingIcon = { Icon(Icons.Filled.Close, null) },
                        onClick = { menu = false; acc.quitar(l, f) }
                    )
                }
            }
        }
    }

    /**
     * **La nota de una tarea** (el «💬 ¿Por qué no ahora?» del pin de tarea del PC): una línea; vacía
     * la quita. Va dentro del texto de la tarea, así que viaja y se ve en el PC.
     */
    @Composable
    private fun NotaDeTarea(f: Fila, cerrar: () -> Unit, guardar: (String) -> Unit) {
        val marcas = remember(f.crudo) { Frase.marcas(f.crudo) }
        val inicial = marcas.nota.orEmpty()
        var nota by remember(f.crudo) { mutableStateOf(inicial) }
        AlertDialog(
            onDismissRequest = cerrar,
            title = { Text(marcas.titulo.ifBlank { "Tarea" }.take(60), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                OutlinedTextField(
                    value = nota, onValueChange = { nota = it.replace('\n', ' ') }, singleLine = true,
                    placeholder = { Text("Por qué no la haces ahora") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { guardar(nota) })
                )
            },
            confirmButton = { TextButton(onClick = { guardar(nota) }) { Text("Guardar") } },
            dismissButton = {
                if (inicial.isNotEmpty()) TextButton(onClick = { guardar("") }) { Text("Quitar la nota") }
                else TextButton(onClick = cerrar) { Text("Cancelar") }
            }
        )
    }

    /** Una tarjeta que se está arrastrando en el tablero: de qué lista es, cuál y por dónde va el dedo (en la raíz). */
    private data class Arrastre(val lista: Int, val fila: Fila, val titulo: String, val dedo: Offset)

    /**
     * **El tablero Kanban** (PC `18aaf99`, [Kanban]): una columna por lista, con su encabezado, sus
     * pendientes y su pliegue de hechas; lo que no cabe a lo ancho baja a otra banda (nunca hay que
     * desplazarse de lado). Mantener pulsada una tarjeta y soltarla en otra columna la pasa a esa lista.
     */
    @Composable
    private fun TableroKanban(
        v: Vista, colorDe: (Int) -> Color, palabras: List<String>, abiertas: MutableMap<String, Boolean>,
        acc: AccionesDeTarjeta, soltar: (Lista, Fila, Lista) -> Unit, modifier: Modifier
    ) {
        val densidad = LocalDensity.current
        BoxWithConstraints(modifier) {
            val margen = 12f
            val m = Kanban.Medidas(
                ancho = maxWidth.value - 2 * margen, columnaMin = 150f, columnaMax = 360f, hueco = 8f, relleno = 6f,
                encabezado = 40f, pliegue = 40f, entreBandas = 14f, filaHecha = 44f
            )
            val (k, anchoCol) = Kanban.reparto(m, v.grupos.size)
            // Dónde está cada columna (x y ancho) y cada banda (y y alto), en la raíz: ahí se suelta.
            val xs = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
            val bandas = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
            var origen by remember { mutableStateOf(Offset.Zero) }
            var arrastre by remember { mutableStateOf<Arrastre?>(null) }
            fun columnas(): List<Kanban.Columna> = v.grupos.chunked(k).flatMapIndexed { b, banda ->
                val (y, alto) = bandas[b] ?: return@flatMapIndexed emptyList()
                banda.mapNotNull { g -> xs[g.lista]?.let { (x, w) -> Kanban.Columna(g.lista, Kanban.Caja(x, y, w, alto)) } }
            }
            fun acabar(a: Arrastre?) {
                arrastre = null
                a ?: return
                val hasta = Kanban.destinoAlSoltar(columnas(), a.lista, a.dedo.x, a.dedo.y) ?: return
                soltar(v.listas[a.lista], a.fila, v.listas[hasta])
            }
            val destino = arrastre?.let { a -> Kanban.destinoAlSoltar(columnas(), a.lista, a.dedo.x, a.dedo.y) }
            Column(
                Modifier.fillMaxSize().onGloballyPositioned { origen = it.positionInRoot() }
                    .verticalScroll(rememberScrollState()).padding(horizontal = margen.dp, vertical = 12.dp)
            ) {
                v.grupos.chunked(k).forEachIndexed { b, banda ->
                    if (b > 0) Spacer(Modifier.height(m.entreBandas.dp))
                    Row(
                        Modifier.fillMaxWidth().onGloballyPositioned { c -> val r = c.boundsInRoot(); bandas[b] = r.top to r.height },
                        horizontalArrangement = Arrangement.spacedBy(m.hueco.dp)
                    ) {
                        banda.forEach { g ->
                            val l = v.listas[g.lista]
                            val color = colorDe(g.lista)
                            Column(
                                Modifier.width(anchoCol.dp)
                                    .onGloballyPositioned { c -> val r = c.boundsInRoot(); xs[g.lista] = r.left to r.width }
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(color.copy(alpha = if (destino == g.lista) 0.34f else 0.12f))
                                    .padding(m.relleno.dp),
                                verticalArrangement = Arrangement.spacedBy(m.hueco.dp)
                            ) {
                                Encabezado(l, g.pendientes(l), color)
                                g.arriba.forEach { i ->
                                    androidx.compose.runtime.key(l.clave, l.filas[i].crudo, i) {
                                        TarjetaArrastrable(l, l.filas[i], g.lista, color, palabras, v.hoy, acc, { arrastre = it }, ::acabar)
                                    }
                                }
                                if (g.hechas.isNotEmpty()) {
                                    val abierta = palabras.isNotEmpty() || abiertas[l.clave] == true
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .clickable { abiertas[l.clave] = !abierta }.padding(horizontal = 4.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(if (abierta) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Cristal.tinta.copy(alpha = 0.7f))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Hechas (${g.hechas.size})", color = Cristal.tinta.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                                    }
                                    if (abierta) g.hechas.forEach { i ->
                                        androidx.compose.runtime.key(l.clave, l.filas[i].crudo, i) {
                                            Tarjeta(l, l.filas[i], color, palabras, v.hoy, false, acc)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
            // La tarjeta que va con el dedo, por encima de todo.
            arrastre?.let { a ->
                val x = with(densidad) { (a.dedo.x - origen.x).toDp() }
                val y = with(densidad) { (a.dedo.y - origen.y).toDp() }
                Text(
                    a.titulo.ifBlank { "Tarea" }, color = Cristal.tinta, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.offset(x - 24.dp, y - 36.dp).widthIn(max = 220.dp)
                        .cristal(RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }

    /**
     * Una tarjeta del tablero que se arrastra tras mantenerla pulsada: [mientras] recibe por dónde va
     * el dedo, y [alSoltar] el último sitio (o null si se canceló).
     */
    @Composable
    private fun TarjetaArrastrable(
        l: Lista, f: Fila, lista: Int, color: Color, palabras: List<String>, hoy: LocalDate, acc: AccionesDeTarjeta,
        mientras: (Arrastre) -> Unit, alSoltar: (Arrastre?) -> Unit
    ) {
        var esquina by remember { mutableStateOf(Offset.Zero) }
        var dedo by remember { mutableStateOf<Offset?>(null) }
        val titulo = remember(f.crudo) { Frase.marcas(f.crudo).titulo }
        Tarjeta(
            l, f, color, palabras, hoy, false, acc,
            Modifier.alpha(if (dedo != null) 0.4f else 1f)
                .onGloballyPositioned { esquina = it.positionInRoot() }
                .pointerInput(l.clave, f.crudo) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { p -> val d = esquina + p; dedo = d; mientras(Arrastre(lista, f, titulo, d)) },
                        onDrag = { cambio, delta ->
                            cambio.consume()
                            val d = (dedo ?: esquina) + delta
                            dedo = d
                            mientras(Arrastre(lista, f, titulo, d))
                        },
                        onDragEnd = { val d = dedo; dedo = null; alSoltar(d?.let { Arrastre(lista, f, titulo, it) }) },
                        onDragCancel = { dedo = null; alSoltar(null) }
                    )
                }
        )
    }

    /** Una imagen de la tarea: 48 dp, recortada al centro. Sin fichero aún, su hueco con el icono. */
    @Composable
    private fun Miniatura(enlace: String) {
        val lado = 48.dp
        var archivo by remember(enlace) { mutableStateOf<File?>(null) }
        var imagen by remember(enlace) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        LaunchedEffect(enlace) {
            withContext(Dispatchers.IO) {
                val f = TodasLasTareas.archivoDeEnlace(filesDir, enlace)
                archivo = f
                // La misma lectura (y la misma caché) que la fila de la lista en MiniActivity.
                imagen = f?.let { ImagenesDeTareas.miniatura(it.absolutePath, 192) }
            }
        }
        val forma = RoundedCornerShape(6.dp)
        Box(
            Modifier.size(lado).clip(forma).background(MaterialTheme.colorScheme.surfaceVariant).clickable {
                val f = archivo
                if (f == null) ImagenesDeTareas.avisarQueFalta(this)
                else runCatching { com.forge.pixpin.ui.AbrirCon.abrir(this, f) }
            },
            contentAlignment = Alignment.Center
        ) {
            val i = imagen
            if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun MoverA(f: Fila, otras: List<Lista>, cerrar: () -> Unit, elegir: (Lista) -> Unit) {
        AlertDialog(
            onDismissRequest = cerrar,
            title = { Text("Mover «${f.texto.ifBlank { "Tarea" }.take(40)}» a…", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                if (otras.isEmpty()) Text("Aún no hay otros grupos de tareas. Crea una lista de tareas en un chat y saldrá aquí.")
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(otras, key = { it.clave }) { l ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { elegir(l) }.padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(l.titulo.ifBlank { "Tareas" }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Text("  ·  ${l.chat}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = cerrar) { Text("Cerrar") } }
        )
    }

    @Composable
    private fun Vacia(modifier: Modifier) {
        Box(modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
            Text(
                "Aún no hay tareas. Escribe arriba y pulsa Intro: irá al Inbox y desde ahí la pasas a su grupo.",
                color = Cristal.tinta.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge
            )
        }
    }

    @Composable
    private fun SinResultados(consulta: String, modifier: Modifier, vaciar: () -> Unit) {
        Column(
            modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Nada coincide con «${consulta.trim().take(40)}»", color = Cristal.tinta, style = MaterialTheme.typography.titleMedium)
            Text(
                "Se busca en el texto de cada tarea y en el nombre de su lista y de su chat, sin mirar tildes ni mayúsculas. Prueba con menos palabras, o con «hoy» o «ayer».",
                color = Cristal.tinta.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium
            )
            FilledTonalButton(onClick = vaciar) { Text("Vaciar la búsqueda") }
        }
    }

    companion object {
        fun abrir(context: Context) = context.startActivity(
            Intent(context, TodasLasTareasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
