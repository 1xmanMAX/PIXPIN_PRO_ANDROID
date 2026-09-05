package com.forge.pixpin.motor

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Leer un PDF como líneas**, sin dispositivo.
 *
 * Los archivos se escriben aquí a mano, como en [PdfLecturaTest] y por el mismo motivo: así
 * cada prueba enseña **exactamente** el caso que comprueba —una matriz que encoge, una capa
 * apagada, una página girada— en vez de esconderlo dentro de un binario.
 *
 * Lo que se mira siempre es lo mismo: que la geometría caiga **donde se ve**, o sea con el
 * origen arriba a la izquierda, la y hacia abajo y el giro de la página ya aplicado, que es
 * como la pone el papel del editor. Ver [PlanoDePdf].
 */
class PlanoDePdfTest {

    // ---------------------------------------------------------------------
    // Fabricar el archivo
    // ---------------------------------------------------------------------

    /** Arma un PDF con estos objetos —el primero es el 1— y su tabla clásica. */
    private fun pdfCon(objetos: List<String>): ByteArray {
        val salida = ByteArrayOutputStream()
        salida.write("%PDF-1.5\n".toByteArray(Charsets.ISO_8859_1))
        val donde = mutableListOf<Int>()
        for (o in objetos) {
            donde += salida.size()
            salida.write(o.toByteArray(Charsets.ISO_8859_1))
        }
        val inicio = salida.size()
        val tabla = StringBuilder("xref\n0 ${objetos.size + 1}\n0000000000 65535 f \n")
        for (d in donde) tabla.append("%010d 00000 n \n".format(d))
        tabla.append("trailer\n<< /Size ${objetos.size + 1} /Root 1 0 R >>\n")
        tabla.append("startxref\n$inicio\n%%EOF\n")
        salida.write(tabla.toString().toByteArray(Charsets.ISO_8859_1))
        return salida.toByteArray()
    }

    private fun flujo(numero: Int, datos: String, dicc: String = ""): String =
        "$numero 0 obj\n<< /Length ${datos.toByteArray(Charsets.ISO_8859_1).size}$dicc >>\nstream\n" +
            datos + "\nendstream\nendobj\n"

