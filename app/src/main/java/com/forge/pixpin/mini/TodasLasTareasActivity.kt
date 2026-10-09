package com.forge.pixpin.mini

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
        // Las recién tachadas (clave de la lista + texto crudo) y cuándo.
        val recien = remember { mutableStateMapOf<String, Long>() }
        val abiertas = remember { mutableStateMapOf<String, Boolean>() }
        var moviendo by remember { mutableStateOf<Pair<Lista, Fila>?>(null) }
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

        fun apuntarAhora() {
            val t = apuntar
            if (Tareas.saneado(t).isEmpty()) return
            apuntar = ""
            app.scope.launch(Dispatchers.IO) {
                TareaRapidaActivity.alInbox(applicationContext, t)
                withContext(Dispatchers.Main) { avisar("«${Tareas.saneado(t)}» añadida a «${TodasLasTareas.INBOX}».") }
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
                            "Apunta una tarea en el Inbox", color = Cristal.tinta.copy(alpha = 0.5f),
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
                    TextButton(onClick = { apuntarAhora() }, enabled = apuntar.isNotBlank()) { Text("Apuntar") }
                }

                val v = vista
                when {
                    v == null -> Box(Modifier.weight(1f).fillMaxWidth())
                    v.grupos.isEmpty() && palabras.isNotEmpty() -> SinResultados(consulta, Modifier.weight(1f)) { consulta = "" }
                    v.grupos.isEmpty() -> Vacia(Modifier.weight(1f))
                    else -> LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        v.grupos.forEach { g ->
                            val l = v.listas[g.lista]
                            item(key = "e:" + l.clave) { Encabezado(l, g.pendientes(l)) }
                            items(g.arriba, key = { "a:" + l.clave + ":" + l.filas[it].crudo + ":" + it }) { i ->
                                Tarjeta(l, l.filas[i], palabras, v.hoy, palabras.isNotEmpty(), ::marcar, { moviendo = l to l.filas[i] }, ::quitar, ::recordar) { a, b -> ponerHora(a, b, null) }
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
                                    Tarjeta(l, l.filas[i], palabras, v.hoy, palabras.isNotEmpty(), ::marcar, { moviendo = l to l.filas[i] }, ::quitar, ::recordar) { a, b -> ponerHora(a, b, null) }
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
    }

    private fun clave(l: Lista, f: Fila) = l.clave + "\u0000" + f.crudo

    @Composable
    private fun Encabezado(l: Lista, pendientes: Int) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable { MiniActivity.abrir(this, l.codigo) }.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

    @Composable
    private fun Tarjeta(
        l: Lista, f: Fila, palabras: List<String>, hoy: LocalDate, conSuLista: Boolean,
        marcar: (Lista, Fila, Boolean) -> Unit, moverA: () -> Unit, quitar: (Lista, Fila) -> Unit,
        recordar: (Lista, Fila) -> Unit, olvidarHora: (Lista, Fila) -> Unit
    ) {
        val hora = remember(f.crudo) { Tareas.horaDe(f.crudo) }
        val atenuada = if (f.hecha) 0.55f else 1f
        val resalte = Color(0x61B38F00)
        val texto = remember(f.texto, palabras) {
            buildAnnotatedString {
                append(f.texto)
                TodasLasTareas.resaltes(f.texto, palabras).forEach { addStyle(SpanStyle(background = resalte), it.first, it.last + 1) }
            }
        }
        var menu by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().cristal(RoundedCornerShape(16.dp)).padding(start = 4.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = f.hecha, onCheckedChange = { marcar(l, f, it) })
            Column(Modifier.weight(1f).padding(vertical = 6.dp).alpha(atenuada), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (f.texto.isNotEmpty()) Text(
                    texto, color = Cristal.tinta, style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (f.hecha) TextDecoration.LineThrough else null
                )
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
                        onClick = { menu = false; moverA() }
                    )
                    if (!f.hecha) DropdownMenuItem(
                        text = { Text(if (hora == null) "Recordar…" else "Otra hora…") }, leadingIcon = { Icon(Icons.Filled.Alarm, null) },
                        onClick = { menu = false; recordar(l, f) }
                    )
                    if (hora != null) DropdownMenuItem(
                        text = { Text("No recordar") }, leadingIcon = { Icon(Icons.Filled.AlarmOff, null) },
                        onClick = { menu = false; olvidarHora(l, f) }
                    )
                    DropdownMenuItem(
                        text = { Text("Quitar") }, leadingIcon = { Icon(Icons.Filled.Close, null) },
                        onClick = { menu = false; quitar(l, f) }
                    )
                }
            }
        }
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
