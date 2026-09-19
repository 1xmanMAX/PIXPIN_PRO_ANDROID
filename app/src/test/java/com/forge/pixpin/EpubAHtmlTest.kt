package com.forge.pixpin

import com.forge.pixpin.motor.EpubAHtml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **El lector de libros.** Ver [EpubAHtml]. Cómo queda la página en el teléfono no se puede probar
 * aquí; lo que se comprueba es que de un `.epub` salen sus capítulos en el orden del lomo, sin
 * guiones, con las imágenes donde la página las busca, y que nada se escribe fuera de la carpeta.
 */
class EpubAHtmlTest {

    @get:Rule val temporal = TemporaryFolder()

    private val CONTENEDOR = "<?xml version=\"1.0\"?><container version=\"1.0\" " +
        "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>" +
        "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>" +
        "</rootfiles></container>"

    /** El manifiesto va en un orden y el lomo en el **contrario**: manda el lomo. */
    private fun opf(titulo: String = "Tom &amp; Jerry &lt;b&gt;", lomo: String = "<itemref idref=\"c2\"/><itemref idref=\"c1\"/>") =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\">" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>$titulo</dc:title>" +
            "<dc:creator>Ana \"la\" Autora</dc:creator><dc:language>es</dc:language></metadata><manifest>" +
            "<item id=\"c1\" href=\"text/c1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
            "<item id=\"c2\" href=\"text/c2.xhtml\" media-type=\"application/xhtml+xml\"/>" +
            "<item id=\"foto\" href=\"img/a.png\" media-type=\"image/png\"/>" +
            "<item id=\"css\" href=\"estilo.css\" media-type=\"text/css\"/>" +
            "</manifest><spine>$lomo</spine></package>"

    private val C1 = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">\n" +
        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>uno</title>" +
        "<link rel=\"stylesheet\" href=\"../estilo.css\"/><style>p{color:black}</style></head>\n" +
        "<BODY class=\"x\"><h2 id=\"nota\">Capítulo primero</h2>" +
        "<p onclick=\"alert(1)\" style=\"color:#000\">Hola&nbsp;mundo</p>" +
        "<script type=\"text/javascript\">alert('malo')</script><script src=\"x.js\"/>" +
        "<a id=\"p12\"/>" +
        "<p><img src=\"../img/a.png\" alt=\"foto\" onerror='alert(2)'/></p>" +
        "<p><img src=\"../../../fuera.png\"/></p></BODY></html>"

    private val C2 = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>dos</title></head><body>" +
        "<h2>Capítulo segundo</h2><p><a href=\"c1.xhtml#nota\">a la nota</a> <a href=\"c1.xhtml\">al otro</a> " +
        "<a href=\"javascript:alert(3)\">malo</a></p></body></html>"

