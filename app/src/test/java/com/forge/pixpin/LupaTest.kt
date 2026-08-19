package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lupa: **dos cajas atadas por una cuenta**.
 *
 * Lo que se comprueba aquí es lo que la hace no mentir: que lo que se ve dentro
 * del cristal esté siempre a la escala que dice el aumento, y que apartar el
 * cristal **no** arrastre lo que se está mirando —que es justo para lo que sirve
 * una lupa en una lámina—.
 */
class LupaTest {

    private fun lupa(
        x: Double = 0.0, y: Double = 0.0,
        w: Double = 200.0, h: Double = 100.0,
        aumento: Double? = 4.0,
        foco: Pt? = null
    ) = Element(
        id = "l", type = ElementType.LUPA, x = x, y = y,
        width = w, height = h, seed = 1, aumento = aumento, foco = foco
    )

    /** Sin foco puesto mira **a lo que tiene debajo**, como una lupa de verdad. */
    @Test
    fun `una lupa recien puesta mira a su propio centro`() {
        val e = lupa()
        assertEquals(100.0, focoDe(e).x, 0.001)
        assertEquals(50.0, focoDe(e).y, 0.001)
    }

    /**
     * Lo que se recoge es el cristal **dividido por el aumento**.
     *
     * De aquí sale la única propiedad que importa: lo que entra llena
     * exactamente lo que sale, así que agrandar cuatro veces es agrandar cuatro
     * veces y no «algo más grande».
     */
    @Test
    fun `la region es el cristal dividido por el aumento`() {
        val r = regionDeLaLupa(lupa(w = 200.0, h = 100.0, aumento = 4.0))
        assertEquals(50.0, r.width, 0.001)
        assertEquals(25.0, r.height, 0.001)
        // Y centrada en el foco, que aquí es el centro del cristal.
        assertEquals(100.0, r.midX, 0.001)
        assertEquals(50.0, r.midY, 0.001)
    }

    /**
     * **Estirar el cristal agranda más, no mira más.**
     *
     * Es lo que cambió: antes la zona mirada salía del cristal partido por el
     * aumento, así que agrandar la ventana agrandaba también lo mirado y por
     * mucho que se estirara se seguía viendo lo mismo de grande. Ahora la zona
     * se guarda: crece la ventana y sube el aumento.
     */
    @Test
    fun `estirar el cristal sube el aumento y no toca el foco`() {
        val base = lupa(w = 200.0, h = 100.0).copy(focoAncho = 50.0, focoAlto = 25.0)
        assertEquals(4.0, aumentoDe(base), 0.001)

        val estirada = base.copy(width = 400.0, height = 200.0)
        assertEquals("la zona mirada ha cambiado", 50.0, regionDeLaLupa(estirada).width, 0.001)
        assertEquals("el aumento no ha subido", 8.0, aumentoDe(estirada), 0.001)
    }

    /** Y el deslizador hace lo contrario: mueve la ventana, no la zona. */
    @Test
    fun `el deslizador agranda la ventana y deja la zona`() {
        val base = lupa(w = 200.0, h = 100.0).copy(focoAncho = 50.0, focoAlto = 25.0)
        val centro = getElementAbsoluteCoords(base).let { Pt(it.cx, it.cy) }

        val x6 = conAumento(base, 6.0)
        assertEquals(300.0, x6.width, 0.001)
        assertEquals("la zona mirada ha cambiado", 50.0, regionDeLaLupa(x6).width, 0.001)
        // Y crece desde su centro: subir el zoom no la desplaza.
        assertEquals(centro.x, getElementAbsoluteCoords(x6).cx, 0.001)
        assertEquals(centro.y, getElementAbsoluteCoords(x6).cy, 0.001)
    }

    /**
     * **Apartar el cristal deja el foco donde estaba.**
     *
     * Es el corazón de la herramienta. Si el foco fuera detrás, una lupa sería
     * un cuadro que siempre se enseña a sí mismo y no habría forma de sacar el
     * detalle a un lado de la lámina.
     */
    @Test
    fun `mover la lupa no mueve lo que mira`() {
        val e = lupa()
        val antes = focoDe(e)
        val movida = dragElements(listOf(e), 500.0, 300.0).first()

        assertEquals("el foco se ha ido detrás", antes.x, focoDe(movida).x, 0.001)
        assertEquals("el foco se ha ido detrás", antes.y, focoDe(movida).y, 0.001)
        assertEquals("el cristal no se ha movido", 500.0, movida.x, 0.001)
    }

