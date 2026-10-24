package com.forge.pixpin.planos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los marcadores 2D de un plano con el formato del PC (`FicheroDeVistas` de `pixpin-cad`). */
class MarcadoresDelPlanoTest {
    @Test fun leeElDelPcYConservaLoQueNoConoce() {
        val delPc = """{"version":1,"plano":[{"id":"m1","nombre":"","x":1000.5,"y":-20,"ancho":800,"alto":600}],
            "modelo":[{"id":"m2","nombre":"","estado":{"objetivo":[0,0,0],"rumbo":0,"altura":0,"dist":1}}],"otro":7}"""
        val a = MarcadoresDelPlano.deTexto(delPc)
        assertEquals(listOf(MarcadoresDelPlano.Marcador("m1", "", 1000.5, -20.0, 800.0, 600.0)), a.plano)
        val vuelta = MarcadoresDelPlano.deTexto(a.con(a.plano + MarcadoresDelPlano.Marcador("m3", "⭐", 1.0, 2.0, 3.0, 4.0)).aTexto())
        assertEquals(2, vuelta.plano.size)
        val texto = a.aTexto()
        assertTrue("el 3D del PC sigue", texto.contains("\"rumbo\""))
        assertTrue("y lo desconocido", texto.contains("\"otro\""))
    }

    @Test fun vacioEsUnaListaEscritaYConLasDos() {
        val t = MarcadoresDelPlano.deTexto(null).aTexto()
        assertTrue(t.contains("\"plano\": []") && t.contains("\"modelo\": []") && t.contains("\"version\": 1"))
    }

    @Test fun laVistaVaYVuelveConElOrigen() {
        val c = CamaraPlano(10.0, 20.0, 0.5)
        val m = MarcadoresDelPlano.deLaVista(c, 1000.0, 2000.0, 800, 600, "⭐", "m9")
        assertEquals(1010.0, m.x, 1e-9)
        assertEquals(400.0, m.ancho, 1e-9)
        assertEquals(c, MarcadoresDelPlano.camaraDe(m, 1000.0, 2000.0, 800, 600))
        // En otra pantalla, cabe todo lo que se veía: manda el lado que más aprieta.
        assertEquals(1.0, MarcadoresDelPlano.camaraDe(m, 1000.0, 2000.0, 400, 600).px, 1e-9)
        assertEquals("⭐", m.emoji)
        assertEquals("📍", m.copy(nombre = "Fachada").emoji)
    }

    @Test fun losDelModeloVanYVuelvenYConservanLoQueNoSeAplica() {
        val delPc = """{"version":1,"plano":[],"modelo":[{"id":"m2","nombre":"","estado":{"objetivo":[1,2,3],"rumbo":0.5,"altura":0.2,"dist":40,
            "seccion":null,"aislado":7,"aislado_clave":"2O2Fr${'$'}t4X7Zf8NOew3FLOH","ocultos":["n:=abc"],"claro":false,"aristas":true}}]}"""
        val a = MarcadoresDelPlano.deTexto(delPc)
        assertEquals(1, a.modelo.size)
        assertEquals(40.0, a.modelo[0].dist, 0.0)
        val nuevo = MarcadoresDelPlano.Marcador3d("m3", "⭐", doubleArrayOf(0.0, 0.0, 1.0), 1.0, 0.3, 10.0)
        val texto = a.conModelo(a.modelo + nuevo).aTexto()
        assertTrue("lo aislado por su clave sigue", texto.contains("aislado_clave"))
        val vuelta = MarcadoresDelPlano.deTexto(texto)
        assertEquals(listOf("m2", "m3"), vuelta.modelo.map { it.id })
        assertEquals(nuevo, vuelta.modelo[1])
    }
}
