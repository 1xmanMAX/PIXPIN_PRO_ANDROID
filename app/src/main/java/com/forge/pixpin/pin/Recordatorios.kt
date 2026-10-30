package com.forge.pixpin.pin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

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
 *
 * **Sin permiso de alarma exacta** (30-oct-2026, la versión de Google Play): se pone inexacta en vez
 * de reventar, y se ofrece el permiso. Ver [AlarmaExacta].
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
        // Exacta con `setAlarmClock` si se puede; sin permiso (la versión de Play, si el usuario no
        // lo dio), inexacta en vez de reventar. Ver [AlarmaExacta].
        AlarmaExacta.programar(context, gestor, cuando, aviso)
        apuntar(context) { it.putLong(pinId, cuando) }
    }

    /** Lo quita. Se llama al borrar el pin o al apagar su recordatorio. */
    fun quitar(context: Context, pinId: String) {
        val gestor = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { gestor.cancel(intentDe(context, pinId)) }
        apuntar(context) { it.remove(pinId) }
    }

    /**
     * **Las que siguen esperando** de [apuntadas] (id → hora): las de una hora ya pasada sobran (o
     * sonaron, o se perdieron con el teléfono apagado). Pura, para probarla.
     */
    fun pendientes(apuntadas: Map<String, *>, ahora: Long): Map<String, Long> =
        apuntadas.mapNotNull { (id, v) -> (v as? Long)?.takeIf { it > ahora }?.let { id to it } }.toMap()

    /**
     * **Vuelve a poner todas las que esperan** (30-oct-2026): al conceder el permiso de alarmas
     * exactas (para que las que se pusieron inexactas pasen a la hora en punto) y, en la versión de
     * Play, al arrancar la app (quitar el permiso cancela las exactas). Las pasadas se olvidan.
     */
    fun reponerTodos(context: Context, ahora: Long = System.currentTimeMillis()) {
        val prefs = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull() ?: return
        val todas = runCatching { prefs.all }.getOrNull() ?: return
        val siguen = pendientes(todas, ahora)
        siguen.forEach { (id, cuando) -> poner(context, id, cuando) }
        val pasadas = todas.keys - siguen.keys
        if (pasadas.isNotEmpty()) apuntar(context) { e -> pasadas.forEach { e.remove(it) } }
    }

    /**
     * Lo apuntado de cada alarma puesta (id → hora), solo para poder **reponerlas** ([reponerTodos]):
     * el sistema no deja preguntarle cuáles tiene. No cambia cuándo ni cómo suenan.
     */
    private fun apuntar(context: Context, cambio: (android.content.SharedPreferences.Editor) -> Unit) {
        runCatching {
            val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            cambio(e)
            e.apply()
        }
    }

    private const val PREFS = "recordatorios_puestos"

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
