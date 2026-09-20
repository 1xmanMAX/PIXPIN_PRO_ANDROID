package com.forge.pixpin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.forge.pixpin.motor.Bounds
import com.forge.pixpin.motor.DrawCanvas
import com.forge.pixpin.motor.DrawController
import com.forge.pixpin.motor.DrawTheme
import com.forge.pixpin.motor.Hojita
import com.forge.pixpin.motor.Scene

/** La pantalla de la hojita. Lo que decide —qué entra en el lienzo al insertar— está en [Hojita]. */
@Composable
fun HojitaDeNotas(
    /** El de la hojita: lo guarda quien la enseña, para que lo anotado siga ahí al cerrarla y abrirla. */
    controlador: DrawController,
    /** El del lienzo grande: de él salen la herramienta y el estilo. */
    delLienzo: DrawController,
    papel: String,
    onPapel: (String) -> Unit,
    imageProvider: (String) -> Bitmap?,
    onCambio: () -> Unit,
    onInsertar: (vista: Bounds) -> Unit,
    onCerrar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pantalla = LocalConfiguration.current
    // **De tamaño limitado**: un tercio de la pantalla, entre 190 y 320 dp de alto.
    val alto = (pantalla.screenHeightDp * 0.34f).coerceIn(190f, 320f).dp
    var tick by remember { mutableIntStateOf(0) }
    var medida by remember { mutableStateOf(0 to 0) }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .height(alto)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(DrawTheme.colorDe(papel)))
            .border(1.5.dp, Color(0xFF8A8F98), RoundedCornerShape(14.dp))
            .onSizeChanged { medida = it.width to it.height }
            // Antes de que el lienzo de la hojita vea el dedo, coge lo que haya puesto en la barra.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.changes.any { it.pressed && !it.previousPressed }) {
                            val herramienta = delLienzo.tool
                            if (herramienta !in Hojita.FUERA && herramienta != controlador.tool) controlador.selectTool(herramienta)
                            if (controlador.scene.style != delLienzo.scene.style) controlador.cambiarEstilo(delLienzo.scene.style) { it }
                        }
                    }
                }
            }
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        DrawCanvas(
            controller = controlador,
            modifier = Modifier.fillMaxSize(),
            imageProvider = imageProvider,
            onChange = { trazando -> if (!trazando) { tick++; onCambio() } }
        )
        Row(
            Modifier.align(Alignment.TopEnd).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // El papel: un toque pasa al siguiente color.
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(DrawTheme.colorDe(Hojita.PAPELES[(Hojita.PAPELES.indexOf(papel) + 1).mod(Hojita.PAPELES.size)])))
                    .border(1.5.dp, Color(0xFF8A8F98), CircleShape)
                    .clickable { onPapel(Hojita.PAPELES[(Hojita.PAPELES.indexOf(papel) + 1).mod(Hojita.PAPELES.size)]) }
            )
            Boton(Icons.Filled.DeleteSweep, "Borrar") {
                controlador.load(Scene(style = controlador.scene.style))
                tick++
                onCambio()
            }
            Boton(Icons.Filled.NoteAdd, "Insertar") {
                val v = controlador.scene.viewport
                val a = v.toScene(0.0, 0.0)
                val b = v.toScene(medida.first.toDouble(), medida.second.toDouble())
                onInsertar(Bounds(a.x, a.y, b.x, b.y))
            }
            Boton(Icons.Filled.Close, null, onCerrar)
        }
    }
}

/** Un botón pequeño de la hojita: oscuro y translúcido, que se lee sobre cualquier papel claro. */
@Composable
private fun Boton(icono: ImageVector, texto: String?, onToque: () -> Unit) {
    Row(
        Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xB314182B))
            .clickable(onClick = onToque)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icono, contentDescription = texto ?: "Cerrar", tint = Color.White, modifier = Modifier.size(16.dp))
        if (texto != null) Text(texto, color = Color.White, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
    }
}
