package com.forge.pixpin.pin

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoNotTouch
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.forge.pixpin.R
import com.forge.pixpin.clipboard.ContentClassifier
import com.forge.pixpin.floating.FloatingBallController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Un pin = una ventana overlay independiente, SIN límites de pantalla
 * (atraviesa bordes y puede cubrir la barra de estado).
 *
 * Gestos:
 * - 1 dedo: mover (soltar sobre la bola flotante = minimizar en burbuja)
 * - pellizco: zoom temporal estilo Telegram (al soltar vuelve a su tamaño)
 * - 2 dedos vertical: ajustar opacidad en vivo
 * - tirador inferior-derecho: redimensionar persistente
 * - doble toque: cerrar · pulsación larga: menú · toque en burbuja: restaurar
 *
 * Posición y opacidad viven en los LayoutParams (fuera del estado de Compose):
 * mover/ajustar no recompone el contenido.
 */
class PinWindowController(
    private val context: Context,
    initialState: PinState,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onPinChanged(controller: PinWindowController)
        fun onPinClosed(controller: PinWindowController)
        fun onPinDestroyed(controller: PinWindowController)
        fun onPinSaveRequested(controller: PinWindowController)
    }

    private val wm = context.getSystemService(WindowManager::class.java)!!
    private var window: OverlayComposeWindow? = null
    private var lp: WindowManager.LayoutParams? = null

    private val uiState = mutableStateOf(initialState)
    private val menuOpen = mutableStateOf(false)

    val id: String get() = uiState.value.id
    val isShowing: Boolean get() = window != null

    /** Estado completo actual, incluyendo posición y opacidad de la ventana. */
    fun snapshot(): PinState {
        val s = uiState.value
        val p = lp
        return if (p != null) s.copy(x = p.x, y = p.y, alpha = p.alpha) else s
    }

    fun show() {
        if (window != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            computeFlags(uiState.value.clickThrough),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = uiState.value.x
            y = uiState.value.y
            alpha = uiState.value.alpha
            @Suppress("DEPRECATION")
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val w = OverlayComposeWindow(context) { PinRoot() }
        window = w
        lp = params
        runCatching {
            wm.addView(w.view, params)
            w.onAttached()
        }
    }

    fun hideView() {
        val w = window ?: return
        runCatching { wm.removeView(w.view) }
        w.onDetached()
        window = null
        lp = null
    }

    fun close() {
        hideView()
        callbacks.onPinClosed(this)
    }

    fun destroy() {
        hideView()
        callbacks.onPinDestroyed(this)
    }

    fun setClickThrough(value: Boolean) {
        if (uiState.value.clickThrough == value) return
        uiState.value = uiState.value.copy(clickThrough = value)
        lp?.let { p ->
            p.flags = computeFlags(value)
            window?.view?.let { v -> runCatching { wm.updateViewLayout(v, p) } }
        }
        callbacks.onPinChanged(this)
    }

    fun minimize() {
        if (uiState.value.minimized) return
        uiState.value = uiState.value.copy(minimized = true)
        menuOpen.value = false
        callbacks.onPinChanged(this)
    }

    fun restore() {
        if (!uiState.value.minimized) return
        uiState.value = uiState.value.copy(minimized = false)
        callbacks.onPinChanged(this)
    }

    private fun computeFlags(clickThrough: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (clickThrough) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    private fun moveBy(dx: Float, dy: Float) {
        val p = lp ?: return
        val v = window?.view ?: return
        p.x += dx.toInt()
        p.y += dy.toInt()
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun onDragFinished() {
        val p = lp ?: return
        val v = window?.view ?: return
        // Soltar sobre la bola flotante = minimizar en burbuja
        val bounds = FloatingBallController.active?.ballBounds()
        if (bounds != null && v.width > 0) {
            val cx = p.x + v.width / 2
            val cy = p.y + v.height / 2
            if (bounds.contains(cx, cy)) {
                minimize()
                return
            }
        }
        callbacks.onPinChanged(this)
    }

    private fun setAlphaLive(value: Float) {
        val p = lp ?: return
        val v = window?.view ?: return
        p.alpha = value.coerceIn(0.2f, 1f)
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun commitAlpha() {
        val p = lp ?: return
        uiState.value = uiState.value.copy(alpha = p.alpha)
        callbacks.onPinChanged(this)
    }

    private fun copyColorToClipboard(argb: Int) {
        val hex = ContentClassifier.toHex(argb)
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("color", hex))
        Toast.makeText(context, context.getString(R.string.copied_hex, hex), Toast.LENGTH_SHORT).show()
    }

    private fun openFile(s: PinState) {
        val path = s.filePath ?: return
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(path)
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, s.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_for_file, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Contenido Compose ----

    @Composable
    private fun PinRoot() {
        val s by uiState
        val menu by menuOpen
        Box {
            Surface(
                shape = if (s.minimized) CircleShape else RoundedCornerShape(12.dp),
                shadowElevation = 8.dp,
                border = if (s.clickThrough) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
                } else null,
                modifier = Modifier
                    .pointerInput(s.locked, menu, s.type, s.minimized) {
                        detectTapGestures(
                            onLongPress = { menuOpen.value = !menuOpen.value },
                            onDoubleTap = {
                                if (!menuOpen.value && !s.locked && !s.minimized) close()
                            },
                            onTap = {
                                when {
                                    s.minimized -> restore()
                                    menuOpen.value -> Unit
                                    s.type == PinType.COLOR ->
                                        s.colorArgb?.let { copyColorToClipboard(it) }
                                    s.type == PinType.FILE -> openFile(s)
                                }
                            }
                        )
                    }
                    .pointerInput(s.minimized) {
                        if (!s.minimized) return@pointerInput
                        detectDragGestures(
                            onDrag = { change, amount ->
                                change.consume()
                                moveBy(amount.x, amount.y)
                            },
                            onDragEnd = { callbacks.onPinChanged(this@PinWindowController) }
                        )
                    }
                    .pointerInput(s.locked, menu, s.minimized) {
                        if (s.locked || menu || s.minimized) return@pointerInput
                        unifiedGestureLoop()
                    }
            ) {
                when {
                    menu -> PinMenuContent(s)
                    s.minimized -> BubbleContent(s)
                    else -> PinBodyContent(s)
                }
            }
            if (!s.minimized && !s.locked && !menu) {
                ResizeHandle(Modifier.align(Alignment.BottomEnd))
            }
        }
    }

    /**
     * Detector unificado de gestos: 1 dedo mueve, pellizco = zoom temporal
     * (vuelve al soltar), 2 dedos en vertical = opacidad en vivo.
     */
    private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.unifiedGestureLoop() {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var prevSpan = 0f
            var decided = false
            var peek = false
            var opacityGesture = false
            var spanAccum = 0f
            var vertAccum = 0f
            var peekStartSpan = 1f
            var peekStartScale = 1f
            var opacityStartAlpha = 1f
            var opacityDy = 0f
            var moved = false

            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break

                if (pressed.size == 1 && !decided) {
                    val change = pressed[0]
                    val delta = change.positionChange()
                    if (delta != Offset.Zero) {
                        moved = true
                        moveBy(delta.x, delta.y)
                        change.consume()
                    }
                } else if (pressed.size >= 2) {
                    val p0 = pressed[0]
                    val p1 = pressed[1]
                    val span = (p0.position - p1.position).getDistance()
                    val dyAvg = (p0.positionChange().y + p1.positionChange().y) / 2f
                    if (prevSpan == 0f) prevSpan = span

                    if (!decided) {
                        spanAccum += abs(span - prevSpan)
                        vertAccum += abs(dyAvg)
                        if (spanAccum > 24f || vertAccum > 24f) {
                            decided = true
                            if (spanAccum > vertAccum * 1.3f) {
                                peek = true
                                peekStartSpan = span
                                peekStartScale = uiState.value.scale
                            } else {
                                opacityGesture = true
                                opacityStartAlpha = lp?.alpha ?: uiState.value.alpha
                            }
                        }
                    } else if (peek) {
                        val newScale = (peekStartScale * span / peekStartSpan)
                            .coerceIn(0.25f, 5f)
                        uiState.value = uiState.value.copy(scale = newScale)
                    } else if (opacityGesture) {
                        opacityDy += dyAvg
                        setAlphaLive(opacityStartAlpha - opacityDy / 500f)
                    }
                    prevSpan = span
                    pressed.forEach { it.consume() }
                }
            }

            when {
                peek -> uiState.value = uiState.value.copy(scale = peekStartScale)
                opacityGesture -> commitAlpha()
                moved -> onDragFinished()
            }
        }
    }

    @Composable
    private fun ResizeHandle(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .offset { IntOffset(8, 8) }
                .size(22.dp)
                .shadow(3.dp, CircleShape)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .border(2.dp, Color.White, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            val current = uiState.value.scale
                            val newScale = (current + amount.x * 0.006f).coerceIn(0.25f, 5f)
                            if (newScale != current) {
                                uiState.value = uiState.value.copy(scale = newScale)
                            }
                        },
                        onDragEnd = { callbacks.onPinChanged(this@PinWindowController) }
                    )
                }
        )
    }

    @Composable
    private fun PinMenuContent(s: PinState) {
        var sliderValue by remember(s.id) { mutableFloatStateOf(lp?.alpha ?: s.alpha) }
        Column(
            modifier = Modifier
                .width(230.dp)
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { menuOpen.value = false }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
                Text(
                    context.getString(R.string.pin_menu_title),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(
                context.getString(R.string.opacity_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    setAlphaLive(it)
                },
                onValueChangeFinished = { commitAlpha() },
                valueRange = 0.2f..1f
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = {
                    setClickThrough(!s.clickThrough)
                    menuOpen.value = false
                }) {
                    Icon(
                        if (s.clickThrough) Icons.Filled.DoNotTouch else Icons.Filled.TouchApp,
                        contentDescription = context.getString(R.string.cd_clickthrough),
                        tint = if (s.clickThrough) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    uiState.value = s.copy(locked = !s.locked)
                    menuOpen.value = false
                    callbacks.onPinChanged(this@PinWindowController)
                }) {
                    Icon(
                        if (s.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = null,
                        tint = if (s.locked) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    if (s.minimized) restore() else minimize()
                }) {
                    Icon(
                        if (s.minimized) Icons.Filled.OpenInFull else Icons.Filled.Minimize,
                        contentDescription = null
                    )
                }
                IconButton(onClick = {
                    menuOpen.value = false
                    callbacks.onPinSaveRequested(this@PinWindowController)
                }) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                }
                IconButton(onClick = { close() }) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
                IconButton(onClick = { destroy() }) {
                    Icon(
                        Icons.Filled.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    @Composable
    private fun PinBodyContent(s: PinState) {
        when (s.type) {
            PinType.IMAGE -> ImagePinBody(s)
            PinType.TEXT -> TextPinBody(s)
            PinType.COLOR -> ColorPinBody(s)
            PinType.FILE -> FilePinBody(s)
        }
    }

    @Composable
    private fun BubbleContent(s: PinState) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (s.type == PinType.COLOR && s.colorArgb != null) Color(s.colorArgb)
                    else MaterialTheme.colorScheme.primary
                ),
            contentAlignment = Alignment.Center
        ) {
            when (s.type) {
                PinType.IMAGE -> {
                    val bitmap = rememberPinBitmap(s.imagePath).value
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(CircleShape)
                        )
                    }
                }
                PinType.TEXT -> Icon(Icons.Filled.TextFields, contentDescription = null, tint = Color.White)
                PinType.COLOR -> Unit // la burbuja ya es del color
                PinType.FILE -> Icon(
                    mimeIcon(s.mimeType), contentDescription = null, tint = Color.White
                )
            }
        }
    }

    @Composable
    private fun ImagePinBody(s: PinState) {
        val bmp = rememberPinBitmap(s.imagePath).value
        if (bmp == null) {
            Box(Modifier.size(64.dp))
            return
        }
        val density = LocalDensity.current.density
        val screenW = context.resources.displayMetrics.widthPixels
        val baseWidthPx = minOf(bmp.width, (screenW * 0.6f).toInt())
        val baseWidthDp = baseWidthPx / density
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.width((baseWidthDp * s.scale).dp)
        )
    }

    @Composable
    private fun rememberPinBitmap(path: String?): State<Bitmap?> {
        return produceState<Bitmap?>(initialValue = null, path) {
            if (path != null) {
                value = withContext(Dispatchers.IO) { ImageStore.load(path) }
            }
        }
    }

    @Composable
    private fun TextPinBody(s: PinState) {
        SelectionContainer {
            Text(
                text = s.text.orEmpty(),
                fontSize = (14f * s.scale).sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(14.dp)
            )
        }
    }

    @Composable
    private fun ColorPinBody(s: PinState) {
        val argb = s.colorArgb ?: return
        val side = (96f * s.scale).coerceIn(48f, 480f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(side.dp)
                    .background(Color(argb))
            )
            Text(
                text = ContentClassifier.toHex(argb),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    @Composable
    private fun FilePinBody(s: PinState) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 230.dp)
                .padding(12.dp)
        ) {
            Icon(
                mimeIcon(s.mimeType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = s.fileName ?: context.getString(R.string.pin_type_file),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = mimeLabel(s.mimeType),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    private fun mimeIcon(mime: String?) = when {
        mime == null -> Icons.Filled.InsertDriveFile
        mime == "application/pdf" -> Icons.Filled.PictureAsPdf
        mime.startsWith("image/") -> Icons.Filled.Image
        mime.startsWith("video/") -> Icons.Filled.Videocam
        mime.startsWith("audio/") -> Icons.Filled.AudioFile
        mime.contains("zip") || mime.contains("compressed") || mime.contains("tar") ->
            Icons.Filled.FolderZip
        mime.startsWith("text/") -> Icons.Filled.Description
        else -> Icons.Filled.InsertDriveFile
    }

    private fun mimeLabel(mime: String?): String {
        if (mime == null) return ""
        return mime.substringAfter('/').take(12).uppercase()
    }
}
