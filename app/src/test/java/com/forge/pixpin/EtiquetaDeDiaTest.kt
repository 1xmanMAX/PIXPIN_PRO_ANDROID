package com.forge.pixpin.guardados

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Las esquinas de «hoy» y «ayer».
 *
 * Se prueban con marcas construidas a mano y una zona fija, porque el fallo de estas
 * cuentas no es equivocarse siempre: es equivocarse **el día que cambia la hora, el 1 de
 * enero y a las dos de la madrugada**, que son los tres momentos en que nadie lo mira.
 */
class EtiquetaDeDiaTest {

    private val madrid: TimeZone = TimeZone.getTimeZone("Europe/Madrid")

    private fun momento(
        ano: Int, mes: Int, dia: Int, hora: Int = 12, minuto: Int = 0,
        zona: TimeZone = madrid
    ): Long = Calendar.getInstance(zona).apply {
        clear()
        set(ano, mes - 1, dia, hora, minuto, 0)
    }.timeInMillis

    @Test
    fun `lo de esta manana es de hoy`() {
        val ahora = momento(2026, 8, 17, hora = 20)
        val antes = momento(2026, 8, 17, hora = 9)
        assertEquals(NombreDelDia.HOY, nombreDelDia(antes, ahora, madrid))
    }

    @Test
    fun `lo del dia anterior es de ayer`() {
        val ahora = momento(2026, 8, 17)
        val antes = momento(2026, 8, 16)
        assertEquals(NombreDelDia.AYER, nombreDelDia(antes, ahora, madrid))
    }

    /**
     * A las dos de la madrugada, lo de hace tres horas es de ayer aunque no hayan
     * pasado veinticuatro. Es el caso que rompe una resta de milisegundos.
     */
    @Test
    fun `de madrugada lo de hace tres horas ya es de ayer`() {
        val ahora = momento(2026, 8, 17, hora = 2)
        val antes = momento(2026, 8, 16, hora = 23)
        assertEquals(NombreDelDia.AYER, nombreDelDia(antes, ahora, madrid))
    }

    /** Y al revés: veinte horas antes puede seguir siendo hoy si se madrugó. */
    @Test
    fun `veinte horas antes puede seguir siendo hoy`() {
        val ahora = momento(2026, 8, 17, hora = 23)
        val antes = momento(2026, 8, 17, hora = 3)
        assertEquals(NombreDelDia.HOY, nombreDelDia(antes, ahora, madrid))
    }

    @Test
    fun `hace tres dias es de este ano`() {
        val ahora = momento(2026, 8, 17)
        val antes = momento(2026, 8, 14)
        assertEquals(NombreDelDia.ESTE_ANO, nombreDelDia(antes, ahora, madrid))
    }

    /** El 1 de enero, lo del 31 de diciembre es «ayer», no «de otro año». */
    @Test
    fun `el uno de enero lo de nochevieja sigue siendo ayer`() {
        val ahora = momento(2026, 1, 1, hora = 10)
        val antes = momento(2025, 12, 31, hora = 22)
        assertEquals(NombreDelDia.AYER, nombreDelDia(antes, ahora, madrid))
    }

    /** Pero lo del 30 de diciembre ya lleva año, aunque sean dos días. */
    @Test
    fun `dos dias atras cruzando el ano ya lleva el ano`() {
        val ahora = momento(2026, 1, 1)
        val antes = momento(2025, 12, 30)
        assertEquals(NombreDelDia.CON_ANO, nombreDelDia(antes, ahora, madrid))
    }

    /** Y en diciembre, lo de enero del mismo año no lleva año pese a los once meses. */
    @Test
    fun `once meses atras dentro del mismo ano no lleva ano`() {
        val ahora = momento(2026, 12, 20)
        val antes = momento(2026, 1, 15)
        assertEquals(NombreDelDia.ESTE_ANO, nombreDelDia(antes, ahora, madrid))
    }

    /**
     * El día que se adelanta la hora dura veintitrés horas. Restar un día natural sigue
     * cayendo en el día correcto; restar 24 horas en milisegundos, no.
     */
    @Test
    fun `el dia del cambio de hora ayer sigue siendo ayer`() {
        // En Madrid, la madrugada del 29 de marzo de 2026 se adelanta el reloj.
        val ahora = momento(2026, 3, 29, hora = 12)
        val antes = momento(2026, 3, 28, hora = 12)
        assertEquals(NombreDelDia.AYER, nombreDelDia(antes, ahora, madrid))
    }

    /**
     * La zona manda: el mismo instante puede ser de días distintos según dónde se mire,
     * y el separador tiene que hablar del día de quien está mirando.
     */
    @Test
    fun `la zona decide de que dia es`() {
        val ahora = momento(2026, 8, 17, hora = 10)
        // Las 23:30 de Madrid del día anterior son las 14:30 del mismo día en Los
        // Ángeles: para uno es «ayer» y para el otro también, pero el corte no cae igual.
        val antes = momento(2026, 8, 17, hora = 1)
        assertEquals(NombreDelDia.HOY, nombreDelDia(antes, ahora, madrid))
        val angeles = TimeZone.getTimeZone("America/Los_Angeles")
        assertEquals(NombreDelDia.AYER, nombreDelDia(antes, ahora, angeles))
    }

    @Test
    fun `lo del ano pasado lleva ano`() {
        val ahora = momento(2026, 8, 17)
        val antes = momento(2025, 8, 17)
        assertEquals(NombreDelDia.CON_ANO, nombreDelDia(antes, ahora, madrid))
    }
}
