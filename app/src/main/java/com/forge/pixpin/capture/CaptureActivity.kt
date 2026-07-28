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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.forge.pixpin.annotate.AnnotationCanvas
import com.forge.pixpin.annotate.AnnotationController
import com.forge.pixpin.annotate.AnnotationRenderer
import com.forge.pixpin.annotate.AnnotationType
import com.forge.pixpin.annotate.Pt
import com.forge.pixpin.annotate.StrokeTouchReader
import com.forge.pixpin.clipboard.ContentClassifier
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

    private val controller = AnnotationController()
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

private val PALETTE = listOf(
    0xFFF44336.toInt(), 0xFFFF9800.toInt(), 0xFFFFEB3B.toInt(),
    0xFF4CAF50.toInt(), 0xFF2196F3.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()
)

@OptIn(ExperimentalComposeUiApi::class) // pointerInteropFilter: hace falta el MotionEvent crudo
@Composable
fun CaptureScreen(
    bitmap: Bitmap,
    controller: AnnotationController,
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

    var showTextDialog by remember { mutableStateOf(false) }
    var textPoint by remember { mutableStateOf(Pt(0f, 0f)) }
    var textInput by remember { mutableStateOf("") }

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

        fun toImage(pos: Offset): Pt? {
            if (!imageRect.contains(pos)) return null
            return Pt(
                ((pos.x - imageRect.left) / imgScale).coerceIn(0f, bitmap.width.toFloat()),
                ((pos.y - imageRect.top) / imgScale).coerceIn(0f, bitmap.height.toFloat())
            )
        }

        /**
         * Igual que [toImage] pero sin descartar nada: al dibujar, un punto que
         * cae fuera de la imagen se pega al borde en vez de perderse, o el trazo
         * se cortaría al llegar al final de la foto.
         */
        fun toImagePt(x: Float, y: Float, pressure: Float): Pt = Pt(
            ((x - imageRect.left) / imgScale).coerceIn(0f, bitmap.width.toFloat()),
            ((y - imageRect.top) / imgScale).coerceIn(0f, bitmap.height.toFloat()),
            pressure
        )

        fun selectionToImageRect(sel: Rect): android.graphics.Rect {
            val l = ((sel.left - imageRect.left) / imgScale).coerceIn(0f, bitmap.width - 1f)
            val t = ((sel.top - imageRect.top) / imgScale).coerceIn(0f, bitmap.height - 1f)
            val r = ((sel.right - imageRect.left) / imgScale).coerceIn(l + 1, bitmap.width.toFloat())
            val b = ((sel.bottom - imageRect.top) / imgScale).coerceIn(t + 1, bitmap.height.toFloat())
            return android.graphics.Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }

        /** Hornea el recorte + anotaciones fuera del hilo de UI y entrega el resultado. */
        fun bake(block: suspend (Bitmap) -> Unit) {
            if (busy) return
            val sel = selection ?: imageRect
            busy = true
            scope.launch {
                try {
                    val baked = withContext(Dispatchers.IO) {
                        AnnotationRenderer.bake(
                            bitmap, selectionToImageRect(sel), controller.annotations.toList()
                        )
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

        // Motor de trazo, el mismo que usan los pines al anotar.
        val strokeReader = remember(controller, imageRect) {
            StrokeTouchReader(
                onBegin = { x, y, p -> controller.begin(toImagePt(x, y, p)) },
                onExtend = { x, y, p -> controller.drag(toImagePt(x, y, p)) },
                onFinish = { controller.end() },
                onCancel = { controller.cancel() }
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

        // 2) Anotaciones
        AnnotationCanvas(
            controller = controller,
            sourceBitmap = bitmap,
            imageRectInView = imageRect,
            modifier = Modifier.fillMaxSize()
        )

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
                .pointerInput(annotateMode, controller.tool.value) {
                    if (!annotateMode || controller.tool.value != AnnotationType.TEXT) {
                        return@pointerInput
                    }
                    detectTapGestures { pos ->
                        toImage(pos)?.let {
                            textPoint = it
                            textInput = ""
                            showTextDialog = true
                        }
                    }
                }
                // Dibujo: se lee el MotionEvent en crudo en vez de usar los gestos
                // de Compose, que se comen el arranque del trazo (touch slop) y
                // tiran las muestras intermedias del lápiz. Ver StrokeTouchReader.
                .pointerInteropFilter { event ->
                    if (!annotateMode || controller.tool.value == AnnotationType.TEXT) {
                        false
                    } else {
                        strokeReader.onTouchEvent(event)
                    }
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
                AnnotateToolbar(controller, onDone = { annotateMode = false })
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
                                app.overlayManager.pinImage(path)
                            } else {
                                Toast.makeText(context, R.string.capture_error, Toast.LENGTH_SHORT).show()
                            }
                            onFinish()
                        }
                    },
                    onAnnotate = { annotateMode = true },
                    onSave = {
                        bake { baked ->
                            val uri = withContext(Dispatchers.IO) { Export.saveToGallery(context, baked) }
                            Toast.makeText(
                                context,
                                if (uri != null) R.string.saved_to_gallery else R.string.capture_error,
                                Toast.LENGTH_SHORT
                            ).show()
                            onFinish()
                        }
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

        // 8) Diálogo de texto de anotación
        if (showTextDialog) {
            AlertDialog(
                onDismissRequest = { showTextDialog = false },
                title = { Text(stringResource(R.string.add_text_title)) },
                text = {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        controller.addText(textPoint, textInput)
                        showTextDialog = false
                    }) { Text(stringResource(R.string.add)) }
                },
                dismissButton = {
                    TextButton(onClick = { showTextDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

// ---- Lupa con color picker ----

@Composable
private fun Magnifier(
    bitmap: Bitmap,
    touchPos: Offset,
    toImage: (Offset) -> Pt?
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
    onSave: () -> Unit,
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
            ToolbarButton(Icons.Filled.Save, active = false, onClick = onSave)
            ToolbarButton(Icons.Filled.ContentCopy, active = false, onClick = onCopy)
            ToolbarButton(Icons.Filled.Share, active = false, onClick = onShare)
            ToolbarButton(Icons.Filled.Close, active = false, onClick = onClose)
        }
    }
}

@Composable
private fun AnnotateToolbar(controller: AnnotationController, onDone: () -> Unit) {
    val tools = listOf(
        AnnotationType.RECT to Icons.Filled.CropSquare,
        AnnotationType.ELLIPSE to Icons.Filled.RadioButtonUnchecked,
        AnnotationType.ARROW to Icons.Filled.NorthEast,
        AnnotationType.PENCIL to Icons.Filled.Gesture,
        AnnotationType.HIGHLIGHT to Icons.Filled.Highlight,
        AnnotationType.MOSAIC to Icons.Filled.BlurOn,
        AnnotationType.TEXT to Icons.Filled.TextFields,
        AnnotationType.SERIAL to Icons.Filled.FormatListNumbered,
        AnnotationType.POLYLINE to Icons.Filled.Timeline,
        AnnotationType.SPOTLIGHT to Icons.Filled.CenterFocusStrong,
        AnnotationType.ERASER to Icons.Filled.AutoFixNormal
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PALETTE.forEach { c ->
                    val selected = controller.color.value == c
                    Box(
                        Modifier
                            .padding(3.dp)
                            .size(if (selected) 26.dp else 22.dp)
                            .background(Color(c), CircleShape)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                shape = CircleShape
                            )
                            .pointerInput(c) {
                                detectTapGestures { controller.color.value = c }
                            }
                    )
                }
                ToolbarButton(Icons.Filled.Undo, active = false, enabled = controller.canUndo.value) {
                    controller.undo()
                }
                ToolbarButton(Icons.Filled.Redo, active = false, enabled = controller.canRedo.value) {
                    controller.redo()
                }
                ToolbarButton(Icons.Filled.Delete, active = false) { controller.clearAll() }
            }
        }
        Card(shape = RoundedCornerShape(28.dp)) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                tools.forEach { (type, icon) ->
                    ToolbarButton(
                        icon,
                        active = controller.tool.value == type,
                        onClick = { controller.selectTool(type) }
                    )
                }
                // Solo mientras haya una polilínea a medias: es su forma de cerrarse.
                if (controller.polylineOpen) {
                    ToolbarButton(
                        Icons.Filled.CheckCircle,
                        active = true,
                        onClick = { controller.finishPolyline() }
                    )
                }
                ToolbarButton(Icons.Filled.Done, active = true, onClick = onDone)
            }
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
