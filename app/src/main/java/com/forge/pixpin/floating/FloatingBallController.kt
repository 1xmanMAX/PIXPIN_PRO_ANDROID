package com.forge.pixpin.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.animation.doOnEnd
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.capture.CaptureService
import com.forge.pixpin.pin.OverlayComposeWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * La bola flotante: punto de entrada siempre visible (equivalente al
 * Desktop Floating Icon de PixPin). Arrastrable con snap al borde y
 * retracción parcial; al tocarla despliega el menú de acciones.
 */
class FloatingBallController(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ball: OverlayComposeWindow? = null
    private var ballLp: WindowManager.LayoutParams? = null
    private var scrim: OverlayComposeWindow? = null
    private var menu: OverlayComposeWindow? = null
    private var snapAnimator: ValueAnimator? = null

    val isShowing: Boolean get() = ball != null

    fun show() {
        if (ball != null) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // Posición inicial: última guardada, o borde derecho a 1/3 de pantalla
        scope.launch {
            val settings = (context.applicationContext as PixPinApp).settings.settings.first()
            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels
            lp.x = if (settings.ballX >= 0) settings.ballX else screenW - dp(12)
            lp.y = if (settings.ballY >= 0) settings.ballY else screenH / 3
            val window = OverlayComposeWindow(context) { BallContent() }
            ball = window
            ballLp = lp
            runCatching {
                wm.addView(window.view, lp)
                window.onAttached()
            }
        }
    }

    fun hide() {
        closeMenu()
        ball?.let { window ->
            runCatching { wm.removeView(window.view) }
            window.onDetached()
        }
        ball = null
        ballLp = null
        snapAnimator?.cancel()
        scope.cancel()
    }

    // ---- Gestos de la bola ----

    private fun moveBy(dx: Float, dy: Float) {
        val lp = ballLp ?: return
        val view = ball?.view ?: return
        closeMenu()
        snapAnimator?.cancel()
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun snapToEdge() {
        val lp = ballLp ?: return
        val view = ball?.view ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        val viewW = if (view.width > 0) view.width else dp(48)
        val center = lp.x + viewW / 2
        // Snap al borde más cercano, retraído a medias (estilo PixPin)
        val targetX = if (center < screenW / 2) -viewW / 2 else screenW - viewW / 2
        snapAnimator = ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                lp.x = anim.animatedValue as Int
                runCatching { wm.updateViewLayout(view, lp) }
            }
            doOnEnd {
                scope.launch {
                    (context.applicationContext as PixPinApp).settings
                        .setBallPosition(lp.x, lp.y)
                }
            }
            start()
        }
    }

    // ---- Menú de acciones ----

    private fun toggleMenu() {
        if (menu != null) closeMenu() else openMenu()
    }

    private fun openMenu() {
        if (menu != null) return

        // Scrim a pantalla completa: tocar fuera cierra el menú
        val scrimLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        val scrimWindow = OverlayComposeWindow(context) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.12f))
                    .pointerInput(Unit) { detectTapGestures { closeMenu() } }
            )
        }

        val menuLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (ballLp?.y ?: 200) + dp(56)
        }
        val menuWindow = OverlayComposeWindow(context) { MenuContent() }

        scrim = scrimWindow
        menu = menuWindow
        runCatching {
            wm.addView(scrimWindow.view, scrimLp)
            scrimWindow.onAttached()
            wm.addView(menuWindow.view, menuLp)
            menuWindow.onAttached()
        }
    }

    private fun closeMenu() {
        scrim?.let { runCatching { wm.removeView(it.view) }; it.onDetached() }
        menu?.let { runCatching { wm.removeView(it.view) }; it.onDetached() }
        scrim = null
        menu = null
    }

    private fun onMenuAction(action: BallAction) {
        closeMenu()
        when (action) {
            BallAction.CAPTURE -> CaptureService.requestCapture(context)
            else -> Toast.makeText(context, R.string.coming_soon, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    // ---- Contenido Compose ----

    @Composable
    private fun BallContent() {
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(6.dp, CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                    ),
                    CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { toggleMenu() })
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            moveBy(amount.x, amount.y)
                        },
                        onDragEnd = { snapToEdge() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = stringResourceSafe(R.string.app_name),
                tint = Color.White
            )
        }
    }

    @Composable
    private fun MenuContent() {
        Card(shape = RoundedCornerShape(28.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MenuButton(BallAction.CAPTURE) { Icon(Icons.Filled.Crop, contentDescription = null) }
                MenuButton(BallAction.PIN_CLIPBOARD) { Icon(Icons.Filled.PushPin, contentDescription = null) }
                MenuButton(BallAction.HIDE_ALL) { Icon(Icons.Filled.VisibilityOff, contentDescription = null) }
                MenuButton(BallAction.PIN_LIST) { Icon(Icons.Filled.FormatListBulleted, contentDescription = null) }
            }
        }
    }

    @Composable
    private fun MenuButton(action: BallAction, icon: @Composable () -> Unit) {
        IconButton(onClick = { onMenuAction(action) }) { icon() }
    }
}

private enum class BallAction { CAPTURE, PIN_CLIPBOARD, HIDE_ALL, PIN_LIST }

@Composable
private fun stringResourceSafe(resId: Int): String = LocalContext.current.getString(resId)
