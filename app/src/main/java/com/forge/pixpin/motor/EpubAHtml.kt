package com.forge.pixpin.motor

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * **Un libro (`.epub`) convertido en una página que se lee en el teléfono.**
 *
 * Hermano de [DocxAHtml], y por lo mismo: un `.epub` de la conversación salía con «abrir con» a
 * otra aplicación, y en medio teléfono no hay ninguna. Un EPUB ya *es* una web metida en un ZIP
 * —capítulos en XHTML, un índice (`.opf`) que dice en qué orden van—, así que aquí no se
 * traduce nada: se cosen los capítulos, en el orden del lomo (`spine`), en **una sola página**
 * (`pagina.html`) y se sacan a la carpeta las imágenes del libro (todas las del ZIP, y nada más que imágenes).
 *
 * **Las imágenes van fuera y no en base64**, al revés que en el Word: un libro ilustrado trae
 * cientos, y una página de 80 MB en base64 tumba el `WebView`. Se conservan sus rutas de dentro
 * del ZIP y los `src` de los capítulos se reescriben para que cuelguen de la raíz de la carpeta.
 *
 * **El CSS del libro se tira entero** (hojas, `<style>` y `style="…"`): está pensado para un
 * lector de tinta, y en un teléfono —y más en modo noche— es imprevisible: letra negra sobre
 * fondo negro, márgenes de medio ancho, tipografías que no vienen. Se pone el nuestro: una
 * columna que se lee, los colores del modo noche y reglas de impresión (A4, cada capítulo en
 * página nueva) para que «imprimir» dé un PDF decente.
 *
 * **Los capítulos no pasan por el analizador de XML.** Traen `DOCTYPE` y entidades con nombre
 * (`&nbsp;`), y el analizador que usamos —con el `DOCTYPE` prohibido, que es como tiene que
 * estar para un archivo que llega de otro— los rechaza. Se tratan como texto: se corta lo de
 * dentro del `<body>` y se rehace etiqueta por etiqueta, quitando guiones y manejadores `on…`.
 * El contenedor y el `.opf` sí son XML limpio y van por el DOM, como en [DocxAHtml].
 *
 * **Sin ninguna librería** y sin tocar Android: `ZipFile` y el XML de la plataforma.
 */
object EpubAHtml {

    /** Lo que se le dice al usuario cuando el archivo no es lo que parece. */
    class NoSeLee(mensaje: String) : Exception(mensaje)

    /** Nada suelto mayor que esto sale del ZIP: ni una imagen ni un capítulo. */
    private const val TOPE_DE_RECURSO = 25L * 1024 * 1024

    private const val NO_ES_EPUB = "El archivo no es un libro EPUB"

    private val IMAGENES = setOf("png", "jpg", "jpeg", "gif", "webp", "svg")

    /** Si por el nombre (o la ruta) es un libro de los que se leen. */
    fun esEpub(nombre: String?): Boolean =
        nombre.orEmpty().substringAfterLast('.', "").lowercase() == "epub"

    /**
     * Saca el libro a [carpeta] (que se **vacía** antes) y escribe `carpeta/pagina.html`; devuelve
     * ese archivo. [titulo] solo se usa si el libro no dice cómo se llama; si también viene vacío,
     * el nombre del archivo. Lanza [NoSeLee] si no es un EPUB de verdad o si viene con DRM.
     */
    fun convertir(archivo: File, carpeta: File, titulo: String = ""): File {
        val zip = try { ZipFile(archivo) } catch (e: Exception) { throw NoSeLee(NO_ES_EPUB) }
        return zip.use { Lector(it, carpeta).pagina(titulo.ifBlank { archivo.nameWithoutExtension }) }
    }

    // ------------------------------------------------------------------------------------------

    /** Un documento del lomo: su ruta dentro del ZIP y si es una imagen suelta (una portada) y no XHTML. */
    private class Hoja(val ruta: String, val esImagen: Boolean)

    private class Lector(val zip: ZipFile, val carpeta: File) {

        /** Ruta dentro del ZIP → número de capítulo, para que los enlaces entre capítulos sigan llevando a su sitio. */
        private val capitulos = HashMap<String, Int>()

