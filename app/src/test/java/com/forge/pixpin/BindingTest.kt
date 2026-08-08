package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A qué se engancha la punta de una flecha.
 *
 * Es la parte del motor que decide si dibujar un esquema es cómodo o
 * desesperante, y tiene dos fallos opuestos que se persiguen el uno al otro: si
 * solo ancla en el borde, hay que buscar el borde con el dedo; si ancla en
 * cualquier cosa que toque, una flecha que cruza una caja se queda atada a
 * ella. Estas pruebas fijan el equilibrio del original.
 */
class BindingTest {

    private fun caja(
        id: String, x: Double, y: Double, w: Double, h: Double,
        fondo: String = Element.TRANSPARENT
    ) = Element(
        id = id, type = ElementType.RECTANGLE, x = x, y = y, width = w, height = h,
        seed = 1, backgroundColor = fondo
    )

    // ---- Lo que pedía el usuario: dentro también ancla ----

    /**
     * La punta **dentro** de la caja ancla. No hace falta acertar el borde: es
     * lo que hace el original y sin ello un esquema es imposible con el dedo.
     */
    @Test
    fun `la punta dentro de la forma ancla`() {
        val r = caja("a", 100.0, 100.0, 200.0, 150.0)
        // Bien adentro, lejísimos de cualquier borde.
        assertEquals("a", getHoveredElementForBinding(listOf(r), Pt(200.0, 175.0))?.id)
    }

    @Test
    fun `la punta sobre el borde tambien`() {
        val r = caja("a", 100.0, 100.0, 200.0, 150.0)
        assertEquals("a", getHoveredElementForBinding(listOf(r), Pt(100.0, 175.0))?.id)
    }

    /** Cerca por fuera también, que es lo que da el imán. */
    @Test
    fun `la punta un poco fuera sigue anclando`() {
        val r = caja("a", 100.0, 100.0, 200.0, 150.0)
        assertEquals("a", getHoveredElementForBinding(listOf(r), Pt(95.0, 175.0))?.id)
    }

    @Test
    fun `lejos no ancla`() {
        val r = caja("a", 100.0, 100.0, 200.0, 150.0)
        assertNull(getHoveredElementForBinding(listOf(r), Pt(20.0, 175.0)))
    }

    // ---- Y sin volver a romper lo de antes ----

    /**
     * El fallo que motivó el intento anterior: una flecha que **atraviesa** un
     * rectángulo y acaba más allá no puede quedarse atada al del medio.
     *
     * Se resuelve solo, porque lo que se prueba es dónde ACABA la flecha. Aquí
     * se comprueba explícitamente para que nadie lo vuelva a «arreglar»
     * cambiando la prueba del interior.
     */
    @Test
    fun `una flecha que cruza una caja y acaba fuera no se ata a ella`() {
        val medio = caja("medio", 100.0, 100.0, 200.0, 150.0, fondo = "#ffcccc")
        val destino = caja("destino", 500.0, 150.0, 80.0, 60.0)
        // La punta acaba en el destino, habiendo cruzado «medio» por el camino.
        val elegido = getHoveredElementForBinding(listOf(medio, destino), Pt(540.0, 180.0))
        assertEquals("destino", elegido?.id)
    }

    // ---- Las reglas de desempate del original ----

    /** Con una caja dentro de otra gana la pequeña: es la que se señalaba. */
    @Test
    fun `entre dos que se solapan gana la mas pequeña`() {
        val grande = caja("grande", 0.0, 0.0, 400.0, 400.0)
        val pequena = caja("pequena", 150.0, 150.0, 60.0, 60.0)
        assertEquals(
            "pequena",
            getHoveredElementForBinding(listOf(grande, pequena), Pt(180.0, 180.0))?.id
        )
        // Y si se sale de la pequeña, la grande vuelve a ser la buena.
        assertEquals(
            "grande",
            getHoveredElementForBinding(listOf(grande, pequena), Pt(50.0, 50.0))?.id
        )
    }

    /**
     * Una forma **opaca** tapa lo que hay detrás, y lo tapado no es candidato:
     * no se puede anclar a algo que no se ve.
     */
    @Test
    fun `una forma opaca tapa a la de detras`() {
        val detras = caja("detras", 100.0, 100.0, 60.0, 60.0)
        val delante = caja("delante", 90.0, 90.0, 200.0, 200.0, fondo = "#1971c2")
        assertEquals(
            "delante",
            getHoveredElementForBinding(listOf(detras, delante), Pt(130.0, 130.0))?.id
        )
    }

    /** Si la de delante es transparente, se ve la de detrás y compite. */
    @Test
    fun `una forma transparente deja ver la de detras`() {
        val detras = caja("detras", 100.0, 100.0, 60.0, 60.0)
        val delante = caja("delante", 90.0, 90.0, 200.0, 200.0)
        assertEquals(
            "detras",
            getHoveredElementForBinding(listOf(detras, delante), Pt(130.0, 130.0))?.id
        )
    }

