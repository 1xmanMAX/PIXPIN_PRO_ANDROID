package com.forge.pixpin.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El riel se va con las barras** (27-oct-2026): se ve cuando se ven las barras, y se queda mientras
 * se usa (anotando, con los marcos, poniendo un marcador o con medir/piezas del 3D).
 */
class RielALaVistaTest {

    @Test
    fun sigueALasBarras() {
        assertTrue(RielALaVista.visible(barras = true, anotando = false, marcando = false))
        assertFalse(RielALaVista.visible(barras = false, anotando = false, marcando = false))
    }

    @Test
    fun anotandoNoSeVa() {
        assertTrue(RielALaVista.visible(barras = false, anotando = true, marcando = false))
    }

    @Test
    fun conLosMarcosNoSeVa() {
        assertTrue(RielALaVista.visible(barras = false, anotando = false, marcando = true))
    }

    @Test
    fun poniendoUnMarcadorNoSeVa() {
        assertTrue(RielALaVista.visible(barras = false, anotando = false, marcando = false, poniendoMarca = true))
    }

    @Test
    fun conOtraHerramientaNoSeVa() {
        assertTrue(RielALaVista.visible(barras = false, anotando = false, marcando = false, otraHerramienta = true))
        assertFalse(RielALaVista.visible(barras = false, anotando = false, marcando = false, poniendoMarca = false, otraHerramienta = false))
    }

    @Test
    fun unToqueEsUnDedoQuietoYBreve() {
        assertTrue(RielALaVista.esUnToque(recorrido = 3f, margen = 8f, dedos = 1, duracionMs = 120))
        assertTrue(RielALaVista.esUnToque(recorrido = 8f, margen = 8f, dedos = 1, duracionMs = 0))
    }

    @Test
    fun moverNoEsTocar() {
        assertFalse(RielALaVista.esUnToque(recorrido = 30f, margen = 8f, dedos = 1, duracionMs = 120))
    }

    @Test
    fun dosDedosNoEsTocar() {
        assertFalse(RielALaVista.esUnToque(recorrido = 0f, margen = 8f, dedos = 2, duracionMs = 120))
    }

    @Test
    fun mantenerNoEsTocar() {
        assertFalse(RielALaVista.esUnToque(recorrido = 0f, margen = 8f, dedos = 1, duracionMs = RielALaVista.TOQUE_MAXIMO_MS))
        assertTrue(RielALaVista.esUnToque(recorrido = 0f, margen = 8f, dedos = 1, duracionMs = RielALaVista.TOQUE_MAXIMO_MS - 1))
    }
}
