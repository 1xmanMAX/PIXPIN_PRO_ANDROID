package com.forge.pixpin.pdf

import com.forge.pixpin.motor.PdfValor
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **Aligerar un PDF no puede estropearlo.** Lo que aquí se prueba son los guardas: qué fotos no
 * se tocan y qué PDF se dejan enteros. Rasterizar no se puede probar (no hay `BitmapFactory` de
 * verdad en estas pruebas, ver [[pixpin-pruebas-de-pintado]]), así que cada caso de abajo se
 * queda **antes** de llegar a la foto; si un guarda se cayera, la prueba fallaría al intentar
 * decodificar.
 */
class ComprimirPdfTest {

    private fun flujo(vararg entradas: Pair<String, PdfValor>, datos: ByteArray = ByteArray(4096)) =
        PdfValor.Flujo(PdfValor.Dicc(linkedMapOf(*entradas)), datos)

    private fun foto(
        filtro: String = "DCTDecode",
        espacio: PdfValor = PdfValor.Nombre("DeviceRGB"),
        extra: Map<String, PdfValor> = emptyMap(),
    ) = flujo(
        "Subtype" to PdfValor.Nombre("Image"),
        "Filter" to PdfValor.Nombre(filtro),
        "ColorSpace" to espacio,
        "Width" to PdfValor.Numero(2000.0),
        "Height" to PdfValor.Numero(3000.0),
        "BitsPerComponent" to PdfValor.Numero(8.0),
        *extra.toList().toTypedArray(),
    )

    /**
     * **Lo que no se toca nunca**, comprobado sin llegar a rasterizar nada: si un guarda se
     * cayera, la prueba fallaría al intentar descodificar la imagen (aquí no hay `BitmapFactory`
     * de verdad). Lo que sí se toca se prueba en `ComprimirFotoCrudaTest`, con Robolectric.
     */
    @Test
    fun `lo que no es una foto normal se deja en paz`() {
        val lado = 2480
        assertNull("un flujo que no es foto", ComprimirPdf.fotoMasLigera(flujo("Subtype" to PdfValor.Nombre("Form")), lado))
        assertNull("un CCITT no se sabe deshacer", ComprimirPdf.fotoMasLigera(foto(filtro = "CCITTFaxDecode"), lado))
        assertNull("un JPEG 2000 tampoco", ComprimirPdf.fotoMasLigera(foto(filtro = "JPXDecode"), lado))
        assertNull("una máscara de recorte no es una foto", ComprimirPdf.fotoMasLigera(foto(extra = mapOf("Mask" to PdfValor.Ref(3, 0))), lado))
        assertNull("un esténcil tampoco", ComprimirPdf.fotoMasLigera(foto(extra = mapOf("ImageMask" to PdfValor.Nombre("true"))), lado))
        assertNull("con /Decode los valores no significan lo de siempre", ComprimirPdf.fotoMasLigera(foto(extra = mapOf("Decode" to PdfValor.Lista(emptyList()))), lado))
        assertNull("un patrón no es un color que se pueda leer", ComprimirPdf.fotoMasLigera(foto(filtro = "FlateDecode", espacio = PdfValor.Nombre("Pattern")), lado))
        // Un color de 1 bit que no va por paleta es una página en blanco y negro: ya pesa poco.
        assertNull(
            ComprimirPdf.fotoMasLigera(
                foto(filtro = "FlateDecode", extra = mapOf("BitsPerComponent" to PdfValor.Numero(1.0))), lado,
                ComprimirPdf.Ayuda(descomprimir = { ByteArray(1024) })
            )
        )
    }

    /**
     * Un PDF cuyos flujos van en claro **sí** se aprieta: es gratis y sin pérdida. El de aquí los
     * lleva sin filtro, así que lo que gana es eso.
     */
    @Test
    fun `lo que va sin comprimir se comprime`() {
        val gordo = pdf(relleno = "hola ".repeat(400))
        val ligero = ComprimirPdf.comprimir(gordo)
        assertNotNull("un texto repetido tiene que bajar", ligero)
        assertTrue("${ligero!!.size} vs ${gordo.size}", ligero.size < gordo.size)
        // Y sigue siendo un PDF que se lee, con su página y su texto dentro.
        val leido = com.forge.pixpin.motor.leerPdf(ligero)!!
        assertEquals(1, leido.paginas().size)
    }

    @Test
    fun `un flujo ya bien apretado se deja en paz`() {
        val datos = java.util.Random(7).let { r -> ByteArray(4096) { r.nextInt(256).toByte() } }   // al azar: no baja
        val flate = com.forge.pixpin.motor.PdfValor.Flujo(
            com.forge.pixpin.motor.PdfValor.Dicc(mapOf("Filter" to PdfValor.Nombre("FlateDecode"))),
            aprieta(datos)
        )
        assertNull(ComprimirPdf.masApretado(flate) { datos })
        // Ni lo que no es Flate ni lo pequeño.
        val jpeg = com.forge.pixpin.motor.PdfValor.Flujo(
            com.forge.pixpin.motor.PdfValor.Dicc(mapOf("Filter" to PdfValor.Nombre("DCTDecode"))), ByteArray(4096)
        )
        assertNull(ComprimirPdf.masApretado(jpeg) { null })
        assertNull(ComprimirPdf.masApretado(flujo(datos = ByteArray(100))) { null })
    }

