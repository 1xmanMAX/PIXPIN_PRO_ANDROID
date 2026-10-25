package com.forge.pixpin.guardados.qwen3

import com.forge.pixpin.guardados.Transcriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sin

/** El guion de Qwen3-ASR y sus cuentas: los mismos casos que `qwen3/guion.rs` del PC. */
class GuionQwen3Test {

    private fun fichas(): Pair<FichasQwen3.Fichas, FichasQwen3.Fusiones> {
        // Un tokenizador de juguete con los especiales de Qwen y cada letra suelta (sin fusiones):
        // basta para ver la forma del guion.
        val vocab = JsonObject((0..255).associate { FichasQwen3.letraDeByte(it).toString() to JsonPrimitive(it) }).toString()
        val config = buildJsonObject {
            put("added_tokens_decoder", JsonObject(
                listOf("<|endoftext|>", "<|im_start|>", "<|im_end|>", "<|audio_start|>", "<|audio_end|>", "<|audio_pad|>", "<asr_text>")
                    .withIndex().associate { (i, n) -> (1000 + i).toString() to buildJsonObject { put("content", n); put("special", true) } }
            ))
        }.toString()
        return FichasQwen3.Fichas.leer(vocab, config)!! to FichasQwen3.Fusiones.NINGUNA
    }

    @Test
    fun `el guion lleva el audio en medio y el idioma al final`() {
        val (f, fus) = fichas()
        val g = GuionQwen3.Guion.nuevo(f, fus)!!
        val v = g.fuente(3, "Spanish")
        val pads = v.indices.filter { v[it] == g.pad }
        assertEquals(3, pads.size)
        assertTrue("seguidos", pads.zipWithNext().all { (a, b) -> b == a + 1 })
        assertEquals("<|audio_start|> justo antes", 1003L, v[pads[0] - 1])
        assertEquals("<|audio_end|> justo después", 1004L, v[pads[2] + 1])
        assertEquals(g.asrText, v.last())
        assertTrue(f.texto(v.toList()).endsWith("assistant\nlanguage Spanish"))
        // Sin idioma acaba en el turno del asistente, sin `<asr_text>`.
        val w = g.fuente(3, null)
        assertFalse(g.asrText in w)
        assertTrue(f.texto(w.toList()).endsWith("assistant\n"))
        assertEquals(listOf(1002L, 1000L), g.fines)
    }

    @Test
    fun `lo adivinado se separa del texto y dice el idioma`() {
        val (f, fus) = fichas()
        val g = GuionQwen3.Guion.nuevo(f, fus)!!
        // Con el juguete cada letra es un token: un nombre corto, para que `<asr_text>` caiga
        // dentro de los 16 primeros (con el de verdad, `language English` son dos tokens).
        val texto = f.codificar("Hello", fus)
        val ids = f.codificar("language Thai", fus) + g.asrText + texto
        val (resto, idioma) = g.sinPrefijo(ids, f)
        assertEquals(texto, resto)
        assertEquals("th", idioma)
        // Sin prefijo, todo es texto.
        assertEquals(texto to null, g.sinPrefijo(texto, f))
    }

    @Test
    fun `los idiomas de la app tienen su nombre en qwen`() {
        assertEquals("Spanish", GuionQwen3.nombreDelIdioma("es"))
        assertEquals("Spanish", GuionQwen3.nombreDelIdioma("es-PE"))
        assertEquals("English", GuionQwen3.nombreDelIdioma("EN_us"))
        assertEquals("Portuguese", GuionQwen3.nombreDelIdioma("pt"))
        assertEquals("Filipino", GuionQwen3.nombreDelIdioma("tl"))
        assertNull("el euskera no está", GuionQwen3.nombreDelIdioma("eu"))
        assertNull("el catalán tampoco: con él se pasa con Whisper", GuionQwen3.nombreDelIdioma("ca"))
        assertEquals("es", GuionQwen3.codigoDelIdioma(" spanish"))
        assertTrue(GuionQwen3.sabe("es") && !GuionQwen3.sabe(""))
    }

    @Test
    fun `de los marcos salen los tokens de audio de sherpa`() {
        // `FeatToAudioTokensLen(n, 100)`: 13 por segundo lleno.
        assertEquals(0, GuionQwen3.tokensDeAudio(0))
        assertEquals(13, GuionQwen3.tokensDeAudio(100))
        assertEquals(1, GuionQwen3.tokensDeAudio(1))
        assertEquals(13 + 7, GuionQwen3.tokensDeAudio(150))
        assertEquals(260, GuionQwen3.tokensDeAudio(2_000))
        assertEquals(247 + 13, GuionQwen3.tokensDeAudio(1_999))
    }

