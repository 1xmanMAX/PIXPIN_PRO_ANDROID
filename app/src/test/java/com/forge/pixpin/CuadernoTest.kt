package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El cuaderno: **el lienzo infinito puesto en hojas**.
 *
 * Lo que se comprueba aquí es sobre todo que una hoja **no sea un concepto nuevo**: es un
 * marco con tamaño de papel, así que todo lo que el marco ya sabía hacer —recortar el
 * pin, decidir el encuadre, salir como una página de PDF— sigue valiendo sin tocarlo.
 */
class CuadernoTest {

    private val tol = 1e-9

    private fun escena(vararg hojas: Element) = Scene(elements = hojas.toList())

    private fun hoja(
        x: Double = 0.0, y: Double = 0.0,
        ancho: Double = ANCHO_DE_LA_HOJA, alto: Double = ANCHO_DE_LA_HOJA * 1.414,
        papel: TamanoDePapel? = TamanoDePapel.A4,
        pauta: PautaDeHoja = PautaDeHoja.LISA
    ) = newElement(ElementType.FRAME, x, y, ItemStyle(), ancho, alto)
        .copy(papel = papel, pauta = pauta)

    /** La primera hoja cae donde se está mirando, no en el origen del lienzo. */
    @Test
    fun `la primera hoja nace delante`() {
        val nueva = sitioDeLaHojaSiguiente(Scene(), TamanoDePapel.A4, Pt(1000.0, 500.0))
        assertEquals(1000.0, nueva.x + nueva.width / 2, tol)
        assertEquals(500.0, nueva.y + nueva.height / 2, tol)
        assertEquals(ElementType.FRAME, nueva.type)
    }

    /** Y la siguiente, debajo de la última y alineada con ella. */
    @Test
    fun `la hoja siguiente va debajo`() {
        val primera = hoja(x = 100.0, y = 200.0, ancho = 800.0, alto = 1000.0)
        val nueva = sitioDeLaHojaSiguiente(escena(primera), TamanoDePapel.A4, Pt(0.0, 0.0))
        assertEquals(100.0, nueva.x, tol)
        assertEquals(200.0 + 1000.0 + HUECO_ENTRE_HOJAS, nueva.y, tol)
    }

    /** Del ancho de la anterior: un cuaderno no tiene hojas de tamaños distintos. */
    @Test
    fun `la hoja siguiente hereda el ancho`() {
        val estirada = hoja(ancho = 1200.0, alto = 900.0)
        val nueva = sitioDeLaHojaSiguiente(escena(estirada), TamanoDePapel.A4, Pt(0.0, 0.0))
        assertEquals(1200.0, nueva.width, tol)
        // Y el alto sale de la proporción del papel elegido, no del de antes.
        assertEquals(1200.0 * TamanoDePapel.A4.proporcion, nueva.height, 1e-6)
    }

    /** Y su pauta, para no tener que volver a elegirla en cada página. */
    @Test
    fun `la hoja siguiente hereda la pauta`() {
        val rayada = hoja(pauta = PautaDeHoja.RAYADA)
        assertEquals(
            PautaDeHoja.RAYADA,
            sitioDeLaHojaSiguiente(escena(rayada), TamanoDePapel.A4, Pt(0.0, 0.0)).pauta
        )
    }

    /**
     * **El orden sale de dónde están**, no de una lista guardada. Arrastrar una hoja más
     * arriba que otra la pone antes, sin renumerar nada.
     */
    @Test
    fun `el orden es el de la posicion`() {
        val abajo = hoja(y = 2000.0).copy(id = "b")
        val arriba = hoja(y = 0.0).copy(id = "a")
        val orden = hojasEnOrden(escena(abajo, arriba)).map { it.id }
        assertEquals(listOf("a", "b"), orden)
        assertEquals(2, numeroDeHoja(escena(abajo, arriba), abajo))
    }

    // ---- La pauta ----

    /** Lisa no imprime nada. */
    @Test
    fun `la hoja lisa no lleva rayas`() {
        assertTrue(rayasDeLaPauta(hoja(pauta = PautaDeHoja.LISA)).isEmpty())
    }

    /** Rayada, solo horizontales, y todas dentro de la hoja. */
    @Test
    fun `la rayada solo lleva horizontales`() {
        val h = hoja(x = 10.0, y = 20.0, ancho = 200.0, alto = 100.0, pauta = PautaDeHoja.RAYADA)
        val rayas = rayasDeLaPauta(h, paso = 20.0)
        assertTrue(rayas.isNotEmpty())
        assertTrue(rayas.all { kotlin.math.abs(it.first.y - it.second.y) < tol })
        assertTrue(rayas.all { it.first.y > 20.0 && it.second.y < 120.0 })
    }

    /** A cuadros lleva las dos, así que hay más que en la rayada. */
    @Test
    fun `los cuadros llevan las dos direcciones`() {
        val h = hoja(ancho = 200.0, alto = 100.0, pauta = PautaDeHoja.CUADROS)
        val cuadros = rayasDeLaPauta(h, paso = 20.0)
        val rayada = rayasDeLaPauta(h.copy(pauta = PautaDeHoja.RAYADA), paso = 20.0)
        assertTrue(cuadros.size > rayada.size)
        assertTrue(cuadros.any { kotlin.math.abs(it.first.x - it.second.x) < tol })
    }

    /** Y los puntos son segmentos de largo cero: uno por nodo. */
    @Test
    fun `los puntos son nodos sueltos`() {
        val h = hoja(ancho = 100.0, alto = 100.0, pauta = PautaDeHoja.PUNTOS)
        val puntos = rayasDeLaPauta(h, paso = 20.0)
        assertTrue(puntos.isNotEmpty())
        assertTrue(puntos.all { it.first == it.second })
    }

    /** Una hoja más pequeña que su propia pauta no imprime nada, en vez de reventar. */
    @Test
    fun `una hoja diminuta no lleva pauta`() {
        val h = hoja(ancho = 5.0, alto = 5.0, pauta = PautaDeHoja.CUADROS)
        assertTrue(rayasDeLaPauta(h, paso = 20.0).isEmpty())
    }

    /** Un marco de los de siempre —sin tamaño de papel— sigue siendo un marco. */
    @Test
    fun `un marco sin papel no es una hoja`() {
        val marco = hoja(papel = null)
        assertEquals(null, marco.papel)
        assertTrue(marco.isFrame)
    }
}
