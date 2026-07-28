package com.forge.pixpin.pin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.forge.pixpin.data.CrashLog
import com.forge.pixpin.floating.PinHostService

/**
 * Restaura PixPin tras reiniciar el teléfono. Tener concedido
 * SYSTEM_ALERT_WINDOW exime de la restricción de arrancar un FGS
 * en segundo plano, y PinHostService (tipo specialUse) sí puede arrancar
 * desde el boot (a diferencia del tipo mediaProjection, prohibido ahí).
 *
 * Modo seguro: si quedó un informe de fallo sin revisar, NO se levantan los
 * overlays en el arranque. Un fallo al crear una ventana overlay se repite en
 * cada reinicio del servicio y dejaría la app imposible de abrir; así siempre
 * se puede entrar, leer el informe y volver a activarla con «Comenzar».
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (CrashLog.hasReport(context)) return
        if (Settings.canDrawOverlays(context)) {
            PinHostService.start(context)
        }
    }
}
