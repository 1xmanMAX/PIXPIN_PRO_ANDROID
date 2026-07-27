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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop54
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.annotate.AnnotationCanvas
import com.forge.pixpin.annotate.AnnotationController
import com.forge.pixpin.annotate.AnnotationRenderer
import com.forge.pixpin.annotate.AnnotationType
import com.forge.pixpin.annotate.Pt
import com.forge.pixpin.clipboard.ContentClassifier
import com.forge.pixpin.pin.ImageStore
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Pantalla de captura: fotograma congelado, selección de región con tiradores
 * y lupa/color picker, modo anotación y acciones (pin/guardar/copiar/compartir).
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
        // Inmersiva: cubrir también la barra de estado para seleccionar a pantalla completa
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

    override fun onDestroy() {
        frame?.let { if (!it.isRecycled) it.recycle() }
        frame = null
        super.onDestroy()
    }
}

private enum class DragMode { NONE, NEW, MOVE, TL, TR, BL, BR }

private val PALETTE = listOf(
    0xFFF44336.toInt(), 0xFFFF9800.toInt(), 0xFFFFEB3B.toInt(),
    0xFF4CAF50.toInt(), 0xFF2196F3.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()
)

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
    var lockSquare by remember { mutableStateOf(false) }
    var roundedCorners by remember { mutableStateOf(false) }

    var magnifierPos by remember { mutableStateOf<Offset?>(null) }
    var pickedColor by remember { mutableIntStateOf(0) }
    var hasPickedColor by remember { mutableStateOf(false) }

    var showTextDialog by remember { mutableStateOf(false) }
    var textPoint by remember { mutableStateOf(Pt(0f, 0f)) }
    var textInput by remember { mutableStateOf("") }

    BackHandler {
        if (annotateMode) annotateMode = false else onFinish()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(if (annotateMode) Color.Black else Color.Transparent)
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

        if (selection == null) {
            selection = Rect(
                imageRect.left + dispW * 0.1f, imageRect.top + dispH * 0.15f,
                imageRect.right - dispW * 0.1f, imageRect.bottom - dispH * 0.15f
            )
        }

        fun toImage(pos: Offset): Pt? {
            if (!imageRect.contains(pos)) return null
            return Pt(
                ((pos.x - imageRect.left) / imgScale).coerceIn(0f, bitmap.width.toFloat()),
                ((pos.y - imageRect.top) / imgScale).coerceIn(0f, bitmap.height.toFloat())
            )
        }

        fun selectionToImageRect(sel: Rect): android.graphics.Rect {
            val l = ((sel.left - imageRect.left) / imgScale).coerceIn(0f, bitmap.width - 1f)
            val t = ((sel.top - imageRect.top) / imgScale).coerceIn(0f, bitmap.height - 1f)
            val r = ((sel.right - imageRect.left) / imgScale).coerceIn(l + 1, bitmap.width.toFloat())
            val b = ((sel.bottom - imageRect.top) / imgScale).coerceIn(t + 1, bitmap.height.toFloat())
            return android.graphics.Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }

        fun bake(block: (Bitmap) -> Unit) {
            val sel = selection ?: return
            scope.launch {
                val baked = withContext(Dispatchers.IO) {
                    AnnotationRenderer.bake(
                        bitmap, selectionToImageRect(sel),
                        controller.annotations.toList(),
                        if (roundedCorners) 48f else 0f
                    )
                }
                block(baked)
            }
        }

        // 1) Fotograma: solo en modo anotación; en selección se ve la pantalla viva
        if (annotateMode) {
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
        }

        // 2) Anotaciones (siempre visibles)
        AnnotationCanvas(
            controller = controller,
            sourceBitmap = bitmap,
            imageRectInView = imageRect,
            modifier = Modifier.fillMaxSize()
        )

        // 3) Overlay de selección (dim + borde + tiradores)
        if (!annotateMode) {
            selection?.let { sel ->
                Canvas(Modifier.fillMaxSize()) {
                    val dim = Color.Black.copy(alpha = 0.55f)
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
                    val handleR = 12.dp.toPx()
                    for (corner in listOf(
                        Offset(sel.left, sel.top), Offset(sel.right, sel.top),
                        Offset(sel.left, sel.bottom), Offset(sel.right, sel.bottom)
                    )) {
                        drawCircle(Color.White, handleR, corner)
                        drawCircle(Color(0xFF29B8DB), handleR, corner, style = Stroke(width = 4f))
                    }
                }
            }
        }

        // 4) Capa de gestos
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(annotateMode) {
                    if (annotateMode) return@pointerInput
                    detectDragGestures(
                        onDragStart = { pos ->
                            dragMode = classifyDrag(pos, selection, imageRect, density.density)
                            if (dragMode == DragMode.NEW) selection = Rect(pos, pos)
                            magnifierPos = pos
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val sel = selection ?: return@detectDragGestures
                            selection = updateSelection(
                                dragMode, sel, pos = change.position, amount = amount,
                                bounds = imageRect, square = lockSquare
                            )
                            magnifierPos = change.position
                            toImage(change.position)?.let {
                                pickedColor = bitmap.getPixel(it.x.toInt(), it.y.toInt())
                                hasPickedColor = true
                            }
                        },
                        onDragEnd = {
                            dragMode = DragMode.NONE
                            magnifierPos = null
                        }
                    )
                }
                .pointerInput(annotateMode, controller.tool.value) {
                    if (!annotateMode) return@pointerInput
                    if (controller.tool.value == AnnotationType.TEXT) {
                        detectTapGestures { pos ->
                            toImage(pos)?.let {
                                textPoint = it
                                textInput = ""
                                showTextDialog = true
                            }
                        }
                    } else {
                        detectDragGestures(
                            onDragStart = { pos -> toImage(pos)?.let { controller.begin(it) } },
                            onDrag = { change, _ ->
                                change.consume()
                                toImage(change.position)?.let { controller.drag(it) }
                            },
                            onDragEnd = { controller.end() }
                        )
                    }
                }
        )

        // 5) Lupa + color
        magnifierPos?.let { pos ->
            Magnifier(
                bitmap = bitmap,
                touchPos = pos,
                imageRect = imageRect,
                toImage = { toImage(it) }
            )
        }

        // 6) Barra de acciones / herramientas
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasPickedColor && !annotateMode) {
                ColorChip(pickedColor) {
                    val hex = ContentClassifier.toHex(pickedColor)
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("color", hex))
                    Toast.makeText(context, context.getString(R.string.copied_hex, hex), Toast.LENGTH_SHORT).show()
                }
            }
            if (annotateMode) {
                AnnotateToolbar(controller, onDone = { annotateMode = false })
            } else {
                ActionToolbar(
                    lockSquare = lockSquare,
                    roundedCorners = roundedCorners,
                    onToggleSquare = { lockSquare = !lockSquare },
                    onToggleRounded = { roundedCorners = !roundedCorners },
                    onAnnotate = { annotateMode = true },
                    onPin = {
                        bake { baked ->
                            val path = ImageStore.saveBitmap(
                                context, baked, "pin_${System.currentTimeMillis()}.png"
                            )
                            app.overlayManager.pinImage(path)
                            Toast.makeText(context, R.string.pinned, Toast.LENGTH_SHORT).show()
                            onFinish()
                        }
                    },
                    onSave = {
                        bake { baked ->
                            val uri = Export.saveToGallery(context, baked)
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
                            if (Export.copyToClipboard(context, baked)) {
                                Toast.makeText(context, R.string.copied_image, Toast.LENGTH_SHORT).show()
                            }
                            onFinish()
                        }
                    },
                    onShare = {
                        bake { baked ->
                            Export.share(context, baked)
                            onFinish()
                        }
                    },
                    onClose = onFinish
                )
            }
        }

        // 7) Diálogo de texto
        if (showTextDialog) {
            AlertDialog(
                onDismissRequest = { showTextDialog = false },
                title = { Text(stringResourceText(context, R.string.add_text_title)) },
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
                    }) { Text(stringResourceText(context, R.string.add)) }
                },
                dismissButton = {
                    TextButton(onClick = { showTextDialog = false }) {
                        Text(stringResourceText(context, R.string.cancel))
                    }
                }
            )
        }
    }
}

