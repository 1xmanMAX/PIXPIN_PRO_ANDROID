package com.forge.pixpin.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.motor.DrawCanvas
import com.forge.pixpin.motor.DrawController
import com.forge.pixpin.motor.DrawToolbar
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.LECTOR_TOOLS_FUERA
import com.forge.pixpin.motor.PdfDelProyecto
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.Tool
import com.forge.pixpin.motor.Viewport
import com.forge.pixpin.pin.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **El editor rápido**: anotar una página de un PDF **sin salir del lector** (20-sep-2026).
 *
 * El requisito del usuario fue uno y tajante: **el mismo motor del lienzo**. Ni otras
 * herramientas, ni otras tintas, ni otro formato. Así que esto no es un editor: es el
 * [DrawCanvas] y la [DrawToolbar] de siempre, con la página de telón y una barra recortada a lo
 * que se haya elegido en Ajustes → «Herramientas del editor rápido» (arrastrando, como las demás).
 *
 * Y anota **en el mismo dibujo que el editor completo**: la hoja del proyecto de ese PDF, con sus
 * mismas coordenadas (la página a [PdfDoc.PAGE_WIDTH] de ancho). Lo que se raye aquí se abre luego
 * desde proyectos y se mueve, se borra o se recolorea como cualquier otra cosa, y al revés. Son
 * dos formas de mirar el mismo dibujo: una rápida y una avanzada. Al cerrar —o al pasar de
 * página— se guarda el dibujo y el PDF del proyecto se rehace **en segundo plano**.
 *
 * Lo que queda fuera son las herramientas que necesitan la pantalla del editor completo: texto,
 * imagen, zona, escalar, marcos y lupa ([LECTOR_TOOLS_FUERA]).
 */
@Composable
fun EditorRapido(
    app: PixPinApp,
    proyecto: Proyecto,
    pagina: Int,
    cuantas: Int,
    onPagina: (Int) -> Unit,
    /** Se cierra. [cambiado] dice si hay que volver a pintar el documento. */
    onCerrar: (cambiado: Boolean) -> Unit
) {
    val alcance = rememberCoroutineScope()
    val ajustes by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    // La hoja de esta página, y su dibujo: el mismo que abre el editor completo.
    val dibujo = remember(proyecto.id, pagina) {
        val actual = app.proyectos.porId(proyecto.id) ?: proyecto
        val hoja = actual.hojas.firstOrNull { it.pagina == pagina }
        hoja?.dibujo ?: "dib-${System.currentTimeMillis()}-$pagina".also { nuevo ->
            if (hoja != null) app.proyectos.guardar(Proyectos.conDibujo(actual, hoja.id, nuevo, System.currentTimeMillis()))
        }
    }
    val controlador = remember(dibujo) {
        DrawController(ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(app, dibujo)) ?: Scene()).also {
            it.pedirLaMedida = false
            it.selectTool(Tool.FREEDRAW)
        }
    }
    // El telón: la página **limpia**, que lo anotado ya lo pinta el lienzo encima.
    val fondo by produceState<Bitmap?>(null, proyecto.id, pagina) {
        value = withContext(Dispatchers.IO) {
            val archivo = proyecto.pdfLimpio?.takeIf { java.io.File(it).exists() } ?: proyecto.pdfOrigen
            archivo?.let { runCatching { PdfDoc.render(it, pagina, PdfDoc.PAGE_WIDTH) }.getOrNull() }
        }
    }
    var medida by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableIntStateOf(0) }
    var huboCambios by remember { mutableStateOf(false) }
    var sucio by remember(dibujo) { mutableStateOf(false) }
    // Al entrar, la página a lo ancho y desde arriba.
    LaunchedEffect(dibujo, fondo != null, medida) {
        val papel = fondo ?: return@LaunchedEffect
        if (medida.width <= 0) return@LaunchedEffect
        controlador.setViewport(Viewport(scrollX = 0.0, scrollY = 0.0, zoom = medida.width.toDouble() / papel.width))
        tick++
    }
    val fotos = remember(dibujo) { HashMap<String, Bitmap?>() }

    /** Guarda lo anotado y rehace el PDF del proyecto, fuera del hilo de la pantalla. */
    fun guardar() {
        if (!sucio) return
        sucio = false
        huboCambios = true
        val escena = controlador.scene
        val id = dibujo
        alcance.launch(Dispatchers.IO) {
            runCatching { ExcalidrawStore.guardar(app, id, escena) }
            val real = app.proyectos.porId(proyecto.id) ?: proyecto
            runCatching { PdfDelProyecto.rehacer(app, real) { f -> escena.files[f]?.path?.let { ImageStore.load(it) } } }
        }
    }
    fun cerrar() {
        val cambiado = huboCambios || sucio
        guardar()
        onCerrar(cambiado)
    }
    BackHandler { cerrar() }

    Box(Modifier.fillMaxSize().background(Color(0xFF1B1B1B)).onSizeChanged { medida = it }) {
        @Suppress("UNUSED_EXPRESSION") tick
        DrawCanvas(
            controller = controlador,
            modifier = Modifier.fillMaxSize(),
            imageProvider = { f -> fotos.getOrPut(f) { controlador.scene.files[f]?.path?.let { ImageStore.load(it) } } },
            onChange = { trazando -> if (!trazando) { sucio = true; tick++ } },
            backdrop = fondo,
            papelALaVista = true
        )
        // Arriba: listo, y de qué página a qué página.
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xB314182B))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Boton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Página anterior", pagina > 0) { guardar(); onPagina(pagina - 1) }
            Text("${pagina + 1} / $cuantas", color = Color.White, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp))
            Boton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Página siguiente", pagina < cuantas - 1) { guardar(); onPagina(pagina + 1) }
            Boton(Icons.Filled.Check, "Listo", true) { cerrar() }
        }
        // Abajo: la barra de siempre, con lo elegido en Ajustes.
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp, start = 6.dp, end = 6.dp)) {
            com.forge.pixpin.ui.theme.SuperficieDeCristal(Modifier, RoundedCornerShape(22.dp)) {
                DrawToolbar(
                    tool = controlador.tool,
                    onTool = { controlador.selectTool(it); tick++ },
                    style = controlador.scene.style,
                    onStyle = { nuevo -> controlador.cambiarEstilo(nuevo) { it }; sucio = true; tick++ },
                    canUndo = controlador.canUndo,
                    onUndo = { controlador.undo(); sucio = true; tick++ },
                    permitidas = ajustes.lectorToolSet - LECTOR_TOOLS_FUERA,
                    grupos = ajustes.lectorGroupList.map { g -> g.filterNot { it in LECTOR_TOOLS_FUERA } }.filter { it.isNotEmpty() }
                )
            }
        }
    }
}

@Composable
private fun Boton(icono: ImageVector, descripcion: String, activo: Boolean, onToque: () -> Unit) {
    Icon(
        icono, contentDescription = descripcion,
        tint = Color.White.copy(alpha = if (activo) 1f else 0.3f),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = activo, onClick = onToque).padding(9.dp).size(22.dp)
    )
}
