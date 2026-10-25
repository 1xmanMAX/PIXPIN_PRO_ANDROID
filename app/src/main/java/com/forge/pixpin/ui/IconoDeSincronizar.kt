package com.forge.pixpin.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.sincro.VueltasEnMarcha

/** El rojo de la marca de «falló» (`#E53935`, el del PC). */
val ROJO_DE_FALLO = Color(0xFFE53935)

/**
 * **El icono del botón de sincronizar, que gira mientras se sincroniza** (25-oct-2026, lo del PC
 * `0470294`; el usuario: «mientras se sincroniza, que el botón gire»).
 *
 * Gira una vuelta por segundo, en el sentido de las agujas del reloj, mientras haya **cualquier**
 * vuelta en marcha ([VueltasEnMarcha]); al acabar se para y vuelve a su sitio. Si la última falló
 * lleva arriba a la derecha una **chapa roja con «!»** con un aro del color de [aro] (el de la
 * cabecera), hasta que se pulsa el botón ([VueltasEnMarcha.olvidarFallo], lo hace quien lo pulsa)
 * o empieza otra vuelta.
 *
 * **Sin gasto en reposo**: la transición infinita solo existe mientras gira (es una rama del `if`);
 * quieto, el icono no pide ningún fotograma.
 */
@Composable
fun IconoDeSincronizar(tinta: Color, aro: Color, tamano: Dp = 22.dp, modifier: Modifier = Modifier) {
    val estado by VueltasEnMarcha.estado.collectAsState()
    Box(modifier) {
        if (estado.gira) {
            val giro = rememberInfiniteTransition(label = "sincronizando")
            val angulo by giro.animateFloat(
                0f, 360f,
                infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
                label = "angulo"
            )
            Icon(Icons.Filled.Sync, contentDescription = null, tint = tinta, modifier = Modifier.size(tamano).rotate(angulo))
        } else {
            Icon(Icons.Filled.Sync, contentDescription = null, tint = tinta, modifier = Modifier.size(tamano))
        }
        if (estado.fallo) Box(
            Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(13.dp)
                .border(1.5.dp, aro, CircleShape).background(ROJO_DE_FALLO, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("!", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
        }
    }
}
