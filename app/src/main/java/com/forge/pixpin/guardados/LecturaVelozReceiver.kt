package com.forge.pixpin.guardados

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * **El punto que devuelve Lectura Veloz** mientras se lee (cada 15 s como mucho, y siempre al
 * pausar, cerrar o abrir): `Intent("com.forge.pixpin.LECTURA_VELOZ").setPackage("com.forge.pixpin")`.
 * Exportado porque lo manda otra app; lo que no se reconoce se tira ([LecturaVelozEnElTelefono.recibir]).
 */
class LecturaVelozReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pendiente = goAsync()
        Thread {
            try {
                runCatching { LecturaVelozEnElTelefono.recibir(app, intent) }
            } finally {
                pendiente.finish()
            }
        }.start()
    }
}
