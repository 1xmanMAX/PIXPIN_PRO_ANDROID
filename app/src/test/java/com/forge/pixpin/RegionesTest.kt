package com.forge.pixpin.motor

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El bote de pintura: rellenar el hueco que dejan varias figuras.
 *
 * Lo que hay que comprobar aquí no es que salga una mancha, sino **de qué
 * tamaño sale y hasta dónde llega**, porque eso es exactamente lo que no se ve
 * mirando una captura de pantalla: un relleno que se cuela por una rendija se
 * nota, pero uno que se queda tres píxeles corto o que se come un agujero
 * pequeño parece correcto hasta que alguien mira de cerca.
 *
 * Y sobre todo: que **no rellene lo que no está cerrado**. Es la mitad de la
 * herramienta. Pintar «lo que se pueda» cuando el espacio está abierto teñiría
 * media escena de un botellazo.
 */
class RegionesTest {

    private fun linea(id: String, a: Pt, b: Pt) = Element(
        id = id, type = ElementType.LINE, x = a.x, y = a.y,
        width = abs(b.x - a.x), height = abs(b.y - a.y), seed = 1,
        points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
    )

    private fun caja(id: String, x: Double, y: Double, w: Double, h: Double) = Element(
        id = id, type = ElementType.RECTANGLE, x = x, y = y,
        width = w, height = h, seed = 1
    )

    private fun circulo(id: String, cx: Double, cy: Double, r: Double) = Element(
        id = id, type = ElementType.ELLIPSE, x = cx - r, y = cy - r,
        width = r * 2, height = r * 2, seed = 1
    )

    /** Cuatro rayas sueltas que dibujan un cuadrado de 200. */
    private fun cuadradoDeRayas(hueco: Double = 0.0): List<Element> = listOf(
        linea("arriba", Pt(0.0, 0.0), Pt(200.0, 0.0)),
        linea("derecha", Pt(200.0, 0.0), Pt(200.0, 200.0)),
        linea("abajo", Pt(200.0, 200.0), Pt(0.0, 200.0)),
        linea("izquierda", Pt(0.0, 200.0), Pt(0.0, hueco))
    )

    /** Superficie de la mancha: el contorno menos sus agujeros. */
    private fun superficie(r: Region): Double =
        abs(areaDe(r.contorno)) - r.huecos.sumOf { abs(areaDe(it)) }

    // ---- Encontrar el hueco ----

    /**
     * Cuatro rayas sueltas que se tocan encierran un espacio, aunque ninguna de
     * las cuatro sea una figura cerrada. **Es la razón de ser de la
     * herramienta**: ese cuadrado no es de nadie.
     */
    @Test
    fun `cuatro rayas que se tocan encierran un espacio`() {
        val r = regionEn(cuadradoDeRayas(), Pt(100.0, 100.0))
        assertNotNull("no encuentra el cuadrado", r)
        assertEquals(40_000.0, superficie(r!!), 40_000.0 * 0.03)
        assertTrue("no debería tener agujeros", r.huecos.isEmpty())
    }

    /**
     * Con una rendija abierta **no se rellena nada**. Lo que se escapa por ahí
     * es el lienzo entero, y teñirlo asusta más de lo que ayuda.
     */
    @Test
    fun `si el espacio no esta cerrado no sale region`() {
        assertNull(regionEn(cuadradoDeRayas(hueco = 20.0), Pt(100.0, 100.0)))
    }

    /**
     * Un hueco más estrecho que una celda se da por cerrado, **y eso es lo que
     * se quiere**: dibujando con el dedo, dos trazos que «se tocan» casi nunca
     * se tocan de verdad, y sin esta tolerancia el bote no funcionaría casi
     * nunca sin que nadie entendiera por qué.
     */
    @Test
    fun `una rendija diminuta se da por cerrada`() {
        // La rejilla parte el lado mayor (200) en 320, así que la celda mide
        // 0,625: medio píxel de rendija no llega ni a eso.
        val r = regionEn(cuadradoDeRayas(hueco = 0.5), Pt(100.0, 100.0))
        assertNotNull("una rendija de medio píxel debería darse por cerrada", r)
    }

