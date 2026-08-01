package com.forge.pixpin.floating

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.core.animation.doOnEnd
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.capture.CaptureFlow
import com.forge.pixpin.clipboard.ClipboardPinActivity
import com.forge.pixpin.pin.OverlayComposeWindow
import com.forge.pixpin.pin.OverlayTouchHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * La bola flotante: punto de entrada siempre visible (equivalente al
 * Desktop Floating Icon de PixPin). Arrastrable con snap al borde y
 * retracción parcial; al tocarla despliega el menú de acciones.
 */
class FloatingBallController(private val context: Context) {

    companion object {
        /** Instancia activa para que los pines puedan localizar la bola (drop-to-minimize). */
        var active: FloatingBallController? = null
            private set

        private const val BALL_DP = 48
    }

    private val app = context.applicationContext as PixPinApp
    private val wm = context.getSystemService(WindowManager::class.java)!!
    private val scope = app.scope

    private var ball: OverlayComposeWindow? = null
    private var ballLp: WindowManager.LayoutParams? = null
    private var scrim: OverlayComposeWindow? = null
    private var menu: OverlayComposeWindow? = null
    private var snapAnimator: ValueAnimator? = null

    private var dragStartX = 0
    private var dragStartY = 0

    /**
     * Puesta Y visible. Mirar solo si la ventana existe dejaba fuera el caso en
     * que está puesta pero oculta, y ahí nadie la recuperaba. Ver [BallState].
     */
    val isShowing: Boolean get() = ball?.isContentVisible == true

    /**
     * Deja la bola a la vista venga del estado que venga. Es el punto de
     * recuperación: lo llama el servicio en cada arranque, así que pulsar
     * «Comenzar» siempre la devuelve.
     */
    fun ensureVisible() {
        when (BallState.recoveryFor(windowExists = ball != null, contentVisible = isShowing)) {
            BallRecovery.NADA -> Unit
            BallRecovery.CREAR -> show()
            BallRecovery.VOLVER_A_ENSENAR -> setVisible(true)
        }
    }

    /** Rectángulo actual de la bola en coordenadas de pantalla, o null si está oculta. */
    fun ballBounds(): Rect? {
        val lp = ballLp ?: return null
        val view = ball?.view ?: return null
        if (view.visibility != android.view.View.VISIBLE) return null
        val w = if (view.width > 0) view.width else dp(BALL_DP)
        val h = if (view.height > 0) view.height else dp(BALL_DP)
        return Rect(lp.x, lp.y, lp.x + w, lp.y + h)
    }

    fun show() {
        if (ball != null) return
        scope.launch {
            val settings = app.settings.settings.first()
            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = if (settings.ballX >= 0) settings.ballX else screenW - dp(BALL_DP) / 2
                y = if (settings.ballY >= 0) settings.ballY else screenH / 3
            }
            if (ball != null) return@launch
            val window = OverlayComposeWindow(context) { BallContent() }
            window.setTouchHandler(OverlayTouchHandler(context, BallGestures()))
            ball = window
            ballLp = lp
            runCatching {
                wm.addView(window.view, lp)
                window.onAttached()
                active = this@FloatingBallController
            }.onFailure {
                ball = null
                ballLp = null
            }
        }
    }

    /** Oculta sin destruir (durante la captura de pantalla). */
    fun setVisible(visible: Boolean) {
        if (!visible) closeMenu()
        ball?.isContentVisible = visible
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
        if (active == this) active = null
    }

    // ---- Gestos de la bola ----

    private inner class BallGestures : OverlayTouchHandler.Listener {
        override fun onDragStart() {
            closeMenu()
            snapAnimator?.cancel()
            dragStartX = ballLp?.x ?: 0
            dragStartY = ballLp?.y ?: 0
        }

        override fun onDrag(dxFromDown: Float, dyFromDown: Float) {
            val lp = ballLp ?: return
            val view = ball?.view ?: return
            lp.x = dragStartX + dxFromDown.toInt()
            lp.y = dragStartY + dyFromDown.toInt()
            runCatching { wm.updateViewLayout(view, lp) }
        }

        override fun onDragEnd() = snapToEdge()

        override fun onTap(x: Float, y: Float) = toggleMenu()

        override fun onDoubleTap() = CaptureFlow.requestCapture(context)

        override fun onLongPress() {
            app.overlayManager.toggleHideAll()
        }
    }

    private fun snapToEdge() {
        val lp = ballLp ?: return
        val view = ball?.view ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val viewW = if (view.width > 0) view.width else dp(BALL_DP)
        val viewH = if (view.height > 0) view.height else dp(BALL_DP)
        // Nunca fuera del alcance vertical del pulgar.
        lp.y = lp.y.coerceIn(0, screenH - viewH)
        val center = lp.x + viewW / 2
        // Snap al borde más cercano, retraída a medias (estilo PixPin).
        val targetX = if (center < screenW / 2) -viewW / 2 else screenW - viewW / 2
        snapAnimator = ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                lp.x = anim.animatedValue as Int
                runCatching { wm.updateViewLayout(view, lp) }
            }
            doOnEnd {
                scope.launch { app.settings.setBallPosition(lp.x, lp.y) }
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
            BallAction.CAPTURE -> CaptureFlow.requestCapture(context)
            BallAction.PIN_CLIPBOARD -> context.startActivity(
                Intent(context, ClipboardPinActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            BallAction.HIDE_ALL -> app.overlayManager.toggleHideAll()
            BallAction.PIN_LIST -> app.overlayManager.togglePinList()
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    // ---- Contenido Compose ----

    @Composable
    private fun BallContent() {
        Box(
            modifier = Modifier
                .size(BALL_DP.dp)
                .shadow(6.dp, CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.PushPin, contentDescription = null, tint = Color.White)
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
