package com.forge.pixpin.motormd

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **Una nota Markdown exportada a Word (`.docx`)**, escrita a mano como en el PC (1-oct-2026,
 * `pixpin-docs/src/exportar_docx.rs`): un Office Open XML de verdad, no un HTML disfrazado.
 *
 * Títulos con los estilos `Heading1-6` (salen en el panel de navegación de Word), viñetas y
 * numeradas con `numbering.xml`, casillas ☐/☒, tablas con su rejilla (`gridSpan`/`vMerge` para
 * las combinadas, fondos y color de letra, alineación, cabecera repetida en cada página), fotos
 * incrustadas a su tamaño, enlaces web como hipervínculos, código en Consolas sobre gris, citas
 * con su barra, la raya, fórmulas en Cambria Math y **los comentarios de la nota** como
 * comentarios de Word, con las respuestas en su hilo y los resueltos marcados.
 *
 * Las partes fijas (estilos, numeración, letras, comentarios, propiedades) son las del PC letra
 * por letra: el mismo `.docx` sale de los dos aparatos. El cuerpo se lee con el mismo Markdown
 * que pinta la nota aquí ([Markdown], bloque a bloque como el editor).
 *
 * Puro: no toca el disco. Las fotos las da quien llama ([imagen]) y sale el ZIP en bytes.
 */
object ExportarDocx {

    class Letra(val cuerpo: String = "Work Sans", val titulos: String = "Fraunces", val px: Int = 16)

    class Nota(
        val markdown: String,
        val titulo: String = "",
        val letra: Letra = Letra(),
        val comentarios: Comentarios.Fichero? = null,
        /** Los bytes de la foto de una ruta de la nota, ya en PNG, JPEG, GIF o BMP; null si no está. */
        val imagen: (String) -> ByteArray? = { null },
        val autor: String = "",
        val ahoraMs: Long = System.currentTimeMillis()
    )

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val W14 = "http://schemas.microsoft.com/office/word/2010/wordml"
    private const val W15 = "http://schemas.microsoft.com/office/word/2012/wordml"
    private const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    private const val CABECERA = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val CODIGO = "Consolas"

    /** Ancho del texto en una A4 con 2,5 cm de margen, en EMU (16 cm). Es la «columna» de 720 px del PC. */
    private const val ANCHO_TEXTO_EMU = 5_760_000L
    private const val EMU_POR_PX = 9525L

    // ------------------------------------------------------------- exportar

    fun exportar(n: Nota): ByteArray {
        val titulo = n.titulo.trim().ifEmpty { tituloDe(n.markdown) }
        val cuerpo = Cuerpo(n)
        val documento = cuerpo.escribir()
        val hayComentarios = cuerpo.comentariosW.isNotEmpty()
        val entradas = ArrayList<Pair<String, ByteArray>>()
        entradas += "[Content_Types].xml" to tipos(cuerpo.medios, hayComentarios).toByteArray()
        entradas += "_rels/.rels" to RELS_RAIZ.toByteArray()
        entradas += "docProps/core.xml" to core(titulo, n.autor, n.ahoraMs).toByteArray()
        entradas += "docProps/app.xml" to APP.toByteArray()
        entradas += "word/document.xml" to documento.toByteArray()
        entradas += "word/styles.xml" to estilos(n.letra).toByteArray()
        entradas += "word/numbering.xml" to numeracion(cuerpo.rachas).toByteArray()
        entradas += "word/settings.xml" to AJUSTES.toByteArray()
        entradas += "word/fontTable.xml" to letras(n.letra).toByteArray()
        if (hayComentarios) {
            val (c, ex) = comentarios(cuerpo.comentariosW)
            entradas += "word/comments.xml" to c.toByteArray()
            entradas += "word/commentsExtended.xml" to ex.toByteArray()
        }
        entradas += "word/_rels/document.xml.rels" to relsDocumento(cuerpo.medios, cuerpo.enlaces, hayComentarios).toByteArray()
        for (m in cuerpo.medios) entradas += "word/media/${m.nombre}" to m.datos
        val salida = ByteArrayOutputStream()
        ZipOutputStream(salida).use { z ->
            for ((nombre, datos) in entradas) {
                z.putNextEntry(ZipEntry(nombre)); z.write(datos); z.closeEntry()
            }
        }
        return salida.toByteArray()
    }

    /** El título de la nota: su primer título, o su primer renglón con algo. */
    fun tituloDe(md: String): String {
        val bloques = Markdown.parse(md)
        bloques.filterIsInstance<MarkdownBlock.Heading>().firstOrNull()?.let { return it.content.text.trim() }
        return md.lineSequence().map { it.trim().trimStart('#', '-', '*', '>', ' ') }.firstOrNull { it.isNotEmpty() }?.take(80) ?: "Nota"
    }