// ---- Selección: clasificación y actualización ----

private fun classifyDrag(pos: Offset, sel: Rect?, bounds: Rect, density: Float): DragMode {
    if (sel == null) return DragMode.NEW
    val zone = 40f * density
    val corners = listOf(
        DragMode.TL to Offset(sel.left, sel.top),
        DragMode.TR to Offset(sel.right, sel.top),
        DragMode.BL to Offset(sel.left, sel.bottom),
        DragMode.BR to Offset(sel.right, sel.bottom)
    )
    for ((mode, corner) in corners) {
        if ((pos - corner).getDistance() < zone) return mode
    }
    return if (sel.contains(pos)) DragMode.MOVE else DragMode.NEW
}

private fun updateSelection(
    mode: DragMode,
    sel: Rect,
    pos: Offset,
    amount: Offset,
    bounds: Rect,
    square: Boolean
): Rect {
    val minSize = 30f
    fun clamped(v: Float, lo: Float, hi: Float) = v.coerceIn(lo, hi)

    return when (mode) {
        DragMode.MOVE -> {
            var l = clamped(sel.left + amount.x, bounds.left, bounds.right - sel.width)
            var t = clamped(sel.top + amount.y, bounds.top, bounds.bottom - sel.height)
            Rect(l, t, l + sel.width, t + sel.height)
        }
        DragMode.NEW, DragMode.TL, DragMode.TR, DragMode.BL, DragMode.BR -> {
            var (anchor, moving) = when (mode) {
                DragMode.TL -> Offset(sel.right, sel.bottom) to pos
                DragMode.TR -> Offset(sel.left, sel.bottom) to pos
                DragMode.BL -> Offset(sel.right, sel.top) to pos
                DragMode.BR -> Offset(sel.left, sel.top) to pos
                else -> Offset(sel.left, sel.top) to pos // NEW: ancla = inicio guardado en sel
            }
            moving = Offset(
                clamped(moving.x, bounds.left, bounds.right),
                clamped(moving.y, bounds.top, bounds.bottom)
            )
            var l = min(anchor.x, moving.x)
            var t = min(anchor.y, moving.y)
            var r = max(anchor.x, moving.x)
            var b = max(anchor.y, moving.y)
            if (square) {
                val side = min(r - l, b - t)
                if (anchor.x <= moving.x) r = l + side else l = r - side
                if (anchor.y <= moving.y) b = t + side else t = b - side
            }
            if (r - l < minSize) r = l + minSize
            if (b - t < minSize) b = t + minSize
            Rect(l, t, r, b)
        }
        DragMode.NONE -> sel
    }
}