    /** Y el foco se guarda al moverla: si no, volvería a atarse al cristal. */
    @Test
    fun `al moverla el foco queda fijado`() {
        val movida = dragElements(listOf(lupa()), 500.0, 300.0).first()
        assertNotNull("el foco se ha quedado suelto", movida.foco)
    }

    /** El foco se apunta a otro sitio sin tocar el cristal. */
    @Test
    fun `apuntar el foco no mueve el cristal`() {
        val e = lupa()
        val apuntada = conFoco(e, Pt(900.0, 700.0))
        assertEquals(0.0, apuntada.x, 0.001)
        assertEquals(0.0, apuntada.y, 0.001)
        assertEquals(900.0, regionDeLaLupa(apuntada).midX, 0.001)
    }

    /** Encima de lo que mira no hay nada que señalar: la guía sobra. */
    @Test
    fun `sin apartarla no hay guia`() {
        assertNull(flechaDeLaLupa(lupa()))
    }

    /**
     * Apartada, la guía va **de borde a borde**, nunca por encima del detalle.
     *
     * Con el cristal cuadrado, que es donde el borde es el de la caja. La
     * versión redonda se comprueba aparte, contra el óvalo.
     */
    @Test
    fun `la guia sale de los bordes`() {
        val e = conFoco(lupa(x = 0.0, y = 0.0, w = 200.0, h = 100.0), Pt(0.0, 600.0))
            .copy(lupaRedonda = false)
        val (desde, hasta) = flechaDeLaLupa(e)!!
        val region = regionDeLaLupa(e)
        val cristal = getElementAbsoluteCoords(e)

        // El extremo de arriba toca el borde del cristal, no su centro.
        assertTrue("la guía nace dentro del cristal", hasta.y >= cristal.y2 - 0.001)
        // Y el otro, el borde del recuadro del foco.
        assertTrue("la guía se mete dentro del foco", desde.y <= region.y2 + 0.001)
        assertTrue(desde.y >= region.y1 - 0.001)
    }

    /** Apagada no se dibuja, aunque esté apartada. */
    @Test
    fun `la guia se puede quitar`() {
        val e = conFoco(lupa(), Pt(0.0, 600.0)).copy(guia = GuiaDeLupa.NINGUNA)
        assertNull(flechaDeLaLupa(e))
    }

    /** Un aumento absurdo se recorta: ni menos que nada ni el grano de pantalla. */
    @Test
    fun `el aumento se queda en lo razonable`() {
        assertEquals(AUMENTO_MAXIMO, aumentoDe(lupa(aumento = 400.0)), 0.001)
        assertEquals(AUMENTO_MINIMO, aumentoDe(lupa(aumento = 0.01)), 0.001)
        assertEquals(AUMENTO_POR_DEFECTO, aumentoDe(lupa(aumento = null)), 0.001)
    }

    /** Un toque suelto deja una lupa usable, no uba de dos píxeles. */
    @Test
    fun `una lupa diminuta crece hasta poder verse`() {
        val e = lupaNueva(lupa(x = 100.0, y = 100.0, w = 3.0, h = 2.0))
        assertEquals(LADO_MINIMO_DE_LUPA, e.width, 0.001)
        assertEquals(LADO_MINIMO_DE_LUPA, e.height, 0.001)
        // Y crece **desde su centro**: nace donde se tocó, no desplazada.
        assertEquals(101.5, getElementAbsoluteCoords(e).cx, 0.001)
    }

    /**
     * **El foco tiene la forma del cristal.**
     *
     * Con el cristal redondo lo que entra es un óvalo —el recorte se hace ahí—
     * así que señalarlo con un recuadro sería marcar una zona que no es la que
     * se ve, y hace dudar de cuál de las dos manda.
     */
    @Test
    fun `el foco copia la forma del cristal`() {
        assertEquals(4, puntosDelFoco(lupa().copy(lupaRedonda = false)).size)
        assertEquals(LADOS_DEL_ARO, puntosDelFoco(lupa()).size)
    }

