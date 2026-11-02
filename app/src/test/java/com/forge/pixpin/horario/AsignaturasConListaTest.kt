package com.forge.pixpin.horario

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.mini.MiniApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Cada asignatura con su lista** (2-nov-2026): las pruebas de `semana.rs` y `asignaturas.rs` del PC (`71bf70f6`). */
class AsignaturasConListaTest {

    private val ahora = Momento.deMsLocales(0)

    private fun semanaDe(t: String) = Semana.deHorario(Horario.leer(t, ahora))

    @Test
    fun `el enlace con la lista va y vuelve y sobrevive al renombrar`() {
        val t = "Asignatura Geometría @A-12 #0A84FF lista:1730000000000-ab12\nLun 8:00-9:00 Geometría @A-12\n"
        val s = semanaDe(t)
        assertEquals("el enlace no es nombre", "Geometría", s.asignaturas[0].nombre)
        assertEquals("A-12", s.asignaturas[0].aula)
        assertEquals("1730000000000-ab12", s.asignaturas[0].lista)
        assertEquals(Semana.Renombrada.Hecho("Geometría"), s.renombrarAsignatura(0, "  Geometría   analítica "))
        val n = Semana.reescribir(t, s)
        assertTrue(n, n.contains("Asignatura Geometría analítica @A-12 #0A84FF lista:1730000000000-ab12"))
        assertTrue(n, n.contains("Lun 8:00-9:00 Geometría analítica @A-12"))
        val h = Horario.leer(n, ahora)
        assertEquals("Geometría analítica", h.clases[0].nombre)
        assertEquals("1730000000000-ab12", h.asignaturas[0].lista)
    }

    @Test
    fun `caso negativo renombrar a otra que ya esta no cambia nada`() {
        val t = "Asignatura Física\nAsignatura Química\nMar 10:00-11:00 Física\n"
        val s = semanaDe(t)
        val antes = s.copy(asignaturas = s.asignaturas.toMutableList(), bloques = s.bloques.toMutableList())
        assertEquals(Semana.Renombrada.Otra(1), s.renombrarAsignatura(0, "química"))
        assertNull(s.renombrarAsignatura(0, "   "))
        assertNull(s.renombrarAsignatura(0, "Física"))
        assertNull(s.renombrarAsignatura(7, "Arte"))
        assertEquals(antes, s)
    }

    @Test
    fun `los enlaces se apuntan al final de su linea y lo demas no se toca`() {
        val t = "# Mi horario\n\nAsignatura Física @Lab #112233\nAsignatura Química lista:q1\nLun 8:00-9:00 Física\n"
        val n = Horario.conEnlaces(t) { a -> if (a.nombre == "Física") "f1" else "otro" }
        assertEquals("# Mi horario\n\nAsignatura Física @Lab #112233 lista:f1\nAsignatura Química lista:q1\nLun 8:00-9:00 Física\n", n)
        // Sin enlace que dar, igual.
        assertEquals(t, Horario.conEnlaces(t) { null })
    }

    private fun lista(id: String, titulo: String, chat: String = "asig") = Mensaje(
        id = id, cuando = 1, clase = Clase.MINIAPP, miniapp = MiniApp.TAREAS.id, texto = "# $titulo\n\n- [ ] a", proyecto = chat
    )

    @Test
    fun `el plan crea enlaza y renombra como el PC`() {
        val listas = listOf(lista("g1", "Geometría"), lista("f9", "Fisica vieja"), lista("x", "Química", chat = "otro"))
        val pasos = Asignaturas.plan(
            conLinea = listOf(
                Horario.Asignatura("geometria", null),            // por su nombre, sin enlace → enlazar
                Horario.Asignatura("Física", null, null, "f9"),   // enlazada y renombrada → renombrar
                Horario.Asignatura("Química", null)               // su lista es de otro chat → crear
            ),
            soloEnClases = listOf(Horario.Asignatura("Geometría", null), Horario.Asignatura("Arte", null)),
            mensajes = listas, chat = "asig"
        )
        assertEquals(
            listOf(
                Asignaturas.Paso.Enlazar("g1", "geometria"),
                Asignaturas.Paso.Renombrar("f9", "Física"),
                Asignaturas.Paso.Crear("Química"),
                Asignaturas.Paso.Crear("Arte")
            ),
            pasos
        )
        // La de su enlace manda aunque se llame de otra forma; sin la lista enlazada, la de su nombre.
        assertEquals("f9", Asignaturas.deAsignatura(listas, "asig", Horario.Asignatura("Física", null, null, "f9"))?.id)
        assertEquals("g1", Asignaturas.deAsignatura(listas, "asig", Horario.Asignatura("Geometría", null, null, "borrada"))?.id)
    }
}
