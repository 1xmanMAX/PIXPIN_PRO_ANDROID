package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El reparto de la barra en grupos.
 *
 * Lo que puede romperse aquí no es que se vea mal: es que una herramienta
 * **desaparezca de la barra** y no haya manera de llegar a ella. Por eso casi
 * todas las pruebas de este archivo comprueban lo mismo desde ángulos
 * distintos — que no se pierde nada y que no se duplica nada.
 */
class BarraTest {

    private fun todas(grupos: List<List<Tool>>): List<Tool> = grupos.flatten()

    // ---- El reparto ----

    @Test
    fun `los grupos de fábrica no pierden ni repiten ninguna herramienta`() {
        val puestas = todas(GRUPOS_DE_FABRICA)
        assertEquals("hay repetidas", puestas.size, puestas.toSet().size)
        val faltan = Tool.entries.toSet() - puestas.toSet()
        assertTrue("inalcanzables desde la barra: $faltan", faltan.isEmpty())
    }

    @Test
    fun `solo salen las permitidas`() {
        val permitidas = setOf(Tool.ARROW, Tool.RECTANGLE, Tool.ERASER)
        val grupos = gruposDe(null, permitidas)
        assertEquals(permitidas, todas(grupos).toSet())
        assertTrue("no puede quedar un grupo vacío", grupos.none { it.isEmpty() })
    }

    /**
     * La red que impide que añadir una herramienta al motor la deje
     * inalcanzable para quien ya tenía su reparto guardado.
     */
    @Test
    fun `una herramienta que el reparto guardado no menciona aparece igual`() {
        val guardado = escribirGrupos(listOf(listOf(Tool.ARROW), listOf(Tool.RECTANGLE)))
        val permitidas = setOf(Tool.ARROW, Tool.RECTANGLE, Tool.TEXT)
        val grupos = gruposDe(guardado, permitidas)
        assertTrue("la nueva se ha perdido", Tool.TEXT in todas(grupos))
        assertEquals("y va sola al final", listOf(Tool.TEXT), grupos.last())
    }

    @Test
    fun `lo guardado va y vuelve igual`() {
        val original = listOf(
            listOf(Tool.ARROW, Tool.LINE),
            listOf(Tool.RECTANGLE),
            listOf(Tool.TEXT, Tool.SERIAL, Tool.ERASER)
        )
        val vuelta = gruposDe(escribirGrupos(original), Tool.entries.toSet())
        // Lo que no estaba se añade al final; lo que estaba, tal cual y en orden.
        assertEquals(original, vuelta.take(original.size))
    }

    @Test
    fun `un nombre desconocido no tira el reparto entero`() {
        val grupos = leerGrupos("ARROW,PLANCHA_DE_VAPOR|RECTANGLE")
        assertEquals(listOf(listOf(Tool.ARROW), listOf(Tool.RECTANGLE)), grupos)
    }

    @Test
    fun `un reparto guardado con repetidas se queda con la primera`() {
        val grupos = gruposDe("ARROW|ARROW,RECTANGLE", setOf(Tool.ARROW, Tool.RECTANGLE))
        assertEquals(listOf(listOf(Tool.ARROW), listOf(Tool.RECTANGLE)), grupos)
    }

    // ---- Mover ----

    @Test
    fun `mover una herramienta a otro grupo la saca del suyo`() {
        val grupos = listOf(listOf(Tool.ARROW, Tool.LINE), listOf(Tool.RECTANGLE))
        val movido = moverHerramienta(grupos, Tool.LINE, grupoDestino = 1, indice = 0)
        assertEquals(listOf(listOf(Tool.ARROW), listOf(Tool.LINE, Tool.RECTANGLE)), movido)
    }

    /** Sacar algo de su grupo: se arrastra más allá del último. */
    @Test
    fun `soltar más allá del último grupo crea uno nuevo`() {
        val grupos = listOf(listOf(Tool.ARROW, Tool.LINE))
        val movido = moverHerramienta(grupos, Tool.LINE, grupoDestino = 1, indice = 0)
        assertEquals(listOf(listOf(Tool.ARROW), listOf(Tool.LINE)), movido)
    }

    /** Un grupo que se queda sin nada desaparece: un hueco no es una decisión. */
    @Test
    fun `el grupo que se queda vacío desaparece`() {
        val grupos = listOf(listOf(Tool.ARROW), listOf(Tool.RECTANGLE))
        val movido = moverHerramienta(grupos, Tool.ARROW, grupoDestino = 1, indice = 1)
        assertEquals(listOf(listOf(Tool.RECTANGLE, Tool.ARROW)), movido)
    }

