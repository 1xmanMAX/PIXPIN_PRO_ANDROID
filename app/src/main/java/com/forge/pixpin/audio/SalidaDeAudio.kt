package com.forge.pixpin.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.forge.pixpin.audio.DecisionDeSalida.Preferida
import com.forge.pixpin.audio.DecisionDeSalida.Ruta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * **Por dónde suena todo lo que suena en la aplicación: altavoz, auricular de llamadas o
 * audífonos** (27-oct-2026, lo pidió el usuario: «que la música, los audios, todo se pueda
 * reproducir por el altavoz del oído»).
 *
 * Antes solo la lectura en voz alta ([com.forge.pixpin.ui.LectorEnVoz]) y la llamada secreta
 * sabían ir por el auricular, cada una con su copia del truco. Ahora el truco vive aquí y lo usan
 * todos los reproductores: el de la app ([com.forge.pixpin.guardados.Reproductor]: notas de voz,
 * música, clases), las lecciones, los pines con audio, Pronunciar, los audios de las notas y la
 * lectura en voz alta.
 *
 * ## El truco del auricular
 *
 * Android no deja mandar «medios» al auricular: ese altavoz es de las llamadas. Lo que hacen
 * WhatsApp y Telegram es **disfrazar el audio de llamada**: el reproductor se prepara con el uso
 * `USAGE_VOICE_COMMUNICATION` y el teléfono se pone en `MODE_IN_COMMUNICATION`, con el auricular
 * como aparato de la comunicación (`setCommunicationDevice`, Android 12+) o el altavoz apagado
 * (`isSpeakerphoneOn = false`, antes). El uso **se fija al preparar** el `MediaPlayer`: cambiar de
 * salida con el audio sonando obliga a rehacer el reproductor en el mismo punto, y eso lo hace
 * quien sabe ([sonando] con `rehacer`).
 *
 * ## Cuándo
 *
 * - **Los audífonos mandan**: puestos, todo va por ellos y el modo de llamada no se toca.
 * - **Solo mientras suena**: al parar o pausar, el teléfono vuelve al modo de antes (que no se
 *   quede «en llamada», con el volumen de llamada en los botones). Nadie tiene que acordarse de
 *   avisar: cada segundo se mira si lo registrado sigue sonando ([vigilar]).
 * - **El teléfono en la oreja**: con algo sonando que sabe cambiar de salida, el sensor de
 *   cercanía lo pasa al auricular y la pantalla se apaga (`PROXIMITY_SCREEN_OFF_WAKE_LOCK`); al
 *   apartarlo, vuelve al altavoz en pausa. Ver [DecisionDeSalida].
 * - **Si entra una llamada de verdad** el modo es suyo: solo se devuelve el modo si sigue en el
 *   que se puso aquí.
 *
 * La elección es del aparato (preferencias locales), no viaja con la sincronización: el PC no
 * tiene auricular de llamadas.
 *
 * Todo se usa desde el hilo principal.
 */
object SalidaDeAudio {
    private const val TAG = "SalidaDeAudio"
    private const val PREFS = "salida_de_audio"
    private const val CLAVE = "al_oido"

    private val _preferida = MutableStateFlow(Preferida.ALTAVOZ)
    /** Lo elegido con el botón. */
    val preferida: StateFlow<Preferida> = _preferida

    private val _ruta = MutableStateFlow(Ruta.ALTAVOZ)
    /** Por dónde sonaría ahora lo que empiece: lo que enseña el icono del botón. */
    val ruta: StateFlow<Ruta> = _ruta

    private var app: Context? = null
    private val principal = Handler(Looper.getMainLooper())
    private var auriculares = false
    private var cerca = false

    /** Algo que suena: cómo saber si sigue, si se preparó para el auricular y cómo rehacerlo. */
    private class Fuente(
        val sigue: () -> Boolean,
        var alOido: Boolean,
        val rehacer: ((pausar: Boolean) -> Unit)?,
        var calladas: Int = 0
    )
    private val fuentes = LinkedHashMap<Any, Fuente>()

