package com.forge.pixpin

import com.forge.pixpin.motor.DocxAHtml
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **El lector de Word.** Ver [DocxAHtml]. Cómo queda la página en el teléfono no se puede probar
 * aquí; lo que se comprueba es que de un `.docx` sale lo que dice, con sus etiquetas, y que el
 * texto no se cuela como HTML.
 */
class DocxAHtmlTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    private val R = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val A = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""

    private fun docx(cuerpo: String, rels: String? = null, fotos: Map<String, ByteArray> = emptyMap()): File {
        val f = carpeta.newFile("prueba.docx")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write("<w:document $W $R $A><w:body>$cuerpo</w:body></w:document>".toByteArray())
            z.closeEntry()
            if (rels != null) { z.putNextEntry(ZipEntry("word/_rels/document.xml.rels")); z.write(rels.toByteArray()); z.closeEntry() }
            for ((ruta, bytes) in fotos) { z.putNextEntry(ZipEntry(ruta)); z.write(bytes); z.closeEntry() }
        }
        return f
    }

    private fun p(texto: String, pPr: String = "", rPr: String = "") =
        "<w:p><w:pPr>$pPr</w:pPr><w:r><w:rPr>$rPr</w:rPr><w:t xml:space=\"preserve\">$texto</w:t></w:r></w:p>"

    @Test
    fun `un título, una negrita, una tabla y una lista salen con sus etiquetas`() {
        val html = DocxAHtml.convertir(
            docx(
                p("El informe", pPr = "<w:pStyle w:val=\"Heading1\"/>") +
                    p("Apartado", pPr = "<w:pStyle w:val=\"Ttulo2\"/>") +
                    p("muy importante", rPr = "<w:b/><w:i w:val=\"0\"/>") +
                    "<w:tbl><w:tr><w:tc>${p("izquierda")}</w:tc><w:tc>${p("derecha")}</w:tc></w:tr></w:tbl>" +
                    p("primero", pPr = "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>")
            ),
            "Informe.docx"
        )
        assertTrue(html, html.startsWith("<!DOCTYPE html>"))
        assertTrue(html, html.contains("<title>Informe.docx</title>"))
        assertTrue(html, html.contains("<h1>El informe</h1>"))
        assertTrue(html, html.contains("<h2>Apartado</h2>"))
        assertTrue(html, html.contains("<b>muy importante</b>"))
        // `w:i w:val="0"` es cursiva **apagada**.
        assertFalse(html, html.contains("<i>"))
        assertTrue(html, html.contains("<table>"))
        assertTrue(html, html.contains("<td><p>izquierda</p>\n</td><td><p>derecha</p>\n</td>"))
        assertTrue(html, html.contains("• primero"))
        assertFalse("la página va sin guion", html.contains("<script"))
    }

    @Test
    fun `el texto se escapa`() {
        val html = DocxAHtml.convertir(docx(p("a &lt; b &amp; &lt;script&gt;alert(1)&lt;/script&gt;")))
        assertTrue(html, html.contains("a &lt; b &amp; &lt;script&gt;"))
        assertFalse(html, html.contains("<script>"))
    }

    @Test
    fun `saltos, tabuladores, subrayado y tachado`() {
        val html = DocxAHtml.convertir(
            docx(
                "<w:p><w:r><w:rPr><w:u w:val=\"single\"/><w:strike/></w:rPr>" +
                    "<w:t>uno</w:t><w:br/><w:t>dos</w:t><w:tab/><w:t>tres</w:t></w:r>" +
                    "<w:hyperlink r:id=\"rId9\"><w:r><w:t>el enlace</w:t></w:r></w:hyperlink></w:p>"
            )
        )
        assertTrue(html, html.contains("<u><s>uno<br>dos&emsp;tres</s></u>"))
        assertTrue(html, html.contains("el enlace"))
    }

    @Test
    fun `una imagen va incrustada y un EMF se queda en un aviso`() {
        val rels = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"x/image\" Target=\"media/image1.png\"/>" +
            "<Relationship Id=\"rId2\" Type=\"x/image\" Target=\"media/image2.emf\"/></Relationships>"
        fun dibujo(id: String) = "<w:p><w:r><w:drawing><a:graphic><a:graphicData><a:blip r:embed=\"$id\"/></a:graphicData></a:graphic></w:drawing></w:r></w:p>"
        val html = DocxAHtml.convertir(
            docx(
                dibujo("rId1") + dibujo("rId2"), rels,
                mapOf("word/media/image1.png" to byteArrayOf(1, 2, 3), "word/media/image2.emf" to byteArrayOf(4))
            )
        )
        assertTrue(html, html.contains("<img alt=\"\" src=\"data:image/png;base64,AQID\">"))
        assertTrue(html, html.contains("[imagen que no se puede mostrar]"))
    }

    @Test
    fun `lo que no es un docx lo dice`() {
        val f = carpeta.newFile("falso.docx").apply { writeText("esto no es un zip") }
        try { DocxAHtml.convertir(f); fail("tenía que fallar") } catch (e: DocxAHtml.NoSeLee) {
            assertTrue(e.message.orEmpty().contains(".docx"))
        }
        // Un ZIP sin `word/document.xml` tampoco.
        val z = carpeta.newFile("otro.docx")
        ZipOutputStream(z.outputStream()).use { it.putNextEntry(ZipEntry("hola.txt")); it.write(1); it.closeEntry() }
        try { DocxAHtml.convertir(z); fail("tenía que fallar") } catch (e: DocxAHtml.NoSeLee) { assertTrue(e.message.orEmpty().isNotBlank()) }
        // Y el Word antiguo, con su propio aviso.
        val viejo = carpeta.newFile("viejo.doc").apply { writeText("x") }
        try { DocxAHtml.convertir(viejo); fail("tenía que fallar") } catch (e: DocxAHtml.NoSeLee) {
            assertTrue(e.message.orEmpty().contains("Solo se leen"))
        }
    }

    @Test
    fun `se reconoce por el nombre`() {
        assertTrue(DocxAHtml.esDocx("Memoria FINAL.DOCX"))
        assertFalse(DocxAHtml.esDocx("memoria.doc"))
        assertFalse(DocxAHtml.esDocx(null))
    }
}
