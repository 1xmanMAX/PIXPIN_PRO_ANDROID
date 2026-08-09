package com.forge.pixpin.motor

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Escribir dentro de un PDF ajeno sin romperlo.
 *
 * Es el sitio del proyecto donde un fallo **destruye el archivo de otro**, así
 * que aquí no basta con que el resultado se lea: cada prueba comprueba, además,
 * que el original sigue entero byte por byte. Un exportador que devuelve algo
 * parecido no vale — un PDF corrupto se descubre cuando el que lo recibió
 * intenta abrirlo, que es tarde.
 *
 * Los archivos se escriben a mano, con sus dos tipos de índice: la tabla clásica
 * y el flujo comprimido de los PDF modernos. Sin los dos no se comprueba nada,
 * porque una actualización incremental tiene que escribir su índice del mismo
 * tipo que el que había.
 */
class PdfEscrituraTest {

    // ---- Dos PDF hechos a mano ----

    private fun pdfClasico(): ByteArray {
        val objetos = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n",
            "4 0 obj\n<< /Length 42 >>\nstream\n" +
                "BT /F1 24 Tf 72 700 Td (Hola mundo) Tj ET\n\nendstream\nendobj\n",
            "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
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

    /** El de hoy: índice comprimido, con `/Rotate` y sin `/Resources` propios. */
    private fun pdfModerno(giro: Int = 0): ByteArray {
        val salida = ByteArrayOutputStream()
        salida.write("%PDF-1.5\n".toByteArray())
        val donde = HashMap<Int, Int>()

        fun objeto(numero: Int, cuerpo: String) {
            donde[numero] = salida.size()
            salida.write("$numero 0 obj\n$cuerpo\nendobj\n".toByteArray())
        }

        objeto(1, "<< /Type /Catalog /Pages 2 0 R >>")
        // Los recursos viven en el padre: la página los hereda, que es lo
        // corriente y lo que más fácil se rompe al reescribirla.
        objeto(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 /Resources << /Font << /F1 5 0 R >> >> >>")
        objeto(
            3,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                (if (giro != 0) "/Rotate $giro " else "") + "/Contents 4 0 R >>"
        )
        val contenido = "BT /F1 24 Tf 72 700 Td (Hola) Tj ET\n"
        objeto(4, "<< /Length ${contenido.length} >>\nstream\n$contenido\nendstream")
        objeto(5, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

        // El índice, como flujo: [1 4 2] por entrada, sin predictor.
        val numeroIndice = 6
        val inicioIndice = salida.size()
        donde[numeroIndice] = inicioIndice
        val datos = ByteArrayOutputStream()
        datos.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0))    // el objeto 0, libre
        for (n in 1..numeroIndice) {
            val off = donde.getValue(n)
            datos.write(1)
            datos.write((off ushr 24) and 0xFF)
            datos.write((off ushr 16) and 0xFF)
            datos.write((off ushr 8) and 0xFF)
            datos.write(off and 0xFF)
            datos.write(0); datos.write(0)
        }
        val comprimido = comprimir(datos.toByteArray())
        salida.write(
            (
                "$numeroIndice 0 obj\n<< /Type /XRef /Size ${numeroIndice + 1} " +
                    "/W [1 4 2] /Root 1 0 R /Filter /FlateDecode " +
                    "/Length ${comprimido.size} >>\nstream\n"
                ).toByteArray()
        )
        salida.write(comprimido)
        salida.write("\nendstream\nendobj\n".toByteArray())
        salida.write("startxref\n$inicioIndice\n%%EOF\n".toByteArray())
        return salida.toByteArray()
    }

    private fun comprimir(datos: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(datos); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private val dibujo = "1 0 0 RG 4 w 100 100 m 300 400 l S\n".toByteArray()

    // ---- Los números y los nombres ----

    /**
     * **Sin notación científica.**
     *
     * Es el fallo silencioso de escribir números pequeños: `1.0E-5` es sintaxis
     * válida de Java y no es un número de PDF, así que el lector se pierde justo
     * ahí y el archivo deja de abrirse a partir de ese punto.
     */
    @Test
    fun `los números se escriben como los entiende un PDF`() {
        assertEquals("0", PdfEscritura.numero(0.0000001))
        assertEquals("12", PdfEscritura.numero(12.0))
        assertEquals("-3.5", PdfEscritura.numero(-3.5))
        assertTrue(!PdfEscritura.numero(0.000012).contains("E"))
        assertTrue(!PdfEscritura.numero(1e-9).contains("e"))
    }

    @Test
    fun `un nombre con caracteres raros se escapa`() {
        assertEquals("/Type", PdfEscritura.nombre("Type"))
        assertEquals("/A#20B", PdfEscritura.nombre("A B"))
        assertEquals("/a#2fb", PdfEscritura.nombre("a/b"))
    }

    @Test
    fun `un flujo lleva siempre el Length de sus datos`() {
        val flujo = PdfValor.Flujo(
            // Con un /Length mentiroso a propósito: tiene que corregirse.
            PdfValor.Dicc(mapOf("Length" to PdfValor.Numero(999.0))),
            "hola".toByteArray()
        )
        val texto = String(PdfEscritura.serializar(flujo), Charsets.ISO_8859_1)
        assertTrue(texto, texto.contains("/Length 4"))
        assertTrue(texto, !texto.contains("999"))
    }

    // ---- La actualización incremental ----

    /**
     * Lo primero y lo más importante: **el original no se toca**.
     *
     * El archivo nuevo tiene que empezar por los mismos bytes, uno a uno. Es lo
     * que garantiza que su texto siga siendo el suyo, sus fuentes las suyas y
     * cualquier desplazamiento escrito ahí dentro siga apuntando donde apuntaba.
     */
    @Test
    fun `los bytes del original siguen intactos`() {
        val original = pdfClasico()
        val anotado = PdfAnotado.fundir(leerPdf(original)!!, 0, dibujo)
        assertNotNull("no ha salido nada", anotado)
        assertTrue("el archivo no ha crecido", anotado!!.size > original.size)
        assertArrayEquals(original, anotado.copyOfRange(0, original.size))
    }

    @Test
    fun `el archivo anotado se vuelve a leer`() {
        val anotado = PdfAnotado.fundir(leerPdf(pdfClasico())!!, 0, dibujo)!!
        val releido = leerPdf(anotado)
        assertNotNull("el resultado no se puede leer", releido)
        assertEquals(listOf(3), releido!!.paginas())
    }

    /**
     * La página tiene ahora tres contenidos: el que abre la pila, el suyo de
     * siempre y el nuestro. Y **el suyo sigue en medio**, que es lo que hace que
     * el texto original siga estando.
     */
    @Test
    fun `el contenido original sigue ahí, con el nuestro detrás`() {
        val anotado = PdfAnotado.fundir(leerPdf(pdfClasico())!!, 0, dibujo)!!
        val pagina = leerPdf(anotado)!!.pagina(0)!!
        val contenidos = pagina.lista("Contents")
        assertNotNull("el contenido ya no es una lista", contenidos)
        assertEquals(3, contenidos!!.size)
        assertEquals("el contenido de siempre no está en medio", PdfValor.Ref(4, 0), contenidos[1])
    }

    /** Y lo que escribimos se puede recuperar y es lo que mandamos. */
    @Test
    fun `el dibujo llega al archivo`() {
        val releido = leerPdf(PdfAnotado.fundir(leerPdf(pdfClasico())!!, 0, dibujo)!!)!!
        val contenidos = releido.pagina(0)!!.lista("Contents")!!
        val ultimo = releido.resolver(contenidos.last()) as PdfValor.Flujo
        val texto = String(releido.descomprimir(ultimo)!!, Charsets.ISO_8859_1)
        assertTrue(texto, texto.contains("100 100 m 300 400 l S"))
        // Envuelto en su propia pila: sin esto heredaría el estado de la página.
        assertTrue("no equilibra la pila: $texto", texto.startsWith("Q\nq\n"))
        assertTrue("no equilibra la pila: $texto", texto.trimEnd().endsWith("Q"))
    }

    /** El texto de la página sigue siendo texto de verdad, no una imagen. */
    @Test
    fun `el texto original sigue siendo texto`() {
        val releido = leerPdf(PdfAnotado.fundir(leerPdf(pdfClasico())!!, 0, dibujo)!!)!!
        val original = releido.resolver(PdfValor.Ref(4, 0)) as PdfValor.Flujo
        val texto = String(releido.descomprimir(original)!!, Charsets.ISO_8859_1)
        assertTrue(texto, texto.contains("(Hola mundo) Tj"))
    }

    /**
     * Y sus recursos siguen siendo los suyos.
     *
     * Aquí estaba la trampa: la página moderna **hereda** los recursos del
     * padre. Escribirle un diccionario con solo lo nuestro le quitaría su
     * fuente, y la página saldría en blanco con nuestro dibujo encima.
     */
    @Test
    fun `los recursos heredados no se pierden`() {
        val recursos = PdfValor.Dicc(
            mapOf(
                "ExtGState" to PdfValor.Dicc(
                    mapOf("PxG0" to PdfValor.Dicc(mapOf("ca" to PdfValor.Numero(0.5))))
                )
            )
        )
        val archivo = leerPdf(pdfModerno())!!
        val anotado = PdfAnotado.fundir(archivo, 0, dibujo, recursos)!!
        val releido = leerPdf(anotado)!!
        val res = releido.diccDe(releido.pagina(0)!!.entradas["Resources"])
        assertNotNull("la página se ha quedado sin recursos", res)
        assertNotNull("ha perdido su fuente", releido.diccDe(res!!.entradas["Font"]))
        assertNotNull("no ha entrado lo nuestro", releido.diccDe(res.entradas["ExtGState"]))
    }

    /** Con índice comprimido se escribe índice comprimido, no una tabla. */
    @Test
    fun `un pdf moderno se anota con su mismo tipo de índice`() {
        val original = pdfModerno()
        val archivo = leerPdf(original)!!
        assertTrue("el de prueba no tiene índice comprimido", archivo.indiceEsFlujo)
        val anotado = PdfAnotado.fundir(archivo, 0, dibujo)!!
        assertArrayEquals(original, anotado.copyOfRange(0, original.size))
        val cola = String(
            anotado.copyOfRange(original.size, anotado.size), Charsets.ISO_8859_1
        )
        assertTrue("ha escrito una tabla clásica: $cola", !cola.contains("\ntrailer"))
        assertTrue("no ha escrito un índice comprimido", cola.contains("/Type /XRef"))
        assertNotNull("no se relee", leerPdf(anotado))
        assertEquals(listOf(3), leerPdf(anotado)!!.paginas())
    }

    /** Anotar dos veces seguidas encadena revisiones y las dos se ven. */
    @Test
    fun `se puede anotar dos veces`() {
        val primera = PdfAnotado.fundir(leerPdf(pdfClasico())!!, 0, dibujo)!!
        val segunda = PdfAnotado.fundir(
            leerPdf(primera)!!, 0, "0 0 1 RG 2 w 10 10 m 50 50 l S\n".toByteArray()
        )!!
        val releido = leerPdf(segunda)!!
        val contenidos = releido.pagina(0)!!.lista("Contents")!!
        // La segunda vuelve a envolver: dos aperturas, el original y dos dibujos.
        assertEquals(5, contenidos.size)
        val ultimo = releido.resolver(contenidos.last()) as PdfValor.Flujo
        assertTrue(String(releido.descomprimir(ultimo)!!).contains("10 10 m"))
    }

    // ---- La capa ----

    /**
     * **La página no se toca.**
     *
     * Es la ventaja gorda de la capa frente a fundir el dibujo en el contenido:
     * de la hoja solo cambia que tiene una anotación más. Su contenido, sus
     * recursos y todo lo demás quedan exactamente como estaban, así que no hay
     * nada que se pueda romper ahí.
     */
    @Test
    fun `en capa, el contenido de la página ni se roza`() {
        val archivo = leerPdf(pdfClasico())!!
        val antes = archivo.pagina(0)!!
        val releido = leerPdf(PdfAnotado.anotar(archivo, 0, dibujo)!!)!!
        val despues = releido.pagina(0)!!

        assertEquals("le han tocado el contenido", antes.entradas["Contents"], despues.entradas["Contents"])
        assertEquals("le han tocado los recursos", antes.entradas["Resources"], despues.entradas["Resources"])
        // Lo único nuevo: la lista de anotaciones.
        assertEquals(
            setOf("Annots"),
            despues.entradas.keys - antes.entradas.keys
        )
    }

    @Test
    fun `la capa cuelga de una anotación que se imprime`() {
        val releido = leerPdf(PdfAnotado.anotar(leerPdf(pdfClasico())!!, 0, dibujo)!!)!!
        val anots = releido.pagina(0)!!.lista("Annots")
        assertNotNull("no hay anotaciones", anots)
        assertEquals(1, anots!!.size)
        val marca = releido.diccDe(anots[0])!!
        assertEquals("Annot", marca.nombre("Type"))
        assertEquals("Stamp", marca.nombre("Subtype"))
        // Sin el bit de imprimir se ve en pantalla y no sale en el papel, que es
        // la peor forma de enterarse.
        assertEquals(4, marca.entero("F"))
        assertNotNull("no lleva capa", marca.ref("OC"))
        assertNotNull("no lleva apariencia", releido.diccDe(marca.entradas["AP"]))
    }

    /** Y el dibujo está dentro de esa apariencia, marcado como de la capa. */
    @Test
    fun `el dibujo va dentro de la capa`() {
        val releido = leerPdf(PdfAnotado.anotar(leerPdf(pdfClasico())!!, 0, dibujo)!!)!!
        val marca = releido.diccDe(releido.pagina(0)!!.lista("Annots")!![0])!!
        val ap = releido.diccDe(marca.entradas["AP"])!!
        val forma = releido.resolver(ap.entradas["N"]) as PdfValor.Flujo
        assertEquals("Form", forma.dicc.nombre("Subtype"))
        assertEquals(
            "la forma y la anotación no son de la misma capa",
            marca.ref("OC"), forma.dicc.ref("OC")
        )
        val texto = String(releido.descomprimir(forma)!!, Charsets.ISO_8859_1)
        assertTrue(texto, texto.contains("100 100 m 300 400 l S"))
    }

    /**
     * El catálogo tiene que conocerla, o el panel de capas no la enseña y hay
     * lectores que ni siquiera dibujan lo que la lleva.
     */
    @Test
    fun `el catálogo anuncia la capa, y encendida`() {
        val releido = leerPdf(PdfAnotado.anotar(leerPdf(pdfClasico())!!, 0, dibujo, nombre = "Revisión")!!)!!
        val catalogo = releido.diccDe(releido.trailer.entradas["Root"])!!
        val props = releido.diccDe(catalogo.entradas["OCProperties"])
        assertNotNull("el catálogo no conoce la capa", props)
        val todas = (releido.resolver(props!!.entradas["OCGs"]) as PdfValor.Lista).valores
        assertEquals(1, todas.size)
        val d = releido.diccDe(props.entradas["D"])!!
        val encendidas = (releido.resolver(d.entradas["ON"]) as PdfValor.Lista).valores
        assertEquals("nace apagada", todas, encendidas)
        // Y con su nombre, que es lo que se lee en el panel.
        val capa = releido.diccDe(todas[0])!!
        val nombre = (capa.entradas["Name"] as PdfValor.Cadena).bytes
        assertTrue("el nombre no va en UTF-16", nombre.size > 2 && nombre[0] == 0xFE.toByte())
        assertEquals("Revisión", String(nombre.copyOfRange(2, nombre.size), Charsets.UTF_16BE))
    }

    /** Anotar dos veces da dos capas, no una pisando a la otra. */
    @Test
    fun `dos tandas son dos capas`() {
        val una = PdfAnotado.anotar(leerPdf(pdfClasico())!!, 0, dibujo, nombre = "Lunes")!!
        val dos = PdfAnotado.anotar(
            leerPdf(una)!!, 0, "0 0 1 RG 10 10 m 50 50 l S\n".toByteArray(), nombre = "Martes"
        )!!
        val releido = leerPdf(dos)!!
        assertEquals(2, releido.pagina(0)!!.lista("Annots")!!.size)
        val props = releido.diccDe(
            releido.diccDe(releido.trailer.entradas["Root"])!!.entradas["OCProperties"]
        )!!
        assertEquals(2, (releido.resolver(props.entradas["OCGs"]) as PdfValor.Lista).valores.size)
    }

    /** Y si el PDF ya traía sus capas, no se le pierden. */
    @Test
    fun `las capas que ya tenía el archivo se conservan`() {
        val conCapas = pdfConCapaPropia()
        val releido = leerPdf(PdfAnotado.anotar(leerPdf(conCapas)!!, 0, dibujo)!!)!!
        val props = releido.diccDe(
            releido.diccDe(releido.trailer.entradas["Root"])!!.entradas["OCProperties"]
        )!!
        val todas = (releido.resolver(props.entradas["OCGs"]) as PdfValor.Lista).valores
        assertEquals("le hemos borrado su capa", 2, todas.size)
        assertTrue("falta la suya", todas.contains(PdfValor.Ref(6, 0)))
    }

    /** Un PDF que ya viene con una capa suya, como los que salen de un CAD. */
    private fun pdfConCapaPropia(): ByteArray {
        val objetos = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R /OCProperties " +
                "<< /OCGs [6 0 R] /D << /Order [6 0 R] /ON [6 0 R] >> >> >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                "/Contents 4 0 R /Resources << >> >>\nendobj\n",
            "4 0 obj\n<< /Length 10 >>\nstream\n0 0 m 1 1 l\nendstream\nendobj\n",
            "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n",
            "6 0 obj\n<< /Type /OCG /Name (Cotas) >>\nendobj\n"
        )
        val salida = ByteArrayOutputStream()
        salida.write("%PDF-1.5\n".toByteArray())
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

    /** En capa el original también sigue intacto, que es lo primero de todo. */
    @Test
    fun `en capa los bytes del original siguen intactos`() {
        val original = pdfModerno()
        val anotado = PdfAnotado.anotar(leerPdf(original)!!, 0, dibujo)!!
        assertArrayEquals(original, anotado.copyOfRange(0, original.size))
        assertNotNull(leerPdf(anotado))
    }

    // ---- Las cuatro esquinas ----

    /**
     * Sin giro: la esquina de arriba a la izquierda de la imagen cae en la de
     * arriba a la izquierda del papel, y la de abajo a la derecha en la suya.
     */
    @Test
    fun `la imagen encaja en el papel`() {
        val archivo = leerPdf(pdfClasico())!!
        val m = PdfAnotado.matrizDePagina(archivo, 0, 595.0, 842.0)!!
        assertEquals(0.0, aplicar(m, 0.0, 0.0).first, 0.01)
        assertEquals(842.0, aplicar(m, 0.0, 0.0).second, 0.01)
        assertEquals(595.0, aplicar(m, 595.0, 842.0).first, 0.01)
        assertEquals(0.0, aplicar(m, 595.0, 842.0).second, 0.01)
    }

    /**
     * Con la hoja girada, lo que se anotó sobre la imagen **girada** tiene que
     * volver a su sitio en el papel **sin girar**. Es lo que hace que dibujar
     * sobre un plano apaisado funcione.
     */
    @Test
    fun `una hoja girada devuelve el dibujo a su sitio`() {
        for (giro in listOf(90, 180, 270)) {
            val archivo = leerPdf(pdfModerno(giro))!!
            // Girada 90 o 270, la imagen sale apaisada: 842 × 595.
            val ancho = if (giro == 180) 595.0 else 842.0
            val alto = if (giro == 180) 842.0 else 595.0
            val m = PdfAnotado.matrizDePagina(archivo, 0, ancho, alto)!!

            // Las cuatro esquinas de la imagen tienen que caer en las cuatro del
            // papel: da igual cuál con cuál, pero ninguna puede salirse.
            for ((ix, iy) in listOf(0.0 to 0.0, ancho to 0.0, 0.0 to alto, ancho to alto)) {
                val (px, py) = aplicar(m, ix, iy)
                assertTrue("giro $giro: x fuera del papel ($px)", px >= -0.01 && px <= 595.01)
                assertTrue("giro $giro: y fuera del papel ($py)", py >= -0.01 && py <= 842.01)
            }
            // Y las esquinas opuestas siguen siendo opuestas: la matriz no
            // aplasta la hoja contra un lado.
            val a = aplicar(m, 0.0, 0.0)
            val b = aplicar(m, ancho, alto)
            assertEquals("giro $giro", 595.0, kotlin.math.abs(b.first - a.first), 0.01)
            assertEquals("giro $giro", 842.0, kotlin.math.abs(b.second - a.second), 0.01)
        }
    }

    private fun aplicar(m: DoubleArray, x: Double, y: Double): Pair<Double, Double> =
        (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])

    // ---- Lo que no se hace ----

    /**
     * Un PDF protegido no se toca.
     *
     * Habría que cifrar lo añadido con su misma clave, y equivocarse ahí no da
     * un archivo raro: da un archivo que no abre. Mejor decir que no.
     */
    @Test
    fun `un pdf cifrado se deja en paz`() {
        val cifrado = PdfArchivo(
            pdfClasico(),
            mapOf(1 to Sitio.Suelto(9)),
            PdfValor.Dicc(
                mapOf(
                    "Root" to PdfValor.Ref(1, 0),
                    "Encrypt" to PdfValor.Ref(9, 0)
                )
            )
        )
        assertNull(PdfAnotado.anotar(cifrado, 0, dibujo))
        assertNull(PdfEscritura.incremental(cifrado, listOf(ObjetoPdf(7, PdfValor.Nulo))))
    }

    @Test
    fun `una página que no existe no se anota`() {
        assertNull(PdfAnotado.anotar(leerPdf(pdfClasico())!!, 7, dibujo))
    }

    @Test
    fun `sin nada que dibujar no se escribe nada`() {
        assertNull(PdfAnotado.anotar(leerPdf(pdfClasico())!!, 0, ByteArray(0)))
    }
}
