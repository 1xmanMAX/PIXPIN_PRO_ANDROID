package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.VoskEnVivo.FraseOida
import com.forge.pixpin.guardados.VoskEnVivo.Oido
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Vosk en vivo, sin Vosk** (1-nov-2026): las pruebas de `vosk_vivo.rs` de `pixpin-voz` del PC (43910f8a)
 * con un reconocedor de mentira: el minuto de cada frase, el parcial que no se repite, quien habla de
 * corrido, los huecos y lo que Vosk devuelve raro.
 */
class VoskEnVivoTest {

    /**
     * Un reconocedor de mentira: lo que tiene voz es una palabra; cierra la frase en el primer trozo callado
     * si [cierraSolo], y si no solo cuando se le pide ([acabar]). Los tiempos cuentan desde que se creó.
     */
    private class DeMentira(val cierraSolo: Boolean) : VoskEnVivo.Reconocedor {
        var dado = 0
        var voz: Pair<Int, Int>? = null
        var cerrada: Pair<Int, Int>? = null
        var parciales = 0

        private fun json(v: Pair<Int, Int>?): String {
            if (v == null) return """{"text":""}"""
            val (a, b) = v
            fun s(m: Int) = m / 16_000.0
            val t = (b - a) / 3
            return """{"result":[{"word":"hola","start":${s(a)},"end":${s(a + t)}},{"word":"que","start":${s(a + t)},"end":${s(a + 2 * t)}},""" +
                """{"word":"tal","start":${s(a + 2 * t)},"end":${s(b)}}],"text":"hola que tal"}"""
        }

        override fun comer(muestras: ShortArray): Boolean {
            val hayVoz = muestras.any { kotlin.math.abs(it.toInt()) > 1000 }
            val desde = dado
            dado += muestras.size
            return if (hayVoz) {
                val a = voz?.first ?: desde
                voz = a to dado
                false
            } else if (cierraSolo && voz != null) {
                cerrada = voz; voz = null
                true
            } else false
        }

        override fun resultado(): String = json(cerrada).also { cerrada = null }
        override fun parcial(): String { parciales++; return if (voz != null) """{"partial":"hola que"}""" else """{"partial":""}""" }
        override fun acabar(): String = json(voz).also { voz = null }
    }

    /** [ms] de 48 kHz: voz (un seno fuerte) o silencio. */
    private fun bloque(ms: Long, voz: Boolean): ShortArray =
        ShortArray((ms * 48).toInt()) { i -> if (voz) (8_000.0 * Math.sin(i * 0.06)).toInt().toShort() else 0 }

    /** Mete [pcm] en bloques de 10 ms desde [desde] (marcos a 48 kHz). */
    private fun enBloques(c: VoskEnVivo.Corriente, pcm: ShortArray, desde: Long): Pair<List<Oido>, Long> {
        var pos = desde
        val v = ArrayList<Oido>()
        var i = 0
        while (i < pcm.size) {
            val b = pcm.copyOfRange(i, minOf(pcm.size, i + 480))
            v += c.meter(pos, 48_000, b)
            pos += b.size
            i += 480
        }
        return v to pos
    }

    private fun frases(v: List<Oido>): List<FraseOida> = v.filterIsInstance<Oido.Frase>().map { it.frase }

    @Test
    fun `la frase sale con su minuto de la grabacion y antes el parcial`() {
        val c = VoskEnVivo.Corriente(DeMentira(cierraSolo = true), "es-ES")
        // La grabación ya iba por el segundo 10 cuando empezó a oírse.
        val pcm = bloque(1_000, false) + bloque(1_500, true) + bloque(600, false)
        val (v, _) = enBloques(c, pcm, 480_000)
        val f = frases(v)
        assertEquals(v.toString(), 1, f.size)
        assertEquals(0L, f[0].n)
        assertTrue("${f[0].ms}", f[0].ms in 10_990..11_010)
        assertTrue("${f[0].finVozMs}", f[0].finVozMs in 12_490..12_510)
        assertTrue("${f[0].calladoAlFinalMs}", f[0].calladoAlFinalMs <= 110)
        assertEquals("hola que tal", f[0].texto)
        // Mientras hablaba, el parcial; y al cerrar, vacío.
        val i = v.indexOfFirst { it is Oido.Frase }
        assertTrue(v.subList(0, i).contains(Oido.Parcial("hola que")))
        assertEquals(Oido.Parcial(""), v[i - 1])
    }

    @Test
    fun `el mismo parcial no se repite`() {
        val rec = DeMentira(cierraSolo = true)
        val c = VoskEnVivo.Corriente(rec, "es-ES")
        val (v, _) = enBloques(c, bloque(3_000, true), 0)
        assertEquals(v.toString(), 1, v.count { it is Oido.Parcial && it.texto.isNotEmpty() })
        // Se pidió cada 200 ms, no en cada bloque de 10 ms.
        assertTrue("${rec.parciales}", rec.parciales in 13..16)
    }

