package com.forge.pixpin.guardados.qwen3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Lo que no necesita el modelo: catálogo, lo bajado, si se ofrece, si toca y las capas del decoder (`qwen3/mod.rs`, `catalogo.rs` y `motor.rs` del PC). */
class MotorQwen3Test {

    @Test
    fun `los ficheros estan fijados al commit y traen su sha`() {
        for (f in CatalogoQwen3.FICHEROS) {
            assertEquals(f.nombre, 64, f.sha256.length)
            assertTrue(f.sha256.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue(f.url.startsWith("https://huggingface.co/"))
            assertTrue(f.nombre, f.url.contains(CatalogoQwen3.REVISION))
            assertTrue(f.nombre, f.url.endsWith(f.nombre))
            assertTrue(f.bytes > 0)
        }
        assertEquals("conv_frontend.onnx", CatalogoQwen3.FICHEROS[CatalogoQwen3.CONV].nombre)
        assertEquals("encoder.int8.onnx", CatalogoQwen3.FICHEROS[CatalogoQwen3.ENCODER].nombre)
        assertEquals("decoder.int8.onnx", CatalogoQwen3.FICHEROS[CatalogoQwen3.DECODER].nombre)
        assertEquals("vocab.json", CatalogoQwen3.FICHEROS[CatalogoQwen3.VOCAB].nombre)
        assertEquals("merges.txt", CatalogoQwen3.FICHEROS[CatalogoQwen3.MERGES].nombre)
        assertEquals("tokenizer_config.json", CatalogoQwen3.FICHEROS[CatalogoQwen3.CONFIG].nombre)
    }

    @Test
    fun `pesa casi un giga y lo pequeno va primero`() {
        assertEquals(988L, CatalogoQwen3.megas())
        assertTrue(CatalogoQwen3.FICHEROS.zipWithNext().all { (a, b) -> a.bytes <= b.bytes })
    }

    @Test
    fun `listo solo con los seis ficheros enteros`() {
        val c = File(Files.createTempDirectory("pixpin-qwen3").toFile(), "qwen3-asr").apply { mkdirs() }
        assertFalse(MotorQwen3.modeloListo(c))
        assertFalse(MotorQwen3.algoBajado(c))
        assertEquals(CatalogoQwen3.megas(), MotorQwen3.megasABajar(c))
        // Un fichero pequeño con el tamaño bueno cuenta; uno corto no.
        val f = CatalogoQwen3.FICHEROS[CatalogoQwen3.VOCAB]
        File(c, f.nombre).writeBytes(ByteArray(f.bytes.toInt()) { ' '.code.toByte() })
        assertTrue(MotorQwen3.algoBajado(c))
        assertTrue(MotorQwen3.megasABajar(c) < CatalogoQwen3.megas())
        File(c, f.nombre).writeText("{}")
        assertEquals(CatalogoQwen3.megas(), MotorQwen3.megasABajar(c))
        assertFalse(MotorQwen3.modeloListo(c))
        MotorQwen3.borrar(c)
        assertFalse(c.exists())
        MotorQwen3.borrar(c)
    }

    @Test
    fun `el sha de un fichero es el de sha256sum`() {
        val p = File.createTempFile("pixpin-qwen3-sha", ".txt").apply { deleteOnExit(); writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", MotorQwen3.sha256De(p))
    }

    @Test
    fun `se ofrece desde los seis gigas y solo en 64 bits`() {
        assertTrue(MotorQwen3.seOfrece(5_700, true))
        assertTrue("un móvil de 8 GB", MotorQwen3.seOfrece(7_600, true))
        assertFalse("uno de 4 GB", MotorQwen3.seOfrece(3_700, true))
        assertFalse("de 32 bits no carga el runtime", MotorQwen3.seOfrece(12_000, false))
    }

    @Test
    fun `cuatro hilos y preempaquetado solo con memoria de sobra`() {
        assertEquals(MotorQwen3.Reparto(4, false), MotorQwen3.reparto(5_700, 8))
        assertEquals(MotorQwen3.Reparto(4, true), MotorQwen3.reparto(11_000, 8))
        assertEquals(2, MotorQwen3.reparto(7_600, 2).hilos)
        assertEquals(1, MotorQwen3.reparto(7_600, 0).hilos)
    }

    @Test
    fun `toca solo elegido, bajado, ofrecido, con su idioma y sin traducir`() {
        assertTrue(MotorQwen3.toca(true, true, true, "es-PE", "", false))
        assertFalse("sin elegir", MotorQwen3.toca(false, true, true, "es", "", false))
        assertFalse("sin bajar", MotorQwen3.toca(true, false, true, "es", "", false))
        assertFalse("sin memoria", MotorQwen3.toca(true, true, false, "es", "", false))
        assertFalse("el catalán, con Whisper", MotorQwen3.toca(true, true, true, "ca", "", false))
        assertTrue("dos idiomas, cada uno el suyo", MotorQwen3.toca(true, true, true, "es", "en", false))
        assertFalse("todo en el primero es traducir: Whisper", MotorQwen3.toca(true, true, true, "es", "en", true))
        assertTrue("el segundo igual que el primero no cuenta", MotorQwen3.toca(true, true, true, "es", "es-PE", true))
    }

    private fun nombres(capas: Int): Pair<MutableList<String>, MutableList<String>> {
        val e = mutableListOf("input_ids", "audio_features", "attention_mask", "cache_position")
        val s = mutableListOf("logits")
        for (i in 0 until capas) { e += "cache_key_$i"; e += "cache_value_$i"; s += "key_delta_$i"; s += "value_delta_$i" }
        return e to s
    }

    @Test
    fun `las capas salen de los nombres si estan en orden`() {
        val (e, s) = nombres(28)
        assertEquals(28, MotorQwen3.capasDe(e, s))
        val (e2, s2) = nombres(28)
        java.util.Collections.swap(e2, 4, 5)
        assertNull("clave y valor cambiados", MotorQwen3.capasDe(e2, s2))
        val (e3, s3) = nombres(28)
        s3.removeAt(s3.size - 1)
        assertNull(MotorQwen3.capasDe(e3, s3))
        assertNull(MotorQwen3.capasDe(emptyList(), emptyList()))
    }

    @Test
    fun `los indices de la tabla de onnx runtime estan en orden`() {
        val t = OrtPorJna.TODOS
        assertTrue(t.toList().zipWithNext().all { (a, b) -> a < b })
        assertEquals(OrtPorJna.ADD_SESSION_CONFIG_ENTRY, t.last())
    }
}
