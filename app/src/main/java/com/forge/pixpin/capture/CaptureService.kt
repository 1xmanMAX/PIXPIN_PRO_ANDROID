package com.forge.pixpin.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.forge.pixpin.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Guardián del token de MediaProjection: se arranca al primer intento de
 * captura, recibe el consentimiento (vía [ConsentActivity]) y conserva el
 * objeto MediaProjection en memoria para que las siguientes capturas de la
 * sesión no vuelvan a pedir permiso. Android 14 exige que este servicio esté
 * en primer plano con el tipo mediaProjection ANTES de getMediaProjection().
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_CAPTURE_REQUEST = "com.forge.pixpin.action.CAPTURE_REQUEST"
        const val ACTION_PROJECTION_GRANTED = "com.forge.pixpin.action.PROJECTION_GRANTED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "pixpin_capture"
        private const val NOTIF_ID = 2

        @Volatile
        var projection: MediaProjection? = null
            private set

        /** Punto de entrada único para "quiero capturar" desde cualquier disparador. */
        fun requestCapture(context: Context) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(ACTION_CAPTURE_REQUEST)
            context.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE_REQUEST -> handleCaptureRequest()
            ACTION_PROJECTION_GRANTED -> handleProjectionGranted(
                intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                intent.getParcelableExtra(EXTRA_DATA)
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { projection?.stop() }
        projection = null
        scope.cancel()
        super.onDestroy()
    }

    private fun handleProjectionGranted(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            toast(getString(R.string.capture_consent_denied))
            return
        }
        val mgr = getSystemService(MediaProjectionManager::class.java)
        try {
            val proj = mgr.getMediaProjection(resultCode, data)
            if (proj == null) {
                toast(getString(R.string.capture_error))
                return
            }
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    projection = null
                }
            }, null)
            projection = proj
            // El consentimiento siempre viene de un intento de captura: continuar.
            grabAndDeliver()
        } catch (t: Throwable) {
            toast(getString(R.string.capture_error))
        }
    }

    private fun handleCaptureRequest() {
        if (projection == null) {
            // Sin token: abrir el diálogo de consentimiento del sistema.
            startActivity(
                Intent(this, ConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            grabAndDeliver()
        }
    }

    private fun grabAndDeliver() {
        val proj = projection ?: return
        scope.launch {
            val frame = withContext(Dispatchers.IO) { FrameGrabber.grab(this@CaptureService, proj) }
            if (frame != null) {
                FrameHolder.set(frame)
                startActivity(
                    Intent(this@CaptureService, CaptureActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                toast(getString(R.string.capture_error))
            }
        }
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.capture_notif_title))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
