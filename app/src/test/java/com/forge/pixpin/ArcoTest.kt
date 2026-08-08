package com.forge.pixpin.motor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El arco: se pone un círculo **de referencia** y se repasa con la herramienta
 * de elipse el trozo que hace falta, como con un transportador.
 *
 * Lo que se comprueba aquí es lo que decide si el arco sale donde uno quiso: por
 * dónde empieza, cuánto barre y que no pegue saltos al cruzar el punto donde el
 * ángulo cambia de signo.
 */
class ArcoTest {

    private fun controller() = DrawController()

    /** Pone el óvalo **de referencia** arrastrando de esquina a esquina. */
    private fun DrawController.ponOvalo(
        x1: Double = 0.0, y1: Double = 0.0, x2: Double = 200.0, y2: Double = 200.0
    ): Element {
        // De esquina a esquina y no desde el centro: así la caja es la que dicen
        // las coordenadas y las cuentas de estas pruebas se leen solas. Con el
        // dedo, la elipse nace del centro y eso no cambia nada de lo que aquí se
        // comprueba.
        ellipseFromCenter = false
        modoReferencia = true
        selectTool(Tool.ELLIPSE)
        pointerDown(Pt(x1, y1))
        pointerMove(Pt(x2, y2))
        pointerUp(Pt(x2, y2))
        modoReferencia = false
        return scene.visible.last()
    }

    // ---- Los dos tiempos ----

    /** Lo trazado en modo referencia nace marcado como tal. */
    @Test
    fun `el óvalo de referencia nace marcado`() {
        val c = controller()
        val e = c.ponOvalo()
        assertEquals(ElementType.ELLIPSE, e.type)
        assertTrue("tendría que ser referencia", e.reference)
        assertTrue(c.hayReferencias)
    }

    /**
     * **Repasar la referencia con la elipse dibuja el trozo repasado.** El
     * círculo de referencia se queda donde estaba: es el instrumento, y un
     * transportador no se gasta al usarlo.
     */
    @Test
    fun `repasar el óvalo de referencia traza un arco`() {
        val c = controller()
        val guia = c.ponOvalo()
        // Centro en (100,100), radio 100: se empieza por la derecha y se baja.
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(200.0, 100.0))
        c.pointerMove(Pt(100.0, 200.0))
        c.pointerUp(Pt(100.0, 200.0))