// ---- Lupa con color picker ----

@Composable
private fun Magnifier(
    bitmap: Bitmap,
    touchPos: Offset,
    imageRect: Rect,
    toImage: (Offset) -> Pt?
) {
    val imgPt = toImage(touchPos) ?: return
    val argb = bitmap.getPixel(imgPt.x.toInt(), imgPt.y.toInt())
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
                addOval(Rect(radius - radius, radius - radius, radius + radius, radius + radius))
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
        Text(
            text = ContentClassifier.toHex(argb),
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
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
private fun ActionToolbar(
    lockSquare: Boolean,
    roundedCorners: Boolean,
    onToggleSquare: () -> Unit,
    onToggleRounded: () -> Unit,
    onAnnotate: () -> Unit,
    onPin: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit
) {
    Card(shape = RoundedCornerShape(28.dp)) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ToolbarButton(Icons.Filled.Edit, active = false, onClick = onAnnotate)
            ToolbarButton(Icons.Filled.Crop54, active = lockSquare, onClick = onToggleSquare)
            ToolbarButton(Icons.Filled.RoundedCorner, active = roundedCorners, onClick = onToggleRounded)
            ToolbarButton(Icons.Filled.PushPin, active = false, onClick = onPin)
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
                        onClick = { controller.tool.value = type }
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

private fun stringResourceText(context: android.content.Context, resId: Int): String =
    context.getString(resId)
