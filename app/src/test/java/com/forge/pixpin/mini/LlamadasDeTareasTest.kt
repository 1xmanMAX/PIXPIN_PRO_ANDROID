package com.forge.pixpin.mini

import com.forge.pixpin.horario.Horario
import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que llama por teléfono, dicho en Tareas (1-nov-2026): las tareas con hora y los grupos del horario. */
class LlamadasDeTareasTest {

    private val ahora = Momento(Fechas.diasDe(2026, 11, 1), 10 * 60)

    // ---- Las tareas ------------------------------------------------------

    @Test
    fun `la llamada de una tarea sigue siendo suya aunque cambie o pierda la hora`() {
        val con = "llamar al banco ⏰ 2026-11-02 10:00 ➕ 2026-11-01"
        val otraHora = "llamar al banco ⏰ 2026-11-03 18:30 ➕ 2026-11-01"
        val sinHora = "llamar al banco ➕ 2026-11-01"
        val claves = LlamadasDeTareas.conLlamada(emptySet(), "lista1", con, true)
        assertTrue(LlamadasDeTareas.llama(claves, "lista1", con))
        assertTrue(LlamadasDeTareas.llama(claves, "lista1", otraHora))
        assertTrue("al sonar se le quita la hora y sigue llamando si se le vuelve a poner", LlamadasDeTareas.llama(claves, "lista1", sinHora))
        // Casos negativos: otra lista, otro día de creación u otro texto no llaman.
        assertFalse(LlamadasDeTareas.llama(claves, "lista2", con))
        assertFalse(LlamadasDeTareas.llama(claves, "lista1", "llamar al banco ➕ 2026-10-01"))
        assertFalse(LlamadasDeTareas.llama(claves, "lista1", "llamar al notario ⏰ 2026-11-02 10:00 ➕ 2026-11-01"))
        // Apagarla la quita.
        assertTrue(LlamadasDeTareas.conLlamada(claves, "lista1", otraHora, false).isEmpty())
    }

    @Test
    fun `el id de la llamada lleva la lista y la tarea y volver a llamar le pone la hora otra vez`() {
        val crudo = "llamar al banco ⏰ 2026-11-02 10:00 ➕ 2026-11-01"
        val id = LlamadasDeTareas.idDeLlamada("m-1", crudo)
        val (lista, sinHora) = LlamadasDeTareas.deIdDeLlamada(id)!!
        assertEquals("m-1" to "llamar al banco ➕ 2026-11-01", lista to sinHora)
        assertNull("caso negativo: el de otra cosa no es de una tarea", LlamadasDeTareas.deIdDeLlamada("horario:clase:1:Física"))

        // Ya sonó: sin hora. «Volver a llamar en 15 min» se la pone.
        val doc = "# Banco\n\n- [x] otra\n- [ ] llamar al banco ➕ 2026-11-01"
        val luego = LocalDateTime.of(2026, 11, 2, 10, 15)
        val nuevo = LlamadasDeTareas.conHoraOtraVez(doc, sinHora, luego)!!
        val t = Tareas.leer(nuevo)[1]
        assertEquals(luego, Tareas.horaDe(t.texto))
        assertEquals("la fecha de creación se queda", java.time.LocalDate.of(2026, 11, 1), Tareas.partir(t.texto).second)
        assertTrue("y sigue siendo la que llama", LlamadasDeTareas.llama(LlamadasDeTareas.conLlamada(emptySet(), "m-1", crudo, true), "m-1", t.texto))
        // Caso negativo: si ya está hecha (o quitada), no se vuelve a llamar.
        assertNull(LlamadasDeTareas.conHoraOtraVez("# Banco\n\n- [x] llamar al banco ➕ 2026-11-01", sinHora, luego))
    }

    @Test
    fun `lo que dice la tarjeta y las claves de listas que ya no estan se olvidan`() {
        val ahora = LocalDateTime.of(2026, 11, 1, 9, 0)
        assertEquals("Te llamará hoy 10:00", LlamadasDeTareas.textoDeTarea(LocalDateTime.of(2026, 11, 1, 10, 0), ahora))
        assertEquals("Te llamará mañana 08:30", LlamadasDeTareas.textoDeTarea(LocalDateTime.of(2026, 11, 2, 8, 30), ahora))
        val claves = setOf(LlamadasDeTareas.clave("a", "x"), LlamadasDeTareas.clave("b", "y"))
        assertEquals(setOf(LlamadasDeTareas.clave("a", "x")), LlamadasDeTareas.vigentes(claves, setOf("a", "c")))
    }

    // ---- Los grupos del horario -----------------------------------------

