package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El enganche donde se cruzan **dos figuras cualesquiera**.
 *
 * Antes solo se cruzaban rectángulos, rombos y líneas: los tres tipos que se
 * dibujan con rectas. Todo lo demás —una elipse, un arco, una esquina
 * redondeada, una imagen— no producía ni un solo punto de intersección, y son
 * justo los casos en los que más falta hace, porque el cruce de dos curvas no
 * está cerca de ningún vértice al que agarrarse.
 *
 * Estas pruebas comprueban las dos mitades del asunto: que ahora **sí** hay
 * cruce, y que está **donde de verdad se cortan las figuras** y no donde se
 * cortarían sus cajas. Lo segundo es lo que se rompería en silencio: un imán que
 * tira a un sitio parecido pero equivocado es peor que ninguno.
 */
class InterseccionesTest {

    private fun linea(id: String, a: Pt, b: Pt) = Element(
        id = id, type = ElementType.LINE, x = a.x, y = a.y,
        width = b.x - a.x, height = b.y - a.y, seed = 1,
        points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
    )

    /** Un círculo de radio [r] centrado en ([cx], [cy]). */
    private fun circulo(id: String, cx: Double, cy: Double, r: Double) = Element(
        id = id, type = ElementType.ELLIPSE, x = cx - r, y = cy - r,
        width = r * 2, height = r * 2, seed = 1
    )

    private fun cruce(elementos: List<Element>, cerca: Pt): Pt? =
        buscarAnclaje(elementos, cerca, zoom = 1.0)
            ?.takeIf { it.tipo == TipoAnclaje.INTERSECCION }?.punto

    // ---- Lo que antes no cruzaba ----

    /**
     * Una recta que atraviesa un círculo lo corta en dos puntos exactos. Es el
     * caso más simple de los que no funcionaban, y el que más se pide: el radio
     * de una circunferencia empieza justo ahí.
     */
    @Test
    fun `una linea corta un circulo por donde lo corta`() {
        val c = circulo("c", 100.0, 100.0, 50.0)
        val l = linea("l", Pt(0.0, 100.0), Pt(200.0, 100.0))

        val derecha = cruce(listOf(c, l), Pt(153.0, 102.0))
        assertNotNull("no engancha al cruce de la derecha", derecha)
        assertEquals(150.0, derecha!!.x, 0.5)
        assertEquals(100.0, derecha.y, 0.5)

        val izquierda = cruce(listOf(c, l), Pt(48.0, 97.0))
        assertNotNull("no engancha al cruce de la izquierda", izquierda)
        assertEquals(50.0, izquierda!!.x, 0.5)
    }

    /**
     * Dos círculos que se solapan se cortan en dos puntos, y **ninguno de los
     * dos está en sus cajas**: es el ejemplo de libro de un punto que no existe
     * como vértice de nada.
     */
    @Test
    fun `dos circulos que se solapan se cruzan`() {
        val a = circulo("a", 0.0, 0.0, 100.0)
        val b = circulo("b", 100.0, 0.0, 100.0)
        // Se cortan en x = 50, y = ±√(100² − 50²) = ±86,60.
        val arriba = cruce(listOf(a, b), Pt(52.0, -84.0))
        assertNotNull("dos círculos no dan cruce", arriba)
        assertEquals(50.0, arriba!!.x, 0.6)
        assertEquals(-86.60, arriba.y, 0.6)
    }

    /** Un círculo dentro de un rectángulo sin tocarlo **no** se cruza con él. */
    @Test
    fun `un circulo que no toca la caja no da cruce`() {
        val caja = Element(
            id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0,
            width = 400.0, height = 400.0, seed = 1
        )
        val c = circulo("c", 200.0, 200.0, 50.0)
        // Sobre el borde del círculo, lejos del rectángulo: no hay cruce que
        // valga aunque sus cajas se solapen del todo.
        assertNull(cruce(listOf(caja, c), Pt(250.0, 200.0)))
    }

