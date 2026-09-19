package com.forge.pixpin.motor

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * **Un Word (`.docx`) convertido en una página que se lee en el teléfono.**
 *
 * Un `.docx` de la conversación salía con «abrir con» a otra aplicación, y en medio teléfono
 * no hay ninguna que lo abra. Aquí no se pretende ser Word: se saca **lo que dice** el
 * documento —párrafos, títulos, negritas, listas, tablas e imágenes— y se deja en un HTML de
 * un solo archivo que enseña [com.forge.pixpin.ui.VisorHtmlActivity]. Sin maquetación de
 * página: ni márgenes, ni columnas, ni encabezados y pies. Lo que se quiere es leerlo.
 *
 * **Sin ninguna librería** (norma del proyecto): un `.docx` es un ZIP con XML dentro, igual que
 * el `.pptx` de [Diapositivas] y el `.xlsx` de [ImportarHojas], y se lee con lo mismo que
 * ellos —`ZipFile` y el analizador de XML de la plataforma—, que está tanto en Android como en
 * la JVM de las pruebas. Y sin tocar Android: las imágenes van en base64 con `java.util.Base64`.
 *
 * La página sale **sin guion**: todo el texto va escapado y el visor la abre con JavaScript
 * apagado, que un documento que llega de otro no tiene por qué ejecutar nada.
 */
object DocxAHtml {

    /** Lo que se le dice al usuario cuando el archivo no es lo que parece. */
    class NoSeLee(mensaje: String) : Exception(mensaje)

    /** Una imagen mayor que esto no se incrusta: en base64 engorda un tercio y el `WebView` la sufre. */
    private const val TOPE_DE_IMAGEN = 12L * 1024 * 1024

    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** Si por el nombre (o la ruta) es un Word de los que se leen: `.docx`, y sus primos con macros o de plantilla. */
    fun esDocx(nombre: String?): Boolean =
        when (nombre.orEmpty().substringAfterLast('.', "").lowercase()) {
            "docx", "docm", "dotx" -> true
            else -> false
        }

    /**
     * El HTML entero de [archivo]. [titulo] va al `<title>`; si viene vacío, el nombre del archivo.
     * Lanza [NoSeLee] si no es un `.docx` de verdad.
     */
    fun convertir(archivo: File, titulo: String = ""): String {
        if (archivo.name.substringAfterLast('.', "").lowercase() in setOf("doc", "dot", "rtf")) {
            throw NoSeLee("Solo se leen los documentos .docx: guarda este como .docx o PDF")
        }
        val zip = try { ZipFile(archivo) } catch (e: Exception) {
            // Un `.doc` antiguo con la extensión cambiada empieza por la firma de OLE, no por la del ZIP.
            val viejo = runCatching {
                archivo.inputStream().use { s -> val b = ByteArray(4); s.read(b) == 4 && b[0] == 0xD0.toByte() && b[1] == 0xCF.toByte() }
            }.getOrDefault(false)
            throw NoSeLee(
                if (viejo) "Es un Word antiguo (.doc): solo se leen los .docx"
                else "El archivo no es un documento de Word (.docx)"
            )
        }
        return zip.use { Lector(it).html(titulo.ifBlank { archivo.name }) }
    }

    // ------------------------------------------------------------------------------------------

    private class Lector(val zip: ZipFile) {

        private val fabrica = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Nada de entidades externas: un archivo que llega por el chat no manda en el disco.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }

        private val sale = StringBuilder()

        /** id de relación → ruta dentro del ZIP. Solo las de dentro: un enlace externo no es una imagen. */
        private var relaciones: Map<String, String> = emptyMap()

        private fun xml(ruta: String): Element? {
            val e = zip.getEntry(ruta) ?: return null
            val bytes = zip.getInputStream(e).use { it.readBytes() }
            return runCatching {
                fabrica.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
            }.getOrNull()
        }

        fun html(titulo: String): String {
            val raiz = xml("word/document.xml")
                ?: throw NoSeLee("El archivo no es un documento de Word (.docx)")
            relaciones = leerRelaciones()
            val cuerpo = raiz.hijos().firstOrNull { it.localName == "body" } ?: raiz
            bloques(cuerpo)
            if (sale.isBlank()) sale.append("<p class=\"nota\">El documento no tiene texto.</p>")
            return "<!DOCTYPE html>\n<html lang=\"es\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>" + escapar(titulo) + "</title><style>" + ESTILO + "</style></head><body><main>\n" +
                sale + "</main></body></html>\n"
        }

        private fun leerRelaciones(): Map<String, String> {
            val rels = xml("word/_rels/document.xml.rels") ?: return emptyMap()
            val m = HashMap<String, String>()
            for (r in rels.hijos()) {
                if (r.localName != "Relationship") continue
                if (r.getAttribute("TargetMode") == "External") continue
                val destino = r.getAttribute("Target")
                // «media/image1.png» cuelga de `word/`; «/word/media/…» ya viene entero.
                m[r.getAttribute("Id")] = if (destino.startsWith("/")) destino.drop(1) else "word/$destino"
            }
            return m
        }

