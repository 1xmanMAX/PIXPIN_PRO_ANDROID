package com.forge.pixpin.motor

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las páginas de un PDF, pegadas detrás de las de otro**, como páginas de verdad:
 * el resultado se lee, cuenta todas, y las nuevas conservan su tamaño y su contenido.
 */
class PdfUnionTest {

    /** Un PDF de [paginas] páginas de [ancho]×[alto], con tamaño heredado del árbol y un texto en cada una. */
    private fun pdf(paginas: Int, ancho: Int, alto: Int, texto: String): ByteArray {
        val objetos = ArrayList<String>()
        objetos += "<< /Type /Catalog /Pages 2 0 R >>"
        val kids = (0 until paginas).joinToString(" ") { "${3 + it * 2} 0 R" }
        objetos += "<< /Type /Pages /Kids [$kids] /Count $paginas /MediaBox [0 0 $ancho $alto] /Resources << /Font << /F1 ${3 + paginas * 2} 0 R >> >> >>"
        for (i in 0 until paginas) {
            objetos += "<< /Type /Page /Parent 2 0 R /Contents ${4 + i * 2} 0 R >>"
            val contenido = "BT /F1 12 Tf 20 20 Td ($texto ${i + 1}) Tj ET"
            objetos += "<< /Length ${contenido.length} >>\nstream\n$contenido\nendstream"
        }
        objetos += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        val salida = ByteArrayOutputStream()
        salida.write("%PDF-1.4\n".toByteArray())
        val sitios = ArrayList<Int>()
        for ((i, o) in objetos.withIndex()) {
            sitios += salida.size()
            salida.write("${i + 1} 0 obj\n$o\nendobj\n".toByteArray())
        }
        val xref = salida.size()
        val tabla = StringBuilder("xref\n0 ${objetos.size + 1}\n0000000000 65535 f \n")
        for (s in sitios) tabla.append(String.format("%010d 00000 n \n", s))
        tabla.append("trailer\n<< /Size ${objetos.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        salida.write(tabla.toString().toByteArray())
        return salida.toByteArray()
    }

    @Test
    fun `dos y uno hacen tres, y la nueva conserva su tamano y su texto`() {
        val a = pdf(2, 200, 100, "A")
        val b = pdf(1, 300, 400, "B")
        val unidos = PdfUnion.anadirPaginas(a, b)
        assertNotNull("no se pudo unir", unidos)
        val leido = leerPdf(unidos!!)!!
        assertEquals(3, leido.paginas().size)
        assertEquals(Pair(200.0, 100.0), leido.tamanoDePagina(0))
        assertEquals("la tercera es la del segundo, con su tamaño", Pair(300.0, 400.0), leido.tamanoDePagina(2))
        // Y el original sigue intacto delante: ni un byte movido.
        assertTrue(unidos.copyOfRange(0, a.size).contentEquals(a))
        // El contenido de la página copiada viaja con ella.
        val pagina = leido.pagina(2)!!
        val contenido = leido.resolver(pagina.entradas["Contents"]) as PdfValor.Flujo
        assertTrue(String(contenido.datos).contains("(B 1)"))
        // Y sus recursos, que heredaba del árbol del segundo, van escritos dentro.
        assertNotNull(pagina.entradas["Resources"])
    }

    @Test
    fun `un pdf cifrado no se toca`() {
        val a = pdf(1, 100, 100, "A")
        val roto = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R /Encrypt 9 0 R >>".toByteArray()
        assertEquals(null, PdfUnion.anadirPaginas(a, roto))
    }

    @Test
    fun `solo unas paginas salen tal cual y en el orden pedido`() {
        val plano = pdf(4, 200, 100, "P")
        val trozo = PdfUnion.soloPaginas(plano, listOf(2, 0))
        assertNotNull("no se pudo sacar", trozo)
        val leido = leerPdf(trozo!!)!!
        assertEquals(2, leido.paginas().size)
        val primera = leido.resolver(leido.pagina(0)!!.entradas["Contents"]) as PdfValor.Flujo
        assertTrue("la primera es la tercera del plano", String(primera.datos).contains("(P 3)"))
        val segunda = leido.resolver(leido.pagina(1)!!.entradas["Contents"]) as PdfValor.Flujo
        assertTrue(String(segunda.datos).contains("(P 1)"))
        assertEquals(Pair(200.0, 100.0), leido.tamanoDePagina(0))
    }

    @Test
    fun `sin paginas pedidas no sale nada`() {
        assertEquals(null, PdfUnion.soloPaginas(pdf(2, 100, 100, "A"), emptyList()))
    }

    @Test
    fun `un lienzo con marcos ofrece tambien el lienzo completo delante`() {
        val marco1 = Element(id = "m1", type = ElementType.FRAME, x = 0.0, y = 0.0, width = 100.0, height = 100.0, seed = 1)
        val marco2 = Element(id = "m2", type = ElementType.FRAME, x = 200.0, y = 0.0, width = 100.0, height = 100.0, seed = 1)
        val escena = Scene(elements = listOf(marco1, marco2))
        val p = Proyecto("pr", "Obra", hojas = listOf(Hoja("h1", "Planta", dibujo = "d1"), Hoja("h2", "Nota", nota = "hola")))
        val con = HojasDelProyecto.conEntero(p) { if (it == "d1") escena else null }
        assertEquals(listOf("h1///", "h1/m1/", "h1/m2/", "h2//").map { it.replace("///", "//") }, con.map { it.clave })
        assertEquals(HojasDelProyecto.NOMBRE_DEL_ENTERO, con[0].nombre)
        assertEquals(listOf("h1/m2/"), HojasDelProyecto.elegidas(p, { escena }, setOf("h1/m2/")).map { it.clave })
    }
}
