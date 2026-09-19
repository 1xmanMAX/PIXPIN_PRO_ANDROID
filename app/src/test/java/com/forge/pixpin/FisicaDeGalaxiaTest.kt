package com.forge.pixpin

import com.forge.pixpin.ui.EnlaceDeGalaxia
import com.forge.pixpin.ui.FisicaDeGalaxia
import com.forge.pixpin.ui.Galaxia
import com.forge.pixpin.ui.NotaDeGalaxia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** El sistema solar de proyectos: que las conexiones tiren y que todo acabe quieto. */
class FisicaDeGalaxiaTest {

    /** Dos soles a 300 dp unidos por una cuerda. */
    private fun dosSoles(): FisicaDeGalaxia = FisicaDeGalaxia(2, 2).apply {
        poner(0, 0f, 0f); poner(1, 300f, 0f)
        radio[0] = 40f; radio[1] = 40f
        ponerCuerdas(intArrayOf(0, 1))
    }

    private fun FisicaDeGalaxia.dist(a: Int, b: Int) = hypot(x[b] - x[a], y[b] - y[a])

    @Test
    fun `tirar de un sol se lleva al conectado, sin moverse en bloque`() {
        val f = dosSoles()
        f.sujetar(0)
        // El dedo se lleva el sol 400 dp a la izquierda en 20 pasos.
        for (t in 1..20) { f.llevar(0, -20f * t, 0f); f.paso() }
        // El otro ha venido detrás…
        assertTrue("x=${f.x[1]}", f.x[1] < 200f)
        // …pero con retraso: la cuerda está más estirada que al principio.
        assertTrue(f.dist(0, 1) > 300f)
        f.soltar(0)
        repeat(300) { f.paso() }
        // Al asentarse, vuelve a su largo (ha cedido un poco, no del todo).
        assertEquals(300f, f.dist(0, 1), 60f)
    }

    @Test
    fun `acercar un sol no empuja al conectado`() {
        val f = dosSoles()
        f.sujetar(0)
        for (t in 1..10) { f.llevar(0, 15f * t, 0f); f.paso() }
        assertEquals(300f, f.x[1], 0.01f)
    }

    @Test
    fun `los soles no se pisan`() {
        val f = FisicaDeGalaxia(2, 2).apply {
            poner(0, 0f, 0f); poner(1, 10f, 0f)
            radio[0] = 40f; radio[1] = 40f
        }
        repeat(60) { f.paso() }
        assertTrue(f.dist(0, 1) >= 80f + FisicaDeGalaxia.HOLGURA - 1f)
    }

    @Test
    fun `un exoplaneta sigue a su sol y no lo arrastra`() {
        val f = FisicaDeGalaxia(2, 1).apply {
            poner(0, 0f, 0f); poner(1, 120f, 0f)
            radio[0] = 40f
            ponerVarillas(intArrayOf(0, 1))
        }
        f.sujetar(0)
        for (t in 1..30) { f.llevar(0, 0f, 10f * t); f.paso() }
        f.soltar(0)
        repeat(200) { f.paso() }
        assertEquals(120f, f.dist(0, 1), 3f)
        // Sujetar el planeta y llevárselo lejos no mueve al sol.
        val solX = f.x[0]; val solY = f.y[0]
        f.sujetar(1)
        for (t in 1..20) { f.llevar(1, 500f + t, 0f); f.paso() }
        assertEquals(solX, f.x[0], 0.01f)
        assertEquals(solY, f.y[0], 0.01f)
    }

    @Test
    fun `todo acaba quieto`() {
        val f = dosSoles()
        f.sujetar(0)
        for (t in 1..15) { f.llevar(0, -30f * t, 5f * t); f.paso() }
        f.soltar(0)
        var pasos = 0
        while (f.paso() >= FisicaDeGalaxia.QUIETO && pasos < 2000) pasos++
        assertTrue("tardó $pasos pasos", pasos < 600)
    }

    @Test
    fun `al crecer un sol se abren sus órbitas`() {
        val f = FisicaDeGalaxia(2, 1).apply {
            poner(0, 0f, 0f); poner(1, 100f, 0f)
            radio[0] = 40f
            ponerVarillas(intArrayOf(0, 1))
        }
        f.crecer(0, 30f)
        repeat(200) { f.paso() }
        assertEquals(130f, f.dist(0, 1), 2f)
    }

    @Test
    fun `una varilla se puede poner a otro largo y el planeta se va a esa distancia`() {
        val f = FisicaDeGalaxia(3, 1).apply {
            poner(0, 0f, 0f); poner(1, 100f, 0f); poner(2, 0f, 150f)
            radio[0] = 40f
            ponerVarillas(intArrayOf(0, 1, 0, 2))
        }
        assertEquals(0, f.varillaDe(1))
        assertEquals(1, f.varillaDe(2))
        assertEquals(-1, f.varillaDe(0))
        // El planeta 1 pasa a la órbita del 2: misma distancia, mismo ángulo.
        f.ponerLargoDeVarilla(0, f.largoDeVarilla(1))
        assertEquals(150f, f.dist(0, 1), 0.01f)
        assertEquals(0f, f.y[1], 0.01f)
    }

    // ---- El modelo ----

    @Test
    fun `conectar dos veces desconecta, y el orden no importa`() {
        val g = Galaxia().alternarEnlace("b", "a")
        assertTrue(g.conectados("a", "b"))
        assertEquals(listOf(EnlaceDeGalaxia("a", "b")), g.enlaces)
        assertFalse(g.alternarEnlace("a", "b").conectados("a", "b"))
        assertEquals(g, g.alternarEnlace("a", "a"))
    }

    @Test
    fun `una conexión con un proyecto borrado no cuenta`() {
        val g = Galaxia().alternarEnlace("a", "b").alternarEnlace("a", "c")
        assertEquals(listOf(EnlaceDeGalaxia("a", "b")), g.enlacesEntre(setOf("a", "b")))
        assertTrue(g.sinEnlacesDe("a").enlaces.isEmpty())
    }

    @Test
    fun `el tamaño normal no se guarda y los extremos se recortan`() {
        assertTrue(Galaxia().conTamano("a", 1f).tamanos.isEmpty())
        assertEquals(Galaxia.TAMANO_MAX, Galaxia().conTamano("a", 99f).tamanoDe("a"))
        assertEquals(1f, Galaxia().tamanoDe("x"))
    }

    @Test
    fun `un archivo de antes, sin soles ni emojis, se sigue leyendo`() {
        val viejo = """{"posiciones":{"a":{"x":1.0,"y":2.0}},"notas":[{"id":"n","texto":"hola","x":3.0,"y":4.0}]}"""
        val g = Galaxia.leer(viejo)
        assertEquals(NotaDeGalaxia("n", "hola", 3f, 4f), g.notas.single())
        assertEquals(1f, g.posiciones.getValue("a").x)
    }

    @Test
    fun `mover a la vez soles y notas`() {
        val g = Galaxia().conNota(NotaDeGalaxia("n", "x", 0f, 0f, sol = "a"))
        val movida = g.conSitios(
            mapOf("a" to com.forge.pixpin.ui.PuntoDeGalaxia(5f, 5f)),
            mapOf("n" to com.forge.pixpin.ui.PuntoDeGalaxia(9f, 9f))
        )
        assertEquals(5f, movida.posiciones.getValue("a").x)
        assertEquals(9f, movida.notas.single().x)
        assertEquals("a", movida.notas.single().sol)
    }
}
