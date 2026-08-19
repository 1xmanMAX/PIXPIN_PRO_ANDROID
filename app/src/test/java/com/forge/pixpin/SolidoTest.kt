package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La caja del boceto 3D.
 *
 * Lo que se vigila aquí son los fallos que **no se ven como fallos**. Una caja
 * con las caras del revés se sigue viendo como una caja, solo que sombreada al
 * contrario; una cara trasera que se cuela tapa a una de delante y parece un
 * dibujo raro, no un error; y un orden de pintado equivocado solo se nota
 * cuando dos volúmenes se cruzan. Todo eso se comprueba con números.
 */
class SolidoTest {

    private val tol = 1e-9
    private val vistas = Vista.entries

    /** Un cubo unidad en el origen: lo más fácil de comprobar a mano. */
    private val cubo = Solido(0.0, 0.0, 1.0, 1.0, 1.0)

    // ---------------------------------------------------------------------
    // Los vértices
    // ---------------------------------------------------------------------

    /** El orden de los ocho vértices es contrato: [Cara] indexa sobre él. */
    @Test
    fun `los ocho vértices salen en el orden pactado`() {
        val v = verticesDe(Solido(10.0, 20.0, 3.0, 4.0, 5.0))
        assertEquals(8, v.size)
        assertEquals(Pt3(10.0, 20.0, 0.0), v[0])
        assertEquals(Pt3(13.0, 20.0, 0.0), v[1])
        assertEquals(Pt3(13.0, 24.0, 0.0), v[2])
        assertEquals(Pt3(10.0, 24.0, 0.0), v[3])
        // Los de arriba son los mismos cuatro, subidos: el de arriba de `i` es `i + 4`.
        for (i in 0..3) {
            assertEquals(v[i].x, v[i + 4].x, tol)
            assertEquals(v[i].y, v[i + 4].y, tol)
            assertEquals(0.0, v[i].z, tol)
            assertEquals(5.0, v[i + 4].z, tol)
        }
    }

    /**
     * **Dibujarla al revés no le da la vuelta a la caja.**
     *
     * Un arrastre de derecha a izquierda deja el ancho negativo, y eso invertía
     * el sentido de todos los polígonos: se pintarían las tres caras de detrás
     * en vez de las tres de delante. Se ve como una caja perfectamente normal
     * con la luz al revés, así que no salta a la vista: por eso se normaliza.
     */
    @Test
    fun `una caja dibujada al revés es la misma caja`() {
        val derecha = Solido(2.0, 3.0, 4.0, 5.0, 6.0)
        val alReves = Solido(6.0, 8.0, -4.0, -5.0, -6.0)
        assertEquals(verticesDe(derecha), verticesDe(alReves))
        for (v in vistas) {
            assertEquals(
                carasDeSolido(derecha, v).map { it.cara },
                carasDeSolido(alReves, v).map { it.cara }
            )
        }
    }

    // ---------------------------------------------------------------------
    // Qué se ve
    // ---------------------------------------------------------------------

    /** De las seis caras de una caja se ven tres: la tapa y dos paredes. */
    @Test
    fun `de las seis caras se ven tres`() {
        for (v in vistas) {
            val caras = carasDeSolido(cubo, v).map { it.cara }
            assertEquals("vista $v: $caras", 3, caras.size)
            assertTrue("vista $v: falta la tapa", Cara.TAPA in caras)
            assertEquals("vista $v: $caras", 2, caras.count { it.normal.z == 0.0 })
        }
    }

    /** Y son exactamente las que tocan a cada vista, ni una más. */
    @Test
    fun `en cada vista se ven las paredes que miran a la cámara`() {
        val esperadas = mapOf(
            Vista.CERO to setOf(Cara.TAPA, Cara.PARED_X_MAS, Cara.PARED_Y_MAS),
            Vista.CUARTO to setOf(Cara.TAPA, Cara.PARED_X_MAS, Cara.PARED_Y_MENOS),
            Vista.MEDIA to setOf(Cara.TAPA, Cara.PARED_X_MENOS, Cara.PARED_Y_MENOS),
            Vista.TRES_CUARTOS to setOf(Cara.TAPA, Cara.PARED_X_MENOS, Cara.PARED_Y_MAS)
        )
        for (v in vistas) {
            assertEquals("vista $v", esperadas[v], carasDeSolido(cubo, v).map { it.cara }.toSet())
        }
    }

