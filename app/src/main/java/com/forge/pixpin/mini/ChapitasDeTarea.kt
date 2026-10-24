package com.forge.pixpin.mini

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * **Las chapitas de una tarea** (como `tareas/chapitas.rs` del PC, 18-oct-2026): su urgencia
 * («Muy urgente» en rojo, «Urgente» en ámbar) y sus `#etiquetas`, pequeñas y en su fila bajo el
 * título, que así queda limpio. Lo que se guarda no cambia: son los `⏫`/`🔼`/`#casa` del texto
 * ([TareaInteligente.marcas]). Lo usan la pantalla de Tareas y la lista de la mini-app.
 */
object ChapitasDeTarea {
    /** El rojo de «muy urgente» (`chapitas::ROJO` del PC). */
    val ROJO = Color(0xFFE5534B)

    /** El ámbar de «urgente» (`chapitas::AMBAR` del PC). */
    val AMBAR = Color(0xFFE8A33D)

    /** El color de la marca de una urgencia; null si es normal (no lleva marca). */
    fun colorDe(u: TareaInteligente.Urgencia): Color? = when (u) {
        TareaInteligente.Urgencia.MUY_URGENTE -> ROJO
        TareaInteligente.Urgencia.URGENTE -> AMBAR
        TareaInteligente.Urgencia.NORMAL -> null
    }
}

/** La fila de chapitas: nada si la tarea es normal y no tiene etiquetas. [tinta]: el color del texto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilaDeChapitas(urgencia: TareaInteligente.Urgencia, etiquetas: List<String>, tinta: Color, apagada: Boolean = false) {
    if (urgencia == TareaInteligente.Urgencia.NORMAL && etiquetas.isEmpty()) return
    val alfa = if (apagada) 0.5f else 1f
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChapitasDeTarea.colorDe(urgencia)?.let { c ->
            val muy = urgencia == TareaInteligente.Urgencia.MUY_URGENTE
            Chapita(
                urgencia.nombre,
                fondo = if (muy) c.copy(alpha = alfa) else c.copy(alpha = 0.25f * alfa),
                texto = if (muy) Color.White else c,
                negrita = true
            )
        }
        etiquetas.forEach { Chapita("#$it", fondo = tinta.copy(alpha = 0.12f * alfa), texto = tinta.copy(alpha = 0.85f * alfa)) }
    }
}

/** Una chapita: texto pequeño en una píldora. */
@Composable
private fun Chapita(rotulo: String, fondo: Color, texto: Color, negrita: Boolean = false) {
    Text(
        rotulo,
        color = texto,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (negrita) FontWeight.SemiBold else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(fondo).padding(horizontal = 7.dp, vertical = 2.dp)
    )
}
