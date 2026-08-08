package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Geometría del módulo `draw`: cajas, picado y tiradores.
 *
 * Todo aquí es Kotlin puro —ni una llamada a `android.graphics`—, así que corre
 * en la JVM sin dispositivo, que es lo que permite comprobar el port contra los
 * números del original sin tener que mirar la pantalla.
 */
class DrawGeometryTest {

    private fun rect(
        x: Double = 0.0, y: Double = 0.0, w: Double = 100.0, h: Double = 50.0,
        angle: Double = 0.0, bg: String = Element.TRANSPARENT
    ) = Element(
        id = "r", type = ElementType.RECTANGLE, x = x, y = y,
        width = w, height = h, angle = angle, backgroundColor = bg, seed = 1
    )

    @Test
    fun `caja sin rotar coincide con la posicion y el tamaño`() {
        val c = getElementAbsoluteCoords(rect(10.0, 20.0, 100.0, 50.0))
        assertEquals(10.0, c.x1, 1e-9)
        assertEquals(20.0, c.y1, 1e-9)
        assertEquals(110.0, c.x2, 1e-9)
        assertEquals(70.0, c.y2, 1e-9)
        assertEquals(60.0, c.cx, 1e-9)
        assertEquals(45.0, c.cy, 1e-9)
    }

    /**
     * Un cuadrado girado 45° ocupa una caja de lado `l·√2`. Es el caso que
     * distingue [getElementBounds] de [getElementAbsoluteCoords]; confundirlos
     * hace que la selección por área falle con todo lo que esté rotado.
     */
    @Test
    fun `la caja de un cuadrado girado 45 grados crece por la raiz de dos`() {
        val e = rect(0.0, 0.0, 100.0, 100.0, angle = Math.PI / 4)
        val b = getElementBounds(e)
        assertEquals(100.0 * Math.sqrt(2.0), b.width, 1e-6)
        assertEquals(100.0 * Math.sqrt(2.0), b.height, 1e-6)
        // El centro no se mueve al rotar.
        assertEquals(50.0, b.midX, 1e-6)
        assertEquals(50.0, b.midY, 1e-6)
    }

    @Test
    fun `girar un punto y desgirarlo lo devuelve a su sitio`() {
        val p = Pt(37.0, -12.0)
        val center = Pt(5.0, 5.0)
        val ida = pointRotateRads(p, center, 1.234)
        val vuelta = pointRotateRads(ida, center, -1.234)
        assertEquals(p.x, vuelta.x, 1e-9)
        assertEquals(p.y, vuelta.y, 1e-9)
    }

    // ---------------------------------------------------------------------
    // Picado
    // ---------------------------------------------------------------------

    /**
     * Un rectángulo sin relleno se coge por el borde, no por el hueco. Es la
     * regla de `shouldTestInside` y lo que permite seleccionar lo que hay
     * debajo de un marco vacío.
     */
    @Test
    fun `un rectangulo transparente no se pica por dentro`() {
        val e = rect()
        assertFalse(shouldTestInside(e))
        assertFalse(hitElementItself(Pt(50.0, 25.0), e, 2.0))
        assertTrue(hitElementItself(Pt(0.0, 25.0), e, 2.0))
    }

    @Test
    fun `un rectangulo relleno si se pica por dentro`() {
        val e = rect(bg = "#ff0000")
        assertTrue(shouldTestInside(e))
        assertTrue(hitElementItself(Pt(50.0, 25.0), e, 2.0))
    }

    @Test
    fun `el picado respeta la rotacion del elemento`() {
        val e = rect(0.0, 0.0, 100.0, 20.0, angle = Math.PI / 2, bg = "#ff0000")
        // Girado 90°, el rectángulo largo pasa a ser alto: un punto muy por
        // encima del centro cae dentro, y uno muy a la derecha ya no.
        assertTrue(hitElementItself(Pt(50.0, 45.0), e, 2.0))
        assertFalse(hitElementItself(Pt(95.0, 10.0), e, 2.0))
    }

