package com.forge.pixpin.ia

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **Un Excel mínimo** (`.xlsx` de una hoja, texto y números): lo que hace falta para que
 * `tabla_de_imagen` con destino «excel» deje un fichero que abre cualquier hoja de cálculo. Los
 * números van como números (`1250.50`) y lo demás como texto en línea; la primera fila, en negrita
 * si es cabecera. Sin estilos ni fórmulas.
 */
object Xlsx {
    private fun xml(s: String) = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> if (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') append(' ') else append(c)
        }
    }

    private fun columna(c: Int): String {
        var n = c + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
        return sb.toString()
    }

    fun escribir(salida: OutputStream, nombre: String, filas: List<List<String>>, cabecera: Boolean) {
        val hoja = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        filas.forEachIndexed { f, fila ->
            hoja.append("<row r=\"${f + 1}\">")
            fila.forEachIndexed { c, texto0 ->
                val texto = texto0.trim()
                if (texto.isEmpty()) return@forEachIndexed
                val ref = columna(c) + (f + 1)
                val estilo = if (f == 0 && cabecera) " s=\"1\"" else ""
                val numero = if (f == 0 && cabecera) null else TablaEscaneada.comoNumero(texto)
                if (numero != null) hoja.append("<c r=\"$ref\"$estilo><v>$numero</v></c>")
                else hoja.append("<c r=\"$ref\" t=\"inlineStr\"$estilo><is><t xml:space=\"preserve\">${xml(texto)}</t></is></c>")
            }
            hoja.append("</row>")
        }
        hoja.append("</sheetData></worksheet>")
        val titulo = xml(nombre.take(31).replace(Regex("[\\[\\]:*?/\\\\]"), " ").ifBlank { "Tabla" })
        val partes = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="$titulo" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf/><xf fontId="1" applyFont="1"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to hoja.toString()
        )
        ZipOutputStream(salida).use { z ->
            for ((nombreParte, texto) in partes) { z.putNextEntry(ZipEntry(nombreParte)); z.write(texto.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
    }
}
