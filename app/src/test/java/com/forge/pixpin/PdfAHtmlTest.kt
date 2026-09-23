package com.forge.pixpin

import com.forge.pixpin.motor.PdfAHtml
import com.forge.pixpin.motor.PdfAHtml.Trozo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfAHtmlTest {

    @Test
    fun `los trozos a la misma altura hacen una linea, con espacio solo donde hay hueco`() {
        val l = PdfAHtml.lineas(listOf(
            Trozo(100.0, 200.3, 130.0, 10.0, "mun"),
            Trozo(72.0, 200.0, 95.0, 10.0, "Hola"),
            Trozo(130.2, 200.1, 140.0, 10.0, "do"),
            Trozo(72.0, 214.0, 120.0, 10.0, "Otra línea")
        ))
        assertEquals(listOf("Hola mundo", "Otra línea"), l.map { it.texto })
    }

    @Test
    fun `a dos columnas se lee la izquierda entera y luego la derecha, con el titulo delante`() {
        val trozos = ArrayList<Trozo>()
        trozos += Trozo(72.0, 60.0, 520.0, 20.0, "Un título que cruza las dos columnas")
        for (k in 0 until 10) {
            val y = 100.0 + k * 14
            trozos += Trozo(72.0, y, 280.0, 11.0, "izquierda $k de la columna que va primero")
            trozos += Trozo(310.0, y, 520.0, 11.0, "derecha $k de la columna que va después")
        }
        assertEquals(305.0, PdfAHtml.pasilloDeColumnas(trozos)!!, 12.0)
        val l = PdfAHtml.lineasEnOrden(trozos)
        assertEquals("Un título que cruza las dos columnas", l.first().texto)
        assertEquals((0 until 10).map { "izquierda $it de la columna que va primero" } + (0 until 10).map { "derecha $it de la columna que va después" },
            l.drop(1).map { it.texto })
        // Y los párrafos no se cortan en cada línea de la columna derecha por estar a la derecha.
        val p = PdfAHtml.parrafos(l, 11.0, 0)
        assertEquals(2, p.size)
        assertTrue(p[1].texto.startsWith("izquierda 0") && p[1].texto.endsWith("derecha 9 de la columna que va después"))
        // A una columna, nada de pasillo.
        assertEquals(null, PdfAHtml.pasilloDeColumnas(trozos.filter { it.x < 300 }))
    }

    @Test
    fun `las lineas se juntan en parrafos y el guion de fin de linea se deshace`() {
        fun linea(y: Double, texto: String, x1: Double = 520.0, x0: Double = 72.0, alto: Double = 11.0) =
            PdfAHtml.Linea(y, x0, x1, alto, texto, false)
        val lineas = listOf(
            linea(100.0, "Título grande", 300.0, alto = 20.0),
            linea(140.0, "Esto es el primer párrafo, que ocupa varias líneas y habla de la inter-"),
            linea(154.0, "pretación de un documento."),
            linea(168.0, "Sigue igual porque no hay salto.", 300.0),
            linea(196.0, "Segundo párrafo tras un salto grande."),
            linea(780.0, "12", 300.0, x0 = 290.0)
        )
        val p = PdfAHtml.parrafos(lineas, 11.0, 0)
        assertEquals(1, p[0].nivel)
        assertEquals("Esto es el primer párrafo, que ocupa varias líneas y habla de la interpretación de un documento. Sigue igual porque no hay salto.", p[1].texto)
        assertEquals("Segundo párrafo tras un salto grande.", p[2].texto)
        // El número de página suelto se va.
        assertEquals(3, p.size)
    }

    @Test
    fun `un pdf de verdad sale como pagina con titulos, parrafos, hojas e idioma`() {
        val html = PdfAHtml.convertir(pdf(), "Apuntes", "es-PE")
        assertTrue(html.contains("<html lang=\"es\">"))
        assertTrue(html.contains("<h1 data-hoja=\"1\">Capítulo uno</h1>") || html.contains("<h2 data-hoja=\"1\">Capítulo uno</h2>"))
        assertTrue(html.contains("La ingeniería de los materiales estudia cómo se comportan las cosas cuando se cargan"))
        assertTrue(html.contains("id=\"hoja-2\""))
        assertTrue(html.contains("Segunda hoja con más texto"))
        assertFalse("el número de página no es texto", Regex(">\\s*1\\s*<").containsMatchIn(html))
    }

    @Test
    fun `el documento exportado lleva el texto a la vista y su idioma, para leerlo en alto en el navegador`() {
        val html = "<html><head><style>body{font:16px/1.5 serif}</style></head><body><main>" +
            "<p>The quick brown fox jumps over the lazy dog and this is the text that the browser should read aloud for the user.</p>" +
            "</main></body></html>"
        val hoja = com.forge.pixpin.motor.DocumentoAnotado.hoja("Doc", html, 600, 400, emptyList(), emptyList(), emptyList(), 100, "#fff")
        assertEquals("en", hoja.idioma)
        val pagina = com.forge.pixpin.motor.ExportarHtml.paginas(listOf(hoja), "Doc", "Doc")
        assertTrue(pagina.contains("<article class=\"doc\" lang=\"en\""))
        assertTrue(pagina.contains("The quick brown fox jumps over the lazy dog"))
    }

    @Test(expected = PdfAHtml.NoSeLee::class)
    fun `lo que no es un pdf avisa`() {
        PdfAHtml.convertir("esto no es un pdf".toByteArray(), "x")
    }

    /** Dos hojas con Helvetica: un título, un párrafo de dos líneas y el número de página. */
    private fun pdf(): ByteArray {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val hoja1 = "BT /F1 22 Tf 72 760 Td (${esc("Capítulo uno")}) Tj ET\n" +
            "BT /F1 11 Tf 72 720 Td (${esc("La ingeniería de los materiales estudia cómo se")}) Tj ET\n" +
            "BT /F1 11 Tf 72 706 Td (${esc("comportan las cosas cuando se cargan y por qué se rompen.")}) Tj ET\n" +
            "BT /F1 11 Tf 72 670 Td (${esc("Es la base de todo lo que se construye, y en esta hoja lo vemos.")}) Tj ET\n" +
            "BT /F1 9 Tf 297 40 Td (1) Tj ET\n"
        val hoja2 = "BT /F1 11 Tf 72 760 Td (${esc("Segunda hoja con más texto para que se lea bien el idioma de la página y de los que la leen.")}) Tj ET\n" +
            "BT /F1 9 Tf 297 40 Td (2) Tj ET\n"
        val fuente = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
        val objetos = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 7 0 R >> >> >>\nendobj\n",
            "4 0 obj\n<< /Length ${hoja1.toByteArray(Charsets.ISO_8859_1).size} >>\nstream\n$hoja1\nendstream\nendobj\n",
            "5 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 6 0 R /Resources << /Font << /F1 7 0 R >> >> >>\nendobj\n",
            "6 0 obj\n<< /Length ${hoja2.toByteArray(Charsets.ISO_8859_1).size} >>\nstream\n$hoja2\nendstream\nendobj\n",
            "7 0 obj\n$fuente\nendobj\n"
        )
        val salida = ByteArrayOutputStream()
        salida.write("%PDF-1.4\n".toByteArray())
        val desplazamientos = mutableListOf<Int>()
        for (o in objetos) { desplazamientos += salida.size(); salida.write(o.toByteArray(Charsets.ISO_8859_1)) }
        val inicioXref = salida.size()
        val tabla = StringBuilder("xref\n0 ${objetos.size + 1}\n0000000000 65535 f \n")
        for (d in desplazamientos) tabla.append("%010d 00000 n \n".format(d))
        tabla.append("trailer\n<< /Size ${objetos.size + 1} /Root 1 0 R >>\nstartxref\n$inicioXref\n%%EOF\n")
        salida.write(tabla.toString().toByteArray())
        return salida.toByteArray()
    }
}
