#!/usr/bin/env python3
"""Hace un Word y un PowerPoint pequeños, a mano (zip + XML), para las pruebas del formato PXOF.

    python3 hacer-documentos.py <carpeta de salida>

Deja `prueba.docx` (dos páginas: título, negrita/cursiva/subrayado, un enlace, una tabla, un salto
de página y un pie con el número de página) y `prueba.pptx` (una diapositiva con un título, un
rectángulo relleno con texto y una elipse con borde). Con `office-a-pxof` se pasan a PXOF y esos
`.pxof` son los fixtures de `app/src/test/resources/office/`.
"""
import sys
import zipfile
from pathlib import Path

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" ' \
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'


def escribir(ruta, partes):
    with zipfile.ZipFile(ruta, "w", zipfile.ZIP_DEFLATED) as z:
        for nombre, texto in partes:
            z.writestr(nombre, texto)


def word(ruta):
    tipos = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
</Types>"""
    rels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
    drels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rIdPie" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
<Relationship Id="rIdWeb" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.org/pixpin" TargetMode="External"/>
</Relationships>"""
    celda = lambda t: f'<w:tc><w:tcPr><w:tcW w:w="3000" w:type="dxa"/></w:tcPr><w:p><w:r><w:t>{t}</w:t></w:r></w:p></w:tc>'
    borde = '<w:tblBorders>' + ''.join(
        f'<w:{b} w:val="single" w:sz="4" w:color="000000"/>' for b in ("top", "left", "bottom", "right", "insideH", "insideV")
    ) + '</w:tblBorders>'
    cuerpo = f"""<?xml version="1.0" encoding="UTF-8"?>
<w:document {W}><w:body>
<w:p><w:r><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:b/><w:sz w:val="40"/><w:color w:val="1F4E79"/></w:rPr><w:t>Informe de prueba</w:t></w:r></w:p>
<w:p><w:r><w:t xml:space="preserve">Texto normal, </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">negrita, </w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t xml:space="preserve">cursiva </w:t></w:r><w:r><w:rPr><w:u w:val="single"/></w:rPr><w:t>y subrayado</w:t></w:r><w:r><w:t xml:space="preserve"> con eñes y tildes: canción, pingüino.</w:t></w:r></w:p>
<w:p><w:hyperlink r:id="rIdWeb"><w:r><w:rPr><w:color w:val="0563C1"/><w:u w:val="single"/></w:rPr><w:t>Un enlace</w:t></w:r></w:hyperlink></w:p>
<w:tbl><w:tblPr><w:tblW w:w="6000" w:type="dxa"/>{borde}</w:tblPr><w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid>
<w:tr>{celda("Uno")}{celda("Dos")}</w:tr><w:tr>{celda("Tres")}{celda("Cuatro")}</w:tr></w:tbl>
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
<w:p><w:r><w:t>Segunda página.</w:t></w:r></w:p>
<w:sectPr><w:footerReference w:type="default" r:id="rIdPie"/><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1701" w:bottom="1417" w:left="1701" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>
</w:body></w:document>"""
    pie = f"""<?xml version="1.0" encoding="UTF-8"?>
<w:ftr {W}><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t xml:space="preserve">Página </w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p></w:ftr>"""
    escribir(ruta, [("[Content_Types].xml", tipos), ("_rels/.rels", rels), ("word/_rels/document.xml.rels", drels),
                    ("word/document.xml", cuerpo), ("word/footer1.xml", pie)])


def powerpoint(ruta):
    A = 'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" ' \
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" ' \
        'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"'
    tipos = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
<Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
<Override PartName="/ppt/slides/slide2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
</Types>"""
    rels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>"""
    prels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide2.xml"/>
</Relationships>"""
    pres = f"""<?xml version="1.0" encoding="UTF-8"?>