    @Test
    fun `el tope crece con lo largo de la tanda`() {
        assertEquals(16, GuionQwen3.topeDeTokens(0))
        assertEquals(216, GuionQwen3.topeDeTokens(20 * MelQwen3.HERCIOS))
    }

    @Test
    fun `se elige el mas alto y lo que no es numero no cuenta`() {
        assertEquals(1L, GuionQwen3.elegir(floatArrayOf(0f, 3f, Float.NaN, 2f)))
        assertEquals(1L, GuionQwen3.elegir(floatArrayOf(Float.POSITIVE_INFINITY, -1f)))
        assertEquals(0L, GuionQwen3.elegir(floatArrayOf()))
    }

    @Test
    fun `un bucle se corta y una frase normal no`() {
        assertEquals(3, GuionQwen3.repiteAlFinal(listOf(1L, 5, 6, 5, 6, 5, 6, 5, 6)))
        assertNull(GuionQwen3.repiteAlFinal(listOf(1L, 5, 6, 5, 6, 5, 6)))
        assertNull(GuionQwen3.repiteAlFinal((0L until 100L).toList()))
        // La regla de sherpa: 64 tokens de solo tres distintos.
        val t = (100L until 110L).toMutableList()
        val ciclo = listOf(1L, 2, 1, 3, 1, 2, 2, 3, 1)
        for (i in 0 until 70) t += ciclo[i % ciclo.size]
        // El ciclo de 9 se repite exacto: también vale la regla de Whisper, que deja una vuelta.
        // En cualquier caso se queda lo de antes.
        val largo = GuionQwen3.repiteAlFinal(t)!!
        assertTrue("$largo", largo >= 10 && largo < t.size)
        assertEquals((100L until 110L).toList(), t.subList(0, 10))
    }

    @Test
    fun `las filas a cero del final no son voz`() {
        val d = floatArrayOf(1f, 0f, 0f, 2f, 0f, 0f, 0f, 0f)
        assertEquals(2, GuionQwen3.filasConVoz(d, 2))
        assertEquals(0, GuionQwen3.filasConVoz(FloatArray(6), 3))
        assertEquals(0, GuionQwen3.filasConVoz(d, 0))
    }

    private fun tono(segundos: Float) = ShortArray((segundos * MelQwen3.HERCIOS).toInt()) { (sin(it * 0.3f) * 6_000f).toInt().toShort() }

    private fun pcm(muestras: ShortArray): File = File.createTempFile("qwen3-guion", ".raw").apply {
        deleteOnExit()
        val b = ByteArray(muestras.size * 2)
        for (i in muestras.indices) { b[2 * i] = (muestras[i].toInt() and 0xFF).toByte(); b[2 * i + 1] = (muestras[i].toInt() shr 8).toByte() }
        writeBytes(b)
    }

    @Test
    fun `una nota larga se parte en tandas de hasta treinta segundos sin perder nada`() {
        // Un minuto de habla continua (sin pausas): se parte por los puntos más callados y
        // ninguna tanda pasa del tope.
        val muestras = tono(65f)
        val t = GuionQwen3.tandas(pcm(muestras))
        assertTrue("$t", t.size >= 3)
        assertTrue(t.all { it.hasta - it.desde <= GuionQwen3.TOPE_SEGUNDOS * MelQwen3.HERCIOS * 2L })
        assertEquals(0L, t[0].desde)
        assertEquals(muestras.size * 2L, t.last().hasta)
        assertTrue("sin solapar", t.zipWithNext().all { (a, b) -> a.hasta <= b.desde })
    }

    @Test
    fun `dos frases con una pausa van juntas si caben`() {
        val muestras = tono(5f) + ShortArray(MelQwen3.HERCIOS) + tono(5f)
        assertEquals(1, GuionQwen3.tandas(pcm(muestras)).size)
    }

    @Test
    fun `una frase de mas de treinta segundos se parte en trozos de treinta`() {
        val seg = MelQwen3.HERCIOS * 2L
        val t = GuionQwen3.tandas(listOf(0 until 70 * seg))
        assertEquals(listOf(0L to 30 * seg, 30 * seg to 60 * seg, 60 * seg to 70 * seg), t.map { it.desde to it.hasta })
    }

    @Test
    fun `el silencio no se le da al modelo`() {
        assertTrue(GuionQwen3.callada(ShortArray(16_000)))
        assertTrue("ruido de fondo", GuionQwen3.callada(ShortArray(16_000) { 50 }))
        assertFalse(GuionQwen3.callada(tono(1f)))
        assertTrue(GuionQwen3.callada(ShortArray(0)))
    }
}
