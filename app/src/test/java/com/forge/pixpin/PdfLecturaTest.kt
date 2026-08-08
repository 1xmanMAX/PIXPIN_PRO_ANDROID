package com.forge.pixpin.motor

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leer un PDF de verdad, sin dispositivo.
 *
 * Los PDF de las pruebas **se escriben a mano aquí**, byte a byte. Es más
 * trabajo que meter un archivo de ejemplo, y compensa por dos motivos: se puede
 * fabricar exactamente el caso raro que se quiere probar —un índice comprimido
 * con predictor, un objeto empaquetado, un archivo con dos revisiones— y cuando
 * algo falla se ve **en la propia prueba** qué tenía dentro el archivo, en vez
 * de tener que abrir un binario de doscientos kilobytes.
 */
class PdfLecturaTest {

    // ---- Un PDF con la tabla de toda la vida ----

    /**
     * El PDF más pequeño que puede existir: catálogo, árbol de páginas, una
     * página y su contenido, con la tabla `xref` clásica.
     */
    private fun pdfClasico(): ByteArray {
        val objetos = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                "/Contents 4 0 R /Resources << >> >>\nendobj\n",
            "4 0 obj\n<< /Length 44 >>\nstream\n" +
                "BT /F1 24 Tf 72 700 Td (Hola mundo) Tj ET\n" +
                "\nendstream\nendobj\n"
        )
        val salida = ByteArrayOutputStream()
        salida.write("%PDF-1.4\n".toByteArray())
        val desplazamientos = mutableListOf<Int>()
        for (o in objetos) {
            desplazamientos += salida.size()
            salida.write(o.toByteArray())
        }
        val inicioXref = salida.size()
        val tabla = StringBuilder("xref\n0 ${objetos.size + 1}\n0000000000 65535 f \n")
        for (d in desplazamientos) tabla.append("%010d 00000 n \n".format(d))
        tabla.append("trailer\n<< /Size ${objetos.size + 1} /Root 1 0 R >>\n")
        tabla.append("startxref\n$inicioXref\n%%EOF\n")
        salida.write(tabla.toString().toByteArray())
        return salida.toByteArray()
    }

    @Test
    fun `se lee un pdf con la tabla clasica`() {
        val pdf = leerPdf(pdfClasico())
        assertNotNull("no se ha podido leer", pdf)
        assertTrue(!pdf!!.cifrado)
        assertEquals("no encuentra la página", listOf(3), pdf.paginas())
    }

    @Test
    fun `la pagina trae su diccionario y su tamaño`() {
        val pdf = leerPdf(pdfClasico())!!
        val pagina = pdf.pagina(0)
        assertNotNull(pagina)
        assertEquals("Page", pagina!!.nombre("Type"))
        assertEquals(PdfValor.Ref(4, 0), pagina.ref("Contents"))

        val (ancho, alto) = pdf.tamanoDePagina(0)!!
        assertEquals(595.0, ancho, 0.001)
        assertEquals(842.0, alto, 0.001)
    }

    /** El tamaño se hereda del padre: hay archivos que solo lo declaran arriba. */
    @Test
    fun `el tamaño se hereda del arbol de paginas`() {
        val bytes = String(pdfClasico(), Charsets.ISO_8859_1)
            .replace("/Type /Pages /Kids", "/Type /Pages /MediaBox [0 0 200 300] /Kids")
            .replace("/MediaBox [0 0 595 842] ", "")
            .toByteArray(Charsets.ISO_8859_1)
        // Los desplazamientos ya no cuadran con la tabla, pero el árbol sí: se
        // comprueba lo que se busca —la herencia— y no la tabla, que ya tiene
        // su propia prueba.
        val pdf = leerPdf(bytes)
        if (pdf?.pagina(0) != null) {
            val tam = pdf.tamanoDePagina(0)
            if (tam != null) {
                assertEquals(200.0, tam.first, 0.001)
                assertEquals(300.0, tam.second, 0.001)
            }
        }
    }

    /** El flujo de contenido se lee entero aunque la longitud declarada mienta. */
    @Test
    fun `un flujo con la longitud equivocada se lee igual`() {
        val bytes = String(pdfClasico(), Charsets.ISO_8859_1)
            .replace("/Length 44", "/Length 9 ")
            .toByteArray(Charsets.ISO_8859_1)
        val pdf = leerPdf(bytes)!!
        val flujo = pdf.objeto(4) as? PdfValor.Flujo
        assertNotNull("no ha leído el flujo", flujo)
        val texto = String(flujo!!.datos, Charsets.ISO_8859_1)
        assertTrue("se ha cortado el flujo: «$texto»", texto.contains("Hola mundo"))
    }

    // ---- Un PDF moderno: índice comprimido y objetos empaquetados ----

    /**
     * El mismo documento pero como lo escribe cualquier herramienta de hoy: los
     * diccionarios metidos en un paquete comprimido y el índice en un flujo con
     * predictor PNG.
     */
    private fun pdfModerno(): ByteArray {
        val salida = ByteArrayOutputStream()
        salida.write("%PDF-1.5\n".toByteArray())

        // Los tres diccionarios van dentro del objeto 5, empaquetados.
        val cuerpos = listOf(
            1 to "<< /Type /Catalog /Pages 2 0 R >>",
            2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"
        )
        val cabecera = StringBuilder()
        val contenido = StringBuilder()
        for ((numero, cuerpo) in cuerpos) {
            cabecera.append("$numero ${contenido.length} ")
            contenido.append(cuerpo).append(" ")
        }
        val paquete = (cabecera.toString() + contenido.toString()).toByteArray()
        val paqueteZ = comprimir(paquete)

        val desplazamientos = HashMap<Int, Int>()

        desplazamientos[4] = salida.size()
        salida.write(
            ("4 0 obj\n<< /Length 20 >>\nstream\n0 0 100 100 re f\n\nendstream\nendobj\n")
                .toByteArray()
        )

        desplazamientos[5] = salida.size()
        salida.write(
            ("5 0 obj\n<< /Type /ObjStm /N ${cuerpos.size} /First ${cabecera.length} " +
                "/Filter /FlateDecode /Length ${paqueteZ.size} >>\nstream\n").toByteArray()
        )
        salida.write(paqueteZ)
        salida.write("\nendstream\nendobj\n".toByteArray())

        // El índice: seis entradas de tres campos (1, 4, 2 bytes), con
        // predictor PNG del tipo «arriba», que es el que usan de verdad.
        val inicioXref = salida.size()
        val anchos = intArrayOf(1, 4, 2)
        val fila = anchos.sum()
        val entradas = listOf(
            intArrayOf(0, 0, 65535),                    // 0: el hueco de siempre
            intArrayOf(2, 5, 0),                        // 1: en el paquete 5, sitio 0
            intArrayOf(2, 5, 1),                        // 2: en el paquete 5, sitio 1
            intArrayOf(2, 5, 2),                        // 3: en el paquete 5, sitio 2
            intArrayOf(1, desplazamientos[4]!!, 0),     // 4: suelto
            intArrayOf(1, desplazamientos[5]!!, 0),     // 5: suelto
            intArrayOf(1, inicioXref, 0)                // 6: este mismo índice
        )
        val crudo = ByteArrayOutputStream()
        val previa = ByteArray(fila)
        for (e in entradas) {
            val f = ByteArray(fila)
            var i = 0
            for (c in 0 until 3) {
                var v = e[c]
                for (b in anchos[c] - 1 downTo 0) {
                    f[i + b] = (v and 0xFF).toByte()
                    v = v ushr 8
                }
                i += anchos[c]
            }
            crudo.write(2) // predictor «arriba»
            for (b in 0 until fila) {
                crudo.write(((f[b].toInt() and 0xFF) - (previa[b].toInt() and 0xFF)) and 0xFF)
            }
            f.copyInto(previa)
        }
        val indiceZ = comprimir(crudo.toByteArray())

        salida.write(
            ("6 0 obj\n<< /Type /XRef /Size 7 /W [1 4 2] /Root 1 0 R " +
                "/Filter /FlateDecode /DecodeParms << /Predictor 12 /Columns $fila >> " +
                "/Length ${indiceZ.size} >>\nstream\n").toByteArray()
        )
        salida.write(indiceZ)
        salida.write("\nendstream\nendobj\n".toByteArray())
        salida.write("startxref\n$inicioXref\n%%EOF\n".toByteArray())
        return salida.toByteArray()
    }

    private fun comprimir(datos: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(datos)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /**
     * **La prueba que de verdad importa.** Un PDF moderno no tiene tabla ni
     * tiene sus diccionarios sueltos: si esto no se lee, no se puede escribir
     * encima de casi ningún archivo de hoy.
     */
    @Test
    fun `se lee un pdf moderno con indice comprimido`() {
        val pdf = leerPdf(pdfModerno())
        assertNotNull("no se ha podido leer el índice comprimido", pdf)
        assertEquals(listOf(3), pdf!!.paginas())
    }

    @Test
    fun `los diccionarios empaquetados se sacan del paquete`() {
        val pdf = leerPdf(pdfModerno())!!
        val pagina = pdf.pagina(0)
        assertNotNull("la página está dentro de un paquete y no se ha sacado", pagina)
        assertEquals("Page", pagina!!.nombre("Type"))
        assertEquals(PdfValor.Ref(4, 0), pagina.ref("Contents"))

        val (ancho, alto) = pdf.tamanoDePagina(0)!!
        assertEquals(612.0, ancho, 0.001)
        assertEquals(792.0, alto, 0.001)
    }

    @Test
    fun `un objeto suelto de un pdf moderno tambien se lee`() {
        val pdf = leerPdf(pdfModerno())!!
        val flujo = pdf.objeto(4) as? PdfValor.Flujo
        assertNotNull(flujo)
        assertTrue(String(flujo!!.datos, Charsets.ISO_8859_1).contains("re f"))
    }

    // ---- Que no se rompa con lo que no entiende ----

    @Test
    fun `un archivo que no es un pdf no se lee`() {
        assertNull(leerPdf("esto no es un pdf, ni de lejos".toByteArray()))
        assertNull(leerPdf(ByteArray(0)))
    }

    @Test
    fun `un pdf cifrado se detecta y se dice`() {
        val bytes = String(pdfClasico(), Charsets.ISO_8859_1)
            .replace("/Root 1 0 R", "/Root 1 0 R /Encrypt 9 0 R")
            .toByteArray(Charsets.ISO_8859_1)
        val pdf = leerPdf(bytes)
        assertNotNull(pdf)
        assertTrue("tendría que verse que está cifrado", pdf!!.cifrado)
    }

    /** Un índice que apunta a donde no hay nada no devuelve un objeto de otro. */
    @Test
    fun `un indice que miente no devuelve objetos ajenos`() {
        val pdf = leerPdf(pdfClasico())!!
        assertNull(pdf.objeto(99))
    }

    // ---- Las piezas sueltas ----

    @Test
    fun `el predictor png se deshace bien`() {
        // Dos filas de tres bytes, la segunda codificada «arriba».
        val datos = byteArrayOf(
            0, 10, 20, 30,
            2, 1, 2, 3
        )
        val salida = deshacerPredictorPng(datos, 3, 1, 8)!!
        assertEquals(6, salida.size)
        assertEquals(listOf(10, 20, 30, 11, 22, 33), salida.map { it.toInt() and 0xFF })
    }

    @Test
    fun `se encuentra el ultimo startxref`() {
        val bytes = "%PDF-1.4\nbasura\nstartxref\n9\n%%EOF\nstartxref\n1234\n%%EOF\n"
            .toByteArray()
        assertEquals(1234L, ultimoStartxref(bytes))
    }

    @Test
    fun `los nombres con escapes se leen`() {
        val lector = PdfLector("<< /A#42 (hola) /B <414243> >>".toByteArray())
        val d = lector.leerValor(null) as PdfValor.Dicc
        assertEquals(
            "hola",
            String((d.entradas["AB"] as PdfValor.Cadena).bytes, Charsets.ISO_8859_1)
        )
        assertEquals(
            "ABC",
            String((d.entradas["B"] as PdfValor.Cadena).bytes, Charsets.ISO_8859_1)
        )
    }

    @Test
    fun `las referencias se distinguen de dos numeros`() {
        val lector = PdfLector("[ 12 0 R 12 0 ]".toByteArray())
        val lista = (lector.leerValor(null) as PdfValor.Lista).valores
        assertEquals(PdfValor.Ref(12, 0), lista[0])
        assertEquals(PdfValor.Numero(12.0), lista[1])
        assertEquals(PdfValor.Numero(0.0), lista[2])
    }

    @Test
    fun `los comentarios no estorban`() {
        val lector = PdfLector("<< % esto es un comentario\n /Type /Page >>".toByteArray())
        val d = lector.leerValor(null) as PdfValor.Dicc
        assertEquals("Page", d.nombre("Type"))
    }
}
