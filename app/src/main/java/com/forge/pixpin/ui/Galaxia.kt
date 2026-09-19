package com.forge.pixpin.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.ui.theme.BarraDeCristal
import com.forge.pixpin.ui.theme.BotonDeBarra
import com.forge.pixpin.ui.theme.BotonRedondo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * **El sistema solar de proyectos** (17-sep-2026).
 *
 * El paginador encaja de uno en uno y con muchos proyectos se llega despacio. Esto es la vista
 * de pájaro, pedida por el usuario como «una galaxia donde mover los proyectos como estrellas»,
 * y después como **sistemas solares**:
 *
 * - Cada proyecto es un **sol**. Tocarlo abre su chat; mantenerlo abre su menú; pellizcarlo lo
 *   hace más grande o más pequeño.
 * - Las **notas y los emojis** son exoplanetas: se dejan sueltos o en la órbita de un sol, y lo
 *   siguen cuando se mueve. Soltados cerca de un sol, **el imán los engancha**; lejos, se sueltan.
 * - Entre soles hay **conexiones** que se ven y que tiran: arrastrar un sol se lleva detrás a los
 *   conectados, con su retraso, sin moverse en bloque. Ver [FisicaDeGalaxia].
 * - Cuatro modos abajo —Mover, Conectar, Nota, Emoji— con una frase que dice qué hace cada uno.
 * - Un buscador que vuela al proyecto, y un botón para verlo todo.
 * - **Ningún proyecto se borra desde aquí.** Las notas y los emojis sí.
 *
 * ## Por qué va fluido
 *
 * Un solo gesto para todo el espacio (se decide qué se tocó con cuentas, no con un detector por
 * objeto). La cámara y las posiciones se leen **solo en las capas y en el dibujo**: moverse no
 * recompone. La física corre solo mientras algo se mueve y se duerme sola; al dormirse guarda.
 * Las portadas se piden solo de los soles que se ven con tamaño. Ver [pixpin-rendimiento-primero].
 */
