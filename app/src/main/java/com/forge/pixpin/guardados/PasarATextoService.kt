package com.forge.pixpin.guardados

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
import android.os.PowerManager
import com.forge.pixpin.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * **Lo que mantiene viva la app mientras Qwen3-ASR trabaja** (25-oct-2026, `docs/voz-qwen3.md`
 * § Android: «en un servicio en primer plano»). **Y cualquier motor, desde el 28-oct-2026**: todo lo
 * que se pasa a texto (la cola de [PasarATexto], [Transcriptor.transcribir]) va dentro, que con la
 * app fuera es lo único que impide que Android congele o mate el proceso a medias.
 *
 * Pasar una clase de una hora con Qwen3-ASR son decenas de minutos de CPU con ~1,5 GB ocupados,
 * y bajar el modelo, casi 1 GB. Sin un servicio en primer plano, en cuanto el usuario sale de la
 * app Android la ve como un proceso de fondo que come memoria y la mata a la primera: se pierde
 * todo lo hecho. Con él, una notificación dice qué se está haciendo y cuánto lleva, y un
 * `WakeLock` parcial deja la CPU despierta con la pantalla apagada.
 *
 * No lleva el trabajo: quien lo hace lo envuelve en [mientras], en su propio hilo, y el servicio
 * solo existe mientras haya alguno en marcha. Tipo `mediaProcessing` para transcribir (Android 15,
 * el que es para procesar audio) y `dataSync` para bajar (y para transcribir en Android 14, que no
 * tiene el otro). Si Android no deja arrancarlo (la app ya en segundo plano), se trabaja igual.
 */
class PasarATextoService : Service() {

    /** Un trabajo en marcha: qué dice la notificación y cuánto lleva. */
    private class Trabajo(val titulo: String, val descarga: Boolean) {
        @Volatile var avance = 0f
    }

    companion object {
        private const val CANAL = "pixpin_pasar_a_texto"
        private const val AVISO = 13
        private val trabajos = ConcurrentHashMap<Long, Trabajo>()
        private val siguiente = AtomicLong()
        @Volatile private var vivo: PasarATextoService? = null

        /**
         * **Hace [cuerpo] con el servicio en marcha**, en el hilo de quien llama. [cuerpo] recibe
         * con qué decir el avance (0 a 1) en la notificación. El servicio se va con el último.
         */
        fun <T> mientras(context: Context, titulo: String, descarga: Boolean, cuerpo: ((Float) -> Unit) -> T): T {
            val id = reservar(context, titulo, descarga)
            val t = trabajos[id]
            var ultimo = -1
            try {
                return cuerpo { a ->
                    t?.avance = a
                    val pct = (a * 100).toInt()
                    if (pct != ultimo) { ultimo = pct; vivo?.poner() }
                }
            } finally {
                soltar(id)
            }
        }

        /**
         * **Arranca el servicio ya, para un trabajo que vendrá luego** (28-oct-2026), y devuelve con qué
         * soltarlo ([soltar]). Lo usa la reunión presencial al pulsar «Terminar»: en ese momento Android
         * deja arrancar un servicio en primer plano (es un toque del usuario en la notificación, la
         * burbuja o la app); unos segundos después, cuando ya está guardada y toca pasarla a texto,
         * quizá ya no. Reservado, el servicio sigue vivo hasta que la cola coja el trabajo.
         */
        fun reservar(context: Context, titulo: String, descarga: Boolean = false): Long {
            val app = context.applicationContext
            val id = siguiente.incrementAndGet()
            trabajos[id] = Trabajo(titulo, descarga)
            if (vivo != null) vivo?.poner()
            else runCatching { app.startForegroundService(Intent(app, PasarATextoService::class.java)) }
                .onFailure { android.util.Log.w("PixPinVoz", "sin servicio en primer plano: $titulo", it) }
            return id
        }

        /** Suelta lo reservado con [reservar]; con el último, el servicio se va. */
        fun soltar(id: Long) {
            if (trabajos.remove(id) == null) return
            // Se para él mismo y no con `stopService`: parado antes de su `startForeground`,
            // Android lo da por un servicio que incumplió y tumba la app. Si aún no arrancó,
            // al arrancar verá que no queda nada y se irá.
            if (trabajos.isEmpty()) vivo?.terminar() else vivo?.poner()
        }

        /** «Reintentar» de la notificación de la cola: retoma lo pendiente (ver [PasarATexto]). */
        const val REANUDAR = "com.forge.pixpin.action.PASAR_A_TEXTO_REANUDAR"
    }

    private var despierto: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Pasar a texto", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "Una grabación pasándose a texto, o el modelo de voz bajándose"
            }
        )
        despierto = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pixpin:pasar-a-texto")
            ?.apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == REANUDAR) {
            // Desde la notificación de «falta el modelo»: con un trabajo reservado mientras arranca la
            // cola, para que esto sea un servicio en primer plano de verdad.
            val r = reservar(this, getString(com.forge.pixpin.R.string.pasar_a_texto_titulo))
            PasarATexto.reanudar(this, aunSinRed = true)
            android.os.Handler(mainLooper).postDelayed({ soltar(r) }, 3_000)
        }
        // Arrancado tarde (el trabajo ya acabó): startForeground igual, que Android lo exige, y fuera.
        val tipo = if (trabajos.values.any { !it.descarga } && Build.VERSION.SDK_INT >= 35)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        runCatching { startForeground(AVISO, aviso(), tipo) }
            .onFailure { android.util.Log.w("PixPinVoz", "startForeground", it) }
        vivo = this
        if (trabajos.isEmpty()) terminar()
        // Si el sistema lo mata, el trabajo se fue con él: no tiene sentido volver.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        vivo = null
        runCatching { despierto?.release() }
        super.onDestroy()
    }

    fun terminar() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** La notificación, al día. */
    fun poner() {
        if (trabajos.isEmpty()) return
        runCatching { getSystemService(NotificationManager::class.java).notify(AVISO, aviso()) }
    }

    private fun aviso(): Notification {
        val t = trabajos.values.firstOrNull()
        val pct = ((t?.avance ?: 0f) * 100).toInt().coerceIn(0, 100)
        val abrir = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(t?.titulo ?: "Pasar a texto")
            .setContentText("$pct %")
            .setProgress(100, pct, t == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { abrir?.let { setContentIntent(it) } }
            .build()
    }
}
