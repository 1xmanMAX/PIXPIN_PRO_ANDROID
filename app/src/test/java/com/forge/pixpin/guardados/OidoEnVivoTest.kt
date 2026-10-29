package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las frases de la escucha en vivo** (29-oct-2026): las pruebas de `en_vivo.rs` de `pixpin-voz` del PC
 * (cortar al callarse, la tos, quien no para, la medida de Whisper, huecos, 44,1/48 kHz a 16 kHz, la cola
 * y el freno), sin sonido ni modelo.
 */
class OidoEnVivoTest {

    private fun marcos(ms: Long, hz: Int) = (ms * hz / 1000).toInt()

    private fun tono(ms: Long, hz: Int, a: Double): ShortArray =
        ShortArray(marcos(ms, hz)) { i -> (a * Math.sin(i * 440.0 * 2 * Math.PI / hz)).toInt().toShort() }

    private fun callado(ms: Long, hz: Int) = ShortArray(marcos(ms, hz))

    private operator fun ShortArray.plus(o: ShortArray): ShortArray = ShortArray(size + o.size).also {
        System.arraycopy(this, 0, it, 0, size); System.arraycopy(o, 0, it, size, o.size)
    }

    /** Mete [pcm] en bloques de 10 ms desde [desde] y devuelve lo que salió y por dónde va. */
    private fun enBloques(f: OidoEnVivo.Frasero, pcm: ShortArray, hz: Int, desde: Long): Pair<List<OidoEnVivo.Frase>, Long> {
        val n = marcos(10, hz)
        var pos = desde
        val v = ArrayList<OidoEnVivo.Frase>()
        var i = 0
        while (i < pcm.size) {
            val b = pcm.copyOfRange(i, minOf(i + n, pcm.size))
            v += f.meter(pos, hz, b)
            pos += b.size
            i += n
        }
        return v to pos
    }

    @Test
    fun `corta la frase en cuanto se calla`() {
        val hz = 48_000
        val f = OidoEnVivo.Frasero(OidoEnVivo.PARA_FRASES)
        val pcm = callado(2_000, hz) + tono(2_000, hz, 8_000.0) + callado(1_000, hz) + tono(3_000, hz, 8_000.0) + callado(1_000, hz)
        val (v, _) = enBloques(f, pcm, hz, 0)
        assertEquals(v.map { it.ms }.toString(), 2, v.size)
        assertEquals(0L, v[0].n)
        assertTrue("${v[0].ms}", v[0].ms in 1_690..1_710)
        assertTrue("${v[0].finVozMs}", v[0].finVozMs in 3_990..4_010)
        assertTrue("${v[0].calladoAlFinalMs}", v[0].calladoAlFinalMs in 440..470)
        assertTrue("${v[0].duracionMs()}", v[0].duracionMs() in 2_450..2_550)
        assertEquals(1L, v[1].n)
        assertTrue("${v[1].finVozMs}", v[1].finVozMs in 7_990..8_010)
        assertTrue(v[1].ms > v[0].finVozMs && v[1].ms < 5_000)
        assertNull("lo que queda es silencio", f.acabar())
    }

    @Test
    fun `una tos no es una frase y el silencio no se guarda`() {
        val hz = 16_000
        val f = OidoEnVivo.Frasero(OidoEnVivo.PARA_FRASES)
        val pcm = callado(30_000, hz) + tono(200, hz, 8_000.0) + callado(3_000, hz)
        val (v, _) = enBloques(f, pcm, hz, 0)
        assertTrue(v.isEmpty())
        assertTrue("${f.largo}", f.largo < marcos(2_000, hz))
        assertNull(f.acabar())
    }

    @Test
    fun `quien no para de hablar se corta en lo mas callado`() {
        val hz = 16_000
        val f = OidoEnVivo.Frasero(OidoEnVivo.PARA_FRASES)
        val pcm = tono(7_000, hz, 8_000.0) + callado(200, hz) + tono(5_000, hz, 8_000.0)
        val (v, _) = enBloques(f, pcm, hz, 0)
        assertEquals(1, v.size)
        val d = v[0].duracionMs()
        assertTrue("$d", d in 7_000 until 7_200)
        val r = f.acabar()!!
        assertEquals(1L, r.n)
        assertEquals(d, r.ms)
    }

