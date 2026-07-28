package com.forge.pixpin.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.forge.pixpin.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sostiene el servicio en primer plano mientras la sesión de captura está viva.
 *
 * ORDEN OBLIGATORIO en Android 14+: el usuario concede el permiso ([ConsentActivity])
 * ANTES de que este servicio exista. Si se arranca un servicio de tipo
 * mediaProjection sin consentimiento previo, startForeground() lanza
 * SecurityException y el sistema mata la app. Por eso este servicio SOLO se
 * arranca desde ConsentActivity con el resultado ya en la mano.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START_SESSION = "com.forge.pixpin.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.forge.pixpin.action.STOP_SESSION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "pixpin_capture"
        private const val NOTIF_ID = 2

        /** Arranca la sesión con el consentimiento recién concedido. */
        fun startSession(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, CaptureService::class.java)
                    .setAction(ACTION_START_SESSION)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data)
            )
        }

        fun stopSession(context: Context) {
            ProjectionSession.stop()
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        ProjectionSession.onSessionLost = { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> startSession(intent)
            ACTION_STOP_SESSION -> stopSelf()
        }
        // NO_STICKY: si el sistema mata el servicio, la sesión de captura ya no
        // es válida; recrearlo sin token solo provocaría el crash de Android 14+.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ProjectionSession.onSessionLost = null
        ProjectionSession.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSession(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return
        }
        val proj = runCatching {
            getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, data)
        }.getOrNull()

        if (proj == null || !ProjectionSession.start(this, proj)) {
            CaptureFlow.toast(this, R.string.capture_error)
            stopSelf()
            return
        }
        // Margen extra: el diálogo de permiso acaba de cerrarse y la pantalla
        // tarda unos fotogramas en volver a la app del usuario.
        scope.launch { CaptureFlow.captureNow(this@CaptureService, settleMs = 500) }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP_SESSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.capture_notif_title))
            .setContentText(getString(R.string.capture_notif_text))
            .addAction(0, getString(R.string.capture_stop_session), stopPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