    @Test
    fun `tocando fuera de todo no hay nada que rellenar`() {
        assertNull(regionEn(cuadradoDeRayas(), Pt(500.0, 500.0)))
    }

    @Test
    fun `sin nada dibujado no hay nada que rellenar`() {
        assertNull(regionEn(emptyList(), Pt(0.0, 0.0)))
    }

    // ---- Los agujeros ----

    /**
     * Un círculo dentro de un cuadrado deja un anillo, y **el anillo tiene
     * agujero**. Sin esto el bote pintaría también el círculo: justo el trozo
     * que no se ha tocado.
     */
    @Test
    fun `el hueco entre dos figuras deja el de dentro sin pintar`() {
        val escena = listOf(caja("r", 0.0, 0.0, 300.0, 300.0), circulo("c", 150.0, 150.0, 60.0))
        val r = regionEn(escena, Pt(20.0, 150.0))
        assertNotNull("no encuentra el anillo", r)
        assertEquals("debería tener un agujero", 1, r!!.huecos.size)

        val areaCirculo = Math.PI * 60 * 60
        assertEquals(areaCirculo, abs(areaDe(r.huecos.first())), areaCirculo * 0.10)
        assertEquals(90_000.0 - areaCirculo, superficie(r), 90_000.0 * 0.03)
    }

    /** Y tocando dentro del círculo se rellena el círculo, no el anillo. */
    @Test
    fun `tocando dentro del circulo se rellena el circulo`() {
        val escena = listOf(caja("r", 0.0, 0.0, 300.0, 300.0), circulo("c", 150.0, 150.0, 60.0))
        val r = regionEn(escena, Pt(150.0, 150.0))
        assertNotNull(r)
        val areaCirculo = Math.PI * 60 * 60
        assertEquals(areaCirculo, superficie(r!!), areaCirculo * 0.10)
        assertTrue(r.huecos.isEmpty())
    }

    /**
     * Tocando **encima de un trazo** se rellena a un lado, no se falla.
     *
     * El dedo tapa medio centímetro: exigir que el toque caiga en hueco libre
     * haría fallar la herramienta justo cuando se apunta con cuidado al borde.
     */
    @Test
    fun `tocando encima del trazo se rellena lo de al lado`() {
        val r = regionEn(cuadradoDeRayas(), Pt(200.0, 100.0))
        assertNotNull("tocar el borde debería rellenar a un lado", r)
    }

    // ---- Lo que se guarda ----

    /**
     * El relleno guarda **el contorno que se encontró**, no las figuras que lo
     * encerraban: lo que se rellenó se queda relleno aunque luego se muevan.
     */
    @Test
    fun `el elemento guarda el contorno y sus agujeros en relativo`() {
        val escena = listOf(caja("r", 0.0, 0.0, 300.0, 300.0), circulo("c", 150.0, 150.0, 60.0))
        val region = regionEn(escena, Pt(20.0, 150.0))!!
        val e = nuevaRegion(region, ItemStyle(backgroundColor = "#ffc9c9"))

        assertEquals(ElementType.REGION, e.type)
        assertEquals("#ffc9c9", e.backgroundColor)
        assertEquals(1, e.huecos!!.size)
        // Los puntos van relativos a (x, y), como en cualquier elemento de
        // puntos: al mover el relleno se mueve todo con él.
        val vueltos = anillosDeRegion(e)
        assertEquals(region.contorno.size, vueltos.first().size)
        assertEquals(region.contorno.first().x, vueltos.first().first().x, 0.001)
        assertEquals(region.contorno.first().y, vueltos.first().first().y, 0.001)
    }

    /**
     * Sin fondo elegido se usa el color del trazo. El fondo nace transparente,
     * así que sin esta salida el primer botellazo de todo el mundo no pintaría
     * nada y la herramienta parecería rota.
     */
    @Test
    fun `sin fondo elegido se pinta del color del trazo`() {
        val region = regionEn(cuadradoDeRayas(), Pt(100.0, 100.0))!!
        val e = nuevaRegion(region, ItemStyle(strokeColor = "#e03131"))
        assertEquals("#e03131", e.backgroundColor)
    }