@Composable
fun PantallaDeGalaxia(
    proyectos: List<Proyecto>,
    onVolver: () -> Unit,
    /** Volver a la lista, puesto en ese proyecto. */
    onVer: (String) -> Unit
) {
    val contexto = LocalContext.current
    val densidad = LocalDensity.current.density
    val alcance = rememberCoroutineScope()
    val archivo = remember { File(contexto.filesDir, ARCHIVO) }

    var galaxia by remember { mutableStateOf<Galaxia?>(null) }
    LaunchedEffect(Unit) {
        galaxia = withContext(Dispatchers.IO) { Galaxia.leer(archivo.takeIf { it.exists() }?.readText()) }
    }
    fun guardar(g: Galaxia) {
        galaxia = g
        // En fila: dos guardados seguidos no se pisan el archivo.
        alcance.launch(EN_FILA) {
            runCatching {
                val tmp = File(archivo.path + ".tmp")
                tmp.writeText(Galaxia.escribir(g))
                tmp.renameTo(archivo)
            }
        }
    }

    val camara = remember { Camara() }
    var modo by remember { mutableStateOf(ModoDeGalaxia.MOVER) }
    var menuDe by remember { mutableStateOf<Proyecto?>(null) }
    var editandoNota by remember { mutableStateOf<NotaDeGalaxia?>(null) }
    var eligiendoEmoji by remember { mutableStateOf<NotaDeGalaxia?>(null) }
    var buscando by remember { mutableStateOf(false) }
    // **Dentro de un proyecto**: su propia galaxia, con sus hojas en órbita (17-sep-2026).
    // Ver [GalaxiaDelProyecto].
    var dentroDe by remember { mutableStateOf<String?>(null) }

    // **El mundo se rehace solo si cambia quién está y con quién va**, no al moverse: mover
    // guarda posiciones y eso no toca la física que ya está corriendo.
    val g = galaxia
    val ids = remember(proyectos) { proyectos.map { it.id } }
    val estructura = g?.let { gg -> Triple(ids, gg.notas.map { it.id to it.sol }, gg.enlacesEntre(ids.toSet())) }
    val previo = remember { arrayOfNulls<Mundo>(1) }
    val mundo = remember(estructura, proyectos) {
        if (g == null) null else Mundo(proyectos, g, previo[0]).also { previo[0] = it }
    }

    /** La galaxia con lo que la física tiene ahora mismo: sitios y tamaños. */
    fun conLoDeAhora(gg: Galaxia, m: Mundo): Galaxia {
        var r = gg.conSitios(
            m.soles.indices.associate { i -> m.soles[i].id to PuntoDeGalaxia(m.fis.x[i], m.fis.y[i]) },
            m.satelites.indices.associate { k -> m.satelites[k].id to PuntoDeGalaxia(m.fis.x[m.nS + k], m.fis.y[m.nS + k]) }
        )
        for (i in m.soles.indices) if (r.tamanoDe(m.soles[i].id) != m.esc[i]) r = r.conTamano(m.soles[i].id, m.esc[i])
        return r
    }

    // **La física, solo despierta.** Al quedarse todo quieto se duerme, asienta las cuerdas y
    // guarda; un toque la vuelve a despertar.
    LaunchedEffect(mundo) {
        val m = mundo ?: return@LaunchedEffect
        while (true) {
            snapshotFlow { m.despierto }.first { it }
            var quietos = 0
            var antes = 0L
            while (m.despierto) {
                withFrameNanos { t ->
                    val pasos = if (antes == 0L) 1 else ((t - antes) / 16_666_667f).roundToInt().coerceIn(1, 3)
                    antes = t
                    var v = 0f
                    repeat(pasos) { v = max(v, m.fis.paso()) }
                    m.frame.longValue++
                    quietos = if (m.arrastrando < 0 && !m.pellizcando && v < FisicaDeGalaxia.QUIETO) quietos + 1 else 0
                }
                if (quietos > 20) {
                    m.despierto = false
                    m.fis.asentar()
                    galaxia?.let { gg -> conLoDeAhora(gg, m).takeIf { it != gg }?.let { guardar(it) } }
                }
            }
        }
    }

    fun avisar(texto: String) = Toast.makeText(contexto, texto, Toast.LENGTH_SHORT).show()

    fun nuevaEn(m: Mundo, tocado: Int, wx: Float, wy: Float, emoji: String?): NotaDeGalaxia {
        val id = java.util.UUID.randomUUID().toString()
        if (tocado in 0 until m.nS) {
            val p = m.soles[tocado]
            val suyos = galaxia?.notas?.count { it.sol == p.id } ?: 0
            val sitio = Galaxia.enOrbita(PuntoDeGalaxia(m.fis.x[tocado], m.fis.y[tocado]), m.radio(tocado), suyos)
            return NotaDeGalaxia(id, "", sitio.x, sitio.y, emoji = emoji, sol = p.id)
        }
        val cerca = m.solCercano(wx, wy)
        return NotaDeGalaxia(id, "", wx, wy, emoji = emoji, sol = if (cerca >= 0) m.soles[cerca].id else null)
    }

    fun editar(m: Mundo, i: Int) {
        val n = galaxia?.notas?.getOrNull(i - m.nS) ?: return
        if (n.emoji != null) eligiendoEmoji = n else editandoNota = n
    }

    fun alTocar(m: Mundo, tocado: Int, wx: Float, wy: Float) {
        val gg = galaxia ?: return
        when (modo) {
            ModoDeGalaxia.MOVER -> when {
                // **Un toque entra en la galaxia del proyecto**: sus hojas, orbitándolo. El chat
                // sigue a un toque, en su menú (mantener) y en la cabecera de dentro.
                tocado in 0 until m.nS -> dentroDe = m.soles[tocado].id
                tocado >= m.nS -> editar(m, tocado)
            }
            ModoDeGalaxia.CONECTAR -> {
                val e = m.elegido.intValue
                m.elegido.intValue = -1
                when {
                    tocado < 0 || tocado == e -> Unit
                    e < 0 -> m.elegido.intValue = tocado
                    e < m.nS && tocado < m.nS -> {
                        val a = m.soles[e].id
                        val b = m.soles[tocado].id
                        avisar(if (gg.conectados(a, b)) "Conexión quitada" else "Conectados")
                        guardar(conLoDeAhora(gg, m).alternarEnlace(a, b))
                    }
                    (e < m.nS) != (tocado < m.nS) -> {
                        val sol = min(e, tocado)
                        val sat = max(e, tocado)
                        val n = gg.notas[sat - m.nS]
                        val id = m.soles[sol].id
                        val nuevo = if (n.sol == id) null else id
                        avisar(if (nuevo == null) "Suelta de su órbita" else "En la órbita de ${m.soles[sol].nombre}")
                        guardar(conLoDeAhora(gg, m).conNota(n.copy(sol = nuevo, x = m.fis.x[sat], y = m.fis.y[sat])))
                    }
                    else -> m.elegido.intValue = tocado
                }
                m.despertar()
            }
            ModoDeGalaxia.NOTA -> if (tocado >= m.nS) editar(m, tocado) else editandoNota = nuevaEn(m, tocado, wx, wy, null)
            ModoDeGalaxia.EMOJI -> if (tocado >= m.nS) editar(m, tocado) else eligiendoEmoji = nuevaEn(m, tocado, wx, wy, "")
        }
    }

    fun alMantener(m: Mundo, tocado: Int, wx: Float, wy: Float) {
        when {
            tocado in 0 until m.nS -> menuDe = m.soles[tocado]
            tocado >= m.nS -> editar(m, tocado)
            // Mantener en el vacío: una nota ahí mismo, sea cual sea el modo.
            else -> editandoNota = nuevaEn(m, -1, wx, wy, null)
        }
    }

    /** Soltar lo que se arrastraba: un exoplaneta mira si le ha cogido otro sol. */
    fun alSoltar(m: Mundo, i: Int) {
        m.fis.soltar(i)
        m.arrastrando = -1
        if (i < m.nS) return
        val k = i - m.nS
        val iman = m.iman.intValue
        m.iman.intValue = -1
        if (iman == m.solDe[k]) {
            m.fis.reajustarVarillasDe(i)
            return
        }
        val gg = galaxia ?: return
        val n = gg.notas.getOrNull(k) ?: return
        guardar(
            conLoDeAhora(gg, m).conNota(
                n.copy(sol = if (iman >= 0) m.soles[iman].id else null, x = m.fis.x[i], y = m.fis.y[i])
            )
        )
    }

    // Dentro de un proyecto se ve su galaxia y nada más.
    dentroDe?.let { id ->
        val suyo = proyectos.firstOrNull { it.id == id }
        val gg = galaxia
        if (suyo != null && gg != null) {
            // **Dentro, el universo del proyecto** (19-sep-2026): entra vacío y se llena a mano,
            // como un organizador. Antes aquí orbitaban solas todas las hojas
            // ([GalaxiaDelProyecto]); ahora las hojas se traen con «+ → Hoja». Ver [Universos].
            PantallaDeUniverso(
                app = contexto.applicationContext as com.forge.pixpin.PixPinApp,
                proyecto = suyo,
                onVolver = { dentroDe = null },
                onChat = { com.forge.pixpin.guardados.MensajesActivity.abrirChatDe(contexto, suyo.id, suyo.nombre) }
            )
            return
        }
        dentroDe = null
    }

    BackHandler {
        when {
            buscando -> buscando = false
            mundo != null && mundo.elegido.intValue >= 0 -> mundo.elegido.intValue = -1
            else -> onVolver()
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
                    camara.puesta = true
                }
            }
            .pointerInput(mundo, modo) {
                val m = mundo ?: return@pointerInput
                awaitEachGesture {
                    val abajo = awaitFirstDown(requireUnconsumed = false)
                    val inicio = SystemClock.uptimeMillis()
                    val (w0x, w0y) = camara.alMundo(abajo.position.x, abajo.position.y, densidad)
                    val tocado = m.tocar(w0x, w0y, DEDO_MINIMO / camara.escala.floatValue)
                    // 0 sin decidir · 1 arrastrando algo · 2 paseando · 3 con dos dedos · 4 ya mantenido
                    var clase = 0
                    var sujeto = -1
                    var agarreX = 0f
                    var agarreY = 0f
                    var pellizcoSol = -1
                    while (true) {
                        val evento = if (clase == 0) {
                            val quedan = LARGO - (SystemClock.uptimeMillis() - inicio)
                            withTimeoutOrNull(quedan.coerceAtLeast(1L)) { awaitPointerEvent() }
                        } else awaitPointerEvent()
                        if (evento == null) {
                            clase = 4
                            alMantener(m, tocado, w0x, w0y)
                            continue
                        }
                        val activos = evento.changes.filter { it.pressed }
                        if (activos.isEmpty()) {
                            if (clase == 0) alTocar(m, tocado, w0x, w0y)
                            break
                        }
                        if (activos.size >= 2) {
                            if (sujeto >= 0) { alSoltar(m, sujeto); sujeto = -1 }
                            if (clase != 3) {
                                clase = 3
                                // Dos dedos con el primero sobre un sol: se pellizca el sol.
                                if (tocado in 0 until m.nS) {
                                    pellizcoSol = tocado
                                    m.pellizcando = true
                                }
                            }
                            val zoom = evento.calculateZoom()
                            if (pellizcoSol >= 0) {
                                val antes = m.radio(pellizcoSol)
                                m.esc[pellizcoSol] = (m.esc[pellizcoSol] * zoom).coerceIn(Galaxia.TAMANO_MIN, Galaxia.TAMANO_MAX)
                                m.fis.crecer(pellizcoSol, m.radio(pellizcoSol) - antes)
                                m.despertar()
                                m.frame.longValue++
                            } else {
                                camara.mover(evento.calculateCentroid(), evento.calculatePan(), zoom)
                            }
                            evento.changes.forEach { it.consume() }
                            continue
                        }
                        val dedo = activos.first()
                        if (clase == 0 && (dedo.position - abajo.position).getDistance() > viewConfiguration.touchSlop) {
                            if (tocado >= 0 && modo != ModoDeGalaxia.CONECTAR) {
                                clase = 1
                                sujeto = tocado
                                agarreX = m.fis.x[tocado] - w0x
                                agarreY = m.fis.y[tocado] - w0y
                                m.fis.sujetar(tocado)
                                m.arrastrando = tocado
                                m.despertar()
                            } else {
                                clase = 2
                            }
                        }
                        if (clase == 1 && sujeto >= 0) {
                            val (wx, wy) = camara.alMundo(dedo.position.x, dedo.position.y, densidad)
                            m.fis.llevar(sujeto, wx + agarreX, wy + agarreY)
                            if (sujeto >= m.nS) {
                                val cerca = m.solCercano(m.fis.x[sujeto], m.fis.y[sujeto])
                                if (cerca != m.iman.intValue) m.iman.intValue = cerca
                            }
                        } else if (clase == 2 || clase == 3) {
                            camara.mover(dedo.position, dedo.position - dedo.previousPosition, 1f)
                        }
                        evento.changes.forEach { it.consume() }
                    }
                    if (sujeto >= 0) alSoltar(m, sujeto)
                    if (pellizcoSol >= 0) { m.pellizcando = false; m.despertar() }
                }
            }
            .drawBehind {
                dibujarCielo(camara, densidad)
                mundo?.let { dibujarLazos(it, camara, densidad) }
            }
    ) {
        val m = mundo
        if (m != null && g != null) {
            for (i in m.soles.indices) {
                key(m.soles[i].id) { Sol(m, i, camara, densidad) }
            }
            for (k in m.satelites.indices) {
                val n = g.notas.getOrNull(k) ?: continue
                key(n.id) { Satelite(m, k, n, camara, densidad) }
            }
        }

        // ---- Arriba: salir, título, buscar, verlo todo ----
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BotonRedondo(Icons.AutoMirrored.Filled.ArrowBack, "Volver", onVolver)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("Sistema solar", color = Color.White, style = MaterialTheme.typography.titleMedium)
                val enlaces = g?.enlacesEntre(ids.toSet())?.size ?: 0
                Text(
                    "${proyectos.size} proyectos · ${g?.notas?.size ?: 0} notas · $enlaces conexiones",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BotonRedondo(if (buscando) Icons.Filled.Close else Icons.Filled.Search, "Buscar un proyecto", { buscando = !buscando })
            Spacer(Modifier.width(6.dp))
            BotonRedondo(Icons.Filled.CenterFocusStrong, "Verlo todo", {
                mundo?.let { mm ->
                    val puntos = (0 until mm.fis.n).map { PuntoDeGalaxia(mm.fis.x[it], mm.fis.y[it]) }
                    alcance.launch { camara.encuadrar(puntos, densidad) }
                }
            })
        }

        if (buscando && m != null) {
            Buscador(
                proyectos = proyectos,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 60.dp, start = 16.dp, end = 16.dp),
                onElegir = { p ->
                    buscando = false
                    val i = m.indice[p.id] ?: return@Buscador
                    m.elegidoParaVer.intValue = i
                    m.frame.longValue++
                    alcance.launch { camara.volarA(m.fis.x[i], m.fis.y[i], densidad, max(camara.escala.floatValue, 1.1f)) }
                }
            )
        }

        // ---- Abajo: qué hace el modo, y los cuatro modos ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 10.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val elegido = m?.elegido?.intValue ?: -1
            val pista = if (modo == ModoDeGalaxia.CONECTAR && m != null && elegido >= 0) {
                if (elegido < m.nS) "Ahora toca otro sol para unirlo con «${m.soles[elegido].nombre}», o una nota para ponerla en su órbita"
                else "Ahora toca el sol en cuya órbita quieres ponerla"
            } else modo.pista
            Text(
                pista,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x77000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Spacer(Modifier.height(8.dp))
            BarraDeCristal {
                for (cual in ModoDeGalaxia.entries) {
                    BotonDeModo(cual, puesto = cual == modo) {
                        modo = cual
                        m?.elegido?.intValue = -1
                        m?.frame?.let { it.longValue++ }
                    }
                }
            }
        }
    }

    // ---- Los diálogos ----
    menuDe?.let { p ->
        val m = mundo
        val i = m?.indice?.get(p.id)
        val conexiones = g?.enlaces?.count { it.a == p.id || it.b == p.id } ?: 0
        MenuDelSol(
            p = p,
            agrandado = m != null && i != null && m.esc[i] != 1f,
            conexiones = conexiones,
            onCerrar = { menuDe = null },
            onChat = { menuDe = null; com.forge.pixpin.guardados.MensajesActivity.abrirChatDe(contexto, p.id, p.nombre) },
            onVer = { menuDe = null; onVer(p.id) },
            onNota = { menuDe = null; if (m != null && i != null) editandoNota = nuevaEn(m, i, 0f, 0f, null) },
            onEmoji = { menuDe = null; if (m != null && i != null) eligiendoEmoji = nuevaEn(m, i, 0f, 0f, "") },
            onTamanoNormal = {
                menuDe = null
                if (m != null && i != null) {
                    val antes = m.radio(i)
                    m.esc[i] = 1f
                    m.fis.crecer(i, m.radio(i) - antes)
                    m.despertar()
                }
            },
            onQuitarConexiones = {
                menuDe = null
                galaxia?.let { gg -> guardar((m?.let { conLoDeAhora(gg, it) } ?: gg).sinEnlacesDe(p.id)) }
            }
        )
    }

    editandoNota?.let { n ->
        DialogoDeNotaDeGalaxia(
            nota = n,
            existe = galaxia?.notas?.any { it.id == n.id } == true,
            onCerrar = { editandoNota = null },
            onGuardar = { texto, color ->
                editandoNota = null
                galaxia?.let { gg ->
                    val base = mundo?.let { conLoDeAhora(gg, it) } ?: gg
                    val actual = base.notas.firstOrNull { it.id == n.id } ?: n
                    guardar(if (texto.isBlank()) base.sinNota(n.id) else base.conNota(actual.copy(texto = texto, color = color)))
                }
            },
            onQuitar = {
                editandoNota = null
                galaxia?.let { gg -> guardar((mundo?.let { conLoDeAhora(gg, it) } ?: gg).sinNota(n.id)) }
            }
        )
    }

    eligiendoEmoji?.let { n ->
        DialogoDeEmoji(
            existe = galaxia?.notas?.any { it.id == n.id } == true,
            onCerrar = { eligiendoEmoji = null },
            onElegir = { e ->
                eligiendoEmoji = null
                galaxia?.let { gg ->
                    val base = mundo?.let { conLoDeAhora(gg, it) } ?: gg
                    val actual = base.notas.firstOrNull { it.id == n.id } ?: n
                    guardar(base.conNota(actual.copy(emoji = e)))
                }
            },
            onQuitar = {
                eligiendoEmoji = null
                galaxia?.let { gg -> guardar((mundo?.let { conLoDeAhora(gg, it) } ?: gg).sinNota(n.id)) }
            }
        )
    }
}

