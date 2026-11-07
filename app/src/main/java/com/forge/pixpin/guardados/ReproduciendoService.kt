package com.forge.pixpin.guardados

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import com.forge.pixpin.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **La música de PinDesk, para el sistema** (7-nov-2026, pedido del usuario: «el sistema no reconoce que
 * reproduzco música; que salgan los mandos en vivo como con Spotify»). Mientras [Reproductor] tiene algo cargado,
 * una sesión de medios y su notificación: los mandos de la notificación, de la pantalla de bloqueo y de los
 * ajustes rápidos (y los de los auriculares o el reloj), con la carátula y la barra del tiempo para arrastrar.
 * También: pausa al quitar los auriculares y cuando otra app se pone a sonar (el foco de audio).
 */
class ReproduciendoService : Service() {

    companion object {
        private const val CANAL = "pixpin_musica"
        private const val AVISO = 11
        private const val ATRAS = "com.forge.pixpin.musica.ATRAS"
        private const val ALTERNAR = "com.forge.pixpin.musica.ALTERNAR"
        private const val ADELANTE = "com.forge.pixpin.musica.ADELANTE"
        private const val CERRAR = "com.forge.pixpin.musica.CERRAR"

        /** Al empezar a sonar (lo llama [Reproductor.seguir]). */
        fun arrancar(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ReproduciendoService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sesion: MediaSession
    private lateinit var audio: AudioManager
    private var caratula: Bitmap? = null
    private var artista: String? = null
    private var deQue: String? = null

    private val alQuitarAuriculares = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) Reproductor.pausar()
        }
    }

    private val foco by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { cambio ->
                // Otra app suena (o una llamada): se pausa. No se vuelve sola, como Spotify con otra música.
                if (cambio == AudioManager.AUDIOFOCUS_LOSS || cambio == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) Reproductor.pausar()
            }.build()
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Música", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "Los mandos de la canción o el audio que suena"
            }
        )
        sesion = MediaSession(this, "PinDeskMusica").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Reproductor.seguir()
                override fun onPause() = Reproductor.pausar()
                override fun onSeekTo(pos: Long) = Reproductor.irAMs(pos.toInt())
                override fun onFastForward() = Reproductor.saltar(Reproductor.SALTO_MS)
                override fun onRewind() = Reproductor.saltar(-Reproductor.SALTO_MS)
                override fun onSkipToNext() = Reproductor.saltar(Reproductor.SALTO_MS)
                override fun onSkipToPrevious() = Reproductor.saltar(-Reproductor.SALTO_MS)
                override fun onStop() = cerrar()
            })
            isActive = true
        }
        startForeground(AVISO, aviso(Reproductor.estado.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        androidx.core.content.ContextCompat.registerReceiver(this, alQuitarAuriculares, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            var sonaba = false
            var clave: Any? = null
            var puestoEn = 0L; var posPuesta = 0
            Reproductor.estado.collect { e ->
                if (e.ruta == null) { cerrar(); return@collect }
                if (e.ruta != deQue) { deQue = e.ruta; leerEtiquetas(e.ruta) }
                if (e.sonando && !sonaba) audio.requestAudioFocus(foco)
                sonaba = e.sonando
                // Solo al cambiar algo (o al saltar): el sistema mueve la barra solo con la posición y la velocidad.
                val ahora = android.os.SystemClock.elapsedRealtime()
                val prevista = posPuesta + if (e.sonando) ((ahora - puestoEn) * e.velocidad).toInt() else 0
                val k = listOf(e.ruta, e.titulo, e.sonando, e.velocidad, e.duracionMs)
                if (k != clave || kotlin.math.abs(e.posicionMs - prevista) > 1_500) {
                    clave = k; puestoEn = ahora; posPuesta = e.posicionMs
                    poner(e)
                }
            }
        }
    }

    /** La carátula y el artista de las etiquetas de la canción (fuera del hilo de la pantalla). */
    private fun leerEtiquetas(ruta: String) {
        caratula = null; artista = null
        scope.launch {
            val (b, a) = withContext(Dispatchers.IO) {
                runCatching {
                    MediaMetadataRetriever().run {
                        try {
                            setDataSource(ruta)
                            embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } to extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        } finally { release() }
                    }
                }.getOrDefault(null to null)
            }
            if (deQue == ruta) { caratula = b; artista = a; Reproductor.estado.value.takeIf { it.ruta != null }?.let { poner(it) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ATRAS -> Reproductor.saltar(-Reproductor.SALTO_MS)
            ALTERNAR -> Reproductor.estado.value.let { if (it.sonando) Reproductor.pausar() else Reproductor.seguir() }
            ADELANTE -> Reproductor.saltar(Reproductor.SALTO_MS)
            CERRAR -> Reproductor.parar()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(alQuitarAuriculares) }
        runCatching { audio.abandonAudioFocusRequest(foco) }
        sesion.isActive = false
        sesion.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun cerrar() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun poner(e: Reproductor.Estado) {
        val acciones = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        sesion.setPlaybackState(
            PlaybackState.Builder().setActions(acciones)
                .setState(if (e.sonando) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, e.posicionMs.toLong(), if (e.sonando) e.velocidad else 0f)
                .build()
        )
        sesion.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, e.titulo.ifBlank { "Audio" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artista ?: "PinDesk")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, e.duracionMs.toLong())
                .apply { caratula?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(AVISO, aviso(e))
    }

    private fun aviso(e: Reproductor.Estado): Notification {
        fun accion(que: String, icono: Int, rotulo: String) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icono), rotulo,
            PendingIntent.getService(this, que.hashCode(), Intent(this, ReproduciendoService::class.java).setAction(que), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ).build()
        val abrir = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(e.titulo.ifBlank { "Audio" })
            .setContentText(artista ?: "PinDesk")
            .apply { caratula?.let { setLargeIcon(it) } }
            .setContentIntent(abrir)
            .setDeleteIntent(PendingIntent.getService(this, 1, Intent(this, ReproduciendoService::class.java).setAction(CERRAR), PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(e.sonando)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(accion(ATRAS, android.R.drawable.ic_media_rew, "10 s atrás"))
            .addAction(if (e.sonando) accion(ALTERNAR, android.R.drawable.ic_media_pause, "Pausa") else accion(ALTERNAR, android.R.drawable.ic_media_play, "Reproducir"))
            .addAction(accion(ADELANTE, android.R.drawable.ic_media_ff, "10 s adelante"))
            .setStyle(Notification.MediaStyle().setMediaSession(sesion.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }
}
