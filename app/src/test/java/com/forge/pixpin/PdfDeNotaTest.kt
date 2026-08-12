package com.forge.pixpin.motor

import com.forge.pixpin.motormd.Markdown
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meter una nota dentro de un PDF, como texto de verdad.
 *
 * Aquí se comprueban dos cosas: que el archivo que sale **es un PDF válido** y
 * que dentro hay **texto**, no dibujo. Lo primero se mira con un lector de
 * verdad si está disponible; lo segundo, buscando los operadores de texto y las
 * fuentes en el archivo.
 */
class PdfDeNotaTest {

    /** Un PDF mínimo de una página, escrito a mano, del que partir. */
    private fun pdfDeUnaPagina(): ByteArray {
        val objetos = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                "/Contents 4 0 R /Resources << >> >>\nendobj\n",
            "4 0 obj\n<< /Length 8 >>\nstream\n0 0 m S\nendstream\nendobj\n"
        )
        val cuerpo = StringBuilder("%PDF-1.4\n")
        val posiciones = mutableListOf<Int>()
        objetos.forEach {
            posiciones += cuerpo.length
            cuerpo.append(it)
        }
        val xref = cuerpo.length
        cuerpo.append("xref\n0 ${objetos.size + 1}\n0000000000 65535 f \n")
        posiciones.forEach { cuerpo.append("%010d 00000 n \n".format(it)) }
        cuerpo.append("trailer\n<< /Size ${objetos.size + 1} /Root 1 0 R >>\n")
            .append("startxref\n").append(xref).append("\n%%EOF\n")
        return cuerpo.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * Todo el texto que hay dentro del PDF, ya descomprimido.
     *
     * Los flujos van con FlateDecode —como debe ser, o el archivo pesaría el
     * triple—, así que mirar los bytes en crudo no encuentra nada. Esto saca
     * cada flujo, lo descomprime y devuelve todo junto para poder buscar.
     */
    private fun dentroDe(pdf: ByteArray): String {
        val plano = String(pdf, Charsets.ISO_8859_1)
        val salida = StringBuilder(plano)
        var desde = 0
        while (true) {
            val abre = plano.indexOf("stream", desde)
            if (abre < 0) break
            var inicio = abre + 6
            if (inicio < plano.length && plano[inicio] == '\r') inicio++
            if (inicio < plano.length && plano[inicio] == '\n') inicio++
            val cierra = plano.indexOf("endstream", inicio)
            if (cierra < 0) break
            val crudo = pdf.copyOfRange(inicio, cierra)
            runCatching {
                val inflador = java.util.zip.Inflater()
                inflador.setInput(crudo)
                val buffer = ByteArray(1 shl 16)
                val out = java.io.ByteArrayOutputStream()
                while (!inflador.finished()) {
                    val n = inflador.inflate(buffer)
                    if (n == 0) break
                    out.write(buffer, 0, n)
                }
                inflador.end()
                salida.append('\n').append(String(out.toByteArray(), Charsets.ISO_8859_1))
            }
            desde = cierra + 9
        }
        return salida.toString()
    }

    private val nota = """
        # Acta de obra

        Lo hablado el martes, con **negrita** y _cursiva_.

        - primer punto
        - segundo punto
        - [x] una casilla hecha

        > una cita de alguien

        | Partida | Importe |
        |:---|---:|
        | Tabiquería | 1.200 |
        | Pintura | 800 |

        ```
        val x = 1
        ```

        ---
    """.trimIndent()

    @Test
    fun `la nota entra en el pdf`() {
        val salida = PdfDeNota.aniadir(pdfDeUnaPagina(), nota)
        assertNotNull("no escribió nada", salida)
        assertTrue(salida!!.size > pdfDeUnaPagina().size)
    }

