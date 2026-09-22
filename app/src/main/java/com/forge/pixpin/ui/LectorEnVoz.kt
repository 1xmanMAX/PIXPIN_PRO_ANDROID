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
 */
class LectorEnVoz(context: Context) {

    data class Estado(
        /** El motor está arrancado y tiene voz. */
        val listo: Boolean = false,
        val leyendo: Boolean = false,
        /** El párrafo que suena (o donde se paró); −1 si aún no hay documento. */
        val parrafo: Int = -1,
        val cuantos: Int = 0,
        val velocidad: Float = 1f,
        /** El nombre de la voz, para enseñarlo: «es-ES · Google». */
        val voz: String = ""
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
    /** Los trozos de cada párrafo, hechos al encolarlo. */
    private val trozosDe = HashMap<Int, List<String>>()
    private var lectura = 0
    private var actual = 0
    private var trozoActual = 0
    private var encoladoHasta = -1

    /** Avisa cada vez que suena otro párrafo, para resaltarlo en la página. */
    var alCambiarDeParrafo: ((Int) -> Unit)? = null

    /** Si el motor de Google está en el teléfono. */
    fun hayGoogle(): Boolean = runCatching {
        app.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .any { it.serviceInfo.packageName == VozAlta.MOTOR_DE_GOOGLE }
    }.getOrDefault(false)

    /**
     * Arranca el motor —el de Google si está; si no, el que haya— y le pone la mejor voz sin
     * conexión para [idioma]. [alAcabar] se llama en el hilo principal.
     */
    fun arrancar(idioma: String, velocidad: Float, alAcabar: (Arranque) -> Unit) {
        soltar()
        motor = if (hayGoogle()) VozAlta.MOTOR_DE_GOOGLE else null
        var creado: TextToSpeech? = null
        val alIniciar = TextToSpeech.OnInitListener { status ->
            principal.post {
                val t = creado
                if (t == null || t !== tts) return@post
                if (status != TextToSpeech.SUCCESS) { soltar(); alAcabar(Arranque.SinMotor); return@post }
                alAcabar(ponerVoz(t, idioma, velocidad))
            }
        }
        creado = runCatching {
            if (motor != null) TextToSpeech(app, alIniciar, motor) else TextToSpeech(app, alIniciar)
        }.getOrNull()
        if (creado == null) { alAcabar(Arranque.SinMotor); return }
        tts = creado
    }

    private fun ponerVoz(t: TextToSpeech, idioma: String, velocidad: Float): Arranque {
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
        val elegida = VozAlta.mejorVoz(voces, idioma)
        if (elegida == null) {
            val bajable = VozAlta.hayQueBajarla(voces, idioma)
            soltar()
            return Arranque.SinVoz(idioma, bajable, motor)
        }
        todas.firstOrNull { it.name == elegida.nombre }?.let { t.voice = it }
        runCatching {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        t.setSpeechRate(velocidad)
        t.setOnUtteranceProgressListener(Oyente())
        val etiqueta = Locale.forLanguageTag(elegida.idioma).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } } +
            (if (motor == VozAlta.MOTOR_DE_GOOGLE) " · Google" else "") + " · sin conexión"
        _estado.value = _estado.value.copy(listo = true, velocidad = velocidad, voz = etiqueta)
        return Arranque.Bien
    }

    /** Qué hay que leer. No empieza a sonar: para eso, [leer]. */
    fun documento(textos: List<String>) {
        parar()
        parrafos = textos
        trozosDe.clear()
        _estado.value = _estado.value.copy(cuantos = textos.size, parrafo = -1)
    }

    /** Empieza a leer en el párrafo [desde] (y en su trozo [trozo]). */
    fun leer(desde: Int, trozo: Int = 0) {
        val t = tts ?: return
        if (parrafos.isEmpty()) return
        lectura++
        t.stop()
        actual = desde.coerceIn(0, parrafos.lastIndex)
        trozoActual = trozo
        encoladoHasta = actual - 1
        encolarHasta(actual + POR_DELANTE, desdeTrozo = trozo)
        _estado.value = _estado.value.copy(leyendo = true, parrafo = actual)
        alCambiarDeParrafo?.invoke(actual)
    }

    /** Para, y se queda donde iba: [seguir] vuelve a empezar por ese mismo trozo. */
    fun pausar() {
        lectura++
        tts?.stop()
        _estado.value = _estado.value.copy(leyendo = false)
    }

    fun seguir() {
        if (_estado.value.parrafo < 0) leer(0) else leer(actual, trozoActual)
    }

    /** Un párrafo adelante (+1) o atrás (−1), desde su principio. */
    fun saltar(cuantos: Int) {
        if (parrafos.isEmpty()) return
        val a = (actual + cuantos).coerceIn(0, parrafos.lastIndex)
        if (_estado.value.leyendo) leer(a)
        else { actual = a; trozoActual = 0; _estado.value = _estado.value.copy(parrafo = a); alCambiarDeParrafo?.invoke(a) }
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
        _estado.value = _estado.value.copy(leyendo = false)
    }

    /** Suelta el motor: al salir del visor. */
    fun soltar() {
        lectura++
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        _estado.value = _estado.value.copy(listo = false, leyendo = false)
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