/** Qué hace un toque en el espacio. */
enum class ModoDeGalaxia(val titulo: String, val pista: String, val icono: ImageVector) {
    MOVER(
        "Mover",
        "Arrastra soles y notas · pellizca un sol para agrandarlo · toca un sol para entrar en él",
        Icons.Filled.PanTool
    ),
    CONECTAR(
        "Conectar",
        "Toca dos soles para unirlos o separarlos · o una nota y un sol para ponerla en su órbita",
        Icons.Filled.Link
    ),
    NOTA("Nota", "Toca un sol para darle una nota, o el espacio para dejarla suelta", Icons.Filled.EditNote),
    EMOJI("Emoji", "Toca un sol o el espacio para dejar un emoji flotando", Icons.Filled.EmojiEmotions)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val EN_FILA = Dispatchers.IO.limitedParallelism(1)

/** El archivo del sistema solar, en la carpeta de la aplicación. Es de este aparato: no viaja. */
private const val ARCHIVO = "galaxia.json"

/** Lo que tarda un toque en volverse «mantener», en ms. */
private const val LARGO = 450L

/** Lo mínimo que mide un blanco para el dedo, en dp de pantalla. */
internal const val DEDO_MINIMO = 26f

/**
 * **El mundo en marcha**: los cuerpos, su física y lo que la pantalla necesita saber de ellos
 * mientras se mueven. Se rehace cuando cambia quién está, conservando dónde estaba cada uno.
 */
@Stable
private class Mundo(val soles: List<Proyecto>, g: Galaxia, anterior: Mundo?) {
    val satelites: List<NotaDeGalaxia> = g.notas
    val nS = soles.size
    val fis = FisicaDeGalaxia(nS + satelites.size, nS)
    val indice = HashMap<String, Int>()
    val base = FloatArray(nS)
    val esc = FloatArray(nS)
    val hojas = IntArray(nS)
    val color = Array(nS) { colorDelSol(soles[it].id) }
    val medioAncho = FloatArray(satelites.size) { 22f }
    val medioAlto = FloatArray(satelites.size) { 22f }
    val solDe = IntArray(satelites.size)
    /** Sube en cada paso de la física: quien lo lee en su capa se repinta. */
    val frame = mutableLongStateOf(0L)
    var despierto by mutableStateOf(true)
    var arrastrando = -1
    var pellizcando = false
    /** El sol que enganchará al exoplaneta que se arrastra, o -1. */
    val iman = mutableIntStateOf(-1)
    /** Lo primero que se tocó en el modo Conectar, o -1. */
    val elegido = mutableIntStateOf(-1)
    /** El sol al que se acaba de volar desde el buscador, para marcarlo. */
    val elegidoParaVer = mutableIntStateOf(-1)
    val colorCuerda: Array<Color>