    /**
     * **Ninguna cara trasera se cuela**, comprobado por otro camino.
     *
     * El motor decide por el signo del área del polígono ya aplanado; aquí se
     * contrasta contra el criterio de libro —si la normal de la cara apunta
     * hacia quien mira—, que es una cuenta distinta con los mismos datos. Si las
     * dos coinciden en las cuatro vistas y con cajas de cualquier tamaño, el
     * convenio de giro de los vértices está bien puesto.
     */
    @Test
    fun `ninguna cara de detrás se cuela`() {
        val cajas = listOf(
            cubo,
            Solido(-4.0, 7.0, 12.0, 1.0, 30.0),
            Solido(100.0, -100.0, 0.5, 9.0, 0.25)
        )
        for (caja in cajas) for (v in vistas) {
            for (cara in Cara.entries) {
                val miraAlaCamara = profundidad(cara.normal.x, cara.normal.y, cara.normal.z, v) > 0
                val laPinta = carasDeSolido(caja, v).any { it.cara == cara }
                assertEquals("$caja / $v / $cara", miraAlaCamara, laPinta)
            }
        }
    }

    /** La base no se ve nunca: no hay forma de mirar desde debajo del suelo. */
    @Test
    fun `la base no se ve desde ninguna vista`() {
        for (v in vistas) {
            assertFalse(carasDeSolido(cubo, v).any { it.cara == Cara.BASE })
        }
    }

    /**
     * Las tres caras visibles tapan el hexágono entero, sin huecos ni solapes.
     *
     * Sus áreas suman exactamente la del hexágono de un cubo (`3·√3/2·paso²`).
     * Si sobrara, es que se está pintando dos veces algo; si faltara, quedaría un
     * trozo de caja sin rellenar y se vería el dibujo por debajo.
     */
    @Test
    fun `las tres caras visibles cubren el hexágono`() {
        val paso = 20.0
        for (v in vistas) {
            val suma = carasDeSolido(cubo, v, paso).sumOf { areaConSigno(it.poligono) }
            assertEquals("vista $v", 3 * sqrt(3.0) / 2 * paso * paso, suma, 1e-6)
        }
    }

    // ---------------------------------------------------------------------
    // El orden de pintado
    // ---------------------------------------------------------------------

    /**
     * **De atrás hacia delante, y ninguna de detrás encima de una de delante.**
     *
     * Con las seis caras pedidas (que es como se pinta una caja translúcida),
     * las tres traseras tienen que salir antes que las tres de delante: en una
     * caja las de delante las tapan enteras, así que si el orden se invirtiera se
     * vería el interior de la caja por fuera.
     */
    @Test
    fun `el orden de pintado va de atrás hacia delante`() {
        for (v in vistas) {
            val todas = carasDeSolido(cubo, v, soloVisibles = false)
            assertEquals(6, todas.size)

            val profundidades = todas.map { it.profundidad }
            assertEquals(profundidades.sorted(), profundidades)

            val ultimaDeAtras = todas.indexOfLast { !it.visible }
            val primeraDeDelante = todas.indexOfFirst { it.visible }
            assertTrue(
                "vista $v: se cuela una de atrás encima de una de delante: " +
                    todas.map { "${it.cara}${if (it.visible) "*" else ""}" },
                ultimaDeAtras < primeraDeDelante
            )
        }
    }

    /** Y entre varias cajas manda la misma cuenta: la de más delante, la última. */
    @Test
    fun `entre dos cajas pinta antes la de detrás`() {
        val cerca = Solido(5.0, 5.0, 1.0, 1.0, 1.0)
        val lejos = Solido(-5.0, -5.0, 1.0, 1.0, 1.0)
        assertTrue(profundidadDe(cerca, Vista.CERO) > profundidadDe(lejos, Vista.CERO))
        // Y al girar media vuelta se cambian los papeles, que es justo para lo
        // que la profundidad depende de la vista.
        assertTrue(profundidadDe(lejos, Vista.MEDIA) > profundidadDe(cerca, Vista.MEDIA))
    }

    // ---------------------------------------------------------------------
    // El sombreado
    // ---------------------------------------------------------------------

    /** Tres tonos separados por el mismo escalón: tapa, pared clara, pared oscura. */
    @Test
    fun `el sombreado sube un escalón fijo por cara`() {
        for (v in vistas) {
            val caras = carasDeSolido(cubo, v)
            val tapa = caras.first { it.cara == Cara.TAPA }.claridad
            val paredes = caras.filter { it.cara.normal.z == 0.0 }.map { it.claridad }.sorted()
            assertEquals("vista $v", PASO_DE_CLARIDAD, tapa, tol)
            assertEquals("vista $v", -PASO_DE_CLARIDAD, paredes[0], tol)
            assertEquals("vista $v", 0.0, paredes[1], tol)
            // El mismo salto de una a la siguiente: es lo que hace que se lea
            // como volumen y no como tres colores sueltos.
            assertEquals(tapa - paredes[1], paredes[1] - paredes[0], tol)
        }
    }

