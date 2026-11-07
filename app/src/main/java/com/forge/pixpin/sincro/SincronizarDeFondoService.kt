package com.forge.pixpin.sincro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.forge.pixpin.MainActivity
import com.forge.pixpin.R

/**
 * **Sincronizar en segundo plano** (3-nov-2026, el usuario: «que se pueda sincronizar en el
 * background con otros dispositivos»).
 *
 * Antes, un minuto después de salir de PinDesk se dejaba de escuchar y de sincronizar ([Presencia]):
 * lo hecho en el PC no llegaba hasta volver a abrir la app, y lo de aquí no salía. Ahora un servicio
 * en primer plano, con su aviso discreto, mantiene la escucha, el anuncio en la Wi-Fi y la vuelta
 * automática ([SincronizacionAutomatica]) mientras el teléfono no lo pare.
 *
 * - **Arranca al abrir la app** (con PinDesk a la vista): Android no deja arrancar un servicio así
 *   desde segundo plano. Luego sigue al salir.
 * - Solo si hay con quién: un grupo de aparatos o algún chat compartido. Y el ajuste
 *   «Sincronizar en segundo plano» ([com.forge.pixpin.data.Settings.sincronizarDeFondo]) encendido.
 * - Android 15 limita estos servicios a unas horas al día ([onTimeout]): al llegar al tope se para, y
 *   vuelve la próxima vez que se abra la app.
 */
class SincronizarDeFondoService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, getString(R.string.sf_canal), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                description = getString(R.string.sf_canal_detalle)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching { startForeground(AVISO, aviso(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        activo = true
        Presencia.deFondo(true)
        return START_STICKY
    }

    /** El tope de Android 15 para `dataSync`: se para, y la app vuelve a escuchar al abrirse. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        parar(applicationContext)
    }

    override fun onDestroy() {
        activo = false
        Presencia.deFondo(false)
        super.onDestroy()
    }

    private fun aviso(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.sf_aviso))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .build()
    }

    companion object {
        private const val CANAL = "sincronizar-de-fondo"
        private const val AVISO = 4417
        @Volatile var activo = false
            private set

        /** Con PinDesk a la vista: lo arranca si toca (ajuste encendido y alguien con quien). */
        fun siToca(c: Context, hayConQuien: Boolean) {
            val app = c.applicationContext
            val quiere = (app as? com.forge.pixpin.PixPinApp)?.ajustes?.sincronizarDeFondo ?: true
            if (!quiere || !hayConQuien) { parar(app); return }
            if (activo) return
            runCatching { app.startForegroundService(Intent(app, SincronizarDeFondoService::class.java)) }
        }

        fun parar(c: Context) {
            runCatching { c.applicationContext.stopService(Intent(c.applicationContext, SincronizarDeFondoService::class.java)) }
        }
    }
}
