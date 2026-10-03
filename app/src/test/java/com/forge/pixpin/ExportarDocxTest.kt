package com.forge.pixpin.motormd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** La nota en Word y lo que el PC mete en las notas (ancho, páginas vivas, enlaces). */
class ExportarDocxTest {

    private fun partes(zip: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes() }
        }
        return m
    }

    private fun xmlBienFormado(b: ByteArray) {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        f.newDocumentBuilder().parse(ByteArrayInputStream(b))
    }

    // Un PNG de 2×1 de verdad.
    private val PNG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0, 0, 0, 2, 0, 0, 0, 1, 8, 6, 0, 0, 0
    )

    @Test
    fun `el paquete lleva todas sus partes y todas son XML bien formado`() {
        val md = """
            # Obra & <cosas>
            Texto con **negrita**, *cursiva*, ~~tachado~~, `código` y [web](https://pixpin.app).

            - una
            - dos

            1. primero
            2. segundo

            - [x] hecha
            - [ ] pendiente

            > una cita

            | A | B |
            |:---|---:|
            | 1 | 2 |

            ![Planta|360](foto.png)

            ---
        """.trimIndent()
        val (c, _) = Comentarios.nuevo(Comentarios.Fichero(), Comentarios.anclaDe(md, md.indexOf("negrita"), md.indexOf("negrita") + 7)!!,
            Comentarios.Quien("Teléfono", "MOVI"), 1000, "¿por qué?")!!
        val zip = ExportarDocx.exportar(ExportarDocx.Nota(md, comentarios = c, imagen = { if (it == "foto.png") PNG else null }))
        val p = partes(zip)
        for (n in listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/numbering.xml",
            "word/settings.xml", "word/fontTable.xml", "word/comments.xml", "word/commentsExtended.xml", "word/_rels/document.xml.rels",
            "docProps/core.xml", "docProps/app.xml", "word/media/imagen1.png")) assertTrue(n, n in p)
        p.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { xmlBienFormado(it) }
        val doc = String(p.getValue("word/document.xml"))
        assertTrue(doc.contains("Heading1"))
        assertTrue(doc.contains("<w:numId w:val=\"1\"/>"))
        assertTrue(doc.contains("<w:numId w:val=\"2\"/>"))
        assertTrue(doc.contains("☒"))
        assertTrue(doc.contains("<w:tbl>"))
        assertTrue(doc.contains("commentRangeStart"))
        assertTrue(doc.contains("<w:hyperlink"))
        // 360 de 720: media columna.
        assertTrue(doc.contains("cx=\"2880000\""))
        assertTrue(String(p.getValue("word/comments.xml")).contains("¿por qué?"))
    }

    @Test
    fun `una tabla combinada y con colores sale con gridSpan vMerge y sombra`() {
        val md = "<table>\n  <tr>\n    <th colspan=\"2\">Presupuesto</th>\n  </tr>\n  <tr>\n    <td rowspan=\"2\" style=\"background:#ffc9c9;color:#e03131\">Hormigon</td>\n    <td>3</td>\n  </tr>\n  <tr>\n    <td>4</td>\n  </tr>\n</table>"
        val doc = String(partes(ExportarDocx.exportar(ExportarDocx.Nota(md))).getValue("word/document.xml"))
        xmlBienFormado(doc.toByteArray())
        assertTrue(doc.contains("<w:gridSpan w:val=\"2\"/>"))
        assertTrue(doc.contains("<w:vMerge w:val=\"restart\"/>"))
        assertTrue(doc.contains("<w:vMerge/>"))
        assertTrue(doc.contains("w:fill=\"FFC9C9\""))
        assertTrue(doc.contains("w:val=\"E03131\""))
    }

    @Test
    fun `el nombre de archivo es seguro`() {
        assertEquals("Obra_ planta.docx", ExportarDocx.nombreDeArchivo("Obra: planta"))
        assertEquals("CON_.docx", ExportarDocx.nombreDeArchivo("CON"))
        assertEquals("Nota.docx", ExportarDocx.nombreDeArchivo("***"))
    }

    @Test
    fun `el ancho de la foto se separa del pie`() {
        assertEquals("Planta" to 320, Incrustados.partirAlt("Planta|320"))
        assertEquals("Planta" to null, Incrustados.partirAlt("Planta"))
        assertEquals("Planta|320", Incrustados.conAncho("Planta|500", 320))
        assertEquals("Planta", Incrustados.conAncho("Planta|500", 720))
    }

    @Test
    fun `paginas vivas y enlaces del PC`() {
        assertEquals("ABCDE23456", Incrustados.paginaViva("pixpin:files/guardados/pc/x/notas/vivo-ABCDE23456.png"))
        assertNull(Incrustados.paginaViva("notas/foto.png"))
        assertEquals(Incrustados.Enlace.Hoja("P1", "H2"), Incrustados.enlace("pixpin:hoja=P1/H2"))
        assertEquals(Incrustados.Enlace.Mensaje("P1", "M2"), Incrustados.enlace("pixpin:mensaje=P1/M2"))
        val linea = Markdown.parseInline("[Pedir la grúa](pixpin:mensaje=P1/M2)")
        assertEquals("Pedir la grúa", Incrustados.soloEnlace(linea)?.second)
        assertNull(Incrustados.soloEnlace(Markdown.parseInline("Ver [esto](pixpin:hoja=P/H) y más")))
    }
}