    // ---- Lo que nunca ancla ----

    @Test
    fun `lo borrado, lo bloqueado y lo no anclable se ignoran`() {
        val p = Pt(150.0, 150.0)
        assertNull(getHoveredElementForBinding(listOf(caja("a", 100.0, 100.0, 100.0, 100.0).copy(isDeleted = true)), p))
        assertNull(getHoveredElementForBinding(listOf(caja("a", 100.0, 100.0, 100.0, 100.0).copy(locked = true)), p))
        val flecha = Element(
            id = "f", type = ElementType.ARROW, x = 100.0, y = 100.0,
            width = 100.0, height = 100.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(100.0, 100.0))
        )
        assertNull(getHoveredElementForBinding(listOf(flecha), p))
    }

    /** El umbral se relaja al alejar la vista, para que el imán se note igual. */
    @Test
    fun `el alcance del iman crece al alejar la vista`() {
        val cerca = maxBindingDistance(1.0)
        val lejos = maxBindingDistance(0.25)
        assert(lejos > cerca) { "alejando ($lejos) tendría que atraer más que a 1× ($cerca)" }
        assertEquals(cerca * 2, maxBindingDistance(0.05), 0.001)
    }

    // ---- Los dos modos de anclaje ----

    /**
     * Soltar la punta **bien adentro** la deja ahí, señalando ese punto.
     *
     * Es lo que faltaba: antes cualquier anclaje acababa proyectado al
     * contorno, así que daba igual dónde soltases — la flecha siempre tocaba la
     * caja por fuera. El original distingue dos modos y este es el de dentro.
     */
    @Test
    fun `soltar dentro guarda el punto y el modo dentro`() {
        val r = caja("a", 100.0, 100.0, 200.0, 200.0)
        val flecha = Element(
            id = "f", type = ElementType.ARROW, x = 0.0, y = 0.0,
            width = 150.0, height = 50.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(150.0, 150.0))
        )
        val atada = bindArrow(flecha, r, ArrowEnd.END)
        val b = atada.endBinding!!

        assertEquals(BindMode.INSIDE, b.mode)
        // (150,150) dentro de una caja en (100,100) de 200×200 → la cuarta parte.
        assertEquals(0.25, b.fixedPoint!![0], 0.001)
        assertEquals(0.25, b.fixedPoint!![1], 0.001)
    }

    @Test
    fun `soltar en el borde usa el modo contorno`() {
        val r = caja("a", 100.0, 100.0, 200.0, 200.0)
        val flecha = Element(
            id = "f", type = ElementType.ARROW, x = 0.0, y = 0.0,
            width = 100.0, height = 200.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(100.0, 200.0))
        )
        assertEquals(BindMode.ORBIT, bindArrow(flecha, r, ArrowEnd.END).endBinding!!.mode)
    }

    /**
     * Y el punto guardado **sobrevive a mover la forma**: la punta lo persigue
     * en vez de recolocarse en el borde.
     */
    @Test
    fun `el punto de dentro sigue a la forma`() {
        val r = caja("a", 100.0, 100.0, 200.0, 200.0)
        val flecha = Element(
            id = "f", type = ElementType.ARROW, x = 0.0, y = 0.0,
            width = 150.0, height = 150.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(150.0, 150.0))
        )
        val atada = bindArrow(flecha, r, ArrowEnd.END)

        // La caja se va 300 px a la derecha y 100 abajo.
        val movida = r.copy(x = 400.0, y = 200.0)
        val recolocada = updateBoundPoints(atada, listOf(movida))
        val punta = absolutePoints(recolocada).last()

        // El mismo punto relativo: un cuarto de la caja desde su esquina.
        assertEquals(400.0 + 50.0, punta.x, 0.5)
        assertEquals(200.0 + 50.0, punta.y, 0.5)
    }

    /** Y a redimensionarla: la proporción manda, no la distancia. */
    @Test
    fun `el punto de dentro sobrevive a redimensionar`() {
        val r = caja("a", 0.0, 0.0, 100.0, 100.0)
        val flecha = Element(
            id = "f", type = ElementType.ARROW, x = -100.0, y = -100.0,
            width = 150.0, height = 150.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(150.0, 150.0))
        )
        val atada = bindArrow(flecha, r, ArrowEnd.END)
        assertEquals(BindMode.INSIDE, atada.endBinding!!.mode)

        // Al doble de tamaño, el punto sigue estando a la mitad de la caja.
        val grande = r.copy(width = 200.0, height = 200.0)
        val punta = absolutePoints(updateBoundPoints(atada, listOf(grande))).last()
        assertEquals(100.0, punta.x, 0.5)
        assertEquals(100.0, punta.y, 0.5)
    }
}
