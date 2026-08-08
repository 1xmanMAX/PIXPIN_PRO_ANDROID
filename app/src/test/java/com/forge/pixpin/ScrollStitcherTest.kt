package com.forge.pixpin.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * El cosido de una captura con scroll.
 *
 * **Sin bitmaps y sin Robolectric.** Antes hacían falta imágenes de verdad, y
 * eso solo funciona con el motor gráfico nativo de Robolectric, que no existe
 * para Linux sobre ARM: las siete pruebas no fallaban por un error del cosido,
 * es que no llegaban a ejecutarse. Ahora se comprueba [ScrollPlan], que es quien
 * decide —si el fotograma encaja, qué franja es nueva, cuándo parar—; copiar los
 * píxeles después es trabajo mecánico.
 *
 * La página se representa por sus **firmas de fila**, un número por fila, que es
 * exactamente lo único que el algoritmo mira de una imagen.
 */
class ScrollStitcherTest {

    /**
     * Página larga con textura. Deliberadamente «ruidosa» y no un degradado: un
     * degradado desplazado se parece a sí mismo en cualquier posición, y el
     * cosido lo rechaza a propósito por ambiguo. El contenido real de una
     * pantalla —texto, iconos— tiene esta pinta, no la de una rampa.
     */
    private fun pagina(filas: Int): IntArray =
        IntArray(filas) { y -> Random(y).nextInt(100_000) }

    /** Lo que se ve por la ventana: las filas [desde, desde+filas). */
    private fun ventana(pagina: IntArray, desde: Int, filas: Int): IntArray =
        pagina.copyOfRange(desde, desde + filas)

    @Test
    fun `el primer fotograma es la base`() {
        val plan = ScrollPlan(maxHeight = 2000)
        val orden = plan.plan(pagina(200), 200)
        assertEquals(ScrollPlan.Result.FIRST, orden.result)
        assertEquals(200, plan.height)
        assertEquals("del primero se queda todo", 0, orden.desde)
        assertEquals(200, orden.filas)
    }

    @Test
    fun `solo se añade lo que hay de nuevo`() {
        val fuente = pagina(600)
        val plan = ScrollPlan(maxHeight = 2000)

        plan.plan(ventana(fuente, 0, 200), 200)
        val orden = plan.plan(ventana(fuente, 100, 200), 200)

        assertEquals(ScrollPlan.Result.APPENDED, orden.result)
        assertEquals("200 filas + las 100 nuevas", 300, plan.height)
        assertEquals("solo las 100 del final", 100, orden.filas)
        assertEquals(100, orden.desde)
    }

    /**
     * La prueba que de verdad importa: **lo cosido reproduce la página**.
     *
     * Se sigue el plan fila a fila y se compara el resultado con el original.
     * Antes esto se comprobaba mirando píxeles de un bitmap; lo que decidía si
     * salían bien o mal era esto mismo.
     */
    @Test
    fun `la imagen final reproduce la pagina original`() {
        val fuente = pagina(600)
        val plan = ScrollPlan(maxHeight = 2000)
        val cosido = mutableListOf<Int>()

        for (desde in listOf(0, 100, 200)) {
            val marco = ventana(fuente, desde, 200)
            val orden = plan.plan(marco, 200)
            repeat(orden.filas) { i -> cosido += marco[orden.desde + i] }
        }

        assertEquals(400, cosido.size)
        assertEquals(400, plan.height)
        assertEquals(fuente.take(400), cosido)
    }

    @Test
    fun `si la pantalla no se movio no se añade nada`() {
        val fuente = pagina(600)
        val plan = ScrollPlan(maxHeight = 2000)
        plan.plan(ventana(fuente, 0, 200), 200)
        val orden = plan.plan(ventana(fuente, 0, 200), 200)
        assertEquals(ScrollPlan.Result.NO_MOVEMENT, orden.result)
        assertEquals(200, plan.height)
        assertEquals(0, orden.filas)
    }

    /** Ante la duda, no cose: un fotograma mal encajado estropea la imagen entera. */
    @Test
    fun `un fotograma que no encaja se descarta`() {
        val plan = ScrollPlan(maxHeight = 2000)
        plan.plan(pagina(200), 200)
        // Contenido sin ninguna relación: el usuario cambió de aplicación.
        val otro = IntArray(200) { Random(it + 9_999).nextInt(100_000) }
        assertEquals(ScrollPlan.Result.UNCERTAIN, plan.plan(otro, 200).result)
        assertEquals("no se ha ensuciado lo acumulado", 200, plan.height)
    }

    @Test
    fun `al llegar al alto maximo deja de acumular`() {
        val fuente = pagina(600)
        val plan = ScrollPlan(maxHeight = 250)
        plan.plan(ventana(fuente, 0, 200), 200)
        val orden = plan.plan(ventana(fuente, 100, 200), 200)
        assertEquals(ScrollPlan.Result.FULL, orden.result)
        assertEquals("lo ya cosido se conserva", 200, plan.height)
        assertEquals(0, orden.filas)
    }

    @Test
    fun `un fotograma mas bajo que la referencia se descarta`() {
        val plan = ScrollPlan(maxHeight = 2000)
        plan.plan(pagina(200), 200)
        assertEquals(ScrollPlan.Result.UNCERTAIN, plan.plan(pagina(20), 20).result)
    }

    /** Y el primero tampoco entra si ya no cabe: no se empieza para nada. */
    @Test
    fun `un primer fotograma más alto que el tope no se cose`() {
        val plan = ScrollPlan(maxHeight = 100)
        assertEquals(ScrollPlan.Result.FULL, plan.plan(pagina(200), 200).result)
        assertTrue(plan.isEmpty)
    }
}