    /** Y se pica como se ve: en un foco redondo, las esquinas no responden. */
    @Test
    fun `un foco redondo no se pica por las esquinas`() {
        val e = lupa(w = 200.0, h = 200.0, aumento = 4.0)
        val r = regionDeLaLupa(e)
        // La esquina de la caja, justo fuera del óvalo.
        assertTrue(!tocaElFoco(e, Pt(r.x1, r.y1), 0.0))
        // Y el centro, dentro de los dos.
        assertTrue(tocaElFoco(e, Pt(r.midX, r.midY), 0.0))
        // En cuadrado la esquina sí responde.
        assertTrue(tocaElFoco(e.copy(lupaRedonda = false), Pt(r.x1, r.y1), 0.0))
    }

    /** La guía sale del borde del óvalo, no del de su caja. */
    @Test
    fun `en redondo la guia sale del ovalo`() {
        val e = conFoco(lupa(w = 200.0, h = 200.0, aumento = 4.0), Pt(0.0, 600.0))
        val (desde, _) = flechaDeLaLupa(e)!!
        val r = regionDeLaLupa(e)
        val rx = r.width / 2
        val dx = (desde.x - r.midX) / rx
        val dy = (desde.y - r.midY) / (r.height / 2)
        assertEquals("no está sobre el óvalo", 1.0, dx * dx + dy * dy, 0.01)
    }

    /**
     * **Girar la lupa no descoloca su foco.**
     *
     * El giro es del cristal; lo que se mira está en el dibujo y ahí se queda.
     * Era el fallo que dejaba el foco inalcanzable: se pintaba girado con el
     * cristal, así que lo que se veía y lo que se picaba estaban en dos sitios.
     */
    @Test
    fun `girar la lupa deja el foco donde esta`() {
        val e = conFoco(lupa(), Pt(400.0, 400.0)).copy(angle = Math.toRadians(35.0))
        assertEquals(400.0, focoDe(e).x, 0.001)
        assertEquals(400.0, regionDeLaLupa(e).midY, 0.001)
    }

    /** Y moverla girada tampoco se lo lleva. */
    @Test
    fun `mover una lupa girada no mueve su foco`() {
        val e = conFoco(lupa(), Pt(400.0, 400.0)).copy(angle = Math.toRadians(35.0))
        val movida = dragElements(listOf(e), 120.0, -80.0).first()
        assertEquals(400.0, focoDe(movida).x, 0.001)
        assertEquals(400.0, focoDe(movida).y, 0.001)
    }

    // ---- La varita: cualquier figura cerrada se convierte -----------------

    private fun circulo() = Element(
        id = "c", type = ElementType.ELLIPSE, x = 10.0, y = 20.0,
        width = 100.0, height = 60.0, seed = 1
    )

    /** Un círculo se convierte, y la lupa **ocupa su mismo sitio**. */
    @Test
    fun `una figura cerrada se convierte en lupa`() {
        val l = lupaDesdeFigura(circulo(), ItemStyle(aumento = 2.0), "l2", 7)!!
        assertEquals(ElementType.LUPA, l.type)
        // **Nace ya al doble y centrada en el mismo sitio**: se ve al momento
        // qué hace, y lo único que queda es apartarla.
        assertEquals(200.0, l.width, 0.001)
        assertEquals(2.0, aumentoDe(l), 0.001)
        assertEquals(60.0, getElementAbsoluteCoords(l).cx, 0.001)
        // Y mira a la figura que se ha tocado, con su tamaño de verdad.
        assertEquals(60.0, focoDe(l).x, 0.001)
        assertEquals(50.0, focoDe(l).y, 0.001)
        assertEquals(100.0, regionDeLaLupa(l).width, 0.001)
    }

    /**
     * El foco sale de la misma varita y nace **con marco alrededor**.
     *
     * Ese anillo entre el marco y la figura es lo que se oscurece: con el marco
     * pegado a la figura no habría nada que pintar, y con la pantalla entera se
     * apagaría el plano completo para señalar un detalle.
     */
    @Test
    fun `una figura cerrada se convierte en foco con marco`() {
        val f = focoDesdeFigura(circulo(), ItemStyle(), "f2", 7)!!
        assertEquals(ElementType.SPOTLIGHT, f.type)
        assertTrue("el marco no rodea a la figura", f.width > 100.0)
        assertEquals("no está centrado en ella", 60.0, getElementAbsoluteCoords(f).cx, 0.001)
        assertTrue("no ha copiado la forma", (f.forma?.size ?: 0) >= 3)
        // Y lo que se marcó sigue midiendo lo suyo: es el hueco.
        assertEquals(100.0, regionDeLaLupa(f).width, 0.001)
    }

