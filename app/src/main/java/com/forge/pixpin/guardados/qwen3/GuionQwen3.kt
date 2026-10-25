package com.forge.pixpin.guardados.qwen3

import com.forge.pixpin.guardados.Transcriptor
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * **Lo que se le dice a Qwen3-ASR y cómo se lee lo que contesta**, sin modelo (25-oct-2026).
 *
 * Puerto de `crates/pixpin-voz/src/qwen3/guion.rs` del PC. Qwen3-ASR es un modelo de chat: el
 * audio entra como una fila de `<|audio_pad|>` (uno por cada «token de audio» del encoder, unos
 * 13 por segundo) dentro de un turno de usuario, y el modelo contesta en el del asistente:
 *
 * ```
 * <|im_start|>system\n<|im_end|>\n<|im_start|>user\n<|audio_start|>
 * <|audio_pad|> × n
 * <|audio_end|><|im_end|>\n<|im_start|>assistant\n
 * language Spanish<asr_text>          ← solo si se le dice el idioma
 * ```
 *
 * **El idioma se le dice** (como a Whisper): con el turno del asistente ya empezado por
 * `language Spanish<asr_text>`, el modelo solo escribe el texto. Sin él, escribe primero
 * `language X<asr_text>` y luego el texto; [Guion.sinPrefijo] lo separa.
 *
 * Aquí viven también las cuentas que no necesitan ONNX: cuántos tokens de audio salen de N marcos
 * de mel, el tope de tokens por tanda, el token elegido (voraz), los bucles de repetición y por
 * dónde se parte una nota. Mismo guion y misma voracidad que el PC: el texto sale igual.
 */
object GuionQwen3 {

    /** Los 30 idiomas de Qwen3-ASR (`support_languages` de su `config.json`), con el código de la app. `tl` es otro nombre del filipino. */
    val IDIOMAS: List<Pair<String, String>> = listOf(
        "zh" to "Chinese", "en" to "English", "yue" to "Cantonese", "ar" to "Arabic", "de" to "German",
        "fr" to "French", "es" to "Spanish", "pt" to "Portuguese", "id" to "Indonesian", "it" to "Italian",
        "ko" to "Korean", "ru" to "Russian", "th" to "Thai", "vi" to "Vietnamese", "ja" to "Japanese",
        "tr" to "Turkish", "hi" to "Hindi", "ms" to "Malay", "nl" to "Dutch", "sv" to "Swedish",
        "da" to "Danish", "fi" to "Finnish", "pl" to "Polish", "cs" to "Czech", "fil" to "Filipino",
        "tl" to "Filipino", "fa" to "Persian", "el" to "Greek", "ro" to "Romanian", "hu" to "Hungarian",
        "mk" to "Macedonian"
    )

    /** El nombre que entiende Qwen3-ASR para un código (`es`, `es-PE`…). */
    fun nombreDelIdioma(codigo: String): String? {
        val lengua = codigo.split('-', '_').first().lowercase()
        return IDIOMAS.firstOrNull { it.first == lengua }?.second
    }

    /** La vuelta: el código de un nombre (`Spanish` → `es`). */
    fun codigoDelIdioma(nombre: String): String? = IDIOMAS.firstOrNull { it.second.equals(nombre.trim(), ignoreCase = true) }?.first

    /** Si Qwen3-ASR sabe el idioma. */
    fun sabe(codigo: String): Boolean = nombreDelIdioma(codigo) != null

    private const val ANTES = "<|im_start|>system\n<|im_end|>\n<|im_start|>user\n<|audio_start|>"
    private const val DESPUES = "<|audio_end|><|im_end|>\n<|im_start|>assistant\n"

