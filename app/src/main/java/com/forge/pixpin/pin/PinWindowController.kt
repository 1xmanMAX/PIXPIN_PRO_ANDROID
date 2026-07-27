package com.forge.pixpin.pin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DoNotTouch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.clipboard.ContentClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Un pin = una ventana overlay independiente. Gestos: arrastrar (mover),
 * pellizcar (zoom), doble toque (cerrar), pulsación larga (menú).
 * Posición y opacidad viven en los LayoutParams de la ventana (fuera del
 * estado de Compose) para que mover/ajustar no recomponga el contenido.
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

    private fun computeFlags(clickThrough: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
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

    private fun scaleBy(zoom: Float) {
        val s = uiState.value
        val newScale = (s.scale * zoom).coerceIn(0.25f, 5f)
        if (newScale != s.scale) {
            uiState.value = s.copy(scale = newScale)
            callbacks.onPinChanged(this)
        }
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

    // ---- Contenido Compose ----

    @Composable
    private fun PinRoot() {
        val s by uiState
        val menu by menuOpen
        Surface(
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 8.dp,
            border = if (s.clickThrough) {
                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
            } else null,
            modifier = Modifier
                .pointerInput(s.locked, menu, s.type, s.clickThrough) {
                    detectTapGestures(
                        onLongPress = { menuOpen.value = !menuOpen.value },
                        onDoubleTap = { if (!menuOpen.value && !s.locked) close() },
                        onTap = {
                            val argb = s.colorArgb
                            if (s.type == PinType.COLOR && !menuOpen.value && argb != null) {
                                copyColorToClipboard(argb)
                            }
                        }
                    )
                }
                .pointerInput(s.locked, menu) {
                    if (s.locked || menu) return@pointerInput
                    detectTransformGestures { _, pan, zoom, _ ->
                        moveBy(pan.x, pan.y)
                        if (zoom != 1f) scaleBy(zoom)
                    }
                }
        ) {
            if (menu) PinMenuContent(s) else PinBodyContent(s)
        }
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
                horizontalArrangement = Arrangement.SpaceEvenly
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
        }
    }

    @Composable
    private fun ImagePinBody(s: PinState) {
        val bitmap by produceState<Bitmap?>(initialValue = null, s.imagePath) {
            val path = s.imagePath
            if (path != null) {
                value = withContext(Dispatchers.IO) { ImageStore.load(path) }
            }
        }
        val bmp = bitmap
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
}
