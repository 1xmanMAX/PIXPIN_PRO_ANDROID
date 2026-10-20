package com.forge.pixpin.mini

import com.forge.pixpin.mini.Kanban.Pieza
import com.forge.pixpin.mini.TodasLasTareas.Grupo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las pruebas de `tareas/kanban.rs` del PC (18aaf99), menos las de las flechas del teclado. */
class KanbanTest {

    private fun medidas(ancho: Float) = Kanban.Medidas(
        ancho = ancho, columnaMin = 220f, columnaMax = 360f, hueco = 12f, relleno = 8f,
        encabezado = 40f, pliegue = 40f, entreBandas = 18f, filaHecha = 44f
    )

    private fun grupo(lista: Int, arriba: List<Int>, hechas: List<Int>) = Grupo(lista, arriba, hechas)

    private fun cajaDe(celdas: List<Kanban.Celda>, p: Pieza) = celdas.first { it.pieza == p }.caja

    @Test
    fun cada_lista_es_una_columna_con_su_encabezado_sus_tarjetas_y_sus_hechas() {
        val grupos = listOf(grupo(0, listOf(0, 1), listOf(2)), grupo(3, listOf(0), emptyList()))
        val (celdas, columnas, alto) = Kanban.disponer(grupos, { true }, { _, _, _ -> 100f }, medidas(700f))
        // Dos columnas, una al lado de otra, en el orden de las listas.
        assertEquals(listOf(0, 3), columnas.map { it.lista })
        assertTrue(columnas[1].caja.x > columnas[0].caja.x + columnas[0].caja.ancho)
        assertEquals(columnas[0].caja.y, columnas[1].caja.y)
        val orden = listOf(Pieza.Encabezado(0), Pieza.Tarjeta(0, 0), Pieza.Tarjeta(0, 1), Pieza.Pliegue(0), Pieza.Tarjeta(0, 2))
        val ys = orden.map { cajaDe(celdas, it).y }
        assertTrue(ys.toString(), ys.zipWithNext().all { (a, b) -> a < b })
        for (p in orden) {
            val r = cajaDe(celdas, p)
            assertTrue(r.x >= columnas[0].caja.x && r.x + r.ancho <= columnas[0].caja.x + columnas[0].caja.ancho)
        }
        // Las dos columnas, del alto de la más larga: se suelta en cualquier sitio.
        assertEquals(columnas[0].caja.alto, columnas[1].caja.alto)
        assertTrue(alto >= columnas[0].caja.alto)
    }

    @Test
    fun plegadas_las_hechas_no_salen_y_lo_que_no_cabe_baja_a_otra_banda() {
        val grupos = listOf(grupo(0, listOf(0), listOf(1)), grupo(1, listOf(0), emptyList()), grupo(2, listOf(0), emptyList()))
        // 480 de ancho: caben dos columnas de 220; la tercera baja.
        val (celdas, columnas, _) = Kanban.disponer(grupos, { false }, { _, _, _ -> 100f }, medidas(480f))
        assertFalse(celdas.any { it.pieza == Pieza.Tarjeta(0, 1) })
        assertTrue(celdas.any { it.pieza == Pieza.Pliegue(0) })
        assertEquals(columnas[0].caja.x, columnas[2].caja.x)
        assertTrue(columnas[2].caja.y >= columnas[0].caja.y + columnas[0].caja.alto)
        // Caso negativo: una pantalla estrechísima sigue con una columna, sin anchos negativos.
        val (k, a) = Kanban.reparto(medidas(50f), 3)
        assertEquals(1, k)
        assertTrue(a > 0f)
        // Y una muy ancha con una lista no la estira más de la cuenta.
        assertEquals(1 to 360f, Kanban.reparto(medidas(2000f), 1))
    }

    @Test
    fun soltar_en_otra_columna_la_mueve_y_en_la_suya_o_fuera_no() {
        val grupos = listOf(grupo(0, listOf(0), emptyList()), grupo(5, listOf(0), emptyList()))
        val (_, columnas, _) = Kanban.disponer(grupos, { true }, { _, _, _ -> 100f }, medidas(700f))
        val otra = columnas[1].caja
        val cx = otra.x + otra.ancho / 2
        val cy = otra.y + otra.alto - 2
        assertEquals(5, Kanban.destinoAlSoltar(columnas, 0, cx, cy))
        val suya = columnas[0].caja
        assertNull(Kanban.destinoAlSoltar(columnas, 0, suya.x + 5, suya.y + 5))
        assertNull(Kanban.destinoAlSoltar(columnas, 0, cx, otra.y + otra.alto + 50))
        // Un toque que tiembla no es un arrastre; seis puntos (a escala 1) sí.
        assertFalse(Kanban.esArrastre(10f to 10f, 13f to 12f, 1f))
        assertTrue(Kanban.esArrastre(10f to 10f, 16f to 10f, 1f))
        assertFalse(Kanban.esArrastre(10f to 10f, 16f to 10f, 2f))
    }

    @Test
    fun la_vista_elegida_se_lee_de_su_palabra() {
        assertEquals(Kanban.Vista.KANBAN, Kanban.Vista.deTexto("kanban"))
        assertEquals(Kanban.Vista.LISTA, Kanban.Vista.deTexto("lista"))
        // Caso negativo: una palabra parecida no es el tablero.
        assertEquals(Kanban.Vista.LISTA, Kanban.Vista.deTexto("kanbann"))
        assertEquals(Kanban.Vista.LISTA, Kanban.Vista.deTexto(null))
    }
}
