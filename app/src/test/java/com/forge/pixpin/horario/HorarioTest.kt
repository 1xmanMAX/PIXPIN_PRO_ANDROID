package com.forge.pixpin.horario

import com.forge.pixpin.horario.Horario.Que
import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Las pruebas de `horario.rs` del PC, portadas tal cual (miércoles 14-oct-2026, 10:30). */
class HorarioTest {

    private val ahora = Momento(Fechas.diasDe(2026, 10, 14), 10 * 60 + 30)

    private fun ms(d: Long, h: Long, m: Long): Long = (Fechas.diasDe(2026, 10, d) * 1440 + h * 60 + m) * Horario.MINUTO

    private val EJEMPLO = """# Horario

Lun Mie 8:00-10:00 Geometría @A-12
> traer el compás
> repasar el tema 3
Martes y Jueves 10:15 a 12:00 Física (Aula 7)
L-V 14-16 Inglés
- Evaluación Geometría 23/10
Examen de Física el jueves
avisar 15 min antes
<!-- comentario
Lun 8-9 No soy clase
-->
Esto no es nada
"""

    @Test fun `un dia en el nombre no se pierde`() {
        val c = Horario.claseDeLinea("Lun Mié 8:00-10:00 Taller del Sábado @A-12")!!
        assertEquals("Taller del Sábado", c.nombre)
        assertEquals(listOf(0, 2), c.dias)
        val g = Horario.claseDeLinea("Geometría lunes 8-10")!!
        assertEquals("Geometría" to listOf(0), g.nombre to g.dias)
    }

    @Test fun `una linea por clase con dias horas y aula`() {
        val h = Horario.leer(EJEMPLO, ahora)
        assertEquals(h.clases.toString(), 3, h.clases.size)
        val g = h.clases[0]
        assertEquals(Triple(listOf(0, 2), 480, 600), Triple(g.dias, g.inicio, g.fin))
        assertEquals("Geometría" to "A-12", g.nombre to g.aula)
        assertEquals(listOf("traer el compás", "repasar el tema 3"), g.notas)
        val f = h.clases[1]
        assertEquals(Triple(listOf(1, 3), 615, 720), Triple(f.dias, f.inicio, f.fin))
        assertEquals("Física" to "Aula 7", f.nombre to f.aula)
        val i = h.clases[2]
        assertEquals(listOf(0, 1, 2, 3, 4), i.dias)
        assertEquals("14-16", 14 * 60 to 16 * 60, i.inicio to i.fin)
        assertEquals(15, h.antelacion)
        assertEquals(Horario.DIAS_ANTES, h.diasAntes)
    }

    @Test fun `las evaluaciones llevan su fecha y su clase`() {
        val h = Horario.leer(EJEMPLO, ahora)
        assertEquals(h.evaluaciones.toString(), 2, h.evaluaciones.size)
        val e = h.evaluaciones[0]
        assertEquals("Evaluación Geometría", e.que)
        assertEquals(0, e.clase)
        assertEquals(ms(23, 9, 0), Horario.msDe(e.cuando))
        assertEquals("Examen de Física", 1, h.evaluaciones[1].clase)
        assertEquals("el jueves", ms(15, 9, 0), Horario.msDe(h.evaluaciones[1].cuando))
    }

    @Test fun `caso negativo lo que no es clase no llama`() {
        for (t in listOf(
            "Geometría",                 // sin días ni horas
            "Lun Geometría",             // sin horas
            "8:00-10:00 Geometría",      // sin días
            "Lun 10:00-8:00 Geometría",  // al revés
            "Lun 25:00-26:00 Geometría", // horas que no existen
            "Lun 8-10",                  // sin nombre
            "Evaluación sin fecha",
            "Evaluación Física 1/1/2020" // ya pasó
        )) {
            val h = Horario.leer(t, ahora)
            assertTrue("$t: $h", h.clases.isEmpty() && h.evaluaciones.isEmpty())
        }
        // La plantilla no llama: el ejemplo va comentado.
        val p = Horario.leer(Horario.PLANTILLA, ahora)
        assertTrue(p.toString(), p.clases.isEmpty() && p.evaluaciones.isEmpty())
        assertEquals(10, p.antelacion)
    }

    @Test fun `una tabla pegada de filas o cuadricula`() {
        val filas = "| Día | Hora | Curso | Aula |\n|---|---|---|---|\n| Lunes | 8:00-10:00 | Geometría | A-12 |\n| Mie | 8:00-10:00 | Geometría | A-12 |"
        var h = Horario.leer(filas, ahora)
        assertEquals(h.clases.toString(), 2, h.clases.size)
        assertEquals("Geometría" to "A-12", h.clases[0].nombre to h.clases[0].aula)
        // La de los colegios, pegada de Excel (tabuladores).
        val cuadricula = "Hora\tLunes\tMartes\tMiércoles\n8:00-10:00\tGeometría\t\tGeometría\n10:15-12:00\t\tFísica @B2\t"
        h = Horario.leer(cuadricula, ahora)
        assertEquals(h.clases.toString(), 2, h.clases.size)
        assertEquals("Geometría" to listOf(0, 2), h.clases[0].nombre to h.clases[0].dias)
        assertEquals(listOf(1) to "B2", h.clases[1].dias to h.clases[1].aula)
    }