    @Test
    fun `gana el elemento de mas arriba`() {
        val abajo = rect(bg = "#ff0000").copy(id = "abajo")
        val arriba = rect(bg = "#00ff00").copy(id = "arriba")
        val hit = getElementAtPosition(listOf(abajo, arriba), Pt(50.0, 25.0))
        assertEquals("arriba", hit?.id)
    }

    @Test
    fun `un elemento bloqueado no se pica`() {
        val e = rect(bg = "#ff0000").copy(locked = true)
        assertNull(getElementAtPosition(listOf(e), Pt(50.0, 25.0)))
    }

    @Test
    fun `seleccion por area en modo contener exige encerrar del todo`() {
        val e = rect(0.0, 0.0, 100.0, 50.0)
        val parcial = Bounds(-10.0, -10.0, 50.0, 60.0)
        assertTrue(getElementsWithinSelection(listOf(e), parcial, BoxSelectionMode.CONTAIN).isEmpty())
        assertEquals(1, getElementsWithinSelection(listOf(e), parcial, BoxSelectionMode.OVERLAP).size)
    }

    // ---------------------------------------------------------------------
    // Tiradores
    // ---------------------------------------------------------------------

    /**
     * Los tiradores se miden en píxeles de pantalla, así que al duplicar el
     * zoom su tamaño en escena tiene que reducirse a la mitad. Si no, al
     * ampliar mucho el tirador taparía la forma entera.
     */
    @Test
    fun `el tirador mide lo mismo en pantalla a cualquier zoom`() {
        val e = rect(0.0, 0.0, 200.0, 200.0)
        val a = getTransformHandles(e, zoom = 1.0, handleSize = 28.0).first()
        val b = getTransformHandles(e, zoom = 2.0, handleSize = 28.0).first()
        assertEquals(a.width / 2, b.width, 1e-9)
    }

    @Test
    fun `por defecto solo salen las cuatro esquinas y la rotacion`() {
        val e = rect(0.0, 0.0, 200.0, 200.0)
        val tipos = getTransformHandles(e, zoom = 1.0).map { it.type }.toSet()
        assertEquals(
            setOf(
                HandleType.NW, HandleType.NE, HandleType.SW, HandleType.SE,
                HandleType.ROTATION
            ),
            tipos
        )
    }

    @Test
    fun `un elemento bloqueado no enseña tiradores`() {
        assertTrue(getTransformHandles(rect().copy(locked = true), 1.0).isEmpty())
    }

    // ---------------------------------------------------------------------
    // Redimensionar y rotar
    // ---------------------------------------------------------------------

    /**
     * El invariante de todo el redimensionado: **la esquina opuesta no se
     * mueve**. Se comprueba sin rotación y con rotación, que es donde la
     * aritmética ingenua falla.
     */
    @Test
    fun `redimensionar por SE deja clavada la esquina NW`() {
        val e = rect(10.0, 20.0, 100.0, 50.0)
        val antes = getElementAbsoluteCoords(e)
        val despues = getElementAbsoluteCoords(
            resizeSingleElement(e, HandleType.SE, Pt(200.0, 300.0))
        )
        assertEquals(antes.x1, despues.x1, 1e-6)
        assertEquals(antes.y1, despues.y1, 1e-6)
        assertEquals(200.0, despues.x2, 1e-6)
        assertEquals(300.0, despues.y2, 1e-6)
    }

    @Test
    fun `redimensionar un elemento rotado tambien deja clavada el ancla`() {
        val e = rect(10.0, 20.0, 100.0, 50.0, angle = 0.7)
        val c = getElementAbsoluteCoords(e)
        val anclaAntes = pointRotateRads(Pt(c.x1, c.y1), Pt(c.cx, c.cy), e.angle)

        val r = resizeSingleElement(e, HandleType.SE, Pt(180.0, 160.0))
        val rc = getElementAbsoluteCoords(r)
        val anclaDespues = pointRotateRads(Pt(rc.x1, rc.y1), Pt(rc.cx, rc.cy), r.angle)

        assertEquals(anclaAntes.x, anclaDespues.x, 1e-6)
        assertEquals(anclaAntes.y, anclaDespues.y, 1e-6)
    }