    /** Arranca lo que haga falta (preferencia guardada, aviso de audífonos). Se puede llamar siempre. */
    fun iniciar(context: Context) {
        if (app != null) return
        val a = context.applicationContext
        app = a
        _preferida.value = runCatching {
            if (a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE, false)) Preferida.OIDO else Preferida.ALTAVOZ
        }.getOrDefault(Preferida.ALTAVOZ)
        runCatching {
            val am = a.getSystemService(AudioManager::class.java)
            auriculares = hayAuriculares(am)
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = alCambiarLosAparatos()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = alCambiarLosAparatos()
            }, principal)
        }.onFailure { Log.w(TAG, "sin aviso de audífonos: $it") }
        recalcular()
    }

    /** Cambia entre altavoz y auricular (el botón), lo guarda y rehace lo que esté sonando. */
    fun alternar(context: Context): Preferida {
        iniciar(context)
        val nueva = DecisionDeSalida.otra(_preferida.value)
        _preferida.value = nueva
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(CLAVE, nueva == Preferida.OIDO).apply()
        }
        recalcular()
        return nueva
    }

    /** Si un reproductor preparado [alOido] (o no) no va por donde toca ahora y hay que rehacerlo. */
    fun hayQueRehacer(alOido: Boolean): Boolean = app != null && DecisionDeSalida.hayQueRehacer(alOido, _ruta.value)

    /** Si hay audífonos puestos ahora (mandan sobre lo elegido). */
    fun conAudifonos(): Boolean = auriculares

    /** Si lo que empiece ahora va por el auricular de llamadas. */
    fun alOido(context: Context): Boolean {
        iniciar(context)
        return _ruta.value == Ruta.OIDO
    }

    /** Los atributos de audio para lo que empiece ahora: de llamada para el auricular, de medios si no. */
    fun atributos(context: Context, voz: Boolean = false): AudioAttributes = atributosPara(alOido(context), voz)

    fun atributosPara(alOido: Boolean, voz: Boolean = false): AudioAttributes =
        if (alOido) AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        else AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(if (voz) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    /**
     * **Prepara [p] para la salida de ahora.** Llamar después de `setDataSource` y **antes de
     * `prepare()`**: después ya no surte efecto. Devuelve si va por el auricular, que es lo que
     * luego se le pasa a [sonando].
     */
    fun preparar(context: Context, p: MediaPlayer): Boolean {
        val oido = alOido(context)
        runCatching { p.setAudioAttributes(atributosPara(oido)) }
        return oido
    }

    /**
     * [preparar] para quien no tiene un `Context` a mano (el [com.forge.pixpin.guardados.Reproductor]):
     * usa el de la aplicación, que pone `PixPinApp` al arrancar. Sin él, suena como siempre.
     */
    fun preparar(p: MediaPlayer): Boolean = app?.let { preparar(it, p) } ?: false

    /** [sonando] con el `Context` de la aplicación; sin él no hace nada. */
    fun sonando(clave: Any, alOido: Boolean, rehacer: ((pausar: Boolean) -> Unit)?, sigue: () -> Boolean) {
        app?.let { sonando(it, clave, alOido, rehacer, sigue) }
    }

    /**
     * **Un `MediaPlayer` que acaba de arrancar.** Con [alOido], el teléfono pasa a modo de llamada
     * mientras suene; deja de contar solo cuando para, acaba o se libera.
     */
    fun sonando(context: Context, p: MediaPlayer, alOido: Boolean) =
        sonando(context, p, alOido, null) { runCatching { p.isPlaying }.getOrDefault(false) }

    /**
     * **Algo que suena**, identificado por [clave]. [sigue] dice si sigue sonando (se mira cada
     * segundo; dos «no» seguidos y deja de contar). [rehacer], si lo hay, sabe volver a montarse
     * en el mismo punto por la otra salida: entonces también vale el gesto de llevárselo a la oreja.
     */
    fun sonando(context: Context, clave: Any, alOido: Boolean, rehacer: ((pausar: Boolean) -> Unit)?, sigue: () -> Boolean) {
        iniciar(context)
        fuentes[clave] = Fuente(sigue, alOido, rehacer)
        aplicar()
        principal.removeCallbacks(vigilar)
        principal.postDelayed(vigilar, 1000)
    }

    /** Ya no suena [clave] (al pausar o parar). Sin esto también se entera, un par de segundos después. */
    fun callado(clave: Any) {
        if (fuentes.remove(clave) != null) aplicar()
    }

    private val vigilar = object : Runnable {
        override fun run() {
            var cambio = false
            val i = fuentes.entries.iterator()
            while (i.hasNext()) {
                val f = i.next().value
                if (runCatching { f.sigue() }.getOrDefault(false)) f.calladas = 0
                else if (++f.calladas >= 2) { i.remove(); cambio = true }
            }
            if (cambio) aplicar()
            if (fuentes.isNotEmpty()) principal.postDelayed(this, 1000)
        }
    }

    private fun alCambiarLosAparatos() {
        val a = app ?: return
        val ahora = runCatching { hayAuriculares(a.getSystemService(AudioManager::class.java)) }.getOrDefault(false)
        if (ahora == auriculares) return
        auriculares = ahora
        recalcular()
    }

    /** La ruta de nuevo, y lo que esté sonando por la que no toca, rehecho. */
    private fun recalcular(pausar: Boolean = false) {
        val r = DecisionDeSalida.decidir(_preferida.value, auriculares, cerca)
        _ruta.value = r
        // Una copia: rehacer vuelve a llamar a [sonando] y cambia el mapa.
        fuentes.values.toList().forEach { f ->
            val rehacer = f.rehacer
            if (rehacer != null && DecisionDeSalida.hayQueRehacer(f.alOido, r)) {
                runCatching { rehacer(pausar) }.onFailure { Log.w(TAG, "no se pudo cambiar de salida: $it") }
            }
        }
        aplicar()
    }

    // ---- El modo de llamada, la cercanía y la pantalla ----

    private var modoDeAntes: Int? = null
    private var sensor: SensorEventListener? = null
    private var pantalla: PowerManager.WakeLock? = null

    /** Pone o quita el modo de llamada, el sensor y el apagado de la pantalla según lo que suena. */
    private fun aplicar() {
        val a = app ?: return
        val am = runCatching { a.getSystemService(AudioManager::class.java) }.getOrNull() ?: return
        if (fuentes.values.any { it.alOido }) ponerElAuricular(am) else devolverElModo(am)
        val escuchar = DecisionDeSalida.escucharLaCercania(fuentes.values.any { it.rehacer != null }, auriculares)
        if (escuchar) escucharLaCercania(a) else dejarDeEscuchar()
        // La pantalla se apaga en la oreja mientras suena por el auricular o se escucha la cercanía.
        if (escuchar || fuentes.values.any { it.alOido }) apagarAlAcercar(a) else soltarLaPantalla()
    }

    private fun ponerElAuricular(am: AudioManager) {
        runCatching {
            if (modoDeAntes == null) modoDeAntes = am.mode
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?.let { am.setCommunicationDevice(it) }
            } else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
        }.onFailure { Log.w(TAG, "sin auricular: $it") }
    }

    private fun devolverElModo(am: AudioManager) {
        val antes = modoDeAntes ?: return
        modoDeAntes = null
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            // Solo si sigue en el modo que se puso aquí: si entró una llamada de verdad, es suyo.
            if (am.mode == AudioManager.MODE_IN_COMMUNICATION) am.mode = antes
        }
    }

    private fun escucharLaCercania(a: Context) {
        if (sensor != null) return
        runCatching {
            val sm = a.getSystemService(SensorManager::class.java)
            val s = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val ahora = DecisionDeSalida.estaCerca(e.values.firstOrNull() ?: return, s.maximumRange)
                    if (ahora == cerca) return
                    val pausar = DecisionDeSalida.pausarAlAlejar(_preferida.value, cerca, ahora, auriculares)
                    cerca = ahora
                    recalcular(pausar)
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, principal)
            sensor = l
        }.onFailure { Log.w(TAG, "sin sensor de cercanía: $it") }
    }

    private fun dejarDeEscuchar() {
        val l = sensor ?: return
        sensor = null
        runCatching { app?.getSystemService(SensorManager::class.java)?.unregisterListener(l) }
        if (cerca) { cerca = false; _ruta.value = DecisionDeSalida.decidir(_preferida.value, auriculares, false) }
    }

    private fun apagarAlAcercar(a: Context) {
        if (pantalla != null) return
        runCatching {
            val energia = a.getSystemService(PowerManager::class.java)
            if (energia.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                pantalla = energia.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "pixpin:salida")
                    .also { it.acquire(4 * 60 * 60 * 1000L) }
            }
        }
    }

    private fun soltarLaPantalla() {
        val w = pantalla ?: return
        pantalla = null
        // Con la cara aún delante, se espera a que se aparte para encender: si no, la oreja toca la pantalla.
        runCatching { if (w.isHeld) w.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
    }

    /** Si hay audífonos puestos: de cable, Bluetooth (música o llamada), USB o BLE. */
    private fun hayAuriculares(am: AudioManager): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
}
