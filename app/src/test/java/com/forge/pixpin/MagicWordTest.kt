package com.forge.pixpin.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MagicWordTest {

    @Test
    fun `croquis, cad y sketch abren el croquis`() {
        assertEquals(MiniApp.CROQUIS, MagicWord.detect("croquis"))
        assertEquals(MiniApp.CROQUIS, MagicWord.detect("cad"))
        assertEquals(MiniApp.CROQUIS, MagicWord.detect("sketch"))
        assertEquals(MiniApp.CROQUIS, MagicWord.detect("  CROQUIS "))
    }

    @Test
    fun `plano no es palabra magica, se copia solo demasiado a menudo`() {
        assertNull(MagicWord.detect("plano"))
    }

    @Test
    fun `croquis dentro de una frase sigue siendo texto`() {
        assertNull(MagicWord.detect("hazme un croquis"))
    }

    @Test
    fun `reconoce las palabras en cualquier combinacion de mayusculas`() {
        assertEquals(MiniApp.TIMER, MagicWord.detect("time"))
        assertEquals(MiniApp.TIMER, MagicWord.detect("TIME"))
        assertEquals(MiniApp.TIMER, MagicWord.detect("TiMe"))
        assertEquals(MiniApp.COUNTER, MagicWord.detect("Contador"))
        assertEquals(MiniApp.CHECKLIST, MagicWord.detect("TODO"))
        assertEquals(MiniApp.LEDGER, MagicWord.detect("Gastos"))
        assertEquals(MiniApp.BOARD, MagicWord.detect("PIZARRA"))
        assertEquals(MiniApp.STOPWATCH, MagicWord.detect("Crono"))
        assertEquals(MiniApp.STOPWATCH, MagicWord.detect("STOPWATCH"))
    }

    /** El cronómetro y el temporizador son palabras distintas y no se confunden. */
    @Test
    fun `crono y time no son lo mismo`() {
        assertEquals(MiniApp.STOPWATCH, MagicWord.detect("cronometro"))
        assertEquals(MiniApp.TIMER, MagicWord.detect("time"))
    }

    @Test
    fun `los espacios de alrededor no estorban`() {
        assertEquals(MiniApp.TIMER, MagicWord.detect("  time  "))
        assertEquals(MiniApp.TIMER, MagicWord.detect("\ntime\n"))
    }

    /**
     * La razón de ser de la regla: copiar una de estas palabras dentro de una
     * frase tiene que seguir dando un pin de texto normal.
     */
    @Test
    fun `dentro de una frase no activa nada`() {
        assertNull(MagicWord.detect("el time es oro"))
        assertNull(MagicWord.detect("lista de compras"))
        assertNull(MagicWord.detect("gastos de enero"))
        assertNull(MagicWord.detect("todo listo"))
    }

    @Test
    fun `una palabra corriente no activa nada`() {
        assertNull(MagicWord.detect("hola"))
        assertNull(MagicWord.detect("nota"))
        assertNull(MagicWord.detect("lista"))
    }

    @Test
    fun `texto vacio o nulo no activa nada`() {
        assertNull(MagicWord.detect(null))
        assertNull(MagicWord.detect(""))
        assertNull(MagicWord.detect("   "))
    }

    /** Un texto largo no debe siquiera mirarse palabra a palabra. */
    @Test
    fun `un texto largo no activa nada`() {
        assertNull(MagicWord.detect("time".repeat(50)))
    }
}
