package com.forge.pixpin.floating

import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.material.icons.filled.Gesture
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
     * De qué tamaño era la pantalla la última vez que se colocó la bola.
     *
     * Es lo que permite saber **qué ha cambiado** al girar y trasladar el sitio
     * en vez de recalcularlo a ciegas. Ver [BallState.alCambiarLaPantalla].
     */
    private var ultimaPantalla: Pair<Int, Int>? = null

    /** Quien avisa de que el móvil ha girado. Se quita al esconder la bola. */
    private var vigilante: ComponentCallbacks? = null

    /**
     * El tamaño **real** de la pantalla, con la rotación de ahora mismo.
     *
     * No vale `resources.displayMetrics`: en un contexto de servicio devuelve el
     * área útil de la aplicación y no siempre se entera de que el móvil ha
     * girado. Y la bola es una ventana con `FLAG_LAYOUT_NO_LIMITS`, o sea que
     * sus coordenadas son las del display entero, barras del sistema incluidas
     * — hay que preguntar por el display entero o el borde derecho cae donde no
     * es.
     */
    private fun pantalla(): Pair<Int, Int> {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels > 0) return metrics.widthPixels to metrics.heightPixels
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** Lo que mide la bola de lado, con lo que ya esté medido si lo hay. */
    private fun ladoDeLaBola(): Int {
        val view = ball?.view
        val medido = minOf(view?.width ?: 0, view?.height ?: 0)
        return if (medido > 0) medido else dp(BALL_DP)
    }

    /**
     * La devuelve a la pantalla si hiciera falta, y la deja quieta si no.
     *
     * Es el único sitio que mueve la bola sin que el usuario la toque, y se
     * llama desde los tres puntos donde puede haberse perdido: al crearla —por
     * si lo guardado es de otra orientación—, al girar el móvil y al pedir que
     * vuelva a verse.
     */
    private fun recolocar() {
        val lp = ballLp ?: return
        val view = ball?.view ?: return
        val (ancho, alto) = pantalla()
        if (ancho <= 0 || alto <= 0) return
        val lado = ladoDeLaBola()
        val anterior = ultimaPantalla

        val sitio = if (anterior != null && anterior != (ancho to alto)) {
            BallState.alCambiarLaPantalla(
                lp.x, lp.y, lado, anterior.first, anterior.second, ancho, alto
            )
        } else {
            BallState.rescatar(lp.x, lp.y, lado, ancho, alto)
        }
        ultimaPantalla = ancho to alto
        if (sitio.x == lp.x && sitio.y == lp.y) return

        snapAnimator?.cancel()
        lp.x = sitio.x
        lp.y = sitio.y
        runCatching { wm.updateViewLayout(view, lp) }
        scope.launch { app.settings.setBallPosition(lp.x, lp.y) }
    }

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
            // **NADA ya no quiere decir «no hagas nada».** La ventana puede
            // existir y estar visible y aun así no verse, porque se quedó fuera
            // de la pantalla al girar el móvil; desde dentro todo parecía
            // correcto y por eso «Comenzar» no la recuperaba. Ver [BallState].
            BallRecovery.NADA -> recolocar()
            BallRecovery.CREAR -> show()
            BallRecovery.VOLVER_A_ENSENAR -> {
                setVisible(true)
                recolocar()
            }
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
            val (screenW, screenH) = pantalla()
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
                ultimaPantalla = screenW to screenH
                // Lo guardado puede ser de la otra orientación: una bola pegada
                // al borde derecho en horizontal cae en mitad de la pantalla al
                // volver a vertical. Se recoloca antes de que nadie la vea.
                window.view.post { recolocar() }
                vigilarLaOrientacion()
            }.onFailure {
                ball = null
                ballLp = null
            }
        }
    }

    /**
     * Se apunta a los cambios de configuración para enterarse de los giros.
     *
     * Un servicio no recibe `onConfigurationChanged` por su cuenta como una
     * actividad; hay que pedirlo. Y cuando llega, **el sistema todavía no ha
     * terminado de girar**: preguntar por el tamaño en ese instante devuelve a
     * veces el de antes, así que se espera al siguiente ciclo de dibujo de la
     * propia ventana, que ya está girada.
     */
    private fun vigilarLaOrientacion() {
        if (vigilante != null) return
        val cb = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                ball?.view?.post { recolocar() }
            }

            override fun onLowMemory() = Unit
        }
        runCatching { context.registerComponentCallbacks(cb) }
            .onSuccess { vigilante = cb }
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
        vigilante?.let { runCatching { context.unregisterComponentCallbacks(it) } }
        vigilante = null
        ball = null
        ballLp = null
        ultimaPantalla = null
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
            val (ancho, alto) = pantalla()
            // Acotada mientras se arrastra: sin esto un manotazo la saca de la
            // pantalla entera y, aunque al soltar volviera, por el camino
            // desaparece y da la sensación de que se ha ido volando.
            val sitio = BallState.mientrasSeArrastra(
                dragStartX + dxFromDown.toInt(),
                dragStartY + dyFromDown.toInt(),
                ladoDeLaBola(), ancho, alto
            )
            lp.x = sitio.x
            lp.y = sitio.y
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
        val (screenW, screenH) = pantalla()
        val lado = ladoDeLaBola()
        // Nunca fuera del alcance vertical del pulgar.
        lp.y = BallState.alturaSegura(lp.y, lado, screenH)
        // Al borde más cercano, retraída a medias (estilo PixPin).
        val targetX = BallState.pegadaAlBorde(
            BallState.enLaIzquierda(lp.x, lado, screenW), lado, screenW
        )
        ultimaPantalla = screenW to screenH
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
            // Dibujar encima de lo que haya, sin capturar nada: la capa se abre
            // y se cierra desde el mismo botón.
            BallAction.CAPA -> app.overlayManager.capa.alternar()
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
                MenuButton(BallAction.CAPA) { Icon(Icons.Filled.Gesture, contentDescription = null) }
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

private enum class BallAction { CAPTURE, CAPA, PIN_CLIPBOARD, HIDE_ALL, PIN_LIST }