    @Test
    fun `quien habla de corrido se corta en la primera pausa tras el largo`() {
        // Vosk no cierra solo: lo cierra la corriente.
        var c = VoskEnVivo.Corriente(DeMentira(cierraSolo = false), "es-ES")
        // 1 s y una pausa corta (aún no es largo), 4,5 s y otra pausa (ya sí), 2 s y silencio.
        val pcm = bloque(1_000, true) + bloque(300, false) + bloque(4_500, true) +
            bloque(300, false) + bloque(2_000, true) + bloque(1_000, false)
        val (v0, _) = enBloques(c, pcm, 0)
        assertEquals(v0.toString(), 1, frases(v0).size)
        val v = v0 + c.acabar()
        val f = frases(v)
        assertEquals(2, f.size)
        // La primera acaba en la segunda pausa, no en la primera.
        assertTrue("${f[0].finVozMs}", f[0].finVozMs in 5_790..5_810)
        assertTrue("${f[1].ms}", f[1].ms in 6_090..6_110)
        // Caso negativo: sin pausa, se corta igual pasado el máximo.
        c = VoskEnVivo.Corriente(DeMentira(cierraSolo = false), "es-ES")
        val (v2, _) = enBloques(c, bloque(12_000, true), 0)
        val f2 = frases(v2)
        assertEquals(v2.toString(), 1, f2.size)
        assertTrue(f2[0].finVozMs - f2[0].ms <= VoskEnVivo.MAXIMO_MS + 500)
    }

    @Test
    fun `un hueco largo cierra la frase y la cuenta sigue en su minuto`() {
        val c = VoskEnVivo.Corriente(DeMentira(cierraSolo = true), "es-ES")
        val (v0, pos) = enBloques(c, bloque(1_000, true), 0)
        assertTrue(frases(v0).isEmpty())
        // Se pierden 3 s (el teléfono iba justo): lo dicho se cierra ahí.
        val (v, _) = enBloques(c, bloque(800, true) + bloque(500, false), pos + 3 * 48_000)
        val f = frases(v)
        assertEquals(v.toString(), 2, f.size)
        assertTrue(f[0].ms <= 10 && f[0].finVozMs in 990..1_010)
        // La segunda empieza en el segundo 4 de la grabación, no en el 1.
        assertTrue("${f[1].ms}", f[1].ms in 3_990..4_010)
        assertEquals(1L, f[1].n)
    }

    @Test
    fun `un hueco corto se rellena y no mueve el reloj`() {
        val c = VoskEnVivo.Corriente(DeMentira(cierraSolo = true), "es-ES")
        val (_, pos) = enBloques(c, bloque(1_000, false), 0)
        // 300 ms perdidos, luego voz y silencio.
        val (v, _) = enBloques(c, bloque(1_000, true) + bloque(500, false), pos + 300 * 48)
        val f = frases(v)
        assertEquals(1, f.size)
        assertTrue("${f[0].ms}", f[0].ms in 1_290..1_310)
    }

    @Test
    fun `lo repetido no se oye dos veces`() {
        val c = VoskEnVivo.Corriente(DeMentira(cierraSolo = true), "es-ES")
        val (_, pos) = enBloques(c, bloque(1_000, true), 0)
        // El mismo último medio segundo otra vez, y luego silencio: una sola frase, que acaba en el 1.
        val (v, _) = enBloques(c, bloque(500, true) + bloque(600, false), pos - 500 * 48)
        val f = frases(v)
        assertEquals(v.toString(), 1, f.size)
        assertTrue("${f[0].finVozMs}", f[0].finVozMs in 990..1_010)
    }

    @Test
    fun `al acabar sale lo que quedaba a medias`() {
        val c = VoskEnVivo.Corriente(DeMentira(cierraSolo = true), "es-ES")
        val (v, _) = enBloques(c, bloque(1_250, true), 0)
        assertTrue(frases(v).isEmpty())
        assertEquals(1, frases(c.acabar()).size)
        // Caso negativo: acabar otra vez no repite nada.
        assertTrue(frases(c.acabar()).isEmpty())
    }

    @Test
    fun `lo que no es creible se tira y se cuenta`() {
        val c = VoskEnVivo.Corriente(DeMentira(cierraSolo = true), "es-ES", creible = { _, _ -> false })
        val (v, _) = enBloques(c, bloque(1_000, true) + bloque(500, false), 0)
        assertTrue(frases(v).isEmpty())
        assertEquals(1L, c.descartadas)
    }

    @Test
    fun `lo que vosk devuelve raro no tumba nada`() {
        assertNull(VoskEnVivo.leerResultado("no es json"))
        assertNull(VoskEnVivo.leerResultado("""{"text":""}"""))
        assertNull(VoskEnVivo.leerResultado("""{"text":"  "}"""))
        assertEquals(VoskEnVivo.Leido("sin tiempos", null, null), VoskEnVivo.leerResultado("""{"text":"sin tiempos"}"""))
        val l = VoskEnVivo.leerResultado(
            """{"result":[{"conf":1.0,"end":1.2,"start":0.9,"word":"a"},{"conf":0.8,"end":2.05,"start":1.5,"word":"b"}],"text":"a b"}"""
        )!!
        assertEquals(900L to 2_050L, l.inicioMs to l.finMs)
        assertEquals("va oyendo", VoskEnVivo.textoDelParcial("""{"partial" : "va oyendo "}"""))
        assertEquals("", VoskEnVivo.textoDelParcial("{}"))
        assertEquals("", VoskEnVivo.textoDelParcial("roto"))
    }

    @Test
    fun `el modelo en vivo solo con el de su idioma`() {
        assertTrue(MotorVosk.tieneModelo("es-PE"))
        assertTrue(MotorVosk.tieneModelo("en"))
        // Caso negativo: el de español de reserva no vale para oír una reunión en japonés.
        assertTrue(!MotorVosk.tieneModelo("ja-JP"))
    }
}
