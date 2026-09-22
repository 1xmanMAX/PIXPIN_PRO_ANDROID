package com.forge.pixpin.ui

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.forge.pixpin.motor.VozAlta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * **La voz que lee el documento**: el `TextToSpeech` del sistema con el motor de Google, y solo
 * con sus voces **sin conexión**. Las cuentas —qué voz, en qué trozos, qué idioma— están en
 * [VozAlta]; esto es lo que habla con Android.
 *
 * El documento se le da **por párrafos** y no entero: un libro son miles, y el motor los guardaría
 * todos en su cola. Se mantienen [POR_DELANTE] párrafos encolados por delante del que suena; al
 * empezar cada uno se encola el siguiente. Cada lectura lleva su número ([lectura]) en el nombre de
 * sus trozos: al parar, el motor aún avisa de los que tenía, y esos se ignoran.
 *
 * Todo lo que cambia el estado pasa en el hilo principal; los avisos del motor llegan en el suyo
 * y se mandan ahí.
 *
 * **Uno para toda la app** ([delApp], 22-sep-2026, pedido por el usuario: que siga sonando al
 * salir del visor, como un audio). El visor lo toma prestado mientras está abierto; lo que lo
 * mantiene vivo fuera es [LeyendoEnVozService], con su notificación de reproductor. Pide el
 * **foco de audio** como cualquier reproductor: una llamada o la música de otra app lo pausan.
 */
class LectorEnVoz private constructor(context: Context) {

    data class Estado(
        /** El motor está arrancado y tiene voz. */
        val listo: Boolean = false,
        val leyendo: Boolean = false,
        /** El párrafo que suena (o donde se paró); −1 si aún no hay documento. */
        val parrafo: Int = -1,
        val cuantos: Int = 0,
        val velocidad: Float = 1f,
        /** El nombre de la voz, para enseñarlo: «es-ES · Google». */
        val voz: String = "",
        /** Suena por el auricular de las llamadas y no por el altavoz ([porElAuricular]). */
        val auricular: Boolean = false,
        /** De qué documento es lo que se lee ([documento]): el visor lo compara con el suyo. */
        val clave: String = "",
        val titulo: String = ""
    )

    /** Cómo acabó el arranque. */
    sealed class Arranque {
        object Bien : Arranque()
        /** No hay motor de voz en el teléfono, o no arrancó. */
        object SinMotor : Arranque()
        /** El motor no tiene voz sin conexión del idioma; con [bajable], la tiene pero sin bajar. */
        class SinVoz(val idioma: String, val bajable: Boolean, val motor: String?) : Arranque()
    }

    private val app = context.applicationContext
    private val principal = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var motor: String? = null

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado

    private var parrafos: List<String> = emptyList()
    /** Dónde empieza cada párrafo en el documento, de 0 a 1: para el marcador verde. */
    private var fracciones: List<Float> = emptyList()
    private var idiomaActual = ""
    private var enLineaActual = false
    private val prefs by lazy { app.getSharedPreferences("lectura", Context.MODE_PRIVATE) }
    /** Los trozos de cada párrafo, hechos al encolarlo. */
    private val trozosDe = HashMap<Int, List<String>>()
    private var lectura = 0
    private var actual = 0
    private var trozoActual = 0
    private var encoladoHasta = -1

    /** Avisa cada vez que suena otro párrafo, para resaltarlo en la página. Lo pone el visor que lo tiene abierto. */
    var alCambiarDeParrafo: ((Int) -> Unit)? = null

    /** Cómo volver a abrir el visor del documento que suena: al tocar la notificación. */
    var paraVolver: Intent? = null

