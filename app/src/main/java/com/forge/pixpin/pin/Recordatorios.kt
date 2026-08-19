package com.forge.pixpin.pin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.forge.pixpin.PixPinApp

/**
 * «A las diez, que suene esto».
 *
 * ## Por qué una alarma y no un temporizador
 *
 * Un contador dentro de la aplicación solo cuenta mientras la aplicación viva, y la
 * aplicación no vive: Android la mata en cuanto hace falta memoria. Un recordatorio que se
 * pierde porque el sistema hizo limpieza es peor que no tenerlo, porque uno **contaba con
 * él**. Así que se le pide la hora al sistema, que es el único que no se duerme.
 *
 * ## Y una alarma de despertador, no una cualquiera
 *
 * `setAlarmClock` es la única que Android respeta con el ahorro de batería puesto: las
 * demás se agrupan y se retrasan «un rato», que para un despertador significa que suena
 * cuando ya da igual. Lo de siempre — a cambio, el sistema enseña el icono de alarma
 * puesta, que además es honesto: hay una alarma puesta.
 */
object Recordatorios {

    /**
     * Pone el recordatorio de [pinId] para [cuando].
     *
     * Se identifica por el pin, así que volver a ponerlo lo **cambia** en vez de duplicarlo:
     * es lo que uno espera al corregir la hora de algo que ya había puesto.
     */
    fun poner(context: Context, pinId: String, cuando: Long) {
        val gestor = context.getSystemService(AlarmManager::class.java) ?: return
        val aviso = intentDe(context, pinId)
        runCatching {
            gestor.setAlarmClock(AlarmManager.AlarmClockInfo(cuando, aviso), aviso)
        }.onFailure {
            // Sin permiso de alarma exacta se cae a la inexacta. Llegar tarde es
            // mucho mejor que no llegar, y avisar de que no se puede sería pedirle
            // al usuario que arregle algo que él no ha roto.
            runCatching { gestor.set(AlarmManager.RTC_WAKEUP, cuando, aviso) }
        }
    }

    /** Lo quita. Se llama al borrar el pin o al apagar su recordatorio. */
    fun quitar(context: Context, pinId: String) {
        val gestor = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { gestor.cancel(intentDe(context, pinId)) }
    }

    private fun intentDe(context: Context, pinId: String): PendingIntent {
        val intent = Intent(context, RecordatorioReceiver::class.java)
            .setAction(ACCION)
            .putExtra(EXTRA_PIN, pinId)
        return PendingIntent.getBroadcast(
            context,
            pinId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val ACCION = "com.forge.pixpin.RECORDATORIO"
    const val EXTRA_PIN = "pin"
}