    /**
     * El arco es una raya curva que ni siquiera guarda sus puntos: se generan de
     * la caja del óvalo y de cuánto barre. Aun así se cruza como todo lo demás.
     */
    @Test
    fun `un arco se cruza con una linea`() {
        // Medio círculo de radio 50 centrado en (100,100), de 0 a π: la mitad
        // de abajo. La vertical x = 100 lo corta en (100, 150).
        val arco = Element(
            id = "arc", type = ElementType.ARC, x = 50.0, y = 50.0,
            width = 100.0, height = 100.0, seed = 1,
            arcStart = 0.0, arcSweep = Math.PI
        )
        val vertical = linea("v", Pt(100.0, 0.0), Pt(100.0, 200.0))

        val hit = cruce(listOf(arco, vertical), Pt(102.0, 147.0))
        assertNotNull("el arco no cruza", hit)
        assertEquals(100.0, hit!!.x, 0.5)
        assertEquals(150.0, hit.y, 0.5)
    }

    /**
     * **La esquina redondeada corta donde corta ella, no donde cortaría el
     * pico.** Es la diferencia que delata si el perímetro se está sacando de la
     * caja o de la forma de verdad: son diez píxeles de distancia, más que el
     * radio con el que engancha el dedo.
     */
    @Test
    fun `una esquina redondeada corta por la curva y no por el pico`() {
        val caja = Element(
            id = "r", type = ElementType.RECTANGLE, x = 100.0, y = 100.0,
            width = 200.0, height = 100.0, seed = 1,
            roundness = Roundness(Roundness.ADAPTIVE_RADIUS)
        )
        // Radio de la esquina: el lado corto es 100 y el algoritmo adaptativo
        // da 25. La horizontal y = 105 corta la curva en x = 125 − √(25²−20²)
        // = 110, y no en x = 100, que es donde estaría el pico.
        val horizontal = linea("h", Pt(80.0, 105.0), Pt(150.0, 105.0))

        val hit = cruce(listOf(caja, horizontal), Pt(111.0, 104.0))
        assertNotNull("la esquina redondeada no cruza", hit)
        assertEquals(110.0, hit!!.x, 0.8)
        assertEquals(105.0, hit.y, 0.5)
    }

    /** La imagen se cruza por su caja, que es lo que se ve de ella. */
    @Test
    fun `una imagen se cruza con una linea`() {
        val img = Element(
            id = "i", type = ElementType.IMAGE, x = 0.0, y = 0.0,
            width = 100.0, height = 100.0, seed = 1, fileId = "f"
        )
        val diagonal = linea("d", Pt(50.0, 50.0), Pt(200.0, 200.0))
        val hit = cruce(listOf(img, diagonal), Pt(98.0, 102.0))
        assertNotNull("la imagen no cruza", hit)
        assertEquals(100.0, hit!!.x, 0.5)
        assertEquals(100.0, hit.y, 0.5)
    }