    /** Y uno apretado de mala manera baja sin tocar su contenido. */
    @Test
    fun `un flujo mal apretado se vuelve a apretar`() {
        val crudo = "linea de un plano\n".repeat(500).toByteArray()
        val flojo = aprieta(crudo, java.util.zip.Deflater.NO_COMPRESSION)
        val f = com.forge.pixpin.motor.PdfValor.Flujo(
            com.forge.pixpin.motor.PdfValor.Dicc(mapOf("Filter" to PdfValor.Nombre("FlateDecode"))), flojo
        )
        val mejor = ComprimirPdf.masApretado(f) { crudo }
        assertNotNull(mejor)
        assertTrue("${mejor!!.datos.size} vs ${flojo.size}", mejor.datos.size < flojo.size / 2)
        assertEquals("el mismo contenido", crudo.toList(), desaprieta(mejor.datos).toList())
    }

    /**
     * **Un color que no se sabe leer se deja en paz**, y los que sí se leen se aceptan.
     *
     * Antes solo pasaban el gris y el RGB, y eso era el grueso de los documentos que se quedaban
     * igual: un escaneo de pocos colores va con paleta y una exportación de imprenta, en CMYK.
     */
    @Test
    fun `los colores que se saben leer`() {
        fun color(cs: PdfValor) = ComprimirPdf.colorDe(PdfValor.Dicc(mapOf("ColorSpace" to cs)), ComprimirPdf.Ayuda())
        assertEquals(1, color(PdfValor.Nombre("DeviceGray"))?.canales)
        assertEquals(3, color(PdfValor.Nombre("DeviceRGB"))?.canales)
        assertTrue("el cmyk se convierte", color(PdfValor.Nombre("DeviceCMYK"))?.cmyk == true)
        assertNull("un patrón no es una foto", color(PdfValor.Nombre("Pattern")))
        // Una paleta: un canal —el índice— y su tabla de colores ya en pantalla.
        val indexado = PdfValor.Lista(
            listOf(
                PdfValor.Nombre("Indexed"), PdfValor.Nombre("DeviceRGB"), PdfValor.Numero(1.0),
                PdfValor.Cadena(byteArrayOf(-1, 0, 0, 0, 0, -1))
            )
        )
        val paleta = color(indexado)
        assertEquals(1, paleta?.canales)
        assertEquals(0xFFFF0000.toInt(), paleta?.paleta?.get(0))
        assertEquals(0xFF0000FF.toInt(), paleta?.paleta?.get(1))
    }

    private fun aprieta(datos: ByteArray, nivel: Int = java.util.zip.Deflater.BEST_SPEED): ByteArray {
        val d = java.util.zip.Deflater(nivel)
        d.setInput(datos); d.finish()
        val salida = ByteArrayOutputStream()
        val tanda = ByteArray(8192)
        while (!d.finished()) salida.write(tanda, 0, d.deflate(tanda))
        d.end()
        return salida.toByteArray()
    }

    private fun desaprieta(datos: ByteArray): ByteArray {
        val i = java.util.zip.Inflater()
        i.setInput(datos)
        val salida = ByteArrayOutputStream()
        val tanda = ByteArray(8192)
        while (!i.finished()) {
            val n = i.inflate(tanda)
            if (n <= 0) break
            salida.write(tanda, 0, n)
        }
        i.end()
        return salida.toByteArray()
    }

    @Test
    fun `los planos con capas de AutoCAD no se rehacen`() {
        assertNull(ComprimirPdf.comprimir(pdf(raiz = "/OCProperties << /OCGs [] >>")))
        assertNull(ComprimirPdf.comprimir(pdf(raiz = "/AcroForm << >>")))
        assertNull(ComprimirPdf.comprimir(pdf(raiz = "/Outlines 1 0 R")))
    }

    @Test
    fun `el ancho nativo es el de la foto mas grande de la pagina`() {
        val archivo = File.createTempFile("escaneo", ".pdf")
        archivo.writeBytes(pdf(conFotos = listOf(1700 to 2200, 64 to 64)))
        assertEquals(1700, ComprimirPdf.anchoNativo(archivo.path, 0))
        assertNull("la página que no existe", ComprimirPdf.anchoNativo(archivo.path, 7))
        archivo.delete()
    }

    @Test
    fun `una pagina sin fotos no tiene ancho nativo`() {
        val archivo = File.createTempFile("vector", ".pdf")
        archivo.writeBytes(pdf())
        assertNull(ComprimirPdf.anchoNativo(archivo.path, 0))
        archivo.delete()
    }

    /** Un PDF de una página A4 con un texto, las fotos que se pidan y lo que se añada al catálogo. */
    private fun pdf(raiz: String = "", conFotos: List<Pair<Int, Int>> = emptyList(), relleno: String = ""): ByteArray {
        val objetos = ArrayList<String>()
        val primeraFoto = 5
        val recursos = StringBuilder("/Font << /F1 4 0 R >>")
        if (conFotos.isNotEmpty()) {
            recursos.append(" /XObject << ")
            for (i in conFotos.indices) recursos.append("/Im$i ${primeraFoto + i} 0 R ")
            recursos.append(">>")
        }
        objetos += "<< /Type /Catalog /Pages 2 0 R $raiz >>"
        objetos += "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 595 842] >>"
        objetos += "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << $recursos >> >>"
        val contenido = "BT /F1 12 Tf 20 20 Td (hola) Tj ET" + if (relleno.isEmpty()) "" else "\n% $relleno"
        objetos += "<< /Length ${contenido.length} >>\nstream\n$contenido\nendstream"
        for ((an, al) in conFotos) {
            val datos = "x".repeat(64)
            objetos += "<< /Type /XObject /Subtype /Image /Width $an /Height $al /ColorSpace /DeviceRGB " +
                "/BitsPerComponent 8 /Filter /DCTDecode /Length ${datos.length} >>\nstream\n$datos\nendstream"
        }
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
}
