package com.forge.pixpin.guardados

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los trozos se cortan donde hay silencio, y un audio corto es un solo trozo. */
class TranscriptorCortesTest {
    private fun pcm(segundos: Int, silencioEn: List<Int>): File {
        val f = File.createTempFile("pcm", ".raw")
        val bytes = ByteArray(segundos * Transcriptor.HERCIOS * 2)
        var i = 0
        while (i < bytes.size) {
            val seg = i / (Transcriptor.HERCIOS * 2)
            val v = if (seg in silencioEn) 0 else 8000
            bytes[i] = (v and 0xFF).toByte(); bytes[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `corto, un trozo`() {
        val f = pcm(30, emptyList())
        assertEquals(listOf(0L until f.length()), Transcriptor.cortes(f))
    }

    @Test
    fun `largo, cortado en el silencio`() {
        // Tres minutos con un segundo de silencio a los 40, 82 y 130 segundos.
        val f = pcm(180, listOf(40, 82, 130))
        val trozos = Transcriptor.cortes(f)
        assertTrue("tenía que partirse: " + trozos.size, trozos.size >= 3)
        val bps = Transcriptor.HERCIOS * 2
        val primero = trozos[0].last + 1
        assertTrue("el corte va en el silencio de los 40 s, no en el tope: " + primero / bps.toDouble(), primero in (40L * bps)..(41L * bps))
        // Seguidos y sin huecos.
        for (i in 1 until trozos.size) assertEquals(trozos[i - 1].last + 1, trozos[i].first)
        assertEquals(f.length() - 1, trozos.last().last)
    }
}
