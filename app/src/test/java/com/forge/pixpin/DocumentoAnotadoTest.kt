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

    @Test fun `la hoja de estilo del documento queda encerrada en su caja`() {
        val css = "html{a:1}body{margin:0;font:17px/1.6 serif}p,h1{b:2}header.libro p{c:3}" +
            "@media (prefers-color-scheme: dark){body{d:4}td,th{e:5}}@media print{@page{size:A4}body{f:6}}@page{g:7}"
        val r = DocumentoAnotado.acotar(css)
        assertEquals(
            ".doc{a:1}.doc{margin:0;font:17px/1.6 serif}.doc p,.doc h1{b:2}.doc header.libro p{c:3}" +
                "@media (prefers-color-scheme: dark){.doc{d:4}.doc td,.doc th{e:5}}@media print{.doc{f:6}}",
            r
        )
    }

    @Test fun `el documento entra en el documento web con sus mandos, lo anotado y los marcadores`() {
        val svg = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"60\" height=\"30\" viewBox=\"0 0 60 30\"><path d=\"M0 0L60 30\" stroke=\"#d00\" stroke-width=\"3\" fill=\"none\"/></svg>\n"
        val parrafos = (1..40).joinToString("") { "<p>Párrafo $it. " + "Texto de relleno para que la columna tenga varias líneas. ".repeat(3) + "</p>" }
        val html = "<html><head><style>body{margin:0;background:#fff;font:17px/1.6 Georgia,serif}main{padding:18px}p{margin:.7em 0}</style>" +
            "<script>alert(1)</script></head><body><main><h1>Título</h1>$parrafos</main><script>alert(2)</script></body></html>"
        val hoja = DocumentoAnotado.hoja(
            "Libro", html, 360, 240, tops,
            listOf(DocumentoAnotado.Pieza(svg, 12.0, 130.5, 60.0, 30.0, 3)),
            listOf(DocumentoAnotado.Senal("⭐", 310.0, 4)), 130, "#ffffff"
        )
        assertEquals(17.0 * 1.3, hoja.letra, 1e-9)
        assertTrue(hoja.estilo.startsWith(".doc{margin:0"))
        assertTrue(!hoja.cuerpo.contains("alert") && hoja.cuerpo.contains("<h1>Título</h1>"))
        val p = com.forge.pixpin.motor.ExportarHtml.paginas(listOf(hoja), "Libro")
        assertTrue(p.contains("class=\"doc-caja\" data-columna=\"360\" data-margen=\"240\""))
        assertTrue(p.contains("style=\"width:840px\""))
        assertTrue(p.contains("class=\"ppa\" data-i=\"3\" data-y=\"130.5\""))
        assertTrue(!p.contains("<?xml"))
        assertTrue(p.contains("id=\"ppm-d-0\"") && p.contains("data-m=\"ppm-d-0\"") && p.contains("⭐"))
        // Los mandos de toda página web, y el grupo donde guardar escribe lo rayado: uno, el de la tinta.
        assertTrue(p.contains("id=\"lapiz\"") && p.contains("id=\"guardar\""))
        assertEquals(1, Regex("<g id=\"croquis\"").findAll(p.substringBefore("<script>")).count())
        java.io.File("build/doc-anotado.html").apply { parentFile?.mkdirs() }.writeText(p)
    }
}