    init {
        val sitios = g.colocar(soles.map { it.id })
        soles.forEachIndexed { i, p ->
            indice[p.id] = i
            hojas[i] = p.hojas.count { it.padre == null }
            base[i] = Galaxia.diametro(hojas[i])
            val j = anterior?.indice?.get(p.id)?.takeIf { it < anterior.nS }
            esc[i] = if (j != null) anterior.esc[j] else g.tamanoDe(p.id)
            val s = if (j != null) PuntoDeGalaxia(anterior.fis.x[j], anterior.fis.y[j]) else sitios.getValue(p.id)
            fis.poner(i, s.x, s.y)
            fis.radio[i] = radio(i)
        }
        satelites.forEachIndexed { k, n ->
            val i = nS + k
            indice[n.id] = i
            val j = anterior?.indice?.get(n.id)?.takeIf { it >= anterior.nS }
            if (j != null) {
                fis.poner(i, anterior.fis.x[j], anterior.fis.y[j])
                medioAncho[k] = anterior.medioAncho[j - anterior.nS]
                medioAlto[k] = anterior.medioAlto[j - anterior.nS]
            } else {
                fis.poner(i, n.x, n.y)
            }
            solDe[k] = n.sol?.let { indice[it] }?.takeIf { it < nS } ?: -1
        }
        val enlaces = g.enlaces.filter { (indice[it.a] ?: nS) < nS && (indice[it.b] ?: nS) < nS }
        fis.ponerCuerdas(IntArray(enlaces.size * 2) {
            val e = enlaces[it / 2]
            indice.getValue(if (it % 2 == 0) e.a else e.b)
        })
        colorCuerda = Array(enlaces.size) { lerp(color[fis.cuerda(it, 0)], color[fis.cuerda(it, 1)], 0.5f) }
        val conSol = solDe.indices.filter { solDe[it] >= 0 }
        fis.ponerVarillas(IntArray(conSol.size * 2) { if (it % 2 == 0) solDe[conSol[it / 2]] else nS + conSol[it / 2] })
    }

