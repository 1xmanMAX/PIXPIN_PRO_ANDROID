package com.forge.pixpin.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.forge.pixpin.MainActivity
import com.forge.pixpin.R
import com.forge.pixpin.capture.CaptureService

/**
 * Servicio ambiental siempre activo: mantiene la notificación persistente,
 * la bola flotante (M3) y aloja los pines overlay (M4).
 * No tiene relación con la captura de pantalla; por eso puede arrancar
 * en el boot incluso en Android 15 (a diferencia del tipo mediaProjection).
 */
class PinHostService : Service() {

    companion object {
        const val CHANNEL_ID = "pixpin_ambient"
        const val NOTIF_ID = 1
        const val ACTION_CAPTURE = "com.forge.pixpin.action.NOTIF_CAPTURE"
        const val ACTION_OPEN = "com.forge.pixpin.action.NOTIF_OPEN"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PinHostService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> CaptureService.requestCapture(this)
            ACTION_OPEN -> startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(context: Context): Notification {
        val capturePi = PendingIntent.getService(
            context, 0,
            Intent(context, PinHostService::class.java).setAction(ACTION_CAPTURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_text))
            .addAction(0, context.getString(R.string.action_capture), capturePi)
            .addAction(0, context.getString(R.string.action_open), openPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
