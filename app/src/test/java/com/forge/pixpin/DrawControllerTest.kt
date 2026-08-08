package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La interacción completa: qué pasa entre que baja el dedo y que se levanta.
 *
 * [DrawController] no toca Android, así que todo esto se comprueba en la JVM.
 * Es donde se verifica de verdad que el port sirve para algo: que dibujar,
 * seleccionar, mover y deshacer hacen lo que uno espera.
 */
class DrawControllerTest {

    private fun controller() = DrawController()

    /** Dibuja un rectángulo arrastrando de (x1,y1) a (x2,y2). */
    private fun DrawController.dibujaRect(
        x1: Double, y1: Double, x2: Double, y2: Double
    ): Element {
        selectTool(Tool.RECTANGLE)
        pointerDown(Pt(x1, y1))
        pointerMove(Pt(x2, y2))
        pointerUp(Pt(x2, y2))
        return scene.visible.last()
    }

    // ---------------------------------------------------------------------
    // Crear
    // ---------------------------------------------------------------------

    @Test
    fun `arrastrar con la herramienta rectangulo crea uno`() {
        val c = controller()
        val e = c.dibujaRect(10.0, 10.0, 110.0, 60.0)
        assertEquals(ElementType.RECTANGLE, e.type)
        assertEquals(10.0, e.x, 1e-9)
        assertEquals(100.0, e.width, 1e-9)
        assertEquals(50.0, e.height, 1e-9)
    }

    /**
     * Arrastrar hacia arriba y a la izquierda tiene que dar la misma caja que
     * hacia abajo y a la derecha. Es el fallo clásico de restar en vez de usar
     * min/max, y deja formas con ancho negativo que luego no se pueden picar.
     */
    @Test
    fun `arrastrar en cualquier direccion da una caja valida`() {
        val e = controller().dibujaRect(110.0, 60.0, 10.0, 10.0)
        assertEquals(10.0, e.x, 1e-9)
        assertEquals(10.0, e.y, 1e-9)
        assertEquals(100.0, e.width, 1e-9)
        assertEquals(50.0, e.height, 1e-9)
    }

