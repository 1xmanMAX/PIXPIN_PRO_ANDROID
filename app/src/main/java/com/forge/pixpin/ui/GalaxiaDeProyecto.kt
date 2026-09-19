package com.forge.pixpin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.ui.theme.BotonRedondo
import com.forge.pixpin.motor.HojasDelProyecto
import com.forge.pixpin.motor.Proyecto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **La galaxia de un proyecto** (17-sep-2026): se toca un sol de [PantallaDeGalaxia] y se entra
 * en la suya. Aquí el proyecto **es la galaxia** y sus hojas son los satélites que le orbitan; una
 * hoja que cuelga de otra —un sublienzo— orbita **a su alrededor**, y la órbita se ve, que es lo
 * que enseña de qué está colgada. Lo pidió el usuario así.
 *
 * - Arrastrar el fondo pasea, pellizcar acerca.
 * - Arrastrar un satélite lo cambia de sitio y ahí se queda: **la órbita es la que tú dejes**,
 *   de radio y de ángulo. Aquí solo se ve este proyecto, así que alejarlos no confunde con nada.
 * - Tocarlo abre esa hoja: el lienzo, la nota, la tabla o el croquis.
 *
 * Las posiciones se guardan en el mismo archivo que la galaxia grande, con la clave
 * `proyecto/hoja` ([claveDeSatelite]). La física es la misma de siempre: varillas de la hoja a
 * aquello de lo que cuelga, y a dormir en cuanto se para. Ver [FisicaDeGalaxia].
 */