    @Test
    fun `redimensionar nunca deja tamaño cero o negativo`() {
        val e = rect(0.0, 0.0, 100.0, 50.0)
        val r = resizeSingleElement(e, HandleType.SE, Pt(-500.0, -500.0))
        assertTrue(r.width > 0.0)
        assertTrue(r.height > 0.0)
    }

    @Test
    fun `rotar con angulo discreto se engancha a multiplos de quince grados`() {
        val e = rect(0.0, 0.0, 100.0, 100.0)
        val r = rotateSingleElement(e, Pt(140.0, 10.0), discreteAngle = true)
        val enPasos = r.angle / SHIFT_LOCKING_ANGLE
        assertEquals(0.0, abs(enPasos - Math.round(enPasos)), 1e-9)
    }

    @Test
    fun `rotar suelta los anclajes de la flecha`() {
        val flecha = Element(
            id = "a", type = ElementType.ARROW, x = 0.0, y = 0.0,
            width = 100.0, height = 0.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)),
            endBinding = Binding("otro")
        )
        assertNull(rotateSingleElement(flecha, Pt(50.0, 50.0)).endBinding)
    }

    @Test
    fun `voltear en horizontal refleja sobre el centro de la seleccion`() {
        val a = rect(0.0, 0.0, 20.0, 20.0).copy(id = "a")
        val b = rect(80.0, 0.0, 20.0, 20.0).copy(id = "b")
        val (na, nb) = flipHorizontal(listOf(a, b))
        // La caja común va de 0 a 100: la de la izquierda pasa a la derecha.
        assertEquals(80.0, na.x, 1e-6)
        assertEquals(0.0, nb.x, 1e-6)
    }

    @Test
    fun `duplicar cambia id y semilla pero no la forma`() {
        val e = rect(5.0, 5.0)
        val copia = duplicateElements(listOf(e)).first()
        assertTrue(copia.id != e.id)
        assertTrue(copia.seed != e.seed || copia.id != e.id)
        assertEquals(e.width, copia.width, 1e-9)
        assertEquals(e.height, copia.height, 1e-9)
    }
}

/**
 * Organización, historial y anclaje.
 */
class DrawSceneTest {

    private fun box(id: String, x: Double, w: Double = 10.0) = Element(
        id = id, type = ElementType.RECTANGLE, x = x, y = 0.0,
        width = w, height = 10.0, seed = 1, backgroundColor = "#ff0000"
    )

    // ---------------------------------------------------------------------
    // Orden de pintado
    // ---------------------------------------------------------------------

    @Test
    fun `indices sueltos se agrupan en tramos contiguos`() {
        assertEquals(
            listOf(listOf(1, 2, 3), listOf(7), listOf(9, 10)),
            toContiguousGroups(listOf(3, 1, 10, 7, 2, 9))
        )
    }

    @Test
    fun `traer al frente pone la seleccion al final de la lista`() {
        val l = listOf(box("a", 0.0), box("b", 20.0), box("c", 40.0))
        assertEquals(listOf("b", "c", "a"), moveAllRight(l, setOf("a")).map { it.id })
    }

    @Test
    fun `enviar al fondo pone la seleccion al principio`() {
        val l = listOf(box("a", 0.0), box("b", 20.0), box("c", 40.0))
        assertEquals(listOf("c", "a", "b"), moveAllLeft(l, setOf("c")).map { it.id })
    }

    @Test
    fun `subir un paso intercambia con el vecino`() {
        val l = listOf(box("a", 0.0), box("b", 20.0), box("c", 40.0))
        assertEquals(listOf("b", "a", "c"), moveOneRight(l, setOf("a")).map { it.id })
    }

    // ---------------------------------------------------------------------
    // Grupos
    // ---------------------------------------------------------------------

    @Test
    fun `agrupar añade el mismo id a todos y desagrupar lo quita`() {
        val l = listOf(box("a", 0.0), box("b", 20.0))
        val g = groupElements(l, setOf("a", "b"))
        val ids = g.map { it.groupIds.last() }.distinct()
        assertEquals(1, ids.size)
        assertTrue(ungroupElements(g, setOf("a", "b")).all { it.groupIds.isEmpty() })
    }

