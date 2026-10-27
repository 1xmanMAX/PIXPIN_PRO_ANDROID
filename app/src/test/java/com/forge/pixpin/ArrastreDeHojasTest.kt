package com.forge.pixpin

import com.forge.pixpin.guardados.CarpetaDePaginas
import com.forge.pixpin.guardados.OrdenDeHojas
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * **El asa de las hojas del cuaderno** (27-oct-2026): arrastrar una hoja de un puesto a otro tiene
 * que dejar exactamente el documento que dejaban las flechas tocadas tantas veces como puestos.
 */
class ArrastreDeHojasTest {

    private fun cuaderno(n: Int) = CarpetaDePaginas(
        paginas = (1..n).map { CarpetaDePaginas.Pagina("p$it", "Día $it") },
        // Algo que no se entiende, para ver que viaja igual.
        resto = mapOf("delPc" to JsonPrimitive("x"))
    )

    /** Lo que hacían las flechas: «Antes» o «Después» tocadas una vez por puesto. */
    private fun conFlechas(c: CarpetaDePaginas, desde: Int, hasta: Int): CarpetaDePaginas {
        var x = c
        var i = desde
        while (i > hasta) { x = x.subir(i)!!; i-- }
        while (i < hasta) { x = x.bajar(i)!!; i++ }
        return x
    }

    @Test fun mover_lleva_el_elemento_a_su_puesto() {
        val l = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("b", "c", "d", "a", "e"), OrdenDeHojas.mover(l, 0, 3))
        assertEquals(listOf("a", "e", "b", "c", "d"), OrdenDeHojas.mover(l, 4, 1))
        assertEquals(listOf("a", "c", "b", "d", "e"), OrdenDeHojas.mover(l, 1, 2))
        // Un destino de más cae al final; uno de menos, al principio.
        assertEquals(listOf("a", "c", "d", "e", "b"), OrdenDeHojas.mover(l, 1, 99))
        assertEquals(listOf("c", "a", "b", "d", "e"), OrdenDeHojas.mover(l, 2, -3))
        // Sin moverse o fuera de rango, la misma lista.
        assertSame(l, OrdenDeHojas.mover(l, 2, 2))
        assertSame(l, OrdenDeHojas.mover(l, 7, 0))
        assertSame(l, OrdenDeHojas.mover(l, -1, 0))
        assertEquals(emptyList<String>(), OrdenDeHojas.mover(emptyList<String>(), 0, 0))
    }

    @Test fun una_tanda_de_cambios_al_arrastrar_acaba_donde_se_suelta() {
        // Lo que pasa en pantalla: la hoja va cambiando con su vecina, puesto a puesto.
        var l = listOf("p1", "p2", "p3", "p4", "p5")
        for (i in 0 until 4) l = OrdenDeHojas.mover(l, i, i + 1)
        assertEquals(listOf("p2", "p3", "p4", "p5", "p1"), l)
        assertEquals(OrdenDeHojas.mover(listOf("p1", "p2", "p3", "p4", "p5"), 0, 4), l)
    }

    @Test fun soltar_escribe_lo_mismo_que_las_flechas() {
        val c = cuaderno(6)
        for (desde in 0 until c.size) for (hasta in 0 until c.size) {
            val soltada = OrdenDeHojas.soltar(c, "p${desde + 1}", hasta)
            if (desde == hasta) {
                assertNull(soltada)
                continue
            }
            val flechas = conFlechas(c, desde, hasta)
            assertEquals("$desde→$hasta", flechas.escribir(), soltada!!.escribir())
            // Y el orden es el que se veía al arrastrar.
            assertEquals(OrdenDeHojas.mover(c.paginas.map { it.id }, desde, hasta), soltada.paginas.map { it.id })
        }
    }

    @Test fun soltar_una_hoja_que_ya_no_esta_no_escribe() {
        assertNull(OrdenDeHojas.soltar(cuaderno(3), "otra", 0))
    }

    @Test fun el_orden_del_arrastre_solo_manda_con_las_mismas_hojas() {
        val l = listOf("a" to 1, "b" to 2, "c" to 3)
        assertEquals(listOf("c" to 3, "a" to 1, "b" to 2), OrdenDeHojas.segun(l, listOf("c", "a", "b")) { it.first })
        assertSame(l, OrdenDeHojas.segun(l, null) { it.first })
        // La sincronización trajo o quitó una: manda lo que llega.
        assertSame(l, OrdenDeHojas.segun(l, listOf("c", "a")) { it.first })
        assertSame(l, OrdenDeHojas.segun(l, listOf("c", "a", "z")) { it.first })
    }
}