    /** Estirar el marco de un foco **no toca el hueco**. */
    @Test
    fun `estirar el marco del foco deja el hueco`() {
        val f = focoDesdeFigura(circulo(), ItemStyle(), "f2", 7)!!
        val estirado = f.copy(width = f.width * 3, height = f.height * 3)
        assertEquals(100.0, regionDeLaLupa(estirado).width, 0.001)
    }

    /**
     * Con el punto, la raya **llega al punto**: no se para en el contorno.
     *
     * En este modo el contorno no se dibuja, así que ese borde no existe para
     * quien mira: una raya que se parase ahí dejaría el punto flotando suelto.
     */
    @Test
    fun `con el punto la raya llega al centro`() {
        val e = conFoco(lupa(w = 200.0, h = 200.0), Pt(0.0, 800.0))
            .copy(guia = GuiaDeLupa.PUNTO)
        val r = regionDeLaLupa(e)
        val (desde, _) = lineasDeLaGuia(e).first()
        assertEquals(r.midX, desde.x, 0.001)
        assertEquals(r.midY, desde.y, 0.001)
    }

    /** Con la flecha se para en el borde, que ahí sí se ve. */
    @Test
    fun `con la flecha la raya se para en el contorno`() {
        val e = conFoco(lupa(w = 200.0, h = 200.0), Pt(0.0, 800.0))
            .copy(guia = GuiaDeLupa.FLECHA)
        val r = regionDeLaLupa(e)
        val (desde, _) = lineasDeLaGuia(e).first()
        // Sale del centro hacia el cristal: lo que se comprueba es que **no
        // nace en el centro**, sino en el borde por el que apunta.
        val fuera = kotlin.math.hypot(desde.x - r.midX, desde.y - r.midY)
        assertTrue("no se ha parado en el borde", fuera > 1.0)
    }

    /** El marco del foco nace **derecho**, aunque la figura esté torcida. */
    @Test
    fun `el foco no hereda el giro de la figura`() {
        val torcido = Element(
            id = "c", type = ElementType.RECTANGLE, x = 0.0, y = 0.0,
            width = 100.0, height = 40.0, seed = 1, angle = Math.toRadians(30.0)
        )
        val f = focoDesdeFigura(torcido, ItemStyle(), "f2", 7)!!
        assertEquals("el marco ha salido torcido", 0.0, f.angle, 0.0001)
        // Pero la figura de dentro conserva su inclinación: su contorno no es
        // un rectángulo derecho, así que toca los cuatro lados de su caja.
        val forma = f.forma!!
        assertTrue(forma.any { it.x < 0.05 } && forma.any { it.x > 0.95 })
        assertTrue(forma.any { it.y < 0.05 } && forma.any { it.y > 0.95 })
    }

    /** La zona del foco se ajusta **dentro de su marco**, sin moverlo. */
    @Test
    fun `la zona del foco cambia sin tocar el marco`() {
        val f = focoDesdeFigura(circulo(), ItemStyle(), "f2", 7)!!
        val antes = getElementAbsoluteCoords(f)
        val medio = conZona(f, 0.5)

        assertEquals("el marco se ha movido", antes.x1, getElementAbsoluteCoords(medio).x1, 0.001)
        assertEquals("el marco ha cambiado", antes.x2, getElementAbsoluteCoords(medio).x2, 0.001)
        assertEquals(antes.x2 - antes.x1, regionDeLaLupa(medio).width * 2, 0.001)
        assertEquals(0.5, zonaDe(medio), 0.001)
    }

    /** Y no se sale de lo que sirve: pegada al marco no queda anillo. */
    @Test
    fun `la zona no llega a comerse el marco`() {
        val f = focoDesdeFigura(circulo(), ItemStyle(), "f2", 7)!!
        assertEquals(ZONA_MAXIMA, zonaDe(conZona(f, 3.0)), 0.001)
        assertEquals(ZONA_MINIMA, zonaDe(conZona(f, 0.0)), 0.001)
    }

