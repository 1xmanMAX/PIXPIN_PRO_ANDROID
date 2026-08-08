package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El alfiler: un clavo que atraviesa dos figuras.
 *
 * La analogía manda, y de ella salen todas estas pruebas. Dos listones clavados
 * por un punto **no se pueden separar**, muevas el que muevas; **pueden girar**
 * uno respecto del otro alrededor del clavo; y **con dos clavos no gira nada**,
 * porque dos puntos en común fijan la posición relativa entera.
 */
class NudosTest {

    private fun raya(id: String, a: Pt, b: Pt) = Element(
        id = id, type = ElementType.LINE, x = a.x, y = a.y,
        width = abs(b.x - a.x), height = abs(b.y - a.y),
        seed = 1, points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
    )

    private fun circulo(id: String, cx: Double, cy: Double, r: Double) = Element(
        id = id, type = ElementType.ELLIPSE, x = cx - r, y = cy - r,
        width = r * 2, height = r * 2, seed = 1
    )

    /** Tres rayas que dibujan un triángulo, cada una por su cuenta. */
    private fun triangulo() = listOf(
        raya("a", Pt(0.0, 100.0), Pt(100.0, 100.0)),
        raya("b", Pt(100.0, 100.0), Pt(50.0, 0.0)),
        raya("c", Pt(50.0, 0.0), Pt(0.0, 100.0))
    )

    private fun puntas(c: DrawController, id: String) = absolutePoints(c.scene.byId(id)!!)

    private fun clavar(c: DrawController, donde: Pt) {
        c.selectTool(Tool.NUDO)
        c.pointerDown(donde)
        c.pointerUp(donde)
    }

    // ---- Clavar ----

    @Test
    fun `el clavo atraviesa las figuras que pasan por el punto`() {
        val alfileres = clavarEn(triangulo(), emptyList(), Pt(100.0, 100.0), radio = 10.0)
        assertNotNull("no ha clavado nada", alfileres)
        val a = alfileres!!.single()
        assertTrue(a.valido)
        assertEquals(setOf("a", "b"), a.agarres.map { it.elementId }.toSet())
        // Las dos son rayas y tienen un vértice ahí: se agarran por el vértice.
        assertTrue(a.agarres.all { it.indice != null })
    }

    /** Un clavo que sujeta una sola cosa no sujeta nada. */
    @Test
    fun `una figura sola no se puede clavar`() {
        val sola = listOf(raya("a", Pt(0.0, 0.0), Pt(100.0, 0.0)))
        assertNull(clavarEn(sola, emptyList(), Pt(0.0, 0.0), radio = 10.0))
    }

    @Test
    fun `lejos de todo no se clava nada`() {
        assertNull(clavarEn(triangulo(), emptyList(), Pt(400.0, 400.0), radio = 10.0))
    }

    /** Volver a tocar el clavo lo saca. */
    @Test
    fun `tocar un clavo lo quita`() {
        val con = clavarEn(triangulo(), emptyList(), Pt(100.0, 100.0), 10.0)!!
        assertEquals(emptyList<Alfiler>(), clavarEn(triangulo(), con, Pt(100.0, 100.0), 10.0))
    }

    /**
     * **Se clava en el cruce de verdad, no donde cayó el dedo.** Clavarlo «casi»
     * en el cruce uniría las figuras por un punto que no está en ninguna de las
     * dos, y al girar se vería el desajuste.
     */
    @Test
    fun `el clavo se afina hasta la interseccion`() {
        val cruz = listOf(
            raya("h", Pt(0.0, 100.0), Pt(200.0, 100.0)),
            raya("v", Pt(120.0, 0.0), Pt(120.0, 200.0))
        )
        // El dedo cae a cuatro píxeles del cruce, que está en (120, 100).
        val a = clavarEn(cruz, emptyList(), Pt(123.0, 103.0), radio = 14.0)!!.single()
        assertEquals(120.0, a.punto.x, 0.001)
        assertEquals(100.0, a.punto.y, 0.001)
        // Y ahí no hay vértice de nadie: las dos se agarran por su caja.
        assertTrue(a.agarres.all { it.indice == null })
    }