    fun radio(i: Int) = base[i] * esc[i] / 2

    fun despertar() {
        despierto = true
    }

    /** Qué hay bajo (wx, wy): primero los exoplanetas, que van encima; -1 si nada. */
    fun tocar(wx: Float, wy: Float, minimo: Float): Int {
        for (k in satelites.indices.reversed()) {
            val i = nS + k
            if (abs(fis.x[i] - wx) <= max(medioAncho[k], minimo) && abs(fis.y[i] - wy) <= max(medioAlto[k], minimo)) return i
        }
        for (i in (0 until nS).reversed()) {
            val dx = fis.x[i] - wx
            val dy = fis.y[i] - wy
            val r = max(radio(i), minimo)
            if (dx * dx + dy * dy <= r * r) return i
        }
        return -1
    }

    /** El sol cuyo imán alcanza (wx, wy), el más cercano a su borde; -1 si ninguno. */
    fun solCercano(wx: Float, wy: Float): Int {
        var mejor = -1
        var mejorD = Float.MAX_VALUE
        for (i in 0 until nS) {
            val dx = fis.x[i] - wx
            val dy = fis.y[i] - wy
            val d = sqrt(dx * dx + dy * dy)
            if (d <= Galaxia.alcanceDelIman(radio(i)) && d - radio(i) < mejorD) {
                mejor = i
                mejorD = d - radio(i)
            }
        }
        return mejor
    }
}

/**
 * Dónde se mira: pantalla = mundo·dp·escala + (x, y).
 *
 * Estados sueltos y no un objeto inmutable: cada uno se lee en la capa que lo necesita y
 * cambiarlo no recompone nada.
 */
@Stable
internal class Camara {
    val x: MutableFloatState = mutableFloatStateOf(0f)
    val y: MutableFloatState = mutableFloatStateOf(0f)
    val escala: MutableFloatState = mutableFloatStateOf(1f)
    var ancho = 0f
    var alto = 0f
    var puesta = false

    fun mover(centro: Offset, pan: Offset, zoom: Float) {
        val antes = escala.floatValue
        val ahora = (antes * zoom).coerceIn(ESCALA_MIN, ESCALA_MAX)
        val f = ahora / antes
        // El punto bajo los dedos se queda bajo los dedos.
        x.floatValue = centro.x - (centro.x - x.floatValue) * f + pan.x
        y.floatValue = centro.y - (centro.y - y.floatValue) * f + pan.y
        escala.floatValue = ahora
    }

    fun alMundo(sx: Float, sy: Float, d: Float): Pair<Float, Float> {
        val e = escala.floatValue * d
        return (sx - x.floatValue) / e to (sy - y.floatValue) / e
    }

    /** Lleva la cámara, con una transición corta, a que se vea todo [puntos]. */
    suspend fun encuadrar(puntos: List<PuntoDeGalaxia>, d: Float) {
        if (puntos.isEmpty() || ancho <= 0f) return
        val margen = 130f
        val x0 = puntos.minOf { it.x } - margen
        val x1 = puntos.maxOf { it.x } + margen
        val y0 = puntos.minOf { it.y } - margen
        val y1 = puntos.maxOf { it.y } + margen
        val meta = min(ancho / ((x1 - x0) * d), alto / ((y1 - y0) * d)).coerceIn(ESCALA_MIN, 1.4f)
        volarA((x0 + x1) / 2, (y0 + y1) / 2, d, meta)
    }