    /** Lo que distingue esto de pintar la nota: dentro hay texto. */
    @Test
    fun `dentro va texto de verdad, no dibujo`() {
        val salida = PdfDeNota.aniadir(pdfDeUnaPagina(), nota)!!
        val plano = dentroDe(salida)

        assertTrue("no hay operadores de texto", plano.contains(" Tj"))
        assertTrue("no hay bloques de texto", plano.contains("BT "))
        assertTrue("no declara la fuente", plano.contains("Helvetica"))
        // Y el contenido de verdad, legible dentro del archivo.
        assertTrue("no está el título", plano.contains("Acta de obra"))
        assertTrue("no está la tabla", plano.contains("Tabiquer"))
    }

    /** Las marcas no viajan al PDF: lo que va es el texto ya limpio. */
    @Test
    fun `los asteriscos no acaban dentro del pdf`() {
        val salida = PdfDeNota.aniadir(pdfDeUnaPagina(), "con **negrita** dentro")!!
        val plano = dentroDe(salida)
        assertTrue(plano.contains("negrita"))
        assertTrue("se coló la marca", !plano.contains("**negrita**"))
    }

    @Test
    fun `una nota larga ocupa varias paginas`() {
        val larga = (1..200).joinToString("\n\n") { "Párrafo número $it de la nota." }
        val salida = PdfDeNota.aniadir(pdfDeUnaPagina(), larga)!!
        val plano = String(salida, Charsets.ISO_8859_1)
        // Cada página añadida trae su objeto de página.
        val paginas = Regex("/Type\\s*/Page[^s]").findAll(plano).count()
        assertTrue("solo salieron $paginas páginas", paginas >= 3)
    }

    @Test
    fun `una nota vacia no toca el archivo`() {
        assertNull(PdfDeNota.aniadir(pdfDeUnaPagina(), ""))
        assertNull(PdfDeNota.aniadir(pdfDeUnaPagina(), "   \n\n  "))
    }

    @Test
    fun `un pdf ilegible no revienta`() {
        assertNull(PdfDeNota.aniadir("no soy un pdf".toByteArray(), nota))
        assertNull(PdfDeNota.aniadir(ByteArray(0), nota))
    }

    @Test
    fun `los parentesis del texto no rompen el archivo`() {
        val salida = PdfDeNota.aniadir(pdfDeUnaPagina(), "esto (entre paréntesis) y \\ barra")
        assertNotNull(salida)
        val plano = dentroDe(salida!!)
        assertTrue("no se escapó el paréntesis", plano.contains("\\(entre"))
    }

    /**
     * El juez independiente. Ghostscript no sabe nada de cómo escribimos el
     * archivo: si él lo abre sin quejarse, es que está bien de verdad.
     *
     * Si no está instalado, la prueba se salta en vez de fallar: no todo el
     * mundo que compile esto lo tiene.
     */
    @Test
    fun `un lector de verdad lo abre sin quejarse`() {
        val gs = File("/usr/bin/gs").takeIf { it.exists() }
            ?: File("/usr/local/bin/gs").takeIf { it.exists() }
            ?: return

        val salida = PdfDeNota.aniadir(pdfDeUnaPagina(), nota)!!
        val temporal = File.createTempFile("nota", ".pdf")
        temporal.writeBytes(salida)

        val proceso = ProcessBuilder(
            gs.path, "-dBATCH", "-dNOPAUSE", "-sDEVICE=nullpage", temporal.path
        ).redirectErrorStream(true).start()
        val salidaTexto = proceso.inputStream.bufferedReader().readText()
        val codigo = proceso.waitFor()
        temporal.delete()

        assertTrue("ghostscript se quejó:\n$salidaTexto", codigo == 0)
    }

    @Test
    fun `lo que se escribe se puede volver a leer como pdf`() {
        val salida = PdfDeNota.aniadir(pdfDeUnaPagina(), nota)!!
        val releido = leerPdf(salida)
        assertNotNull("el propio lector no lo entiende", releido)
    }
}
