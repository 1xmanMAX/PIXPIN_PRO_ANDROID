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

    /** El gris de la prioridad «baja» de Jev (`chapitas::GRIS` del PC). */
    val GRIS = Color(0xFF8B949E)

    /**
     * **La chapita de la prioridad de Jev** (rótulo y color), o null: si el usuario marcó urgencia (lo
     * suyo manda y ya tiene su chapita), si no hay prioridad o si es «normal».
     */
    fun deJev(u: TareaInteligente.Urgencia, p: com.forge.pixpin.jev.Urgencia.Prioridad?): Pair<String, Color>? {
        if (u != TareaInteligente.Urgencia.NORMAL || p == null) return null
        val color = when (p) {
            com.forge.pixpin.jev.Urgencia.Prioridad.URGENTE -> ROJO
            com.forge.pixpin.jev.Urgencia.Prioridad.ALTA -> AMBAR
            com.forge.pixpin.jev.Urgencia.Prioridad.BAJA -> GRIS
            com.forge.pixpin.jev.Urgencia.Prioridad.NORMAL -> return null
        }
        return "✦ ${p.rotulo}" to color
    }
}

/**
 * La fila de chapitas: nada si la tarea es normal y no tiene etiquetas. [tinta]: el color del texto.
 * [prioridad] (28-oct-2026, Jev, `chapitas.rs` del PC): «✦ Urgente», «✦ Alta» o «✦ Baja», la primera y
 * más suave que una marca del usuario (la chispa dice que es de Jev); una tarea marcada a mano enseña
 * solo su marca, y «normal» no se enseña.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilaDeChapitas(
    urgencia: TareaInteligente.Urgencia, etiquetas: List<String>, tinta: Color, apagada: Boolean = false,
    prioridad: com.forge.pixpin.jev.Urgencia.Prioridad? = null
) {
    val deJev = ChapitasDeTarea.deJev(urgencia, prioridad)
    if (urgencia == TareaInteligente.Urgencia.NORMAL && etiquetas.isEmpty() && deJev == null) return
    val alfa = if (apagada) 0.5f else 1f
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        deJev?.let { (rotulo, c) -> Chapita(rotulo, fondo = c.copy(alpha = 0.16f * alfa), texto = c.copy(alpha = alfa)) }
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