        /** Lo que hay en un cuerpo, una celda o un envoltorio: párrafos y tablas. */
        private fun bloques(padre: Element) {
            for (h in padre.hijos()) when (h.localName) {
                "p" -> parrafo(h)
                "tbl" -> tabla(h)
                // Controles de contenido, cambios con seguimiento: el contenido está dentro.
                "sdt", "sdtContent", "ins", "smartTag", "customXml" -> bloques(h)
            }
        }

        private fun parrafo(p: Element) {
            val pPr = p.hijo("pPr")
            val estilo = pPr?.hijo("pStyle")?.valor().orEmpty()
            val nivel = nivelDeTitulo(estilo)
            // Con numeración es una lista. Se queda en un punto delante: los números de Word
            // viven en otra parte (`numbering.xml`) y aquí lo que importa es que se lea como lista.
            val enLista = nivel == 0 && (pPr?.hijo("numPr") != null)
            val dentro = StringBuilder()
            enLinea(p, dentro)
            if (dentro.isEmpty()) {
                // Un párrafo vacío en Word es una línea en blanco puesta a propósito.
                if (nivel == 0) sale.append("<p>&nbsp;</p>\n")
                return
            }
            val alineado = when (pPr?.hijo("jc")?.valor()) {
                "center" -> " style=\"text-align:center\""
                "right", "end" -> " style=\"text-align:right\""
                else -> ""
            }
            when {
                nivel > 0 -> sale.append("<h").append(nivel).append(alineado).append('>').append(dentro)
                    .append("</h").append(nivel).append(">\n")
                enLista -> sale.append("<p class=\"lista\">• ").append(dentro).append("</p>\n")
                else -> sale.append("<p").append(alineado).append('>').append(dentro).append("</p>\n")
            }
        }

        /** «Heading1», «Ttulo1» (así escribe Word «Título 1» en español), «Title»… → 1 a 6; 0 si no es título. */
        private fun nivelDeTitulo(estilo: String): Int {
            val e = estilo.lowercase()
            if (e == "title" || e == "ttulo" || e == "titulo" || e == "puesto") return 1
            if (e == "subtitle" || e == "subttulo" || e == "subtitulo") return 2
            for (raiz in listOf("heading", "ttulo", "titulo", "encabezado")) {
                if (e.startsWith(raiz)) {
                    val n = e.removePrefix(raiz).toIntOrNull() ?: continue
                    return n.coerceIn(1, 6)
                }
            }
            return 0
        }

        /** Lo que va dentro de un párrafo: tramos, enlaces y lo que los envuelve. */
        private fun enLinea(padre: Element, a: StringBuilder) {
            for (h in padre.hijos()) when (h.localName) {
                "r" -> tramo(h, a)
                // Del enlace se queda el texto: la dirección está en las relaciones externas y
                // la página se enseña sin salir a la red.
                "hyperlink" -> { a.append("<span class=\"enlace\">"); enLinea(h, a); a.append("</span>") }
                "ins", "smartTag", "sdt", "sdtContent", "fldSimple", "customXml" -> enLinea(h, a)
            }
        }

        private fun tramo(r: Element, a: StringBuilder) {
            val rPr = r.hijo("rPr")
            val abre = StringBuilder()
            val cierra = StringBuilder()
            fun marca(etiqueta: String) { abre.append('<').append(etiqueta).append('>'); cierra.insert(0, "</$etiqueta>") }
            if (rPr != null) {
                if (encendido(rPr.hijo("b"))) marca("b")
                if (encendido(rPr.hijo("i"))) marca("i")
                val u = rPr.hijo("u")
                if (u != null && u.valor() != "none" && encendido(u)) marca("u")
                if (encendido(rPr.hijo("strike")) || encendido(rPr.hijo("dstrike"))) marca("s")
                when (rPr.hijo("vertAlign")?.valor()) { "superscript" -> marca("sup"); "subscript" -> marca("sub") }
            }
            val dentro = StringBuilder()
            for (h in r.hijos()) when (h.localName) {
                "t" -> dentro.append(escapar(h.textContent.orEmpty()))
                "br", "cr" -> dentro.append("<br>")
                "tab" -> dentro.append("&emsp;")
                "noBreakHyphen" -> dentro.append('‑')
                "drawing", "pict", "object" -> imagenes(h, dentro)
                // Word envuelve en esto lo moderno con su alternativa antigua: basta una de las dos.
                "AlternateContent" -> (h.hijo("Choice") ?: h.hijo("Fallback"))?.let { imagenes(it, dentro) }
            }
            if (dentro.isEmpty()) return
            a.append(abre).append(dentro).append(cierra)
        }

        /** `w:b` a secas es «sí»; con `w:val="0"` o `"false"` es «no» (así se apaga lo que el estilo enciende). */
        private fun encendido(e: Element?): Boolean {
            if (e == null) return false
            return when (e.valor().lowercase()) { "0", "false", "off" -> false; else -> true }
        }

