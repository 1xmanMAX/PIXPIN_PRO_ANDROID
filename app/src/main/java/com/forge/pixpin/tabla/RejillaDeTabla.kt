package com.forge.pixpin.tabla

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import com.forge.pixpin.motor.Celdas
import com.forge.pixpin.motor.ReferenciaEnFormula
import com.forge.pixpin.motor.TablaViva
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** El marco de una celda con fórmula: rojo apagado y fino, como en la página web. */
private val MARCO_DE_FORMULA = Color(0xFFD93025).copy(alpha = 0.55f)

/** Las celdas que se podrán cambiar en la página de una tabla protegida: ámbar, como en la web. */
private val TONO_EDITABLE = Color(0xFFFFC107).copy(alpha = 0.30f)

/** Los colores de las referencias, en orden de aparición: los mismos que en la página web. */
private val COLORES_DE_REFERENCIA = listOf(
    0xFF1A73E8, 0xFFD93025, 0xFF8E24AA, 0xFF188038, 0xFFE8710A, 0xFF0097A7, 0xFFC2185B, 0xFF795548
).map { Color(it) }

/** Un color por rango distinto; el mismo rango citado dos veces lleva el mismo color. */
internal fun conColores(refs: List<ReferenciaEnFormula>): List<Pair<ReferenciaEnFormula, Color>> {
    val vistos = HashMap<List<Int>, Int>()
    return refs.map { r ->
        val i = vistos.getOrPut(listOf(r.f1, r.c1, r.f2, r.c2)) { vistos.size }
        r to COLORES_DE_REFERENCIA[i % COLORES_DE_REFERENCIA.size]
    }
}

/** Medidas de la rejilla, en dp a zoom 1. */
internal const val ALTO_FILA = 30f
internal const val ALTO_CABECERA = 26f
internal const val ANCHO_CABECERA = 44f
private const val LARGO_MS = 420L

/**
 * **Dónde se mira y qué está elegido.** Todo en estado de Compose y leído **al pintar**, no al
 * componer: desplazar y ampliar repintan el lienzo y no recomponen nada (ver la memoria de
 * «recomponer contra pintar»).
 */
class EstadoDeRejilla {
    var desplX by mutableFloatStateOf(0f)
    var desplY by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)

    /** La celda activa y el ancla del rango: el rango va de una a otra. */
    var f by mutableIntStateOf(0)
    var c by mutableIntStateOf(0)
    var af by mutableIntStateOf(0)
    var ac by mutableIntStateOf(0)

    val f1: Int get() = minOf(f, af)
    val f2: Int get() = maxOf(f, af)
    val c1: Int get() = minOf(c, ac)
    val c2: Int get() = maxOf(c, ac)
    val esUna: Boolean get() = f == af && c == ac

    internal var ancho = 0f
    internal var alto = 0f
    internal var densidad = 1f
    internal val acum = FloatArray(Celdas.MAX_COLS + 1)
    internal var trabajo: Job? = null

    fun elegir(fila: Int, col: Int, extender: Boolean) {
        f = fila.coerceIn(0, Celdas.MAX_FILAS - 1)
        c = col.coerceIn(0, Celdas.MAX_COLS - 1)
        if (!extender) { af = f; ac = c }
    }

    fun mover(dx: Float, dy: Float) {
        desplX = (desplX + dx).coerceIn(0f, acum[Celdas.MAX_COLS] * densidad * zoom)
        desplY = (desplY + dy).coerceIn(0f, Celdas.MAX_FILAS * ALTO_FILA * densidad * zoom)
    }

    /** Amplía **alrededor del centro de los dedos**: lo que hay debajo no se mueve. */
    fun ampliar(factor: Float, centro: Offset) {
        val d = densidad
        val viejo = zoom
        val nuevo = (zoom * factor).coerceIn(0.4f, 3f)
        if (nuevo == viejo) return
        val k = nuevo / viejo
        desplX = ((desplX + centro.x - ANCHO_CABECERA * d * viejo) * k - (centro.x - ANCHO_CABECERA * d * nuevo)).coerceAtLeast(0f)
        desplY = ((desplY + centro.y - ALTO_CABECERA * d * viejo) * k - (centro.y - ALTO_CABECERA * d * nuevo)).coerceAtLeast(0f)
        zoom = nuevo
    }

    /** Desplaza lo justo para que la celda activa se vea. */
    fun verActiva() {
        if (ancho <= 0f) return
        val d = densidad * zoom
        val cabW = ANCHO_CABECERA * d
        val cabH = ALTO_CABECERA * d
        val x0 = acum[c] * d
        val x1 = acum[c + 1] * d
        val y0 = f * ALTO_FILA * d
        val y1 = y0 + ALTO_FILA * d
        if (x0 < desplX) desplX = x0
        else if (x1 > desplX + ancho - cabW) desplX = x1 - (ancho - cabW)
        if (y0 < desplY) desplY = y0
        else if (y1 > desplY + alto - cabH) desplY = y1 - (alto - cabH)
    }
}

