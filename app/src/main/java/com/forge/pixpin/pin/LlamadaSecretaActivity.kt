package com.forge.pixpin.pin

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * **La llamada secreta** (20-sep-2026): un recordatorio que solo oye quien lo puso.
 *
 * Lo pidió el usuario: a la hora fijada **suena como si llamaran por teléfono**; se contesta, se
 * lleva el teléfono a la oreja y lo que se oye es **la nota de voz que uno mismo se grabó**, por
 * el **auricular** —el altavoz pequeño de las llamadas— y no por el de fuera. Nadie alrededor
 * oye el recado, y a nadie le extraña que alguien conteste una llamada.
 *
 * Se pone desde el chat: a una nota de voz se le da una hora («Llamada secreta» en su menú).
 * Al sonar ([RecordatorioReceiver]) se abre esta pantalla —también con el teléfono bloqueado—:
 *
 * - **Sonando**: el tono de llamada del teléfono y vibración, nombre de quien «llama», contestar y
 *   colgar. Si no se contesta en [SEGUNDOS_SONANDO] s, se corta y queda un pin de «llamada perdida».
 * - **Contestada**: la grabación por el auricular (`MODE_IN_COMMUNICATION`, uso de voz), el contador
 *   de la llamada, y la pantalla **se apaga al acercarla a la cara**, como en una llamada de
 *   verdad. Hay un botón de altavoz por si se quiere oír sin acercarlo. Al acabar, cuelga sola.
 *
 * Al salir se deja el audio del teléfono **como estaba**.
 */
class LlamadaSecretaActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_NOMBRE = "nombre"
        private const val SEGUNDOS_SONANDO = 45

        private const val CANAL = "llamadas"
        private const val AVISO = 7741

        private fun laIntencion(context: Context, audio: String, nombre: String) =
            Intent(context, LlamadaSecretaActivity::class.java)
                .putExtra(EXTRA_AUDIO, audio)
                .putExtra(EXTRA_NOMBRE, nombre)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        /**
         * **Como llaman las aplicaciones de llamadas**: con un aviso de pantalla completa. Abrir
         * la pantalla a pelo desde la alarma lo corta el sistema cuando la aplicación está en
         * segundo plano —y más en las capas de Huawei o Xiaomi—: no daba error, simplemente no
         * salía nada y el usuario se quedaba sin su «llamada». El aviso de categoría llamada sí
         * pasa: con el aparato bloqueado abre la pantalla entera, y en uso sale arriba para tocarlo.
         */
        fun llamar(context: Context, audio: String, nombre: String) {
            val avisos = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                avisos.createNotificationChannel(
                    android.app.NotificationChannel(CANAL, "Llamadas", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(null, null); enableVibration(false)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )
            }
            val pendiente = android.app.PendingIntent.getActivity(
                context, AVISO, laIntencion(context, audio, nombre),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val aviso = androidx.core.app.NotificationCompat.Builder(context, CANAL)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(nombre)
                .setContentText("Llamada entrante")
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setOngoing(true).setAutoCancel(true)
                .setContentIntent(pendiente)
                .setFullScreenIntent(pendiente, true)
                .setTimeoutAfter(60_000)
                .build()
            runCatching { avisos.notify(AVISO, aviso) }
            // Y a pelo también: donde el sistema lo deja, sale al instante.
            runCatching { abrir(context, audio, nombre) }
        }

        fun abrir(context: Context, audio: String, nombre: String) {
            context.startActivity(
                Intent(context, LlamadaSecretaActivity::class.java)
                    .putExtra(EXTRA_AUDIO, audio)
                    .putExtra(EXTRA_NOMBRE, nombre)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
        }
    }

    private var tono: Ringtone? = null
    private var voz: MediaPlayer? = null
    private var cerca: PowerManager.WakeLock? = null
    private var modoDeAntes = AudioManager.MODE_NORMAL
    private var altavozDeAntes = false
    private var contestada by mutableStateOf(false)
    private var enAltavoz by mutableStateOf(false)
    private val audio: AudioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Encima del bloqueo y encendiendo la pantalla, como una llamada.
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        // La pantalla ya está: el aviso que la trajo sobra.
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(AVISO) }
        val ruta = intent?.getStringExtra(EXTRA_AUDIO)
        if (ruta == null || !java.io.File(ruta).exists()) { finish(); return }
        val nombre = intent?.getStringExtra(EXTRA_NOMBRE).orEmpty().ifBlank { "Llamada" }
        sonar()
        setContent { Pantalla(nombre, ruta) }
    }

    private fun sonar() {
        runCatching {
            tono = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.also {
                it.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                if (Build.VERSION.SDK_INT >= 28) it.isLooping = true
                // En silencio o en vibración el tono no suena: el teléfono manda, como con una llamada.
                if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) it.play()
            }
        }
        if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT) runCatching {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0)
            )
        }
    }

    private fun callar() {
        runCatching { tono?.stop() }
        tono = null
        @Suppress("DEPRECATION")
        runCatching { (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel() }
    }

    /** Contestar: fuera el tono, y la grabación por el auricular. */
    private fun contestar(ruta: String) {
        callar()
        contestada = true
        modoDeAntes = audio.mode
        @Suppress("DEPRECATION")
        altavozDeAntes = audio.isSpeakerphoneOn
        porDonde(altavoz = false)
        runCatching {
            voz = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(ruta)
                setOnCompletionListener { window.decorView.postDelayed({ colgar() }, 900) }
                prepare()
                start()
            }
        }.onFailure { colgar() }
        // La pantalla se apaga al acercarla a la cara, para no tocar nada con la mejilla.
        runCatching {
            val energia = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (energia.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                cerca = energia.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "pixpin:llamada").also { it.acquire(10 * 60 * 1000L) }
            }
        }
    }

    /** Por el auricular de las llamadas, o por el altavoz si se pide. */
    private fun porDonde(altavoz: Boolean) {
        enAltavoz = altavoz
        runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val tipo = if (altavoz) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                audio.availableCommunicationDevices.firstOrNull { it.type == tipo }?.let { audio.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = altavoz
            }
        }
    }

    private fun colgar() {
        if (isFinishing) return
        finish()
    }

    override fun onDestroy() {
        callar()
        runCatching { voz?.stop(); voz?.release() }
        voz = null
        runCatching { if (cerca?.isHeld == true) cerca?.release() }
        // El audio del teléfono, como estaba.
        if (contestada) runCatching {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            else @Suppress("DEPRECATION") { audio.isSpeakerphoneOn = altavozDeAntes }
            audio.mode = modoDeAntes
        }
        super.onDestroy()
    }

    @Composable
    private fun Pantalla(nombre: String, ruta: String) {
        BackHandler { colgar() }
        var segundos by androidx.compose.runtime.remember { mutableStateOf(0) }
        LaunchedEffect(contestada) {
            segundos = 0
            while (true) {
                delay(1000)
                segundos++
                if (!contestada && segundos >= SEGUNDOS_SONANDO) {
                    // Nadie contestó: queda dicho, como una llamada perdida.
                    runCatching { (application as? com.forge.pixpin.PixPinApp)?.overlayManager?.pinTexto("Llamada perdida · $nombre") }
                    colgar()
                }
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF101A33), Color(0xFF05070F))))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (contestada) "%d:%02d".format(segundos / 60, segundos % 60) else "Llamada entrante", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            Spacer(Modifier.height(26.dp))
            Box(Modifier.size(112.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(64.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(nombre, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (contestada) {
                    Redondo(Icons.Filled.VolumeUp, "Altavoz", if (enAltavoz) Color.White else Color.White.copy(alpha = 0.16f), if (enAltavoz) Color.Black else Color.White) { porDonde(!enAltavoz) }
                    Redondo(Icons.Filled.CallEnd, "Colgar", Color(0xFFE5484D), Color.White) { colgar() }
                } else {
                    Redondo(Icons.Filled.CallEnd, "Colgar", Color(0xFFE5484D), Color.White) { colgar() }
                    Redondo(Icons.Filled.Call, "Contestar", Color(0xFF2FB344), Color.White) { contestar(ruta) }
                }
            }
        }
    }

    @Composable
    private fun Redondo(icono: ImageVector, texto: String, fondo: Color, tinta: Color, onToque: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(fondo).clickable(onClick = onToque), contentAlignment = Alignment.Center) {
                Icon(icono, contentDescription = texto, tint = tinta, modifier = Modifier.size(32.dp))
            }
            Text(texto, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
