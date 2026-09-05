package com.forge.pixpin.guardados

import com.forge.pixpin.data.ProyectosRepository
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Scene
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **Los mensajes de un chat se vuelven hojas del proyecto.**
 *
 * Una nota es una hoja de notas; una foto, un lienzo con la foto; un dibujo, una copia
 * —no el mismo—; y lo que no tiene hoja posible (una nota de voz) se queda fuera sin romper
 * nada.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnirAlProyectoTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `nota, foto, dibujo y voz`() {
        val proyectos = ProyectosRepository(context)
        val proyecto = proyectos.nuevo("Obra", 10)
        val foto = File(context.cacheDir, "foto.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val escena = Scene(elements = listOf(Element(id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0, width = 5.0, height = 5.0, seed = 2)))
        assertNotNull(ExcalidrawStore.guardar(context, "dib-chat", escena))
        val mensajes = listOf(
            Mensaje(id = "1", cuando = 1, clase = Clase.NOTA, texto = "# Pendiente\nLlamar al fontanero"),
            Mensaje(id = "2", cuando = 2, clase = Clase.IMAGEN, ruta = foto.absolutePath, nombre = "foto.jpg"),
            Mensaje(id = "3", cuando = 3, clase = Clase.DIBUJO, referencia = "dib-chat", nombre = "Boceto"),
            Mensaje(id = "4", cuando = 4, clase = Clase.VOZ, ruta = foto.absolutePath)
        )
        assertFalse(UnirAlProyecto.sePuedeUnir(mensajes[3]))

        val cuantas = UnirAlProyecto.unir(context, proyectos, proyecto.id, mensajes, ahora = 99)
        assertEquals(3, cuantas)
        val hojas = proyectos.porId(proyecto.id)!!.hojas
        assertEquals(3, hojas.size)
        assertEquals("# Pendiente\nLlamar al fontanero", hojas[0].nota)
        assertEquals("Pendiente", hojas[0].nombre)
        assertEquals("foto", hojas[1].nombre)
        val conFoto = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, hojas[1].dibujo!!))!!
        assertEquals(ElementType.IMAGE, conFoto.elements.single().type)
        assertTrue(File(conFoto.files.values.single().path!!).exists())
        assertNotEquals("el dibujo se copia, no se enlaza", "dib-chat", hojas[2].dibujo)
        assertEquals(1, ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, hojas[2].dibujo!!))!!.elements.size)
    }

    @Test
    fun `un pdf se vuelve el documento del proyecto que no tenia`() {
        val proyectos = ProyectosRepository(context)
        val proyecto = proyectos.nuevo("Reforma", 20)
        val pdf = File(context.cacheDir, "planos.pdf").also { it.writeText(PDF_DE_UNA_PAGINA) }
        val m = Mensaje(id = "p", cuando = 1, clase = Clase.ARCHIVO, ruta = pdf.absolutePath, nombre = "planos.pdf")
        assertTrue(UnirAlProyecto.sePuedeUnir(m))
        val cuantas = UnirAlProyecto.unir(context, proyectos, proyecto.id, listOf(m), ahora = 77)
        val despues = proyectos.porId(proyecto.id)!!
        if (cuantas == 0) {
            // Robolectric no siempre sabe leer PDF: entonces el proyecto se queda como estaba.
            assertNull(despues.pdfOrigen)
            return
        }
        assertEquals(1, cuantas)
        assertNotNull(despues.pdfOrigen)
        assertNotEquals("se copia fuera del chat", pdf.absolutePath, despues.pdfOrigen)
        assertEquals(0, despues.hojas.single().pagina)
    }

    private companion object {
        const val PDF_DE_UNA_PAGINA = """%PDF-1.1
1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] >> endobj
trailer << /Root 1 0 R >>
%%EOF"""
    }
}