    /** **Dos circunferencias se clavan por donde se cortan.** */
    @Test
    fun `dos circunferencias se clavan en su interseccion`() {
        val dos = listOf(circulo("a", 0.0, 0.0, 100.0), circulo("b", 100.0, 0.0, 100.0))
        // Se cortan en (50, ±86,6).
        val a = clavarEn(dos, emptyList(), Pt(52.0, -84.0), radio = 14.0)!!.single()
        assertEquals(50.0, a.punto.x, 0.6)
        assertEquals(-86.60, a.punto.y, 0.6)
        assertEquals(2, a.agarres.size)
    }

    // ---- No se separan ----

    /**
     * **La prueba que da sentido a todo esto.** Se mueve un vértice del
     * triángulo: el triángulo se deforma —cambia de forma— pero no se abre.
     */
    @Test
    fun `mover un vertice clavado se lleva al otro`() {
        val c = DrawController(Scene(elements = triangulo()))
        clavar(c, Pt(100.0, 100.0))

        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf("a"))
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerMove(Pt(180.0, 60.0))
        c.pointerUp(Pt(180.0, 60.0))

        assertEquals(180.0, puntas(c, "a").last().x, 0.001)
        assertEquals(180.0, puntas(c, "b").first().x, 0.001)
        assertEquals(60.0, puntas(c, "b").first().y, 0.001)
        // El vértice que no estaba clavado se queda donde estaba.
        assertEquals(50.0, puntas(c, "c").first().x, 0.001)
    }

    /**
     * **Con un clavo, arrastrar la figura la hace girar sobre él.**
     *
     * Es la ley del alfiler (ver [Libertad]): un listón clavado por un punto no
     * se puede llevar a otro sitio, solo dar vueltas alrededor del clavo. Antes
     * se trasladaba y arrastraba al vecino en bloque, y así el clavo parecía un
     * pegamento en vez de un eje: no se podía articular nada.
     */
    @Test
    fun `con un clavo la figura gira sobre el en vez de trasladarse`() {
        val c = DrawController(Scene(elements = triangulo()))
        clavar(c, Pt(100.0, 100.0))
        val clavo = c.scene.alfileres.single().punto

        c.selectTool(Tool.SELECTION)
        // Se agarra «a» por su mitad, lejos del clavo, y se arrastra.
        c.pointerDown(Pt(50.0, 100.0))
        c.pointerMove(Pt(50.0, 160.0))
        c.pointerUp(Pt(50.0, 160.0))

        // El punto clavado sigue clavado…
        val punta = puntas(c, "a").last()
        assertEquals("el clavo se ha soltado", clavo.x, punta.x, 0.5)
        assertEquals(clavo.y, punta.y, 0.5)
        // …la raya ha girado…
        assertTrue("no ha girado nada", c.scene.byId("a")!!.angle != 0.0)
        // …y la vecina no se ha movido: es una articulación, no un bloque.
        assertEquals(100.0, puntas(c, "b").first().x, 0.001)
    }

    /** Con dos clavos no se mueve nada: dos puntos fijan la figura entera. */
    @Test
    fun `con dos clavos la figura no se mueve`() {
        val cruz = listOf(
            raya("h", Pt(0.0, 100.0), Pt(200.0, 100.0)),
            raya("v", Pt(0.0, 100.0), Pt(200.0, 100.0)).copy(id = "v2"),
            raya("a", Pt(0.0, 100.0), Pt(0.0, 300.0)),
            raya("b", Pt(200.0, 100.0), Pt(200.0, 300.0))
        )
        val c = DrawController(Scene(elements = cruz))
        clavar(c, Pt(0.0, 100.0))
        clavar(c, Pt(200.0, 100.0))
        assertEquals(2, c.scene.alfileres.size)
        assertEquals(Libertad.FIJA, libertadDe(c.scene.alfileres, "h"))

        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf("h"))
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerMove(Pt(140.0, 180.0))
        c.pointerUp(Pt(140.0, 180.0))

        assertEquals(0.0, puntas(c, "h").first().x, 0.001)
        assertEquals(100.0, puntas(c, "h").first().y, 0.001)
    }

    /** Y sin clavos, la figura se traslada como siempre. */
    @Test
    fun `sin clavos la figura se traslada`() {
        val c = DrawController(Scene(elements = triangulo()))
        c.selectTool(Tool.SELECTION)
        // Sin preseleccionar: el primer toque la coge y empieza a moverla. Con
        // ella ya seleccionada, tocar su mitad agarraría el tirador de añadir
        // punto, que es otro gesto distinto.
        c.pointerDown(Pt(50.0, 100.0))
        c.pointerMove(Pt(50.0, 160.0))
        c.pointerUp(Pt(50.0, 160.0))
        assertEquals(160.0, puntas(c, "a").first().y, 0.001)
    }

    /** Sin clavo, cada uno por su lado — que es el problema de partida. */
    @Test
    fun `sin clavo el triangulo se abre`() {
        val c = DrawController(Scene(elements = triangulo()))
        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf("a"))
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerMove(Pt(180.0, 60.0))
        c.pointerUp(Pt(180.0, 60.0))
        assertEquals("se ha movido sin estar clavada", 100.0, puntas(c, "b").first().x, 0.001)
    }

    /** La tabla de libertades, que es la ley entera del alfiler. */
    @Test
    fun `cuantos clavos decide cuanto se puede mover`() {
        val uno = listOf(Alfiler(Pt(0.0, 0.0), listOf(Agarre("a"), Agarre("b"))))
        val dos = uno + Alfiler(Pt(9.0, 9.0), listOf(Agarre("a"), Agarre("c")))
        assertEquals(Libertad.LIBRE, libertadDe(emptyList(), "a"))
        assertEquals(Libertad.GIRA, libertadDe(uno, "a"))
        assertEquals(Libertad.FIJA, libertadDe(dos, "a"))
        assertEquals(Libertad.GIRA, libertadDe(dos, "c"))
    }

    /**
     * **Dos círculos clavados por su cruce: uno gira alrededor del otro.**
     *
     * Es el caso que pedía la analogía. El clavo es absoluto: sujeta el círculo
     * por ese punto, y como es uno solo, lo único que puede hacer el círculo es
     * girar a su alrededor.
     */
    @Test
    fun `un circulo clavado gira alrededor del clavo`() {
        val dos = listOf(circulo("a", 0.0, 0.0, 100.0), circulo("b", 100.0, 0.0, 100.0))
        val c = DrawController(Scene(elements = dos))
        clavar(c, Pt(52.0, -84.0))
        assertEquals(1, c.scene.alfileres.size)
        val clavo = c.scene.alfileres.single().punto

        c.selectTool(Tool.SELECTION)
        // Se agarra el círculo por su borde —un óvalo sin relleno no se coge por
        // dentro— y se arrastra.
        c.pointerDown(Pt(-100.0, 0.0))
        c.pointerMove(Pt(-90.0, 60.0))
        c.pointerUp(Pt(-90.0, 60.0))

        // El clavo no se ha movido…
        val despues = c.scene.alfileres.single().punto
        assertEquals(clavo.x, despues.x, 0.5)
        assertEquals(clavo.y, despues.y, 0.5)
        // …el círculo movido ha girado sobre él…
        assertTrue("no ha girado", c.scene.byId("a")!!.angle != 0.0)
        // …y sigue pasando por el clavo: no se ha despegado.
        assertEquals(
            "el círculo se ha soltado del clavo",
            100.0,
            hypot(
                despues.x - getElementAbsoluteCoords(c.scene.byId("a")!!).cx,
                despues.y - getElementAbsoluteCoords(c.scene.byId("a")!!).cy
            ),
            1.0
        )
        // …y el otro círculo no se ha movido.
        assertEquals(0.0, c.scene.byId("b")!!.x, 0.001)
    }

    // ---- Girar sobre el clavo ----

    /**
     * **Un listón clavado por un extremo gira sobre el clavo**, no sobre su
     * mitad. Eso es la articulación.
     */
    @Test
    fun `con un clavo se gira sobre el clavo`() {
        val r = raya("a", Pt(100.0, 100.0), Pt(200.0, 100.0))
        val otra = raya("b", Pt(100.0, 100.0), Pt(100.0, 200.0))
        val alfileres = listOf(
            Alfiler(Pt(100.0, 100.0), listOf(Agarre("a", indice = 0), Agarre("b", indice = 0)))
        )
        val girada = girarSobreAlfiler(r, alfileres, Pt(100.0, 0.0))
        // El punto del clavo sigue donde estaba…
        val eje = puntoDelAgarre(girada, alfileres.first().agarres.first())!!
        assertEquals(100.0, eje.x, 0.5)
        assertEquals(100.0, eje.y, 0.5)
        // …y la raya ha girado de verdad.
        assertTrue("no ha girado", girada.angle != 0.0)
    }

    /** Con dos clavos no gira nada: dos puntos fijan la posición entera. */
    @Test
    fun `con dos clavos ya no gira`() {
        val r = raya("a", Pt(0.0, 0.0), Pt(100.0, 0.0))
        val alfileres = listOf(
            Alfiler(Pt(0.0, 0.0), listOf(Agarre("a", indice = 0), Agarre("b", indice = 0))),
            Alfiler(Pt(100.0, 0.0), listOf(Agarre("a", indice = 1), Agarre("b", indice = 1)))
        )
        assertEquals(r, girarSobreAlfiler(r, alfileres, Pt(50.0, 90.0)))
    }

    /** Y sin clavos, gira sobre su centro como toda la vida. */
    @Test
    fun `sin clavos gira como siempre`() {
        val r = raya("a", Pt(0.0, 0.0), Pt(100.0, 0.0))
        val girada = girarSobreAlfiler(r, emptyList(), Pt(50.0, 90.0))
        assertEquals(rotateSingleElement(r, Pt(50.0, 90.0)).angle, girada.angle, 1e-9)
    }

    // ---- El clavo manda sobre el tirador ----

    /**
     * **Donde hay clavo no hay tirador.** Eran dos reglas peleándose por el
     * mismo toque, y ganaba el tirador: acercabas el dedo al clavo, agarrabas la
     * bolita blanca y la estructura se movía entera en vez de articularse.
     */
    @Test
    fun `el tirador desaparece donde hay un clavo`() {
        val r = raya("a", Pt(0.0, 0.0), Pt(100.0, 0.0))
        val conClavo = getSelectionTransformHandles(
            listOf(r), zoom = 1.0, alfileres = listOf(Pt(100.0, 0.0))
        )
        val sinClavo = getSelectionTransformHandles(listOf(r), zoom = 1.0)

        assertTrue("no ha quitado ningún tirador", conClavo.size < sinClavo.size)
        assertTrue(
            "ha quedado un tirador encima del clavo",
            conClavo.none { it.type.esPunto && abs(it.centerX - 100.0) < 1.0 }
        )
        // Y el de la otra punta sigue: solo se quita donde hay clavo.
        assertTrue(conClavo.any { it.type.esPunto && abs(it.centerX) < 1.0 })
    }

    /** Tocar el clavo lo agarra a él, no a la figura. */
    @Test
    fun `tocar el clavo lo arranca y lo lleva`() {
        val c = DrawController(Scene(elements = triangulo()))
        clavar(c, Pt(100.0, 100.0))
        c.selectTool(Tool.SELECTION)

        c.pointerDown(Pt(100.0, 100.0))
        c.pointerMove(Pt(160.0, 140.0))
        c.pointerUp(Pt(160.0, 140.0))

        // El clavo se ha ido, y las dos rayas con él por donde las agarraba.
        assertEquals(160.0, c.scene.alfileres.single().punto.x, 0.001)
        assertEquals(160.0, puntas(c, "a").last().x, 0.001)
        assertEquals(160.0, puntas(c, "b").first().x, 0.001)
        // Y las otras puntas no se han movido: es un clavo, no un arrastre.
        assertEquals(0.0, puntas(c, "a").first().x, 0.001)
    }

    /**
     * **Un clavo en mitad de una raya sujeta de verdad.**
     *
     * Antes era de adorno: al mover una punta, la raya cambiaba de largo y de
     * inclinación y se llevaba el punto clavado por delante. Ahora la raya se
     * recoloca hasta que su punto clavado vuelve al clavo — que es lo que hace
     * una madera clavada cuando le tiras de un extremo: gira sobre el clavo.
     */
    @Test
    fun `un clavo en el medio no se mueve al tirar de una punta`() {
        // Una horizontal cruzada por una vertical en su mitad.
        val cruz = listOf(
            raya("h", Pt(0.0, 100.0), Pt(200.0, 100.0)),
            raya("v", Pt(100.0, 0.0), Pt(100.0, 200.0))
        )
        val c = DrawController(Scene(elements = cruz))
        clavar(c, Pt(100.0, 100.0))
        val clavo = c.scene.alfileres.single().punto

        // Se tira de la punta derecha de la horizontal.
        c.selectTool(Tool.SELECTION)
        c.setSelection(setOf("h"))
        c.pointerDown(Pt(200.0, 100.0))
        c.pointerMove(Pt(240.0, 40.0))
        c.pointerUp(Pt(240.0, 40.0))

        val despues = c.scene.alfileres.single().punto
        assertEquals("el clavo se ha movido", clavo.x, despues.x, 1.0)
        assertEquals(clavo.y, despues.y, 1.0)
    }

    // ---- Que no se quede basura ----

    /** Un clavo que se queda con una sola figura ya no ata nada: se tira. */
    @Test
    fun `los clavos de lo que ya no existe se tiran`() {
        val elementos = triangulo()
        val alfileres = clavarEn(elementos, emptyList(), Pt(100.0, 100.0), 10.0)!!
        val sinB = elementos.map { if (it.id == "b") it.copy(isDeleted = true) else it }
        assertEquals(emptyList<Alfiler>(), refrescarAlfileres(sinB, alfileres))
    }

    /** La cabeza del clavo se pinta donde está de verdad la unión. */
    @Test
    fun `cada clavo da un punto que pintar`() {
        val elementos = triangulo()
        val alfileres = clavarEn(elementos, emptyList(), Pt(100.0, 100.0), 10.0)!!
        val marcas = puntosDeAlfileres(elementos, alfileres)
        assertEquals(1, marcas.size)
        assertEquals(100.0, marcas.first().x, 0.001)
        assertEquals(100.0, marcas.first().y, 0.001)
    }

    @Test
    fun `tocar el vacio con el alfiler avisa`() {
        val c = DrawController(Scene(elements = triangulo()))
        clavar(c, Pt(400.0, 400.0))
        assertTrue(c.nudoSinPareja)
        c.limpiarAvisoNudo()
        assertTrue(!c.nudoSinPareja)
    }

    /** Arrastrando no se clava: la misma regla que salvó al bote del zoom. */
    @Test
    fun `arrastrando no se clava`() {
        val c = DrawController(Scene(elements = triangulo()))
        c.selectTool(Tool.NUDO)
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerMove(Pt(100.0, 180.0))
        c.pointerUp(Pt(100.0, 180.0))
        assertTrue(c.scene.alfileres.isEmpty())
    }
}