        fun pagina(tituloDeFuera: String): File {
            val contenedor = xml("META-INF/container.xml")
                ?: throw NoSeLee("$NO_ES_EPUB: le falta META-INF/container.xml")
            val rutaOpf = contenedor.todos("rootfile")
                .map { descodificar(it.getAttribute("full-path")).trimStart('/') }
                .firstOrNull { it.isNotBlank() }
                ?: throw NoSeLee("$NO_ES_EPUB: no dice dónde está su índice")
            val opf = xml(rutaOpf) ?: throw NoSeLee("$NO_ES_EPUB: no se puede leer su índice ($rutaOpf)")
            val dirOpf = rutaOpf.substringBeforeLast('/', "")

            // El manifiesto: id → ruta entera dentro del ZIP y tipo.
            val rutas = HashMap<String, String>()
            val tipos = HashMap<String, String>()
            for (item in opf.hijo("manifest")?.hijos().orEmpty()) {
                if (item.nombre() != "item") continue
                val ruta = resolver(dirOpf, item.getAttribute("href")) ?: continue
                rutas[item.getAttribute("id")] = ruta
                tipos[item.getAttribute("id")] = item.getAttribute("media-type").lowercase()
            }

            // El lomo: el orden de lectura. Lo que no esté en el ZIP o no sea página, fuera.
            val hojas = ArrayList<Hoja>()
            for (ref in opf.hijo("spine")?.hijos().orEmpty()) {
                if (ref.nombre() != "itemref") continue
                val id = ref.getAttribute("idref")
                val ruta = rutas[id] ?: continue
                val entrada = zip.getEntry(ruta) ?: continue
                if (entrada.isDirectory) continue
                val ext = ruta.substringAfterLast('.', "").lowercase()
                val tipo = tipos[id].orEmpty()
                when {
                    ext in IMAGENES || tipo.startsWith("image/") -> hojas.add(Hoja(ruta, true))
                    tipo.contains("html") || ext in setOf("xhtml", "html", "htm", "xml") -> hojas.add(Hoja(ruta, false))
                }
            }
            if (hojas.isEmpty()) throw NoSeLee("El libro no tiene capítulos que leer")
            if (protegido(hojas)) throw NoSeLee("Este libro está protegido (DRM) y no se puede leer aquí")
            hojas.forEachIndexed { i, h -> if (h.ruta !in capitulos) capitulos[h.ruta] = i + 1 }

            val metadatos = opf.hijo("metadata")
            val titulo = metadatos?.todos("title")?.map { it.textContent.orEmpty().trim() }?.firstOrNull { it.isNotEmpty() }
                ?: tituloDeFuera
            val autor = metadatos?.todos("creator").orEmpty()
                .map { it.textContent.orEmpty().trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            val idioma = metadatos?.todos("language")?.firstOrNull()?.textContent.orEmpty().trim()
                .takeIf { Regex("[A-Za-z0-9-]{2,20}").matches(it) } ?: "es"

            // Todo lo que podía fallar ya ha fallado: ahora sí se toca el disco.
            carpeta.deleteRecursively()
            carpeta.mkdirs()
            sacarImagenes()

            val sale = StringBuilder()
            sale.append("<!doctype html>\n<html lang=\"").append(idioma).append("\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(escapar(titulo)).append("</title><style>").append(ESTILO)
                .append("</style></head><body><main>\n<header class=\"libro\"><h1>").append(escapar(titulo)).append("</h1>")
            if (autor.isNotEmpty()) sale.append("<p>").append(escapar(autor)).append("</p>")
            sale.append("</header>\n")
            hojas.forEachIndexed { i, hoja ->
                val n = i + 1
                val dentro = if (hoja.esImagen) "<p><img alt=\"\" src=\"" + atributo(codificar(hoja.ruta)) + "\"></p>"
                else capitulo(hoja.ruta, n)
                // Un capítulo en blanco (los hay: hojas de cortesía) no gasta un separador ni una página al imprimir.
                if (dentro.isNotBlank()) {
                    sale.append("<section class=\"capitulo\" id=\"cap-").append(n).append("\">\n")
                        .append(dentro).append("\n</section>\n")
                }
            }
            sale.append("</main></body></html>\n")
            return File(carpeta, "pagina.html").apply { writeText(sale.toString(), Charsets.UTF_8) }
        }

        // ---- el ZIP ---------------------------------------------------------------------------

        /** Los bytes de una entrada, o nada si no está o si pasa del tope (el tamaño que declara el ZIP puede mentir: se cuenta). */
        private fun bytes(ruta: String): ByteArray? {
            val e = zip.getEntry(ruta) ?: return null
            if (e.isDirectory || e.size > TOPE_DE_RECURSO) return null
            return runCatching {
                zip.getInputStream(e).use { s ->
                    val sale = java.io.ByteArrayOutputStream()
                    val tramo = ByteArray(64 * 1024)
                    while (sale.size() <= TOPE_DE_RECURSO) {
                        val leidos = s.read(tramo)
                        if (leidos < 0) break
                        sale.write(tramo, 0, leidos)
                    }
                    if (sale.size() > TOPE_DE_RECURSO) null else sale.toByteArray()
                }
            }.getOrNull()
        }

        /**
         * El contenedor y el índice, por el DOM. Un `.opf` antiguo puede traer su `DOCTYPE`: se le
         * quita antes, que es más limpio que abrirle la puerta al analizador. Y si el libro usa
         * `dc:` sin declararlo (los hay), se reintenta sin espacios de nombres.
         */
        private fun xml(ruta: String): Element? {
            val texto = SIN_DOCTYPE.replace(texto(bytes(ruta) ?: return null), "")
            for (conEspacios in listOf(true, false)) {
                val fabrica = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = conEspacios
                    // Nada de entidades externas: un archivo que llega por el chat no manda en el disco.
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                    isExpandEntityReferences = false
                }
                val raiz = runCatching {
                    fabrica.newDocumentBuilder().parse(InputSource(StringReader(texto))).documentElement
                }.getOrNull()
                if (raiz != null) return raiz
            }
            return null
        }

        /**
         * Con DRM, `META-INF/encryption.xml` lista lo cifrado. Que liste **tipografías** es lo
         * normal y no estorba (es ofuscación, y las fuentes no se usan); que liste los capítulos
         * es que el libro no se puede leer.
         */
        private fun protegido(hojas: List<Hoja>): Boolean {
            val cifrado = texto(bytes("META-INF/encryption.xml") ?: return false)
            val cifradas = CIFRADA.findAll(cifrado)
                .mapNotNull { resolver("", it.groupValues[1].replace("&amp;", "&")) }.toSet()
            return hojas.any { it.ruta in cifradas }
        }

        /**
         * Las imágenes del ZIP a la carpeta, con la misma ruta. Un nombre de entrada es texto que
         * pone quien hizo el archivo: «../../fuera.png» escribiría donde no debe, y por eso solo
         * se escribe lo que, resuelto de verdad, sigue cayendo dentro de la carpeta.
         */
        private fun sacarImagenes() {
            val raiz = carpeta.canonicalFile
            val entradas = zip.entries()
            while (entradas.hasMoreElements()) {
                val e: ZipEntry = entradas.nextElement()
                if (e.isDirectory || e.size > TOPE_DE_RECURSO) continue
                if (e.name.substringAfterLast('.', "").lowercase() !in IMAGENES) continue
                val destino = runCatching { File(raiz, e.name).canonicalFile }.getOrNull() ?: continue
                if (!destino.path.startsWith(raiz.path + File.separator)) continue
                val b = bytes(e.name) ?: continue
                runCatching { destino.parentFile?.mkdirs(); destino.writeBytes(b) }
            }
        }

        // ---- un capítulo ----------------------------------------------------------------------

        private fun capitulo(ruta: String, n: Int): String {
            val texto = texto(bytes(ruta) ?: return "")
            // Lo de dentro del <body>. Si no lo hay (pasa en libros hechos a mano), lo que siga a la cabecera.
            val abre = CUERPO.find(texto)
            var dentro = if (abre != null) {
                val fin = texto.lastIndexOf("</body", ignoreCase = true)
                texto.substring(abre.range.last + 1, if (fin > abre.range.last) fin else texto.length)
            } else {
                val cabeza = texto.lastIndexOf("</head>", ignoreCase = true)
                SIN_DOCTYPE.replace(if (cabeza >= 0) texto.substring(cabeza + 7) else texto, "")
                    .replace(Regex("<\\?xml[^>]*\\?>"), "")
            }
            // Comentarios y bloques que no se quieren, hasta que no quede ninguno: quitar uno puede
            // juntar los dos trozos de otro («<scr<script></script>ipt>»).
            for (vuelta in 0 until 5) {
                val antes = dentro
                dentro = COMENTARIO.replace(dentro, "")
                dentro = BLOQUE_SUELTO.replace(dentro, "")
                dentro = BLOQUE.replace(dentro, "")
                if (dentro == antes) break
            }
            val dir = ruta.substringBeforeLast('/', "")
            return ETIQUETA.replace(dentro) { etiqueta(it, dir, n) }.trim()
        }

        /**
         * Una etiqueta de apertura, rehecha atributo por atributo. Aquí se decide todo lo que
         * cambia: fuera los `on…` y el `style`, las rutas a la raíz de la carpeta, y los `id` con
         * el capítulo delante —«c3-nota1»— porque al coser los capítulos en una sola página los
         * identificadores se repiten (todos los capítulos tienen su «nota1») y los enlaces se
         * irían al primero.
         */
        private fun etiqueta(m: MatchResult, dir: String, n: Int): String {
            val nombre = m.groupValues[1]
            val minus = nombre.lowercase()
            if (minus in PROHIBIDAS) return ""
            val resto = m.groupValues[2]
            val b = StringBuilder("<").append(nombre)
            for (a in ATRIBUTO.findAll(resto)) {
                val clave = a.groupValues[1]
                val k = clave.lowercase()
                if (k.startsWith("on") || k in ATRIBUTOS_FUERA) continue
                val v = (a.groups[2] ?: a.groups[3] ?: a.groups[4])?.value
                if (v == null) { b.append(' ').append(clave); continue }
                val nuevo = when {
                    k == "src" || k == "poster" -> atributo(recurso(v, dir))
                    k == "href" || k == "xlink:href" ->
                        atributo(if (minus == "image" || minus == "img") recurso(v, dir) else enlace(v, dir, n))
                    k == "id" || (k == "name" && minus == "a") -> "c$n-$v"
                    // Los degradados y recortes de un SVG se piden por su id: hay que seguirlo.
                    v.contains("url(#") -> v.replace("url(#", "url(#c$n-")
                    else -> v
                }
                b.append(' ').append(clave).append("=\"").append(nuevo.replace("\"", "&quot;")).append('"')
            }
            // `<a id="p12"/>` es XHTML correcto y HTML desastroso: el navegador no cree en la barra
            // y deja el enlace abierto hasta el final del libro. Se cierra a mano.
            val cerrada = resto.trimEnd().endsWith("/")
            return if (cerrada && minus !in VACIAS) b.append("></").append(nombre).append('>').toString()
            else b.append('>').toString()
        }

        /** El `src` de una imagen, colgando de la raíz de la carpeta. Vacío si no es una imagen del libro. */
        private fun recurso(valor: String, dir: String): String {
            val u = valor.trim().replace("&amp;", "&")
            if (u.startsWith("data:", ignoreCase = true)) return if (u.startsWith("data:image/", ignoreCase = true)) u else ""
            if (u.startsWith("//")) return ""
            if (ESQUEMA.containsMatchIn(u)) return if (u.substringBefore(':').lowercase() in setOf("http", "https")) u else ""
            val ruta = resolver(dir, u.substringBefore('#').substringBefore('?')) ?: return ""
            if (ruta.substringAfterLast('.', "").lowercase() !in IMAGENES) return ""
            return codificar(ruta)
        }

        /** Adónde lleva un enlace ahora que todo el libro es una página: a otro capítulo, a un ancla, o fuera. */
        private fun enlace(valor: String, dir: String, n: Int): String {
            val u = valor.trim().replace("&amp;", "&")
            if (u.startsWith("#")) return if (u.length == 1) "#" else "#c$n-" + u.drop(1)
            if (u.startsWith("//")) return "#"
            if (ESQUEMA.containsMatchIn(u)) {
                return if (u.substringBefore(':').lowercase() in setOf("http", "https", "mailto", "tel")) u else "#"
            }
            val ancla = u.substringAfter('#', "")
            val ruta = resolver(dir, u.substringBefore('#').substringBefore('?')) ?: return "#"
            val cap = capitulos[ruta]
            return when {
                cap != null -> if (ancla.isEmpty()) "#cap-$cap" else "#c$cap-$ancla"
                ruta.substringAfterLast('.', "").lowercase() in IMAGENES -> codificar(ruta)
                else -> "#"
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    private val CODIFICACION = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']")
    private val SIN_DOCTYPE = Regex("<!DOCTYPE[^>\\[]*(?:\\[[\\s\\S]*?\\]\\s*)?>", RegexOption.IGNORE_CASE)
    private val CUERPO = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val COMENTARIO = Regex("<!--[\\s\\S]*?-->")

    // Lo de dentro de una etiqueta, a trozos y sin volver atrás (`++`, `*+`): un `src` con una
    // imagen en base64 mide cientos de miles de letras, y letra a letra el motor de expresiones
    // de Java revienta la pila.
    private const val ATRIBUTOS = "(?:[^>\"']++|\"[^\"]*+\"|'[^']*+')*+"
    private const val BLOQUES = "script|style|iframe|object|embed|noscript|applet"

    /** `<script src="…"/>`: cerrado en sí mismo, que en XHTML vale. Va antes que [BLOQUE] para que ese no se coma el capítulo buscando su cierre. */
    private val BLOQUE_SUELTO = Regex("<(?:$BLOQUES)\\b$ATRIBUTOS(?<=/)>", RegexOption.IGNORE_CASE)
    private val BLOQUE = Regex("<($BLOQUES)\\b[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val ETIQUETA = Regex("<([a-zA-Z][^\\s/>\"']*)($ATRIBUTOS)>")
    private val ATRIBUTO = Regex("([^\\s=/<>\"']+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?")
    private val ESQUEMA = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
    private val PORCIENTOS = Regex("(?:%[0-9A-Fa-f]{2})+")
    private val CIFRADA = Regex("CipherReference\\b[^>]*?\\bURI\\s*=\\s*[\"']([^\"']+)[\"']")

    /** Si el bloque no cerraba, [BLOQUE] no lo vio: al menos su etiqueta no pasa. Y las de la cabecera, que aquí no pintan nada. */
    private val PROHIBIDAS = setOf("script", "style", "iframe", "object", "embed", "noscript", "applet", "link", "meta", "base", "frame", "frameset")
    private val ATRIBUTOS_FUERA = setOf("style", "srcset", "srcdoc", "formaction", "action", "background")
    private val VACIAS = setOf("area", "br", "col", "hr", "img", "input", "param", "source", "track", "wbr")

    /** Los bytes como texto: UTF-8 si nadie dice otra cosa; lo que diga la marca de orden o la declaración `<?xml encoding=…?>` si lo dicen. */
    private fun texto(b: ByteArray): String {
        val juego = when {
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            else -> CODIFICACION.find(String(b, 0, minOf(b.size, 200), Charsets.ISO_8859_1))
                ?.let { runCatching { Charset.forName(it.groupValues[1]) }.getOrNull() } ?: Charsets.UTF_8
        }
        return String(b, juego).removePrefix("\uFEFF")
    }

    /**
     * [relativa] vista desde [dir], las dos dentro del ZIP, con los `.` y `..` resueltos. `null`
     * si se sale por arriba: esa ruta no es de este libro.
     */
    private fun resolver(dir: String, relativa: String): String? {
        val limpia = descodificar(relativa.trim()).replace('\\', '/')
        if (limpia.isEmpty()) return null
        val pila = ArrayList<String>()
        if (!limpia.startsWith("/")) dir.split('/').filterTo(pila) { it.isNotEmpty() }
        for (trozo in limpia.split('/')) when (trozo) {
            "", "." -> Unit
            ".." -> if (pila.isEmpty()) return null else pila.removeAt(pila.size - 1)
            else -> pila.add(trozo)
        }
        return if (pila.isEmpty()) null else pila.joinToString("/")
    }

    /** «Cap%C3%ADtulo%201.xhtml» → «Capítulo 1.xhtml». No es `URLDecoder`: ese convierte los `+` en espacios. */
    private fun descodificar(s: String): String {
        if (!s.contains('%')) return s
        return PORCIENTOS.replace(s) { m ->
            val pares = m.value.split('%').filter { it.isNotEmpty() }
            String(ByteArray(pares.size) { pares[it].toInt(16).toByte() }, Charsets.UTF_8)
        }
    }

    /** Una ruta del disco puesta como dirección: lo que en una URL significa otra cosa, con su `%`. */
    private fun codificar(ruta: String): String {
        val b = StringBuilder(ruta.length + 8)
        for (c in ruta) when (c) {
            ' ', '"', '\'', '<', '>', '#', '?', '%', '&' -> b.append('%').append(c.code.toString(16).uppercase().padStart(2, '0'))
            else -> b.append(c)
        }
        return b.toString()
    }

    /** Un valor que se ha compuesto aquí, listo para ir entre comillas. */
    private fun atributo(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")

    /** El nombre sin prefijo, con espacios de nombres o sin ellos. */
    private fun Element.nombre(): String = localName ?: nodeName.substringAfterLast(':')

    private fun Element.hijos(): List<Element> {
        val lista = ArrayList<Element>()
        var n = firstChild
        while (n != null) { if (n is Element) lista.add(n); n = n.nextSibling }
        return lista
    }

    private fun Element.hijo(nombre: String): Element? = hijos().firstOrNull { it.nombre() == nombre }

    /** Todos los descendientes que se llamen así, en el orden del documento (los OPF antiguos anidan los metadatos en `dc-metadata`). */
    private fun Element.todos(nombre: String): List<Element> {
        val lista = ArrayList<Element>()
        fun baja(e: Element) { for (h in e.hijos()) { if (h.nombre() == nombre) lista.add(h); baja(h) } }
        baja(this)
        return lista
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

    /**
     * Pensado para leer en un teléfono: una columna, letra con remate, y los colores del modo
     * noche si toca. Lo de imprimir va **al final** para que mande sobre el modo noche: en papel,
     * negro sobre blanco, A4, y cada capítulo en página nueva (la cabecera se queda sola, de portada).
     */
    private const val ESTILO =
        "html{-webkit-text-size-adjust:100%}" +
            "body{margin:0;background:#fbf9f4;color:#1b1b1f;font:17px/1.6 Georgia,'Noto Serif','Times New Roman',serif}" +
            "main{max-width:42em;margin:0 auto;padding:18px 18px 64px;overflow-wrap:break-word}" +
            "header.libro{text-align:center;margin:2em 0 1em}" +
            "header.libro h1{font-size:1.9em;line-height:1.2;margin:0 0 .35em}" +
            "header.libro p{margin:0;color:#6b6b73;font-style:italic}" +
            "section.capitulo{border-top:1px solid #d5d2ca;margin-top:2.5em;padding-top:2em}" +
            "h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.3em 0 .6em}" +
            "h1{font-size:1.6em}h2{font-size:1.35em}h3{font-size:1.15em}h4,h5,h6{font-size:1em}" +
            "p{margin:.7em 0}blockquote{margin:1em 1.4em}pre{white-space:pre-wrap}" +
            "img,svg,video{max-width:100%;height:auto}" +
            "table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #b9b9c2;padding:4px 8px;vertical-align:top}" +
            "a{color:#2a5bd7}hr{border:0;border-top:1px solid #d5d2ca;margin:1.5em 0}" +
            "@media (prefers-color-scheme: dark){body{background:#121316;color:#e4e2e6}" +
            "header.libro p{color:#9a9aa2}section.capitulo,hr{border-color:#3a3a42}" +
            "td,th{border-color:#4a4a52}a{color:#9db7ff}}" +
            "@media print{@page{size:A4;margin:20mm}" +
            "body{background:#fff;color:#000;font-size:11pt}main{max-width:none;padding:0}" +
            "header.libro{margin-top:30%}header.libro p{color:#333}a{color:inherit;text-decoration:none}" +
            "section.capitulo{break-before:page;page-break-before:always;border-top:0;margin-top:0;padding-top:0}" +
            "h1,h2,h3,h4,h5,h6{break-after:avoid;page-break-after:avoid}img,svg,table{break-inside:avoid;page-break-inside:avoid}}"
}
