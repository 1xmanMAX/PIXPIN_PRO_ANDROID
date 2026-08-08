package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El enganche a los puntos notables.
 *
 * Lo importante de estas pruebas no es que enganche —eso es fácil— sino que
 * **no enganche donde no debe**: un imán demasiado goloso es peor que no
 * tenerlo, porque te mueve el trazo sin que se lo hayas pedido y no entiendes
 * por qué.
 */
class SnappingTest {

    private fun caja(id: String = "a", x: Double = 100.0, y: Double = 100.0) = Element(
        id = id, type = ElementType.RECTANGLE, x = x, y = y,
        width = 200.0, height = 100.0, seed = 1
    )

    // ---- Qué puntos ofrece una figura ----

    @Test
    fun `una caja ofrece cuatro esquinas, cuatro medios y su centro`() {
        val a = anclajesDe(caja(), AjustesEnganche())
        assertEquals(4, a.count { it.tipo == TipoAnclaje.ESQUINA })
        assertEquals(4, a.count { it.tipo == TipoAnclaje.MEDIO })
        assertEquals(1, a.count { it.tipo == TipoAnclaje.CENTRO })
    }

    @Test
    fun `las esquinas son las de verdad`() {
        val puntos = anclajesDe(caja(), AjustesEnganche())
            .filter { it.tipo == TipoAnclaje.ESQUINA }.map { it.punto }
        assertTrue(puntos.any { it.x == 100.0 && it.y == 100.0 })
        assertTrue(puntos.any { it.x == 300.0 && it.y == 200.0 })
    }

