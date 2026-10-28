package com.forge.pixpin.audio

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/**
 * **El respaldo WAV de la nota** (28-oct-2026): los casos de `respaldo.rs` del PC (cerrado, cortado a
 * mitad de una muestra, volcado al disco sin cerrar, lo que no es un WAV) y la cuenta del tiempo al
 * rehacer la nota.
 */
class RespaldoWavTest {

    private val dir: File = Files.createTempDirectory("pixpin-respaldo").toFile()

    @After
    fun limpiar() {
        dir.deleteRecursively()
    }

    @Test
    fun `el respaldo va al lado del destino con su nombre entero`() {
        assertEquals(File("/p/voz-1.m4a.respaldo.wav"), RespaldoWav.rutaDelRespaldo(File("/p/voz-1.m4a")))
    }

    @Test
    fun `la cabecera es la de un WAV de 16 bits y sin fin mientras se graba`() {
        val c = RespaldoWav.cabecera(22_050, 1, RespaldoWav.SIN_FIN)
        assertEquals("RIFF", String(c, 0, 4))
        assertEquals("WAVE", String(c, 8, 4))
        assertEquals("fmt ", String(c, 12, 4))
        assertEquals("data", String(c, 36, 4))
        // «Sin fin» en los dos tamaños.
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), c.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), c.copyOfRange(40, 44))
        // 22 050 Hz, mono: 44 100 bytes por segundo, 2 por marco, 16 bits.
        assertArrayEquals(byteArrayOf(0x22, 0x56, 0, 0), c.copyOfRange(24, 28))
        assertArrayEquals(byteArrayOf(0x44, 0xAC.toByte(), 0, 0), c.copyOfRange(28, 32))
        assertArrayEquals(byteArrayOf(2, 0, 16, 0), c.copyOfRange(32, 36))
        // Cerrado con 144 000 bytes: RIFF 144 036.
        val b = RespaldoWav.cabecera(48_000, 1, 144_000)
        assertArrayEquals(byteArrayOf(0xA4.toByte(), 0x32, 0x02, 0), b.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x32, 0x02, 0), b.copyOfRange(40, 44))
    }

    @Test
    fun `un respaldo cerrado se lee entero y con su cabecera buena`() {
        val ruta = File(dir, "a.wav")
        val r = RespaldoWav.Respaldo(ruta, 48_000, 1)
        val tono = ShortArray(48_000) { ((it % 300) - 150).toShort() }
        assertTrue(r.escribir(tono))
        assertTrue(r.escribir(tono, 24_000))
        assertEquals(72_000L, r.marcos)
        assertEquals(72_000L, r.cerrar())

        val w = RespaldoWav.leer(ruta)
        assertEquals(48_000, w.muestreo)
        assertEquals(1, w.canales)
        assertEquals(RespaldoWav.BYTES_CABECERA, w.inicio)
        assertEquals(72_000L, w.marcos)
        assertEquals(1_500L, w.duracionMs())
        // La cabecera dice lo que hay (la lee cualquiera).
        val bytes = ruta.readBytes()
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x32, 0x02, 0), bytes.copyOfRange(40, 44))

        val leidas = ArrayList<Short>()
        RespaldoWav.recorrer(ruta, w, 10_000) { m, n -> for (i in 0 until n) leidas += m[i] }
        assertEquals(72_000, leidas.size)
        assertEquals(tono.toList(), leidas.subList(0, 48_000))
        assertEquals(1_500L, RespaldoWav.duracionMs(ruta))
    }

    @Test
    fun `un respaldo cortado sin cerrar se lee hasta donde llego`() {
        // **El corte**: Android mata la app y nadie llama a `cerrar`. La cabecera sigue diciendo «sin
        // fin» y el último marco puede quedar a medias.
        val ruta = File(dir, "b.wav")
        val r = RespaldoWav.Respaldo(ruta, 48_000, 2)
        r.escribir(ShortArray(2 * 4_800) { 7 })
        r.volcar()
        r.soltarSinCerrar()
        // Un byte suelto de más, como si se cortara a mitad de una muestra.
        FileOutputStream(ruta, true).use { it.write(byteArrayOf(1, 2, 3)) }

        val w = RespaldoWav.leer(ruta)
        assertEquals(2, w.canales)
        assertEquals("el marco a medias no cuenta", 4_800L, w.marcos)
        assertEquals(100L, w.duracionMs())
    }

    @Test
    fun `el respaldo se vuelca al disco cada pocos segundos`() {
        val ruta = File(dir, "c.wav")
        val r = RespaldoWav.Respaldo(ruta, 8_000, 1)
        // Más de un volcado (5 s a 8 kHz son 40 000 marcos), sin cerrar ni vaciar el búfer.
        repeat(50) { r.escribir(ShortArray(1_000) { 3 }) }
        // Lo de antes del último volcado ya está en el fichero aunque el búfer no se vacíe nunca.
        val w = RespaldoWav.leer(ruta)
        assertTrue(w.toString(), w.marcos >= 40_000)
        assertTrue(w.toString(), w.marcos < 50_000)
    }

    @Test
    fun `lo que no es un WAV de 16 bits no se lee`() {
        val ruta = File(dir, "d.wav")
        ruta.writeText("esto no es un wav de nada")
        assertLanza { RespaldoWav.leer(ruta) }
        // Caso negativo: un WAV de 8 bits.
        val c = RespaldoWav.cabecera(8_000, 1, 0)
        c[34] = 8
        ruta.writeBytes(c)
        assertLanza { RespaldoWav.leer(ruta) }
        // Y uno vacío pero bueno vale, con cero marcos.
        ruta.writeBytes(RespaldoWav.cabecera(8_000, 1, 0))
        assertEquals(0L, RespaldoWav.leer(ruta).marcos)
        assertLanza { RespaldoWav.leer(File(dir, "no-esta.wav")) }
        assertNull(RespaldoWav.duracionMs(File(dir, "no-esta.wav")))
    }

    @Test
    fun `con el disco lleno no hay respaldo pero tampoco se para nada`() {
        if (!File("/dev/full").exists()) return
        // Lleno al empezar: no se crea (el grabador sigue sin él, ver `Grabador.Realzado.empezar`).
        assertLanza { RespaldoWav.Respaldo(File("/dev/full"), 8_000, 1) }
        assertNull(runCatching { RespaldoWav.EnHilo(File("/dev/full"), 8_000) }.getOrNull())
    }

    @Test
    fun `un respaldo ya soltado no escribe mas ni lanza`() {
        val ruta = File(dir, "soltado.wav")
        val r = RespaldoWav.Respaldo(ruta, 8_000, 1)
        assertTrue(r.escribir(ShortArray(8_000) { 1 }))
        r.soltarSinCerrar()
        // Lo que llegue tarde del micrófono no rompe nada ni se cuenta.
        r.escribir(ShortArray(10))
        assertEquals(8_000L, r.cerrar())
        assertEquals(8_000L, RespaldoWav.leer(ruta).marcos)
    }

    @Test
    fun `el respaldo en su hilo escribe todo antes de cerrar`() {
        val ruta = File(dir, "e.wav")
        val r = RespaldoWav.EnHilo(ruta, 22_050)
        val bloque = ShortArray(2_205) { (it % 100).toShort() }
        repeat(100) { r.escribir(bloque, bloque.size) }
        assertEquals(220_500L, r.cerrar())
        assertFalse(r.callado)
        assertEquals(10_000L, RespaldoWav.leer(ruta).duracionMs())
    }

    @Test
    fun `el tiempo de cada bloque al rehacer la nota`() {
        assertEquals(0L, RespaldoWav.microsegundos(0, 22_050))
        assertEquals(100_000L, RespaldoWav.microsegundos(2_205, 22_050))
        // Una hora a 22 050 Hz: 3 600 s exactos, sin desbordarse.
        assertEquals(3_600_000_000L, RespaldoWav.microsegundos(22_050L * 3_600, 22_050))
        assertEquals(0L, RespaldoWav.microsegundos(1_000, 0))
    }

    private fun assertLanza(bloque: () -> Unit) {
        try {
            bloque()
            fail("tenía que lanzar")
        } catch (_: IOException) {
        }
    }
}
