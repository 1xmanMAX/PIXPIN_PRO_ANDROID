package com.forge.pixpin.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MagicWordTest {

    /**
     * El croquis se retiró: medir es ahora dos herramientas del motor. Sus
     * palabras dejan de abrir nada en vez de abrir otra cosa — quien copiaba
     * «cad» esperaba el editor de tipo CAD, y darle un lienzo en blanco sería
     * más desconcertante que no darle nada.
     */
    @Test
    fun `las palabras del croquis retirado ya no abren nada`() {
        assertNull(MagicWord.detect("croquis"))
        assertNull(MagicWord.detect("cad"))
        assertNull(MagicWord.detect("sketch"))
    }

    @Test
    fun `plano no es palabra magica, se copia solo demasiado a menudo`() {
        assertNull(MagicWord.detect("plano"))
    }

    @Test
    fun `canvas, lienzo y excalidraw abren el lienzo infinito`() {
        assertEquals(MiniApp.DRAW, MagicWord.detect("canvas"))
        assertEquals(MiniApp.DRAW, MagicWord.detect("lienzo"))
        assertEquals(MiniApp.DRAW, MagicWord.detect("Excalidraw"))
    }

    /**
     * Mismo criterio que «plano»: se copian solas demasiado a menudo y
     * perderlas como pin de texto costaría más de lo que aporta el atajo.
     */
    @Test
    fun `draw y dibujo se quedaron fuera a proposito`() {
        assertNull(MagicWord.detect("draw"))
        assertNull(MagicWord.detect("dibujo"))
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

    // ---- Las que pone el usuario ----

    @Test
    fun `sin nada guardado mandan las de fabrica`() {
        assertEquals(MagicWord.POR_DEFECTO, MagicWord.leer(null))
    }

    /** Vacío no es «no lo he tocado»: es «no quiero ninguna», y se respeta. */
    @Test
    fun `sin ninguna palabra no se abre nada`() {
        val ninguna = MagicWord.leer("")
        assertTrue(ninguna.isEmpty())
        assertNull(MagicWord.detect("time", ninguna))
    }

    @Test
    fun `lo guardado se vuelve a leer igual`() {
        val escrito = MagicWord.escribir(MagicWord.POR_DEFECTO)
        assertEquals(MagicWord.POR_DEFECTO, MagicWord.leer(escrito))
    }

    @Test
    fun `una palabra propia abre su herramienta`() {
        val mias = MagicWord.leer("reloj=TIMER\nsorteito=RULETA")
        assertEquals(MiniApp.TIMER, MagicWord.detect("Reloj", mias))
        assertEquals(MiniApp.RULETA, MagicWord.detect("sorteito", mias))
        // Y lo de fábrica ya no vale: se dijo cuáles son.
        assertNull(MagicWord.detect("time", mias))
    }

    /** Una línea rota no puede tirar el resto de los ajustes. */
    @Test
    fun `lo que no se entiende se salta`() {
        val mias = MagicWord.leer("reloj=TIMER\nbasura\n=TIMER\nx=NOEXISTE\ndos palabras=TIMER")
        assertEquals(mapOf("reloj" to MiniApp.TIMER), mias)
    }

    @Test
    fun `una palabra solo puede abrir una cosa`() {
        val antes = mapOf("x" to MiniApp.TIMER)
        val despues = MagicWord.conLasDe(antes, MiniApp.RULETA, listOf("x"))
        assertEquals(MiniApp.RULETA, despues["x"])
        assertEquals(1, despues.size)
    }

    @Test
    fun `cambiar las de una herramienta deja las demas`() {
        val antes = mapOf("time" to MiniApp.TIMER, "crono" to MiniApp.STOPWATCH)
        val despues = MagicWord.conLasDe(antes, MiniApp.TIMER, listOf("reloj", "temporizador"))
        assertEquals(MiniApp.STOPWATCH, despues["crono"])
        assertNull(despues["time"])
        assertEquals(listOf("reloj", "temporizador"), MagicWord.deLaApp(despues, MiniApp.TIMER))
    }

    @Test
    fun `al guardar se limpian los espacios, las mayusculas y lo repetido`() {
        val r = MagicWord.conLasDe(
            emptyMap(), MiniApp.RULETA, listOf("  Sorteo ", "sorteo", "", "dos palabras", "azar")
        )
        assertEquals(listOf("sorteo", "azar"), MagicWord.deLaApp(r, MiniApp.RULETA))
    }

    /** Quitarle todas las palabras a una herramienta es una respuesta válida. */
    @Test
    fun `una herramienta se puede quedar sin palabras`() {
        val r = MagicWord.conLasDe(MagicWord.POR_DEFECTO, MiniApp.TIMER, emptyList())
        assertTrue(MagicWord.deLaApp(r, MiniApp.TIMER).isEmpty())
        assertNull(MagicWord.detect("time", r))
        assertEquals(MiniApp.STOPWATCH, MagicWord.detect("crono", r))
    }

    private fun assertTrue(v: Boolean) = org.junit.Assert.assertTrue(v)
}
