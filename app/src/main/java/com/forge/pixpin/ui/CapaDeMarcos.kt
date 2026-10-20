package com.forge.pixpin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.motor.MarcoDoc
import com.forge.pixpin.motor.MarcosDoc

/**
 * **Los marcos numerados encima de un visor** (20-oct-2026, PC `marcos_doc.rs`): raya azul `#0078D4`
 * de 2 dp, el número blanco en un cuadrito azul en la esquina de arriba a la izquierda y, con el modo
 * puesto, las fichas 1, 2, 3… abajo a la izquierda (tocar una va a ese marco) con la pista y los
 * mandos que en el PC son teclas: imprimir (Ctrl+P), quitar el último (Supr) y borrar todos.
 *
 * Cada visor dice cómo pasar de su documento a la pantalla ([aPantalla]) y de vuelta
 * ([aDocumento]), cuántos píxeles mide una unidad ([px]) y en qué parte se está ([parte], la hoja
 * de un libro de Excel; 0 si no hay). Se pinta en la fase de dibujo leyendo esas funciones: si leen
 * estado de Compose (el desplazamiento, el zoom), la capa se repinta sola sin recomponer.
 *
 * Con el modo puesto la capa se queda con el dedo: uno arrastra un marco; dos mueven y acercan el
 * documento por [alDesplazar] (lo que en el PC es la rueda). Sin el modo no toca ningún gesto.
 */
@Composable
fun CapaDeMarcos(
    marcos: MarcosDoc,
    /** Sube cuando cambia la lista o el modo (lo lleva quien tiene los marcos). */
    version: Int,
    aPantalla: (Double, Double) -> Offset,
    aDocumento: (Offset) -> DoubleArray?,
    px: () -> Double,
    parte: () -> Int = { 0 },
    alCambiar: () -> Unit,
    alIrA: (Int) -> Unit,
    alImprimir: () -> Unit,
    alDesplazar: ((pan: Offset, zoom: Float, centro: Offset) -> Unit)? = null,
    /** Hueco abajo (una barra del visor que ya ocupa el canto). */
    abajo: androidx.compose.ui.unit.Dp = 12.dp
) {
    var arrastre by remember { mutableIntStateOf(0) }
    // El gesto no se reinicia al recomponer (el visor recompone al desplazarse): lee lo último.
    val aDoc by rememberUpdatedState(aDocumento)
    val pxYa by rememberUpdatedState(px)
    val parteYa by rememberUpdatedState(parte)
    val cambiado by rememberUpdatedState(alCambiar)
    val desplazar by rememberUpdatedState(alDesplazar)
    @Suppress("UNUSED_EXPRESSION") version
    val activo = marcos.activo
    Box(Modifier.fillMaxSize()) {
    Canvas(
        Modifier.fillMaxSize().then(
            if (!activo) Modifier else Modifier.pointerInput(marcos) {
                awaitEachGesture {
                    val abajo1 = awaitFirstDown(requireUnconsumed = false)
                    abajo1.consume()
                    var conDos = false
                    aDoc(abajo1.position)?.let { marcos.pulsar(it, parteYa()) }
                    arrastre++
                    while (true) {
                        val e = awaitPointerEvent()
                        val vivos = e.changes.filter { it.pressed }
                        if (vivos.isEmpty()) {
                            val ultimo = e.changes.firstOrNull()
                            if (!conDos && ultimo != null) {
                                val p = aDoc(ultimo.position)
                                if (p != null && marcos.soltar(p, pxYa())) cambiado() else marcos.cancelar()
                            } else marcos.cancelar()
                            arrastre++
                            break
                        }
                        if (vivos.size >= 2) {
                            // Dos dedos: fuera el marco a medias, y a mover el documento.
                            if (!conDos) { conDos = true; marcos.cancelar(); arrastre++ }
                            desplazar?.invoke(e.calculatePan(), e.calculateZoom(), e.calculateCentroid(useCurrent = true))
                        } else if (!conDos) {
                            val d = vivos.first()
                            if (d.positionChanged()) aDoc(d.position)?.let { marcos.mover(it); arrastre++ }
                        }
                        e.changes.forEach { it.consume() }
                    }
                }
            }
        )
    ) {
        @Suppress("UNUSED_EXPRESSION") arrastre
        val azul = Color(MarcosDoc.AZUL)
        val raya = 2.dp.toPx()
        val p = parte()
        val numero = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; textSize = 12.dp.toPx(); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        fun caja(m: MarcoDoc): Pair<Offset, Size> {
            val a = aPantalla(m.x0, m.y0)
            val b = aPantalla(m.x1, m.y1)
            return Offset(minOf(a.x, b.x), minOf(a.y, b.y)) to Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
        }
        marcos.lista.forEachIndexed { i, m ->
            if (m.parte != p) return@forEachIndexed
            val (o, s) = caja(m)
            if (o.x > size.width || o.y > size.height || o.x + s.width < 0 || o.y + s.height < 0) return@forEachIndexed
            drawRect(azul, o, s, style = Stroke(raya))
            val n = (i + 1).toString()
            val w = numero.measureText(n) + 12.dp.toPx()
            drawRect(azul, o, Size(w, 20.dp.toPx()))
            drawIntoCanvas { it.nativeCanvas.drawText(n, o.x + 6.dp.toPx(), o.y + 15.dp.toPx(), numero) }
        }
        marcos.cajaNueva()?.takeIf { it.parte == p }?.let { m ->
            val (o, s) = caja(m)
            drawRect(azul, o, s, style = Stroke(1.5.dp.toPx()))
            drawRect(azul.copy(alpha = 0.08f), o, s)
        }
    }
    if (activo) FichasDeMarcos(
        cuantos = marcos.lista.size, alIrA = alIrA, alImprimir = alImprimir,
        alQuitarUltimo = { if (marcos.quitarUltimo()) alCambiar() },
        alBorrar = { marcos.borrar(); alCambiar() },
        modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = abajo)
    )
    }
}

/** Las fichas 1, 2, 3… y, a su derecha, la pista y los mandos. Las mismas en todos los visores. */
@Composable
fun FichasDeMarcos(
    cuantos: Int, alIrA: (Int) -> Unit, alImprimir: (() -> Unit)?, alQuitarUltimo: (() -> Unit)?, alBorrar: (() -> Unit)?,
    pista: String? = null,
    modifier: Modifier = Modifier
) {
    val azul = Color(MarcosDoc.AZUL)
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until cuantos) Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(azul).clickable(onClickLabel = "Ir al marco ${i + 1}") { alIrA(i) },
            contentAlignment = Alignment.Center
        ) { Text("${i + 1}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color(0xE6141417)).padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(pista ?: if (cuantos == 0) "Arrastra para poner un marco" else MarcosDoc.PISTA, color = Color.White, fontSize = 12.sp)
            if (cuantos > 0 && (alImprimir != null || alQuitarUltimo != null || alBorrar != null)) {
                alImprimir?.let { BotonDeBarra(Icons.Filled.Print, "Imprimir los marcos", false, lado = 34, alTocar = it) }
                alQuitarUltimo?.let { BotonDeBarra(Icons.AutoMirrored.Filled.Undo, "Quitar el último", false, lado = 34, alTocar = it) }
                alBorrar?.let { BotonDeBarra(Icons.Filled.DeleteSweep, "Borrar los marcos", false, lado = 34, alTocar = it) }
            } else androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp, 34.dp))
        }
    }
}
