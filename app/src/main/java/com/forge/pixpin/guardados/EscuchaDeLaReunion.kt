package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.forge.pixpin.audio.Grabador
import com.forge.pixpin.jev.Cliente
import com.forge.pixpin.jev.EnLaReunion
import com.forge.pixpin.jev.Temas
import com.forge.pixpin.jev.Vivo
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/**
 * **La escucha en vivo de una reunión** (29-oct-2026; `ventana_chat::grabar_clase::escucha` del PC y
 * `docs/jev.md`, «Android: los temas de la reunión en el móvil», pasos 2 y 3). Con temas en la agenda o
 * alguna idea de la reunión encendida, mientras graba [GrabarClaseService] o [ReunionPresencialService]:
 *
 * 1. **El oído**: la toma del PCM de la misma nota ([Grabador.oir]); **no se abre otro micrófono** salvo
 *    el reconocedor del sistema en Android 12 (que no sabe leer de otro sitio). El hilo del micrófono
 *    nunca espera: copia el bloque en una cola que **tira lo que no cabe** y sigue.
 * 2. **El texto, frase a frase, en el teléfono**:
 *    - **el reconocedor del sistema sin red** (`createOnDeviceSpeechRecognizer`, `EXTRA_PREFER_OFFLINE`,
 *      `EXTRA_PARTIAL_RESULTS`). Desde Android 13 se le da **el sonido de la propia grabación** por una
 *      tubería (`EXTRA_AUDIO_SOURCE`) en una sesión por tramos (`EXTRA_SEGMENTED_SESSION`): cada tramo
 *      es una frase. En Android 12 abre su micrófono y se vuelve a lanzar en cada `onResults`, como
 *      [MotorGoogle]. Los parciales solo se enseñan.
 *    - si no hay, o falla tres veces seguidas, o Android silencia la grabación por él: **Whisper**
 *      ligero sobre trozos de 5 s o más del PCM de la nota, cortados en un silencio
 *      ([OidoEnVivo.PARA_WHISPER]), en una cola de tres que tira la más vieja.
 *    - **La grabación va primero** ([OidoEnVivo.Freno]): con la batería por debajo del 20 % (sin cargar),
 *      el teléfono caliente o el texto por detrás, se saltan frases. La grabación nunca se toca.
 * 3. **Jev, los temas**: cada frase al [Temas.Rollo] (40 s); en cuanto hay tres palabras nuevas y algún
 *    tema pendiente, **una** petición (como mucho una en vuelo, 1,5 s entre una y otra, 5 s tras un
 *    fallo: [Temas.Ritmo]); la tabla [Temas.Seguimiento] decide.
 * 4. **Jev, lo demás de la reunión**: cada frase, partida en oraciones ([Vivo.partir]), a la mesa
 *    ([EnLaReunion.Mesa]) en **una sola petición por oración**; lo que llega mientras tanto va junto.
 *
 * Lo que sale ([Vuelta]) lo recoge [ReunionConJev] en el hilo principal. **Sin clave, sin red o si Jev
 * falla, no se marca ni se propone nada solo** (un fallo nunca es un «no»): se dice y se marca a mano.
 * Al terminar se para en el acto y la transcripción entera de después es la de siempre.
 */
