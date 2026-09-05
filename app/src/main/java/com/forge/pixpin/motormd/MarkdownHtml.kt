package com.forge.pixpin.motormd

/**
 * **Una nota, escrita como HTML** para que viaje dentro del documento web exportado.
 *
 * En la aplicación una nota se pinta sobre un lienzo de Android ([MarkdownText]); eso no
 * sirve para un archivo que se abre en un navegador, así que aquí se traduce a las etiquetas
 * que el navegador ya sabe componer: **él pagina, ajusta el ancho y busca dentro del texto**,
 * cosas que un dibujo no puede hacer.
 *
 * No es un exportador de Markdown general: cubre lo que el editor de la aplicación escribe
 * —títulos, párrafos, listas, tareas, citas, código, reglas, tablas, fórmulas, medios y
 * cajas— y lo demás cae a párrafo antes que perderse.
 *
 * Lógica pura: entra el texto y sale texto. Se comprueba sin dispositivo.
 */
object MarkdownHtml {

    /** La nota entera como HTML, ya escapado y listo para meter en la página. */
    fun deTexto(texto: String, imagen: (String) -> String? = { null }): String =
        deBloques(Markdown.parse(texto), imagen)

    /**
     * [imagen] devuelve, para la ruta de una imagen de la nota, un `data:` con ella ya
     * pequeña —o null si no se puede—. Sin él, de una imagen solo se dice que la había.
     */
    fun deBloques(bloques: List<MarkdownBlock>, imagen: (String) -> String? = { null }): String {
        val sb = StringBuilder(1024)
        var i = 0
        while (i < bloques.size) {
            val b = bloques[i]
            // Las listas se juntan en una sola: un `<li>` suelto por línea deja al navegador
            // abriendo y cerrando listas, y las viñetas salen descolgadas.
            when (b) {
                is MarkdownBlock.Bullet -> {
                    sb.append("<ul>")
                    while (i < bloques.size && bloques[i] is MarkdownBlock.Bullet) {
                        sb.append("<li>").append(enLinea((bloques[i] as MarkdownBlock.Bullet).content)).append("</li>")
                        i++
                    }
                    sb.append("</ul>")
                    continue
                }
                is MarkdownBlock.Numbered -> {
                    sb.append("<ol start=\"").append(b.number.coerceAtLeast(1)).append("\">")
                    while (i < bloques.size && bloques[i] is MarkdownBlock.Numbered) {
                        sb.append("<li>").append(enLinea((bloques[i] as MarkdownBlock.Numbered).content)).append("</li>")
                        i++
                    }
                    sb.append("</ol>")
                    continue
                }
                is MarkdownBlock.Tarea -> {
                    sb.append("<ul class=\"tareas\">")
                    while (i < bloques.size && bloques[i] is MarkdownBlock.Tarea) {
                        val t = bloques[i] as MarkdownBlock.Tarea
                        sb.append("<li><input type=\"checkbox\" disabled")
                        if (t.hecha) sb.append(" checked")
                        sb.append("/> ").append(enLinea(t.content)).append("</li>")
                        i++
                    }
                    sb.append("</ul>")
                    continue
                }
                else -> sb.append(bloque(b, imagen))
            }
            i++
        }
        return sb.toString()
    }

    private fun bloque(b: MarkdownBlock, imagen: (String) -> String?): String = when (b) {
        is MarkdownBlock.Heading -> {
            val n = b.level.coerceIn(1, 6)
            "<h$n>${enLinea(b.content)}</h$n>"
        }
        is MarkdownBlock.Paragraph -> "<p>${conSalto(enLinea(b.content))}</p>"
        is MarkdownBlock.Quote -> "<blockquote>${enLinea(b.content)}</blockquote>"
        is MarkdownBlock.Code -> {
            val clase = if (b.lenguaje.isBlank()) "" else " class=\"len-${escapar(b.lenguaje)}\""
            "<pre$clase><code>${escapar(b.text)}</code></pre>"
        }
        MarkdownBlock.Rule -> "<hr/>"
        // La fórmula se queda como está escrita: sin un compositor de fórmulas dentro de la
        // página, enseñar el LaTeX es más honrado que enseñar un hueco.
        is MarkdownBlock.Formula -> "<p class=\"formula\"><code>${escapar(b.latex)}</code></p>"
        is MarkdownBlock.Medio -> medio(b, imagen)
        is MarkdownBlock.Caja -> caja(b, imagen)
        is MarkdownBlock.Tabla -> tabla(b)
        is MarkdownBlock.Bullet -> "<ul><li>${enLinea(b.content)}</li></ul>"
        is MarkdownBlock.Numbered -> "<ol><li>${enLinea(b.content)}</li></ol>"
        is MarkdownBlock.Tarea -> "<ul class=\"tareas\"><li>${enLinea(b.content)}</li></ul>"
        else -> ""
    }

