package com.forge.pixpin.ui

import org.robolectric.RuntimeEnvironment
import com.forge.pixpin.motor.PlanoDePdf
import com.forge.pixpin.motor.PlanoWeb
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.Deflater

/**
 * **El PDF anotado del lector, como página web por hojas en líneas** (29-sep-2026): la hoja viaja
 * como geometría y no como foto, que es lo que la hacía pesar y emborronarse al acercar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfAnotadoPorHojasTest {

    /** Una hoja con un texto y una raya: lo mínimo para que valga la pena leerla como líneas. */
    private fun pdfDeLineas(): File {
        val contenido = buildString {
            append("BT /F1 24 Tf 100 700 Td (Hola desde el PDF) Tj ET\n")
            append("BT /F1 12 Tf 100 660 Td (Segunda linea) Tj ET\n")
            append("BT /F1 12 Tf 100 640 Td (Tercera linea) Tj ET\n")
            append("2 w 100 600 m ")
            for (i in 1..30) append("${100 + i * 10} ${600 + (i % 2) * 20} l ")
            append("S\n")
        }.toByteArray()
        val d = Deflater(); d.setInput(contenido); d.finish()
        val buf = ByteArray(contenido.size + 256); val n = d.deflate(buf); d.end()
        val flujo = buf.copyOf(n)
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R " +
                "/Resources << /Font << /F1 5 0 R >> >> >>"
        )
        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offs = ArrayList<Int>()
        objs.forEachIndexed { i, o -> offs += out.size(); out.write("${i + 1} 0 obj\n$o\nendobj\n".toByteArray()) }
        offs += out.size()
        out.write("4 0 obj\n<< /Length ${flujo.size} /Filter /FlateDecode >>\nstream\n".toByteArray())
        out.write(flujo); out.write("\nendstream\nendobj\n".toByteArray())
        offs += out.size()
        out.write("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n".toByteArray())
        val xref = out.size()
        out.write("xref\n0 6\n0000000000 65535 f \n".toByteArray())
        for (o in offs) out.write(String.format("%010d 00000 n \n", o).toByteArray())
        out.write("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return File.createTempFile("lineas", ".pdf").apply { writeBytes(out.toByteArray()) }
    }

    @Test
    fun `una hoja que se lee como lineas viaja como lineas y sin foto`() {
        val pdf = pdfDeLineas()
        val plano = PlanoDePdf.deArchivo(pdf.absolutePath, 0)
        assertNotNull("la hoja de prueba no se lee", plano)
        assertTrue("la hoja de prueba no vale la pena: ${plano!!.puntos} puntos, ${plano.textos.size} textos", plano.valeLaPena && plano.sinEntender == 0)

        val c = RuntimeEnvironment.getApplication()
        val html = ExportarPdfAnotado.hacer(c, pdf.absolutePath, "Prueba", 1, { null })
        assertNotNull("no salió la página web", html)
        val texto = html!!.readText()
        assertTrue("la hoja no va como líneas", texto.contains(PlanoWeb.aJson(plano)))
        assertFalse("la hoja va como foto", texto.contains("data:image/jpeg") || texto.contains("data:image/webp"))
    }

    @Test
    fun `el PDF con anotaciones es el mismo PDF mas margenes tinta y marcadores`() {
        val pdf = pdfDeLineas()
        val original = pdf.readBytes()
        val c = RuntimeEnvironment.getApplication()
        // Un trazo en la hoja y otro en el margen izquierdo (equis negativas).
        val trazo = com.forge.pixpin.motor.Element(
            id = "t1", type = com.forge.pixpin.motor.ElementType.FREEDRAW,
            x = -300.0, y = 200.0, width = 900.0, height = 100.0, seed = 1, strokeWidth = 2.0,
            points = (0..20).map { com.forge.pixpin.motor.Pt(it * 45.0, (it % 3) * 30.0) }
        )
        val escena = com.forge.pixpin.motor.Scene(elements = listOf(trazo))
        val marca = com.forge.pixpin.motor.Marca(1, "⭐", 0.5, 0.25)
        val hecho = com.forge.pixpin.motor.PdfConAnotaciones.hacer(
            c, original, { i -> if (i == 0) escena else null },
            izquierda = true, derecha = false,
            marcadores = com.forge.pixpin.motor.PdfConAnotaciones.marcadoresDe(listOf(marca))
        )
        assertNotNull("no salió el PDF", hecho)
        // El original, intacto al principio: se añade, no se reescribe.
        assertTrue("el original no está entero", hecho!!.copyOf(original.size).contentEquals(original))
        val a = com.forge.pixpin.motor.leerPdf(hecho)!!
        val caja = com.forge.pixpin.motor.PdfAnotado.cajaDePagina(a, 0)!!
        assertTrue("la hoja no se ensanchó a la izquierda: ${caja.toList()}", caja[0] < -100)
        assertTrue("la derecha no debía cambiar: ${caja.toList()}", kotlin.math.abs(caja[2] - 612) < 0.01)
        val hoja = a.pagina(0)!!
        assertTrue("la tinta no va como anotación", (a.resolver(hoja.entradas["Annots"]) as? com.forge.pixpin.motor.PdfValor.Lista)?.valores?.isNotEmpty() == true)
        val catalogo = a.diccDe(a.trailer.entradas["Root"])!!
        val indice = a.diccDe(catalogo.entradas["Outlines"])
        assertNotNull("sin índice de marcadores", indice)
        val primero = a.diccDe(indice!!.entradas["First"])!!
        val titulo = (primero.entradas["Title"] as com.forge.pixpin.motor.PdfValor.Cadena).bytes
        assertTrue("el marcador no lleva su nombre", String(titulo.copyOfRange(2, titulo.size), Charsets.UTF_16BE) == "⭐ Hoja 1")
    }
}
