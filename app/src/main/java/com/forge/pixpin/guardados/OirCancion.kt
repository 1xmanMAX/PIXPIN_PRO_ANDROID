package com.forge.pixpin.guardados

import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Oír una canción para ponerle los tiempos a su letra** (29-oct-2026, puerto de `whisper::oir_cancion`
 * del PC, b15467aa y 147492df).
 *
 * - **Tanda a tanda**: frases de hasta [Transcriptor.TANDA_CANCION_SEGUNDOS] (8 s, no los 15 de una
 *   nota), sin juntar en líneas: cuanto más corta la tanda, más cerca cae el tiempo de cada palabra.
 *   Una tanda que no se entiende no tumba las demás.
 * - **En el idioma de la letra** ([AlinearLetra.idiomasParaOir]): una canción en inglés se oye en
 *   inglés aunque PixPin esté en castellano; si no se nota, el de Ajustes con su segundo idioma.
 * - **Sin anotaciones** ([sinAnotaciones]): con música debajo, Whisper escribe «[Música]» o «♪» en
 *   vez de la letra. El PC le prohíbe esos tokens (`Fichas::sin_habla`, la lista `non_speech_tokens`
 *   de openai/whisper); sherpa-onnx no deja tocar el decodificador, así que aquí se quitan del texto.
 *   Es la única diferencia con el PC: allí Whisper, sin poder escribirlas, a veces escribe la letra
 *   en su lugar; aquí esa tanda se queda sin palabras.
 * - Con Whisper (el de fábrica). Si el teléfono no lo puede cargar (32 bits), el motor de Ajustes.
 */
object OirCancion {

    /** Lo que se oyó, o por qué no. */
    sealed class Oido {
        /** Las tandas con su texto, y lo que dura la canción. */
        class Texto(val trozos: List<Transcriptor.Segmento>, val duracionMs: Long) : Oido()
        /** Falta el reconocedor y no se pudo bajar (sin red). */
        object SinModelo : Oido()
        /** No se entendió nada. */
        object Nada : Oido()
        /** Parada a medias. */
        object Cancelada : Oido()
        class Fallo(val codigo: Int, val detalle: String? = null) : Oido()
    }

    /** Las notas musicales que Whisper escribe en vez de la letra (`NOTAS` de `fichas.rs`). */
    private const val NOTAS = "♩♪♫♬♭♮♯"

    /** Una anotación entre corchetes, paréntesis o llaves: `[Música]`, `(aplausos)`, `{risas}`. */
    private val ANOTACION = Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*}|「[^」]*」|『[^』]*』""")

    /**
     * **Lo que se canta de una tanda, sin anotaciones** (puro): fuera lo que va entre corchetes,
     * paréntesis o llaves, las notas musicales y los corchetes, almohadillas o asteriscos sueltos
     * que queden (los símbolos de `non_speech_tokens`). Lo demás, tal cual, con un blanco entre
     * palabras.
     */
    fun sinAnotaciones(texto: String): String {
        val sin = ANOTACION.replace(texto, " ")
            .filterNot { it in NOTAS || it in "[](){}#*<>|~^_\\@=+「」『』" }
        return sin.split(Regex("\\s+")).filter { p -> p.isNotEmpty() && p.any { it.isLetterOrDigit() } }.joinToString(" ")
    }

    /** Bytes de PCM por milisegundo (16 bits, mono, [Transcriptor.HERCIOS]). */
    private const val BYTES_POR_MS = Transcriptor.HERCIOS * 2 / 1000

    /**
     * **Oye [archivo]** en este hilo. [lineas]: la letra (para su idioma). [avance] de 0 a 1;
     * [cancelado] la para entre tanda y tanda. El reconocedor ya tiene que estar bajado o se baja
     * aquí (quien llame dice antes si eso vale).
     */
    fun oir(context: Context, archivo: File, lineas: List<String>, avance: (Float) -> Unit, cancelado: AtomicBoolean = AtomicBoolean(false)): Oido {
        val (idioma, segundo) = AlinearLetra.idiomasParaOir(lineas, Transcriptor.idiomaElegido(context), Transcriptor.segundoIdioma(context))
        if (!MotorWhisper.soportado()) {
            // Sin Whisper (32 bits): el motor de Ajustes, con el idioma de la letra.
            return when (val r = Transcriptor.transcribirAqui(context, archivo, idioma, avance)) {
                is Transcriptor.Resultado.Texto -> Oido.Texto(r.trozos, 0L)
                is Transcriptor.Resultado.DescargandoIdioma -> Oido.SinModelo
                is Transcriptor.Resultado.Fallo -> if (r.codigo == Transcriptor.NADA) Oido.Nada else Oido.Fallo(r.codigo, r.detalle)
            }
        }
        val pcm = File(context.cacheDir, "pcm-cancion-${System.nanoTime()}.raw")
        try {
            var decodificado = runCatching { pcm.outputStream().buffered().use { Transcriptor.decodificar(archivo, it) } }
            if (decodificado.isFailure || pcm.length() <= 0) {
                pcm.delete()
                decodificado = runCatching { pcm.outputStream().buffered().use { Transcriptor.decodificar(archivo, it, software = true) } }
            }
            if (decodificado.isFailure || pcm.length() <= 0) {
                return Oido.Fallo(Transcriptor.NO_SE_LEE, decodificado.exceptionOrNull()?.let { Transcriptor.resumenDe(it) })
            }
            if (cancelado.get()) return Oido.Cancelada
            avance(0.05f)
            if (!MotorWhisper.modeloListo(context)) MotorWhisper.asegurarModelo(context) ?: return Oido.SinModelo
            val trozos = MotorWhisper.reconocer(
                context, pcm, idioma, segundo, todoEnUno = false,
                avance = { avance(0.05f + 0.95f * it) }, cancion = true, cancelado = cancelado
            ) ?: return Oido.SinModelo
            if (trozos.isEmpty()) return Oido.Nada
            return Oido.Texto(trozos, pcm.length() / BYTES_POR_MS)
        } catch (e: CancellationException) {
            return Oido.Cancelada
        } catch (e: Exception) {
            android.util.Log.e("PixPinVoz", "oír la canción", e)
            return Oido.Fallo(Transcriptor.FALLO_INTERNO, Transcriptor.resumenDe(e))
        } finally {
            pcm.delete()
        }
    }
}
