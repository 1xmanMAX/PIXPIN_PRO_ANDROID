package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * **El número con el que se nombra un mensaje** y **la hora de un recordatorio**.
 *
 * El número no se reutiliza: borrar el 47 deja el 47 vacío para siempre, porque quien apuntó
 * «el 47» tiene que encontrar eso y no otra cosa. Lo pidió el usuario (7-sep-2026).
 */
class NumeroYRecordatorioTest {

    private fun m(id: String, cuando: Long, numero: Int = 0, proyecto: String? = null) =
        Mensaje(id = id, cuando = cuando, clase = Clase.NOTA, texto = id, numero = numero, proyecto = proyecto)

    @Test
    fun `manda el numero guardado, y un hueco sigue siendo un hueco`() {
        // Se borró el 2: el 3 sigue siendo el 3.
        val lista = listOf(m("a", 10, 1), m("c", 30, 3), m("d", 40, 4))
        val n = numerar(lista)
        assertEquals(1, n["a"]); assertEquals(3, n["c"]); assertEquals(4, n["d"])
    }

    @Test
    fun `los de antes se cuentan por su sitio`() {
        val lista = listOf(m("b", 20), m("a", 10), m("c", 30))
        val n = numerar(lista)
        assertEquals(1, n["a"]); assertEquals(2, n["b"]); assertEquals(3, n["c"])
    }

    /** Cada conversación lleva su cuenta: el 1 de un proyecto no es el 1 del general. */
    @Test
    fun `la cuenta es por conversacion`() {
        val lista = listOf(m("a", 10), m("b", 20), m("x", 15, proyecto = "p1"), m("y", 25, proyecto = "p1"))
        val n = numerar(lista)
        assertEquals(1, n["a"]); assertEquals(2, n["b"])
        assertEquals(1, n["x"]); assertEquals(2, n["y"])
    }

    /** Ninguno de los atajos puede caer en el pasado, y todos dicen a qué hora. */
    @Test
    fun `los atajos de la hora caen siempre por delante`() {
        // Las once de la noche: «esta tarde» ya pasó, así que tiene que caer mañana.
        val once = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val atajos = atajosDeRecordatorio(once)
        assertEquals(4, atajos.size)
        for ((texto, cuando) in atajos) {
            assertTrue("«$texto» tiene que decir algo", texto.isNotBlank())
            assertTrue("«$texto» cayó en el pasado", cuando > once)
        }
        // Y a mediodía, «esta tarde» es hoy: menos de doce horas por delante.
        val mediodia = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val tarde = atajosDeRecordatorio(mediodia)[2].second
        assertTrue(tarde - mediodia in 1..(12 * 60 * 60 * 1000L))
    }
}
