package com.forge.pixpin.guardados.qwen3

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **Con el modelo de verdad, en la JVM** (como `qwen3_de_verdad` del PC): `PIXPIN_QWEN3` (la
 * carpeta con los seis ficheros), `PIXPIN_ORT` (la ruta de un `libonnxruntime.so` de esta máquina)
 * y `PIXPIN_WAVS` (una carpeta con WAV PCM de 16 bits a 16 kHz: `es1.wav`, `fr1.wav`… los de
 * `test_wavs/` del mismo repositorio de Hugging Face). Sin ellos no corre.
 *
 * Prueba el canal entero del teléfono —tandas, mel, las tres sesiones por JNA, el decoder en
 * trozos de 64 con la caché del largo justo— salvo el `.so` de ARM, y escribe lo oído, el RTF y el
 * pico de memoria del proceso.
 */
class Qwen3DeVerdadTest {

    private fun env(n: String) = System.getenv(n)?.takeIf { it.isNotBlank() }?.let(::File)

    /** Los datos de un WAV PCM de 16 bits mono, a un `.raw` (lo que deja `Transcriptor.decodificar`). */
    private fun crudo(wav: File): File {
        val b = wav.readBytes()
        var i = 12
        while (i + 8 <= b.size) {
            val largo = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (String(b, i, 4, Charsets.US_ASCII) == "data") {
                return File.createTempFile("qwen3-de-verdad", ".raw").apply { deleteOnExit(); writeBytes(b.copyOfRange(i + 8, minOf(i + 8 + largo, b.size))) }
            }
            i += 8 + largo + (largo and 1)
        }
        error("sin datos en $wav")
    }

    @Test
    fun `qwen3 de verdad`() {
        val modelo = env("PIXPIN_QWEN3"); val ort = env("PIXPIN_ORT"); val wavs = env("PIXPIN_WAVS")
        assumeTrue("pide PIXPIN_QWEN3, PIXPIN_ORT y PIXPIN_WAVS", modelo != null && ort != null && wavs != null)
        val t0 = System.nanoTime()
        val q = MotorQwen3.Cargado.cargar(OrtPorJna.cargar(ort!!.absolutePath), modelo!!, MotorQwen3.reparto(16_000, 4))
        System.err.println("carga ${(System.nanoTime() - t0) / 1e9} s, ONNX Runtime ${q.versionDelRuntime}")
        q.use {
            for ((wav, idioma, segundo, espera) in listOf(
                Cuarteto("es1.wav", "es", "", "prenda"),
                Cuarteto("fr1.wav", "es", "fr", "Paris"),
                Cuarteto("de.wav", "de", "", "Bergbau"),
                Cuarteto("noise1-en.wav", "en", "", "babe")
            )) {
                val f = File(wavs, wav)
                if (!f.isFile) continue
                val pcm = crudo(f)
                val t = System.nanoTime()
                val segmentos = q.transcribir(pcm, idioma, segundo)
                val dt = (System.nanoTime() - t) / 1e9
                val dur = pcm.length() / 32_000.0
                val texto = segmentos.joinToString(" | ") { "[${it.desdeMs}] ${it.texto}" }
                System.err.println("$wav rtf ${"%.3f".format(dt / dur)} ($idioma${if (segundo.isNotEmpty()) "+$segundo" else ""}): $texto")
                assertTrue("$wav: $texto", texto.contains(espera, ignoreCase = true))
            }
        }
        val pico = File("/proc/self/status").takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("VmHWM") }.orEmpty()
        System.err.println(pico)
    }

    private data class Cuarteto(val wav: String, val idioma: String, val segundo: String, val espera: String)
}
