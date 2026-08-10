package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las líneas guía: el lápiz azul de los planos de toda la vida.
 *
 * Se traza una figura «de referencia», se dibuja encima apoyándose en ella, y al
 * final se borra el azul. Lo que hace que eso funcione no es que se vea
 * translúcida —eso es lo fácil— sino **que el trazo de verdad se pueda apoyar en
 * ella**: en sus vértices, en sus cruces y, sobre todo, en todo su canto.
 */
class GuiasTest {

    private fun guiaCaja(id: String = "g") = Element(
        id = id, type = ElementType.RECTANGLE, x = 100.0, y = 100.0,
        width = 200.0, height = 100.0, seed = 1, reference = true
    )

    private fun guiaCirculo(id: String = "c", cx: Double = 200.0, cy: Double = 200.0, r: Double = 80.0) =
        Element(
            id = id, type = ElementType.ELLIPSE, x = cx - r, y = cy - r,
            width = r * 2, height = r * 2, seed = 1, reference = true
        )

    // ---- La escuadra: pegarse al canto ----

    /**
     * En mitad de un lado no hay ningún punto notable, así que sin engancharse
     * al canto la raya salía torcida entre esquina y esquina: exactamente lo que
     * una escuadra existe para evitar.
     */
    @Test
    fun `el trazo se pega al canto de una guia`() {
        // A cuatro píxeles por debajo del lado de arriba, y lejos de las
        // esquinas y del punto medio: ahí no hay nada notable a lo que ir.
        val hit = buscarAnclaje(listOf(guiaCaja()), Pt(250.0, 104.0), zoom = 1.0)
        assertEquals(TipoAnclaje.BORDE, hit?.tipo)
        assertEquals(250.0, hit!!.punto.x, 0.001)
        assertEquals(100.0, hit.punto.y, 0.001)
    }

    /** Y a la curva de un círculo, que es donde más se nota. */
    @Test
    fun `el trazo se pega a la curva de una guia redonda`() {
        val guia = guiaCirculo()
        // Por dentro de la curva, a 45°: lejos de los cuatro puntos notables
        // del óvalo, que es donde una curva no se puede repasar a pulso.
        val hit = buscarAnclaje(listOf(guia), Pt(252.0, 252.0), zoom = 1.0)
        assertEquals(TipoAnclaje.BORDE, hit?.tipo)
        assertEquals(80.0, hypot(hit!!.punto.x - 200.0, hit.punto.y - 200.0), 0.3)
    }

    /**
     * **Solo las guías.** Pegarse al borde de algo del dibujo de verdad
     * convertiría cada figura en un carril y no habría forma de trazar cerca de
     * ella sin quedarse pegado.
     */
    @Test
    fun `el canto de lo que no es guia engancha, y se puede apagar`() {
        // **Cambió a petición de quien la usa.** Estuvo restringido al canto de
        // las guías con el argumento de que pegarse al de una figura de verdad
        // convierte cada figura en un carril. Es cierto, pero clavar algo
        // **sobre** un lado —el pie de una altura, un punto de tangencia, el
        // sitio por donde cortar— resultó ser mucho más frecuente que ese
        // estorbo. Así que engancha, y quien lo prefiera como estaba lo apaga.
        val normal = guiaCaja().copy(reference = false)
        val hit = buscarAnclaje(listOf(normal), Pt(250.0, 104.0), zoom = 1.0)
        assertEquals(TipoAnclaje.BORDE, hit?.tipo)

        assertNull(
            buscarAnclaje(
                listOf(normal), Pt(250.0, 104.0), zoom = 1.0,
                ajustes = AjustesEnganche(bordeDeFigura = false)
            )
        )
    }

    /**
     * Apuntando a una esquina, el trazo tiene que quedar **en** la esquina y no
     * *cerca* de ella — aunque el canto de la propia guía pase más cerca del
     * dedo, que pasa siempre.
     */
    @Test
    fun `en una esquina manda la esquina y no el canto`() {
        val hit = buscarAnclaje(listOf(guiaCaja()), Pt(103.0, 103.0), zoom = 1.0)
        assertEquals(TipoAnclaje.ESQUINA, hit?.tipo)
        assertEquals(100.0, hit!!.punto.x, 0.001)
        assertEquals(100.0, hit.punto.y, 0.001)
    }

