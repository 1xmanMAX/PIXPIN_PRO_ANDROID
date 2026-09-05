package com.forge.pixpin.motor

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * **El agarre del botón flotante**, compartido entre el botón y el lienzo **sin pasar por
 * Compose para el gesto**: el botón escribe cuántos dedos lo aprietan y el lienzo lo lee en
 * el instante en que se posa el lápiz. (Los campos sí son estado de Compose, para que el
 * botón se encienda y se apague; leerlos desde un gesto no cuesta nada.)
 *
 * ## Lo que el sistema esconde
 *
 * Muchos teléfonos **cancelan el dedo en cuanto el lápiz se acerca** a la pantalla, y ya no
 * vuelven a decir nada de él: para la aplicación el dedo se fue, aunque siga ahí puesto. Por
 * eso el agarre no es solo «hay dedos»: tras una cancelación se queda armado una
 * **ventana** ([VENTANA]) que se renueva cada vez que se usa un menú, así se puede seguir
 * eligiendo mientras el dedo —invisible— sigue puesto. Levantar el dedo de verdad, después
 * de haberlo usado, lo apaga en el acto: lo siguiente que haga el lápiz es trazar. Y un
 * toque limpio lo deja **fijo** ([FIJO]) hasta el siguiente toque: el interruptor para
 * quien prefiera no mantener nada.
 *
 * Cómo se distingue la cancelación: Compose la entrega como un «soltar» que **repite la
 * hora del último evento** —lo fabrica sin evento nuevo—, mientras que un levantar de
 * verdad trae su propia hora. Ver [BotonFlotante].
 */
class AgarreDelBoton {
    /** Cuántos dedos lo aprietan ahora mismo. */
    var dedos by mutableIntStateOf(0)
        private set

    /** Hasta cuándo sigue armado sin dedos encima, en ms de `uptime`: 0 es no; [FIJO], hasta que se toque. */
    var hasta by mutableLongStateOf(0L)
        private set

    private var usado = false
    private var porCancelacion = false

    fun armado(ahora: Long): Boolean = dedos > 0 || hasta == FIJO || hasta > ahora

    fun apretar(n: Int) {
        dedos = n
    }

    /** Se han ido los dedos a la hora [ahora]: por un [toque] breve, [cancelado] por el sistema, o levantados. */
    fun soltar(ahora: Long, toque: Boolean, cancelado: Boolean) {
        val yaUsado = usado
        usado = false
        dedos = 0
        porCancelacion = cancelado
        hasta = when {
            // Un toque limpio enciende el botón hasta el primer abanico; tocarlo otra vez
            // estando encendido lo apaga.
            toque -> if (armado(ahora)) 0L else FIJO
            cancelado -> ahora + VENTANA
            yaUsado -> 0L
            else -> ahora + MEMORIA_DEL_AGARRE
        }
    }

    /**
     * Si el puntero que acaba de posarse en el lienzo a la hora [ahora] abre el abanico.
     *
     * **Lo armado se gasta al usarlo.** Un toque deja el botón encendido, pero solo hasta
     * el primer abanico: si siguiera armado, no habría forma de volver a trazar sin tocarlo
     * otra vez, y eso es justo lo que hace que el color elegido «no se aplique» —se aplicó,
     * pero el trazo siguiente se lo comía el menú—. Lo que encadena los menús (el color, los
     * lápices) no es esto, es que el menú quede abierto.
     */
    fun agarrar(ahora: Long): Boolean {
        if (dedos > 0) {
            usado = true
            return true
        }
        if (hasta == FIJO) { hasta = 0L; return true }
        if (hasta > ahora) return true
        hasta = 0L
        return false
    }

    /**
     * Un menú acaba de usarse con el dedo **cancelado** por el sistema: la ventana vuelve a
     * empezar, para poder seguir eligiendo mientras el dedo —invisible— sigue puesto.
     */
    fun renovar(ahora: Long) {
        if (dedos == 0 && porCancelacion) hasta = ahora + VENTANA
    }

    /** El abanico se abrió sin dedo a la vista y no se eligió nada: era un trazo, se apaga. */
    fun apagar() {
        hasta = 0L
        porCancelacion = false
    }

    companion object {
        const val FIJO = Long.MAX_VALUE
    }
}

/**
 * **El botón flotante de la pantalla completa.** Un cuadrado semitransparente con una
 * cabecera encima: por la cabecera se arrastra y se deja donde uno quiera; el cuadrado
 * solo se aprieta y se suelta. Es la mano que no dibuja: mientras se mantiene apretado,
 * cada vez que el lápiz se posa en el lienzo abre el **abanico** —lápiz, borrador, color,
 * figuras— y se lleva hacia una opción. Un toque limpio lo deja fijo (con una chincheta)
 * hasta el siguiente toque, y mientras está apretado o armado se enciende. Pedido
 * por el usuario (2-sep-2026) en lugar de los dos círculos que abrían la rueda al
 * tocarlos; el atajo de dos dedos que hubo entre medias no le salía y se quitó (3-sep).
 * Ver [AgarreDelBoton] y [DrawCanvas.agarre].
 *
 * Dónde queda se guarda, para que al volver esté donde uno lo dejó. Nace abajo, en el lado
 * contrario a la mano.
 */
