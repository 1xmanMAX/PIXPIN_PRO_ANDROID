package com.forge.pixpin.mini

import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los casos de `horario/semana.rs` del PC (18-oct-2026), sin la rejilla (aquí la pinta Compose). */
class SemanaTest {
    private val ahora = Momento(Fechas.diasDe(2026, 10, 14), 10 * 60 + 30)
    private fun semanaDe(t: String) = Semana.deHorario(Horario.leer(t, ahora))

    @Test
    fun soltar_da_un_bloque_de_una_hora_pegado_a_la_franja() {
        var s = Semana.Estado().conAsignatura("Geometría @A-12")!!
        s = s.soltar(0, 2, 8 * 60 + 7)
        val b = s.bloques.last()
        assertEquals(Triple(2, 8 * 60, 9 * 60), Triple(b.dia, b.inicio, b.fin))
        assertEquals("Geometría" to "A-12", b.nombre to b.aula)
        s = s.soltar(0, 5, 21 * 60 + 50)
        assertEquals(21 * 60 + 45 to Semana.HASTA, s.bloques.last().inicio to s.bloques.last().fin)
    }

    @Test
    fun caso_negativo_sin_asignatura_o_repetida() {
        val s = Semana.Estado()
        assertEquals(s, s.soltar(3, 0, 600))
        assertNull(s.conAsignatura("   "))
        val f = s.conAsignatura("Física")!!
        assertNull("repetida, sin tildes ni mayúsculas", f.conAsignatura("fisica"))
    }

    @Test
    fun mover_y_alargar_se_pegan_a_las_franjas_y_no_se_salen() {
        var s = semanaDe("Lun 8:00-10:00 Geometría\n")
        s = s.mover(0, 3, 10 * 60 + 7)
        assertEquals(Triple(3, 10 * 60, 12 * 60), s.bloques[0].let { Triple(it.dia, it.inicio, it.fin) })
        s = s.mover(0, 9, 21 * 60)
        assertEquals(Triple(6, 20 * 60, Semana.HASTA), s.bloques[0].let { Triple(it.dia, it.inicio, it.fin) })
        s = s.mover(0, 0, -30)
        assertEquals(Semana.DESDE, s.bloques[0].inicio)
        s = s.alargar(0, 9 * 60 + 53)
        assertEquals(10 * 60, s.bloques[0].fin)
        s = s.alargar(0, 6 * 60)
        assertEquals(Semana.DESDE + Semana.PASO, s.bloques[0].fin)
        s = s.alargar(0, 23 * 60 + 59)
        assertEquals(Semana.HASTA, s.bloques[0].fin)
        assertTrue(s.sinBloque(0).bloques.isEmpty())
    }

    private val aMano = """# Mi horario

Algo que escribí yo.

Lun Mie 8:00-10:00 Geometría @A-12
> traer el compás
Martes y Jueves 10:15 a 12:00 Física (Aula 7)
- Evaluación Geometría 23/10
avisar 15 min antes
<!-- un comentario
Lun 8-9 No soy clase
-->
Fin de la nota.
"""

    @Test
    fun guardar_y_volver_a_leer_no_pierde_nada() {
        val h = Horario.leer(aMano, ahora)
        var s = Semana.deHorario(h)
        s = s.conOtroColor(0)
        s = s.conAsignatura("Dibujo técnico")!!
        s = s.soltar(2, 4, 16 * 60)
        val i = s.bloques.size - 1
        s = s.alargar(i, 18 * 60)
        s = s.conBloque(i, s.bloques[i].copy(antelacion = 30, aula = "Lab de dibujo", notas = listOf("llevar la escuadra")))
        val texto = Semana.reescribir(aMano, s)
        for (l in listOf("# Mi horario", "Algo que escribí yo.", "- Evaluación Geometría 23/10", "avisar 15 min antes", "<!-- un comentario", "Lun 8-9 No soy clase", "Fin de la nota."))
            assertTrue("falta «$l»:\n$texto", texto.lines().any { it == l })
        assertTrue(texto, texto.contains("\nLun Mié 8:00-10:00 Geometría @A-12\n> traer el compás\n"))
        assertTrue(texto, texto.contains("Vie 16:00-18:00 Dibujo técnico @(Lab de dibujo) avisar 30"))
        val h2 = Horario.leer(texto, ahora)
        val s2 = Semana.deHorario(h2)
        assertEquals(s.bloques.sortedWith(compareBy({ it.dia }, { it.inicio }, { it.nombre })), s2.bloques)
        assertEquals(s.asignaturas, s2.asignaturas)
        assertEquals(15, h2.antelacion)
        assertEquals(1, h2.evaluaciones.size)
        assertEquals("la evaluación sigue con su clase", 0, h2.evaluaciones[0].clase)
        assertEquals(texto, Semana.reescribir(texto, s2))
        assertEquals(30, h2.clases.first { it.nombre == "Dibujo técnico" }.antelacion)
    }

    @Test
    fun la_plantilla_recibe_las_clases_y_conserva_el_ejemplo_comentado() {
        var s = semanaDe(Horario.PLANTILLA)
        assertTrue(s.bloques.isEmpty())
        s = s.conAsignatura("Inglés")!!.soltar(0, 0, 9 * 60)
        val t = Semana.reescribir(Horario.PLANTILLA, s)
        assertTrue(t, t.contains("## Asignaturas\nAsignatura Inglés\n"))
        assertTrue(t, t.contains("## Clases\nLun 9:00-10:00 Inglés\n"))
        assertEquals("el ejemplo comentado sigue sin llamar", 1, Horario.leer(t, ahora).clases.size)
        val t2 = Semana.reescribir(t, s.sinAsignatura(0))
        assertFalse(t2, t2.contains("Inglés"))
    }

    @Test
    fun una_tabla_pegada_pasa_a_lineas_al_guardar_desde_la_pantalla() {
        val t = "Hora\tLunes\tMartes\n8:00-10:00\tGeometría\t\nnota final\n"
        val s = semanaDe(t)
        assertEquals(1, s.bloques.size)
        val n = Semana.reescribir(t, s)
        assertFalse(n, n.contains('\t'))
        assertTrue(n.contains("Lun 8:00-10:00 Geometría"))
        assertTrue(n.contains("nota final"))
    }

    @Test
    fun el_aviso_propio_de_la_clase_manda() {
        val h = Horario.leer("Jue 12:00-13:00 Física avisar 30\navisar 10 min antes\n", ahora)
        val a = Horario.avisos(h, (ahora.dia * 1440 + ahora.minuto) * 60_000L, 2).first()
        val inicio = ((ahora.dia + 1) * 1440 + 12 * 60) * 60_000L
        assertEquals(inicio - 30 * 60_000L, a.cuando)
    }
}
