package com.forge.pixpin.motor

/**
 * **Un Word o un EPUB anotado, hecho página web** (20-sep-2026).
 *
 * Lo pidió el usuario: exportar el documento **como se ve al abrirlo** —el texto en medio, lo
 * anotado a los lados, los marcadores en su sitio— en un solo HTML ligero, y que al tocar un
 * marcador lleve a su zona. La página ya es HTML (la que fabrican [DocxAHtml] y [EpubAHtml], con
 * la letra de [Lectura.estilo]); aquí se le pone **encima** lo anotado, como SVG —vectorial: pesa
 * poco y no se emborrona al ampliar—, y entra como una hoja del documento web ([hoja]).
 *
 * **Lo anotado va atado al párrafo, no al píxel.** Otro navegador tiene otras letras y el texto
 * no mide lo mismo de alto; una capa entera clavada en coordenadas se iría despegando hacia
 * abajo. Al exportar se mide a qué altura caía cada bloque del documento ([SELECTOR]); cada
 * anotación y cada marcador se apunta al bloque junto al que estaba ([anclaDe]), y al abrir la
 * página el guion vuelve a medir y corre cada uno lo que se haya corrido su bloque.
 *
 * Sin Android: es texto. Ver `VisorHtmlActivity.exportarComoPagina`.
 */
object DocumentoAnotado {
    /** Los bloques que se miden, aquí y en la página: el mismo selector en los dos lados, el mismo orden. */
    const val SELECTOR = "p,h1,h2,h3,h4,h5,h6,li,tr,img,pre,blockquote,figure,hr"

    /** El guion que mide los bloques en el visor. Devuelve `{"t":[…],"h":alto}`, en píxeles CSS. */
    val MEDIR = "(function(){var q=document.body.querySelectorAll('$SELECTOR'),r=[];" +
        "for(var i=0;i<q.length;i++){r.push(Math.round((q[i].getBoundingClientRect().top+window.scrollY)*10)/10)}" +
        "return JSON.stringify({t:r,h:document.documentElement.scrollHeight})})()"

    /** Una pieza de lo anotado: su SVG y dónde iba, en píxeles del documento. */
    class Pieza(val svg: String, val x: Double, val y: Double, val ancho: Double, val alto: Double, val ancla: Int)

    /** Un marcador ya situado: a qué altura del documento estaba y junto a qué bloque. */
    class Senal(val emoji: String, val y: Double, val ancla: Int, val fraccion: Double = -1.0)

    /**
     * El bloque al que se ata algo que estaba a la altura [y]: **el último que empieza por encima**
     * (con un poco de holgura: lo escrito al lado de un título suele empezar un pelo antes que él).
     * −1 si no hay ninguno: entonces se queda donde estaba.
     */
    fun anclaDe(y: Double, tops: List<Double>, holgura: Double = 6.0): Int {
        var mejor = -1
        for (i in tops.indices) {
            val t = tops[i]
            if (t <= y + holgura && (mejor < 0 || t >= tops[mejor])) mejor = i
        }
        return mejor
    }

    /** Lo que se anotó, **por bloques**: los elementos que comparten ancla salen en una misma pieza. */
    fun porAnclas(elementos: List<Element>, tops: List<Double>): Map<Int, List<Element>> =
        elementos.filter { !it.isDeleted }.groupBy { e ->
            val b = getElementBounds(e)
            anclaDe((b.y1 + b.y2) / 2, tops)
        }

