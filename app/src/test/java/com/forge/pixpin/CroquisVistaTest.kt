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

    // --- Extension: el rectangulo que ocupa todo, para encajarlo en la hoja ---

    @Test
    fun `la extension abarca todas las entidades`() {
        val croquis = Croquis(
            entidades = listOf(
                Entidad.Linea(P(0.0, 0.0), P(10.0, 2.0)),
                Entidad.Circulo(P(-3.0, 5.0), 1.0)
            )
        )
        val (min, max) = CroquisGeometria.extension(croquis)!!
        // El circulo manda por la izquierda (-3 menos su radio) y por arriba.
        assertEquals(-4.0, min.x, 1e-12)
        assertEquals(0.0, min.y, 1e-12)
        assertEquals(10.0, max.x, 1e-12)
        assertEquals(6.0, max.y, 1e-12)
    }

    @Test
    fun `un croquis vacio no tiene extension`() {
        assertEquals(null, CroquisGeometria.extension(Croquis()))
    }

    @Test
    fun `encajar en la hoja usa el lado que se queda corto`() {
        val croquis = Croquis(entidades = listOf(Entidad.Linea(P(0.0, 0.0), P(10.0, 5.0))))
        // Hoja de 800x600 con 40 de margen deja 720x520 utiles.
        // 720/10 = 72 px/m contra 520/5 = 104 px/m: manda el 72.
        val vista = CroquisGeometria.vistaQueEncaja(croquis, 800, 600, 40)!!
        assertEquals(72.0, vista.pixelsPorMetro, 1e-9)
        assertEquals(5.0, vista.centro.x, 1e-9)
        assertEquals(2.5, vista.centro.y, 1e-9)
    }

    @Test
    fun `un croquis vacio no se puede encajar`() {
        assertEquals(null, CroquisGeometria.vistaQueEncaja(Croquis(), 800, 600, 40))
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
