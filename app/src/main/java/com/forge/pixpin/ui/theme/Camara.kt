package com.forge.pixpin.ui.theme

import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * **Esquivar la cámara** (17-sep-2026). En algunos teléfonos la cámara frontal es un agujero en
 * la pantalla, y a pantalla completa las barras de arriba pasaban por debajo: se perdía un
 * botón. Lo pidió el usuario así:
 *
 * - **Cámara en el centro**: nada se queda debajo. Los botones de una barra que se desliza
 *   **saltan de un lado al otro** del agujero al pasar ([FilaQueSaltaLaCamara]); lo que va
 *   centrado baja por debajo ([bajarSiTocaLaCamara]).
 * - **Cámara a un lado**: la barra se aparta lo justo de ese lado ([apartarDeLaCamara]).
 *
 * Las cuentas están en [EsquivaDeCamara], sin Android, para poder comprobarlas.
 */
object EsquivaDeCamara {
    /** Lo que se deja entre la cámara y un botón, en píxeles. */
    const val HOLGURA = 12f

    /** Si una cámara está en el centro de [ancho] (y no en una esquina). */
    fun esCentral(camara: Rect, ancho: Float): Boolean =
        camara.center.x in ancho * 0.25f..ancho * 0.75f

    private fun alturaCoincide(a: Rect, b: Rect) = a.top < b.bottom && a.bottom > b.top

    /**
     * Cuánto hay que apartar [caja] por la izquierda y por la derecha para que no pise ninguna
     * cámara **de las esquinas**. Las del centro no se esquivan así: se saltan.
     */
    fun lados(caja: Rect, camaras: List<Rect>, anchoPantalla: Float): Pair<Float, Float> {
        var izq = 0f
        var der = 0f
        for (c in camaras) {
            if (!alturaCoincide(caja, c) || esCentral(c, anchoPantalla)) continue
            if (c.center.x < anchoPantalla / 2) izq = max(izq, c.right - caja.left + HOLGURA)
            else der = max(der, caja.right - c.left + HOLGURA)
        }
        return izq.coerceAtLeast(0f) to der.coerceAtLeast(0f)
    }

    /** Cuánto hay que bajar [caja] para que no pise ninguna cámara. */
    fun bajada(caja: Rect, camaras: List<Rect>): Float {
        var b = 0f
        for (c in camaras) if (caja.overlaps(c)) b = max(b, c.bottom - caja.top + HOLGURA)
        return b
    }

    /**
     * **Dónde va cada botón de una fila**, saltando los huecos.
     *
     * [inicio] es dónde empieza la fila en la pantalla (ya con el desplazamiento restado) y
     * [huecos] los tramos de pantalla, de izquierda a derecha, en los que no puede quedar nada.
     * Un botón que caería encima de un hueco pasa entero al otro lado, y los que van detrás
     * con él. Devuelve la x de cada uno dentro de la fila.
     */
    fun colocar(anchos: IntArray, separacion: Int, inicio: Float, huecos: List<ClosedFloatingPointRange<Float>>): IntArray {
        val xs = IntArray(anchos.size)
        var x = 0f
        for (i in anchos.indices) {
            for (h in huecos) {
                val enPantalla = inicio + x
                if (enPantalla < h.endInclusive + HOLGURA && enPantalla + anchos[i] > h.start - HOLGURA) {
                    x = h.endInclusive + HOLGURA - inicio
                }
            }
            xs[i] = x.roundToInt()
            x += anchos[i] + separacion
        }
        return xs
    }
}

/** Dónde están las cámaras, en píxeles de la ventana. Vacío si no hay o el sistema no lo dice. */
@Composable
fun rememberCamaras(): List<Rect> {
    val vista = LocalView.current
    // Leer los márgenes suscribe a sus cambios: al girar, las cámaras cambian de sitio.
    val d = LocalDensity.current
    val arriba = WindowInsets.displayCutout.getTop(d)
    val izquierda = WindowInsets.displayCutout.getLeft(d, androidx.compose.ui.unit.LayoutDirection.Ltr)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
    return remember(vista, arriba, izquierda) {
        vista.rootWindowInsets?.displayCutout?.boundingRects.orEmpty().map {
            Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        }
    }
}

