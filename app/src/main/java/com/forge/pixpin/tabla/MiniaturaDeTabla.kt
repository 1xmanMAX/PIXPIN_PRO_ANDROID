package com.forge.pixpin.tabla

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forge.pixpin.motor.TablaDeCalculo
import com.forge.pixpin.motor.TablaViva
import com.forge.pixpin.motor.TablasEnDisco
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **La miniatura de una tabla**: su esquina de arriba a la izquierda, ya calculada, pintada en
 * un solo lienzo —nada de un `Text` por celda, que en una tira de veinte hojas serían mil—.
 * Se rehace cuando la tabla se guarda ([TablaActivity.revision]). La usan la rejilla de los
 * proyectos y la burbuja del chat.
 */
@Composable
fun MiniaturaDeTabla(id: String, grande: Boolean = false, modifier: Modifier = Modifier.fillMaxSize()) {
    val contexto = LocalContext.current
    val revision = TablaActivity.revision(id)
    MiniaturaDe(id to revision, grande, modifier) { TablasEnDisco.de(contexto.filesDir).cargar(id) }
}

/**
 * La de una **mini-app `tabla` del chat** (las del PC): la tabla es el [texto] del mensaje, y
 * cambia cuando él cambia.
 */
@Composable
fun MiniaturaDeTablaEnTexto(texto: String, grande: Boolean = false, modifier: Modifier = Modifier.fillMaxSize()) {
    MiniaturaDe(texto, grande, modifier) { TablaDeCalculo.deJson(texto) }
}

@Composable
private fun MiniaturaDe(clave: Any, grande: Boolean, modifier: Modifier, cargar: () -> TablaDeCalculo?) {
    val filas = if (grande) 18 else 9
    val cols = if (grande) 6 else 4
    val celdas by produceState<List<List<Pair<String, Char>>>?>(null, clave, grande) {
        value = withContext(Dispatchers.IO) {
            val t = cargar() ?: return@withContext emptyList()
            val v = TablaViva(t)
            (0 until minOf(maxOf(v.calc.filas, 1), filas)).map { f ->
                (0 until minOf(maxOf(v.calc.cols, 1), cols)).map { c ->
                    val s = v.visto(f, c)
                    s to (if (s.isEmpty()) 'i' else v.estilo(f, c)?.a?.firstOrNull() ?: v.calc.alineacion(f, c))
                }
            }
        }
    }
    val tinta = MaterialTheme.colorScheme.onSurface.toArgb()
    val raya = MaterialTheme.colorScheme.outlineVariant
    val cabecera = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val pintura = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val lista = celdas ?: return
    Canvas(modifier.clipToBounds().padding(6.dp)) {
        val alto = size.height / filas
        val ancho = size.width / cols
        drawRect(cabecera, size = Size(size.width, alto))
        for (f in 0..filas) drawLine(raya, Offset(0f, f * alto), Offset(size.width, f * alto), 1f)
        for (c in 0..cols) drawLine(raya, Offset(c * ancho, 0f), Offset(c * ancho, size.height), 1f)
        pintura.color = tinta
        pintura.textSize = alto * 0.58f
        val lienzo = drawContext.canvas.nativeCanvas
        val medio = (pintura.descent() + pintura.ascent()) / 2
        for ((f, fila) in lista.withIndex()) for ((c, celda) in fila.withIndex()) {
            val (s, al) = celda
            if (s.isEmpty()) continue
            val x0 = c * ancho
            val m = pintura.measureText(s)
            val x = when {
                al == 'd' && m < ancho - 6f -> x0 + ancho - 3f - m
                al == 'c' && m < ancho - 6f -> x0 + (ancho - m) / 2
                else -> x0 + 3f
            }
            lienzo.save()
            lienzo.clipRect(x0 + 1f, f * alto, x0 + ancho - 1f, (f + 1) * alto)
            lienzo.drawText(s, x, f * alto + alto / 2 - medio, pintura)
            lienzo.restore()
        }
    }
}