    /** El relleno se coge por la mancha y **no por sus agujeros**. */
    @Test
    fun `el agujero no roba el toque`() {
        val escena = listOf(caja("r", 0.0, 0.0, 300.0, 300.0), circulo("c", 150.0, 150.0, 60.0))
        val e = nuevaRegion(regionEn(escena, Pt(20.0, 150.0))!!, ItemStyle())
        assertTrue("la mancha no se coge", puntoEnRegion(e, Pt(20.0, 150.0)))
        assertTrue("el agujero se traga el toque", !puntoEnRegion(e, Pt(150.0, 150.0)))
    }

    // ---- Dónde se mete ----

    /**
     * Debajo de lo que lo encierra: encima le comería medio grosor a los trazos
     * que forman el hueco y el dibujo quedaría desigual sin saber por qué.
     */
    @Test
    fun `el relleno se mete debajo de lo que lo encierra`() {
        val paredes = cuadradoDeRayas()
        val relleno = nuevaRegion(regionEn(paredes, Pt(100.0, 100.0))!!, ItemStyle())
        val orden = conRellenoDebajo(paredes, relleno)
        assertEquals(relleno.id, orden.first().id)
        assertEquals(paredes.size + 1, orden.size)
    }

    /**
     * Pero **por encima del soporte**: la foto sobre la que se anota va al
     * fondo y bloqueada, y meter el relleno debajo de ella lo escondería.
     */
    @Test
    fun `el relleno queda por encima de la foto del fondo`() {
        val foto = Element(
            id = "foto", type = ElementType.IMAGE, x = 0.0, y = 0.0,
            width = 400.0, height = 400.0, seed = 1, fileId = "f", locked = true
        )
        val escena = listOf(foto) + cuadradoDeRayas()
        val relleno = nuevaRegion(regionEn(escena, Pt(100.0, 100.0))!!, ItemStyle())
        val orden = conRellenoDebajo(escena, relleno)
        assertEquals("foto", orden.first().id)
        assertEquals(relleno.id, orden[1].id)
    }

    /** La foto no hace de pared: si contase, todo estaría siempre «cerrado». */
    @Test
    fun `la foto del fondo no encierra nada`() {
        val foto = Element(
            id = "foto", type = ElementType.IMAGE, x = 0.0, y = 0.0,
            width = 400.0, height = 400.0, seed = 1, fileId = "f"
        )
        assertNull(regionEn(listOf(foto) + cuadradoDeRayas(hueco = 20.0), Pt(100.0, 100.0)))
    }

    // ---- El rayado con agujeros ----