<p:presentation {A}><p:sldIdLst><p:sldId id="256" r:id="rId2"/><p:sldId id="257" r:id="rId3"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>"""

    def forma(i, nombre, x, y, cx, cy, geo, relleno, linea, texto, tam, efectos=""):
        rel = f'<a:solidFill><a:srgbClr val="{relleno}"/></a:solidFill>' if relleno else '<a:noFill/>'
        lin = f'<a:ln w="38100"><a:solidFill><a:srgbClr val="{linea}"/></a:solidFill></a:ln>' if linea else '<a:ln><a:noFill/></a:ln>'
        tx = f'<p:txBody><a:bodyPr anchor="ctr"/><a:lstStyle/><a:p><a:pPr algn="ctr"/><a:r><a:rPr lang="es-ES" sz="{tam}"><a:solidFill><a:srgbClr val="202020"/></a:solidFill><a:latin typeface="Calibri"/></a:rPr><a:t>{texto}</a:t></a:r></a:p></p:txBody>' if texto else ''
        return f"""<p:sp><p:nvSpPr><p:cNvPr id="{i}" name="{nombre}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm><a:prstGeom prst="{geo}"><a:avLst/></a:prstGeom>{rel}{lin}{efectos}</p:spPr>{tx}</p:sp>"""

    dia = f"""<?xml version="1.0" encoding="UTF-8"?>
<p:sld {A}><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="F4F1EA"/></a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
{forma(2, "Título", 838200, 365125, 10515600, 1325563, "rect", None, None, "Diapositiva de prueba", 4400)}
{forma(3, "Caja", 1524000, 2286000, 4000000, 2000000, "roundRect", "9DC3E6", None, "Rectángulo", 2400)}
{forma(4, "Elipse", 6600000, 2286000, 3000000, 2000000, "ellipse", None, "C00000", None, 0)}
</p:spTree></p:cSld></p:sld>"""
    # La segunda (13-oct-2026, PC 954e77e y f38c011): un cubo (caras teñidas de la forma
    # predefinida), una caja con sombra exterior y brillo, una elipse con borde suave y un
    # rectángulo con sombra interior.
    sombra = ('<a:effectLst><a:glow rad="63500"><a:srgbClr val="FFC000"><a:alpha val="60000"/></a:srgbClr></a:glow>'
              '<a:outerShdw blurRad="50800" dist="38100" dir="2700000" algn="tl" rotWithShape="0">'
              '<a:srgbClr val="000000"><a:alpha val="40000"/></a:srgbClr></a:outerShdw></a:effectLst>')
    suave = '<a:effectLst><a:softEdge rad="127000"/></a:effectLst>'
    interior = ('<a:effectLst><a:innerShdw blurRad="63500" dist="50800" dir="13500000">'
                '<a:srgbClr val="000000"><a:alpha val="50000"/></a:srgbClr></a:innerShdw></a:effectLst>')
    dia2 = f"""<?xml version="1.0" encoding="UTF-8"?>
<p:sld {A}><p:cSld><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
{forma(2, "Cubo", 838200, 1000000, 2500000, 2500000, "cube", "70AD47", None, None, 0)}
{forma(3, "Con sombra", 4200000, 1000000, 3000000, 1500000, "rect", "4472C4", None, "Sombra", 2400, sombra)}
{forma(4, "Suave", 8000000, 1000000, 3000000, 2000000, "ellipse", "ED7D31", None, None, 0, suave)}
{forma(5, "Hundido", 4200000, 3800000, 3000000, 1500000, "rect", "D9D9D9", None, None, 0, interior)}
{forma(6, "Texto suelto", 838200, 5200000, 6000000, 800000, "rect", None, None, "Texto negro sobre la hoja", 2000)}
</p:spTree></p:cSld></p:sld>"""
    escribir(ruta, [("[Content_Types].xml", tipos), ("_rels/.rels", rels), ("ppt/_rels/presentation.xml.rels", prels),
                    ("ppt/presentation.xml", pres), ("ppt/slides/slide1.xml", dia), ("ppt/slides/slide2.xml", dia2)])


if __name__ == "__main__":
    salida = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    salida.mkdir(parents=True, exist_ok=True)
    word(salida / "prueba.docx")
    powerpoint(salida / "prueba.pptx")
    print("Hechos", salida / "prueba.docx", "y", salida / "prueba.pptx")
