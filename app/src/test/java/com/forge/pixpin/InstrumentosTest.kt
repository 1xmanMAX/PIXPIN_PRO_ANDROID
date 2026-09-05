package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * La recta y el espacio, los dos instrumentos que acompañan al plano, y las
 * puntas de flecha de los tres. Lo que se defiende: que se estiran como el
 * plano —por un lado más números, por una esquina los mismos—, que el giro
 * del espacio da la vuelta al espacio y no a la caja, y que las puntas están
 * donde acaban los ejes y miden lo que deben.
 */
class InstrumentosTest {

    private fun instrumento(tipo: ElementType, ancho: Double = 400.0, alto: Double = 400.0) = Element(
        id = "i", type = tipo, x = 0.0, y = 0.0, width = ancho, height = alto, seed = 1,
        unidad = 40.0, pasoDeNumeros = 1.0, pasoDeCuadros = 1.0
    )

    private fun largo(t: TrazoDelPlano) = hypot(t.b.x - t.a.x, t.b.y - t.a.y)

    @Test
    fun `el plano lleva una punta en cada extremo de sus ejes`() {
        val p = instrumento(ElementType.PLANO)
        val ejes = trazosDelPlano(p).filter { it.eje }
        // Dos ejes y ocho alas: dos por extremo.
        assertEquals(10, ejes.size)
        val alas = ejes.filter { largo(it) < 20.0 }
        assertEquals(8, alas.size)
        val punta = largoDeLaPunta(p)
        alas.forEach { assertTrue(largo(it) <= punta * 1.2 && largo(it) >= punta * 0.9) }
        // Cada ala acaba en una punta que está en el borde de la caja.
        assertTrue(alas.all { a -> a.b.x == 0.0 || a.b.x == p.width || a.b.y == 0.0 || a.b.y == p.height })
    }

    @Test
    fun `la recta es una regla con cifras y dos puntas`() {
        val r = instrumento(ElementType.RECTA, alto = 56.0)
        val numeros = numerosDeLaRecta(r).map { it.texto }
        assertEquals((-5..5).map { it.toString() }, numeros)
        val trazos = trazosDeLaRecta(r)
        assertTrue(trazos.all { it.eje })
        assertTrue(trazos.any { it.a == Pt(0.0, 28.0) && it.b == Pt(400.0, 28.0) })
        assertEquals(4, trazos.count { largo(it) < 20.0 && it.a.x != it.b.x && it.a.y != it.b.y })
    }

    @Test
    fun `la recta se alarga por un lado con mas numeros y por una esquina con los mismos`() {
        val antes = instrumento(ElementType.RECTA, alto = 56.0)
        val c = getElementAbsoluteCoords(antes)
        val porElLado = resizeSingleElement(antes, HandleType.E, Pt(c.x2 + 200, c.cy))
        assertEquals(40.0, porElLado.unidadDelPlano, 1e-9)
        assertTrue(numerosDeLaRecta(porElLado).size > numerosDeLaRecta(antes).size)

        val porLaEsquina = resizeSingleElement(antes, HandleType.SE, Pt(c.x2 + 400, c.y2 + 56))
        assertEquals(80.0, porLaEsquina.unidadDelPlano, 1e-6)
        assertEquals(numerosDeLaRecta(antes).size, numerosDeLaRecta(porLaEsquina).size)
    }

    @Test
    fun `la vista de partida del espacio es la del cuaderno`() {
        val pr = proyeccionDelEspacio(instrumento(ElementType.ESPACIO))
        assertTrue("la z sube", pr.ez.y < 0 && kotlin.math.abs(pr.ez.x) < 1e-9)
        assertTrue("la x viene hacia la izquierda y abajo", pr.ex.x < 0 && pr.ex.y > 0)
        assertTrue("la y se va a la derecha", pr.ey.x > 0)
        // En picado, el suelo se ve entero y la z se acorta.
        val picado = proyeccionDelEspacio(instrumento(ElementType.ESPACIO).copy(elevacion = 89.0))
        assertTrue(kotlin.math.abs(picado.ez.y) < 0.05)
    }

    @Test
    fun `el espacio trae tres ejes con sus letras, cifras y puntas`() {
        val e = instrumento(ElementType.ESPACIO)
        val trazos = trazosDelEspacio(e)
        assertTrue("suelo pautado", trazos.any { !it.eje })
        val ejes = trazos.filter { it.eje }
        // Tres ejes, tres puntas de dos alas y las marcas.
        assertTrue(ejes.count { largo(it) > 100.0 } == 3)
        assertTrue(ejes.count { largo(it) in 8.0..20.0 } >= 6)
        val textos = numerosDelEspacio(e).map { it.texto }
        assertTrue(textos.containsAll(listOf("x", "y", "z", "0", "1", "-1")))
        // Todo cae dentro de la caja.
        assertTrue(trazos.all { it.a.x in -1.0..401.0 && it.b.x in -1.0..401.0 && it.a.y in -1.0..401.0 && it.b.y in -1.0..401.0 })
    }