    /**
     * El error clásico de mover dentro de la misma lista: al sacarlo, todo lo
     * que venía después adelanta una posición, y el índice de destino ya no
     * significa lo mismo.
     */
    @Test
    fun `mover dentro del mismo grupo hacia la derecha cae donde se soltó`() {
        val grupos = listOf(listOf(Tool.ARROW, Tool.LINE, Tool.TEXT))
        // Soltar la flecha justo antes del texto: tiene que quedar en medio.
        val movido = moverHerramienta(grupos, Tool.ARROW, grupoDestino = 0, indice = 2)
        assertEquals(listOf(listOf(Tool.LINE, Tool.ARROW, Tool.TEXT)), movido)
    }

    @Test
    fun `mover nunca pierde ni duplica`() {
        val grupos = GRUPOS_DE_FABRICA
        for (destino in 0..grupos.size) {
            val movido = moverHerramienta(grupos, Tool.TEXT, destino, 0)
            val puestas = todas(movido)
            assertEquals("se ha duplicado algo", puestas.size, puestas.toSet().size)
            assertEquals("se ha perdido algo", todas(grupos).toSet(), puestas.toSet())
        }
    }

    /**
     * Mover algo que no estaba es **darlo de alta**, no un error: es lo que
     * pasa al devolver a la barra una herramienta que estaba fuera de ella, y
     * ahí el gesto del usuario es exactamente el mismo que mover.
     */
    @Test
    fun `mover algo que no estaba lo añade donde se suelta`() {
        val grupos = listOf(listOf(Tool.ARROW))
        assertEquals(
            listOf(listOf(Tool.TEXT, Tool.ARROW)),
            moverHerramienta(grupos, Tool.TEXT, 0, 0)
        )
        assertEquals(
            listOf(listOf(Tool.ARROW), listOf(Tool.TEXT)),
            moverHerramienta(grupos, Tool.TEXT, 1, 0)
        )
    }

    // ---- Dónde cae el dedo ----

    private val filas = listOf(
        FilaDeGrupo(arriba = 0f, abajo = 100f, centros = listOf(10f, 50f, 90f)),
        FilaDeGrupo(arriba = 100f, abajo = 200f, centros = listOf(10f))
    )

    @Test
    fun `el dedo elige fila por la altura y hueco por el lado`() {
        assertEquals(0 to 0, destinoDeArrastre(x = 5f, y = 50f, filas = filas))
        assertEquals(0 to 1, destinoDeArrastre(x = 30f, y = 50f, filas = filas))
        assertEquals(0 to 3, destinoDeArrastre(x = 999f, y = 50f, filas = filas))
        assertEquals(1 to 1, destinoDeArrastre(x = 999f, y = 150f, filas = filas))
    }

    @Test
    fun `por debajo de la última fila es grupo nuevo`() {
        assertEquals(filas.size to 0, destinoDeArrastre(x = 50f, y = 900f, filas = filas))
    }

    @Test
    fun `por encima de la primera es el principio`() {
        assertEquals(0 to 0, destinoDeArrastre(x = 50f, y = -30f, filas = filas))
    }

    @Test
    fun `sin filas no revienta`() {
        assertEquals(0 to 0, destinoDeArrastre(x = 5f, y = 5f, filas = emptyList()))
    }

    // ---- La cara del grupo ----

    @Test
    fun `el grupo enseña la herramienta puesta y si no la primera`() {
        val grupo = listOf(Tool.RECTANGLE, Tool.ELLIPSE, Tool.DIAMOND)
        assertEquals(Tool.ELLIPSE, caraDelGrupo(grupo, Tool.ELLIPSE))
        assertEquals(Tool.RECTANGLE, caraDelGrupo(grupo, Tool.ARROW))
        assertEquals(null, caraDelGrupo(emptyList(), Tool.ARROW))
    }

    /** Con grupos, ninguna herramienta puede quedar a más de dos toques. */
    @Test
    fun `todo se alcanza en dos toques`() {
        val grupos = gruposDe(null, Tool.entries.toSet())
        for (t in Tool.entries) {
            val grupo = grupos.firstOrNull { t in it }
            assertTrue("$t no está en ningún grupo", grupo != null)
            // Uno para abrir el grupo y otro para elegir; si está sola, uno.
            assertFalse("grupo demasiado grande para dos toques", grupo!!.size > 8)
        }
    }
}
