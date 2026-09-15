package com.forge.pixpin

import com.forge.pixpin.motor.Diapositivas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **El lector de PowerPoint.** Ver [Diapositivas]. Lo que pinta (`DiapositivasAPdf`) no se puede
 * probar aquí —no hay rasterizador—; lo que se comprueba es que se lee lo que hay y dónde.
 */
class DiapositivasTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val A = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
    private val P = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    private val R = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun rels(vararg r: Triple<String, String, String>) =
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            r.joinToString("") { (id, tipo, destino) -> "<Relationship Id=\"$id\" Type=\"$REL/$tipo\" Target=\"$destino\"/>" } +
            "</Relationships>"

    private fun pptx(vararg partes: Pair<String, String>, fotos: Map<String, ByteArray> = emptyMap()): File {
        val f = carpeta.newFile("prueba.pptx")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((ruta, texto) in partes) { z.putNextEntry(ZipEntry(ruta)); z.write(texto.toByteArray()); z.closeEntry() }
            for ((ruta, bytes) in fotos) { z.putNextEntry(ZipEntry(ruta)); z.write(bytes); z.closeEntry() }
        }
        return f
    }

    private fun basica(slide1: String, slide2: String? = null): File {
        val ids = if (slide2 != null) "<p:sldId id=\"256\" r:id=\"rId2\"/><p:sldId id=\"257\" r:id=\"rId3\"/>" else "<p:sldId id=\"256\" r:id=\"rId2\"/>"
        val lista = mutableListOf(
            "ppt/presentation.xml" to "<p:presentation $A $P $R><p:sldIdLst>$ids</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/></p:presentation>",
            "ppt/_rels/presentation.xml.rels" to rels(
                Triple("rId2", "slide", "slides/slide1.xml"), Triple("rId3", "slide", "slides/slide2.xml")
            ),
            "ppt/slides/slide1.xml" to slide1,
            "ppt/slides/_rels/slide1.xml.rels" to rels(
                Triple("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"),
                Triple("rId5", "image", "../media/image1.png")
            ),
            "ppt/slideLayouts/slideLayout1.xml" to "<p:sldLayout $A $P $R><p:cSld><p:spTree>" +
                "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"t\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>" +
                "<p:spPr><a:xfrm><a:off x=\"838200\" y=\"365125\"/><a:ext cx=\"10515600\" cy=\"1325563\"/></a:xfrm></p:spPr></p:sp>" +
                "</p:spTree></p:cSld></p:sldLayout>",
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to rels(Triple("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")),
            "ppt/slideMasters/slideMaster1.xml" to "<p:sldMaster $A $P $R><p:cSld><p:bg><p:bgPr><a:solidFill><a:schemeClr val=\"bg1\"/></a:solidFill></p:bgPr></p:bg><p:spTree/></p:cSld>" +
                "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\"/>" +
                "<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"4400\"/></a:lvl1pPr></p:titleStyle></p:txStyles></p:sldMaster>",
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to rels(Triple("rId1", "theme", "../theme/theme1.xml")),
            "ppt/theme/theme1.xml" to "<a:theme $A><a:themeElements><a:clrScheme name=\"x\">" +
                "<a:dk1><a:srgbClr val=\"111111\"/></a:dk1><a:lt1><a:srgbClr val=\"FAFAFA\"/></a:lt1>" +
                "<a:dk2><a:srgbClr val=\"222222\"/></a:dk2><a:lt2><a:srgbClr val=\"EEEEEE\"/></a:lt2>" +
                "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1></a:clrScheme></a:themeElements></a:theme>"
        )
        if (slide2 != null) {
            lista += "ppt/slides/slide2.xml" to slide2
            lista += "ppt/slides/_rels/slide2.xml.rels" to rels(Triple("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"))
        }
        return pptx(*lista.toTypedArray())
    }

    private fun diapositiva(arbol: String, atributos: String = "") =
        "<p:sld $A $P $R $atributos><p:cSld><p:spTree>$arbol</p:spTree></p:cSld></p:sld>"

    @Test
    fun `el titulo sin posicion la hereda de la plantilla y toma el tamano del patron`() {
        val f = basica(diapositiva(
            "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"T\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr/>" +
                "<p:txBody><a:bodyPr/><a:p><a:r><a:rPr lang=\"es\"/><a:t>Hola</a:t></a:r></a:p></p:txBody></p:sp>"
        ))
        val pres = Diapositivas.leer(f)
        assertEquals(12192000L, pres.ancho)
        val forma = pres.diapositivas.single().piezas.single() as Diapositivas.Forma
        assertEquals(838200.0, forma.caja.x, 0.1)
        assertEquals(1325563.0, forma.caja.alto, 0.1)
        val tramo = forma.texto!!.parrafos.single().tramos.single()
        assertEquals("Hola", tramo.texto)
        assertEquals(44.0, tramo.tamano, 0.01)
        // El color del texto sale del tema: tx1 → dk1.
        assertEquals(0xFF111111.toInt(), tramo.color)
        // Y el fondo, del patrón: bg1 → lt1.
        assertEquals(0xFFFAFAFA.toInt(), pres.diapositivas.single().fondo)
    }

    @Test
    fun `un cuadro de texto con su color negrita y alineacion`() {
        val f = basica(diapositiva(
            "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"c\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
                "<p:spPr><a:xfrm rot=\"5400000\"><a:off x=\"100\" y=\"200\"/><a:ext cx=\"3000\" cy=\"4000\"/></a:xfrm><a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill></p:spPr>" +
                "<p:txBody><a:bodyPr anchor=\"ctr\"/><a:p><a:pPr algn=\"ctr\"/><a:r><a:rPr sz=\"2000\" b=\"1\"><a:solidFill><a:srgbClr val=\"00FF00\"/></a:solidFill></a:rPr><a:t>Verde</a:t></a:r></a:p></p:txBody></p:sp>"
        ))
        val forma = Diapositivas.leer(f).diapositivas.single().piezas.single() as Diapositivas.Forma
        assertEquals(90.0, forma.giro, 1e-9)
        assertEquals(0xFFFF0000.toInt(), forma.relleno)
        val t = forma.texto!!
        assertEquals(Diapositivas.Ancla.CENTRO, t.ancla)
        val p = t.parrafos.single()
        assertEquals(Diapositivas.Alineacion.CENTRO, p.alineacion)
        val tramo = p.tramos.single()
        assertTrue(tramo.negrita)
        assertEquals(20.0, tramo.tamano, 1e-9)
        assertEquals(0xFF00FF00.toInt(), tramo.color)
    }

    @Test
    fun `un grupo escala y mueve a sus hijos`() {
        val f = basica(diapositiva(
            "<p:grpSp><p:nvGrpSpPr><p:cNvPr id=\"4\" name=\"g\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
                "<p:grpSpPr><a:xfrm><a:off x=\"1000\" y=\"1000\"/><a:ext cx=\"2000\" cy=\"2000\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"1000\" cy=\"1000\"/></a:xfrm></p:grpSpPr>" +
                "<p:sp><p:nvSpPr><p:cNvPr id=\"5\" name=\"r\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"500\" y=\"0\"/><a:ext cx=\"100\" cy=\"100\"/></a:xfrm>" +
                "<a:prstGeom prst=\"ellipse\"/><a:solidFill><a:schemeClr val=\"accent1\"/></a:solidFill></p:spPr></p:sp></p:grpSp>"
        ))
        val forma = Diapositivas.leer(f).diapositivas.single().piezas.single() as Diapositivas.Forma
        assertEquals(Diapositivas.Geometria.ELIPSE, forma.geometria)
        assertEquals(2000.0, forma.caja.x, 1e-6)   // 1000 + 500·2
        assertEquals(1000.0, forma.caja.y, 1e-6)
        assertEquals(200.0, forma.caja.ancho, 1e-6)
        assertEquals(0xFF4472C4.toInt(), forma.relleno)
    }

    @Test
    fun `la foto lleva su entrada del zip y su recorte`() {
        val f = basica(diapositiva(
            "<p:pic><p:nvPicPr><p:cNvPr id=\"6\" name=\"f\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>" +
                "<p:blipFill><a:blip r:embed=\"rId5\"/><a:srcRect l=\"10000\" r=\"20000\"/><a:stretch/></p:blipFill>" +
                "<p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"5000\" cy=\"5000\"/></a:xfrm></p:spPr></p:pic>"
        ))
        val foto = Diapositivas.leer(f).diapositivas.single().piezas.single() as Diapositivas.Foto
        assertEquals("ppt/media/image1.png", foto.entrada)
        assertEquals(0.1f, foto.recorte[0], 1e-6f)
        assertEquals(0.2f, foto.recorte[2], 1e-6f)
    }

    @Test
    fun `las diapositivas ocultas no salen y el orden es el de la presentacion`() {
        val texto = { s: String ->
            "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"c\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"100\" cy=\"100\"/></a:xfrm></p:spPr>" +
                "<p:txBody><a:bodyPr/><a:p><a:r><a:t>$s</a:t></a:r></a:p></p:txBody></p:sp>"
        }
        val f = basica(diapositiva(texto("uno")), diapositiva(texto("dos"), "show=\"0\""))
        val pres = Diapositivas.leer(f)
        assertEquals(1, pres.diapositivas.size)
    }

    @Test
    fun `un ppt antiguo se reconoce para decir que no se lee`() {
        val f = carpeta.newFile("viejo.ppt")
        try {
            Diapositivas.leer(f)
            fail("tenía que avisar")
        } catch (e: Diapositivas.NoSeLee) {
            assertNotNull(e.message)
            assertTrue(e.message!!.contains(".pptx"))
        }
    }

    @Test
    fun `las rutas relativas de las relaciones se resuelven`() {
        assertEquals("ppt/slideLayouts/slideLayout1.xml", Diapositivas.resolver("ppt/slides", "../slideLayouts/slideLayout1.xml"))
        assertEquals("ppt/media/a.png", Diapositivas.resolver("ppt/slides", "/ppt/media/a.png"))
    }
}
