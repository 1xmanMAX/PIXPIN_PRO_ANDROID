package com.forge.pixpin.motor

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que hace falta para levantar un plano: recortar figuras cerradas, dictar
 * una medida y poner la escala gráfica.
 */
class PlanoTest {

    private fun linea(id: String, a: Pt, b: Pt) = Element(
        id = id, type = ElementType.LINE, x = a.x, y = a.y,
        width = abs(b.x - a.x), height = abs(b.y - a.y), seed = 1,
        points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
    )

    // ---- Recortar lo que no es una raya ----

    /**
     * Un rectángulo al que se le quita un lado **deja de ser un rectángulo**: es
     * una raya abierta, y eso es lo que se ve. En un plano es lo normal — se
     * traza la caja y se van quitando los trozos que sobran.
     */
    @Test
    fun `recortar un rectangulo lo deja en raya abierta`() {
        val caja = Element(
            id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0,
            width = 200.0, height = 100.0, seed = 1
        )
        // Dos verticales que cortan el lado de arriba y el de abajo.
        val paredes = listOf(
            linea("a", Pt(60.0, -50.0), Pt(60.0, 150.0)),
            linea("b", Pt(140.0, -50.0), Pt(140.0, 150.0))
        )
        // Se toca el trozo de arriba que queda entre las dos.
        val trozos = recortarEn(caja, paredes, Pt(100.0, 0.0))
        assertNotNull("no ha recortado", trozos)
        val resto = trozos!!.single()
        assertEquals(ElementType.LINE, resto.type)

        val puntos = absolutePoints(resto)
        // Empieza y acaba en los dos cortes del lado de arriba…
        assertEquals(140.0, puntos.first().x, 0.5)
        assertEquals(0.0, puntos.first().y, 0.5)
        assertEquals(60.0, puntos.last().x, 0.5)
        assertEquals(0.0, puntos.last().y, 0.5)
        // …y por el camino baja, cruza abajo y sube: sigue teniendo tres lados.
        assertTrue("se ha perdido el resto del contorno", largoDe(puntos) > 300.0)
    }

    /** Con el rombo, igual: sus cuatro lados son los que se recortan. */
    @Test
    fun `recortar un rombo lo deja en raya abierta`() {
        val rombo = Element(
            id = "d", type = ElementType.DIAMOND, x = 0.0, y = 0.0,
            width = 200.0, height = 200.0, seed = 1
        )
        val paredes = listOf(
            linea("a", Pt(0.0, 50.0), Pt(200.0, 50.0)),
            linea("b", Pt(0.0, 150.0), Pt(200.0, 150.0))
        )
        val trozos = recortarEn(rombo, paredes, Pt(155.0, 100.0))
        assertEquals(1, trozos!!.size)
        assertEquals(ElementType.LINE, trozos.first().type)
    }

    /**
     * **La semicircunferencia se recorta en arco, no en polilínea.** Un trozo de
     * circunferencia sigue siendo una circunferencia; convertirlo en cien
     * segmentos rectos perdería el compás para siempre.
     */
    @Test
    fun `recortar un arco devuelve arcos`() {
        // Media circunferencia de radio 100 centrada en (100,100), por abajo.
        val arco = Element(
            id = "a", type = ElementType.ARC, x = 0.0, y = 0.0,
            width = 200.0, height = 200.0, seed = 1,
            arcStart = 0.0, arcSweep = Math.PI
        )
        // Una vertical por el centro la parte por su punto más bajo (100, 200).
        val pared = linea("v", Pt(100.0, 100.0), Pt(100.0, 300.0))

        // Se toca el cuarto de la derecha, entre el inicio y el corte.
        val trozos = recortarEn(arco, listOf(pared), Pt(170.0, 170.0))
        assertNotNull(trozos)
        val resto = trozos!!.single()
        assertEquals(ElementType.ARC, resto.type)
        // Queda el cuarto de la izquierda: medio barrido del que había.
        assertEquals(Math.PI / 2, resto.arcSweep!!, 0.1)
        assertEquals(Math.PI / 2, resto.arcStart!!, 0.1)
    }

