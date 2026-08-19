package com.forge.pixpin.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Las dos cuentas de las notas de voz que se pueden comprobar sin micrófono.
 *
 * Grabar y sonar son de Android, pero **a qué hora salta un recordatorio** no lo es, y es
 * justo lo que no puede fallar: un aviso que suena a la hora que no toca —o que suena en
 * el acto al ponerlo— es peor que no tener aviso.
 */
class VozTest {

    private val madrid = TimeZone.getTimeZone("Europe/Madrid")

    private fun momento(dia: Int, hora: Int, minuto: Int): Long {
        val c = Calendar.getInstance(madrid)
        c.set(2026, Calendar.MARCH, dia, hora, minuto, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun horaDe(ms: Long): Pair<Int, Int> {
        val c = Calendar.getInstance(madrid)
        c.timeInMillis = ms
        return c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
    }

    /** «A las diez» cuando son las nueve es hoy. */
    @Test
    fun `una hora que aun no ha pasado es hoy`() {
        val ahora = momento(10, 9, 0)
        val cuando = proximaVezQueSean(ahora, 10, 0, madrid)
        assertEquals(10 to 0, horaDe(cuando))
        assertTrue(cuando - ahora in 1..(60 * 60 * 1000L))
    }

    /**
     * «A las diez» cuando son las once es **mañana**.
     *
     * Sin esto, un recordatorio puesto a las once para las diez saltaría en el acto, que es
     * la forma más rápida de que alguien deje de usar los recordatorios.
     */
    @Test
    fun `una hora que ya paso es manana`() {
        val ahora = momento(10, 11, 0)
        val cuando = proximaVezQueSean(ahora, 10, 0, madrid)
        assertEquals(10 to 0, horaDe(cuando))
        assertTrue("no ha saltado a mañana", cuando > ahora)
        assertTrue(cuando - ahora > 22 * 60 * 60 * 1000L)
    }

    /** La hora exacta también cuenta como pasada: si no, sonaría al ponerla. */
    @Test
    fun `la hora justa es manana`() {
        val ahora = momento(10, 10, 0)
        assertTrue(proximaVezQueSean(ahora, 10, 0, madrid) > ahora)
    }

    /** Los segundos se ponen a cero: un aviso salta en punto, no a los 37 segundos. */
    @Test
    fun `salta en punto`() {
        val c = Calendar.getInstance(madrid)
        c.timeInMillis = proximaVezQueSean(momento(10, 9, 30), 10, 0, madrid)
        assertEquals(0, c.get(Calendar.SECOND))
        assertEquals(0, c.get(Calendar.MILLISECOND))
    }

    /** La duración se lee como se espera: nunca `0:60`. */
    @Test
    fun `la duracion se escribe bien`() {
        assertEquals("0:00", duracionLegible(0))
        assertEquals("0:07", duracionLegible(7_400))
        assertEquals("1:00", duracionLegible(60_000))
        assertEquals("1:24", duracionLegible(84_900))
        assertEquals("0:00", duracionLegible(-5))
    }
}
