package com.forge.pixpin.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.forge.pixpin.MainActivity
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.capture.CaptureFlow
import com.forge.pixpin.clipboard.ClipboardPinActivity

/**
 * Servicio ambiental siempre activo: mantiene la notificación persistente,
 * la bola flotante y los pines overlay.
 * No tiene relación con la captura de pantalla; por eso puede arrancar
 * en el boot incluso en Android 15 (a diferencia del tipo mediaProjection,
 * que el sistema prohíbe arrancar desde BOOT_COMPLETED).
 */
class PinHostService : Service() {

    companion object {
        const val CHANNEL_ID = "pixpin_ambient"
        const val NOTIF_ID = 1
        const val ACTION_CAPTURE = "com.forge.pixpin.action.NOTIF_CAPTURE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PinHostService::class.java))
        }
    }

    private var ball: FloatingBallController? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIF_ID,
            buildNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        ensureOverlays()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Se vuelve a intentar en cada arranque: el permiso de overlay puede
        // haberse concedido después de crear el servicio.
        ensureOverlays()
        if (intent?.action == ACTION_CAPTURE) CaptureFlow.requestCapture(this)
        return START_STICKY
    }

    override fun onDestroy() {
        ball?.hide()
        ball = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureOverlays() {
        if (!Settings.canDrawOverlays(this)) return
        if (ball == null) ball = FloatingBallController(this)
        if (ball?.isShowing != true) ball?.show()
        (application as PixPinApp).overlayManager.restoreOnStart()
    }

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
        val pinPi = PendingIntent.getActivity(
            context, 2,
            Intent(context, ClipboardPinActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_text))
            .setContentIntent(openPi)
            .addAction(0, context.getString(R.string.action_capture), capturePi)
            .addAction(0, context.getString(R.string.action_pin), pinPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