    /** Y un óvalo entero recortado **se convierte** en arco. */
    @Test
    fun `recortar un ovalo lo convierte en arco`() {
        val ovalo = Element(
            id = "o", type = ElementType.ELLIPSE, x = 0.0, y = 0.0,
            width = 200.0, height = 200.0, seed = 1
        )
        val pared = linea("h", Pt(-50.0, 100.0), Pt(250.0, 100.0))
        // El corte es el diámetro horizontal: se toca la mitad de abajo.
        val trozos = recortarEn(ovalo, listOf(pared), Pt(100.0, 200.0))
        val resto = trozos!!.single()
        assertEquals(ElementType.ARC, resto.type)
        // Queda media vuelta: la de arriba.
        assertEquals(Math.PI, abs(resto.arcSweep!!), 0.15)
    }

    /** Sin nada que lo corte, la figura entera se va. */
    @Test
    fun `un rectangulo sin cruces se recorta entero`() {
        val caja = Element(
            id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0,
            width = 200.0, height = 100.0, seed = 1
        )
        assertEquals(emptyList<Element>(), recortarEn(caja, emptyList(), Pt(100.0, 0.0)))
    }

    /** Desde el controlador, tocando el lado que sobra. */
    @Test
    fun `la herramienta recorta tambien un rectangulo`() {
        val caja = Element(
            id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0,
            width = 200.0, height = 100.0, seed = 1
        )
        val c = DrawController(
            Scene(
                elements = listOf(
                    caja,
                    linea("a", Pt(60.0, -50.0), Pt(60.0, 150.0)),
                    linea("b", Pt(140.0, -50.0), Pt(140.0, 150.0))
                )
            )
        )
        c.selectTool(Tool.RECORTAR)
        c.pointerDown(Pt(100.0, 0.0))
        c.pointerUp(Pt(100.0, 0.0))

        assertEquals(ElementType.LINE, c.scene.visible.first { it.id == "r" }.type)
    }

    // ---- La raya que se dicta ----

    /** El ángulo se lee como en un plano: cero a la derecha, creciendo arriba. */
    @Test
    fun `el angulo se mide como en un plano`() {
        assertEquals(0.0, anguloDe(linea("l", Pt(0.0, 0.0), Pt(100.0, 0.0))), 0.001)
        // Hacia arriba en pantalla es y decreciente, y eso son +90°.
        assertEquals(90.0, anguloDe(linea("l", Pt(0.0, 0.0), Pt(0.0, -100.0))), 0.001)
        assertEquals(-90.0, anguloDe(linea("l", Pt(0.0, 0.0), Pt(0.0, 100.0))), 0.001)
        assertEquals(45.0, anguloDe(linea("l", Pt(0.0, 0.0), Pt(100.0, -100.0))), 0.001)
    }

    /**
     * **El principio no se mueve.** Es lo que hace que esto sea corregir y no
     * volver a empezar: el sitio donde arranca la medida se acierta con el dedo,
     * el largo y el ángulo no.
     */
    @Test
    fun `dictar largo y angulo ancla el principio`() {
        val raya = linea("l", Pt(50.0, 50.0), Pt(120.0, 90.0))
        val nueva = conLargoYAngulo(raya, 100.0, 0.0)

        val pts = absolutePoints(nueva)
        assertEquals(50.0, pts.first().x, 0.001)
        assertEquals(50.0, pts.first().y, 0.001)
        assertEquals(150.0, pts.last().x, 0.001)
        assertEquals(50.0, pts.last().y, 0.001)
        assertEquals(100.0, longitudDe(nueva), 0.001)
    }