/** Lo que se ve de cada celda, recordado hasta que cambie la tabla: pintar no recalcula. */
private class Memoria {
    var version = -1
    val textos = HashMap<Int, String>()
    val alineaciones = HashMap<Int, Char>()
    val errores = HashSet<Int>()

    fun preparar(t: TablaViva, v: Int, e: EstadoDeRejilla) {
        if (v == version) return
        version = v
        textos.clear(); alineaciones.clear(); errores.clear()
        e.acum[0] = 0f
        for (c in 0 until Celdas.MAX_COLS) e.acum[c + 1] = e.acum[c] + t.ancho(c)
    }

    fun texto(t: TablaViva, f: Int, c: Int): String {
        val k = Celdas.clave(f, c)
        textos[k]?.let { return it }
        val s = if (t.calc.crudo(f, c).isEmpty()) "" else t.calc.texto(f, c).replace('\n', ' ')
        if (s.isNotEmpty()) {
            alineaciones[k] = t.calc.alineacion(f, c)
            if (t.calc.valor(f, c) is com.forge.pixpin.motor.Valor.Err) errores += k
        }
        textos[k] = s
        return s
    }
}

private fun columnaEn(acum: FloatArray, xDp: Float): Int {
    var lo = 0
    var hi = Celdas.MAX_COLS - 1
    while (lo < hi) {
        val m = (lo + hi + 1) / 2
        if (acum[m] <= xDp) lo = m else hi = m - 1
    }
    return lo
}

private class Zona(val tipo: Int, val f: Int, val c: Int, val borde: Int = -1) {
    companion object {
        const val CELDA = 0
        const val COLUMNA = 1
        const val FILA = 2
        const val ESQUINA = 3
    }
}

/**
 * **La rejilla**: solo se pinta lo que cabe en pantalla, en un único lienzo. Una tabla de diez
 * mil filas cuesta lo mismo de mover que una de diez.
 *
 * Con el dedo: arrastrar desplaza, dos dedos amplían, **tocar elige**, dos toques editan,
 * **mantener y arrastrar elige un rango**, tocar una letra o un número elige la columna o la
 * fila, y arrastrar el borde de una letra cambia el ancho.
 */
