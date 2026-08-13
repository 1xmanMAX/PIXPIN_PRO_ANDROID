package com.forge.pixpin.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * En qué orden —y sobre todo **cuáles**— salen los tipos en la lista de pines.
 *
 * Esto no es una prueba de estética. La lista de pines es la única salida cuando
 * dejas un pin atravesable: desde ese momento el pin no responde al dedo, y si
 * su tipo no sale en la lista no hay ningún sitio desde el que cerrarlo ni desde
 * el que quitarle lo de atravesable. Un tipo que se cae de aquí es un pin que se
 * queda en la pantalla para siempre.
 *
 * Pasó de verdad: el orden estaba escrito a mano con cuatro tipos y se recorría
 * él, así que hacía de filtro. El temporizador, la lista, el contador, las
 * cuentas, la tabla, el lienzo y la ruleta no salían nunca.
 */
class ListaDePinesTest {

    /** La que importa: **no se puede quedar ninguno fuera**. */
    @Test
    fun `salen todos los tipos de pin`() {
        assertEquals(PinType.entries.toSet(), ordenDeTipos().toSet())
    }

    @Test
    fun `ninguno sale dos veces`() {
        val orden = ordenDeTipos()
        assertEquals(orden.size, orden.toSet().size)
        assertEquals(PinType.entries.size, orden.size)
    }

    @Test
    fun `los preferidos van primero y en su orden`() {
        val orden = ordenDeTipos()
        assertEquals(
            listOf(PinType.IMAGE, PinType.TEXT, PinType.COLOR, PinType.FILE),
            orden.take(4)
        )
    }

    /** Los que no se nombran no desaparecen: se van al final. */
    @Test
    fun `lo que no se prefiere va detras`() {
        val orden = ordenDeTipos(listOf(PinType.RULETA))
        assertEquals(PinType.RULETA, orden.first())
        assertEquals(PinType.entries.toSet(), orden.toSet())
    }

    @Test
    fun `sin preferencias siguen saliendo todos`() {
        assertEquals(PinType.entries, ordenDeTipos(emptyList()))
    }

    /** Repetir un tipo en las preferencias no lo duplica en la lista. */
    @Test
    fun `una preferencia repetida no duplica`() {
        val orden = ordenDeTipos(listOf(PinType.TEXT, PinType.TEXT))
        assertEquals(orden.size, orden.toSet().size)
        assertEquals(PinType.TEXT, orden.first())
    }

    /**
     * Las mini-aplicaciones tienen que estar sí o sí: son las que más fácil se
     * quedan fuera de una lista escrita a mano, porque se añaden después.
     */
    @Test
    fun `las mini-aplicaciones estan en la lista`() {
        val orden = ordenDeTipos()
        listOf(
            PinType.TIMER, PinType.CHECKLIST, PinType.COUNTER,
            PinType.LEDGER, PinType.TABLE, PinType.DRAW, PinType.RULETA
        ).forEach {
            assertTrue("$it no sale en la lista de pines", it in orden)
        }
    }
}