    /** Pone (wx, wy) en el centro de la pantalla a la escala [meta], con una transición corta. */
    suspend fun volarA(wx: Float, wy: Float, d: Float, meta: Float) {
        val mx = ancho / 2 - wx * d * meta
        val my = alto / 2 - wy * d * meta
        val ax = x.floatValue
        val ay = y.floatValue
        val ae = escala.floatValue
        animate(0f, 1f, animationSpec = tween(450)) { t, _ ->
            x.floatValue = ax + (mx - ax) * t
            y.floatValue = ay + (my - ay) * t
            escala.floatValue = ae + (meta - ae) * t
        }
    }
}

internal const val ESCALA_MIN = 0.12f
internal const val ESCALA_MAX = 3f

/**
 * **El cielo**: estrellas en baldosas que se repiten, con paralaje —se mueven a un tercio de la
 * velocidad de los soles— y sin escalar, así que por mucho que se aleje no se vuelven una
 * sopa. Cada baldosa es la misma lista de puntos ya hecha: pintar no reserva memoria.
 */
internal fun DrawScope.dibujarCielo(camara: Camara, d: Float) {
    val lado = LADO_DEL_CIELO * d
    val ox = camara.x.floatValue * PARALAJE
    val oy = camara.y.floatValue * PARALAJE
    // Las dos nebulosas, fijas en el mundo pero con más paralaje.
    val nx = camara.x.floatValue * 0.6f
    val ny = camara.y.floatValue * 0.6f
    drawCircle(
        Brush.radialGradient(listOf(Color(0x335B3FD9), Color.Transparent), Offset(nx - 200 * d, ny - 120 * d), 420 * d),
        radius = 420 * d, center = Offset(nx - 200 * d, ny - 120 * d)
    )
    drawCircle(
        Brush.radialGradient(listOf(Color(0x2A1FA2C9), Color.Transparent), Offset(nx + 260 * d, ny + 220 * d), 360 * d),
        radius = 360 * d, center = Offset(nx + 260 * d, ny + 220 * d)
    )
    val i0 = kotlin.math.floor(-ox / lado).toInt()
    val j0 = kotlin.math.floor(-oy / lado).toInt()
    val i1 = i0 + (size.width / lado).toInt() + 1
    val j1 = j0 + (size.height / lado).toInt() + 1
    for (i in i0..i1) for (j in j0..j1) {
        translate(ox + i * lado, oy + j * lado) {
            for (k in ESTRELLAS.indices) {
                drawPoints(
                    ESTRELLAS[k].escalados(d), PointMode.Points, BRILLOS[k],
                    strokeWidth = (k + 1) * 1.1f * d, cap = StrokeCap.Round
                )
            }
        }
    }
}

private val TRAZO_PUNTEADO = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))

/** Un solo camino para las conexiones flojas: se rehace, no se crea. Solo lo usa el hilo de la pantalla. */
private val CAMINO = Path()

/**
 * **Las conexiones y las órbitas**, en el fondo. Una conexión floja **cuelga** —una curva que
 * se tensa al estirarse—, que es lo que dice sin palabras que tira. Una órbita es un anillo
 * tenue y una raya punteada al exoplaneta.
 */
private fun DrawScope.dibujarLazos(m: Mundo, camara: Camara, d: Float) {
    m.frame.longValue
    val esc = camara.escala.floatValue
    val e = esc * d
    val cx = camara.x.floatValue
    val cy = camara.y.floatValue
    val fis = m.fis
    val fino = (1.2f * d).coerceAtLeast(1f)
    // Órbitas
    for (k in 0 until fis.cuantasVarillas) {
        val s = fis.varilla(k, 0)
        val p = fis.varilla(k, 1)
        val centro = Offset(fis.x[s] * e + cx, fis.y[s] * e + cy)
        val c = m.color[s]
        drawCircle(c.copy(alpha = 0.10f), fis.largoDeVarilla(k) * e, centro, style = Stroke(fino))
        drawLine(c.copy(alpha = 0.35f), centro, Offset(fis.x[p] * e + cx, fis.y[p] * e + cy), fino, pathEffect = TRAZO_PUNTEADO)
    }
    // Conexiones
    val grueso = 2.6f * d * esc.coerceIn(0.5f, 1.5f)
    for (k in 0 until fis.cuantasCuerdas) {
        val a = fis.cuerda(k, 0)
        val b = fis.cuerda(k, 1)
        val pa = Offset(fis.x[a] * e + cx, fis.y[a] * e + cy)
        val pb = Offset(fis.x[b] * e + cx, fis.y[b] * e + cy)
        val dx = fis.x[b] - fis.x[a]
        val dy = fis.y[b] - fis.y[a]
        val flojo = fis.largoDeCuerda(k) - sqrt(dx * dx + dy * dy)
        val color = m.colorCuerda[k]
        if (flojo < 1f) {
            // Tensa: recta y más brillante, con su halo.
            drawLine(color.copy(alpha = 0.2f), pa, pb, grueso * 3.5f, StrokeCap.Round)
            drawLine(color.copy(alpha = 0.9f), pa, pb, grueso, StrokeCap.Round)
        } else {
            // Floja: cuelga hacia abajo de la pantalla, más cuanto más floja.
            val caida = min(flojo * 0.6f, 160f) * e
            CAMINO.reset()
            CAMINO.moveTo(pa.x, pa.y)
            CAMINO.quadraticTo((pa.x + pb.x) / 2, (pa.y + pb.y) / 2 + caida, pb.x, pb.y)
            drawPath(CAMINO, color.copy(alpha = 0.16f), style = Stroke(grueso * 3.5f, cap = StrokeCap.Round))
            drawPath(CAMINO, color.copy(alpha = 0.7f), style = Stroke(grueso * 0.8f, cap = StrokeCap.Round))
        }
    }
    // El imán que va a enganchar lo que se arrastra.
    val iman = m.iman.intValue
    if (iman >= 0) {
        drawCircle(
            m.color[iman].copy(alpha = 0.6f), Galaxia.alcanceDelIman(m.radio(iman)) * e,
            Offset(fis.x[iman] * e + cx, fis.y[iman] * e + cy),
            style = Stroke(2f * d, pathEffect = TRAZO_PUNTEADO)
        )
    }
    // Lo elegido en Conectar, y lo buscado.
    for (marcado in intArrayOf(m.elegido.intValue, m.elegidoParaVer.intValue)) {
        if (marcado < 0 || marcado >= fis.n) continue
        val r = if (marcado < m.nS) m.radio(marcado) + 14f
        else max(m.medioAncho[marcado - m.nS], m.medioAlto[marcado - m.nS]) + 12f
        drawCircle(
            Color.White.copy(alpha = 0.9f), r * e,
            Offset(fis.x[marcado] * e + cx, fis.y[marcado] * e + cy),
            style = Stroke(2.5f * d)
        )
    }
}

