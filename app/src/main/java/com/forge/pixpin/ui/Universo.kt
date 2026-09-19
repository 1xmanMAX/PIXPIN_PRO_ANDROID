package com.forge.pixpin.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.dibujoDeLaFoto
import com.forge.pixpin.motor.HojasDelProyecto
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.ui.theme.BotonRedondo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * **El universo de un proyecto, por dentro**: la pantalla de [Universos]. Ver allí el porqué.
 *
 * - Se entra **vacío**. Con «+» se van trayendo cosas: del chat del proyecto, de sus hojas, una
 *   nota, un rótulo de letras, una figura, una imagen o un emoji. Cada una nace en un sitio libre.
 * - **Arrastrar** una cosa la mueve; arrastrar el fondo pasea; pellizcar acerca.
 * - **Tocar** abre: el archivo, la hoja, o —si ya tiene uno— su subespacio. Una nota se edita.
 * - **Mantener pulsado** la elige y saca su barra: abrir, **espacio** (crear o entrar en su
 *   sistema solar propio), **vincular** con otra, tamaño, color y quitar.
 * - Atrás sale del subespacio al de fuera, y del de fuera a la galaxia.
 *
 * Sin física: es un tablero donde las cosas se quedan donde se dejan, que es lo que pide un
 * organizador. Lo que se mueve se lee **al pintar**, así que arrastrar no recompone.
 */