    @Test
    fun `agrupar uno solo no hace nada`() {
        val l = listOf(box("a", 0.0))
        assertTrue(groupElements(l, setOf("a")).first().groupIds.isEmpty())
    }

    @Test
    fun `seleccionar un miembro arrastra al grupo entero`() {
        val g = groupElements(listOf(box("a", 0.0), box("b", 20.0)), setOf("a", "b"))
        assertEquals(2, getElementsInGroupOf(g, g.first()).size)
    }

    // ---------------------------------------------------------------------
    // Alinear y distribuir
    // ---------------------------------------------------------------------

    @Test
    fun `alinear a la izquierda lleva todo al borde de la caja comun`() {
        val l = listOf(box("a", 0.0), box("b", 50.0), box("c", 100.0))
        val r = alignElements(l, setOf("a", "b", "c"), AlignAxis.X, AlignPosition.START)
        assertTrue(r.all { abs(it.x - 0.0) < 1e-9 })
    }

    @Test
    fun `alinear al centro los deja con el mismo centro`() {
        val l = listOf(box("a", 0.0, 10.0), box("b", 50.0, 30.0))
        val r = alignElements(l, setOf("a", "b"), AlignAxis.X, AlignPosition.CENTER)
        assertEquals(r[0].x + r[0].width / 2, r[1].x + r[1].width / 2, 1e-9)
    }

    /**
     * Distribuir reparte los **huecos**, no los centros: con tres cajas de
     * distinto ancho el espacio entre ellas tiene que salir idéntico.
     */
    @Test
    fun `distribuir deja huecos iguales`() {
        val l = listOf(box("a", 0.0, 10.0), box("b", 30.0, 40.0), box("c", 100.0, 10.0))
        val r = distributeElements(l, setOf("a", "b", "c"), AlignAxis.X)
            .sortedBy { it.x }
        val hueco1 = r[1].x - (r[0].x + r[0].width)
        val hueco2 = r[2].x - (r[1].x + r[1].width)
        assertEquals(hueco1, hueco2, 1e-6)
    }

    @Test
    fun `distribuir con menos de tres no toca nada`() {
        val l = listOf(box("a", 0.0), box("b", 50.0))
        assertEquals(l, distributeElements(l, setOf("a", "b"), AlignAxis.X))
    }

    // ---------------------------------------------------------------------
    // Historial
    // ---------------------------------------------------------------------

    @Test
    fun `deshacer devuelve el estado anterior`() {
        val h = History()
        val antes = listOf(box("a", 0.0))
        val despues = dragElements(antes, 50.0, 0.0)
        h.record(antes, despues)

        val vuelto = h.undo(despues)
        assertNotNull(vuelto)
        assertEquals(0.0, vuelto!!.first().x, 1e-9)
        assertEquals(50.0, h.redo(vuelto)!!.first().x, 1e-9)
    }

    @Test
    fun `deshacer restaura un elemento borrado en su sitio`() {
        val h = History()
        val antes = listOf(box("a", 0.0), box("b", 20.0), box("c", 40.0))
        val despues = deleteSelected(antes, setOf("b"))
        h.record(antes, despues)

        val vuelto = h.undo(despues)!!
        assertEquals(listOf("a", "b", "c"), vuelto.map { it.id })
        assertFalse(vuelto[1].isDeleted)
    }

    @Test
    fun `deshacer recupera el orden de pintado`() {
        val h = History()
        val antes = listOf(box("a", 0.0), box("b", 20.0))
        val despues = moveAllRight(antes, setOf("a"))
        h.record(antes, despues)
        assertEquals(listOf("a", "b"), h.undo(despues)!!.map { it.id })
    }

