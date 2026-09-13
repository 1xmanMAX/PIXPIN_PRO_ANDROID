package com.forge.pixpin.guardados

import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reenviar es abrir otra rama, y lo que se quitó de un proyecto se puede devolver desde su chat.
 */
class RamaDelChatTest {

    @Test
    fun `lo reenviado no queda atado al proyecto ni a la hoja de origen`() {
        val nota = Mensaje(id = "a", cuando = 1, clase = Clase.NOTA, texto = "hola", referencia = "n-1", proyecto = "pr-1", unido = true, hojaDelTexto = "x")
        val copia = reenviado(nota, "pr-2", 9, "b")
        assertEquals("pr-2", copia.proyecto)
        assertFalse(copia.unido)
        assertNull("la nota copiada ya no abre la hoja de origen", copia.referencia)
        assertNull(copia.hojaDelTexto)
        val tabla = Mensaje(id = "t", cuando = 1, clase = Clase.TABLA, referencia = "t-1", proyecto = "pr-1", unido = true)
        assertEquals("la tabla la duplica RamaDeMensaje; aquí aún apunta a la de origen", "t-1", reenviado(tabla, null, 9, "u").referencia)
    }

    @Test
    fun `se ofrece devolver solo lo que falta en su proyecto`() {
        val p = Proyecto(
            id = "pr-1", nombre = "Casa",
            hojas = listOf(Hoja(id = "h1", dibujo = "dib-1"), Hoja(id = "n-1", nota = "x")),
            croquis = listOf("c3d-1")
        )
        fun m(clase: Clase, ref: String, texto: String = "") = Mensaje(id = ref, cuando = 1, clase = clase, referencia = ref, texto = texto, proyecto = "pr-1", unido = true)
        assertFalse(UnirAlProyecto.sePuedeDevolver(m(Clase.DIBUJO, "dib-1"), listOf(p)))
        assertTrue(UnirAlProyecto.sePuedeDevolver(m(Clase.DIBUJO, "dib-2"), listOf(p)))
        assertTrue(UnirAlProyecto.sePuedeDevolver(m(Clase.TABLA, "t-1"), listOf(p)))
        assertFalse(UnirAlProyecto.sePuedeDevolver(m(Clase.NOTA, "n-1", "x"), listOf(p)))
        assertTrue(UnirAlProyecto.sePuedeDevolver(m(Clase.NOTA, "n-2", "y"), listOf(p)))
        assertFalse(UnirAlProyecto.sePuedeDevolver(m(Clase.CROQUIS, "c3d-1"), listOf(p)))
        assertTrue(UnirAlProyecto.sePuedeDevolver(m(Clase.CROQUIS, "c3d-9"), listOf(p)))
        assertFalse("sin proyecto no hay a dónde devolver", UnirAlProyecto.sePuedeDevolver(m(Clase.DIBUJO, "dib-2"), emptyList()))
    }

    @Test
    fun `borrar del chat quita de su proyecto lo que ese mensaje es`() {
        val p = Proyecto(
            id = "pr-1", nombre = "Casa",
            hojas = listOf(
                Hoja(id = "h1", dibujo = "dib-1"), Hoja(id = "h2", tabla = "t-1"),
                Hoja(id = "h3", dibujo = "foto-z", deMensaje = "x"), Hoja(id = "n-1", nota = "hola"),
                Hoja(id = "h4", dibujo = "dib-otro")
            ),
            croquis = listOf("c3d-1")
        )
        val borrados = listOf(
            Mensaje(id = "a", cuando = 1, clase = Clase.DIBUJO, referencia = "dib-1", proyecto = "pr-1"),
            Mensaje(id = "b", cuando = 1, clase = Clase.TABLA, referencia = "t-1", proyecto = "pr-1"),
            Mensaje(id = "x", cuando = 1, clase = Clase.IMAGEN, ruta = "/f.jpg", proyecto = "pr-1"),
            Mensaje(id = "c", cuando = 1, clase = Clase.CROQUIS, referencia = "c3d-1", proyecto = "pr-1"),
            // Un dibujo del proyecto adjuntado a la conversación general no es esa hoja.
            Mensaje(id = "d", cuando = 1, clase = Clase.DIBUJO, referencia = "dib-otro", proyecto = null)
        )
        val (nuevo, cuantas) = UnirAlProyecto.sinLoDeLosMensajes(p, borrados, 5)
        assertEquals(listOf("n-1", "h4"), nuevo.hojas.map { it.id })
        assertTrue(nuevo.croquis.isEmpty())
        assertEquals(4, cuantas)
        assertEquals("sin nada suyo, el proyecto no se toca", 0, UnirAlProyecto.sinLoDeLosMensajes(nuevo, borrados, 6).second)
    }
}