@Composable
fun PantallaDeUniverso(
    app: PixPinApp,
    proyecto: Proyecto,
    onVolver: () -> Unit,
    onChat: () -> Unit
) {
    val contexto = LocalContext.current
    val densidad = LocalDensity.current.density
    val alcance = rememberCoroutineScope()
    val vibrar = LocalHapticFeedback.current
    val camara = remember { Camara() }
    val archivo = remember { File(contexto.filesDir, ARCHIVO_DE_UNIVERSOS) }

    var universos by remember { mutableStateOf(Universos.leer(runCatching { archivo.readText() }.getOrNull())) }
    fun guardar(u: Universos) {
        universos = u
        alcance.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(archivo.path + ".tmp")
                tmp.writeText(Universos.escribir(u))
                tmp.renameTo(archivo)
            }
        }
    }

    val raiz = remember(proyecto.id) { Universos.deProyecto(proyecto.id) }
    var dondeEstoy by remember(proyecto.id) { mutableStateOf(raiz) }
    val espacio = universos.espacio(dondeEstoy, proyecto.nombre)
    val espacioYa = rememberUpdatedState(espacio)
    fun cambiar(e: Espacio) = guardar(universos.con(e))

    // El chat del proyecto: de ahí salen los archivos, y con él se sabe si un cuerpo sigue vivo.
    val mensajes by produceState(emptyList<Mensaje>(), proyecto.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { MensajesStore(contexto).leer().filter { it.proyecto == proyecto.id } }.getOrDefault(emptyList())
        }
    }
    val paginas = remember(proyecto) { HojasDelProyecto.paginas(proyecto) { null } }

    var elegido by remember(dondeEstoy) { mutableStateOf<String?>(null) }
    var vinculando by remember(dondeEstoy) { mutableStateOf(false) }
    var anadiendo by remember { mutableStateOf(false) }
    var dialogo by remember { mutableStateOf<Dialogo?>(null) }
    // Por dónde va lo que está en el dedo, en dp del mundo. Se lee al pintar.
    val enElDedo = remember { mutableStateMapOf<String, Offset>() }

    fun sitioDe(c: Cuerpo): Offset = enElDedo[c.id] ?: Offset(c.x, c.y)
    fun centroDe(id: String, e: Espacio): Offset? =
        if (id == Espacio.SOL) Offset.Zero else e.cuerpos.firstOrNull { it.id == id }?.let { sitioDe(it) }

    fun abrir(c: Cuerpo) {
        when (c.clase) {
            Cuerpo.MENSAJE -> {
                val m = mensajes.firstOrNull { it.id == c.ref }
                if (m == null) onChat() else abrirMensajeDelUniverso(contexto, app, proyecto, m, onChat)
            }
            Cuerpo.HOJA -> paginas.firstOrNull { it.hoja.id == c.ref }?.let { abrirHoja(contexto, app, proyecto, it) }
            Cuerpo.NOTA, Cuerpo.ROTULO -> dialogo = Dialogo.Texto(c.clase, c)
            else -> Unit
        }
    }

    fun entrarOCrear(c: Cuerpo) {
        val (nuevo, id) = universos.conSubespacio(dondeEstoy, c.id, System.currentTimeMillis())
        if (id != null) {
            if (nuevo != universos) guardar(nuevo)
            camara.puesta = false
            dondeEstoy = id
        }
    }

    fun salir() {
        val padre = espacio.padre
        when {
            dialogo != null -> dialogo = null
            anadiendo -> anadiendo = false
            elegido != null -> { elegido = null; vinculando = false }
            padre != null -> { camara.puesta = false; dondeEstoy = padre }
            else -> onVolver()
        }
    }
    BackHandler(onBack = ::salir)

    /** Mete un cuerpo nuevo donde haya sitio y lo deja elegido no: se sigue añadiendo. */
    fun poner(clase: String, texto: String, ref: String? = null, ruta: String? = null, color: Int = 0) {
        val e = espacioYa.value
        if (e.yaEsta(clase, ref)) return
        val p = e.sitioLibre()
        cambiar(e.conCuerpo(Cuerpo("c-${System.nanoTime()}", clase, texto, ref, ruta, p.x, p.y, color = color)))
    }

    val elegirImagen = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) alcance.launch {
            val copia = withContext(Dispatchers.IO) {
                runCatching {
                    val carpeta = File(contexto.filesDir, "universos").apply { mkdirs() }
                    val destino = File(carpeta, "img-${System.currentTimeMillis()}.jpg")
                    contexto.contentResolver.openInputStream(uri)?.use { entra -> destino.outputStream().use { entra.copyTo(it) } }
                    destino.takeIf { it.length() > 0 }?.absolutePath
                }.getOrNull()
            }
            if (copia != null) poner(Cuerpo.IMAGEN, "", ruta = copia)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF151B3F), Color(0xFF05060F)), radius = 1900f))
            .onSizeChanged {
                camara.ancho = it.width.toFloat(); camara.alto = it.height.toFloat()
                if (!camara.puesta) {
                    camara.x.floatValue = it.width / 2f
                    camara.y.floatValue = it.height / 2f
                    camara.escala.floatValue = 1f
                    camara.puesta = true
                }
            }
            .pointerInput(dondeEstoy) {
                awaitEachGesture {
                    val abajo = awaitFirstDown(requireUnconsumed = false)
                    val e0 = espacioYa.value
                    val (w0x, w0y) = camara.alMundo(abajo.position.x, abajo.position.y, densidad)
                    val minimo = DEDO_MINIMO / camara.escala.floatValue
                    val tocado: String? = e0.cuerpos.lastOrNull { c ->
                        val r = max(radioDe(c), minimo)
                        (c.x - w0x) * (c.x - w0x) + (c.y - w0y) * (c.y - w0y) <= r * r
                    }?.id ?: if (w0x * w0x + w0y * w0y <= RADIO_DEL_SOL * RADIO_DEL_SOL) Espacio.SOL else null

                    // ¿Toque, pulsación larga o arrastre? Se espera lo que dura una pulsación larga.
                    var sigue = true
                    var movido = false
                    val antes = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) return@withTimeoutOrNull "arriba"
                            if (ev.changes.count { it.pressed } >= 2) return@withTimeoutOrNull "mover"
                            val c = ev.changes.first()
                            if ((c.position - abajo.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull "mover"
                        }
                        @Suppress("UNREACHABLE_CODE") "mover"
                    }
                    when (antes) {
                        "arriba" -> {
                            sigue = false
                            val c = e0.cuerpos.firstOrNull { it.id == tocado }
                            val marcado = elegido
                            when {
                                vinculando && marcado != null && tocado != null && tocado != marcado -> {
                                    cambiar(e0.alternarVinculo(marcado, tocado))
                                    vinculando = false
                                }
                                tocado == null -> { elegido = null; vinculando = false; anadiendo = false }
                                tocado == Espacio.SOL -> {
                                    val sol = e0.sol
                                    if (sol == null) onChat() else abrir(sol)
                                }
                                c == null -> Unit
                                c.espacio != null -> entrarOCrear(c)
                                c.clase == Cuerpo.EMOJI || c.clase == Cuerpo.FIGURA || c.clase == Cuerpo.IMAGEN -> elegido = c.id
                                else -> abrir(c)
                            }
                        }
                        null -> if (tocado != null && tocado != Espacio.SOL) {
                            // Pulsación larga: se elige. Y desde ahí se puede seguir arrastrando.
                            vibrar.performHapticFeedback(HapticFeedbackType.LongPress)
                            elegido = tocado
                            vinculando = false
                        }
                        else -> movido = true
                    }
                    if (!sigue) return@awaitEachGesture

                    val cuerpo = e0.cuerpos.firstOrNull { it.id == tocado }
                    val agarre = cuerpo?.let { Offset(it.x - w0x, it.y - w0y) }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val activos = ev.changes.filter { it.pressed }
                        if (activos.isEmpty()) break
                        if (activos.size >= 2) {
                            camara.mover(ev.calculateCentroid(), ev.calculatePan(), ev.calculateZoom())
                        } else {
                            val dedo = activos.first()
                            if (!movido && (dedo.position - abajo.position).getDistance() > viewConfiguration.touchSlop) movido = true
                            if (movido) {
                                if (cuerpo != null && agarre != null) {
                                    val (wx, wy) = camara.alMundo(dedo.position.x, dedo.position.y, densidad)
                                    enElDedo[cuerpo.id] = Offset(wx + agarre.x, wy + agarre.y)
                                } else {
                                    camara.mover(dedo.position, dedo.position - dedo.previousPosition, 1f)
                                }
                            }
                        }
                        ev.changes.forEach { it.consume() }
                    }
                    // Al soltar, lo movido se queda donde se dejó.
                    if (cuerpo != null) enElDedo.remove(cuerpo.id)?.let { fin ->
                        val ahora = espacioYa.value
                        ahora.cuerpos.firstOrNull { it.id == cuerpo.id }?.let { cambiar(ahora.conCuerpo(it.copy(x = fin.x, y = fin.y))) }
                    }
                }
            }
            .drawBehind {
                dibujarCielo(camara, densidad)
                // Los vínculos: una raya de centro a centro.
                val e = camara.escala.floatValue * densidad
                for (v in espacio.vinculos) {
                    val a = centroDe(v.a, espacio) ?: continue
                    val b = centroDe(v.b, espacio) ?: continue
                    drawLine(
                        Color.White.copy(alpha = 0.55f),
                        Offset(a.x * e + camara.x.floatValue, a.y * e + camara.y.floatValue),
                        Offset(b.x * e + camara.x.floatValue, b.y * e + camara.y.floatValue),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                    )
                }
            }
    ) {
        // El sol: el proyecto, o el archivo del que salió este subespacio.
        EnSuSitio(camara, densidad, { Offset.Zero }, 1f, RADIO_DEL_SOL) {
            val sol = espacio.sol
            if (sol == null) {
                val color = remember(proyecto.id) { colorDelSol(proyecto.id) }
                Bola(color, RADIO_DEL_SOL, null, proyecto.nombre.take(2).uppercase(), proyecto.nombre, false, false)
            } else {
                Pintado(sol, mensajes, proyecto, RADIO_DEL_SOL, elegido = false, conEspacio = false)
            }
        }
        for (c in espacio.cuerpos) {
            key(c.id) {
                EnSuSitio(camara, densidad, { sitioDe(c) }, c.tamano, RADIO_DEL_CUERPO) {
                    Pintado(c, mensajes, proyecto, RADIO_DEL_CUERPO, elegido = c.id == elegido, conEspacio = c.espacio != null)
                }
            }
        }

        // Arriba: volver, dónde se está (las migas) y los botones de siempre.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BotonRedondo(Icons.AutoMirrored.Filled.ArrowBack, "Volver", ::salir)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                val camino = universos.camino(dondeEstoy).ifEmpty { listOf(espacio) }
                Text(
                    camino.joinToString(" › ") { it.nombre.ifBlank { proyecto.nombre } },
                    color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (espacio.cuerpos.isEmpty()) "Vacío: añade con +" else "${espacio.cuerpos.size} en este espacio",
                    color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall
                )
            }
            BotonRedondo(Icons.AutoMirrored.Filled.Chat, "Abrir el chat", onChat)
            Spacer(Modifier.width(6.dp))
            BotonRedondo(Icons.Filled.CenterFocusStrong, "Verlo todo", {
                val puntos = listOf(PuntoDeGalaxia(0f, 0f)) + espacio.cuerpos.map { PuntoDeGalaxia(it.x, it.y) }
                alcance.launch { camara.encuadrar(puntos, densidad) }
            })
        }

        // Abajo: la barra de lo elegido, o la de añadir.
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val c = espacio.cuerpos.firstOrNull { it.id == elegido }
            if (c != null) {
                if (vinculando) {
                    Text("Toca otra cosa para vincularla", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
                }
                Fila {
                    if (c.clase == Cuerpo.MENSAJE || c.clase == Cuerpo.HOJA || c.clase == Cuerpo.NOTA || c.clase == Cuerpo.ROTULO) {
                        Chip(if (c.clase == Cuerpo.NOTA || c.clase == Cuerpo.ROTULO) "Editar" else "Abrir") { abrir(c) }
                    }
                    Chip(if (c.espacio != null) "Entrar" else "Espacio") { entrarOCrear(c) }
                    Chip("Vincular", puesto = vinculando) { vinculando = !vinculando }
                    Chip("−") { cambiar(espacio.conCuerpo(c.copy(tamano = (c.tamano / 1.25f).coerceAtLeast(Cuerpo.TAMANO_MIN)))) }
                    Chip("+") { cambiar(espacio.conCuerpo(c.copy(tamano = (c.tamano * 1.25f).coerceAtMost(Cuerpo.TAMANO_MAX)))) }
                    Chip("Color") { cambiar(espacio.conCuerpo(c.copy(color = (c.color + 1) % COLORES_DE_CUERPO.size))) }
                    Chip("Quitar") {
                        if (c.espacio != null) dialogo = Dialogo.Quitar(c)
                        else { guardar(universos.sinCuerpo(dondeEstoy, c.id)); elegido = null }
                    }
                }
            } else if (anadiendo) {
                Fila {
                    Chip("Del chat") { dialogo = Dialogo.DelChat }
                    Chip("Hoja") { dialogo = Dialogo.Hojas }
                    Chip("Nota") { dialogo = Dialogo.Texto(Cuerpo.NOTA, null) }
                    Chip("Rótulo") { dialogo = Dialogo.Texto(Cuerpo.ROTULO, null) }
                    Chip("Figura") { dialogo = Dialogo.Figuras }
                    Chip("Imagen") { elegirImagen.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    Chip("Emoji") { dialogo = Dialogo.Texto(Cuerpo.EMOJI, null) }
                }
            }
            if (c == null) {
                Spacer(Modifier.size(8.dp))
                BotonRedondo(if (anadiendo) Icons.Filled.Close else Icons.Filled.Add, "Añadir", { anadiendo = !anadiendo }, puesto = anadiendo)
            }
        }
    }

    // ---- Los diálogos ----
    when (val d = dialogo) {
        is Dialogo.Texto -> {
            var texto by remember(d) { mutableStateOf(d.cuerpo?.texto.orEmpty()) }
            AlertDialog(
                onDismissRequest = { dialogo = null },
                title = { Text(when (d.clase) { Cuerpo.NOTA -> "Nota"; Cuerpo.ROTULO -> "Rótulo"; else -> "Emoji" }) },
                text = {
                    Column {
                        OutlinedTextField(texto, { texto = it.take(if (d.clase == Cuerpo.NOTA) 600 else 60) }, singleLine = d.clase != Cuerpo.NOTA)
                        if (d.clase == Cuerpo.EMOJI) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                            EMOJIS_A_MANO.forEach { e -> Text(e, fontSize = 26.sp, modifier = Modifier.clickable { texto = e }.padding(6.dp)) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val limpio = texto.trim()
                        dialogo = null
                        if (limpio.isNotEmpty()) {
                            val c = d.cuerpo
                            if (c == null) poner(d.clase, limpio) else cambiar(espacio.conCuerpo(c.copy(texto = limpio)))
                        }
                    }) { Text("Guardar") }
                },
                dismissButton = { TextButton(onClick = { dialogo = null }) { Text("Cancelar") } }
            )
        }
        Dialogo.Figuras -> AlertDialog(
            onDismissRequest = { dialogo = null },
            title = { Text("Figura") },
            text = {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Cuerpo.FIGURAS.forEach { f ->
                        Box(Modifier.padding(6.dp).size(56.dp).clickable { dialogo = null; poner(Cuerpo.FIGURA, f) }) {
                            Figura(f, MaterialTheme.colorScheme.primary, Modifier.fillMaxSize())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialogo = null }) { Text("Cerrar") } }
        )
        Dialogo.DelChat -> ListaParaAnadir(
            titulo = "Del chat",
            vacio = "En el chat de este proyecto aún no hay nada.",
            filas = mensajes.asReversed().map { m -> FilaDeLista(m.id, nombreDelMensaje(m), iconoDelMensaje(m), espacio.yaEsta(Cuerpo.MENSAJE, m.id)) },
            onElegir = { id ->
                mensajes.firstOrNull { it.id == id }?.let { m ->
                    poner(Cuerpo.MENSAJE, nombreDelMensaje(m), ref = m.id, ruta = m.ruta.takeIf { m.clase == Clase.IMAGEN })
                }
            },
            onCerrar = { dialogo = null }
        )
        Dialogo.Hojas -> ListaParaAnadir(
            titulo = "Hojas",
            vacio = "Este proyecto no tiene hojas.",
            filas = paginas.map { p -> FilaDeLista(p.hoja.id, p.nombre.ifBlank { p.hoja.nombre.ifBlank { "Hoja" } }, Icons.Filled.Description, espacio.yaEsta(Cuerpo.HOJA, p.hoja.id)) }
                .distinctBy { it.id },
            onElegir = { id ->
                paginas.firstOrNull { it.hoja.id == id }?.let { p -> poner(Cuerpo.HOJA, p.nombre.ifBlank { p.hoja.nombre.ifBlank { "Hoja" } }, ref = id) }
            },
            onCerrar = { dialogo = null }
        )
        is Dialogo.Quitar -> AlertDialog(
            onDismissRequest = { dialogo = null },
            title = { Text("¿Quitar con su espacio?") },
            text = { Text("Dentro tiene su propio espacio, y se quita con todo lo que haya en él. Los archivos no se borran: siguen en el chat y en el proyecto.") },
            confirmButton = {
                TextButton(onClick = { dialogo = null; guardar(universos.sinCuerpo(dondeEstoy, d.cuerpo.id)); elegido = null }) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { dialogo = null }) { Text("Cancelar") } }
        )
        null -> Unit
    }
}

