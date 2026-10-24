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

    @Test fun coincideComoElPc() {
        val a = MarcadoresDelPlano.Marcador3d("a", "", doubleArrayOf(0.0, 0.0, 0.0), 1.0, 0.3, 100.0)
        assertTrue(MarcadoresDelPlano.coincide(a.copy(objetivo = doubleArrayOf(0.1, 0.0, 0.0)), a))
        org.junit.Assert.assertFalse("lejos", MarcadoresDelPlano.coincide(a.copy(objetivo = doubleArrayOf(1.0, 0.0, 0.0)), a))
        assertTrue("una vuelta entera es la misma", MarcadoresDelPlano.coincide(a.copy(rumbo = 1.0 + 2 * Math.PI), a))
        org.junit.Assert.assertFalse("con corte y sin él", MarcadoresDelPlano.coincide(a.copy(seccion = DoubleArray(6)), a))
        // Lo aislado en el PC no es esta vista (aquí no se aísla por clave).
        val delPc = MarcadoresDelPlano.deTexto("""{"modelo":[{"id":"m","estado":{"objetivo":[0,0,0],"rumbo":1,"altura":0.3,"dist":100,"aislado":3,"ocultos":[]}}]}""").modelo[0]
        org.junit.Assert.assertFalse(MarcadoresDelPlano.coincide(a, delPc))
    }

    @Test fun losMarcos3DVanYVuelvenYLaTintaEnMilesimasDelAlto() {
        val est = MarcadoresDelPlano.Marcador3d("", "", doubleArrayOf(1.0, 2.0, 3.0), 0.5, 0.1, 9.0)
        val f = MarcadoresDelPlano.marcosDeTexto(null).con(listOf(MarcadoresDelPlano.Marco3d("m1", doubleArrayOf(-0.2, -0.1, 0.3, 0.2), est)))
        val vuelta = MarcadoresDelPlano.marcosDeTexto(f.aTexto())
        assertEquals(1, vuelta.marcos.size)
        assertEquals(0.3, vuelta.marcos[0].caja[2], 0.0)
        assertTrue(MarcadoresDelPlano.coincide(vuelta.marcos[0].estado, est))
        // El centro de la pantalla es el (0,0) de la tinta, y la mitad del alto son 500.
        val v = MarcadoresDelPlano.vistaDeTinta(1000, 2000)
        val centro = v.toScreen(com.forge.pixpin.motor.Pt(0.0, 0.0))
        assertEquals(500.0, centro.x, 1e-9); assertEquals(1000.0, centro.y, 1e-9)
        assertEquals(2000.0, v.toScreen(com.forge.pixpin.motor.Pt(0.0, 500.0)).y, 1e-9)
        val n = MarcadoresDelPlano.aNormal(750.0, 500.0, 1000, 2000)
        assertEquals(0.125, n[0], 1e-12); assertEquals(-0.25, n[1], 1e-12)
        assertEquals(750.0, MarcadoresDelPlano.deNormal(n[0], n[1], 1000, 2000)[0], 1e-9)
        assertEquals("anot-X-mm18f_a-b", MarcadoresDelPlano.baseDeLaTinta("anot-X", "m18f_a-b.é"))
    }
}