@Composable
fun BotonFlotante(zurdo: Boolean, agarre: AgarreDelBoton, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val prefs = remember { contexto.getSharedPreferences(PREFS_DEL_BOTON, Context.MODE_PRIVATE) }
    val densidad = LocalDensity.current
    val ancho = with(densidad) { LADO_DEL_BOTON.toPx() }
    val alto = with(densidad) { (LADO_DEL_BOTON + ASA_DEL_BOTON).toPx() }
    val margen = with(densidad) { MARGEN_DEL_BOTON.toPx() }
    // En píxeles y con `offset { }`: se aplica al colocar, sin recomponer, que es lo que
    // hace que arrastrar vaya suelto.
    var x by remember { mutableFloatStateOf(prefs.getFloat(CLAVE_X, Float.NaN)) }
    var y by remember { mutableFloatStateOf(prefs.getFloat(CLAVE_Y, Float.NaN)) }
    var sitio by remember { mutableStateOf(IntSize.Zero) }
    // **Se enciende mientras está armado.** La ventana caduca sola, y como nadie recompone
    // cuando caduca, se pide un repintado para entonces.
    var relojDeApagado by remember { mutableIntStateOf(0) }
    val hasta = agarre.hasta
    val ahora = SystemClock.uptimeMillis() + relojDeApagado * 0L
    val vivo = agarre.dedos > 0 || hasta == AgarreDelBoton.FIJO || hasta > ahora
    if (hasta != AgarreDelBoton.FIJO && hasta > ahora) {
        LaunchedEffect(hasta) {
            delay(hasta - SystemClock.uptimeMillis() + 1)
            relojDeApagado++
        }
    }

    // La caja de fuera solo mide: sin nada que reciba el dedo, no tapa el lienzo.
    Box(
        modifier.fillMaxSize().onSizeChanged { medida ->
            sitio = medida
            if (x.isNaN() || y.isNaN()) {
                x = if (zurdo) medida.width - ancho - margen else margen
                y = medida.height - alto - margen
            }
        }
    ) {
        if (sitio == IntSize.Zero || x.isNaN() || y.isNaN()) return@Box
        Column(
            Modifier
                .offset {
                    IntOffset(
                        x.coerceIn(0f, (sitio.width - ancho).coerceAtLeast(0f)).roundToInt(),
                        y.coerceIn(0f, (sitio.height - alto).coerceAtLeast(0f)).roundToInt()
                    )
                }
                .width(LADO_DEL_BOTON)
        ) {
            // **El asa**: la cosita de arriba. Solo ella mueve el botón.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(ASA_DEL_BOTON)
                    .background(Color.Black.copy(alpha = 0.5f), FORMA_DEL_ASA)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { prefs.edit().putFloat(CLAVE_X, x).putFloat(CLAVE_Y, y).apply() }
                        ) { cambio, arrastre ->
                            cambio.consume()
                            x += arrastre.x
                            y += arrastre.y
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.DragHandle, contentDescription = "Mover el botón",
                    tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp)
                )
            }
            // **El cuadrado**: se aprieta y se suelta, nada más. Cuenta los dedos y se
            // lo cuenta al lienzo por [agarre]; no se mueve al arrastrarlo.
            Box(
                Modifier
                    .size(LADO_DEL_BOTON)
                    .background(Color.Black.copy(alpha = if (vivo) 0.55f else 0.28f), FORMA_DEL_CUADRO)
                    .border(1.dp, Color.White.copy(alpha = if (vivo) 0.9f else 0.5f), FORMA_DEL_CUADRO)
                    .pointerInput(agarre) {
                        awaitEachGesture {
                            val primero = awaitFirstDown()
                            primero.consume()
                            val desde = primero.uptimeMillis
                            var previa = desde
                            var dedos = 1
                            agarre.apretar(1)
                            var cancelado = false
                            while (true) {
                                val evento = awaitPointerEvent()
                                evento.changes.forEach { it.consume() }
                                val hora = evento.changes.maxOf { it.uptimeMillis }
                                val activos = evento.changes.count { it.pressed }
                                if (activos == 0) {
                                    // **La cancelación repite la hora**: Compose la fabrica
                                    // sin evento nuevo. Un levantar de verdad trae la suya.
                                    cancelado = hora == previa
                                    break
                                }
                                previa = hora
                                if (activos != dedos) {
                                    dedos = activos
                                    agarre.apretar(dedos)
                                }
                            }
                            val toque = !cancelado && previa - desde < LO_QUE_DURA_UN_TOQUE
                            agarre.soltar(if (cancelado) SystemClock.uptimeMillis() else previa, toque, cancelado)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (hasta == AgarreDelBoton.FIJO) Icons.Filled.PushPin else Icons.Filled.Brush,
                    contentDescription = "Botón de gestos",
                    tint = Color.White.copy(alpha = if (vivo) 1f else 0.85f), modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/** Lo que mide el cuadrado, y lo alta que va el asa de encima. */
private val LADO_DEL_BOTON = 64.dp
private val ASA_DEL_BOTON = 18.dp
private val MARGEN_DEL_BOTON = 14.dp
private val FORMA_DEL_ASA = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
private val FORMA_DEL_CUADRO = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)

/** Hasta cuánto dura apretar y soltar para ser un toque, en ms. */
private const val LO_QUE_DURA_UN_TOQUE = 300L

/** Cuánto sigue valiendo un agarre tras levantar los dedos sin haberlo usado, en ms. */
private const val MEMORIA_DEL_AGARRE = 700L

/** Cuánto sigue armado tras una cancelación del sistema, contando desde el último menú, en ms. */
private const val VENTANA = 2500L

private const val PREFS_DEL_BOTON = "boton_flotante"
private const val CLAVE_X = "x"
private const val CLAVE_Y = "y"
