package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las frases se juntan en tandas por duración, y lo inventado por Whisper se tira. */
class TranscriptorTandasTest {
    private val seg = Transcriptor.HERCIOS * 2L

    private fun frase(desdeS: Int, hastaS: Int) = (desdeS * seg) until (hastaS * seg)

    @Test
    fun `frases seguidas en tandas de hasta veinte segundos`() {
        val frases = listOf(frase(0, 6), frase(7, 13), frase(14, 19), frase(20, 27), frase(28, 45), frase(46, 50))
        val tandas = Transcriptor.agrupar(frases, 20)
        assertEquals(listOf(listOf(frases[0], frases[1], frases[2]), listOf(frases[3]), listOf(frases[4]), listOf(frases[5])), tandas)
        // La tanda es un tramo contiguo: de donde empieza la primera a donde acaba la última.
        assertEquals(0L, tandas[0].first().first); assertEquals(19 * seg - 1, tandas[0].last().last)
        assertEquals(0, Transcriptor.msDe(tandas[0].first().first)); assertEquals(20_000, Transcriptor.msDe(tandas[1].first().first))
    }

    @Test
    fun `sin frases no hay tandas, y una frase larga va sola`() {
        assertTrue(Transcriptor.agrupar(emptyList(), 20).isEmpty())
        assertEquals(listOf(listOf(frase(0, 25))), Transcriptor.agrupar(listOf(frase(0, 25)), 20))
    }

    @Test
    fun `lo creible y lo inventado`() {
        assertTrue(Transcriptor.creible("Mañana revisamos el muro de contención a las ocho.", "es-PE"))
        assertTrue(Transcriptor.creible("Ok, that's it for the survey.", "en"))
        assertFalse(Transcriptor.creible("   ", "es"))
        assertFalse(Transcriptor.creible("今天我们去看看", "es"))
        assertFalse(Transcriptor.creible("Спасибо за просмотр", "es"))
        assertFalse(Transcriptor.creible("Subtítulos realizados por la comunidad de Amara.org", "es"))
        assertFalse(Transcriptor.creible("no no no no no no no no no no", "es"))
        // Una palabra suelta en otro alfabeto no tumba una frase de verdad.
        assertTrue(Transcriptor.creible("El proveedor se llama 東京 y manda el acero el lunes por la mañana.", "es"))
        // En un idioma de otro alfabeto no se mira el alfabeto.
        assertTrue(Transcriptor.creible("今天我们去看看", "zh"))
    }

    @Test
    fun `los resultados de Google se casan con las frases`() {
        val tanda = listOf(frase(0, 5), frase(6, 11), frase(12, 18))
        val uno = MotorGoogle.casar(tanda, listOf("uno", "dos", "tres"))
        assertEquals(listOf(0, 6000, 12000), uno.map { it.desdeMs }); assertEquals(listOf("uno", "dos", "tres"), uno.map { it.texto })
        // Menos textos que frases: se reparten.
        val menos = MotorGoogle.casar(tanda, listOf("uno y dos", "tres"))
        assertEquals(listOf(0, 6000), menos.map { it.desdeMs })
        // Más textos que frases: los sobrantes con la última. Los vacíos no cuentan.
        val mas = MotorGoogle.casar(tanda, listOf("a", "", "b", "c", "d"))
        assertEquals(listOf(0, 6000, 12000, 12000), mas.map { it.desdeMs })
        assertTrue(MotorGoogle.casar(tanda, listOf("", " ")).isEmpty())
        // Cada texto acaba donde acaba la última frase que le toca.
        assertEquals(listOf(5000, 11000, 18000), uno.map { it.hastaMs })
        assertEquals(listOf(5000, 18000), menos.map { it.hastaMs })
    }

    /** Los grupos de frases como tramos contiguos, para comparar con los `Range` del PC. */
    private fun tramos(g: List<List<LongRange>>) = g.map { it.first().first until it.last().last + 1 }

    @Test
    fun `pasados ocho segundos la tanda se cierra en la primera pausa`() {
        // `cortes.rs` del PC (7df5532), con los mismos números.
        val trozos = listOf(frase(0, 5), frase(5, 9), frase(9, 13))
        val pausas = listOf(5 * seg, 9 * seg)
        // La pausa de los 5 s llega antes de los 8: no cierra.
        assertEquals(listOf(frase(0, 9), frase(9, 13)), tramos(Transcriptor.agruparEnPausas(trozos, 15, 8, pausas)))
        // Sin pausas, todo cabe en 15 s; y una pausa de antes de los 8 s no cierra luego donde
        // no se calló.
        assertEquals(listOf(frase(0, 13)), tramos(Transcriptor.agruparEnPausas(trozos, 15, 8, emptyList())))
        assertEquals(listOf(frase(0, 13)), tramos(Transcriptor.agruparEnPausas(trozos, 15, 8, pausas.take(1))))
    }

    /** `tono` y `silencio` de las pruebas de `cortes.rs` del PC, en un PCM de 16 bits. */
    private fun tono(segundos: Float) = ShortArray((segundos * Transcriptor.HERCIOS).toInt()) { (kotlin.math.sin(it * 0.2f) * 8_000f).toInt().toShort() }
    private fun silencio(segundos: Float) = ShortArray((segundos * Transcriptor.HERCIOS).toInt())
    private fun pcm(muestras: ShortArray): java.io.File = java.io.File.createTempFile("tandas", ".raw").apply {
        deleteOnExit()
        val b = ByteArray(muestras.size * 2)
        for (i in muestras.indices) { b[2 * i] = (muestras[i].toInt() and 0xFF).toByte(); b[2 * i + 1] = (muestras[i].toInt() shr 8).toByte() }
        writeBytes(b)
    }

    @Test
    fun `una clase seguida sale en tandas de quince segundos como mucho`() {
        // 4 s, pausa, 5 s, pausa, 4 s: cabría en una tanda de 15 s, pero pasados los 8 se
        // cierra en la pausa.
        val t = Transcriptor.tandasDeLineas(pcm(tono(4f) + silencio(0.6f) + tono(5f) + silencio(0.6f) + tono(4f)))
        assertEquals("$t", 2, t.size)
        // Un minuto sin respirar: ninguna tanda pasa de 15 s.
        val largo = pcm(tono(60f))
        val l = tramos(Transcriptor.tandasDeLineas(largo))
        assertTrue("$l", l.size >= 4)
        assertTrue("$l", l.all { it.last + 1 - it.first <= Transcriptor.TANDA_SEGUNDOS * seg })
        assertEquals(largo.length(), l.last().last + 1)
    }
}
