package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * **La hoja de un PDF como SVG con su texto** ([PlanoSvg]) y **las imágenes que no son JPEG, en
 * PNG** ([FotoPng]), 30-sep-2026: lo que hace que un PDF anotado se exporte a página web ligero,
 * nítido y buscable, en vez de como fotos.
 */
class PlanoSvgTest {

    private fun comprimir(b: ByteArray): ByteArray {
        val d = Deflater(); d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end(); return out.toByteArray()
    }

    /** Un PDF de una hoja: [contenido], la fuente F1 con [codificacion] y una imagen Im1 RGB de 2×1 comprimida con Flate. */
    private fun pdf(contenido: String, codificacion: String = "/WinAnsiEncoding"): ByteArray {
        val flujo = comprimir(contenido.toByteArray(Charsets.ISO_8859_1))
        val pixeles = comprimir(byteArrayOf(255.toByte(), 0, 0, 0, 0, 255.toByte()))
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(),
            ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R " +
                "/Resources << /Font << /F1 5 0 R >> /XObject << /Im1 6 0 R >> >> >>").toByteArray(),
            "<< /Length ${flujo.size} /Filter /FlateDecode >>\nstream\n".toByteArray() + flujo + "\nendstream".toByteArray(),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding $codificacion >>".toByteArray(),
            ("<< /Type /XObject /Subtype /Image /Width 2 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                "/Length ${pixeles.size} /Filter /FlateDecode >>\nstream\n").toByteArray() + pixeles + "\nendstream".toByteArray()
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offs = ArrayList<Int>()
        objs.forEachIndexed { i, o -> offs += out.size(); out.write("${i + 1} 0 obj\n".toByteArray()); out.write(o); out.write("\nendobj\n".toByteArray()) }
        val xref = out.size()
        out.write("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n".toByteArray())
        for (o in offs) out.write(String.format("%010d 00000 n \n", o).toByteArray())
        out.write("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    private fun plano(contenido: String, codificacion: String = "/WinAnsiEncoding"): PlanoDePdf.Plano =
        PlanoDePdf.de(leerPdf(pdf(contenido, codificacion))!!, 0)!!

    @Test
    fun `una imagen comprimida con Flate pasa como PNG y la hoja no va como foto`() {
        val p = plano("BT /F1 12 Tf 72 700 Td (Hola) Tj ET\nq 100 0 0 50 72 500 cm /Im1 Do Q\n")
        assertEquals("algo quedó sin entender", 0, p.sinEntender)
        val foto = p.fotos.single()
        assertEquals("image/png", foto.tipo)
        val img = javax.imageio.ImageIO.read(foto.datos.inputStream())
        assertEquals(2, img.width); assertEquals(1, img.height)
        assertEquals(0xFF0000, img.getRGB(0, 0) and 0xFFFFFF)
        assertEquals(0x0000FF, img.getRGB(1, 0) and 0xFFFFFF)
    }

    @Test
    fun `el texto va como texto, con los espacios que el PDF hace a empujones`() {
        val p = plano("BT /F1 10 Tf 72 700 Td [(Readers)-145(interested)-145(only)] TJ ET\n")
        val svg = PlanoSvg.aSvg(p, 800, "h0")
        assertTrue(svg, svg.contains(">Readers interested only</text>"))
        assertTrue(svg.startsWith("<svg class=\"hoja-svg\""))
        // Derecho: con su sitio y su tamaño, sin matriz.
        assertTrue(svg, svg.contains("<text x=\"72\" y=\"92\" font-size=\"10\""))
    }

    @Test
    fun `la ligadura fi de MacRoman se lee fi`() {
        val p = plano("BT /F1 10 Tf 72 700 Td (deÞned) Tj ET\n", "/MacRomanEncoding")
        assertEquals("defined", p.textos.joinToString("") { it.texto })
    }

    @Test
    fun `la imagen repetida en otra hoja se escribe una sola vez`() {
        val p = plano("q 100 0 0 50 72 500 cm /Im1 Do Q\nq 10 0 0 5 0 0 cm /Im1 Do Q\nBT /F1 12 Tf 72 700 Td (Una hoja) Tj ET\n")
        val ya = PlanoSvg.YaPuestas()
        val una = PlanoSvg.aSvg(p, 800, "h0", yaPuestas = ya)
        val otra = PlanoSvg.aSvg(p, 800, "h1", yaPuestas = ya)
        assertEquals(1, Regex("data:image/png").findAll(una).count())
        assertEquals(2, Regex("<use href=\"#h0i").findAll(una).count())
        assertFalse(otra.contains("data:image"))
        assertEquals(2, Regex("<use href=\"#h0i").findAll(otra).count())
    }

    @Test
    fun `lo que se escribe no rompe el SVG`() {
        val p = plano("BT /F1 12 Tf 72 700 Td (a < b & c) Tj ET\nBT /F1 12 Tf 72 680 Td (otra linea) Tj ET\nBT /F1 12 Tf 72 660 Td (y otra) Tj ET\n")
        val svg = PlanoSvg.aSvg(p, 800, "h0")
        assertTrue(svg, svg.contains(">a &lt; b &amp; c</text>"))
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
    }
}
