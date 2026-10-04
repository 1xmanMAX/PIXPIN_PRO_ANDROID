package com.forge.pixpin

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.lecciones.Leccion
import com.forge.pixpin.lecciones.LeccionesStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 4-oct-2026, de la revisión: cambiar el chat a partir de una lista leída antes borraba (con lápida)
 * lo llegado entretanto; y las fotos de una lección que no estaban en su lista quedaban escondidas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatSinCarrerasTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun nota(id: String, texto: String) = Mensaje(id = id, cuando = System.currentTimeMillis(), clase = Clase.NOTA, texto = texto)

    private fun marcas(): String = File(context.filesDir, "sincro/borrados.jsonl").takeIf { it.isFile }?.readText().orEmpty()

    @Test
    fun `fijar con la lista vieja de la pantalla no se lleva lo que llego entretanto`() {
        val almacen = MensajesStore(context)
        almacen.anadir(nota("a", "uno"))
        val laDeLaPantalla = almacen.leer()
        almacen.anadir(nota("b", "llegó sincronizando"))
        // Lo que hace ahora fijar: el cambio, sobre lo que hay en el disco.
        almacen.cambiar { lista -> lista.map { if (it.id == "a") it.copy(fijado = true) else it } }
        val ahora = almacen.leer()
        assertEquals(listOf("a", "b"), ahora.map { it.id })
        assertTrue(ahora.single { it.id == "a" }.fijado)
        assertFalse("sin lápida para lo que llegó", marcas().contains("\"b\""))
        assertEquals(1, laDeLaPantalla.size)
    }

    @Test
    fun `una leccion ensena tambien las fotos que le responden y se las lleva al borrarla`() {
        val lecciones = LeccionesStore(context)
        val carpeta = MensajesStore(context).carpetaDeAdjuntos()
        val f1 = File(carpeta, "leccion_1.jpg").apply { writeText("1") }
        val f2 = File(carpeta, "leccion_2.jpg").apply { writeText("2") }
        val l = Leccion(id = "abc", creada = 1, titulo = "Medir dos veces")
        val foto1 = Mensaje(id = "f1", cuando = 2, clase = Clase.IMAGEN, ruta = f1.path)
        lecciones.guardar(l, null, listOf(foto1), emptyList())
        // Otra foto que llegó de otro aparato: le responde, pero la lista del archivo no la trae.
        MensajesStore(context).anadir(Mensaje(id = "f2", cuando = 3, clase = Clase.IMAGEN, ruta = f2.path, respondeA = "lec-abc"))
        val e = lecciones.recargar().single()
        assertEquals(listOf("f1", "f2"), lecciones.adjuntosDe(e).map { it.id })
        lecciones.borrar(e)
        assertTrue(MensajesStore(context).leer().none { it.id in setOf("lec-abc", "f1", "f2") })
        assertFalse(f1.exists()); assertFalse(f2.exists())
    }
}
