package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Varias páginas en un lienzo**: dónde cae cada una y a cuántos píxeles se pinta.
 *
 * Lo que se comprueba es lo que se ve: que las páginas no se pisen, que la separación se note y
 * sea la misma entre todas, que el conjunto salga lo más cuadrado posible —que es lo que se puede
 * pasear en una pantalla— y que repartir los píxeles no deje una página con la mitad de detalle
 * que su vecina. Ver [FusionDePaginas].
 */
class FusionDePaginasTest {

    private val a4 = 595.0 to 842.0
    private val a4Apaisado = 842.0 to 595.0

    @Test
    fun `las paginas verticales van en fila y las apaisadas en columna`() {
        assertTrue("tres A4 verticales, en fila", FusionDePaginas.enFila(List(3) { a4 }))
        assertTrue("apaisadas, en columna", !FusionDePaginas.enFila(List(3) { a4Apaisado }))
    }

    /** Y lo decide la página media: una portada apaisada no vuelca a los planos verticales. */
    @Test
    fun `una pagina distinta no manda sobre las demas`() {
        assertTrue(FusionDePaginas.enFila(listOf(a4Apaisado, a4, a4, a4)))
    }

    @Test
    fun `en fila las paginas se separan y no se pisan`() {
        val tamanos = List(3) { 1000.0 to 1400.0 }
        val hueco = FusionDePaginas.separacion(tamanos)
        assertTrue("la separación se tiene que notar", hueco > 20.0)
        val sitios = FusionDePaginas.sitios(tamanos, enFila = true)
        assertEquals(3, sitios.size)
        assertEquals(0.0, sitios[0].x, 0.001)
        assertEquals(1000.0 + hueco, sitios[1].x, 0.001)
        assertEquals(2 * (1000.0 + hueco), sitios[2].x, 0.001)
        // Alineadas por arriba, y ninguna encima de otra.
        assertTrue(sitios.all { it.y == 0.0 })
        for ((a, b) in sitios.zipWithNext()) assertTrue("se pisan", a.x + a.ancho < b.x)
    }

    @Test
    fun `en columna se apilan con el mismo hueco`() {
        val tamanos = listOf(1000.0 to 700.0, 1000.0 to 900.0)
        val hueco = FusionDePaginas.separacion(tamanos)
        val sitios = FusionDePaginas.sitios(tamanos, enFila = false)
        assertEquals(0.0, sitios[0].y, 0.001)
        assertEquals(700.0 + hueco, sitios[1].y, 0.001)
        assertTrue(sitios.all { it.x == 0.0 })
    }

    /** Ninguna página con la mitad de detalle que su vecina: los píxeles van con el papel. */
    @Test
    fun `una pagina del doble de ancha se lleva el doble de pixeles`() {
        val anchos = FusionDePaginas.anchos(listOf(500.0 to 800.0, 1000.0 to 800.0))
        assertEquals(2.0, anchos[1].toDouble() / anchos[0], 0.05)
    }

    @Test
    fun `entre todas no se pasan del presupuesto`() {
        val medidas = List(6) { 2384.0 to 3370.0 }          // seis planos A0
        val anchos = FusionDePaginas.anchos(medidas)
        val pixeles = anchos.indices.sumOf { i ->
            val alto = anchos[i] * medidas[i].second / medidas[i].first
            anchos[i].toDouble() * alto
        }
        assertTrue("son $pixeles píxeles", pixeles <= FusionDePaginas.PIXELES_EN_TOTAL * 1.05)
        assertTrue("y aun así se tienen que leer", anchos.all { it >= FusionDePaginas.ANCHO_MINIMO })
    }

    /** Con dos páginas sobra presupuesto, y entonces manda el tope de lo que hace falta. */
    @Test
    fun `dos paginas no se pintan mas grandes de lo que sirve`() {
        val anchos = FusionDePaginas.anchos(List(2) { a4 })
        assertEquals(FusionDePaginas.ANCHO_MAXIMO, anchos[0])
    }

    @Test
    fun `el nombre dice que paginas son`() {
        assertEquals("Páginas 4 a 6", FusionDePaginas.nombre(listOf(3, 4, 5)))
        assertEquals("Páginas 4, 6 y 9", FusionDePaginas.nombre(listOf(3, 5, 8)))
        assertEquals("Página 2", FusionDePaginas.nombre(listOf(1)))
    }

    /** Y cada página lleva el grupo en su rótulo, para reconocerla en la rejilla del proyecto. */
    @Test
    fun `cada pagina dice de que fusion viene`() {
        assertEquals("4 a 6 · pág. 5", FusionDePaginas.rotulo(listOf(3, 4, 5), 4))
        assertEquals("4, 6 y 9 · pág. 9", FusionDePaginas.rotulo(listOf(3, 5, 8), 8))
    }
}
