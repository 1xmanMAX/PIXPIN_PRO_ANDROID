package com.forge.pixpin.motor

/**
 * **Un Word o un EPUB anotado, hecho página web** (20-sep-2026).
 *
 * Lo pidió el usuario: exportar el documento **como se ve al abrirlo** —el texto en medio, lo
 * anotado a los lados, los marcadores en su sitio— en un solo HTML ligero, y que al tocar un
 * marcador lleve a su zona. La página ya es HTML (la que fabrican [DocxAHtml] y [EpubAHtml], con
 * la letra y los márgenes de [Lectura.estilo]); aquí se le pone **encima** lo anotado, como SVG
 * —vectorial: pesa poco y no se emborrona al ampliar—, y un guion mínimo.
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

    fun pagina(
        html: String, columna: Int?, tops: List<Double>, piezas: List<Pieza>, senales: List<Senal>, tamano: Int = 100
    ): String {
        val margen = columna?.let { Lectura.margenDe(it) } ?: 0
        val estilo = buildString {
            append("<style id=\"pixpin-anotado\">")
            append("html{position:relative !important;margin-left:auto !important;margin-right:auto !important;")
            append("-webkit-text-size-adjust:$tamano% !important;text-size-adjust:$tamano% !important}")
            append("body{position:static !important}")
            append(".ppa{position:absolute;pointer-events:none;overflow:visible;z-index:5}")
            append(".ppm{position:absolute;z-index:6;font-size:20px;line-height:1;transform:translateY(-4px)}")
            append("#pprail{position:fixed;right:6px;top:50%;transform:translateY(-50%);z-index:9;display:flex;flex-direction:column;gap:6px;")
            append("padding:6px 4px;border-radius:16px;background:rgba(127,127,127,.22);-webkit-backdrop-filter:blur(6px);backdrop-filter:blur(6px)}")
            append("#pprail button{all:unset;cursor:pointer;font-size:18px;line-height:1;padding:3px;text-align:center}")
            append("@media print{#pprail{display:none}}")
            append("</style>")
        }
        val capa = buildString {
            for (p in piezas) {
                val cuerpo = sinCabecera(p.svg).replaceFirst(
                    "<svg ",
                    "<svg class=\"ppa\" data-i=\"${p.ancla}\" data-y=\"${num(p.y)}\" style=\"left:${num(p.x)}px;top:${num(p.y)}px;width:${num(p.ancho)}px;height:${num(p.alto)}px\" "
                )
                append(cuerpo)
            }
            // El marcador, **donde se puso**: en el canto derecho de la columna de texto.
            val xDeLaSenal = if (columna != null) margen + columna + 6 else 4
            senales.forEachIndexed { k, s ->
                append("<span class=\"ppm\" id=\"ppm$k\" data-i=\"${s.ancla}\" data-y=\"${num(s.y)}\"${if (s.ancla < 0 && s.fraccion >= 0) " data-f=\"${s.fraccion}\"" else ""} style=\"${if (columna != null) "left:${xDeLaSenal}px" else "right:${xDeLaSenal}px"};top:${num(s.y)}px\">${s.emoji}</span>")
            }
            if (senales.isNotEmpty()) {
                append("<div id=\"pprail\">")
                senales.forEachIndexed { k, s -> append("<button data-m=\"ppm$k\" title=\"Ir al marcador\">${s.emoji}</button>") }
                append("</div>")
            }
        }
        val guion = "<script>(function(){var S='$SELECTOR',T=[${tops.joinToString(",") { num(it) }}];" +
            "function poner(){var q=document.body.querySelectorAll(S),n=[];for(var i=0;i<q.length;i++)n.push(q[i].getBoundingClientRect().top+window.scrollY);" +
            "var e=document.querySelectorAll('[data-y]');for(var k=0;k<e.length;k++){var i=+e[k].getAttribute('data-i'),y=+e[k].getAttribute('data-y');" +
            "var f=e[k].getAttribute('data-f');if(f!==null&&i<0)y=(+f)*document.documentElement.scrollHeight;var d=(i>=0&&i<n.length&&i<T.length)?n[i]-T[i]:0;e[k].style.top=(y+d)+'px';e[k]._y=y+d}}" +
            "function ir(id){var m=document.getElementById(id);if(m)window.scrollTo({left:window.scrollX,top:Math.max(0,(m._y||0)-24),behavior:'smooth'})}" +
            "var b=document.querySelectorAll('#pprail button');for(var k=0;k<b.length;k++)(function(x){x.onclick=function(){ir(x.getAttribute('data-m'))}})(b[k]);" +
            "poner();window.addEventListener('load',function(){poner();" +
            (if (margen > 0) "if(document.documentElement.scrollWidth>window.innerWidth+2)window.scrollTo($margen,0);" else "") +
            "});window.addEventListener('resize',poner);if(document.fonts&&document.fonts.ready)document.fonts.ready.then(poner)})()</script>"

        // La ventana del móvil, **del ancho de la columna**: se abre viendo el texto de borde a
        // borde, como en la aplicación, y lo anotado queda a los lados, a un gesto.
        val ventana = "<meta name=\"viewport\" content=\"width=${columna ?: "device-width"}\">"
        var salida = html.replace(Regex("<meta[^>]*name=[\"']viewport[\"'][^>]*>", RegexOption.IGNORE_CASE), "")
        val cabeza = salida.indexOf("</head>", ignoreCase = true)
        salida = if (cabeza < 0) ventana + estilo + salida else salida.substring(0, cabeza) + ventana + estilo + salida.substring(cabeza)
        val pie = salida.lastIndexOf("</body>", ignoreCase = true)
        return if (pie < 0) salida + capa + guion else salida.substring(0, pie) + capa + guion + salida.substring(pie)
    }

    private fun num(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == Math.rint(r)) r.toLong().toString() else r.toString()
    }
}
