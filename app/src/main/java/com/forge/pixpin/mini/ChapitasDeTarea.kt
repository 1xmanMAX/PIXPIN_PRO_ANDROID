package com.forge.pixpin.mini

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.R
import com.forge.pixpin.jev.Urgencia

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

    /** El gris de las prioridades «normal» y «baja» de Jev (`chapitas::GRIS` del PC). */
    val GRIS = Color(0xFF8B949E)

    /**
     * **La chapita de la prioridad de Jev** (rótulo en español y color), o null: si el usuario marcó
     * urgencia (lo suyo manda y ya tiene su chapita) o si no hay prioridad. **«Normal» también se enseña**
     * (2-nov-2026, `e775e9f4` del PC; el usuario: «Jev no está valorando la prioridad»: sin chapita, una
     * tarea juzgada normal parecía sin juzgar), en gris y casi sin fondo ([fondoDeJev]).
     */
    fun deJev(u: TareaInteligente.Urgencia, p: Urgencia.Prioridad?): Pair<String, Color>? {
        if (u != TareaInteligente.Urgencia.NORMAL || p == null) return null
        val color = when (p) {
            Urgencia.Prioridad.URGENTE -> ROJO
            Urgencia.Prioridad.ALTA -> AMBAR
            Urgencia.Prioridad.NORMAL, Urgencia.Prioridad.BAJA -> GRIS
        }
        return "✦ ${p.rotulo}" to color
    }

    /** Lo opaco del fondo de la chapita de Jev: «normal», la mitad, que se lea sin llamar la atención. */
    fun fondoDeJev(p: Urgencia.Prioridad): Float = if (p == Urgencia.Prioridad.NORMAL) 0.08f else 0.16f

    /** Los textos de la chapita de Jev y de su porqué (`tareas-prioridad-*` y `tareas-jev-*` del PC). */
    enum class Texto(val id: Int) {
        URGENTE(R.string.tareas_prioridad_urgente), ALTA(R.string.tareas_prioridad_alta),
        NORMAL(R.string.tareas_prioridad_normal), BAJA(R.string.tareas_prioridad_baja),
        CONSECUENCIA_NADA(R.string.tareas_jev_consecuencia_nada), CONSECUENCIA_LEVE(R.string.tareas_jev_consecuencia_leve),
        CONSECUENCIA_MEDIA(R.string.tareas_jev_consecuencia_media), CONSECUENCIA_GRAVE(R.string.tareas_jev_consecuencia_grave),
        LA_ESPERAN(R.string.tareas_jev_la_esperan), NADIE_ESPERA(R.string.tareas_jev_nadie_espera),
        PLAZO_AJENO(R.string.tareas_jev_plazo_ajeno), LA_PIDEN(R.string.tareas_jev_la_piden),
        SIN_FECHA(R.string.tareas_jev_sin_fecha), YA_PASO(R.string.tareas_jev_ya_paso),
        HOY(R.string.tareas_jev_hoy), MANANA(R.string.tareas_jev_manana),
        /** Con el número de días. */
        EN_DIAS(R.string.tareas_jev_en_dias),
        /** Con el «cuándo» de antes. */
        POR_LA_LISTA(R.string.tareas_jev_por_la_lista);

        /** El nombre del texto en `strings_prioridad_de_jev.xml`. */
        val clave: String get() = (if (ordinal < 4) "tareas_prioridad_" else "tareas_jev_") + name.lowercase()
    }

    /** El texto del rótulo de una prioridad de Jev. */
    fun textoDe(p: Urgencia.Prioridad): Texto = when (p) {
        Urgencia.Prioridad.URGENTE -> Texto.URGENTE
        Urgencia.Prioridad.ALTA -> Texto.ALTA
        Urgencia.Prioridad.NORMAL -> Texto.NORMAL
        Urgencia.Prioridad.BAJA -> Texto.BAJA
    }

    /**
     * **La pista de la chapita de Jev** (2-nov-2026, `chapitas::pista_de_jev` del PC): el porqué de su
     * prioridad, en una línea («Jev: consecuencia media · nadie la espera · sin fecha»). [t] da cada
     * texto en el idioma puesto, con su hueco si lo lleva (null si no).
     */
    fun pistaDeJev(m: Urgencia.Motivo, t: (Texto, Any?) -> String): String {
        val consecuencia = when (m.consecuencia) {
            0 -> Texto.CONSECUENCIA_NADA
            1 -> Texto.CONSECUENCIA_LEVE
            2 -> Texto.CONSECUENCIA_MEDIA
            else -> Texto.CONSECUENCIA_GRAVE
        }
        val partes = mutableListOf(t(consecuencia, null))
        partes += t(if (m.espera) Texto.LA_ESPERAN else Texto.NADIE_ESPERA, null)
        if (m.plazoAjeno) partes += t(Texto.PLAZO_AJENO, null)
        if (m.pidenYa) partes += t(Texto.LA_PIDEN, null)
        val d = m.dias
        val cuando = when {
            d == null -> t(Texto.SIN_FECHA, null)
            d < 0 -> t(Texto.YA_PASO, null)
            d == 0L -> t(Texto.HOY, null)
            d == 1L -> t(Texto.MANANA, null)
            else -> t(Texto.EN_DIAS, d.toInt())
        }
        partes += if (m.deLaLista) t(Texto.POR_LA_LISTA, cuando) else cuando
        return "Jev: " + partes.joinToString(" · ")
    }
}