        /** Todas las imágenes que cuelguen de [e]: `a:blip r:embed` en lo moderno, `v:imagedata r:id` en lo antiguo. */
        private fun imagenes(e: Element, a: StringBuilder) {
            val id = when (e.localName) {
                "blip" -> e.getAttributeNS(NS_R, "embed")
                "imagedata" -> e.getAttributeNS(NS_R, "id")
                else -> ""
            }
            if (id.isNotEmpty()) imagen(id, a)
            for (h in e.hijos()) imagenes(h, a)
        }

        private fun imagen(id: String, a: StringBuilder) {
            val ruta = relaciones[id] ?: return
            val mime = when (ruta.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg", "jpe" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "svg" -> "image/svg+xml"
                // EMF y WMF son dibujos de Windows: ningún navegador los pinta.
                else -> { a.append("<span class=\"nota\">[imagen que no se puede mostrar]</span>"); return }
            }
            val entrada = zip.getEntry(ruta) ?: return
            if (entrada.size > TOPE_DE_IMAGEN) {
                a.append("<span class=\"nota\">[imagen demasiado grande]</span>"); return
            }
            val bytes = runCatching { zip.getInputStream(entrada).use { it.readBytes() } }.getOrNull() ?: return
            a.append("<img alt=\"\" src=\"data:").append(mime).append(";base64,")
                .append(Base64.getEncoder().encodeToString(bytes)).append("\">")
        }

        private fun tabla(t: Element) {
            sale.append("<div class=\"tabla\"><table>\n")
            for (fila in t.hijos()) {
                if (fila.localName != "tr") continue
                sale.append("<tr>")
                for (celda in fila.hijos()) {
                    // Una fila también puede traer sus celdas dentro de un control de contenido.
                    val celdas = if (celda.localName == "tc") listOf(celda)
                    else if (celda.localName == "sdt") celda.hijo("sdtContent")?.hijos()?.filter { it.localName == "tc" }.orEmpty()
                    else emptyList()
                    for (c in celdas) {
                        val tcPr = c.hijo("tcPr")
                        // La continuación de una celda unida hacia abajo no trae nada suyo.
                        val vMerge = tcPr?.hijo("vMerge")
                        if (vMerge != null && vMerge.valor() != "restart") { sale.append("<td></td>"); continue }
                        val ancho = tcPr?.hijo("gridSpan")?.valor()?.toIntOrNull() ?: 1
                        sale.append(if (ancho > 1) "<td colspan=\"$ancho\">" else "<td>")
                        bloques(c)
                        sale.append("</td>")
                    }
                }
                sale.append("</tr>\n")
            }
            sale.append("</table></div>\n")
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun Element.hijos(): List<Element> {
        val lista = ArrayList<Element>()
        var n = firstChild
        while (n != null) { if (n is Element) lista.add(n); n = n.nextSibling }
        return lista
    }

    private fun Element.hijo(nombre: String): Element? {
        var n = firstChild
        while (n != null) { if (n is Element && n.localName == nombre) return n; n = n.nextSibling }
        return null
    }

    /** El `w:val`, venga con espacio de nombres o sin él; vacío si no está. */
    private fun Element.valor(): String {
        val a = attributes ?: return ""
        for (i in 0 until a.length) {
            val n = a.item(i)
            if ((n.localName ?: n.nodeName.substringAfterLast(':')) == "val") return n.nodeValue.orEmpty()
        }
        return ""
    }

    private fun escapar(s: String): String {
        val b = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '&' -> b.append("&amp;")
            '<' -> b.append("&lt;")
            '>' -> b.append("&gt;")
            '"' -> b.append("&quot;")
            '\'' -> b.append("&#39;")
            else -> b.append(c)
        }
        return b.toString()
    }

    /** Pensado para un teléfono: columna estrecha, letra del sistema, y los colores del modo noche si toca. */
    private const val ESTILO =
        "html{-webkit-text-size-adjust:100%}" +
            "body{margin:0;background:#fff;color:#1b1b1f;font:16px/1.55 system-ui,-apple-system,Roboto,sans-serif}" +
            "main{max-width:46em;margin:0 auto;padding:18px 16px 48px;overflow-wrap:break-word}" +
            "h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.2em 0 .5em}" +
            "h1{font-size:1.7em}h2{font-size:1.4em}h3{font-size:1.2em}h4,h5,h6{font-size:1.05em}" +
            "p{margin:.55em 0}p.lista{margin:.25em 0 .25em 1.2em;text-indent:-1em}" +
            "img{max-width:100%;height:auto}" +
            ".tabla{overflow-x:auto;margin:.8em 0}" +
            "table{border-collapse:collapse}" +
            "td{border:1px solid #b9b9c2;padding:4px 8px;vertical-align:top}td p{margin:.2em 0}" +
            ".enlace{color:#2a5bd7;text-decoration:underline}" +
            ".nota{color:#77777f;font-style:italic}" +
            "@media (prefers-color-scheme: dark){body{background:#121316;color:#e4e2e6}" +
            "td{border-color:#4a4a52}.enlace{color:#9db7ff}.nota{color:#9a9aa2}}"
}