private const val LADO_DEL_CIELO = 360f
private const val PARALAJE = 0.35f
private val BRILLOS = listOf(Color(0x66FFFFFF), Color(0x99DCE6FF), Color(0xDDFFF4D6))

/** Tres tamaños de estrella, en dp de una baldosa; de menos a más brillantes y escasas. */
private val ESTRELLAS: List<Estrellas> = Random(7).let { r ->
    listOf(70, 22, 7).map { n -> Estrellas(List(n) { Offset(r.nextFloat() * LADO_DEL_CIELO, r.nextFloat() * LADO_DEL_CIELO) }) }
}

/** Las estrellas de un tamaño, y su copia en píxeles para la densidad de la pantalla. */
private class Estrellas(val enDp: List<Offset>) {
    private var d = 0f
    private var px: List<Offset> = emptyList()
    fun escalados(densidad: Float): List<Offset> {
        if (densidad != d) {
            px = enDp.map { it * densidad }
            d = densidad
        }
        return px
    }
}

/** El color de un sol: fijo por proyecto. */
internal fun colorDelSol(id: String): Color = COLORES_DE_SOL[Math.floorMod(id.hashCode(), COLORES_DE_SOL.size)]

private val COLORES_DE_SOL = listOf(
    Color(0xFFFFB84D), Color(0xFFFF7A6B), Color(0xFF7C9CFF), Color(0xFF5FE0C2),
    Color(0xFFB48CFF), Color(0xFF6FD3FF), Color(0xFFFF8FB1), Color(0xFFFFE066)
)

/** Los fondos de las notas: suaves, para que el texto oscuro se lea. */
private val COLORES_DE_NOTA = listOf(
    Color(0xFFFFF1B8), Color(0xFFFFD6E0), Color(0xFFCDEBFF), Color(0xFFD4F5DC), Color(0xFFE5DBFF)
)

/** Lo ancho que es la etiqueta bajo un sol, en dp. */
private const val ANCHO_DE_LA_ETIQUETA = 150f

