package com.forge.pixpin.motor

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * El dibujo escrito dentro de un PDF ajeno, de principio a fin.
 *
 * Es la cadena entera: leer el archivo, calcular la matriz de las cuatro
 * esquinas, traducir el dibujo a órdenes y colgarlo como capa. Lo que se
 * comprueba es que **el original siga intacto** y que lo escrito sea de verdad
 * geometría —`m`, `l`, `c`— y no una imagen disfrazada de vectores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfLienzoTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** Un PDF de una página con texto de verdad, para poder comprobar que sigue. */
    private fun pdfDeUnaPagina(): ByteArray {
        val objetos = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n",
            "4 0 obj\n<< /Length 42 >>\nstream\n" +
                "BT /F1 24 Tf 72 700 Td (Memoria tecnica) Tj ET\n\nendstream\nendobj\n",
            "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
        )
        val salida = ByteArrayOutputStream()
        salida.write("%PDF-1.4\n".toByteArray())
        val donde = mutableListOf<Int>()
        for (o in objetos) {
            donde += salida.size()
            salida.write(o.toByteArray())
        }
        val inicio = salida.size()
        val tabla = StringBuilder("xref\n0 ${objetos.size + 1}\n0000000000 65535 f \n")
        for (d in donde) tabla.append("%010d 00000 n \n".format(d))
        tabla.append("trailer\n<< /Size ${objetos.size + 1} /Root 1 0 R >>\n")
        tabla.append("startxref\n$inicio\n%%EOF\n")
        salida.write(tabla.toString().toByteArray())
        return salida.toByteArray()
    }

    /** Un croquis cualquiera, del tamaño de la imagen de la página. */
    private fun croquis() = Scene(
        elements = listOf(
            Element(
                id = "r", type = ElementType.RECTANGLE,
                x = 100.0, y = 100.0, width = 300.0, height = 200.0, seed = 4321,
                strokeColor = "#e03131", strokeWidth = 3.0
            ),
            Element(
                id = "l", type = ElementType.LINE,
                x = 100.0, y = 400.0, width = 300.0, height = 100.0, seed = 99,
                points = listOf(Pt(0.0, 0.0), Pt(300.0, 100.0))
            )
        )
    )

    private fun anotado(): ByteArray? = DrawPdf.anotarPagina(
        context, pdfDeUnaPagina(), 0, croquis(),
        anchoImagen = 595.0, altoImagen = 842.0, nombreDeLaCapa = "Revisión 1"
    )

    // ---- La cadena entera ----

    @Test
    fun `un croquis se pega sobre la página`() {
        val salida = anotado()
        assertNotNull("no ha salido nada", salida)
        assertNotNull("el resultado no se puede leer", leerPdf(salida!!))
    }

    /**
     * **Lo primero de todo: el original no se toca.**
     *
     * Es lo que garantiza que su texto siga siendo suyo. Si esto falla, da igual
     * lo bien que se vea el dibujo.
     */
    @Test
    fun `el pdf original sigue intacto`() {
        val original = pdfDeUnaPagina()
        val salida = anotado()!!
        assertTrue("no ha crecido", salida.size > original.size)
        assertEquals(
            "le han tocado los bytes del original",
            original.toList(), salida.copyOfRange(0, original.size).toList()
        )
    }

    /** Y su texto se sigue pudiendo buscar: no lo ha tapado una imagen. */
    @Test
    fun `el texto de la página sigue vivo`() {
        val releido = leerPdf(anotado()!!)!!
        val contenido = releido.resolver(PdfValor.Ref(4, 0)) as PdfValor.Flujo
        val texto = String(releido.descomprimir(contenido)!!, Charsets.ISO_8859_1)
        assertTrue(texto, texto.contains("(Memoria tecnica) Tj"))
    }

    /** La página solo gana una anotación: su contenido no se ha tocado. */
    @Test
    fun `la página solo gana su capa`() {
        val releido = leerPdf(anotado()!!)!!
        val pagina = releido.pagina(0)!!
        assertEquals(PdfValor.Ref(4, 0), pagina.ref("Contents"))
        assertEquals(1, pagina.lista("Annots")!!.size)
    }

    /**
     * **Lo escrito es geometría, no una foto.**
     *
     * Es la diferencia entre anotar un PDF y pegarle un pantallazo encima: el
     * dibujo tiene que salir como trazos, así que dentro de la capa tiene que
     * haber órdenes de camino.
     */
    @Test
    fun `dentro de la capa hay trazos y no una imagen`() {
        val releido = leerPdf(anotado()!!)!!
        val marca = releido.diccDe(releido.pagina(0)!!.lista("Annots")!![0])!!
        val ap = releido.diccDe(marca.entradas["AP"])!!
        val forma = releido.resolver(ap.entradas["N"]) as PdfValor.Flujo
        val ordenes = String(releido.descomprimir(forma)!!, Charsets.ISO_8859_1)

        assertTrue("no hay caminos: $ordenes", ordenes.contains(" m\n"))
        assertTrue("no hay curvas", ordenes.contains(" c\n"))
        assertTrue("no se traza nada", ordenes.contains("S\n"))
        assertTrue("no ha puesto la matriz de la página", ordenes.contains(" cm\n"))
        assertTrue("ha metido una imagen", !ordenes.contains(" Do\n"))
    }

    /** Y el color del trazo llega: un rojo escrito en 0-1. */
    @Test
    fun `el color del dibujo llega al PDF`() {
        val releido = leerPdf(anotado()!!)!!
        val marca = releido.diccDe(releido.pagina(0)!!.lista("Annots")!![0])!!
        val forma = releido.resolver(
            releido.diccDe(marca.entradas["AP"])!!.entradas["N"]
        ) as PdfValor.Flujo
        val ordenes = String(releido.descomprimir(forma)!!, Charsets.ISO_8859_1)
        // #e03131 → 0.878 0.192 0.192
        assertTrue("no lleva el rojo del trazo", ordenes.contains("0.878"))
    }

    // ---- Lo que no se hace ----

    @Test
    fun `un dibujo vacío no toca el PDF`() {
        assertNull(
            DrawPdf.anotarPagina(
                context, pdfDeUnaPagina(), 0, Scene(), 595.0, 842.0
            )
        )
    }

    @Test
    fun `una página que no existe no se anota`() {
        assertNull(
            DrawPdf.anotarPagina(context, pdfDeUnaPagina(), 9, croquis(), 595.0, 842.0)
        )
    }

    @Test
    fun `un archivo que no es un PDF no rompe nada`() {
        assertNull(
            DrawPdf.anotarPagina(
                context, "esto no es un pdf".toByteArray(), 0, croquis(), 595.0, 842.0
            )
        )
    }

    /** Anotar dos veces deja dos capas y las dos revisiones se leen. */
    @Test
    fun `se puede anotar dos veces la misma página`() {
        val primera = anotado()!!
        val segunda = DrawPdf.anotarPagina(
            context, primera, 0,
            Scene(
                elements = listOf(
                    Element(
                        id = "x", type = ElementType.LINE, x = 0.0, y = 0.0,
                        width = 50.0, height = 50.0, seed = 7,
                        points = listOf(Pt(0.0, 0.0), Pt(50.0, 50.0))
                    )
                )
            ),
            595.0, 842.0, nombreDeLaCapa = "Revisión 2"
        )
        assertNotNull("la segunda tanda no ha salido", segunda)
        val releido = leerPdf(segunda!!)!!
        assertEquals(2, releido.pagina(0)!!.lista("Annots")!!.size)
    }
}
