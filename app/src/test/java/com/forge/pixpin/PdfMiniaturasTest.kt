package com.forge.pixpin.motor

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La caché de miniaturas, por su parte comprobable: **cuándo aparece un archivo
 * con el nombre bueno**.
 *
 * De ahí venía el «unas páginas del PDF salen y otras no». Escribiendo
 * directamente sobre el nombre definitivo hay un rato —el que tarda
 * `Bitmap.compress` en soltar sus trozos— en el que el archivo ya existe y no
 * tiene dentro la imagen. Durante ese rato, el que lee saca media página y la
 * mete en la caché de memoria encima de la buena, y el que prepara la tanda da
 * la página por hecha y no la vuelve a dibujar. Si además el proceso muere
 * justo ahí, el archivo a medias se queda en el disco para siempre.
 *
 * Nada de esto necesita un `Bitmap` ni un dispositivo: es una pregunta sobre
 * archivos, y por eso [escribirDeGolpe] vive fuera del objeto y se comprueba
 * aquí. Lo que se mira siempre es lo mismo — **desde fuera solo puede verse
 * «nada» o «la miniatura entera»**, nunca un estado intermedio.
 */
class PdfMiniaturasTest {

    private fun carpeta(): File =
        File.createTempFile("miniaturas", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }

    /** Lo que quede suelto por ahí también es un fallo: nadie lo va a recoger. */
    private fun sueltos(carpeta: File, salvo: File): List<String> =
        carpeta.listFiles().orEmpty().filter { it != salvo }.map { it.name }

    @Test
    fun `mientras se escribe, el nombre bueno todavia no existe`() {
        val dir = carpeta()
        val destino = File(dir, "pagina-7")
        val vistoAMedias = mutableListOf<Long>()

        val pudo = escribirDeGolpe(destino) { salida ->
            // Tres trozos, como los dieciséis kilobytes de `Bitmap.compress`.
            repeat(3) {
                salida.write(ByteArray(1000) { 7 })
                salida.flush()
                // Esto es exactamente lo que hacía el lector de al lado.
                vistoAMedias += destino.length()
            }
            true
        }

        assertTrue(pudo)
        // En ningún momento del camino había nada que leer con ese nombre.
        assertEquals(listOf(0L, 0L, 0L), vistoAMedias)
        // Y al acabar está entera.
        assertEquals(3000L, destino.length())
        assertArrayEquals(ByteArray(3000) { 7 }, destino.readBytes())
        assertEquals(emptyList<String>(), sueltos(dir, destino))
    }

    /**
     * `Bitmap.compress` devuelve `false` sin avisar de otra forma —WEBP no
     * admite cualquier tamaño—, y aquello dejaba un archivo de cero bytes que
     * para [PdfMiniaturas.preparar] era una miniatura terminada. Esa página no
     * se dibujaba nunca más.
     */
    @Test
    fun `si el contenido no pudo escribirse no queda archivo alguno`() {
        val dir = carpeta()
        val destino = File(dir, "pagina-12")

        val pudo = escribirDeGolpe(destino) { salida ->
            salida.write(ByteArray(500))
            false
        }

        assertFalse(pudo)
        assertFalse("no puede quedar un archivo vacío haciéndose pasar por hecho", destino.exists())
        assertEquals(emptyList<String>(), dir.listFiles().orEmpty().map { it.name })
    }

    /** Disco lleno o permisos raros: va más lento, no se rompe. */
    @Test
    fun `si la escritura revienta no se deja nada por medio`() {
        val dir = carpeta()
        val destino = File(dir, "pagina-3")

        val pudo = escribirDeGolpe(destino) { salida ->
            salida.write(ByteArray(100))
            throw IOException("disco lleno")
        }

        assertFalse(pudo)
        assertFalse(destino.exists())
        assertEquals(emptyList<String>(), dir.listFiles().orEmpty().map { it.name })
    }

    /**
     * Una miniatura vieja no puede quedarse a medio sustituir: si la de antes
     * valía, o se cambia entera o se queda la de antes.
     */
    @Test
    fun `rehacer una miniatura sustituye la anterior de una vez`() {
        val dir = carpeta()
        val destino = File(dir, "pagina-1")
        destino.writeBytes(ByteArray(4000) { 1 })

        val visto = mutableListOf<Long>()
        val pudo = escribirDeGolpe(destino) { salida ->
            salida.write(ByteArray(50) { 2 })
            salida.flush()
            visto += destino.length()
            salida.write(ByteArray(50) { 2 })
            true
        }

        assertTrue(pudo)
        // A mitad de camino seguía estando la vieja, entera.
        assertEquals(listOf(4000L), visto)
        assertArrayEquals(ByteArray(100) { 2 }, destino.readBytes())
        assertEquals(emptyList<String>(), sueltos(dir, destino))
    }

    /**
     * Dos hilos escribiendo la misma página a la vez no pueden estropearse el
     * archivo provisional el uno al otro: cada uno usa el suyo.
     */
    @Test
    fun `dos escrituras a la vez no se pisan`() {
        val dir = carpeta()
        val destino = File(dir, "pagina-9")
        val listos = java.util.concurrent.CountDownLatch(2)
        val bien = java.util.concurrent.atomic.AtomicInteger()

        val hilos = (1..2).map { n ->
            Thread {
                val ok = escribirDeGolpe(destino) { salida ->
                    salida.write(ByteArray(2000) { n.toByte() })
                    listos.countDown()
                    listos.await()
                    salida.write(ByteArray(2000) { n.toByte() })
                    true
                }
                if (ok) bien.incrementAndGet()
            }
        }
        hilos.forEach { it.start() }
        hilos.forEach { it.join() }

        assertEquals(2, bien.get())
        // Gane quien gane, lo que queda es una de las dos, entera y de una sola
        // mano: nunca una mezcla de las dos ni un trozo de cada una.
        val quedo = destino.readBytes()
        assertEquals(4000, quedo.size)
        assertTrue("salió una mezcla", quedo.all { it == quedo[0] })
        assertEquals(emptyList<String>(), sueltos(dir, destino))
    }
}
