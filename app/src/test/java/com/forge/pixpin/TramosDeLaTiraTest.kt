package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las miniaturas pequeñas juntadas por tanda. Ver [TramosDeLaTira]. */
class TramosDeLaTiraTest {

    private fun pdf(n: Int) = HojasDelProyecto.Pagina(Hoja(id = "p$n", pagina = n))
    private fun marco(dibujo: String, m: String) =
        HojasDelProyecto.Pagina(Hoja(id = "l-$dibujo", dibujo = dibujo), marco = m)
    private fun nota(id: String, i: Int) =
        HojasDelProyecto.Pagina(Hoja(id = id, nota = "texto"), nota = i, texto = "texto")

    @Test fun `las seguidas del mismo sitio se juntan en un monton`() {
        // El caso que contó el usuario: diez de un PDF, cinco de un lienzo, diez notas.
        val paginas = (0 until 10).map { pdf(it) } +
            (0 until 5).map { marco("d1", "m$it") } +
            (0 until 10).map { nota("n1", it) }
        val tramos = TramosDeLaTira.de(paginas)
        assertEquals("tres montones y nada más", 3, tramos.size)
        assertEquals(listOf(10, 5, 10), tramos.map { it.paginas.size })
        assertEquals(listOf(0, 10, 15), tramos.map { it.desde })
        assertTrue(tramos.all { it.montón })
    }

    @Test fun `dos lienzos seguidos son dos montones y no uno`() {
        // Son dos documentos: juntarlos escondería que se pasa de uno a otro.
        val paginas = (0 until 4).map { marco("d1", "m$it") } + (0 until 4).map { marco("d2", "m$it") }
        val tramos = TramosDeLaTira.de(paginas)
        assertEquals(2, tramos.size)
        assertEquals(listOf("lienzo:d1", "lienzo:d2"), tramos.map { it.deQue })
    }

    @Test fun `dos paginas del mismo documento ya son un monton`() {
        // El usuario lo pidió en dos (9-sep-2026): lo que se gana no es una miniatura, es que
        // un documento ocupe un sitio y se vea de un vistazo dónde empieza el siguiente.
        val tramos = TramosDeLaTira.de(listOf(pdf(0), pdf(1)))
        assertEquals(1, tramos.size)
        assertTrue(tramos.single().montón)
        assertEquals(2, tramos.single().paginas.size)
    }

    @Test fun `una hoja sola no se pliega`() {
        // Un montón de una no es un montón: es una hoja con un toque de más para verla.
        val tramos = TramosDeLaTira.de(listOf(pdf(0), marco("d1", "m0"), nota("n1", 0)))
        assertEquals(3, tramos.size)
        assertTrue(tramos.none { it.montón })
        assertEquals(listOf(0, 1, 2), tramos.map { it.desde })
    }

    @Test fun `un monton abierto sale suelto y en su sitio`() {
        val paginas = (0 until 4).map { pdf(it) } + (0 until 4).map { marco("d1", "m$it") }
        val tramos = TramosDeLaTira.de(paginas, abiertos = setOf(paginas[0].clave))
        assertEquals("las cuatro del PDF sueltas, más el montón del lienzo", 5, tramos.size)
        assertEquals(listOf(0, 1, 2, 3, 4), tramos.map { it.desde })
        assertTrue("el lienzo sigue plegado", tramos.last().montón)
    }

    @Test fun `las posiciones apuntan a la lista de verdad`() {
        // Es lo que hace que tocar la tercera del montón abra la tercera y no otra.
        val paginas = (0 until 6).map { pdf(it) }
        val tramos = TramosDeLaTira.de(paginas, abiertos = setOf(paginas[0].clave))
        tramos.forEachIndexed { i, t -> assertEquals(paginas[i].clave, t.primera.clave) }
    }

    @Test fun `una tira vacia no da tramos`() {
        assertTrue(TramosDeLaTira.de(emptyList()).isEmpty())
    }
    @Test fun `dos pdf en el mismo proyecto son dos montones`() {
        // Una hoja de PDF no guarda de qué PDF sale, pero sí su número: las páginas de un
        // documento entran seguidas y el siguiente vuelve a empezar. Donde la cuenta se rompe,
        // empieza otro montón. Lo pidió el usuario: «cada PDF individual se agrupa».
        val paginas = (0 until 4).map { pdf(it) } + (0 until 3).map { pdf(it) }
        val tramos = TramosDeLaTira.de(paginas)
        assertEquals(2, tramos.size)
        assertEquals(listOf(4, 3), tramos.map { it.paginas.size })
        assertEquals(listOf(0, 4), tramos.map { it.desde })
    }

    @Test fun `un salto en la numeracion tambien parte el monton`() {
        // Una página suelta sacada de otro documento no se cuela en el montón del primero.
        val tramos = TramosDeLaTira.de(listOf(pdf(0), pdf(1), pdf(7), pdf(8)))
        assertEquals(2, tramos.size)
    }

}
