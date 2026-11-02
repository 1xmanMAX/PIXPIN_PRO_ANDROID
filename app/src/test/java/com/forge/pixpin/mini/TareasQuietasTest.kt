package com.forge.pixpin.mini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Lo que no se mueve bajo el dedo en Tareas** (2-nov-2026): las pruebas de `tareas_quietas.rs` del PC, una a una. */
class TareasQuietasTest {

    private fun s(vararg v: String) = v.toMutableList()
    private val id = { x: String -> x }

    @Test
    fun `colocar deja lo visto quieto y lo nuevo en su hueco`() {
        // Jev subió «c» arriba del todo: no se mueve mientras se mira.
        val v = s("c", "a", "b")
        TareasQuietas.colocar(v, listOf("a", "b", "c"), id)
        assertEquals(listOf("a", "b", "c"), v)
        // Lo nuevo entra donde lo pone el orden de siempre.
        val w = s("n", "b", "a")
        TareasQuietas.colocar(w, listOf("a", "b"), id)
        assertEquals(listOf("n", "a", "b"), w)
        // Sin nada visto, el orden de siempre.
        val x = s("b", "a")
        TareasQuietas.colocar(x, emptyList(), id)
        assertEquals(listOf("b", "a"), x)
    }

    @Test
    fun `lo tachado se queda arriba hasta soltar`() {
        val q = TareasQuietas()
        assertFalse(q.sigueArriba("L", "pan"))
        q.marcar("L", "pan", true)
        q.marcar("L", "leche", true)
        assertTrue(q.sigueArriba("L", "pan") && q.sigueArriba("L", "leche"))
        assertFalse("es de su lista", q.sigueArriba("Otra", "pan"))
        // Destacharla la deja pendiente, sin marca.
        q.marcar("L", "pan", false)
        assertFalse(q.sigueArriba("L", "pan"))
        assertFalse(q.siguePlegada("L", "pan"))
        q.soltar()
        assertFalse("al salir, a «Hechas»", q.sigueArriba("L", "leche"))
        assertEquals(0, q.tocadas())
    }

    @Test
    fun `lo destachado desde hechas se queda abajo en su sitio`() {
        val q = TareasQuietas()
        q.ordenarTareas("L", s("a"), s("h1", "h2", "h3"), id)
        // Se destacha «h2»: el orden de siempre la sube arriba.
        q.marcar("L", "h2", false)
        val arriba = s("a", "h2"); val plegadas = s("h1", "h3")
        q.ordenarTareas("L", arriba, plegadas, id)
        assertEquals(listOf("a"), arriba)
        assertEquals("en su sitio", listOf("h1", "h2", "h3"), plegadas)
        // Al salir, arriba con lo pendiente.
        q.soltar()
        val a2 = s("a", "h2"); val p2 = s("h1", "h3")
        q.ordenarTareas("L", a2, p2, id)
        assertEquals(listOf("a", "h2"), a2)
        assertEquals(listOf("h1", "h3"), p2)
    }

    @Test
    fun `el orden de las tareas se congela y se suelta al salir`() {
        val q = TareasQuietas()
        val p = mutableListOf<String>()
        q.ordenarTareas("L", s("a", "b", "c"), p, id)
        // Jev contesta: «c» es urgente.
        val arriba = s("c", "a", "b")
        q.ordenarTareas("L", arriba, p, id)
        assertEquals("no salta bajo el dedo", listOf("a", "b", "c"), arriba)
        q.soltar()
        val otra = s("c", "a", "b")
        q.ordenarTareas("L", otra, p, id)
        assertEquals("al volver, ordenada", listOf("c", "a", "b"), otra)
    }

    @Test
    fun `una lista sin pendientes no se va al final mientras se mira`() {
        val q = TareasQuietas()
        q.ordenarListas(s("Inbox", "Casa", "Clase"), id)
        // Tachada la última de «Casa», el orden de siempre la manda al final.
        val v = s("Inbox", "Clase", "Casa")
        q.ordenarListas(v, id)
        assertEquals(listOf("Inbox", "Casa", "Clase"), v)
        // Una lista nueva entra en su hueco.
        val w = s("Inbox", "Nueva", "Clase", "Casa")
        q.ordenarListas(w, id)
        assertEquals(listOf("Inbox", "Nueva", "Casa", "Clase"), w)
    }

    @Test
    fun `buscar otra cosa suelta y la misma no`() {
        val q = TareasQuietas()
        q.marcar("L", "pan", true)
        assertFalse("nada buscado sigue siendo nada", q.buscar("  "))
        assertTrue(q.sigueArriba("L", "pan"))
        assertTrue(q.buscar("pan"))
        assertFalse(q.sigueArriba("L", "pan"))
        q.marcar("L", "pan", true)
        assertFalse("lo mismo con un espacio", q.buscar("pan "))
        assertTrue(q.sigueArriba("L", "pan"))
    }

    @Test
    fun `quitar la olvida`() {
        val q = TareasQuietas()
        q.marcar("L", "pan", true)
        q.marcar("L", "sal", false)
        q.quitar("L", "pan")
        q.quitar("L", "sal")
        assertEquals(0, q.tocadas())
    }
}
