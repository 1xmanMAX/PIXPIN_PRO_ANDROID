package com.forge.pixpin.mini

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El tablero Kanban de Tareas** por dentro, con los casos de `tareas/kanban.rs` del PC: una
 * columna por lista, lo pendiente arriba y lo hecho al pie, adónde va una tarjeta al soltarla, y
 * que soltarla en otra columna es el mismo «Mover a…» de siempre.
 */
class KanbanTest {

    private val hoy = LocalDate.of(2026, 10, 3)

    private fun lista(chat: String, titulo: String, cuando: Long, doc: String, proyecto: String? = chat.lowercase()) =
        TodasLasTareas.Lista(proyecto, chat, "m$cuando", titulo, cuando, TodasLasTareas.filasDe(doc))

    private fun muestra() = listOf(
        lista(
            "Mensajes guardados", "Inbox", 30,
            "- [ ] Llamar al fontanero ➕ 2026-10-03\n- [x] pagar la luz ➕ 2026-10-02\n- [ ] comprar pan ➕ 2026-10-02",
            proyecto = null
        ),
        lista("Obra Miraflores", "Pendientes", 20, "- [ ] revisar puntales ➕ 2026-10-01\n- [x] pedir yeso"),
        lista("Tesis", "Por hacer", 10, "")
    )

    private fun textos(l: TodasLasTareas.Lista, indices: List<Int>) = indices.map { l.filas[it].texto }

    @Test
    fun `cada lista es una columna con lo pendiente arriba y lo hecho al pie`() {
        val listas = muestra()
        val c = Kanban.columnas(listas, emptyList(), hoy)
        assertEquals("una por lista, en su orden, también la vacía", listOf(0, 1, 2), c.map { it.lista })
        // Lo pendiente, la más vieja arriba (como la lista); lo hecho, aparte.
        assertEquals(listOf("comprar pan", "Llamar al fontanero"), textos(listas[0], c[0].arriba))
        assertEquals(listOf("pagar la luz"), textos(listas[0], c[0].hechas))
        assertEquals(listOf("revisar puntales"), textos(listas[1], c[1].arriba))
        assertEquals(listOf("pedir yeso"), textos(listas[1], c[1].hechas))
        // La vacía: sin tarjetas, pero está para soltar en ella.
        assertTrue(c[2].arriba.isEmpty() && c[2].hechas.isEmpty())
        // Las mismas tarjetas que la vista de lista.
        assertEquals(TodasLasTareas.agrupar(listas, emptyList(), hoy), c.take(2))
    }

    @Test
    fun `lo recien tachado se queda arriba en su columna`() {
        val listas = muestra()
        val c = Kanban.columnas(listas, emptyList(), hoy) { _, f -> f.texto == "pagar la luz" }
        assertTrue("pagar la luz" in textos(listas[0], c[0].arriba))
        assertTrue(c[0].hechas.isEmpty())
        assertEquals("lo tachado no cuenta como pendiente", 2, c[0].pendientes(listas[0]))
    }

    @Test
    fun `buscando solo salen las columnas con algo que coincida`() {
        val listas = muestra()
        val c = Kanban.columnas(listas, TodasLasTareas.palabras("puntales"), hoy)
        assertEquals(listOf(1), c.map { it.lista })
        assertEquals(listOf("revisar puntales"), textos(listas[1], c[0].arriba))
        // Caso negativo: nada coincide, ninguna columna (ni la vacía).
        assertTrue(Kanban.columnas(listas, TodasLasTareas.palabras("zzz"), hoy).isEmpty())
    }

    private val cajas = listOf(
        Kanban.Caja(0, 0f, 0f, 300f, 900f),
        Kanban.Caja(1, 310f, 0f, 610f, 900f),
        Kanban.Caja(2, 620f, 0f, 920f, 900f)
    )

