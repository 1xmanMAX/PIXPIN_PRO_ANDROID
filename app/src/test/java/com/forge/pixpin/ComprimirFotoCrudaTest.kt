package com.forge.pixpin.pdf

import com.forge.pixpin.motor.PdfValor
import com.forge.pixpin.motor.leerPdf
import com.forge.pixpin.motor.nombre
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Una página escaneada que el PDF guarda en crudo pasa a JPEG.**
 *
 * Es de donde sale el ahorro de verdad: un escáner no guarda JPEG, guarda los píxeles comprimidos
 * con Deflate, y por eso «una página pesada más de una mega» se quedaba igual (usuario,
 * 14-sep-2026). Esta prueba necesita rasterizar de verdad —hacer el JPEG—, así que va por
 * Robolectric y no por la JVM sola.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComprimirFotoCrudaTest {

    /** Una «foto» de 900×600: franjas de color con ruido, que es lo que el Deflate apenas aprieta. */
    private fun muestras(ancho: Int, alto: Int): ByteArray {
        val datos = ByteArray(ancho * alto * 3)
        val r = java.util.Random(3)
        var i = 0
        for (y in 0 until alto) for (x in 0 until ancho) {
            datos[i++] = (x * 255 / ancho + r.nextInt(40)).toByte()
            datos[i++] = (y * 255 / alto + r.nextInt(40)).toByte()
            datos[i++] = (160 + r.nextInt(60)).toByte()
        }
        return datos
    }

    private fun aprieta(datos: ByteArray): ByteArray {
        val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        d.setInput(datos); d.finish()
        val salida = ByteArrayOutputStream()
        val tanda = ByteArray(1 shl 16)
        while (!d.finished()) {
            val n = d.deflate(tanda)
            if (n <= 0) break
            salida.write(tanda, 0, n)
        }
        d.end()
        return salida.toByteArray()
    }

    /** Un PDF de una página con esa foto en crudo ocupándola entera. */
    private fun pdfEscaneado(
        px: Int,
        py: Int,
        ancho: Int = 595,
        alto: Int = 842,
        catalogo: String = "",
        crudas: ByteArray? = null,
        espacio: String = "/DeviceRGB"
    ): ByteArray {
        val foto = aprieta(crudas ?: muestras(px, py))
        val objetos = ArrayList<Pair<String, ByteArray?>>()
        objetos += "<< /Type /Catalog /Pages 2 0 R $catalogo>>" to null
        objetos += "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 $ancho $alto] >>" to null
        objetos += "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /XObject << /Im0 5 0 R >> >> >>" to null
        val contenido = "q $ancho 0 0 $alto 0 0 cm /Im0 Do Q".toByteArray()
        objetos += "<< /Length ${contenido.size} >>" to contenido
        objetos += ("<< /Type /XObject /Subtype /Image /Width $px /Height $py /ColorSpace $espacio " +
            "/BitsPerComponent 8 /Filter /FlateDecode /Length ${foto.size} >>") to foto
        if (catalogo.isNotEmpty()) objetos += "<< /Type /Outlines /Count 0 >>" to null
        val salida = ByteArrayOutputStream()
        salida.write("%PDF-1.5\n".toByteArray(Charsets.ISO_8859_1))
        val sitios = ArrayList<Int>()
        for ((i, o) in objetos.withIndex()) {
            sitios += salida.size()
            salida.write("${i + 1} 0 obj\n${o.first}\n".toByteArray(Charsets.ISO_8859_1))
            o.second?.let { salida.write("stream\n".toByteArray()); salida.write(it); salida.write("\nendstream\n".toByteArray()) }
            salida.write("endobj\n".toByteArray())
        }
        val xref = salida.size()
        val tabla = StringBuilder("xref\n0 ${objetos.size + 1}\n0000000000 65535 f \n")
        for (s in sitios) tabla.append("%010d 00000 n \n".format(s))
        tabla.append("trailer\n<< /Size ${objetos.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        salida.write(tabla.toString().toByteArray(Charsets.ISO_8859_1))
        return salida.toByteArray()
    }

    @Test
    fun `una pagina escaneada en crudo se guarda en jpeg y baja de peso`() {
        val gordo = pdfEscaneado(900, 600)
        val ligero = ComprimirPdf.comprimir(gordo)
        assertNotNull("no se comprimió", ligero)
        assertTrue("${ligero!!.size} no baja de ${gordo.size}", ligero.size < gordo.size / 2)
        // Y la imagen de dentro ya es un JPEG del mismo tamaño en píxeles: se apretó, no se recortó.
        val leido = leerPdf(ligero)!!
        val imagenes = (1..20).mapNotNull { leido.resolver(PdfValor.Ref(it, 0)) as? PdfValor.Flujo }
            .filter { it.dicc.nombre("Subtype") == "Image" }
        assertEquals(1, imagenes.size)
        assertEquals("DCTDecode", imagenes[0].dicc.nombre("Filter"))
        assertEquals(900, (imagenes[0].dicc.entradas["Width"] as PdfValor.Numero).valor.toInt())
    }

    /**
     * Y un dibujo de líneas —zonas planas, que el Deflate aprieta a nada— **no** se pasa a JPEG: se
     * intenta y **se rechaza porque sale más gordo**, que es la prueba que de verdad importa (antes
     * se adivinaba por el ratio de compresión y eso dejaba fuera escaneos de texto que sí ganaban).
     */
    @Test
    fun `un dibujo de lineas en crudo se deja como estaba`() {
        val ancho = 900
        val alto = 600
        // Blanco con unas rayas negras: el Deflate lo baja a menos del 1 %.
        val datos = ByteArray(ancho * alto * 3) { -1 }
        for (y in 0 until alto step 40) for (x in 0 until ancho) {
            val o = (y * ancho + x) * 3
            datos[o] = 0; datos[o + 1] = 0; datos[o + 2] = 0
        }
        val foto = aprieta(datos)
        assertTrue("el caso de la prueba: tiene que apretar mucho", foto.size < datos.size / 50)
        val flujo = PdfValor.Flujo(
            PdfValor.Dicc(
                linkedMapOf(
                    "Subtype" to PdfValor.Nombre("Image"),
                    "Filter" to PdfValor.Nombre("FlateDecode"),
                    "ColorSpace" to PdfValor.Nombre("DeviceRGB"),
                    "Width" to PdfValor.Numero(ancho.toDouble()),
                    "Height" to PdfValor.Numero(alto.toDouble()),
                    "BitsPerComponent" to PdfValor.Numero(8.0)
                )
            ),
            foto
        )
        assertEquals(
            null,
            ComprimirPdf.fotoMasLigera(flujo, 2480, ComprimirPdf.Ayuda(descomprimir = { datos }))
        )
    }

    /**
     * **Una página escaneada a 300 ppp baja alrededor de tres cuartas partes**, que es el orden de
     * lo que hacen los compresores de internet con los que el usuario lo comparó. El ahorro sale de
     * dos sitios a la vez: los píxeles que sobran por encima de [ComprimirPdf.PPP] y el JPEG.
     */
    @Test
    fun `un escaneo a 300 ppp baja como lo hacen los compresores de internet`() {
        // Página de 200×300 puntos con una foto de 833×1250: sus 300 ppp.
        val gordo = pdfEscaneado(833, 1250, ancho = 200, alto = 300)
        val ligero = ComprimirPdf.comprimir(gordo)!!
        val cuanto = 1.0 - ligero.size.toDouble() / gordo.size
        assertTrue("solo bajó el ${(cuanto * 100).toInt()} %", cuanto > 0.6)
        // Y la foto queda a la resolución de imprimir, no a la del escáner.
        val imagen = (1..20).mapNotNull { leerPdf(ligero)!!.resolver(PdfValor.Ref(it, 0)) as? PdfValor.Flujo }
            .first { it.dicc.nombre("Subtype") == "Image" }
        val anchoNuevo = (imagen.dicc.entradas["Width"] as PdfValor.Numero).valor.toInt()
        val quePinta = (200.0 / 72.0 * ComprimirPdf.PPP).toInt()
        assertTrue("$anchoNuevo no es el ancho de $quePinta", kotlin.math.abs(anchoNuevo - quePinta) <= 12)
    }

    /**
     * **Y un documento con marcadores también.** Antes se dejaba entero sin tocar —rehacerlo por
     * páginas los perdería— y eso es casi todo lo que sale de un programa de oficina.
     */
    @Test
    fun `un pdf con marcadores y formulario ya se aligera`() {
        val gordo = pdfEscaneado(833, 1250, ancho = 200, alto = 300, catalogo = "/Outlines 6 0 R /AcroForm << /Fields [] >>")
        val ligero = ComprimirPdf.comprimir(gordo)
        assertNotNull("se quedó sin tocar", ligero)
        assertTrue(ligero!!.size < gordo.size / 2)
        val raiz = leerPdf(ligero)!!.let { it.diccDe(it.trailer.entradas["Root"]) }!!
        assertTrue("se perdieron los marcadores", raiz.entradas.containsKey("Outlines"))
        assertTrue("se perdió el formulario", raiz.entradas.containsKey("AcroForm"))
    }

    /** Un escaneo con paleta de colores (`/Indexed`) también: se rechazaba entero. */
    @Test
    fun `un escaneo con paleta de colores se aligera`() {
        val ancho = 833
        val alto = 1250
        // 256 grises en paleta, con ruido: un canal por píxel.
        val r = java.util.Random(11)
        val datos = ByteArray(ancho * alto) { (it % 200 + r.nextInt(50)).toByte() }
        val paleta = ByteArray(256 * 3) { (it / 3).toByte() }
        val gordo = pdfEscaneado(
            ancho, alto, ancho = 200, alto = 300, crudas = datos,
            espacio = "[/Indexed /DeviceRGB 255 <${paleta.joinToString("") { "%02x".format(it) }}>]"
        )
        val ligero = ComprimirPdf.comprimir(gordo)
        assertNotNull("la paleta se rechazaba entera", ligero)
        assertTrue("${ligero!!.size} vs ${gordo.size}", ligero.size < gordo.size)
    }
}
