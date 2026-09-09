package com.forge.pixpin.motor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * **La hoja adhesiva**: una hoja pequeña que sale de un tirón, se garabatea y se pega donde sea.
 *
 * Lo pidió el usuario el 8-sep-2026: «una hoja pequeña que sale jalando los tres dedos para
 * arriba, que pueda anotar lo que quiera y pegarlo en cualquier parte como una pegatina».
 *
 * **Por qué es una hoja aparte y no dibujar en el lienzo.** Lo que se apunta en un adhesivo es
 * de otra clase que lo que se dibuja en el plano: es un recado, va encima, y se mueve entero.
 * Dibujado suelto sobre el lienzo habría que rodearlo y agruparlo para poder moverlo, y a los
 * dos minutos ya no se sabe qué rayas eran del recado. Al pegarse entra como **una sola cosa**,
 * que se arrastra, se gira y se borra de una vez, como cualquier imagen.
 *
 * Se dibuja aquí a mano y no con el motor entero a propósito: en un adhesivo se garabatea, no
 * se hace geometría, y traerse el motor pediría capas, selección y deshacer para una hoja de
 * dos dedos de alto. Ver [alBitmap], que es lo que se pega.
 */
@Composable
fun NotaAdhesiva(
    /** El color del papel. El amarillo de siempre, porque un adhesivo se reconoce por eso. */
    papel: Color = Color(0xFFFFF3A3),
    tinta: Color = Color(0xFF1F1B12),
    onPegar: (trazos: List<List<Offset>>, ancho: Int, alto: Int, papel: Int, tinta: Int) -> Unit,
    onCerrar: () -> Unit
) {
    val trazos = remember { mutableStateListOf<List<Offset>>() }
    var enCurso by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var medida by remember { mutableStateOf(0 to 0) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(papel, RoundedCornerShape(10.dp))
            .padding(6.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .pointerInput(Unit) {
                    medida = size.width to size.height
                    detectDragGestures(
                        onDragStart = { enCurso = listOf(it) },
                        onDrag = { cambio, _ -> enCurso = enCurso + cambio.position },
                        // Un trazo de un punto no es un trazo: es haber apoyado el dedo.
                        onDragEnd = { if (enCurso.size > 1) trazos.add(enCurso); enCurso = emptyList() },
                        onDragCancel = { enCurso = emptyList() }
                    )
                }
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                medida = size.width.toInt() to size.height.toInt()
                (trazos + listOf(enCurso)).forEach { t ->
                    for (i in 1 until t.size) {
                        drawLine(tinta, t[i - 1], t[i], strokeWidth = 4f, cap = Stroke.DefaultCap)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCerrar) { Text("Cerrar") }
            // Deshacer el último trazo: en una hoja de recados es lo único que se necesita, y
            // más barato que traerse la pila de deshacer del motor entero.
            TextButton(
                onClick = { if (trazos.isNotEmpty()) trazos.removeAt(trazos.size - 1) }
            ) { Text("Deshacer") }
            TextButton(
                onClick = {
                    if (trazos.isNotEmpty()) {
                        onPegar(trazos.toList(), medida.first, medida.second, papel.valor(), tinta.valor())
                    }
                }
            ) { Text("Pegar") }
        }
    }
}

private fun Color.valor(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

/**
 * El adhesivo como mapa de bits, listo para pegarlo en el lienzo.
 *
 * Se dibuja a **[ESCALA] veces** lo que medía en pantalla: la hoja se pega en un lienzo que se
 * amplía, y una pegatina hecha a los píxeles exactos de la pantalla se ve blanda en cuanto uno
 * se acerca a leerla — que es justo lo que se hace con un recado.
 */
fun alBitmap(
    trazos: List<List<Offset>>,
    ancho: Int,
    alto: Int,
    papel: Int,
    tinta: Int
): Bitmap? {
    if (ancho <= 0 || alto <= 0) return null
    val bmp = Bitmap.createBitmap(ancho * ESCALA, alto * ESCALA, Bitmap.Config.ARGB_8888)
    val lienzo = android.graphics.Canvas(bmp)
    val fondo = android.graphics.Paint().apply { color = papel; isAntiAlias = true }
    val radio = 10f * ESCALA
    lienzo.drawRoundRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), radio, radio, fondo)
    val pincel = android.graphics.Paint().apply {
        color = tinta
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f * ESCALA
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    for (t in trazos) {
        if (t.size < 2) continue
        val camino = android.graphics.Path()
        camino.moveTo(t[0].x * ESCALA, t[0].y * ESCALA)
        for (i in 1 until t.size) camino.lineTo(t[i].x * ESCALA, t[i].y * ESCALA)
        lienzo.drawPath(camino, pincel)
    }
    return bmp
}

/** A cuántas veces su tamaño en pantalla se dibuja el adhesivo. Ver [alBitmap]. */
private const val ESCALA = 3