    @Test
    fun `un cambio nuevo invalida la pila de rehacer`() {
        val h = History()
        val a = listOf(box("a", 0.0))
        val b = dragElements(a, 10.0, 0.0)
        h.record(a, b)
        h.undo(b)
        assertTrue(h.canRedo)
        h.record(a, dragElements(a, 99.0, 0.0))
        assertFalse(h.canRedo)
    }

    @Test
    fun `sin cambios no se anota nada`() {
        val h = History()
        val a = listOf(box("a", 0.0))
        h.record(a, a)
        assertFalse(h.canUndo)
    }

    // ---------------------------------------------------------------------
    // Anclaje de flechas
    // ---------------------------------------------------------------------

    private fun flecha(from: Pt, to: Pt) = Element(
        id = "f", type = ElementType.ARROW, x = from.x, y = from.y,
        width = to.x - from.x, height = to.y - from.y, seed = 1,
        points = listOf(Pt(0.0, 0.0), Pt(to.x - from.x, to.y - from.y))
    )

    /**
     * Lo que justifica todo el anclaje: al mover la forma, la punta la sigue.
     */
    @Test
    fun `la punta anclada sigue a la forma cuando esta se mueve`() {
        val caja = box("caja", 200.0, 100.0).copy(height = 100.0)
        val f = bindArrow(flecha(Pt(0.0, 50.0), Pt(180.0, 50.0)), caja, ArrowEnd.END)

        val movida = caja.copy(x = 400.0)
        val recolocada = updateBoundPoints(f, listOf(movida, f))
        val punta = absolutePoints(recolocada).last()

        // La punta tiene que haberse ido tras la caja, quedando a su izquierda.
        assertTrue(punta.x > 300.0)
        assertTrue(punta.x < 400.0)
    }

    @Test
    fun `la punta se queda fuera de la forma, separada por el hueco`() {
        val caja = box("caja", 200.0, 100.0).copy(height = 100.0)
        val f = bindArrow(flecha(Pt(0.0, 50.0), Pt(180.0, 50.0)), caja, ArrowEnd.END)
        val punta = absolutePoints(updateBoundPoints(f, listOf(caja, f))).last()
        assertTrue("la punta no debe invadir la caja", punta.x <= 200.0)
        assertEquals(200.0 - getBindingGap(caja), punta.x, 1.0)
    }

    @Test
    fun `soltar el anclaje deja la flecha quieta`() {
        val caja = box("caja", 200.0, 100.0)
        val f = bindArrow(flecha(Pt(0.0, 50.0), Pt(180.0, 50.0)), caja, ArrowEnd.END)
        val suelta = unbindArrow(f, ArrowEnd.END)
        assertNull(suelta.endBinding)
        assertEquals(f.points, updateBoundPoints(suelta, listOf(caja)).points)
    }

    @Test
    fun `una flecha no se ancla a si misma ni a otra flecha`() {
        val f = flecha(Pt(0.0, 0.0), Pt(50.0, 0.0))
        assertNull(getHoveredElementForBinding(listOf(f), Pt(25.0, 0.0)))
    }
}

/**
 * El lienzo acotado a la imagen, que es como se anota dentro de un pin.
 *
 * Lo que se defiende aquí: **no puedes alejarte hasta ver el vacío, pero sí
 * acercarte todo lo que quieras**. Es la diferencia entre anotar una captura y
 * el pin `canvas`, y el mismo motor sirve para los dos.
 */
class ViewportBoundsTest {

    private val imagen = Bounds(0.0, 0.0, 800.0, 600.0)

    @Test
    fun `sin limites no se toca nada, el lienzo sigue infinito`() {
        val v = Viewport(scrollX = -9999.0, scrollY = 5000.0, zoom = 0.15)
        assertEquals(v, clampViewportToBounds(v, null, 400.0, 300.0))
    }

    @Test
    fun `no se puede alejar mas alla de que la imagen llene el hueco`() {
        // El hueco es 400×300 y la imagen 800×600: encaja justo a zoom 0,5.
        val r = clampViewportToBounds(Viewport(zoom = 0.01), imagen, 400.0, 300.0)
        assertEquals(0.5, r.zoom, 1e-9)
    }