    @Test
    fun `un toque seco no deja una forma invisible`() {
        val c = controller()
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(50.0, 50.0))
        c.pointerUp(Pt(50.0, 50.0))
        assertTrue(c.scene.visible.isEmpty())
    }

    /**
     * Regresión de uso real: la herramienta se quedaba desactivada tras cada
     * forma y había que volver a la barra entre cuadrado y cuadrado. Dibujar
     * con el dedo tiene que ser continuo.
     */
    @Test
    fun `la herramienta sigue activa despues de dibujar`() {
        val c = controller()
        c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        assertEquals(Tool.RECTANGLE, c.tool)
        assertTrue("no debe dejar tiradores encima", c.selectedIds.isEmpty())

        // Y se puede encadenar otra sin tocar nada.
        c.pointerDown(Pt(200.0, 0.0))
        c.pointerMove(Pt(300.0, 100.0))
        c.pointerUp(Pt(300.0, 100.0))
        assertEquals(2, c.scene.visible.size)
    }

    @Test
    fun `el lapiz acumula puntos y presiones`() {
        val c = controller()
        c.selectTool(Tool.FREEDRAW)
        c.pointerDown(Pt(0.0, 0.0), pressure = 0.4)
        c.pointerMove(Pt(10.0, 5.0), pressure = 0.8)
        c.pointerMove(Pt(20.0, 0.0), pressure = 1.0)
        c.pointerUp(Pt(20.0, 0.0))
        val e = c.scene.visible.single()
        assertEquals(3, e.points!!.size)
        assertEquals(3, e.pressures!!.size)
        assertEquals(0.4, e.pressures!!.first(), 1e-9)
    }

    @Test
    fun `con proporcion bloqueada el rectangulo sale cuadrado`() {
        val c = controller()
        c.keepAspectRatio = true
        val e = c.dibujaRect(0.0, 0.0, 100.0, 30.0)
        assertEquals(e.width, e.height, 1e-9)
    }

    /**
     * **Forma perfecta con el segundo dedo.**
     *
     * En un teclado esto es la tecla Shift; en una pantalla es la otra mano
     * apoyada. Lo que activa el modo vive en el lector de toques, pero lo que
     * decide que un círculo salga redondo es esto, y es lo que se puede
     * comprobar sin dispositivo.
     */
    private fun DrawController.dibujaForma(
        herramienta: Tool, x1: Double, y1: Double, x2: Double, y2: Double
    ): Element {
        selectTool(herramienta)
        pointerDown(Pt(x1, y1))
        pointerMove(Pt(x2, y2))
        pointerUp(Pt(x2, y2))
        return scene.visible.last()
    }

    @Test
    fun `con el segundo dedo la elipse sale redonda y el rombo simétrico`() {
        for (herramienta in listOf(Tool.ELLIPSE, Tool.DIAMOND, Tool.RECTANGLE)) {
            val c = controller()
            c.keepAspectRatio = true
            val e = c.dibujaForma(herramienta, 0.0, 0.0, 200.0, 40.0)
            assertEquals("$herramienta no salió perfecta", e.width, e.height, 1e-9)
        }
    }

    /** Y sin él, la forma sigue lo que hace el dedo. */
    @Test
    fun `sin el segundo dedo la forma sigue al dedo`() {
        val c = controller()
        val e = c.dibujaForma(Tool.ELLIPSE, 0.0, 0.0, 200.0, 40.0)
        assertTrue("no debería haber salido redonda", e.width != e.height)
    }

    /**
     * El modificador se puede apoyar **a mitad del trazo**: el gesto natural es
     * empezar a dibujar y, al ver que sale torcido, posar el otro dedo.
     */
    @Test
    fun `apoyar el segundo dedo a mitad del trazo corrige la forma`() {
        val c = controller()
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(200.0, 40.0))
        // Aquí baja el segundo dedo.
        c.keepAspectRatio = true
        c.pointerMove(Pt(200.0, 40.0))
        c.pointerUp(Pt(200.0, 40.0))
        val e = c.scene.visible.last()
        assertEquals(e.width, e.height, 1e-9)
    }

    /** Y con la línea, el segundo dedo la endereza al eje más cercano. */
    @Test
    fun `con el segundo dedo la línea sale recta`() {
        val c = controller()
        c.keepAspectRatio = true
        c.selectTool(Tool.LINE)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(200.0, 12.0))
        c.pointerUp(Pt(200.0, 12.0))
        val puntos = absolutePoints(c.scene.visible.last())
        assertEquals("tendría que haberse pegado a la horizontal", 0.0, puntos.last().y, 1e-9)
    }

    @Test
    fun `cada forma nueva recibe su propia semilla`() {
        val c = controller()
        val a = c.dibujaRect(0.0, 0.0, 50.0, 50.0)
        val b = c.dibujaRect(100.0, 0.0, 150.0, 50.0)
        assertTrue("dos formas no pueden compartir semilla", a.seed != b.seed)
    }

    // ---------------------------------------------------------------------
    // Las puntas de una flecha
    // ---------------------------------------------------------------------

    /** Dibuja una flecha de (x1,y1) a (x2,y2) y la deja seleccionada. */
    private fun DrawController.flechaSeleccionada(
        x1: Double, y1: Double, x2: Double, y2: Double
    ): Element {
        selectTool(Tool.ARROW)
        pointerDown(Pt(x1, y1))
        pointerMove(Pt(x2, y2))
        pointerUp(Pt(x2, y2))
        val e = scene.visible.last()
        selectTool(Tool.SELECTION)
        setSelection(setOf(e.id))
        return e
    }

    /**
     * Una raya se coge por sus **puntas**, no por las esquinas de una caja: en
     * una diagonal, esas esquinas caen donde no hay nada dibujado.
     */
    @Test
    fun `una flecha seleccionada ofrece tiradores en sus dos puntas`() {
        val c = controller()
        val e = c.flechaSeleccionada(0.0, 0.0, 100.0, 60.0)
        val tiradores = getSelectionTransformHandles(listOf(c.scene.byId(e.id)!!), zoom = 1.0)

        // Los dos puntos, más el del medio del tramo para doblarla.
        assertEquals(
            setOf(HandleType.POINT_START, HandleType.POINT_END, HandleType.POINT_ADD),
            tiradores.map { it.type }.toSet()
        )
        assertEquals(2, tiradores.count { it.type != HandleType.POINT_ADD })
        val inicio = tiradores.first { it.type == HandleType.POINT_START }
        val fin = tiradores.first { it.type == HandleType.POINT_END }
        assertEquals(0.0, inicio.centerX, 1e-6)
        assertEquals(0.0, inicio.centerY, 1e-6)
        assertEquals(100.0, fin.centerX, 1e-6)
        assertEquals(60.0, fin.centerY, 1e-6)
    }

    /**
     * Lo delicado de mover una punta: **la otra no se mueve**. Los puntos se
     * guardan relativos al origen del elemento, así que tocar el primero obliga
     * a recolocar ese origen y compensar el resto — y sin eso, arrastrar la
     * cola de una flecha se llevaba la punta con ella.
     */
    @Test
    fun `arrastrar una punta deja la otra donde estaba`() {
        val c = controller()
        val e = c.flechaSeleccionada(0.0, 0.0, 100.0, 0.0)

        // El dedo baja sobre el tirador de la punta y la lleva a otro sitio.
        c.pointerDown(Pt(100.0, 0.0))
        c.pointerMove(Pt(140.0, 80.0))
        c.pointerUp(Pt(140.0, 80.0))

        val puntos = absolutePoints(c.scene.byId(e.id)!!)
        assertEquals("la cola se ha movido", 0.0, puntos.first().x, 1e-6)
        assertEquals("la cola se ha movido", 0.0, puntos.first().y, 1e-6)
        assertEquals(140.0, puntos.last().x, 1e-6)
        assertEquals(80.0, puntos.last().y, 1e-6)
    }

    @Test
    fun `arrastrar la cola deja la punta donde estaba`() {
        val c = controller()
        val e = c.flechaSeleccionada(0.0, 0.0, 100.0, 0.0)

        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(-40.0, -30.0))
        c.pointerUp(Pt(-40.0, -30.0))

        val puntos = absolutePoints(c.scene.byId(e.id)!!)
        assertEquals(-40.0, puntos.first().x, 1e-6)
        assertEquals(-30.0, puntos.first().y, 1e-6)
        assertEquals("la punta se ha movido", 100.0, puntos.last().x, 1e-6)
        assertEquals("la punta se ha movido", 0.0, puntos.last().y, 1e-6)
    }

    /** Y la caja del elemento se queda al día, o el picado dejaría de acertar. */
    @Test
    fun `mover una punta recalcula la caja`() {
        val c = controller()
        val e = c.flechaSeleccionada(0.0, 0.0, 100.0, 0.0)
        c.pointerDown(Pt(100.0, 0.0))
        c.pointerMove(Pt(100.0, 50.0))
        c.pointerUp(Pt(100.0, 50.0))

        val movida = c.scene.byId(e.id)!!
        assertEquals(100.0, movida.width, 1e-6)
        assertEquals(50.0, movida.height, 1e-6)
    }

    // ---------------------------------------------------------------------
    // Líneas de varios tramos
    // ---------------------------------------------------------------------

    /** El tirador del medio de un tramo crea un punto ahí y lo arrastra. */
    @Test
    fun `arrastrar el medio de un tramo dobla la línea`() {
        val c = controller()
        val e = c.flechaSeleccionada(0.0, 0.0, 200.0, 0.0)

        // El medio del único tramo está en (100, 0).
        c.pointerDown(Pt(100.0, 0.0))
        c.pointerMove(Pt(100.0, 90.0))
        c.pointerUp(Pt(100.0, 90.0))

        val puntos = absolutePoints(c.scene.byId(e.id)!!)
        assertEquals("tendría que haber tres puntos", 3, puntos.size)
        assertEquals(0.0, puntos[0].x, 1e-6)
        assertEquals(100.0, puntos[1].x, 1e-6)
        assertEquals(90.0, puntos[1].y, 1e-6)
        assertEquals(200.0, puntos[2].x, 1e-6)
        assertEquals("la punta no debía moverse", 0.0, puntos[2].y, 1e-6)
    }

    /** Y una vez doblada, el doblez se mueve como cualquier punto. */
    @Test
    fun `un punto intermedio se arrastra sin tocar los extremos`() {
        val c = controller()
        val e = c.flechaSeleccionada(0.0, 0.0, 200.0, 0.0)
        c.pointerDown(Pt(100.0, 0.0))
        c.pointerMove(Pt(100.0, 90.0))
        c.pointerUp(Pt(100.0, 90.0))

        c.pointerDown(Pt(100.0, 90.0))
        c.pointerMove(Pt(60.0, 140.0))
        c.pointerUp(Pt(60.0, 140.0))

        val puntos = absolutePoints(c.scene.byId(e.id)!!)
        assertEquals(3, puntos.size)
        assertEquals(60.0, puntos[1].x, 1e-6)
        assertEquals(140.0, puntos[1].y, 1e-6)
        assertEquals(0.0, puntos.first().x, 1e-6)
        assertEquals(200.0, puntos.last().x, 1e-6)
    }

    /**
     * Arrastrar un doblez encima de su vecino lo borra: es la forma de quitar
     * un punto de más sin otro botón que aprender.
     */
    @Test
    fun `un doblez soltado encima de su vecino desaparece`() {
        val c = controller()
        val e = c.flechaSeleccionada(0.0, 0.0, 200.0, 0.0)
        c.pointerDown(Pt(100.0, 0.0))
        c.pointerMove(Pt(100.0, 90.0))
        c.pointerUp(Pt(100.0, 90.0))
        assertEquals(3, absolutePoints(c.scene.byId(e.id)!!).size)

        // Se arrastra el doblez justo encima del extremo inicial.
        c.pointerDown(Pt(100.0, 90.0))
        c.pointerMove(Pt(0.0, 0.0))
        c.pointerUp(Pt(0.0, 0.0))

        assertEquals(2, absolutePoints(c.scene.byId(e.id)!!).size)
    }

    /** Los extremos no se borran así: sin ellos no queda línea. */
    @Test
    fun `un extremo soltado encima del otro no borra nada`() {
        val c = controller()
        val e = c.flechaSeleccionada(0.0, 0.0, 200.0, 0.0)
        c.pointerDown(Pt(200.0, 0.0))
        c.pointerMove(Pt(0.0, 0.0))
        c.pointerUp(Pt(0.0, 0.0))
        assertEquals(2, absolutePoints(c.scene.byId(e.id)!!).size)
    }

    /**
     * **Solo los extremos se anclan.** Un doblez del recorrido soltado encima
     * de una caja no puede quedar atado a ella: la línea se retorcería sola en
     * cuanto se moviera esa caja.
     */
    @Test
    fun `un punto intermedio soltado sobre una forma no se ancla`() {
        val c = controller()
        val caja = c.dibujaRect(300.0, 300.0, 420.0, 400.0)
        val e = c.flechaSeleccionada(0.0, 0.0, 800.0, 0.0)

        // Se dobla la flecha llevando el medio del tramo encima de la caja.
        c.pointerDown(Pt(400.0, 0.0))
        c.pointerMove(Pt(360.0, 350.0))
        c.pointerUp(Pt(360.0, 350.0))

        val flecha = c.scene.byId(e.id)!!
        assertEquals(3, absolutePoints(flecha).size)
        assertNull("un doblez no se ata a nada", flecha.startBinding)
        assertNull("un doblez no se ata a nada", flecha.endBinding)
        assertNotNull(c.scene.byId(caja.id))
    }

    /** La punta sí: soltarla sobre una forma la engancha, como siempre. */
    @Test
    fun `un extremo soltado sobre una forma sí se ancla`() {
        val c = controller()
        c.dibujaRect(300.0, 300.0, 420.0, 400.0)
        val e = c.flechaSeleccionada(0.0, 0.0, 100.0, 20.0)

        c.pointerDown(Pt(100.0, 20.0))
        c.pointerMove(Pt(360.0, 350.0))
        c.pointerUp(Pt(360.0, 350.0))

        assertNotNull("la punta tenía que engancharse", c.scene.byId(e.id)!!.endBinding)
    }

    /**
     * **El imán también al corregir.** Servía solo mientras se trazaba, y
     * recolocar después la punta de una flecha para que cayera justo en una
     * esquina había que hacerlo a pulso — que es lo que el imán existe para
     * evitar.
     */
    @Test
    fun `mover una punta se engancha a la esquina de una forma`() {
        val c = controller()
        val caja = c.dibujaRect(300.0, 300.0, 400.0, 400.0)
        val e = c.flechaSeleccionada(0.0, 0.0, 100.0, 0.0)

        // La punta se suelta *cerca* de la esquina de la caja, no encima.
        c.pointerDown(Pt(100.0, 0.0))
        c.pointerMove(Pt(295.0, 296.0))
        c.pointerUp(Pt(295.0, 296.0))

        val punta = absolutePoints(c.scene.byId(e.id)!!).last()
        assertEquals("tenía que haberse pegado a la esquina", caja.x, punta.x, 1e-6)
        assertEquals(caja.y, punta.y, 1e-6)
    }

    /** Y a un punto tecleado en una tabla, que para eso se teclea exacto. */
    @Test
    fun `mover una punta se engancha a un punto de una tabla`() {
        val c = controller()
        val tabla = c.addTabla(Pt(0.0, 0.0))
        c.updateTabla(tabla.copy(puntos = listOf(PuntoDeTabla(500.0, -500.0))))
        val e = c.flechaSeleccionada(0.0, 0.0, 100.0, 0.0)

        c.pointerDown(Pt(100.0, 0.0))
        c.pointerMove(Pt(496.0, 503.0))
        c.pointerUp(Pt(496.0, 503.0))

        val punta = absolutePoints(c.scene.byId(e.id)!!).last()
        assertEquals(500.0, punta.x, 1e-6)
        assertEquals(500.0, punta.y, 1e-6)
    }

    // ---------------------------------------------------------------------
    // Tablas de coordenadas
    // ---------------------------------------------------------------------

    /**
     * **Un color, una serie.** Es lo que hace que mirar un punto en el dibujo
     * baste para saber de qué tabla salió.
     */
    @Test
    fun `cada color es una tabla distinta`() {
        val c = controller()
        val roja = c.tablaDeColor(COLORES_DE_TABLA[0], Pt(0.0, 0.0))
        val azul = c.tablaDeColor(COLORES_DE_TABLA[1], Pt(0.0, 0.0))
        assertTrue("dos colores no pueden ser la misma tabla", roja.id != azul.id)
        assertEquals(2, c.scene.tablas.size)

        // Volver a pedir un color ya usado devuelve la misma, no crea otra.
        assertEquals(roja.id, c.tablaDeColor(COLORES_DE_TABLA[0], Pt(9.0, 9.0)).id)
        assertEquals(2, c.scene.tablas.size)
    }

    // ---------------------------------------------------------------------
    // Texto
    // ---------------------------------------------------------------------

    /**
     * Tocar un texto ya puesto lo **abre para corregirlo**, como en el
     * original. Antes plantaba otro encima y el de abajo seguía ahí: al
     * intentar arreglar una errata acababas con dos textos superpuestos.
     */
    @Test
    fun `tocar un texto con la herramienta de texto lo abre en vez de crear otro`() {
        val c = controller()
        c.selectTool(Tool.TEXT)
        c.pointerDown(Pt(10.0, 10.0))
        val primero = c.scene.visible.last()
        c.updateText(primero.id, "hola", 40.0, 25.0)
        c.clearPendingText()

        // Se vuelve a tocar encima del texto que ya hay.
        c.pointerDown(Pt(20.0, 20.0))

        assertEquals("no debería haber creado otro", 1, c.scene.visible.size)
        assertEquals(primero.id, c.pendingTextId)
    }

    /** Tocando en un hueco sí nace uno nuevo, como siempre. */
    @Test
    fun `tocar en vacío con la herramienta de texto crea uno nuevo`() {
        val c = controller()
        c.selectTool(Tool.TEXT)
        c.pointerDown(Pt(10.0, 10.0))
        val primero = c.scene.visible.last()
        c.updateText(primero.id, "hola", 40.0, 25.0)
        c.clearPendingText()

        c.pointerDown(Pt(400.0, 400.0))
        assertEquals(2, c.scene.visible.size)
        assertTrue(c.pendingTextId != primero.id)
    }

    // ---------------------------------------------------------------------
    // Seleccionar
    // ---------------------------------------------------------------------

    @Test
    fun `tocar una forma rellena la selecciona`() {
        val c = controller()
        c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
        val e = c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        c.deselect()

        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(50.0, 50.0))
        c.pointerUp(Pt(50.0, 50.0))
        assertEquals(setOf(e.id), c.selectedIds)
    }

    @Test
    fun `tocar el vacio deselecciona`() {
        val c = controller()
        c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(500.0, 500.0))
        c.pointerUp(Pt(500.0, 500.0))
        assertTrue(c.selectedIds.isEmpty())
    }

    @Test
    fun `el rectangulo de seleccion coge lo que encierra del todo`() {
        val c = controller()
        val a = c.dibujaRect(0.0, 0.0, 50.0, 50.0)
        val b = c.dibujaRect(200.0, 200.0, 250.0, 250.0)
        c.deselect()

        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(-10.0, -10.0))
        c.pointerMove(Pt(100.0, 100.0))
        assertNotNull("debe haber caja de selección visible", c.selectionBox)
        c.pointerUp(Pt(100.0, 100.0))

        assertEquals(setOf(a.id), c.selectedIds)
        assertFalse(b.id in c.selectedIds)
        assertNull("la caja desaparece al soltar", c.selectionBox)
    }

    @Test
    fun `cambiar de herramienta deselecciona`() {
        val c = controller()
        val e = c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        c.setSelection(setOf(e.id))
        assertTrue(c.selectedIds.isNotEmpty())
        c.selectTool(Tool.ELLIPSE)
        assertTrue(c.selectedIds.isEmpty())
    }

    /**
     * Tocar algo que ya está seleccionado **no** rehace la selección. Sin esta
     * regla sería imposible arrastrar varios elementos a la vez: el primer
     * toque reduciría la selección a uno solo.
     */
    @Test
    fun `tocar dentro de una seleccion multiple la conserva`() {
        val c = controller()
        c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
        val a = c.dibujaRect(0.0, 0.0, 50.0, 50.0)
        val b = c.dibujaRect(60.0, 0.0, 110.0, 50.0)
        c.setSelection(setOf(a.id, b.id))

        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(25.0, 25.0))
        assertEquals(setOf(a.id, b.id), c.selectedIds)
    }

    @Test
    fun `seleccionar un miembro de un grupo coge el grupo entero`() {
        val c = controller()
        c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
        val a = c.dibujaRect(0.0, 0.0, 50.0, 50.0)
        val b = c.dibujaRect(200.0, 0.0, 250.0, 50.0)
        c.setSelection(setOf(a.id, b.id))
        c.group()
        c.deselect()

        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(25.0, 25.0))
        assertEquals(2, c.selectedIds.size)
    }

    // ---------------------------------------------------------------------
    // Mover y transformar
    // ---------------------------------------------------------------------

    @Test
    fun `arrastrar una forma seleccionada la mueve`() {
        val c = controller()
        c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
        val e = c.dibujaRect(0.0, 0.0, 100.0, 100.0)

        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf(e.id))
        c.pointerDown(Pt(50.0, 50.0))
        c.pointerMove(Pt(150.0, 90.0))
        c.pointerUp(Pt(150.0, 90.0))

        val movido = c.scene.byId(e.id)!!
        assertEquals(100.0, movido.x, 1e-6)
        assertEquals(40.0, movido.y, 1e-6)
    }

    /**
     * Mover en muchos pasos pequeños tiene que dar exactamente lo mismo que en
     * uno grande. Es el invariante de calcular siempre desde los originales; si
     * se acumulasen deltas, esto se iría desviando.
     */
    @Test
    fun `mover a saltitos acaba en el mismo sitio que de una vez`() {
        fun mover(pasos: Int): Element {
            val c = controller()
            c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
            val e = c.dibujaRect(0.0, 0.0, 100.0, 100.0)
            c.selectTool(Tool.SELECTION)
            c.setSelection(setOf(e.id))
            c.pointerDown(Pt(50.0, 50.0))
            for (i in 1..pasos) {
                c.pointerMove(Pt(50.0 + 137.0 * i / pasos, 50.0 + 91.0 * i / pasos))
            }
            c.pointerUp(Pt(187.0, 141.0))
            return c.scene.byId(e.id)!!
        }
        assertEquals(mover(1).x, mover(50).x, 1e-9)
        assertEquals(mover(1).y, mover(50).y, 1e-9)
    }

    @Test
    fun `arrastrar el tirador SE redimensiona`() {
        val c = controller()
        val e = c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf(e.id))

        // El tirador SE cae fuera de la esquina, separado por el margen.
        val handle = getTransformHandles(c.scene.byId(e.id)!!, 1.0)
            .first { it.type == HandleType.SE }
        c.pointerDown(Pt(handle.centerX, handle.centerY))
        c.pointerMove(Pt(200.0, 150.0))
        c.pointerUp(Pt(200.0, 150.0))

        val r = c.scene.byId(e.id)!!
        assertEquals(0.0, r.x, 1e-6)
        assertEquals(200.0, r.x + r.width, 1e-6)
        assertEquals(150.0, r.y + r.height, 1e-6)
    }

    @Test
    fun `el borrador marca lo que toca al pasar`() {
        val c = controller()
        c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
        c.dibujaRect(0.0, 0.0, 100.0, 100.0)

        c.selectTool(Tool.ERASER)
        c.pointerDown(Pt(50.0, 50.0))
        c.pointerUp(Pt(50.0, 50.0))
        assertTrue(c.scene.visible.isEmpty())
    }

    @Test
    fun `la mano desplaza la vista sin tocar los elementos`() {
        val c = controller()
        val e = c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        c.selectTool(Tool.HAND)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(30.0, 40.0))
        c.pointerUp(Pt(30.0, 40.0))

        assertEquals(30.0, c.scene.viewport.scrollX, 1e-9)
        assertEquals(40.0, c.scene.viewport.scrollY, 1e-9)
        assertEquals(0.0, c.scene.byId(e.id)!!.x, 1e-9)
    }

    // ---------------------------------------------------------------------
    // Flechas
    // ---------------------------------------------------------------------

    @Test
    fun `una flecha que acaba sobre una forma se ancla sola`() {
        val c = controller()
        c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
        val caja = c.dibujaRect(200.0, 0.0, 300.0, 100.0)
        c.deselect()

        c.selectTool(Tool.ARROW)
        c.pointerDown(Pt(0.0, 50.0))
        c.pointerMove(Pt(210.0, 50.0))
        c.pointerUp(Pt(210.0, 50.0))

        val flecha = c.scene.visible.last()
        assertEquals(ElementType.ARROW, flecha.type)
        assertEquals(caja.id, flecha.endBinding?.elementId)
        // Y la punta ya no invade la caja.
        assertTrue(absolutePoints(flecha).last().x <= 200.0)
    }

    /**
     * Regresión de uso real: la flecha se enganchaba con solo **entrar** en una
     * forma rellena, así que cruzar un rectángulo para llegar a otro sitio la
     * dejaba atada al del medio, apuntándolo para siempre. Anclar es una
     * decisión sobre dónde acaba la flecha, y eso ocurre en el borde.
     */
    @Test
    fun `soltar la punta dentro de una forma la ancla`() {
        val c = controller()
        c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
        c.dibujaRect(100.0, 0.0, 200.0, 100.0)
        val rect = c.scene.visible.last().id
        c.deselect()

        // Termina muy dentro del rectángulo, lejos de cualquier borde: es como
        // se dibuja un esquema con el dedo, sin buscar el borde.
        c.selectTool(Tool.ARROW)
        c.pointerDown(Pt(0.0, 50.0))
        c.pointerMove(Pt(150.0, 50.0))
        c.pointerUp(Pt(150.0, 50.0))

        assertEquals(rect, c.scene.visible.last().endBinding?.elementId)
    }

    /**
     * Lo otro: **atravesar** una forma para llegar más allá no la ancla.
     *
     * Es el fallo que en su día se intentó arreglar prohibiendo anclar por
     * dentro. La causa no era esa: lo que decide el anclaje es dónde ACABA la
     * flecha, y una que cruza y sigue tiene la punta fuera.
     */
    @Test
    fun `atravesar una forma y acabar mas alla no la ancla`() {
        val c = controller()
        c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
        c.dibujaRect(100.0, 0.0, 200.0, 100.0)
        c.deselect()

        c.selectTool(Tool.ARROW)
        c.pointerDown(Pt(0.0, 50.0))
        c.pointerMove(Pt(150.0, 50.0))
        c.pointerMove(Pt(500.0, 50.0))
        c.pointerUp(Pt(500.0, 50.0))

        assertNull(
            "la forma que se cruza por el camino no debe anclar",
            c.scene.visible.last().endBinding
        )
    }

    @Test
    fun `los dos extremos no se anclan a la misma forma`() {
        val c = controller()
        c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
        val caja = c.dibujaRect(0.0, 0.0, 200.0, 200.0)
        c.deselect()

        // Del borde izquierdo al derecho de la MISMA caja.
        c.selectTool(Tool.ARROW)
        c.pointerDown(Pt(0.0, 100.0))
        c.pointerMove(Pt(200.0, 100.0))
        c.pointerUp(Pt(200.0, 100.0))

        val flecha = c.scene.visible.last()
        assertEquals(caja.id, flecha.endBinding?.elementId)
        assertNull("el origen no puede atarse a la misma caja", flecha.startBinding)
    }

    @Test
    fun `una flecha en el vacio no se ancla a nada`() {
        val c = controller()
        c.selectTool(Tool.ARROW)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(100.0, 0.0))
        c.pointerUp(Pt(100.0, 0.0))
        val flecha = c.scene.visible.single()
        assertNull(flecha.startBinding)
        assertNull(flecha.endBinding)
    }

    // ---------------------------------------------------------------------
    // Historial
    // ---------------------------------------------------------------------

    @Test
    fun `deshacer retira la ultima forma dibujada`() {
        val c = controller()
        c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        assertEquals(1, c.scene.visible.size)
        assertTrue(c.canUndo)
        c.undo()
        assertTrue(c.scene.visible.isEmpty())
        c.redo()
        assertEquals(1, c.scene.visible.size)
    }

    @Test
    fun `un gesto entero es un solo paso de deshacer`() {
        val c = controller()
        c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
        val e = c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf(e.id))

        c.pointerDown(Pt(50.0, 50.0))
        repeat(20) { c.pointerMove(Pt(50.0 + it * 5.0, 50.0)) }
        c.pointerUp(Pt(145.0, 50.0))

        c.undo()
        assertEquals("un arrastre no puede dejar 20 pasos", 0.0, c.scene.byId(e.id)!!.x, 1e-6)
    }

    @Test
    fun `deshacer no deja seleccionado lo que ya no existe`() {
        val c = controller()
        c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        c.undo()
        assertTrue(c.selectedIds.isEmpty())
    }

    @Test
    fun `cancelar deja la escena como estaba`() {
        val c = controller()
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(100.0, 100.0))
        c.cancel()
        assertTrue(c.scene.visible.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Estilo
    // ---------------------------------------------------------------------

    @Test
    /**
     * Sin nada seleccionado, cambiar el color solo carga el pincel: lo ya
     * dibujado no se toca. Es lo que espera la mano al elegir un color **antes**
     * de trazar, que es como se usa con el dedo.
     */
    fun `sin seleccion el color solo afecta a lo siguiente`() {
        val c = controller()
        val previo = c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        c.changeStyle({ it.copy(strokeColor = "#ff0000") }, { it.copy(strokeColor = "#ff0000") })

        assertEquals(Element.DEFAULT_STROKE_COLOR, c.scene.byId(previo.id)!!.strokeColor)
        assertEquals("#ff0000", c.dibujaRect(200.0, 0.0, 300.0, 100.0).strokeColor)
    }

    @Test
    fun `con seleccion el color afecta tambien a lo seleccionado`() {
        val c = controller()
        val e = c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        c.setSelection(setOf(e.id))
        c.changeStyle({ it.copy(strokeColor = "#ff0000") }, { it.copy(strokeColor = "#ff0000") })

        assertEquals("#ff0000", c.scene.byId(e.id)!!.strokeColor)
        assertEquals("#ff0000", c.dibujaRect(200.0, 0.0, 300.0, 100.0).strokeColor)
    }

    @Test
    fun `un elemento bloqueado no se mueve ni se borra`() {
        val c = controller()
        c.changeStyle({ it.copy(backgroundColor = "#ff0000") }, { it })
        val e = c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        c.setSelection(setOf(e.id))
        c.toggleLockSelection()

        c.deleteSelection()
        assertEquals(1, c.scene.visible.size)
    }

    @Test
    fun `duplicar deja la copia seleccionada`() {
        val c = controller()
        val e = c.dibujaRect(0.0, 0.0, 100.0, 100.0)
        c.setSelection(setOf(e.id))
        c.duplicateSelection()

        assertEquals(2, c.scene.visible.size)
        assertEquals(1, c.selectedIds.size)
        assertFalse("la selección debe ser la copia", e.id in c.selectedIds)
    }
}

