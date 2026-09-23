package com.forge.pixpin.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.motor.Lectura

/**
 * **El riel de marcadores, uno solo para toda la aplicación** (21-sep-2026).
 *
 * Nació en el visor de Word y libros y el usuario lo pidió igual en el lienzo 2D y en el lector de
 * PDF: «que todos tengan la misma interfaz». Así que está aquí, y los tres lo llaman; lo único
 * que cambia es qué significa ir a un marcador, que lo pone cada pantalla.
 *
 * Un punto por marcador, en el orden del documento, cada uno con su emoticono. Se pasa el dedo
 * por ellos —**vibra al cambiar de uno a otro**— y al soltar se va al que quedó debajo. Un toque
 * a secas hace lo mismo. En reposo van pequeños y semitransparentes; con el dedo encima crecen, y
 * **el elegido salta delante del dedo**, hacia dentro de la pantalla, porque debajo del dedo no
 * se veía cuál era.
 */
@Composable
fun RielDeMarcas(
    cuantas: Int,
    emojiDe: (Int) -> String,
    modifier: Modifier = Modifier,
    alElegir: (Int) -> Unit
) {
    if (cuantas <= 0) return
    val vibrar = LocalHapticFeedback.current
    val cuantasYa by rememberUpdatedState(cuantas)
    val elegirYa by rememberUpdatedState(alElegir)
    var bajoElDedo by remember { mutableStateOf(-1) }
    val paso = 38.dp
    Column(
        modifier
            .padding(end = 2.dp)
            .pointerInput(Unit) {
                val pasoPx = paso.toPx()
                awaitEachGesture {
                    val abajo = awaitFirstDown(requireUnconsumed = false)
                    abajo.consume()
                    bajoElDedo = Lectura.puntoBajoElDedo(abajo.position.y, pasoPx, cuantasYa)
                    vibrar.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    while (true) {
                        val e = awaitPointerEvent()
                        val dedo = e.changes.firstOrNull { it.pressed } ?: break
                        dedo.consume()
                        val i = Lectura.puntoBajoElDedo(dedo.position.y, pasoPx, cuantasYa)
                        if (i != bajoElDedo) {
                            bajoElDedo = i
                            vibrar.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    if (bajoElDedo in 0 until cuantasYa) elegirYa(bajoElDedo)
                    bajoElDedo = -1
                }
            },
        horizontalAlignment = Alignment.End
    ) {
        repeat(cuantas) { i ->
            val elegido = i == bajoElDedo
            val conDedo = bajoElDedo >= 0
            Box(Modifier.size(width = if (conDedo) 64.dp else 30.dp, height = paso), contentAlignment = Alignment.CenterEnd) {
                val salto = animateDpAsState(
                    if (elegido) (-78).dp else 0.dp,
                    spring(dampingRatio = 0.6f, stiffness = 700f), label = "salto"
                )
                Box(
                    Modifier
                        .offset(x = salto.value)
                        .size(if (elegido) 52.dp else if (conDedo) 28.dp else 20.dp)
                        .clip(CircleShape)
                        .background(Color(if (elegido) 0xE614182B else 0x6614182B)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        emojiDe(i),
                        fontSize = if (elegido) 30.sp else if (conDedo) 15.sp else 10.sp,
                        modifier = Modifier.alpha(if (conDedo) 1f else 0.75f)
                    )
                }
            }
        }
    }
}

/** La fila de emoticonos para elegir cuál se planta. La misma en los tres sitios. */
@Composable
fun ElegirEmojiDeMarca(
    modifier: Modifier = Modifier,
    /**
     * **Con el marcador verde delante** (23-sep-2026, lectores): «leer desde aquí». Solo hay uno
     * por documento, así que ponerlo lo **mueve** de donde estuviera. Ver [Lectura.EMOJI_DE_VOZ].
     */
    conVerde: Boolean = false,
    onCerrar: () -> Unit,
    onElegir: (String) -> Unit
) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(12.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xD914182B))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (conVerde) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0x3322C55E))
                    .clickable(onClickLabel = "Empezar a leer aquí") { onElegir(Lectura.EMOJI_DE_VOZ) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(Lectura.EMOJI_DE_VOZ, fontSize = 22.sp, color = Color.White)
                Text(" leer aquí", fontSize = 13.sp, color = Color.White)
            }
        }
        Lectura.EMOJIS.forEach { e ->
            Text(e, fontSize = 24.sp, color = Color.White, modifier = Modifier.clip(CircleShape).clickable { onElegir(e) }.padding(8.dp))
        }
        IconButton(onClick = onCerrar, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Cancelar", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}