    /** **El guion ya pasado a números**, una vez al cargar. */
    class Guion private constructor(
        private val antes: List<Long>,
        private val despues: List<Long>,
        val pad: Long,
        val asrText: Long,
        /** `<|im_end|>` y `<|endoftext|>`: cualquiera de los dos cierra. */
        val fines: List<Long>,
        /** `language X` de cada idioma, por nombre. */
        private val idiomas: Map<String, List<Long>>
    ) {
        /** **La entrada del decoder** para [audio] tokens de audio, con el idioma dicho (`"Spanish"`) o para que lo adivine (null). */
        fun fuente(audio: Int, idioma: String?): LongArray {
            val v = ArrayList<Long>(antes.size + audio + despues.size + 4)
            v += antes
            repeat(audio) { v += pad }
            v += despues
            idioma?.let { idiomas[it] }?.let { v += it; v += asrText }
            return v.toLongArray()
        }

        fun esFin(token: Long): Boolean = token in fines

        /**
         * **Lo escrito sin el `language X<asr_text>` del principio** (cuando se le dejó adivinar el
         * idioma), y el código del idioma que dijo. Solo se mira en los 16 primeros tokens, como
         * sherpa-onnx.
         */
        fun sinPrefijo(ids: List<Long>, f: FichasQwen3.Fichas): Pair<List<Long>, String?> {
            val ventana = minOf(ids.size, 16)
            val p = ids.subList(0, ventana).indexOf(asrText)
            if (p < 0) return ids to null
            val dicho = f.texto(ids.subList(0, p)).trim()
            val codigo = if (dicho.startsWith("language")) codigoDelIdioma(dicho.removePrefix("language")) else null
            return ids.subList(p + 1, ids.size) to codigo
        }

        companion object {
            /** Codifica el guion con el tokenizador. Null si falta algún especial (no es el de Qwen3-ASR). */
            fun nuevo(f: FichasQwen3.Fichas, fusiones: FichasQwen3.Fusiones): Guion? {
                val pad = f.especial("<|audio_pad|>") ?: return null
                val asr = f.especial("<asr_text>") ?: return null
                val fines = listOf(f.especial("<|im_end|>") ?: return null, f.especial("<|endoftext|>") ?: return null)
                f.especial("<|audio_start|>") ?: return null
                f.especial("<|audio_end|>") ?: return null
                val idiomas = LinkedHashMap<String, List<Long>>()
                for ((_, n) in IDIOMAS) if (n !in idiomas) idiomas[n] = f.codificar("language $n", fusiones)
                return Guion(f.codificar(ANTES, fusiones), f.codificar(DESPUES, fusiones), pad, asr, fines, idiomas)
            }
        }
    }

    /** Los marcos de mel que agrupa el frente en cada «trozo» (1 s). */
    private const val TROZO_DEL_FRENTE = 100

    /**
     * **Cuántos tokens de audio salen de [marcos] de mel**: tres convoluciones de paso 2 por cada
     * trozo de 100 marcos (13 tokens por segundo), y lo que sobre, lo suyo
     * (`FeatToAudioTokensLen` de sherpa-onnx).
     */
    fun tokensDeAudio(marcos: Int): Int {
        fun mitadArriba(n: Int) = (n + 1) / 2
        val lleno = mitadArriba(mitadArriba(mitadArriba(TROZO_DEL_FRENTE)))
        fun tras(x0: Int): Int {
            if (x0 == 0) return 0
            var x = (x0 - 1) / 2 + 1
            x = (x - 1) / 2 + 1
            return (x - 1) / 2 + 1
        }
        return (marcos / TROZO_DEL_FRENTE) * lleno + tras(marcos % TROZO_DEL_FRENTE)
    }

    /**
     * **Cuántos tokens puede escribir como mucho** una tanda de [muestras]. En castellano salen
     * ~3,3 por segundo; se dejan diez y un margen, como con Whisper: cortar una frase buena es peor
     * que dejar correr una mala (esa la paran [repiteAlFinal] o `creible`).
     */
    fun topeDeTokens(muestras: Int): Int = ceil(muestras.toFloat() / MelQwen3.HERCIOS * 10f).toInt() + 16

    /** El token más probable (voraz). Los valores no finitos no compiten. */
    fun elegir(fila: FloatArray, desde: Int = 0, hasta: Int = fila.size): Long {
        var mejor = 0
        var valor = Float.NEGATIVE_INFINITY
        for (i in desde until hasta) {
            val l = fila[i]
            if (l.isFinite() && l > valor) { valor = l; mejor = i - desde }
        }
        return mejor.toLong()
    }

