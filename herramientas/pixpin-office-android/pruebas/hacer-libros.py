#!/usr/bin/env python3
"""Hace libros de Excel a mano (zip + XML) para las pruebas y las medidas del visor de hojas.

    python3 hacer-libros.py <carpeta de salida> [filas del grande]

Deja `prueba.xlsx` —dos hojas; en la primera, un título combinado A1:C1 con relleno y negrita,
un número con miles y dos decimales, una fecha dd/mm/yyyy, un texto en rojo, un porcentaje, un
borde fino, una fila alta, una columna ancha, un enlace y la fila 1 inmovilizada— y
`grande.xlsx`, con N filas × 10 columnas (números, textos compartidos y fechas) para medir.
Con `hojas-a-pxhj` se pasan a PXHL/PXHG/PXHT: los de `prueba.xlsx` son los fixtures de
`app/src/test/resources/excel/`.
"""
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

TIPOS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
</Types>"""

RAIZ = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

NS = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" ' \
     'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'


def libro_xml(hojas):
    s = "".join(f'<sheet name="{escape(n)}" sheetId="{i + 1}" r:id="rId{i + 1}"/>' for i, n in enumerate(hojas))
    return f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<workbook {NS}><sheets>{s}</sheets></workbook>'


def rels_libro(n):
    r = "".join(
        f'<Relationship Id="rId{i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
        f'Target="worksheets/sheet{i + 1}.xml"/>' for i in range(n))
    r += f'<Relationship Id="rId{n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
    r += f'<Relationship Id="rId{n + 2}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>'
    return f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' \
           f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{r}</Relationships>'


# Estilos: 0 normal; 1 título (negrita 14, relleno amarillo, centrado); 2 número #,##0.00 con
# borde fino; 3 fecha dd/mm/yyyy; 4 texto rojo; 5 porcentaje 0%.
ESTILOS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="dd/mm/yyyy"/></numFmts>
<fonts count="3">
<font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/></font>
<font><b/><sz val="14"/><color rgb="FF1F3864"/><name val="Calibri"/></font>
<font><sz val="11"/><color rgb="FFFF0000"/><name val="Arial"/></font>
</fonts>
<fills count="3">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/><bgColor indexed="64"/></patternFill></fill>
</fills>
<borders count="2">
<border><left/><right/><top/><bottom/><diagonal/></border>
<border><left style="thin"><color rgb="FF000000"/></left><right style="thin"><color rgb="FF000000"/></right><top style="thin"><color rgb="FF000000"/></top><bottom style="medium"><color rgb="FF0000FF"/></bottom><diagonal/></border>
</borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="6">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
<xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="9" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""


def textos_xml(textos):
    s = "".join(f"<si><t xml:space=\"preserve\">{escape(t)}</t></si>" for t in textos)
    return f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' \
           f'<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="{len(textos)}" uniqueCount="{len(textos)}">{s}</sst>'


def escribir(ruta, partes):
    with zipfile.ZipFile(ruta, "w", zipfile.ZIP_DEFLATED) as z:
        for nombre, texto in partes:
            z.writestr(nombre, texto)


def prueba(ruta):
    textos = ["Informe de obra", "Avance", "Texto en rojo", "Datos"]
    hoja1 = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet {NS}>
<dimension ref="A1:C3"/>
<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/><selection activeCell="B2" sqref="B2"/></sheetView></sheetViews>
<sheetFormatPr defaultRowHeight="15"/>
<cols><col min="2" max="2" width="20" customWidth="1"/></cols>
<sheetData>
<row r="1"><c r="A1" s="1" t="s"><v>0</v></c><c r="B1" s="1"/><c r="C1" s="1"/></row>
<row r="2"><c r="A2" s="2"><v>1234.5</v></c><c r="B2" s="3"><v>45000</v></c><c r="C2" s="4" t="s"><v>2</v></c></row>
<row r="3" ht="30" customHeight="1"><c r="A3" s="5"><v>0.25</v></c><c r="B3" t="s"><v>1</v></c></row>
</sheetData>
<mergeCells count="1"><mergeCell ref="A1:C1"/></mergeCells>
<hyperlinks><hyperlink ref="B3" location="Datos!A1" display="Avance"/></hyperlinks>
</worksheet>"""
    hoja2 = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet {NS}>
