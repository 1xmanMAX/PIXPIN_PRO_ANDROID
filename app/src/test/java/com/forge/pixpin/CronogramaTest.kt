package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El cronograma: **un plan dibujado**.
 *
 * Lo que se comprueba es que nada dentro de él viva en coordenadas: todo está en filas y
 * columnas, así que estirar la figura estira el plan sin descuadrarlo, y arrastrar una
 * barra la deja donde el dedo la puso pero enganchada a la escala.
 */
class CronogramaTest {

    private val tol = 1e-9

    private fun plan(
        x: Double = 0.0, y: Double = 0.0, ancho: Double = 400.0, alto: Double = 200.0,
        tareas: List<TareaDelCronograma> = listOf(
            TareaDelCronograma("A", 0.0, 2.0),
            TareaDelCronograma("B", 2.0, 1.0)
        ),
        periodos: Int = 8
    ) = newElement(ElementType.CRONOGRAMA, x, y, ItemStyle(), ancho, alto)
        .copy(tareas = tareas, periodos = periodos)

    @Test
    fun `nace con filas para que enseñe algo`() {
        val recien = newElement(ElementType.CRONOGRAMA, 0.0, 0.0, ItemStyle(), 300.0, 150.0)
        assertTrue(recien.tareas.isNotEmpty())
        // Y escalonadas: cada una detrás de la anterior, que es lo que es un plan.
        assertEquals(0.0, recien.tareas[0].desde, tol)
        assertEquals(1.0, recien.tareas[1].desde, tol)
    }

    /** La rejilla se reparte dentro de la caja: la primera columna arranca tras los nombres. */
    @Test
    fun `la rejilla se reparte sola`() {
        val e = plan(ancho = 400.0, periodos = 8)
        val columnas = columnasDelCronograma(e)
        assertEquals(9, columnas.size)
        assertEquals(xDeLaEscala(e), columnas.first(), tol)
        assertEquals(400.0, columnas.last(), 1e-6)
        // Todas iguales de anchas: es lo único que la escala tiene que decir.
        val anchos = columnas.zipWithNext { a, b -> b - a }
        assertTrue(anchos.all { kotlin.math.abs(it - anchos[0]) < 1e-9 })
    }

    /** Estirar la figura estira el plan: la barra sigue ocupando las mismas columnas. */
    @Test
    fun `estirar la figura no descuadra el plan`() {
        val chico = plan(ancho = 400.0)
        val grande = plan(ancho = 800.0)
        fun enColumnas(e: Element): Double {
            val b = barraDeTarea(e, 0)!!
            return b.width / anchoDeColumna(e)
        }
        assertEquals(enColumnas(chico), enColumnas(grande), 1e-9)
    }

    /** Cada fila ocupa su parte del alto, y las barras no se salen de la suya. */
    @Test
    fun `cada barra se queda en su fila`() {
        val e = plan()
        val alto = altoDeFila(e)
        for (i in e.tareas.indices) {
            val b = barraDeTarea(e, i)!!
            val techo = yDeLasFilas(e) + i * alto
            assertTrue(b.y1 >= techo - tol && b.y2 <= techo + alto + tol)
        }
    }

    // ---- La letra ----

    /**
     * **La cuenta que tiró la aplicación en un móvil de verdad.**
     *
     * Era `coerceIn(1.0, alto * 0.8)`, y `coerceIn` no recorta cuando el máximo baja del
     * mínimo: lanza. Con la figura pequeña o con muchas filas, una fila mide menos de un
     * píxel y medio — y la excepción saltaba **al dibujar**, así que se llevaba la
     * aplicación desde el hilo de la pantalla. El informe traía el número exacto:
     * «maximum 0.275 is less than minimum 1.0».
     */
    @Test
    fun `la letra aguanta una fila diminuta`() {
        val e = plan().copy(fontSize = null)
        assertEquals(1.0, letraDelCronograma(e, 0.34375), tol)
        assertEquals(1.0, letraDelCronograma(e, 0.0), tol)
        assertEquals(1.0, letraDelCronograma(e, -5.0), tol)
    }

    /** Con sitio de sobra, la letra la decide la fila. */
    @Test
    fun `la letra crece con la fila`() {
        assertEquals(20.0, letraDelCronograma(plan().copy(fontSize = null), 40.0), tol)
    }

    /** Y una letra elegida a mano se respeta, pero sin salirse de la fila. */
    @Test
    fun `la letra elegida no se sale de su fila`() {
        assertEquals(32.0, letraDelCronograma(plan().copy(fontSize = 200.0), 40.0), tol)
    }

    // ---- Arrastrar ----