    @Test
    fun `soltar en otra columna la mueve y en la suya o fuera no`() {
        assertEquals(1, Kanban.destinoAlSoltar(cajas, 0, 400f, 50f))
        assertEquals(2, Kanban.destinoAlSoltar(cajas, 0, 900f, 899f))
        assertEquals(0, Kanban.destinoAlSoltar(cajas, 2, 10f, 10f))
        // En la suya: se queda.
        assertNull(Kanban.destinoAlSoltar(cajas, 1, 400f, 50f))
        // Entre dos columnas, debajo de todas o sin columnas: tampoco.
        assertNull(Kanban.destinoAlSoltar(cajas, 0, 305f, 50f))
        assertNull(Kanban.destinoAlSoltar(cajas, 0, 400f, 950f))
        assertNull(Kanban.destinoAlSoltar(emptyList(), 0, 400f, 50f))
        assertEquals(1, Kanban.columnaEn(cajas, 400f, 50f))
    }

    @Test
    fun `soltar en otra columna pasa la tarea al final de esa lista como Mover a`() {
        val listas = muestra()
        val c = Kanban.columnas(listas, emptyList(), hoy)
        val f = listas[0].filas[c[0].arriba.first()]   // «comprar pan»
        val destino = Kanban.destinoAlSoltar(cajas, 0, 700f, 100f)
        assertEquals(2, destino)
        val (o, d) = TodasLasTareas.mover("# Inbox\n\n- [ ] Llamar al fontanero ➕ 2026-10-03\n- [x] pagar la luz ➕ 2026-10-02\n- [ ] comprar pan ➕ 2026-10-02", f, "# Por hacer\n")!!
        val despues = listOf(
            listas[0].copy(filas = TodasLasTareas.filasDe(o)),
            listas[1],
            listas[2].copy(filas = TodasLasTareas.filasDe(d))
        )
        val c2 = Kanban.columnas(despues, emptyList(), hoy)
        assertEquals(listOf("Llamar al fontanero"), textos(despues[0], c2[0].arriba))
        assertEquals(listOf("comprar pan"), textos(despues[2], c2[2].arriba))
        assertEquals("con su fecha", LocalDate.of(2026, 10, 2), despues[2].filas.single().creada)
        // Una hecha llevada a otra columna sigue hecha: cae en su pie.
        val hecha = despues[0].filas.first { it.hecha }
        val (_, d2) = TodasLasTareas.mover(o, hecha, d)!!
        val c3 = Kanban.columnas(listOf(despues[0], despues[1], despues[2].copy(filas = TodasLasTareas.filasDe(d2))), emptyList(), hoy)
        assertEquals(listOf("pagar la luz"), c3[2].hechas.map { TodasLasTareas.filasDe(d2)[it].texto })
        // Caso negativo: si la lista cambió desde que se vio, no se mueve nada.
        assertNull(TodasLasTareas.mover(o, f, d))
    }

    @Test
    fun `cerca del borde el tablero se desplaza solo y en medio no`() {
        assertEquals(0f, Kanban.desplazamientoAlBorde(500f, 1000f, 100f, 20f), 0f)
        assertEquals(-20f, Kanban.desplazamientoAlBorde(0f, 1000f, 100f, 20f), 0.001f)
        assertEquals(-10f, Kanban.desplazamientoAlBorde(50f, 1000f, 100f, 20f), 0.001f)
        assertEquals(10f, Kanban.desplazamientoAlBorde(950f, 1000f, 100f, 20f), 0.001f)
        // Fuera del tablero, lo más rápido y no más.
        assertEquals(20f, Kanban.desplazamientoAlBorde(1300f, 1000f, 100f, 20f), 0.001f)
        assertEquals(-20f, Kanban.desplazamientoAlBorde(-50f, 1000f, 100f, 20f), 0.001f)
        // Sin medir aún: quieto.
        assertEquals(0f, Kanban.desplazamientoAlBorde(10f, 0f, 100f, 20f), 0f)
    }

    @Test
    fun `la vista elegida se recuerda y una palabra parecida no es el tablero`() {
        for (v in Kanban.Vista.entries) assertEquals(v, Kanban.Vista.deTexto(v.texto))
        assertEquals("kanban", Kanban.Vista.KANBAN.texto)
        assertEquals(Kanban.Vista.KANBAN, Kanban.Vista.deTexto(" kanban\n"))
        assertEquals(Kanban.Vista.LISTA, Kanban.Vista.deTexto(null))
        assertEquals(Kanban.Vista.LISTA, Kanban.Vista.deTexto(""))
        assertEquals(Kanban.Vista.LISTA, Kanban.Vista.deTexto("kanbann"))
        assertNotNull(Kanban.Vista.deTexto("tarjetas"))
    }
}
