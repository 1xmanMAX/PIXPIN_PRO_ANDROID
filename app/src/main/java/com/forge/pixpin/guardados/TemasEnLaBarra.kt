package com.forge.pixpin.guardados

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **Los temas y las fichas de la reunión en la barra de la grabación** (29-oct-2026; la barra de «Grabar
 * clase» del PC: «☑ 2/5», la lista debajo y las fichas; `docs/jev.md` §5 paso 2 y §8, y «Android», paso 4).
 *
 * - [Boton]: «☑ 2/5» en la barra; abre o pliega la lista.
 * - [Panel]: debajo de la barra, la lista con una casilla por tema (**un toque marca o desmarca a mano**,
 *   y Jev ya no lo toca), lo de Jev con ✓ y «Jev · 12:30», la fila recién marcada iluminada en verde, y
 *   arriba cómo va la escucha (o por qué no: sin clave, apagado, sin reconocedor, Jev no contesta…). Debajo,
 *   **las tres fichas más nuevas**: tareas y eventos propuestos (✓ y ✕), los cambios de urgencia ya
 *   aplicados («Deshacer») y el tema olvidado.
 *
 * Lo mismo en la burbuja flotante de [ReunionPresencialService], en la barra de la grabación en la app
 * ([ReunionEnLaApp]) y, en grande ([ListaGrande]), en la pantalla completa ([ReunionEnCursoActivity]). Sin
 * reunión con Jev no pinta nada.
 * Sin `return` antes de tiempo (R8).
 */
object TemasEnLaBarra {

    private val VERDE = Color(0xFF22A06B)
    private val AMBAR = Color(0xFFF0A020)
    private val FONDO = Color(0xEE1E1E22)

    /** «☑ 2/5»: solo con temas. */
    @Composable
    fun Boton(claro: Color = Color.White) {
        val e by ReunionConJev.estado.collectAsState()
        if (e.activa && e.temas.isNotEmpty()) {
            val (hechos, total) = TemasDeLaReunion.cuenta(e.temas)
            TextButton(onClick = { ReunionConJev.alternarLista() }) {
                Text(
                    "☑ $hechos/$total", color = if (e.brillo != null) VERDE else claro, fontSize = 13.sp,
                    fontWeight = if (e.abierta) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }

    /** La lista (si está abierta), el aviso y las fichas, debajo de la barra. */
    @Composable
    fun Panel(modifier: Modifier = Modifier) {
        val e by ReunionConJev.estado.collectAsState()
        val claro = Color.White
        if (e.activa) Column(modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (e.abierta && e.temas.isNotEmpty()) {
                Column(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(14.dp)).background(FONDO).padding(8.dp)) {
                    e.cabecera?.let { Text(it, color = claro.copy(alpha = 0.7f), fontSize = 11.sp) }
                    if (e.parcial.isNotBlank()) Text(
                        "«${e.parcial}»", color = claro.copy(alpha = 0.55f), fontSize = 11.sp, fontStyle = FontStyle.Italic,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(e.temas, key = { it.id }) { t -> Fila(t, e.brillo == t.id) }
                    }
                }
            }
            e.aviso?.let {
                Text(
                    it, color = claro, fontSize = 12.sp, maxLines = 2,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(FONDO).padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            for (f in e.fichas.asReversed().take(ReunionConJev.FICHAS_A_LA_VISTA)) Ficha(f)
        }
    }

    /**
     * **La lista en grande** (29-oct-2026, la pantalla completa de la grabación: «que me aparezca lo que voy a
     * decir, la lista… de forma que sea más grande»): todos los temas con su casilla, a [escala] (A−/A+), la
     * cabecera de la escucha y las fichas de Jev. Sin temas, [vacio].
     */
    @Composable
    fun ListaGrande(escala: Float, vacio: String, modifier: Modifier = Modifier) {
        val e by ReunionConJev.estado.collectAsState()
        val claro = Color.White
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            e.cabecera?.let { Text(it, color = claro.copy(alpha = 0.7f), fontSize = (12 * escala).sp) }
            if (e.temas.isEmpty()) Text(vacio, color = claro.copy(alpha = 0.6f), fontSize = (14 * escala).sp)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(e.temas, key = { it.id }) { t -> Fila(t, e.brillo == t.id, escala) }
            }
            for (f in e.fichas.asReversed().take(ReunionConJev.FICHAS_A_LA_VISTA)) Ficha(f)
        }
    }

    @Composable
    private fun Fila(t: TemasDeLaReunion.Tema, brilla: Boolean, escala: Float = 1f) {
        val claro = Color.White
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(if (brilla) VERDE.copy(alpha = 0.35f) else Color.Transparent)
                .clickable { ReunionConJev.marcar(t.id) }.padding(horizontal = 4.dp, vertical = (2 * escala).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (t.hecho) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, contentDescription = null,
                tint = if (t.hecho) VERDE else claro.copy(alpha = 0.8f), modifier = Modifier.size((20 * escala).dp)
            )
            Text(
                t.texto, color = if (t.hecho) claro.copy(alpha = 0.6f) else claro, fontSize = (13 * escala).sp,
                modifier = Modifier.padding(start = 6.dp).weight(1f), maxLines = if (escala > 1f) 4 else 2, overflow = TextOverflow.Ellipsis
            )
            val ms = t.ms
            if (t.hecho && ms != null) Text(
                (if (t.porJev) "Jev · " else "") + GrabarClase.minuto(ms), color = if (t.porJev) VERDE else claro.copy(alpha = 0.6f),
                fontSize = (11 * escala).sp, modifier = Modifier.padding(start = 6.dp)
            )
        }
    }

    @Composable
    private fun Ficha(f: ReunionConJev.Ficha) {
        val claro = Color.White
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(FONDO).padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                f.texto, color = if (f.tipo == ReunionConJev.TipoDeFicha.PROPUESTA) claro else AMBAR, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
            when (f.tipo) {
                ReunionConJev.TipoDeFicha.PROPUESTA -> IconButton(onClick = { ReunionConJev.fichaSi(f.id) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = TextosDeLaReunion.ACEPTAR, tint = VERDE)
                }
                ReunionConJev.TipoDeFicha.CAMBIO -> TextButton(onClick = { ReunionConJev.fichaSi(f.id) }) {
                    Text(TextosDeLaReunion.DESHACER, color = AMBAR, fontSize = 12.sp)
                }
                ReunionConJev.TipoDeFicha.AVISO -> {}
            }
            IconButton(onClick = { ReunionConJev.fichaNo(f.id) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = claro.copy(alpha = 0.7f))
            }
        }
    }
}