    /** Lejos del canto no tira nada: el trazo va donde va el dedo. */
    @Test
    fun `lejos de la guia no engancha`() {
        assertNull(buscarAnclaje(listOf(guiaCaja()), Pt(250.0, 150.0), zoom = 1.0))
    }

    @Test
    fun `el canto se puede apagar por su cuenta`() {
        val sinCanto = AjustesEnganche(bordeDeGuia = false)
        assertNull(
            buscarAnclaje(listOf(guiaCaja()), Pt(250.0, 104.0), zoom = 1.0, ajustes = sinCanto)
        )
    }

    /** Y las guías cruzan con lo demás, como cualquier figura. */
    @Test
    fun `dos guias que se cruzan dan interseccion`() {
        val caja = guiaCaja()
        val circulo = guiaCirculo(cx = 100.0, cy = 100.0, r = 50.0)
        // El círculo, centrado en la esquina de la caja, corta su lado de arriba
        // en (150, 100).
        val hit = buscarAnclaje(listOf(caja, circulo), Pt(148.0, 102.0), zoom = 1.0)
        assertEquals(TipoAnclaje.INTERSECCION, hit?.tipo)
        assertEquals(150.0, hit!!.punto.x, 0.5)
    }

    // ---- El lápiz sobre la guía ----

    /**
     * **El lápiz también resbala por el canto**, que es lo que convierte la
     * guía en plantilla de verdad: se apoya y se repasa.
     */
    @Test
    fun `el lapiz sigue el canto de la guia`() {
        val c = DrawController(Scene(elements = listOf(guiaCaja())))
        c.selectTool(Tool.FREEDRAW)
        c.pointerDown(Pt(150.0, 104.0))
        c.pointerMove(Pt(210.0, 103.0))
        c.pointerUp(Pt(210.0, 103.0))

        val trazo = c.scene.visible.last { it.isFreeDraw }
        // Los dos puntos han caído sobre el lado de arriba de la guía.
        assertTrue(absolutePoints(trazo).all { abs(it.y - 100.0) < 0.001 })
    }

    /**
     * Pero **solo** por el canto: un lápiz que salte a un vértice o a un cruce
     * en mitad del recorrido no se corrige, se rompe.
     */
    @Test
    fun `el lapiz no salta a los vertices`() {
        val c = DrawController(Scene(elements = listOf(guiaCaja().copy(reference = false))))
        c.selectTool(Tool.FREEDRAW)
        c.pointerDown(Pt(103.0, 103.0))
        c.pointerUp(Pt(103.0, 103.0))

        val trazo = c.scene.visible.last { it.isFreeDraw }
        assertEquals(103.0, trazo.x, 0.001)
        assertEquals(103.0, trazo.y, 0.001)
    }

    // ---- El transportador: repasar una guía redonda ----

    /**
     * **La regresión del arco.**
     *
     * Trazando sobre el óvalo guía solo se dibujaba un pellizco y ahí se
     * quedaba, pasearas el dedo por donde lo pasearas. El motivo no estaba en el
     * gesto sino en la caché del renderizador: un arco no guarda sus puntos sino
     * cuánto barre, y la huella que decide si la geometría sigue valiendo no
     * miraba ese campo. Como la caja del arco es la del óvalo y no se mueve, la
     * huella salía idéntica en cada fotograma y se devolvía el primer trozo
     * generado para siempre.
     */
    @Test
    fun `un arco que barre mas no reutiliza la geometria vieja`() {
        val arco = Element(
            id = "a", type = ElementType.ARC, x = 0.0, y = 0.0,
            width = 100.0, height = 100.0, seed = 1,
            arcStart = 0.0, arcSweep = 0.2
        )
        assertFalse(
            "el arco se queda congelado en su primer trozo",
            hasSameGeometry(arco, arco.copy(arcSweep = 1.4))
        )
        assertFalse(
            "empezar por otro sitio dibuja otro arco",
            hasSameGeometry(arco, arco.copy(arcStart = 1.0))
        )
        // Y lo que no cambia el dibujo sigue sin invalidar nada.
        assertTrue(hasSameGeometry(arco, arco.copy(opacity = 40, strokeColor = "#e03131")))
    }

