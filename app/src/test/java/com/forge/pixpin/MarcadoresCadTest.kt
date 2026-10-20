package com.forge.pixpin

import com.forge.pixpin.motor.Estado3d
import com.forge.pixpin.motor.MarcadoresCad
import com.forge.pixpin.motor.Marcador2d
import com.forge.pixpin.motor.Marcador3d
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los ficheros de marcadores de planos y modelos tal como los escribe el PC (`marcadores.rs`). */
class MarcadoresCadTest {
    private val delPc = """{ "version": 1,
  "plano":  [ {"id":"m18f","nombre":"Muro","x":10.5,"y":-3,"ancho":40,"alto":20} ],
  "modelo": [ {"id":"m19a","nombre":"","estado":{"objetivo":[100,200,3],"rumbo":0.5,"altura":0.3,"dist":25,
     "seccion":[0,0,0,10,10,10],"aislado":null,"ocultos":["n:2","t:IfcWall"],"claro":false,"aristas":true,"futuro":7}} ],
  "otra": {"de":"otro aparato"} }"""

    @Test
    fun `lee lo del PC y lo vuelve a escribir sin perder lo que no conoce`() {
        val c = MarcadoresCad.deTextos(delPc, null)
        assertEquals(Marcador2d("m18f", "Muro", 10.5, -3.0, 40.0, 20.0), c.plano.single())
        val e = c.modelo.single().estado
        assertEquals(200.0, e.objetivo[1], 0.0)
        assertEquals(listOf("n:2", "t:IfcWall"), e.ocultos)
        assertEquals(10.0, e.seccion!![5], 0.0)
        val otra = MarcadoresCad.deTextos(c.textoDeVistas(), null)
        assertEquals(c.plano, otra.plano)
        assertEquals(c.modelo, otra.modelo)
        assertTrue(c.textoDeVistas().contains("\"futuro\""))
        assertTrue(c.textoDeVistas().contains("otro aparato"))
    }

    @Test
    fun `vacio se escribe con las dos listas`() {
        val t = MarcadoresCad().textoDeVistas()
        assertTrue(t.contains("\"plano\": []") && t.contains("\"modelo\": []"))
        assertTrue(MarcadoresCad().textoDeMarcos().contains("\"marcos\": []"))
    }

    @Test
    fun `coincide con margen y sin contar el tema`() {
        val a = Estado3d(doubleArrayOf(0.0, 0.0, 0.0), 0.1, 0.2, 10.0)
        assertTrue(MarcadoresCad.coincide(a.copy(objetivo = doubleArrayOf(0.001, 0.0, 0.0), claro = true), a))
        assertTrue(MarcadoresCad.coincide(a.copy(rumbo = 0.1 + 2 * Math.PI), a))
        assertFalse(MarcadoresCad.coincide(a.copy(dist = 11.0), a))
        assertFalse(MarcadoresCad.coincide(a.copy(ocultos = listOf("n:1")), a))
        assertFalse(MarcadoresCad.coincide(a.copy(seccion = DoubleArray(6)), a))
    }

    @Test
    fun `los marcos del 19-oct dentro de vistas se leen y el normal va y vuelve`() {
        val viejo = """{"version":1,"plano":[],"modelo":[],"marcos":[{"caja":[-0.2,-0.1,0.3,0.2],"estado":{"objetivo":[0,0,0],"rumbo":0,"altura":0,"dist":1}}]}"""
        val c = MarcadoresCad.deTextos(viejo, null)
        assertEquals(1, c.marcos.size)
        assertTrue(c.marcos[0].id.startsWith("m"))
        val n = MarcadoresCad.aNormal(300.0, 100.0, 800, 400)
        val p = MarcadoresCad.deNormal(n[0], n[1], 800, 400)
        assertEquals(300.0, p[0], 1e-9); assertEquals(100.0, p[1], 1e-9)
        assertEquals(c.marcos, MarcadoresCad.deTextos(null, c.textoDeMarcos()).marcos)
        assertEquals(listOf(Marcador3d("x", "", c.marcos[0].estado)).size, 1)
    }
}
