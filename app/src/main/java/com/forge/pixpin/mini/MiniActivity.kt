package com.forge.pixpin.mini

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.R
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ui.theme.PixPinTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La pantalla de una mini-aplicación: la lista de tareas, los gastos.
 *
 * ## Por qué es una tarjeta aparte y no una burbuja que se edita en el sitio
 *
 * Porque se escribe. Editar dentro de la conversación obliga a convivir con el teclado, la
 * lista desplazándose y el campo de escribir de abajo, que es otro sitio donde escribir a
 * dos centímetros: dos campos de texto a la vez en la misma pantalla es la receta de
 * escribir la compra en el chat y el mensaje en la compra.
 *
 * ## Se guarda solo, y a cada cambio
 *
 * No hay botón de guardar. Marcar una tarea es un gesto de medio segundo y nadie va a
 * confirmarlo después; si hubiera que hacerlo, la mitad de las listas se quedarían sin
 * guardar. Escribir el documento entero cuesta poco —es texto— y va al hilo de disco.
 */
class MiniActivity : ComponentActivity() {

    private lateinit var almacen: MensajesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        almacen = MensajesStore(this)
        val id = intent.getStringExtra(EXTRA_MENSAJE)
        if (id == null) {
            finish()
            return
        }
        setContent { PixPinTheme { Pantalla(id) } }
    }

    @Composable
    private fun Pantalla(id: String) {
        // El documento vive aquí mientras se edita, y el archivo se actualiza detrás. Al
        // revés —releer el archivo tras cada cambio— la lista parpadearía a cada letra.
        var documento by remember { mutableStateOf<String?>(null) }
        var cual by remember { mutableStateOf<MiniApp?>(null) }

        androidx.compose.runtime.LaunchedEffect(id) {
            val m = withContext(Dispatchers.IO) { almacen.leer() }.firstOrNull { it.id == id }
            if (m == null) {
                finish()
                return@LaunchedEffect
            }
            cual = MiniApp.de(m.miniapp)
            documento = m.texto
        }

        fun guardar(nuevo: String) {
            documento = nuevo
            lifecycleScope.launch(Dispatchers.IO) {
                almacen.cambiar { todos -> todos.map { if (it.id == id) it.copy(texto = nuevo) else it } }
            }
        }

        val doc = documento
        val app = cual
        var borrandoLista by remember { mutableStateOf(false) }
        if (borrandoLista && doc != null) {
            val nombre = Cabecera.titulo(doc).ifBlank { getString(R.string.miniapp_titulo_nuevo) }
            val n = Tareas.leer(doc).size
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { borrandoLista = false },
                title = { Text("Borrar lista") },
                text = {
                    Text(
                        "¿Borrar la lista «$nombre»" +
                            (if (n == 0) "" else if (n == 1) " con su tarea" else " con sus $n tareas") +
                            "? Se borra también del chat y del PC, y no se puede deshacer."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { borrandoLista = false; borrarLista(id, nombre) }) {
                        Text(getString(R.string.cd_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { borrandoLista = false }) { Text("Cancelar") }
                }
            )
        }

        // **Una tarjeta que sube desde abajo, no una pantalla** (24-sep-2026). Lo pidió el
        // usuario: que la mini app salga de abajo sin ponerse en pantalla completa, y que se
        // pueda ocultar para volver al chat, que se sigue viendo detrás. Se cierra con la X,
        // tocando fuera, bajando el asa o con atrás; lo escrito ya está guardado (ver arriba).
        val estado = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
        fun cerrar() { estado.targetState = false }
        androidx.activity.compose.BackHandler { cerrar() }
        androidx.compose.runtime.LaunchedEffect(estado.currentState, estado.isIdle) {
            if (estado.isIdle && !estado.currentState && !estado.targetState) finish()
        }
        var arrastre by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            androidx.compose.animation.AnimatedVisibility(
                visibleState = estado,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                // El velo: oscurece el chat y, tocado, cierra la tarjeta.
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color(0x66000000))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { cerrar() }
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visibleState = estado,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically { it },
                exit = androidx.compose.animation.slideOutVertically { it }
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.72f)
                        .offset { androidx.compose.ui.unit.IntOffset(0, arrastre.toInt().coerceAtLeast(0)) }
                        .imePadding()
                ) {
                    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                        // El asa: arrastrándola hacia abajo un buen trecho, la tarjeta se oculta.
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            if (arrastre > 120.dp.toPx()) cerrar()
                                            arrastre = 0f
                                        },
                                        onDragCancel = { arrastre = 0f }
                                    ) { cambio, dy ->
                                        cambio.consume()
                                        arrastre = (arrastre + dy).coerceAtLeast(0f)
                                    }
                                }
                                .padding(top = 10.dp, bottom = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.layout.Box(
                                Modifier
                                    .size(width = 36.dp, height = 4.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // El título se edita aquí mismo: es una línea, y mandarla a un
                            // diálogo aparte para cambiar una palabra sería más trabajo que
                            // el que uno viene a hacer.
                            TextField(
                                value = doc?.let { Cabecera.titulo(it) }.orEmpty(),
                                onValueChange = { nuevo ->
                                    val actual = doc ?: return@TextField
                                    guardar(Cabecera.linea(nuevo) + Cabecera.cuerpo(actual))
                                },
                                singleLine = true,
                                placeholder = { Text(getString(R.string.miniapp_titulo_nuevo)) },
                                modifier = Modifier.weight(1f)
                            )
                            // **Borrar la lista entera** (4-oct-2026, como la papelera del PC):
                            // por el mismo camino que borrar su mensaje en el chat, así que viaja
                            // como borrado y se va también del PC. Se pregunta: no hay deshacer.
                            if (app == MiniApp.TAREAS && doc != null) {
                                IconButton(onClick = { borrandoLista = true }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Borrar lista")
                                }
                            }
                            IconButton(onClick = { cerrar() }) {
                                Icon(Icons.Filled.Close, contentDescription = getString(R.string.cd_close))
                            }
                        }
                        HorizontalDivider()
                        if (doc != null && app != null) {
                            Column(Modifier.fillMaxWidth().weight(1f)) {
                                when (app) {
                                    MiniApp.TAREAS -> DeTareas(doc, ::guardar)
                                    MiniApp.GASTOS -> DeGastos(doc, ::guardar)
                                    MiniApp.CRONOMETRO -> DeCronometro(doc, ::guardar)
                                    MiniApp.TEMPORIZADOR -> DeTemporizador(id, doc, ::guardar)
                                    MiniApp.ALARMA -> DeAlarma(id, doc, ::guardar)
                                    MiniApp.CONTADOR -> DeContador(doc, ::guardar)
                                    MiniApp.RULETA -> DeRuleta(doc, ::guardar)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * La lista de tareas: casillas que se marcan y una línea para añadir.
     *
     * **Lo que trajo el PC** (2-oct-2026), que es pantalla y nada más —el documento no cambia—:
     * cuántos días hace que se creó cada tarea (nunca la fecha: lo pidió así el usuario), la línea
     * de avance con su barra, ocultar las hechas, y al tocar una fila elegirla para corregirla o
     * subirla y bajarla. La casilla es lo único que tacha.
     *
     * **Lo del 3 y 4-oct**: imágenes dentro de la tarea (miniaturas en la fila, el clip y pegar
     * en los campos; ver [ImagenesDeTareas]), y el aspa que quita una tarea con «Deshacer».
     */
    @Composable
    private fun DeTareas(documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val tareas = remember(documento) { Tareas.leer(documento) }
        var ocultarHechas by remember { mutableStateOf(false) }
        var elegida by remember { mutableStateOf<Int?>(null) }
        var corrigiendo by remember { mutableStateOf<Int?>(null) }
        val hoy = remember { java.time.LocalDate.now() }
        val alcance = androidx.compose.runtime.rememberCoroutineScope()
        // **Con imágenes** (3-oct-2026, como el PC): la línea de añadir y el campo de corregir
        // llevan chapas `[img 01]`; ver [CampoConImagenes].
        val nueva = remember { CampoConImagenes(lifecycleScope) }
        val corrector = remember { CampoConImagenes(lifecycleScope) }
        // Salir sin añadir borra las copias que no llegaron a ninguna tarea.
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { nueva.descartar(); corrector.descartar() }
        }
        val avisos = remember { androidx.compose.material3.SnackbarHostState() }
        var viendo by remember { mutableStateOf<String?>(null) }
        // Lo último, para «Deshacer»: el aviso vive más que esta composición.
        val docActual by androidx.compose.runtime.rememberUpdatedState(documento)
        val carpetaDeFiles = remember { filesDir.absolutePath }

        fun conLasTareas(nuevas: List<Tarea>) = onGuardar(Tareas.escribir(titulo, nuevas))

        fun dejarDeCorregir() {
            if (corrigiendo != null) corrector.descartar()
            corrigiendo = null
        }
        fun corregir(i: Int, visible: String) {
            corrector.cargar(visible)
            corrigiendo = i
        }
        fun guardarCorreccion(i: Int) {
            conLasTareas(Tareas.renombrar(tareas, i, corrector.componer()))
            corrigiendo = null
        }
        fun anadir() {
            if (nueva.texto.isBlank() && nueva.imagenes.isEmpty()) return
            val compuesto = nueva.componer()
            if (compuesto.isNotBlank()) conLasTareas(Tareas.anadir(tareas, compuesto))
        }

        // **Quitar una con su aspa, con «Deshacer»** (4-oct-2026, como el PC): se va al momento y
        // el aviso la devuelve a su sitio con su fecha, su estado y sus imágenes.
        fun quitar(i: Int) {
            val quitada = tareas.getOrNull(i) ?: return
            conLasTareas(Tareas.borrar(tareas, i))
            elegida = null
            dejarDeCorregir()
            // Una de solo imágenes se nombra por su primera, como el PC: «[img 01]» quitada.
            val dice = Tareas.legible(quitada.texto).ifEmpty { Tareas.fichaDeImagen(1) }
            alcance.launch {
                avisos.currentSnackbarData?.dismiss()
                val r = avisos.showSnackbar(
                    "«$dice» quitada", actionLabel = "Deshacer",
                    duration = androidx.compose.material3.SnackbarDuration.Long
                )
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    val doc = docActual
                    onGuardar(Tareas.escribir(Cabecera.titulo(doc), Tareas.reponer(Tareas.leer(doc), i, quitada)))
                }
            }
        }

        // El clip y el pegar: a qué campo van las imágenes que lleguen.
        var destino by remember { mutableStateOf(nueva) }
        fun adjuntar(uris: List<Uri>, campo: CampoConImagenes) {
            if (uris.isEmpty()) return
            alcance.launch {
                val carpeta = almacen.carpetaDeAdjuntos()
                val ms = System.currentTimeMillis()
                val copias = withContext(Dispatchers.IO) {
                    uris.mapIndexedNotNull { n, u -> ImagenesDeTareas.copiar(this@MiniActivity, u, carpeta, ms, n + 1) }
                }
                if (copias.size < uris.size) {
                    android.widget.Toast.makeText(this@MiniActivity, "Una imagen no se pudo leer", android.widget.Toast.LENGTH_SHORT).show()
                }
                copias.forEach { campo.meter(it.absolutePath) }
            }
        }
        val galeria = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
        ) { uris -> adjuntar(uris, destino) }
        // La cámara escribe directamente en la copia. Guardada como texto: sobrevive a que el
        // sistema recree la pantalla mientras la cámara está abierta.
        var fotoEnCurso by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
        val camara = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.TakePicture()
        ) { bien ->
            val f = fotoEnCurso?.let { java.io.File(it) }
            fotoEnCurso = null
            if (bien && f != null && f.length() > 0) {
                val campo = destino
                alcance.launch {
                    withContext(Dispatchers.IO) { ImagenesDeTareas.reducirEnSuSitio(f) }
                    campo.meter(f.absolutePath)
                }
            } else if (f != null) lifecycleScope.launch(Dispatchers.IO) { f.delete() }
        }
        fun hacerFoto() {
            val f = java.io.File(almacen.carpetaDeAdjuntos(), Tareas.nombreDeCopia(System.currentTimeMillis(), 1, "jpg"))
            fotoEnCurso = f.absolutePath
            runCatching {
                camara.launch(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
            }.onFailure {
                fotoEnCurso = null
                android.widget.Toast.makeText(this, "No hay cámara disponible", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        // El manifiesto declara la cámara, y entonces Android exige el permiso también para la
        // aplicación de cámara del sistema (ver `AdjuntosDeLaLeccion`).
        val permisoCamara = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { si ->
            if (si) hacerFoto()
            else android.widget.Toast.makeText(this, "Sin permiso para la cámara", android.widget.Toast.LENGTH_SHORT).show()
        }
        fun pedirFoto(campo: CampoConImagenes) {
            destino = campo
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) hacerFoto()
            else permisoCamara.launch(android.Manifest.permission.CAMERA)
        }
        fun pedirGaleria(campo: CampoConImagenes) {
            destino = campo
            galeria.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }

        Column(Modifier.fillMaxSize()) {
            val resumen = Tareas.resumenDe(tareas)
            if (!resumen.vacia) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(resumen.texto, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { resumen.avance ?: 0f },
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    )
                    if (resumen.hechas > 0) {
                        androidx.compose.material3.TextButton(onClick = { ocultarHechas = !ocultarHechas }) {
                            Text(if (ocultarHechas) "Ver las hechas" else "Ocultar las hechas")
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (tareas.isEmpty()) {
                        item { Nada() }
                    }
                    itemsIndexed(tareas, key = { i, _ -> i }) { i, t ->
                        if (ocultarHechas && t.hecha) return@itemsIndexed
                        val (texto, fecha) = remember(t.texto) { Tareas.partir(t.texto) }
                        // Lo que se lee y las imágenes, aparte: la fila enseña el texto limpio y,
                        // detrás, una miniatura por imagen.
                        val (limpio, enlaces) = remember(texto) { Tareas.imagenes(texto) }
                        val esLaElegida = elegida == i
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (esLaElegida) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable {
                                    // Tocar la elegida otra vez la corrige; tocar otra, la elige.
                                    if (esLaElegida) corregir(i, texto) else { elegida = i; dejarDeCorregir() }
                                }
                                .padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = t.hecha,
                                onCheckedChange = { conLasTareas(Tareas.alternar(tareas, i)) }
                            )
                            if (corrigiendo == i) {
                                Column(Modifier.weight(1f)) {
                                    FichasDelCampo(corrector) { viendo = it }
                                    CampoDeTarea(
                                        campo = corrector,
                                        placeholder = "",
                                        alPegarImagenes = { adjuntar(it, corrector) },
                                        alHecho = { guardarCorreccion(i) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                BotonDelClip({ pedirGaleria(corrector) }, { pedirFoto(corrector) })
                                IconButton(onClick = { guardarCorreccion(i) }) {
                                    Icon(Icons.Filled.Check, contentDescription = "Guardar")
                                }
                            } else {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    // Lo hecho se tacha en vez de irse: ver lo tachado es la mitad de
                                    // la satisfacción de una lista, y además dice lo que ya no hace
                                    // falta volver a pensar. El texto cede su sitio a las miniaturas.
                                    Text(
                                        limpio,
                                        fontSize = 16.sp,
                                        overflow = TextOverflow.Ellipsis,
                                        textDecoration = if (t.hecha) TextDecoration.LineThrough else null,
                                        color = if (t.hecha) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (enlaces.isNotEmpty()) {
                                        Row(
                                            Modifier.padding(start = if (limpio.isEmpty()) 0.dp else 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            enlaces.forEach { e ->
                                                MiniaturaDeTarea(Tareas.rutaDeImagen(e, carpetaDeFiles), apagada = t.hecha) { viendo = it }
                                            }
                                        }
                                    }
                                }
                                if (fecha != null) {
                                    val dias = Tareas.diasDesde(fecha, hoy)
                                    Text(
                                        if (dias == 0L) getString(R.string.tarea_creada_hoy)
                                        else resources.getQuantityString(R.plurals.tarea_creada_hace, dias.toInt(), dias.toInt()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                // La hora a la que suena (`⏰`, como el PC). En rojo si pasó sin sonar.
                                val hora = Tareas.horaDe(t.texto)
                                if (hora != null && !t.hecha) {
                                    Text(
                                        "${Tareas.RELOJ} ${Tareas.textoDeHora(hora)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hora.isBefore(java.time.LocalDateTime.now())) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                if (esLaElegida) {
                                    IconButton(onClick = { corregir(i, texto) }) { Icon(Icons.Filled.Edit, contentDescription = "Corregir", modifier = Modifier.size(18.dp)) }
                                    if (!t.hecha) IconButton(onClick = {
                                        ElegirHora.pedir(this@MiniActivity, hora) { nueva ->
                                            conLasTareas(tareas.toMutableList().also { l -> l[i] = l[i].copy(texto = Tareas.conHora(l[i].texto, nueva)) })
                                        }
                                    }) { Icon(Icons.Filled.Alarm, contentDescription = "Recordar a una hora", modifier = Modifier.size(18.dp)) }
                                    if (hora != null) IconButton(onClick = {
                                        conLasTareas(tareas.toMutableList().also { l -> l[i] = l[i].copy(texto = Tareas.conHora(l[i].texto, null)) })
                                    }) { Icon(Icons.Filled.AlarmOff, contentDescription = "No recordar", modifier = Modifier.size(18.dp)) }
                                    IconButton(onClick = { conLasTareas(Tareas.mover(tareas, i, i - 1)); elegida = (i - 1).coerceAtLeast(0) }, enabled = i > 0) {
                                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Subir", modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = { conLasTareas(Tareas.mover(tareas, i, i + 1)); elegida = (i + 1).coerceAtMost(tareas.lastIndex) }, enabled = i < tareas.lastIndex) {
                                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Bajar", modifier = Modifier.size(20.dp))
                                    }
                                }
                                IconButton(onClick = { quitar(i) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Quitar",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                androidx.compose.material3.SnackbarHost(avisos, Modifier.align(Alignment.BottomCenter))
            }
            // La línea de añadir, con su clip a la izquierda del +.
            Surface(shadowElevation = 8.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(4.dp)
                ) {
                    FichasDelCampo(nueva) { viendo = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CampoDeTarea(
                            campo = nueva,
                            placeholder = getString(R.string.miniapp_anadir),
                            alPegarImagenes = { adjuntar(it, nueva) },
                            alHecho = { anadir() },
                            modifier = Modifier.weight(1f)
                        )
                        BotonDelClip({ pedirGaleria(nueva) }, { pedirFoto(nueva) })
                        IconButton(onClick = { anadir() }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = getString(R.string.miniapp_anadir),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        viendo?.let { VisorDeImagenDeTarea(it) { viendo = null } }
    }

    /**
     * El clip: elegir fotos (varias a la vez) o hacer una. Un menú corto en vez de dos botones,
     * que la línea de añadir no tiene sitio para más.
     */
    @Composable
    private fun BotonDelClip(alElegir: () -> Unit, alHacerFoto: () -> Unit) {
        var abierto by remember { mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            IconButton(onClick = { abierto = true }) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Adjuntar imagen")
            }
            androidx.compose.material3.DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Elegir fotos") },
                    leadingIcon = { Icon(Icons.Filled.Image, null) },
                    onClick = { abierto = false; alElegir() }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Hacer foto") },
                    leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                    onClick = { abierto = false; alHacerFoto() }
                )
            }
        }
    }

    /**
     * Las imágenes que lleva el campo, encima de él: su miniatura (tocarla la enseña entera), su
     * número y un aspa que quita su chapa. Solo las que siguen teniendo chapa en el texto.
     */
    @Composable
    private fun FichasDelCampo(campo: CampoConImagenes, alVer: (String) -> Unit) {
        if (campo.imagenes.isEmpty()) return
        // Derivado: escribir una letra no recompone las miniaturas, solo quitar o poner una chapa.
        val van by androidx.compose.runtime.remember(campo) {
            androidx.compose.runtime.derivedStateOf { Tareas.conFicha(campo.estado.text.toString(), campo.imagenes) }
        }
        if (van.isEmpty()) return
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            van.forEach { (n, ruta) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniaturaDeTarea(ruta, apagada = false, alTocar = alVer)
                    Text(
                        Tareas.rotuloDeImagen(n),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Quitar imagen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable { campo.quitar(n) }
                            .padding(3.dp)
                    )
                }
            }
        }
    }

    /** Los gastos: concepto, importe y **el total**, que es a lo que se viene. */
    @Composable
    private fun DeGastos(documento: String, onGuardar: (String) -> Unit) {
        val idioma = resources.configuration.locales[0] ?: Locale.getDefault()
        val libro = remember(documento) { Gastos.leer(documento, idioma) }
        var concepto by remember { mutableStateOf("") }
        var importe by remember { mutableStateOf("") }

        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f)) {
                if (libro.gastos.isEmpty()) {
                    item { Nada() }
                }
                itemsIndexed(libro.gastos, key = { i, _ -> i }) { i, g ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp,
                                                        top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            g.concepto,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            Gastos.textoDeImporte(g.centimos, libro.moneda, idioma),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        IconButton(onClick = {
                            onGuardar(Gastos.escribir(Gastos.borrar(libro, i), idioma))
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = getString(R.string.cd_delete),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            // El total, separado y en grande: es el número por el que se abre esto.
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(getString(R.string.miniapp_total), fontSize = 16.sp)
                Text(
                    Gastos.textoDeImporte(libro.total, libro.moneda, idioma),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Surface(shadowElevation = 8.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = concepto,
                        onValueChange = { concepto = it },
                        singleLine = true,
                        placeholder = { Text(getString(R.string.miniapp_concepto)) },
                        modifier = Modifier.weight(1.6f)
                    )
                    // **El menos, en un botón.**
                    //
                    // El teclado decimal de Android **no trae signo menos**, así que una
                    // devolución no se podía escribir: había que teclear el importe y
                    // buscarse la vida. El botón lo pone y lo quita, y así además se ve
                    // de un vistazo si lo que se va a añadir suma o resta.
                    val enNegativo = importe.trimStart().startsWith("-")
                    androidx.compose.material3.TextButton(
                        onClick = {
                            importe = if (enNegativo) importe.trimStart().removePrefix("-")
                            else "-" + importe.trimStart()
                        }
                    ) {
                        Text(
                            if (enNegativo) "−" else "+",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (enNegativo) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                    TextField(
                        value = importe,
                        onValueChange = { importe = it },
                        singleLine = true,
                        // Teclado de números con coma: escribir «12,50» con el teclado de
                        // letras es cambiar de plano dos veces por cada gasto.
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        placeholder = { Text(getString(R.string.miniapp_importe)) },
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                    IconButton(onClick = {
                        val centimos = Gastos.centimosDe(importe, libro.decimales)
                        // Sin importe legible no se añade nada: una línea de gasto sin
                        // número no suma, y una cuenta con líneas que no suman engaña.
                        if (concepto.isNotBlank() && centimos != null) {
                            onGuardar(
                                Gastos.escribir(
                                    Gastos.anadir(libro, concepto, centimos), idioma
                                )
                            )
                            concepto = ""
                            importe = ""
                        }
                    }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = getString(R.string.miniapp_anadir),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    /**
     * El cronómetro.
     *
     * El número se repinta **diez veces por segundo mientras corre**, y ni una sola vez
     * mientras está parado: un reloj parado que se recompone es batería tirada. Y el
     * número no se guarda en cada latido —se guarda el instante de arranque, ver
     * [Tiempos]—, así que esos latidos no tocan el disco.
     */
    @Composable
    private fun DeCronometro(documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val c = remember(documento) { Tiempos.leerCronometro(documento) }
        var ahora by remember { mutableStateOf(System.currentTimeMillis()) }
        androidx.compose.runtime.LaunchedEffect(c.corriendo) {
            while (c.corriendo) {
                ahora = System.currentTimeMillis()
                kotlinx.coroutines.delay(100)
            }
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                Tiempos.comoSeLee(c.transcurrido(ahora)),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 24.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Button(onClick = {
                    val t = System.currentTimeMillis()
                    onGuardar(
                        Tiempos.escribirCronometro(
                            titulo,
                            if (c.corriendo) Tiempos.parar(c, t) else Tiempos.arrancar(c, t)
                        )
                    )
                }) {
                    Text(
                        getString(
                            if (c.corriendo) R.string.miniapp_parar
                            else R.string.miniapp_arrancar
                        )
                    )
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        onGuardar(
                            Tiempos.escribirCronometro(
                                titulo, Tiempos.vuelta(c, System.currentTimeMillis())
                            )
                        )
                    },
                    enabled = c.corriendo
                ) { Text(getString(R.string.miniapp_vuelta)) }
                androidx.compose.material3.OutlinedButton(onClick = {
                    onGuardar(Tiempos.escribirCronometro(titulo, Tiempos.reiniciar(c)))
                }) { Text(getString(R.string.miniapp_reiniciar)) }
            }
            LazyColumn(Modifier.padding(top = 20.dp)) {
                itemsIndexed(c.vueltas.reversed()) { i, v ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${c.vueltas.size - i}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Tiempos.comoSeLee(v), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    /** El temporizador: lo que falta, en grande, y la alarma puesta de verdad. */
    @Composable
    private fun DeTemporizador(id: String, documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val t = remember(documento) { Tiempos.leerTemporizador(documento) }
        var ahora by remember { mutableStateOf(System.currentTimeMillis()) }
        androidx.compose.runtime.LaunchedEffect(t.corriendo) {
            while (t.corriendo) {
                ahora = System.currentTimeMillis()
                kotlinx.coroutines.delay(200)
            }
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                Tiempos.comoSeLeeCorto(t.restante(ahora)),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = if (t.vencido(ahora)) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 24.dp)
            )
            // Sumar y restar minutos, que es como se pone un temporizador de cocina.
            // Un selector de hora para «cinco minutos» son cuatro toques y una pantalla.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-5, -1, 1, 5).forEach { minutos ->
                    androidx.compose.material3.OutlinedButton(onClick = {
                        onGuardar(
                            Tiempos.escribirTemporizador(
                                titulo,
                                Tiempos.conDuracion(
                                    t, t.duracion + minutos * 60_000L,
                                    System.currentTimeMillis()
                                )
                            )
                        )
                    }) { Text(if (minutos > 0) "+$minutos" else "$minutos") }
                }
            }
            androidx.compose.material3.Button(
                onClick = {
                    val cuando = System.currentTimeMillis()
                    val nuevo = if (t.corriendo) {
                        com.forge.pixpin.pin.Recordatorios.quitar(this@MiniActivity, aviso(id))
                        Tiempos.detener(t)
                    } else {
                        Tiempos.lanzar(t, cuando).also { lanzado ->
                            lanzado.finEn?.let {
                                com.forge.pixpin.pin.Recordatorios.poner(
                                    this@MiniActivity, aviso(id), it
                                )
                            }
                        }
                    }
                    onGuardar(Tiempos.escribirTemporizador(titulo, nuevo))
                },
                modifier = Modifier.padding(top = 20.dp)
            ) {
                Text(
                    getString(
                        if (t.corriendo) R.string.miniapp_parar else R.string.miniapp_arrancar
                    )
                )
            }
        }
    }

    /** La alarma: una hora y un interruptor. */
    @Composable
    private fun DeAlarma(id: String, documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val a = remember(documento) { Tiempos.leerAlarma(documento) }

        fun conLaAlarma(nueva: Alarma) {
            // La alarma del sistema se rehace entera en cada cambio: quitarla y volver a
            // ponerla es una llamada, y así no hay forma de que quede una vieja sonando a
            // una hora que ya nadie pidió.
            com.forge.pixpin.pin.Recordatorios.quitar(this, aviso(id))
            if (nueva.activa) {
                com.forge.pixpin.pin.Recordatorios.poner(
                    this, aviso(id),
                    com.forge.pixpin.pin.proximaVezQueSean(
                        System.currentTimeMillis(), nueva.hora, nueva.minuto,
                        java.util.TimeZone.getDefault()
                    )
                )
            }
            onGuardar(Tiempos.escribirAlarma(titulo, nueva))
        }

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "${a.hora}:${a.minuto.toString().padStart(2, '0')}",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 24.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(getString(R.string.miniapp_activa), fontSize = 16.sp)
                androidx.compose.material3.Switch(
                    checked = a.activa,
                    onCheckedChange = { conLaAlarma(a.copy(activa = it)) },
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            Row(
                Modifier.padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.OutlinedButton(onClick = {
                    conLaAlarma(a.copy(hora = (a.hora + 23) % 24))
                }) { Text("-1 h") }
                androidx.compose.material3.OutlinedButton(onClick = {
                    conLaAlarma(a.copy(hora = (a.hora + 1) % 24))
                }) { Text("+1 h") }
                androidx.compose.material3.OutlinedButton(onClick = {
                    conLaAlarma(a.copy(minuto = (a.minuto + 55) % 60))
                }) { Text("-5 min") }
                androidx.compose.material3.OutlinedButton(onClick = {
                    conLaAlarma(a.copy(minuto = (a.minuto + 5) % 60))
                }) { Text("+5 min") }
            }
        }
    }

    /**
     * Borra la lista: lo mismo que el «Borrar» de su burbuja en el chat (`MensajesActivity`): su
     * adjunto, lo que hubiera de ella en los proyectos y el mensaje, con [MensajesStore.cambiar],
     * que le deja su marca de borrado para que la sincronización no la resucite desde el PC.
     */
    private fun borrarLista(id: String, nombre: String) {
        val app = application as? com.forge.pixpin.PixPinApp
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val m = almacen.leer().firstOrNull { it.id == id } ?: return@withContext
                almacen.borrarAdjunto(m)
                if (app != null) runCatching {
                    com.forge.pixpin.guardados.UnirAlProyecto.quitarDeLosProyectos(app.proyectos, listOf(m), System.currentTimeMillis())
                }
                almacen.cambiar { todos -> todos.filterNot { it.id == id } }
            }
            android.widget.Toast.makeText(this@MiniActivity, "Lista «$nombre» borrada", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** El identificador con el que esta mini-app pide hora al sistema. */
    private fun aviso(id: String): String =
        com.forge.pixpin.pin.RecordatorioReceiver.DE_UNA_MINIAPP + id

    /**
     * El contador: un número grande y dos botones grandes.
     *
     * Grandes de verdad, porque se usa sin mirar: contando cajas o vueltas, el dedo va al
     * botón mientras la vista está en otro sitio. Un botón de icono de 24 puntos ahí es
     * un fallo de cuenta.
     */
    @Composable
    private fun DeContador(documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val c = remember(documento) { Contador.leer(documento) }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                c.valor.toString(),
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 20.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { onGuardar(Contador.escribir(titulo, Contador.menos(c))) },
                    modifier = Modifier.size(96.dp)
                ) { Text("−", fontSize = 34.sp) }
                androidx.compose.material3.Button(
                    onClick = { onGuardar(Contador.escribir(titulo, Contador.mas(c))) },
                    modifier = Modifier.size(96.dp)
                ) { Text("+", fontSize = 34.sp) }
            }
            Row(
                Modifier.padding(top = 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(getString(R.string.miniapp_paso), fontSize = 14.sp)
                listOf(1L, 5L, 10L, 12L).forEach { paso ->
                    androidx.compose.material3.TextButton(onClick = {
                        onGuardar(Contador.escribir(titulo, Contador.conPaso(c, paso)))
                    }) {
                        Text(
                            "$paso",
                            fontWeight = if (c.paso == paso) FontWeight.Bold else FontWeight.Normal,
                            color = if (c.paso == paso) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            androidx.compose.material3.TextButton(
                onClick = { onGuardar(Contador.escribir(titulo, Contador.reiniciar(c))) }
            ) { Text(getString(R.string.miniapp_reiniciar)) }
        }
    }

    /**
     * La ruleta: los nombres y el sorteo.
     *
     * El sorteo usa el mismo [Ruleta] que el pin, así que reparte igual desde los dos
     * sitios. Al que sale se le puede quitar de la lista, que es lo que uno hace cuando
     * está repartiendo turnos y no quiere que repita.
     */
    @Composable
    private fun DeRuleta(documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val nombres = remember(documento) { RuletaDoc.leer(documento) }
        var escrito by remember { mutableStateOf("") }
        var tocado by remember { mutableStateOf<Int?>(null) }

        Column(Modifier.fillMaxSize()) {
            tocado?.let { i ->
                nombres.getOrNull(i)?.let { quien ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                quien,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            androidx.compose.material3.TextButton(onClick = {
                                onGuardar(
                                    RuletaDoc.escribir(titulo, RuletaDoc.quitar(nombres, i))
                                )
                                tocado = null
                            }) { Text(getString(R.string.cd_delete)) }
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                if (nombres.isEmpty()) {
                    item { Nada() }
                }
                itemsIndexed(nombres, key = { i, _ -> i }) { i, nombre ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            nombre,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (tocado == i) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            onGuardar(RuletaDoc.escribir(titulo, RuletaDoc.quitar(nombres, i)))
                            tocado = null
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = getString(R.string.cd_delete),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            androidx.compose.material3.Button(
                onClick = {
                    // El azar entra por fuera, como en el pin: así el sorteo se puede
                    // comprobar de verdad en vez de girar mil veces y confiar.
                    tocado = Ruleta.elegir(nombres) { Math.random() }
                },
                enabled = nombres.size >= 2,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text(getString(R.string.miniapp_sortear)) }
            LineaParaAnadir(
                valor = escrito,
                onValor = { escrito = it },
                onAnadir = {
                    if (escrito.isNotBlank()) {
                        onGuardar(RuletaDoc.escribir(titulo, RuletaDoc.anadir(nombres, escrito)))
                        escrito = ""
                    }
                }
            )
        }
    }

    /** Recién creada y sin nada dentro. */
    @Composable
    private fun Nada() {
        Text(
            getString(R.string.miniapp_sin_nada),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(32.dp)
        )
    }

    /** La línea de abajo para añadir una cosa más. */
    @Composable
    private fun LineaParaAnadir(
        valor: String,
        onValor: (String) -> Unit,
        onAnadir: () -> Unit
    ) {
        Surface(shadowElevation = 8.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = valor,
                    onValueChange = onValor,
                    singleLine = true,
                    placeholder = { Text(getString(R.string.miniapp_anadir)) },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onAnadir) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = getString(R.string.miniapp_anadir),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_MENSAJE = "mini_mensaje"

        /** Abre la mini-app de ese mensaje. */
        fun abrir(context: Context, idDelMensaje: String) {
            context.startActivity(
                Intent(context, MiniActivity::class.java)
                    .putExtra(EXTRA_MENSAJE, idDelMensaje)
            )
        }
    }
}
