package com.forge.pixpin.horario

import com.forge.pixpin.horario.Semana.Companion.DESDE
import com.forge.pixpin.horario.Semana.Companion.HASTA
import com.forge.pixpin.horario.Semana.Companion.PASO
import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las pruebas de `horario/semana.rs` del PC, portadas tal cual. */
class SemanaTest {

    private val ahora = Momento(Fechas.diasDe(2026, 10, 14), 10 * 60 + 30)

    /** Lunes a sábado, de 100 en 100 de ancho y 10 de alto por franja, empezando en (60, 100). */
    private val rejilla = Rejilla(60f, 100f, 100f, 10f, 6)

    private fun semanaDe(t: String) = Semana.deHorario(Horario.leer(t, ahora))

    @Test fun `soltar en una franja da el dia y la hora correctos`() {
        val r = rejilla
        // Miércoles (tercera columna), a 4 franjas y media de las 7:00: 8:07 → la franja de las 8:00.
        val (dia, minuto) = r.en(60f + 2f * 100f + 50f, 100f + 4.5f * 10f)!!
        assertEquals(2, dia)
        assertEquals("7:00 + 4,5 franjas = 8:07", 8 * 60 + 7, minuto)
        val s = Semana()
        s.anadirAsignatura("Geometría @A-12")
        val i = s.soltar(0, dia, minuto)!!
        val b = s.bloques[i]
        assertEquals("8:00-9:00", Triple(2, 7 * 60 + 60, 7 * 60 + 120), Triple(b.dia, b.inicio, b.fin))
        assertEquals("Geometría" to "A-12", b.nombre to b.aula)
        // La caja del bloque cae donde se soltó.
        val c = r.caja(b.dia, b.inicio, b.fin)
        assertEquals(Triple(260f, 140f, 40f), Triple(c.x, c.y, c.alto))
        // Al final del día no se sale: a las 21:50 dura lo que queda.
        val j = s.soltar(0, 5, 21 * 60 + 50)!!
        assertEquals(21 * 60 + 45 to HASTA, s.bloques[j].inicio to s.bloques[j].fin)
    }

    @Test fun `caso negativo fuera de la rejilla no se suelta`() {
        val r = rejilla
        assertNull("en la columna de las horas", r.en(59f, 150f))
        assertNull("en la cabecera de los días", r.en(100f, 99f))
        assertNull("el domingo no se ve", r.en(60f + 6f * 100f + 1f, 150f))
        assertNull("pasadas las 22:00", r.en(100f, 100f + r.alto() + 1f))
        val conDomingo = r.copy(dias = 7)
        assertEquals(6 to DESDE, conDomingo.en(60f + 6.5f * 100f, 100f))
        val s = Semana()
        assertNull("no hay asignatura 3", s.soltar(3, 0, 600))
        assertEquals(Semana.Resultado.Nueva(null), s.anadirAsignatura("   "))
        s.anadirAsignatura("Física")
        assertEquals("repetida, sin tildes ni mayúsculas", Semana.Resultado.Repetida(0), s.anadirAsignatura("fisica"))
    }

    @Test fun `mover y alargar se pegan a las franjas y no se salen`() {
        val s = semanaDe("Lun 8:00-10:00 Geometría\n")
        s.mover(0, 3, 10L * 60 + 7)
        var b = s.bloques[0]
        assertEquals("jueves 10:00, sigue durando 2 h", Triple(3, 10 * 60, 12 * 60), Triple(b.dia, b.inicio, b.fin))
        s.mover(0, 9, 21L * 60)
        b = s.bloques[0]
        assertEquals("no pasa de las 22:00 ni del domingo", Triple(6, 20 * 60, HASTA), Triple(b.dia, b.inicio, b.fin))
        s.mover(0, 0, -30)
        assertEquals("ni antes de las 7:00", DESDE, s.bloques[0].inicio)
        s.alargar(0, 9 * 60 + 53)
        assertEquals("9:53 se pega a las 10:00 (9:52 iría a las 9:45)", 10 * 60, s.bloques[0].fin)
        s.alargar(0, 6 * 60)
        assertEquals("un cuarto de hora como poco", DESDE + PASO, s.bloques[0].fin)
        s.alargar(0, 23 * 60 + 59)
        assertEquals(HASTA, s.bloques[0].fin)
        // Arrastrar fuera por la derecha deja el bloque en el último día.
        assertEquals(5, rejilla.diaCercano(5000f))
        assertEquals(0, rejilla.diaCercano(-50f))
        assertNotNull(s.borrar(0))
        assertNull(s.borrar(0))
    }