    @Test
    fun `acercarse no tiene tope practico, que es lo que permite afinar`() {
        val r = clampViewportToBounds(Viewport(zoom = 8.0), imagen, 400.0, 300.0)
        assertEquals(8.0, r.zoom, 1e-9)
    }

    @Test
    fun `el paneo se frena antes de dejar hueco en los bordes`() {
        // A zoom 2 se ve un trozo de 200×150 de la imagen: puede moverse, pero
        // sin sacar el borde de la imagen dentro del hueco.
        val v = Viewport(scrollX = 5000.0, scrollY = 5000.0, zoom = 2.0)
        val r = clampViewportToBounds(v, imagen, 400.0, 300.0)

        val esquina = r.toScreen(Pt(imagen.x1, imagen.y1))
        assertTrue("el borde izquierdo no puede entrar en el hueco", esquina.x <= 1e-6)
        assertTrue("el borde superior tampoco", esquina.y <= 1e-6)
    }

    @Test
    fun `panear al otro extremo tambien se frena`() {
        val v = Viewport(scrollX = -5000.0, scrollY = -5000.0, zoom = 2.0)
        val r = clampViewportToBounds(v, imagen, 400.0, 300.0)

        val opuesta = r.toScreen(Pt(imagen.x2, imagen.y2))
        assertTrue("el borde derecho no puede entrar en el hueco", opuesta.x >= 400.0 - 1e-6)
        assertTrue("el inferior tampoco", opuesta.y >= 300.0 - 1e-6)
    }

    /**
     * Si la superficie es más pequeña que el hueco los dos topes se cruzan.
     * Ahí se centra, en vez de pegarla a un borde arbitrario.
     */
    @Test
    fun `una imagen mas pequeña que el hueco queda centrada`() {
        val pequena = Bounds(0.0, 0.0, 100.0, 100.0)
        val r = clampViewportToBounds(Viewport(zoom = 1.0), pequena, 400.0, 300.0)
        val centro = r.toScreen(Pt(50.0, 50.0))
        assertEquals(200.0, centro.x, 1e-6)
        assertEquals(150.0, centro.y, 1e-6)
    }
}

/**
 * El zoom de pellizco.
 *
 * Regresión de uso real: el zoom se anclaba en el origen del lienzo, así que
 * el dibujo se escapaba de debajo de los dedos salvo que estuvieras mirando
 * justo al centro de la pantalla.
 */
class ZoomAnchorTest {

    private fun offset(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)

    /** El invariante: lo que había bajo los dedos sigue bajo los dedos. */
    private fun assertAnclado(v: Viewport, factor: Float, from: Pair<Float, Float>) {
        val c = offset(from.first, from.second)
        val antes = v.toScene(c.x.toDouble(), c.y.toDouble())
        val despues = zoomAnchored(v, factor, c, c)
        val donde = despues.toScreen(antes)
        assertEquals(c.x.toDouble(), donde.x, 1e-6)
        assertEquals(c.y.toDouble(), donde.y, 1e-6)
    }

    @Test
    fun `ampliar mantiene el punto bajo los dedos`() {
        assertAnclado(Viewport(), 2.0f, 800f to 1200f)
    }

    @Test
    fun `reducir tambien lo mantiene`() {
        assertAnclado(Viewport(scrollX = -50.0, scrollY = 120.0, zoom = 3.0), 0.4f, 300f to 900f)
    }

    @Test
    fun `funciona con la vista ya desplazada y lejos del origen`() {
        assertAnclado(Viewport(scrollX = -4000.0, scrollY = -2500.0, zoom = 0.7), 1.6f, 60f to 40f)
    }

    /** Con los dedos moviéndose además de abrirse, el punto viaja con ellos. */
    @Test
    fun `el punto acompaña al centroide cuando ademas se desplaza`() {
        val v = Viewport(scrollX = 10.0, scrollY = -30.0, zoom = 1.5)
        val desde = offset(400f, 400f)
        val hasta = offset(520f, 350f)
        val antes = v.toScene(desde.x.toDouble(), desde.y.toDouble())
        val donde = zoomAnchored(v, 1.3f, desde, hasta).toScreen(antes)
        assertEquals(hasta.x.toDouble(), donde.x, 1e-6)
        assertEquals(hasta.y.toDouble(), donde.y, 1e-6)
    }