    private val HORARIO = """
L-V 8:00-9:00 Matemáticas
Lun Mie 10:00-12:00 Geometría @A-12
Vie 12:00-13:00 Geometría
Lun Mar Mie Jue Vie Sab Dom 8:10-9:00 Gimnasio
Evaluación Geometría 5/11
avisar 10 min antes
"""

    private fun h() = Horario.leer(HORARIO, ahora)

    @Test
    fun `un grupo de asignaturas dice cuando llama`() {
        val h = h()
        val mates = LlamadasDeTareas.deGrupo("Matemáticas", "Asignaturas", h, emptySet())!!
        assertEquals("Te llamará de lunes a viernes a las 7:50", LlamadasDeTareas.textoDeGrupo(mates))
        // Sin mirar mayúsculas ni tildes, y con dos clases a horas distintas, las dos.
        val geo = LlamadasDeTareas.deGrupo("  geometria ", "Asignaturas", h, emptySet())!!
        assertEquals(2, geo.clases.size)
        assertEquals("Te llamará lun y mié a las 9:50 y los viernes a las 11:50", LlamadasDeTareas.textoDeGrupo(geo))
        assertEquals("Te llamará todos los días a las 8:00", LlamadasDeTareas.textoDeGrupo(LlamadasDeTareas.deGrupo("Gimnasio", "Asignaturas", h, emptySet())!!))
        // A las 00:05 con 10 min de antelación llama la víspera.
        val guardia = LlamadasDeTareas.DeGrupo(listOf(Horario.Clase(listOf(5), 5, 60, "Guardia", null)), 10, apagada = false)
        assertEquals("Te llamará los viernes a las 23:55", LlamadasDeTareas.textoDeGrupo(guardia))
        // Casos negativos: otra lista u otro chat no llaman.
        assertNull(LlamadasDeTareas.deGrupo("Inbox", "Asignaturas", h, emptySet()))
        assertNull(LlamadasDeTareas.deGrupo("Matemáticas", "Mensajes guardados", h, emptySet()))
        assertNull(LlamadasDeTareas.deGrupo("", "Asignaturas", h, emptySet()))
    }

    @Test
    fun `apagar un grupo quita sus llamadas de clase pero no las evaluaciones`() {
        val h = h()
        val apagadas = LlamadasDeTareas.conGrupo(emptySet(), "GEOMETRÍA", encender = false)
        val geo = LlamadasDeTareas.deGrupo("Geometría", "Asignaturas", h, apagadas)!!
        assertTrue(geo.apagada)
        assertEquals("Llamadas apagadas", LlamadasDeTareas.textoDeGrupo(geo))
        assertFalse(LlamadasDeTareas.deGrupo("Matemáticas", "Asignaturas", h, apagadas)!!.apagada)

        val desde = (ahora.dia * 1440 + ahora.minuto) * Horario.MINUTO
        val todos = Horario.avisos(h, desde, Horario.DIAS_A_LA_VISTA)
        val quedan = LlamadasDeTareas.avisosQueLlaman(h, todos, apagadas)
        fun deGeometria(a: Horario.Aviso) = (a.que as? Horario.Que.DeClase)?.let { h.clases[it.i].nombre == "Geometría" } == true
        assertTrue(todos.any(::deGeometria))
        assertTrue(quedan.none(::deGeometria))
        assertNotNull("las evaluaciones siguen llamando", quedan.firstOrNull { it.que is Horario.Que.DeEvaluacion })
        assertEquals(todos.count { !deGeometria(it) }, quedan.size)
        // Encenderla otra vez las devuelve todas.
        val otraVez = LlamadasDeTareas.conGrupo(apagadas, "geometría", encender = true)
        assertTrue(otraVez.isEmpty())
        assertEquals(todos, LlamadasDeTareas.avisosQueLlaman(h, todos, otraVez))
    }

    @Test
    fun `los dias se dicen como se hablan`() {
        assertEquals("todos los días", LlamadasDeTareas.dias(listOf(6, 5, 4, 3, 2, 1, 0)))
        assertEquals("de lunes a viernes", LlamadasDeTareas.dias(listOf(0, 1, 2, 3, 4)))
        assertEquals("de lunes a sábado", LlamadasDeTareas.dias(listOf(0, 1, 2, 3, 4, 5)))
        assertEquals("los fines de semana", LlamadasDeTareas.dias(listOf(5, 6)))
        assertEquals("los miércoles", LlamadasDeTareas.dias(listOf(2)))
        assertEquals("lun, mié y vie", LlamadasDeTareas.dias(listOf(4, 0, 2)))
        assertEquals("a la 1:05", LlamadasDeTareas.aLas(65))
        assertEquals("a las 0:05", LlamadasDeTareas.aLas(5))
        assertEquals("a las 13:00", LlamadasDeTareas.aLas(780))
    }
}
