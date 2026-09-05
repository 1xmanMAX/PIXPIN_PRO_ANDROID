package com.forge.pixpin.motor

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las hojitas del plano guardadas en el teléfono. Ver [CuadrosEnDisco].
 *
 * Lo que se comprueba aquí es lo que hace que **un plano no se extraiga dos veces**: que cada
 * plano tenga su carpeta y no se mezcle con otro, que lo escrito se vuelva a encontrar, y que
 * cuando hay que hacer sitio se borre lo viejo y no lo que se está mirando.
 */
class CuadrosEnDiscoTest {

    private val base: File = Files.createTempDirectory("planos").toFile()

    @After
    fun limpiar() {
        base.deleteRecursively()
    }

    private fun pdf(nombre: String, bytes: Int = 100): File =
        File(base, nombre).apply { writeBytes(ByteArray(bytes)) }

    /** El nombre del archivo dice qué cuadro es, y se lee de vuelta. */
    @Test
    fun `el nombre de un cuadro va y vuelve`() {
        val c = MosaicoDePdf.Cuadro(3, 7)
        assertEquals("3_7.webp", CuadrosEnDisco.nombre(c))
        assertEquals(c, CuadrosEnDisco.cuadroDe(CuadrosEnDisco.nombre(c)))
        assertNull(CuadrosEnDisco.cuadroDe("portada.png"))
        assertNull(CuadrosEnDisco.cuadroDe("3.webp"))
    }

    /**
     * **Cada plano en su carpeta.** El mismo archivo, la misma página y la misma resolución
     * caen siempre en la misma; cualquier otra cosa, en otra.
     */
    @Test
    fun `cada plano tiene su carpeta`() {
        val a = pdf("uno.pdf")
        val suya = CuadrosEnDisco.carpetaDe(base, a, 0, 3.0)
        assertEquals(suya, CuadrosEnDisco.carpetaDe(base, a, 0, 3.0))
        assertNotEquals(suya, CuadrosEnDisco.carpetaDe(base, a, 1, 3.0))
        assertNotEquals(suya, CuadrosEnDisco.carpetaDe(base, a, 0, 6.0))
        assertNotEquals(suya, CuadrosEnDisco.carpetaDe(base, pdf("otro.pdf"), 0, 3.0))
    }

    /**
     * **Y si el PDF cambia, los cuadros viejos no se cuelan**: la seña del archivo —lo que
     * mide y cuándo se tocó— entra en el nombre de la carpeta.
     */
    @Test
    fun `un pdf que cambia estrena carpeta`() {
        val a = pdf("uno.pdf", 100)
        val antes = CuadrosEnDisco.carpetaDe(base, a, 0, 3.0)
        a.writeBytes(ByteArray(200))
        assertNotEquals(antes, CuadrosEnDisco.carpetaDe(base, a, 0, 3.0))
    }

    /** Lo escrito se encuentra, y se sabe qué hay hecho sin abrir nada. */
    @Test
    fun `lo escrito se vuelve a leer`() {
        val carpeta = CuadrosEnDisco.carpetaDe(base, pdf("uno.pdf"), 0, 3.0)
        val c = MosaicoDePdf.Cuadro(2, 5)
        assertTrue(CuadrosEnDisco.losQueHay(carpeta).isEmpty())
        assertNull(CuadrosEnDisco.leer(carpeta, c))

        val bytes = ByteArray(1024) { (it % 251).toByte() }
        assertTrue(CuadrosEnDisco.escribir(carpeta, c, bytes))
        assertArrayEquals(bytes, CuadrosEnDisco.leer(carpeta, c))
        assertEquals(setOf(c), CuadrosEnDisco.losQueHay(carpeta))
        assertEquals(1024L, CuadrosEnDisco.peso(carpeta))
        // Y no queda ningún archivo a medias de los de escribir y mover.
        assertEquals(1, carpeta.listFiles()!!.size)
    }

    /**
     * **Cuando se llena, se va lo viejo y se queda lo que se está mirando.** Aunque el que se
     * mira sea justamente el más antiguo.
     */
    @Test
    fun `al llenarse se borra lo viejo y no lo que se mira`() {
        val bytes = ByteArray(4096)
        val mirando = CuadrosEnDisco.carpetaDe(base, pdf("mirando.pdf"), 0, 3.0)
        val viejo = CuadrosEnDisco.carpetaDe(base, pdf("viejo.pdf"), 0, 3.0)
        val nuevo = CuadrosEnDisco.carpetaDe(base, pdf("nuevo.pdf"), 0, 3.0)
        for (carpeta in listOf(mirando, viejo, nuevo)) {
            CuadrosEnDisco.escribir(carpeta, MosaicoDePdf.Cuadro(0, 0), bytes)
        }
        mirando.setLastModified(1000L)
        viejo.setLastModified(2000L)
        nuevo.setLastModified(3000L)

        // Con sitio de sobra no se toca nada.
        CuadrosEnDisco.limpiar(base, mirando, techo = 100L * 1024)
        assertTrue(viejo.exists())

        // Con sitio para uno solo: se va el viejo, y el que se mira se queda aunque sea el
        // que hace más que no se tocaba.
        CuadrosEnDisco.limpiar(base, mirando, techo = 5L * 1024)
        assertTrue("el que se está mirando no se borra", mirando.exists())
        assertFalse("el más viejo se va primero", viejo.exists())
    }

    /** Un plano nuevo no hereda los cuadros de otro: la carpeta está vacía. */
    @Test
    fun `un plano nuevo empieza sin nada`() {
        val uno = CuadrosEnDisco.carpetaDe(base, pdf("uno.pdf"), 0, 3.0)
        CuadrosEnDisco.escribir(uno, MosaicoDePdf.Cuadro(0, 0), ByteArray(16))
        val otro = CuadrosEnDisco.carpetaDe(base, pdf("otro.pdf"), 0, 3.0)
        assertTrue(CuadrosEnDisco.losQueHay(otro).isEmpty())
    }

    private fun assertArrayEquals(esperado: ByteArray, salio: ByteArray?) =
        org.junit.Assert.assertArrayEquals(esperado, salio)
}