<sheetData><row r="1"><c r="A1" t="s"><v>3</v></c><c r="B1"><v>42</v></c></row></sheetData>
</worksheet>"""
    escribir(ruta, [
        ("[Content_Types].xml", TIPOS), ("_rels/.rels", RAIZ),
        ("xl/workbook.xml", libro_xml(["Resumen", "Datos"])), ("xl/_rels/workbook.xml.rels", rels_libro(2)),
        ("xl/styles.xml", ESTILOS), ("xl/sharedStrings.xml", textos_xml(textos)),
        ("xl/worksheets/sheet1.xml", hoja1), ("xl/worksheets/sheet2.xml", hoja2),
    ])


def columna(c):
    s = ""
    c += 1
    while c:
        c, r = divmod(c - 1, 26)
        s = chr(65 + r) + s
    return s


def grande(ruta, filas):
    textos = [f"Partida {i}" for i in range(500)] + ["Concreto", "Acero", "Encofrado", "Mano de obra"]
    trozos = []
    for f in range(filas):
        r = f + 1
        celdas = []
        for c in range(10):
            ref = f"{columna(c)}{r}"
            if c in (0, 5):
                celdas.append(f'<c r="{ref}" t="s"><v>{(f * 7 + c) % len(textos)}</v></c>')
            elif c == 3:
                celdas.append(f'<c r="{ref}" s="3"><v>{44000 + f % 900}</v></c>')
            elif c == 6:
                celdas.append(f'<c r="{ref}" s="5"><v>{(f % 100) / 100}</v></c>')
            else:
                celdas.append(f'<c r="{ref}" s="2"><v>{(f * 31 + c * 17) % 100000 / 7:.4f}</v></c>')
        trozos.append(f'<row r="{r}">{"".join(celdas)}</row>')
    hoja1 = f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<worksheet {NS}>' \
            f'<dimension ref="A1:J{filas}"/><sheetData>{"".join(trozos)}</sheetData></worksheet>'
    hoja2 = f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<worksheet {NS}><sheetData/></worksheet>'
    escribir(ruta, [
        ("[Content_Types].xml", TIPOS), ("_rels/.rels", RAIZ),
        ("xl/workbook.xml", libro_xml(["Grande", "Vacía"])), ("xl/_rels/workbook.xml.rels", rels_libro(2)),
        ("xl/styles.xml", ESTILOS), ("xl/sharedStrings.xml", textos_xml(textos)),
        ("xl/worksheets/sheet1.xml", hoja1), ("xl/worksheets/sheet2.xml", hoja2),
    ])


def extras(ruta):
    """Lo de Excel que llegó al PC el 12-oct (9975385): encabezado A1:C1 con autofiltro (filtro
    puesto en B), barras de datos en A2:A7, iconos (3 flechas) en B2:B7, un minigráfico de
    columnas en C2 con A2:A7, una nota en A2 y una lista desplegable «Sí,No» en D2:D7."""
    filas = ['<row r="1"><c r="A1" t="inlineStr"><is><t>Valor</t></is></c><c r="B1" t="inlineStr"><is><t>Tendencia</t></is></c>'
             '<c r="C1" t="inlineStr"><is><t>Mini</t></is></c><c r="D1" t="inlineStr"><is><t>Hecho</t></is></c></row>']
    valores = [3, 8, -2, 5, 10, 1]
    for i, v in enumerate(valores):
        r = i + 2
        filas.append(f'<row r="{r}"><c r="A{r}"><v>{v}</v></c><c r="B{r}"><v>{v * 10}</v></c></row>')
    hoja = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet {NS} xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main" xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main">
<dimension ref="A1:D7"/>
<sheetData>{"".join(filas)}</sheetData>
<autoFilter ref="A1:C7"><filterColumn colId="1"><filters><filter val="30"/></filters></filterColumn></autoFilter>
<conditionalFormatting sqref="A2:A7"><cfRule type="dataBar" priority="1"><dataBar><cfvo type="min"/><cfvo type="max"/><color rgb="FF638EC6"/></dataBar></cfRule></conditionalFormatting>
<conditionalFormatting sqref="B2:B7"><cfRule type="iconSet" priority="2"><iconSet iconSet="3Arrows"><cfvo type="percent" val="0"/><cfvo type="percent" val="33"/><cfvo type="percent" val="67"/></iconSet></cfRule></conditionalFormatting>
<dataValidations count="1"><dataValidation type="list" allowBlank="1" showInputMessage="1" showErrorMessage="1" sqref="D2:D7"><formula1>"Sí,No"</formula1></dataValidation></dataValidations>
<extLst><ext uri="{{05C60535-1F16-4fd2-B633-F4F36F0B64E0}}"><x14:sparklineGroups><x14:sparklineGroup type="column"><x14:colorSeries rgb="FF376092"/><x14:colorNegative rgb="FFD00000"/><x14:sparklines><x14:sparkline><xm:f>A2:A7</xm:f><xm:sqref>C2</xm:sqref></x14:sparkline></x14:sparklines></x14:sparklineGroup></x14:sparklineGroups></ext></extLst>
</worksheet>"""
    rels_hoja = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="../comments1.xml"/></Relationships>"""
    notas = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<comments xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><authors><author>Luis</author></authors><commentList><comment ref="A2" authorId="0"><text><r><t>Revisar este valor</t></r></text></comment></commentList></comments>"""
    escribir(ruta, [
        ("[Content_Types].xml", TIPOS), ("_rels/.rels", RAIZ),
        ("xl/workbook.xml", libro_xml(["Extras", "Datos"])), ("xl/_rels/workbook.xml.rels", rels_libro(2)),
        ("xl/styles.xml", ESTILOS), ("xl/sharedStrings.xml", textos_xml(["x"])),
        ("xl/worksheets/sheet1.xml", hoja), ("xl/worksheets/_rels/sheet1.xml.rels", rels_hoja), ("xl/comments1.xml", notas),
        ("xl/worksheets/sheet2.xml", f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<worksheet {NS}><sheetData/></worksheet>'),
    ])


if __name__ == "__main__":
    salida = Path(sys.argv[1])
    salida.mkdir(parents=True, exist_ok=True)
    prueba(salida / "prueba.xlsx")
    extras(salida / "extras.xlsx")
    if len(sys.argv) > 2:
        grande(salida / "grande.xlsx", int(sys.argv[2]))
