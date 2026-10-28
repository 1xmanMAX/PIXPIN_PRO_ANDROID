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
import android.os.PowerManager
import android.os.SystemClock
import com.forge.pixpin.R
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Sube al PC las fotos y los clips de su grabación, en segundo plano** (28-oct-2026, §7.3 y §8
 * de `docs/movil-como-camara.md` del PC: «un servicio en primer plano, `dataSync`, notificación
 * «Subiendo 3 fotos al PC…»»).
 *
 * Mientras quede algo en la [ColaDeLaCamara], llama al PC de cada toma (su última dirección; si no
 * contesta, lo busca por la red con [AlDia.aparatos]) y le sube lo suyo por una sola conexión
 * ([SubidaALaGrabacion]). Si no se puede, espera 2, 4, 8… s hasta un minuto ([ColaDeLaCamara.espera])
 * y vuelve a probar. **Sigue subiendo aunque la grabación ya acabara** (el PC aún lo mete en la
 * clase guardada) **y aunque se cierre la pantalla de cámara**.
 *
 * Tras [SIN_AVANCE] sin poder subir nada, se para para no gastar batería: lo que quede sale la
 * próxima vez que se abra PixPin ([CamaraDelPc] lo empuja) o con la siguiente foto.
 *
 * Un servicio y no WorkManager porque el proyecto no lo usa y esto ya es lo que hace
 * [com.forge.pixpin.guardados.PasarATextoService]: un hilo, una notificación y un `WakeLock`.
 */
class SubidaDeLaCamaraService : Service() {

    companion object {
        private const val CANAL = "pixpin_camara_del_pc"
        private const val AVISO = 4714
        /** Lo que se insiste sin subir nada antes de dejarlo para luego. */
        private const val SIN_AVANCE = 15 * 60_000L
        private val corriendo = AtomicBoolean(false)
        /** El servicio en marcha, si Android lo dejó arrancar: su notificación y quién lo para. */
        @Volatile private var vivo: SubidaDeLaCamaraService? = null

        /**
         * **Que suba lo pendiente**: arranca el servicio si hay algo en la cola y no está ya en
         * marcha. Si Android no deja arrancarlo (la app en segundo plano), lo intenta en un hilo
         * mientras dure el proceso.
         */
        fun empujar(context: Context) {
            val app = context.applicationContext
            if (!ColaDeLaCamara.de(app.filesDir).hayPendientes()) return
            runCatching { app.startForegroundService(Intent(app, SubidaDeLaCamaraService::class.java)) }
                .onFailure {
                    android.util.Log.w("PixPinCamara", "sin servicio en primer plano", it)
                    lanzar(app)
                }
        }

        private fun lanzar(app: Context) {
            if (!corriendo.compareAndSet(false, true)) return
            Thread({
                var terminado = false
                try {
                    bucle(app)
                    terminado = true
                } finally {
                    corriendo.set(false)
                    val s = vivo
                    // Lo que entró justo al acabar: otra vuelta. Si se rindió, hasta la próxima.
                    if (terminado && s != null && !s.parado && ColaDeLaCamara.de(app.filesDir).hayPendientes() && !rendido) lanzar(app)
                    else s?.terminar()
                }
            }, "pixpin-camara-subida").start()
        }

        /** Si la última vuelta lo dejó por no poder subir nada en [SIN_AVANCE]. */
        @Volatile private var rendido = false

        private fun bucle(app: Context) {
            rendido = false
            val cola = ColaDeLaCamara.de(app.filesDir)
            var fallos = 0
            var avanzoEn = SystemClock.elapsedRealtime()
            while (true) {
                val pendientes = cola.pendientes()
                if (pendientes.isEmpty()) break
                vivo?.poner(ColaDeLaCamara.aviso(pendientes))
                var algo = false
                var falta = false
                for (pc in pendientes.map { it.pc }.distinct()) {
                    val r = runCatching { subirA(app, cola, pc, buscar = fallos >= 2 && fallos % 3 == 2) }
                    val p = r.getOrNull()
                    if (p == null) { falta = true; continue }
                    if (p.subidas + p.negadas > 0) algo = true
                    if (p.reintentar) falta = true
                }
                if (algo) { fallos = 0; avanzoEn = SystemClock.elapsedRealtime() }
                if (!falta) continue
                if (SystemClock.elapsedRealtime() - avanzoEn > SIN_AVANCE) { rendido = true; break }
                fallos++
                Thread.sleep(ColaDeLaCamara.espera(fallos))
            }
            cola.limpiar(System.currentTimeMillis())
        }

        /** Una pasada con [pc]: su dirección de siempre o, con [buscar], la que diga la red. */
        private fun subirA(app: Context, cola: ColaDeLaCamara, pc: String, buscar: Boolean): SubidaALaGrabacion.Pasada? {
            // Por la conexión caliente si está abierta con ese PC (28-oct-2026, §11.7); si no, una
            // propia, de una en una con ella ([ConexionCaliente.SUBIDA]).
            ConexionCaliente.subir(pc, cola)?.let { return it }
            return synchronized(ConexionCaliente.SUBIDA) { subirConLaSuya(app, cola, pc, buscar) }
        }

        private fun subirConLaSuya(app: Context, cola: ColaDeLaCamara, pc: String, buscar: Boolean): SubidaALaGrabacion.Pasada? {
            val disco = Disco(app.filesDir)
            val identidad = disco.identidad.leer()
            if (buscar) runCatching { kotlinx.coroutines.runBlocking { AlDia.aparatos(app) } }
            val (host, puerto) = disco.direcciones()[pc] ?: return null
            return Red.conectar(InetAddress.getByName(host), puerto).use { s ->
                SubidaALaGrabacion.subir(
                    s.getInputStream(), s.getOutputStream(), identidad, cola, pc,
                    miPuerto = runCatching { Presencia.red.puerto }.getOrDefault(0)
                )
            }
        }
    }

    @Volatile private var parado = false
    private var despierto: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Mientras sube, la baliza oye: si el PC cambia de dirección se sabe en un segundo (§11.8).
        BalizaDelMovil.servicio(BalizaDelMovil.SUBIDA, true)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Cámara para el PC", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "Las fotos y los clips que van a la reunión que graba el PC"
            }
        )
        despierto = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pixpin:camara-del-pc")
            ?.apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendientes = ColaDeLaCamara.de(filesDir).pendientes()
        runCatching { startForeground(AVISO, aviso(ColaDeLaCamara.aviso(pendientes)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            .onFailure { android.util.Log.w("PixPinCamara", "startForeground", it) }
        vivo = this
        parado = false
        if (pendientes.isEmpty() && !corriendo.get()) terminar() else lanzar(applicationContext)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        parado = true
        BalizaDelMovil.servicio(BalizaDelMovil.SUBIDA, false)
        if (vivo === this) vivo = null
        runCatching { despierto?.release() }
        super.onDestroy()
    }

    fun terminar() {
        parado = true
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    fun poner(texto: String) {
        if (parado) return
        runCatching { getSystemService(NotificationManager::class.java).notify(AVISO, aviso(texto)) }
    }

    private fun aviso(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, CamaraDelPcActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(texto)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .build()
    }
}
