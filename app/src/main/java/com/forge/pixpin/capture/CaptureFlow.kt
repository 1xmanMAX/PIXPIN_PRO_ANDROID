package com.forge.pixpin.capture

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.data.CaptureMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Punto de entrada único de "quiero capturar", venga de la bola, del tile o de
 * la notificación. Decide si hace falta pedir consentimiento o si la sesión ya
 * está viva y se puede capturar al instante.
 */
object CaptureFlow {

    fun requestCapture(context: Context) {
        val app = context.applicationContext as PixPinApp
        if (!Settings.canDrawOverlays(app)) {
            toast(app, R.string.need_overlay_toast)
            return
        }
        if (ProjectionSession.isAlive) {
            app.scope.launch { captureNow(app) }
        } else {
            // Sin sesión: el consentimiento SIEMPRE va antes del servicio.
            app.startActivity(
                Intent(app, ConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Toma el fotograma y abre la pantalla de recorte. Los overlays de PixPin se
     * esconden un instante: MediaProjection graba la pantalla real y, si no, la
     * bola y los pines saldrían dentro de la propia captura.
     */
    suspend fun captureNow(context: Context, settleMs: Long = 48) {
        val app = context.applicationContext as PixPinApp
        val overlays = app.overlayManager

        overlays.setOverlaysVisible(false)
        // Margen para que el compositor ya no dibuje los overlays (y, tras el
        // diálogo de permiso, para que su atenuación haya desaparecido).
        delay(settleMs)
        val frame = ProjectionSession.grab()
        overlays.setOverlaysVisible(true)

        if (frame == null) {
            toast(app, R.string.capture_error)
            CaptureService.stopSession(app)
            return
        }

        FrameHolder.set(frame)
        app.startActivity(
            Intent(app, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )

        // Modo discreto: el fotograma ya está en memoria, así que la sesión se
        // cierra de inmediato y el icono de "grabando pantalla" desaparece.
        if (app.settings.settings.first().captureMode == CaptureMode.DISCREET) {
            CaptureService.stopSession(app)
        }
    }

    fun toast(context: Context, resId: Int) {
        Toast.makeText(context.applicationContext, resId, Toast.LENGTH_SHORT).show()
    }
}
