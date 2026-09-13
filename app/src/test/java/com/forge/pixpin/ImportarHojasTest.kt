package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Un Excel, un ODS o un CSV compartido se convierten en tablas: valores, fórmulas, estilos,
 * fechas, y las fórmulas que aquí no se calculan se quedan con el valor que traían.
 */
class ImportarHojasTest {

    private fun zip(sufijo: String, vararg entradas: Pair<String, String>): File {
        val f = File.createTempFile("libro", sufijo)
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, t) in entradas) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        return f
    }

    private fun TablaViva.ver(dir: String): String { val p = Celdas.leer(dir)!!; return calc.texto(p[0], p[1]) }
    private fun TablaViva.crudo(dir: String): String { val p = Celdas.leer(dir)!!; return calc.crudo(p[0], p[1]) }

    @Test
    fun `un xlsx con dos hojas visibles estilos fechas y formulas`() {
        val f = zip(
            ".xlsx",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><workbookPr/><sheets><sheet name="Gastos" sheetId="1" r:id="rId1"/><sheet name="Oculta" sheetId="2" state="hidden" r:id="rId2"/><sheet name="Resumen" sheetId="3" r:id="rId3"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="w" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="w" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="w" Target="/xl/worksheets/sheet3.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst><si><t>Concepto</t></si><si><r><t>Tot</t></r><r><t>al</t></r></si><si><t>00123</t></si><si><t>=no es fórmula</t></si></sst>""",
            "xl/styles.xml" to """<styleSheet><numFmts><numFmt numFmtId="164" formatCode="dd/mm/yyyy;@"/></numFmts><fonts><font><sz val="11"/></font><font><b/><sz val="11"/></font></fonts><fills><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/></patternFill></fill></fills><cellStyleXfs><xf numFmtId="0" fontId="1"/></cellStyleXfs><cellXfs><xf numFmtId="0" fontId="0" fillId="0"/><xf numFmtId="0" fontId="1" fillId="2" applyAlignment="1"><alignment horizontal="center"/></xf><xf numFmtId="164" fontId="0" fillId="0"/><xf numFmtId="9" fontId="0" fillId="0"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><cols><col min="1" max="1" width="20" customWidth="1"/></cols><sheetData>
                <row r="1"><c r="A1" t="s" s="1"><v>0</v></c><c r="B1" t="s"><v>2</v></c><c r="C1" t="s"><v>3</v></c></row>
                <row r="2"><c r="A2"><v>0.30000000000000004</v></c><c r="B2" s="2"><v>46275</v></c><c r="C2" s="3"><v>0.15</v></c></row>
                <row r="3"><c r="A3"><v>10</v></c><c r="B3"><f t="shared" ref="B3:B4" si="0">A3*2</f><v>20</v></c></row>
                <row r="4"><c r="A4"><v>7</v></c><c r="B4"><f t="shared" si="0"/><v>14</v></c></row>
                <row r="5"><c r="A5" t="s"><v>1</v></c><c r="B5"><f>SUM(B3:B4)</f><v>34</v></c><c r="C5"><f>_xlfn.XLOOKUP(7,A3:A4,B3:B4)</f><v>14</v></c><c r="D5"><f>CUBEVALUE("x")</f><v>99</v></c><c r="E5"><f>D5+1</f><v>100</v></c><c r="F5" t="str"><f>"a"&amp;"b"</f><v>ab</v></c><c r="G5" t="b"><v>1</v></c></row>
                </sheetData></worksheet>""",
            "xl/worksheets/sheet2.xml" to """<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData></worksheet>""",
            "xl/worksheets/sheet3.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Hola</t></is></c></row></sheetData></worksheet>"""
        )
        val hojas = ImportarHojas.leer(f, "Cuentas 2026.xlsx")
        assertEquals("la oculta no entra", listOf("Cuentas 2026 · Gastos", "Cuentas 2026 · Resumen"), hojas.map { it.nombre })
        val t = TablaViva(hojas[0].tabla)
        assertEquals("Cuentas 2026 · Gastos", t.nombre)
        assertEquals("Concepto", t.ver("A1"))
        assertEquals(EstiloDeCelda(n = true, a = "c", f = "#ffff00"), t.estilo(0, 0))
        assertEquals("un texto que parece número sigue siendo texto", "'00123", t.crudo("B1"))
        assertEquals("00123", t.ver("B1"))
        assertEquals("=no es fórmula", t.ver("C1"))
        assertEquals("diecisiete cifras, a quince", "0.3", t.crudo("A2"))
        assertEquals("10/09/2026", t.ver("B2"))
        assertEquals("15%", t.ver("C2"))
        assertEquals("fórmula compartida", "=A4*2", t.crudo("B4"))
        assertEquals("14", t.ver("B4"))
        assertEquals("=SUM(B3:B4)", t.crudo("B5"))
        assertEquals("34", t.ver("B5"))
        assertEquals("sin el _xlfn.", "=XLOOKUP(7,A3:A4,B3:B4)", t.crudo("C5"))
        assertEquals("14", t.ver("C5"))
        assertEquals("la función que aquí no hay se queda con su valor", "99", t.crudo("D5"))
        assertEquals("y la que depende de ella sigue siendo fórmula", "=D5+1", t.crudo("E5"))
        assertEquals("100", t.ver("E5"))
        assertEquals("ab", t.ver("F5"))
        assertEquals("VERDADERO", t.ver("G5"))
        assertEquals(145, t.ancho(0))
        assertEquals(1, hojas[0].formulasComoValor)
        assertEquals("Hola", TablaViva(hojas[1].tabla).ver("A1"))
    }

    @Test
    fun `un csv de la Excel espanola`() {
        val f = File.createTempFile("precios", ".csv").apply { deleteOnExit() }
        f.writeText("﻿Producto;Precio\n\"Pan; grande\";1.234,50\nLeche;3,20\n")
        val hojas = ImportarHojas.leer(f, "precios.csv")
        assertEquals(1, hojas.size)
        assertEquals("precios", hojas[0].nombre)
        val t = TablaViva(hojas[0].tabla)
        assertEquals("Pan; grande", t.ver("A2"))
        t.escribir(3, 1, "=SUMA(B2:B3)")
        assertEquals("1237.7", t.ver("B4"))
    }

    @Test
    fun `un ods con filas repetidas y formulas`() {
        val f = zip(
            ".ods",
            "content.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                <office:document-content xmlns:office="o" xmlns:table="t" xmlns:text="x"><office:body><office:spreadsheet>
                <table:table table:name="Hoja1">
                  <table:table-column table:number-columns-repeated="16384"/>
                  <table:table-row>
                    <table:table-cell office:value-type="float" office:value="2"><text:p>2</text:p></table:table-cell>
                    <table:table-cell office:value-type="float" office:value="3"><text:p>3</text:p></table:table-cell>
                    <table:table-cell table:formula="of:=SUM([.A1:.B1])" office:value-type="float" office:value="5"><text:p>5</text:p></table:table-cell>
                    <table:table-cell table:formula="of:=[Hoja2.A1]" office:value-type="float" office:value="42"><text:p>42</text:p></table:table-cell>
                    <table:table-cell table:number-columns-repeated="16380"/>
                  </table:table-row>
                  <table:table-row table:number-rows-repeated="2">
                    <table:table-cell office:value-type="string"><text:p>x<text:s text:c="2"/>y</text:p></table:table-cell>
                    <table:table-cell office:value-type="date" office:date-value="2026-09-10"><text:p>10/09/26</text:p></table:table-cell>
                  </table:table-row>
                  <table:table-row table:number-rows-repeated="1048570"><table:table-cell table:number-columns-repeated="16384"/></table:table-row>
                </table:table>
                </office:spreadsheet></office:body></office:document-content>"""
        )
        val hojas = ImportarHojas.leer(f, "libro.ods")
        val t = TablaViva(hojas[0].tabla)
        assertEquals("=SUM(A1:B1)", t.crudo("C1"))
        assertEquals("5", t.ver("C1"))
        assertEquals("otra hoja: se queda con su valor", "42", t.crudo("D1"))
        assertEquals("x  y", t.ver("A2"))
        assertEquals("la fila repetida se copia", "x  y", t.ver("A3"))
        assertEquals("10/09/2026", t.ver("B3"))
        assertEquals("las filas vacías no ocupan", 3, t.calc.filas)
    }

    @Test
    fun `un xls antiguo se reconoce y se dice que no se lee`() {
        val f = File.createTempFile("viejo", ".xls").apply { deleteOnExit(); writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())) }
        assertTrue(ImportarHojas.esLibro("viejo.xls"))
        try {
            ImportarHojas.leer(f, "viejo.xls")
            fail("tenía que avisar")
        } catch (e: ImportarHojas.NoSeLee) {
            assertTrue(e.message!!.contains(".xlsx"))
        }
    }
}