    /** Repasando media guía redonda sale medio arco, no un punto. */
    @Test
    fun `repasar la guia redonda traza el arco entero`() {
        val guia = guiaCirculo()
        val c = DrawController(Scene(elements = listOf(guia)))
        c.selectTool(Tool.ELLIPSE)

        // Se baja el dedo sobre el borde derecho y se recorre media vuelta por
        // abajo hasta el borde izquierdo.
        c.pointerDown(Pt(280.0, 200.0))
        for (grados in 30..180 step 30) {
            val a = Math.toRadians(grados.toDouble())
            c.pointerMove(Pt(200 + 80 * Math.cos(a), 200 + 80 * Math.sin(a)))
        }
        c.pointerUp(Pt(120.0, 200.0))

        val arco = c.scene.visible.singleOrNull { it.type == ElementType.ARC }
        assertNotNull("no ha quedado ningún arco", arco)
        assertEquals("no ha barrido media vuelta", Math.PI, arco!!.arcSweep!!, 0.15)
        assertTrue("el arco no puede nacer de referencia", !arco.reference)
        // Y los puntos que se pintan son los de medio óvalo, no dos sueltos.
        assertTrue("el arco sale con muy pocos puntos", puntosDelArco(arco).size > 20)
    }

    /** La guía **se queda donde estaba**: se repasa, no se consume. */
    @Test
    fun `repasar la guia no se lleva la guia`() {
        val guia = guiaCirculo()
        val c = DrawController(Scene(elements = listOf(guia)))
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(280.0, 200.0))
        c.pointerMove(Pt(200.0, 280.0))
        c.pointerUp(Pt(200.0, 280.0))