    /**
     * Un medio del que **no viaja el archivo**: la nota lleva una ruta del teléfono, y en el
     * ordenador de quien la reciba esa ruta no existe. Se enseña de qué era, que es lo único
     * cierto que se puede decir.
     */
    private val RELLENOS = setOf("audio", "imagen", "image", "video", "vídeo", "archivo", "file", "foto")

    /**
     * Un párrafo que empieza por su minuto —`[1:23] …`, como los de una transcripción—
     * lleva el minuto como **salto**: en la página, tocarlo lleva el audio de la nota a ese
     * punto (ver el visor en `ExportarHtml`).
     */
    private val MARCA_DE_TIEMPO = Regex("""^\[(?:(\d+):)?(\d+):(\d\d)\]\s*""")
    private fun conSalto(html: String): String {
        val m = MARCA_DE_TIEMPO.find(html) ?: return html
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val ms = ((h * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()) * 1000)
        val etiqueta = m.value.trim().removePrefix("[").removeSuffix("]")
        return "<a class=\"salto\" data-ms=\"$ms\" href=\"#\">$etiqueta</a> " + html.substring(m.range.last + 1)
    }

    private fun medio(b: MarkdownBlock.Medio, imagen: (String) -> String?): String {
        // **Una imagen viaja de verdad**, pequeña y comprimida, si quien exporta sabe leerla
        // del teléfono (ver `ExportarProyectoWeb`). Es lo que pidió el usuario: en la nota
        // exportada solo salía «Imagen: foto.jpg».
        if (b.clase == ClaseDeMedio.IMAGEN) {
            imagen(b.ruta)?.let { datos ->
                return "<figure><img src=\"$datos\" alt=\"${escapar(b.alt)}\" loading=\"lazy\"/>" +
                    (if (b.alt.isNotBlank()) "<figcaption>${escapar(b.alt)}</figcaption>" else "") +
                    "</figure>"
            }
        }
        // **Y un audio también**, como reproductor: una nota de voz transcrita lleva su
        // audio, y en la página exportada tiene que poder escucharse al lado del texto.
        // El mismo [imagen] devuelve el `data:` del archivo si sabe (ver `ExportarProyectoWeb`).
        if (b.clase == ClaseDeMedio.AUDIO) {
            imagen(b.ruta)?.let { datos ->
                return "<figure><audio controls preload=\"metadata\" src=\"$datos\"></audio>" +
                    (if (b.alt.isNotBlank() && b.alt != "audio") "<figcaption>${escapar(b.alt)}</figcaption>" else "") +
                    "</figure>"
            }
        }
        // **Y un archivo, para bajarlo**: el PDF que la nota lleva al lado viaja dentro de
        // la página, como enlace de descarga con su nombre.
        if (b.clase == ClaseDeMedio.ARCHIVO) {
            imagen(b.ruta)?.let { datos ->
                val nombre = b.alt.takeIf { it.isNotBlank() && it.lowercase() !in RELLENOS } ?: b.ruta.substringAfterLast('/')
                return "<p class=\"medio\"><a class=\"adjunto\" download=\"${escapar(nombre)}\" href=\"$datos\">&#128206; ${escapar(nombre)}</a></p>"
            }
        }
        val que = when (b.clase) {
            ClaseDeMedio.IMAGEN -> "Imagen"
            ClaseDeMedio.VIDEO -> "Vídeo"
            ClaseDeMedio.AUDIO -> "Audio"
            ClaseDeMedio.ARCHIVO -> "Archivo"
        }
        // Un «audio» o «imagen» de relleno no es un nombre: se dice el del archivo.
        val nombre = b.alt.takeIf { it.isNotBlank() && it.lowercase() !in RELLENOS } ?: b.ruta.substringAfterLast('/')
        return "<p class=\"medio\">$que: ${escapar(nombre)}</p>"
    }

