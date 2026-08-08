package com.forge.pixpin.capture

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.annotate.StrokeTouchReader
import com.forge.pixpin.clipboard.ContentClassifier
import com.forge.pixpin.motor.DrawController
import com.forge.pixpin.motor.DrawFonts
import com.forge.pixpin.motor.DrawToolbar
import com.forge.pixpin.motor.DialogoEscala
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Renderer
import com.forge.pixpin.motor.Tool
import com.forge.pixpin.motor.Viewport
import com.forge.pixpin.motor.estiloAplicado
import com.forge.pixpin.motor.longitudDe
import com.forge.pixpin.pin.ImageStore
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Pantalla de captura: el fotograma congelado a pantalla completa, recorte
 * directo con el dedo y una única barra de acciones que aparece junto a la
 * selección. Sin menús ni diálogos intermedios: arrastrar → Pin.
 */
class CaptureActivity : ComponentActivity() {

    /**
     * **El mismo motor que el pin y el editor.**
     *
     * Esta pantalla era lo último que quedaba con el motor de anotación viejo:
     * sus once herramientas, su barra y su renderizador, todo paralelo a lo del
     * resto de la aplicación. Se notaba al usarla —lo aprendido en el pin no
     * valía aquí— y se notaba al mantenerla, porque cada arreglo había que
     * hacerlo dos veces. Ahora anota, mide y exporta con el mismo motor.
     */
    private val controller = DrawController().apply {
        // Sobre una captura se viene a señalar: se entra con la flecha, igual
        // que en el pin.
        selectTool(Tool.ARROW)
    }
    private var frame: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bitmap = FrameHolder.take()
        if (bitmap == null) {
            finish()
            return
        }
        frame = bitmap
        // Inmersiva: la selección cubre también la barra de estado y la de gestos.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            PixPinTheme {
                CaptureScreen(
                    bitmap = bitmap,
                    controller = controller,
                    onFinish = { finish() }
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        frame?.let { if (!it.isRecycled) it.recycle() }
        frame = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalComposeUiApi::class) // pointerInteropFilter: hace falta el MotionEvent crudo
@Composable
fun CaptureScreen(
    bitmap: Bitmap,
    controller: DrawController,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var annotateMode by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Rect?>(null) }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var dragAnchor by remember { mutableStateOf(Offset.Zero) }
    var busy by remember { mutableStateOf(false) }

    var magnifierPos by remember { mutableStateOf<Offset?>(null) }
    var pickedColor by remember { mutableIntStateOf(0) }

    // El controlador no es estado de Compose —copiar la escena entera en cada
    // punto del lápiz saldría carísimo—, así que este contador es lo que ata los
    // dos mundos. Mismo truco que en `DrawCanvas` y en el pin.
    //
    // Se guarda el **objeto de estado**, no su valor: así lo lee cada trozo de
    // interfaz que dependa del motor, y subirlo repinta el lienzo y refresca la
    // barra sin recomponer la pantalla entera a cada muestra del lápiz.
    val tick = remember { mutableIntStateOf(0) }

