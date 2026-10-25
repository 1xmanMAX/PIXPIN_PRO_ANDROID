package com.forge.pixpin.mini

import com.forge.pixpin.mini.VistaDeTareas.Caja
import com.forge.pixpin.mini.VistaDeTareas.MedidasHorario
import com.forge.pixpin.mini.VistaDeTareas.Vista
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las vistas de Tareas** (Tarjetas · Kanban · Horario) y la semana del Horario con sus chapitas
 * de tareas, con los casos de `crates/pixpin-ui/src/vista_tareas.rs` del PC (25-oct-2026). Los de
 * los atajos y del marco de la ventana no: son del escritorio.
 */
class VistaDeTareasTest {

    @Test
    fun `la lista de antes se lee como tarjetas y cada vista vuelve a su texto`() {
        for (v in Vista.entries) assertEquals(v, Vista.deTexto(v.texto))
        assertEquals(listOf("tarjetas", "kanban", "horario"), Vista.entries.map { it.texto })
        assertEquals("la que se quitó", Vista.TARJETAS, Vista.deTexto("lista"))
        assertEquals(Vista.HORARIO, Vista.deTexto(" horario\n"))
        assertEquals(Vista.TARJETAS, Vista.deTexto("¿?"))
        assertEquals(Vista.TARJETAS, Vista.deTexto(null))
        assertTrue(Vista.KANBAN.conCuadrados && !Vista.HORARIO.conCuadrados)
        assertTrue(!Vista.HORARIO.conTareas && Vista.TARJETAS.conTareas)
    }

    @Test
    fun `el horario reparte la lateral y la semana`() {
        val m = MedidasHorario()
        val area = Caja(0f, 76f, 1180f, 704f)
        val d = VistaDeTareas.disponerHorario(area, 1f, m)
        assertEquals(280f, d.lateral.ancho, 0f)
        assertEquals(16f, d.lateral.x, 0f)
        assertEquals(16f + 280f + 16f + 48f, d.semana.x, 0f)
        assertEquals(76f + 36f, d.semana.y, 0f)
        assertEquals("hasta el margen", 1180f - 16f, d.semana.x + d.semana.ancho, 0.001f)
        assertFalse(d.lateral.contiene(d.semana.x + 1f, d.semana.y + 1f))
        // Estrecha: la lateral encoge, pero no por debajo de su mínimo.
        val estrecha = VistaDeTareas.disponerHorario(area.copy(ancho = 660f), 1f, m)
        assertTrue(estrecha.lateral.ancho < 280f && estrecha.lateral.ancho >= 200f)
        // Diminuta: nada negativo.
        val mini = VistaDeTareas.disponerHorario(area.copy(ancho = 100f, alto = 50f), 1f, m)
        assertTrue(mini.semana.ancho >= 60f && mini.semana.alto >= 60f)
        // Con escala 2, todo al doble.
        val doble = VistaDeTareas.disponerHorario(area.copy(ancho = 2360f, alto = 1408f), 2f, m)
        assertEquals(560f, doble.lateral.ancho, 0f)
    }

    private val dia = 86_400_000L
    private val hora = 3_600_000L

    @Test
    fun `las tareas con hora van a su dia y se apilan en la misma hora`() {
        val lunes = 20_000L
        val base = lunes * dia
        val horas = listOf(
            base + 9 * hora, // lunes 9:00
            base + 2 * dia + 15 * hora, // miércoles 15:00
            base + 9 * hora + 10 * 60_000, // lunes 9:10: debajo de la primera
            base - dia + 9 * hora, // el domingo de antes: fuera
            base + 7 * dia, // el lunes siguiente: fuera
            base + 4 * dia + 6 * hora, // viernes 6:00: al borde de arriba
            base + 6 * dia + 10 * hora, // domingo: solo si se ve
            base + 2 * dia + 15 * hora + 1000 // miércoles 15:00 otra vez
        )
        val c = VistaDeTareas.chipsDeLaSemana(horas, lunes, 6, 7 * 60, 22 * 60, 30)
        fun de(i: Int) = c.firstOrNull { it.indice == i }
        assertEquals(Triple(0, 540, 0), de(0)?.let { Triple(it.dia, it.minuto, it.carril) })
        assertEquals("a la hora de la primera, debajo", Triple(0, 540, 1), de(2)?.let { Triple(it.dia, it.minuto, it.carril) })
        assertEquals(2 to 0, de(1)?.let { it.dia to it.carril })
        assertEquals(2 to 1, de(7)?.let { it.dia to it.carril })
        assertEquals("al borde", 7 * 60, de(5)?.minuto)
        // Casos negativos: lo de otra semana y el domingo sin su columna.
        assertNull(de(3))
        assertNull(de(4))
        assertNull(de(6))
        val conDomingo = VistaDeTareas.chipsDeLaSemana(horas, lunes, 7, 7 * 60, 22 * 60, 30)
        assertTrue(conDomingo.any { it.indice == 6 && it.dia == 6 })
    }

    @Test
    fun `mas tareas de las que caben en una hora se cuentan`() {
        val lunes = 20_000L
        val aLasDiez = lunes * dia + 10 * hora
        val horas = (0 until 5).map { aLasDiez + it * 60_000L }
        val c = VistaDeTareas.chipsDeLaSemana(horas, lunes, 6, 7 * 60, 22 * 60, 30)
        assertEquals(VistaDeTareas.CARRILES, c.size)
        assertEquals(2, c.last().mas)
        assertTrue(c.take(2).all { it.mas == 0 })
        // Una hora después ya es otro grupo: vuelve al primer carril.
        val otra = VistaDeTareas.chipsDeLaSemana(horas + (aLasDiez + hora), lunes, 6, 7 * 60, 22 * 60, 30)
        assertEquals(5 to 0, otra.last().let { it.indice to it.carril })
    }

    @Test
    fun `la chapita va a la derecha de su dia y cada carril debajo`() {
        val a = VistaDeTareas.cajaDeChip(100f, 150f, 300f, 0, 18f, 1f)
        val b = VistaDeTareas.cajaDeChip(100f, 150f, 300f, 1, 18f, 1f)
        assertTrue("$a", a.x > 100f && a.x + a.ancho <= 250f)
        assertTrue(a.ancho <= 100f)
        assertEquals(a.x, b.x, 0f)
        assertTrue("sin pisarse", b.y >= a.y + a.alto)
    }
}