    /** Los cuatro modos: dos enseñan la zona y dos no. */
    @Test
    fun `solo la flecha y el cono dibujan la zona`() {
        assertTrue(GuiaDeLupa.FLECHA.dibujaLaZona)
        assertTrue(GuiaDeLupa.DOS_LINEAS.dibujaLaZona)
        assertTrue(!GuiaDeLupa.PUNTO.dibujaLaZona)
        assertTrue(!GuiaDeLupa.NINGUNA.dibujaLaZona)
    }

    /** Y su oscurecimiento se queda entre lo que sirve. */
    @Test
    fun `el oscurecimiento no se sale de lo razonable`() {
        assertEquals(OSCURECER_MAXIMO, oscurecimientoDe(lupa().copy(oscurecer = 300)))
        assertEquals(OSCURECER_MINIMO, oscurecimientoDe(lupa().copy(oscurecer = 0)))
        assertEquals(OSCURECER_POR_DEFECTO, oscurecimientoDe(lupa()))
    }

    /** Y se queda con **su contorno**, no con un óvalo cualquiera. */
    @Test
    fun `la lupa hereda la forma de la figura`() {
        val l = lupaDesdeFigura(circulo(), ItemStyle(aumento = 1.0), "l2", 7)!!
        val forma = l.forma!!
        assertTrue("no ha copiado el contorno", forma.size >= 3)
        // Guardado en proporción: todo dentro de la caja.
        assertTrue(forma.all { it.x >= -0.001 && it.x <= 1.001 })
        assertTrue(forma.all { it.y >= -0.001 && it.y <= 1.001 })
        // Y al pintarla vuelve a caer sobre la figura de origen.
        val puntos = puntosDelCristal(l, getElementAbsoluteCoords(l))
        assertTrue(puntos.all { it.x >= 9.9 && it.x <= 110.1 })
    }

    /** Lo que no encierra nada no vale: una lupa es un recorte. */
    @Test
    fun `una raya no sirve de lupa`() {
        val raya = Element(
            id = "r", type = ElementType.LINE, x = 0.0, y = 0.0,
            width = 100.0, height = 0.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0))
        )
        assertTrue(!sirveDeLupa(raya))
        assertNull(lupaDesdeFigura(raya, ItemStyle(), "l2", 7))
    }

    /** Y una lupa no se convierte en otra lupa. */
    @Test
    fun `una lupa no se convierte en lupa`() {
        assertTrue(!sirveDeLupa(lupa()))
    }

    // ---- El cono de dos líneas -------------------------------------------

    /** Con el cono salen **dos** rayas, y no se cruzan. */
    @Test
    fun `el cono son dos rayas que se abren`() {
        val e = conFoco(lupa(w = 200.0, h = 200.0), Pt(0.0, 800.0))
            .copy(guia = GuiaDeLupa.DOS_LINEAS)
        val lineas = lineasDeLaGuia(e)
        assertEquals(2, lineas.size)
        // Se abren: los extremos del cristal están más separados que los del
        // foco, que es lo que enseña cuánto se ha ampliado.
        val separacionFoco = kotlin.math.hypot(
            lineas[0].first.x - lineas[1].first.x, lineas[0].first.y - lineas[1].first.y
        )
        val separacionCristal = kotlin.math.hypot(
            lineas[0].second.x - lineas[1].second.x, lineas[0].second.y - lineas[1].second.y
        )
        assertTrue("no se abren", separacionCristal > separacionFoco)
    }

    /** Apoyada sobre lo que mira, el cono tampoco se dibuja. */
    @Test
    fun `sin apartarla no hay cono`() {
        assertTrue(lineasDeLaGuia(lupa().copy(guia = GuiaDeLupa.DOS_LINEAS)).isEmpty())
    }

    /** El cristal redondo se traza con muchos lados; el cuadrado con cuatro. */
    @Test
    fun `el contorno del cristal tiene la forma que toca`() {
        val c = getElementAbsoluteCoords(lupa())
        assertEquals(4, puntosDelCristal(lupa().copy(lupaRedonda = false), c).size)
        assertEquals(LADOS_DEL_ARO, puntosDelCristal(lupa(), c).size)
    }
}