    /** La caja de un SVG de [DrawSvg], leída de su `viewBox`: x, y, ancho, alto. */
    fun cajaDe(svg: String): DoubleArray? =
        Regex("viewBox=\"([-0-9.eE]+) ([-0-9.eE]+) ([-0-9.eE]+) ([-0-9.eE]+)\"").find(svg)
            ?.groupValues?.drop(1)?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }?.toDoubleArray()

    /** El SVG listo para ir dentro de una página: sin la cabecera XML, que ahí estorba. */
    fun sinCabecera(svg: String): String = svg.substringAfter("?>", svg).trim()

    /**
     * **El mismo documento, como hoja del documento web de siempre** (21-sep-2026). El usuario
     * pidió que lo exportado lleve «los controles del HTML»: lápiz, resaltador, borrador,
     * deshacer, guardar. Así que en vez de una página aparte con su guion mínimo, como al principio, el
     * Word o el libro entra en [ExportarHtml] como una hoja más, y los mandos le vienen dados.
     * [html] es la página entera, con su `<style>`; aquí se separa la hoja de estilo —que se
     * acota— del cuerpo, y lo anotado se pone encima ([capaDe]).
     */
    fun hoja(
        nombre: String, html: String, columna: Int, margen: Int, tops: List<Double>,
        piezas: List<Pieza>, senales: List<Senal>, tamano: Int, fondo: String, clave: String = "d",
        /** El espacio de la derecha, si no es el mismo que el de la izquierda ([margen]). */
        margenDerecho: Int = margen,
        /** El idioma del texto, para la voz del navegador. Null: se adivina por el propio texto. */
        idioma: String? = null
    ): ExportarHtml.HojaWeb.Documento {
        val estilos = Regex("<style[^>]*>(.*?)</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val css = estilos.findAll(html).joinToString("\n") { it.groupValues[1] }
        val desde = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(html)?.range?.last?.plus(1) ?: 0
        val hasta = html.lastIndexOf("</body>", ignoreCase = true).takeIf { it >= desde } ?: html.length
        val cuerpo = estilos.replace(html.substring(desde, hasta), "")
            .replace(Regex("<script\\b.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        // El cuerpo de la letra: el de la hoja de estilo del documento, por lo que se agrandó al leer.
        val base = Regex("body\\{[^}]*?font:\\s*(\\d+(?:\\.\\d+)?)px").find(css)?.groupValues?.get(1)?.toDoubleOrNull() ?: 16.0
        val capa = capaDe(piezas, senales, columna, margen, clave)
        return ExportarHtml.HojaWeb.Documento(
            nombre, acotar(css), cuerpo, capa, columna, margen, tops, base * tamano / 100.0, fondo, margenDerecho,
            // Un Word dice siempre `lang="es"` (ver [DocxAHtml]): el idioma de verdad sale del texto.
            idioma ?: VozAlta.idiomaDelTexto(cuerpo.replace(Regex("<[^>]+>"), " ").take(6000), "es")
        )
    }

    /** Lo anotado, por piezas atadas a su bloque, y los marcadores con su riel. Ver [ExportarHtml.HojaWeb.Documento]. */
    fun capaDe(piezas: List<Pieza>, senales: List<Senal>, columna: Int, margen: Int, clave: String = "d"): String = buildString {
        for (p in piezas) {
            append(
                sinCabecera(p.svg).replaceFirst(
                    "<svg ",
                    "<svg class=\"ppa\" data-i=\"${p.ancla}\" data-y=\"${num(p.y)}\" style=\"left:${num(p.x)}px;top:${num(p.y)}px;width:${num(p.ancho)}px;height:${num(p.alto)}px\" "
                )
            )
        }
        senales.forEachIndexed { k, s ->
            append("<span class=\"ppm\" id=\"ppm-$clave-$k\" data-i=\"${s.ancla}\" data-y=\"${num(s.y)}\"")
            if (s.ancla < 0 && s.fraccion >= 0) append(" data-f=\"${s.fraccion}\"")
            append(" style=\"left:${margen + columna + 6}px;top:${num(s.y)}px\">${s.emoji}</span>")
        }
        if (senales.isNotEmpty()) {
            append("<div class=\"pprail\">")
            senales.forEachIndexed { k, s -> append("<button data-m=\"ppm-$clave-$k\" title=\"Ir al marcador\">${s.emoji}</button>") }
            append("</div>")
        }
    }

    /**
     * **La hoja de estilo del documento, encerrada en `.doc`.** Dice `body{…}`, `p{…}`, `a{…}`;
     * suelta dentro del documento web le cambiaría la letra y los colores a la barra y a las
     * demás hojas. Cada selector se cuelga de `.doc`, y `html`/`body` pasan a ser `.doc` mismo.
     * Las reglas de `@media` se acotan por dentro; `@page` y compañía, que no tienen a qué
     * aplicarse, se van. Es para las hojas que escribe la aplicación, no para CSS cualquiera.
     */
    fun acotar(css: String, raiz: String = ".doc"): String {
        val sale = StringBuilder()
        var i = 0
        while (i < css.length) {
            val abre = css.indexOf('{', i)
            if (abre < 0) break
            val selector = css.substring(i, abre).trim()
            // La llave que cierra **esta** regla, contando las de dentro.
            var hondo = 1; var j = abre + 1
            while (j < css.length && hondo > 0) { if (css[j] == '{') hondo++ else if (css[j] == '}') hondo--; j++ }
            val dentro = css.substring(abre + 1, (j - 1).coerceAtLeast(abre + 1))
            when {
                selector.startsWith("@media") || selector.startsWith("@supports") ->
                    sale.append(selector).append('{').append(acotar(dentro, raiz)).append('}')
                selector.startsWith("@") -> Unit
                else -> {
                    sale.append(
                        selector.split(',').joinToString(",") { uno ->
                            val u = uno.trim()
                            val m = Regex("^(html|body)(?![\\w-])").find(u)
                            if (m != null) raiz + u.substring(m.range.last + 1) else "$raiz $u"
                        }
                    ).append('{').append(dentro).append('}')
                }
            }
            i = j
        }
        return sale.toString()
    }

    private fun num(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == Math.rint(r)) r.toLong().toString() else r.toString()
    }
}