    /**
     * Una figura **consigo misma**: un trazo que se cruza al volver.
     *
     * El punto donde un lazo se cierra sobre sí mismo es tan poco acertable a
     * pulso como el cruce de dos figuras, y tampoco es vértice de nada.
     */
    @Test
    fun `un trazo que se cruza consigo mismo engancha en su cruce`() {
        val lazo = Element(
            id = "z", type = ElementType.LINE, x = 0.0, y = 0.0,
            width = 100.0, height = 100.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(100.0, 100.0), Pt(100.0, 0.0), Pt(0.0, 100.0))
        )
        val hit = cruce(listOf(lazo), Pt(52.0, 48.0))
        assertNotNull("no engancha al cruce consigo mismo", hit)
        assertEquals(50.0, hit!!.x, 0.5)
        assertEquals(50.0, hit.y, 0.5)
    }

    /** Los tramos **seguidos** comparten un extremo: eso es un vértice, no un cruce. */
    @Test
    fun `dos tramos seguidos no cuentan como cruce consigo misma`() {
        val codo = Element(
            id = "c", type = ElementType.LINE, x = 0.0, y = 0.0,
            width = 100.0, height = 100.0, seed = 1,
            points = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0))
        )
        assertTrue(
            interseccionesCerca(listOf(codo), Pt(100.0, 0.0), radio = 20.0).isEmpty()
        )
    }

    // ---- Que siga sin enganchar donde no debe ----

    /** El cruce solo cuenta **dentro de los dos tramos**, como siempre. */
    @Test
    fun `una linea que se queda corta no cruza el circulo`() {
        val c = circulo("c", 100.0, 100.0, 50.0)
        // Acaba en x = 120, dentro del círculo: no llega a salir por el otro
        // lado, así que solo hay un cruce y es el de la izquierda.
        val corta = linea("l", Pt(0.0, 100.0), Pt(120.0, 100.0))
        assertNull(cruce(listOf(c, corta), Pt(151.0, 100.0)))
        assertNotNull(cruce(listOf(c, corta), Pt(51.0, 100.0)))
    }

    /**
     * El elemento que se está dibujando no cruza consigo mismo por el camino:
     * se engancharía a su propio trazo en cuanto se doblara.
     */
    @Test
    fun `lo que se esta dibujando queda fuera`() {
        val c = circulo("c", 100.0, 100.0, 50.0)
        val l = linea("l", Pt(0.0, 100.0), Pt(200.0, 100.0))
        assertNull(
            buscarAnclaje(listOf(c, l), Pt(150.0, 100.0), 1.0, excluir = "l")
                ?.takeIf { it.tipo == TipoAnclaje.INTERSECCION }
        )
    }

    /**
     * El perímetro de una figura girada va girado.
     *
     * Es el fallo que más fácil se cuela: los puntos de un trazo se guardan sin
     * rotar y el elemento gira alrededor de su centro, así que quien se olvide
     * de aplicar el ángulo obtiene un cruce que no está donde se ve.
     */
    @Test
    fun `una figura girada cruza por donde se ve`() {
        // Cuadrado de 100 centrado en (100,100), girado 45°: sus vértices
        // quedan arriba, abajo y a los lados, a 70,71 del centro.
        val rombo = Element(
            id = "r", type = ElementType.RECTANGLE, x = 50.0, y = 50.0,
            width = 100.0, height = 100.0, seed = 1, angle = Math.PI / 4
        )
        val horizontal = linea("h", Pt(0.0, 100.0), Pt(200.0, 100.0))
        val hit = cruce(listOf(rombo, horizontal), Pt(168.0, 101.0))
        assertNotNull("la figura girada no cruza donde se ve", hit)
        assertEquals(100 + 50 * Math.sqrt(2.0), hit!!.x, 0.5)
    }

    /** Y su contorno, el que se ve: girado también. */
    @Test
    fun `el contorno de una figura girada sale girado`() {
        val caja = Element(
            id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0,
            width = 100.0, height = 100.0, seed = 1, angle = Math.PI / 2
        )
        val puntos = contornosDe(caja).single().puntos
        // Girar un cuadrado 90° sobre su centro lo deja donde estaba.
        assertEquals(4, puntos.size)
        assertTrue(puntos.all { it.x >= -0.001 && it.x <= 100.001 })
        assertTrue(puntos.all { it.y >= -0.001 && it.y <= 100.001 })
    }

    /**
     * Cada tipo tiene que decir por dónde pasa, y el `when` de [contornosDe] es
     * exhaustivo justo para que nadie pueda añadir uno y olvidarse. Esto
     * comprueba lo que el compilador no puede: que lo que declara **no esté
     * vacío** en los que sí dibujan algo.
     */
    @Test
    fun `todos los tipos que se dibujan tienen perimetro`() {
        // Tres que no tienen contorno, cada uno por su motivo. El foco es una
        // mancha sobre todo lo demás; el punto es un sitio, no una figura; y la
        // caja del texto **no se dibuja**, así que tratarla como borde hacía
        // aparecer enganches flotando alrededor de cada texto y cruces contra
        // una raya invisible.
        val sinPerimetro = setOf(
            ElementType.SPOTLIGHT, ElementType.PUNTO, ElementType.TEXT
        )
        for (tipo in ElementType.entries) {
            val e = Element(
                id = "x", type = tipo, x = 0.0, y = 0.0,
                width = 100.0, height = 100.0, seed = 1,
                points = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0)),
                arcStart = 0.0, arcSweep = Math.PI
            )
            val tramos = segmentosDe(e)
            if (tipo in sinPerimetro) {
                assertTrue("$tipo no debería tener perímetro", tramos.isEmpty())
            } else {
                assertTrue("$tipo se queda sin perímetro", tramos.isNotEmpty())
            }
        }
    }
}