/** Un sol: la portada del proyecto, su corona y su nombre. */
@Composable
private fun Sol(m: Mundo, i: Int, camara: Camara, densidad: Float) {
    val p = m.soles[i]
    val diametro = m.base[i]
    val color = m.color[i]
    val apagado = p.archivado
    val ancho = max(ANCHO_DE_LA_ETIQUETA, diametro)

    // **La portada solo cuando se ve**, en pantalla y con tamaño para distinguirla. Una vez
    // pedida se queda: alejarse y volver no la carga otra vez.
    val seVe by remember(m, i) {
        derivedStateOf {
            m.frame.longValue
            val e = camara.escala.floatValue * densidad
            val r = m.radio(i) * e
            val cx = m.fis.x[i] * e + camara.x.floatValue
            val cy = m.fis.y[i] * e + camara.y.floatValue
            r * 2 >= 36f && cx + r > 0 && cy + r > 0 && cx - r < camara.ancho && cy - r < camara.alto
        }
    }
    val conPortada = remember { mutableStateOf(false) }
    if (seVe && !conPortada.value) conPortada.value = true

    Column(
        Modifier
            .requiredWidth(ancho.dp)
            .graphicsLayer {
                m.frame.longValue
                val e = camara.escala.floatValue
                val s = e * m.esc[i]
                val sx = m.fis.x[i] * densidad * e + camara.x.floatValue
                val sy = m.fis.y[i] * densidad * e + camara.y.floatValue
                transformOrigin = TransformOrigin(0.5f, diametro * densidad / 2 / size.height)
                scaleX = s
                scaleY = s
                translationX = sx - size.width / 2
                translationY = sy - diametro / 2 * densidad
                // Fuera de la pantalla, la capa no se pinta.
                val r = max(size.height, size.width) * s
                alpha = if (sx + r < 0 || sy + r < 0 || sx - r > camara.ancho || sy - r > camara.alto) 0f else 1f
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .requiredSize(diametro.dp)
                .drawBehind {
                    // La corona: un resplandor ancho del color y otro cálido pegado.
                    val r = size.minDimension / 2
                    drawCircle(
                        Brush.radialGradient(
                            listOf(color.copy(alpha = if (apagado) 0.10f else 0.40f), Color.Transparent),
                            center, r * 2.1f
                        ),
                        radius = r * 2.1f
                    )
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = if (apagado) 0.05f else 0.35f), color.copy(alpha = 0.25f), Color.Transparent),
                            center, r * 1.3f
                        ),
                        radius = r * 1.3f
                    )
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            lerp(color, Color.White, 0.35f).copy(alpha = if (apagado) 0.35f else 1f),
                            color.copy(alpha = if (apagado) 0.3f else 1f),
                            lerp(color, Color.Black, 0.45f)
                        )
                    )
                )
                .border(2.dp, lerp(color, Color.White, 0.5f).copy(alpha = if (apagado) 0.35f else 0.95f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (conPortada.value && m.hojas[i] > 0) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .graphicsLayer { alpha = if (apagado) 0.45f else 0.95f }
                ) {
                    PortadaDePlaneta(p)
                }
            } else {
                Text(
                    p.nombre.trim().take(1).uppercase(),
                    color = Color.White,
                    fontSize = (diametro * 0.38f).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            p.nombre,
            color = Color.White.copy(alpha = if (apagado) 0.5f else 0.96f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        val h = m.hojas[i]
        Text(
            if (apagado) "archivado · $h" else "$h ${if (h == 1) "hoja" else "hojas"}",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** Un exoplaneta: una nota o un emoji. */
@Composable
private fun Satelite(m: Mundo, k: Int, nota: NotaDeGalaxia, camara: Camara, densidad: Float) {
    val i = m.nS + k
    Box(
        Modifier
            .onSizeChanged {
                m.medioAncho[k] = it.width / 2f / densidad
                m.medioAlto[k] = it.height / 2f / densidad
            }
            .graphicsLayer {
                m.frame.longValue
                val e = camara.escala.floatValue
                transformOrigin = TransformOrigin.Center
                scaleX = e
                scaleY = e
                translationX = m.fis.x[i] * densidad * e + camara.x.floatValue - size.width / 2
                translationY = m.fis.y[i] * densidad * e + camara.y.floatValue - size.height / 2
            }
    ) {
        val emoji = nota.emoji
        if (emoji != null) {
            Text(emoji, fontSize = 34.sp, modifier = Modifier.padding(4.dp))
        } else {
            Text(
                nota.texto,
                color = Color(0xFF2B2600),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 180.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(COLORES_DE_NOTA[nota.color.coerceIn(0, COLORES_DE_NOTA.lastIndex)])
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun BotonDeModo(modo: ModoDeGalaxia, puesto: Boolean, onClick: () -> Unit) {
    BotonDeBarra(modo.icono, modo.titulo, onClick, puesto = puesto)
}

@Composable
private fun Buscador(proyectos: List<Proyecto>, modifier: Modifier, onElegir: (Proyecto) -> Unit) {
    var texto by remember { mutableStateOf("") }
    val hallados = remember(texto, proyectos) {
        val t = sinTildes(texto.trim())
        if (t.isEmpty()) proyectos.take(6) else proyectos.filter { sinTildes(it.nombre).contains(t) }.take(6)
    }
    Column(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp)
    ) {
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it },
            singleLine = true,
            placeholder = { Text("Buscar un proyecto…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        if (hallados.isEmpty()) {
            Text(
                "Ningún proyecto se llama así",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }
        LazyColumn(Modifier.heightIn(max = 260.dp)) {
            items(hallados, key = { it.id }) { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onElegir(p) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(colorDelSol(p.id)))
                    Text(p.nombre, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}

private val MARCAS = Regex("\\p{Mn}+")

private fun sinTildes(s: String) =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(MARCAS, "")

@Composable
private fun MenuDelSol(
    p: Proyecto,
    agrandado: Boolean,
    conexiones: Int,
    onCerrar: () -> Unit,
    onChat: () -> Unit,
    onVer: () -> Unit,
    onNota: () -> Unit,
    onEmoji: () -> Unit,
    onTamanoNormal: () -> Unit,
    onQuitarConexiones: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(p.nombre, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                OpcionDelMenu(Icons.AutoMirrored.Filled.Chat, "Abrir el chat", onChat)
                OpcionDelMenu(Icons.Filled.ViewCarousel, "Ver en la lista de proyectos", onVer)
                OpcionDelMenu(Icons.Filled.EditNote, "Añadir una nota a su órbita", onNota)
                OpcionDelMenu(Icons.Filled.EmojiEmotions, "Añadir un emoji a su órbita", onEmoji)
                if (agrandado) OpcionDelMenu(Icons.Filled.Restore, "Volver a su tamaño normal", onTamanoNormal)
                if (conexiones > 0) {
                    OpcionDelMenu(
                        Icons.Filled.LinkOff,
                        if (conexiones == 1) "Quitar su conexión" else "Quitar sus $conexiones conexiones",
                        onQuitarConexiones
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } }
    )
}

@Composable
private fun OpcionDelMenu(icono: ImageVector, texto: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icono, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(texto, modifier = Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DialogoDeNotaDeGalaxia(
    nota: NotaDeGalaxia,
    existe: Boolean,
    onCerrar: () -> Unit,
    onGuardar: (String, Int) -> Unit,
    onQuitar: () -> Unit
) {
    var texto by remember(nota.id) { mutableStateOf(nota.texto) }
    var color by remember(nota.id) { mutableIntStateOf(nota.color) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (existe) "Nota" else "Nueva nota") },
        text = {
            Column {
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it },
                    placeholder = { Text("Escribe algo sobre tus proyectos…") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    COLORES_DE_NOTA.forEachIndexed { k, c ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    if (k == color) 3.dp else 1.dp,
                                    if (k == color) MaterialTheme.colorScheme.primary else Color(0x33000000),
                                    CircleShape
                                )
                                .clickable { color = k }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onGuardar(texto, color) }) { Text("Guardar") } },
        dismissButton = {
            Row {
                if (existe) TextButton(onClick = onQuitar) { Text("Quitar") }
                TextButton(onClick = onCerrar) { Text("Cancelar") }
            }
        }
    )
}

private val EMOJIS = listOf(
    "⭐", "🌟", "💡", "🔥", "✅", "❗",
    "❓", "📌", "📅", "⏰", "🎯", "🚀",
    "💬", "📝", "📐", "🏗️", "🎨", "💰",
    "👥", "🔒", "❤️", "😀", "🤔", "⚠️"
)

@Composable
private fun DialogoDeEmoji(existe: Boolean, onCerrar: () -> Unit, onElegir: (String) -> Unit, onQuitar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (existe) "Cambiar el emoji" else "Elige un emoji") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (fila in EMOJIS.chunked(6)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (e in fila) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).clickable { onElegir(e) },
                                contentAlignment = Alignment.Center
                            ) { Text(e, fontSize = 26.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
        dismissButton = { if (existe) TextButton(onClick = onQuitar) { Text("Quitar") } }
    )
}
