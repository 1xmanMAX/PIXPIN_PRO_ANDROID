package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los sublienzos pegados a su página en la web. Ver [SublienzosWeb]. */
class SublienzosWebTest {
    private val pagina = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1000\" height=\"1400\" viewBox=\"0 0 1000 1400\">\n" +
        "<defs><clipPath id=\"c1\"><rect/></clipPath></defs><rect x=\"100\" y=\"200\" width=\"200\" height=\"100\"/>\n</svg>\n"
    private val sub = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"200\" viewBox=\"10 20 400 200\">\n" +
        "<rect x=\"10\" y=\"20\" width=\"400\" height=\"200\" fill=\"#fafafa\"/>\n<defs><clipPath id=\"c1\"><rect/></clipPath></defs><g clip-path=\"url(#c1)\"><use href=\"#c1\"/></g>\n</svg>\n"

    @Test fun `sin sublienzos la pagina no cambia`() {
        assertEquals(pagina, SublienzosWeb.adjuntar(pagina, emptyList()))
    }

    @Test fun `la pagina se ensancha y lleva cada sublienzo unido a su recuadro`() {
        val caja = Bounds(100.0, 200.0, 300.0, 300.0)
        val html = SublienzosWeb.adjuntar(pagina, listOf(SublienzosWeb.Adjunto(caja, sub), SublienzosWeb.Adjunto(Bounds(600.0, 900.0, 800.0, 1000.0), sub)))
        val vb = Regex("viewBox=\"([^\"]*)\"").find(html)!!.groupValues[1].split(" ").map { it.toDouble() }
        assertTrue("sitio a la izquierda", vb[0] < 0)
        assertTrue("y a la derecha", vb[0] + vb[2] > 1000)
        assertEquals("una sola raíz de verdad", 1, Regex("<svg xmlns").findAll(html).count())
        assertEquals(2, Regex("<svg x=").findAll(html).count())
        // La línea llega al borde del recuadro, a su mitad.
        assertTrue(html.contains("x2=\"100\" y2=\"250\""))
        assertTrue(html.contains("x2=\"800\" y2=\"950\""))
        // Los ids del sublienzo no pisan los de la página.
        assertTrue(html.contains("id=\"p-sl0-c1\"") && html.contains("url(#p-sl0-c1)") && html.contains("href=\"#p-sl1-c1\""))
        assertEquals("el de la página sigue", 1, Regex("id=\"c1\"").findAll(html).count())
        assertTrue(html.trim().endsWith("</svg>"))
    }

    /** La foto de la zona no viaja dos veces: se toma de la página con un `use` recortado. */
    @Test fun `la foto de la zona sale de la pagina`() {
        val html = SublienzosWeb.adjuntar(
            pagina,
            listOf(SublienzosWeb.Adjunto(Bounds(100.0, 200.0, 300.0, 300.0), sub, foto = Bounds(10.0, 20.0, 410.0, 220.0))),
            sello = "h3"
        )
        assertTrue("la página en su grupo", html.contains("<g id=\"pagina-h3\">"))
        assertTrue(html.contains("href=\"#pagina-h3\""))
        // Escala 2 (200 → 400) y la esquina de la zona cae en la de la foto: 10 - 100·2, 20 - 200·2.
        assertTrue(html, html.contains("matrix(2 0 0 2 -190 -380)"))
        // Encima del fondo del sublienzo, no debajo.
        val anidado = html.substring(html.indexOf("<svg x="))
        assertTrue(anidado.indexOf("<use") > anidado.indexOf("fill=\"#fafafa\""))
        assertEquals(1, Regex("<g id=\"pagina-h3\">").findAll(html).count())
    }

    /**
     * **Cada sublienzo por el lado más cercano a su zona**, no alternando lados: el reparto lo hace
     * [SitioDeSublienzos] y aquí se comprueba que la página web lo usa —el que sale de una zona de
     * la izquierda queda a la izquierda— y que el `viewBox` se abre para que quepan.
     */
    @Test fun `el sublienzo sale por el lado de su zona y el viewBox se abre`() {
        val sub = "<svg viewBox=\"0 0 400 200\"><rect fill=\"#fafafa\" width=\"400\" height=\"200\"/></svg>"
        val pagina = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1000\" height=\"1400\" viewBox=\"0 0 1000 1400\"><rect/></svg>"
        val html = SublienzosWeb.adjuntar(
            pagina,
            listOf(
                SublienzosWeb.Adjunto(Bounds(20.0, 600.0, 200.0, 700.0), sub),
                SublienzosWeb.Adjunto(Bounds(820.0, 300.0, 980.0, 400.0), sub)
            )
        )
        val vb = Regex("viewBox=\"([^\"]*)\"").find(html)!!.groupValues[1].split(" ").map { it.toDouble() }
        assertTrue("el viewBox se abre por la izquierda: $vb", vb[0] < 0.0)
        assertTrue("y por la derecha: $vb", vb[0] + vb[2] > 1000.0)
        // Los dos sublienzos: el de la zona izquierda a la izquierda de la página (x negativa).
        val rectangulos = Regex("<rect x=\"(-?[0-9.]+)\" y=\"(-?[0-9.]+)\" width=\"([0-9.]+)\"").findAll(html)
            .map { it.groupValues[1].toDouble() }.toList()
        assertTrue("uno a cada lado: $rectangulos", rectangulos.any { it < 0.0 } && rectangulos.any { it > 1000.0 })
    }
}