    /**
     * **La luz va pegada a la pantalla, no al modelo.**
     *
     * En las cuatro vistas la pared de la izquierda es la clara y la de la
     * derecha la oscura. Si el tono fuera del lado del mundo, al girar un cuarto
     * la cara clara saltaría de lado y el volumen parecería darse la vuelta.
     */
    @Test
    fun `al girar la vista la luz no se mueve`() {
        for (v in vistas) {
            val centro = proyectar(0.5, 0.5, 0.5, v)
            val paredes = carasDeSolido(cubo, v).filter { it.cara.normal.z == 0.0 }
            assertEquals(2, paredes.size)
            for (p in paredes) {
                val sx = p.poligono.sumOf { it.x } / p.poligono.size
                val esperada = if (sx < centro.x) 0.0 else -PASO_DE_CLARIDAD
                assertEquals("vista $v, cara ${p.cara} en sx=$sx", esperada, p.claridad, tol)
            }
        }
    }

    /** El color de cada cara: el base movido hacia el blanco o hacia el negro. */
    @Test
    fun `aclarar mueve el color hacia el blanco o hacia el negro`() {
        assertEquals("#808080", aclarar("#808080", 0.0))
        assertEquals("#ffffff", aclarar("#000000", 1.0))
        assertEquals("#000000", aclarar("#ffffff", -1.0))
        // Los topes no se pasan de rosca por mucho que se insista.
        assertEquals("#ffffff", aclarar("#336699", 5.0))
        assertEquals("#000000", aclarar("#336699", -5.0))

        val base = "#3d7ea6"
        val claro = aclarar(base, PASO_DE_CLARIDAD)
        val oscuro = aclarar(base, -PASO_DE_CLARIDAD)
        assertTrue("$claro no es más claro que $base", canales(claro) > canales(base))
        assertTrue("$oscuro no es más oscuro que $base", canales(oscuro) < canales(base))
    }

    /** Lo que no entiende lo deja pasar tal cual: una caja sin relleno sigue sin relleno. */
    @Test
    fun `aclarar respeta lo que no es un color hexadecimal`() {
        assertEquals(Element.TRANSPARENT, aclarar(Element.TRANSPARENT, 0.5))
        assertEquals("red", aclarar("red", 0.5))
        assertEquals("#12345", aclarar("#12345", 0.5))
        assertEquals("#zzzzzz", aclarar("#zzzzzz", 0.5))
        // Las formas cortas y con transparencia sí las entiende.
        assertEquals("#aabbcc", aclarar("#abc", 0.0))
        assertEquals("#80ff0000", aclarar("#80ff0000", 0.0))
    }

    private fun canales(hex: String): Int = hex.removePrefix("#").takeLast(6).toInt(16)

    // ---------------------------------------------------------------------
    // La sombra
    // ---------------------------------------------------------------------

    /** La sombra es la huella, y no se mueve al cambiar la altura. */
    @Test
    fun `la sombra es la huella en el suelo`() {
        val bajo = Solido(3.0, 4.0, 2.0, 5.0, 0.5)
        val alto = bajo.copy(altura = 40.0)
        for (v in vistas) {
            val sombra = sombraEnElSuelo(bajo, v)
            assertEquals(4, sombra.size)
            assertEquals(sombra, sombraEnElSuelo(alto, v))
            // Y son los cuatro puntos del suelo, proyectados.
            assertEquals(proyectar(3.0, 4.0, 0.0, v), sombra[0])
            assertEquals(proyectar(5.0, 9.0, 0.0, v), sombra[2])
        }
    }

    /**
     * **La sombra es el tirador**, y agarra por el suelo y no por el bulto.
     *
     * Un dedo puesto sobre la tapa de una caja alta **no** toca su sombra: por
     * ahí no se mueve. Es a propósito — agarrando por la caja no habría forma de
     * saber si el arrastre quiere mover por el suelo o levantar.
     */
    @Test
    fun `la sombra agarra por el suelo`() {
        val torre = Solido(0.0, 0.0, 2.0, 2.0, 10.0)
        for (v in vistas) {
            val centroDeLaHuella = proyectar(1.0, 1.0, 0.0, v)
            assertTrue("vista $v", tocaLaSombra(torre, centroDeLaHuella, v))

            val techo = proyectar(1.0, 1.0, 10.0, v)
            assertFalse("vista $v: el techo no es tirador", tocaLaSombra(torre, techo, v))

            val lejos = proyectar(30.0, 30.0, 0.0, v)
            assertFalse("vista $v", tocaLaSombra(torre, lejos, v))
        }
    }

    /** Una caja muy plana se puede agarrar igual gracias al margen. */
    @Test
    fun `el margen ensancha el tirador`() {
        val plana = Solido(0.0, 0.0, 4.0, 0.2, 0.1)
        val justoAlLado = proyectar(2.0, 0.5, 0.0, Vista.CERO)
        assertFalse(tocaLaSombra(plana, justoAlLado, Vista.CERO))
        assertTrue(tocaLaSombra(plana, justoAlLado, Vista.CERO, margen = 0.5))
    }

