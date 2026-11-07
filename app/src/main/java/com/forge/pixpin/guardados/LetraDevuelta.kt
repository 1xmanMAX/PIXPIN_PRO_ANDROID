package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent

/**
 * **La letra que llega de LetraSync Pro sin haberla pedido ahora** (7-nov-2026): una canción de PinDesk que se retocó
 * desde la biblioteca de LetraSync. Solo se acepta para una canción que existe, es de esta app y tiene audio, y
 * solo cambia su letra (lo mismo que al volver de «Sincronizar la letra»).
 */
class LetraDevuelta : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LetraSyncPro.ACCION_DEVUELTA) return
        val id = intent.getStringExtra(LetraSyncPro.EXTRA_ID)?.takeIf { it.isNotBlank() } ?: return
        val letra = intent.getStringExtra(LetraSyncPro.EXTRA_LETRA)?.takeIf { it.isNotBlank() && it.length < 200_000 } ?: return
        val pendiente = goAsync()
        Thread {
            try {
                val almacen = MensajesStore(context)
                val m = almacen.leer().firstOrNull { it.id == id }
                if (m != null && m.clase == Clase.VOZ && m.ruta != null && m.transcripcion != letra) {
                    almacen.actualizar(id) { it.copy(transcripcion = letra, estadoDelTexto = TEXTO_LETRA) }
                }
            } finally { pendiente.finish() }
        }.start()
    }
}
