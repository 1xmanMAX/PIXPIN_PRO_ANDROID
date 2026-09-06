package com.forge.pixpin.motor

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** En el PDF de una nota, una imagen se ve y un archivo se representa con su etiqueta. */
class PdfDeNotaMediosTest {
    private fun pdfVacio(): ByteArray {
        val objetos = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 595 842] >>",
            "<< /Type /Page /Parent 2 0 R >>"
        )
        val salida = ByteArrayOutputStream()
        salida.write("%PDF-1.4\n".toByteArray())
        val sitios = ArrayList<Int>()
        for ((i, o) in objetos.withIndex()) { sitios += salida.size(); salida.write("${i + 1} 0 obj\n$o\nendobj\n".toByteArray()) }
        val xref = salida.size()
        val tabla = StringBuilder("xref\n0 ${objetos.size + 1}\n0000000000 65535 f \n")
        for (s in sitios) tabla.append(String.format("%010d 00000 n \n", s))
        tabla.append("trailer\n<< /Size ${objetos.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        salida.write(tabla.toString().toByteArray())
        return salida.toByteArray()
    }

    @Test
    fun `imagen como XObject y archivo como recuadro con etiqueta`() {
        val texto = "# Nota\n\n![foto](/x/foto.jpg)\n\n![informe](/x/informe.pdf)\n\nTexto."
        val jpegFalso = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val salida = PdfDeNota.aniadir(pdfVacio(), texto) { ruta ->
            when {
                ruta.endsWith(".jpg") -> PdfDeNota.MedioParaPdf(jpegFalso, 400, 300, null)
                ruta.endsWith(".pdf") -> PdfDeNota.MedioParaPdf(null, 0, 0, "informe.pdf · PDF · 1,2 MB")
                else -> null
            }
        }
        assertNotNull(salida)
        val texto2 = String(salida!!, Charsets.ISO_8859_1)
        assertTrue("la imagen va como XObject", texto2.contains("/Subtype /Image") && texto2.contains("/DCTDecode"))
        val leido = leerPdf(salida)!!
        assertTrue("la página nueva existe", leido.paginas().size == 2)
        // El contenido de la página va comprimido: se lee de vuelta para mirar dentro.
        val pagina = leido.pagina(1)!!
        val flujo = leido.resolver(pagina.entradas["Contents"]) as PdfValor.Flujo
        val contenido = String(leido.descomprimir(flujo)!!, Charsets.ISO_8859_1)
        assertTrue("y se pinta: " + contenido.take(200), contenido.contains("/Im1 Do"))
        assertTrue("el archivo va con su recuadro", contenido.contains(" re S"))
        val recursos = leido.diccDe(pagina.entradas["Resources"])!!
        assertNotNull("la imagen está en los recursos", leido.diccDe(recursos.entradas["XObject"])?.entradas?.get("Im1"))
    }
}
