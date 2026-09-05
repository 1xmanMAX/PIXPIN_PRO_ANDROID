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
        val f = pcm(6, emptyList())
        assertEquals(listOf(0L until f.length()), Transcriptor.cortes(f))
    }

    @Test
    fun `el texto con tiempos, y de vuelta`() {
        val segmentos = listOf(
            Transcriptor.Segmento(0, "Hola a todos."), Transcriptor.Segmento(4000, "Empezamos."),
            Transcriptor.Segmento(25000, "Segundo punto."), Transcriptor.Segmento(3_700_000, "Al final.")
        )
        val texto = Transcriptor.conTiempos(segmentos)
        val lineas = texto.split("\n\n")
        assertEquals("los dos primeros van juntos, el tercero aparte: " + texto, 3, lineas.size)
        assertEquals(Pair(0, "Hola a todos. Empezamos."), Transcriptor.tiempoDe(lineas[0]))
        assertEquals(Pair(25000, "Segundo punto."), Transcriptor.tiempoDe(lineas[1]))
        assertEquals(Pair(3_700_000, "Al final."), Transcriptor.tiempoDe(lineas[2]))
        assertEquals("1:01:40", Transcriptor.marcaDeTiempo(3_700_000))
        assertEquals("con quién habla delante", "[0:00] **Pepe:** Hola a todos. Empezamos.", Transcriptor.conTiempos(segmentos.take(2), "**Pepe:** "))
        assertEquals(null, Transcriptor.tiempoDe("sin tiempo"))
    }

    @Test
    fun `cada frase, su trozo`() {
        // Tres frases de cuatro segundos separadas por medio segundo de silencio.
        val f = pcm(14, listOf(4, 9))
        val trozos = Transcriptor.cortes(f)
        assertEquals("una por frase: " + trozos, 3, trozos.size)
        val bps = Transcriptor.HERCIOS * 2
        assertTrue("el primer corte cae en el silencio de los 4 s", (trozos[0].last + 1) in (4L * bps)..(5L * bps))
    }

    @Test
    fun `largo, cortado en el silencio`() {
        // Tres minutos con un segundo de silencio a los 40, 82 y 130 segundos.
        val f = pcm(180, listOf(40, 82, 130))
        val trozos = Transcriptor.cortes(f)
        assertTrue("tenía que partirse: " + trozos.size, trozos.size >= 3)
        val bps = Transcriptor.HERCIOS * 2
        assertTrue("ningún trozo pasa del tope", trozos.all { it.last - it.first <= 9L * bps })
        assertTrue("hay un corte en el silencio de los 40 s", trozos.any { (it.last + 1) in (40L * bps)..(41L * bps) })
        // Seguidos y sin huecos.
        for (i in 1 until trozos.size) assertEquals(trozos[i - 1].last + 1, trozos[i].first)
        assertEquals(f.length() - 1, trozos.last().last)
    }
}