/**
 * El formato de archivo.
 *
 * Es lo que sostiene la promesa de interoperabilidad: si el JSON se desvía, un
 * dibujo hecho aquí deja de abrirse en excalidraw.com y toda la estrategia se
 * cae.
 */
class ExcalidrawFormatTest {

    private fun escenaDeEjemplo(): Scene {
        val c = DrawController()
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(0.0, 0.0)); c.pointerMove(Pt(100.0, 50.0)); c.pointerUp(Pt(100.0, 50.0))
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(200.0, 0.0)); c.pointerMove(Pt(300.0, 80.0)); c.pointerUp(Pt(300.0, 80.0))
        return c.scene
    }

    @Test
    fun `la escena sobrevive a un viaje de ida y vuelta`() {
        val original = escenaDeEjemplo()
        val texto = ExcalidrawJson.encodeToString(original)
        val vuelta = ExcalidrawJson.decodeFromString<Scene>(texto)
        assertEquals(original.elements, vuelta.elements)
        assertEquals(original.style, vuelta.style)
    }

    @Test
    fun `el archivo exportado lleva el tipo y la version que Excalidraw exige`() {
        val texto = ExcalidrawStore.exportar(escenaDeEjemplo())
        assertTrue(texto.contains("\"type\":\"excalidraw\""))
        assertTrue(texto.contains("\"version\":2"))
    }

    /**
     * [Element] es una clase plana, así que un rectángulo tiene `points`,
     * `text` y `fileId` a null. Excalidraw **no escribe** los campos que no
     * aplican al tipo, y emitirlos como null aleja el archivo del formato.
     */
    @Test
    fun `el archivo exportado no lleva nulos de campos que no aplican`() {
        val texto = ExcalidrawStore.exportar(escenaDeEjemplo())
        assertFalse("un rectángulo no debe llevar points", texto.contains("\"points\":null"))
        assertFalse(texto.contains("\"text\":null"))
        assertFalse(texto.contains("\"fileId\":null"))
    }

    @Test
    fun `los tipos se serializan con el nombre de Excalidraw`() {
        val texto = ExcalidrawStore.exportar(escenaDeEjemplo())
        assertTrue(texto.contains("\"type\":\"rectangle\""))
        assertTrue(texto.contains("\"type\":\"ellipse\""))
    }

    /**
     * Un archivo hecho en la web trae campos que este módulo no porta
     * (`frameId`, `elbowed`, `customData`…). Sin tolerancia a claves
     * desconocidas, abrirlo reventaría.
     */
    @Test
    fun `un archivo con campos desconocidos se puede leer`() {
        val texto = """
            {"type":"excalidraw","version":2,"source":"https://excalidraw.com",
             "elements":[{"id":"x1","type":"rectangle","x":0,"y":0,"width":10,"height":10,
               "seed":1,"frameId":null,"customData":{"a":1},"boundElements":[],
               "index":"a0","elbowed":false}],
             "appState":{"viewBackgroundColor":"#ffffff","currentItemStrokeColor":"#000"}}
        """.trimIndent()
        val archivo = ExcalidrawJson.decodeFromString<ExcalidrawFile>(texto)
        assertEquals(1, archivo.elements.size)
        assertEquals("x1", archivo.elements[0].id)
        assertEquals(ElementType.RECTANGLE, archivo.elements[0].type)
    }

    @Test
    fun `lo borrado no se exporta`() {
        val c = DrawController()
        c.selectTool(Tool.RECTANGLE)
        c.pointerDown(Pt(0.0, 0.0)); c.pointerMove(Pt(100.0, 50.0)); c.pointerUp(Pt(100.0, 50.0))
        val id = c.scene.visible.single().id
        c.setSelection(setOf(id))
        c.deleteSelection()

        val archivo = ExcalidrawJson.decodeFromString<ExcalidrawFile>(
            ExcalidrawStore.exportar(c.scene)
        )
        assertTrue(archivo.elements.isEmpty())
    }

    @Test
    fun `los enums de estilo usan los nombres del formato`() {
        val e = newElement(
            ElementType.DIAMOND, 0.0, 0.0,
            ItemStyle(fillStyle = FillStyle.CROSS_HATCH, strokeStyle = StrokeStyle.DOTTED),
            10.0, 10.0
        )
        val texto = ExcalidrawJson.encodeToString(e)
        assertTrue(texto.contains("\"fillStyle\":\"cross-hatch\""))
        assertTrue(texto.contains("\"strokeStyle\":\"dotted\""))
        assertTrue(texto.contains("\"type\":\"diamond\""))
    }
}