    private val A_MANO = """# Mi horario

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

    @Test fun `guardar y volver a leer no pierde nada`() {
        val h = Horario.leer(A_MANO, ahora)
        val s = Semana.deHorario(h)
        // Lo hecho en la rejilla: un color, una asignatura nueva con su clase de 2 h con aviso
        // propio, y el aula de varias palabras.
        s.cambiarColor(0)
        s.anadirAsignatura("Dibujo técnico")
        val i = s.soltar(2, 4, 16 * 60)!!
        s.alargar(i, 18 * 60)
        s.bloques[i] = s.bloques[i].copy(antelacion = 30, aula = "Lab de dibujo", notas = listOf("llevar la escuadra"))
        val texto = Semana.reescribir(A_MANO, s)
        // Lo del usuario sigue ahí, tal cual.
        for (l in listOf(
            "# Mi horario", "Algo que escribí yo.", "- Evaluación Geometría 23/10", "avisar 15 min antes",
            "<!-- un comentario", "Lun 8-9 No soy clase", "Fin de la nota."
        )) assertTrue("falta «$l»:\n$texto", texto.lines().any { it == l })
        // Legible: una línea por clase, los días juntos.
        assertTrue(texto, texto.contains("\nLun Mié 8:00-10:00 Geometría @A-12\n> traer el compás\n"))
        assertTrue(texto, texto.contains("Vie 16:00-18:00 Dibujo técnico @(Lab de dibujo) avisar 30"))
        // Y se lee igual que lo que había en la rejilla.
        val h2 = Horario.leer(texto, ahora)
        val s2 = Semana.deHorario(h2)
        assertEquals(s.bloques.sortedWith(Semana.ORDEN), s2.bloques)
        assertEquals(s.asignaturas, s2.asignaturas)
        assertEquals(15, h2.antelacion)
        assertEquals(1, h2.evaluaciones.size)
        assertEquals("la evaluación sigue con su clase", 0, h2.evaluaciones[0].clase)
        // Guardar otra vez lo mismo no cambia nada (ni repite títulos).
        assertEquals(texto, Semana.reescribir(texto, s2))
        // El aviso propio de la clase manda sobre el del horario.
        assertEquals(30, h2.clases.first { it.nombre == "Dibujo técnico" }.antelacion)
    }

    @Test fun `la plantilla recibe las clases y conserva el ejemplo comentado`() {
        val s = semanaDe(Horario.PLANTILLA)
        assertTrue(s.bloques.isEmpty())
        s.anadirAsignatura("Inglés")
        s.soltar(0, 0, 9 * 60)
        val t = Semana.reescribir(Horario.PLANTILLA, s)
        assertTrue(t, t.contains("<!-- Ejemplo"))
        assertTrue(t.contains("avisar 10 min antes"))
        assertTrue(t, t.contains("## Asignaturas\nAsignatura Inglés\n"))
        assertTrue(t, t.contains("## Clases\nLun 9:00-10:00 Inglés\n"))
        val h = Horario.leer(t, ahora)
        assertEquals("el ejemplo comentado sigue sin llamar", 1, h.clases.size)
        // Borrar la asignatura se lleva sus clases.
        s.borrarAsignatura(0)
        val t2 = Semana.reescribir(t, s)
        assertFalse(t2, t2.contains("Inglés"))
        assertTrue(t2.contains("<!-- Ejemplo"))
    }

    @Test fun `una tabla pegada pasa a lineas al guardar desde la rejilla`() {
        val t = "Hora\tLunes\tMartes\n8:00-10:00\tGeometría\t\nnota final\n"
        val s = semanaDe(t)
        assertEquals(1, s.bloques.size)
        val n = Semana.reescribir(t, s)
        assertFalse(n, n.contains('\t'))
        assertTrue(n.contains("Lun 8:00-10:00 Geometría"))
        assertTrue(n.contains("nota final"))
    }
}