        val arco = c.scene.visible.last()
        assertEquals(ElementType.ARC, arco.type)
        assertFalse("lo trazado es dibujo, no referencia", arco.reference)
        assertEquals("empieza por la derecha", 0.0, arco.arcStart!!, 1e-6)
        assertEquals("y ha barrido un cuarto", PI / 2, arco.arcSweep!!, 1e-6)
        assertNotNull("la referencia sigue ahí", c.scene.byId(guia.id))
    }

    /** Lejos de la referencia, la elipse hace lo de siempre. */
    @Test
    fun `fuera del óvalo la elipse sigue dibujando elipses`() {
        val c = controller()
        c.ponOvalo()
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(600.0, 600.0))
        c.pointerMove(Pt(700.0, 700.0))
        c.pointerUp(Pt(700.0, 700.0))
        assertEquals(ElementType.ELLIPSE, c.scene.visible.last().type)
    }

    /** Y repasar un óvalo del dibujo de verdad, tampoco: ahí se dibuja encima. */
    @Test
    fun `un óvalo normal no se repasa`() {
        val c = controller()
        c.ellipseFromCenter = false
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(200.0, 200.0))
        c.pointerUp(Pt(200.0, 200.0))

        c.pointerDown(Pt(200.0, 100.0))
        c.pointerMove(Pt(100.0, 200.0))
        c.pointerUp(Pt(100.0, 200.0))
        assertTrue(c.scene.visible.none { it.type == ElementType.ARC })
    }

    /** Un roce sin recorrido no es un arco: no se queda nada. */
    @Test
    fun `sin barrido no queda nada en el dibujo`() {
        val c = controller()
        val guia = c.ponOvalo()
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(200.0, 100.0))
        c.pointerUp(Pt(200.0, 100.0))
        assertTrue(c.scene.visible.none { it.type == ElementType.ARC })
        assertNotNull("y la referencia no se toca", c.scene.byId(guia.id))
    }

    // ---- Las referencias ----

    /** Esconderlas no las borra: es para mirar el dibujo limpio y volver. */
    @Test
    fun `esconder las referencias no las pierde`() {
        val c = controller()
        val guia = c.ponOvalo()
        c.alternarReferencias()
        assertFalse(c.referenciasVisibles)
        assertNotNull(c.scene.byId(guia.id))
        assertTrue("escondida, no se pinta", c.scene.visibleConReferencias.none { it.reference })
        c.alternarReferencias()
        assertTrue(c.scene.visibleConReferencias.any { it.reference })
    }

    /** Escondidas tampoco imantan: pegarse a algo que no se ve desconcierta. */
    @Test
    fun `escondidas dejan de imantar`() {
        val c = controller()
        c.ponOvalo(0.0, 0.0, 200.0, 200.0)
        c.alternarReferencias()
        c.selectTool(Tool.LINE)
        c.pointerDown(Pt(203.0, 100.0))
        c.pointerMove(Pt(400.0, 400.0))
        c.pointerUp(Pt(400.0, 400.0))
        val inicio = absolutePoints(c.scene.visible.last()).first()
        assertEquals("no debía pegarse a nada", 203.0, inicio.x, 1e-6)
    }

    /**
     * El borrón final: se quita el azul con el borrador y queda lo dibujado.
     *
     * Ya no hay «borrar todas»: era un botón que en un descuido se lleva el
     * andamio entero. Se quitan de una en una, y **solo en modo guía**, que es
     * lo que impide rascar el dibujo de debajo al hacerlo.
     */
    @Test
    fun `borrar una referencia no toca el dibujo`() {
        val c = controller()
        c.ponOvalo()
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(400.0, 400.0))
        c.pointerMove(Pt(500.0, 500.0))
        c.pointerUp(Pt(500.0, 500.0))

        c.modoReferencia = true
        c.selectTool(Tool.ERASER)
        // Justo sobre el borde del óvalo guía, que va de (0,0) a (200,200).
        c.pointerDown(Pt(200.0, 100.0))
        c.pointerUp(Pt(200.0, 100.0))

        assertFalse(c.hayReferencias)
        assertEquals(1, c.scene.visible.size)
        assertEquals(ElementType.RECTANGLE, c.scene.visible.first().type)
        // Y es un borrón deshacible como cualquier otro.
        c.undo()
        assertTrue(c.hayReferencias)
    }

    /** Cualquier herramienta puede trazar referencia, no solo las formas. */
    @Test
    fun `el modo referencia vale para todas las herramientas`() {
        val c = controller()
        c.modoReferencia = true
        for (t in listOf(Tool.RECTANGLE, Tool.LINE, Tool.ARROW, Tool.FREEDRAW, Tool.TEXT)) {
            c.selectTool(t)
            c.pointerDown(Pt(0.0, 0.0))
            c.pointerMove(Pt(80.0, 80.0))
            c.pointerUp(Pt(80.0, 80.0))
            assertTrue("$t no trazó referencia", c.scene.visible.last().reference)
        }
    }

    // ---- El barrido ----

    /**
     * **El salto de +π a −π.** Al pasar por la izquierda del círculo el ángulo
     * cambia de signo de golpe; sumando en bruto, el arco se daba la vuelta
     * entera en un fotograma. Lo que se acumula es la diferencia más corta.
     */
    @Test
    fun `el barrido cruza el cambio de signo sin pegar saltos`() {
        // Justo antes y justo después de π: el paso real es de 0,2 radianes.
        val antes = PI - 0.1
        val despues = -PI + 0.1
        assertEquals(0.2, barridoAcumulado(0.0, antes, despues), 1e-9)
        // Y al revés.
        assertEquals(-0.2, barridoAcumulado(0.0, despues, antes), 1e-9)
    }

    @Test
    fun `el barrido se acumula en las dos direcciones`() {
        assertEquals(1.5, barridoAcumulado(1.0, 0.0, 0.5), 1e-9)
        assertEquals(0.5, barridoAcumulado(1.0, 0.0, -0.5), 1e-9)
    }

    /** Más de una vuelta vuelve a ser el óvalo entero: no se sigue sumando. */
    @Test
    fun `el barrido se topa en una vuelta`() {
        assertEquals(2 * PI, barridoAcumulado(2 * PI - 0.05, 0.0, 0.5), 1e-9)
        assertEquals(-2 * PI, barridoAcumulado(-2 * PI + 0.05, 0.0, -0.5), 1e-9)
    }

    // ---- Dónde cae el trazo ----

    /**
     * El ángulo es **paramétrico**, no geométrico: se divide por cada semieje
     * antes del `atan2`. Es lo que hace que recorrer un óvalo achatado avance a
     * ritmo constante bajo el dedo en vez de correr en los extremos.
     */
    @Test
    fun `el ángulo de un óvalo achatado sigue al dedo`() {
        val c = controller()
        // Ancho 400, alto 100: bien achatado.
        val e = c.ponOvalo(0.0, 0.0, 400.0, 100.0)
        // A 45° paramétricos de un óvalo así, el punto no está en la diagonal.
        val punto = Pt(200.0 + 200.0 * 0.7071, 50.0 + 50.0 * 0.7071)
        assertEquals(PI / 4, anguloEnElOvalo(e, punto), 1e-6)
    }

    @Test
    fun `los puntos del arco caen sobre el óvalo`() {
        val c = controller()
        val e = c.ponOvalo(0.0, 0.0, 200.0, 200.0).copy(arcStart = 0.0, arcSweep = PI)
        val puntos = puntosDelArco(e).map { Pt(e.x + it.x, e.y + it.y) }

        assertTrue(puntos.size > 8)
        // Todos a 100 del centro, que es el radio.
        puntos.forEach {
            assertEquals(100.0, hypot(it.x - 100.0, it.y - 100.0), 1e-6)
        }
        // Media vuelta: empieza a la derecha y acaba a la izquierda.
        assertEquals(200.0, puntos.first().x, 1e-6)
        assertEquals(0.0, puntos.last().x, 1e-6)
    }

    /** Media vuelta gasta la mitad de tramos que la vuelta entera. */
    @Test
    fun `un arco corto no gasta los tramos de una vuelta entera`() {
        val c = controller()
        val base = c.ponOvalo()
        val medio = puntosDelArco(base.copy(arcStart = 0.0, arcSweep = PI)).size
        val entero = puntosDelArco(base.copy(arcStart = 0.0, arcSweep = 2 * PI)).size
        assertTrue("el corto tendría que gastar menos", medio < entero)
        assertTrue("pero no quedarse en dos rayas", medio > 8)
    }

    @Test
    fun `un óvalo sin tamaño no da puntos`() {
        val e = newElement(ElementType.ARC, 0.0, 0.0, ItemStyle())
        assertTrue(puntosDelArco(e).isEmpty())
    }

    // ---- Con el resto del motor ----

    /** Se pica por su trazo, que es lo que se ve: no tiene dentro. */
    @Test
    fun `el arco se selecciona tocando su curva`() {
        val c = controller()
        c.ponOvalo(0.0, 0.0, 200.0, 200.0)
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(200.0, 100.0))
        c.pointerMove(Pt(100.0, 200.0))
        c.pointerUp(Pt(100.0, 200.0))

        val arco = c.scene.visible.last()
        // Un punto sobre el cuarto trazado, a 45°.
        val enLaCurva = Pt(100.0 + 100.0 * 0.7071, 100.0 + 100.0 * 0.7071)
        assertNotNull(getElementAtPosition(listOf(arco), enLaCurva, 6.0))
        // Y el centro no lo selecciona: un arco no encierra nada.
        assertNull(getElementAtPosition(listOf(arco), Pt(100.0, 100.0), 6.0))
        // El trozo que NO se trazó tampoco.
        assertNull(getElementAtPosition(listOf(arco), Pt(0.0, 100.0), 6.0))
    }

    /** Viaja con el dibujo: se guarda y se relee con su arco puesto. */
    @Test
    fun `el arco se guarda y se relee`() {
        val c = controller()
        // Un arco de verdad, del que sale de repasar la referencia.
        val e = c.ponOvalo().copy(
            type = ElementType.ARC, reference = false, arcStart = 0.5, arcSweep = 1.25
        )
        val escena = Scene(elements = listOf(e))
        val vuelta = ExcalidrawJson.decodeFromString<Scene>(
            ExcalidrawJson.encodeToString(escena)
        )
        val leido = vuelta.elements.first()
        assertEquals(ElementType.ARC, leido.type)
        assertEquals(0.5, leido.arcStart!!, 1e-9)
        assertEquals(1.25, leido.arcSweep!!, 1e-9)
    }

    /** Con el segundo dedo, la referencia sale redonda como cualquier forma. */
    @Test
    fun `con el segundo dedo la guía sale circular`() {
        val c = controller()
        c.keepAspectRatio = true
        val e = c.ponOvalo(0.0, 0.0, 300.0, 80.0)
        assertEquals(e.width, e.height, 1e-9)
        assertTrue(abs(e.width) > 0.0)
    }
}
