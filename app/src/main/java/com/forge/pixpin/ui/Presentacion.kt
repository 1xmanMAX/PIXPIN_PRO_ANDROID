package com.forge.pixpin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * **El modo presentación** (14-sep-2026): la diapositiva a pantalla completa, sin barras, y
 * una pastilla abajo para pasar y para anotar encima mientras se habla.
 *
 * Qué es una diapositiva lo decide quien lo hospeda: la página de un PDF (y un PowerPoint
 * importado es un PDF), o cada hoja del cuaderno. Ver `DrawEditorActivity.empezarAPresentar`.
 */
enum class ModoDePresentacion { PASAR, LAPIZ, RESALTADOR, BORRADOR }

/**
 * **Pasar con el dedo**, solo en el modo PASAR: un toque en el tercio derecho o un barrido a
 * la izquierda es la siguiente; en el tercio izquierdo o barriendo a la derecha, la anterior;
 * en el medio, esconder o enseñar la pastilla.
 */
@Composable
fun ZonaDePasar(
    onAnterior: () -> Unit,
    onSiguiente: () -> Unit,
    onMedio: () -> Unit,
    modifier: Modifier = Modifier
) {
    val anterior by rememberUpdatedState(onAnterior)
    val siguiente by rememberUpdatedState(onSiguiente)
    val medio by rememberUpdatedState(onMedio)
    val holgura = LocalViewConfiguration.current.touchSlop
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val abajo = awaitFirstDown()
                    val inicio = abajo.position
                    var fin = inicio
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == abajo.id } ?: break
                        fin = c.position
                        c.consume()
                        if (!c.pressed) break
                    }
                    val dx = fin.x - inicio.x
                    val dy = fin.y - inicio.y
                    val barrido = 64.dp.toPx()
                    when {
                        abs(dx) > barrido && abs(dx) > abs(dy) -> if (dx < 0) siguiente() else anterior()
                        abs(dx) < holgura * 2 && abs(dy) < holgura * 2 -> {
                            val tercio = size.width / 3f
                            when {
                                inicio.x < tercio -> anterior()
                                inicio.x > tercio * 2 -> siguiente()
                                else -> medio()
                            }
                        }
                    }
                }
            }
    )
}

@Composable
fun PastillaDePresentacion(
    visible: Boolean,
    actual: Int,
    total: Int,
    modo: ModoDePresentacion,
    onModo: (ModoDePresentacion) -> Unit,
    onAnterior: () -> Unit,
    onSiguiente: () -> Unit,
    onSalir: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        val cosmos = com.forge.pixpin.ui.theme.LocalCosmos.current
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = if (cosmos) com.forge.pixpin.ui.theme.Cristal.barra else Color.Black.copy(alpha = 0.62f),
            contentColor = Color.White,
            border = if (cosmos) androidx.compose.foundation.BorderStroke(1.dp, com.forge.pixpin.ui.theme.Cristal.filo) else null
        ) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAnterior, enabled = actual > 1) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Anterior", tint = if (actual > 1) Color.White else Color.White.copy(alpha = 0.35f))
                }
                Text(
                    if (total > 0) "$actual / $total" else "$actual",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                IconButton(onClick = onSiguiente, enabled = total <= 0 || actual < total) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Siguiente", tint = if (total <= 0 || actual < total) Color.White else Color.White.copy(alpha = 0.35f))
                }
                Separador()
                for ((m, icono) in listOf(
                    ModoDePresentacion.PASAR to Icons.Filled.TouchApp,
                    ModoDePresentacion.LAPIZ to Icons.Filled.Gesture,
                    ModoDePresentacion.RESALTADOR to Icons.Filled.BorderColor,
                    ModoDePresentacion.BORRADOR to Icons.Filled.AutoFixNormal
                )) {
                    val puesto = m == modo
                    Surface(
                        onClick = { onModo(m) },
                        shape = RoundedCornerShape(50),
                        color = if (!puesto) Color.Transparent
                        else if (cosmos) com.forge.pixpin.ui.theme.Cristal.puesto else Color.White.copy(alpha = 0.28f),
                        contentColor = if (puesto && cosmos) com.forge.pixpin.ui.theme.Cristal.tintaPuesta else Color.White,
                        modifier = Modifier.padding(2.dp)
                    ) {
                        Icon(icono, m.name, Modifier.padding(9.dp).size(24.dp))
                    }
                }
                Separador()
                IconButton(onClick = onSalir) { Icon(Icons.Filled.Close, "Salir de la presentación") }
            }
        }
    }
}

@Composable
private fun Separador() {
    Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 22.dp).padding(0.dp)) {
        Surface(color = Color.White.copy(alpha = 0.3f), modifier = Modifier.fillMaxSize()) {}
    }
}
