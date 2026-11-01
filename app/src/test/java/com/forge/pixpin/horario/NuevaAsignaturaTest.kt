package com.forge.pixpin.horario

import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Crear una asignatura desde la rejilla** (1-nov-2026, el usuario: «en el Horario quiero crear
 * una asignatura que se llame Hola»): que cualquier nombre se guarde en el `horario.md` con la
 * línea del PC (`Asignatura Hola`), se vuelva a leer igual y salga en la rejilla; y qué cuenta el
 * diálogo cuando de lo escrito no sale un nombre.
 */
class NuevaAsignaturaTest {

    private val ahora = Momento(Fechas.diasDe(2026, 11, 1), 10 * 60)

    private fun releer(t: String) = Semana.deHorario(Horario.leer(t, ahora))

    @Test fun `Hola se guarda con la linea del PC y vuelve a salir en la rejilla`() {
        val s = releer(Horario.PLANTILLA)
        assertEquals(Semana.Resultado.Nueva(0), s.anadirAsignatura("Hola"))
        val texto = Semana.reescribir(Horario.PLANTILLA, s)
        assertTrue(texto, texto.lines().contains("Asignatura Hola"))
        assertTrue("la plantilla se queda", texto.startsWith("# Horario de clases"))
        val otra = releer(texto)
        assertEquals(listOf("Hola"), otra.asignaturas.map { it.nombre })
        // Y se pone en la semana.
        assertEquals(0, otra.soltar(0, 1, 9 * 60))
        val conClase = Semana.reescribir(texto, otra)
        assertTrue(conClase, conClase.lines().contains("Mar 9:00-10:00 Hola"))
        assertEquals(1, Horario.leer(conClase, ahora).clases.size)
    }

    @Test fun `nombres que podrian liarse se leen igual al volver`() {
        for (n in listOf("Hola", "hola mundo", "Mar y Tierra", "Examen", "Lunes", "Historia 2", "C#", "Inglés · B2")) {
            val s = Semana()
            assertEquals(n, Semana.Resultado.Nueva(0), s.anadirAsignatura(n))
            val nombre = s.asignaturas[0].nombre
            val texto = Semana.reescribir("", s)
            assertEquals(n, listOf(nombre), releer(texto).asignaturas.map { it.nombre })
            assertTrue(n, Semana().previaDeNueva(n).vale)
        }
    }

    @Test fun `lo que no deja nombre no se puede anadir y se dice por que`() {
        val s = Semana()
        assertEquals(Semana.Previa(false, null), s.previaDeNueva("   "))
        val lab = s.previaDeNueva("Lab 2")
        assertFalse(lab.vale)
        assertEquals("Falta el nombre: «Lab 2» se lee como el aula", lab.texto)
        assertEquals(Semana.Resultado.Nueva(null), s.anadirAsignatura("Lab 2"))
        assertFalse(s.previaDeNueva("@A-12").vale)
    }

    @Test fun `el aula se cuenta y la repetida se elige`() {
        val s = Semana()
        assertEquals(Semana.Previa(true, "Se añadirá «Geometría» en el aula «A-12»"), s.previaDeNueva("Geometría @A-12"))
        // «Laboratorio de …» es un aula: se cuenta antes de guardarlo así.
        assertEquals("Se añadirá «Física» en el aula «Laboratorio de»", s.previaDeNueva("Laboratorio de Física").texto)
        assertNull(s.previaDeNueva("Hola").texto)
        s.anadirAsignatura("Hola")
        assertEquals(Semana.Previa(true, "«Hola» ya está: se elegirá esa"), s.previaDeNueva("HOLA"))
    }
}