    /**
     * **Si el final se ha metido en un bucle**, el largo que hay que dejar. Dos reglas: la de
     * Whisper (cuatro veces seguidas el mismo bloque de 1 a 16 tokens: se deja uno) y la de
     * sherpa-onnx para Qwen3-ASR (los últimos 64 tokens son solo 2 o 3 distintos: se quita la cola
     * entera).
     */
    fun repiteAlFinal(t: List<Long>): Int? {
        val veces = 4
        for (p in 1..16) {
            if (t.size < veces * p) break
            val n = t.size
            var todas = true
            for (k in 2..veces) {
                for (j in 0 until p) if (t[n - k * p + j] != t[n - p + j]) { todas = false; break }
                if (!todas) break
            }
            if (todas) return n - (veces - 1) * p
        }
        val ventana = 64
        val distintos = 3
        if (t.size >= ventana) {
            val cola = t.subList(t.size - ventana, t.size)
            val vistos = ArrayList<Long>()
            for (x in cola) if (x !in vistos) { vistos += x; if (vistos.size > distintos) return null }
            val quitar = cola.asReversed().takeWhile { it in vistos }.size
            return t.size - quitar
        }
        return null
    }

    /**
     * **Cuántas filas del encoder tienen voz**: las del final que salen a cero (relleno o silencio
     * puro) se quitan, como `TrimAudioFeatures` de sherpa-onnx. [ancho] es lo largo de cada fila.
     */
    fun filasConVoz(datos: FloatArray, ancho: Int): Int {
        if (ancho == 0) return 0
        for (f in datos.size / ancho - 1 downTo 0) {
            for (j in f * ancho until (f + 1) * ancho) if (kotlin.math.abs(datos[j]) > 1e-6f) return f + 1
        }
        return 0
    }

    /** Lo que se junta en una tanda: frases seguidas hasta 20 s (cada segundo son 13 tokens de audio en la caché). */
    const val TANDA_SEGUNDOS = 20

    /** Ninguna tanda pasa de 30 s (lo que el modelo vio entrenando). */
    const val TOPE_SEGUNDOS = 30

    /** Un tramo del PCM, en bytes (16 bits, mono, 16 kHz): [desde] incluido, [hasta] no. */
    data class Tanda(val desde: Long, val hasta: Long)

    /**
     * **Por dónde se parte una nota**: en las frases de [Transcriptor.cortes] (las mismas que con
     * Whisper, y las que portó el PC a `cortes.rs`) juntadas hasta 20 s por [Transcriptor.agrupar],
     * sin solapar y cortando siempre en un silencio o en el punto más callado; lo que aún pase de
     * 30 s se parte en trozos de 30. Cada tanda sabe en qué byte empieza: de ahí sale su `[m:ss]`.
     */
    fun tandas(frases: List<LongRange>): List<Tanda> {
        val tope = TOPE_SEGUNDOS.toLong() * MelQwen3.HERCIOS * 2
        return Transcriptor.agrupar(frases, TANDA_SEGUNDOS).flatMap { g ->
            val desde = g.first().first
            val hasta = g.last().last + 1
            (desde until hasta step tope).map { a -> Tanda(a, minOf(a + tope, hasta)) }
        }
    }

    /** Si una tanda es silencio (ni un tramo de 20 ms por encima del ruido de fondo): no se le da al modelo, que con silencio puede inventar. */
    fun callada(trozo: ShortArray): Boolean {
        val ventana = 320
        val ruidoDeFondo = 120.0
        var i = 0
        while (i < trozo.size) {
            val fin = minOf(i + ventana, trozo.size)
            var e = 0.0
            for (j in i until fin) { val x = trozo[j].toDouble(); e += x * x }
            if (sqrt(e / (fin - i)) >= ruidoDeFondo) return false
            i = fin
        }
        return true
    }
}
