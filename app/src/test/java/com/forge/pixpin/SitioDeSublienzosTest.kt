package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Cada sublienzo, por el lado más cercano a su zona.**
 *
 * Iban alternando izquierda y derecha por orden de llegada, así que una zona del borde izquierdo
 * salía enseñada a la derecha y su línea cruzaba la página entera. Lo que se comprueba aquí es lo
 * que el usuario mira: el lado que toca, lo más cerca posible de su zona, y que dos no se monten.
 * Ver [SitioDeSublienzos].
 */
class SitioDeSublienzosTest {

    private val pagina = Bounds(0.0, 0.0, 1000.0, 1400.0)
    private val lado = 300.0
    private val hueco = 30.0

    private fun colocar(vararg zonas: Bounds) =
        SitioDeSublienzos.colocar(pagina, zonas.toList(), zonas.map { 1.0 }, lado, hueco)

    private fun sePisan(a: SitioDeSublienzos.Puesto, b: SitioDeSublienzos.Puesto): Boolean =
        a.x < b.x + b.ancho && b.x < a.x + a.ancho && a.y < b.y + b.alto && b.y < a.y + a.alto

    @Test
    fun `la zona de la izquierda sale por la izquierda`() {
        val puestos = colocar(
            Bounds(20.0, 600.0, 200.0, 800.0),      // pegada al borde izquierdo
            Bounds(820.0, 300.0, 980.0, 460.0)      // pegada al derecho
        )
        assertEquals(SitioDeSublienzos.Lado.IZQUIERDA, puestos[0].lado)
        assertEquals(SitioDeSublienzos.Lado.DERECHA, puestos[1].lado)
        // Y fuera de la página, no encima.
        assertTrue(puestos[0].x + puestos[0].ancho <= pagina.x1)
        assertTrue(puestos[1].x >= pagina.x2)
    }

    @Test
    fun `una zona de arriba sale por arriba y una de abajo por abajo`() {
        val puestos = colocar(
            Bounds(400.0, 10.0, 600.0, 120.0),
            Bounds(400.0, 1300.0, 600.0, 1390.0)
        )
        assertEquals(SitioDeSublienzos.Lado.ARRIBA, puestos[0].lado)
        assertEquals(SitioDeSublienzos.Lado.ABAJO, puestos[1].lado)
        assertTrue(puestos[0].y + puestos[0].alto <= pagina.y1)
        assertTrue(puestos[1].y >= pagina.y2)
    }

    /** Y a la altura de su zona: la línea que las une sale casi recta. */
    @Test
    fun `cada uno queda a la altura de su zona`() {
        val zona = Bounds(20.0, 900.0, 200.0, 1000.0)
        val p = colocar(zona).single()
        assertEquals(zona.midY, p.y + p.alto / 2, 1.0)
    }

    @Test
    fun `dos zonas del mismo lado y la misma altura no se montan`() {
        val puestos = colocar(
            Bounds(20.0, 600.0, 200.0, 700.0),
            Bounds(30.0, 620.0, 210.0, 720.0)
        )
        assertTrue("las dos por la izquierda", puestos.all { it.lado == SitioDeSublienzos.Lado.IZQUIERDA })
        assertTrue("se pisan", !sePisan(puestos[0], puestos[1]))
        // Y la que se corre lo hace lo mínimo: quedan pegadas, con su hueco.
        val separacion = kotlin.math.abs(puestos[0].y - puestos[1].y)
        assertTrue("se fue demasiado lejos: $separacion", separacion <= lado + hueco + 1.0)
    }

    @Test
    fun `cuando un lado se llena se usa el siguiente mas cercano`() {
        // Seis zonas pegadas al borde izquierdo: en la banda izquierda caben cuatro (1400 / 330).
        val zonas = (0 until 6).map { Bounds(20.0, 100.0 + it * 30.0, 200.0, 200.0 + it * 30.0) }
        val puestos = SitioDeSublienzos.colocar(pagina, zonas, zonas.map { 1.0 }, lado, hueco)
        assertTrue("alguna tuvo que irse a otro lado", puestos.map { it.lado }.distinct().size > 1)
        for (i in puestos.indices) for (j in i + 1 until puestos.size) {
            assertTrue("se pisan $i y $j", !sePisan(puestos[i], puestos[j]))
        }
        assertEquals(6, puestos.size)
    }

    /** Ni con veinte se pierde ninguno, aunque haya que salirse de la página. */
    @Test
    fun `nunca se pierde un sublienzo`() {
        val zonas = (0 until 20).map { Bounds(400.0, 100.0 + it * 10.0, 600.0, 200.0 + it * 10.0) }
        val puestos = SitioDeSublienzos.colocar(pagina, zonas, zonas.map { 1.0 }, lado, hueco)
        assertEquals(20, puestos.size)
        for (i in puestos.indices) for (j in i + 1 until puestos.size) {
            assertTrue("se pisan $i y $j", !sePisan(puestos[i], puestos[j]))
        }
    }

    /** Un sublienzo alargado se ajusta dentro del cuadrado: una banda no se come la página. */
    @Test
    fun `el sublienzo se ajusta a su cuadrado`() {
        val zonas = listOf(Bounds(20.0, 600.0, 200.0, 700.0))
        val alto = SitioDeSublienzos.colocar(pagina, zonas, listOf(3.0), lado, hueco).single()
        assertEquals(lado, alto.alto, 0.001)
        assertEquals(lado / 3, alto.ancho, 0.001)
        val ancho = SitioDeSublienzos.colocar(pagina, zonas, listOf(0.5), lado, hueco).single()
        assertEquals(lado, ancho.ancho, 0.001)
        assertEquals(lado / 2, ancho.alto, 0.001)
    }

    @Test
    fun `la caja de todo junto abarca los sublienzos`() {
        val puestos = colocar(Bounds(20.0, 600.0, 200.0, 700.0), Bounds(400.0, 10.0, 600.0, 120.0))
        val caja = SitioDeSublienzos.todoJunto(pagina, puestos, 10.0)
        assertTrue(caja.x1 <= puestos[0].x - 10.0)
        assertTrue(caja.y1 <= puestos[1].y - 10.0)
        assertTrue(caja.x2 >= pagina.x2)
        assertTrue(caja.y2 >= pagina.y2)
    }

    /** El orden de salida es el de entrada, aunque se repartan por cercanía. */
    @Test
    fun `devuelve en el orden en que llegan`() {
        val puestos = colocar(
            Bounds(400.0, 700.0, 600.0, 800.0),     // en el centro: elige después
            Bounds(10.0, 700.0, 120.0, 800.0)       // pegada al borde: elige antes
        )
        assertEquals(SitioDeSublienzos.Lado.IZQUIERDA, puestos[1].lado)
        assertEquals(2, puestos.size)
    }
}
