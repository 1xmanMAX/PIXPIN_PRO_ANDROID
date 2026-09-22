package com.forge.pixpin.ui

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
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import com.forge.pixpin.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * **Lo que deja sonar un Word o un libro con el visor cerrado** (22-sep-2026, pedido por el
 * usuario: «que el audio continúe al salir y se ponga en pantalla como si estuviera reproduciendo
 * un audio»).
 *
 * Un servicio en primer plano de tipo `mediaPlayback` con **una sesión multimedia**: la
 * notificación es la de un reproductor —anterior, pausa, siguiente y cerrar—, y los mismos mandos
 * salen en la pantalla de bloqueo y los responden los auriculares. Tocarla vuelve al documento.
 * Quitar los auriculares pausa, como en cualquier reproductor.
 *
 * No lleva el motor: el que habla es [LectorEnVoz.delApp], el mismo que maneja el visor; esto solo
 * lo mantiene vivo y lo enseña. Se va solo cuando el lector se suelta (la X de la barra o de aquí).
 */
class LeyendoEnVozService : Service() {

    companion object {
        private const val CANAL = "pixpin_leyendo"
        private const val AVISO = 7
        private const val ANTERIOR = "com.forge.pixpin.voz.ANTERIOR"
        private const val ALTERNAR = "com.forge.pixpin.voz.ALTERNAR"
        private const val SIGUIENTE = "com.forge.pixpin.voz.SIGUIENTE"
        private const val CERRAR = "com.forge.pixpin.voz.CERRAR"

        /** Se llama al empezar a escuchar, con el visor a la vista. */
        fun arrancar(context: Context) {
            runCatching { context.startForegroundService(Intent(context, LeyendoEnVozService::class.java)) }
        }
    }

    private val lector by lazy { LectorEnVoz.delApp(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sesion: MediaSession

    /** Quitar los auriculares: se pausa, que no se ponga a hablar por el altavoz. */
    private val alQuitarAuriculares = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) lector.pausar()
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Leyendo en voz alta", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "Los mandos del Word o el libro que se está escuchando"
            }
        )
        sesion = MediaSession(this, "PixPinLeyendo").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = lector.seguir()
                override fun onPause() = lector.pausar()
                override fun onSkipToNext() = lector.saltar(1)
                override fun onSkipToPrevious() = lector.saltar(-1)
                override fun onStop() = cerrar()
            })
            isActive = true
        }
        startForeground(AVISO, aviso(lector.estado.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        androidx.core.content.ContextCompat.registerReceiver(this, alQuitarAuriculares, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            lector.estado.collect { e ->
                // Soltado (la X de la barra del visor, o no arrancó): se va la notificación.
                if (!e.listo) { stopSelf(); return@collect }
                poner(e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ANTERIOR -> lector.saltar(-1)
            ALTERNAR -> lector.alternar()
            SIGUIENTE -> lector.saltar(1)
            CERRAR -> cerrar()
        }
        // Si el sistema lo mata, el motor de voz se fue con él: no tiene sentido volver.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Quitar la app de recientes con el visor cerrado: se deja de leer, que no quede sonando sin dueño. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!lector.estado.value.leyendo) cerrar()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(alQuitarAuriculares) }
        sesion.isActive = false
        sesion.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun cerrar() {
        lector.soltar()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** La sesión (pantalla de bloqueo, auriculares) y la notificación, al día. */
    private fun poner(e: LectorEnVoz.Estado) {
        val acciones = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        sesion.setPlaybackState(
            PlaybackState.Builder()
                .setActions(acciones)
                .setState(if (e.leyendo) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (e.leyendo) e.velocidad else 0f)
                .build()
        )
        sesion.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, e.titulo.ifBlank { "Documento" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, donde(e))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "PixPin")
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(AVISO, aviso(e))
    }

    private fun donde(e: LectorEnVoz.Estado): String =
        (if (e.parrafo >= 0 && e.cuantos > 0) "Párrafo ${e.parrafo + 1} de ${e.cuantos}" else "Preparando la voz…") +
            (if (e.voz.isNotBlank()) " · ${e.voz}" else "")

    private fun aviso(e: LectorEnVoz.Estado): Notification {
        fun accion(que: String, icono: Int, rotulo: String) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icono), rotulo,
            PendingIntent.getService(this, que.hashCode(), Intent(this, LeyendoEnVozService::class.java).setAction(que), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ).build()
        val volver = lector.paraVolver?.let {
            PendingIntent.getActivity(this, 0, Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(e.titulo.ifBlank { "Documento" })
            .setContentText(donde(e))
            .setContentIntent(volver)
            .setDeleteIntent(PendingIntent.getService(this, 1, Intent(this, LeyendoEnVozService::class.java).setAction(CERRAR), PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(e.leyendo)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(accion(ANTERIOR, android.R.drawable.ic_media_previous, "Párrafo anterior"))
            .addAction(
                if (e.leyendo) accion(ALTERNAR, android.R.drawable.ic_media_pause, "Pausa")
                else accion(ALTERNAR, android.R.drawable.ic_media_play, "Leer")
            )
            .addAction(accion(SIGUIENTE, android.R.drawable.ic_media_next, "Párrafo siguiente"))
            .addAction(accion(CERRAR, android.R.drawable.ic_menu_close_clear_cancel, "Dejar de escuchar"))
            .setStyle(Notification.MediaStyle().setMediaSession(sesion.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }
}