    @Test fun `cada aviso toca su antelacion antes y las evaluaciones 3 y 1 dia antes`() {
        val h = Horario.leer(EJEMPLO, ahora)
        // Desde el miércoles 14 a las 10:30, dos días por delante.
        var v = Horario.avisos(h, ms(14, 10, 30), 2)
        val horas = v.map {
            it.cuando to when (val q = it.que) {
                is Que.DeClase -> h.clases[q.i].nombre
                is Que.DeEvaluacion -> "${h.evaluaciones[q.j].que}-${q.dias}"
            }
        }
        assertEquals(
            "la Geometría de hoy (7:45) y el aviso del examen del jueves (hoy 9:00) ya pasaron; el Inglés del viernes cae fuera de los dos días",
            listOf(ms(14, 13, 45) to "Inglés", ms(15, 10, 0) to "Física", ms(15, 13, 45) to "Inglés"),
            horas
        )
        // La evaluación del 23: el 20 y el 22 a las 9.
        v = Horario.avisos(h, ms(19, 0, 0), 4)
        val deEval = v.filter { (it.que as? Que.DeEvaluacion)?.j == 0 }.map { it.cuando }
        assertEquals(listOf(ms(20, 9, 0), ms(22, 9, 0)), deEval)
        // Y el id lleva al mismo aviso aunque ya haya vencido.
        val a = v[0]
        assertEquals(a, Horario.avisoDe(h, a.id, a.cuando + 60_000))
        assertEquals(a, Horario.avisoDe(h, "${a.id}|123", a.cuando))
        assertNull(Horario.avisoDe(h, "horario:clase:1:Nada", a.cuando))
    }

    @Test fun `la nota de voz es de esta clase si se grabo tras la anterior`() {
        val h = Horario.leer(EJEMPLO, ahora)
        val g = h.clases[0] // lunes y miércoles a las 8
        val lunes19 = ms(19, 8, 0)
        assertEquals("el miércoles de antes", ms(14, 8, 0), Horario.sesionAnterior(g, lunes19))
        assertTrue("grabada en la clase del miércoles", Horario.notaVigente(g, lunes19, ms(14, 9, 30)))
        assertFalse("la del lunes pasado ya no", Horario.notaVigente(g, lunes19, ms(12, 9, 0)))
        // La llamada: con nota, nombre, hora, aula y lo que lee la voz.
        val a = Horario.avisos(h, ms(18, 12, 0), 2).first { (it.que as? Que.DeClase)?.i == 0 }
        val voz = File("C:/voz")
        var l = Horario.llamadaDe(h, a, voz) { ms(14, 9, 30) }
        assertEquals("Geometría", l.nombre)
        assertEquals(File(voz, "geometria.m4a"), l.audio)
        assertTrue(l.detalle, l.detalle.startsWith("08:00–10:00 · A-12"))
        assertTrue("la del 23, en esa semana: ${l.detalle}", l.detalle.contains("📝 Evaluación Geometría"))
        assertTrue(l.texto!!.startsWith("traer el compás. repasar el tema 3"))
        assertTrue(l.esDeHorario())
        // Sin nota vigente, solo la voz de la app.
        l = Horario.llamadaDe(h, a, voz) { ms(1, 9, 0) }
        assertNull(l.audio)
        l = Horario.llamadaDe(h, a, voz) { null }
        assertNull(l.audio)
    }

    @Test fun `la nota se graba para la clase en marcha o la ultima o la proxima`() {
        val h = Horario.leer(EJEMPLO, ahora)
        assertEquals("en Geometría", 0, Horario.claseParaGrabar(h, ms(14, 9, 0)))
        assertEquals("acaba de salir", 0, Horario.claseParaGrabar(h, ms(14, 11, 0)))
        assertEquals("jueves, en Física", 1, Horario.claseParaGrabar(h, ms(15, 11, 0)))
        assertEquals("sábado: la próxima es Geometría del lunes", 0, Horario.claseParaGrabar(h, ms(17, 9, 0)))
        assertNull(Horario.claseParaGrabar(Horario.Leido(), ms(14, 9, 0)))
        assertEquals("geometria-analitica-ii.m4a", Horario.ficheroDeVoz("Geometría Analítica II"))
    }
}