@Composable
fun RejillaDeTabla(
    tabla: TablaViva,
    version: Int,
    estado: EstadoDeRejilla,
    modifier: Modifier = Modifier,
    onToque: (Int, Int) -> Unit,
    onDobleToque: (Int, Int) -> Unit,
    onLargo: (Int, Int) -> Unit,
    onArrastre: (Int, Int) -> Unit,
    onColumna: (Int) -> Unit,
    onFila: (Int) -> Unit,
    onAncho: (Int, Int) -> Unit,
    /** Las celdas que cita la fórmula que se está escribiendo, con su color. Ver [conColores]. */
    marcas: List<Pair<ReferenciaEnFormula, Color>> = emptyList()
) {
    val colores = MaterialTheme.colorScheme
    val densidad = LocalDensity.current
    val memoria = remember { Memoria() }
    val pintura = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val negrita = remember { Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val alcance = rememberCoroutineScope()
    val toque by rememberUpdatedState(onToque)
    val doble by rememberUpdatedState(onDobleToque)
    val largo by rememberUpdatedState(onLargo)
    val arrastre by rememberUpdatedState(onArrastre)
    val columna by rememberUpdatedState(onColumna)
    val fila by rememberUpdatedState(onFila)
    val ancho by rememberUpdatedState(onAncho)
    val tablaActual by rememberUpdatedState(tabla)
    estado.densidad = densidad.density

    fun zonaEn(p: Offset): Zona {
        val d = estado.densidad * estado.zoom
        val cabW = ANCHO_CABECERA * d
        val cabH = ALTO_CABECERA * d
        val xDp = (estado.desplX + p.x - cabW) / d
        val c = columnaEn(estado.acum, xDp.coerceAtLeast(0f))
        val f = ((estado.desplY + p.y - cabH) / (ALTO_FILA * d)).toInt().coerceIn(0, Celdas.MAX_FILAS - 1)
        return when {
            p.x < cabW && p.y < cabH -> Zona(Zona.ESQUINA, 0, 0)
            p.y < cabH -> {
                val margen = 14f * estado.densidad
                val borde = when {
                    abs(estado.acum[c + 1] * d - (xDp * d)) < margen -> c
                    c > 0 && abs(estado.acum[c] * d - (xDp * d)) < margen -> c - 1
                    else -> -1
                }
                Zona(Zona.COLUMNA, 0, c, borde)
            }
            p.x < cabW -> Zona(Zona.FILA, f, 0)
            else -> Zona(Zona.CELDA, f, c)
        }
    }

    fun lanzar(vx: Float, vy: Float) {
        estado.trabajo?.cancel()
        estado.trabajo = alcance.launch {
            launch {
                var antes = 0f
                AnimationState(0f, -vx).animateDecay(exponentialDecay()) { estado.mover(value - antes, 0f); antes = value }
            }
            launch {
                var antes = 0f
                AnimationState(0f, -vy).animateDecay(exponentialDecay()) { estado.mover(0f, value - antes); antes = value }
            }
        }
    }

    val entrada = Modifier.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        var ultimoToque = 0L
        var ultimaCelda = -1
        awaitEachGesture {
            val primero = awaitFirstDown(requireUnconsumed = false)
            estado.trabajo?.cancel()
            val inicio = primero.position
            val zona = zonaEn(inicio)
            var modo = 0 // 0 pendiente, 1 desplazar, 2 pellizcar, 3 elegir rango, 4 ancho
            val vt = VelocityTracker()
            vt.addPosition(primero.uptimeMillis, inicio)
            val anchoInicial = if (zona.borde >= 0) tablaActual.ancho(zona.borde) else 0
            val limite = primero.uptimeMillis + LARGO_MS
            var ahora = primero.uptimeMillis
            while (true) {
                val ev = if (modo == 0 && zona.tipo == Zona.CELDA) {
                    withTimeoutOrNull((limite - ahora).coerceAtLeast(1L)) { awaitPointerEvent() }
                } else awaitPointerEvent()
                if (ev == null) {
                    modo = 3
                    largo(zona.f, zona.c)
                    continue
                }
                if (ev.type == PointerEventType.Scroll) {
                    val s = ev.changes.first().scrollDelta
                    estado.mover(s.x * 60f, s.y * 60f)
                    ev.changes.forEach { it.consume() }
                    continue
                }
                val cambios = ev.changes
                val principal = cambios.firstOrNull { it.id == primero.id } ?: cambios.first()
                ahora = principal.uptimeMillis
                val apretados = cambios.count { it.pressed }
                if (apretados == 0) {
                    when (modo) {
                        0 -> when (zona.tipo) {
                            Zona.CELDA -> {
                                val k = Celdas.clave(zona.f, zona.c)
                                if (ahora - ultimoToque < 320 && ultimaCelda == k) {
                                    ultimoToque = 0L
                                    doble(zona.f, zona.c)
                                } else {
                                    ultimoToque = ahora
                                    ultimaCelda = k
                                    toque(zona.f, zona.c)
                                }
                            }
                            Zona.COLUMNA -> columna(zona.c)
                            Zona.FILA -> fila(zona.f)
                        }
                        1 -> {
                            val v = vt.calculateVelocity()
                            lanzar(v.x, v.y)
                        }
                    }
                    break
                }
                if (apretados >= 2 && (modo == 0 || modo == 1)) modo = 2
                when (modo) {
                    0 -> if ((principal.position - inicio).getDistance() > slop) {
                        modo = if (zona.tipo == Zona.COLUMNA && zona.borde >= 0) 4 else 1
                    }
                    1 -> {
                        val paso = principal.positionChange()
                        estado.mover(-paso.x, -paso.y)
                        vt.addPosition(principal.uptimeMillis, principal.position)
                    }
                    2 -> {
                        estado.ampliar(ev.calculateZoom(), ev.calculateCentroid())
                        val pan = ev.calculatePan()
                        estado.mover(-pan.x, -pan.y)
                    }
                    3 -> {
                        val p = principal.position
                        val z = zonaEn(p)
                        if (z.tipo == Zona.CELDA) arrastre(z.f, z.c)
                        // Cerca del borde, la rejilla se va desplazando sola.
                        val margen = 36f * estado.densidad
                        val empuje = 14f * estado.densidad
                        estado.mover(
                            if (p.x > estado.ancho - margen) empuje else if (p.x < ANCHO_CABECERA * estado.densidad * estado.zoom + margen / 2) -empuje else 0f,
                            if (p.y > estado.alto - margen) empuje else if (p.y < ALTO_CABECERA * estado.densidad * estado.zoom + margen / 2) -empuje else 0f
                        )
                    }
                    4 -> {
                        val dxDp = (principal.position.x - inicio.x) / (estado.densidad * estado.zoom)
                        ancho(zona.borde, (anchoInicial + dxDp).roundToInt())
                    }
                }
                cambios.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    }

    Canvas(modifier.clipToBounds().then(entrada)) {
        memoria.preparar(tabla, version, estado)
        estado.ancho = size.width
        estado.alto = size.height
        val d = density * estado.zoom
        val altoFila = ALTO_FILA * d
        val cabW = ANCHO_CABECERA * d
        val cabH = ALTO_CABECERA * d
        val dx = estado.desplX
        val dy = estado.desplY
        val lienzo = drawContext.canvas.nativeCanvas
        val acum = estado.acum
        val rejilla = colores.outlineVariant.copy(alpha = 0.55f)
        val tinta = colores.onSurface.toArgb()
        val rojo = Color(0xFFD93025).toArgb()

        drawRect(colores.surface)
        val c0 = columnaEn(acum, dx / d)
        var cFin = c0
        while (cFin < Celdas.MAX_COLS && acum[cFin] * d - dx < size.width - cabW) cFin++
        val f0 = (dy / altoFila).toInt().coerceAtLeast(0)
        val fFin = minOf(Celdas.MAX_FILAS - 1, ((dy + size.height - cabH) / altoFila).toInt() + 1)
        fun xDe(c: Int) = cabW + acum[c] * d - dx
        fun yDe(f: Int) = cabH + f * altoFila - dy

        // Fondos de color, y **las celdas con fórmula sombreadas**: se ve de un vistazo qué se
        // calcula y qué se escribió (lo pidió el usuario el 11-sep-2026). El color puesto a
        // mano manda sobre el sombreado.
        for (f in f0..fFin) for (c in c0 until cFin) {
            val fondo = tabla.estilo(f, c)?.f
            val color = fondo?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                ?: if (tabla.estilo(f, c)?.e == true) TONO_EDITABLE else continue
            drawRect(color, Offset(xDe(c), yDe(f)), Size(acum[c + 1] * d - acum[c] * d, altoFila))
        }
        // El rango elegido, debajo del texto.
        if (!estado.esUna) {
            val x0 = xDe(estado.c1).coerceAtLeast(cabW)
            val y0 = yDe(estado.f1).coerceAtLeast(cabH)
            val x1 = xDe(estado.c2 + 1)
            val y1 = yDe(estado.f2 + 1)
            if (x1 > x0 && y1 > y0) drawRect(colores.primary.copy(alpha = 0.13f), Offset(x0, y0), Size(x1 - x0, y1 - y0))
        }
        // Las rayas.
        for (c in c0..cFin) {
            val x = xDe(c)
            drawLine(rejilla, Offset(x, cabH), Offset(x, size.height), 1f)
        }
        for (f in f0..fFin + 1) {
            val y = yDe(f)
            drawLine(rejilla, Offset(cabW, y), Offset(size.width, y), 1f)
        }
        // **Las celdas con fórmula: un marco fino y discreto**, sin relleno (usuario, 11-sep-2026).
        val grosorDeFormula = 1.2f * density
        for (f in f0..fFin) for (c in c0 until cFin) {
            if (!tabla.calc.esFormula(f, c)) continue
            val m = grosorDeFormula
            drawRect(
                MARCO_DE_FORMULA, Offset(xDe(c) + m, yDe(f) + m),
                Size(acum[c + 1] * d - acum[c] * d - 2 * m, altoFila - 2 * m), style = Stroke(grosorDeFormula)
            )
        }
        // El texto.
        pintura.textSize = 14f * densidad.fontScale * d
        val relleno = 5f * d
        val medioAlto = (pintura.descent() + pintura.ascent()) / 2
        for (f in f0..fFin) for (c in c0 until cFin) {
            val s = memoria.texto(tabla, f, c)
            if (s.isEmpty()) continue
            val k = Celdas.clave(f, c)
            val estilo = tabla.estilo(f, c)
            pintura.typeface = if (estilo?.n == true) negrita else Typeface.DEFAULT
            pintura.color = if (k in memoria.errores) rojo else tinta
            val x0 = xDe(c)
            val anchoCelda = acum[c + 1] * d - acum[c] * d
            val medida = pintura.measureText(s)
            val al = estilo?.a?.firstOrNull() ?: memoria.alineaciones[k] ?: 'i'
            val tx = when (al) {
                'd' -> x0 + anchoCelda - relleno - medida
                'c' -> x0 + (anchoCelda - medida) / 2
                else -> x0 + relleno
            }
            val base = yDe(f) + altoFila / 2 - medioAlto
            if (medida > anchoCelda - 2 * relleno) {
                lienzo.save()
                lienzo.clipRect(x0 + 1, yDe(f), x0 + anchoCelda - 1, yDe(f) + altoFila)
                lienzo.drawText(s, if (al == 'd') x0 + relleno else tx.coerceAtLeast(x0 + relleno), base, pintura)
                lienzo.restore()
            } else lienzo.drawText(s, tx, base, pintura)
        }
        // La celda activa.
        run {
            val x0 = xDe(estado.c)
            val y0 = yDe(estado.f)
            val w = acum[estado.c + 1] * d - acum[estado.c] * d
            if (x0 + w > cabW && y0 + altoFila > cabH) {
                drawRect(colores.primary, Offset(x0, y0), Size(w, altoFila), style = Stroke(2.2f * density))
            }
        }
        // **Las celdas que cita la fórmula**, cada una de su color, como Excel.
        for ((r, color) in marcas) {
            val x0 = xDe(r.c1)
            val y0 = yDe(r.f1)
            val x1 = xDe(minOf(r.c2 + 1, Celdas.MAX_COLS))
            val y1 = yDe(minOf(r.f2 + 1, Celdas.MAX_FILAS))
            if (x1 < cabW || y1 < cabH || x0 > size.width || y0 > size.height) continue
            val a = Offset(x0.coerceAtLeast(cabW - 4f), y0.coerceAtLeast(cabH - 4f))
            val b = Offset(x1.coerceAtMost(size.width + 4f), y1.coerceAtMost(size.height + 4f))
            drawRect(color.copy(alpha = 0.12f), a, Size(b.x - a.x, b.y - a.y))
            drawRect(color, a, Size(b.x - a.x, b.y - a.y), style = Stroke(2f * density))
        }
        // Cabeceras, encima de todo: se quedan quietas.
        val cabecera = colores.surfaceVariant
        val marcada = colores.primaryContainer
        drawRect(cabecera, Offset(0f, 0f), Size(size.width, cabH))
        drawRect(cabecera, Offset(0f, 0f), Size(cabW, size.height))
        pintura.typeface = Typeface.DEFAULT
        pintura.textSize = 12f * densidad.fontScale * d
        val medioCab = (pintura.descent() + pintura.ascent()) / 2
        val tintaCab = colores.onSurfaceVariant.toArgb()
        for (c in c0 until cFin) {
            val x0 = xDe(c)
            val w = acum[c + 1] * d - acum[c] * d
            if (c in estado.c1..estado.c2) drawRect(marcada, Offset(x0, 0f), Size(w, cabH))
            drawLine(rejilla, Offset(x0, 0f), Offset(x0, cabH), 1f)
            val letras = Celdas.letras(c)
            pintura.color = tintaCab
            lienzo.drawText(letras, x0 + (w - pintura.measureText(letras)) / 2, cabH / 2 - medioCab, pintura)
        }
        for (f in f0..fFin) {
            val y0 = yDe(f)
            if (y0 + altoFila < cabH) continue
            if (f in estado.f1..estado.f2) drawRect(marcada, Offset(0f, y0), Size(cabW, altoFila))
            drawLine(rejilla, Offset(0f, y0), Offset(cabW, y0), 1f)
            val n = (f + 1).toString()
            pintura.color = tintaCab
            lienzo.drawText(n, (cabW - pintura.measureText(n)) / 2, y0 + altoFila / 2 - medioCab, pintura)
        }
        drawRect(cabecera, Offset(0f, 0f), Size(cabW, cabH))
        drawLine(rejilla, Offset(0f, cabH), Offset(size.width, cabH), 1.5f)
        drawLine(rejilla, Offset(cabW, 0f), Offset(cabW, size.height), 1.5f)
    }
}