    /**
     * Una página de [ancho]×[alto] con este contenido. [extra] entra en el diccionario de la
     * página —para `/Rotate`—, [recursos] en sus recursos y [masObjetos] detrás, numerados
     * desde el 5.
     */
    private fun paginaCon(
        contenido: String,
        ancho: Int = 100,
        alto: Int = 200,
        caja: String = "0 0 $ancho $alto",
        extra: String = "",
        recursos: String = "",
        catalogo: String = "",
        filtro: String = "",
        masObjetos: List<String> = emptyList()
    ): ByteArray = pdfCon(
        listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R $catalogo>>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [$caja] " +
                "/Contents 4 0 R /Resources << $recursos >> $extra>>\nendobj\n",
            flujo(4, contenido, filtro)
        ) + masObjetos
    )

    private fun leer(bytes: ByteArray) = PlanoDePdf.de(bytes, 0)

    /** Los puntos de una brocha, en puntos del papel, para poder compararlos a ojo. */
    private fun puntos(b: PlanoDePdf.Brocha): List<Pair<Double, Double>> =
        b.xs.indices.map {
            b.xs[it].toDouble() / PlanoDePdf.FINEZA to b.ys[it].toDouble() / PlanoDePdf.FINEZA
        }

    // ---------------------------------------------------------------------
    // La geometría
    // ---------------------------------------------------------------------

    @Test
    fun `una raya sale con su color, su grosor y del derecho`() {
        val p = leer(paginaCon("1 0 0 RG 2 w 10 20 m 30 40 l S"))!!
        assertEquals(100.0, p.ancho, 0.001)
        assertEquals(200.0, p.alto, 0.001)
        assertEquals(1, p.brochas.size)
        val b = p.brochas[0]
        assertEquals(0xff0000, b.color)
        assertEquals(2.0, b.grosor, 0.001)
        assertTrue("una raya no se rellena", !b.relleno)
        assertEquals(listOf(PlanoDePdf.MOVER, PlanoDePdf.LINEA), b.ops.toList())
        // La y del PDF sube y la de la pantalla baja: 20 desde abajo son 180 desde arriba.
        assertEquals(listOf(10.0 to 180.0, 30.0 to 160.0), puntos(b))
    }

    @Test
    fun `un rectangulo relleno son cuatro esquinas y un cierre`() {
        val p = leer(paginaCon("0.5 g 10 10 50 40 re f"))!!
        val b = p.brochas.single()
        assertTrue("tiene que ser relleno", b.relleno)
        assertEquals(0x808080, b.color)
        assertEquals(
            listOf(PlanoDePdf.MOVER, PlanoDePdf.LINEA, PlanoDePdf.LINEA, PlanoDePdf.LINEA, PlanoDePdf.CERRAR),
            b.ops.toList()
        )
        assertEquals(10.0 to 190.0, puntos(b).first())
        assertEquals(60.0 to 150.0, puntos(b)[2])
    }

    @Test
    fun `pintar y trazar el mismo camino da dos brochas`() {
        val p = leer(paginaCon("1 0 0 rg 0 0 1 RG 10 10 20 20 re B"))!!
        assertEquals(2, p.brochas.size)
        assertEquals(0xff0000, p.brochas.first { it.relleno }.color)
        assertEquals(0x0000ff, p.brochas.first { !it.relleno }.color)
    }

    /**
     * La matriz manda en todo, **incluido el grosor**. Un plano de AutoCAD empieza con un `cm`
     * que lo achica doce veces: sin pasar el grosor por ahí, cada raya saldría doce veces más
     * gorda de lo que es.
     */
    @Test
    fun `la matriz encoge el dibujo y el grosor con el`() {
        val p = leer(paginaCon("0.5 0 0 0.5 0 0 cm 4 w 0 0 m 100 100 l S"))!!
        val b = p.brochas.single()
        assertEquals(2.0, b.grosor, 0.001)
        assertEquals(listOf(0.0 to 200.0, 50.0 to 150.0), puntos(b))
    }

    @Test
    fun `q y Q devuelven el estado como estaba`() {
        val p = leer(paginaCon("q 3 w 1 0 0 RG 0 0 m 1 1 l S Q 0 0 m 2 2 l S"))!!
        assertEquals(2, p.brochas.size)
        val fuera = p.brochas.first { it.color == 0x000000 }
        assertEquals("el grosor de dentro se ha escapado", 1.0, fuera.grosor, 0.001)
    }

    @Test
    fun `las curvas viajan como curvas y no como trocitos`() {
        val p = leer(paginaCon("0 0 m 10 0 20 10 20 20 c S"))!!
        val b = p.brochas.single()
        assertEquals(listOf(PlanoDePdf.MOVER, PlanoDePdf.CURVA), b.ops.toList())
        assertEquals("una curva gasta tres puntos", 4, b.xs.size)
    }

    @Test
    fun `el cmyk se convierte a color de pantalla`() {
        val p = leer(paginaCon("0 1 1 0 K 0 0 m 1 1 l S"))!!
        assertEquals(0xff0000, p.brochas.single().color)
    }

    // ---------------------------------------------------------------------
    // Las capas
    // ---------------------------------------------------------------------

    private fun conCapas(contenido: String, off: String = ""): ByteArray = paginaCon(
        contenido,
        recursos = "/Properties << /oc1 5 0 R /oc2 6 0 R >>",
        catalogo = "/OCProperties << /OCGs [5 0 R 6 0 R] /D << /Order [5 0 R 6 0 R] /OFF [$off] >> >> ",
        masObjetos = listOf(
            "5 0 obj\n<< /Type /OCG /Name (Muros) >>\nendobj\n",
            "6 0 obj\n<< /Type /OCG /Name (Cotas) >>\nendobj\n"
        )
    )

    @Test
    fun `cada capa del pdf sale con su nombre y lo suyo dentro`() {
        val p = leer(
            conCapas(
                "/OC /oc1 BDC 0 0 m 1 1 l S EMC " +
                    "/OC /oc2 BDC 1 0 0 RG 2 0 m 3 3 l S EMC " +
                    "5 5 m 6 6 l S"
            )
        )!!
        assertEquals(listOf("Muros", "Cotas"), p.capas.map { it.nombre })
        assertTrue("sin /OFF todas vienen encendidas", p.capas.all { it.encendida })
        assertEquals(0, p.brochas.first { it.color == 0x000000 && it.capa == 0 }.capa)
        assertEquals(1, p.brochas.first { it.color == 0xff0000 }.capa)
        // Lo que no está dentro de ninguna marca no tiene capa, y eso es un dato: el visor lo
        // enseña como «Sin capa» para poder apagarlo igual.
        assertTrue("falta lo que va fuera de las capas", p.brochas.any { it.capa == -1 })
    }

    @Test
    fun `una capa apagada en el pdf llega apagada`() {
        val p = leer(conCapas("/OC /oc2 BDC 0 0 m 1 1 l S EMC", off = "6 0 R"))!!
        assertTrue(p.capas[0].encendida)
        assertTrue("la capa venía apagada del PDF", !p.capas[1].encendida)
    }

    @Test
    fun `las marcas metidas unas en otras devuelven la capa de fuera`() {
        val p = leer(
            conCapas(
                "/OC /oc1 BDC /Span <</Lang (es)>> BDC 0 0 m 1 1 l S EMC 2 2 m 3 3 l S EMC"
            )
        )!!
        assertTrue("todo esto es de la primera capa", p.brochas.all { it.capa == 0 })
    }

    // ---------------------------------------------------------------------
    // La página
    // ---------------------------------------------------------------------

    /**
     * `/Rotate` no es un adorno: el plano del usuario viene con 270 —una hoja vertical que se
     * ve apaisada— y Android lo aplica al rasterizar. Si aquí no se girara igual, las líneas
     * caerían atravesadas sobre las anotaciones hechas encima.
     */
    @Test
    fun `una pagina girada un cuarto de vuelta sale apaisada`() {
        val p = leer(paginaCon("0 0 m 10 20 l S", extra = "/Rotate 90 "))!!
        assertEquals("el papel se pone de lado", 200.0, p.ancho, 0.001)
        assertEquals(100.0, p.alto, 0.001)
        // Con 90 grados: lo que estaba en la esquina de abajo a la izquierda pasa a arriba a
        // la izquierda, y la x del PDF baja por la pantalla.
        assertEquals(listOf(0.0 to 0.0, 20.0 to 10.0), puntos(p.brochas.single()))
    }

    @Test
    fun `con tres cuartos de vuelta el papel también se tumba`() {
        val p = leer(paginaCon("0 0 m 10 20 l S", extra = "/Rotate 270 "))!!
        assertEquals(200.0, p.ancho, 0.001)
        assertEquals(100.0, p.alto, 0.001)
        assertEquals(listOf(200.0 to 100.0, 180.0 to 90.0), puntos(p.brochas.single()))
    }

    @Test
    fun `el mediabox que no empieza en cero se lleva al origen`() {
        val p = leer(paginaCon("50 50 m 60 60 l S", caja = "50 50 150 250"))!!
        assertEquals(100.0, p.ancho, 0.001)
        assertEquals(listOf(0.0 to 200.0, 10.0 to 190.0), puntos(p.brochas.single()))
    }

    // ---------------------------------------------------------------------
    // Lo que no se manda
    // ---------------------------------------------------------------------

    @Test
    fun `lo que el recorte deja fuera no viaja`() {
        // El recorte es la mitad de abajo; la raya de arriba está fuera del todo.
        val p = leer(paginaCon("q 0 0 100 100 re W n 10 10 m 20 20 l S 10 150 m 20 160 l S Q"))!!
        val b = p.brochas.single()
        assertEquals("solo tenía que quedar la raya de dentro", 2, b.xs.size)
        assertEquals(10.0 to 190.0, puntos(b).first())
    }

    @Test
    fun `una imagen se cuenta y no se dibuja`() {
        val p = leer(
            paginaCon(
                "q 100 0 0 100 0 0 cm /Im1 Do Q 0 0 m 1 1 l S",
                recursos = "/XObject << /Im1 5 0 R >>",
                masObjetos = listOf(
                    flujo(5, "xxxx", " /Type /XObject /Subtype /Image /Width 2 /Height 2")
                )
            )
        )!!
        assertEquals(1, p.sinEntender)
        assertEquals(1, p.brochas.size)
    }

    /**
     * Un JPEG del PDF viaja **tal cual**: son los bytes que ya estaban dentro del archivo, sin
     * volver a comprimir nada. Es lo que permite que un plano con el logotipo del estudio siga
     * yendo como líneas en vez de como fotografía de toda la página.
     */
    @Test
    fun `una foto en jpeg se pasa tal cual con su sitio`() {
        val jpeg = "\u00ff\u00d8\u00ff\u00e0FOTO"
        val p = leer(
            paginaCon(
                "q 40 0 0 20 10 30 cm /Im1 Do Q 0 0 m 1 1 l S",
                recursos = "/XObject << /Im1 5 0 R >>",
                masObjetos = listOf(
                    flujo(
                        5, jpeg,
                        " /Type /XObject /Subtype /Image /Width 4 /Height 2 " +
                            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode"
                    )
                )
            )
        )!!
        assertEquals("no se ha quedado nada fuera", 0, p.sinEntender)
        assertEquals(1, p.fotos.size)
        val f = p.fotos[0]
        assertEquals("image/jpeg", f.tipo)
        assertEquals(jpeg, String(f.datos, Charsets.ISO_8859_1))
        // El cuadro va de (10,30) a (50,50) en el PDF: arriba a la izquierda, en pantalla, es
        // (10, 200-50).
        assertEquals(10.0, f.x, 0.001)
        assertEquals(150.0, f.y, 0.001)
        assertEquals("ancho", 40.0, f.a, 0.001)
        assertEquals("alto, hacia abajo", 20.0, f.d, 0.001)
    }

    @Test
    fun `una imagen con recorte de transparencia no se pasa`() {
        val p = leer(
            paginaCon(
                "q 10 0 0 10 0 0 cm /Im1 Do Q",
                recursos = "/XObject << /Im1 5 0 R >>",
                masObjetos = listOf(
                    flujo(
                        5, "xx",
                        " /Type /XObject /Subtype /Image /Width 1 /Height 1 /SMask 6 0 R " +
                            "/Filter /DCTDecode"
                    ),
                    "6 0 obj\n<< >>\nendobj\n"
                )
            )
        )!!
        assertEquals("hay que contarla para poder volver a la foto", 1, p.sinEntender)
        assertTrue(p.fotos.isEmpty())
    }

    @Test
    fun `una pagina que es solo una imagen no vale la pena`() {
        val p = leer(
            paginaCon(
                "q 100 0 0 100 0 0 cm /Im1 Do Q",
                recursos = "/XObject << /Im1 5 0 R >>",
                masObjetos = listOf(
                    flujo(5, "xxxx", " /Type /XObject /Subtype /Image /Width 2 /Height 2")
                )
            )
        )!!
        assertTrue("un escaneo no es un plano vectorial", !p.valeLaPena)
    }

    @Test
    fun `un formulario se ejecuta con su matriz`() {
        val p = leer(
            paginaCon(
                "q 1 0 0 1 10 10 cm /Fm1 Do Q",
                recursos = "/XObject << /Fm1 5 0 R >>",
                masObjetos = listOf(
                    flujo(
                        5, "0 0 m 10 10 l S",
                        " /Type /XObject /Subtype /Form /BBox [0 0 20 20] /Matrix [2 0 0 2 0 0]"
                    )
                )
            )
        )!!
        // La matriz del formulario dobla, y la de fuera desplaza diez.
        assertEquals(listOf(10.0 to 190.0, 30.0 to 170.0), puntos(p.brochas.single()))
    }

    // ---------------------------------------------------------------------
    // El texto
    // ---------------------------------------------------------------------

    /** Una fuente de dos bytes con su tabla de letras, como las de un plano de AutoCAD. */
    private fun conFuente(contenido: String): ByteArray = paginaCon(
        contenido,
        recursos = "/Font << /F1 5 0 R >>",
        masObjetos = listOf(
            "5 0 obj\n<< /Type /Font /Subtype /Type0 /BaseFont /ArialMT /Encoding /Identity-H " +
                "/DescendantFonts [6 0 R] /ToUnicode 7 0 R >>\nendobj\n",
            "6 0 obj\n<< /Type /Font /Subtype /CIDFontType2 /BaseFont /ArialMT /DW 1000 " +
                "/W [3 [500 500]] >>\nendobj\n",
            flujo(
                7,
                "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n" +
                    "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n" +
                    "2 beginbfchar\n<0003> <0041>\n<0004> <00d1>\nendbfchar\nendcmap\nend end"
            )
        )
    )

    @Test
    fun `un rotulo sale con lo que dice, donde y cuanto ocupa`() {
        val p = leer(conFuente("BT /F1 10 Tf 20 100 Td <00030004> Tj ET"))!!
        assertEquals(1, p.textos.size)
        val t = p.textos[0]
        assertEquals("AÑ", t.texto)
        assertEquals(20.0, t.x, 0.001)
        assertEquals("la línea base, contada desde arriba", 100.0, t.y, 0.001)
        // Dos letras de media eme cada una: una eme de ancho.
        assertEquals(1.0, t.ancho, 0.001)
        // Sin giro: se escribe hacia la derecha, y las letras caen hacia abajo.
        assertEquals(10.0, t.a, 0.001)
        assertEquals(0.0, t.b, 0.001)
        assertEquals(10.0, t.d, 0.001)
        assertEquals("sans-serif", t.familia)
    }

    @Test
    fun `la matriz del texto lo gira`() {
        val p = leer(conFuente("BT /F1 10 Tf 0 1 -1 0 30 40 Tm <0003> Tj ET"))!!
        val t = p.textos.single()
        // Escrito hacia arriba de la hoja, o sea hacia arriba de la pantalla.
        assertEquals(0.0, t.a, 0.001)
        assertEquals(-10.0, t.b, 0.001)
        assertEquals(30.0, t.x, 0.001)
        assertEquals(160.0, t.y, 0.001)
    }

    @Test
    fun `el texto invisible del escaneo no se manda`() {
        val p = leer(conFuente("BT /F1 10 Tf 3 Tr 20 100 Td <0003> Tj ET"))!!
        assertTrue("el modo 3 es el que esconde el OCR debajo de su foto", p.textos.isEmpty())
    }

    @Test
    fun `los saltos de renglon van bajando el texto`() {
        val p = leer(conFuente("BT /F1 10 Tf 12 TL 20 100 Td <0003> Tj T* <0003> Tj ET"))!!
        assertEquals(2, p.textos.size)
        assertEquals(100.0, p.textos[0].y, 0.001)
        assertEquals("el segundo renglón cae doce puntos más abajo", 112.0, p.textos[1].y, 0.001)
    }

    @Test
    fun `un empujon dentro de TJ separa lo que viene detras`() {
        val p = leer(conFuente("BT /F1 10 Tf 20 100 Td [<0003> -1000 <0003>] TJ ET"))!!
        assertEquals(2, p.textos.size)
        // Media eme de la primera letra más una eme de empujón.
        assertEquals(20.0 + 5.0 + 10.0, p.textos[1].x, 0.001)
    }

    // ---------------------------------------------------------------------
    // Cuando no se puede
    // ---------------------------------------------------------------------

    @Test
    fun `un pdf cifrado no se toca`() {
        val bytes = String(paginaCon("0 0 m 1 1 l S"), Charsets.ISO_8859_1)
            .replace("/Size 5 /Root 1 0 R", "/Size 5 /Root 1 0 R /Encrypt 9 0 R")
            .toByteArray(Charsets.ISO_8859_1)
        assertNull(leer(bytes))
    }

    /**
     * Hay generadores —ReportLab, y los que evitan escribir bytes crudos— que encadenan dos
     * filtros: primero comprimen y luego lo pasan a letras imprimibles. Sin deshacer la cadena
     * entera, su página no se puede ni mirar.
     */
    @Test
    fun `un contenido en hexadecimal se lee igual`() {
        val orden = "1 0 0 RG 10 20 m 30 40 l S"
        val hex = orden.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it) } + ">"
        val p = leer(paginaCon(hex, filtro = " /Filter /ASCIIHexDecode"))!!
        assertEquals(listOf(10.0 to 180.0, 30.0 to 160.0), puntos(p.brochas.single()))
    }

    @Test
    fun `un contenido en base 85 se lee igual`() {
        val orden = "10 20 m 30 40 l S"
        val p = leer(paginaCon(aBase85(orden), filtro = " /Filter /ASCII85Decode"))!!
        assertEquals(listOf(10.0 to 180.0, 30.0 to 160.0), puntos(p.brochas.single()))
    }

    /** Cuatro bytes se escriben con cinco letras; lo que sobra al final se rellena y se corta. */
    private fun aBase85(texto: String): String {
        val datos = texto.toByteArray(Charsets.ISO_8859_1)
        val sb = StringBuilder()
        var i = 0
        while (i < datos.size) {
            val cuantos = minOf(4, datos.size - i)
            var v = 0L
            for (k in 0 until 4) {
                v = v * 256 + (if (i + k < datos.size) (datos[i + k].toInt() and 0xFF) else 0)
            }
            val letras = CharArray(5)
            var resto = v
            for (k in 4 downTo 0) {
                letras[k] = ('!' + (resto % 85).toInt())
                resto /= 85
            }
            sb.append(letras, 0, cuantos + 1)
            i += 4
        }
        return sb.append("~>").toString()
    }

    @Test
    fun `un archivo que no es un pdf devuelve nada`() {
        assertNull(leer("esto no es un pdf".toByteArray()))
    }

    @Test
    fun `lo que no se entiende se salta sin llevarse el resto`() {
        val p = leer(paginaCon("/Nada gs 4 sh 0 0 m 1 1 l S 99 99 raro"))
        assertNotNull(p)
        assertEquals(1, p!!.brochas.size)
    }
}
