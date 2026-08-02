package com.forge.pixpin.croquis

import org.junit.Assert.assertEquals
import org.junit.Test

class CroquisVistaTest {

    private val ancho = 800
    private val alto = 600

    @Test
    fun `el centro de la vista cae en el centro de la pantalla`() {
        val vista = Vista(centro = P(0.0, 0.0), pixelsPorMetro = 1.0)
        val px = CroquisGeometria.aPantalla(P(0.0, 0.0), vista, ancho, alto)
        assertEquals(400f, px.x, 1e-4f)
        assertEquals(300f, px.y, 1e-4f)
    }

    @Test
    fun `el zoom convierte metros en pixeles`() {
        val vista = Vista(centro = P(0.0, 0.0), pixelsPorMetro = 2.0)
        val px = CroquisGeometria.aPantalla(P(3.0, 0.0), vista, ancho, alto)
        assertEquals(406f, px.x, 1e-4f)
    }

    @Test
    fun `la Y del mundo sube y la de la pantalla baja`() {
        val vista = Vista(centro = P(0.0, 0.0), pixelsPorMetro = 1.0)
        val arriba = CroquisGeometria.aPantalla(P(0.0, 10.0), vista, ancho, alto)
        // En CAD la Y crece hacia arriba; en pantalla, hacia abajo. Un punto
        // «arriba» tiene que salir con una Y de pantalla MENOR.
        assertEquals(290f, arriba.y, 1e-4f)
    }

    @Test
    fun `ida y vuelta devuelve el mismo punto`() {
        val vista = Vista(centro = P(12.5, -3.25), pixelsPorMetro = 37.0)
        val original = P(14.125, -1.5)
        val px = CroquisGeometria.aPantalla(original, vista, ancho, alto)
        val vuelta = CroquisGeometria.aMundo(px, vista, ancho, alto)
        assertEquals(original.x, vuelta.x, 1e-6)
        assertEquals(original.y, vuelta.y, 1e-6)
    }

    @Test
    fun `a coordenadas UTM, la ida y vuelta conserva los milimetros`() {
        // Este es el motivo de restar el centro de la vista ANTES de pasar a
        // Float. Convirtiendo la coordenada absoluta, estos 3 mm sobre
        // 8.240.708 m se pierden en el redondeo del Float.
        val vista = Vista(centro = P(709927.0, 8240708.0), pixelsPorMetro = 1000.0)
        val original = P(709927.003, 8240708.002)
        val px = CroquisGeometria.aPantalla(original, vista, ancho, alto)
        val vuelta = CroquisGeometria.aMundo(px, vista, ancho, alto)
        assertEquals(0.003, vuelta.x - 709927.0, 1e-6)
        assertEquals(0.002, vuelta.y - 8240708.0, 1e-6)
    }
}