    private fun caja(b: MarkdownBlock.Caja, imagen: (String) -> String?): String {
        val dentro = deBloques(b.dentro, imagen)
        return when (b.tipo) {
            TipoDeCaja.PLEGABLE ->
                "<details><summary>${escapar(b.titulo)}</summary>$dentro</details>"
            TipoDeCaja.PIE -> "<div class=\"pie\">$dentro</div>"
            TipoDeCaja.DESTACADO -> "<div class=\"destacado\">$dentro</div>"
            TipoDeCaja.CENTRO -> "<div class=\"centro\">$dentro</div>"
            TipoDeCaja.DERECHA -> "<div class=\"derecha\">$dentro</div>"
        }
    }

    private fun tabla(t: MarkdownBlock.Tabla): String = buildString {
        append("<table>")
        if (t.titulo.text.isNotBlank()) append("<caption>").append(enLinea(t.titulo)).append("</caption>")
        for (fila in t.filas) {
            append("<tr>")
            for (c in fila) {
                val et = if (c.cabecera) "th" else "td"
                append('<').append(et)
                if (c.anchoEnColumnas > 1) append(" colspan=\"").append(c.anchoEnColumnas).append('"')
                if (c.altoEnFilas > 1) append(" rowspan=\"").append(c.altoEnFilas).append('"')
                when (c.alineacion) {
                    Alineacion.CENTRO -> append(" style=\"text-align:center\"")
                    Alineacion.DERECHA -> append(" style=\"text-align:right\"")
                    Alineacion.IZQUIERDA -> {}
                }
                append('>').append(enLinea(c.contenido)).append("</").append(et).append('>')
            }
            append("</tr>")
        }
        append("</table>")
    }

    /**
     * El texto de una línea con sus estilos. Los tramos vienen ya aplanados —sin solapes—
     * por [tramosDe], así que cada uno abre y cierra sus etiquetas sin anidar mal.
     */
    internal fun enLinea(t: InlineText): String {
        val tramos = t.tramos()
        if (tramos.isEmpty()) return escapar(t.text)
        val sb = StringBuilder(t.text.length + 32)
        var pos = 0
        for (tr in tramos) {
            if (tr.inicio > pos) sb.append(escapar(t.text.substring(pos, tr.inicio)))
            val trozo = escapar(t.text.substring(tr.inicio, tr.fin))
            val abre = StringBuilder()
            val cierra = StringBuilder()
            if (tr.tiene(SpanKind.LINK)) {
                // Sin `href` no hay enlace: un `<a>` vacío se ve azul y no lleva a ninguna
                // parte, que es peor que no marcarlo.
                val url = tr.url
                if (url != null && url.isNotBlank()) {
                    abre.append("<a href=\"").append(escapar(url))
                        .append("\" target=\"_blank\" rel=\"noopener\">")
                    cierra.insert(0, "</a>")
                }
            }
            if (tr.tiene(SpanKind.BOLD)) { abre.append("<strong>"); cierra.insert(0, "</strong>") }
            if (tr.tiene(SpanKind.ITALIC)) { abre.append("<em>"); cierra.insert(0, "</em>") }
            if (tr.tiene(SpanKind.STRIKE)) { abre.append("<s>"); cierra.insert(0, "</s>") }
            if (tr.tiene(SpanKind.CODE)) { abre.append("<code>"); cierra.insert(0, "</code>") }
            // Lo tapado se tapa de verdad: se destapa al pasar por encima o al tocarlo.
            if (tr.tiene(SpanKind.SPOILER)) {
                abre.append("<span class=\"tapado\" tabindex=\"0\">"); cierra.insert(0, "</span>")
            }
            sb.append(abre).append(trozo).append(cierra)
            pos = tr.fin
        }
        if (pos < t.text.length) sb.append(escapar(t.text.substring(pos)))
        return sb.toString()
    }

    internal fun escapar(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
