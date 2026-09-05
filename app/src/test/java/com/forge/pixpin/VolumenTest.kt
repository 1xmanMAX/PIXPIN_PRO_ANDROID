package com.forge.pixpin.motor

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que la caja aprendió a hacer: **apoyarse, girarse, repetirse y ser cuña**.
 *
 * Hasta aquí un sólido iba siempre del suelo hacia arriba y a escuadra con la retícula,
 * así que apilar —que es lo que este módulo dice servir para dibujar— no se podía. Todo
 * lo que se comprueba abajo es geometría pura y por eso se comprueba sin pantalla: dónde
 * quedan los vértices, a qué se engancha el imán y qué sale de repetir.
 */
class VolumenTest {

    private val tol = 1e-9

    private fun caja(
        x: Double = 100.0, y: Double = 100.0,
        ancho: Double = 60.0, fondo: Double = 40.0, alto: Double? = 50.0,
        cota: Double = 0.0, giro: Double = 0.0,
        forma: FormaDeSolido = FormaDeSolido.CAJA
    ): Element = newElement(ElementType.SOLIDO, x, y, ItemStyle(), ancho, fondo)
        .copy(altura = alto, cota = cota, giroEnPlanta = giro, formaSolida = forma,
              backgroundColor = "#808080")

    // ---- La cota ----

    /** Apoyada en alto, los ocho vértices suben lo mismo. */
    @Test
    fun `la cota levanta la caja entera`() {
        val suelo = verticesDe(solidoDe(caja(cota = 0.0)))
        val alta = verticesDe(solidoDe(caja(cota = 30.0)))
        for (i in suelo.indices) {
            assertEquals(suelo[i].x, alta[i].x, tol)
            assertEquals(suelo[i].y, alta[i].y, tol)
            assertEquals(suelo[i].z + 30.0, alta[i].z, tol)
        }
    }

    /** Pero la sombra **no**: se queda en el suelo, que es de lo que sirve. */
    @Test
    fun `la sombra no sube con la caja`() {
        val abajo = sombraEnElSuelo(solidoDe(caja(cota = 0.0)), Vista.CERO, PASO_DEL_SOLIDO)
        val arriba = sombraEnElSuelo(solidoDe(caja(cota = 30.0)), Vista.CERO, PASO_DEL_SOLIDO)
        assertEquals(abajo, arriba)
    }

    /**
     * Y la envoltura es la del **cuerpo**, no la del suelo.
     *
     * La sombra dejó de pintarse —en un dibujo con varias piezas eran manchas por todas
     * partes y estorbaban para dibujar encima—, así que el recuadro de la selección se
     * ajusta a lo que se ve: una caja apoyada en alto no arrastra un rectángulo vacío
     * hasta el suelo.
     */
    @Test
    fun `la envoltura de una caja en alto es la del cuerpo`() {
        val alta = caja(cota = 60.0)
        val baja = caja(cota = 0.0)
        val arriba = envolturaDeSolido(alta, Vista.CERO)
        val abajo = envolturaDeSolido(baja, Vista.CERO)
        // Subirla sube la caja entera: ni el techo ni el suelo del recuadro se quedan.
        assertEquals(abajo.y1 - 60.0, arriba.y1, 1e-6)
        assertEquals(abajo.y2 - 60.0, arriba.y2, 1e-6)
    }

    // ---- El imán entre cajas ----

    /**
     * La prueba que resume todo esto: **una caja arrimada a otra se le sube encima**.
     * El origen de la huella acaba en la esquina de la tapa de la de abajo, y la cota
     * pasa a ser la altura de esa tapa.
     */
    @Test
    fun `una caja arrimada se apoya en la tapa de la otra`() {
        val abajo = caja(x = 200.0, y = 200.0, ancho = 60.0, fondo = 40.0, alto = 50.0)
        // La de arriba, casi encima de la esquina del origen de la tapa: a dos píxeles.
        val tapa = proyectar(0.0, 0.0, 50.0, Vista.CERO, PASO_DEL_SOLIDO)
        val arriba = caja(x = 200.0 + tapa.x + 2.0, y = 200.0 + tapa.y + 2.0, cota = 0.0)

        val enganche = imanEntreSolidos(arriba, listOf(abajo), Vista.CERO, 12.0)
        assertNotNull(enganche)
        assertEquals(50.0, enganche!!.cota, tol)
        assertEquals(200.0, enganche.x, tol)
        assertEquals(200.0, enganche.y, tol)
    }

    /** Lejos no engancha: un imán que ata siempre no es un imán, es un pegamento. */
    @Test
    fun `de lejos no engancha nada`() {
        val abajo = caja(x = 200.0, y = 200.0)
        val lejos = caja(x = 900.0, y = 900.0)
        assertNull(imanEntreSolidos(lejos, listOf(abajo), Vista.CERO, 12.0))
    }

    /** Ni consigo misma, que si no una caja sola se quedaría pegada a su propio vértice. */
    @Test
    fun `no se engancha a si misma`() {
        val e = caja()
        assertNull(imanEntreSolidos(e, listOf(e), Vista.CERO, 999.0))
    }

    // ---- Repetir ----

    /** Tres copias a lo alto son tres cotas seguidas, cada una encima de la anterior. */
    @Test
    fun `repetir a lo alto apila`() {
        val e = caja(alto = 50.0, cota = 0.0)
        val copias = repetirSolido(e, 3, EjeDeRepeticion.ALTO, Vista.CERO)
        assertEquals(3, copias.size)
        assertEquals(listOf(50.0, 100.0, 150.0), copias.map { it.cota })
        // A lo alto no se mueven de sitio en planta.
        assertTrue(copias.all { abs(it.x - e.x) < tol && abs(it.y - e.y) < tol })
    }

