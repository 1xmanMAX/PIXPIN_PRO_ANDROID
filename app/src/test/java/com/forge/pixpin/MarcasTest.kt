package com.forge.pixpin

import com.forge.pixpin.motor.Marca
import com.forge.pixpin.motor.Marcas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarcasTest {
    @Test fun `se ordenan de arriba abajo y mover una las reordena`() {
        var lista = Marcas.con(emptyList(), 100.0, 500.0, "⭐", 1)
        lista = Marcas.con(lista, 10.0, 20.0, "🔖", 2)
        lista = Marcas.con(lista, 900.0, 20.0, "📌", 3)
        assertEquals(listOf("🔖", "📌", "⭐"), lista.map { it.emoji })
        // La de abajo sube del todo: el riel la enseña la primera.
        val laDeAbajo = lista.first { it.emoji == "⭐" }
        lista = Marcas.movida(lista, laDeAbajo.id, 0.0, -50.0)
        assertEquals(listOf("⭐", "🔖", "📌"), lista.map { it.emoji })
    }

    @Test fun `dos en el mismo milisegundo son dos`() {
        val dos = Marcas.con(Marcas.con(emptyList(), 0.0, 0.0, "⭐", 7), 1.0, 1.0, "🔖", 7)
        assertEquals(2, dos.size)
        assertEquals(2, dos.map { it.id }.distinct().size)
    }

    @Test fun `pasado el tope se va la mas vieja y se queda la recien puesta`() {
        var lista = emptyList<Marca>()
        repeat(Marcas.MAXIMO) { i -> lista = Marcas.con(lista, i.toDouble(), i.toDouble(), "🔖", 1000L + i) }
        lista = Marcas.con(lista, -1.0, -1.0, "🏁", 9999)
        assertEquals(Marcas.MAXIMO, lista.size)
        assertTrue(lista.any { it.emoji == "🏁" })
        assertTrue(lista.none { it.id == 1000L })
    }

    @Test fun `ida y vuelta por texto, con emoticonos que llevan dos puntos`() {
        val lista = Marcas.con(Marcas.con(emptyList(), -12.5, 3.25, "⭐", 1), 4.0, 900.125, "a:b", 2)
        val vuelta = Marcas.deTexto(Marcas.aTexto(lista))
        assertEquals(lista.map { it.emoji }, vuelta.map { it.emoji })
        assertEquals(lista.map { it.id }, vuelta.map { it.id })
        assertEquals(-12.5, vuelta[0].x, 1e-9)
        assertEquals(3.25, vuelta[0].y, 1e-9)
        assertEquals(900.13, vuelta[1].y, 1e-9)
        assertEquals(emptyList<Marca>(), Marcas.deTexto(null))
        assertEquals(emptyList<Marca>(), Marcas.deTexto("basura|1:2"))
    }

    @Test fun `ir a una marca la deja arriba y centrada a lo ancho`() {
        val m = Marca(1, "⭐", 250.0, 400.0)
        val v = Marcas.vistaConLaMarcaArriba(m, zoom = 2.0, ancho = 1000.0, margen = 100.0)
        val enPantalla = v.toScreen(com.forge.pixpin.motor.Pt(m.x, m.y))
        assertEquals(500.0, enPantalla.x, 1e-6)
        assertEquals(100.0, enPantalla.y, 1e-6)
        assertEquals(2.0, v.zoom, 1e-9)
    }

    @Test fun `una pagina del pdf y donde cae dentro de ella`() {
        val (x, y) = Marcas.enLaPagina(2, 0.5)
        assertEquals(0.5, x, 1e-9)
        assertEquals(2.5, y, 1e-9)
        val m = Marca(1, "🔖", x, y)
        assertEquals(2, Marcas.paginaDe(m))
        assertEquals(0.5, Marcas.altoEnLaPagina(m), 1e-9)
        // Nada de páginas negativas ni de fracciones fuera de la hoja: una página −3 al 400 %
        // es el final de la primera, que es **el mismo sitio** que el principio de la segunda.
        assertEquals(1.0, Marcas.enLaPagina(-3, 4.0).second, 1e-9)
        assertEquals(0.0, Marcas.enLaPagina(0, -2.0).second, 1e-9)
        assertEquals(0.0, Marcas.enLaPagina(0, 0.5, fraccionAncho = -1.0).first, 1e-9)
    }
}
