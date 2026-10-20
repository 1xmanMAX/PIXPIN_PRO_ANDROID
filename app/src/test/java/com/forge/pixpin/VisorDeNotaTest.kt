package com.forge.pixpin

import com.forge.pixpin.motor.MarcoDoc
import com.forge.pixpin.ui.VisorDeNotaActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **El riel de las notas** (PC `visor_html/capa_web.rs` y `riel.rs`): los marcos en la página miden en
 * la pantalla lo mismo que en el PC, la cámara de la tinta es la del PC y el guion es el suyo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisorDeNotaTest {
    @Test fun `los marcos miden en la pantalla lo mismo que en la ventana`() {
        val m = { c: List<Double>, parte: Int -> MarcoDoc(c, parte) }
        // Pantalla al 150 % y página al 100 %: la raya de 2 px de pantalla son 2 px CSS.
        val s = VisorDeNotaActivity.svgDeLosMarcos(listOf(m(listOf(10.0, 20.0, 110.0, 70.0), 0), m(listOf(0.0, 300.0, 50.0, 350.0), 0)), 1.5, 1.5)
        assertTrue(s, s.contains("""<rect x="10" y="20" width="100" height="50" fill="none" stroke="#0078d4" stroke-width="2"/>"""))
        assertTrue(s.contains(">1</text>") && s.contains(">2</text>"))
        // Con la página al 200 %, más finos en CSS (iguales en pantalla).
        assertTrue(VisorDeNotaActivity.svgDeLosMarcos(listOf(m(listOf(10.0, 20.0, 110.0, 70.0), 0)), 3.0, 1.5).contains("""stroke-width="1""""))
        // Los de otra parte no; sin marcos, nada.
        assertEquals("", VisorDeNotaActivity.svgDeLosMarcos(listOf(m(listOf(0.0, 0.0, 5.0, 5.0), 2)), 1.0, 1.0))
    }

    @Test fun `la camara y la esquina son las del PC`() {
        val s = VisorDeNotaActivity.Companion.Sitio.de(JSONObject("""{"pixpin_riel":"sitio","x":-10,"y":400,"arriba":420,"maximo":900,"dpr":2.5,"vx":0,"vy":3,"escala":1.2,"ax":10,"ay":56,"aw":400,"ah":700}"""))
        assertTrue(s.valido())
        assertEquals(-10.0, s.camX, 1e-9); assertEquals(403.0, s.camY, 1e-9)
        assertEquals(3.0, s.zoom, 1e-9)
        assertEquals(listOf(0.0, 456.0), s.esquina().toList())
        assertEquals(420.0 / 900.0, s.fraccion(), 1e-9)
        // Una página que cabe entera está en 0; un número roto no vale.
        assertEquals(0.0, VisorDeNotaActivity.Companion.Sitio(arriba = 5.0).fraccion(), 0.0)
        assertFalse(VisorDeNotaActivity.Companion.Sitio(x = Double.NaN).valido())
    }

    @Test fun `el guion del riel es el del PC y escapa lo que mete`() {
        assertTrue(VisorDeNotaActivity.INSTALAR.startsWith("(function(){\nif(window.__pixpinRiel)return;"))
        assertTrue(VisorDeNotaActivity.INSTALAR.contains("addEventListener('scroll'"))
        val g = VisorDeNotaActivity.guion("colocar", 12.5, Double.NaN)
        assertTrue(g, g.endsWith("""("colocar",12.5,0.0)"""))
        assertTrue(VisorDeNotaActivity.PUENTE.contains("window.chrome.webview") && VisorDeNotaActivity.PUENTE.contains("Work Sans"))
    }

    @Test fun `la maqueta va y vuelve y dice si el texto cae igual`() {
        val M = VisorDeNotaActivity.Companion.Maqueta
        val m = VisorDeNotaActivity.Companion.Maqueta(768.0, 16.0, 212.5)
        assertEquals(m, M.deTexto(m.aTexto()))
        // Medio píxel de redondeo: cae igual; otra columna u otra letra, no.
        assertTrue(m.coincide(VisorDeNotaActivity.Companion.Maqueta(768.6, 16.0, 40.0)))
        assertFalse(m.coincide(VisorDeNotaActivity.Companion.Maqueta(540.0, 16.0)))
        assertFalse(m.coincide(VisorDeNotaActivity.Companion.Maqueta(768.0, 18.0, 212.5)))
        // Lo que no es una maqueta no se toma por una.
        assertEquals(null, M.deTexto("1.25 10 20"))
        assertEquals(null, M.deTexto("""{"ancho":0,"tam":16}"""))
        // Y un sitio con la medida de la columna se lee (el guion nuevo del PC la manda).
        val s = VisorDeNotaActivity.Companion.Sitio.de(JSONObject("""{"x":1,"y":2,"maq":{"ancho":700,"tam":15,"ox":30}}"""))
        assertEquals(VisorDeNotaActivity.Companion.Maqueta(700.0, 15.0, 30.0), s.maq)
        assertTrue(VisorDeNotaActivity.INSTALAR.contains("__pixpinMaqueta"))
    }
}
