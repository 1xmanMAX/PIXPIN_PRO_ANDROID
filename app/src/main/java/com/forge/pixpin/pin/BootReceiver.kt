package com.forge.pixpin.pin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.forge.pixpin.floating.PinHostService

/**
 * Restaura PixPin tras reiniciar el teléfono. Tener concedido
 * SYSTEM_ALERT_WINDOW exime de la restricción de arrancar un FGS
 * en segundo plano (incluso en Android 12+), y PinHostService
 * (tipo specialUse) puede arrancar desde el boot en Android 15.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings.canDrawOverlays(context)) {
            PinHostService.start(context)
        }
    }
}