    @Test
    fun `para whisper espera cinco segundos salvo que se acabe el turno`() {
        val hz = 16_000
        val f = OidoEnVivo.Frasero(OidoEnVivo.PARA_WHISPER)
        var pcm = tono(2_000, hz, 8_000.0) + callado(600, hz) + tono(3_000, hz, 8_000.0) + callado(600, hz)
        val (v, pos) = enBloques(f, pcm, hz, 0)
        assertEquals(1, v.size)
        assertEquals(0L, v[0].ms)
        assertTrue("${v[0].finVozMs}", v[0].finVozMs in 5_590..5_610)
        pcm = tono(1_500, hz, 8_000.0) + callado(2_000, hz)
        val (w, _) = enBloques(f, pcm, hz, pos)
        assertEquals(1, w.size)
        assertEquals(OidoEnVivo.SILENCIO_LARGO_MS, w[0].calladoAlFinalMs)
    }

    @Test
    fun `un hueco corto es silencio y uno largo cierra la frase`() {
        val hz = 16_000
        var f = OidoEnVivo.Frasero(OidoEnVivo.PARA_FRASES)
        val s3 = tono(3_000, hz, 8_000.0)
        assertTrue(f.meter(0, hz, s3).isEmpty())
        val v = f.meter(3_000L * 16 + 160_000, hz, s3)
        assertEquals(1, v.size)
        assertEquals(0L to 3_000L, v[0].ms to v[0].finVozMs)
        assertEquals(13_000L, f.acabar()!!.ms)
        // Un bloque repetido (solapado) no se cuenta dos veces.
        f = OidoEnVivo.Frasero(OidoEnVivo.PARA_FRASES)
        f.meter(0, hz, s3)
        f.meter(0, hz, s3)
        assertEquals(3_000L, f.acabar()!!.duracionMs())
    }

    @Test
    fun `de 44100 a 16000 sin perder el reloj`() {
        val r = OidoEnVivo.a16k(ShortArray(44_100) { 1000 }, hz = 44_100)
        assertEquals(16_000, r.size)
        assertTrue(r.all { it.toInt() == 1000 })
        assertEquals(16_000, OidoEnVivo.a16k(ShortArray(48_000), hz = 48_000).size)
        assertEquals(listOf<Short>(5, 6), OidoEnVivo.a16k(shortArrayOf(5, 6), hz = 16_000).toList())
    }

    @Test
    fun `la cola tira lo mas viejo`() {
        val c = OidoEnVivo.Cola<Int>(2)
        assertNull(c.meter(1))
        assertNull(c.meter(2))
        assertEquals(1, c.meter(3))
        assertEquals(1L, c.tirados)
        assertEquals(2, c.sacar())
        assertEquals(3, c.sacar())
        assertEquals(0, c.tamano)
    }

    @Test
    fun `el freno`() {
        val voz = tono(3_000, 16_000, 6_000.0)
        val calla = tono(3_000, 16_000, 50.0)
        val f = OidoEnVivo.Freno()
        assertEquals(OidoEnVivo.Paso.SALTAR_SILENCIO, f.decidir(calla, false))
        assertEquals(OidoEnVivo.Paso.SALTAR_APARATO, f.decidir(voz, true))
        assertEquals(OidoEnVivo.Paso.PASAR, f.decidir(voz, false))
        // Tardó 2,6 s en 3: se descansa una, y solo una.
        f.tardo(3_000, 2_600)
        assertEquals(OidoEnVivo.Paso.SALTAR_RETRASO, f.decidir(voz, false))
        assertEquals(OidoEnVivo.Paso.PASAR, f.decidir(voz, false))
        f.tardo(3_000, 300)
        assertEquals(OidoEnVivo.Paso.PASAR, f.decidir(voz, false))
    }
}