    private fun epub(nombre: String = "libro.epub", entradas: Map<String, ByteArray>): File {
        val f = temporal.newFile(nombre)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((ruta, bytes) in entradas) { z.putNextEntry(ZipEntry(ruta)); z.write(bytes); z.closeEntry() }
        }
        return f
    }

    private fun libro(extra: Map<String, ByteArray> = emptyMap(), opf: String = opf()): File = epub(
        entradas = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to CONTENEDOR.toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
            "OEBPS/text/c1.xhtml" to C1.toByteArray(),
            "OEBPS/text/c2.xhtml" to C2.toByteArray(),
            "OEBPS/img/a.png" to byteArrayOf(1, 2, 3),
            "OEBPS/estilo.css" to "p{color:black}".toByteArray()
        ) + extra
    )

    /** La carpeta de salida, dentro de otra: así «fuera» es un sitio que también se puede mirar. */
    private fun destino() = File(temporal.newFolder(), "libro")

    @Test
    fun `los capítulos salen en el orden del lomo, sin guiones y con su imagen`() {
        val carpeta = destino()
        // Lo que hubiera de un libro anterior se va.
        carpeta.mkdirs(); File(carpeta, "viejo.txt").writeText("x")
        val pagina = EpubAHtml.convertir(libro(), carpeta)
        assertEquals(File(carpeta, "pagina.html"), pagina)
        assertTrue(pagina.isFile)
        assertFalse(File(carpeta, "viejo.txt").exists())
        val html = pagina.readText()

        assertTrue(html, html.startsWith("<!doctype html>\n<html lang=\"es\">"))
        val segundo = html.indexOf("Capítulo segundo")
        val primero = html.indexOf("Capítulo primero")
        assertTrue(html, segundo > 0 && primero > segundo)
        assertTrue(html, html.indexOf("id=\"cap-1\"") in 1 until segundo)
        assertTrue(html, html.indexOf("id=\"cap-2\"") in segundo until primero)

        // Ni guiones, ni manejadores, ni el CSS del libro; el DOCTYPE del capítulo tampoco se cuela.
        assertFalse(html, html.contains("<script"))
        assertFalse(html, html.contains("alert("))
        assertFalse(html, html.contains("onclick"))
        assertFalse(html, html.contains("onerror"))
        assertFalse(html, html.contains("color:black"))
        assertFalse(html, html.contains("color:#000\""))
        assertFalse(html, html.contains("estilo.css"))
        assertFalse(html, html.contains("DTD XHTML"))
        // Las entidades con nombre llegan tal cual: el navegador las entiende.
        assertTrue(html, html.contains("Hola&nbsp;mundo"))

        // La imagen, desde la raíz de la carpeta, y el archivo donde la página lo busca.
        assertTrue(html, html.contains("<img src=\"OEBPS/img/a.png\" alt=\"foto\">"))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(carpeta, "OEBPS/img/a.png").readBytes())
        // Solo imágenes: ni el CSS ni los capítulos sueltos.
        assertFalse(File(carpeta, "OEBPS/estilo.css").exists())
        assertFalse(File(carpeta, "OEBPS/text/c1.xhtml").exists())

        // Los enlaces entre capítulos llevan a su sitio dentro de la página única.
        assertTrue(html, html.contains("<h2 id=\"c2-nota\">"))
        assertTrue(html, html.contains("<a href=\"#c2-nota\">a la nota</a>"))
        assertTrue(html, html.contains("<a href=\"#cap-2\">al otro</a>"))
        assertTrue(html, html.contains("<a href=\"#\">malo</a>"))
        // `<a id="p12"/>` en HTML se quedaría abierto hasta el final.
        assertTrue(html, html.contains("<a id=\"c2-p12\"></a>"))
        // Y para imprimir: A4 y capítulo en página nueva.
        assertTrue(html, html.contains("@page{size:A4;margin:20mm}"))
        assertTrue(html, html.contains("break-before:page"))
    }

    @Test
    fun `el título y el autor se escapan`() {
        val html = EpubAHtml.convertir(libro(), destino(), "otro nombre").readText()
        assertTrue(html, html.contains("<title>Tom &amp; Jerry &lt;b&gt;</title>"))
        assertTrue(html, html.contains("<h1>Tom &amp; Jerry &lt;b&gt;</h1>"))
        assertTrue(html, html.contains("Ana &quot;la&quot; Autora"))
        assertFalse(html, html.contains("<b>"))
    }

    @Test
    fun `sin título en el libro vale el que se le pasa, también escapado`() {
        val html = EpubAHtml.convertir(libro(opf = opf(titulo = "")), destino(), "Mi <libro>.epub").readText()
        assertTrue(html, html.contains("<title>Mi &lt;libro&gt;.epub</title>"))
    }

    @Test
    fun `una entrada que apunta fuera de la carpeta no se escribe`() {
        val carpeta = destino()
        val pagina = EpubAHtml.convertir(
            libro(mapOf("../fuera.png" to byteArrayOf(9), "../../fuera.png" to byteArrayOf(9), "OEBPS/../../fuera.png" to byteArrayOf(9))),
            carpeta
        )
        assertTrue(pagina.isFile)
        assertFalse(File(carpeta.parentFile, "fuera.png").exists())
        assertFalse(File(carpeta.parentFile.parentFile, "fuera.png").exists())
        // Y la página tampoco la pide: `../../../fuera.png` se sale del libro.
        assertFalse(pagina.readText().contains("fuera.png"))
        // Lo de dentro sí salió.
        assertTrue(File(carpeta, "OEBPS/img/a.png").isFile)
    }

    @Test
    fun `lo que no es un epub lo dice`() {
        val falso = temporal.newFile("falso.epub").apply { writeText("esto no es un zip") }
        try { EpubAHtml.convertir(falso, destino()); fail("tenía que fallar") } catch (e: EpubAHtml.NoSeLee) {
            assertTrue(e.message.orEmpty().contains("EPUB"))
        }
        // Un ZIP sin `META-INF/container.xml` tampoco.
        val otro = epub("otro.epub", mapOf("hola.txt" to byteArrayOf(1)))
        try { EpubAHtml.convertir(otro, File(temporal.root, "otro")); fail("tenía que fallar") } catch (e: EpubAHtml.NoSeLee) {
            assertTrue(e.message.orEmpty().contains("container.xml"))
        }
        // Ni con contenedor pero sin el índice que dice.
        val sinOpf = epub("sinopf.epub", mapOf("META-INF/container.xml" to CONTENEDOR.toByteArray()))
        try { EpubAHtml.convertir(sinOpf, File(temporal.root, "sinopf")); fail("tenía que fallar") } catch (e: EpubAHtml.NoSeLee) {
            assertTrue(e.message.orEmpty().contains("índice"))
        }
    }

    @Test
    fun `un lomo vacío y un libro con DRM avisan, y no tocan la carpeta`() {
        val carpeta = File(temporal.newFolder("queda"), "libro").apply { mkdirs() }
        val testigo = File(carpeta, "testigo.txt").apply { writeText("x") }
        try { EpubAHtml.convertir(libro(opf = opf(lomo = "")), carpeta); fail("tenía que fallar") } catch (e: EpubAHtml.NoSeLee) {
            assertTrue(e.message.orEmpty().contains("capítulos"))
        }
        val cifrado = "<encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\">" +
            "<enc:EncryptedData><enc:CipherData><enc:CipherReference URI=\"OEBPS/text/c1.xhtml\"/></enc:CipherData></enc:EncryptedData></encryption>"
        val conDrm = epub(
            "drm.epub",
            linkedMapOf(
                "META-INF/container.xml" to CONTENEDOR.toByteArray(),
                "META-INF/encryption.xml" to cifrado.toByteArray(),
                "OEBPS/content.opf" to opf().toByteArray(),
                "OEBPS/text/c1.xhtml" to byteArrayOf(7, 7, 7),
                "OEBPS/text/c2.xhtml" to byteArrayOf(7, 7, 7)
            )
        )
        try { EpubAHtml.convertir(conDrm, carpeta); fail("tenía que fallar") } catch (e: EpubAHtml.NoSeLee) {
            assertTrue(e.message.orEmpty().contains("DRM"))
        }
        assertTrue("un libro que no se lee no borra el anterior", testigo.exists())
    }

    @Test
    fun `una tipografía ofuscada no es DRM`() {
        val cifrado = "<encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\">" +
            "<enc:EncryptedData><enc:CipherData><enc:CipherReference URI=\"OEBPS/fonts/letra.otf\"/></enc:CipherData></enc:EncryptedData></encryption>"
        val pagina = EpubAHtml.convertir(libro(mapOf("META-INF/encryption.xml" to cifrado.toByteArray())), destino())
        assertTrue(pagina.readText().contains("Capítulo primero"))
    }

    @Test
    fun `se reconoce por el nombre`() {
        assertTrue(EpubAHtml.esEpub("El Quijote.EPUB"))
        assertTrue(EpubAHtml.esEpub("/ruta/con.puntos/libro.epub"))
        assertFalse(EpubAHtml.esEpub("libro.pdf"))
        assertFalse(EpubAHtml.esEpub("epub"))
        assertFalse(EpubAHtml.esEpub(null))
    }
}
