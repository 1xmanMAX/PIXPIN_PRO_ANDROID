package com.forge.pixpin.integraciones

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * **El aviso de Lectura Veloz: por dónde va la lectura** (27-oct-2026, protocolo v1). Llega en
 * segundo plano, sin abrir nada, y mueve el marcador verde del documento ([LecturaVeloz.aplicar]).
 *
 * Exportado porque lo manda otra aplicación (difusión explícita a `com.forge.pixpin`). Por eso no
 * se cree nada de lo que trae: el código tiene que ser uno que PixPin mandó antes —el registro dice
 * dónde escribir, nunca el aviso—, y los números se acotan ([ProtocoloLecturaVeloz.Llegada]).
 */
class LecturaVelozReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ProtocoloLecturaVeloz.ACCION) return
        // Unos extras que no se dejan leer (una clase que aquí no existe) no tumban nada.
        val extras = runCatching { intent.extras?.also { it.size() } }.getOrNull() ?: return
        @Suppress("DEPRECATION")
        fun crudo(k: String): Any? = runCatching { extras.get(k) }.getOrNull()
        fun texto(k: String) = crudo(k) as? String
        fun entero(k: String) = when (val v = crudo(k)) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull(); else -> null }
        fun largo(k: String) = when (val v = crudo(k)) { is Number -> v.toLong(); is String -> v.trim().toLongOrNull(); else -> null }
        fun real(k: String) = when (val v = crudo(k)) { is Number -> v.toFloat(); is String -> v.trim().toFloatOrNull(); else -> null }

        val llegada = ProtocoloLecturaVeloz.Llegada.de(
            doc = texto("doc"), tipo = texto("tipo"), pagina = entero("pagina"), frac = real("frac"),
            fraccion = real("fraccion"), t = largo("t"), palabra = entero("palabra"), total = entero("total")
        )
        if (llegada == null) {
            Log.w("LecturaVeloz", "aviso que no vale: se ignora")
            return
        }
        val app = context.applicationContext
        val pendiente = goAsync()
        // Disco, fuera del hilo principal; y siempre se acaba, que el sistema da 10 s.
        Thread {
            try {
                LecturaVeloz.aplicar(app, llegada)
            } catch (e: Exception) {
                Log.w("LecturaVeloz", "no se pudo mover el verde", e)
            } finally {
                pendiente.finish()
            }
        }.start()
    }
}