/** Aparta esta barra de una cámara de esquina. Ver [EsquivaDeCamara.lados]. */
fun Modifier.apartarDeLaCamara(): Modifier = composed {
    val camaras = rememberCamaras()
    if (camaras.isEmpty()) return@composed this
    val vista = LocalView.current
    var caja by remember { mutableStateOf<Rect?>(null) }
    val d = LocalDensity.current.density
    val (izq, der) = caja?.let { EsquivaDeCamara.lados(it, camaras, vista.width.toFloat()) } ?: (0f to 0f)
    this
        .onGloballyPositioned { caja = it.boundsInWindow() }
        .padding(start = (izq / d).dp, end = (der / d).dp)
}

/** Baja lo que va centrado si pisa una cámara. Ver [EsquivaDeCamara.bajada]. */
fun Modifier.bajarSiTocaLaCamara(): Modifier = composed {
    val camaras = rememberCamaras()
    if (camaras.isEmpty()) return@composed this
    var caja by remember { mutableStateOf<Rect?>(null) }
    val d = LocalDensity.current.density
    val baja = caja?.let { EsquivaDeCamara.bajada(it, camaras) } ?: 0f
    this
        .onGloballyPositioned { caja = it.boundsInWindow() }
        .padding(top = (baja / d).dp)
}

/**
 * **Una fila que se desliza y cuyos botones saltan la cámara del centro.**
 *
 * Va dentro de un `horizontalScroll(desplazamiento)`: [desplazamiento] se lee al colocar, así
 * que deslizar solo recoloca (no vuelve a medir ni recompone). Sin cámara en la fila, coloca
 * como una `Row` normal. Se reserva al final el sitio del salto más largo posible, para que el
 * ancho no cambie al deslizar.
 */
@Composable
fun FilaQueSaltaLaCamara(
    desplazamiento: ScrollState,
    modifier: Modifier = Modifier,
    contenido: @Composable () -> Unit
) {
    val camaras = rememberCamaras()
    var caja by remember { mutableStateOf<Rect?>(null) }
    val conCamara = camaras.isNotEmpty()
    Layout(
        contenido,
        if (conCamara) modifier.onGloballyPositioned {
            // Dónde empieza la fila sin deslizar: su sitio más lo ya deslizado.
            val b = it.boundsInWindow()
            caja = Rect(b.left + desplazamiento.value, b.top, b.right, b.bottom)
        } else modifier
    ) { medibles, restricciones ->
        val suelta = restricciones.copy(minWidth = 0)
        val piezas = medibles.map { it.measure(suelta) }
        val alto = piezas.maxOfOrNull { it.height } ?: 0
        val anchos = IntArray(piezas.size) { piezas[it].width }
        val sitio = caja
        val huecos = if (sitio == null) emptyList() else camaras
            .filter { it.top < sitio.bottom && it.bottom > sitio.top }
            .sortedBy { it.left }
            .map { it.left..it.right }
        val reserva = huecos.sumOf { (it.endInclusive - it.start + 2 * EsquivaDeCamara.HOLGURA).toDouble() }.toInt() +
            if (huecos.isEmpty()) 0 else (anchos.maxOrNull() ?: 0)
        val ancho = anchos.sum() + reserva
        layout(ancho.coerceIn(restricciones.minWidth, restricciones.maxWidth), alto) {
            val xs = if (huecos.isEmpty() || sitio == null) {
                var x = 0
                IntArray(anchos.size) { i -> x.also { x += anchos[i] } }
            } else {
                EsquivaDeCamara.colocar(anchos, 0, sitio.left - desplazamiento.value, huecos)
            }
            piezas.forEachIndexed { i, p -> p.place(xs[i], (alto - p.height) / 2) }
        }
    }
}
