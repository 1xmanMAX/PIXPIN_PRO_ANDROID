package com.forge.pixpin.motor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * **La vista rápida**, dentro de la tarjeta de figuras (3-nov-2026, el usuario: «que la
 * previsualización esté dentro de la ventana que sale de abajo y no aparezca como ventana; al
 * agrandarse, que no se salga de su contenedor»). [onUsar] es lo que el panel se abrió para hacer
 * (insertarla en el lienzo, adjuntarla al chat) y [rotuloDeUsar] cómo se dice; [onCerrar] vuelve a
 * la lista.
 */
@Composable
fun VistaPreviaDeFiguraUi(
    figura: FiguraGuardada,
    rotuloDeUsar: String,
    onUsar: () -> Unit,
    onCerrar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember { Renderer(typefaces = DrawFonts.provider(context)) }
    val escena = remember(figura) { Scene(elements = FigurasDeSerie.paraVer(figura.elementos)) }
    val caja = remember(figura) { getCommonBounds(figura.elementos.filter { !it.isDeleted }) }
    // El encuadre: null hasta conocer el tamaño del recuadro (lo pone [onSizeChanged]).
    var vista by remember(figura) { mutableStateOf<Viewport?>(null) }
    var encajada by remember(figura) { mutableStateOf(Viewport()) }
    val categoria = FigurasDeSerie.categoriaDe(figura.nombre)?.first
    // Atrás vuelve a la lista, no cierra la tarjeta.
    androidx.activity.compose.BackHandler(onBack = onCerrar)

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCerrar) { Text("‹ " + androidx.compose.ui.res.stringResource(com.forge.pixpin.R.string.ip_figuras)) }
            Column(Modifier.weight(1f)) {
                Text(
                    FigurasDeSerie.nombreCorto(figura.nombre),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (categoria != null) Text(
                    categoria, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
        }
        // **El papel de día detrás, siempre**: es como queda sobre el lienzo de día, y la de noche
        // trae su propia hoja oscura encima. Recortado a su recuadro: al acercar, lo que sobra no
        // se pinta encima de la tarjeta.
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .background(Color.White)
                .clipToBounds()
                .onSizeChanged { t ->
                    encajada = VistaPreviaDeFigura.encajar(caja, t.width.toDouble(), t.height.toDouble())
                    // Al girar el teléfono se vuelve a encajar: lo acercado ya no cae en su sitio.
                    vista = encajada
                }
                .pointerInput(figura) {
                    detectTransformGestures { centro, desplazamiento, factor, _ ->
                        vista?.let { v ->
                            vista = VistaPreviaDeFigura.pellizcar(
                                v, encajada, factor.toDouble(),
                                Pt(centro.x.toDouble(), centro.y.toDouble()),
                                desplazamiento.x.toDouble(), desplazamiento.y.toDouble()
                            )
                        }
                    }
                }
                .pointerInput(figura) {
                    detectTapGestures(onDoubleTap = { p ->
                        vista?.let { v ->
                            vista = VistaPreviaDeFigura.dobleToque(v, encajada, Pt(p.x.toDouble(), p.y.toDouble()))
                        }
                    })
                }
        ) {
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                val w = size.width.toDouble()
                val h = size.height.toDouble()
                if (w <= 0 || h <= 0) return@Canvas
                val v = vista ?: return@Canvas
                clipRect {
                    drawIntoCanvas { c ->
                        renderer.renderScene(c.nativeCanvas, escena.copy(viewport = v), w, h)
                    }
                }
            }
        }
        Text(
            "Pellizca para acercar · doble toque para encajar",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            OutlinedButton(onClick = onCerrar) { Text(androidx.compose.ui.res.stringResource(com.forge.pixpin.R.string.ip_cerrar)) }
            Button(onClick = onUsar, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = AZUL_IPHONE, contentColor = Color.White)) { Text(rotuloDeUsar) }
        }
    }
}