/**
 * La fila de chapitas: nada si la tarea es normal y no tiene etiquetas. [tinta]: el color del texto.
 * [prioridad] (28-oct-2026, Jev, `chapitas.rs` del PC): «✦ Urgente», «✦ Alta», «✦ Normal» (gris, casi
 * sin fondo; 2-nov-2026) o «✦ Baja», la primera y más suave que una marca del usuario (la chispa dice
 * que es de Jev); una tarea marcada a mano enseña solo su marca. Con su [motivo], **un toque o una
 * pulsación larga en la chapita de Jev dice el porqué** (en el PC, al pasar el ratón).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilaDeChapitas(
    urgencia: TareaInteligente.Urgencia, etiquetas: List<String>, tinta: Color, apagada: Boolean = false,
    prioridad: Urgencia.Prioridad? = null, motivo: Urgencia.Motivo? = null
) {
    val deJev = ChapitasDeTarea.deJev(urgencia, prioridad)
    if (urgencia == TareaInteligente.Urgencia.NORMAL && etiquetas.isEmpty() && deJev == null) return
    val alfa = if (apagada) 0.5f else 1f
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (deJev != null && prioridad != null) ChapitaDeJev(prioridad, deJev.second, alfa, motivo)
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

/**
 * La chapita de Jev, en el idioma puesto; con [motivo], un toque o una pulsación larga abre su porqué
 * ([ChapitasDeTarea.pistaDeJev]) en un globo que se cierra tocando fuera.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapitaDeJev(p: Urgencia.Prioridad, c: Color, alfa: Float, motivo: Urgencia.Motivo?) {
    val context = LocalContext.current
    var abierta by remember { mutableStateOf(false) }
    val rotulo = "✦ " + context.getString(ChapitasDeTarea.textoDe(p).id)
    Box {
        Chapita(
            rotulo, fondo = c.copy(alpha = ChapitasDeTarea.fondoDeJev(p) * alfa), texto = c.copy(alpha = alfa),
            modifier = if (motivo != null) Modifier.combinedClickable(onLongClick = { abierta = true }) { abierta = true } else Modifier
        )
        if (motivo != null) {
            DropdownMenu(expanded = abierta, onDismissRequest = { abierta = false }) {
                val pista = remember(motivo, context) {
                    ChapitasDeTarea.pistaDeJev(motivo) { t, a -> if (a == null) context.getString(t.id) else context.getString(t.id, a) }
                }
                Text(
                    pista, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** Una chapita: texto pequeño en una píldora. */
@Composable
private fun Chapita(rotulo: String, fondo: Color, texto: Color, negrita: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        rotulo,
        color = texto,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (negrita) FontWeight.SemiBold else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(50)).then(modifier).background(fondo).padding(horizontal = 7.dp, vertical = 2.dp)
    )
}
