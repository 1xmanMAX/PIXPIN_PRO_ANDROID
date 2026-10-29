package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * **El reconocedor del teléfono, frase a frase, del micrófono** (29-oct-2026, para dictar al chat y
 * repasar en voz alta; el «dictado de Windows» del PC, `pixpin_voz::Dictado`). Cada resultado final es
 * una frase ([alFrase]); los parciales solo se enseñan ([alParcial]), no se mandan (cambian). Tras cada
 * frase vuelve a escuchar solo, hasta [parar].
 *
 * Prefiere el reconocedor **en el aparato** (`createOnDeviceSpeechRecognizer`, Android 12 o más, y
 * `EXTRA_PREFER_OFFLINE`); si no hay, el del sistema, que puede necesitar red: entonces un fallo de red
 * se dice tal cual ([alFallo]) y se para, nunca se finge que se oyó algo. Todo en el hilo principal.
 */
class EscuchaFraseAFrase(
    private val context: Context,
    private val alFrase: (String) -> Unit,
    private val alParcial: (String) -> Unit = {},
    private val alFallo: (String) -> Unit = {},
    /** true al empezar a oír (el micrófono ya está abierto); false al parar. */
    private val alOir: (Boolean) -> Unit = {}
) {
    private val principal = Handler(Looper.getMainLooper())
    private var reconocedor: SpeechRecognizer? = null
    private var activa = false
    private var ocupado = 0

    val escuchando: Boolean get() = activa

    private fun intento(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    /** **Empieza a escuchar.** false si el teléfono no tiene reconocedor (se dice por [alFallo]). */
    fun empezar(): Boolean {
        if (activa) return true
        if (!disponible(context)) {
            alFallo("este teléfono no tiene reconocedor de voz")
            return false
        }
        val r = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        if (r == null) {
            alFallo("no se pudo abrir el reconocedor de voz")
            return false
        }
        reconocedor = r
        activa = true
        ocupado = 0
        r.setRecognitionListener(oyente)
        escuchar()
        return true
    }

    private fun escuchar() {
        val r = reconocedor ?: return
        if (!activa) return
        runCatching { r.startListening(intento()) }.onFailure { fallar("no se pudo escuchar: ${it.message}") }
    }

    /** **Para** (lo ya dicho y cerrado ya llegó; lo provisional se pierde). */
    fun parar() {
        if (!activa && reconocedor == null) return
        activa = false
        principal.removeCallbacksAndMessages(null)
        reconocedor?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
        reconocedor = null
        alOir(false)
    }

    private fun fallar(razon: String) {
        parar()
        alFallo(razon)
    }

    private val oyente = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { ocupado = 0; alOir(true) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (activa && t.isNotBlank()) alParcial(t)
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (!activa) return
            alParcial("")
            if (t.isNotEmpty()) alFrase(t)
            // Y a por la siguiente frase.
            principal.postDelayed({ escuchar() }, 120)
        }

        override fun onError(error: Int) {
            if (!activa) return
            when (error) {
                // Silencio o nada entendido: se sigue escuchando.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    principal.postDelayed({ escuchar() }, 120)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT ->
                    if (++ocupado <= 3) principal.postDelayed({ runCatching { reconocedor?.cancel() }; escuchar() }, 400)
                    else fallar("el reconocedor de voz está ocupado")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    fallar("sin conexión: el reconocedor de voz de este teléfono necesita red")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fallar("falta el permiso del micrófono")
                SpeechRecognizer.ERROR_AUDIO -> fallar("el micrófono no está libre")
                else -> fallar("el reconocedor de voz falló ($error)")
            }
        }
    }

    companion object {
        /** Si el teléfono tiene algún reconocedor de voz. */
        fun disponible(context: Context): Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false))
    }
}
