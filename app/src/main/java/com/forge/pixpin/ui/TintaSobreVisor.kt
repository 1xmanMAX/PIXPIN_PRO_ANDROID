package com.forge.pixpin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forge.pixpin.motor.DrawController
import com.forge.pixpin.motor.Viewport

/**
 * **La tinta encima de un visor que no es un lienzo** (20-oct-2026: Excel y los pines de imagen, como
 * `lector_tinta` del PC). Las unidades de la tinta son las del documento (los píxeles de la hoja al
 * 100 %, los de la imagen…); el visor dice qué vista le toca ([vistaDelVisor]).
 *
 * - **Leyendo**, solo se pinta, en la fase de dibujo (desplazarse no recompone): no coge ningún toque.
 * - **Anotando**, el [com.forge.pixpin.motor.DrawCanvas] de siempre con la barra del editor rápido:
 *   un dedo dibuja y dos mueven y acercan, **y el visor sigue al lienzo** fotograma a fotograma
 *   ([alSeguir]), como el visor de Word.
 */
@Composable
fun TintaSobreVisor(
    controlador: DrawController?,
    anotando: Boolean,
    /** Sube cuando el visor se mueve (lo lee solo el dibujo). */
    pulso: Int,
    vistaDelVisor: () -> Viewport,
    alSeguir: (Viewport) -> Unit,
    alCambiar: () -> Unit,
    alAcabar: () -> Unit,
    oscuro: Boolean = false,
    /** La vista no se mueve (la tinta de un marcador 3D va clavada a la pantalla). */
    vistaFija: Boolean = false
) {
    val c = controlador ?: return
    val contexto = LocalContext.current
    val pintor = remember(oscuro) { com.forge.pixpin.motor.Renderer(typefaces = com.forge.pixpin.motor.DrawFonts.provider(contexto.applicationContext), dark = oscuro) }
    var tick by remember { mutableIntStateOf(0) }
    if (!anotando) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") pulso
            @Suppress("UNUSED_EXPRESSION") tick
            if (c.scene.elements.none { !it.isDeleted }) return@Canvas
            pintor.renderScene(drawContext.canvas.nativeCanvas, c.scene.copy(viewport = vistaDelVisor()), size.width.toDouble(), size.height.toDouble())
        }
        return
    }
    LaunchedEffect(c) {
        c.setViewport(vistaDelVisor())
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            alSeguir(c.scene.viewport)
        }
    }
    val app = contexto.applicationContext as? com.forge.pixpin.PixPinApp
    val ajustes by (app?.settings?.settings ?: kotlinx.coroutines.flow.flowOf(com.forge.pixpin.data.Settings())).collectAsState(initial = com.forge.pixpin.data.Settings())
    Box(Modifier.fillMaxSize()) {
        com.forge.pixpin.motor.DrawCanvas(
            controller = c,
            modifier = Modifier.fillMaxSize(),
            dark = oscuro,
            vistaFija = vistaFija,
            onChange = { trazando -> if (!trazando) { tick++; alCambiar() } }
        )
        Text(
            "Listo", color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
                .clip(RoundedCornerShape(50)).background(Color(0xB314182B)).clickable { alAcabar() }
                .padding(horizontal = 16.dp, vertical = 9.dp)
        )
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp, start = 6.dp, end = 6.dp)) {
            @Suppress("UNUSED_EXPRESSION") tick
            com.forge.pixpin.ui.theme.SuperficieDeCristal(Modifier, RoundedCornerShape(22.dp)) {
                com.forge.pixpin.motor.DrawToolbar(
                    tool = c.tool,
                    onTool = { c.selectTool(it); tick++ },
                    style = c.scene.style,
                    onStyle = { nuevo -> c.cambiarEstilo(nuevo) { it }; tick++ },
                    canUndo = c.canUndo,
                    onUndo = { c.undo(); tick++; alCambiar() },
                    permitidas = ajustes.lectorToolSet - com.forge.pixpin.motor.LECTOR_TOOLS_FUERA,
                    grupos = ajustes.lectorGroupList.map { g -> g.filterNot { it in com.forge.pixpin.motor.LECTOR_TOOLS_FUERA } }.filter { it.isNotEmpty() }
                )
            }
        }
    }
}