    /**
     * El rayado **salta los agujeros**. Es lo que hace que un relleno a rayas
     * de un anillo se vea como un anillo y no como un disco rayado.
     */
    @Test
    fun `el rayado no cruza los agujeros`() {
        val fuera = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0))
        val dentro = listOf(Pt(40.0, 40.0), Pt(60.0, 40.0), Pt(60.0, 60.0), Pt(40.0, 60.0))

        // Rayas horizontales (el barrido suma 90°, así que -90 las tumba).
        val rayas = anillosHachureLines(listOf(fuera, dentro), 5.0, -90.0, 1.0)
        assertTrue("no raya nada", rayas.isNotEmpty())

        val queCruzan = rayas.count { (a, b) ->
            val y = (a.y + b.y) / 2
            y > 41 && y < 59 && minOf(a.x, b.x) < 45 && maxOf(a.x, b.x) > 55
        }
        assertEquals("alguna raya atraviesa el agujero", 0, queCruzan)

        // Y a la altura del agujero se raya a los dos lados, no en uno solo.
        val aLaAltura = rayas.filter { (a, _) -> a.y > 45 && a.y < 55 }
        assertTrue("debería rayar a los dos lados del agujero", aLaAltura.size >= 2)
    }

    // ---- La herramienta ----

    private fun conElBote(escena: List<Element>, donde: Pt): DrawController {
        val c = DrawController(Scene(elements = escena))
        c.selectTool(Tool.RELLENO)
        c.pointerDown(donde)
        c.pointerUp(donde)
        return c
    }

    /** Un toque = un relleno. No se arrastra: lo que manda es hasta dónde llega el hueco. */
    @Test
    fun `el bote deja un relleno en la escena`() {
        val c = conElBote(cuadradoDeRayas(), Pt(100.0, 100.0))
        assertEquals(1, c.scene.visible.count { it.type == ElementType.REGION })
        assertTrue("no debería avisar de nada", !c.rellenoSinCerrar)
    }

    /**
     * **Rellenar dos veces seguidas sigue funcionando igual.**
     *
     * No lo hacía: cada mancha quedaba en la escena como una pared nueva, un
     * pelo desplazada de los trazos de verdad —sale de una rejilla, con su celda
     * de margen—, así que la siguiente chocaba contra ella y se quedaba corta.
     * Las primeras veces iba de maravilla y luego empezaba a rellenar a trozos:
     * cuantos más rellenos, peor.
     */
    @Test
    fun `rellenar varias veces no degrada el resultado`() {
        var escena = cuadradoDeRayas()
        var superficie = 0.0
        repeat(4) { vuelta ->
            val region = regionEn(escena, Pt(100.0, 100.0))
            assertNotNull("el relleno número ${vuelta + 1} no encuentra el hueco", region)
            val area = superficie(region!!)
            if (vuelta == 0) superficie = area
            assertEquals(
                "el relleno número ${vuelta + 1} sale distinto del primero",
                superficie, area, superficie * 0.02
            )
            escena = conRellenoDebajo(escena, nuevaRegion(region, ItemStyle()), Pt(100.0, 100.0))
        }
        // Y no se han apilado cuatro manchas: volver a dar sobre el mismo hueco
        // repinta, no acumula capas.
        assertEquals(1, escena.count { it.isRegion })
    }

    /** Volver a dar con otro color cambia el color, no añade otra mancha. */
    @Test
    fun `repintar el mismo hueco sustituye la mancha`() {
        val paredes = cuadradoDeRayas()
        val roja = nuevaRegion(regionEn(paredes, Pt(100.0, 100.0))!!, ItemStyle(strokeColor = "#e03131"))
        val conRoja = conRellenoDebajo(paredes, roja, Pt(100.0, 100.0))

        val azul = nuevaRegion(regionEn(conRoja, Pt(100.0, 100.0))!!, ItemStyle(strokeColor = "#1971c2"))
        val conAzul = conRellenoDebajo(conRoja, azul, Pt(100.0, 100.0))

        assertEquals(1, conAzul.count { it.isRegion })
        assertEquals("#1971c2", conAzul.first { it.isRegion }.backgroundColor)
    }

    /** Un hueco distinto sí es otra mancha. */
    @Test
    fun `rellenar otro hueco no borra el anterior`() {
        val escena = listOf(caja("r", 0.0, 0.0, 300.0, 300.0), circulo("c", 150.0, 150.0, 60.0))
        val anillo = nuevaRegion(regionEn(escena, Pt(20.0, 150.0))!!, ItemStyle())
        val conAnillo = conRellenoDebajo(escena, anillo, Pt(20.0, 150.0))

        val centro = nuevaRegion(regionEn(conAnillo, Pt(150.0, 150.0))!!, ItemStyle())
        val conDos = conRellenoDebajo(conAnillo, centro, Pt(150.0, 150.0))
        assertEquals(2, conDos.count { it.isRegion })
    }

    /**
     * **El bote no se dispara al mover el lienzo ni al hacer zoom.**
     *
     * Actuaba al bajar el dedo, y el primer dedo de un pellizco baja igual que
     * un toque: la escena se llenaba de botellazos que nadie había pedido.
     */
    @Test
    fun `arrastrando el dedo no se rellena`() {
        val c = DrawController(Scene(elements = cuadradoDeRayas()))
        c.selectTool(Tool.RELLENO)
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerMove(Pt(100.0, 160.0))
        c.pointerUp(Pt(100.0, 160.0))

        assertTrue(c.scene.visible.none { it.isRegion })
        assertTrue("no debería haber tocado la escena", !c.canUndo)
    }

    /** Y con el segundo dedo apoyado —el gesto se cancela— tampoco queda nada. */
    @Test
    fun `un gesto cancelado no deja relleno`() {
        val c = DrawController(Scene(elements = cuadradoDeRayas()))
        c.selectTool(Tool.RELLENO)
        c.pointerDown(Pt(100.0, 100.0))
        c.cancel()

        assertTrue(c.scene.visible.none { it.isRegion })
        assertTrue(!c.canUndo)
    }

    /** Y si no hay hueco cerrado: ni relleno, ni silencio. */
    @Test
    fun `sobre un espacio abierto avisa y no dibuja`() {
        val c = conElBote(cuadradoDeRayas(hueco = 20.0), Pt(100.0, 100.0))
        assertTrue(c.scene.visible.none { it.type == ElementType.REGION })
        assertTrue("debería avisar de que no está cerrado", c.rellenoSinCerrar)
    }

    /** Un botellazo se deshace como cualquier otra cosa, de una vez. */
    @Test
    fun `un relleno se deshace entero`() {
        val c = conElBote(cuadradoDeRayas(), Pt(100.0, 100.0))
        assertTrue(c.canUndo)
        c.undo()
        assertTrue(c.scene.visible.none { it.type == ElementType.REGION })
        assertEquals(4, c.scene.visible.size)
    }

    // ---- El lápiz que se cierra ----

    /** Un garabato con [puntos] y el fondo puesto. */
    private fun garabato(puntos: List<Pt>, fondo: String = "#ffc9c9") = Element(
        id = "g", type = ElementType.FREEDRAW, x = 0.0, y = 0.0,
        width = 100.0, height = 100.0, seed = 1,
        points = puntos, backgroundColor = fondo
    )

    /** Un lazo cuadrado que vuelve a su punto de partida. */
    private val lazo = listOf(
        Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0), Pt(0.0, 0.0)
    )

    /**
     * Un lápiz que se cierra sobre sí mismo **se rellena**, como en el original.
     *
     * El modelo lo admitía desde el primer día y no se pintaba: elegir un fondo
     * para un garabato cerrado no hacía absolutamente nada.
     */
    @Test
    fun `un lapiz cerrado con fondo se rellena`() {
        assertTrue(rellenaSuLazo(garabato(lazo)))
    }

    /** Uno abierto no: el color se escaparía por donde el trazo no ha cerrado. */
    @Test
    fun `un lapiz abierto no se rellena`() {
        val abierto = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0))
        assertTrue(!rellenaSuLazo(garabato(abierto)))
    }

    @Test
    fun `sin fondo elegido no hay nada que rellenar`() {
        assertTrue(!rellenaSuLazo(garabato(lazo, fondo = Element.TRANSPARENT)))
    }

    /**
     * El camino se simplifica antes de rellenar (`simplify(points, 0.75)`).
     *
     * El lápiz entrega cientos de puntos casi pegados y el barrido del rayado es
     * cuadrático en el número de aristas: sin simplificar, un garabato relleno
     * se comía el fotograma entero.
     */
    @Test
    fun `el camino del lapiz se simplifica antes de rellenar`() {
        val denso = (0..200).map { Pt(it * 0.5, 0.0) }
        assertEquals(2, douglasPeucker(denso, FREEDRAW_FILL_TOLERANCE).size)
        // Pero lo que es forma de verdad no se pierde: medio píxel sí, diez no.
        val conPico = listOf(Pt(0.0, 0.0), Pt(50.0, 10.0), Pt(100.0, 0.0))
        assertEquals(3, douglasPeucker(conPico, FREEDRAW_FILL_TOLERANCE).size)
    }

    /** A tiralíneas: rectas de verdad, sin pasarlas por el generador de ruido. */
    @Test
    fun `el relleno de lineas sale recto`() {
        val cuadrado = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0))
        val ops = Rough(RoughOptions(seed = 7, fillStyle = FillStyle.LINEAS, hachureGap = 10.0))
            .fillPolygon(cuadrado)
        assertTrue("no rellena", ops.isNotEmpty())
        assertTrue("una recta no lleva curvas", ops.none { it is Op.CurveTo })
    }
}