    // ---- El foco de audio, como cualquier reproductor. ----
    private val audio = app.getSystemService(android.media.AudioManager::class.java)
    /** Se pausó porque otro pidió el audio por un rato (una llamada, un aviso): al devolverlo, sigue. */
    private var pausadoPorOtro = false
    private val foco: android.media.AudioFocusRequest by lazy {
        android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ATRIBUTOS)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ cambio ->
                when (cambio) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS -> { pausadoPorOtro = false; pausar() }
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        if (_estado.value.leyendo) { pausar(); pausadoPorOtro = true }
                    android.media.AudioManager.AUDIOFOCUS_GAIN ->
                        if (pausadoPorOtro) { pausadoPorOtro = false; seguir() }
                }
            }, principal)
            .build()
    }

    // ---- Por el auricular de las llamadas (22-sep-2026, pedido por el usuario: oírlo en sitios
    // ruidosos con el teléfono pegado a la oreja). Lo mismo que la llamada secreta
    // ([com.forge.pixpin.pin.LlamadaSecretaActivity]): modo de comunicación y el auricular como
    // salida; y la pantalla se apaga al acercarla a la cara. Solo mientras suena: al pausar, el
    // teléfono vuelve a como estaba, que no se quede «en llamada». ----
    private var modoDeAntes: Int? = null
    private var cerca: android.os.PowerManager.WakeLock? = null

    /** Cambia la salida; si estaba sonando, vuelve a empezar el trozo por la nueva. */
    fun porElAuricular(si: Boolean) {
        if (_estado.value.auricular == si) return
        _estado.value = _estado.value.copy(auricular = si)
        tts?.let { t -> runCatching { t.setAudioAttributes(atributos()) } }
        if (_estado.value.leyendo) leer(actual, trozoActual)
    }

    private fun atributos(): AudioAttributes =
        if (!_estado.value.auricular) ATRIBUTOS
        else AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun ponerLaSalida() {
        if (!_estado.value.auricular) { devolverLaSalida(); return }
        runCatching {
            if (modoDeAntes == null) modoDeAntes = audio.mode
            audio.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                audio.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?.let { audio.setCommunicationDevice(it) }
            } else @Suppress("DEPRECATION") { audio.isSpeakerphoneOn = false }
        }
        runCatching {
            val energia = app.getSystemService(android.os.PowerManager::class.java)
            if (cerca == null && energia.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                cerca = energia.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "pixpin:leyendo")
                    .also { it.acquire(4 * 60 * 60 * 1000L) }
            }
        }
    }

    private fun devolverLaSalida() {
        runCatching { if (cerca?.isHeld == true) cerca?.release() }
        cerca = null
        val antes = modoDeAntes ?: return
        modoDeAntes = null
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            // Solo si sigue en el modo que se puso: si entró una llamada de verdad, es suyo.
            if (audio.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) audio.mode = antes
        }
    }

    private fun pedirElAudio(): Boolean =
        runCatching { audio.requestAudioFocus(foco) != android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED }.getOrDefault(true)

    private fun soltarElAudio() {
        pausadoPorOtro = false
        devolverLaSalida()
        runCatching { audio.abandonAudioFocusRequest(foco) }
    }

    /** Si el motor de Google está en el teléfono. */
    fun hayGoogle(): Boolean = runCatching {
        app.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .any { it.serviceInfo.packageName == VozAlta.MOTOR_DE_GOOGLE }
    }.getOrDefault(false)

    /**
     * Arranca el motor —el de Google si está; si no, el que haya— y le pone la mejor voz sin
     * conexión para [idioma]. [alAcabar] se llama en el hilo principal.
     */
    fun arrancar(idioma: String, velocidad: Float, enLinea: Boolean, alAcabar: (Arranque) -> Unit) {
        soltar()
        idiomaActual = idioma
        enLineaActual = enLinea
        motor = if (hayGoogle()) VozAlta.MOTOR_DE_GOOGLE else null
        var creado: TextToSpeech? = null
        val alIniciar = TextToSpeech.OnInitListener { status ->
            principal.post {
                val t = creado
                if (t == null || t !== tts) return@post
                if (status != TextToSpeech.SUCCESS) { soltar(); alAcabar(Arranque.SinMotor); return@post }
                alAcabar(ponerVoz(t, idioma, velocidad, enLinea))
            }
        }
        creado = runCatching {
            if (motor != null) TextToSpeech(app, alIniciar, motor) else TextToSpeech(app, alIniciar)
        }.getOrNull()
        if (creado == null) { alAcabar(Arranque.SinMotor); return }
        tts = creado
    }

    /** Si hay red de verdad ahora mismo: sin ella, una voz en línea se quedaría muda. */
    private fun hayRed(): Boolean = runCatching {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    /**
     * **Voces en línea sí o no**, con el documento sonando: se elige otra voz y se vuelve a
     * empezar el trozo que sonaba. Si no está arrancado, vale para la próxima vez.
     */
    fun ponerEnLinea(enLinea: Boolean) {
        enLineaActual = enLinea
        val t = tts ?: return
        if (!_estado.value.listo) return
        val sonaba = _estado.value.leyendo
        if (sonaba) pausar()
        if (ponerVoz(t, idiomaActual, _estado.value.velocidad, enLinea) is Arranque.Bien && sonaba) seguir()
    }

    private fun ponerVoz(t: TextToSpeech, idioma: String, velocidad: Float, enLinea: Boolean): Arranque {
        val todas: List<Voice> = runCatching { t.voices?.toList() }.getOrNull().orEmpty()
        val voces = todas.map { v ->
            VozAlta.Voz(
                nombre = v.name,
                idioma = v.locale.toLanguageTag(),
                local = !v.isNetworkConnectionRequired,
                instalada = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty(),
                calidad = v.quality
            )
        }
        val elegida = VozAlta.mejorVoz(voces, idioma, enLinea && hayRed())
        if (elegida == null) {
            val bajable = VozAlta.hayQueBajarla(voces, idioma)
            soltar()
            return Arranque.SinVoz(idioma, bajable, motor)
        }
        todas.firstOrNull { it.name == elegida.nombre }?.let { t.voice = it }
        runCatching { t.setAudioAttributes(atributos()) }
        t.setSpeechRate(velocidad)
        t.setOnUtteranceProgressListener(Oyente())
        val etiqueta = Locale.forLanguageTag(elegida.idioma).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } } +
            (if (motor == VozAlta.MOTOR_DE_GOOGLE) " · Google" else "") + (if (elegida.local) " · sin conexión" else " · en línea")
        _estado.value = _estado.value.copy(listo = true, velocidad = velocidad, voz = etiqueta)
        return Arranque.Bien
    }

    /** Qué hay que leer, de qué documento ([clave], [titulo]). No empieza a sonar: para eso, [leer]. */
    fun documento(textos: List<String>, clave: String = "", titulo: String = "", donde: List<Float> = emptyList()) {
        parar()
        parrafos = textos
        fracciones = donde
        trozosDe.clear()
        _estado.value = _estado.value.copy(cuantos = textos.size, parrafo = -1, clave = clave, titulo = titulo)
    }

    /** Empieza a leer en el párrafo [desde] (y en su trozo [trozo]). */
    fun leer(desde: Int, trozo: Int = 0) {
        val t = tts ?: return
        if (parrafos.isEmpty()) return
        if (!pedirElAudio()) return
        ponerLaSalida()
        lectura++
        t.stop()
        actual = desde.coerceIn(0, parrafos.lastIndex)
        trozoActual = trozo
        encoladoHasta = actual - 1
        encolarHasta(actual + POR_DELANTE, desdeTrozo = trozo)
        _estado.value = _estado.value.copy(leyendo = true, parrafo = actual)
        apuntarElVerde(actual)
        alCambiarDeParrafo?.invoke(actual)
    }

    /**
     * **El marcador verde se mueve al párrafo que suena** (ver [com.forge.pixpin.motor.Lectura.EMOJI_DE_VOZ]).
     * Se apunta en cada párrafo, no solo al parar: si el sistema mata la app, sigue donde iba.
     */
    private fun apuntarElVerde(p: Int) {
        val clave = _estado.value.clave.takeIf { it.isNotEmpty() } ?: return
        val f = fracciones.getOrNull(p) ?: if (parrafos.isEmpty()) 0f else p.toFloat() / parrafos.size
        prefs.edit().putString(com.forge.pixpin.motor.Lectura.claveDeVoz(clave), com.forge.pixpin.motor.Lectura.vozATexto(p, f)).apply()
    }

    /** Para, y se queda donde iba: [seguir] vuelve a empezar por ese mismo trozo. */
    fun pausar() {
        lectura++
        tts?.stop()
        devolverLaSalida()
        _estado.value = _estado.value.copy(leyendo = false)
    }

    /** Lo que hace el botón de play/pausa, desde el visor o desde la notificación. */
    fun alternar() { if (_estado.value.leyendo) pausar() else seguir() }

    fun seguir() {
        if (_estado.value.parrafo < 0) leer(0) else leer(actual, trozoActual)
    }

    /** Un párrafo adelante (+1) o atrás (−1), desde su principio. */
    fun saltar(cuantos: Int) {
        if (parrafos.isEmpty()) return
        val a = (actual + cuantos).coerceIn(0, parrafos.lastIndex)
        if (_estado.value.leyendo) leer(a)
        else { actual = a; trozoActual = 0; _estado.value = _estado.value.copy(parrafo = a); apuntarElVerde(a); alCambiarDeParrafo?.invoke(a) }
    }

    fun ponerVelocidad(v: Float) {
        tts?.setSpeechRate(v)
        _estado.value = _estado.value.copy(velocidad = v)
        // La velocidad nueva vale para lo que se encole; lo ya encolado se vuelve a pedir.
        if (_estado.value.leyendo) leer(actual, trozoActual)
    }

    /** Se calla sin olvidar el documento. */
    fun parar() {
        lectura++
        tts?.stop()
        soltarElAudio()
        _estado.value = _estado.value.copy(leyendo = false)
    }

    /** Suelta el motor y olvida el documento: al cerrar la barra o la notificación. */
    fun soltar() {
        lectura++
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        soltarElAudio()
        paraVolver = null
        _estado.value = _estado.value.copy(listo = false, leyendo = false, clave = "", titulo = "")
    }

    private fun trozosDelParrafo(i: Int): List<String> =
        trozosDe.getOrPut(i) { VozAlta.trozos(parrafos.getOrElse(i) { "" }).ifEmpty { listOf(" ") } }

    private fun encolarHasta(hasta: Int, desdeTrozo: Int = 0) {
        val t = tts ?: return
        val fin = minOf(hasta, parrafos.lastIndex)
        while (encoladoHasta < fin) {
            val p = encoladoHasta + 1
            val trozos = trozosDelParrafo(p)
            val primero = if (p == actual) desdeTrozo.coerceIn(0, trozos.lastIndex) else 0
            for (k in primero..trozos.lastIndex) {
                t.speak(trozos[k], TextToSpeech.QUEUE_ADD, null, VozAlta.idDe(lectura, p, k))
            }
            encoladoHasta = p
        }
    }

    private inner class Oyente : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (l, p, k) = VozAlta.deId(utteranceId) ?: return
            principal.post {
                if (l != lectura) return@post
                val nuevo = p != actual || !_estado.value.leyendo
                actual = p
                trozoActual = k
                encolarHasta(p + POR_DELANTE)
                if (nuevo || _estado.value.parrafo != p) {
                    apuntarElVerde(p)
                    _estado.value = _estado.value.copy(parrafo = p, leyendo = true)
                    alCambiarDeParrafo?.invoke(p)
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            val (l, p, k) = VozAlta.deId(utteranceId) ?: return
            principal.post {
                if (l != lectura) return@post
                // El último trozo del último párrafo: se acabó el documento.
                if (p >= parrafos.lastIndex && k >= trozosDelParrafo(p).lastIndex) {
                    _estado.value = _estado.value.copy(leyendo = false)
                    actual = 0
                    trozoActual = 0
                    soltarElAudio()
                    // Acabado entero: el verde se quita, la próxima vez se empieza por donde se mire.
                    _estado.value.clave.takeIf { it.isNotEmpty() }?.let { prefs.edit().remove(com.forge.pixpin.motor.Lectura.claveDeVoz(it)).apply() }
                    _estado.value = _estado.value.copy(parrafo = -1)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String?, errorCode: Int) {
            val (l, _, _) = VozAlta.deId(utteranceId) ?: return
            principal.post { if (l == lectura) _estado.value = _estado.value.copy(leyendo = false) }
        }
    }

    companion object {
        private const val POR_DELANTE = 3

        private val ATRIBUTOS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        @Volatile private var unico: LectorEnVoz? = null

        /** **El lector de la app**: el mismo para todos los visores y para la notificación. */
        fun delApp(context: Context): LectorEnVoz =
            unico ?: synchronized(this) { unico ?: LectorEnVoz(context).also { unico = it } }

        /**
         * Lo que se abre para bajar la voz que falta: la pantalla del propio motor si se sabe cuál
         * es, y si no, los ajustes de voz de Android.
         */
        fun paraBajarVoces(motor: String?): List<Intent> = listOfNotNull(
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply { motor?.let { setPackage(it) } },
            Intent("com.android.settings.TTS_SETTINGS")
        ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