private sealed interface Dialogo {
    data class Texto(val clase: String, val cuerpo: Cuerpo?) : Dialogo
    data object Figuras : Dialogo
    data object DelChat : Dialogo
    data object Hojas : Dialogo
    data class Quitar(val cuerpo: Cuerpo) : Dialogo
}

private data class FilaDeLista(val id: String, val nombre: String, val icono: ImageVector, val yaEsta: Boolean)

/** La lista de la que se va añadiendo, **sin cerrarse**: se tocan varias y luego se cierra. */
@Composable
private fun ListaParaAnadir(
    titulo: String,
    vacio: String,
    filas: List<FilaDeLista>,
    onElegir: (String) -> Unit,
    onCerrar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(titulo) },
        text = {
            if (filas.isEmpty()) Text(vacio)
            else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(filas, key = { it.id }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !f.yaEsta) { onElegir(f.id) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(f.icono, contentDescription = null, Modifier.size(20.dp))
                        Text(
                            f.nombre, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (f.yaEsta) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(start = 10.dp)
                        )
                        if (f.yaEsta) Text("Ya está", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Hecho") } }
    )
}

/** Coloca [contenido] en su sitio del mundo, con su tamaño. Lo que cambia se lee al pintar. */
@Composable
private fun EnSuSitio(
    camara: Camara,
    densidad: Float,
    sitio: () -> Offset,
    tamano: Float,
    radio: Float,
    contenido: @Composable () -> Unit
) {
    Column(
        Modifier
            .requiredWidth(150.dp)
            .graphicsLayer {
                val e = camara.escala.floatValue * tamano
                val p = sitio()
                transformOrigin = TransformOrigin(0.5f, if (size.height > 0f) radio * densidad / size.height else 0f)
                scaleX = e; scaleY = e
                translationX = p.x * densidad * camara.escala.floatValue + camara.x.floatValue - size.width / 2
                translationY = p.y * densidad * camara.escala.floatValue + camara.y.floatValue - radio * densidad
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) { contenido() }
}

/** Cómo se ve un cuerpo según lo que es. */
@Composable
private fun Pintado(c: Cuerpo, mensajes: List<Mensaje>, proyecto: Proyecto, radio: Float, elegido: Boolean, conEspacio: Boolean) {
    val color = COLORES_DE_CUERPO[c.color.coerceIn(0, COLORES_DE_CUERPO.lastIndex)]
    when (c.clase) {
        Cuerpo.NOTA -> Text(
            c.texto, color = Color(0xFF14172B), fontSize = 12.sp, maxLines = 8, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(lerp(color, Color.White, 0.55f))
                .then(if (elegido || conEspacio) Modifier.border(2.dp, if (elegido) Color.White else color, RoundedCornerShape(10.dp)) else Modifier)
                .padding(8.dp)
        )
        Cuerpo.ROTULO -> Text(
            c.texto, color = lerp(color, Color.White, 0.6f), fontSize = 24.sp, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center, maxLines = 3,
            modifier = if (elegido) Modifier.border(1.dp, Color.White, RoundedCornerShape(6.dp)).padding(4.dp) else Modifier
        )
        Cuerpo.EMOJI -> Text(c.texto, fontSize = 40.sp, modifier = if (elegido) Modifier.border(1.dp, Color.White, CircleShape).padding(4.dp) else Modifier)
        Cuerpo.FIGURA -> Figura(c.texto, if (elegido) Color.White else color, Modifier.requiredSize((radio * 2).dp))
        Cuerpo.IMAGEN -> {
            val foto = miniaturaDe(c.ruta)
            Box(
                Modifier
                    .requiredSize((radio * 2.4f).dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1A2040))
                    .border(if (elegido) 2.dp else 1.dp, if (elegido) Color.White else Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (foto != null) Image(foto.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.Image, null, tint = Color.White.copy(alpha = 0.5f))
            }
        }
        Cuerpo.MENSAJE -> {
            val m = mensajes.firstOrNull { it.id == c.ref }
            val foto = if (m?.clase == Clase.IMAGEN) miniaturaDe(m.ruta) else null
            // Un mensaje que ya no está en el chat se pinta apagado: se puede quitar, no abrir.
            Bola(if (m == null && mensajes.isNotEmpty()) Color(0xFF555B70) else color, radio, foto, null, c.texto, elegido, conEspacio, m?.let { iconoDelMensaje(it) } ?: Icons.AutoMirrored.Filled.InsertDriveFile)
        }
        else -> Bola(color, radio, null, c.texto.take(2).uppercase(), c.texto.ifBlank { proyecto.nombre }, elegido, conEspacio)
    }
}

/** La bola de un archivo o de una hoja: su foto, su icono o sus letras, y el nombre debajo. */
@Composable
private fun Bola(
    color: Color, radio: Float, foto: Bitmap?, letras: String?, nombre: String,
    elegido: Boolean, conEspacio: Boolean, icono: ImageVector? = null
) {
    Box(contentAlignment = Alignment.Center) {
        // El anillo punteado dice que dentro hay un espacio propio.
        if (conEspacio) Canvas(Modifier.requiredSize((radio * 2 + 18).dp)) {
            drawCircle(Color.White.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
        }
        Box(
            Modifier
                .requiredSize((radio * 2).dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(lerp(color, Color.White, 0.25f), lerp(color, Color.Black, 0.4f))))
                .border(if (elegido) 3.dp else 1.5.dp, Color.White.copy(alpha = if (elegido) 1f else 0.7f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            when {
                foto != null -> Image(foto.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                icono != null -> Icon(icono, null, tint = Color.White, modifier = Modifier.size((radio * 0.8f).dp))
                else -> Text(letras.orEmpty(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (nombre.isNotBlank()) Text(
        nombre, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
    )
}

/** Las figuras de componer: a trazo, del color del cuerpo. */
@Composable
private fun Figura(cual: String, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val trazo = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        when (cual) {
            "cuadro" -> drawRect(color, Offset(w * 0.1f, h * 0.1f), androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.8f), style = trazo)
            "triangulo" -> drawPath(Path().apply { moveTo(w / 2, h * 0.1f); lineTo(w * 0.92f, h * 0.88f); lineTo(w * 0.08f, h * 0.88f); close() }, color, style = trazo)
            "estrella" -> drawPath(Path().apply {
                for (k in 0 until 10) {
                    val r = if (k % 2 == 0) w * 0.46f else w * 0.2f
                    val a = -PI / 2 + k * PI / 5
                    val x = w / 2 + (r * cos(a)).toFloat()
                    val y = h / 2 + (r * sin(a)).toFloat()
                    if (k == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }, color, style = trazo)
            "flecha" -> {
                drawLine(color, Offset(w * 0.08f, h / 2), Offset(w * 0.9f, h / 2), trazo.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.9f, h / 2), Offset(w * 0.65f, h * 0.28f), trazo.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.9f, h / 2), Offset(w * 0.65f, h * 0.72f), trazo.width, StrokeCap.Round)
            }
            else -> drawCircle(color, radius = w * 0.44f, style = trazo)
        }
    }
}

@Composable
private fun Fila(contenido: @Composable () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC10163A))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { contenido() }
}

@Composable
private fun Chip(texto: String, puesto: Boolean = false, onToque: () -> Unit) {
    Text(
        texto, color = Color.White, maxLines = 1, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (puesto) Color.White.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onToque)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    )
}

/** Una foto en pequeño, leída fuera del hilo de la pantalla y a un tamaño que no pese. */
@Composable
private fun miniaturaDe(ruta: String?): Bitmap? {
    val foto by produceState<Bitmap?>(null, ruta) {
        value = if (ruta == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val medida = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(ruta, medida)
                var muestra = 1
                while (max(medida.outWidth, medida.outHeight) / muestra > LADO_DE_MINIATURA * 2) muestra *= 2
                BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = muestra })
            }.getOrNull()
        }
    }
    return foto
}

private fun radioDe(c: Cuerpo): Float = when (c.clase) {
    Cuerpo.NOTA, Cuerpo.ROTULO -> 60f
    Cuerpo.IMAGEN -> RADIO_DEL_CUERPO * 1.2f
    else -> RADIO_DEL_CUERPO
} * c.tamano

private fun nombreDelMensaje(m: Mensaje): String =
    m.nombre.ifBlank { m.texto.lineSequence().firstOrNull().orEmpty().take(40) }.ifBlank {
        when (m.clase) {
            Clase.IMAGEN -> "Foto"
            Clase.VOZ -> "Nota de voz"
            Clase.PAGINA -> "Página " + ((m.pagina ?: 0) + 1)
            Clase.DIBUJO -> "Dibujo"
            else -> "Archivo"
        }
    }

private fun iconoDelMensaje(m: Mensaje): ImageVector = when (m.clase) {
    Clase.IMAGEN -> Icons.Filled.Image
    Clase.VOZ -> Icons.Filled.GraphicEq
    Clase.TABLA -> Icons.Filled.TableChart
    Clase.CROQUIS -> Icons.Filled.ViewInAr
    Clase.NOTA, Clase.DIBUJO, Clase.PAGINA -> Icons.Filled.Description
    else -> if (m.ruta?.endsWith(".pdf", ignoreCase = true) == true) Icons.Filled.PictureAsPdf else Icons.AutoMirrored.Filled.InsertDriveFile
}

/**
 * Abre un mensaje del chat **desde fuera del chat**: lo que tiene pantalla propia va a ella, y lo
 * demás —una nota de voz, un archivo de otra aplicación— lleva al chat, que es quien sabe.
 */
private fun abrirMensajeDelUniverso(contexto: Context, app: PixPinApp, proyecto: Proyecto, m: Mensaje, alChat: () -> Unit) {
    val ruta = m.ruta
    when {
        m.clase == Clase.TABLA && m.referencia != null ->
            com.forge.pixpin.tabla.TablaActivity.abrir(contexto, m.referencia, desdeProyecto = proyecto.id)
        m.clase == Clase.CROQUIS && m.referencia != null ->
            com.forge.pixpin.croquis3d.Croquis3DActivity.abrir(contexto, m.proyecto, m.referencia)
        m.clase == Clase.DIBUJO && m.referencia != null -> com.forge.pixpin.motor.DrawEditorActivity.abrir(
            contexto, m.referencia, com.forge.pixpin.motor.ExcalidrawStore.rutaDe(contexto, m.referencia), null, proyecto.id
        )
        m.clase == Clase.PAGINA && ruta != null && m.pagina != null && m.referencia != null ->
            com.forge.pixpin.motor.DrawEditorActivity.abrirPaginaDePdf(
                contexto, m.referencia, com.forge.pixpin.motor.ExcalidrawStore.rutaDe(contexto, m.referencia), ruta, m.pagina, proyecto.id
            )
        // Una foto se abre en el editor, sobre el mismo dibujo que usa el chat. Si aún no lo
        // tiene apuntado, que lo apunte el chat: aquí no se reescribe su archivo.
        m.clase == Clase.IMAGEN && ruta != null && m.referencia != null -> com.forge.pixpin.motor.DrawEditorActivity.abrir(
            contexto, m.dibujoDeLaFoto, com.forge.pixpin.motor.ExcalidrawStore.rutaDe(contexto, m.dibujoDeLaFoto), null, proyecto.id
        )
        ruta != null && ruta.endsWith(".pdf", ignoreCase = true) ->
            com.forge.pixpin.pdf.LectorPdfActivity.abrir(contexto, ruta, m.nombre)
        else -> alChat()
    }
    @Suppress("UNUSED_EXPRESSION") app
}

private const val ARCHIVO_DE_UNIVERSOS = "universos.json"
private const val RADIO_DEL_SOL = 46f
private const val RADIO_DEL_CUERPO = 34f
private const val LADO_DE_MINIATURA = 192

private val COLORES_DE_CUERPO = listOf(
    Color(0xFF5B8CFF), Color(0xFFFFB84D), Color(0xFF59D499), Color(0xFFFF6B8B), Color(0xFFB48CFF), Color(0xFF4DD0E1)
)

private val EMOJIS_A_MANO = listOf("⭐", "🔥", "✅", "❗", "💡", "📌", "🏗️", "📐", "🧱", "💧", "⚡", "🚧")
