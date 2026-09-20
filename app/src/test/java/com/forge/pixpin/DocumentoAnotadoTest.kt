package com.forge.pixpin

import com.forge.pixpin.motor.DocumentoAnotado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentoAnotadoTest {
    private val tops = listOf(0.0, 40.0, 120.0, 120.0, 300.0)

    @Test fun `cada cosa se ata al ultimo bloque que empieza por encima`() {
        assertEquals(-1, DocumentoAnotado.anclaDe(-50.0, tops))
        assertEquals(1, DocumentoAnotado.anclaDe(100.0, tops))
        assertEquals(3, DocumentoAnotado.anclaDe(125.0, tops))
        // Un pelo antes del título sigue siendo del título.
        assertEquals(4, DocumentoAnotado.anclaDe(296.0, tops))
    }

    @Test fun `la caja sale del viewBox`() {
        val c = DocumentoAnotado.cajaDe("<?xml version=\"1.0\"?>\n<svg width=\"10\" height=\"5\" viewBox=\"-3.5 20 10 5\"></svg>")!!
        assertEquals(listOf(-3.5, 20.0, 10.0, 5.0), c.toList())
    }

    @Test fun `la pagina lleva lo anotado, los marcadores y su guion`() {
        val svg = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"5\" viewBox=\"0 0 10 5\"><path d=\"M0 0\"/></svg>\n"
        val html = "<html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body><p>hola</p></body></html>"
        val p = DocumentoAnotado.pagina(
            html, 360, tops,
            listOf(DocumentoAnotado.Pieza(svg, 12.0, 130.5, 10.0, 5.0, 3)),
            listOf(DocumentoAnotado.Senal("⭐", 310.0, 4)), 130
        )
        assertTrue(p.contains("content=\"width=360\""))
        assertTrue(!p.contains("device-width"))
        assertTrue(p.contains("class=\"ppa\" data-i=\"3\" data-y=\"130.5\""))
        assertTrue(!p.contains("<?xml"))
        assertTrue(p.contains("id=\"ppm0\"") && p.contains("data-m=\"ppm0\"") && p.contains("⭐"))
        assertTrue(p.contains("window.scrollTo(240,0)"))
        assertTrue(p.contains("text-size-adjust:130%"))
        assertTrue(p.indexOf("pprail") < p.lastIndexOf("</body>"))
    }
}