    @Test
    fun `dictar el angulo gira sobre el principio`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(100.0, 0.0))
        val nueva = conLargoYAngulo(raya, 100.0, 90.0)
        assertEquals(0.0, absolutePoints(nueva).last().x, 0.001)
        assertEquals(-100.0, absolutePoints(nueva).last().y, 0.001)
        assertEquals(90.0, anguloDe(nueva), 0.001)
    }

    /** Con escala se teclea en metros, no en píxeles. */
    @Test
    fun `el largo se teclea en las unidades en que se mide`() {
        val escala = Escala(unidadesPorPixel = 0.05) // 20 px = 1 m
        val raya = linea("l", Pt(0.0, 0.0), Pt(100.0, 0.0))
        assertEquals(5.0, largoEnUnidades(raya, escala), 0.001)
        assertEquals(60.0, largoEnPixeles(3.0, escala), 0.001)

        val tresMetros = conLargoYAngulo(raya, largoEnPixeles(3.0, escala), 0.0)
        assertEquals(3.0, medidaDe(tresMetros, escala)!!, 0.001)
    }

    /** Sin escala se teclea en píxeles y no se inventa nada. */
    @Test
    fun `sin escala se teclea en pixeles`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(100.0, 0.0))
        assertEquals(100.0, largoEnUnidades(raya, null), 0.001)
        assertEquals(40.0, largoEnPixeles(40.0, null), 0.001)
    }

    /** Un largo imposible no rompe nada: se queda como estaba. */
    @Test
    fun `un largo que no vale no toca la raya`() {
        val raya = linea("l", Pt(0.0, 0.0), Pt(100.0, 0.0))
        assertEquals(raya, conLargoYAngulo(raya, 0.0, 45.0))
        assertEquals(raya, conLargoYAngulo(raya, -10.0, 45.0))
        assertEquals(raya, conLargoYAngulo(raya, Double.NaN, 45.0))
    }

    /**
     * **La cota pide su medida nada más trazarla.**
     *
     * Es el momento en que uno sabe cuánto mide lo que acaba de señalar;
     * mandarle a buscar un panel después es perder el número por el camino.
     */
    @Test
    fun `al trazar una cota se pide cuanto mide`() {
        val c = DrawController()
        c.selectTool(Tool.MEASURE)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(100.0, 0.0))
        c.pointerUp(Pt(100.0, 0.0))

        val cota = c.pendingCotaElement()
        assertNotNull("no ha pedido la medida", cota)
        assertEquals(ElementType.MEASURE, cota!!.type)

        // Se dicta: dos metros y medio a treinta grados.
        c.aplicarCota(250.0, 30.0)
        assertNull(c.pendingCotaElement())
        val rehecha = c.scene.byId(cota.id)!!
        assertEquals(250.0, longitudDe(rehecha), 0.001)
        assertEquals(30.0, anguloDe(rehecha), 0.001)
        // Y el principio no se ha movido.
        assertEquals(0.0, absolutePoints(rehecha).first().x, 0.001)
        assertEquals(0.0, absolutePoints(rehecha).first().y, 0.001)
    }

    /** Con escala puesta, lo que se teclea son unidades de mundo. */
    @Test
    fun `lo tecleado en la cota va en las unidades de la escena`() {
        val c = DrawController(Scene(escala = Escala(unidadesPorPixel = 0.05)))
        c.selectTool(Tool.MEASURE)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(100.0, 0.0))
        c.pointerUp(Pt(100.0, 0.0))

        c.aplicarCota(3.0, 0.0)
        val cota = c.scene.visible.last { it.isMeasure }
        assertEquals(3.0, medidaDe(cota, c.scene.escala)!!, 0.001)
        assertEquals(60.0, longitudDe(cota), 0.001)
    }

    /** Desistir deja la cota como se trazó: ya dice lo que mide. */
    @Test
    fun `cancelar la cota la deja como estaba`() {
        val c = DrawController()
        c.selectTool(Tool.MEASURE)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(100.0, 0.0))
        c.pointerUp(Pt(100.0, 0.0))

        c.cancelarCota()
        assertNull(c.pendingCotaElement())
        assertEquals(100.0, longitudDe(c.scene.visible.last()), 0.001)
    }

    // ---- La escala gráfica ----

    /**
     * Los cuadros miden un número **redondo**: 1, 2 o 5 por una potencia de
     * diez. Con cuadros de 3,7 m la barra hay que leerla con calculadora, que es
     * justo lo que viene a evitar.
     */
    @Test
    fun `los cuadros miden numeros redondos`() {
        assertEquals(1.0, pasoRedondo(1.4), 0.001)
        assertEquals(2.0, pasoRedondo(2.9), 0.001)
        assertEquals(5.0, pasoRedondo(7.3), 0.001)
        assertEquals(10.0, pasoRedondo(13.0), 0.001)
        assertEquals(0.5, pasoRedondo(0.7), 0.001)
        assertEquals(500.0, pasoRedondo(830.0), 0.001)
    }

    @Test
    fun `la barra se reparte en cuadros que caben enteros`() {
        // 20 px = 1 m, y la barra mide 400 px: 20 m de barra.
        val barra = barraDeEscala(400.0, Escala(unidadesPorPixel = 0.05))!!
        assertEquals(5.0, barra.porTramo, 0.001)
        assertEquals(4, barra.tramos)
        assertEquals(20.0, barra.total, 0.001)
        assertEquals(100.0, barra.anchoDeTramo, 0.001)
        assertEquals("m", barra.unidad)
        // Y nunca se pasa de lo que mide la caja.
        assertTrue(barra.ancho <= 400.0)
    }

    /** Sin escala se reparte en píxeles: sigue sirviendo para comparar. */
    @Test
    fun `sin escala la barra habla en pixeles`() {
        val barra = barraDeEscala(400.0, null)!!
        assertEquals("px", barra.unidad)
        assertEquals(100.0, barra.porTramo, 0.001)
    }

    /** Estirarla cambia lo que dice: es lo que la hace no mentir nunca. */
    @Test
    fun `estirar la barra recalcula lo que mide`() {
        val escala = Escala(unidadesPorPixel = 0.05)
        val corta = barraDeEscala(200.0, escala)!!
        val larga = barraDeEscala(800.0, escala)!!
        assertTrue("la barra larga tiene que abarcar más", larga.total > corta.total)
        // Y las dos siguen midiendo lo mismo por píxel, que es lo importante.
        assertEquals(
            corta.porTramo / corta.anchoDeTramo,
            larga.porTramo / larga.anchoDeTramo,
            1e-9
        )
    }

    /** Las etiquetas salen sin decimales cuando no hacen falta. */
    @Test
    fun `las cifras de la barra se escriben cortas`() {
        val barra = barraDeEscala(400.0, Escala(unidadesPorPixel = 0.05))!!
        assertEquals("0", barra.etiqueta(0))
        assertEquals("5", barra.etiqueta(1))
        assertEquals("20", barra.etiqueta(4))
        assertEquals("0.5", formatearMedida(0.5))
    }

    @Test
    fun `una barra sin ancho no se reparte`() {
        assertNull(barraDeEscala(0.0, null))
        assertNull(barraDeEscala(-10.0, null))
    }

    /** Y la reglita se coloca arrastrando, como cualquier forma con caja. */
    @Test
    fun `la escala grafica se coloca arrastrando`() {
        val c = DrawController()
        c.selectTool(Tool.ESCALA_GRAFICA)
        c.pointerDown(Pt(10.0, 10.0))
        c.pointerMove(Pt(210.0, 50.0))
        c.pointerUp(Pt(210.0, 50.0))

        val e = c.scene.visible.single()
        assertEquals(ElementType.ESCALA_GRAFICA, e.type)
        assertEquals(200.0, e.width, 0.001)
        // No hace de pared para el bote: es una anotación sobre el plano.
        assertTrue(!esPared(e))
    }
}
