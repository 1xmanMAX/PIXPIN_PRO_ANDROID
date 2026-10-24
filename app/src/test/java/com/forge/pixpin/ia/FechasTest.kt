package com.forge.pixpin.ia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Las pruebas de `fechas.rs` del PC, portadas tal cual (miércoles 14-oct-2026, 10:30). */
class FechasTest {
    private val ahora = Momento(Fechas.diasDe(2026, 10, 14), 10 * 60 + 30)

    private fun en(frase: String): String = try { Fechas.interpretar(frase, ahora).marca() } catch (e: ErrorFecha) { fail("«$frase»: ${e.message}"); "" }

    @Test fun `el calendario es el de siempre`() {
        assertEquals(2L, Fechas.diaDeLaSemana(Fechas.diasDe(2026, 10, 14)))
        assertTrue(Fechas.existe(2028, 2, 29) && !Fechas.existe(2026, 2, 29) && !Fechas.existe(2026, 2, 31) && !Fechas.existe(2026, 13, 1))
        assertEquals(Triple(2026L, 12L, 31L), Fechas.fechaDe(Fechas.diasDe(2026, 12, 31)))
    }

    @Test fun `lo que dice la gente`() {
        assertEquals("⏰ 2026-10-15 09:00", en("mañana"))
        assertEquals("⏰ 2026-10-15 10:00", en("mañana a las 10"))
        assertEquals("⏰ 2026-10-15 17:00", en("para mañana a las 5 de la tarde"))
        assertEquals("⏰ 2026-10-16 09:00", en("pasado mañana"))
        assertEquals("⏰ 2026-10-14 18:30", en("hoy a las 18:30"))
        assertEquals("⏰ 2026-10-14 17:00", en("esta tarde"))
        assertEquals("⏰ 2026-10-16 09:00", en("el viernes"))
        assertEquals("⏰ 2026-10-21 09:00", en("el miércoles"))
        assertEquals("⏰ 2026-10-19 17:00", en("el lunes por la tarde"))
        assertEquals("⏰ 2026-10-19 09:00", en("la semana que viene"))
        assertEquals("⏰ 2026-10-17 09:00", en("en 3 días"))
        assertEquals("⏰ 2026-10-28 09:00", en("dentro de dos semanas"))
        assertEquals("⏰ 2026-10-20 09:00", en("20/10"))
        assertEquals("⏰ 2026-10-20 08:15", en("el 20/10/2026 a las 8:15"))
        assertEquals("⏰ 2026-11-03 16:00", en("2026-11-03 16:00"))
        assertEquals("⏰ 2026-11-03 09:00", en("el 3 de noviembre"))
        assertEquals("⏰ 2027-01-05 09:00", en("el 5 de enero"))
        assertEquals("⏰ 2026-10-14 12:00", en("a las 12"))
        assertEquals("⏰ 2026-10-15 09:00", en("a las 9"))
        assertEquals("⏰ 2026-10-15 10:30", en("mañana a las 10 y media"))
    }

    /** El 18-oct-2026 del PC: día, mes y hora sin año son del año en curso; si ya pasó, del siguiente. */
    @Test fun `dia mes y hora como se apuntan en una tarea`() {
        assertEquals("⏰ 2026-10-18 17:00", en("18/10 17:00"))
        assertEquals("⏰ 2026-10-18 17:00", en("18-10 5pm"))
        assertEquals("⏰ 2026-10-18 17:30", en("18-10 5:30pm"))
        assertEquals("⏰ 2026-10-18 17:00", en("18 oct 17:00"))
        assertEquals("⏰ 2026-10-20 09:00", en("el 20 a las 9"))
        assertEquals("«el 20» es un día, no las 20:00", "⏰ 2026-10-20 09:00", en("el 20"))
        assertEquals("⏰ 2026-10-20 08:30", en("20 de octubre 8:30"))
        assertEquals("⏰ 2026-10-15 07:00", en("mañana 7"))
        assertEquals("⏰ 2026-10-16 15:30", en("viernes 15:30"))
        assertEquals("⏰ 2026-10-15 08:00", en("a las 8am"))
        // Ya pasado este año: el que viene; «el 3» ya pasado: el mes que viene.
        assertEquals("⏰ 2027-10-02 17:00", en("2/10 17:00"))
        assertEquals("⏰ 2027-10-13 09:00", en("13 oct"))
        assertEquals("⏰ 2026-11-03 10:00", en("el 3 a las 10"))
        // «el 31» en un mes de 30 días no existe: el siguiente que lo tenga.
        val finDeNov = Momento(Fechas.diasDe(2026, 11, 2), 600)
        assertEquals("⏰ 2026-12-31 09:00", Fechas.interpretar("el 31", finDeNov).marca())
        // Negativos: sigue sin adivinar.
        for (f in listOf("el 32", "18/13 17:00", "5xm"))
            assertTrue(f, runCatching { Fechas.interpretar(f, ahora) }.isFailure)
        assertTrue(runCatching { Fechas.interpretar("el 32", ahora) }.exceptionOrNull() is ErrorFecha.NoExiste)
    }

    @Test fun `lo imposible y lo pasado no se adivinan`() {
        for (f in listOf("el 31 de febrero", "30/02/2027", "mañana a las 25:00", "el 32 de octubre"))
            assertTrue(f, runCatching { Fechas.interpretar(f, ahora) }.exceptionOrNull() is ErrorFecha.NoExiste)
        assertTrue(runCatching { Fechas.interpretar("el 1/1/2020", ahora) }.exceptionOrNull() is ErrorFecha.Pasada)
        for (f in listOf("cuando pueda", "algun dia", "el jueves o el viernes", "", "mañana y pasado mañana"))
            assertTrue(f, runCatching { Fechas.interpretar(f, ahora) }.exceptionOrNull() is ErrorFecha.NoEntiendo)
    }

    @Test fun `la fecha se separa del final`() {
        val (t, m) = Fechas.separar("llamar al banco mañana a las 10", ahora)
        assertEquals("llamar al banco", t); assertEquals("⏰ 2026-10-15 10:00", m!!.marca())
        val (t2, m2) = Fechas.separar("revisar planos para el viernes", ahora)
        assertEquals("revisar planos", t2); assertEquals("⏰ 2026-10-16 09:00", m2!!.marca())
        assertEquals("pedir el cemento" to null, Fechas.separar("pedir el cemento", ahora))
        assertNull(Fechas.separar("comprar 3 sacos", ahora).second)
        assertTrue(runCatching { Fechas.separar("pagar el alquiler el 31 de febrero", ahora) }.isFailure)
    }

    @Test fun `la marca va y vuelve`() {
        val m = Fechas.interpretar("mañana a las 10", ahora)
        assertEquals(m, Momento.deMarca("llamar ${m.marca()}"))
        assertNull(Momento.deMarca("llamar ⏰ 2026-02-31 10:00"))
        assertEquals("mañana 10:00", m.legible(ahora))
    }
}