    /** Por el cuerpo se mueve; por la punta se estira. */
    @Test
    fun `la punta estira y el cuerpo mueve`() {
        val e = plan()
        val b = barraDeTarea(e, 0)!!
        val medio = Pt(b.x1 + b.width * 0.2, (b.y1 + b.y2) / 2)
        val punta = Pt(b.x2 - 1.0, (b.y1 + b.y2) / 2)
        assertEquals(ManoEnLaBarra.MOVER, toqueEnBarra(e, medio)!!.mano)
        assertEquals(ManoEnLaBarra.ESTIRAR, toqueEnBarra(e, punta)!!.mano)
    }

    /** Fuera de las barras no se agarra nada: ahí lo que se mueve es la lámina. */
    @Test
    fun `en el hueco no hay barra`() {
        val e = plan()
        assertNull(toqueEnBarra(e, Pt(xDeLaEscala(e) + anchoDeColumna(e) * 6, yDeLasFilas(e) + 5)))
    }

    /** Moverla la lleva a donde el dedo, enganchada a cuartos de columna. */
    @Test
    fun `mover engancha a la escala`() {
        val e = plan()
        val col = anchoDeColumna(e)
        // El dedo a tres columnas y pico del principio, agarrando por el origen.
        val p = Pt(xDeLaEscala(e) + col * 3.13, yDeLasFilas(e) + 5)
        val t = tareaArrastrada(e, 0, ManoEnLaBarra.MOVER, p, 0.0)!!
        assertEquals(3.25, t.desde, 1e-9)
        assertEquals(2.0, t.cuanto, tol)
    }

    /** Y estirarla cambia el largo, no el principio. */
    @Test
    fun `estirar cambia el largo`() {
        val e = plan()
        val col = anchoDeColumna(e)
        val p = Pt(xDeLaEscala(e) + col * 5.0, yDeLasFilas(e) + 5)
        val t = tareaArrastrada(e, 0, ManoEnLaBarra.ESTIRAR, p, 0.0)!!
        assertEquals(0.0, t.desde, tol)
        assertEquals(5.0, t.cuanto, 1e-9)
    }

    /** Ninguna barra se sale de la escala ni se queda sin largo. */
    @Test
    fun `la barra no se sale ni desaparece`() {
        val e = plan(periodos = 4)
        val col = anchoDeColumna(e)
        val lejos = Pt(xDeLaEscala(e) + col * 99, yDeLasFilas(e) + 5)
        val movida = tareaArrastrada(e, 0, ManoEnLaBarra.MOVER, lejos, 0.0)!!
        assertTrue(movida.desde + movida.cuanto <= 4.0 + tol)
        val encogida = tareaArrastrada(
            e, 0, ManoEnLaBarra.ESTIRAR, Pt(xDeLaEscala(e) - 500.0, 0.0), 0.0
        )!!
        assertTrue(encogida.cuanto >= MINIMA_BARRA - tol)
    }

    // ---- Añadir y quitar ----

    /** Una tarea nueva nace detrás de la última: un plan se lee de arriba abajo. */
    @Test
    fun `la tarea nueva va detras de la ultima`() {
        val e = plan(tareas = listOf(TareaDelCronograma("A", 1.0, 2.0)))
        val con = conTareaNueva(e, "B")
        assertEquals(2, con.tareas.size)
        assertEquals(3.0, con.tareas[1].desde, tol)
        assertEquals(2.0, con.tareas[1].cuanto, tol)
    }

    /** Y si no cabe, la escala crece con ella en vez de dejarla fuera. */
    @Test
    fun `la escala crece si la tarea no cabe`() {
        val e = plan(tareas = listOf(TareaDelCronograma("A", 7.0, 1.0)), periodos = 8)
        val con = conTareaNueva(e, "B")
        assertTrue("${con.periodos}", con.periodos >= 9)
        assertTrue(con.tareas[1].desde + con.tareas[1].cuanto <= con.periodos + tol)
    }

    /** Quitar de una lista vacía no rompe nada. */
    @Test
    fun `quitar sin tareas no hace nada`() {
        val vacio = plan(tareas = emptyList())
        assertEquals(0, sinLaUltimaTarea(vacio).tareas.size)
    }

    /** La escala no baja de una columna ni se dispara. */
    @Test
    fun `la escala tiene tope por los dos lados`() {
        assertEquals(1, conPeriodos(plan(), -5).periodos)
        assertEquals(MAXIMO_DE_PERIODOS, conPeriodos(plan(), 9999).periodos)
    }

    /** Con cero tareas la figura sigue siendo válida: es el hueco donde caerá la primera. */
    @Test
    fun `sin tareas la figura no revienta`() {
        val vacio = plan(tareas = emptyList())
        assertTrue(altoDeFila(vacio) > 0.0)
        assertNull(barraDeTarea(vacio, 0))
        assertNotNull(columnasDelCronograma(vacio))
    }
}