    BackHandler {
        when {
            annotateMode -> annotateMode = false
            selection != null -> selection = null
            else -> onFinish()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()

        // Rect de la imagen centrada (fit-center) en coordenadas de vista
        val fitScale = min(viewW / bitmap.width, viewH / bitmap.height)
        val dispW = bitmap.width * fitScale
        val dispH = bitmap.height * fitScale
        val imageRect = Rect(
            (viewW - dispW) / 2, (viewH - dispH) / 2,
            (viewW + dispW) / 2, (viewH + dispH) / 2
        )
        val imgScale = imageRect.width / bitmap.width // imagen px → vista px

        fun toImage(pos: Offset): Offset? {
            if (!imageRect.contains(pos)) return null
            return Offset(
                ((pos.x - imageRect.left) / imgScale).coerceIn(0f, bitmap.width.toFloat()),
                ((pos.y - imageRect.top) / imgScale).coerceIn(0f, bitmap.height.toFloat())
            )
        }

        /**
         * De la pantalla a **coordenadas de escena del motor**, que aquí son
         * píxeles de la captura.
         *
         * Sin descartar nada, al revés que [toImage]: al dibujar, un punto que
         * cae fuera de la imagen se pega al borde en vez de perderse, o el trazo
         * se cortaría al llegar al final de la foto.
         */
        fun toEscena(x: Float, y: Float): Pt = Pt(
            ((x - imageRect.left) / imgScale).coerceIn(0f, bitmap.width.toFloat()).toDouble(),
            ((y - imageRect.top) / imgScale).coerceIn(0f, bitmap.height.toFloat()).toDouble()
        )

        fun selectionToImageRect(sel: Rect): android.graphics.Rect {
            val l = ((sel.left - imageRect.left) / imgScale).coerceIn(0f, bitmap.width - 1f)
            val t = ((sel.top - imageRect.top) / imgScale).coerceIn(0f, bitmap.height - 1f)
            val r = ((sel.right - imageRect.left) / imgScale).coerceIn(l + 1, bitmap.width.toFloat())
            val b = ((sel.bottom - imageRect.top) / imgScale).coerceIn(t + 1, bitmap.height.toFloat())
            return android.graphics.Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }

        /**
         * Hornea el recorte + anotaciones fuera del hilo de UI y entrega el
         * resultado.
         *
         * De paso lo **guarda siempre** en `Pictures/PixPin`: ya no hay botón de
         * guardar. Si has hecho algo con la captura —fijarla, copiarla,
         * compartirla— es que la querías; y si no la querías, la cierras con la
         * ✕ y entonces no se guarda nada.
         */
        fun bake(block: suspend (Bitmap) -> Unit) {
            if (busy) return
            val sel = selection ?: imageRect
            busy = true
            scope.launch {
                try {
                    val baked = withContext(Dispatchers.IO) {
                        Export.horneaCaptura(
                            context, bitmap, controller.scene, selectionToImageRect(sel)
                        )
                    }
                    val saved = withContext(Dispatchers.IO) { Export.saveToGallery(context, baked) }
                    if (saved == null) {
                        Toast.makeText(context, R.string.capture_error, Toast.LENGTH_SHORT).show()
                    }
                    block(baked)
                    if (!baked.isRecycled) baked.recycle()
                } catch (t: Throwable) {
                    Toast.makeText(context, R.string.capture_error, Toast.LENGTH_SHORT).show()
                } finally {
                    busy = false
                }
            }
        }

        // **Se conserva `StrokeTouchReader`**, igual que en el pin: trae el
        // rechazo de palma y las muestras históricas del lápiz —un digitalizador
        // va a cientos de hercios pero solo entrega un evento por fotograma—, y
        // con los gestos de Compose se perderían las dos cosas. Lo único que
        // cambia respecto a antes es a quién se las entrega.
        val strokeReader = remember(controller, imageRect) {
            // El motor necesita saber dónde se levantó el dedo y `onFinish` no
            // trae coordenadas; se recuerda el último punto entregado.
            var ultimo = Pt(0.0, 0.0)
            // Cuántos píxeles de pantalla ocupa un píxel de la foto: con esto
            // los umbrales de toque —picar, enganchar— miden lo mismo bajo el
            // dedo esté la captura como esté encajada.
            val zoom = imgScale.toDouble()
            StrokeTouchReader(
                onBegin = { x, y, p ->
                    ultimo = toEscena(x, y)
                    controller.pointerDown(ultimo, p.toDouble(), zoom)
                    tick.intValue++
                },
                onExtend = { x, y, p ->
                    ultimo = toEscena(x, y)
                    controller.pointerMove(ultimo, p.toDouble(), zoom)
                    tick.intValue++
                },
                onFinish = {
                    controller.pointerUp(ultimo, zoom)
                    tick.intValue++
                    // El bote no encontró hueco cerrado. Se dice: si no, parece
                    // que la herramienta no funciona.
                    if (controller.rellenoSinCerrar) {
                        controller.limpiarAvisoRelleno()
                        Toast.makeText(
                            context, R.string.relleno_sin_cerrar, Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onCancel = { controller.cancel(); tick.intValue++ },
                // El segundo dedo apoyado pide forma perfecta.
                onModifier = { activo ->
                    controller.keepAspectRatio = activo
                    tick.intValue++
                }
            )
        }

        // 1) Fotograma congelado (lo que se ve es exactamente lo que se recorta)
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .offset { IntOffset(imageRect.left.toInt(), imageRect.top.toInt()) }
                .size(
                    with(density) { dispW.toDp() },
                    with(density) { dispH.toDp() }
                )
        )

        // 2) Anotaciones, con el motor
        //
        // La escena está en píxeles de la captura, así que verla encima es
        // desplazar el lienzo hasta la esquina de la imagen y escalar por lo que
        // la imagen ocupa. Sin más conversiones: es la ventaja de haber elegido
        // ese sistema de coordenadas, la misma que aprovecha el horneado.
        val renderer = remember(bitmap, context) {
            Renderer(
                typefaces = DrawFonts.provider(context),
                // De aquí saca el mosaico sus píxeles: la propia captura.
                backdrop = bitmap
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") tick
            drawIntoCanvas { lienzo ->
                val nativo = lienzo.nativeCanvas
                nativo.save()
                nativo.translate(imageRect.left, imageRect.top)
                renderer.renderScene(
                    nativo,
                    controller.scene.copy(viewport = Viewport(zoom = imgScale.toDouble())),
                    dispW.toDouble(),
                    dispH.toDouble()
                )
                nativo.restore()
            }
        }

        // 3) Máscara de selección
        if (!annotateMode) {
            Canvas(Modifier.fillMaxSize()) {
                val sel = selection
                val dim = Color.Black.copy(alpha = if (sel == null) 0.35f else 0.55f)
                if (sel == null) {
                    drawRect(dim, size = Size(size.width, size.height))
                    return@Canvas
                }
                drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, sel.top))
                drawRect(dim, topLeft = Offset(0f, sel.bottom), size = Size(size.width, size.height - sel.bottom))
                drawRect(dim, topLeft = Offset(0f, sel.top), size = Size(sel.left, sel.height))
                drawRect(dim, topLeft = Offset(sel.right, sel.top), size = Size(size.width - sel.right, sel.height))
                drawRect(
                    Color(0xFF29B8DB),
                    topLeft = Offset(sel.left, sel.top),
                    size = Size(sel.width, sel.height),
                    style = Stroke(width = 3f)
                )
                val handleR = 11.dp.toPx()
                for (corner in listOf(
                    Offset(sel.left, sel.top), Offset(sel.right, sel.top),
                    Offset(sel.left, sel.bottom), Offset(sel.right, sel.bottom)
                )) {
                    drawCircle(Color.White, handleR, corner)
                    drawCircle(Color(0xFF29B8DB), handleR, corner, style = Stroke(width = 4f))
                }
            }
        }

        // 4) Capa de gestos
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(annotateMode) {
                    if (annotateMode) return@pointerInput
                    detectTapGestures(
                        onTap = { if (selection == null) selection = imageRect }
                    )
                }
                .pointerInput(annotateMode) {
                    if (annotateMode) return@pointerInput
                    detectDragGestures(
                        onDragStart = { pos ->
                            dragMode = SelectionGeometry.classifyDrag(pos, selection, density.density)
                            dragAnchor = SelectionGeometry.anchorFor(dragMode, selection, pos)
                            if (dragMode == DragMode.NEW) selection = Rect(pos, pos)
                            magnifierPos = pos
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val sel = selection ?: return@detectDragGestures
                            selection = SelectionGeometry.update(
                                dragMode, sel, dragAnchor, change.position, amount, imageRect
                            )
                            magnifierPos = change.position
                            toImage(change.position)?.let {
                                pickedColor = bitmap.getPixel(it.x.toInt(), it.y.toInt())
                            }
                        },
                        onDragEnd = {
                            dragMode = DragMode.NONE
                            magnifierPos = null
                        }
                    )
                }
                // Dibujo: se lee el MotionEvent en crudo en vez de usar los gestos
                // de Compose, que se comen el arranque del trazo (touch slop) y
                // tiran las muestras intermedias del lápiz. Ver StrokeTouchReader.
                //
                // **Todas las herramientas pasan por aquí, también el texto.** En
                // el motor, tocar con el texto puesto crea el elemento y deja su
                // id pedido; el teclado lo abre la barra al verlo. Antes el texto
                // necesitaba su propio detector de toques porque el motor viejo
                // no sabía crearlo sin uno.
                .pointerInteropFilter { event ->
                    if (!annotateMode) false else strokeReader.onTouchEvent(event)
                }
        )

        // 5) Lupa + color mientras se ajusta el recorte
        magnifierPos?.let { pos ->
            Magnifier(bitmap = bitmap, touchPos = pos, toImage = { toImage(it) })
        }

        // 6) Pista inicial
        if (selection == null && !annotateMode) {
            Text(
                text = stringResource(R.string.capture_hint),
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xAA000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        // 7) Barra única de acciones
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (annotateMode) {
                BarraDeAnotar(controller, tick, onDone = { annotateMode = false })
            } else {
                if (magnifierPos != null || pickedColor != 0) {
                    val hex = ContentClassifier.toHex(pickedColor)
                    val copiedMessage = stringResource(R.string.copied_hex, hex)
                    ColorChip(pickedColor) {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("color", hex))
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                ActionBar(
                    onPin = {
                        bake { baked ->
                            val path = withContext(Dispatchers.IO) {
                                ImageStore.saveBitmap(
                                    context, baked, "pin_${System.currentTimeMillis()}.png"
                                )
                            }
                            if (path != null) {
                                val sel = selection ?: imageRect
                                val screenW = context.resources.displayMetrics.widthPixels
                                val defaultNaturalW = minOf(baked.width, (screenW * 0.6f).toInt()).coerceAtLeast(1)
                                val scale = sel.width / defaultNaturalW
                                app.overlayManager.pinImage(
                                    path,
                                    x = sel.left.toInt(),
                                    y = sel.top.toInt(),
                                    scale = scale
                                )
                                Toast.makeText(context, R.string.saved_to_gallery, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, R.string.capture_error, Toast.LENGTH_SHORT).show()
                            }
                            onFinish()
                        }
                    },
                    onAnnotate = { annotateMode = true },
                    onScroll = {
                        // La zona está en coordenadas de view. ScrollCaptureController captura
                        // de la pantalla en vivo, no del fotograma congelado, así que necesita
                        // coordenadas de pantalla sin transformar.
                        val sel = selection ?: imageRect
                        ScrollCaptureController.request(context, android.graphics.Rect(
                            sel.left.toInt(),
                            sel.top.toInt(),
                            sel.right.toInt(),
                            sel.bottom.toInt()
                        ))
                        onFinish()
                    },
                    onCopy = {
                        bake { baked ->
                            val ok = withContext(Dispatchers.IO) { Export.copyToClipboard(context, baked) }
                            if (ok) {
                                Toast.makeText(context, R.string.copied_image, Toast.LENGTH_SHORT).show()
                            }
                            onFinish()
                        }
                    },
                    onShare = {
                        bake { baked ->
                            withContext(Dispatchers.IO) { Export.prepareShare(context, baked) }
                                ?.let { Export.share(context, it) }
                            onFinish()
                        }
                    },
                    onClose = onFinish
                )
            }
        }

    }
}

/**
 * La barra de dibujo de la captura: **la misma que el pin y el editor**.
 *
 * Lee el contador aquí dentro y no en la pantalla entera a propósito: mientras
 * el dedo dibuja esto sube decenas de veces por segundo, y recomponer desde
 * arriba arrastraría también el fotograma congelado, la máscara de recorte y la
 * lupa. Mismo trato que en la barrita del pin.
 *
 * De paso cuelgan de aquí las dos cosas que el motor pide por su cuenta: el
 * teclado del texto recién puesto y la medida de la raya de escalar.
 */
@Composable
private fun BarraDeAnotar(
    controller: DrawController,
    tick: androidx.compose.runtime.MutableIntState,
    onDone: () -> Unit
) {
    @Suppress("UNUSED_EXPRESSION") tick.intValue
    val context = LocalContext.current

    fun cambiado() {
        tick.intValue++
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        controller.pendingScaleElement()?.let { cota ->
            DialogoEscala(
                largoPx = longitudDe(cota),
                onCalibrar = { medida, unidad ->
                    controller.applyScale(medida, unidad)
                    cambiado()
                },
                onCancelar = { controller.cancelScale(); cambiado() },
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        DrawToolbar(
            tool = controller.tool,
            onTool = { controller.selectTool(it); cambiado() },
            style = controller.scene.style,
            onStyle = { nuevo ->
                controller.changeStyle({ nuevo }, { estiloAplicado(it, nuevo) })
                cambiado()
            },
            canUndo = controller.canUndo,
            onUndo = { controller.undo(); cambiado() },
            onDone = onDone,
            escala = controller.scene.escala,
            onQuitarEscala = { controller.clearScale(); cambiado() },
            // Las guías **también aquí**. Era el único sitio de los cuatro donde
            // faltaban, y es donde más falta hacen: sobre una captura se traza
            // encima de algo que ya está, así que apoyar el trazo en una guía es
            // el caso normal, no el raro.
            modoReferencia = controller.modoReferencia,
            onModoReferencia = {
                controller.modoReferencia = !controller.modoReferencia
                cambiado()
            },
            referenciasVisibles = controller.referenciasVisibles,
            onAlternarReferencias = { controller.alternarReferencias(); cambiado() },
            hayReferencias = controller.hayReferencias
        )
    }

    // El texto recién plantado pide teclado. Aquí sí cabe un campo de verdad
    // —esto es una actividad con foco, no la ventana flotante del pin—, así que
    // se escribe en un diálogo normal y corriente.
    val textoId = controller.pendingTextId
    if (textoId != null) {
        // Con lo que ya hubiera: tocar un texto puesto lo abre para corregirlo,
        // y empezar en blanco sería borrarlo sin querer.
        var escrito by remember(textoId) {
            mutableStateOf(controller.scene.byId(textoId)?.text.orEmpty())
        }
        fun cerrar(guardar: Boolean) {
            val e = controller.scene.byId(textoId)
            if (guardar && escrito.isNotBlank() && e != null) {
                val (ancho, alto) = DrawFonts.medirTexto(
                    context, escrito, e.fontFamily, e.fontSize ?: 20.0
                )
                controller.updateText(textoId, escrito, ancho, alto)
            } else {
                // Un texto vacío no deja rastro: sería un elemento invisible que
                // roba toques al picar.
                controller.setSelection(setOf(textoId))
                controller.deleteSelection()
            }
            controller.clearPendingText()
            cambiado()
        }
        AlertDialog(
            onDismissRequest = { cerrar(false) },
            title = { Text(stringResource(R.string.add_text_title)) },
            text = {
                OutlinedTextField(
                    value = escrito,
                    onValueChange = { escrito = it },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { cerrar(true) }) { Text(stringResource(R.string.add)) }
            },
            dismissButton = {
                TextButton(onClick = { cerrar(false) }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

// ---- Lupa con color picker ----

@Composable
private fun Magnifier(
    bitmap: Bitmap,
    touchPos: Offset,
    toImage: (Offset) -> Offset?
) {
    val imgPt = toImage(touchPos) ?: return
    val density = LocalDensity.current
    val offsetY = with(density) { 150.dp.toPx() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.offset {
            IntOffset(
                (touchPos.x - with(density) { 48.dp.toPx() }).toInt(),
                (touchPos.y - offsetY).toInt().coerceAtLeast(0)
            )
        }
    ) {
        Canvas(
            Modifier
                .size(96.dp)
                .border(3.dp, Color.White, CircleShape)
        ) {
            val radius = size.minDimension / 2
            val srcRadius = 7
            val cx = imgPt.x.toInt()
            val cy = imgPt.y.toInt()
            val path = Path().apply {
                addOval(Rect(0f, 0f, radius * 2, radius * 2))
            }
            clipPath(path) {
                drawImage(
                    image = bitmap.asImageBitmap(),
                    srcOffset = IntOffset(
                        (cx - srcRadius).coerceAtLeast(0),
                        (cy - srcRadius).coerceAtLeast(0)
                    ),
                    srcSize = IntSize(srcRadius * 2, srcRadius * 2),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.None
                )
                drawLine(
                    Color(0xFF29B8DB),
                    Offset(radius, 0f), Offset(radius, size.height),
                    strokeWidth = 2f
                )
                drawLine(
                    Color(0xFF29B8DB),
                    Offset(0f, radius), Offset(size.width, radius),
                    strokeWidth = 2f
                )
            }
        }
    }
}

@Composable
private fun ColorChip(argb: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(bottom = 8.dp)
            .background(Color(0xCC1A1A1A), RoundedCornerShape(16.dp))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(Color(argb), CircleShape)
                .border(1.dp, Color.White, CircleShape)
        )
        Text(
            ContentClassifier.toHex(argb),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

// ---- Barras de herramientas ----

@Composable
private fun ActionBar(
    onPin: () -> Unit,
    onAnnotate: () -> Unit,
    onScroll: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit
) {
    Card(shape = RoundedCornerShape(30.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onPin,
                shape = RoundedCornerShape(24.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 18.dp, vertical = 10.dp
                )
            ) {
                Icon(Icons.Filled.PushPin, contentDescription = null)
                Text(
                    text = stringResource(R.string.action_pin),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            ToolbarButton(Icons.Filled.Edit, active = false, onClick = onAnnotate)
            ToolbarButton(Icons.Filled.ExpandMore, active = false, onClick = onScroll)
            ToolbarButton(Icons.Filled.ContentCopy, active = false, onClick = onCopy)
            ToolbarButton(Icons.Filled.Share, active = false, onClick = onShare)
            ToolbarButton(Icons.Filled.Close, active = false, onClick = onClose)
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