    // ---------------------------------------------------------------------
    // Casos límite
    // ---------------------------------------------------------------------

    /** Altura cero: queda la huella y solo se ve la tapa, que es la propia huella. */
    @Test
    fun `sin altura solo queda la huella`() {
        val plana = Solido(1.0, 1.0, 3.0, 2.0, 0.0)
        for (v in vistas) {
            val caras = carasDeSolido(plana, v)
            assertEquals("vista $v: $caras", 1, caras.size)
            assertEquals(Cara.TAPA, caras[0].cara)
            // Y la tapa cae exactamente encima de la sombra.
            assertEquals(sombraEnElSuelo(plana, v).toSet(), caras[0].poligono.toSet())
        }
    }

    /**
     * Huella de ancho cero: la caja se queda **en un tabique**, y de un tabique
     * se ve una cara y no dos.
     *
     * Las otras cuatro caras se han quedado sin superficie y el criterio del área
     * las descarta solo, sin ningún caso especial. Que la trasera no se cuele
     * importa aquí más que en una caja normal: al pintarla translúcida se vería
     * el doble de tinta justo donde no hay ningún grosor que la justifique.
     */
    @Test
    fun `un tabique enseña una sola cara`() {
        for (v in vistas) {
            val deCanto = carasDeSolido(Solido(0.0, 0.0, 0.0, 5.0, 5.0), v)
            assertEquals("vista $v: $deCanto", 1, deCanto.size)
            assertTrue(deCanto[0].cara in setOf(Cara.PARED_X_MAS, Cara.PARED_X_MENOS))
            assertTrue(areaConSigno(deCanto[0].poligono) > 0)

            val delOtroLado = carasDeSolido(Solido(0.0, 0.0, 5.0, 0.0, 5.0), v)
            assertEquals("vista $v: $delOtroLado", 1, delOtroLado.size)
            assertTrue(delOtroLado[0].cara in setOf(Cara.PARED_Y_MAS, Cara.PARED_Y_MENOS))
        }
    }

    /** Y de un sólido sin ninguna medida no se pinta nada, sin reventar. */
    @Test
    fun `un sólido sin medidas no pinta nada`() {
        for (v in vistas) {
            assertTrue(carasDeSolido(Solido(7.0, 7.0, 0.0, 0.0, 0.0), v).isEmpty())
            assertTrue(carasDeSolido(Solido(7.0, 7.0, 0.0, 0.0, 9.0), v).isEmpty())
            assertTrue(carasVisibles(Solido(7.0, 7.0, 3.0, 3.0, 0.0), v).isNotEmpty())
        }
    }

    /** Con coordenadas negativas todo sigue igual: el mundo no empieza en el cero. */
    @Test
    fun `las coordenadas negativas no cambian nada`() {
        val lejos = Solido(-40.0, -25.0, 3.0, 3.0, 3.0)
        for (v in vistas) {
            assertEquals(3, carasDeSolido(lejos, v).size)
            assertEquals(
                carasDeSolido(lejos, v).map { it.cara },
                carasDeSolido(Solido(0.0, 0.0, 3.0, 3.0, 3.0), v).map { it.cara }
            )
        }
    }

    /** La caja que envuelve lo dibujado: la del hexágono del cubo. */
    @Test
    fun `la caja envolvente encierra los ocho vértices`() {
        val paso = 20.0
        for (v in vistas) {
            val caja = cajaDeSolido(cubo, v, paso)
            assertEquals("vista $v", sqrt(3.0) * paso, caja.width, tol)
            assertEquals("vista $v", 2 * paso, caja.height, tol)
            for (p in verticesDe(cubo).map { proyectar(it, v, paso) }) {
                assertTrue(p.x >= caja.x1 - tol && p.x <= caja.x2 + tol)
                assertTrue(p.y >= caja.y1 - tol && p.y <= caja.y2 + tol)
            }
        }
    }

    /**
     * El área con signo es la del cordón de zapato con la `y` de la pantalla, y
     * **su signo es el contrario que el de `Regiones.areaDe`**. Se deja escrito
     * en una prueba porque las dos funciones se llaman casi igual y confundirlas
     * daría una caja con las caras al revés.
     */
    @Test
    fun `el signo del área es el contrario que el de las regiones`() {
        val cuadrado = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))
        assertEquals(100.0, areaConSigno(cuadrado), tol)
        assertEquals(-100.0, areaConSigno(cuadrado.reversed()), tol)
        assertTrue(abs(areaConSigno(cuadrado) + areaDe(cuadrado)) < tol)
        assertEquals(0.0, areaConSigno(listOf(Pt(0.0, 0.0), Pt(1.0, 1.0))), tol)
    }
}
