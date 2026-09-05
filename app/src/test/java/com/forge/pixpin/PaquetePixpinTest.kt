package com.forge.pixpin.motor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **El paquete `.pixpin`: lo que se escribe se vuelve a abrir, en otro sitio y con otros ids.**
 *
 * Un proyecto con un lienzo que lleva una foto, una nota y un PDF se escribe, se abre como
 * si llegara de otro aparato y se importa: tiene que salir un proyecto nuevo —ids nuevos, la
 * foto copiada y con su ruta de aquí, el PDF copiado— con lo mismo dentro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaquetePixpinTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `ida y vuelta de un proyecto con lienzo, foto, nota y pdf`() {
        val foto = File(context.cacheDir, "foto.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val archivoDeFoto = ExcalidrawStore.guardarImagen(context, foto, "image/jpeg")!!
        val escena = Scene(
            elements = listOf(
                Element(id = "i", type = ElementType.IMAGE, x = 0.0, y = 0.0, width = 10.0, height = 10.0, seed = 1, fileId = archivoDeFoto.id),
                Element(id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0, width = 5.0, height = 5.0, seed = 2)
            ),
            files = mapOf(archivoDeFoto.id to archivoDeFoto)
        )
        assertNotNull(ExcalidrawStore.guardar(context, "dib1", escena))
        val pdf = File(context.cacheDir, "doc.pdf").also { it.writeBytes("%PDF-1.4 falso".toByteArray()) }
        val proyecto = Proyecto(
            id = "pr1", nombre = "Casa", tocado = 5,
            hojas = listOf(
                Hoja(id = "h1", nombre = "Planta", dibujo = "dib1", pagina = 0),
                Hoja(id = "h2", nombre = "Notas", nota = "# Obra\nHola")
            ),
            pdfOrigen = pdf.absolutePath, pdfLimpio = pdf.absolutePath
        )

        val paquete = PaquetePixpin.escribir(context, proyecto, File(context.cacheDir, "casa.pixpin"), escrito = 1L)
        assertNotNull("no se escribió", paquete)
        val entradas = java.util.zip.ZipFile(paquete!!).use { z -> z.entries().toList().map { it.name }.toSet() }
        assertTrue(entradas.toString(), "manifest.json" in entradas)
        assertTrue("proyecto.json" in entradas)
        assertTrue("lienzos/dib1.excalidraw" in entradas)
        assertTrue("imagenes/${archivoDeFoto.id}" in entradas)
        assertTrue("notas/h2.md" in entradas)
        assertTrue("documento.pdf" in entradas)

        val contenido = PaquetePixpin.abrir(paquete)!!
        assertEquals("Casa", contenido.proyecto.nombre)
        assertNull("las rutas del aparato no viajan", contenido.proyecto.pdfOrigen)
        assertTrue("la foto va por su id, no por su ruta", contenido.lienzos["dib1"]!!.contains("imagenes/"))

        val nuevo = PaquetePixpin.importar(context, contenido, ahora = 99, carpetaDeProyectos = File(context.cacheDir, "proyectos"))!!
        assertEquals("pr-99", nuevo.id)
        assertEquals("Casa", nuevo.nombre)
        assertEquals(2, nuevo.hojas.size)
        assertEquals("dib1-99", nuevo.hojas[0].dibujo)
        assertEquals("# Obra\nHola", nuevo.hojas[1].nota)
        assertTrue("el pdf tenía que copiarse", File(nuevo.pdfOrigen!!).exists())
        val traida = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, "dib1-99"))!!
        assertEquals(2, traida.elements.size)
        val rutaDeLaFoto = traida.files.values.single().path!!
        assertTrue("la foto tenía que estar en este aparato: ${rutaDeLaFoto}", File(rutaDeLaFoto).exists())
        assertEquals(4, File(rutaDeLaFoto).length())
    }

    @Test
    fun `un zip cualquiera no es un paquete`() {
        val cualquiera = File(context.cacheDir, "otro.zip")
        java.util.zip.ZipOutputStream(cualquiera.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("hola.txt")); z.write(1); z.closeEntry()
        }
        assertNull(PaquetePixpin.abrir(cualquiera))
    }
}