    @Test
    fun `el tirador de giro da la vuelta al espacio y deja la caja derecha`() {
        val e = instrumento(ElementType.ESPACIO)
        val c = getElementAbsoluteCoords(e)
        val girado = rotateSingleElement(e, Pt(c.cx + 100, c.cy))
        assertEquals(0.0, girado.angle, 1e-9)
        assertEquals(90.0, girado.azimutDelEspacio, 1e-6)
        val vuelta = rotateSingleElement(e, Pt(c.cx, c.cy - 100))
        assertEquals(0.0, vuelta.azimutDelEspacio, 1e-6)
        // Con el espacio girado un cuarto, la x ya no cae donde caía.
        assertTrue(proyeccionDelEspacio(girado).ex != proyeccionDelEspacio(e).ex)
    }

    @Test
    fun `el espacio se estira como el plano`() {
        val antes = instrumento(ElementType.ESPACIO)
        val c = getElementAbsoluteCoords(antes)
        val porElLado = resizeSingleElement(antes, HandleType.E, Pt(c.x2 + 300, c.cy))
        assertEquals(40.0, porElLado.unidadDelPlano, 1e-9)
        val porLaEsquina = resizeSingleElement(antes, HandleType.SE, Pt(c.x2 + 400, c.y2 + 400))
        assertEquals(80.0, porLaEsquina.unidadDelPlano, 1e-6)
        assertEquals(alcanceDelEspacio(antes).first, alcanceDelEspacio(porLaEsquina).first, 0.5)
    }

    /** El imán engancha por lo que miden: el origen, las marcas y los cruces de la rejilla. */
    @Test
    fun `los instrumentos enganchan por el origen, las marcas y la rejilla`() {
        val plano = instrumento(ElementType.PLANO)
        val anclajes = anclajesDe(plano, AjustesEnganche())
        assertTrue(anclajes.any { it.tipo == TipoAnclaje.CENTRO && it.punto == Pt(200.0, 200.0) })
        // La marca del 3 del eje x y la del −2 del eje y, y su cruce: el (3, −2).
        assertTrue(anclajes.any { it.tipo == TipoAnclaje.EXTREMO && it.punto == Pt(320.0, 200.0) })
        assertTrue(anclajes.any { it.tipo == TipoAnclaje.EXTREMO && it.punto == Pt(200.0, 280.0) })
        assertTrue(anclajes.any { it.tipo == TipoAnclaje.INTERSECCION && it.punto == Pt(320.0, 280.0) })
        assertEquals(puntoDelPlano(plano, 3.0, -2.0), Pt(320.0, 280.0))
        // Y las esquinas de la caja no enganchan como esquinas: lo que cae ahí, si algo
        // cae, es un cruce de la rejilla, que sí está dibujado.
        assertTrue(anclajes.none { it.tipo == TipoAnclaje.ESQUINA || it.tipo == TipoAnclaje.MEDIO })

        val recta = instrumento(ElementType.RECTA, alto = 56.0)
        assertTrue(anclajesDe(recta, AjustesEnganche()).any { it.tipo == TipoAnclaje.EXTREMO && it.punto == Pt(240.0, 28.0) })
        val espacio = instrumento(ElementType.ESPACIO)
        val delEspacio = anclajesDe(espacio, AjustesEnganche())
        assertTrue(delEspacio.any { it.tipo == TipoAnclaje.CENTRO })
        assertTrue(delEspacio.size > 4)
    }

    /** Se cogen por dentro: tocar los ejes o la rejilla los mueve. */
    @Test
    fun `los instrumentos se cogen por cualquier parte`() {
        val plano = instrumento(ElementType.PLANO)
        assertTrue(shouldTestInside(plano))
        assertTrue(hitElementItself(Pt(150.0, 150.0), plano, 4.0))
        assertTrue(hitElementItself(Pt(150.0, 150.0), instrumento(ElementType.ESPACIO), 4.0))
    }

    @Test
    fun `dice donde cae un punto del espacio`() {
        val e = instrumento(ElementType.ESPACIO)
        val o = puntoDelEspacio(e, 0.0, 0.0, 0.0)
        assertEquals(Pt(200.0, 200.0), o)
        val arriba = puntoDelEspacio(e, 0.0, 0.0, 2.0)
        assertEquals(200.0, arriba.x, 1e-9)
        assertTrue(arriba.y < 200.0)
    }
}
