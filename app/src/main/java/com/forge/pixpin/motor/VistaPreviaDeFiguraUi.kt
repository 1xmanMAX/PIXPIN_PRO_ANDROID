package com.forge.pixpin.motor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
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
 * **La vista rápida**, a pantalla completa. [onUsar] es lo que el panel se abrió para hacer
 * (insertarla en el lienzo, adjuntarla al chat) y [rotuloDeUsar] cómo se dice.
 */
@Composable
fun VistaPreviaDeFiguraUi(
    figura: FiguraGuardada,
    rotuloDeUsar: String,
    onUsar: () -> Unit,
    onCerrar: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember { Renderer(typefaces = DrawFonts.provider(context)) }
    val escena = remember(figura) { Scene(elements = figura.elementos) }
    val caja = remember(figura) { getCommonBounds(figura.elementos.filter { !it.isDeleted }) }
    // El encuadre: null hasta conocer el tamaño de la pantalla (lo pone [onSizeChanged]).
    var vista by remember(figura) { mutableStateOf<Viewport?>(null) }
    var encajada by remember(figura) { mutableStateOf(Viewport()) }
    val categoria = FigurasDeSerie.categoriaDe(figura.nombre)?.first

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCerrar,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF2B2B2E))) {
            // **El papel de día detrás, siempre**: es como queda sobre el lienzo de día, y la de
            // noche trae su propia hoja oscura encima. Sin depender del tema de la aplicación.
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(8.dp).background(Color.White)
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
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width.toDouble()
                    val h = size.height.toDouble()
                    if (w <= 0 || h <= 0) return@Canvas
                    val v = vista ?: return@Canvas
                    drawIntoCanvas { c ->
                        renderer.renderScene(c.nativeCanvas, escena.copy(viewport = v), w, h)
                    }
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        FigurasDeSerie.nombreCorto(figura.nombre),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(categoria, "Pellizca para acercar · doble toque para encajar").joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        OutlinedButton(onClick = onCerrar) { Text("Cerrar") }
                        Button(onClick = onUsar) { Text(rotuloDeUsar) }
                    }
                }
            }
        }
    }
}