    @Test
    fun `una flecha ofrece sus extremos y su medio, no su caja`() {
        val f = Element(
            id = "f", type = ElementType.ARROW, x = 0.0, y = 0.0,
            width = 100.0, height = 100.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(50.0, 50.0), Pt(100.0, 100.0))
        )
        val a = anclajesDe(f, AjustesEnganche())
        assertEquals(2, a.count { it.tipo == TipoAnclaje.EXTREMO })
        assertTrue(a.none { it.tipo == TipoAnclaje.CENTRO })
    }

    // ---- Cuándo engancha y cuándo no ----

    @Test
    fun `cerca de una esquina engancha a la esquina`() {
        val hit = buscarAnclaje(listOf(caja()), Pt(104.0, 103.0), zoom = 1.0)
        assertEquals(TipoAnclaje.ESQUINA, hit?.tipo)
        assertEquals(100.0, hit!!.punto.x, 0.001)
        assertEquals(100.0, hit.punto.y, 0.001)
    }

    /** Lejos no pasa nada: el trazo va donde va el dedo. */
    @Test
    fun `lejos de todo no engancha`() {
        assertNull(buscarAnclaje(listOf(caja()), Pt(500.0, 500.0), zoom = 1.0))
    }

    @Test
    fun `el punto medio de un lado tambien engancha`() {
        val hit = buscarAnclaje(listOf(caja()), Pt(202.0, 98.0), zoom = 1.0)
        assertEquals(TipoAnclaje.MEDIO, hit?.tipo)
        assertEquals(200.0, hit!!.punto.x, 0.001)
    }

    /**
     * El radio se mide **en pantalla**, no en escena: el dedo tapa lo mismo
     * mires al zoom que mires. Muy acercado, un punto que está a 10 px de
     * escena queda lejísimos en pantalla y no debe tirar.
     */
    @Test
    fun `el radio se mide en pantalla`() {
        val p = Pt(112.0, 100.0)
        assertTrue("a 1x tendría que enganchar", buscarAnclaje(listOf(caja()), p, 1.0) != null)
        assertNull("a 8x el mismo punto queda lejos", buscarAnclaje(listOf(caja()), p, 8.0))
    }

    // ---- El desempate ----

    /** Gana el más cercano; en empate, la esquina antes que el centro. */
    @Test
    fun `gana el mas cercano`() {
        val a = caja("a", 0.0, 0.0)
        val b = caja("b", 400.0, 400.0)
        assertEquals("b", buscarAnclaje(listOf(a, b), Pt(402.0, 402.0), 1.0)?.elementId)
    }

    // ---- Lo configurable ----

    @Test
    fun `apagado no engancha nada`() {
        assertNull(
            buscarAnclaje(listOf(caja()), Pt(101.0, 101.0), 1.0, AjustesEnganche.NINGUNO)
        )
    }

    @Test
    fun `cada clase de punto se apaga por separado`() {
        val soloEsquinas = AjustesEnganche(medios = false, centros = false)
        val a = anclajesDe(caja(), soloEsquinas)
        assertEquals(4, a.size)
        assertTrue(a.all { it.tipo == TipoAnclaje.ESQUINA })

        // Con las esquinas apagadas, el punto medio sigue estando.
        val hit = buscarAnclaje(
            listOf(caja()), Pt(202.0, 98.0), 1.0, AjustesEnganche(esquinas = false)
        )
        assertEquals(TipoAnclaje.MEDIO, hit?.tipo)
    }

    /** La figura que se está dibujando no engancha consigo misma. */
    @Test
    fun `el elemento excluido no cuenta`() {
        assertNull(buscarAnclaje(listOf(caja("a")), Pt(101.0, 101.0), 1.0, excluir = "a"))
    }

    /** La hoja no engancha: es el papel, no un dibujo con el que alinearse. */
    @Test
    fun `la hoja no engancha`() {
        val marco = Element(
            id = "hoja", type = ElementType.FRAME, x = 100.0, y = 100.0,
            width = 200.0, height = 100.0, seed = 1
        )
        assertNull(buscarAnclaje(listOf(marco), Pt(101.0, 101.0), 1.0))
    }

    // ---- Dibujando de verdad ----

    @Test
    fun `un rectangulo nuevo se pega a la esquina del anterior`() {
        val c = DrawController()
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(100.0, 100.0))
        c.pointerUp(Pt(100.0, 100.0))
        c.deselect()

        // Se empieza el segundo a 3 px de la esquina del primero.
        c.pointerDown(Pt(103.0, 103.0))
        c.pointerMove(Pt(200.0, 200.0))
        c.pointerUp(Pt(200.0, 200.0))

        val segundo = c.scene.visible.last()
        assertEquals("no se pegó a la esquina", 100.0, segundo.x, 0.001)
        assertEquals(100.0, segundo.y, 0.001)
    }

    // ---- Desde el centro ----

    /**
     * El punto tocado es el **centro**, y el dedo se queda **sobre el borde**.
     *
     * Los semiejes no son el arrastre a secas sino el arrastre por √2: con
     * `|dx|` y `|dy|` el dedo se quedaba a media distancia del trazo —en la
     * diagonal, un 41% dentro— porque esa es la esquina de la caja, no un punto
     * de la elipse. El detalle, en `updateCreating`.
     */
    @Test
    fun `el circulo crece desde el centro y llega hasta el dedo`() {
        val c = DrawController()
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(200.0, 200.0))
        c.pointerMove(Pt(250.0, 230.0))
        c.pointerUp(Pt(250.0, 230.0))

        val e = c.scene.visible.last()
        val raiz2 = kotlin.math.sqrt(2.0)
        assertEquals(200.0 - 50 * raiz2, e.x, 0.001)
        assertEquals(200.0 - 30 * raiz2, e.y, 0.001)
        assertEquals(100.0 * raiz2, e.width, 0.001)
        assertEquals(60.0 * raiz2, e.height, 0.001)

        // Y el dedo cae justo en la circunferencia, que es de lo que iba todo.
        val coords = getElementAbsoluteCoords(e)
        val dx = (250.0 - coords.cx) / ((coords.x2 - coords.x1) / 2)
        val dy = (230.0 - coords.cy) / ((coords.y2 - coords.y1) / 2)
        assertEquals(1.0, dx * dx + dy * dy, 0.001)
    }

    @Test
    fun `se puede volver al modo esquina a esquina`() {
        val c = DrawController()
        c.ellipseFromCenter = false
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(200.0, 200.0))
        c.pointerMove(Pt(250.0, 230.0))
        c.pointerUp(Pt(250.0, 230.0))

        val e = c.scene.visible.last()
        assertEquals(200.0, e.x, 0.001)
        assertEquals(50.0, e.width, 0.001)
    }

    /** La hoja siempre de esquina a esquina: se coloca por sus bordes. */
    @Test
    fun `la hoja no crece desde el centro`() {
        val c = DrawController()
        c.selectTool(Tool.FRAME)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(300.0, 200.0))
        c.pointerUp(Pt(300.0, 200.0))

        val m = c.scene.marco!!
        assertEquals(0.0, m.x, 0.001)
        assertEquals(300.0, m.width, 0.001)
    }

    // ---- El ancla del arrastre ----

    /**
     * **Arrastrar hacia arriba y a la izquierda funciona igual que hacia abajo
     * y a la derecha.**
     *
     * No lo hacía: el ancla se leía del elemento, y el elemento se recoloca en
     * cada fotograma, así que en cuanto ibas hacia la izquierda su `x` pasaba a
     * ser la del dedo y el origen se perdía. La forma salía encogida o corrida.
     */
    @Test
    fun `una forma crece igual en las cuatro direcciones`() {
        for ((dx, dy) in listOf(100.0 to 100.0, -100.0 to 100.0, 100.0 to -100.0, -100.0 to -100.0)) {
            val c = DrawController()
            c.selectTool(Tool.RECTANGLE)
            val origen = Pt(500.0, 500.0)
            c.pointerDown(origen)
            // Varios pasos: con uno solo el fallo del ancla no se manifestaba.
            for (i in 1..4) {
                c.pointerMove(Pt(origen.x + dx * i / 4, origen.y + dy * i / 4))
            }
            c.pointerUp(Pt(origen.x + dx, origen.y + dy))

            val e = c.scene.visible.last()
            assertEquals("ancho hacia ($dx, $dy)", 100.0, e.width, 0.001)
            assertEquals("alto hacia ($dx, $dy)", 100.0, e.height, 0.001)
            assertEquals("x hacia ($dx, $dy)", minOf(origen.x, origen.x + dx), e.x, 0.001)
            assertEquals("y hacia ($dx, $dy)", minOf(origen.y, origen.y + dy), e.y, 0.001)
        }
    }

    /** Y el círculo desde el centro, en las cuatro direcciones también. */
    @Test
    fun `el circulo desde el centro va en las cuatro direcciones`() {
        for ((dx, dy) in listOf(50.0 to 30.0, -50.0 to 30.0, 50.0 to -30.0, -50.0 to -30.0)) {
            val c = DrawController()
            c.selectTool(Tool.ELLIPSE)
            c.pointerDown(Pt(200.0, 200.0))
            c.pointerMove(Pt(200.0 + dx / 2, 200.0 + dy / 2))
            c.pointerMove(Pt(200.0 + dx, 200.0 + dy))
            c.pointerUp(Pt(200.0 + dx, 200.0 + dy))

            val e = c.scene.visible.last()
            assertEquals("hacia ($dx, $dy)", 200.0, e.x + e.width / 2, 0.001)
            assertEquals("hacia ($dx, $dy)", 200.0, e.y + e.height / 2, 0.001)
        }
    }

    // ---- El rombo, desde un vértice ----

    /**
     * El punto que se toca es **la punta de arriba** del rombo, no la esquina de
     * una caja invisible donde no hay nada dibujado.
     */
    @Test
    fun `el rombo nace de su punta de arriba`() {
        val c = DrawController()
        c.selectTool(Tool.DIAMOND)
        c.pointerDown(Pt(300.0, 100.0))
        c.pointerMove(Pt(360.0, 300.0))
        c.pointerUp(Pt(360.0, 300.0))

        val e = c.scene.visible.last()
        val cc = getElementAbsoluteCoords(e)
        // La punta de arriba de un rombo es (centro en x, borde de arriba).
        assertEquals("la punta no está donde se tocó", 300.0, cc.cx, 0.001)
        assertEquals(100.0, cc.y1, 0.001)
        // Y el dedo marca la anchura y hasta dónde baja.
        assertEquals(120.0, e.width, 0.001)
        assertEquals(200.0, e.height, 0.001)
    }

    @Test
    fun `el rombo tambien se puede abrir hacia arriba`() {
        val c = DrawController()
        c.selectTool(Tool.DIAMOND)
        c.pointerDown(Pt(300.0, 300.0))
        c.pointerMove(Pt(250.0, 100.0))
        c.pointerUp(Pt(250.0, 100.0))

        val e = c.scene.visible.last()
        assertEquals(300.0, getElementAbsoluteCoords(e).cx, 0.001)
        assertEquals(200.0, e.height, 0.001)
    }

    // ---- Intersecciones ----

    /**
     * Donde dos líneas se cruzan hay un punto que **no es vértice de nada**, y
     * a pulso es el más difícil de acertar. Es el que más se agradece.
     */
    @Test
    fun `engancha donde se cruzan dos lineas`() {
        val horizontal = Element(
            id = "h", type = ElementType.LINE, x = 0.0, y = 100.0,
            width = 200.0, height = 0.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(200.0, 0.0))
        )
        val vertical = Element(
            id = "v", type = ElementType.LINE, x = 120.0, y = 0.0,
            width = 0.0, height = 200.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(0.0, 200.0))
        )
        val hit = buscarAnclaje(listOf(horizontal, vertical), Pt(124.0, 103.0), 1.0)
        assertEquals(TipoAnclaje.INTERSECCION, hit?.tipo)
        assertEquals(120.0, hit!!.punto.x, 0.001)
        assertEquals(100.0, hit.punto.y, 0.001)
    }

    /**
     * Solo cuentan los cruces **dentro de los dos tramos**. Prolongar las
     * rectas daría puntos en mitad de la nada y el imán tiraría del dedo hacia
     * sitios que no se ven.
     */
    @Test
    fun `dos lineas que no llegan a cruzarse no dan interseccion`() {
        assertNull(interseccion(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(100.0, -50.0), Pt(100.0, 50.0)))
    }

    @Test
    fun `dos lineas paralelas no dan interseccion`() {
        assertNull(interseccion(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(0.0, 50.0), Pt(100.0, 50.0)))
    }

    /** Los lados de dos rectángulos que se solapan también se cruzan. */
    @Test
    fun `dos cajas que se solapan dan intersecciones en sus lados`() {
        // a ocupa (0,0)-(200,100) y b (150,50)-(350,150): sus lados se cruzan
        // en (150,100) —abajo de a con la izquierda de b— y en (200,50).
        val a = caja("a", 0.0, 0.0)
        val b = caja("b", 150.0, 50.0)

        val hit = buscarAnclaje(listOf(a, b), Pt(152.0, 102.0), 1.0)
        assertEquals(TipoAnclaje.INTERSECCION, hit?.tipo)
        assertEquals(150.0, hit!!.punto.x, 0.001)
        assertEquals(100.0, hit.punto.y, 0.001)

        val otro = buscarAnclaje(listOf(a, b), Pt(198.0, 52.0), 1.0)
        assertEquals(TipoAnclaje.INTERSECCION, otro?.tipo)
        assertEquals(200.0, otro!!.punto.x, 0.001)
        assertEquals(50.0, otro.punto.y, 0.001)
    }

    @Test
    fun `las intersecciones se pueden apagar`() {
        val horizontal = Element(
            id = "h", type = ElementType.LINE, x = 0.0, y = 100.0,
            width = 200.0, height = 0.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(200.0, 0.0))
        )
        val vertical = Element(
            id = "v", type = ElementType.LINE, x = 120.0, y = 0.0,
            width = 0.0, height = 200.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(0.0, 200.0))
        )
        val sinCruces = AjustesEnganche(intersecciones = false, esquinas = false, medios = false)
        assertNull(buscarAnclaje(listOf(horizontal, vertical), Pt(121.0, 101.0), 1.0, sinCruces))
    }

    /** El centro de una elipse es el centro de la circunferencia. */
    @Test
    fun `el centro de una circunferencia engancha`() {
        val circulo = Element(
            id = "c", type = ElementType.ELLIPSE, x = 100.0, y = 100.0,
            width = 200.0, height = 200.0, seed = 1
        )
        val hit = buscarAnclaje(listOf(circulo), Pt(203.0, 198.0), 1.0)
        assertEquals(TipoAnclaje.CENTRO, hit?.tipo)
        assertEquals(200.0, hit!!.punto.x, 0.001)
        assertEquals(200.0, hit.punto.y, 0.001)
    }
}