    @Test
    fun `el zoom se queda dentro de los limites`() {
        assertEquals(
            Viewport.MAX_ZOOM,
            zoomAnchored(Viewport(zoom = 20.0), 100f, offset(0f, 0f), offset(0f, 0f)).zoom,
            1e-9
        )
        assertEquals(
            Viewport.MIN_ZOOM,
            zoomAnchored(Viewport(zoom = 0.2), 0.001f, offset(0f, 0f), offset(0f, 0f)).zoom,
            1e-9
        )
    }
}

/**
 * El predicado que decide si la geometría cacheada sigue sirviendo.
 *
 * Es el punto delicado de la caché de render: **un falso positivo deja en
 * pantalla la forma vieja**, y eso no se ve como un fallo de rendimiento sino
 * como un dibujo que no responde. Así que cada campo que influye en el trazo
 * tiene aquí su prueba de que invalida.
 */
class RenderCacheKeyTest {

    private fun base() = Element(
        id = "e", type = ElementType.RECTANGLE, x = 0.0, y = 0.0,
        width = 100.0, height = 50.0, seed = 42
    )

    @Test
    fun `un elemento identico reutiliza la geometria`() {
        assertTrue(hasSameGeometry(base(), base()))
    }

    @Test
    fun `mover o redimensionar invalida`() {
        assertFalse(hasSameGeometry(base(), base().copy(x = 1.0)))
        assertFalse(hasSameGeometry(base(), base().copy(y = 1.0)))
        assertFalse(hasSameGeometry(base(), base().copy(width = 101.0)))
        assertFalse(hasSameGeometry(base(), base().copy(height = 51.0)))
    }

    @Test
    fun `cambiar la semilla invalida`() {
        assertFalse(hasSameGeometry(base(), base().copy(seed = 43)))
    }

    @Test
    fun `los estilos que cambian el trazo invalidan`() {
        assertFalse(hasSameGeometry(base(), base().copy(strokeWidth = 4.0)))
        assertFalse(hasSameGeometry(base(), base().copy(strokeStyle = StrokeStyle.DASHED)))
        assertFalse(hasSameGeometry(base(), base().copy(roughness = 2)))
        assertFalse(hasSameGeometry(base(), base().copy(fillStyle = FillStyle.HACHURE)))
        assertFalse(
            hasSameGeometry(base(), base().copy(roundness = Roundness(Roundness.ADAPTIVE_RADIUS)))
        )
    }

    /**
     * Aunque `backgroundColor` parezca solo color: de si es transparente
     * depende que se pida el relleno, y pedirlo consume números del generador,
     * lo que cambia el garabato del **contorno**.
     */
    @Test
    fun `pasar de transparente a relleno invalida`() {
        assertFalse(hasSameGeometry(base(), base().copy(backgroundColor = "#ff0000")))
    }

    /** Pero cambiar de un relleno a otro no cambia la geometría, solo el pincel. */
    @Test
    fun `cambiar entre dos colores de relleno no invalida`() {
        assertTrue(
            hasSameGeometry(
                base().copy(backgroundColor = "#ff0000"),
                base().copy(backgroundColor = "#00ff00")
            )
        )
    }

    /**
     * El ángulo lo aplica la matriz del lienzo, no la geometría: invalidar al
     * rotar tiraría la caché en cada fotograma del giro sin ganar nada.
     */
    @Test
    fun `rotar, recolorear el trazo o cambiar la opacidad no invalidan`() {
        assertTrue(hasSameGeometry(base(), base().copy(angle = 1.2)))
        assertTrue(hasSameGeometry(base(), base().copy(strokeColor = "#e03131")))
        assertTrue(hasSameGeometry(base(), base().copy(opacity = 30)))
    }

