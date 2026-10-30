package com.forge.pixpin.pin

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

/**
 * **La alarma exacta, con permiso o sin él** (30-oct-2026, para Google Play).
 *
 * El APK de GitHub declara USE_EXACT_ALARM, que se concede al instalar y no se puede quitar: ahí
 * [puede] siempre es `true` y todo sigue igual que antes (`setAlarmClock`). La versión de Play no
 * puede declararlo (solo lo admite en despertadores y calendarios) y lleva SCHEDULE_EXACT_ALARM, que
 * **el usuario concede o quita en Ajustes**. Sin él, `setAlarmClock` lanza SecurityException; en vez
 * de reventar se pone la alarma **inexacta** (`setAndAllowWhileIdle`, que también suena en reposo,
 * con algún minuto de retraso) y, cuando es el usuario quien acaba de poner el recordatorio, se le
 * **ofrece** el permiso ([ofrecerSiFalta]): un aviso breve y una notificación que lleva a Ajustes.
 *
 * Al concederlo, el sistema manda `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` a
 * [BootReceiver], que vuelve a poner como exactas las que estaban esperando ([Recordatorios.reponerTodos]).
 */
object AlarmaExacta {

    /** Cómo se pone una alarma. */
    enum class Modo {
        /** `setAlarmClock`: a la hora en punto, con el icono del despertador. */
        EXACTA,
        /** `setAndAllowWhileIdle(RTC_WAKEUP)`: suena aunque el teléfono duerma, con algo de retraso. */
        INEXACTA
    }

    /**
     * **Qué alarma toca**, sin Android para poder probarlo: antes de Android 12 no hay permiso que
     * pedir; desde Android 12, solo exacta si el sistema dice que se puede.
     */
    fun modo(sdk: Int, puedeExactas: Boolean): Modo =
        if (sdk >= Build.VERSION_CODES.S && !puedeExactas) Modo.INEXACTA else Modo.EXACTA

    /** Si este aparato deja poner alarmas exactas a PixPin ahora mismo. */
    fun puede(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val gestor = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { gestor.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /**
     * **Pone la alarma** [aviso] para [cuando], exacta si se puede. Nunca lanza: si la exacta falla
     * (el permiso se quitó entre medias) cae a la inexacta, y si también falla, a la de siempre.
     * Llegar algo tarde es mucho mejor que no llegar.
     */
    fun programar(context: Context, gestor: AlarmManager, cuando: Long, aviso: PendingIntent) {
        if (modo(Build.VERSION.SDK_INT, puede(context)) == Modo.EXACTA) {
            val puesta = runCatching { gestor.setAlarmClock(AlarmManager.AlarmClockInfo(cuando, aviso), aviso) }.isSuccess
            if (puesta) return
        }
        runCatching { gestor.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, aviso) }
            .onFailure { runCatching { gestor.set(AlarmManager.RTC_WAKEUP, cuando, aviso) } }
    }

    /** El intento que abre en Ajustes el permiso de alarmas exactas de PixPin. */
    fun intentDeAjustes(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * **Se ofrece el permiso** cuando el usuario acaba de poner un recordatorio y no se puede a la hora
     * exacta. No se le manda a Ajustes sin preguntar (sería sacarlo de lo que hacía): se le dice en un
     * aviso breve y se deja una notificación que lleva ahí al tocarla. Con permiso no hace nada, así que
     * en el APK de GitHub nunca se ve.
     */
    fun ofrecerSiFalta(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || puede(context)) return
        val ahora = System.currentTimeMillis()
        synchronized(this) {
            if (!tocaOfrecer(ultimaOferta, ahora)) return
            ultimaOferta = ahora
        }
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(
                    app,
                    "Sonará con algún minuto de retraso: permite «Alarmas y recordatorios» para PixPin (toca el aviso)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        runCatching {
            val avisos = app.getSystemService(NotificationManager::class.java) ?: return
            avisos.createNotificationChannel(
                NotificationChannel(CANAL, "Permisos", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val abrir = PendingIntent.getActivity(
                app, AVISO, intentDeAjustes(app),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = androidx.core.app.NotificationCompat.Builder(app, CANAL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Recordatorios a la hora exacta")
                .setContentText("Toca para permitir «Alarmas y recordatorios» a PixPin")
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                        "Sin este permiso los recordatorios, las alarmas y las llamadas del horario suenan con algún minuto de retraso. Toca para permitir «Alarmas y recordatorios» a PixPin."
                    )
                )
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .build()
            avisos.notify(AVISO, n)
        }
    }

    /**
     * **Si toca ofrecerlo otra vez**: como mucho una vez cada [CADA_MS]. Mover una clase en la rejilla
     * del horario guarda (y rehace las alarmas) en cada gesto, y no se va a repetir el aviso en cada uno.
     */
    fun tocaOfrecer(ultima: Long, ahora: Long): Boolean = ultima == 0L || ahora - ultima >= CADA_MS || ahora < ultima

    @Volatile private var ultimaOferta = 0L

    /** Ya hay permiso: el aviso que lo pedía sobra. */
    fun quitarElAviso(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(AVISO) }
    }

    const val CADA_MS = 10 * 60_000L
    private const val CANAL = "permisos"
    private const val AVISO = 0x0A1A
}
