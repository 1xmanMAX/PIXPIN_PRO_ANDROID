package com.forge.pixpin.ui

import com.forge.pixpin.ui.Funciones.Caja
import com.forge.pixpin.ui.Funciones.Funcion
import com.forge.pixpin.ui.Funciones.Paso
import com.forge.pixpin.ui.Funciones.mover
import com.forge.pixpin.ui.Funciones.rejilla
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La pestaña de Funciones** de la lista de chats: el orden de los mosaicos, la rejilla y las
 * flechas, con los casos de `crates/pixpin-ui/src/funciones.rs` del PC (25-oct-2026).
 */
class FuncionesTest {

    private fun centro(r: Caja) = (r.x + r.ancho / 2) to (r.y + r.alto / 2)

    @Test
    fun `las funciones van en su orden y cada una con sus claves`() {
        // El orden y los nombres del documento `docs/ui-funciones-y-chats.md`.
        assertEquals(
            listOf("grabar", "lienzo", "tareas", "timeline", "horario", "galeria", "figuras"),
            Funcion.entries.map { it.nombre }
        )
        assertEquals("nombres distintos", Funcion.entries.size, Funcion.entries.map { it.nombre }.toSet().size)
        for (f in Funcion.entries) {
            assertTrue(f.claveTitulo.startsWith("funciones-"))
            assertEquals("${f.claveTitulo}-detalle", f.claveDetalle)
        }
        assertEquals(0xFFF76B15, Funcion.HORARIO.color)
    }

    @Test
    fun `en la lista de siempre salen dos columnas sin tocarse ni salirse`() {
        val zona = Caja(0, 40, 320, 700)
        val g = rejilla(zona, 7, 100)
        assertEquals(2, g.columnas)
        assertFalse(g.soloIconos)
        assertTrue(g.conDetalle)
        assertEquals(7, g.mosaicos.size)
        g.mosaicos.forEachIndexed { i, a ->
            assertTrue(a.x >= zona.x && a.derecha <= zona.derecha)
            assertTrue(a.y >= g.titulo.abajo && a.abajo <= zona.abajo)
            assertEquals(Funciones.MOSAICO_ALTO, a.alto)
            for (b in g.mosaicos.drop(i + 1)) assertFalse("$a pisa $b", a.pisa(b))
        }
        // Se leen como un texto: el segundo a la derecha del primero, el tercero debajo.
        assertEquals(g.mosaicos[0].y, g.mosaicos[1].y)
        assertTrue(g.mosaicos[1].x > g.mosaicos[0].x)
        assertEquals(g.mosaicos[0].x, g.mosaicos[2].x)
        assertTrue(g.mosaicos[2].y > g.mosaicos[0].y)
    }

    @Test
    fun `ancha nunca pasa de tres columnas`() {
        assertEquals(Funciones.COLUMNAS_MAX, rejilla(Caja(0, 0, 2000, 900), 7, 100).columnas)
    }

    @Test
    fun `baja encoge los mosaicos y les quita la descripcion`() {
        val g = rejilla(Caja(0, 0, 320, 330), 7, 100)
        assertTrue(g.mosaicos[0].alto in Funciones.MOSAICO_ALTO_MIN until Funciones.ALTO_CON_DETALLE)
        assertFalse(g.conDetalle)
        // Caso negativo: más baja que el mínimo no encoge más.
        assertEquals(Funciones.MOSAICO_ALTO_MIN, rejilla(Caja(0, 0, 320, 120), 7, 100).mosaicos[0].alto)
    }

    @Test
    fun `la lista plegada deja solo los iconos en una columna`() {
        val g = rejilla(Caja(0, 40, 72, 700), 7, 100)
        assertTrue(g.soloIconos)
        assertEquals(1, g.columnas)
        assertEquals(0, g.titulo.ancho)
        for (m in g.mosaicos) {
            assertEquals("cuadrados", m.alto, m.ancho)
            assertTrue(m.x >= 0 && m.derecha <= 72)
        }
    }

    @Test
    fun `la escala lo agranda todo`() {
        val g = rejilla(Caja(0, 0, 640, 1400), 7, 200)
        assertEquals(2, g.columnas)
        assertEquals(2 * Funciones.MOSAICO_ALTO, g.mosaicos[0].alto)
    }

    @Test
    fun `el toque cae en su mosaico y fuera en ninguno`() {
        val g = rejilla(Caja(0, 40, 320, 700), 7, 100)
        g.mosaicos.forEachIndexed { i, m -> val (x, y) = centro(m); assertEquals(i, g.en(x, y)) }
        assertNull("en el margen", g.en(1, 41))
        val (x, y) = centro(g.titulo)
        assertNull("en el título", g.en(x, y))
    }

    @Test
    fun `las flechas se mueven por la rejilla sin salirse`() {
        // Dos columnas, siete mosaicos: 0 1 / 2 3 / 4 5 / 6.
        assertEquals(0, mover(null, Paso.ABAJO, 2, 7))
        assertEquals(6, mover(null, Paso.FIN, 2, 7))
        assertEquals(1, mover(0, Paso.DERECHA, 2, 7))
        assertEquals("a la fila de abajo", 2, mover(1, Paso.DERECHA, 2, 7))
        assertEquals(0, mover(0, Paso.IZQUIERDA, 2, 7))
        assertEquals(3, mover(1, Paso.ABAJO, 2, 7))
        assertEquals("no hay debajo", 5, mover(5, Paso.ABAJO, 2, 7))
        assertEquals(6, mover(4, Paso.ABAJO, 2, 7))
        assertEquals(1, mover(1, Paso.ARRIBA, 2, 7))
        assertEquals(4, mover(6, Paso.ARRIBA, 2, 7))
        assertEquals(6, mover(6, Paso.DERECHA, 2, 7))
        assertEquals(0, mover(3, Paso.INICIO, 2, 7))
        // Caso negativo: sin mosaicos no hay nada que elegir.
        assertNull(mover(0, Paso.ABAJO, 2, 0))
    }
}
