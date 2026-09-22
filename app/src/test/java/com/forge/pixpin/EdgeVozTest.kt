package com.forge.pixpin

import com.forge.pixpin.motor.EdgeVoz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeVozTest {

    @Test
    fun `el Sec-MS-GEC redondea a cinco minutos y es el SHA-256 en mayusculas`() {
        val a = EdgeVoz.gec(1_790_000_000.0)
        // Dentro de los mismos cinco minutos, el mismo; al pasar al siguiente tramo, otro.
        assertEquals(a, EdgeVoz.gec(1_790_000_000.0 + 50))
        assertTrue(a != EdgeVoz.gec(1_790_000_000.0 + 400))
        assertTrue(a.matches(Regex("[0-9A-F]{64}")))
        // Cuenta a mano: (1790000000 + 11644473600) redondeado a 300, en centenas de nanosegundos.
        val t = (1_790_000_000L + 11_644_473_600L).let { it - it % 300 }
        val esperado = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${t}0000000${EdgeVoz.TOKEN}".toByteArray()).joinToString("") { "%02X".format(it) }
        assertEquals(esperado, a)
    }

    @Test
    fun `el texto va escapado y sin caracteres de control`() {
        val m = EdgeVoz.mensajeDeTexto("id", "fecha", "es-ES-ElviraNeural", "a < b & 'c'\u000Bd")
        assertTrue(m.contains("a &lt; b &amp; &apos;c&apos; d"))
        assertTrue(m.contains("X-Timestamp:fechaZ\r\nPath:ssml\r\n\r\n<speak"))
        assertTrue(m.contains("<voice name='es-ES-ElviraNeural'>"))
    }

    @Test
    fun `el audio se saca de la trama binaria`() {
        fun trama(cab: String, datos: ByteArray): ByteArray {
            val c = cab.toByteArray()
            return byteArrayOf((c.size shr 8).toByte(), c.size.toByte()) + c + datos
        }
        val mp3 = byteArrayOf(1, 2, 3)
        assertEquals(mp3.toList(), EdgeVoz.audioDe(trama("X-RequestId:x\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n", mp3))!!.toList())
        assertNull(EdgeVoz.audioDe(trama("Path:audio\r\n", byteArrayOf())))
        assertNull(EdgeVoz.audioDe(trama("Path:otra\r\n", mp3)))
        assertNull(EdgeVoz.audioDe(byteArrayOf(0)))
        assertEquals("turn.end", EdgeVoz.rutaDe("X-RequestId:x\r\nPath:turn.end\r\n\r\n{}"))
    }

    @Test
    fun `se elige la voz preferida y si no la del pais`() {
        val voces = EdgeVoz.deLista(
            """[{"ShortName":"es-MX-DaliaNeural","Locale":"es-MX","Gender":"Female"},
                {"ShortName":"es-ES-ElviraNeural","Locale":"es-ES","Gender":"Female"},
                {"ShortName":"es-ES-AlvaroNeural","Locale":"es-ES","Gender":"Male"},
                {"ShortName":"en-US-EmmaMultilingualNeural","Locale":"en-US","Gender":"Female"}]"""
        )
        assertEquals(4, voces.size)
        assertEquals("es-ES-AlvaroNeural", EdgeVoz.elegir(voces, "es-ES", null)?.nombre)
        assertEquals("es-MX-DaliaNeural", EdgeVoz.elegir(voces, "es-MX", null)?.nombre)
        assertEquals("es-ES-ElviraNeural", EdgeVoz.elegir(voces, "es-PE", "es-ES-ElviraNeural")?.nombre)
        assertNull(EdgeVoz.elegir(voces, "fr-FR", null))
        assertEquals("Emma multilingüe", voces[3].corto)
        assertEquals(emptyList<EdgeVoz.Voz>(), EdgeVoz.deLista("no es json"))
    }
}