    /** Un nombre de archivo seguro, con `.docx` (las mismas reglas que el PC). */
    fun nombreDeArchivo(titulo: String): String {
        var limpio = titulo.map { if ("<>:\"/\\|?*".contains(it) || it.isISOControl()) '_' else it }.joinToString("").take(120)
        limpio = limpio.trim().trimEnd('.', ' ').trim()
        val base = limpio.substringBefore('.').trim().uppercase()
        val reservado = base in setOf("CON", "PRN", "AUX", "NUL") ||
            ((base.startsWith("COM") || base.startsWith("LPT")) && base.length == 4 && base[3].isDigit())
        limpio = when {
            limpio.all { it == '_' || it.isWhitespace() } -> "Nota"
            reservado -> "${limpio}_"
            else -> limpio
        }
        return "$limpio.docx"
    }

    // ------------------------------------------------------------- texto XML

    /** Escapado y sin lo que XML 1.0 no admite: una letra prohibida y Word da el archivo por dañado. */
    fun esc(s: String): String {
        val o = StringBuilder(s.length + 8)
        for (c in s) when {
            c == '&' -> o.append("&amp;")
            c == '<' -> o.append("&lt;")
            c == '>' -> o.append("&gt;")
            c == '"' -> o.append("&quot;")
            c == '\t' || c == '\n' || c == '\r' -> o.append(c)
            c.code < 0x20 -> {}
            c == '￾' || c == '￿' || c == '￹' || c == '￺' || c == '￻' -> {}
            else -> o.append(c)
        }
        return o.toString()
    }

    // ------------------------------------------------------------- el cuerpo

    class Medio(val nombre: String, val rid: String, val datos: ByteArray, val extension: String, val mime: String)

    /** Un comentario de Word: el hilo o una respuesta ([padre] = id del hilo). */
    class ComentarioW(val id: Int, val autor: String, val cuando: Long, val texto: String, val resuelto: Boolean, val padre: Int?)

    private class Cuerpo(val n: Nota) {
        val medios = ArrayList<Medio>()
        val enlaces = ArrayList<Pair<String, String>>()
        val rachas = ArrayList<Int>()
        val comentariosW = ArrayList<ComentarioW>()
        private var siguienteRid = 10
        private var docPr = 1
        private val s = StringBuilder()

        /** Por bloque (índice del trozo): los comentarios que caen en él, con su cita limpia. */
        private val porBloque = HashMap<Int, MutableList<Pair<String, List<Int>>>>()

