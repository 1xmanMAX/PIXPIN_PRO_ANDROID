package com.forge.pixpin.croquis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class CroquisGeometriaTest {

    @Test
    fun `calibrar una linea de 300 px que mide 4,20 m da 0,014 m por pixel`() {
        val escala = CroquisGeometria.calibrar(P(0.0, 0.0), P(300.0, 0.0), 4.20)
        assertEquals(0.014, escala!!, 1e-9)
    }

    @Test
    fun `calibrar con longitud cero no da escala`() {
        assertNull(CroquisGeometria.calibrar(P(0.0, 0.0), P(300.0, 0.0), 0.0))
    }

    @Test
    fun `calibrar con longitud negativa no da escala`() {
        assertNull(CroquisGeometria.calibrar(P(0.0, 0.0), P(300.0, 0.0), -4.20))
    }

    @Test
    fun `calibrar sin arrastrar, con los dos puntos en el mismo sitio, no da escala`() {
        assertNull(CroquisGeometria.calibrar(P(50.0, 50.0), P(50.0, 50.0), 4.20))
    }

    @Test
    fun `la distancia entre dos puntos es la euclidea`() {
        assertEquals(5.0, CroquisGeometria.distancia(P(0.0, 0.0), P(3.0, 4.0)), 1e-12)
    }

    // --- Imantado a extremos: lo que hace que las lineas conecten de verdad ---

    @Test
    fun `un punto cerca de un extremo salta exactamente encima`() {
        val extremos = listOf(P(5.0, 5.0), P(20.0, 0.0))
        val r = CroquisGeometria.imantar(P(5.08, 5.0), extremos, 0.12)
        assertEquals(5.0, r.x, 1e-12)
        assertEquals(5.0, r.y, 1e-12)
    }

    @Test
    fun `un punto lejos de todo extremo se queda donde esta`() {
        val extremos = listOf(P(5.0, 5.0), P(20.0, 0.0))
        val r = CroquisGeometria.imantar(P(5.40, 5.0), extremos, 0.12)
        assertEquals(5.40, r.x, 1e-12)
    }

    @Test
    fun `entre dos extremos a tiro, imanta al mas cercano`() {
        val extremos = listOf(P(0.0, 0.0), P(0.10, 0.0))
        val r = CroquisGeometria.imantar(P(0.09, 0.0), extremos, 0.5)
        assertEquals(0.10, r.x, 1e-12)
    }

    @Test
    fun `sin extremos a los que agarrarse, el punto no se mueve`() {
        val r = CroquisGeometria.imantar(P(3.0, 7.0), emptyList(), 0.12)
        assertEquals(3.0, r.x, 1e-12)
        assertEquals(7.0, r.y, 1e-12)
    }

    // --- Orto: enderezar a 0, 45 o 90 grados ---

    @Test
    fun `una linea a 3 grados se endereza a la horizontal conservando su longitud`() {
        val hasta = enAngulo(3.0, 10.0)
        val r = CroquisGeometria.orto(P(0.0, 0.0), hasta, 5.0)
        assertEquals(10.0, r.x, 1e-9)
        assertEquals(0.0, r.y, 1e-9)
    }

    @Test
    fun `una linea a 43 grados se endereza a 45`() {
        val hasta = enAngulo(43.0, 10.0)
        val r = CroquisGeometria.orto(P(0.0, 0.0), hasta, 5.0)
        assertEquals(10.0 * Math.cos(Math.PI / 4), r.x, 1e-9)
        assertEquals(10.0 * Math.sin(Math.PI / 4), r.y, 1e-9)
    }

    @Test
    fun `una linea a 20 grados se queda como esta`() {
        val hasta = enAngulo(20.0, 10.0)
        val r = CroquisGeometria.orto(P(0.0, 0.0), hasta, 5.0)
        assertEquals(hasta.x, r.x, 1e-12)
        assertEquals(hasta.y, r.y, 1e-12)
    }

    // --- Longitud tecleada: manda el numero, no el dedo ---

    @Test
    fun `teclear la longitud recoloca el extremo conservando la direccion`() {
        val r = CroquisGeometria.conLongitud(P(0.0, 0.0), P(3.0, 4.0), 10.0)
        assertEquals(6.0, r!!.x, 1e-12)
        assertEquals(8.0, r.y, 1e-12)
    }

    @Test
    fun `teclear la longitud sobre una linea sin direccion no hace nada`() {
        assertNull(CroquisGeometria.conLongitud(P(2.0, 2.0), P(2.0, 2.0), 10.0))
    }

    @Test
    fun `teclear una longitud que no sea positiva se rechaza`() {
        assertNull(CroquisGeometria.conLongitud(P(0.0, 0.0), P(3.0, 4.0), 0.0))
    }

    // --- Cota viva: calcula su cifra, no la guarda ---

    @Test
    fun `la cota mide la distancia entre sus dos puntos`() {
        val cota = Entidad.Cota(P(0.0, 0.0), P(3.0, 4.0))
        assertEquals(5.0, cota.medida(), 1e-12)
    }

    @Test
    fun `mover un extremo de la cota cambia su cifra`() {
        val cota = Entidad.Cota(P(0.0, 0.0), P(3.0, 4.0))
        val movida = cota.copy(b = P(6.0, 8.0))
        assertEquals(10.0, movida.medida(), 1e-12)
    }

    // --- La razon de ser del Double ---

    @Test
    fun `a coordenadas UTM de un plano real, la distancia conserva los milimetros`() {
        // Y de 8.240.708 m es lo que trae un plano de topografia en UTM. Con
        // Float, estos 3 mm desaparecen: son la septima cifra significativa.
        val a = P(709927.0, 8240708.000)
        val b = P(709927.0, 8240708.003)
        assertEquals(0.003, CroquisGeometria.distancia(a, b), 1e-9)
    }

    // --- Formato ---

    @Test
    fun `una medida se escribe con coma decimal y su unidad`() {
        assertEquals("4,20 m", CroquisGeometria.formatear(4.2, 2, Locale("es", "ES")))
    }

    @Test
    fun `los decimales pedidos se respetan aunque el numero sea redondo`() {
        assertEquals("7,000 m", CroquisGeometria.formatear(7.0, 3, Locale("es", "ES")))
    }

    private fun enAngulo(grados: Double, longitud: Double): P {
        val rad = Math.toRadians(grados)
        return P(longitud * Math.cos(rad), longitud * Math.sin(rad))
    }
}