    /** Y a lo ancho, cada una pegada al lado de la anterior. */
    @Test
    fun `repetir a lo ancho hace una fila`() {
        val e = caja(ancho = 60.0)
        val copias = repetirSolido(e, 2, EjeDeRepeticion.ANCHO, Vista.CERO)
        val paso = proyectar(60.0, 0.0, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
        assertEquals(e.x + paso.x, copias[0].x, tol)
        assertEquals(e.x + paso.x * 2, copias[1].x, tol)
        assertTrue(copias.map { it.id }.toSet().size == 2)
        assertTrue(copias.none { it.id == e.id })
    }

    // ---- La cuña ----

    /** La cuña es la caja con dos vértices bajados hasta la base. */
    @Test
    fun `la cuna baja dos vertices`() {
        val v = verticesDe(solidoDe(caja(forma = FormaDeSolido.CUNA, alto = 50.0)))
        assertEquals(50.0, v[4].z, tol)
        assertEquals(50.0, v[5].z, tol)
        assertEquals(0.0, v[6].z, tol)
        assertEquals(0.0, v[7].z, tol)
    }

    /** Y por eso una de sus caras se cae sola: proyecta área cero. */
    @Test
    fun `la cuna pierde una cara`() {
        val caras = carasDeSolido(solidoDe(caja(forma = FormaDeSolido.CUNA)), Vista.CERO, PASO_DEL_SOLIDO)
        val comoCaja = carasDeSolido(solidoDe(caja()), Vista.CERO, PASO_DEL_SOLIDO)
        assertTrue("${caras.size} vs ${comoCaja.size}", caras.size < comoCaja.size)
    }

    // ---- El giro en planta ----

    /** Media vuelta y la caja vuelve a medir lo mismo: girar no estira. */
    @Test
    fun `el giro no cambia lo que mide`() {
        val recta = verticesDe(solidoDe(caja(giro = 0.0)))
        val torcida = verticesDe(solidoDe(caja(giro = PI / 2)))
        fun lado(v: List<Pt3>) =
            kotlin.math.hypot(v[1].x - v[0].x, v[1].y - v[0].y)
        assertEquals(lado(recta), lado(torcida), 1e-9)
    }

    /** Un cuarto de vuelta lleva el eje del ancho al del fondo. */
    @Test
    fun `un cuarto de vuelta cambia los ejes de sitio`() {
        val v = verticesDe(solidoDe(caja(ancho = 60.0, fondo = 40.0, giro = PI / 2)))
        // La esquina del ancho se ha ido al eje `y`.
        assertEquals(0.0, v[1].x, 1e-9)
        assertEquals(60.0, v[1].y, 1e-9)
    }

    /** El origen de la huella no se mueve al girar: es el punto que el elemento guarda. */
    @Test
    fun `girar no mueve el origen`() {
        for (g in listOf(0.0, PI / 6, PI / 2, PI)) {
            val v = verticesDe(solidoDe(caja(giro = g)))
            assertEquals(0.0, v[0].x, 1e-9)
            assertEquals(0.0, v[0].y, 1e-9)
        }
    }

    /** Y tocar la sombra sigue funcionando con la planta torcida. */
    @Test
    fun `la sombra girada se sigue tocando donde se ve`() {
        val s = solidoDe(caja(ancho = 60.0, fondo = 40.0, giro = PI / 4))
        // El centro de la huella, vaya girada como vaya, cae dentro.
        val centro = enPlantaDePrueba(s, 30.0, 20.0)
        val pantalla = proyectar(centro.x, centro.y, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
        assertTrue(tocaLaSombra(s, pantalla, Vista.CERO, PASO_DEL_SOLIDO))
        // Y un punto claramente fuera, no.
        val fuera = proyectar(500.0, 500.0, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
        assertTrue(!tocaLaSombra(s, fuera, Vista.CERO, PASO_DEL_SOLIDO))
    }

    /** Copia de la cuenta del giro, para no abrir la de dentro solo por la prueba. */
    private fun enPlantaDePrueba(s: Solido, dx: Double, dy: Double): Pt {
        val c = kotlin.math.cos(s.giro)
        val sn = kotlin.math.sin(s.giro)
        return Pt(s.x + dx * c - dy * sn, s.y + dx * sn + dy * c)
    }

    // ---- Las aristas ocultas ----

    /** Una caja enseña tres caras y esconde tres aristas. */
    @Test
    fun `una caja esconde tres aristas`() {
        val ocultas = aristasOcultasDeSolido(solidoDe(caja()), Vista.CERO, PASO_DEL_SOLIDO)
        assertEquals(3, ocultas.size)
    }

    /** Y ninguna de largo cero, que sería un punto suelto en medio del dibujo. */
    @Test
    fun `no se cuelan aristas de largo cero`() {
        for (forma in FormaDeSolido.entries) {
            val ocultas = aristasOcultasDeSolido(
                solidoDe(caja(forma = forma)), Vista.CERO, PASO_DEL_SOLIDO
            )
            assertTrue(
                "$forma",
                ocultas.all { kotlin.math.hypot(it.second.x - it.first.x, it.second.y - it.first.y) > 0.5 }
            )
        }
    }
}