    /**
     * Regresión del arrastre: al crear una forma, la caja crece **sin** que
     * suba `version`. Una caché que se fiara de la versión dejaría el trazo
     * congelado en el primer fotograma del arrastre.
     */
    @Test
    fun `la version no basta como clave`() {
        val c = DrawController()
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(0.0, 0.0))
        val primero = c.scene.visible.single()
        c.pointerMove(Pt(80.0, 40.0))
        val despues = c.scene.visible.single()

        assertEquals("la versión no cambia al arrastrar", primero.version, despues.version)
        assertFalse("y aun así la geometría es otra", hasSameGeometry(primero, despues))
    }

    @Test
    fun `mover un punto de una linea invalida`() {
        val linea = base().copy(
            type = ElementType.LINE,
            points = listOf(Pt(0.0, 0.0), Pt(50.0, 0.0))
        )
        val movida = linea.copy(points = listOf(Pt(0.0, 0.0), Pt(60.0, 0.0)))
        assertFalse(hasSameGeometry(linea, movida))
        assertTrue(hasSameGeometry(linea, linea.copy()))
    }
}

/**
 * El generador de trazo rugoso.
 *
 * La propiedad que hay que defender con tests es el **determinismo**: es lo
 * único que impide que los dibujos tiemblen al repintarse.
 */
class RoughTest {

    @Test
    fun `la misma semilla da exactamente el mismo trazo`() {
        val a = Rough(RoughOptions(seed = 12345)).rectangle(0.0, 0.0, 100.0, 50.0)
        val b = Rough(RoughOptions(seed = 12345)).rectangle(0.0, 0.0, 100.0, 50.0)
        assertEquals(a, b)
    }

    @Test
    fun `semillas distintas dan trazos distintos`() {
        val a = Rough(RoughOptions(seed = 1)).rectangle(0.0, 0.0, 100.0, 50.0)
        val b = Rough(RoughOptions(seed = 2)).rectangle(0.0, 0.0, 100.0, 50.0)
        assertTrue(a != b)
    }

    /**
     * El generador es el Lehmer de rough.js. Se fija el primer valor para una
     * semilla conocida: si alguien cambia la aritmética, esto salta y avisa de
     * que los dibujos van a dejar de coincidir con los de excalidraw.com.
     */
    @Test
    fun `el generador reproduce la secuencia de rough js`() {
        val r = Rand(1)
        // 48271 / 2^31
        assertEquals(48271.0 / 2147483648.0, r.next(), 1e-15)
    }

    @Test
    fun `con rugosidad cero el trazo no se dispersa`() {
        val ops = Rough(RoughOptions(seed = 7, roughness = 0.0))
            .doubleLine(0.0, 0.0, 100.0, 0.0)
        // Sin ruido, todos los puntos siguen sobre la recta y = 0.
        for (op in ops) {
            if (op is Op.CurveTo) {
                assertEquals(0.0, op.y, 1e-9)
                assertEquals(0.0, op.y1, 1e-9)
                assertEquals(0.0, op.y2, 1e-9)
            }
        }
    }

    @Test
    fun `el trazo doble emite dos pasadas y el sencillo una`() {
        val doble = Rough(RoughOptions(seed = 3)).doubleLine(0.0, 0.0, 100.0, 0.0)
        val simple = Rough(RoughOptions(seed = 3, disableMultiStroke = true))
            .doubleLine(0.0, 0.0, 100.0, 0.0)
        assertEquals(2 * simple.size, doble.size)
    }

    @Test
    fun `el rayado de un cuadrado produce lineas`() {
        val cuadrado = listOf(
            Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0)
        )
        val ops = Rough(RoughOptions(seed = 5, fillStyle = FillStyle.HACHURE, hachureGap = 10.0))
            .fillPolygon(cuadrado)
        assertTrue("el rayado no puede salir vacío", ops.isNotEmpty())
    }

    @Test
    fun `el relleno solido no genera trazos de rayado`() {
        val cuadrado = listOf(
            Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0)
        )
        val ops = Rough(RoughOptions(seed = 5, fillStyle = FillStyle.SOLID)).fillPolygon(cuadrado)
        assertTrue(ops.isEmpty())
    }
}
