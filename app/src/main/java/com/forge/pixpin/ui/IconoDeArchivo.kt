package com.forge.pixpin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.motor.ColorDeExtension

/**
 * **El icono de un archivo: una hoja de su color con la esquina doblada y la extensión escrita**
 * (30-sep-2026), como los de Telegram (`media_doc_*`). El color sale de [ColorDeExtension]: rojo
 * los PDF, azul los Word, amarillo los planos…
 */
@Composable
fun IconoDeArchivo(nombre: String?, modifier: Modifier = Modifier, lado: Dp = 44.dp) {
    val familia = remember(nombre) { ColorDeExtension.de(nombre) }
    val rotulo = remember(nombre) { ColorDeExtension.rotulo(nombre) }
    val color = Color(0xFF000000 or familia.color.toLong())
    Box(modifier.size(lado), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = w * 0.14f
            val pliegue = w * 0.3f
            // La hoja: esquinas redondeadas salvo la de arriba a la derecha, que va cortada.
            val hoja = Path().apply {
                moveTo(r, 0f)
                lineTo(w - pliegue, 0f)
                lineTo(w, pliegue)
                lineTo(w, h - r)
                quadraticTo(w, h, w - r, h)
                lineTo(r, h)
                quadraticTo(0f, h, 0f, h - r)
                lineTo(0f, r)
                quadraticTo(0f, 0f, r, 0f)
                close()
            }
            drawPath(hoja, color)
            // El doblez: un triángulo más claro, con su puntita redondeada.
            val doblez = Path().apply {
                moveTo(w - pliegue, 0f)
                lineTo(w - pliegue, pliegue - r * 0.6f)
                quadraticTo(w - pliegue, pliegue, w - pliegue + r * 0.6f, pliegue)
                lineTo(w, pliegue)
                close()
            }
            drawPath(doblez, Color.White.copy(alpha = 0.38f))
        }
        if (rotulo.isNotEmpty()) {
            val tam = when {
                rotulo.length <= 3 -> lado.value * 0.3f
                else -> lado.value * 0.25f
            }
            Text(
                rotulo,
                color = if (familia.textoOscuro) Color(0xFF3A2B00) else Color.White,
                fontSize = tam.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.align(Alignment.Center).padding(top = lado * 0.16f)
            )
        }
    }
}
