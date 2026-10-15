package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.ui.LectorEnVoz
import java.io.File

/**
 * **Hablarle y oírle** (§4.6 del manual del PC, `voz_del_asistente.rs`):
 *
 * - **Micrófono**: lo grabado no se guarda como nota de voz; se transcribe con el motor elegido en
 *   Ajustes → Voz ([com.forge.pixpin.guardados.Transcriptor]), sin las marcas `[m:ss]`, y se borra.
 * - **Altavoz**: lee una respuesta con el lector común ([LectorEnVoz]); no se lee el pensamiento,
 *   el JSON, los bloques de código, las líneas de estado ni los avisos ([textoParaLeer]). Hablar
 *   al micrófono calla la lectura.
 */
object VozDelAsistente {
    /** Lo que se lee de una respuesta (`texto_para_leer` del PC). */
    fun textoParaLeer(t0: String): String {
        var t = ChatDelAsistente.sinPensado(t0)
        t = t.replace(Regex("```[\\s\\S]*?(```|$)"), " ")
        return t.lineSequence().map { it.trim() }
            .filter { l -> l.isNotEmpty() && !l.startsWith("{") && !l.startsWith("[") && !l.startsWith("⚠️") && !l.startsWith("❓") &&
                listOf("✏️ ", "📊 ", "🔎 ", "⚙️ ", "🔁 ", "💭 ", "🗂️ ", "🖼️ ", "📖 ").none { l.startsWith(it) } }
            .map { it.replace(" ⏹ (parado)", "").replace(Regex("\\[\\d+]"), "").replace(Regex("[*_#`>]"), "") }
            .joinToString("\n").trim()
    }

    /** El texto transcrito, sin las marcas de tiempo `[m:ss]` ni espacios de más. */
    fun limpiarDictado(t: String): String = t.replace(Regex("\\[\\d+:\\d{2}(:\\d{2})?]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * **Transcribe** lo grabado (en este hilo: llamar fuera del de la pantalla) y lo borra. null si no
     * se entendió nada o falló.
     */
    fun transcribir(c: Context, audio: File): String? = try {
        when (val r = com.forge.pixpin.guardados.Transcriptor.transcribirAqui(c, audio, com.forge.pixpin.guardados.Transcriptor.idiomaElegido(c), {})) {
            is com.forge.pixpin.guardados.Transcriptor.Resultado.Texto -> limpiarDictado(r.texto).takeIf { it.isNotBlank() }
            else -> null
        }
    } catch (e: Throwable) {
        null
    } finally {
        audio.delete()
    }

    /** **Lee una respuesta** en voz alta. En el hilo de la pantalla. */
    fun leer(c: Context, texto: String) {
        val limpio = textoParaLeer(texto)
        if (limpio.isBlank()) return
        val lector = LectorEnVoz.delApp(c)
        val parrafos = limpio.split(Regex("\n+")).filter { it.isNotBlank() }
        val idioma = com.forge.pixpin.motor.VozAlta.idiomaParaLeer(limpio, "", java.util.Locale.getDefault().toLanguageTag())
        fun sonar() { lector.usarEdge(null); lector.documento(parrafos, "asistente", "Asistente"); lector.leer(0) }
        if (lector.estado.value.listo) sonar()
        else lector.arrancar(idioma, 1f, false) { a -> if (a is LectorEnVoz.Arranque.Bien) sonar() }
    }

    /** Calla la lectura (al hablar al micrófono). */
    fun callar(c: Context) = runCatching { LectorEnVoz.delApp(c).parar() }
}
