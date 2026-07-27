package com.forge.pixpin.floating

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import com.forge.pixpin.MainActivity
import com.forge.pixpin.capture.CaptureService

/**
 * Tile de ajustes rápidos: tercer disparador de captura.
 * Si falta el permiso de overlay, abre el onboarding en su lugar.
 */
class CaptureTileService : TileService() {

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        PinHostService.start(this)
        CaptureService.requestCapture(this)
    }
}
