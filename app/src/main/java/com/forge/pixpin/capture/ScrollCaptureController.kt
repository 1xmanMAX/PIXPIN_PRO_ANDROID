package com.forge.pixpin.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.data.CaptureMode
import com.forge.pixpin.pin.OverlayComposeWindow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Captura con scroll: el usuario desplaza a mano y PixPin va cosiendo.
 *
 * Android no ofrece nada para esto —la captura larga del sistema solo funciona
 * dentro de apps que la implementan—, así que se va tomando fotogramas de la
 * sesión de captura y deduciendo el desplazamiento comparando filas
 * ([ScrollStitcher]).
 *
 * Dos decisiones que se notan al usarlo:
 *
 * - **La barrita nunca entra en la imagen.** Vive en su propia ventana pegada
 *   abajo y la zona capturada se recorta por encima de ella; si no, saldría
 *   repetida en cada tramo cosido.
 * - **Los pines y la bola se ocultan** mientras dura, por la misma razón.
 */
class ScrollCaptureController private constructor(
    private val app: PixPinApp,
    private val region: Rect
) {

    companion object {
        /** Alto máximo de la imagen final, en "pantallas" de la zona elegida. */
        private const val MAX_SCREENS = 5

        /** Ritmo de muestreo: más rápido no aporta y calienta el teléfono. */
        private const val FRAME_INTERVAL_MS = 110L

        /** Fotogramas dudosos seguidos que hacen saltar el aviso de "más despacio". */
        private const val UNCERTAIN_HINT = 8

        private const val BAR_HEIGHT_DP = 108

        var active: ScrollCaptureController? = null
            private set

        /** Zona pendiente mientras se pide permiso de captura (modo discreto). */
        private var pendingRegion: Rect? = null

        /**
         * Punto de entrada desde la pantalla de recorte. Si no hay sesión viva
         * (modo Discreto) hay que pedir permiso primero y retomar después.
         */
        fun request(context: Context, region: Rect) {
            val app = context.applicationContext as PixPinApp
            if (active != null) return
            if (ProjectionSession.isAlive) {
                begin(app, region)
                return
            }
            pendingRegion = region
            CaptureFlow.toast(app, R.string.scroll_needs_session)
            app.startActivity(
                Intent(app, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /**
         * Lo llama [CaptureService] en cuanto la sesión arranca: si veníamos de
         * pedir permiso para una captura con scroll, se retoma ahí.
         */
        fun resumePending(context: Context): Boolean {
            val region = pendingRegion ?: return false
            pendingRegion = null
            begin(context.applicationContext as PixPinApp, region)
            return true
        }

        private fun begin(app: PixPinApp, region: Rect) {
            val controller = ScrollCaptureController(app, region)
            if (!controller.start()) return
            active = controller
        }
    }

    private val wm = app.getSystemService(WindowManager::class.java)!!
    private var bar: OverlayComposeWindow? = null
    private var job: Job? = null

    /** Zona realmente capturada: la elegida, recortada para que no entre la barra. */
    private val captureArea: Rect
    private val stitcher: ScrollStitcher

    private val rows = mutableFloatStateOf(0f)
    private val hint = mutableStateOf<Int?>(null)
    private var uncertainStreak = 0
    private var running = false

    init {
        val metrics = app.resources.displayMetrics
        val barPx = (BAR_HEIGHT_DP * metrics.density).toInt()
        val bottomLimit = (metrics.heightPixels - barPx).coerceAtLeast(0)
        captureArea = Rect(
            region.left.coerceAtLeast(0),
            region.top.coerceAtLeast(0),
            region.right.coerceAtMost(metrics.widthPixels),
            region.bottom.coerceAtMost(bottomLimit)
        )
        stitcher = ScrollStitcher(
            width = captureArea.width().coerceAtLeast(1),
            maxHeight = captureArea.height().coerceAtLeast(1) * MAX_SCREENS
        )
    }

    private fun start(): Boolean {
        // Con la zona pegada al borde inferior no queda nada que capturar una vez
        // reservado el sitio de la barra.
        if (captureArea.width() < 32 || captureArea.height() < 120) {
            CaptureFlow.toast(app, R.string.scroll_region_too_small)
            return false
        }
        running = true
        app.overlayManager.setOverlaysVisible(false)
        openBar()
        // Si el bucle muere por lo que sea, hay que cerrar igual: dejar `running`
        // en true significaría que finish() no vuelve a entrar nunca y los
        // overlays se quedarían ocultos sin forma de recuperarlos.
        job = app.scope.launch {
            try {
                loop()
            } finally {
                if (running) finish(save = !stitcher.isEmpty)
            }
        }
        return true
    }

    private suspend fun loop() {
        // Margen para que el compositor deje de dibujar los overlays.
        delay(140)
        while (running) {
            // La sesión puede caerse a media captura (bloqueo de pantalla, chip
            // de grabación): entonces se cierra con lo que haya cosido.
            if (!ProjectionSession.isAlive) {
                finish(save = !stitcher.isEmpty)
                return
            }
            val full = ProjectionSession.grabIfChanged()
            if (full == null) {
                delay(FRAME_INTERVAL_MS) // dedo parado: nada que coser
                continue
            }
            val piece = runCatching {
                Bitmap.createBitmap(
                    full, captureArea.left, captureArea.top,
                    captureArea.width(), captureArea.height()
                )
            }.getOrNull()
            if (piece !== full && !full.isRecycled) full.recycle()

            if (piece != null) {
                val result = stitcher.addFrame(piece)
                if (!piece.isRecycled) piece.recycle()
                when (result) {
                    ScrollStitcher.Result.FULL -> {
                        hint.value = R.string.scroll_full
                        finishAfterHint()
                        return
                    }
                    ScrollStitcher.Result.UNCERTAIN -> {
                        uncertainStreak++
                        if (uncertainStreak >= UNCERTAIN_HINT) hint.value = R.string.scroll_slower
                    }
                    else -> {
                        uncertainStreak = 0
                        hint.value = null
                    }
                }
                rows.floatValue = stitcher.height.toFloat()
            }
            delay(FRAME_INTERVAL_MS)
        }
    }

    /** Deja ver el aviso un momento antes de cerrar por su cuenta. */
    private suspend fun finishAfterHint() {
        delay(1200)
        finish(save = true)
    }

    fun finish(save: Boolean) {
        if (!running) return
        running = false
        job?.cancel()
        job = null
        closeBar()
        app.overlayManager.setOverlaysVisible(true)
        active = null

        val result = if (save) stitcher.build() else null
        stitcher.recycle()

        if (result != null) {
            FrameHolder.set(result)
            app.startActivity(
                Intent(app, CaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } else if (save) {
            CaptureFlow.toast(app, R.string.capture_error)
        }

        app.scope.launch {
            if (app.settings.settings.first().captureMode == CaptureMode.DISCREET) {
                CaptureService.stopSession(app)
            }
        }
    }

    // ---- Barrita ----

    private fun openBar() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        val window = OverlayComposeWindow(app) { BarContent() }
        bar = window
        runCatching {
            wm.addView(window.view, params)
            window.onAttached()
        }.onFailure { bar = null }
    }

    private fun closeBar() {
        bar?.let {
            runCatching { wm.removeView(it.view) }
            it.onDetached()
        }
        bar = null
    }

    @Composable
    private fun BarContent() {
        val captured = rows.floatValue
        val screens = captured / captureArea.height().coerceAtLeast(1)
        val message = hint.value
        Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), shadowElevation = 8.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = app.getString(R.string.scroll_hint),
                    style = MaterialTheme.typography.bodyMedium
                )
                LinearProgressIndicator(
                    progress = { (screens / MAX_SCREENS).coerceIn(0f, 1f) },
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message?.let { app.getString(it) }
                            ?: app.getString(R.string.scroll_progress, screens),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (message != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { finish(save = false) }) {
                            Text(app.getString(R.string.action_cancel))
                        }
                        Button(onClick = { finish(save = true) }) {
                            Text(app.getString(R.string.action_done))
                        }
                    }
                }
            }
        }
    }
}