        val sigue = c.scene.visible.singleOrNull { it.id == guia.id }
        assertNotNull("la guía ha desaparecido al repasarla", sigue)
        assertTrue("la guía ha dejado de ser guía", sigue!!.reference)
        assertEquals(ElementType.ELLIPSE, sigue.type)
    }

    /**
     * Un toque seco sobre la guía no deja nada: es un arco de barrido cero, o
     * sea un elemento invisible que luego roba los toques al picar.
     */
    @Test
    fun `un toque sobre la guia no deja un arco vacio`() {
        val c = DrawController(Scene(elements = listOf(guiaCirculo())))
        c.selectTool(Tool.ELLIPSE)
        c.pointerDown(Pt(280.0, 200.0))
        c.pointerUp(Pt(280.0, 200.0))
        assertTrue(c.scene.visible.none { it.type == ElementType.ARC })
    }

    /**
     * Con el modo referencia puesto, tocar sobre una guía **dibuja otra guía**:
     * ahí lo que se está haciendo es montar el andamio, no repasarlo.
     */
    @Test
    fun `en modo referencia la guia no se repasa`() {
        val c = DrawController(Scene(elements = listOf(guiaCirculo())))
        c.selectTool(Tool.ELLIPSE)
        c.modoReferencia = true
        c.pointerDown(Pt(280.0, 200.0))
        c.pointerMove(Pt(330.0, 250.0))
        c.pointerUp(Pt(330.0, 250.0))

        assertTrue(c.scene.visible.none { it.type == ElementType.ARC })
        assertEquals(2, c.scene.visible.count { it.reference })
    }

    // ---- Esconder y borrar ----

    @Test
    fun `esconder las guias no las borra y deja de imantar`() {
        val c = DrawController(Scene(elements = listOf(guiaCaja())))
        assertTrue(c.hayReferencias)

        c.alternarReferencias()
        assertFalse(c.referenciasVisibles)
        assertTrue("esconder no puede borrar", c.hayReferencias)
        // Escondida tampoco tira del dedo: pegarse a algo que no se ve
        // desconcierta más de lo que ayuda.
        assertNull(buscarAnclaje(c.scene.visibleConReferencias, Pt(250.0, 104.0), 1.0))

        c.alternarReferencias()
        assertTrue(c.referenciasVisibles)
        assertNotNull(buscarAnclaje(c.scene.visibleConReferencias, Pt(250.0, 104.0), 1.0))
    }

    /**
     * **Las guías se quitan de una en una, con el borrador y en modo guía.**
     *
     * Hubo un botón de borrarlas todas y se ha ido: un solo toque que se lleva
     * por delante el andamio entero es de los que solo se pulsan por error. Lo
     * que queda es esto, que enseña lo que se lleva mientras se lo lleva.
     */
    @Test
    fun `las guias se quitan una a una con el borrador`() {
        val otra = guiaCaja("g2").copy(x = 400.0)
        val c = DrawController(Scene(elements = listOf(guiaCaja(), otra)))
        c.modoReferencia = true
        c.selectTool(Tool.ERASER)
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerUp(Pt(100.0, 100.0))

        assertEquals("se ha llevado las dos", 1, c.scene.visible.size)
        assertTrue(c.hayReferencias)
        c.undo()
        assertEquals(2, c.scene.visible.size)
    }

    // ---- Los dos mundos: dentro y fuera del modo guía ----

    /**
     * **Fuera del modo guía no se puede borrar una guía.**
     *
     * El andamio está ahí para pasarle el lápiz por encima, así que el borrador
     * y el dedo pasan por él todo el rato. Sin esta separación, media faena
     * consistía en volver a trazar guías que uno mismo se había llevado por
     * delante sin enterarse.
     */
    @Test
    fun `el borrador no se lleva las guias por delante`() {
        val c = DrawController(Scene(elements = listOf(guiaCaja())))
        c.selectTool(Tool.ERASER)
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerUp(Pt(100.0, 100.0))
        assertTrue("el borrador se ha comido la guía", c.hayReferencias)
    }

    /** Y dentro del modo guía sí, que es donde se edita el andamio. */
    @Test
    fun `en modo guia el borrador si borra guias`() {
        val c = DrawController(Scene(elements = listOf(guiaCaja())))
        c.modoReferencia = true
        c.selectTool(Tool.ERASER)
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerUp(Pt(100.0, 100.0))
        assertFalse(c.hayReferencias)
    }

    /** Dentro del modo guía, el borrador no toca el dibujo de verdad. */
    @Test
    fun `en modo guia el borrador respeta el dibujo`() {
        val dibujo = guiaCaja("d").copy(reference = false)
        val c = DrawController(Scene(elements = listOf(dibujo)))
        c.modoReferencia = true
        c.selectTool(Tool.ERASER)
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerUp(Pt(100.0, 100.0))
        assertEquals(1, c.scene.visible.size)
    }

    /** La selección obedece a la misma regla: se coge lo del mundo en el que estás. */
    @Test
    fun `la seleccion solo coge lo del mundo activo`() {
        val guia = guiaCaja()
        val dibujo = guiaCaja("d").copy(reference = false, x = 400.0)
        val c = DrawController(Scene(elements = listOf(guia, dibujo)))
        c.selectTool(Tool.SELECTION)

        c.pointerDown(Pt(100.0, 100.0))
        c.pointerUp(Pt(100.0, 100.0))
        assertTrue("ha cogido una guía fuera del modo guía", c.selectedIds.isEmpty())

        c.modoReferencia = true
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerUp(Pt(100.0, 100.0))
        assertEquals(setOf(guia.id), c.selectedIds)
    }

    /** Y cambiar de mundo suelta lo que había cogido en el otro. */
    @Test
    fun `cambiar de mundo suelta la seleccion`() {
        val c = DrawController(Scene(elements = listOf(guiaCaja())))
        c.modoReferencia = true
        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerUp(Pt(100.0, 100.0))
        assertTrue(c.selectedIds.isNotEmpty())

        c.modoReferencia = false
        assertTrue("los tiradores se quedan sobre algo intocable", c.selectedIds.isEmpty())
    }

    /** «Seleccionar todo» tampoco mezcla los dos mundos. */
    @Test
    fun `seleccionar todo no mezcla guias y dibujo`() {
        val c = DrawController(
            Scene(elements = listOf(guiaCaja(), guiaCaja("d").copy(reference = false)))
        )
        c.selectAll()
        assertEquals(setOf("d"), c.selectedIds)
    }

    // ---- El bote, también en su mundo ----

    /** Cuatro guías que encierran un cuadrado, y una raya del dibujo cruzándolo. */
    private fun andamioYDibujo(): List<Element> {
        fun raya(id: String, a: Pt, b: Pt, guia: Boolean) = Element(
            id = id, type = ElementType.LINE, x = a.x, y = a.y,
            width = kotlin.math.abs(b.x - a.x), height = kotlin.math.abs(b.y - a.y),
            seed = 1, reference = guia,
            points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
        )
        return listOf(
            raya("arriba", Pt(0.0, 0.0), Pt(200.0, 0.0), true),
            raya("derecha", Pt(200.0, 0.0), Pt(200.0, 200.0), true),
            raya("abajo", Pt(200.0, 200.0), Pt(0.0, 200.0), true),
            raya("izquierda", Pt(0.0, 200.0), Pt(0.0, 0.0), true),
            // Del dibujo: parte el cuadrado por la mitad, pero no lo cierra.
            raya("tabique", Pt(100.0, -50.0), Pt(100.0, 250.0), false)
        )
    }

    /**
     * **En modo guía encierran las guías y solo ellas.**
     *
     * Con el tabique del dibujo contando como pared, rellenar el hueco del
     * andamio daba media mancha: se paraba en una raya que en el andamio no
     * existe.
     */
    @Test
    fun `en modo guia el bote solo mira las guias`() {
        val c = DrawController(Scene(elements = andamioYDibujo()))
        c.modoReferencia = true
        c.selectTool(Tool.RELLENO)
        c.pointerDown(Pt(50.0, 100.0))
        c.pointerUp(Pt(50.0, 100.0))

        val mancha = c.scene.visible.single { it.isRegion }
        assertTrue("el relleno del andamio no es guía", mancha.reference)
        // El cuadrado entero, no la mitad que deja el tabique.
        assertEquals(40_000.0, mancha.width * mancha.height, 40_000.0 * 0.06)
    }

    /** Y fuera del modo guía, el andamio no encierra nada. */
    @Test
    fun `fuera del modo guia el bote no se apoya en las guias`() {
        val c = DrawController(Scene(elements = andamioYDibujo()))
        c.selectTool(Tool.RELLENO)
        c.pointerDown(Pt(50.0, 100.0))
        c.pointerUp(Pt(50.0, 100.0))

        assertTrue(
            "se ha apoyado en el andamio para rellenar el dibujo",
            c.scene.visible.none { it.isRegion }
        )
        assertTrue(c.rellenoSinCerrar)
    }

    /** Cualquier herramienta puede trazar guía: no hay una lista aparte. */
    @Test
    fun `las guias no son una herramienta sino un interruptor`() {
        val c = DrawController()
        c.modoReferencia = true
        for (t in listOf(Tool.RECTANGLE, Tool.DIAMOND, Tool.ELLIPSE, Tool.LINE, Tool.FREEDRAW)) {
            c.selectTool(t)
            c.pointerDown(Pt(0.0, 0.0))
            c.pointerMove(Pt(60.0, 40.0))
            c.pointerUp(Pt(60.0, 40.0))
        }
        assertEquals(5, c.scene.visible.size)
        assertTrue("algo se ha trazado como dibujo", c.scene.visible.all { it.reference })
    }
}
