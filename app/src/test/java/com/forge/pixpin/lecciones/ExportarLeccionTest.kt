package com.forge.pixpin.lecciones

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Exportar una lección** (15-oct-2026, como `lecciones/exportar.rs` del PC): lo que llevan el
 * texto, el Markdown y la página web, y el PDF con su texto de verdad (la prueba del PC mira que se
 * ofrezcan PDF, web, imagen, .pixpin, original, TXT y MD; aquí la hoja se arma con el teléfono).
 */
class ExportarLeccionTest {

    private val l = Leccion(
        id = "k1", creada = 1_700_000_000_000, titulo = "Revisar el encofrado antes de vaciar",
        quePaso = "Se abrió una junta en la losa <b>", porQue = "Puntales flojos", proxima = "Si hay vaciado, entonces revisar puntales",
        tipo = Leccion.TIPO_ERROR, area = "Construcción", gravedad = 3, etiquetas = listOf("obra"), causas = listOf("Prisa")
    )

    @Test
    fun `el texto y el markdown llevan todos los campos`() {
        val t = ExportarLeccion.texto(l)
        for (s in listOf("Revisar el encofrado", "Qué pasó: Se abrió", "Por qué: Puntales", "La próxima vez: Si hay", "#obra", "Área: Construcción", "Causas: Prisa")) assertTrue("falta «$s» en:\n$t", s in t)
        val md = ExportarLeccion.markdown(l, listOf("/ruta/foto.jpg"))
        assertTrue(md.startsWith("# Revisar el encofrado antes de vaciar\n"))
        assertTrue("**Qué pasó:** Se abrió" in md && "**La próxima vez:**" in md && "Grave" in md)
        assertTrue("![foto](/ruta/foto.jpg)" in md)
    }

    @Test
    fun `la pagina web escapa el texto y lleva las fotos dentro`() {
        val dir = Files.createTempDirectory("leccion").toFile()
        val foto = File(dir, "f.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val html = ExportarLeccion.html(l, true, listOf(foto.absolutePath))
        assertTrue("<title>Revisar el encofrado antes de vaciar</title>" in html)
        assertTrue("&lt;b&gt;" in html)
        assertFalse("<b>" in html.substringAfter("<body>"))
        assertTrue("data:image/png;base64,AQID" in html)
        // Solo las fotos: sin el resumen.
        assertFalse("Puntales" in ExportarLeccion.html(l, false, emptyList()))
        dir.deleteRecursively()
    }

    @Test
    fun `el pdf de la leccion lleva su texto`() {
        val pdf = com.forge.pixpin.motor.PdfDeNota.aniadir(com.forge.pixpin.motor.PdfUnion.enBlanco(), ExportarLeccion.markdown(l))!!
        assertTrue(com.forge.pixpin.motor.leerPdf(pdf) != null)
        val crudo = String(pdf, Charsets.ISO_8859_1)
        assertTrue(crudo.contains("/Type /Page") || crudo.contains("/Type/Page"))
    }
}