class EscuchaDeLaReunion(
    context: Context,
    private val reunion: String,
    private val agenda: List<String>,
    pendientes: List<Temas.Pendiente>,
    /** El cliente de los temas (null: los temas no se marcan solos). */
    private val clienteTemas: Cliente?,
    /** Lo demás de la reunión (null: apagado o sin clave) y su cliente. */
    private val mesa: EnLaReunion.Mesa?,
    private val clienteMesa: Cliente?,
    private val idioma: String,
    private val alVolver: (Vuelta) -> Unit
) {
    private val app = context.applicationContext

    /** Lo que la escucha le cuenta a la barra. */
    sealed class Vuelta {
        /** Jev marcó un tema como tratado, y lo que tardó desde que se dejó de hablar. */
        data class Tratado(val tratado: Temas.Tratado, val tardeMs: Long) : Vuelta()
        /** Cómo va la escucha. */
        data class Aviso(val aviso: Como) : Vuelta()
        /** Lo que salió de una oración para lo demás de la reunión. */
        data class Reunion(val sucesos: List<EnLaReunion.Suceso>) : Vuelta()
        /** Lo que se está oyendo ahora (un parcial del reconocedor): solo se enseña. */
        data class Parcial(val texto: String) : Vuelta()
    }

    /** Cómo va la escucha (la cabecera de la lista de los temas). */
    enum class Como { ESCUCHANDO, SIN_MODELO, JEV_FALLA, JUSTO, REUNION_FALLA, SIN_PCM }

    private enum class Motor { RECONOCEDOR, WHISPER }

    private class Bloque(val muestras: ShortArray, val posicion: Long)

    // --- Lo compartido entre hilos --------------------------------------------------------------

    private val cerrojo = java.util.concurrent.locks.ReentrantLock()
    private val hay = cerrojo.newCondition()
    @Volatile private var parar = false
    @Volatile private var sinTexto = false
    private val t0 = System.nanoTime()
    private fun reloj(): Long = (System.nanoTime() - t0) / 1_000_000

    /** Los bloques del PCM que esperan al oído (cada uno ~100 ms): unos 25 s. */
    private val bloques = ArrayBlockingQueue<Bloque>(256)
    @Volatile var perdidos = 0L
        private set
    private var muestreo = 0

    // Los temas (con [candado]).
    private val rollo = Temas.Rollo()
    private val ritmo = Temas.Ritmo()
    private var pendientesDeJev: List<Temas.Pendiente> = pendientes

    // Lo demás de la reunión (con [candado]).
    private val frases = ArrayList<Pair<Vivo.Frase, Long>>()

    @Volatile private var motor = Motor.WHISPER
    private val frasesWhisper = OidoEnVivo.Cola<Pair<OidoEnVivo.Frase, Long>>(OidoEnVivo.COLA)
    private var grabador: Grabador? = null
    private val principal = Handler(Looper.getMainLooper())

    // --- Empezar y parar ------------------------------------------------------------------------

    /**
     * **Empieza a escuchar** la nota de [g]. false si no hay de dónde oír (la grabadora del sistema no da
     * el PCM) o con qué pasar a texto (ni reconocedor sin red ni el modelo de Whisper): entonces se dice
     * en la barra y los temas se marcan a mano.
     */
    fun empezar(g: Grabador): Boolean {
        muestreo = g.muestreo()
        if (muestreo <= 0 || !g.oir { m, n, pos -> oir(m, n, pos) }) {
            alVolver(Vuelta.Aviso(Como.SIN_PCM))
            return false
        }
        grabador = g
        hilo("pixpin-reunion-oido") { oido() }
        hilo("pixpin-reunion-texto") { elegirElMotor() }
        if (clienteTemas != null && agenda.isNotEmpty()) hilo("pixpin-reunion-temas") { preguntarPorLosTemas() }
        if (mesa != null && clienteMesa != null) hilo("pixpin-reunion-jev") { loDemasDeLaReunion() }
        vigilarElSilencio()
        return true
    }

    /** **Para** en el acto: lo que esté en vuelo se tira y la grabación sigue como si nada. */
    fun parar() {
        parar = true
        grabador?.oir(null)
        cerrojo.withLock { hay.signalAll() }
        principal.post { pararElReconocedor() }
        quitarElVigiaDelSilencio()
    }

    /** Los temas por los que se pregunta desde ahora (la persona marcó uno: Jev ya no lo mira). */
    fun pendientes(v: List<Temas.Pendiente>) {
        cerrojo.withLock {
            pendientesDeJev = v
            hay.signalAll()
        }
    }

    private fun hilo(nombre: String, f: () -> Unit) {
        Thread({
            // Por debajo de lo normal: la grabación (prioridad de audio) va primero.
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            runCatching(f).onFailure { android.util.Log.w("PixPinReunion", "la escucha en vivo ($nombre) se paró", it) }
        }, nombre).apply { isDaemon = true }.start()
    }

    // --- El oído --------------------------------------------------------------------------------

    /** En el hilo del micrófono: copia y vuelve. */
    private fun oir(m: ShortArray, n: Int, pos: Long) {
        if (parar) return
        if (!bloques.offer(Bloque(m.copyOf(n), pos))) perdidos++
    }

    /** Si el teléfono va justo: batería por debajo del 20 % sin cargar, o caliente. Se mira cada 5 s. */
    @Volatile private var justoMirado = 0L
    @Volatile private var justoAhora = false

    private fun justo(): Boolean {
        val ahora = reloj()
        if (ahora - justoMirado < 5_000 && justoMirado != 0L) return justoAhora
        justoMirado = ahora
        justoAhora = runCatching {
            val b = app.getSystemService(BatteryManager::class.java)
            val bateria = b?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            val cargando = b?.isCharging == true
            val caliente = Build.VERSION.SDK_INT >= 29 &&
                (app.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE
            (bateria in 0 until OidoEnVivo.BATERIA_MINIMA && !cargando) || caliente
        }.getOrDefault(false)
        return justoAhora
    }

    /**
     * **El oído**: del PCM a lo que pide el motor. Con el reconocedor, el sonido a 16 kHz a su tubería;
     * con Whisper, frases cortadas en un silencio a la cola de tres. Mientras el teléfono hace de cámara
     * del PC no se oye nada: escucha el PC.
     */
    private fun oido() {
        val frasero = OidoEnVivo.Frasero(OidoEnVivo.PARA_WHISPER)
        while (!parar) {
            val b = bloques.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (com.forge.pixpin.sincro.CamaraDelPc.haciendoDeCamara()) continue
            if (motor == Motor.RECONOCEDOR) {
                alReconocedor(b)
                continue
            }
            for (f in frasero.meter(b.posicion, muestreo, b.muestras)) {
                // Sale en cuanto se corta, con su silencio del final ya oído: se dejó de hablar ese silencio antes.
                val fin = reloj() - f.calladoAlFinalMs.coerceAtLeast(0)
                frasesWhisper.meter(f to fin)?.let { android.util.Log.d("PixPinReunion", "el texto va tarde: se tira la frase ${it.first.n}") }
                cerrojo.withLock { hay.signalAll() }
            }
        }
    }

    // --- El texto -------------------------------------------------------------------------------

    /** Una frase ya en texto: a los temas y a lo demás de la reunión. */
    private fun entregar(ms: Long, finMs: Long, texto0: String, relojFin: Long) {
        val texto = texto0.trim()
        if (texto.isEmpty() || parar) return
        cerrojo.withLock {
            if (mesa != null && clienteMesa != null) frases += Vivo.Frase(ms, EnLaReunion.QUIEN_NO_SE, texto) to finMs
            if (clienteTemas != null) rollo.meter(Temas.Dicho(ms, finMs, texto, relojFin))
            hay.signalAll()
        }
    }

    /** Con qué se pasa a texto: el reconocedor sin red si lo hay, y si no, Whisper; si no, se dice. */
    private fun elegirElMotor() {
        if (Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }.getOrDefault(false)) {
            val etiqueta = if (Build.VERSION.SDK_INT >= 33) {
                when (val i = MotorGoogle.idiomaInstalado(app, idioma)) {
                    is MotorGoogle.Idioma.Vale -> i.etiqueta
                    else -> null
                }
            } else idioma
            if (etiqueta != null && !parar) {
                etiquetaDelReconocedor = etiqueta
                motor = Motor.RECONOCEDOR
                principal.post { lanzarElReconocedor() }
                alVolver(Vuelta.Aviso(Como.ESCUCHANDO))
                return
            }
        }
        aWhisper()
    }

    /** **A Whisper**: carga el ligero y pasa a texto lo que deja el oído, frase a frase. */
    private fun aWhisper() {
        motor = Motor.WHISPER
        val rec = MotorWhisper.ligero(app, idioma)
        if (rec == null) {
            alVolver(Vuelta.Aviso(Como.SIN_MODELO))
            return
        }
        alVolver(Vuelta.Aviso(Como.ESCUCHANDO))
        val freno = OidoEnVivo.Freno()
        try {
            while (!parar) {
                val (f, relojFin) = siguienteFrase() ?: break
                when (freno.decidir(f.muestras, justo())) {
                    OidoEnVivo.Paso.PASAR -> {}
                    OidoEnVivo.Paso.SALTAR_APARATO -> { alVolver(Vuelta.Aviso(Como.JUSTO)); continue }
                    else -> continue
                }
                val empezo = reloj()
                val texto = runCatching { MotorWhisper.textoDe(rec, f.muestras, idioma) }.getOrDefault("")
                freno.tardo(f.duracionMs(), reloj() - empezo)
                entregar(f.ms, f.finVozMs, texto, relojFin)
            }
        } finally {
            runCatching { rec.release() }
            sinTexto = true
            cerrojo.withLock { hay.signalAll() }
        }
    }

    private fun siguienteFrase(): Pair<OidoEnVivo.Frase, Long>? {
        while (!parar) {
            frasesWhisper.sacar()?.let { return it }
            cerrojo.withLock { hay.await(500, TimeUnit.MILLISECONDS) }
        }
        return null
    }

    // --- El reconocedor del sistema (en el hilo principal) --------------------------------------

    @Volatile private var etiquetaDelReconocedor = ""
    private var reconocedor: SpeechRecognizer? = null
    /** La tubería por la que se le da el sonido (Android 13+) y su cola, que tira lo que no cabe. */
    @Volatile private var tubo: OutputStream? = null
    private val paraElTubo = ArrayBlockingQueue<ShortArray>(64)
    @Volatile private var msEscrito = 0L
    private var desdeMs = 0L
    private var fallosSeguidos = 0
    @Volatile private var conTubo = false
    @Volatile private var enPausaPorJusto = false

    private fun msAhora(): Long = if (conTubo) msEscrito else grabador?.llevadoMs() ?: 0L

    /** En el hilo del oído: el bloque, a 16 kHz, a la cola de la tubería (o se tira si el teléfono va justo). */
    private fun alReconocedor(b: Bloque) {
        if (!conTubo) return
        if (justo()) {
            if (!enPausaPorJusto) { enPausaPorJusto = true; alVolver(Vuelta.Aviso(Como.JUSTO)); principal.post { pararElReconocedor() } }
            return
        }
        if (enPausaPorJusto) { enPausaPorJusto = false; alVolver(Vuelta.Aviso(Como.ESCUCHANDO)); principal.post { lanzarElReconocedor() } }
        msEscrito = (b.posicion + b.muestras.size) * 1000 / muestreo
        if (!paraElTubo.offer(OidoEnVivo.a16k(b.muestras, hz = muestreo))) perdidos++
    }

    private fun lanzarElReconocedor() {
        if (parar || reconocedor != null || motor != Motor.RECONOCEDOR) return
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(app) }.getOrNull()
        if (r == null) { alWhisper(); return }
        reconocedor = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, etiquetaDelReconocedor)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        conTubo = false
        if (Build.VERSION.SDK_INT >= 33) {
            val par = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
            if (par != null) {
                val (lectura, escritura) = par
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, lectura)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, OidoEnVivo.HERCIOS)
                // Que no pare en cada silencio: un resultado por tramo.
                intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                paraElTubo.clear()
                val salida = ParcelFileDescriptor.AutoCloseOutputStream(escritura)
                tubo = salida
                conTubo = true
                hilo("pixpin-reunion-tubo") { escribirElTubo(salida, lectura) }
            }
        }
        desdeMs = msAhora()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { desdeMs = msAhora() }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                mejorDe(partialResults)?.let { alVolver(Vuelta.Parcial(it)) }
            }
            override fun onSegmentResults(segmentResults: Bundle) { unResultado(segmentResults) }
            override fun onResults(results: Bundle?) {
                unResultado(results)
                // Sin sesión por tramos (Android 12), cada resultado cierra: se vuelve a lanzar.
                if (!conTubo) otraVez(0)
            }
            override fun onEndOfSegmentedSession() { otraVez(300) }
            override fun onError(error: Int) {
                val nada = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (!nada) fallosSeguidos++
                if (fallosSeguidos >= 3) alWhisper() else otraVez(if (nada) 0 else 1_000)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { r.startListening(intent) }.onFailure { fallosSeguidos++; if (fallosSeguidos >= 3) alWhisper() else otraVez(1_000) }
    }

    private fun mejorDe(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun unResultado(b: Bundle?) {
        val texto = mejorDe(b) ?: return
        fallosSeguidos = 0
        val fin = msAhora()
        val ms = desdeMs.coerceAtMost(fin)
        desdeMs = fin
        alVolver(Vuelta.Parcial(""))
        val relojFin = reloj()
        Thread { entregar(ms, fin, texto, relojFin) }.start()
    }

    /** Cierra esta sesión y lanza otra dentro de [ms]. */
    private fun otraVez(ms: Long) {
        pararElReconocedor()
        if (!parar && !enPausaPorJusto) principal.postDelayed({ lanzarElReconocedor() }, ms)
    }

    private fun pararElReconocedor() {
        reconocedor?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        reconocedor = null
        tubo?.let { runCatching { it.close() } }
        tubo = null
    }

    /** El reconocedor no va: a Whisper (en su hilo). */
    private fun alWhisper() {
        if (motor != Motor.RECONOCEDOR) return
        motor = Motor.WHISPER
        conTubo = false
        pararElReconocedor()
        android.util.Log.i("PixPinReunion", "el reconocedor del sistema no va: la escucha pasa a Whisper")
        hilo("pixpin-reunion-texto") { aWhisper() }
    }

    /** La tubería, tan deprisa como la lea el reconocedor (la escritura espera aquí, nunca en el micrófono). */
    private fun escribirElTubo(salida: OutputStream, lectura: ParcelFileDescriptor) {
        try {
            val bytes = ByteArray(OidoEnVivo.HERCIOS / 5 * 2)
            while (!parar && tubo === salida) {
                val m = paraElTubo.poll(200, TimeUnit.MILLISECONDS) ?: continue
                var i = 0
                while (i < m.size) {
                    val n = minOf(m.size - i, bytes.size / 2)
                    for (k in 0 until n) {
                        val v = m[i + k].toInt()
                        bytes[2 * k] = (v and 0xFF).toByte()
                        bytes[2 * k + 1] = (v shr 8).toByte()
                    }
                    salida.write(bytes, 0, n * 2)
                    i += n
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { salida.close() }
            runCatching { lectura.close() }
        }
    }

    // --- Si Android silencia la grabación por el reconocedor: la grabación gana --------------------

    private var vigia: AudioManager.AudioRecordingCallback? = null

    private fun vigilarElSilencio() {
        val sesion = grabador?.sesionDeAudio() ?: return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val callada = configs.orEmpty().any { it.clientAudioSessionId == sesion && it.isClientSilenced }
                if (callada && motor == Motor.RECONOCEDOR) {
                    android.util.Log.w("PixPinReunion", "Android calló la grabación por el reconocedor: fuera el reconocedor")
                    alWhisper()
                }
            }
        }
        runCatching { am.registerAudioRecordingCallback(cb, principal) }.onSuccess { vigia = cb }
    }

    private fun quitarElVigiaDelSilencio() {
        val cb = vigia ?: return
        vigia = null
        runCatching { app.getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(cb) }
    }

    // --- Jev: los temas -------------------------------------------------------------------------

    /** **Los temas**: en cuanto hay texto nuevo y le toca ([Temas.Ritmo]), una petición. */
    private fun preguntarPorLosTemas() {
        val cliente = clienteTemas ?: return
        val seguimiento = Temas.Seguimiento()
        var falloDicho = false
        while (!parar) {
            var ventana: Temas.Ventana? = null
            var pendientes: List<Temas.Pendiente> = emptyList()
            cerrojo.withLock {
                val ahora = reloj()
                val hayAlgo = rollo.hayNuevo() && pendientesDeJev.isNotEmpty()
                when (val c = ritmo.cuando(ahora, hayAlgo)) {
                    Temas.Cuando.Ya -> {
                        ventana = rollo.ventana()
                        pendientes = pendientesDeJev
                        if (ventana != null) ritmo.sale(ahora)
                    }
                    is Temas.Cuando.Esperar -> hay.await(c.ms.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                    Temas.Cuando.Nada -> if (sinTexto) return else hay.await(500, TimeUnit.MILLISECONDS)
                }
            }
            val v = ventana ?: continue
            // Sin el candado: lo que llegue mientras tanto se junta para la siguiente.
            val r = runCatching { Temas.preguntar(cliente, reunion, agenda, v, pendientes) }
            cerrojo.withLock {
                ritmo.vuelve(reloj(), r.isSuccess)
                r.onSuccess { (senales, _) ->
                    rollo.mirado(v)
                    if (falloDicho) { falloDicho = false; alVolver(Vuelta.Aviso(Como.ESCUCHANDO)) }
                    // Solo lo que aún sigue pendiente (alguien pudo marcarlo a mano mientras Jev pensaba).
                    val siguen = senales.filter { (id, _) -> pendientesDeJev.any { it.id == id } }
                    for (t in seguimiento.decidir(v, siguen)) {
                        val tarde = reloj() - v.relojUltima
                        android.util.Log.i("PixPinReunion", "temas: Jev marca un tema tratado (${t.id}, ${t.ms} ms, p ${t.p}; ${tarde} ms desde que se calló)")
                        pendientesDeJev = pendientesDeJev.filter { it.id != t.id }
                        alVolver(Vuelta.Tratado(t, tarde))
                    }
                }.onFailure { e ->
                    android.util.Log.w("PixPinReunion", "temas: Jev no contestó: ${e.message}")
                    if (!falloDicho) { falloDicho = true; alVolver(Vuelta.Aviso(Como.JEV_FALLA)) }
                }
            }
        }
    }

    // --- Jev: lo demás de la reunión ------------------------------------------------------------

    /** Las frases que esperan, juntas en una (la primera pone su minuto; el fin es el de la última). */
    private fun frasesQueEsperan(): Pair<Vivo.Frase, Long>? {
        cerrojo.withLock {
            while (!parar) {
                if (frases.isNotEmpty()) {
                    val todas = frases.toList()
                    frases.clear()
                    val primera = todas.first().first
                    return primera.copy(dice = todas.joinToString(" ") { it.first.dice }) to todas.last().second
                }
                if (sinTexto) return null
                hay.await(500, TimeUnit.MILLISECONDS)
            }
        }
        return null
    }

    /** **Lo demás**: por cada frase (o lo que se juntó mientras la anterior estaba en vuelo), una petición por oración. */
    private fun loDemasDeLaReunion() {
        val m = mesa ?: return
        val cliente = clienteMesa ?: return
        var falloDicho = false
        while (!parar) {
            val (junta, finJunta) = frasesQueEsperan() ?: return
            for ((f, fin) in Vivo.partir(junta, finJunta)) {
                if (parar) return
                val respuesta = if (!m.valeLaPena(f)) null else runCatching { m.preguntar(cliente, f) }.fold(
                    { falloDicho = false; it },
                    { e ->
                        // Un fallo no es un «no»: esa oración no propone nada.
                        android.util.Log.w("PixPinReunion", "reunión: Jev no contestó: ${e.message}")
                        if (!falloDicho) { falloDicho = true; alVolver(Vuelta.Aviso(Como.REUNION_FALLA)) }
                        null
                    }
                )
                if (parar) return
                val sucesos = m.trasFrase(f, respuesta, fin)
                if (sucesos.isNotEmpty()) alVolver(Vuelta.Reunion(sucesos))
            }
        }
    }
}
