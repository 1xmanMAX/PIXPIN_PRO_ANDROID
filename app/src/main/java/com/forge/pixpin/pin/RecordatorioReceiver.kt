package com.forge.pixpin.pin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.forge.pixpin.PixPinApp

/**
 * Lo que pasa cuando llega la hora: **el pin aparece y se oye**.
 *
 * No una notificación. Una notificación es una fila más en una lista que uno ya ignora, y
 * lo que se pidió fue «que me lo recuerdes», no «que me lo apuntes». El pin vuelve a la
 * pantalla, encima de lo que sea que se esté haciendo, y dice lo que tenga que decir.
 */
class RecordatorioReceiver : BroadcastReceiver() {

    companion object {
        /**
         * Lo que se le pone delante al identificador cuando quien avisa es una mini-app.
         *
         * Con un prefijo y no con otro extra en el intento porque la alarma ya guardada
         * en el sistema lleva solo este campo: añadir otro dejaría sin efecto las alarmas
         * puestas antes de actualizar, que es justo las que ya están esperando.
         */
        const val DE_UNA_MINIAPP = "mini:"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Recordatorios.ACCION) return
        val pinId = intent.getStringExtra(Recordatorios.EXTRA_PIN) ?: return
        val app = context.applicationContext as? PixPinApp ?: return
        // **Una mini-app no es un pin**, así que no hay ninguno al que hacer sonar: lo
        // que hay es un temporizador o una alarma dentro de la conversación. Se saca su
        // texto a la pantalla en ese momento, que es lo mismo que hace un recordatorio
        // normal —aparecer encima de lo que estés haciendo— sin inventar otro camino.
        if (pinId.startsWith(DE_UNA_MINIAPP)) {
            val id = pinId.removePrefix(DE_UNA_MINIAPP)
            val mensaje = runCatching {
                com.forge.pixpin.guardados.MensajesStore(context).leer()
                    .firstOrNull { it.id == id }
            }.getOrNull() ?: return
            val titulo = com.forge.pixpin.mini.Cabecera.titulo(mensaje.texto)
                .ifBlank { mensaje.nombre }
            app.overlayManager.pinTexto(titulo)
            return
        }
        app.overlayManager.sonarRecordatorio(pinId)
    }
}