        fun escribir(): String {
            val trozos = trozosDe(n.markdown)
            colocarComentarios(trozos)
            s.append(CABECERA)
            s.append("""<w:document xmlns:w="$W" xmlns:r="$R" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""")
            var rachaAbierta = -1
            var ultimoNumero = 0
            trozos.forEachIndexed { i, t ->
                val bloque = Markdown.parse(t.de(n.markdown)).firstOrNull() ?: return@forEachIndexed
                if (bloque is MarkdownBlock.Numbered) {
                    if (rachaAbierta < 0 || bloque.number != ultimoNumero + 1) {
                        rachas += bloque.number.coerceAtLeast(1)
                        rachaAbierta = rachas.size + 1
                    }
                    ultimoNumero = bloque.number
                } else rachaAbierta = -1
                bloque(bloque, porBloque[i].orEmpty(), rachaAbierta)
            }
            // A4 con márgenes de 2,5 cm.
            s.append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>""")
            return s.toString()
        }

        /** Cada hilo a su bloque; sin sitio, al principio del documento (con su cita), como el PC. */
        private fun colocarComentarios(trozos: List<Trozo>) {
            val f = n.comentarios ?: return
            var id = 0
            for (h in f.comentarios) {
                val sitio = Comentarios.ubicar(n.markdown, h.ancla)
                val bloque = if (sitio == null) 0 else trozoEn(trozos, sitio.first)
                val ids = ArrayList<Int>()
                val hiloId = id++
                ids += hiloId
                val texto = if (sitio == null) "«${h.ancla.cita}» — ${h.texto}" else h.texto
                comentariosW += ComentarioW(hiloId, h.autor, h.cuando, texto, h.resuelto, null)
                for (r in h.respuestas) {
                    val rid = id++
                    ids += rid
                    comentariosW += ComentarioW(rid, r.autor, r.cuando, r.texto, h.resuelto, hiloId)
                }
                val cita = Markdown.parseInline(h.ancla.cita).text
                porBloque.getOrPut(bloque) { ArrayList() } += cita to ids
            }
        }

        private fun rid(): String = "rId${siguienteRid++}"

        private fun parrafo(estilo: String? = null, extraPPr: String = "", contenido: () -> Unit) {
            s.append("<w:p>")
            if (estilo != null || extraPPr.isNotEmpty()) {
                s.append("<w:pPr>")
                if (estilo != null) s.append("""<w:pStyle w:val="$estilo"/>""")
                s.append(extraPPr)
                s.append("</w:pPr>")
            }
            contenido()
            s.append("</w:p>")
        }

        private fun bloque(b: MarkdownBlock, comentarios: List<Pair<String, List<Int>>>, numId: Int) {
            when (b) {
                is MarkdownBlock.Heading -> parrafo("Heading${b.level.coerceIn(1, 6)}") { texto(b.content, comentarios) }
                is MarkdownBlock.Paragraph -> {
                    // Un párrafo que es solo un enlace a un mensaje o una hoja: su nombre (como el PC).
                    parrafo { texto(b.content, comentarios) }
                }
                is MarkdownBlock.Bullet -> parrafo("ListParagraph", """<w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr>""") { texto(b.content, comentarios) }
                is MarkdownBlock.Numbered -> parrafo("ListParagraph", """<w:numPr><w:ilvl w:val="0"/><w:numId w:val="${numId.coerceAtLeast(2)}"/></w:numPr>""") { texto(b.content, comentarios) }
                is MarkdownBlock.Quote -> parrafo("Cita") { texto(b.content, comentarios) }
                is MarkdownBlock.Tarea -> parrafo("ListParagraph") {
                    s.append("""<w:r><w:rPr><w:rFonts w:ascii="Segoe UI Symbol" w:hAnsi="Segoe UI Symbol"/></w:rPr><w:t xml:space="preserve">${if (b.hecha) "☒" else "☐"} </w:t></w:r>""")
                    texto(b.content, comentarios, tachado = b.hecha)
                }
                is MarkdownBlock.Code -> {
                    val lineas = b.text.split('\n')
                    lineas.forEachIndexed { i, l ->
                        parrafo("CodigoBloque") {
                            if (i == 0) abrirComentarios(comentarios)
                            if (l.isNotEmpty()) s.append("""<w:r><w:t xml:space="preserve">${esc(l)}</w:t></w:r>""")
                            if (i == lineas.lastIndex) cerrarComentarios(comentarios)
                        }
                    }
                }
                MarkdownBlock.Rule -> parrafo(extraPPr = """<w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="BFBFBF"/></w:pBdr>""") {}
                is MarkdownBlock.Formula -> parrafo(extraPPr = """<w:jc w:val="center"/>""") {
                    abrirComentarios(comentarios)
                    s.append("""<w:r><w:rPr><w:rFonts w:ascii="Cambria Math" w:hAnsi="Cambria Math"/><w:i/></w:rPr><w:t xml:space="preserve">${esc(b.latex)}</w:t></w:r>""")
                    cerrarComentarios(comentarios)
                }
                is MarkdownBlock.Medio -> medio(b, comentarios)
                is MarkdownBlock.Tabla -> tabla(b, comentarios)
                is MarkdownBlock.Caja -> {
                    if (b.titulo.isNotBlank()) parrafo { s.append("""<w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">${esc(b.titulo)}</w:t></w:r>""") }
                    var primero = true
                    for (d in b.dentro) { bloque(d, if (primero) comentarios else emptyList(), -1); primero = false }
                }
            }
        }

        private fun abrirComentarios(c: List<Pair<String, List<Int>>>) {
            for ((_, ids) in c) for (id in ids) s.append("""<w:commentRangeStart w:id="$id"/>""")
        }

        private fun cerrarComentarios(c: List<Pair<String, List<Int>>>) {
            for ((_, ids) in c) for (id in ids) {
                s.append("""<w:commentRangeEnd w:id="$id"/><w:r><w:rPr><w:rStyle w:val="CommentReference"/></w:rPr><w:commentReference w:id="$id"/></w:r>""")
            }
        }

        /**
         * El texto de un renglón en tramos: cada cambio de estilo y cada borde de un comentario
         * parte un tramo. Un comentario cuya cita está en el renglón abraza esas letras; si no, el
         * renglón entero.
         */
        private fun texto(t: InlineText, comentarios: List<Pair<String, List<Int>>>, tachado: Boolean = false) {
            val txt = t.text
            val rangos = comentarios.map { (cita, ids) ->
                val i = if (cita.isEmpty()) -1 else txt.indexOf(cita)
                Triple(if (i < 0) 0 else i, if (i < 0) txt.length else i + cita.length, ids)
            }
            val cortes = sortedSetOf(0, txt.length)
            for (sp in t.spans) { cortes += sp.start.coerceIn(0, txt.length); cortes += sp.end.coerceIn(0, txt.length) }
            for ((a, b, _) in rangos) { cortes += a; cortes += b }
            val lista = cortes.toList()
            for (k in lista.indices) {
                val pos = lista[k]
                for ((a, _, ids) in rangos) if (a == pos) for (id in ids) s.append("""<w:commentRangeStart w:id="$id"/>""")
                if (k < lista.lastIndex) {
                    val fin = lista[k + 1]
                    if (fin > pos) tramo(txt.substring(pos, fin), t.spans.filter { it.start <= pos && it.end >= fin }, tachado)
                }
                for ((_, b, ids) in rangos) if (b == pos) for (id in ids) {
                    s.append("""<w:commentRangeEnd w:id="$id"/><w:r><w:rPr><w:rStyle w:val="CommentReference"/></w:rPr><w:commentReference w:id="$id"/></w:r>""")
                }
            }
        }

        private fun tramo(trozo: String, spans: List<InlineSpan>, tachado: Boolean) {
            val enlace = spans.firstOrNull { it.kind == SpanKind.LINK }?.url
            val web = enlace != null && (enlace.startsWith("http://") || enlace.startsWith("https://") || enlace.startsWith("mailto:"))
            val rPr = StringBuilder()
            if (spans.any { it.kind == SpanKind.CODE }) rPr.append("""<w:rStyle w:val="CodigoEnLinea"/>""")
            else if (web) rPr.append("""<w:rStyle w:val="Hyperlink"/>""")
            if (spans.any { it.kind == SpanKind.BOLD }) rPr.append("<w:b/><w:bCs/>")
            if (spans.any { it.kind == SpanKind.ITALIC }) rPr.append("<w:i/><w:iCs/>")
            if (tachado || spans.any { it.kind == SpanKind.STRIKE }) rPr.append("<w:strike/>")
            val run = "<w:r>" + (if (rPr.isNotEmpty()) "<w:rPr>$rPr</w:rPr>" else "") + """<w:t xml:space="preserve">${esc(trozo)}</w:t></w:r>"""
            if (web) {
                val id = rid()
                enlaces += id to enlace!!
                s.append("""<w:hyperlink r:id="$id" w:history="1">$run</w:hyperlink>""")
            } else s.append(run)
        }

        private fun medio(m: MarkdownBlock.Medio, comentarios: List<Pair<String, List<Int>>>) {
            val (alt, ancho) = Incrustados.partirAlt(m.alt)
            val datos = if (m.clase == ClaseDeMedio.IMAGEN) runCatching { n.imagen(m.ruta) }.getOrNull() else null
            val formato = datos?.let { formatoDe(it) }
            val tam = datos?.let { tamanoDe(it) }
            if (datos == null || formato == null || tam == null) {
                // Sin la foto (o un adjunto que no es foto): su nombre, con su clip, en su sitio.
                parrafo {
                    abrirComentarios(comentarios)
                    val nombre = alt.ifBlank { m.ruta.substringAfterLast('/') }
                    val aviso = if (m.clase == ClaseDeMedio.IMAGEN) " (la foto no está en este aparato)" else ""
                    s.append("""<w:r><w:t xml:space="preserve">📎 ${esc(nombre)}$aviso</w:t></w:r>""")
                    cerrarComentarios(comentarios)
                }
                return
            }
            val id = rid()
            val nombre = "imagen${medios.size + 1}.${formato.first}"
            medios += Medio(nombre, id, datos, formato.first, formato.second)
            val (px, py) = tam
            val anchoNatural = px * EMU_POR_PX
            val cx = if (ancho != null) (ANCHO_TEXTO_EMU * ancho.coerceAtMost(720) / 720) else anchoNatural.coerceAtMost(ANCHO_TEXTO_EMU)
            val cy = if (px > 0) cx * py / px else cx
            val d = docPr++
            parrafo(extraPPr = """<w:jc w:val="center"/>""") {
                abrirComentarios(comentarios)
                s.append("""<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$d" name="Imagen $d" descr="${esc(alt)}"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$d" name="$nombre"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="$id"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>""")
                cerrarComentarios(comentarios)
            }
            if (alt.isNotBlank() && !alt.startsWith("vivo-")) parrafo("Pie", """<w:jc w:val="center"/>""") {
                s.append("""<w:r><w:t xml:space="preserve">${esc(alt)}</w:t></w:r>""")
            }
        }

        private fun hexDe(c: Int) = String.format(java.util.Locale.ROOT, "%06X", c and 0xFFFFFF)

        private fun tabla(t: MarkdownBlock.Tabla, comentarios: List<Pair<String, List<Int>>>) {
            val rejilla = Tablas.rejilla(t)
            val columnas = t.columnas.coerceAtLeast(1)
            // Anchos en proporción a lo que lleva cada columna (como el PC), sobre 9071 twips.
            val pesos = IntArray(columnas) { 1 }
            rejilla.forEach { fila -> fila.forEachIndexed { c, h -> if (h != null && h.esElAncla && h.celda.anchoEnColumnas == 1) pesos[c] = maxOf(pesos[c], h.celda.contenido.text.length.coerceIn(3, 40)) } }
            val total = pesos.sum().coerceAtLeast(1)
            val anchos = pesos.map { 9071 * it / total }
            if (t.titulo.text.isNotBlank()) parrafo("Pie") { s.append("""<w:r><w:t xml:space="preserve">${esc(t.titulo.text)}</w:t></w:r>""") }
            s.append("""<w:tbl><w:tblPr><w:tblStyle w:val="TablaNota"/><w:tblW w:w="0" w:type="auto"/><w:tblLook w:val="04A0" w:firstRow="1" w:lastRow="0" w:firstColumn="0" w:lastColumn="0" w:noHBand="0" w:noVBand="1"/></w:tblPr><w:tblGrid>""")
            anchos.forEach { s.append("""<w:gridCol w:w="$it"/>""") }
            s.append("</w:tblGrid>")
            var primeraCelda = true
            rejilla.forEachIndexed { f, fila ->
                val esCabecera = fila.isNotEmpty() && fila.all { it?.celda?.cabecera == true } && f == 0
                s.append("<w:tr>")
                if (esCabecera) s.append("<w:trPr><w:tblHeader/></w:trPr>")
                var c = 0
                while (c < columnas) {
                    val h = fila.getOrNull(c)
                    if (h == null) {
                        s.append("""<w:tc><w:tcPr><w:tcW w:w="${anchos[c]}" w:type="dxa"/></w:tcPr><w:p/></w:tc>"""); c++; continue
                    }
                    val celda = h.celda
                    val span = celda.anchoEnColumnas.coerceIn(1, columnas - c)
                    val ancho = (c until c + span).sumOf { anchos[it] }
                    val tapadaPorArriba = !h.esElAncla && h.filaDelAncla != f
                    s.append("<w:tc><w:tcPr>")
                    s.append("""<w:tcW w:w="$ancho" w:type="dxa"/>""")
                    if (span > 1) s.append("""<w:gridSpan w:val="$span"/>""")
                    if (celda.altoEnFilas > 1) s.append(if (tapadaPorArriba) "<w:vMerge/>" else """<w:vMerge w:val="restart"/>""")
                    celda.fondo?.let { s.append("""<w:shd w:val="clear" w:color="auto" w:fill="${hexDe(it)}"/>""") }
                    when (celda.altura) {
                        AlturaEnCelda.MEDIO -> s.append("""<w:vAlign w:val="center"/>""")
                        AlturaEnCelda.ABAJO -> s.append("""<w:vAlign w:val="bottom"/>""")
                        else -> {}
                    }
                    s.append("</w:tcPr>")
                    val jc = when (celda.alineacion) { Alineacion.CENTRO -> """<w:jc w:val="center"/>"""; Alineacion.DERECHA -> """<w:jc w:val="right"/>"""; else -> "" }
                    s.append("<w:p>")
                    if (jc.isNotEmpty()) s.append("<w:pPr>$jc</w:pPr>")
                    if (!tapadaPorArriba) {
                        if (primeraCelda) abrirComentarios(comentarios)
                        val antes = s.length
                        texto(celda.contenido, emptyList())
                        if (celda.cabecera || celda.letra != null) {
                            // Negrita y color de letra a los tramos de esta celda.
                            val extra = (if (celda.cabecera) "<w:b/><w:bCs/>" else "") + (celda.letra?.let { """<w:color w:val="${hexDe(it)}"/>""" } ?: "")
                            val hecho = s.substring(antes)
                                .replace("<w:r><w:rPr>", "<w:r><w:rPr>$extra")
                                .replace("<w:r><w:t ", "<w:r><w:rPr>$extra</w:rPr><w:t ")
                            s.setLength(antes); s.append(hecho)
                        }
                        if (primeraCelda) { cerrarComentarios(comentarios); primeraCelda = false }
                    }
                    s.append("</w:p></w:tc>")
                    c += span
                }
                s.append("</w:tr>")
            }
            s.append("</w:tbl>")
            parrafo {}
        }
    }

    // --------------------------------------------------------------- fotos

    /** Extensión y tipo por los bytes; null si no es una foto que Word entienda. */
    fun formatoDe(b: ByteArray): Pair<String, String>? = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "png" to "image/png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpeg" to "image/jpeg"
        b.size > 6 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "gif" to "image/gif"
        b.size > 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "bmp" to "image/bmp"
        else -> null
    }

    private fun u16be(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u16le(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32be(b: ByteArray, i: Int) = (u16be(b, i).toLong() shl 16) or u16be(b, i + 2).toLong()

    /** Ancho y alto en píxeles, leídos de la cabecera. */
    fun tamanoDe(b: ByteArray): Pair<Long, Long>? = runCatching {
        when (formatoDe(b)?.first) {
            "png" -> u32be(b, 16) to u32be(b, 20)
            "gif" -> u16le(b, 6).toLong() to u16le(b, 8).toLong()
            "bmp" -> ((b[18].toLong() and 0xFF) or ((b[19].toLong() and 0xFF) shl 8)) to kotlin.math.abs(
                (b[22].toInt() and 0xFF) or ((b[23].toInt() and 0xFF) shl 8) or ((b[24].toInt() and 0xFF) shl 16) or (b[25].toInt() shl 24)
            ).toLong()
            "jpeg" -> {
                var i = 2
                var r: Pair<Long, Long>? = null
                while (i + 9 < b.size) {
                    if (b[i] != 0xFF.toByte()) { i++; continue }
                    val marca = b[i + 1].toInt() and 0xFF
                    val largo = u16be(b, i + 2)
                    if (marca in 0xC0..0xCF && marca != 0xC4 && marca != 0xC8 && marca != 0xCC) {
                        r = u16be(b, i + 7).toLong() to u16be(b, i + 5).toLong(); break
                    }
                    i += 2 + largo
                }
                r
            }
            else -> null
        }
    }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 }

    // ------------------------------------------------- partes fijas (del PC)

    private fun tipos(medios: List<Medio>, comentarios: Boolean): String {
        val s = StringBuilder("""$CABECERA<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
        val vistas = HashSet<String>()
        for (m in medios) if (vistas.add(m.extension)) s.append("""<Default Extension="${m.extension}" ContentType="${m.mime}"/>""")
        val partes = arrayListOf(
            "/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
            "/word/styles.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml",
            "/word/numbering.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml",
            "/word/settings.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml",
            "/word/fontTable.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.fontTable+xml",
            "/docProps/core.xml" to "application/vnd.openxmlformats-package.core-properties+xml",
            "/docProps/app.xml" to "application/vnd.openxmlformats-officedocument.extended-properties+xml"
        )
        if (comentarios) {
            partes += "/word/comments.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"
            partes += "/word/commentsExtended.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtended+xml"
        }
        for ((p, t) in partes) s.append("""<Override PartName="$p" ContentType="$t"/>""")
        return s.append("</Types>").toString()
    }

    private const val RELS_RAIZ = CABECERA +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
        """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
        """<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>""" +
        "</Relationships>"

    private fun relsDocumento(medios: List<Medio>, enlaces: List<Pair<String, String>>, comentarios: Boolean): String {
        val s = StringBuilder("""$CABECERA<Relationships xmlns="$RELS">""")
        val fijas = arrayListOf("rId1" to ("styles" to "styles.xml"), "rId2" to ("numbering" to "numbering.xml"), "rId3" to ("settings" to "settings.xml"), "rId4" to ("fontTable" to "fontTable.xml"))
        if (comentarios) fijas += "rId5" to ("comments" to "comments.xml")
        for ((id, td) in fijas) s.append("""<Relationship Id="$id" Type="$R/${td.first}" Target="${td.second}"/>""")
        if (comentarios) s.append("""<Relationship Id="rId6" Type="http://schemas.microsoft.com/office/2011/relationships/commentsExtended" Target="commentsExtended.xml"/>""")
        for (m in medios) s.append("""<Relationship Id="${m.rid}" Type="$R/image" Target="media/${esc(m.nombre)}"/>""")
        for ((rid, url) in enlaces) s.append("""<Relationship Id="$rid" Type="$R/hyperlink" Target="${esc(url.trim())}" TargetMode="External"/>""")
        return s.append("</Relationships>").toString()
    }

    /** Milisegundos UTC como fecha W3C (`2026-10-01T09:30:00Z`). */
    fun fecha(ms: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms.coerceAtLeast(0)))
    }

    private fun core(titulo: String, autor: String, ms: Long): String {
        val a = esc(autor.trim().ifEmpty { "PixPin" })
        val f = fecha(ms)
        return CABECERA + """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            "<dc:title>${esc(titulo)}</dc:title><dc:creator>$a</dc:creator><cp:lastModifiedBy>$a</cp:lastModifiedBy>" +
            """<dcterms:created xsi:type="dcterms:W3CDTF">$f</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">$f</dcterms:modified></cp:coreProperties>"""
    }

    private const val APP = CABECERA + """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>PixPin</Application></Properties>"""

    private const val AJUSTES = CABECERA + """<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:zoom w:percent="100"/><w:defaultTabStop w:val="708"/><w:characterSpacingControl w:val="doNotCompress"/><w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>"""

    private fun sustituta(nombre: String): Triple<String?, String, String> = when (nombre) {
        "Work Sans", "Nunito" -> Triple("Segoe UI", "swiss", "020B0502040204020203")
        "Lilita One" -> Triple("Segoe UI Black", "swiss", "020B0A02040204020203")
        "Fraunces" -> Triple("Georgia", "roman", "02040502050405020303")
        "Comic Shanns" -> Triple("Comic Sans MS", "script", "030F0702030302020204")
        "Excalifont", "Caveat" -> Triple("Segoe Print", "script", "02000600000000000000")
        "Courier New" -> Triple(null, "modern", "02070309020205020404")
        "Consolas" -> Triple(null, "modern", "020B0609020204030204")
        "Cambria Math" -> Triple(null, "roman", "02040503050406030204")
        "Segoe UI Symbol" -> Triple(null, "swiss", "020B0502040204020203")
        "Arial" -> Triple(null, "swiss", "020B0604020202020204")
        else -> Triple(null, "auto", "")
    }

    private fun letras(l: Letra): String {
        val s = StringBuilder("""$CABECERA<w:fonts xmlns:w="$W">""")
        for (n in listOf(l.cuerpo, l.titulos, CODIGO, "Cambria Math", "Segoe UI Symbol", "Arial").distinct()) {
            val (alt, familia, panose) = sustituta(n)
            s.append("""<w:font w:name="${esc(n)}">""")
            if (alt != null) s.append("""<w:altName w:val="$alt"/>""")
            if (panose.isNotEmpty()) s.append("""<w:panose1 w:val="$panose"/>""")
            val paso = if (familia == "modern") "fixed" else "variable"
            s.append("""<w:charset w:val="00"/><w:family w:val="$familia"/><w:pitch w:val="$paso"/></w:font>""")
        }
        return s.append("</w:fonts>").toString()
    }

    private fun caras(n: String): String { val e = esc(n); return """<w:rFonts w:ascii="$e" w:hAnsi="$e" w:eastAsia="$e" w:cs="$e"/>""" }

    private fun estilos(l: Letra): String {
        val cuerpo = (l.px.coerceIn(8, 48) * 3 / 2).coerceAtLeast(12)
        fun x(f: Float) = Math.round(cuerpo * f).coerceAtLeast(12)
        val s = StringBuilder("""$CABECERA<w:styles xmlns:w="$W"><w:docDefaults><w:rPrDefault><w:rPr>${caras(l.cuerpo)}<w:sz w:val="$cuerpo"/><w:szCs w:val="$cuerpo"/><w:lang w:val="es-ES" w:eastAsia="en-US" w:bidi="ar-SA"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""")
        s.append("""<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/><w:rPr><w:color w:val="1F1F1F"/></w:rPr></w:style>""")
        s.append("""<w:style w:type="character" w:default="1" w:styleId="DefaultParagraphFont"><w:name w:val="Default Paragraph Font"/><w:uiPriority w:val="1"/><w:semiHidden/><w:unhideWhenUsed/></w:style>""")
        val tamanos = floatArrayOf(1.75f, 1.4f, 1.2f, 1.05f, 1.0f, 0.95f)
        val antes = intArrayOf(360, 280, 240, 200, 200, 200)
        for (n in 1..6) {
            s.append("""<w:style w:type="paragraph" w:styleId="Heading$n"><w:name w:val="heading $n"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:uiPriority w:val="9"/><w:qFormat/><w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="${antes[n - 1]}" w:after="120"/><w:outlineLvl w:val="${n - 1}"/></w:pPr><w:rPr>${caras(l.titulos)}<w:b/><w:bCs/><w:color w:val="111111"/><w:sz w:val="${x(tamanos[n - 1])}"/><w:szCs w:val="${x(tamanos[n - 1])}"/></w:rPr></w:style>""")
        }
        s.append("""<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:uiPriority w:val="34"/><w:qFormat/><w:pPr><w:spacing w:after="60"/><w:ind w:left="720"/><w:contextualSpacing/></w:pPr></w:style>""")
        s.append("""<w:style w:type="paragraph" w:styleId="Cita"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:uiPriority w:val="29"/><w:qFormat/><w:pPr><w:pBdr><w:left w:val="single" w:sz="18" w:space="10" w:color="C9C9C9"/></w:pBdr><w:spacing w:after="60"/><w:ind w:left="284"/></w:pPr><w:rPr><w:i/><w:iCs/><w:color w:val="555555"/></w:rPr></w:style>""")
        s.append("""<w:style w:type="paragraph" w:styleId="CodigoBloque"><w:name w:val="Codigo"/><w:basedOn w:val="Normal"/><w:uiPriority w:val="39"/><w:qFormat/><w:pPr><w:pBdr><w:top w:val="single" w:sz="4" w:space="4" w:color="E1E1E1"/><w:left w:val="single" w:sz="4" w:space="4" w:color="E1E1E1"/><w:bottom w:val="single" w:sz="4" w:space="4" w:color="E1E1E1"/><w:right w:val="single" w:sz="4" w:space="4" w:color="E1E1E1"/></w:pBdr><w:shd w:val="clear" w:color="auto" w:fill="F5F5F5"/><w:spacing w:after="160" w:line="240" w:lineRule="auto"/><w:ind w:left="113" w:right="113"/><w:contextualSpacing/></w:pPr><w:rPr>${caras(CODIGO)}<w:noProof/><w:color w:val="24292F"/><w:sz w:val="${x(0.85f)}"/><w:szCs w:val="${x(0.85f)}"/></w:rPr></w:style>""")
        s.append("""<w:style w:type="paragraph" w:styleId="Pie"><w:name w:val="caption"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:uiPriority w:val="35"/><w:qFormat/><w:pPr><w:spacing w:after="200"/></w:pPr><w:rPr><w:i/><w:iCs/><w:color w:val="666666"/><w:sz w:val="${x(0.85f)}"/><w:szCs w:val="${x(0.85f)}"/></w:rPr></w:style>""")
        s.append("""<w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/><w:basedOn w:val="DefaultParagraphFont"/><w:uiPriority w:val="99"/><w:unhideWhenUsed/><w:rPr><w:color w:val="0563C1"/><w:u w:val="single"/></w:rPr></w:style>""")
        s.append("""<w:style w:type="character" w:styleId="CodigoEnLinea"><w:name w:val="Codigo en linea"/><w:basedOn w:val="DefaultParagraphFont"/><w:uiPriority w:val="39"/><w:qFormat/><w:rPr>${caras(CODIGO)}<w:noProof/><w:color w:val="C7254E"/><w:sz w:val="${x(0.9f)}"/><w:szCs w:val="${x(0.9f)}"/><w:shd w:val="clear" w:color="auto" w:fill="F2F2F2"/></w:rPr></w:style>""")
        s.append("""<w:style w:type="character" w:styleId="CommentReference"><w:name w:val="annotation reference"/><w:basedOn w:val="DefaultParagraphFont"/><w:uiPriority w:val="99"/><w:semiHidden/><w:unhideWhenUsed/><w:rPr><w:sz w:val="16"/><w:szCs w:val="16"/></w:rPr></w:style>""")
        s.append("""<w:style w:type="paragraph" w:styleId="CommentText"><w:name w:val="annotation text"/><w:basedOn w:val="Normal"/><w:uiPriority w:val="99"/><w:unhideWhenUsed/><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:rPr><w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr></w:style>""")
        s.append("""<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:uiPriority w:val="99"/><w:semiHidden/><w:unhideWhenUsed/><w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>""")
        s.append("""<w:style w:type="table" w:styleId="TablaNota"><w:name w:val="Tabla de nota"/><w:basedOn w:val="TableNormal"/><w:uiPriority w:val="59"/><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/><w:left w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/><w:right w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/></w:tblBorders><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="100" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>""")
        return s.append("</w:styles>").toString()
    }

    private fun numeracion(rachas: List<Int>): String {
        val s = StringBuilder("""$CABECERA<w:numbering xmlns:w="$W">""")
        val vinetas = arrayOf("•", "◦", "▪")
        s.append("""<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/>""")
        for (i in 0 until 9) s.append("""<w:lvl w:ilvl="$i"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="${vinetas[i % 3]}"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="${720 * (i + 1)}" w:hanging="360"/></w:pPr><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Arial" w:hint="default"/></w:rPr></w:lvl>""")
        s.append("</w:abstractNum>")
        val formatos = arrayOf("decimal", "lowerLetter", "lowerRoman")
        s.append("""<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/>""")
        for (i in 0 until 9) s.append("""<w:lvl w:ilvl="$i"><w:start w:val="1"/><w:numFmt w:val="${formatos[i % 3]}"/><w:lvlText w:val="%${i + 1}."/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="${720 * (i + 1)}" w:hanging="360"/></w:pPr></w:lvl>""")
        s.append("</w:abstractNum>")
        s.append("""<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>""")
        rachas.forEachIndexed { k, inicio ->
            s.append("""<w:num w:numId="${k + 2}"><w:abstractNumId w:val="1"/>""")
            for (i in 0 until 9) s.append("""<w:lvlOverride w:ilvl="$i"><w:startOverride w:val="${if (i == 0) inicio else 1}"/></w:lvlOverride>""")
            s.append("</w:num>")
        }
        return s.append("</w:numbering>").toString()
    }

    private fun iniciales(n: String): String =
        n.split(Regex("\\s+")).mapNotNull { it.firstOrNull() }.take(3).joinToString("").ifEmpty { "P" }

    private fun paraId(id: Int) = String.format(java.util.Locale.ROOT, "%08X", 0x1000_0000 + id)

    private fun comentarios(hilos: List<ComentarioW>): Pair<String, String> {
        val c = StringBuilder("""$CABECERA<w:comments xmlns:w="$W" xmlns:w14="$W14" xmlns:mc="$MC" mc:Ignorable="w14">""")
        val ex = StringBuilder("""$CABECERA<w15:commentsEx xmlns:w15="$W15" xmlns:mc="$MC" mc:Ignorable="w15">""")
        for (h in hilos) {
            val autor = h.autor.trim().ifEmpty { "PixPin" }
            c.append("""<w:comment w:id="${h.id}" w:author="${esc(autor)}" w:date="${fecha(h.cuando)}" w:initials="${esc(iniciales(autor))}">""")
            val renglones = h.texto.split('\n').map { it.trimEnd('\r') }
            renglones.forEachIndexed { i, r ->
                val pid = if (i == renglones.lastIndex) paraId(h.id) else String.format(java.util.Locale.ROOT, "%08X", 0x2000_0000 + h.id * 64 + i % 64)
                c.append("""<w:p w14:paraId="$pid" w14:textId="77777777"><w:pPr><w:pStyle w:val="CommentText"/></w:pPr>""")
                if (i == 0) c.append("""<w:r><w:rPr><w:rStyle w:val="CommentReference"/></w:rPr><w:annotationRef/></w:r>""")
                if (r.isNotEmpty()) c.append("""<w:r><w:t xml:space="preserve">${esc(r)}</w:t></w:r>""")
                c.append("</w:p>")
            }
            c.append("</w:comment>")
            val padre = h.padre?.let { """ w15:paraIdParent="${paraId(it)}"""" } ?: ""
            ex.append("""<w15:commentEx w15:paraId="${paraId(h.id)}"$padre w15:done="${if (h.resuelto) 1 else 0}"/>""")
        }
        c.append("</w:comments>")
        ex.append("</w15:commentsEx>")
        return c.toString() to ex.toString()
    }
}