@Composable
fun GalaxiaDelProyecto(
    app: PixPinApp,
    proyecto: Proyecto,
    galaxia: Galaxia,
    onGuardar: (Galaxia) -> Unit,
    onVolver: () -> Unit,
    onChat: () -> Unit
) {
    BackHandler(onBack = onVolver)
    val contexto = LocalContext.current
    val densidad = LocalDensity.current.density
    val alcance = rememberCoroutineScope()
    val camara = remember { Camara() }

    // Las hojas del proyecto y de qué cuelga cada una.
    val hojas = remember(proyecto) { proyecto.hojas }
    val paginas = remember(proyecto) { HojasDelProyecto.paginas(proyecto) { null } }
    val mundo = remember(proyecto, galaxia.posiciones) { MundoDelProyecto(proyecto, galaxia) }

    // La física, solo mientras algo se mueve.
    LaunchedEffect(mundo) {
        while (true) {
            snapshotFlow { mundo.despierto }.first { it }
            var quietos = 0
            var antes = 0L
            while (mundo.despierto) {
                withFrameNanos { t ->
                    val pasos = if (antes == 0L) 1 else ((t - antes) / 16_666_667f).roundToInt().coerceIn(1, 3)
                    antes = t
                    var v = 0f
                    repeat(pasos) { v = max(v, mundo.fis.paso()) }
                    mundo.frame.longValue++
                    quietos = if (mundo.arrastrando < 0 && v < FisicaDeGalaxia.QUIETO) quietos + 1 else 0
                }
                if (quietos > 20) {
                    mundo.despierto = false
                    mundo.fis.asentar()
                    val sitios = hojas.indices.associate { i ->
                        claveDeSatelite(proyecto.id, hojas[i].id) to PuntoDeGalaxia(mundo.fis.x[i + 1], mundo.fis.y[i + 1])
                    }
                    val nueva = galaxia.copy(posiciones = galaxia.posiciones + sitios)
                    if (nueva != galaxia) onGuardar(nueva)
                }
            }
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
            .pointerInput(mundo) {
                awaitEachGesture {
                    val abajo = awaitFirstDown(requireUnconsumed = false)
                    val (w0x, w0y) = camara.alMundo(abajo.position.x, abajo.position.y, densidad)
                    val tocado = mundo.tocar(w0x, w0y, DEDO_MINIMO / camara.escala.floatValue)
                    var clase = 0 // 0 sin decidir · 1 arrastrando · 2 paseando · 3 dos dedos
                    var agarreX = 0f
                    var agarreY = 0f
                    while (true) {
                        val evento = awaitPointerEvent()
                        val activos = evento.changes.filter { it.pressed }
                        if (activos.isEmpty()) {
                            if (clase == 0 && tocado > 0) {
                                val h = hojas.getOrNull(tocado - 1)
                                val pagina = h?.let { hoja -> paginas.firstOrNull { it.hoja.id == hoja.id } }
                                if (pagina != null) abrirHoja(contexto, app, proyecto, pagina)
                            } else if (clase == 0 && tocado == 0) {
                                onChat()
                            }
                            break
                        }
                        if (activos.size >= 2) {
                            if (clase == 1) { mundo.fis.reajustarVarillasDe(tocado); mundo.fis.soltar(tocado); mundo.arrastrando = -1 }
                            clase = 3
                            camara.mover(evento.calculateCentroid(), evento.calculatePan(), evento.calculateZoom())
                            evento.changes.forEach { it.consume() }
                            continue
                        }
                        val dedo = activos.first()
                        if (clase == 0 && (dedo.position - abajo.position).getDistance() > viewConfiguration.touchSlop) {
                            if (tocado >= 0) {
                                clase = 1
                                agarreX = mundo.fis.x[tocado] - w0x
                                agarreY = mundo.fis.y[tocado] - w0y
                                mundo.fis.sujetar(tocado)
                                mundo.arrastrando = tocado
                                mundo.despierto = true
                            } else {
                                clase = 2
                            }
                        }
                        if (clase == 1) {
                            val (wx, wy) = camara.alMundo(dedo.position.x, dedo.position.y, densidad)
                            mundo.fis.llevar(tocado, wx + agarreX, wy + agarreY)
                        } else if (clase == 2 || clase == 3) {
                            camara.mover(dedo.position, dedo.position - dedo.previousPosition, 1f)
                        }
                        evento.changes.forEach { it.consume() }
                    }
                    if (clase == 1) {
                        // **La órbita es la que tú dejas** (18-sep-2026): al soltar un satélite,
                        // su distancia al sol —o a la hoja de la que cuelga— pasa a ser esa. Sin
                        // esto volvía al radio de fábrica y no había forma de abrir la galaxia.
                        mundo.fis.reajustarVarillasDe(tocado)
                        // **Dos en la misma órbita** (18-sep-2026): si se suelta cerca de la
                        // órbita de otro satélite del mismo sol, se pega a ella y comparten el
                        // anillo, que es lo que enseña que van juntos.
                        mundo.pegarALaOrbitaVecina(tocado)
                        mundo.fis.soltar(tocado)
                        mundo.arrastrando = -1
                    }
                }
            }
            .drawBehind {
                dibujarCielo(camara, densidad)
                dibujarOrbitas(mundo, camara, densidad)
            }
    ) {
        // El sol: el proyecto.
        SolDelProyecto(mundo, proyecto, camara, densidad)
        // Los satélites: sus hojas.
        for (i in hojas.indices) {
            key(hojas[i].id) {
                Satelite(mundo, i + 1, hojas[i], proyecto, camara, densidad)
            }
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BotonRedondo(Icons.AutoMirrored.Filled.ArrowBack, "Volver a la galaxia", onVolver)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(proyecto.nombre, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${hojas.size} ${if (hojas.size == 1) "hoja" else "hojas"} en órbita",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            BotonRedondo(Icons.AutoMirrored.Filled.Chat, "Abrir el chat", onChat)
            Spacer(Modifier.width(6.dp))
            BotonRedondo(Icons.Filled.CenterFocusStrong, "Verlo todo", {
                val puntos = (0 until mundo.fis.n).map { PuntoDeGalaxia(mundo.fis.x[it], mundo.fis.y[it]) }
                alcance.launch { camara.encuadrar(puntos, densidad) }
            })
        }

        Text(
            "Toca una hoja para abrirla · arrástrala para moverla · las que cuelgan de otra la orbitan",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp, start = 16.dp, end = 16.dp)
        )
    }
}

/** La clave con la que se guarda dónde está una hoja en la galaxia de su proyecto. */
internal fun claveDeSatelite(proyecto: String, hoja: String) = "$proyecto/$hoja"

/**
 * El mundo de un proyecto: el sol en el cero y una hoja por cuerpo, con su varilla a aquello de
 * lo que cuelga —el proyecto, o la hoja padre si es un sublienzo—.
 */
private class MundoDelProyecto(val proyecto: Proyecto, galaxia: Galaxia) {
    val hojas = proyecto.hojas
    val fis = FisicaDeGalaxia(hojas.size + 1, 1)
    val frame = mutableLongStateOf(0L)
    var despierto by mutableStateOf(true)
    var arrastrando = -1
    val radioDelSol = Galaxia.diametro(hojas.count { it.padre == null }) / 2f
    val color = colorDelSol(proyecto.id)

    init {
        fis.poner(0, 0f, 0f)
        fis.radio[0] = radioDelSol
        val porId = hojas.withIndex().associate { (i, h) -> h.id to i + 1 }
        // De fábrica, en órbitas: las del proyecto alrededor del sol y las que cuelgan de otra,
        // alrededor de la suya. El ángulo áureo las reparte sin que se pisen.
        val hermanos = HashMap<String, Int>()
        for ((i, h) in hojas.withIndex()) {
            val padre = h.padre?.let { porId[it] } ?: 0
            val k = hermanos.merge(h.padre ?: "", 1) { a, b -> a + b }!! - 1
            val guardado = galaxia.posiciones[claveDeSatelite(proyecto.id, h.id)]
            if (guardado != null) {
                fis.poner(i + 1, guardado.x, guardado.y)
            } else {
                val centroX = if (padre == 0) 0f else fis.x[padre]
                val centroY = if (padre == 0) 0f else fis.y[padre]
                val r = (if (padre == 0) radioDelSol + 150f else 90f) + 34f * (k % 3)
                val a = k * Galaxia.ANGULO_AUREO - Math.PI / 2
                fis.poner(i + 1, centroX + (r * cos(a)).toFloat(), centroY + (r * sin(a)).toFloat())
            }
            fis.radio[i + 1] = RADIO_DEL_SATELITE
        }
        // Las varillas: cada hoja, atada a lo suyo.
        val pares = ArrayList<Int>(hojas.size * 2)
        for ((i, h) in hojas.withIndex()) {
            pares.add(h.padre?.let { porId[it] } ?: 0)
            pares.add(i + 1)
        }
        fis.ponerVarillas(pares.toIntArray())
    }

    /**
     * Si la órbita de [planeta] queda a menos de [ORBITA_PEGADA] de la de otro satélite del mismo
     * padre, se iguala a la suya. Devuelve si se pegó.
     */
    fun pegarALaOrbitaVecina(planeta: Int): Boolean {
        val mia = fis.varillaDe(planeta)
        if (mia < 0) return false
        val padre = fis.varilla(mia, 0)
        val largo = fis.largoDeVarilla(mia)
        var mejor = -1
        var mejorDiferencia = ORBITA_PEGADA
        for (k in 0 until fis.cuantasVarillas) {
            if (k == mia || fis.varilla(k, 0) != padre) continue
            val diferencia = abs(fis.largoDeVarilla(k) - largo)
            if (diferencia < mejorDiferencia) { mejor = k; mejorDiferencia = diferencia }
        }
        if (mejor < 0) return false
        fis.ponerLargoDeVarilla(mia, fis.largoDeVarilla(mejor))
        return true
    }

    fun tocar(wx: Float, wy: Float, minimo: Float): Int {
        for (i in (0 until fis.n).reversed()) {
            val dx = fis.x[i] - wx
            val dy = fis.y[i] - wy
            val r = max(if (i == 0) radioDelSol else RADIO_DEL_SATELITE, minimo)
            if (dx * dx + dy * dy <= r * r) return i
        }
        return -1
    }
}

/** Lo que mide un satélite, en dp del mundo. */
private const val RADIO_DEL_SATELITE = 34f

/** A menos de esto (dp) de la órbita de otro, un satélite se pega a ella. */
private const val ORBITA_PEGADA = 22f

private val PUNTEADO = PathEffect.dashPathEffect(floatArrayOf(9f, 9f))

/** Las órbitas: el anillo de cada satélite alrededor de lo suyo, y la raya que los une. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.dibujarOrbitas(
    m: MundoDelProyecto,
    camara: Camara,
    d: Float
) {
    m.frame.longValue
    val e = camara.escala.floatValue * d
    val cx = camara.x.floatValue
    val cy = camara.y.floatValue
    val fino = (1.2f * d).coerceAtLeast(1f)
    for (k in 0 until m.fis.cuantasVarillas) {
        val padre = m.fis.varilla(k, 0)
        val hijo = m.fis.varilla(k, 1)
        val centro = Offset(m.fis.x[padre] * e + cx, m.fis.y[padre] * e + cy)
        val suyo = Offset(m.fis.x[hijo] * e + cx, m.fis.y[hijo] * e + cy)
        // El anillo dice a qué distancia orbita; la raya, de quién cuelga.
        drawCircle(m.color.copy(alpha = if (padre == 0) 0.13f else 0.2f), m.fis.largoDeVarilla(k) * e, centro, style = Stroke(fino))
        drawLine(m.color.copy(alpha = 0.4f), centro, suyo, fino, StrokeCap.Round, PUNTEADO)
    }
}

@Composable
private fun SolDelProyecto(
    m: MundoDelProyecto,
    proyecto: Proyecto,
    camara: Camara,
    densidad: Float
) {
    val diametro = m.radioDelSol * 2
    val color = m.color
    Column(
        Modifier
            .requiredWidth(max(150f, diametro).dp)
            .graphicsLayer {
                m.frame.longValue
                val e = camara.escala.floatValue
                transformOrigin = TransformOrigin(0.5f, diametro * densidad / 2 / size.height)
                scaleX = e; scaleY = e
                translationX = m.fis.x[0] * densidad * e + camara.x.floatValue - size.width / 2
                translationY = m.fis.y[0] * densidad * e + camara.y.floatValue - diametro / 2 * densidad
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .requiredSize(diametro.dp)
                .drawBehind {
                    val r = size.minDimension / 2
                    drawCircle(
                        Brush.radialGradient(listOf(color.copy(alpha = 0.4f), Color.Transparent), center, r * 2.1f),
                        radius = r * 2.1f
                    )
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(lerp(color, Color.White, 0.35f), color, lerp(color, Color.Black, 0.45f))
                    )
                )
                .border(2.dp, lerp(color, Color.White, 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxSize().padding(5.dp).clip(CircleShape).background(Color.White)) {
                PortadaDePlaneta(proyecto)
            }
        }
        Text(
            proyecto.nombre,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun Satelite(
    m: MundoDelProyecto,
    i: Int,
    hoja: Hoja,
    proyecto: Proyecto,
    camara: Camara,
    densidad: Float
) {
    val diametro = RADIO_DEL_SATELITE * 2
    val color = remember(hoja.id) {
        val c = HojasDelProyecto.colorDe(hoja)
        if (c != null) Color(c) else colorDelSol(hoja.id)
    }
    Column(
        Modifier
            .requiredWidth(120.dp)
            .graphicsLayer {
                m.frame.longValue
                val e = camara.escala.floatValue
                transformOrigin = TransformOrigin(0.5f, diametro * densidad / 2 / size.height)
                scaleX = e; scaleY = e
                translationX = m.fis.x[i] * densidad * e + camara.x.floatValue - size.width / 2
                translationY = m.fis.y[i] * densidad * e + camara.y.floatValue - diametro / 2 * densidad
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .requiredSize(diametro.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(lerp(color, Color.White, 0.25f), lerp(color, Color.Black, 0.4f))))
                .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                etiquetaDeHoja(hoja),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            hoja.nombre.ifBlank { nombreDeHoja(hoja, proyecto) },
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Dos letras que dicen qué es la hoja, para verlo de un vistazo sin abrirla. */
private fun etiquetaDeHoja(h: Hoja): String = when {
    h.tabla != null -> "TAB"
    h.croquis != null -> "3D"
    h.nota != null -> "MD"
    h.pagina != null -> "${h.pagina!! + 1}"
    else -> "✎"
}

private fun nombreDeHoja(h: Hoja, p: Proyecto): String = when {
    h.pagina != null -> "Página ${h.pagina!! + 1}"
    h.tabla != null -> "Tabla"
    h.croquis != null -> "Croquis"
    h.nota != null -> "Nota"
    else -> "Lienzo"
}
