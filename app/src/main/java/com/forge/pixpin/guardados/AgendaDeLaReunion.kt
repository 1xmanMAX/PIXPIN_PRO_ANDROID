package com.forge.pixpin.guardados

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **La reunión en «Estudiar la clase»** (29-oct-2026; `estudiar_clase.rs` del PC; `docs/jev.md` §5 paso 4,
 * §8 y «Android»). Encima del texto, **sin clave ni red** (todo sale de la nota):
 *
 * - **La agenda** con ✓ y su minuto (un toque en el tema salta ahí; en la casilla se cambia a mano, y también
 *   en su lista de tareas), y a la derecha lo que llevó cada tema («12:30 · 6 min»). Lo que quedó **sin
 *   tratar** va en ámbar, con **«Pasar a la próxima reunión»** (sale solo al preparar la siguiente grabación
 *   en este teléfono) y **«Añadir a Tareas»** (al Inbox, con su nota `💬 🎙 <reunión>`).
 * - **«Decisiones · N»** (un toque salta a su minuto), **«Preguntas sin respuesta · N»** con «Añadir a
 *   Tareas» y **«Propuestas sin aceptar · N»** con «Aceptar» (al Inbox con el texto del PC,
 *   [ReunionConJev.textoDe], y queda `aceptada`).
 *
 * Sin temas ni acta no pinta nada. Sin `return` antes de tiempo (R8).
 */
@Composable
fun AgendaDeLaReunion(m: Mensaje?, alSaltar: (Long) -> Unit) {
    val contexto = LocalContext.current
    val temas = remember(m?.temas) { TemasDeLaReunion.temasDe(m) }
    val acta = remember(m?.decisiones, m?.preguntasAbiertas, m?.propuestas, m?.tiempoPorTema) { ActaViva.de(m) }
    val pendientes = acta.pendientes()
    var abierta by rememberSaveable(m?.id) { mutableStateOf(true) }
    val hayAlgo = m != null && (temas.isNotEmpty() || acta.decisiones.isNotEmpty() || acta.preguntasAbiertas.isNotEmpty() || pendientes.isNotEmpty())
    val ambar = Color(0xFFF0A020)
    val verde = Color(0xFF22A06B)
    fun enHilo(f: () -> String?) {
        Thread {
            val dicho = runCatching(f).getOrNull()
            if (dicho != null) android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(contexto, dicho, Toast.LENGTH_SHORT).show() }
        }.start()
    }
    val reunion = remember(m?.id) { m?.let { tituloDeAudio(contexto, it) }.orEmpty() }
    if (hayAlgo && m != null) Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        val (hechos, total) = TemasDeLaReunion.cuenta(temas)
        Row(Modifier.fillMaxWidth().clickable { abierta = !abierta }, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (temas.isNotEmpty()) "${TextosDeLaReunion.TITULO} · $hechos/$total" else TextosDeLaReunion.TITULO,
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f)
            )
            // **Reportar** lo que marcó Jev (30-oct-2026, política de contenido generado por IA de Google Play).
            com.forge.pixpin.privacidad.BotonReportarIa(com.forge.pixpin.privacidad.ReportesDeIa.Funcion.REUNION, {
                buildString {
                    for (t in temas) append(if (t.hecho) "✓ " else "· ").append(t.texto).append('\n')
                    for (d in acta.decisiones) append("Decisión: ").append(d.texto).append('\n')
                    for (q in acta.preguntasAbiertas) append("Pregunta: ").append(q.texto).append('\n')
                    for (p in acta.propuestas) append("Propuesta: ").append(p.texto).append('\n')
                }
            })
            Icon(if (abierta) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (abierta) Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (t in temas) {
                val ms = t.ms
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (t.hecho) Color.Transparent else ambar.copy(alpha = 0.15f))
                        .clickable(enabled = ms != null) { ms?.let(alSaltar) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { enHilo { ReunionConJev.cambiarAMano(contexto, m.id, t.id, !t.hecho); null } }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (t.hecho) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, contentDescription = null,
                            tint = if (t.hecho) verde else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(t.texto, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val dura = acta.tiempoPorTema?.de(t.id)?.takeIf { it > 0 }?.let(ActaViva::duracionCorta)
                    val derecha = listOfNotNull(
                        if (t.hecho) ms?.let(GrabarClase::minuto) else TextosDeLaReunion.SIN_TRATAR,
                        dura
                    ).joinToString(" · ")
                    Text(derecha, fontSize = 12.sp, color = if (t.hecho) MaterialTheme.colorScheme.primary else ambar, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
                }
            }
            val sin = TemasDeLaReunion.sinTratar(temas).map { it.texto }
            if (sin.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { enHilo { TextosDeLaReunion.pasados(PrepararTemas.guardarParaLaProxima(contexto, sin)) } }) {
                    Text(TextosDeLaReunion.PROXIMA, fontSize = 12.sp)
                }
                TextButton(onClick = { enHilo { TextosDeLaReunion.anadidos(ReunionConJev.aTareas(contexto, sin, reunion)) } }) {
                    Text(TextosDeLaReunion.A_TAREAS, fontSize = 12.sp)
                }
            }
            if (acta.decisiones.isNotEmpty()) {
                Text("${TextosDeLaReunion.DECISIONES} · ${acta.decisiones.size}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                for (d in acta.decisiones) Text(
                    "${GrabarClase.minuto(d.ms)} · ${d.texto}", fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { alSaltar(d.ms) }.padding(vertical = 2.dp)
                )
            }
            if (acta.preguntasAbiertas.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("${TextosDeLaReunion.PREGUNTAS} · ${acta.preguntasAbiertas.size}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val textos = acta.preguntasAbiertas.map { it.texto }
                        enHilo { TextosDeLaReunion.preguntasAnadidas(ReunionConJev.aTareas(contexto, textos, reunion)) }
                    }) { Text(TextosDeLaReunion.A_TAREAS, fontSize = 12.sp) }
                }
                for (q in acta.preguntasAbiertas) Text(
                    "${GrabarClase.minuto(q.ms)} · ${q.texto}", fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { alSaltar(q.ms) }.padding(vertical = 2.dp)
                )
            }
            if (pendientes.isNotEmpty()) {
                Text("${TextosDeLaReunion.PROPUESTAS} · ${pendientes.size}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                for (p in pendientes) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (p.tipo == ActaViva.Tipo.EVENTO) "📅 " else "＋ ") + "${GrabarClase.minuto(p.ms)} · ${p.texto}" +
                            (p.fecha?.let { " · $it" + (p.hora?.let { h -> " $h" } ?: "") } ?: ""),
                        fontSize = 13.sp, modifier = Modifier.weight(1f).clickable { alSaltar(p.ms) }
                    )
                    TextButton(onClick = {
                        enHilo { if (ReunionConJev.aceptarDeLaNota(contexto, m.id, p.id, reunion)) TextosDeLaReunion.ACEPTADA else null }
                    }) { Text(TextosDeLaReunion.ACEPTAR, fontSize = 12.sp) }
                }
            }
        }
    }
}
